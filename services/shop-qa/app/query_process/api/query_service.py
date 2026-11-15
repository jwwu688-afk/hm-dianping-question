"""店铺智能问答服务的 FastAPI 入口（HTTP API 层）。

整体交互流程（「阻塞队列桥接」设计）：
1. 前端 POST /query 提交问题；服务端为该会话创建一个 queue.Queue（挂在
   全局字典上，以 session_id 为 key），把 LangGraph 流程的执行注册进
   BackgroundTasks 后立即返回 session_id；
2. 后台任务 run_query_graph 同步 invoke 整张流程图，图内各节点边执行边把
   SSE 事件（节点进度 / 检索片段 / 最终答案 / 错误）写进该会话的队列；
3. 前端拿到 session_id 后请求 GET /stream/{session_id}，由 StreamingResponse
   包裹的 sse_generator 消费队列，以 text/event-stream 持续推送给前端。

桥接层（app/utils/sse_utils.py）的两个关键点：
- queue.Queue 是阻塞队列：后台任务 put、SSE 生成器 get，二者天然解耦，
  任何一方慢都不会拖垮事件循环；
- sse_generator 以 1 秒超时轮询 queue.get()：超时窗口内顺带检查客户端是否
  已断连（request.is_disconnected），避免前端关页后生成器永远阻塞在队列上。
相比让前端反复轮询任务表，队列桥接能让答案/片段「随产随推」，体验更流畅。

其余接口：/health 健康检查、GET /history/{session_id} 查 Mongo 会话历史、
后台任务与 SSE 生成器同用一个 session_id 关联；任务进度表（task_utils）
独立于队列存在——前端除了收事件流，还能轮询任务表做逐节点打勾动效。
"""
from pathlib import Path
import uuid
import uvicorn
from fastapi import FastAPI, BackgroundTasks, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field
from starlette.middleware.cors import CORSMiddleware

# ---- 项目内部模块 ----
# task_utils：会话级「节点进度任务表」（前端逐节点打勾动效的数据源），星号导入全部工具函数
from app.utils.task_utils import *
# sse_utils：SSE 桥接层——create_sse_queue 建队列、SSEEvent 封装事件类型、
# sse_generator 以 1 秒超时轮询队列并检测断连（详见文件顶部说明）
from app.utils.sse_utils import create_sse_queue, SSEEvent, sse_generator
# mongo_history_utils：MongoDB 会话历史记录的查询与清除（星号导入）
from app.clients.mongo_history_utils import *
# query_app：已编译的 LangGraph 问答主流程（节点编排见 agent/main_graph.py）
from app.query_process.agent.main_graph import query_app

# 后续导入启动图对象
#from app.query_process.main_graph import query_app


# 定义fastapi对象
app = FastAPI(title="query service",description="小度小度查询服务！")
# 跨域问题解决
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
# 教学项目放开全部来源/方法/请求头；生产环境应收敛为具体的前端域名白名单

# ---------- 页面路由：返回前端静态页 ----------
# 返回纯问答前端页面（chat.html）——本服务只负责「问答」
# 统一工作台页面（问答 + 导入 双视图）已迁移到 app/total_service.py，访问 :8000
@app.get("/")  # 对外访问地址
async def index():
    """返回纯问答前端页面 chat.html；文件缺失时抛 404（带上期望路径便于排查）。"""
    # 从 api -> query_process
    current_dir_parent_path = Path(__file__).absolute().parent.parent
    # 纯问答页面 chat.html
    chat_html_path = current_dir_parent_path / "page" / "chat.html"
    # 如果不存在，抛出404异常
    if not chat_html_path.exists():
        raise HTTPException(status_code=404, detail=f"没有查询到页面，地址为：{chat_html_path}！")
    # no-cache 三件套响应头：禁止浏览器/代理缓存页面，保证前端改动后刷新即生效
    return FileResponse(
        chat_html_path,
        headers={
            "Cache-Control": "no-cache, no-store, must-revalidate",
            "Pragma": "no-cache",
            "Expires": "0",
        },
    )


