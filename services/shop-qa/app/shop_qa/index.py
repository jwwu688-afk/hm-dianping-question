"""Unified Milvus index for shops, blogs and uploaded documents."""
# 模块职责：Milvus 索引层——统一管理店铺（shop）、探店笔记（blog）、上传文档（document）
# 三类语料在同一集合中的写入、替换与删除。
# 设计要点：
# 1. 每个来源有独立 source_key（如 "shop:1"、"blog:5"、"document:<uuid>"），
#    更新时按 source_key 整源删除再插入，实现幂等刷新；
# 2. 店铺/笔记刷新不影响已上传文档（上传文档按独立 source_key 存活）；
# 3. schema 为稠密（COSINE）+ 稀疏（IP）双向量，服务 BGE-M3 混合检索。

import json
import os
import re
# json 用于序列化 image_urls；re 用于 source_key 格式校验。

from .documents import build_shop_documents
# build_shop_documents：把店铺/笔记结构化记录转为可检索文档（纯转换，无 IO）。


def collection_name() -> str:
    """Milvus 集合名：环境变量 KNOWLEDGE_COLLECTION 可覆盖，默认 hmdp_knowledge_v2。

    独立成函数而非常量：集合名可能在运行期被环境变量切换（灰度/多环境隔离）。
    """
    return os.getenv("KNOWLEDGE_COLLECTION", "hmdp_knowledge_v2")


def source_filter(source_key: str) -> str:
    """把 source_key 转成 Milvus 过滤表达式，同时校验其格式合法性。

    格式要求：shop|blog|document 前缀 + [A-Za-z0-9_-] 标识符；
    非法 source_key 直接抛 ValueError，杜绝把用户输入拼进过滤表达式造成注入。
    """
    # fullmatch 校验：表达式由内部 source_key 拼接而来，先验格式再拼接。
    if not re.fullmatch(r"(?:shop|blog|document):[A-Za-z0-9_-]+", source_key):
        raise ValueError("invalid source key")
    return f'source_key == "{source_key}"'


def shop_scope_filter(shop_id: int | None) -> str:
    """生成店铺范围的过滤表达式（检索用）。

    None → 只搜公共语料（shop_id == 0）；
    正整数 → 公共语料 + 该店铺（shop_id in [0, N]）；
    非法值（bool/非 int/非正数）直接拒绝——bool 是 int 子类，必须显式排除。
    表达式拼进 Milvus 检索前已完成类型校验，无注入风险。
    """
    # None 表示全局检索：限定 shop_id == 0 的平台资料。
    if shop_id is None:
        return "shop_id == 0"
    # 防御：bool 是 int 子类（True == 1），必须先排除再判 int。
    if isinstance(shop_id, bool) or not isinstance(shop_id, int) or shop_id <= 0:
        raise ValueError("shop_id must be positive")
    # 店铺检索恒附带公共语料 0：店铺问答可引用平台级背景知识。
    return f"shop_id in [0, {shop_id}]"


def image_urls_json(urls: list[str], max_length: int = 4096) -> str:
    """把图片 URL 列表序列化为 JSON 字符串（Milvus VARCHAR 字段）。

    关键点：逐个尝试追加 URL，累计字节数超过 max_length 即截断——
    Milvus VARCHAR 超长会报错，这里提前裁剪保证写入必成功；
    按字节（UTF-8）而非字符数计算，中文/转义才不会被低估。
    """
    kept = []
    for url in urls:
        # 用「已保留列表 + 新 URL」整体序列化来试长度，保证最终 JSON 合法。
        # 一旦超限立即 break，后续 URL 全部丢弃（不跳过中间项硬塞）。
        candidate = json.dumps([*kept, str(url)], ensure_ascii=False)
        if len(candidate.encode("utf-8")) > max_length:
            break
        kept.append(str(url))
    return json.dumps(kept, ensure_ascii=False)


def replace_source(client, collection: str, source_key: str, rows: list[dict]) -> None:
    """Replace exactly one source; uploaded documents survive shop/blog refreshes.
    按 source_key 整源替换：先删后插，写入 0 行等价于纯删除。

    幂等性：同一 (source_key, rows) 重复执行结果一致；
    隔离性：删除范围限定在单个 source_key，店铺/笔记刷新不会波及上传文档。
    本函数不做格式校验，过滤表达式的合法性由 source_filter 兜底。
    """
    # 先删旧向量：即使后续插入失败，也只造成该源短暂缺失而非脏数据并存。
    # delete 的 filter 来自 source_filter，已含格式校验。
    client.delete(collection_name=collection, filter=source_filter(source_key))
    # rows 非空才插入；空 rows 是删除语义（文档删除、笔记下线都会走到这里）。
    if rows:
        client.insert(collection_name=collection, data=rows)


