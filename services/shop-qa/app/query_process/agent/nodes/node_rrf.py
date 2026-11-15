"""
RRF（Reciprocal Rank Fusion，倒数排名融合）节点模块。

为什么用「排名」而不是「原始分数」做融合：
不同检索源给出的分数不可比——Embedding 源输出余弦/内积相似度（约 0~1），
HyDE 源的打分链路不同，数值范围与分布完全不同。若直接加权原始分数，
分数尺度大的来源会天然压过尺度小的来源，权重形同虚设。
RRF 只看每条结果在各自列表中的名次 rank，按 score = weight × 1/(k + rank) 累加，
丢弃有量纲的「分数」、只保留无量纲的「名次」，从而对量纲差异天然免疫。

公式中各参数的含义：
- weight：来源权重，表达对某路召回的先验信任度。本节点当前两路均配 1.0
  （Embedding 与 HyDE 各占一半话语权），需要倾斜时只改权重不动算法；
- k=60：平滑常数。rank=1 时贡献 1/61≈0.0164，rank=10 时贡献 1/70≈0.0143，
  k 越大名次间分差越平缓，避免头部文档一家独大，让中后段文档也有翻盘机会；
- 多来源重复命中的文档分数累加：同一 chunk 被两路同时召回时得分是两项之和，
  自然被推到结果前列——这正是「多源交叉验证」的融合思想。

模块内两个职责：
1. _as_entity_list：把上游异构结果（pymilvus Hit 对象 / 嵌套 dict / 扁平 dict）
   规整为统一 entity 字典列表。Milvus 不同 SDK 版本、hybrid_search 与普通 search
   的返回结构不一致，RRF 必须先拿到统一的 chunk_id 才能去重计分；
2. reciprocal_rank_fusion + node_rrf：通用加权 RRF 算法与 LangGraph 节点入口。

输出：rrf_chunks（融合后的 Top 文档列表），交给 Rerank 节点做语义精排。
"""
import sys
from typing import List, Dict, Any
from app.utils.task_utils import add_running_task, add_done_task
from app.core.logger import logger
# task_utils 用于向 SSE 进度通道上报节点开始/结束；logger 为项目统一 loguru 实例


