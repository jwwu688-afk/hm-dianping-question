"""Retrieve only documents belonging to the requested shop."""

from .documents import shop_filter


def select_hits(hits: list[dict], shop_id: int, limit: int = 6) -> list[dict]:
    selected = []
    seen = set()
    for hit in hits:
        entity = hit.get("entity") or hit
        if int(entity.get("shop_id") or 0) != shop_id:
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


def search_shop(shop_id: int, question: str) -> list[dict]:
    import os
    from app.clients.milvus_utils import get_milvus_client, create_hybrid_search_requests, hybrid_search
    from app.lm.embedding_utils import generate_embeddings

    client = get_milvus_client()
    if client is None:
        raise RuntimeError("Milvus unavailable")
    collection = os.getenv("SHOP_QA_COLLECTION", "hmdp_shop_qa")
    if not client.has_collection(collection_name=collection):
        return []
    vectors = generate_embeddings([question])
    requests = create_hybrid_search_requests(
        vectors["dense"][0], vectors["sparse"][0], expr=shop_filter(shop_id), limit=12
    )
    result = hybrid_search(
        client, collection, requests, ranker_weights=(0.8, 0.2), norm_score=True,
        limit=8, output_fields=["shop_id", "source_type", "source_id", "title", "content", "part"]
    )
    if result is None:
        raise RuntimeError("Milvus search failed")
    return select_hits(result[0], shop_id)
