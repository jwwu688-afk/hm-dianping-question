"""SSE 学习脚本 3：验证「后台任务 + 异步队列」的 SSE 架构（正式服务的雏形）。

这是三份学习脚本里最接近 query_service.py 真实设计的一版：
- task_queues：全局字典，按 session_id 为每个会话挂一个 asyncio.Queue，
  作为「后台耗时任务」与「SSE 生成器」之间的数据通道；
- /submit/{session_id}：用 BackgroundTasks 启动耗时的 long_task 并立刻返回，
  前端随后再连 /stream/{session_id} 收结果——两阶段调用与正式服务一致；
- long_task：每秒往队列丢一条结果，结束时丢入 None 作为「结束标记」；
- /stream/{session_id} 的生成器：await queue.get() 阻塞等待（无轮询、高效），
  取到数据就 yield 推送，取到 None 就退出循环、关闭连接。

与正式服务的差异：query_service.py 面向线程环境用的是线程安全的
queue.Queue + 1 秒超时轮询（顺带检测断连），本脚本用 asyncio.Queue 的
阻塞 get，架构思路同源、实现细节不同。
"""
import asyncio
from fastapi import FastAPI, BackgroundTasks
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# 核心优化：用异步队列存储每个会话的待推送数据（替代列表+轮询）
# 字典本身在事件循环内读写、无跨线程访问，因此用 asyncio.Queue 即可
task_queues = {}


# 异步耗时任务：直接往队列丢数据，不用列表累加
# 对比脚本 1/2：它们在生成器里「边算边推」；本脚本把「算」与「推」拆到两个协程
async def long_task(session_id: str):
    # 为当前会话创建专属队列
    queue = asyncio.Queue()
    # 挂到全局字典上：SSE 生成器稍后凭 session_id 从这里取到同一个队列
    task_queues[session_id] = queue

    # 模拟5秒处理，每秒生成1条结果并丢进队列
    for i in range(5):
        msg = f"会话{session_id}处理结果{i + 1}"
        await queue.put(msg)  # 把数据丢进队列
        await asyncio.sleep(1)

    # 关键：丢一个"结束标记"，告诉SSE可以停止了
    # None 不是合法消息内容，恰好可以复用为「流程结束」的哨兵值
    # SSE 生成器收到它就退出循环、关闭连接——任务与推流的生命周期在此对齐
    await queue.put(None)


# 提交任务接口（逻辑不变）
# BackgroundTasks 会在响应返回后才真正执行 long_task，实现「先应答、后干活」
@app.get("/submit/{session_id}")
async def submit_task(session_id: str, background_tasks: BackgroundTasks):
    background_tasks.add_task(long_task, session_id)
    return {"message": "任务已启动", "session_id": session_id}
    # 返回体只表示「已受理」，结果需要前端连 /stream 才能收到


# 简化后的SSE接口：直接从队列取数据，没有轮询！
# 前端调用顺序：先 GET /submit/{id}，再 GET /stream/{id}（与正式服务一致）
@app.get("/stream/{session_id}")
async def stream_result(session_id: str):
    async def event_generator():
        # 获取当前会话的队列（没有则等待任务创建）
        # 小轮询只发生在「连接先于任务创建」的窗口期，任务建好队列后即转为阻塞等待
        while session_id not in task_queues:
            await asyncio.sleep(0.1)
        queue = task_queues[session_id]

        # 核心：循环从队列取数据，有数据就推，收到结束标记就停
        while True:
            msg = await queue.get()  # 阻塞等待队列数据（比轮询高效）
            if msg is None:  # 收到结束标记，退出循环
                break
            yield f"data: {msg}\n\n"  # 推送数据
        # 生成器函数返回后，StreamingResponse 自动关闭 SSE 连接
        # 相比正式服务少了「断连检测」：本脚本连接建立后即使前端关闭，协程也会跑完循环

    return StreamingResponse(event_generator(), media_type="text/event-stream")


if __name__ == "__main__":
    import uvicorn

    # 独立运行入口：先 curl /submit/{id} 再 curl -N /stream/{id} 模拟两阶段调用
    uvicorn.run(app, host="127.0.0.1", port=8001)