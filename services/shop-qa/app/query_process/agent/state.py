"""LangGraph 问答流程的全局状态定义（整张图共享的「数据总线」）。

QueryGraphState 是 TypedDict 描述的状态结构：每个节点接收它、处理后把
各自负责的字段写回，LangGraph 按 key 合并更新，从而在节点之间传递
检索、排序、生成各阶段的中间结果。

字段按流程阶段分组：
- 输入：session_id / original_query（is_stream 控制是否走 SSE 推流）；
- 四路检索产物：embedding_chunks（普通向量）、hyde_embedding_chunks（HyDE
  假设文档）、kg_chunks（知识图谱）、web_search_docs（联网搜索）；
- 融合排序产物：rrf_chunks（RRF 候选融合）→ reranked_docs（BGE 精排 Top-K）；
- 生成产物：prompt（组装好的提示词）→ answer（最终答案）；
- 辅助字段：item_names（确认出的商品名）、rewritten_query（改写后问题）、
  history（历史对话）供查询改写与上下文组织参考。
"""
from typing_extensions import TypedDict
from typing import List


class QueryGraphState(TypedDict):
    """
    QueryGraphState 定义了整个查询流程中流转的数据结构。
    """
    session_id: str  # 会话唯一标识
    original_query: str  # 用户原始问题

    # 检索过程中的中间数据
    embedding_chunks: list  # 普通向量检索回来的切片
    hyde_embedding_chunks: list  # HyDE 检索回来的切片
    kg_chunks: list  # 图谱检索回来的切片
    web_search_docs: list  # 网络搜索回来的文档

    # 排序过程中的数据
    rrf_chunks: list  # RRF 融合排序后的切片
    reranked_docs: list  # 重排序后的最终 Top-K 文档

    # 生成过程中的数据
    prompt: str  # 组装好的 Prompt
    answer: str  # 最终生成的答案

    # 辅助信息
    item_names: List[str]  # 提取出的商品名称
    rewritten_query: str  # 改写后的问题
    history: list  # 历史对话记录
    is_stream: bool  # 是否流式输出标记