# RRF节点
# 工具函数用下划线前缀标记「模块私有」：LangGraph 只注册 node_* 入口，
# _as_entity_list 不参与图的编排，仅供节点内部调用
def _as_entity_list(state_list) -> List[Dict[str, Any]]:
    """
    将上游节点输出统一规整为 entity dict 列表。
    兼容：
    - dict: {"entity": {..属性名和对应的字.}, "distance": ...} 或直接就是 {...}
    - pymilvus Hit: 不是 dict，但通常支持 hit.get("entity") 或 hit.entity
    - 其他：当作 chunk_id
    """
    out: List[Dict[str, Any]] = []
    # 用新列表收集而不是原地改写：state 中的列表可能被其他节点引用，不能有副作用
    for doc in (state_list or []):
        # or [] 兜底：上游节点未运行（state 里没有该 key 或为 None）时按空输入处理
        if not doc:
            # 跳过 None/空对象：空条目无法提取任何字段，继续没有意义
            continue
        
        final_ent = {}
        # final_ent 是每条结果的「标准形态」：字段尽量从 entity 里取、缺失字段从外层补，
        # 保证 RRF 拿到的每条都可通过 chunk_id/id 做唯一标识
        
        # 情况A: doc 是 Pymilvus 的 Hit 对象 (具有 entity 属性)
        # Hit 对象结构通常是: id=xxx, distance=xxx, entity={field1: val1, ...}
        # 这里的 id 是 Milvus 内部的主键 ID (int64 或 str)
        if hasattr(doc, "entity") and hasattr(doc, "id"):
            # 同时具备 entity 属性和 id 属性，是判定「这是 Milvus Hit 对象」的充分条件，
            # 比按类型名判断更稳（跨 SDK 版本类名可能变化，鸭子类型判断不依赖具体类）
            # 1. 提取 entity 中的业务字段 (如 content, item_name, chunk_id 等)
            # 注意: doc.entity 可能是一个 Entity 对象，也可能直接是 dict
            entity_content = doc.entity
            # 三级降级取值：能 to_dict 就转 dict，本身是 dict 就拷贝，
            # 都不行再尝试强制转换——不同 pymilvus 版本的 Entity 实现不一样
            if hasattr(entity_content, "to_dict"):
                 final_ent = entity_content.to_dict()
            elif isinstance(entity_content, dict):
                 final_ent = entity_content.copy()
            else:
                 # 尝试直接作为 dict 访问 (某些版本 sdk)
                 # 裸 except 是刻意的：Entity 的实现五花八门，这里只做「尽力而为」的兜底
                 try:
                     final_ent = dict(entity_content)
                 except:
                     # 转换失败就放弃这一路字段：外层 id/distance 仍可补，不能直接抛
                     pass
            
            # 2. 补充最外层的 id 和 distance
            # 优先保留 entity 内部已有的 chunk_id/id，如果没有，则把外层的 id 补进去
            # entity 内的主键名可能与 schema 定义一致（如 chunk_id），外层的 id 是 SDK 统一别名
            # 判断条件是「两个字段都没有才补」：避免覆盖 entity 内已带的主键名
            if "id" not in final_ent and "chunk_id" not in final_ent:
                final_ent["id"] = doc.id
            
            # 补充 distance (score)
            # Milvus 把相似度分叫 distance，这里统一映射为 score，屏蔽底层命名
            if hasattr(doc, "distance"):
                final_ent["score"] = doc.distance

        # 情况B: doc 已经是字典 (模拟数据或已处理数据)
        elif isinstance(doc, dict):
             # 尝试获取 entity 字段 (嵌套结构 {"entity": {...}, "id": ...})
             # 字典也可能带 entity 嵌套：hybrid_search 的一部分返回路径就是这种结构
             if "entity" in doc:
                 ent = doc["entity"]
                 if isinstance(ent, dict):
                     final_ent = ent.copy()
                     # copy 浅拷贝即可：RRF 只读不写，且共享内层引用可省内存
                 # 尝试从外层补充 id/score
                 if "id" in doc and "id" not in final_ent:
                     final_ent["id"] = doc["id"]
                 if "distance" in doc:
                     final_ent["score"] = doc["distance"]
             else:
                 # 扁平结构，直接使用
                 # 已经是标准扁平 dict（如本地 mock 数据）：原样透传即可
                 final_ent = doc

        # 情况C: 其他对象 (尝试 get 方法)
        # 兼容某些模拟对象/历史封装：只要有 dict 风格的 get 就能取到 entity
        elif hasattr(doc, "get"):
             ent = doc.get("entity") or doc
             if isinstance(ent, dict):
                 final_ent = ent
        
        # 最终校验：必须是非空字典
        # 三种情况都没提取出有效实体（如 Hit 缺 entity 字段）就整条丢弃：
        # 无 id 的条目会让 RRF 计分悬空，宁可少一条也不能让脏数据进融合
        if final_ent and isinstance(final_ent, dict):
            out.append(final_ent)
            
    return out


