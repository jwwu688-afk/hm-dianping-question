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
调用方无需感知版本差异：import 本模块即完成探测与日志记录。
"""

from __future__ import annotations
# __future__ annotations：类型注解延迟求值，兼容低版本 Python 的新语法。

import asyncio
import contextlib
from typing import Any

# asyncio/contextlib：异步流上下文管理；Any：CallToolResult 的弱类型占位。
# asyncio 在当前实现中未直接使用，保留供扩展（如超时包装）。
from app.conf.bailian_mcp_config import mcp_config
from app.core.logger import logger

# ===== mcp 版本探测 =====
# 通过尝试导入 v2 特有的函数名来判断；失败则回退到 v1。
# 这里也兼容 httpx2 模块存在与否（v2 必带 httpx2，v1 用 httpx）。
# 探测结果只在 import 时确定一次，进程内不重复探测。
# 用 ImportError 而非版本号比较：版本探测比依赖声明更可靠。
try:
    import httpx2  # noqa: F401
    # v2 的蛇形命名：streamable_http_client；导入成功即认定 v2 环境。
    from mcp.client.streamable_http import streamable_http_client as _streamable_http_client
    _MCP_V2 = True
except ImportError:
    # v1 的驼峰命名：streamablehttp_client；两者功能等价仅签名不同。
    from mcp.client.streamable_http import streamablehttp_client as _streamable_http_client
    _MCP_V2 = False

# ClientSession 两个版本接口一致，放探测之后统一导入（E402 豁免）。
from mcp.client.session import ClientSession  # noqa: E402

# 启动时打一条版本探测日志：部署排障时一眼确认跑的是哪条路径。
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
    任何一步失败都返回 None，绝不抛异常给调用方。
    """
    url = mcp_config.mcp_base_url
    # 缺少密钥时直接短路，避免发一次必然 401 的无效请求
    # 返回 None（而非抛异常）：联网检索是可选增强，失败由上层兜底。
    # URL 与密钥均来自 conf/bailian_mcp_config.py，本文件不硬编码。
    if not mcp_config.api_key:
        logger.error(
            "[BailianMCP] 未配置百炼 API Key（MCP_DASHSCOPE_API_KEY），跳过本次 MCP 调用"
        )
        return None
    # Bearer 鉴权头：两个 mcp 版本的传参方式不同（见下方分支），头本身一致。
    headers = {"Authorization": f"Bearer {mcp_config.api_key}"}

    logger.info(f"[BailianMCP] 正在连接百炼 WebSearch 服务: {url}")

    try:
        # 分支一：mcp v2 路径；分支二：mcp v1 路径（见 else）。
        # 两条路径的目标一致：拿到 CallToolResult；差异只在 SDK 签名。
        if _MCP_V2:
            # mcp v2：通过 httpx2.AsyncClient 传 headers
            # httpx2 是 v2 的依赖分叉：不复用 httpx，避免版本冲突。
            import httpx2 as _httpx
            # 连接超时 60s / 读超时 300s：搜索工具响应可能较慢，读窗口放宽。
            http_client = _httpx.AsyncClient(
                headers=headers,
                timeout=_httpx.Timeout(60.0, read=300.0),
            )
            async with http_client:
                # v2 返回二元组 (read, write)
                # terminate_on_close=False：连接由 http_client 上下文统一关闭，
                # 避免 transport 与客户端双重关闭引发竞态。
                async with _streamable_http_client(
                    url, http_client=http_client, terminate_on_close=False
                ) as streams:
                    # 解包读写流：read_stream 收响应，write_stream 发请求。
                    read_stream, write_stream = streams
                    async with ClientSession(read_stream, write_stream) as session:
                        # 会话必须先握手（MCP 协议要求），否则 call_tool 会被拒。
                        await session.initialize()
                        # 手动 initialize 是关键差异：百炼协议较旧，
                        # 新版 mcp 的自动 discover 流程会在这里失败。
                        # 握手完成后才具备调用工具的会话状态。
                        logger.info(
                            f"[BailianMCP] 连接成功，正在调用工具 'bailian_web_search' 查询: {query}"
                        )
                        # v2 用关键字参数：name=..., arguments=...（签名变化点）。
                        # count=5：每次取 5 条结果，够上层筛选且控制响应体积。
                        result = await session.call_tool(
                            name="bailian_web_search",
                            arguments={"query": query, "count": 5},
                        )
                        # 走到这里说明调用成功：返回 CallToolResult 交上层解析。
                        logger.info("[BailianMCP] 工具调用完成，已获取返回结果")
                        return result
        else:
            # mcp v1：直接传 headers，ClientSession 接受位置参数 name
            # v1 返回三元组 (read, write, get_session_id_callback)
            # timeout/sse_read_timeout 的语义与 v2 的 Timeout(60, read=300) 对齐。
            async with _streamable_http_client(
                url, headers=headers, timeout=60.0, sse_read_timeout=300.0
            ) as streams:
                # 第三个返回值（会话 ID 回调）本场景用不到，丢弃。
                read_stream, write_stream, _ = streams
                session = ClientSession(read_stream, write_stream)
                async with session:
                    await session.initialize()
                    logger.info(
                        f"[BailianMCP] 连接成功，正在调用工具 'bailian_web_search' 查询: {query}"
                    )
                    # v1 call_tool 是位置参数 name
                    # 参数字典与 v2 完全一致，业务语义统一。
                    result = await session.call_tool(
                        "bailian_web_search",
                        {"query": query, "count": 5},
                    )
                    # 走到这里说明调用成功：返回 CallToolResult 交上层解析。
                    logger.info("[BailianMCP] 工具调用完成，已获取返回结果")
                    return result

    except Exception as e:
        # ExceptionGroup 嵌套时递归展开，看到最深层真实异常
        # 异步上下文管理器抛错常被包装成 ExceptionGroup，直接打印只能看到外壳。
        # 不在此重试：MCP 调用是用户请求路径上的可选增强，重试应由上层策略决定。
        def _flatten_exc(exc, depth=0) -> None:
            # exceptions 属性非空说明是组异常，递归展开子异常。
            sub = getattr(exc, "exceptions", None)
            if sub:
                for s in sub:
                    _flatten_exc(s, depth + 1)
            else:
                # 叶子异常：打类型与消息，嵌套深度标注便于阅读。
                logger.error(
                    f"[BailianMCP] 异常链{' (嵌套)' if depth else ''}: "
                    f"{type(exc).__name__}: {exc}"
                )
        # 先展开叶子异常链，再打印原始异常与完整堆栈。
        _flatten_exc(e)
        logger.error(f"[BailianMCP] 调用过程中发生异常: {e}", exc_info=True)
        # 统一吞掉异常返回 None：调用方只需判 None，无需感知网络细节。
        return None


