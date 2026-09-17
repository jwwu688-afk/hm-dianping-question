"""Index Black Horse Review shop records in a separate Milvus collection."""

import os

from .documents import build_shop_documents, shop_filter


def index_shop(shop: dict, blogs: list[dict]) -> int:
    from pymilvus import DataType
    from app.clients.milvus_utils import get_milvus_client
    from app.lm.embedding_utils import generate_embeddings

    documents = build_shop_documents(shop, blogs)
    client = get_milvus_client()
    if client is None:
        raise RuntimeError("Milvus unavailable")
    collection = os.getenv("SHOP_QA_COLLECTION", "hmdp_shop_qa")
    vectors = generate_embeddings([doc["content"] for doc in documents])
    if not client.has_collection(collection_name=collection):
        schema = client.create_schema(auto_id=True, enable_dynamic_fields=False)
        schema.add_field(field_name="chunk_id", datatype=DataType.INT64, is_primary=True, auto_id=True)
        schema.add_field(field_name="shop_id", datatype=DataType.INT64)
        schema.add_field(field_name="source_id", datatype=DataType.INT64)
        schema.add_field(field_name="source_type", datatype=DataType.VARCHAR, max_length=16)
        schema.add_field(field_name="title", datatype=DataType.VARCHAR, max_length=512)
        schema.add_field(field_name="content", datatype=DataType.VARCHAR, max_length=4096)
        schema.add_field(field_name="part", datatype=DataType.INT64)
        schema.add_field(field_name="dense_vector", datatype=DataType.FLOAT_VECTOR, dim=len(vectors["dense"][0]))
        schema.add_field(field_name="sparse_vector", datatype=DataType.SPARSE_FLOAT_VECTOR)
        indexes = client.prepare_index_params()
        indexes.add_index(field_name="dense_vector", metric_type="COSINE", index_type="AUTOINDEX")
        indexes.add_index(field_name="sparse_vector", metric_type="IP", index_type="SPARSE_INVERTED_INDEX")
        client.create_collection(collection_name=collection, schema=schema, index_params=indexes)
    rows = []
    for number, doc in enumerate(documents):
        rows.append({**doc, "part": doc.get("part", 0), "title": doc["title"][:512],
                     "content": doc["content"][:4096], "dense_vector": vectors["dense"][number],
                     "sparse_vector": vectors["sparse"][number]})
    client.delete(collection_name=collection, filter=shop_filter(int(shop["id"])))
    client.insert(collection_name=collection, data=rows)
    return len(rows)
