"""
Rerank 重排序节点：对「本地 RRF 融合结果 + 联网搜索结果」做统一精排。

为什么需要重排序：RRF 只按「名次」融合，无法表达 query 与文档的真实语义相关度；
且本地/联网两路的召回质量参差。BGE-Reranker 是交叉编码器（Cross-Encoder），
把 (query, passage) 拼在一起过 Transformer 逐对打分，精度远高于双塔检索，
适合在「候选集已收敛到几十条」时做最后一道精排。

三阶段流水线：
1. step_1_merge_docs：合并与标准化——本地 RRF 文档与联网摘要格式异构
   （本地取 content、联网取 snippet，ID 只有本地有、url 只有联网有），
   先统一为 {text, doc_id, chunk_id, title, url, source} 的标准结构；
2. step_2_rerank_docs：BGE 重排序——逐对计算 (query, passage) 相关性 logits，
   按分数降序后做 sigmoid 归一化（logits 无固定量纲，归一化后阈值才有通用意义）；
3. step_3_topk：动态 TopK 截断——机械取前 N 条会保留大量低相关噪声，
   结合「分数断崖」（相邻分数骤降处）截断，让保留集合天然贴合语义分界。

关键约束：sentence_pairs 必须严格遵循 (query, passage) 顺序——
BGE-Reranker 训练时 query 恒在第一位，模型对输入顺序敏感，颠倒会静默失真（不报错但打分错乱）。
"""
from app.utils.task_utils import *
from app.lm.reranker_utils import get_reranker_model
from app.core.logger import logger
import sys
import math

# -----------------------------
# Rerank / TopK 全局常量（不从 state 读取）
# -----------------------------
# 常量集中模块级定义：截断策略属于「全局约定」而非单次请求参数，
# 从 state 读取会导致每次会话策略漂移，且难以统一调整
# 动态 TopK 硬上限：最多取前 N 条（<=10）
RERANK_MAX_TOPK: int = 10
# 最小 TopK：至少保留前 N 条（>=1，且 <= RERANK_MAX_TOPK）
RERANK_MIN_TOPK: int = 1
# 断崖阈值（相对） 分比例
RERANK_GAP_RATIO: float = 0.25
# 断崖阈值（绝对） 分值
RERANK_GAP_ABS: float = 0.5

def _sigmoid(x: float) -> float:
    """将 reranker 的 logits 分数映射到 (0, 1) 区间。
    保留原始排序（单调递增），同时让阈值 0.5/0.25 通用适配任何模型。
    为什么归一化：BGE-Reranker 输出的是未定标 logits，不同模型/版本数值范围差异巨大，
    sigmoid 后所有模型都落到 (0,1)，下游断崖阈值才能用统一配置
    """
    # 防止 exp 溢出（x 极大时 e^-x 下溢，x 极小时 e^-x 上溢）
    # 数学上 sigmoid(x) = 1/(1+e^-x)，按 x 正负改写等价形式，让 exp 的参数恒为负数
    if x >= 0:
        z = math.exp(-x)
        return 1 / (1 + z)
    else:
        z = math.exp(x)
        return z / (1 + z)

