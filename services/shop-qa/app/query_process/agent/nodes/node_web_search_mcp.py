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

    # 3. 执行搜索
    if query:
        try:
            # 同步-异步桥接：通过asyncio.run()执行异步的mcp_call函数
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

                    logger.info(f"MCP 返回原始页面数量: {len(pages)}")

                    # 遍历结果，统一封装为结构化格式
                    for item in pages:
                        snippet = (item.get("snippet") or "").strip()
                        url = (item.get("url") or "").strip()
                        title = (item.get("title") or "").strip()

                        # 过滤无核心摘要的结果
                        if not snippet:
                            continue

                        docs.append({"title": title, "url": url, "snippet": snippet})

                except json.JSONDecodeError:
                    logger.error(f"MCP 返回结果解析 JSON 失败: {raw_text[:100]}...")
            else:
                if result and is_error_result(result):
                    logger.error(f"MCP 返回错误: {result}")
                else:
                    logger.warning("MCP 返回结果为空或无效")

            logger.info(f"结构化搜索结果数量: {len(docs)}")

        except Exception as e:
            logger.error(f"MCP 搜索节点执行异常: {e}", exc_info=True)
    else:
        logger.warning("查询词为空，跳过 MCP 搜索")

    # 5. 标记任务结束
    add_done_task(state["session_id"], sys._getframe().f_code.co_name, state.get("is_stream"))

    logger.info("---node_web_search_mcp 处理结束---")

    # 若有有效搜索结果，返回结果供后续节点使用；无则返回空字典
    if docs:
        return {"web_search_docs": docs}
    return {}


if __name__ == '__main__':
    # 测试代码：单独运行该文件时，验证MCP搜索功能是否正常
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