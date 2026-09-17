"""
百炼 WebSearch MCP 客户端封装 —— 同时兼容 mcp v1 和 mcp v2。

为什么需要兼容层：
- mcp v1.x（openai-agents<=0.4 的默认依赖）
  * 导入名是驼峰式：streamablehttp_client
  * 鉴权头直接通过 headers 参数传入（dict）
  * 返回值是三元组：(read, write, get_session_id)
  * 结果对象的错误标志是 isError
- mcp v2.x（openai-agents>=0.5 的默认依赖）
  * 导入名是蛇形式：streamable_http_client
  * 鉴权头必须通过 httpx2.AsyncClient 配置（http_client 参数）
  * 返回值是二元组：(read, write)
  * 结果对象的错误标志是 is_error

本模块对外暴露唯一的异步函数 `search_web(query)`，内部自动探测 mcp 版本并走对应路径。
"""

from __future__ import annotations

import asyncio
import contextlib
from typing import Any

from app.conf.bailian_mcp_config import mcp_config
from app.core.logger import logger

# ===== mcp 版本探测 =====
# 通过尝试导入 v2 特有的函数名来判断；失败则回退到 v1。
# 这里也兼容 httpx2 模块存在与否（v2 必带 httpx2，v1 用 httpx）。
try:
    import httpx2  # noqa: F401
    from mcp.client.streamable_http import streamable_http_client as _streamable_http_client
    _MCP_V2 = True
except ImportError:
    from mcp.client.streamable_http import streamablehttp_client as _streamable_http_client
    _MCP_V2 = False

from mcp.client.session import ClientSession  # noqa: E402

logger.info(f"[BailianMCP] mcp 版本探测结果: v{2 if _MCP_V2 else 1}")


async def _call_tool_bailian_web_search(query: str) -> Any | None:
    """
    调用百炼 WebSearch MCP 工具，返回 CallToolResult 对象（或失败时返回 None）。

    流程：
    1. 构造带 Authorization 头的 HTTP 客户端
    2. 走 mcp 的 streamable_http transport
    3. 创建 ClientSession 并**手动调用 initialize()**（绕过 server/discover 探测）
       —— 百炼服务基于 2024-11-05 协议，不支持新版 mcp 的 discover 探测
    4. 调用工具 bailian_web_search
    """
    url = mcp_config.mcp_base_url
    # 缺少密钥时直接短路，避免发一次必然 401 的无效请求
    if not mcp_config.api_key:
        logger.error(
            "[BailianMCP] 未配置百炼 API Key（MCP_DASHSCOPE_API_KEY），跳过本次 MCP 调用"
        )
        return None
    headers = {"Authorization": f"Bearer {mcp_config.api_key}"}

    logger.info(f"[BailianMCP] 正在连接百炼 WebSearch 服务: {url}")

    try:
        if _MCP_V2:
            # mcp v2：通过 httpx2.AsyncClient 传 headers
            import httpx2 as _httpx
            http_client = _httpx.AsyncClient(
                headers=headers,
                timeout=_httpx.Timeout(60.0, read=300.0),
            )
            async with http_client:
                # v2 返回二元组 (read, write)
                async with _streamable_http_client(
                    url, http_client=http_client, terminate_on_close=False
                ) as streams:
                    read_stream, write_stream = streams
                    async with ClientSession(read_stream, write_stream) as session:
                        await session.initialize()
                        logger.info(
                            f"[BailianMCP] 连接成功，正在调用工具 'bailian_web_search' 查询: {query}"
                        )
                        result = await session.call_tool(
                            name="bailian_web_search",
                            arguments={"query": query, "count": 5},
                        )
                        logger.info("[BailianMCP] 工具调用完成，已获取返回结果")
                        return result
        else:
            # mcp v1：直接传 headers，ClientSession 接受位置参数 name
            # v1 返回三元组 (read, write, get_session_id_callback)
            async with _streamable_http_client(
                url, headers=headers, timeout=60.0, sse_read_timeout=300.0
            ) as streams:
                read_stream, write_stream, _ = streams
                session = ClientSession(read_stream, write_stream)
                async with session:
                    await session.initialize()
                    logger.info(
                        f"[BailianMCP] 连接成功，正在调用工具 'bailian_web_search' 查询: {query}"
                    )
                    # v1 call_tool 是位置参数 name
                    result = await session.call_tool(
                        "bailian_web_search",
                        {"query": query, "count": 5},
                    )
                    logger.info("[BailianMCP] 工具调用完成，已获取返回结果")
                    return result

    except Exception as e:
        # ExceptionGroup 嵌套时递归展开，看到最深层真实异常
        def _flatten_exc(exc, depth=0) -> None:
            sub = getattr(exc, "exceptions", None)
            if sub:
                for s in sub:
                    _flatten_exc(s, depth + 1)
            else:
                logger.error(
                    f"[BailianMCP] 异常链{' (嵌套)' if depth else ''}: "
                    f"{type(exc).__name__}: {exc}"
                )
        _flatten_exc(e)
        logger.error(f"[BailianMCP] 调用过程中发生异常: {e}", exc_info=True)
        return None


async def search_web(query: str) -> Any | None:
    """
    对外统一入口：调用百炼 WebSearch MCP 搜索指定 query。

    :param query: 搜索查询词（通常是经过改写后的精准 Query）
    :return: CallToolResult 对象（v1/v2 都有 .content / .is_error or .isError），失败时返回 None
    """
    return await _call_tool_bailian_web_search(query)


def is_error_result(result: Any) -> bool:
    """
    统一读取 CallToolResult 的错误标志，兼容 v1 (isError) 和 v2 (is_error)。
    """
    if result is None:
        return True
    if hasattr(result, "is_error"):
        return bool(result.is_error)
    if hasattr(result, "isError"):
        return bool(result.isError)
    return False