# Rerank节点（工作流入口）
# 三个 step_ 函数拆分而非塞进一个节点函数：合并/打分/截断各自可单测，
# 测试代码可以直接调用中间函数注入 mock 数据
def step_1_merge_docs(state):
    """
    阶段一：文档合并与标准化
    
    目标：将多路召回（本地知识库 + 联网搜索）的异构数据，统一合并为 Reranker 模型可处理的标准格式。
    
    输入来源：
    1. rrf_chunks (List[Dict]): 本地知识库检索结果（经 RRF 融合排序）。
       - 结构：包含 Milvus entity 信息的复杂字典或对象。
       - 关键字段：chunk_id, content, title/item_name。
    2. web_search_docs (List[Dict]): 联网搜索结果（经 MCP 搜索返回）。
       - 结构：包含搜索摘要的扁平字典。
       - 关键字段：snippet, title, url。
       
    输出结果 (List[Dict]):
    - 标准化文档列表，每项包含：
      - text: 用于重排序的核心文本（content 或 snippet）
      - title: 标题（用于增强语义或展示）
      - doc_id/chunk_id: 唯一标识（本地文档有，联网文档为 None）
      - url: 来源链接（本地为空，联网文档有）
      - source: 来源标记 ("local" 或 "web")
    """
    
    # 1. 提取输入源
    # or [] 双兜底：上游任意一路没跑到（如联网失败返回空 dict）时按空列表处理
    rrf_docs = state.get("rrf_chunks") or []
    web_docs = state.get("web_search_docs") or []
    
    logger.info(f"Step 1: 开始合并文档 - 本地RRF源: {len(rrf_docs)}条, 联网Web源: {len(web_docs)}条")
    doc_items = []
    # ---------------------------------------------------------
    # 2. 处理本地知识库文档 (rrf_chunks)
    # ---------------------------------------------------------
    # 本地文档的字段藏在 entity 里（Milvus 命中对象结构），联网文档是扁平 dict，
    # 因此两段循环的字段提取逻辑不同，标准化输出保证下游无感知
    for i, doc in enumerate(rrf_docs):
        # 简化：直接使用 dict(doc) 转换，如果 doc 本身是 dict 则无损，如果是对象则尝试转换
        # 由于上游 RRF 节点已经做了 _as_entity_list 处理，这里 doc 极大概率已经是纯字典
        # 因此可以移除繁琐的 try-except 和 entity 嵌套判断，直接取值
        
        # 兼容性处理：优先取 'entity' 字段（防守式编程），若无则视为 doc 本身即 entity
        # 注意：这里的 doc 应当已经是字典（由上游 _as_entity_list 保证）
        # 防守的原因：上游节点升级可能引入新的嵌套格式，这里兜一层避免 KeyError 直接炸掉
        entity = doc.get("entity") if isinstance(doc, dict) and "entity" in doc else doc
        
        # 提取核心文本 (content)，这是重排序的依据
        # 如果不是字典或无 content，则跳过
        if not isinstance(entity, dict):
            logger.warning(f"本地文档格式异常 (index={i}): {type(entity)}")
            continue
            
        content = entity.get("content")
        if not content:
            # 仅在 debug 模式记录，避免生产环境日志刷屏
            # 无 content 就无法参与重排（text 是打分的唯一依据），直接丢弃
            logger.debug(f"跳过无内容文档 (index={i}, keys={list(entity.keys())})")
            continue

        # 提取元数据 (使用 .get 链式回退，简洁明了)
        # doc_id 与 chunk_id 同源双写：chunk_id 是 RRF/Milvus 链路的历史命名，doc_id 是新规范
        doc_id = entity.get("chunk_id") or entity.get("id")
        title = entity.get("title") or entity.get("item_name") or ""

        # 组装标准化对象
        doc_items.append({
            "text": content,
            "doc_id": doc_id,
            "chunk_id": doc_id,  # 兼容旧逻辑保留字段
            "title": title,
            "url": "",
            "source": "local",
        })

    # ---------------------------------------------------------
    # 3. 处理联网搜索文档 (web_search_docs)
    # ---------------------------------------------------------
    # 联网文档没有 chunk_id/doc_id（无本地主键），只有 url 可溯源，
    # source 标记 "web" 供下游按来源区分处理（如答案里附引用链接）
    for i, doc in enumerate(web_docs):
        # 兼容不同字段名：优先取 snippet (摘要)，其次 content
        # 联网结果同样可能缺 title/url，strip 后按空串兜底，字段缺失不影响打分
        text = (doc.get("snippet") or doc.get("content") or "").strip()
        url = (doc.get("url") or "").strip()
        title = (doc.get("title") or "").strip()
        
        if not text:
            logger.debug(f"跳过无内容联网结果 (index={i})")
            continue
            
        doc_items.append({
            "text": text,
            "doc_id": None, # 联网结果无固定 ID
            "chunk_id": None,
            "title": title,
            "url": url,
            "source": "web",
        })

    logger.info(f"Step 1: 文档合并完成，共输出 {len(doc_items)} 条标准化文档")
    # 只输出标准化后的列表：格式差异到此为止，下游只认统一结构
    return doc_items