def reciprocal_rank_fusion(
        source_weights: list,
        k: int = 60,
        max_results: int = None,
) -> List[tuple]:
    # 设计成通用函数而非写死两路输入：未来接入知识图谱检索、BM25 等第三路来源时，
    # 只需在 node_rrf 里追加一个 (docs, weight) 元组，算法零改动
    """
    通用带权重的RRF算法实现
    :param source_weights:  列表，每个元素是(来源文档列表, 权重)的元组
                            例如: [([doc1, doc2], 1.0), ([doc2, doc3], 0.8)]
    :param k:     RRF 常数，默认 60。用于平滑排名影响，避免高排名文档占据过大优势。
    :param max_results: 只返回前 N 个，None 表示全部
    :return:      [(元素, RRF 得分), ...] 按得分降序排列
    返回值保留 (doc, score) 元组形式：调用方既要排序结果也可能关心分数，
    解包权交给调用方（本模块 node_rrf 只取 doc 部分）
    """
    # score_map: 记录 chunk_id 到 RRF 累加得分的映射
    # 用 dict 而非 list：按 id 累加分数需要 O(1) 查找，列表每次都要线性扫描
    score_map = {}
    # chunk_map: 记录 chunk_id 到文档实体对象的映射，用于最终返回
    # 分数与实体分存两表：聚合阶段只算分数，最后才按 id 配对，避免列表反复查找实体
    chunk_map = {}

    # 1. 遍历所有来源，计算每个文档的 RRF 分数
    # source_weights 结构: [(doc_list, weight), ...]
    # 双层循环复杂度 O(总文档数)：RRF 只遍历一遍各列表，比两两对比分数的融合便宜得多
    for docs, weight in source_weights:
        # enumerate(docs, start=1): 获取排名 (rank)，从 1 开始
        # rank 从 1 起算：RRF 公式约定，rank=0 会导致 1/(k+0) 偏大且不符合「第几名」语义
        for rank, item in enumerate(docs, start=1):
            # 获取文档唯一标识 ID
            # chunk_id or id 双重回退：本地入库链路写 chunk_id，Milvus SDK 返回统一叫 id
            # Milvus 设计上把主键字段在 API 层面统一叫 id，不管你在 schema 里定义的字段名是 pk、id 还是其他
            # 这是为了保持 API 兼容性：无论用户怎么命名主键，SDK 都用 id 来指代 “这条数据的唯一标识”
            # 你在向量数据库 UI 里看到的 pk 是表结构定义名，而代码里拿到的 id 是API 返回的统一主键别名
            chunk_id = item.get("chunk_id") or item.get("id")
            
            if not chunk_id:
                # 如果找不到 ID，记录警告并跳过，避免程序崩溃
                # 跳过而非抛异常：单条脏数据不应让整轮融合失败，日志留痕方便定位上游数据问题
                logger.warning(
                    f"RRF Warning: item missing chunk_id/id: {list(item.keys()) if isinstance(item, dict) else item}")
                continue

            # RRF 核心公式: score += weight * (1 / (k + rank))
            # 倒数形式 1/(k+rank) 的效果：rank 越靠前分越高，但差距被 k 平滑；
            # 用累加（+=）而不是覆盖：同一文档被多路召回时分数叠加，交叉文档胜出
            score_map[chunk_id] = score_map.get(chunk_id, 0.0) + weight * (1.0 / (k + rank))
            
            # 只记录第一次遇到的文档实体对象
            # setdefault 只在首次出现时写入：多路重复文档保留最早那条实体，
            # 各路返回的同 id 文档内容一致，取哪份都等价
            chunk_map.setdefault(chunk_id, item)

    # 2. 将结果转换为列表并排序
    # score_map/chunk_map 按 chunk_id 关联：分数与实体在两个 map 里，最后配对还原
    merged = []
    for chunk_id, score in score_map.items():
        doc_item = chunk_map[chunk_id]
        merged.append((doc_item, score))
    
    # 按分数降序排序 (得分越高越靠前)
    # 注意 key 取 x[1]（分数）而非 x[0]（文档 dict）：dict 无法比较大小
    merged.sort(key=lambda x: x[1], reverse=True)
    
    # 3. 截断结果
    # 截断放在最后：必须先把所有来源累加完毕才能确定最终排名，中途截断会漏掉交叉文档
    if max_results is not None:
        merged = merged[:max_results]
        
    return merged


