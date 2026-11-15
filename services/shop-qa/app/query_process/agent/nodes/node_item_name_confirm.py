"""
商品名对齐与确认节点：问答主图的第一道闸门。

为什么需要这个节点：用户的提问往往带指代或口语化商品名（"这个多少钱？"、
"HAK180 烫机"），直接拿去检索会召回错误商品的知识。本节点负责把「用户提到的东西」
对齐到知识库中真实存在的标准商品名上，只有对齐成功才允许进入检索与回答流程。

五步流水线（step_1 ~ step_5）：
1. step_1_extract_info：LLM 结合近 10 条历史会话，提取商品名列表（可多个）并把
   指代性问题改写成独立完整的问题；输出 {item_names, rewritten_query}；
2. step_2_vectorize_and_query：对每个提取出的商品名做 BGE-M3 混合检索，
   在「商品名库」中找语义最接近的标准名候选（每个名字独立检索、互不影响）；
3. step_3_align_item_names：按检索分数做三档裁决——
   >0.85 确认（高置信，直接采信）/ 0.6~0.85 候选（中置信，转反问）/ <0.6 拒绝；
4. step_4_check_confirmation：根据裁决结果分流——有确认商品名就更新 state 进入
   下游检索；只有候选就生成反问话术（用户答完再进本节点，形成澄清闭环）；
   全没有则礼貌拒绝，终止流程；
5. step_5_write_history：把本轮问答与商品名关联写回 MongoDB，
   让下一轮对话能基于正确上下文做指代消解。

同名优先裁决的含义：多个候选分数都 >0.85 时，若 LLM 提取的名字与某候选完全一致
（精确匹配），优先采信精确匹配而非最高分——字符串相等是比向量相似更强的信号。

容错设计：LLM 提取失败、Milvus 不可用、商品名检索失败等一律降级为「无确认无候选」，
走反问或拒绝分支，绝不因本节点故障导致整条问答链路崩溃。
"""
import sys
# 各依赖模块职责：
# load_prompt：加载「商品名提取+问题改写」提示词模板
# QueryGraphState：LangGraph 全局状态类型，节点间通过它传递数据
# mongo_history_utils：历史会话的读取与写入（指代消解依赖历史，结果也要落库）
# lm_utils：LLM 客户端（json_mode 抽取）；embedding_utils：商品名向量化
# milvus_utils：商品名集合的混合检索
import os
import json
# logging 导入未直接使用（日志统一走 loguru），保留是为了兼容个别历史调用点
import logging
# typing 泛型：step 函数签名用 List/Dict 做类型标注，静态检查与可读性都有收益
from typing import List, Dict, Any, Optional
# SystemMessage/HumanMessage：LangChain 的消息封装，invoke 接收消息列表而非裸字符串
from langchain_core.messages import SystemMessage, HumanMessage

from app.core.load_prompt import load_prompt
from app.query_process.agent.state import QueryGraphState
from app.utils.task_utils import add_running_task, add_done_task
from app.clients.mongo_history_utils import get_recent_messages, save_chat_message, update_message_item_names
from app.lm.lm_utils import get_llm_client, get_item_name_llm_client
from app.lm.embedding_utils import generate_embeddings
from app.clients.milvus_utils import get_milvus_client, create_hybrid_search_requests, hybrid_search
from dotenv import load_dotenv, find_dotenv
from app.core.logger import logger

# 环境变量在模块加载时生效：step_2 里会读 ITEM_NAME_COLLECTION，必须先于首次调用就绪
load_dotenv(find_dotenv())


