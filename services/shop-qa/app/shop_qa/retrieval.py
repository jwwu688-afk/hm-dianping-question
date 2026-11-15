"""Hybrid retrieval, optional HyDE, RRF fusion and BGE reranking."""
# 模块职责：检索与答案拼装层——问答质量的第二决定因素（第一是语料本身）。
# 检索链路：query →（可选 HyDE 生成假设文档）→ BGE-M3 稠密+稀疏双路混合检索 →
# select_hits 做 shop 范围过滤与去重 → merge_ranked 用 RRF 公式融合多路结果 →
# （可选）BGE reranker 精排 → 截断返回 Top-N 供 Prompt 拼装。
# select_hits/merge_ranked 为纯函数（可单测），search_knowledge 为完整编排入口。

import os

from .index import collection_name, shop_scope_filter
# collection_name：Milvus 集合名；shop_scope_filter：店铺范围过滤表达式生成器。


def select_hits(hits: list[dict], shop_id: int | None, limit: int = 8) -> list[dict]:
    """对单路检索结果做范围过滤、去重与截断（纯函数）。

    shop_id=None：全局检索，只保留平台公共语料（shop_id=0）；
    shop_id=N：店铺检索，保留公共语料 + 该店铺资料（0 或 N）。
    去重键为 (source_type, source_id, content)——不同来源的同文不算重复，
    相同来源的相同内容才是重复；空内容直接丢弃。
    返回条目是 entity 的浅拷贝，content 已 strip，保证下游拿到的干净一致。
    """
    selected = []
    # seen 记录已选中的去重键，保证同一内容只出现一次。
    # 用 set 而非 list：判定 O(1)，候选量大时不拖慢主循环。
    seen = set()
    for hit in hits:
        # Milvus 新旧 SDK 返回结构不同：带 entity 包装的取 entity，否则命中原 dict。
        entity = hit.get("entity") or hit
        # shop_id 字段缺失按 0（公共语料）处理，老数据兼容。
        scope = int(entity.get("shop_id") or 0)
        # 范围判定：全局模式排除所有店铺资料；店铺模式放行公共与本店。
        if (shop_id is None and scope != 0) or (shop_id is not None and scope not in (0, shop_id)):
            continue
        # 空内容无检索价值，跳过。
        content = str(entity.get("content") or "").strip()
        key = (entity.get("source_type"), entity.get("source_id"), content)
        if not content or key in seen:
            continue
        # 记录去重键并展开 entity（拷贝一份，避免调用方共享引用引发变更）。
        seen.add(key)
        selected.append({**entity, "content": content})
        # 达到 limit 立即返回，减少后续无谓处理。
        if len(selected) >= limit:
            break
    return selected


def merge_ranked(result_sets: list[list[dict]], shop_id: int | None, limit: int = 8) -> list[dict]:
    """用 RRF（Reciprocal Rank Fusion）融合多路检索结果。

    每路结果先过 select_hits 过滤去重，再按排名贡献分数 1/(60+rank)——
    常数 60 是 RRF 论文经验值，让头部排名差异平滑、多路命中相互增强；
    聚合后按总分降序取前 limit 条。与 reranker 精排互补：RRF 粗排、语义精排。
    纯函数无副作用，融合逻辑可离线单测。
    候选在 select_hits 阶段放宽到 20 条，避免融合阶段过早截断。
    """
    scores = {}
    # entities 保存每条内容对应的具体文档（同一 key 多路命中时保留最后一次）。
    # key 无法直接反查实体，所以两个字典必须成对维护。
    entities = {}
    for hits in result_sets:
        for rank, entity in enumerate(select_hits(hits, shop_id, limit=20), 1):
            # 融合去重键用 (source_key, part, content)：比 select_hits 的键更细，
            # 能区分同一来源的不同分段。
            key = (entity.get("source_key"), entity.get("part"), entity["content"])
            # RRF 累加：每路按名次贡献 1/(60+rank)，rank 从 1 开始。
            scores[key] = scores.get(key, 0.0) + 1 / (60 + rank)
            entities[key] = entity
    # 按累计分数降序输出，取前 limit 条。
    ordered = sorted(scores, key=scores.get, reverse=True)
    return [entities[key] for key in ordered[:limit]]


