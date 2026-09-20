"""Unified Milvus index for shops, blogs and uploaded documents."""

import json
import os
import re

from .documents import build_shop_documents


def collection_name() -> str:
    return os.getenv("KNOWLEDGE_COLLECTION", "hmdp_knowledge_v2")


def source_filter(source_key: str) -> str:
    if not re.fullmatch(r"(?:shop|blog|document):[A-Za-z0-9_-]+", source_key):
        raise ValueError("invalid source key")
    return f'source_key == "{source_key}"'


def shop_scope_filter(shop_id: int | None) -> str:
    if shop_id is None:
        return "shop_id == 0"
    if isinstance(shop_id, bool) or not isinstance(shop_id, int) or shop_id <= 0:
        raise ValueError("shop_id must be positive")
    return f"shop_id in [0, {shop_id}]"


def image_urls_json(urls: list[str], max_length: int = 4096) -> str:
    kept = []
    for url in urls:
        candidate = json.dumps([*kept, str(url)], ensure_ascii=False)
        if len(candidate.encode("utf-8")) > max_length:
            break
        kept.append(str(url))
    return json.dumps(kept, ensure_ascii=False)


def replace_source(client, collection: str, source_key: str, rows: list[dict]) -> None:
    """Replace exactly one source; uploaded documents survive shop/blog refreshes."""
    client.delete(collection_name=collection, filter=source_filter(source_key))
    if rows:
        client.insert(collection_name=collection, data=rows)


def ensure_collection(client, collection: str, dimension: int) -> None:
    from pymilvus import DataType

    if client.has_collection(collection_name=collection):
        return
    schema = client.create_schema(auto_id=True, enable_dynamic_fields=False)
    schema.add_field(field_name="chunk_id", datatype=DataType.INT64, is_primary=True, auto_id=True)
    schema.add_field(field_name="source_key", datatype=DataType.VARCHAR, max_length=128)
    schema.add_field(field_name="source_type", datatype=DataType.VARCHAR, max_length=16)
    schema.add_field(field_name="source_id", datatype=DataType.VARCHAR, max_length=64)
    schema.add_field(field_name="shop_id", datatype=DataType.INT64)
    schema.add_field(field_name="title", datatype=DataType.VARCHAR, max_length=512)
    schema.add_field(field_name="file_title", datatype=DataType.VARCHAR, max_length=512)
    schema.add_field(field_name="content", datatype=DataType.VARCHAR, max_length=4096)
    schema.add_field(field_name="image_urls", datatype=DataType.VARCHAR, max_length=4096)
    schema.add_field(field_name="part", datatype=DataType.INT64)
    schema.add_field(field_name="dense_vector", datatype=DataType.FLOAT_VECTOR, dim=dimension)
    schema.add_field(field_name="sparse_vector", datatype=DataType.SPARSE_FLOAT_VECTOR)
    indexes = client.prepare_index_params()
    indexes.add_index(field_name="dense_vector", metric_type="COSINE", index_type="AUTOINDEX")
    indexes.add_index(field_name="sparse_vector", metric_type="IP", index_type="SPARSE_INVERTED_INDEX")
    client.create_collection(collection_name=collection, schema=schema, index_params=indexes)


def index_source(source_key: str, documents: list[dict]) -> int:
    from app.clients.milvus_utils import get_milvus_client
    from app.lm.embedding_utils import generate_embeddings

    source_filter(source_key)
    if not documents:
        raise ValueError("source has no text")
    client = get_milvus_client()
    if client is None:
        raise RuntimeError("Milvus unavailable")
    collection = collection_name()
    rows = []
    for start in range(0, len(documents), 5):
        batch = documents[start:start + 5]
        texts = [str(doc["content"]).strip()[:4096] for doc in batch]
        if any(not text for text in texts):
            raise ValueError("empty document chunk")
        vectors = generate_embeddings(texts)
        if start == 0:
            ensure_collection(client, collection, len(vectors["dense"][0]))
        for number, doc in enumerate(batch):
            rows.append({
                "source_key": source_key,
                "source_type": str(doc["source_type"]),
                "source_id": str(doc["source_id"]),
                "shop_id": int(doc.get("shop_id") or 0),
                "title": str(doc.get("title") or "")[:512],
                "file_title": str(doc.get("file_title") or "")[:512],
                "content": texts[number],
                "image_urls": image_urls_json(doc.get("image_urls") or []),
                "part": int(doc.get("part", start + number)),
                "dense_vector": vectors["dense"][number],
                "sparse_vector": vectors["sparse"][number],
            })
    replace_source(client, collection, source_key, rows)
    return len(rows)


def delete_source(source_key: str) -> None:
    from app.clients.milvus_utils import get_milvus_client

    client = get_milvus_client()
    if client is None:
        raise RuntimeError("Milvus unavailable")
    collection = collection_name()
    if client.has_collection(collection_name=collection):
        replace_source(client, collection, source_key, [])


def index_shop(shop: dict, blogs: list[dict]) -> int:
    """Refresh shop and each blog independently, preserving uploaded files."""
    documents = build_shop_documents(shop, blogs)
    shop_id = int(shop["id"])
    count = index_source(f"shop:{shop_id}", [documents[0]])
    by_blog = {}
    for doc in documents[1:]:
        by_blog.setdefault(int(doc["source_id"]), []).append(doc)
    for blog_id, chunks in by_blog.items():
        count += index_source(f"blog:{blog_id}", chunks)
    return count
