"""检索封装层：为评测脚本提供统一的 Milvus 检索入口。

设计说明：
- 复用项目已有的 app.clients.milvus_utils（连接单例、混合检索工具），保证评测环境与生产链路一致；
- 额外补两个「单路检索」函数（dense_only / sparse_only），因为 milvus_utils 只封装了混合检索，
  而做基线对比时必须能单独跑稠密路和稀疏路；
- 所有函数返回统一格式的 dict 列表：{chunk_id, content, title, parent_title, item_name, score}，
  屏蔽 pymilvus Hit 对象的解析细节，让评测主脚本只关心「排序后的 chunk_id 序列」。
"""

import os
import sys
from pathlib import Path

# 让脚本可以直接 `python scripts/eval/run_eval.py` 运行：把项目根目录塞进 sys.path，
# 这样才能 import app.xxx（与项目里其他脚本的 bootstrap 方式一致）
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv, find_dotenv

# 必须在 import app 配置模块之前加载 .env（配置对象在 import 时就读取环境变量）
load_dotenv(find_dotenv())

from app.clients.milvus_utils import get_milvus_client, hybrid_search, create_hybrid_search_requests

# 检索时统一返回的业务字段（与生产链路 node_search_embedding 保持一致，额外多取 title 便于调试）
OUTPUT_FIELDS = ["chunk_id", "content", "title", "parent_title", "item_name"]


# 评测目标集合：默认用生产上传链路真正写入的 hmdp_knowledge_v2（店铺文档知识库），
# 可通过环境变量 EVAL_COLLECTION 覆盖（例如切回 kb_chunks 对比旧库）
EVAL_COLLECTION = os.environ.get("EVAL_COLLECTION") or "hmdp_knowledge_v2"

# 按集合动态决定返回字段：hmdp_knowledge_v2 没有 parent_title/item_name，
# 但有 shop_id/file_title；kb_chunks 则相反。这里取两套 schema 的公共字段 + 集合特有字段
OUTPUT_FIELDS = ["chunk_id", "content", "title", "file_title", "shop_id"]


def _get_collection_name() -> str:
    """返回评测目标集合名（见上方 EVAL_COLLECTION 说明）。"""
    if not EVAL_COLLECTION:
        raise ValueError("评测目标集合名未配置")
    return EVAL_COLLECTION


def _hits_to_dicts(raw_hits) -> list[dict]:
    """把 pymilvus 返回的 hit 列表转成纯字典列表，抽平 entity 结构并补上 distance 作为 score。"""
    out = []
    for hit in raw_hits:
        # MilvusClient.search 返回的 hit 是 dict：{"id":..., "distance":..., "entity": {...}}
        entity = hit.get("entity") or {}
        out.append({
            "chunk_id": entity.get("chunk_id") or hit.get("id"),
            "content": entity.get("content") or "",
            "title": entity.get("title") or "",
            "parent_title": entity.get("parent_title") or entity.get("file_title") or "",
            "item_name": entity.get("item_name") or entity.get("file_title") or "",
            "score": hit.get("distance"),  # 注意：不同 metric 下 distance 含义不同，仅用于排序
        })
    return out


def search_dense(dense_vector: list, expr: str | None = None, limit: int = 10) -> list[dict]:
    """单路稠密向量检索（基线）：只走 dense_vector 字段，余弦相似度。

    :param dense_vector: 查询的稠密向量（BGE-M3 已归一化）
    :param expr: Milvus 过滤表达式（如 item_name 过滤），评测默认不过滤
    :param limit: 召回条数
    """
    client = get_milvus_client()
    res = client.search(
        collection_name=_get_collection_name(),
        data=[dense_vector],
        anns_field="dense_vector",
        search_params={"metric_type": "COSINE"},  # 与建库时一致
        limit=limit,
        filter=expr,  # None 表示不过滤
        output_fields=OUTPUT_FIELDS,
    )
    return _hits_to_dicts(res[0]) if res else []


def search_sparse(sparse_vector: dict, expr: str | None = None, limit: int = 10) -> list[dict]:
    """单路稀疏向量检索（基线）：只走 sparse_vector 字段，内积（IP）相似度。

    :param sparse_vector: 查询的稀疏向量，格式为 {特征索引: 权重} 的字典（generate_embeddings 的输出格式）
    """
    client = get_milvus_client()
    res = client.search(
        collection_name=_get_collection_name(),
        data=[sparse_vector],
        anns_field="sparse_vector",
        search_params={"metric_type": "IP"},  # 与建库时一致
        limit=limit,
        filter=expr,
        output_fields=OUTPUT_FIELDS,
    )
    return _hits_to_dicts(res[0]) if res else []


def search_hybrid(dense_vector: list, sparse_vector: dict,
                  weights: tuple[float, float] = (0.8, 0.2),
                  norm_score: bool = True,
                  expr: str | None = None,
                  limit: int = 10) -> list[dict]:
    """稠密+稀疏混合检索：直接复用生产代码 milvus_utils.hybrid_search（WeightedRanker 加权融合）。

    :param weights: (稠密权重, 稀疏权重)，生产当前为 (0.8, 0.2)
    :param norm_score: 归一化两路分数后再加权，与生产配置一致
    """
    # 先构造两个单路搜索请求（与生产同款封装），再交给生产版 hybrid_search 融合
    reqs = create_hybrid_search_requests(
        dense_vector=dense_vector,
        sparse_vector=sparse_vector,
        expr=expr,
        limit=limit,  # 每路各召回 limit 条，融合后取 Top limit
    )
    client = get_milvus_client()
    res = hybrid_search(
        client=client,
        collection_name=_get_collection_name(),
        reqs=reqs,
        ranker_weights=weights,
        norm_score=norm_score,
        limit=limit,
        output_fields=OUTPUT_FIELDS,
    )
    return _hits_to_dicts(res[0]) if res else []