def node_rrf(state):
    """
    RRF (Reciprocal Rank Fusion) 倒数排名融合节点
    
    功能：
    将来自不同检索源（如 Embedding 检索、HyDE 检索、知识图谱检索等）的结果进行融合排序。
    RRF 是一种无需训练的算法，仅根据文档在不同列表中的排名来计算最终得分。
    选型理由：不依赖分数分布、不引入模型、超参只有 k 和 weight，工程上最省心的多路融合方案。
    
    步骤：
    1. 提取各路检索结果：从 state 中获取 embedding_chunks 和 hyde_embedding_chunks。
    2. 结果标准化：将不同格式的检索结果统一转换为包含 chunk_id 的实体列表。
    3. 设置权重：为不同来源分配权重（当前配置：Embedding=1.0, HyDE=1.0）。
    4. 执行 RRF：计算融合分数并重新排序。
    5. 结果截断：保留 Top K 个结果。
    6. 更新状态：将融合后的结果存入 state["rrf_chunks"]。
    注意：本节点不做语义相关性判断——RRF 只解决「多路结果怎么合并」，
    「哪条最相关」交给下游 Rerank 节点，职责分离让每个节点都可独立替换。
    """
    logger.info("---RRF (倒数排名融合) 开始处理---")
    # _getframe 取当前函数名上报进度：SSE 进度条按节点名渲染，与节点注册名保持一致
    add_running_task(state["session_id"], sys._getframe().f_code.co_name, state.get("is_stream"))

    # 第一步：获取上游检索节点返回的文档
    # 上游检索节点（Milvus hybrid_search）返回的通常是 hit 列表：
    #  {"entity": {...fields...}, "distance": ...}
    # RRF 需要使用 chunk_id 做去重与计分，因此这里必须保留 entity（而不是仅抽取 content 字符串）。
    # 两路输入都要过 _as_entity_list：格式规整后再进算法，保证 chunk_id 提取不踩空
    embedding_chunks = _as_entity_list(state.get("embedding_chunks"))
    hyde_embedding_chunks = _as_entity_list(state.get("hyde_embedding_chunks"))
    # HyDE 路与 Embedding 路可能召回同一 chunk：RRF 靠 chunk_id 识别重复并累加分数

    logger.info(f"RRF 输入统计: Embedding源={len(embedding_chunks)}条, HyDE源={len(hyde_embedding_chunks)}条")
    
    # Debug 日志：打印部分 ID 以便核对
    # 前置非空判断：空列表切片 [:5] 无害，但打一行空日志没有意义
    if embedding_chunks:
        logger.debug(f"Embedding源 chunk_ids (前5个): {[c.get('chunk_id') for c in embedding_chunks[:5]]}")
    if hyde_embedding_chunks:
        logger.debug(f"HyDE源 chunk_ids (前5个): {[c.get('chunk_id') for c in hyde_embedding_chunks[:5]]}")

    # 第二步：为不同来源设置权重
    # 当前策略：两路召回权重相等，均为 1.0
    # 1.0:1.0 的含义：Embedding（原始 query 直检）与 HyDE（假设答案检索）可信度视为等同，
    # 由 RRF 的「交叉命中加成」机制自然凸显两路共识的文档
    source_weights = [
        (embedding_chunks, 1.0),
        (hyde_embedding_chunks, 1.0)
    ]

    # 第三步：应用带权重的RRF计算最终得分
    # k=60 是 RRF 算法的经典常数，max_results=10 限制最终召回数量
    # Top10 而不是直接 Top5：多留候选给 Rerank 精排，RRF 的职责是「融合排序」而非「终选」
    rrf_res = reciprocal_rank_fusion(source_weights, k=60, max_results=10)

    # 第四步：解包结果，提取文档和分数
    # RRF 分数到此完成使命（排序依据），不随 chunk 下传：下游 Rerank 会用语义分重新打分
    rrf_chunks = [doc for doc, score in rrf_res]
    # 记录任务结束
    add_done_task(state['session_id'], sys._getframe().f_code.co_name, state.get("is_stream"))

    # 只返回增量字段：LangGraph 把返回 dict merge 进全局 state，不回传无关字段
    return {"rrf_chunks": rrf_chunks}


