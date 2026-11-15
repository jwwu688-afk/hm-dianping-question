"""知识图谱检索节点：在 Neo4j 图谱中查询与问题相关的实体关系。

当前为教学占位实现：登记「运行中」进度 → 模拟耗时 → 标记完成。
真实实现应在此把查询到的实体/三元组整理成 kg_chunks 写回 state，
供后续 node_join 汇合与 RRF 融合使用。
节点名通过 sys._getframe 取当前函数名，自动登记到会话任务进度表，
前端 SSE 即可收到「图谱查询开始/结束」事件做逐节点打勾动效。
"""
import time
import sys
from app.utils.task_utils import add_running_task, add_done_task

def node_query_kg(state):
    """
    节点功能：在 Neo4j 知识图谱中查询实体关系。
    """
    print("=== node_query_kg 图谱查询处理 ===")
    # 登记当前节点为「运行中」，is_stream 决定是否同步推 SSE 事件
    add_running_task(state["session_id"], sys._getframe().f_code.co_name, state.get("is_stream"))

    # 模拟图谱查询的耗时；真实场景替换为 Neo4j 查询 + kg_chunks 写回
    time.sleep(1)
    # ...
    add_done_task(state['session_id'], sys._getframe().f_code.co_name, state.get("is_stream"))