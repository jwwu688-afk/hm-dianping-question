"""Grounded, single-shop answer generation."""

from .retrieval import search_shop


def answer_shop(shop_id: int, question: str, search=search_shop) -> dict:
    evidence = search(shop_id, question)
    if not evidence:
        return {"shop_id": shop_id, "answer": "目前关于这家店的资料不足，暂时无法可靠地概括它的特色。", "sources": []}

    from app.lm.lm_utils import get_llm_client

    context = "\n\n".join(
        f"[{n}] {doc['source_type']} #{doc['source_id']} {doc.get('title', '')}\n{doc['content']}"
        for n, doc in enumerate(evidence, 1)
    )
    prompt = (
        "你是黑马点评的店铺问答助手。仅根据下面这家店的资料回答问题。"
        "店铺资料是平台记录；探店笔记是用户主观体验，要用‘笔记提到’等措辞。"
        "不要猜测未提供的菜品、价格、营业状态或综合口碑。"
        "回答简洁，关键结论引用资料编号，例如[1]。资料不足时直说。\n\n"
        f"资料：\n{context}\n\n问题：{question}"
    )
    response = get_llm_client().invoke(prompt)
    answer = str(response.content).strip()
    sources = []
    seen = set()
    for number, doc in enumerate(evidence, 1):
        key = (doc["source_type"], doc["source_id"])
        if key in seen:
            continue
        seen.add(key)
        sources.append({"ref": number, "type": doc["source_type"], "id": doc["source_id"],
                        "title": doc.get("title", "")})
    return {"shop_id": shop_id, "answer": answer, "sources": sources}
