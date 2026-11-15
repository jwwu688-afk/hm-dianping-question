# =============================================================================
# Milvus 客户端与检索工具模块
# =============================================================================
# 本模块是整个 RAG 服务与 Milvus 向量库交互的统一入口，封装四类能力：
#   1. 客户端单例（get_milvus_client）：全服务共享一个连接，避免重复建连；
#   2. 主键批量回查（fetch_chunks_by_chunk_ids）：按 chunk_id 补全切片内容，
#      服务于「只有 ID 没有正文」的场景（如 RRF 融合后需要还原实体字段）；
#   3. 混合检索请求构建（create_hybrid_search_requests）：把一次查询拆成
#      稠密（COSINE）+ 稀疏（IP）两个 ANN 请求，供 WeightedRanker 融合；
#   4. 混合搜索执行（hybrid_search）：WeightedRanker 归一化加权融合，是
#      「混合检索较单路更准」这一实测结论的生产实现。
# 下游主要使用方：query_process 各检索节点、import_process 入库节点、
# 以及 scripts/eval 评测框架（通过生产同款路径保证评测即线上行为）。
# 消融实测结论（详见 scripts/eval/eval_out/comparison_report.md）：
#   - 混合检索较纯稠密 hit@1 +3.1~10pp（跨两轮语料方向一致）；
#   - 本模块 hybrid_search 与生产检索节点共用同一代码路径，评测即线上行为。
# =============================================================================
import os
from pymilvus import MilvusClient, AnnSearchRequest, WeightedRanker
from app.conf.milvus_config import milvus_config
from app.core.logger import logger

# 全局Milvus客户端实例，实现单例复用
_milvus_client = None


def get_milvus_client():
    """
    Milvus客户端单例获取方法
    实现客户端连接复用，避免重复创建连接消耗资源
    :return: MilvusClient实例，连接失败返回None
    """
    try:
        global _milvus_client
        # 单例判断：未初始化则创建新连接
        if _milvus_client is None:
            milvus_uri = milvus_config.milvus_url
            # 校验Milvus连接地址配置：缺失时返回 None 而非抛异常，
            # 让调用方有机会走降级逻辑（如直接返回空结果）
            if not milvus_uri:
                logger.error("Milvus客户端连接失败：缺少MILVUS_URL环境变量配置")
                return None
            # 初始化Milvus客户端：URI 形如 tcp://localhost:19530，
            # 失败会抛异常被下方 except 捕获并记录（返回 None 交由上层处理）
            _milvus_client = MilvusClient(uri=milvus_uri)
            logger.info("Milvus客户端连接成功")
        return _milvus_client
    except Exception as e:
        logger.error(f"Milvus客户端连接异常：{str(e)}", exc_info=True)
        return None


def _coerce_int64_ids(ids):
    """
    转换chunk_id为Milvus要求的INT64类型（主键字段schema为INT64）
    过滤无效ID，分离可转换/不可转换的ID
    :param ids: 待转换的chunk_id列表
    :return: 元组(ok_ids, bad_ids)，ok_ids为可转换的int64类型ID列表，bad_ids为无效ID列表
    """
    # 设计动机：RRF 融合结果只携带 chunk_id，业务字段需要按主键回查补全；
    # 若不剥离无效 ID，整批 IN 查询会因单个非法值而全部失败
    ok, bad = [], []
    for x in (ids or []):
        # None 直接跳过：主键不允许为空，空值通常来自上游字段缺失
        if x is None:
            continue
        try:
            # 转换成功 → 归入有效列表；int() 兼容 '123' 字符串与 numpy 整型
            ok.append(int(x))
        except Exception:
            # 转换失败（如 UUID 字符串）→ 归入无效列表，由调用方决定跳过或告警
            bad.append(x)
    return ok, bad


def fetch_chunks_by_chunk_ids(
        client,
        collection_name: str,
        chunk_ids,
        *,
        output_fields=None,
        batch_size: int = 100,
):
    """
    通过chunk_id主键批量查询Milvus中的切片数据
    用于补全「仅拥有chunk_id无文本内容」场景的切片信息
    优先使用get方法（主键直查，性能最优），失败则回退query过滤查询
    :param client: MilvusClient实例
    :param collection_name: 集合名称
    :param chunk_ids: 待查询的chunk_id列表
    :param output_fields: 需要返回的字段列表，默认返回核心切片字段
    :param batch_size: 分批查询大小，避免单次查询数据量过大，默认100
    :return: List[dict]，Milvus实体字典列表，查询失败返回空列表
    """
    # 前置校验：客户端/集合名无效直接返回空
    if client is None:
        return []
    if not collection_name:
        return []
    # 默认返回字段：核心切片标识与内容字段
    if output_fields is None:
        output_fields = ["chunk_id", "content", "title", "parent_title", "item_name"]

    # 转换ID为INT64类型，分离有效/无效ID
    ok_ids, bad_ids = _coerce_int64_ids(chunk_ids)
    if bad_ids:
        # 记录无效ID，跳过查询
        logger.warning(f"存在无法转换为INT64的chunk_id，将跳过查询：{bad_ids}")

    # 无有效ID直接返回空
    if not ok_ids:
        return []

    results = []
    # 分批查询：按batch_size切分有效ID，循环查询
    # ——一次 in 表达式的 ID 过多会导致表达式过长/超时，分批是稳定性兜底
    # 分批大小默认 100：兼顾单批往返开销与表达式长度上限
    for i in range(0, len(ok_ids), batch_size):
        batch = ok_ids[i: i + batch_size]

        # 方式1：优先使用主键get方法查询（性能最优）
        # get 走主键点查，等价于 SQL 的主键 IN 查询，延迟最低
        if hasattr(client, "get"):
            try:
                got = client.get(collection_name=collection_name, ids=batch, output_fields=output_fields)
                if got:
                    results.extend(got)
                # 主键 get 成功即跳过本批 fallback，继续处理下一批
                continue
            except Exception as e:
                # get 失败（如 SDK 版本兼容问题）记录告警并降级，不中断整轮批量回查
                logger.warning(f"Milvus get方法查询失败，将回退至query方法：{str(e)}")

        # 方式2：get方法失败，回退使用filter过滤查询
        # 表达式 `chunk_id in [...]` 语义等价，兼容仅支持 query 的客户端形态
        try:
            expr = f"chunk_id in [{', '.join(str(x) for x in batch)}]"
            q = client.query(collection_name=collection_name, filter=expr, output_fields=output_fields)
            if q:
                results.extend(q)
        except Exception as e:
            logger.error(f"Milvus query方法批量查询chunk_id失败：{str(e)}", exc_info=True)

    return results