# 旧版纯聊天页（向后兼容保留）
@app.get("/chat.html")
async def chat():
    """旧版聊天页入口，与 / 返回同一份 chat.html（路由兼容保留）。"""
    # 实现与 index() 完全相同，仅路由不同
    current_dir_parent_path = Path(__file__).absolute().parent.parent
    chat_html_path = current_dir_parent_path / "page" / "chat.html"
    if not chat_html_path.exists():
        raise HTTPException(status_code=404, detail=f"没有查询到页面，地址为：{chat_html_path}！")
    return FileResponse(
        chat_html_path,
        headers={
            "Cache-Control": "no-cache, no-store, must-revalidate",
            "Pragma": "no-cache",
            "Expires": "0",
        },
    )

# 定义接口接收的数据结构
# 请求体模型：query 必填；session_id 可选（不传则服务端生成）；is_stream 决定走 SSE 还是同步 JSON
class QueryRequest(BaseModel):
    """查询请求数据结构"""
    query: str = Field(..., description="查询内容")  # ...必须填写
    session_id: str = Field(None, description="会话ID")
    is_stream: bool = Field(False, description="是否流式返回")
    # session_id / is_stream 均可选：不传 session_id 则新建会话；is_stream 默认走同步 JSON



# 健康检查：供部署探活与前端启动自检
# 证明服务器启动即可
@app.get("/health")
async def health():
    """
    检查服务是否正常
    """
    # 供部署探针 / 前端启动检查调用：服务进程活着即返回 {"ok": true}
    return {"ok": True}


# 定义查询接口
def run_query_graph(session_id: str, user_query: str, is_stream: bool = True):
    """后台执行 LangGraph 问答流程（流式模式下由 BackgroundTasks 调起）。

    - 组装初始 state（原始问题 / 会话 ID / 是否流式）后同步 invoke 整张图，
      图内各节点边执行边把 SSE 事件（进度、片段、答案）写进该会话的队列；
    - 正常结束：任务状态置为 completed，前端据此停止监听；
    - 异常：任务状态置为 failed；流式模式再补一条 ERROR 事件进队列，
      避免前端只能干等到超时断连、拿不到任何错误原因。
    """
    print(f"开始流程图处理...{session_id} {user_query} {is_stream}")

    # invoke 是同步阻塞调用：一直执行到图的 END 节点才返回
    # LangGraph 的初始 state：后续所有节点共享并逐步补全这份字典
    default_state = {"original_query": user_query, "session_id": session_id, "is_stream": is_stream}
    try:
        # 后期运行
        query_app.invoke(default_state)
        # 整体任务就更新完了！ 接下来就是数据的更新了！
        update_task_status(session_id, TASK_STATUS_COMPLETED, is_stream)
    except Exception as e:
        print(f"流程执行异常: {e}")
        # 异常也必须把任务标为失败，否则前端进度条会永远停在「运行中」
        update_task_status(session_id, TASK_STATUS_FAILED, is_stream)
        if is_stream:
            push_to_session(session_id, SSEEvent.ERROR, {"error": str(e)})



# 主问答接口：流式走「建队列 + 后台任务」，同步走「本次请求内执行完返回」
@app.post("/query")
async def query(background_tasks: BackgroundTasks, request: QueryRequest):
    """
    1 解析参数
    2 更新任务状态
    3 调用处理流程图
    4 返回结果
    :param background_tasks:
    :param request:
    :return:
    """
    # 步骤 1-4 如 docstring；核心顺序是：清残留状态 → 建队列 → 置运行中 → 派发后台任务
    # 用户问题原文；session_id 未传则生成新会话，保证每轮问答都有唯一标识
    user_query = request.query
    session_id = request.session_id if request.session_id else str(uuid.uuid4())

    # 处理是不是流式返回结果
    # True → 建立 SSE 长连接逐事件推送；False → 本次请求内同步执行完，直接返回 JSON
    is_stream = request.is_stream

    # 关键：以 session_id 为 key 的任务进度是【会话级复用】的，
    # 新一轮提问前必须清空上一轮残留的 done/running，
    # 否则前端一进来就会看到上一轮"全部已完成"的对号，逐节点打勾的动效消失。
    clear_task(session_id)

    if is_stream:
        # 创建一个字典 存储对一个session_id : queue 结果队列
        # 即「阻塞队列桥接」的入口：全局字典里挂上一个 queue.Queue，
        # 后台图执行往里写事件，/stream 接口再从里面读出来推给前端
        create_sse_queue(session_id)
    # 更新任务状态
    # 当前会话id作为key! 整体装填处于运行中！
    # 先置为「运行中」，前端轮询任务状态即可立刻看到进度开始
    update_task_status(session_id, TASK_STATUS_PROCESSING,is_stream)

    print("开始处理流程... 是否流式:", is_stream, f"其他参数:{user_query}, session_id:{session_id}")

    if is_stream:
        # 如果是流式，则返回一个流式响应，过程不断地推送
        # 运行执行图对象方法
        # 把整张图的执行注册为后台任务：先立刻返回 session_id，
        # 前端再拿它请求 /stream/{session_id} 建立 SSE 长连接接收结果
        background_tasks.add_task(run_query_graph, session_id,user_query,is_stream)
        # 返回结果
        # 注意：这里返回的是「已受理」响应，真正的答案通过 /stream 异步推送
        print("开始处理结果....")
        return {
            "message":"结果正在处理中...",
            "session_id":session_id
        }
    else:
        # 同步分支：直接携带完整答案返回，前端无需再连 SSE
        # 同步运行
        # 不走队列，直接在本次请求里执行完再返回
        run_query_graph(session_id, user_query, is_stream)
        # 同步模式下结果由图内节点写进任务表，这里直接读出 answer 字段返回
        answer = get_task_result(session_id,"answer","")
        return {
            "message":"处理完成！",
            "session_id":session_id,
            "answer":answer,
            "done_list":[]
        }