def step_2_rerank_docs(state, doc_items):
    """
    阶段二：对文档进行重排序
    - 输入 doc_items：[{ text,doc_id}, ...]（由第一阶段产出）
    - 输出：在 state 中写入 reranked_docs（结构化列表）
    为什么用 reranker 而不是向量相似度再排一遍：检索阶段的双塔向量是「各自编码再算距离」，
    精度上限低；Reranker 交叉编码把 query 与文档拼在一起逐对计算，能建模词级交互，
    精度显著更高，但开销大，所以只用于小候选集的精排
    """
    question = state.get("rewritten_query") or state.get("original_query") or ""

    # 优先用改写后的 query：它补全了指代、含完整上下文，与文档的相关性判断更准；
    # 都为空直接放弃——没有问题就无法打分
    # 如果没有文档或问题，直接返回
    if not doc_items or not question:
        logger.warning("Step 2: 跳过重排序 (无文档或无问题)")
        return []

    logger.info(f"Step 2: 开始重排序 (Rerank), 待排序文档数: {len(doc_items)}")
    
    # 初始化重排序模型（这里以使用 BGE 重排序模型为例）
    # 先取文本列再组装 pairs：texts 与 doc_items 按下标一一对应，后面 zip 回填分数要用
    texts = [x["text"] for x in doc_items]
    try:
        reranker = get_reranker_model()

        # 构建查询-文档对（必须是 str）
        # 大段说明 docstring：详细解释 pairs 的组装格式、顺序约束与分数含义（见下方引用块）
        """
           格式：列表，每个元素是二元元组 / 列表，严格遵循 (query, passage) 顺序（即你的「问题、答案」）：
             第 1 个元素（query）：用户的问题 / 检索词（如 “什么是 RRF 算法？”）；
             第 2 个元素（passage）：候选答案 / 待匹配文档（如你之前 RRF 融合后的文档内容）；
             支持单组匹配和批量匹配：
             # 单组匹配：1个问题+1个候选答案
             sentence_pairs = [("什么是RRF算法？", "RRF是倒数排名融合算法，用于多来源排序结果融合")]
             # 批量匹配：1个问题+多个候选答案（重排序核心场景，推荐）
             sentence_pairs = [
                   ("什么是RRF算法？", "RRF是倒数排名融合算法，用于多来源排序结果融合"),
                   ("什么是RRF算法？", "FP16是半精度推理，能降低模型显存占用"),
                   ("什么是RRF算法？", "FlagReranker是BGE重排序模型的封装类")
             ]
              注意：顺序不可颠倒（必须是「问题在前，答案在后」），模型对输入顺序有严格要求，颠倒会导致打分结果失真。
           2. 输出结果：scores 分数含义与格式
           格式：列表，元素为浮点数，列表长度与 sentence_pairs 完全一致，一一对应（第 n 个分数对应第 n 个 (问题，答案) 元组的相关性）；
           分数含义：数值越高，代表「问题」与「答案」的语义匹配度 / 相关性越强（BGE 重排序模型的分数无固定取值范围，核心看相对大小，用于排序即可）；
           核心用途：将分数与候选答案绑定，按分数降序排列，即可得到「与问题最相关→最不相关」的答案排序，实现重排序。
        """
        # 格式：列表，每个元素是二元元组 / 列表，严格遵循 (query, passage) 顺序
        # 同一个 query 配 N 个 passage：批量打分一次前向完成，比逐对调用快得多
        sentence_pairs = [[question, t] for t in texts]
        # 计算相关性得分
        logger.info("Step 2: 正在计算相关性得分...")
        scores = reranker.compute_score(sentence_pairs)
        # 将得分与文档配对并排序（按 score 降序）
        # zip 三列表按下标对齐：第 i 个文档拿到第 i 个分数，顺序错位会张冠李戴
        scored_docs = []
        for item, text, score in zip(doc_items, texts, scores):
            # 保留两位小数便于日志查看
            score_val = float(score)
            scored_docs.append(
                {
                    "text": text,
                    "score": score_val,
                    "source": item.get("source") or "",
                    "chunk_id": item.get("chunk_id"),
                    "doc_id": item.get("doc_id"),
                    "url": item.get("url") or "",
                    "title": item.get("title") or "",
                }
            )
        # 按分数降序排序
        # 必须在归一化之前排好：sigmoid 单调不减其实不改变顺序，但先排序语义更直白
        scored_docs.sort(key=lambda x: x["score"], reverse=True)

        # ---- 新增：Sigmoid 归一化 ----
        # score_raw 保留原始 logits 便于调试，score 覆盖为 [0,1] 归一化值
        # 双字段共存：断崖阈值按归一化分判断，排查问题时仍能看原始分数量级
        for d in scored_docs:
            d["score_raw"] = d["score"]
            d["score"] = _sigmoid(d["score"])
        # 日志：观察归一化前后的分数
        # raw_range 是「首尾两端」：scored_docs 已降序，[-1] 是最低分、[0] 是最高分
        if scored_docs:
            logger.info(
                f"Step 2: 归一化完成 - "
                f"raw_range=[{scored_docs[-1]['score_raw']:.4f}, {scored_docs[0]['score_raw']:.4f}], "
                f"norm_top3={[round(d['score'], 4) for d in scored_docs[:3]]}"
            )
        # ---- 新增结束 ----

        return scored_docs
    except Exception as e:
        # try 范围覆盖「取模型 + 打分 + 归一化」全程：任何一步失败都走同一条降级路径
        logger.error(f"Step 2: 重排序过程发生异常: {e}", exc_info=True)
        # 出错时降级：返回原始文档顺序，分数置为 0 或 None
        # 避免整个流程中断
        # 降级原则：Rerank 挂了就退回「未精排」状态继续走，相关度损失可接受，
        # 整条问答链路中断不可接受
        fallback_docs = [
            {
                "text": x.get("text"),
                "score": 0.0, # 降级分数
                "source": x.get("source") or "",
                "chunk_id": x.get("chunk_id"),
                "doc_id": x.get("doc_id"),
                "url": x.get("url") or "",
                "title": x.get("title") or "",
            }
            for x in doc_items
        ]
        # 在这里我们不直接修改 state，而是返回结果让主流程处理
        # 但为了兼容原有逻辑（虽然函数签名是返回 scored_docs），我们记录异常并抛出或返回降级结果
        # 这里选择返回降级结果，保证流程继续
        # 降级结果与正常结果的字段结构完全一致：下游 step_3_topk 无需感知是否发生过降级
        return fallback_docs