def create_hybrid_search_requests(dense_vector, sparse_vector, dense_params=None, sparse_params=None, expr=None,
                                  limit=5):
    """
    构建Milvus混合搜索请求对象
    分别创建稠密/稀疏向量的搜索请求，用于后续混合搜索融合
    :param dense_vector: 文本生成的稠密向量
    :param sparse_vector: 文本生成的稀疏向量
    :param dense_params: 稠密向量搜索参数，默认使用余弦相似度
    :param sparse_params: 稀疏向量搜索参数，默认使用内积相似度
    :param expr: 搜索过滤表达式，用于精准筛选数据
    :param limit: 单向量搜索返回结果数量，默认5
    :return: 搜索请求列表，包含[dense_req, sparse_req]
    """
    # 稠密向量默认搜索参数：余弦相似度（COSINE），适配BGE-M3稠密向量并与建库参数保持一致
    if dense_params is None:
        dense_params = {"metric_type": "COSINE"}
    # 稀疏向量默认搜索参数：内积（IP），适配BGE-M3稀疏向量
    if sparse_params is None:
        sparse_params = {"metric_type": "IP"}

    # 构建稠密向量搜索请求，关联Milvus的dense_vector字段 近似最近邻（ANN）检索请求的核心类
    # 注意：metric_type 必须与建库时该字段的索引度量一致（COSINE），否则检索报错或结果失真
    dense_req = AnnSearchRequest(
        data=[dense_vector],
        anns_field="dense_vector",
        param=dense_params,
        expr=expr,
        limit=limit
    )

    # AnnSearchRequest是一个用于ANN（近似最近邻）搜索请求的数据类（Python class）。封装一次向量相似度搜索所需的所有参数
    # 构建稀疏向量搜索请求，关联Milvus的sparse_vector字段
    # 稀疏向量以 {特征索引: 权重} 字典传入，度量用 IP（内积）——归一化后内积等价于余弦
    sparse_req = AnnSearchRequest(
        data=[sparse_vector],
        anns_field="sparse_vector",
        param=sparse_params,
        expr=expr,
        limit=limit
    )

    return [dense_req, sparse_req]


def hybrid_search(client, collection_name, reqs, ranker_weights=(0.5, 0.5), norm_score=False, limit=5,
                  output_fields=None, search_params=None):
    """
    执行Milvus稠密+稀疏向量混合搜索
    基于WeightedRanker实现双向量搜索结果加权融合，提升检索准确性
    :param client: MilvusClient实例
    :param collection_name: 集合名称
    :param reqs: 搜索请求列表，固定为[dense_req, sparse_req]
    :param ranker_weights: 加权融合权重，默认(0.5,0.5)，依次对应稠密/稀疏向量
    :param norm_score: 是否归一化评分后再融合，避免评分量级差异导致权重失效
    :param limit: 混合搜索最终返回结果数量，默认5
    :param output_fields: 需要返回的字段列表，默认返回item_name
    :param search_params: 搜索参数，如ef/topk等，默认None
    :return: 混合搜索结果列表，搜索失败返回None
    """
    try:
        # 初始化加权排名器：按权重融合稠密/稀疏向量的搜索结果
        # norm_score=True：先将两个向量评分归一化到0~1区间，再加权计算
        # 实测口径：生产与评测均使用 (0.8, 0.2)——稠密管语义、稀疏管关键词锚定
        rerank = WeightedRanker(ranker_weights[0], ranker_weights[1], norm_score=norm_score)

        # 默认返回字段：文档标识字段
        if output_fields is None:
            output_fields = ["item_name"]

        # 执行混合搜索：融合稠密+稀疏向量结果，按权重重新排序
        # 服务端流程：两路各自召回 limit 条 → WeightedRanker 归一化加权 → 去重合并取 Top limit
        # 消融实测：混合方案 hit@1 较纯稠密 +3.1~10pp（见 scripts/eval 评测报告）
        res = client.hybrid_search(
            collection_name=collection_name,
            reqs=reqs,
            ranker=rerank,
            limit=limit,
            output_fields=output_fields,
            search_params=search_params
        )

        # 返回结构：res 是按查询批次的二维列表（此处单查询取 res[0]），
        # 每个元素为 {"id": 主键, "distance": 融合分, "entity": {业务字段}}
        # distance 已按 ranker 归一化加权，可直接作为相关性排序依据
        logger.info(f"Milvus混合搜索完成，集合[{collection_name}]共检索到{len(res[0])}条结果")
        return res
    except Exception as e:
        # 失败返回 None（而非抛异常）：调用方（检索节点/评测脚本）据此走降级路径
        # 如返回空结果或回退单路检索，避免单次检索故障拖垮整个问答链路
        logger.error(f"Milvus混合搜索执行失败，集合[{collection_name}]：{str(e)}", exc_info=True)
        return None