def ensure_collection(client, collection: str, dimension: int) -> None:
    """确保集合存在；不存在则按统一 schema 创建（含稠密/稀疏双索引）。

    schema 设计：auto_id 主键免生成，source_key 是业务主键（整源替换的过滤依据），
    dense_vector 维度随首个 embedding 批次动态确定（BGE-M3 为 1024）。
    已存在时直接返回，幂等可重复调用。
    由 index_source 在首个批次时调用，调用方无需提前建集合。
    """
    from pymilvus import DataType

    # 已存在：沿用旧 schema，不做迁移（版本升级另行处理）。
    # 多进程并发建集合时 Milvus 端会幂等处理，重复 create 会被拒绝。
    if client.has_collection(collection_name=collection):
        return
    # 关闭动态字段：字段全部显式声明，避免检索时字段缺失歧义。
    schema = client.create_schema(auto_id=True, enable_dynamic_fields=False)
    # 主键自增，业务不关心 chunk_id 具体值。
    schema.add_field(field_name="chunk_id", datatype=DataType.INT64, is_primary=True, auto_id=True)
    # 业务主键：shop:1 / blog:5 / document:<uuid>，整源删除与刷新的依据。
    # 128 长度足够容纳 UUID 拼接，且为将来更多前缀留余量。
    schema.add_field(field_name="source_key", datatype=DataType.VARCHAR, max_length=128)
    # 语料类型：shop / blog / document 三选一。
    schema.add_field(field_name="source_type", datatype=DataType.VARCHAR, max_length=16)
    # 类型内的来源 ID：店铺 ID / 笔记 ID / 文档 UUID。
    schema.add_field(field_name="source_id", datatype=DataType.VARCHAR, max_length=64)
    # 归属店铺：公共语料为 0，检索时用它做范围过滤。
    schema.add_field(field_name="shop_id", datatype=DataType.INT64)
    # 展示标题（chunk 标题或店铺名）与原始文件名（上传文档用）。
    schema.add_field(field_name="title", datatype=DataType.VARCHAR, max_length=512)
    schema.add_field(field_name="file_title", datatype=DataType.VARCHAR, max_length=512)
    # 正文内容：与 embedding 上限一致取 4096。
    schema.add_field(field_name="content", datatype=DataType.VARCHAR, max_length=4096)
    # 图片外链 JSON 串（可能有中文转义，按 4096 字节预留）。
    schema.add_field(field_name="image_urls", datatype=DataType.VARCHAR, max_length=4096)
    # 分段序号：同一 source 内的 chunk 顺序，供引用定位。
    schema.add_field(field_name="part", datatype=DataType.INT64)
    # 稠密向量：BGE-M3 输出，维度由首个批次实际长度决定。
    schema.add_field(field_name="dense_vector", datatype=DataType.FLOAT_VECTOR, dim=dimension)
    # 稀疏向量：BGE-M3 的 sparse 分量，关键词匹配用。
    schema.add_field(field_name="sparse_vector", datatype=DataType.SPARSE_FLOAT_VECTOR)
    # 稠密索引：COSINE 相似度 + AUTOINDEX（Milvus 自动选索引类型）。
    indexes = client.prepare_index_params()
    indexes.add_index(field_name="dense_vector", metric_type="COSINE", index_type="AUTOINDEX")
    # 稀疏索引：内积 + 稀疏倒排索引，是稀疏检索的标准配置。
    indexes.add_index(field_name="sparse_vector", metric_type="IP", index_type="SPARSE_INVERTED_INDEX")
    client.create_collection(collection_name=collection, schema=schema, index_params=indexes)


