"""Hybrid retrieval, optional HyDE, RRF fusion and BGE reranking."""

import os

from .index import collection_name, shop_scope_filter


def select_hits(hits: list[dict], shop_id: int | None, limit: int = 8) -> list[dict]:
    selected = []
    seen = set()
    for hit in hits:
        entity = hit.get("entity") or hit
        scope = int(entity.get("shop_id") or 0)
        if (shop_id is None and scope != 0) or (shop_id is not None and scope not in (0, shop_id)):
            continue
        content = str(entity.get("content") or "").strip()
        key = (entity.get("source_type"), entity.get("source_id"), content)
        if not content or key in seen:
            continue
        seen.add(key)
        selected.append({**entity, "content": content})
        if len(selected) >= limit:
            break
    return selected


def merge_ranked(result_sets: list[list[dict]], shop_id: int | None, limit: int = 8) -> list[dict]:
    scores = {}
    entities = {}
    for hits in result_sets:
        for rank, entity in enumerate(select_hits(hits, shop_id, limit=20), 1):
            key = (entity.get("source_key"), entity.get("part"), entity["content"])
            scores[key] = scores.get(key, 0.0) + 1 / (60 + rank)
            entities[key] = entity
    ordered = sorted(scores, key=scores.get, reverse=True)
    return [entities[key] for key in ordered[:limit]]


def search_knowledge(shop_id: int | None, question: str) -> list[dict]:
    from app.clients.milvus_utils import get_milvus_client, create_hybrid_search_requests, hybrid_search
    from app.lm.embedding_utils import generate_embeddings

    client = get_milvus_client()
    if client is None:
        raise RuntimeError("Milvus unavailable")
    collection = collection_name()
    if not client.has_collection(collection_name=collection):
        return []
    queries = [question]
    if os.getenv("RAG_ENABLE_HYDE", "true").lower() == "true":
        from app.lm.lm_utils import get_llm_client
        hypothetical = get_llm_client().invoke(
            "写一段可能回答下列问题的简短资料，供语义检索使用。不要解释，也不要引用来源。问题：" + question
        ).content
        if hypothetical:
            queries.append(str(hypothetical)[:1000])
    result_sets = []
    for query in queries:
        vectors = generate_embeddings([query])
        requests = create_hybrid_search_requests(
            vectors["dense"][0], vectors["sparse"][0], expr=shop_scope_filter(shop_id), limit=12
        )
        result = hybrid_search(
            client, collection, requests, ranker_weights=(0.8, 0.2), norm_score=True,
            limit=12, output_fields=["source_key", "shop_id", "source_type", "source_id",
                                     "title", "file_title", "content", "part", "image_urls"]
        )
        if result is None:
            raise RuntimeError("Milvus search failed")
        result_sets.append(result[0])
    documents = merge_ranked(result_sets, shop_id, limit=10)
    if len(documents) > 1 and os.getenv("RAG_ENABLE_RERANK", "true").lower() == "true":
        from app.lm.reranker_utils import get_reranker_model
        scores = get_reranker_model().compute_score([[question, doc["content"]] for doc in documents])
        if not isinstance(scores, (list, tuple)):
            scores = list(scores) if hasattr(scores, "__iter__") else [scores]
        documents = [doc for _, doc in sorted(zip(scores, documents), key=lambda pair: pair[0], reverse=True)]
    return documents[:6]


def search_shop(shop_id: int, question: str) -> list[dict]:
    return search_knowledge(shop_id, question)
