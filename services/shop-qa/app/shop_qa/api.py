"""Unified local RAG API for the Black Horse Review backend."""
# 模块职责：黑马点评「店铺 Markdown 上传」方案的统一 RAG 服务入口（FastAPI 应用）。
# 三类核心能力：
# 1) 文档上传：POST /documents 校验后落盘并登记为 pending，借 BackgroundTasks 异步解析，
#    接口立即返回 202 + document_id，前端随后轮询 GET /documents/{id} 获取处理状态；
# 2) 文档生命周期：GET /documents 按归属人列举，DELETE 只允许删除已结束（非处理中）的文档；
# 3) 智能问答：/shops/{id}/ask 与 /ask 为同步 JSON 返回；/shops/{id}/stream 与 /stream
#    以 SSE（text/event-stream）逐段推送增量答案，供前端做打字机效果。

import json
from functools import lru_cache
from pathlib import Path

from fastapi import BackgroundTasks, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field

from .answer import answer_query, answer_shop, stream_answer
# document_pipeline 提供落盘/校验/异步解析/删除四个能力与数据根目录、大小上限常量。
from .document_pipeline import DATA_ROOT, MAX_UPLOAD_BYTES, process_document, remove_document, save_upload
# registry 提供文档元数据与会话历史的 SQLite 持久化（documents.sqlite3）。
from .registry import DocumentRegistry

# FastAPI 应用实例：标题同时用于 OpenAPI 文档展示。
app = FastAPI(title="黑马点评统一知识库")


@app.get("/")
def console():
    """首页：返回与 api.py 同目录下的 console.html，作为浏览器调试控制台页面。

    用 Path(__file__) 定位而非相对路径，保证无论从哪个工作目录启动都有效。
    仅做静态文件返回，不涉及任何业务状态。
    """
    return FileResponse(Path(__file__).with_name("console.html"))


@lru_cache(maxsize=1)
def registry() -> DocumentRegistry:
    """获取全进程唯一的文档状态注册表（SQLite 单例）。

    使用 lru_cache 保证首次调用后复用同一实例，避免多个连接并发写锁竞争；
    元数据落盘在 data/documents.sqlite3，服务重启后文档状态不丢失。
    所有路由都经由本函数取注册表，保证状态机数据源单一。
    """
    return DocumentRegistry(DATA_ROOT / "documents.sqlite3")


class AskRequest(BaseModel):
    """问答请求体（Pydantic 校验模型）。

    question: 用户问题，长度限定 2~500，过短无语义、过长超出检索与生成预算；
    session_id: 会话 ID，缺省时由服务端生成，用于串联多轮对话历史；
    allow_web: 是否允许在本地证据不足时补充联网搜索证据（默认关闭）。
    """
    question: str = Field(min_length=2, max_length=500)
    session_id: str | None = Field(default=None, max_length=64)
    allow_web: bool = False


@app.get("/health")
def health():
    """存活探针：容器/网关健康检查用，恒返回 ok，不触碰任何重逻辑。"""
    return {"ok": True}


@app.post("/shops/{shop_id}/ask")
def ask_shop(shop_id: int, body: AskRequest):
    """单店铺同步问答：检索限定在该店铺资料内作答，一次性返回完整 JSON。

    与全局 /ask 的区别在于证据范围：只取该店铺自有资料 + 平台公共语料（shop_id=0）。
    """
    # 参数校验：shop_id 必须为正整数，0 或负数视为非法请求。
    if shop_id <= 0:
        raise HTTPException(status_code=400, detail="invalid shop_id")
    try:
        # 检索 → 拼 Prompt → LLM 生成，全流程同步执行；question 去首尾空白避免空查询。
        # strip 只去掉空白字符，不改变问题本身语义。
        return answer_shop(shop_id, body.question.strip(), session_id=body.session_id, registry=registry())
    except Exception as exc:
        # 下游（检索/LLM）任何异常都收敛为 503，避免向前端泄露内部堆栈细节。
        # from exc 保留原始异常链，便于日志排查根因。
        raise HTTPException(status_code=503, detail="问答服务暂不可用") from exc