def step_1_extract_info(query: str, history: List[Dict]) -> Dict:
    # 步骤编号 Step 1 与日志里的 "Step 3" 并存是历史遗留：日志文案暂不影响逻辑，先保持原样
    """
    利用LLM从当前问题以及历史会话中提取出主要询问的商品名称item_names（可多个，JSON列表形式）
    若商品名不够明确则返回空列表，同时根据上下文重新改写问题，保证问题独立完整
    为什么要「改写问题」：用户这轮可能只说“那它的耗电量呢？”，不带历史的话
    这句话无法独立检索；改写把指代补全成完整问题，供下游所有检索节点直接使用
    :param query: 字符串 - 用户当前原始查询问题（如："这个多少钱？"）
    :param history: 列表[字典] - 近期会话历史
    :return: 字典 - 提取结果，格式：{"item_names": [], "rewritten_query": ""}
    """
    # json_mode 强制 JSON 输出：提取结果要被程序解析，自由文本会让解析失败率飙升
    # 用独立的 item_name 客户端（千问）：结构化抽取走专用模型，计费与限流独立核算
    logger.info("Step 3: 开始提取信息 (LLM)")
    
    # 1. 初始化准备
    client = get_item_name_llm_client(json_mode=True)
    
    # 构造历史对话文本
    # 拼成 "role: text" 的平文本：LLM 对对话体的理解比对结构化 JSON 更自然，
    # 历史最多 10 条（上层 limit=10），token 开销可控
    # role 取不到时用 unknown 占位：宁可信息弱化也不让 KeyError 中断
    history_text = ""
    for msg in history:
        history_text += f"{msg.get('role', 'unknown')}: {msg.get('text', '')}\n"
    
    logger.info(f"Step 1: 历史上下文构建完成，长度: {len(history_text)} 字符")

    # 2. 加载提示词
    try:
        # 使用关键字参数传递，避免参数位置错误
        # 提示词承担双重任务：提取商品名 + 改写问题，一次 LLM 调用完成两件事，省一次往返
        prompt = load_prompt("rewritten_query_and_itemnames", history_text=history_text, query=query)
        logger.debug(f"Step 1: 提示词加载成功，Prompt长度: {len(prompt)}")
    except Exception as e:
        # 模板加载失败降级：不提取、不改写，原样返回原始 query 让流程继续
        logger.error(f"Step 1: 加载提示词失败: {e}")
        return {"item_names": [], "rewritten_query": query}

    messages = [
        # SystemMessage 定角色：抽取任务与客服语义绑定，模型输出更稳定；
        # HumanMessage 才是携带历史与问题的真正载荷
        SystemMessage(content="你是一个专业的客服助手，擅长理解用户意图和提取关键信息。"),
        HumanMessage(content=prompt)
    ]
    # 消息结构固定为「System+Human」两条：无多轮 Messages 列表，
    # 历史已拍平进 prompt 文本，避免消息嵌套带来的 token 膨胀

    try:
        logger.info("Step 1: 正在调用 LLM 进行提取...")
        # invoke 同步阻塞调用：节点是同步函数，LangGraph 会在执行器线程里跑
        response = client.invoke(messages)
        # Message 对象要取 .content 才是文本：LLM 响应的结构化封装
        content = response.content
        # debug 打原始响应：抽取错误排查时，第一时间看模型到底输出了什么
        logger.debug(f"Step 1: LLM 原始响应: {content}")

        # 清理 Markdown 代码块
        # 即便 json_mode 开启，部分模型仍习惯包一层 ```json，兼容性剥壳
        if content.startswith("```json"):
            content = content.replace("```json", "").replace("```", "")
        
        # loads 失败会抛异常进入 except 降级：不在这里捕获单次解析错误
        result = json.loads(content)
        
        # 健壮性检查
        # 缺 key 时补默认值而不是抛错：LLM 输出结构不可 100% 保证，降级优于中断
        if "item_names" not in result:
            result["item_names"] = []
        if "rewritten_query" not in result:
            result["rewritten_query"] = query
            
        logger.info(f"Step 1: 提取结果解析成功 - 商品名: {result['item_names']}, 重写问题: {result['rewritten_query']}")
        # 成功路径返回完整结构：item_names 与 rewritten_query 一定是 list/str 两个 key 都在
        return result

    except Exception as e:
        # 解析失败/超时等一律降级：item_names 置空 + 原始 query，
        # 下游 step_3/step_4 会走「无匹配」分支，流程不中断
        logger.error(f"Step 3: LLM 提取或解析失败: {e}")
        return {"item_names": [], "rewritten_query": query}