def step_3_topk(scored_docs):
    """
    阶段三：动态 TopK（最多 10）
    基于 scored_docs（已按 score 降序排序）进行智能截断，
    核心逻辑：结合固定上下限+断崖阈值判断，避免机械取前N条，保留语义相关的连续文档集合
    :param scored_docs: 列表，元素为带score的文档字典，已按score降序排列，格式如[{"doc": 文档对象, "score": 相关性分数}, ...]
    :return: 列表，动态截断后的TopK文档列表，数量≤10
    """
    # 硬上限：最多取前10条，取全局常量与实际文档数的较小值（避免索引越界）
    # 注：max_topk从全局常量读取，不依赖外部状态，保证逻辑一致性
    # 动态 TopK 的动机：文档少时取满、分数断崖处提前截断，
    # 避免把明显不相关的文档硬凑进上下文（噪声会拉低 LLM 答案质量）
    max_topk = min(RERANK_MAX_TOPK, len(scored_docs))
    min_topk = RERANK_MIN_TOPK  # 硬下限：至少保留的文档数量（全局常量配置）
    gap_ratio = RERANK_GAP_RATIO  # 相对断崖阈值：分数下降的相对比例阈值（全局常量配置）
    gap_abs = RERANK_GAP_ABS      # 绝对断崖阈值：分数下降的绝对差值阈值（全局常量配置）

    # 1) 断崖截断核心逻辑：从min_topk之后开始检测分数断崖，出现则提前截断
    # min_topk 之前的文档视为「保底必留」：Top1~N 是核心答案来源，不参与断崖判断
    topk = max_topk  # 默认值：无断崖时取满硬上限（最多10条）
    # 仅当实际可取值超过硬下限时，才触发断崖检测（否则直接取满min_topk）
    if topk > min_topk:
        # 遍历范围：从min_topk-1到max_topk-2（索引从0开始），检测相邻两个文档的分数差
        # 例：min_topk=3，max_topk=10 → 遍历i=2,3,4,5,6,7,8（对应第3~9条文档，检测与下一条的差距）
        for i in range(min_topk - 1, max_topk - 1):
            s1 = scored_docs[i].get("score")  # 当前位置文档的分数
            s2 = scored_docs[i + 1].get("score")  # 下一个位置文档的分数

            gap = s1 - s2  # 计算相邻文档的分数绝对差距（因已降序，gap≥0）
            # 计算相对差距：绝对差距 / 当前文档分数（+1e-6避免除数为0/极小值，防止程序报错）
            # 1e-6 是 Python 中科学计数法的写法，等价于 0.000001（10 的负 6 次方，也就是百万分之一）。
            rel = gap / (abs(s1) + 1e-6)
            # 触发断崖截断条件：绝对差距≥绝对阈值 OR 相对差距≥相对阈值
            # 绝对阈值抓「分数普遍高时的骤降」，相对阈值抓「分数普遍低时的骤降」，两者互补
            # 满足任一条件，说明下一条文档相关性骤降，截断在当前位置
            if gap >= gap_abs or rel >= gap_ratio:
                logger.info(f"Step 3: 触发断崖截断 @ index={i} (Score {s1:.4f} -> {s2:.4f}, Gap={gap:.4f})")
                topk = i + 1  # 最终取前i+1条（索引转实际数量，如i=2 → 取前3条）
                break  # 触发截断后立即退出循环，不再检测后续位置

    # 按最终计算的topk值，截取前topk条文档
    # 入参已保证降序：直接前缀切片即是「分数最高的 topk 条」
    topk_docs = scored_docs[:topk]
    
    logger.info(f"Step 3: 截断完成，保留前 {len(topk_docs)} 条文档 (TopK={topk})")
    
    if topk_docs:
        preview = ", ".join([f"{d.get('chunk_id') or 'Web'}({d.get('score'):.3f})" for d in topk_docs[:3]])
        logger.debug(f"Step 3: Top3 文档预览: {preview}")
        
    # 返回动态TopK处理后的文档列表
    return topk_docs


