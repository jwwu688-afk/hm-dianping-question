"""检索封装层：为评测脚本提供统一的 Milvus 检索入口。

设计说明：
- 复用项目已有的 app.clients.milvus_utils（连接单例、混合检索工具），保证评测环境与生产链路一致；
- 额外补两个「单路检索」函数（dense_only / sparse_only），因为 milvus_utils 只封装了混合检索，
  而做基线对比时必须能单独跑稠密路和稀疏路；
- 所有函数返回统一格式的 dict 列表：{chunk_id, content, title, parent_title, item_name, score}，
  屏蔽 pymilvus Hit 对象的解析细节，让评测主脚本只关心「排序后的 chunk_id 序列」。

三个入口函数与评测配置的对应关系：
- search_dense  → 配置 dense_only（基线①，纯语义召回）；
- search_sparse → 配置 sparse_only（基线②，纯关键词/词权重召回）；
- search_hybrid → 配置 hybrid_w_50_50 / hybrid_w_80_20（Milvus 侧 WeightedRanker 融合）。
hybrid_rrf 配置不用本模块融合，而是 run_eval.py 各拉两路原始排序后本地 RRF。
"""

import os
import sys
from pathlib import Path

# 让脚本可以直接 `python scripts/eval/run_eval.py` 运行：把项目根目录塞进 sys.path，
# 这样才能 import app.xxx（与项目里其他脚本的 bootstrap 方式一致）
# parents[2]：scripts/eval/milvus_search.py 上溯两级 = services/shop-qa
ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv, find_dotenv

# 必须在 import app 配置模块之前加载 .env（配置对象在 import 时就读取环境变量）
# find_dotenv 向上查找仓库里的 .env，找不到则为空操作
# Milvus 地址 / Token / 集合名等连接信息全部来自 .env
load_dotenv(find_dotenv())

from app.clients.milvus_utils import get_milvus_client, hybrid_search, create_hybrid_search_requests

# 检索时统一返回的业务字段（与生产链路 node_search_embedding 保持一致，额外多取 title 便于调试）
# 注意：下方第二个 OUTPUT_FIELDS 赋值会覆盖它（按当前评测集合的实际 schema 重新定义）
OUTPUT_FIELDS = ["chunk_id", "content", "title", "parent_title", "item_name"]


# 评测目标集合：默认用生产上传链路真正写入的 hmdp_knowledge_v2（店铺文档知识库），
# 可通过环境变量 EVAL_COLLECTION 覆盖（例如切回 kb_chunks 对比旧库）
# 评测库必须与生产写入库一致，否则指标再好也不能代表线上效果
EVAL_COLLECTION = os.environ.get("EVAL_COLLECTION") or "hmdp_knowledge_v2"

# 按集合动态决定返回字段：hmdp_knowledge_v2 没有 parent_title/item_name，
# 但有 shop_id/file_title；kb_chunks 则相反。这里取两套 schema 的公共字段 + 集合特有字段
# _hits_to_dicts 里会对缺失字段做空串兜底，两种集合都能跑
OUTPUT_FIELDS = ["chunk_id", "content", "title", "file_title", "shop_id"]


def _get_collection_name() -> str:
    """返回评测目标集合名（见上方 EVAL_COLLECTION 说明）。"""
    # 环境变量未配置时用默认值，显式留了报错分支防止将来改成强制配置后静默跑错库
    # 防御性检查：集合名为空意味着评测结果完全不可信，宁可启动时报错
    if not EVAL_COLLECTION:
        raise ValueError("评测目标集合名未配置")
    return EVAL_COLLECTION