@app.post("/ask")
def ask_global(body: AskRequest):
    """全局问答：shop_id 传 None，检索范围为平台级公共语料（shop_id=0）。"""
    try:
        # allow_web 打开且环境变量 RAG_ENABLE_WEB_SEARCH 放行时，补充联网搜索证据。
        return answer_query(None, body.question.strip(), body.session_id, registry(), allow_web=body.allow_web)
    except Exception as exc:
        # 统一降级为 503，保证失败语义与单店铺问答一致。
        raise HTTPException(status_code=503, detail="问答服务暂不可用") from exc


def _sse(events):
    """把事件字典流编码为 SSE 文本帧；生成器中途异常时也以 SSE 帧报错，保持协议一致。

    SSE 帧格式为 event + data 两行、以空行结尾表示一帧结束；
    ensure_ascii=False 让中文按原文写出，前端无需二次转码。
    这里只做编码不做业务，事件序列由 stream_answer 产出（delta*→final）。
    """
    try:
        for item in events:
            yield f"event: {item['event']}\ndata: {json.dumps(item['data'], ensure_ascii=False)}\n\n"
    except Exception:
        # 流中断（如 LLM/检索异常）不能抛给 HTTP 层，改发一帧 error 让前端友好提示。
        yield 'event: error\ndata: {"error":"问答服务暂不可用"}\n\n'


@app.post("/shops/{shop_id}/stream")
def stream_shop(shop_id: int, body: AskRequest):
    """单店铺 SSE 流式问答：增量 delta 事件 + 收尾 final 事件。

    与同步接口共用 stream_answer 生成器，仅路由与参数校验不同。
    """
    # 校验与同步接口保持一致，shop_id 非正数直接 400。
    if shop_id <= 0:
        raise HTTPException(status_code=400, detail="invalid shop_id")
    return StreamingResponse(
        # stream_answer 是生成器，迭代时才逐段产出；_sse 负责编码为 SSE 帧。
        _sse(stream_answer(shop_id, body.question.strip(), body.session_id, registry())),
        media_type="text/event-stream",
    )


@app.post("/stream")
def stream_global(body: AskRequest):
    """全局 SSE 流式问答：与 /ask 的差异仅在以事件流分帧推送。

    事件序列约定：若干 delta（增量文本）→ 1 个 final（完整答案 + 引用来源）。
    """
    return StreamingResponse(
        # allow_web 透传给生成器，在证据不足时允许联网补充。
        _sse(stream_answer(None, body.question.strip(), body.session_id, registry(), body.allow_web)),
        media_type="text/event-stream",
    )


@app.post("/documents", status_code=202)
async def upload_document(background_tasks: BackgroundTasks, file: UploadFile = File(...),
                          shop_id: int = Form(0), owner_id: int = Form(...)):
    """上传店铺知识文档（仅 PDF/Markdown），进入 202 异步处理状态机。

    状态机流转：pending（已登记）→ processing（解析/切分/向量化中）→ completed 或 failed。
    shop_id=0 表示上传为平台公共资料；owner_id 为归属用户，用于查询与删除时的权限隔离。
    返回 202 而非 200，明确告知客户端「请求已受理但处理尚未完成」。
    """
    # 归属校验：shop_id 允许 0（公共资料）但不允许负数，owner_id 必须为正。
    # 注意 shop_id 与 owner_id 的宽松度不同：公共资料合法，匿名上传不合法。
    if shop_id < 0 or owner_id <= 0:
        raise HTTPException(status_code=400, detail="invalid shop or owner ID")
    # 先整体读入内存做大小校验（多读 1 字节用于判定超限），上限 MAX_UPLOAD_BYTES=20MB。
    # 超限文件读到 MAX_UPLOAD_BYTES+1 即可判定，不必等整个文件传输完成。
    payload = await file.read(MAX_UPLOAD_BYTES + 1)
    try:
        # save_upload 内部完成文件名校验、落盘与 registry 登记（初始状态 pending）。
        document_id, path = save_upload(registry(), file.filename or "", payload, shop_id, owner_id)
    except ValueError as exc:
        # 文件名非法/类型不支持/超限等业务校验失败一律回 400。
        # 400 区别于 503：这是请求本身的问题，重试无意义，需换文件。
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    # 响应返回后再异步执行解析流水线，接口立即以 202 + pending 应答（异步状态机入口）。
    # BackgroundTasks 在响应发送后于同进程执行，无需额外队列组件。
    background_tasks.add_task(process_document, registry(), document_id, path)
    return {"document_id": document_id, "status": "pending"}


