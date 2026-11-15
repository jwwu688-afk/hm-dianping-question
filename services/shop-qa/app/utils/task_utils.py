from typing import Dict, List
from .sse_utils import push_to_session

"""
内存态任务进度追踪工具（单进程版）：为前端 SSE 进度推送提供数据源。

为什么用内存字典而不用 Redis：任务状态只服务于「本进程正在处理的这次请求」的
进度展示，生命周期与请求等长、无跨进程共享需求，内存表最简单且无网络开销；
代价是服务重启或多实例部署时进度不可见，若需水平扩展可平滑替换为 Redis 实现。

三张状态表 + 一张结果表的职责划分：
- _tasks_running_list：正在执行的节点名列表（进行中）；
- _tasks_done_list：已完成的节点名列表（按完成顺序追加）；
- _tasks_status：任务整体状态机（pending/processing/completed/failed）；
- _tasks_result：任务最终结果（如 answer），供流结束后前端轮询兜底取值。

progress 推送机制：add_running_task / add_done_task / update_task_status 在
is_stream=True 时调用 task_push_queue，把三张表的当前快照打包成 progress 事件
写入 SSE 队列，前端据此实时渲染「节点级」进度条。
"""

# ---------------------------
# 内存态任务追踪（单进程）
# ---------------------------
# key: task_id
# value: 节点名列表（原始英文/节点ID）
_tasks_running_list: Dict[str, List[str]] = {}
# 示例：{"task_123": ["node_rerank", "node_rrf"]}
# 表示 task_123 当前正在执行"重排序"和"倒排融合"两个节点
_tasks_done_list: Dict[str, List[str]] = {}
# 示例：{"task_123": ["upload_file", "node_entry", "node_pdf_to_md"]}
# 表示已经完成了文件上传、检查、PDF转换

# key: task_id
# value: status 字符串（如 pending/processing/completed/failed）
# 状态与节点列表分离：整体状态给轮询接口判断「是否结束」，节点列表给进度条展示
_tasks_status: Dict[str, str] = {}

# key: task_id
# value: 任务结果（例如 query 的 answer）
_tasks_result: Dict[str, Dict[str, str]] = {}

# 状态常量集中定义：调用方引用常量而非手写字符串，避免拼写不一致破坏状态机
TASK_STATUS_PENDING = "pending"
TASK_STATUS_PROCESSING = "processing"
TASK_STATUS_COMPLETED = "completed"
TASK_STATUS_FAILED = "failed"

# 节点名 -> 中文名映射（用于前端展示）
# 说明：这里的 key 应与 LangGraph 的 add_node("xxx", ...) 中的节点名一致。
_NODE_NAME_TO_CN: Dict[str, str] = {
    # --- Upload 流程 ---
    "upload_file": "开始上传文件",
    "node_entry": "检查文件",
    "node_pdf_to_md": "PDF转Markdown",
    "node_md_img": "Markdown图片处理",
    "node_item_name_recognition": "主体名称识别",
    "node_document_split": "文档切分",
    "node_bge_embedding": "向量生成",
    "node_import_kg": "导入知识图谱",
    "node_import_milvus": "导入向量库",
    "__end__": "处理完成",
    "END": "处理完成",
    # --- Query 流程节点（kb/query_process/main_graph.py）---
    "node_item_name_confirm": "确认问题产品",
    "node_answer_output": "生成答案",
    "node_rerank": "重排序",
    "node_rrf": "倒排融合",
    "node_web_search_mcp": "网络搜索",
    "node_search_embedding": "切片搜索",
    "node_search_embedding_hyde": "切片搜索(假设性文档)",
    "node_multi_search": "多路搜索",
    "node_query_kg": "查询知识图谱",
    "node_join": "多路搜索合并",
}


def _ensure_task(task_id: str) -> None:
    """确保 task_id 对应的数据结构已初始化。"""
    # 惰性初始化：调用方无需关心「先建任务再加节点」的顺序，读写前自动补齐三张表结构，
    # 避免直接操作空 key 抛 KeyError
    if task_id not in _tasks_running_list:
        _tasks_running_list[task_id] = []
    if task_id not in _tasks_done_list:
        _tasks_done_list[task_id] = []
    if task_id not in _tasks_result:
        _tasks_result[task_id] = {}


