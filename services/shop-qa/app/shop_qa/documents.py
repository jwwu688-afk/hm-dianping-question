"""Pure conversion of shop records into source-traceable search documents."""
# 模块职责：店铺结构化记录 → 检索文档的纯转换层（无任何 IO/网络依赖）。
# build_shop_documents 产出两类文档：
#   1. 店铺主文档（source_type=shop）：字段拼成 facts 文本，一条入库；
#   2. 探店笔记 chunk（source_type=blog）：正文按段落聚合、超长截断，逐段入库。
# 每条文档都带 source_id（店铺/笔记 ID），保证答案引用可回溯到具体来源；
# 纯函数设计使其可脱离 Milvus/MySQL 独立单测。


def shop_filter(shop_id: int) -> str:
    """生成「仅限单店铺」的过滤表达式（区别于 index.shop_scope_filter 的含公共语料版本）。

    只允许正整数；bool 是 int 子类，必须显式排除，
    否则 True 会被当成 1 静默放行。
    """
    # 先判 bool 再判 int：isinstance(True, int) 为 True 的语言陷阱。
    if isinstance(shop_id, bool) or not isinstance(shop_id, int) or shop_id <= 0:
        raise ValueError("shop_id must be a positive integer")
    return f"shop_id == {shop_id}"


def build_shop_documents(shop: dict, blogs: list[dict]) -> list[dict]:
    """把一条店铺记录与其笔记列表转为可入库文档列表。

    返回首元素是店铺主文档（聚合字段事实），其余是笔记 chunk；
    chunk 聚合规则：段落累积到 700 字符即切一块，单段超 700 强制硬切，
    保证每块不超过 embedding 模型的理想长度，同时保留段落完整性。
    非本店笔记（shop_id 不匹配）与空正文笔记直接跳过。
    """
    # 店铺 ID 是文档归属的核心字段，非法值直接报错。
    # shop_id 也出现在过滤表达式里，必须先验干净。
    shop_id = int(shop["id"])
    if shop_id <= 0:
        raise ValueError("invalid shop id")
    # 展示字段按固定顺序组织（标签, 键名）；评分满分 50 沿用项目约定。
    # 字段集合与 export_markdown.render_shop_markdown 保持一致。
    fields = [
        ("店名", "name"), ("商圈", "area"), ("地址", "address"),
        ("人均价格（元）", "avg_price"), ("评分（满分50）", "score"),
        ("营业时间", "open_hours"),
    ]
    # 把字段值拼成「标签：值」行，空值字段不输出——店铺主文档的内容源。
    # str() 包一层保证数字类型（价格/评分）也能安全拼接。
    facts = [f"{label}：{shop[key]}" for label, key in fields if shop.get(key) is not None and str(shop[key]).strip()]
    # 首条文档：店铺主记录。source_id 即店铺 ID，引用时可定位回 tb_shop。
    # content 用换行连接 facts：保留「标签：值」的行结构便于模型阅读。
    docs = [{"shop_id": shop_id, "source_type": "shop", "source_id": shop_id,
             "title": str(shop.get("name") or "店铺资料"), "content": "\n".join(facts)}]
    for blog in blogs:
        # 只处理本店笔记：shop_id 不匹配的直接跳过（调用方可能传入全量笔记）。
        if int(blog.get("shop_id") or 0) != shop_id:
            continue
        # 空正文笔记无检索价值，跳过。
        content = str(blog.get("content") or "").strip()
        if not content:
            continue
        # 标题缺失用「探店笔记」兜底，保证 chunk 标题字段始终非空。
        title = str(blog.get("title") or "探店笔记").strip()
        # Keep short passages searchable while preserving the source blog ID.
        # 按行拆段落：空行段落丢弃，保留有内容的段。
        # 700 字符阈值约为 embedding 模型有效窗口的一半，兼顾召回精度与上下文完整。
        paragraphs = [p.strip() for p in content.splitlines() if p.strip()]
        chunks = []
        current = ""
        for paragraph in paragraphs:
            # 当前块已有内容且新段落放不下：先收束当前块再开新块。
            if current and len(current) + len(paragraph) > 700:
                chunks.append(current)
                current = ""
            # 单段超 700：按 700 硬切成多个子段，避免超长段撑爆单 chunk。
            while len(paragraph) > 700:
                chunks.append(paragraph[:700])
                paragraph = paragraph[700:]
            # 硬切后的剩余部分并入当前块；已有多段时用换行连接。
            current = f"{current}\n{paragraph}".strip() if current else paragraph
        # 收尾：最后一块若还有内容也入库。
        if current:
            chunks.append(current)
        # 逐块生成文档：part 是块序号，source_id 始终是笔记 ID（可回溯到 tb_blog）。
        # 同一篇笔记的所有 chunk 共享 source_id，检索结果可按 part 还原顺序。
        for part, chunk in enumerate(chunks):
            docs.append({"shop_id": shop_id, "source_type": "blog", "source_id": int(blog["id"]),
                         "title": title, "part": part, "content": chunk})
    return docs
