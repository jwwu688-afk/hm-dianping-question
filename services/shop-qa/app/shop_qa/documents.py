"""Pure conversion of shop records into source-traceable search documents."""


def shop_filter(shop_id: int) -> str:
    if isinstance(shop_id, bool) or not isinstance(shop_id, int) or shop_id <= 0:
        raise ValueError("shop_id must be a positive integer")
    return f"shop_id == {shop_id}"


def build_shop_documents(shop: dict, blogs: list[dict]) -> list[dict]:
    shop_id = int(shop["id"])
    if shop_id <= 0:
        raise ValueError("invalid shop id")
    fields = [
        ("店名", "name"), ("商圈", "area"), ("地址", "address"),
        ("人均价格（元）", "avg_price"), ("评分（满分50）", "score"),
        ("营业时间", "open_hours"),
    ]
    facts = [f"{label}：{shop[key]}" for label, key in fields if shop.get(key) is not None and str(shop[key]).strip()]
    docs = [{"shop_id": shop_id, "source_type": "shop", "source_id": shop_id,
             "title": str(shop.get("name") or "店铺资料"), "content": "\n".join(facts)}]
    for blog in blogs:
        if int(blog.get("shop_id") or 0) != shop_id:
            continue
        content = str(blog.get("content") or "").strip()
        if not content:
            continue
        title = str(blog.get("title") or "探店笔记").strip()
        # Keep short passages searchable while preserving the source blog ID.
        paragraphs = [p.strip() for p in content.splitlines() if p.strip()]
        chunks = []
        current = ""
        for paragraph in paragraphs:
            if current and len(current) + len(paragraph) > 700:
                chunks.append(current)
                current = ""
            while len(paragraph) > 700:
                chunks.append(paragraph[:700])
                paragraph = paragraph[700:]
            current = f"{current}\n{paragraph}".strip() if current else paragraph
        if current:
            chunks.append(current)
        for part, chunk in enumerate(chunks):
            docs.append({"shop_id": shop_id, "source_type": "blog", "source_id": int(blog["id"]),
                         "title": title, "part": part, "content": chunk})
    return docs
