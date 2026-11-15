"""
SSE（Server-Sent Events）推送工具：基于「阻塞队列桥接」打通两条执行模型。

为什么需要桥接：LangGraph 工作流节点跑在同步上下文/线程里，而 FastAPI 的响应
是 async 生成器——两侧的执行节奏完全不同步。queue.Queue 是线程安全阻塞队列，
生产者（工作流节点）随时 put，消费者（sse_generator）在事件循环里通过
run_in_executor 阻塞 get，天然解耦两侧节奏：节点产出快不会丢，响应端空闲也不空转。

核心设计：
1. 全局字典 _session_stream 以 session_id 为 key 挂载 queue.Queue，
   任意模块凭 session_id 即可向会话推送事件（跨模块、跨线程）；
2. expected 防误删：同一 session 连续提问时，上一轮生成器的 finally 可能晚于
   新一轮 create_sse_queue 执行，remove_sse_queue 传入 expected 队列引用，
   只有当前注册的仍是「自己的队列」才删除，避免误删新一轮的通道；
3. __close__ 预留通道：工作流结束后向队列投递特殊事件，生成器收到即 break，
   用于在客户端仍连接时优雅关闭 SSE 流。
"""
import json
import queue
import asyncio
from typing import Dict, Any, Optional, AsyncGenerator
from fastapi import Request


class SSEEvent:
    # 事件名常量集中定义：推送方与生成器共用同一套字面量，避免拼写不一致导致前端收不到事件
    READY = "ready"         # 连接建立
    PROGRESS = "progress"   # 任务节点进度
    DELTA = "delta"         # LLM 流式输出增量
    FINAL = "final"         # 最终完整答案
    ERROR = "error"         # 错误信息
    CLOSE = "__close__"     # 关闭连接信号
    # __close__ 不是真正的业务事件，而是「预留的关闭通道」：
    # 生成器循环里对其特殊处理（break），不会下发到前端


# 全局 SSE 会话队列存储
# Key: session_id, Value: queue.Queue
# 选择模块级字典而非 Redis：队列必须与「正在消费它的生成器」同进程，
# queue.Queue 的阻塞语义跨进程无效，内存字典最简单且天然同进程
_session_stream: Dict[str, queue.Queue] = {}

def get_sse_queue(session_id: str) -> Optional["queue.Queue"]:
    """获取指定 session 的队列"""
    # get 而非下标访问：会话不存在时返回 None，调用方自行判空，避免 KeyError
    return _session_stream.get(session_id)

def create_sse_queue(session_id: str) -> "queue.Queue":
    """创建并注册一个新的 SSE 队列"""
    print(f"[SSE] Creating queue for session: {session_id}")
    # 由请求入口在提问时创建：一个 session 同一时刻只有一个活跃队列（新一轮会覆盖旧的）
    q = queue.Queue()
    _session_stream[session_id] = q
    return q

def remove_sse_queue(session_id: str, expected: Optional["queue.Queue"] = None):
    """移除指定 session 的队列。

    传入 expected 时，只有当前注册的队列仍是它才移除：
    避免上一轮遗留的生成器在 finally 里把新一轮刚创建的队列误删掉
    （同一 session 连续提问时会出现，导致新一轮收不到 SSE 事件）。
    """
    # 核心守卫：expected is not None 且当前注册的队列「不是」expected 时直接返回不动字典。
    # 典型时序：轮1生成器 finally -> 此时轮2已 create 覆盖注册 -> 轮1期望删自己的旧队列，
    # 比对后不一致即跳过删除，轮2的队列得以存活
    if expected is not None and _session_stream.get(session_id) is not expected:
        return
    # expected=None 表示调用方明确要求无条件清理（如会话销毁兜底），用 pop 默认值防 KeyError
    print(f"[SSE] Removing queue for session: {session_id}")
    _session_stream.pop(session_id, None)

