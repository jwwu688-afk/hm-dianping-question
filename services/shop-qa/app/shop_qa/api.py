"""Local HTTP interface called by the Black Horse Review backend."""

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .answer import answer_shop

app = FastAPI(title="黑马点评店铺问答")


class AskRequest(BaseModel):
    question: str = Field(min_length=2, max_length=500)


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/shops/{shop_id}/ask")
def ask(shop_id: int, body: AskRequest):
    if shop_id <= 0:
        raise HTTPException(status_code=400, detail="invalid shop_id")
    try:
        return answer_shop(shop_id, body.question.strip())
    except Exception as exc:
        raise HTTPException(status_code=503, detail="问答服务暂不可用") from exc