if __name__ == "__main__":
    # 本地自测入口：不依赖 Milvus/LLM，用 mock 数据验证 RRF 融合逻辑的正确性
    print("\n" + "="*50)
    print(">>> 启动 node_rrf 本地测试")
    print("="*50)

    # 1. 构造假数据 (模拟真实数据库字段)
    # 模拟 Embedding 检索结果 
    # 三个文档按 score 降序：doc_1 > doc_2 > doc_3，注意 mock 里直接用扁平 dict（无 entity 嵌套）
    mock_embedding_chunks = [
        {
            # doc_1：两路都会命中的「交叉文档」，期望融合后排名靠前
            "id": "doc_1", 
            "pk": "pk_1", 
            "file_title": "操作手册_v1.pdf", 
            "item_name": "HAK 180 烫金机", 
            "content": "内容1：打开电源开关...", 
            "score": 0.9
        },
        {
            # doc_2：仅 Embedding 路命中，期望靠 RRF 平滑分留在结果中
            "id": "doc_2", 
            "pk": "pk_2", 
            "file_title": "维修指南.pdf", 
            "item_name": "HAK 180 烫金机", 
            "content": "内容2：遇到故障请联系...", 
            "score": 0.8
        },
        {
            # doc_3：两路都命中但原始分都不高，验证排名累加效应
            "id": "doc_3", 
            "pk": "pk_3", 
            "file_title": "参数表.xlsx", 
            "item_name": "HAK 180 烫金机", 
            "content": "内容3：电压220V...", 
            "score": 0.7
        }
    ]
    
    # 模拟 HyDE 检索结果 (包含 3 个文档，顺序不同，且有新文档 doc_4)
    # 关键差异：HyDE 路的排名与 Embedding 路不同（doc_3 排第1），且引入了新文档 doc_4，
    # 用于验证「排名」而非「分数」驱动融合
    mock_hyde_chunks = [
        {
            "id": "doc_3", 
            "pk": "pk_3", 
            "file_title": "参数表.xlsx", 
            "item_name": "HAK 180 烫金机", 
            "content": "内容3：电压220V...", 
            "score": 0.85
        }, 
        {
            "id": "doc_1", 
            "pk": "pk_1", 
            "file_title": "操作手册_v1.pdf", 
            "item_name": "HAK 180 烫金机", 
            "content": "内容1：打开电源开关...", 
            "score": 0.82
        }, 
        {
            # doc_4：仅 HyDE 路命中的文档，验证并集完整性
            "id": "doc_4", 
            "pk": "pk_4", 
            "file_title": "安全须知.docx", 
            "item_name": "HAK 180 烫金机", 
            "content": "内容4：操作时请佩戴手套...", 
            "score": 0.75
        }
    ]

    # 模拟输入状态
    # is_stream=False：非流式自测，task_utils 只记录状态不推送 SSE
    # state 里不带 chunk_id 字段也没关系：mock 用的是扁平 dict，_as_entity_list 走「情况B」直接透传
    mock_state = {
        "session_id": "test_rrf_session",
        "is_stream": False,
        "embedding_chunks": mock_embedding_chunks,
        "hyde_embedding_chunks": mock_hyde_chunks
    }

    try:
        # 运行节点
        # 直接调 node_rrf 而非手动拼算法：连 _as_entity_list 的规整链路一并验证
        result = node_rrf(mock_state)
        
        # 验证结果
        rrf_chunks = result.get("rrf_chunks", [])
        print("\n" + "="*50)
        print(">>> 测试结果摘要:")
        print(f"输入数量: Embedding={len(mock_embedding_chunks)}, HyDE={len(mock_hyde_chunks)}")
        print(f"输出数量: {len(rrf_chunks)}")
        print("-" * 30)
        
        # 打印详细排名
        # 最终排名应由「RRF 累加分」决定：doc_1/doc_3 双路命中应排在 doc_2/doc_4 单路命中之前
        # 命名按 RRF 约定从 Rank 1 开始，与算法内部的 rank 语义一致
        print("最终排名:")
        for i, doc in enumerate(rrf_chunks, 1):
            # 注意：返回结果中可能没有 chunk_id 字段，而是 id
            doc_id = doc.get('chunk_id') or doc.get('id')
            print(f"Rank {i}: ID={doc_id}, Title={doc.get('file_title')}, Content={doc.get('content')[:20]}...")

        # 验证预期逻辑：
        # 断言1：doc_1/doc_3 是交叉文档（两路都召回），RRF 加成后必须出现在最终结果里
        ids = [d.get("id") or d.get("chunk_id") for d in rrf_chunks]
        
        if "doc_1" in ids and "doc_3" in ids:
            print("\n[PASS] 交叉文档 (doc_1, doc_3) 成功融合保留")
        else:
            print("\n[FAIL] 交叉文档丢失")
            
        # 断言2：两路各 3 条、重叠 2 条，RRF 去重并集应为 4 条（max_results=10 未触发截断）
        if len(ids) == 4:
            print("[PASS] 并集数量正确 (3+3-2重叠=4)")
        else:
            print(f"[FAIL] 并集数量错误: 期望4, 实际{len(ids)}")
            
        print("="*50)

    except Exception as e:
        # 自测脚本的异常兜底：打印完整栈便于定位问题，无需额外处理
        logger.exception(f"测试运行期间发生未捕获异常: {e}")