def step_2_vectorize_and_query(item_names: List[str]) -> List[Dict]:
    """
    对提取的 item_names 进行向量化并在 Milvus 中进行混合搜索
    用户的一句话，可能同时提到好几个东西。比如：“我想买 苹果手机 和 华为笔记本，再推荐个 索尼耳机”
    item_names = ["苹果手机", "华为笔记本", "索尼耳机"]
    为什么要逐个检索：多个商品名的向量混在一起会互相干扰（多中心的查询向量谁都不像），
    拆开检索每个名字都能拿到自己的最相似标准名，召回质量显著更好
    返回结构：[{extracted_name, matches:[{item_name, score}, ...]}]，
    extracted_name 保留 LLM 原始提取值：step_3 的同名优先裁决需要拿它做精确比对
    """
    logger.info(f"Step 2: 开始向量化检索，目标商品: {item_names}")
    # 结果按输入顺序收集：extracted_name 与 matches 一一对应，供 step_3 逐组裁决
    results = []
    
    client = get_milvus_client()
    # 连接检查前置：失败时提前返回，避免循环里每个名字都撞一次连接错误
    if not client:
        # 连接失败返回空 results：step_3 对空输入天然安全，最终走「无匹配」分支
        logger.error("Step 2: 无法连接到 Milvus")
        return results

    collection_name = os.environ.get("ITEM_NAME_COLLECTION")
    if not collection_name:
        # 商品名集合是独立的小集合（与知识切片集合分开）：只存标准商品名，
        # 专库专用让「名字对齐」的检索精度远高于在全文知识里搜名字
        # 集合名缺失直接返回：没有目标库就无法对齐，反问/拒绝分支会接住
        logger.error("Step 2: 环境变量中未找到 ITEM_NAME_COLLECTION")
        return results

    try:
        logger.info("Step 2: 正在生成 Embedding (Dense + Sparse)...")
        # 一次性批量向量化所有商品名：模型前向摊薄，比循环单条编码快
        # 商品名很短，BGE-M3 短文本编码质量足够（名字本身信息密度高）
        embeddings = generate_embeddings(item_names)
        logger.info(f"Step 2: 向量生成完成，开始 Milvus 搜索 (Collection: {collection_name})")

        for i, name in enumerate(item_names):
            # 逐名检索：每个名字独立一组请求，互不干扰；try 在循环内保证单名失败不拖累其他名字
            try:
                # 按下标取各自的向量：generate_embeddings 返回与输入同序的批量结果
                dense_vector = embeddings.get("dense")[i]
                sparse_vector = embeddings.get("sparse")[i]

                # 构造混合搜索请求,把"一个查询"拆成两个 AnnSearchRequest
                # 稠密管语义相似（"烫金机"≈"烫印设备"），稀疏管字面相似（型号数字）
                # limit=5：每路各召回 5 条，融合后得到 5 个候选——商品名对齐不需要太多候选
                reqs = create_hybrid_search_requests(
                    dense_vector=dense_vector,
                    sparse_vector=sparse_vector,
                    limit=5  # 每路各自召回 5 条
                )

                # 执行混合搜索
                # 权重调整为 0.8 (Dense) / 0.2 (Sparse) 以优化评分
                # 商品名匹配场景语义相似占比更高（用户口语 vs 标准名），故稠密权重较大
                search_res = hybrid_search(
                    client=client,
                    collection_name=collection_name,
                    reqs=reqs,
                    ranker_weights=(0.8, 0.2), 
                    limit=5,
                    norm_score=True,
                    # 只取 item_name 字段：商品名集合里也只有这个名字，返回字段最小化
                    # norm_score=True 归一化到 0~1：step_3 的 0.85/0.6 阈值依赖这一口径
                    output_fields=["item_name"]
                )

                # entity 并非 Milvus Hit 对象自身的直接属性，它是你使用的 get() 方法的一个逻辑封装。
                # get() 正是获取 entity 里具体字段的途径。
                # 只抽 item_name 和 score 两个字段：商品名对齐只需要「候选名 + 置信度」
                matches = []
                if search_res and len(search_res) > 0:
                    # 遍历本名字的全部命中：top5 候选都会进入 matches 供 step_3 分档
                    for hit in search_res[0]:
                        entity = hit.get("entity") or {}
                        item_name = entity.get("item_name")
                        # distance 即归一化相似度分数：0~1，越接近 1 越相似
                        score = hit.get("distance")
                        
                        if item_name:
                            # 空候选名丢弃：无名字的命中没有对齐价值
                            # matches 保持命中顺序（Milvus 已按分数排序）
                            matches.append({
                                "item_name": item_name,
                                "score": score
                            })
                            logger.debug(f"Step 2: '{name}' 匹配项: {item_name} (Score: {score:.4f})")

                results.append({
                    "extracted_name": name,  # 这个是 llm 生成的item_name
                    "matches": matches
                })
                logger.info(f"Step 2: 商品 '{name}' 检索完成，找到 {len(matches)} 个匹配项")

            except Exception as inner_e:
                # 单个商品名检索失败不影响其他名字：捕获在内层循环，
                # 补一个空 matches 让该名字走「无匹配」分支
                logger.error(f"Step 2: 处理商品 '{name}' 时出错: {inner_e}")
                results.append({"extracted_name": name, "matches": []})

    except Exception as e:
        # 外层兜底：批量向量化失败等全局错误，已收集的 results 照常返回
        logger.error(f"Step 2: 向量化或搜索过程发生全局错误: {e}")

    return results