def _hits_to_dicts(raw_hits) -> list[dict]:
    """把 pymilvus 返回的 hit 列表转成纯字典列表，抽平 entity 结构并补上 distance 作为 score。"""
    # 统一字段语义：两套集合 schema 的字段名不同（file_title vs parent_title），
    # 这里把别名归一到 parent_title / item_name，上层评测脚本无感知
    out = []
    # 逐条抽平：hit -> dict 的转换是「评测脚本与 Milvus SDK 解耦」的关键一层
    for hit in raw_hits:
        # MilvusClient.search 返回的 hit 是 dict：{"id":..., "distance":..., "entity": {...}}
        # entity 里才是业务字段；score 用 distance 近似，只用于调试打印不影响指标
        entity = hit.get("entity") or {}
        out.append({
            # chunk_id 优先取业务字段，缺失时退回 Milvus 主键
            "chunk_id": entity.get("chunk_id") or hit.get("id"),
            "content": entity.get("content") or "",
            "title": entity.get("title") or "",
            # 两套集合的「章节/文档标题」字段名不同，这里统一
            "parent_title": entity.get("parent_title") or entity.get("file_title") or "",
            "item_name": entity.get("item_name") or entity.get("file_title") or "",
            "score": hit.get("distance"),  # 注意：不同 metric 下 distance 含义不同，仅用于排序
        })
    return out


def search_dense(dense_vector: list, expr: str | None = None, limit: int = 10) -> list[dict]:
    """单路稠密向量检索（基线）：只走 dense_vector 字段，余弦相似度。

    语义召回通路：擅长同义改写（「吃饭」≈「用餐」），但对品牌/型号等
    精确 token 可能召回语义相近却不是目标的内容——这正是消融实验的对照点。
    BGE-M3 输出的向量已归一化，余弦距离越大相似度越高。
    消融实验用它在「混合 vs 纯稠密」之间拉开差距。

    :param dense_vector: 查询的稠密向量（BGE-M3 已归一化）
    :param expr: Milvus 过滤表达式（如 item_name 过滤），评测默认不过滤
    :param limit: 召回条数
    """
    # 每个函数内部独立拿客户端（连接是模块级单例，开销可忽略）
    # metric_type 必须与建库时对 dense_vector 索引的配置一致，否则报索引类型错误
    # 余弦相似度下 distance 是「1 - 余弦值」或余弦值本身（视 Milvus 版本），仅用于排序
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
    # res[0] 是单条查询向量的结果列表；res 为空说明查询本身没返回
    return _hits_to_dicts(res[0]) if res else []


def search_sparse(sparse_vector: dict, expr: str | None = None, limit: int = 10) -> list[dict]:
    """单路稀疏向量检索（基线）：只走 sparse_vector 字段，内积（IP）相似度。

    词级匹配通路：相当于可学习的 BM25，精确 token 命中能力强，
    但对同义改写无能为力——与稠密路互补。
    消融实验用它在「混合 vs 纯稀疏」之间拉开差距。

    :param sparse_vector: 查询的稀疏向量，格式为 {特征索引: 权重} 的字典（generate_embeddings 的输出格式）
    """
    # 稀疏路走 BM25 式的词权重匹配：对品牌词、型号词等精确 token 更敏感
    # sparse_vector 索引的 metric 是 IP（内积），权重即相似度
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

    与 run_eval.py 的 hybrid_rrf 配置的区别：这里是 Milvus 服务端融合
    （两路分数加权求和，一次请求完成）；RRF 是客户端按排名倒数融合，
    两者原理不同，正是评测要对比的「融合方式」变量。
    复用生产代码保证评测结果能代表线上真实行为。

    :param weights: (稠密权重, 稀疏权重)，生产当前为 (0.8, 0.2)
    :param norm_score: 归一化两路分数后再加权，与生产配置一致
    """
    # 先构造两个单路搜索请求（与生产同款封装），再交给生产版 hybrid_search 融合
    # WeightedRanker 在服务端完成融合，一次请求返回最终排序（比客户端融合省一次往返）
    # 每路各召回 limit 条：融合器在两路各自的结果里做加权排序，最终仍取 limit 条
    # expr 同样支持店铺过滤；评测默认 None 全库检索
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
    # 与单路函数同样的取首结果 + 字典化处理
    # 融合排序后的 score 是加权分数，与单路 distance 不在一个量纲，仅供调试
    return _hits_to_dicts(res[0]) if res else []