def _sse_pack(event: str, data: Dict[str, Any]) -> str:
    """打包 SSE 消息格式"""
    # ensure_ascii=False：中文原样输出，避免前端收到 \u 转义后还需二次解码
    payload = json.dumps(data, ensure_ascii=False)
    # print(f"[SSE] Packing event: {event}, payload: {payload[:50]}...")
    # SSE 协议格式：event 行 + data 行 + 空行（空行是事件边界，浏览器据此分发到对应监听器）
    return f"event: {event}\ndata: {payload}\n\n"

def push_to_session(session_id: str, event: str, data: Dict[str, Any]):
    """
    通过 session_id 推送事件
    """
    # push 是「尽力而为」：队列不存在（会话已结束/未建立）只打印告警不抛异常，
    # 保证工作流节点里的推送调用永远不会阻断主流程
    stream_queue = get_sse_queue(session_id)
    if stream_queue:
        # print(f"[SSE] Pushing to session {session_id}: {event}")
        stream_queue.put({"event": event, "data": data})
    else:
        print(f"[SSE] Warning: No queue found for session {session_id} when pushing {event}")

async def sse_generator(session_id: str, request: Request):
    """
    SSE 生成器，用于 FastAPI 的 StreamingResponse
    """
    print(f"[SSE] Generator started for session: {session_id}")
    stream_queue = get_sse_queue(session_id)
    if stream_queue is None:
        # 如果没有对应的队列，直接结束
        print(f"[SSE] Error: Queue not found for session {session_id}. Available sessions: {list(_session_stream.keys())}")
        return

    # 获取当前正在运行的事件循环（Event Loop）。在异步函数中，这行代码返回当前协程所在的事件循环实例。
    # 提前取出 loop 引用：后续循环里每次 get 都要拿它做 run_in_executor，一次获取反复使用
    loop = asyncio.get_running_loop()
    try:
        # 发送连接建立信号
        print(f"[SSE] Sending ready signal for {session_id}")
        yield _sse_pack("ready", {})

        while True:
            # 若客户端断开，尽快退出
            # 每轮循环先探测断连：客户端关页后及时停止推送，释放队列与连接
            if await request.is_disconnected():
                print(f"[SSE] Client disconnected: {session_id}")
                print("-----------------------断开连接--------------------")
                break

            try:
                # 使用 run_in_executor 避免阻塞 async 事件循环
                # timeout=1.0 的阻塞 get：1 秒内没消息就抛 queue.Empty 回到循环头，
                # 既避免忙轮询空转 CPU，又保证断连探测（is_disconnected）有机会每秒执行
                msg = await loop.run_in_executor(None, stream_queue.get, True, 1.0)
            except queue.Empty:
                # print(f"[SSE] Queue empty for {session_id}, waiting...")
                # 空队列不是错误：继续下一轮循环（先探测断连再取消息）
                continue

            event = msg.get("event")
            data = msg.get("data")
            
            # print(f"[SSE] Yielding event {event} for {session_id}")

            # 特殊关闭事件
            # 收到 __close__ 不下发、直接 break 结束生成器，FastAPI 据此终止 SSE 响应
            if event == "__close__":
                print(f"[SSE] Closing signal received for {session_id}")
                break

            yield _sse_pack(event, data)
    except (asyncio.CancelledError, ConnectionResetError, BrokenPipeError):
        print(f"[SSE] Client disconnected (Cancelled/Reset/Pipe): {session_id}")
        # 生成器被取消/对端断开：静默退出
        # 这三类异常属于「正常断开」，不应当错误处理，也不应再向已断开的连接写数据
        return
    except Exception as e:
        print(f"[SSE] Exception in generator for {session_id}: {e}")
    finally:
        print(f"[SSE] Generator finished for {session_id}")
        # 清理资源（仅当当前注册的队列仍是本次这把时才移除）
        # 放在 finally：无论正常结束、断连还是异常，都必须尝试清理，防止字典里的死队列泄漏
        remove_sse_queue(session_id, stream_queue)