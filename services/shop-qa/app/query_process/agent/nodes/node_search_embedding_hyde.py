# HyDE节点
"""
HyDE（Hypothetical Document Embeddings，假设性文档嵌入）检索节点。

为什么「答案和文档」比「问题和文档」更相似：
向量检索的本质是「用问句的向量去匹配文档的向量」，但问句和文档在语义空间里
天生错位——问句短、口语化、只有意图，文档长、书面化、是完整答案，
两者的向量分布差异导致召回精度受限。
HyDE 的思路是先让 LLM 根据 query「假装回答」一段理想答案（假设性文档），
假设答案与真实答案同为「长且书面化的答案文本」，向量落点更接近文档区域，
用它去检索真实文档，天然比用问句去检索更贴。
代价与收益的权衡：多一次 LLM 调用（增加延迟与成本），换来一条语义更贴的召回路，
在 FAQ 型问答（问题与答案表述差异大）场景收益最明显。

两阶段流程：
1. step_1_create_hyde_doc：调 LLM 生成假设性答案（提示词引导模型扮演专家作答）；
2. step_2_search_embedding_hyde：把「改写后 query + 假设文档」拼接编码
   （保留 query 保证不丢原始意图，叠加假设文档增强答案侧语义），
   用 BGE-M3 稠密+稀疏双路混合检索（WeightedRanker 0.8:0.2，语义为主关键词为辅），
   带商品名过滤后在 Milvus 中召回 TopK。

容错定位：HyDE 是与 node_search_embedding 并行的增强路而非唯一路，
生成失败或检索异常都返回空结果，主链路由 Embedding 路兜底，最终由 RRF 融合两路。
"""
import sys
# 通配导入 lm/embedding/milvus 三组工具：节点需要 LLM、向量化、检索三类能力，
# from x import * 写法在此文件沿用历史风格（注意：不利于静态检查，新代码建议显式导入）
from app.utils.task_utils import add_running_task, add_done_task
from app.lm.lm_utils import *
from app.lm.embedding_utils import *
from app.clients.milvus_utils import *
from app.core.logger import logger
from app.core.load_prompt import load_prompt
# prompt 模板独立管理：hyde_prompt 是 HyDE 效果的核心变量，改提示词不需要动代码
from dotenv import load_dotenv, find_dotenv

# 环境变量模块级加载：step_2 里要读 CHUNKS_COLLECTION，必须保证 import 时配置已就绪
load_dotenv(find_dotenv())


def step_1_create_hyde_doc(rewritten_query: str) -> str:
    # 拆成独立 step 函数而非内联在节点里：便于单独单测「提示词→LLM→文档」链路
    """
    阶段1：利用大模型根据用户查询生成假设性文档（Hypothetical Document）。
    HyDE的核心在于：利用LLM生成一个“虚构但相关”的文档，用该文档的向量去检索真实的文档，
    从而缓解短查询（Query）与长文档（Document）在语义空间不匹配的问题。
    注意：生成的文档内容可能包含事实错误——没关系，它只是「语义锚点」，
    只要求风格与主题贴近真实文档，不要求内容正确。

    :param rewritten_query: 用户改写后的查询语句
    :return: LLM生成的假设性文档内容
    """
    if not rewritten_query:
        # 空查询无法生成假设文档：直接抛错而不是返回空串，
        # 让上层节点明确知道是「入参错误」而不是「生成失败」
        logger.error("Step 1 Error: rewritten_query 为空")
        raise ValueError("rewritten_query 不能为空")

    # 提前打日志：假设文档生成要数秒，留痕便于统计各阶段耗时
    logger.info(f"Step 1: 开始生成假设性文档 (HyDE), Query: {rewritten_query}")

    try:
        # 用商品名识别专用客户端：HyDE 只做纯文本生成，无需多模态能力，
        # 且与主回答模型分开计费/限流
        llm = get_item_name_llm_client()
        # 加载提示词模板，生成假设文档
        # 提示词通常引导LLM："请为这个问题写一段专业的回答..."
        # 模板设计意图：让 LLM 输出「像知识库文档一样」的段落（陈述句、含细节），
        # 而不是对话式回答——越接近文档风格，向量落点越贴近文档区域
        hyde_prompt = load_prompt("hyde_prompt", rewritten_query=rewritten_query)
        logger.debug(f"Step 1: Prompt加载成功, 长度: {len(hyde_prompt)}")

        # 调用LLM生成
        # invoke 返回的是 Message 对象，content 才是文本本体
        response = llm.invoke(hyde_prompt)
        hyde_doc = response.content
        
        logger.info(f"Step 1: 假设文档生成完成, 长度: {len(hyde_doc)} 字符")
        # debug 级别打印预览：假设文档质量需要人工抽检，但生产日志只留头部即可
        logger.debug(f"Step 1: 文档预览: {hyde_doc[:50]}...")
        
        return hyde_doc

    except Exception as e:
        # 记录后原样抛出：step_1 是 HyDE 路的关键步骤，失败信号必须传给节点层统一处理
        # 不在 step 内降级：降级策略（返回空）由节点层统一决定，分层保持职责单一
        logger.error(f"Step 1: 生成假设文档失败: {e}")
        raise e