def step_3_align_item_names(query_results: List[Dict]) -> Dict:
    """
    根据 Milvus 搜索评分，对齐商品名，生成「确认商品名」和「候选商品名」
    三档裁决规则（阈值依据）：norm_score 归一化后 0.85 以上基本是同义词/全名命中，
    0.6~0.85 是「像但不确定」（如缺型号、别称），0.6 以下大概率是错误商品——
    宁可反问用户也不能把错误商品的知识塞进答案
    """
    logger.info("Step 3: 开始对齐商品名 (Score Analysis)")
    
    confirmed_item_names = []
    # options 收集中置信候选：这些名字不能直接用，要转成反问让用户选择
    options = []

    for res in query_results:
        # 每轮处理一个「提取名 + 其候选列表」：条目之间互不影响，单条失败只影响该条
        extracted_name = res.get("extracted_name", "").strip()  # llm 生成的可能的item_name
        matches = res.get("matches", []) or []  # 匹配的item_name和他的分数
        
        # or [] 兜底：matches 可能为 None（step_2 降级路径），直接迭代会崩
        if not matches:
            # 该名字无任何候选：既不确认也不反问，直接跳过（多商品场景只影响这一个名字）
            logger.info(f"Step 3: '{extracted_name}' 无匹配结果")
            continue

        # 按分数降序
        # 排序后 high/mid 的「第一个」就是各自区间的最高分，裁决逻辑依赖此顺序
        matches.sort(key=lambda x: x.get("score", 0), reverse=True)
        
        # 打印详细评分日志辅助调试
        # 打 Top3 而非全部：候选多时日志失控，头部三个已能判断分数分布形态
        top_matches_log = ", ".join([f"{m['item_name']}({m['score']:.3f})" for m in matches[:3]])
        logger.info(f"Step 3: '{extracted_name}' Top匹配: {top_matches_log}")

        # 筛选
        # high/mid 是重叠区间：mid 包含 high（>=0.6 含 >0.85），裁决按规则 A→B→C 顺序短路
        high = [m for m in matches if m.get("score", 0) > 0.85]
        mid = [m for m in matches if m.get("score", 0) >= 0.6]

        # 规则 A: 单个高置信度
        # 只有一个 >0.85 的候选：几乎不可能错，直接确认
        if len(high) == 1:
            confirmed_name = high[0].get("item_name")
            confirmed_item_names.append(confirmed_name)
            logger.info(f"Step 3: 规则A命中 (Single High) -> 确认: {confirmed_name}")
            # 裁决完成即短路：不再检查 mid，防止同一条目既确认又进候选
            continue

        # 规则 B: 多个高置信度
        # 多个都 >0.85：不能全要（可能把相近型号都拉进来），必须裁决出唯一一个
        if len(high) > 1:
            # picked 是本条目的最终选择：先尝试同名匹配，失败再退回最高分
            picked = None
            # 优先匹配同名
            # 同名优先裁决：LLM 提取的名字与候选「字符串完全相等」是比向量分数更强的证据，
            # 因为向量相似可能把近似型号都打高分，而精确相等只有真同名才满足
            if extracted_name:
                for m in high:
                    if m.get("item_name") == extracted_name:
                        picked = m
                        logger.info(f"Step 3: 规则B命中 (Exact Match in High) -> 确认: {picked.get('item_name')}")
                        # 命中即停：只取第一个同名候选（high 区间内同名通常唯一）
                        break
            
            # 否则取最高分
            # 没有同名可依就退回分数排序：取 high 区间的第一名（matches 已降序，high[0] 即最高分）
            if not picked:
                picked = high[0]
                logger.info(f"Step 5: 规则B命中 (Highest Score) -> 确认: {picked.get('item_name')}")

            confirmed_item_names.append(picked.get("item_name"))
            # 与规则 A 一样短路：确认后不再检查候选
            continue

        # 规则 C: 无高置信度，取中置信度候选
        # 0.6~0.85 的「像但不确定」：不确认也不丢弃，收集起来转反问
        # 只取前 5 个候选：反问话术里最多展示 3 个，多存无益且 state 膨胀
        # mid 已按分数降序：前 5 个就是候选中最像的 5 个
        if len(mid) > 0:
            current_options = [m.get("item_name") for m in mid[:5]]
            # extend 而非 append：多商品场景的候选要合并到同一个反问列表里
            options.extend(current_options)
            logger.info(f"Step 3: 规则C命中 (Mid Confidence) -> 添加候选: {current_options}")
            # 进了候选就跳过：该条目本轮不产出确认结果，等用户澄清后重新走全流程
            continue
        
        logger.info(f"Step 3: 规则D命中 (Low Confidence) -> 无匹配")
        # 走到这里说明全部候选 <0.6：这个提取名判定为「库里没有」，不进确认也不进候选
        # 什么都不做即可：该条目自然被跳过，循环继续处理下一个提取名

    result = {
        # set 去重：多个提取名可能对齐到同一个标准商品名，下游不应重复处理
        # confirmed 走「直接检索」，options 走「反问澄清」，两者互斥体现在 step_4 的分支顺序
        # list(set()) 顺序不稳定：候选展示顺序由分数决定，这里的集合只是去重容器
        "confirmed_item_names": list(set(confirmed_item_names)),
        "options": list(set(options))
    }
    logger.info(f"Step 3: 对齐结果: {result}")
    # 返回「汇总结构」而非逐条结构：step_4 只关心整体有没有确认/候选
    return result