async def search_web(query: str) -> Any | None:
    """
    对外统一入口：调用百炼 WebSearch MCP 搜索指定 query。

    :param query: 搜索查询词（通常是经过改写后的精准 Query）
    :return: CallToolResult 对象（v1/v2 都有 .content / .is_error or .isError），失败时返回 None
    上层（如 node_web_search_mcp 节点）拿 content 里的网页摘要转成检索证据。
    """
    # 直接委托内部实现：对外只暴露一个稳定签名，版本差异全部内化。
    return await _call_tool_bailian_web_search(query)


def is_error_result(result: Any) -> bool:
    """
    统一读取 CallToolResult 的错误标志，兼容 v1 (isError) 和 v2 (is_error)。
    对外屏蔽版本差异：上层只认这一个函数，不直接摸结果对象字段。
    """
    if result is None:
        # None 视为错误：上层把「调用失败」与「服务报错」同等处理。
        return True
    # v2 与 v1 的错误标志字段名不同，逐个探测。
    # hasattr 顺序：先 v2 后 v1，绝大多数部署环境命中第一个分支。
    if hasattr(result, "is_error"):
        return bool(result.is_error)
    if hasattr(result, "isError"):
        return bool(result.isError)
    # 都没有该字段时按成功处理：不因字段缺失误判失败。
    return False