def step_2_search_embedding_hyde(
    rewritten_query: str,
    hyde_doc: str,
    item_names=None,
    req_limit: int = 10,
    top_k: int = 5,
    ranker_weights=(0.8, 0.2),  # 调整默认权重以偏向稠密向量 (0.8, 0.2)
    norm_score: bool = True,    # 默认开启归一化
    output_fields=["chunk_id", "content", "item_name"],
):
    # 参数默认值与主检索节点保持一致：两路检索的召回口径一致，RRF 融合才有可比性
    # req_limit > top_k 的用意：每路先多召回再融合截断，给加权排序留出选择空间
    """
    阶段2：利用“重写问题 + 假设性文档”生成 embedding，并到向量库检索切片。
    
    :param rewritten_query: 改写后的查询
    :param hyde_doc: Step 1 生成的假设性文档
    :param item_names: 商品名称列表，用于元数据过滤 (item_name in [...])
    :param req_limit: Milvus 搜索时的候选召回数量
    :param top_k: 最终返回的 Top K 结果数量
    :param ranker_weights: 混合检索权重 (Dense, Sparse)
    :param norm_score: 是否对分数进行归一化
    :param output_fields: 返回结果中包含的字段
    :return: 检索结果列表
    返回结构：批量搜索格式 res[0] 是本查询的命中列表，
    每个 hit 含 distance（融合分数）与 entity（业务字段），与主检索节点同构
    """
    if not rewritten_query:
        raise ValueError("rewritten_query 不能为空")
    if not hyde_doc:
        # hyde_doc 为空说明 step_1 失败：空串拼接会退化为纯 query 检索，
        # 使 HyDE 路伪装成普通检索，不如显式报错暴露问题
        raise ValueError("hypothetical_doc 不能为空")

    # 1. 拼接查询与假设文档，形成更丰富的语义上下文
    # 为什么拼接而不是只用假设文档：假设文档是 LLM「编」的，可能偏离真实意图，
    # 保留 query 可以锚定原始意图；假设文档则负责把语义推向「答案侧」
    # 空格分隔而非换行：编码器对短文本更友好，拼接后仍是一个连贯语义单元
    combined_text = rewritten_query + " " + hyde_doc
    logger.info(f"Step 2: 拼接 Query + HyDE Doc, 总长度: {len(combined_text)}")

        # 2. 生成向量 (Dense + Sparse)
    # 与主检索节点同一编码入口（generate_documents 语义）：保证两路向量空间一致，分数可融合
    # 输入仍是列表结构（单条文本包一层），批量接口的统一约定
    logger.info("Step 2: 正在生成混合向量 (Embedding)...")
    embeddings = generate_embeddings([combined_text])
    
    # 3. 准备 Milvus 检索
    # 集合与主检索节点相同：两路检索都必须打同一个知识库，否则 RRF 无法融合
    collection_name = os.environ.get("CHUNKS_COLLECTION")
    if not collection_name:
        # 集合名缺失属于部署配置问题：日志报错并返回空，不抛异常拖垮主链路
        logger.error("Step 2 Error: 环境变量 CHUNKS_COLLECTION 未设置")
        return []
        
    logger.info(f"Step 2: 准备在集合 '{collection_name}' 中执行混合检索")

    # 构造过滤表达式 (如果有商品名限制)
    # expr 默认 None：Milvus 语义里 None 表示「不过滤、全库检索」，
    # 与空串不同——空串会导致过滤表达式语法错误
    # 过滤发生在检索前（预过滤）：先按商品缩小范围再算相似度，而不是检索后再筛
    expr = None
    if item_names:
        # 处理 item_names 中的引号，防止注入或语法错误
        # 每个值包双引号拼成 in 列表：Milvus 布尔表达式的字符串字面量语法
        quoted = ", ".join(f'"{v}"' for v in item_names)
        expr = f"item_name in [{quoted}]"
        logger.info(f"Step 2: 应用过滤条件: {expr}")
    else:
        # 无商品名时全库检索：HyDE 路不做「宁可空也不跨商品」的强约束，
        # 与主检索节点的保守策略不同——增强路多召回一些，由 RRF/Rerank 把关
        logger.info("Step 2: 未指定商品名过滤，将全库检索")

    try:
        # 构造搜索请求
        # 稠密+稀疏各召回 req_limit 条：先多召回，加权融合后再截 TopK
        # 向量取 [0]：拼接文本只有一条，对应返回向量的下标 0
        # expr 可能为 None（无商品名）：create_hybrid_search_requests 内部对 None 会跳过过滤条件
        reqs = create_hybrid_search_requests(
            dense_vector=embeddings.get("dense")[0],
            sparse_vector=embeddings.get("sparse")[0],
            expr=expr,
            limit=req_limit,
        )

        client = get_milvus_client()
        if not client:
            # 连接失败返回空而非抛错：HyDE 是增强路，主链路由 Embedding 路兜底
            logger.error("Step 2 Error: 无法连接到 Milvus")
            return []

        # 执行混合检索
        logger.info(f"Step 2: 执行 Hybrid Search, Weights={ranker_weights}, TopK={top_k}")
        # 两路分数先归一化（norm_score）再加权融合：稠密与稀疏分数量纲不同，直接加权会失真
        # output_fields 转 list：默认参数是不可变元组，Milvus SDK 要求列表类型
        res = hybrid_search(
            client=client,
            collection_name=collection_name,
            reqs=reqs,
            ranker_weights=ranker_weights,
            norm_score=norm_score,
            limit=top_k,
            output_fields=list(output_fields),
        )
        
        hit_count = len(res[0]) if res and len(res) > 0 else 0
        # res 为批量结构：单条查询的结果包在 res[0] 里，判空逻辑与主检索节点一致
        # res 为 None/空时按 0 条计：HyDE 路无结果不算错误，RRF 会自动少融合一路
        logger.info(f"Step 2: 检索完成, 找到 {hit_count} 个匹配切片")
        
        return res

    except Exception as e:
        # 检索异常吞掉并返回空：保证 HyDE 路失败不影响主链路（RRF 对空列表天然兼容）
        logger.error(f"Step 2: 检索过程发生异常: {e}")
        return []