def step_4_check_confirmation(state: Dict, align_result: Dict, session_id: str, history: List[Dict], rewritten_query: str) -> Dict:
    """
    检查对齐结果，更新 State
    三分支设计（A确认/B候选反问/C拒绝）：每个分支都要保证 state 处于自洽状态——
    有 answer 就清空 item_names，有 item_names 就清空 answer，
    下游节点靠这两个字段判断「继续检索」还是「直接把 answer 返给用户」
    session_id/history 参数为预留：当前实现未用到，保留签名兼容未来扩展
    """
    logger.info("Step 4: 检查确认状态并更新 State")
    
    # 健壮性处理
    # None 防御：step_3 理论上总返回 dict，但防上游重构后传 None
    if align_result is None:
        align_result = {}

    confirmed = align_result.get("confirmed_item_names", [])
    options = align_result.get("options", [])
    # 两个列表的判定顺序即优先级：确认 > 候选 > 拒绝，前面的分支命中就 return

    # 分支 A: 有确认商品名
    # 有确认就直接放行进入检索，不打扰用户——这是最常见的快路径
    if confirmed:
        logger.info(f"Step 4: [分支A] 存在确认商品名: {confirmed}")
        
        # 更新历史消息中的 item_names
        # 给「之前没关联商品名」的历史消息补上：下一轮用户再说"这个多少钱"
        # 时，LLM 提取商品名就有据可依，指代消解的准确率依赖这些标注
        # 只挑 item_names 为空的消息：已标注过的历史不重复写
        ids_to_update = []
        for msg in history:
            # 历史消息结构由 mongo_history_utils 定义：_id 是 Mongo ObjectId，
            # 更新接口收字符串，所以这里要 str() 转换
            if not msg.get("item_names"):
                mid = msg.get("_id")
                if mid:
                    ids_to_update.append(str(mid))
        
        if ids_to_update:
            logger.info(f"Step 4: 更新 {len(ids_to_update)} 条历史消息的关联商品名")
            # 异步库操作在同步节点里直接调用：单次更新开销小，无需引入额外并发机制
            update_message_item_names(ids_to_update, confirmed)

        # 写入确认商品名与改写后问题，并清空可能残留的 answer：
        # 防止上一轮反问的 answer 残留在 state 里被下游误当作最终答案返回
        state["item_names"] = confirmed
        state["rewritten_query"] = rewritten_query
        # 有条件删除：answer 字段可能不存在（首轮对话），直接 del 会 KeyError
        if "answer" in state:
            del state["answer"]
        # 分支 A 不写 answer：下游 node_answer_output 生成的才是最终回答
        return state

    # 分支 B: 有候选商品名
    # 只有「像但不确定」的候选：生成反问话术，请用户澄清后重新进入本节点
    if options:
        logger.info(f"Step 4: [分支B] 存在候选商品名: {options}")
        # 只展示前 3 个：选项太多用户看不过来，反而增加澄清成本
        options_str = "、".join(options[:3])
        answer = f"您是想问以下哪个产品：{options_str}？请明确一下型号。"
        # answer 与 item_names 互斥写入：有 answer 时 item_names 必须为空，
        # 下游看到 answer 非空就直接返回、不再检索
        state["answer"] = answer
        state["item_names"] = []
        return state

    # 分支 C: 无结果
    # 确认与候选皆无：礼貌拒绝并引导用户提供准确型号，流程到此终止
    logger.info("Step 4: [分支C] 无确认也无候选")
    # 同样保持 answer/item_names 互斥的自洽状态
    state["answer"] = "抱歉，未找到相关产品，请提供准确型号以便我为您查询。"
    state["item_names"] = []
    return state