def index_source(source_key: str, documents: list[dict]) -> int:
    """把一批文档向量化并整源写入 Milvus，返回写入行数。

    流程：source_key 合法性校验 → 客户端可用性校验 → 按 5 条一批
    向量化（首个批次顺带建集合）→ 组装行 → replace_source 整源替换。
    文本先截断到 4096 字符对齐 schema 上限；空文本直接报错拒绝入库。
    供 document_pipeline（文档入库）与 index_shop（店铺刷新）两条链路复用。
    """
    # 延迟导入：Milvus 客户端与 embedding 依赖重，非索引场景不加载。
    from app.clients.milvus_utils import get_milvus_client
    from app.lm.embedding_utils import generate_embeddings

    # 先做格式校验（不通过会抛 ValueError），避免非法 key 进入下游。
    source_filter(source_key)
    # 空来源没有入库意义：删除走 delete_source，这里强制拒绝。
    if not documents:
        raise ValueError("source has no text")
    client = get_milvus_client()
    if client is None:
        raise RuntimeError("Milvus unavailable")
    collection = collection_name()
    rows = []
    # 分批向量化：每批 5 条，控制单次 embedding 请求体积与失败重试粒度。
    # 一批失败整体抛出，已生成的行不写入（replace_source 在最后才调用）。
    for start in range(0, len(documents), 5):
        batch = documents[start:start + 5]
        # 正文截断 4096：与 schema 的 VARCHAR 上限和 embedding 模型窗口对齐。
        texts = [str(doc["content"]).strip()[:4096] for doc in batch]
        # 截断后为空的 chunk 说明原文只有空白，拒绝静默丢弃以防索引缺漏。
        if any(not text for text in texts):
            raise ValueError("empty document chunk")
        # 批量向量化：dense/sparse 两个分量按批返回。
        vectors = generate_embeddings(texts)
        # 首个批次确定向量维度后顺带建集合（幂等，已存在则跳过）。
        if start == 0:
            ensure_collection(client, collection, len(vectors["dense"][0]))
        for number, doc in enumerate(batch):
            # 逐字段组装行：长度/类型对齐 schema，防止超限写入失败。
            rows.append({
                "source_key": source_key,
                "source_type": str(doc["source_type"]),
                "source_id": str(doc["source_id"]),
                # shop_id 缺省按公共语料 0 处理。
                "shop_id": int(doc.get("shop_id") or 0),
                "title": str(doc.get("title") or "")[:512],
                "file_title": str(doc.get("file_title") or "")[:512],
                "content": texts[number],
                # 图片 URL 序列化时按字节裁剪，保证不超过 4096。
                "image_urls": image_urls_json(doc.get("image_urls") or []),
                # part 显式传入优先；缺省用全局序号兜底，保证分段有序。
                "part": int(doc.get("part", start + number)),
                "dense_vector": vectors["dense"][number],
                "sparse_vector": vectors["sparse"][number],
            })
    # 整源替换写入：先删旧 source_key 的向量再插入新行，实现幂等刷新。
    replace_source(client, collection, source_key, rows)
    return len(rows)


def delete_source(source_key: str) -> None:
    """删除一个来源的全部向量（文档删除时调用）。

    集合不存在时直接返回（冷启动下无东西可删）；
    key 格式非法会被 source_filter 拒绝，防御注入。
    删除走 replace_source 空行写入，与写入路径共用同一套过滤校验。
    """
    from app.clients.milvus_utils import get_milvus_client

    client = get_milvus_client()
    if client is None:
        raise RuntimeError("Milvus unavailable")
    collection = collection_name()
    # 集合未建过 = 该来源从未入库，视为删除成功。
    if client.has_collection(collection_name=collection):
        replace_source(client, collection, source_key, [])


def index_shop(shop: dict, blogs: list[dict]) -> int:
    """Refresh shop and each blog independently, preserving uploaded files.
    刷新一个店铺的索引：店铺主文档 + 每篇笔记各自独立 source_key 入库。

    按来源拆分入库的好处：某篇笔记更新只影响其自身 source_key，
    其余笔记与已上传文档的向量保持不动，避免全量重建的开销。
    返回写入的总行数。
    """
    # 纯转换：生成 1 条店铺主文档 + N 条笔记 chunk。
    # 不含已上传文档——它们的 source_key 独立，刷新店铺不需要重建。
    documents = build_shop_documents(shop, blogs)
    shop_id = int(shop["id"])
    # 第一条是店铺主文档，独立 source_key（shop:<id>）。
    count = index_source(f"shop:{shop_id}", [documents[0]])
    # 其余是笔记 chunk：按笔记 ID 分组，每组一个 source_key（blog:<id>）。
    by_blog = {}
    for doc in documents[1:]:
        by_blog.setdefault(int(doc["source_id"]), []).append(doc)
    for blog_id, chunks in by_blog.items():
        count += index_source(f"blog:{blog_id}", chunks)
    return count
