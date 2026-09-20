"""Unified local RAG API for the Black Horse Review backend."""

import json
from functools import lru_cache
from pathlib import Path

from fastapi import BackgroundTasks, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field

from .answer import answer_query, answer_shop, stream_answer
from .document_pipeline import DATA_ROOT, MAX_UPLOAD_BYTES, process_document, remove_document, save_upload
from .registry import DocumentRegistry

app = FastAPI(title="黑马点评统一知识库")


@app.get("/")
def console():
    return FileResponse(Path(__file__).with_name("console.html"))


@lru_cache(maxsize=1)
def registry() -> DocumentRegistry:
    return DocumentRegistry(DATA_ROOT / "documents.sqlite3")


class AskRequest(BaseModel):
    question: str = Field(min_length=2, max_length=500)
    session_id: str | None = Field(default=None, max_length=64)
    allow_web: bool = False


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/shops/{shop_id}/ask")
def ask_shop(shop_id: int, body: AskRequest):
    if shop_id <= 0:
        raise HTTPException(status_code=400, detail="invalid shop_id")
    try:
        return answer_shop(shop_id, body.question.strip(), session_id=body.session_id, registry=registry())
    except Exception as exc:
        raise HTTPException(status_code=503, detail="问答服务暂不可用") from exc


@app.post("/ask")
def ask_global(body: AskRequest):
    try:
        return answer_query(None, body.question.strip(), body.session_id, registry(), allow_web=body.allow_web)
    except Exception as exc:
        raise HTTPException(status_code=503, detail="问答服务暂不可用") from exc


def _sse(events):
    try:
        for item in events:
            yield f"event: {item['event']}\ndata: {json.dumps(item['data'], ensure_ascii=False)}\n\n"
    except Exception:
        yield 'event: error\ndata: {"error":"问答服务暂不可用"}\n\n'


@app.post("/shops/{shop_id}/stream")
def stream_shop(shop_id: int, body: AskRequest):
    if shop_id <= 0:
        raise HTTPException(status_code=400, detail="invalid shop_id")
    return StreamingResponse(
        _sse(stream_answer(shop_id, body.question.strip(), body.session_id, registry())),
        media_type="text/event-stream",
    )


@app.post("/stream")
def stream_global(body: AskRequest):
    return StreamingResponse(
        _sse(stream_answer(None, body.question.strip(), body.session_id, registry(), body.allow_web)),
        media_type="text/event-stream",
    )


@app.post("/documents", status_code=202)
async def upload_document(background_tasks: BackgroundTasks, file: UploadFile = File(...),
                          shop_id: int = Form(0), owner_id: int = Form(...)):
    if shop_id < 0 or owner_id <= 0:
        raise HTTPException(status_code=400, detail="invalid shop or owner ID")
    payload = await file.read(MAX_UPLOAD_BYTES + 1)
    try:
        document_id, path = save_upload(registry(), file.filename or "", payload, shop_id, owner_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    background_tasks.add_task(process_document, registry(), document_id, path)
    return {"document_id": document_id, "status": "pending"}


@app.get("/documents")
def list_documents(owner_id: int):
    if owner_id <= 0:
        raise HTTPException(status_code=400, detail="invalid owner ID")
    return {"items": registry().list_documents(owner_id)}


@app.get("/documents/{document_id}")
def document_status(document_id: str, owner_id: int):
    document = registry().get(document_id, owner_id)
    if not document:
        raise HTTPException(status_code=404, detail="document not found")
    return document


@app.delete("/documents/{document_id}")
def delete_document(document_id: str, owner_id: int):
    document = registry().get(document_id, owner_id)
    if not document:
        raise HTTPException(status_code=404, detail="document not found")
    if document["status"] in ("pending", "processing"):
        raise HTTPException(status_code=409, detail="document is still processing")
    try:
        remove_document(registry(), document_id, owner_id)
    except Exception as exc:
        raise HTTPException(status_code=503, detail="删除文档失败") from exc
    return {"deleted": True}


@app.get("/sessions/{session_id}/history")
def history(session_id: str, shop_id: int = 0):
    return {"session_id": session_id, "items": registry().get_history(session_id, shop_id)}


@app.delete("/sessions/{session_id}/history")
def clear_history(session_id: str, shop_id: int = 0):
    return {"deleted_count": registry().clear_history(session_id, shop_id)}