def step_5_write_history(state: Dict, session_id: str, history: List[Dict], rewritten_query: str, message_id: str) -> Dict:
    # 参数带 history：签名上是兼容预留，当前实现未使用（历史更新在 step_4 已完成）
    """
    写入最终历史记录

    Step 4 分支	    state["answer"]	    state["item_names"]	    Step 5 行为

    A 确认	        被删除	            confirmed	            只更新用户消息

    B 候选	        反问文本	            []	                    写助手反问 + 更新用户消息

    C 无结果	        提示文本	            []	                    写助手提示 + 更新用户消息

    为什么 B/C 也要写库：反问和拒绝同样是「这轮对话的正式回复」，
    不落库的话下一轮 LLM 看不到自己问过什么，澄清闭环会断
    """
    logger.info("Step 5: 写入会话历史")
    
    # 如果有助手回答（分支 B/C），写入助手消息
    # rewritten_query/item_names 传空：反问文本本身不关联商品，避免污染标注
    if state.get("answer"):
        logger.info("Step 5: 保存助手回答")
        save_chat_message(
            session_id=session_id,
            role="assistant",
            text=state["answer"],
            rewritten_query="",
            item_names=[]
        )

    # 更新用户消息（关联 rewrite_query 和 item_names）
    # 带 message_id 的 save 是「更新」而非「插入」：step 开头已插入过一次初始消息，
    # 这里把提取/对齐的结果回填到同一条消息上，历史里不出现重复的用户消息
    # item_names 取 state 现值：分支A 是 confirmed，分支B/C 是空——与消息的实际语义一致
    logger.info(f"Step 5: 更新用户消息 (ID: {message_id})")
    save_chat_message(
        session_id=session_id,
        role="user",
        text=state["original_query"],
        rewritten_query=rewritten_query,
        item_names=state.get("item_names", []),
        message_id=message_id
    )

    # 返回 state：编排函数需要继续把 history 挂上去
    return state


