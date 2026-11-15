"""
联网搜索 MCP 兜底节点：本地知识库（Milvus）检索之外的第二信息来源。

为什么需要联网兜底：本地知识库只覆盖已导入的官方手册/FAQ，用户问题一旦涉及
新品、动态资讯或手册未写的细节，本地两路（Embedding/HyDE）检索都会空手而归；
该节点调用百炼 MCP 的 WebSearch 工具联网检索，返回的摘要结果与本地结果
一起进入后续 Rerank 节点统一重排——兜底内容与本地内容同台竞争，
而不是无条件追加，保证最终答案始终按相关性取材。

实现要点：
1. LangGraph 节点是同步函数，而 MCP SDK 的 search_web 是协程，
   这里用 asyncio.run() 做同步-异步桥接（每次调用独立事件循环，简单且无跨循环副作用）；
2. MCP 返回 CallToolResult，content[0].text 是一段 JSON 字符串，
   需二次解析为 {title, url, snippet} 扁平字典才能被 Rerank 阶段消费；
3. 搜索失败/为空时不抛异常，返回空 dict 让流程继续——联网只是兜底，
   不能因它挂掉而阻断问答主链路。
"""
import sys
import json
import asyncio
from app.utils.task_utils import add_done_task, add_running_task
from app.clients.bailian_mcp_client import search_web, is_error_result
from app.core.logger import logger


async def mcp_call(query):
    """
    异步调用百炼 MCP WebSearch 服务的入口函数。

    真正的连接 / 握手 / 工具调用细节封装在 app.clients.bailian_mcp_client 中，
    它自动适配 mcp v1 / v2 的 API 差异，并对百炼服务的 server/discover 不兼容
    问题做了"手动 initialize"的处理（详见该模块的注释）。

    :param query: 搜索查询词（通常是经过改写后的精准 Query）
    :return: MCP 返回的原始 CallToolResult (含 content、is_error 字段)，失败时返回 None
    """
    # 单独包一层而不是直接用 search_web：隔离 MCP 客户端模块，
    # 未来更换搜索供应商时只需改这一处
    return await search_web(query)


def node_web_search_mcp(state):
    """
    LangGraph同步节点函数：处理MCP搜索逻辑，作为整个搜索流程的入口。

    该节点会调用 mcp_call 异步函数获取搜索结果，并将其解析为结构化数据存储到 state 中。

    :param state: LangGraph的全局状态对象，包含 session_id, rewritten_query 等信息
    :return: 字典，包含结构化的搜索结果 web_search_docs，供后续节点使用
    """
    logger.info("---node_web_search_mcp 开始处理---")

    # 1. 标记任务开始
    add_running_task(state["session_id"], sys._getframe().f_code.co_name, state.get("is_stream"))

    # 2. 获取查询词
    query = state.get("rewritten_query", "")
    if not query:
        # 尝试回退到原始查询
        query = state.get("original_query", "")

    docs = []
    # 结果累积到局部 docs：无论中途是否异常，最终统一在函数尾部决定是否写入 state

    # 3. 执行搜索
    if query:
        try:
            # 同步-异步桥接：通过asyncio.run()执行异步的mcp_call函数
            # LangGraph 节点运行在同步上下文，asyncio.run 会新建事件循环并阻塞至协程完成
            logger.info(f"启动异步 MCP 调用，Query: {query}")

            # ======================================================================
            # MCP 返回结果格式解析说明
            # ----------------------------------------------------------------------
            # result 是一个 CallToolResult 对象 (定义在 mcp.types 中)
            # result.content 是一个 TextContent 对象的列表，通常只有一项
            # result.content[0].text 是一个 JSON 字符串，包含实际的搜索结果
            #
            # 示例数据结构：
            # result.content[0].text = """
            # {
            #   "pages": [
            #     {
            #       "title": "HAK 180 烫金机使用手册",
            #       "url": "http://example.com/manual",
            #       "snippet": "在出厂默认状态下，若想设置局部转印..."
            #     },
            #     ...
            #   ]
            # }
            # """
            # ======================================================================
            result = asyncio.run(mcp_call(query))

            # 4. 解析结果
            if result and not is_error_result(result) and result.content:
                # 解析MCP原始结果：提取文本内容并转为JSON对象
                # result.content 通常是一个列表，第一项包含文本结果
                raw_text = result.content[0].text
                try:
                    data = json.loads(raw_text)
                    pages = data.get("pages") or []
                    # or [] 兜底：MCP 返回结构中 pages 缺失时按空结果处理，不抛 AttributeError

                    logger.info(f"MCP 返回原始页面数量: {len(pages)}")

                    # 遍历结果，统一封装为结构化格式
                    # 逐页清洗：strip 去首尾空白、过滤无摘要项，
                    # 保证下游 Rerank 拿到的每条都有可用于打分的文本
                    for item in pages:
                        snippet = (item.get("snippet") or "").strip()
                        url = (item.get("url") or "").strip()
                        title = (item.get("title") or "").strip()

                        # 过滤无核心摘要的结果
                        if not snippet:
                            continue

                        docs.append({"title": title, "url": url, "snippet": snippet})

                except json.JSONDecodeError:
                    # JSON 解析失败只记录日志不中断：联网兜底是可选信息源，
                    # 单次解析失败不该拖垮整个问答链路（上游本地检索结果仍然可用）
                    logger.error(f"MCP 返回结果解析 JSON 失败: {raw_text[:100]}...")
            else:
                # 区分两类失败：服务端明确报错 vs 返回为空，日志口径不同便于排查
                if result and is_error_result(result):
                    logger.error(f"MCP 返回错误: {result}")
                else:
                    logger.warning("MCP 返回结果为空或无效")

            logger.info(f"结构化搜索结果数量: {len(docs)}")

        except Exception as e:
            # 兜底节点的总异常闸门：MCP 连接超时、SDK 异常等都在这里吃掉，
            # 绝不让联网失败炸穿 LangGraph 主图
            logger.error(f"MCP 搜索节点执行异常: {e}", exc_info=True)
    else:
        logger.warning("查询词为空，跳过 MCP 搜索")

    # 5. 标记任务结束
    add_done_task(state["session_id"], sys._getframe().f_code.co_name, state.get("is_stream"))

    logger.info("---node_web_search_mcp 处理结束---")

    # 若有有效搜索结果，返回结果供后续节点使用；无则返回空字典
    # 返回空 dict 而非 {"web_search_docs": []}：LangGraph 对空 dict 不做状态覆盖，
    # 避免空列表冲掉上游可能存在的同名数据
    if docs:
        return {"web_search_docs": docs}
    return {}


if __name__ == '__main__':
    # 测试代码：单独运行该文件时，验证MCP搜索功能是否正常
    # 依赖真实 MCP 连接：运行前需确认百炼 API Key 已配置、网络可达
    print("\n" + "="*50)
    print(">>> 启动 node_web_search_mcp 本地测试")
    print("="*50)

    test_state = {
        "session_id": "test_mcp_session",
        "rewritten_query": "HAK 180 烫金机使用说明",
        "is_stream": False
    }

    try:
        # 调用MCP搜索节点函数，执行测试
        result_state = node_web_search_mcp(test_state)

        print("\n" + "="*50)
        print(">>> 测试结果摘要:")
        search_results = result_state.get('web_search_docs', [])
        print(f"搜索结果数量: {len(search_results)}")
        if search_results:
            print("首条结果预览:")
            print(json.dumps(search_results[0], indent=2, ensure_ascii=False))
        else:
            print("未获取到搜索结果")
        print("="*50)

    except Exception as e:
        logger.exception(f"测试运行期间发生未捕获异常: {e}")