def _to_cn(node_name: str) -> str:
    """将节点名转换为中文展示名；若无映射则返回原名。"""
    return _NODE_NAME_TO_CN.get(node_name, node_name)


def add_running_task(task_id: str, node_name: str, is_stream: bool = False) -> None:
    """
    添加“正在运行”的节点任务。

    参数：
    - task_id: 任务ID
    - node_name: 节点名称(节点ID)
    """
    _ensure_task(task_id)
    running = _tasks_running_list[task_id]
    # 避免重复追加
    # LangGraph 节点可能被重试/子图重复进入，同名节点去重保证前端进度条不出现重复项
    if node_name not in running:
        running.append(node_name)

    if is_stream:
        # 只有流式会话才推送进度：非流式请求前端只关心最终结果，推送是多余的 IPC 开销
        task_push_queue(task_id)


def add_done_task(task_id: str, node_name: str, is_stream: bool = False) -> None:
    """
    添加“已完成”的节点任务。

    注意：添加已完成任务时，会把同名的“正在运行”任务删除。

    参数：
    - task_id: 任务ID
    - node_name: 节点名称(节点ID)
    """
    _ensure_task(task_id)

    # 1) 从 running 中移除同名节点（可能出现重复，移除所有）
    running = _tasks_running_list[task_id]
    _tasks_running_list[task_id] = [n for n in running if n != node_name]

    # 2) 追加到 done（保持完成顺序），避免重复
    done = _tasks_done_list[task_id]
    if node_name not in done:
        done.append(node_name)

    if is_stream:
        # 节点完成同样触发推送：前端进度条「完成一格」依赖 done 列表的变化事件
        task_push_queue(task_id)


def set_task_result(task_id: str, key: str, value: str) -> None:
    """
    存储任务结果字段（如 answer / error）。
    """
    _ensure_task(task_id)
    _tasks_result[task_id][key] = value


def get_task_result(task_id: str, key: str, default: str = "") -> str:
    """
    获取任务结果字段（如 answer / error）。
    """
    _ensure_task(task_id)
    return _tasks_result.get(task_id, {}).get(key, default)


def get_task_status(task_id: str) -> str:
    """
    获取当前任务状态。

    参数：
    - task_id: 任务ID

    返回：
    - str: 状态名称；如果未设置过则返回空字符串
    """
    # 返回空串而非 None：调用方可以直接做真值判断（if status），语义更顺
    return _tasks_status.get(task_id, "")


def get_done_task_list(task_id: str) -> List[str]:
    """
    获取已完成节点列表（中文展示）。


    """
    _ensure_task(task_id)
    # 统一转中文：进度数据出口处集中做映射，推送方/轮询接口无需各自处理国际化
    done = _tasks_done_list.get(task_id, [])
    return [_to_cn(n) for n in done]


def get_running_task_list(task_id: str) -> List[str]:
    """
    获取正在运行节点列表（中文展示）。

    """
    _ensure_task(task_id)
    # 与 done 列表同样统一转中文，保证前端展示口径一致
    running = _tasks_running_list.get(task_id, [])
    return [_to_cn(n) for n in running]


def update_task_status(task_id: str, status_name: str, push_queue: bool = False) -> None:
    """
    更新任务状态。

    参数：
    - task_id: 任务ID
    - status_name: 状态名称（字符串）
    """
    # 直接覆盖写：状态机允许回退（如重试把 completed 改回 processing），不做单向校验
    _tasks_status[task_id] = status_name
    if push_queue:
        # 状态变化通常意味着整轮任务结束/开始，push_queue 由调用方显式控制推送时机
        task_push_queue(task_id)


def task_push_queue(task_id: str):
    # 三张表快照一次性打包：前端一条 progress 事件同时拿到状态、已完成、进行中，
    # 避免「状态变了但列表没变」的中间态渲染
    push_to_session(task_id, "progress", {
        "status": get_task_status(task_id),
        "done_list": get_done_task_list(task_id),
        "running_list": get_running_task_list(task_id),
    })


#
# 会话结束/超时后由调用方显式清理：内存表只增不减会缓慢泄漏（单进程长期运行），
# 四张表一次性清空，pop 默认值防 task_id 不存在时抛 KeyError
def clear_task(task_id: str):
    _tasks_running_list.pop(task_id, None)
    _tasks_done_list.pop(task_id, None)
    _tasks_status.pop(task_id, None)
    _tasks_result.pop(task_id, None)