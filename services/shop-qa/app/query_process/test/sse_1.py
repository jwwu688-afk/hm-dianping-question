"""SSE 学习脚本 1：验证 FastAPI 推送 Server-Sent Events 的最小可用写法。

要验证的点：
- StreamingResponse 配 media_type="text/event-stream" 即构成一个 SSE 端点；
- SSE 消息格式固定为 "data: 内容\\n\\n"（两个换行表示一条事件结束），
  也可扩展为 "event: 自定义事件名\\ndata: ...\\n\\n" 的事件类型写法；
- 异步生成器里 await asyncio.sleep(1) 模拟逐秒推送，验证前端能逐条、
  按时间间隔收到消息而不是一次性收完。

curl 验证：curl -N http://127.0.0.1:8001/simple_stream
（-N 禁用缓冲，可看到消息每隔 1 秒到达一条，共 5 条后连接关闭。）
"""
import asyncio
from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware

# 1. 初始化+跨域（最基础配置）
# CORS 放开是为了让本地 HTML 页面也能连上来测 EventSource
app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 仅测试用
    allow_methods=["*"],
    allow_headers=["*"],
)

# 2. 核心：SSE接口（只推固定数据）
# 路由不加参数，先跑通「无状态、纯推送」的最简场景
@app.get("/simple_stream")
async def simple_stream():
    async def event_generator():
        # 异步生成器：yield 一条就推一条，连接保持打开；生成器结束即断开
        # 模拟推5条消息，每秒1条
        for i in range(5):
            # ✅ 核心：SSE固定格式 data: 内容\n\n
            # 两个 \n\n 是协议要求：单个换行会被浏览器当作一条未完成的事件继续攒
            # yield f"event:自定义\ndata:xxxx\n\n"
            yield f"data: 这是第{i+1}条测试消息\n\n"
            await asyncio.sleep(1)  # 每秒推1条
        # 循环结束 = 生成器结束 = 服务器主动断开 SSE 连接

    # ✅ 核心：StreamingResponse + media_type=text/event-stream
    # 注意：yield 的字符串必须自带 "data: ...\\n\\n" 格式，框架不会自动补
    # 浏览器端用 new EventSource("/simple_stream") 即可自动重连并逐条收到消息
    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream"
    )

if __name__ == "__main__":
    import uvicorn
    # 直接运行本文件即启动最小 SSE 演示服务（端口 8001，注意不要与正式服务冲突）
    uvicorn.run(app, host="127.0.0.1", port=8001)
    # 运行后浏览器控制台：const es = new EventSource("/simple_stream"); es.onmessage = console.log