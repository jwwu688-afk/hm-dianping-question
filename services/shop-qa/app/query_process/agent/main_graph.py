"""LangGraph 问答主流程编排：把各节点装配成一张可执行的状态图。

拓扑结构（四路并发搜索 + 融合重排的经典 RAG 流水线）：

    node_item_name_confirm（入口：商品名确认 / 查询改写）
        ├─ answer 已就绪（反问用户或拒答）→ node_answer_output 直接输出
        └─ 正常 → node_multi_search（虚拟分叉点）
              ├→ node_search_embedding       普通向量检索
              ├→ node_search_embedding_hyde  HyDE 假设文档检索
              ├→ node_web_search_mcp         联网搜索（MCP 工具）
              └→ node_query_kg               知识图谱检索
           四路结果汇聚到 node_join（虚拟合并点）
        → node_rrf（RRF 候选融合）→ node_rerank（BGE 精排）
        → node_answer_output（生成最终答案）→ END

两个虚拟节点只做流程组织、没有业务逻辑：
- node_multi_search：LangGraph 中同一节点连出多条边即并发扇出，
  四条搜索边在同一个 super-step 内并行执行，各自把结果写回 state；
- node_join：汇合中转站，保证四路检索全部完成后才继续串行链路。
编译产物 query_app 被 api/query_service.py 导入并 invoke。
"""
from langgraph.graph import StateGraph, END
from app.query_process.agent.state import QueryGraphState
# 导入所有节点函数
from app.query_process.agent.nodes.node_item_name_confirm import node_item_name_confirm
from app.query_process.agent.nodes.node_query_kg import node_query_kg
from app.query_process.agent.nodes.node_answer_output import node_answer_output
from app.query_process.agent.nodes.node_rerank import node_rerank
from app.query_process.agent.nodes.node_rrf import node_rrf
from app.query_process.agent.nodes.node_search_embedding import node_search_embedding
from app.query_process.agent.nodes.node_search_embedding_hyde import node_search_embedding_hyde
from app.query_process.agent.nodes.node_web_search_mcp import node_web_search_mcp

# 初始化状态图
builder = StateGraph(QueryGraphState)

# 注册所有节点
builder.add_node("node_item_name_confirm", node_item_name_confirm)  # 确认商品
builder.add_node("node_multi_search", lambda x: x)  # 虚拟节点：多路搜索分叉点
builder.add_node("node_search_embedding", node_search_embedding)  # 向量搜索
builder.add_node("node_search_embedding_hyde", node_search_embedding_hyde)
builder.add_node("node_query_kg", node_query_kg)
builder.add_node("node_web_search_mcp", node_web_search_mcp)
builder.add_node("node_join", lambda x: {})  # 虚拟节点：多路搜索合并点
# node_join 返回空 dict：不做任何状态更新，纯粹等四路检索都到齐后再往下走
builder.add_node("node_rrf", node_rrf)  # 排序
builder.add_node("node_rerank", node_rerank)  # 重排
builder.add_node("node_answer_output", node_answer_output)  # 生成

# 虚拟节点的作用：作为流程的「分叉 / 合并中转站」，解决多分支流程的组织问题，本身无业务逻辑；
# lambda x:x 含义：接收 state 并原样返回，是最轻便的 “无逻辑传递” 方式；
# 普通函数替换：定义 def 函数名(state): return state 即可完全等价，优势是易扩展、易调试；

# 设置起点
builder.set_entry_point("node_item_name_confirm")


def route_after_item_confirm(state: QueryGraphState):
    """入口节点的条件路由：决定「直接输出」还是「进入四路检索」。

    判断依据是 state 里是否已经被写入了 answer 字段（见下方长注释）。
    """
    # 如果已有答案（Branch B/C），直接跳到输出
    if state.get("answer"):
        """
        这主要发生在 node_item_name_confirm 节点无法直接确定唯一的商品型号，从而需要“反问用户”或“拒绝回答”的场景。
        具体来说，有以下两种情况会导致 state 中直接出现 answer ，从而跳过后续的检索流程，直接输出：
        1. 多选一（反问用户） ：
        - 场景 ：用户问得太模糊（比如“华为P60”），系统发现数据库里有“华为P60 128G”和“华为P60 Art”两个型号，且置信度都不足以直接确认。
        - 处理 ：节点会生成一条反问句作为 answer ，例如：“您是想问以下哪个产品：华为P60 128G、华为P60 Art？请明确一下型号。”
        - 结果 ：此时不需要再去检索文档了，直接把这句话发给用户让他选。
        2. 查无此人（拒绝回答） ：

        - 场景 ：用户问了一个系统里压根没有的商品（比如“小米15”，但库里只有华为的数据），或者评分过低（<0.6）。
        - 处理 ：节点会生成一条拒绝句作为 answer ，例如：“抱歉，未找到相关产品，请提供准确型号以便我为您查询。”
        - 结果 ：同样不需要后续检索，直接结束流程。
        """
        return "node_answer_output"
    # 否则继续搜索流程
    return "node_multi_search"
    # 返回值是目标节点名，LangGraph 据此决定条件边的走向


# 1. 意图确认 -> (条件分叉) -> 多路搜索 / 答案输出
builder.add_conditional_edges(
    "node_item_name_confirm",
    route_after_item_confirm
)

# 2. 并发执行四路搜索
# 同一节点连出多条边 = 同一 super-step 内并行扇出
builder.add_edge("node_multi_search", "node_search_embedding")
builder.add_edge("node_multi_search", "node_search_embedding_hyde")
builder.add_edge("node_multi_search", "node_web_search_mcp")
builder.add_edge("node_multi_search", "node_query_kg")

# 3. 四路搜索 -> 结果合并
builder.add_edge("node_search_embedding", "node_join")
builder.add_edge("node_search_embedding_hyde", "node_join")
builder.add_edge("node_web_search_mcp", "node_join")
builder.add_edge("node_query_kg", "node_join")

# 4. 合并 -> 排序 -> 重排 -> 生成 -> 结束
builder.add_edge("node_join", "node_rrf")
builder.add_edge("node_rrf", "node_rerank")
builder.add_edge("node_rerank", "node_answer_output")
builder.add_edge("node_answer_output", END)

# 编译生成可执行的 Runnable 应用
# query_app.invoke(state) 即可跑完整条流水线（被 api/query_service.py 使用）
query_app = builder.compile()