def node_rerank(state):
  """
  Rerank节点
  对检索到的文档进行重新排序，提高相关性
  """
  logger.info("---Rerank (重排序) 节点开始处理---")
  # 节点入口/出口成对上报：SSE 进度条依赖 running/done 两个列表的增减来渲染
  add_running_task(state["session_id"], sys._getframe().f_code.co_name, state.get("is_stream"))

  # 阶段一：合并文档
  # 三个阶段串行依赖：合并产出喂给打分，打分产出喂给截断，顺序不可调换
  doc_items = step_1_merge_docs(state)
  # 阶段二：对文档进行重排序
  scored_docs = step_2_rerank_docs(state, doc_items)
  # 阶段三：动态 TopK
  topk_docs = step_3_topk(scored_docs)
  
  logger.info(f"Rerank 节点处理结束, 最终输出 {len(topk_docs)} 条文档")

  add_done_task(state['session_id'], sys._getframe().f_code.co_name, state.get("is_stream"))
  # 只返回 reranked_docs 增量字段：LangGraph 会把返回 dict merge 进全局 state
  return {"reranked_docs": topk_docs}


if __name__ == "__main__":
    print("\n" + "="*50)
    print(">>> 启动 node_rerank 本地测试")
    print("="*50)
    
    # 1. 模拟数据
    # 1.1 RRF 本地文档数据
    mock_rrf_chunks = [
        {"chunk_id": "local_1", "content": "RRF是一种倒数排名融合算法", "title": "算法介绍", "score": 0.9},
        {"chunk_id": "local_2", "content": "BGE是一个强大的重排序模型", "title": "模型介绍", "score": 0.8},
        {"chunk_id": "local_3", "content": "无关的测试文档内容", "title": "测试文档", "score": 0.1} # 预期低分
    ]
    
    # 1.2 MCP 联网搜索数据
    mock_web_docs = [
        {"title": "Rerank技术详解", "url": "http://web.com/1", "snippet": "Rerank即重排序，常用于RAG系统的第二阶段"},
        {"title": "无关网页", "url": "http://web.com/2", "snippet": "今天天气不错，适合出去游玩"} # 预期低分
    ]
    
    mock_state = {
        "session_id": "test_rerank_session",
        "rewritten_query": "什么是RRF和Rerank？", # 查询意图：想了解这两个算法
        "rrf_chunks": mock_rrf_chunks,
        "web_search_docs": mock_web_docs,
        "is_stream": False
    }

    try:
        # 运行节点
        result = node_rerank(mock_state)
        reranked = result.get("reranked_docs", [])
        
        print("\n" + "="*50)
        print(">>> 测试结果摘要:")
        print(f"输入文档总数: {len(mock_rrf_chunks) + len(mock_web_docs)}")
        print(f"输出文档总数: {len(reranked)}")
        print("-" * 30)
        
        print("最终排名:")
        for i, doc in enumerate(reranked, 1):
            print(f"Rank {i}: Source={doc.get('source')}, Score={doc.get('score'):.4f}, Text={doc.get('text')[:20]}...")
            
        # 验证逻辑：
        # 预期 "local_1", "local_2", "Rerank技术详解" 分数较高
        # 预期 "local_3", "无关网页" 分数较低，可能被截断或排在最后
        
        top1_score = reranked[0].get("score")
        if top1_score > 0:
            print("\n[PASS] Rerank 打分正常")
        else:
            print("\n[FAIL] Rerank 打分异常 (均为0或负数)")

        print("="*50)

    except Exception as e:
        logger.exception(f"测试运行期间发生未捕获异常: {e}")