def search_knowledge(shop_id: int | None, question: str) -> list[dict]:
    """检索编排主入口：混合检索 → RRF 融合 → 可选精排，最终返回 ≤6 条证据。

    步骤：1) 校验 Milvus 与集合可用性；2) 可选 HyDE 生成假设文档扩充查询；
    3) 每个查询做稠密+稀疏混合检索（RRF 权重 0.8/0.2）；4) merge_ranked 融合；
    5) 多于 1 条时用 BGE reranker 以 question-content 对打分精排。
    Milvus 不可用抛 RuntimeError（api 层转 503），集合不存在返回空列表（冷启动）。
    最终返回 ≤6 条：由调用方 answer.py 直接拼入 Prompt 资料段。
    """
    # 延迟导入：Milvus 与 embedding 客户端初始化较重，仅检索时加载。
    # 模块顶部导入会让纯函数单测也背上这些依赖。
    from app.clients.milvus_utils import get_milvus_client, create_hybrid_search_requests, hybrid_search
    from app.lm.embedding_utils import generate_embeddings

    client = get_milvus_client()
    if client is None:
        # 连接失败必须显式报错，让上层把 503 暴露给前端而不是静默空答。
        raise RuntimeError("Milvus unavailable")
    collection = collection_name()
    # 集合尚不存在（服务冷启动未初始化）时按「无资料」处理，不算故障。
    if not client.has_collection(collection_name=collection):
        return []
    # 查询队列：原始问题始终参与；HyDE 开启时追加假设文档作为第二查询。
    # 队列长度决定混合检索次数（2 次以内），控制检索延迟。
    queries = [question]
    # HyDE：先让 LLM 假想一份答案资料，用它的语义相近度提升召回，
    # 默认开启；假设文档截断 1000 字符，防止长输出拖慢向量化。
    if os.getenv("RAG_ENABLE_HYDE", "true").lower() == "true":
        from app.lm.lm_utils import get_llm_client
        hypothetical = get_llm_client().invoke(
            "写一段可能回答下列问题的简短资料，供语义检索使用。不要解释，也不要引用来源。问题：" + question
        ).content
        if hypothetical:
            queries.append(str(hypothetical)[:1000])
    result_sets = []
    # 对每个查询独立做一次混合检索，结果分别保存供 RRF 融合。
    # 原始查询与 HyDE 查询各一路：两路命中并集的 RRF 分数更高。
    for query in queries:
        # BGE-M3 同时产出稠密与稀疏向量：稠密抓语义、稀疏抓关键词。
        vectors = generate_embeddings([query])
        # 构造双路请求：过滤条件由 shop_scope_filter 生成，单路候选 12 条。
        requests = create_hybrid_search_requests(
            vectors["dense"][0], vectors["sparse"][0], expr=shop_scope_filter(shop_id), limit=12
        )
        # 加权融合（0.8 稠密 / 0.2 稀疏）+ 归一化得分，返回前 12 条。
        result = hybrid_search(
            client, collection, requests, ranker_weights=(0.8, 0.2), norm_score=True,
            limit=12, output_fields=["source_key", "shop_id", "source_type", "source_id",
                                     "title", "file_title", "content", "part", "image_urls"]
        )
        if result is None:
            # 检索层失败（超时/连接问题）不能静默吞掉，向上抛出。
            raise RuntimeError("Milvus search failed")
        result_sets.append(result[0])
    # RRF 融合多路结果，融合阶段放宽到 10 条，留给精排继续压缩。
    # 若融合后只有 1 条或精排关闭，最终仍会截断到 6 条以内。
    documents = merge_ranked(result_sets, shop_id, limit=10)
    # 精排开关默认开：只有多条候选时才有排序意义。
    # reranker 用 cross-encoder 逐对打分，比向量相似度更准但更慢，
    # 所以只在融合后的 10 条上做，不作用于全量候选。
    if len(documents) > 1 and os.getenv("RAG_ENABLE_RERANK", "true").lower() == "true":
        from app.lm.reranker_utils import get_reranker_model
        # compute_score 输入为 [query, doc] 对，输出相关性分数列表。
        scores = get_reranker_model().compute_score([[question, doc["content"]] for doc in documents])
        # 兼容不同返回类型：标量/元组/可迭代统一转列表，避免 zip 崩溃。
        if not isinstance(scores, (list, tuple)):
            scores = list(scores) if hasattr(scores, "__iter__") else [scores]
        # 按精排分数降序重排文档。
        documents = [doc for _, doc in sorted(zip(scores, documents), key=lambda pair: pair[0], reverse=True)]
    # 最终截断 6 条：这是 Prompt 资料段的容量上限，平衡上下文长度与信息量。
    return documents[:6]


def search_shop(shop_id: int, question: str) -> list[dict]:
    """单店铺检索入口：与全局检索共用实现，仅语义上限定店铺范围。

    独立命名让 answer.py 的默认参数更自解释；
    shop_id 必为正整数（由 api 层校验），公共语料 shop_id=0 自动附带。
    """
    # 参数原样透传，无额外逻辑；将来若有店铺级检索差异在此扩展。
    return search_knowledge(shop_id, question)