def node_item_name_confirm(state: QueryGraphState) -> QueryGraphState:
    """
    主节点函数：商品名称确认流程
    串起 step_1~step_5 五个阶段；各 step 都是纯函数（输入输出明确），
    便于单独单测；节点函数只负责编排与 state 生命周期管理
    副作用集中在此层：历史读写、进度上报、state 修改，step 函数保持无副作用
    """
    logger.info(">>> node_item_name_confirm: 开始处理")
    
    # session_id 是必填字段（历史读写都依赖它），缺失会让整个节点无意义
    session_id = state["session_id"]
    original_query = state.get("original_query", "")
    is_stream = state.get("is_stream", False)

    # 标记任务开始
    add_running_task(session_id, "node_item_name_confirm", is_stream)

    # 1. 获取历史记录
    # 近 10 条足够支撑指代消解：更早的上下文对「这个/它」的指向帮助有限，
    # 还会显著增加 prompt 长度
    # 取出的顺序为时间正序：LLM 按对话流理解，最新消息在最下方
    history = get_recent_messages(session_id, limit=10)
    logger.info(f"Node: 获取到 {len(history)} 条历史消息")
    # history 后续有三处用途：LLM 提取的上下文、step_4 补标历史商品名、回传 state 给下游
    # 一次取全避免多次查库

    # 2. 保存用户当前消息 (初始保存，后续 step 5 会更新)
    # 先插入占位：拿到 message_id 后 step_5 才能做「更新」而非「重复插入」
    # 初始 item_names 取 state 现值：可能是上一轮遗留的确认名，step_5 会再回填本轮结果
    message_id = save_chat_message(session_id, "user", original_query, "", state.get("item_names", []))
    logger.debug(f"Node: 用户消息已初始保存, ID: {message_id}")

    # 3. 提取信息
    # step_1 内部已兜底：失败时也返回合法结构（空商品名+原问题），这里无需 try
    extract_res = step_1_extract_info(original_query, history)
    # 两个 get 都带默认值：双保险防御 LLM 输出缺字段
    item_names = extract_res.get("item_names", [])
    rewritten_query = extract_res.get("rewritten_query", original_query)
    
    # 更新 State 中的 rewrite_query
    # 提前写入：即使后续走反问/拒绝分支，改写结果也已进 state 可追溯
    state["rewritten_query"] = rewritten_query

    align_result = {}
    # 先置空 dict：未提取到商品名时 step_4 拿到的就是「无确认无候选」，
    # 与「提取到了但全没对上」走同一个拒绝分支，逻辑统一

    # 4. & 5. 如果有提取到商品名，进行搜索和对齐
    # 未提取到商品名（纯闲聊/无指代）直接跳过检索：省一次向量化与 Milvus 往返
    if len(item_names) > 0:
        query_results = step_2_vectorize_and_query(item_names)
        align_result = step_3_align_item_names(query_results)
    else:
        # 无商品名不等于失败：纯寒暄类问题不需要商品上下文，直接透传改写问题给下游
        logger.info("Node: 未提取到商品名，跳过向量检索")

    # 6. 检查确认状态
    # 三分支裁决：有确认→继续检索 / 有候选→反问 / 全无→拒绝
    # 裁决结果直接决定主图走向：answer 非空时下游输出节点会短路返回
    state = step_4_check_confirmation(state, align_result, session_id, history, rewritten_query)

    # 7. 写入最终历史
    # 无论哪个分支都要落库：这是「这轮对话到底发生了什么」的完整记录
    # 落库放在进度上报之前：保证前端看到「已完成」时数据已持久化
    final_state = step_5_write_history(state, session_id, history, rewritten_query, message_id)

    # 将 history 存入 state，供后续节点（如 node_answer_output）使用
    # 免得下游节点重复查库：一次获取全程复用
    # 注意存的是「补标前」的历史：本节点对历史的更新是异步落库的，
    # 下游用本轮补标前的快照即可，语义一致
    final_state["history"] = history

    # 标记任务完成
    # 节点出口统一上报：SSE 进度条把本节点从 running 挪到 done
    add_done_task(session_id, "node_item_name_confirm", is_stream)
    
    logger.info(f"Node: 处理结束, Final State Item Names: {final_state.get('item_names')}")
    # 返回 final_state 而非增量 dict：本节点对 state 的修改分散在多个 step 中，
    # 直接回传整个 state 让 LangGraph 做整体覆盖，避免逐字段拼装遗漏
    return final_state


