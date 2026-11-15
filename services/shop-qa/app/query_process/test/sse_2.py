"""SSE 学习脚本 2：验证带路径参数的 SSE 接口（按 session_id 定制推送内容）。

在脚本 1 的基础上前进一小步：
- 路由从固定的 /simple_stream 变为 /stream/{session_id}，与正式服务
  「先拿到会话 ID、再用它建立 SSE 连接」的调用方式对齐；
- 每条消息都带上 session_id，用于验证两个浏览器标签页分别连接
  /stream/A 与 /stream/B 时，各自只收到各自的内容、互不串流。

curl 验证：curl -N "http://127.0.0.1:8001/stream/test-session"
"""
import asyncio
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
# 配置与脚本 1 完全一致：教学演示不纠结安全，重点是 SSE 本身

# 新增：接口接收session_id参数
# 路径参数进入函数后，既能拼进消息内容，也是未来查「会话队列」的 key
@app.get("/stream/{session_id}")
async def stream_by_session(session_id: str):
    async def event_generator():
        # 闭包捕获路径参数 session_id，生成器里可以直接引用
        # 仍是纯生成器推送（无队列、无后台任务），只是数据定制了
        for i in range(5):
            # 按session_id定制消息
            yield f"data: 会话{session_id} - 第{i+1}条消息\n\n"
            await asyncio.sleep(1)

    return StreamingResponse(event_generator(), media_type="text/event-stream")
    # 与脚本 1 的唯一区别：数据里拼入了 session_id，验证多会话隔离

if __name__ == "__main__":
    import uvicorn
    # 独立运行入口：端口与脚本 1 相同，两者不要同时启动（端口冲突）
    # 验证方法：开两个终端分别 curl -N .../stream/A 与 .../stream/B，内容应各自不同
    uvicorn.run(app, host="127.0.0.1", port=8001)