@app.get("/documents")
def list_documents(owner_id: int):
    """按归属人列举其全部上传文档（含状态），用于前端文档管理列表。

    返回字段含 status/chunk_count/error，前端可直接展示解析进度与失败原因。
    """
    # owner_id 非正即非法，直接拒绝，避免全表扫描泄露他人文档。
    # 仅按 owner_id 过滤，shop 维度由前端自行按需筛选。
    if owner_id <= 0:
        raise HTTPException(status_code=400, detail="invalid owner ID")
    return {"items": registry().list_documents(owner_id)}


@app.get("/documents/{document_id}")
def document_status(document_id: str, owner_id: int):
    """轮询单个文档的处理状态（pending/processing/completed/failed）。

    前端上传成功后凭 document_id 周期调用，看到 completed/failed 即停止轮询。
    """
    # 查询时同时校验归属人，防止越权读取他人文档状态。
    # 未命中返回 None，路由层统一转为 404。
    document = registry().get(document_id, owner_id)
    if not document:
        raise HTTPException(status_code=404, detail="document not found")
    return document


@app.delete("/documents/{document_id}")
def delete_document(document_id: str, owner_id: int):
    """删除文档：清理 Milvus 向量、磁盘上传目录与注册表记录。

    三处资源按「先向量后磁盘再元数据」的顺序清理，避免残留孤儿数据。
    """
    # 先确认存在且归属正确，避免删除不存在的资源时误判成功。
    # 这里以 owner_id 双重校验，与 document_status 的越权防护一致。
    document = registry().get(document_id, owner_id)
    if not document:
        raise HTTPException(status_code=404, detail="document not found")
    # 处理中的文档删除会与后台任务竞争（向量写入/文件被删），因此以 409 拒绝。
    # 409 Conflict：资源当前状态与请求冲突，前端据此提示稍后再删。
    if document["status"] in ("pending", "processing"):
        raise HTTPException(status_code=409, detail="document is still processing")
    try:
        # remove_document 依次删除 Milvus 中该 source 的向量与磁盘目录，再删注册表行。
        remove_document(registry(), document_id, owner_id)
    except Exception as exc:
        # 删除失败（如 Milvus 不可用）回 503，前端可稍后重试，注册表记录仍保留。
        raise HTTPException(status_code=503, detail="删除文档失败") from exc
    return {"deleted": True}


@app.get("/sessions/{session_id}/history")
def history(session_id: str, shop_id: int = 0):
    """读取会话历史：shop_id=0 表示全局会话，>0 表示某店铺会话，按时间正序返回。

    历史供多轮问答时拼接上下文，也可用于前端会话回放展示。
    """
    return {"session_id": session_id, "items": registry().get_history(session_id, shop_id)}


@app.delete("/sessions/{session_id}/history")
def clear_history(session_id: str, shop_id: int = 0):
    """清空会话历史，返回被删除的条数，便于前端确认清空结果。"""
    return {"deleted_count": registry().clear_history(session_id, shop_id)}