if __name__ == "__main__":
    # 测试代码块
    # 自测依赖真实环境：LLM 可用（提取+改写）、Mongo 可达（历史读写）、Milvus 可达（商品名检索）
    # 三者任一不可用都会走降级分支，观察 answer 字段即可判断走了哪个分支
    print("\n" + "="*50)
    print(">>> 启动 node_item_name_confirm 本地测试")
    print("="*50)
    
    # 模拟输入状态
    # original_query 用「商品名+问价格」：能覆盖 提取→检索→对齐→确认→落库 全链路
    # session_id 固定前缀 + 名称：便于在 Mongo 中定位测试产生的消息记录
    mock_state = {
        "session_id": "test_debug_session_001",
        "original_query": "HAK 180 烫金机多少钱？",  # 针对用户提到的具体 case
        "is_stream": False,
        # item_names 置空进入：模拟「上游还没有任何确认商品」的首轮提问场景
        "item_names": []
    }

    try:
        # 运行节点
        # 直接跑完整节点而非单步：验证五步编排与 state 流转是否正确衔接
        result = node_item_name_confirm(mock_state)
        
        print("\n" + "="*50)
        print(">>> 测试结果摘要:")
        # 三个关键字段逐一打印：rewritten_query 看改写质量，
        # item_names 看对齐结果（非空即走分支A），answer 非空说明走了反问/拒绝分支
        print(f"Rewritten Query: {result.get('rewritten_query')}")
        print(f"Item Names: {result.get('item_names')}")
        print(f"Answer: {result.get('answer')}")
        print("="*50)

    except Exception as e:
        # 走到这里只有一种可能：历史读写等非预期异常（各 step 已内部兜底），
        # 直接抛出让 LangGraph 上层感知节点失败
        logger.exception(f"测试运行期间发生未捕获异常: {e}")