def node_search_embedding_hyde(state):
    """
    HyDE (Hypothetical Document Embedding) 检索节点
    核心思想：通过LLM生成假设性答案（HyDE文档），将其向量化后用于检索，以解决短查询语义稀疏问题。

    在主图中的位置：与 node_search_embedding 平行（Fan-out），两条路的结果
    在 RRF 节点汇合融合；本节点失败/为空不会导致主图报错，只会少一路召回。

    执行步骤：
    1. 参数提取：从会话状态中获取改写后的查询（rewritten_query）和已确认的商品名（item_names）。
    2. 生成假设文档 (Step 1)：调用LLM，基于用户问题生成一段假设性的理想回答（即HyDE文档）。
    3. 混合检索 (Step 2)：
       - 将“用户问题 + 假设文档”合并，生成BGE-M3稠密+稀疏向量。
       - 在Milvus中执行混合检索（带商品名过滤），召回最相似的知识切片。
    4. 结果封装：返回检索到的切片列表和生成的假设文档，更新会话状态。

    :param state: 会话状态字典，包含 session_id, rewritten_query, item_names 等
    :return: 包含 hyde_embedding_chunks (检索结果) 和 hyde_doc (假设文档) 的字典
    """
    logger.info("---HyDE (假设文档检索) 节点开始处理---")
    # 记录任务开始状态
    add_running_task(state["session_id"], sys._getframe().f_code.co_name, state.get("is_stream"))

    # 1. 参数提取与校验
    # 优先使用改写后的查询，若无则降级使用原始查询
    # 降级逻辑：改写可能因 LLM 失败缺失，用原始查询仍可生成假设文档，保证链路可用
    # 改写查询包含完整上下文（指代已补全），生成的假设文档质量更高，故优先级更高
    rewritten_query = state.get("rewritten_query")
    if not rewritten_query:
        rewritten_query = state.get("original_query")
    
    if not rewritten_query:
        # 两级查询都为空属于上游异常：直接返回空 dict，LangGraph 会跳过状态更新
        logger.error("HyDE节点错误: 未找到有效的用户查询 (rewritten_query/original_query 均为空)")
        return {}

    item_names = state.get("item_names")
    # item_names 只用于元数据过滤，不参与向量化：向量化输入是 query+假设文档
    logger.info(f"HyDE检索入参: query='{rewritten_query}', item_names={item_names}")

    # 阶段1：生成假设性文档
    # hyde_doc 预置空串：后续 finally 里要引用，避免异常路径下变量未定义
    # 这一步是全链路最慢的（LLM 生成），生产环境可考虑缓存同 query 的假设文档
    hyde_doc = ""
    try:
        logger.info("Step 1: 开始生成假设性文档 (HyDE Doc)...")
        hyde_doc = step_1_create_hyde_doc(rewritten_query)
        logger.info(f"Step 1: 假设文档生成成功 (长度: {len(hyde_doc)})")
        # 长度是假设文档质量的粗信号：过短（几十字）通常说明提示词引导失败，检索增益有限
        logger.debug(f"假设文档预览: {hyde_doc[:100]}...")
    except Exception as e:
        logger.error(f"Step 1 (生成假设文档) 发生异常: {e}", exc_info=True)
        # HyDE生成失败属于非阻断性错误，可选择直接返回空或降级处理，此处直接返回空结果
        # 不降级重试：LLM 失败通常不是瞬时的，重试会拖长响应时间，主链路有 Embedding 路兜底
        return {}

    # 阶段2：用“重写问题 + 假设文档”检索切片
    # 注意这里不捕获 step_1 已抛过一次的场景：step_1 失败已 return，不会走到这里
    # 两个阶段串行依赖：没有假设文档就无法检索，顺序不可并行
    try:
        logger.info("Step 2: 基于假设文档执行 Milvus 混合检索...")
        res = step_2_search_embedding_hyde(
            rewritten_query=rewritten_query,
            hyde_doc=hyde_doc,
            item_names=item_names,
            top_k=5,
            # top_k=5 与主检索节点一致：两路各出 5 条，RRF 融合后仍有足够候选
        )
        
        hit_count = len(res[0]) if res and len(res) > 0 else 0
        logger.info(f"Step 2: 检索完成，召回 {hit_count} 条相关切片")
        
        if hit_count > 0:
            # 打印第一条结果用于调试
            # 只看 Top1：假设文档质量的核心信号就是「它召回了什么」，
            # Top1 与主题对得上说明假设文档有效
            first_hit = res[0][0]
            # distance 是两路加权融合后的分数；entity 里才是业务字段
            score = first_hit.get("distance")
            content_preview = first_hit.get("entity", {}).get("content", "")[:30]
            logger.debug(f"Top1 结果: Score={score}, Content='{content_preview}...'")

        return {
            # hyde_embedding_chunks：送入 RRF 与 Embedding 路融合
            # hyde_doc：随 state 下传（调试可见，也可供答案节点参考假设答案）
            "hyde_embedding_chunks": res[0] if res else [],
            "hyde_doc": hyde_doc,
        }
    except Exception as e:
        # step_2 内部已吞检索异常，这里的 except 主要兜底其他意外（如 state 结构异常）
        # 返回空 dict：LangGraph 不会更新 hyde_* 字段，下游 RRF 按缺路处理
        logger.error(f"Step 2 (向量生成与检索) 发生异常: {e}", exc_info=True)
        return {}
    finally:
        # 无论成功失败，均标记任务结束
        # 放 finally 而不是各分支手动调用：三条出口路径（正常/两个 return {}）统一收口，
        # 否则异常分支漏调用会让 SSE 进度条卡在「进行中」
        add_done_task(state["session_id"], sys._getframe().f_code.co_name, state.get("is_stream"))
        logger.info("---HyDE 节点处理结束---")