# SSE 推流接口：消费 /query 为该会话建好的事件队列
@app.get("/stream/{session_id}")
async def stream(session_id: str, request: Request):
    print("调用流式/stream...")
    """
    sse 实时返回结果
    """
    # 与 /query 配套：POST /query 拿到 session_id 后，前端立即请求本接口开始接收事件流
    # 注意：本接口不校验会话是否存在；队列不存在时 sse_generator 会空转到客户端断连
    # sse_generator：以 1 秒超时轮询该会话的队列取事件，顺带检测客户端是否已断连
    # text/event-stream 是 SSE 协议规定的 Content-Type
    # 响应开始后，生成器 yield 的每条字符串会按 SSE 协议原样逐条下发
    return StreamingResponse(
        sse_generator(session_id, request),
        media_type="text/event-stream",
        headers={
            # 禁缓存 + 长连接 + 禁 Nginx 缓冲：SSE 必备三件套，缺一个都可能收不到流式效果
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )


# 会话历史查询接口：读取 MongoDB 中该会话的问答记录
@app.get("/history/{session_id}")
async def history(session_id: str, limit: int = 50):
    """
    查询当前会话历史记录
    """
    # limit 由查询参数传入，默认取最近 50 条
    try:
        # 从 Mongo 拉取该会话最近 limit 条消息，再逐条拍平成前端易消费的字段字典
        records = get_recent_messages(session_id, limit=limit)
        items = []
        # 只挑前端需要的字段回传，不把 Mongo 文档的内部字段全量泄漏
        # _id 是 ObjectId，不能直接 JSON 序列化，必须先转 str
        for r in records:
            items.append({
                "_id": str(r.get("_id")) if r.get("_id") is not None else "",
                "session_id": r.get("session_id", ""),
                "role": r.get("role", ""),
                "text": r.get("text", ""),
                "rewritten_query": r.get("rewritten_query", ""),
                "item_names": r.get("item_names", []),
                # 之前漏返回该字段，导致页面刷新后历史回答里的图片丢失（前端已按 item.image_urls 渲染）
                "image_urls": r.get("image_urls") or [],
                # ts 为时间戳，前端按它排序展示消息顺序
                "ts": r.get("ts")
            })
        return {"session_id": session_id, "items": items}
    except Exception as e:
        # Mongo 连接失败等异常统一转成 500，避免把原始堆栈直接暴露给前端
        raise HTTPException(status_code=500, detail=f"history error: {e}")


@app.delete("/history/{session_id}")
async def clear_chat_history(session_id: str):
    """清除指定会话的全部历史记录，返回删除条数供前端提示。"""
    # clear_history 返回实际删除的 Mongo 文档条数
    count = clear_history(session_id)
    return {"message": "History cleared", "deleted_count": count}


# 独立调试入口：生产中通常由 uvicorn/gunicorn 拉起，这里直接以 8001 端口运行
if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8001)