if __name__ == "__main__":
    # 本地测试代码
    # 自测依赖真实环境：LLM 可用（生成假设文档）+ Milvus 可达（执行检索）+ .env 配置齐全
    print("\n" + "="*50)
    print(">>> 启动 node_search_embedding_hyde 本地测试")
    print("="*50)
    
    # 模拟输入状态
    # original_query 与 rewritten_query 都给：验证节点优先取改写后查询的逻辑
    # item_names 给一个真实商品名：带过滤检索才能验证 expr 拼接是否正确
    mock_state = {
        "session_id": "test_hyde_session_001",
        "original_query": "HAK 180 烫金机怎么操作？",
        "rewritten_query": "HAK 180 烫金机的具体操作步骤是什么？",
        "item_names": ["HAK180烫金机"],
        "is_stream": False
    }

    try:
        # 运行节点
        result = node_search_embedding_hyde(mock_state)
        
        print("\n" + "="*50)
        print(">>> 测试结果摘要:")
        # 先看假设文档是否生成：文档为空说明 step_1（LLM 链路）有问题，
        # 文档正常但 chunks 为空则问题在检索侧（向量/过滤/集合配置）
        print(f"HyDE Doc Generated: {bool(result.get('hyde_doc'))}")
        if result.get("hyde_doc"):
            # 假设文档全文打印：自测时人工判断生成质量（是否贴近文档风格、是否切题）
            print(f"Doc Preview: {result.get('hyde_doc')}...")
            
        chunks = result.get("hyde_embedding_chunks", [])
        print(f"Chunks Found: {len(chunks)} , chunks内容：{chunks}")
        if chunks:
            # 打印 Top1 分数：与主检索节点的 Top1 对比，可判断 HyDE 路是否有增益
            # 注意 distance 是「融合后归一化分数」，数值大小只在同配置下可比
            print(f"Top Chunk Score: {chunks[0].get('distance')}")
        print("="*50)

    except Exception as e:
        # 完整堆栈输出：自测脚本异常需要定位到具体行
        logger.exception(f"测试运行期间发生未捕获异常: {e}")
