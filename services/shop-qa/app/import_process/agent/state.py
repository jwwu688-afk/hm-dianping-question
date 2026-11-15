"""文档导入工作流的状态定义模块。

ImportGraphState 是 LangGraph 全流程的统一状态载体：所有节点的入参都是该字典，
节点返回的键值对会自动合并回状态，实现节点间数据传递。
graph_default_state 提供各字段的默认值；create_default_state/get_default_state
基于深拷贝生成独立状态实例，避免多任务共享同一字典导致数据串扰。
"""
# 类型工具：TypedDict 用于定义带类型提示的状态字典结构
from typing import TypedDict
# 深拷贝工具：复制默认状态生成独立实例，避免任务间共享可变字典
import copy
# 项目统一日志工具
from app.core.logger import logger

# 状态类定义：字段按「流程控制/路径/内容/数据库」分组，贯穿导入流水线全流程
class ImportGraphState(TypedDict):
    """
    图的状态定义，包含所有节点产生和消费的数据字段。
    TypedDict 让我们在代码中能有自动补全和类型检查。
    使用字典式访问（如state["session_id"]、state.get("embedding_chunks")）
    """
    task_id: str          # 任务唯一ID，用于追踪日志

    # --- 流程控制标记 ---
    is_md_read_enabled: bool   # 是否启用 Markdown 读取路径
    is_pdf_read_enabled: bool  # 是否启用 PDF 读取路径


    # --- 切块相关 ---
    is_normal_split_enabled: bool
    # 是否启用常规按标题切分策略（文档切分节点的主开关）
    is_silicon_flow_api_enabled: bool
    # 是否启用 SiliconFlow API 通道（大模型/高级切分能力开关）
    is_advanced_split_enabled: bool
    # 是否启用高级切分策略（语义级二次切分，长文档场景）
    is_vllm_enabled: bool
    # 是否启用 vLLM 本地推理加速（大模型部署方式开关）

    # --- 路径相关 ---
    local_dir: str        # 当前工作目录或输出目录
    local_file_path: str  # 原始输入文件路径
    file_title: str       # 文件标题（文件名去后缀）
    pdf_path: str         # PDF 文件路径 (如果输入是PDF)
    md_path: str          # Markdown 文件路径 (转换后或直接输入的)
    split_path: str       # 分块后的文件路径
    embeddings_path: str  # 向量数据库文件路径

    # --- 内容数据 ---
    md_content: str       # Markdown 的全文内容
    chunks: list          # 切片后的文本列表，包含 metadata
    item_name: str        # 识别出的主体名称 (如: "万用表")，用于增强检索
    # 商品名称是检索过滤的关键字段：识别后回填到所有切片并存入 Milvus

    # --- 数据库相关 ---
    embeddings_content: list # 包含向量数据的列表，准备写入 Milvus
    # 由 BGE 向量化节点产出：每个切片附带稠密/稀疏双向量


# 建议定一个初始化对象，方便后续使用
# 定义图状态的默认初始值
# 默认状态字典：键与 ImportGraphState 一一对应，值为各字段的初始值
graph_default_state: ImportGraphState = {
    "task_id":"",
    # --- 流程开关默认值：读取路径默认关闭（由入口节点按文件类型开启） ---
    "is_pdf_read_enabled": False,
    "is_md_read_enabled": False,
    "is_normal_split_enabled": True,
    "is_silicon_flow_api_enabled": True,
    "is_advanced_split_enabled": False,
    "is_vllm_enabled": False,
    # --- 路径与内容字段默认值：初始为空，由上传接口/入口节点注入实际值 ---
    "local_dir": "",
    "local_file_path": "",
    "pdf_path": "",
    "md_path": "",
    "file_title": "",
    "split_path": "",
    "embeddings_path": "",
    "md_content": "",
    # --- 切片与向量字段默认值：流程推进中由切分/识别/向量化节点逐步填充 ---
    "chunks": [],
    "item_name": "",
    "embeddings_content": []
}

# 状态工厂：对外统一入口，主图编译与单元测试均通过它构造初始状态
def create_default_state(**overrides) -> ImportGraphState:
    """
    创建默认状态，支持覆盖

    Args:
        **overrides: 要覆盖的字段（关键字参数解包）

    Returns:
        新的状态实例

    Examples:
        state = create_default_state(task_id="task_001", local_file_path="doc.pdf")
    """

    # 默认状态
    state = copy.deepcopy(graph_default_state)
    # 用 overrides 覆盖默认值
    state.update(overrides)
    # 返回创建好的状态字典实例
    return state

# 对外提供「全新状态实例」的工厂函数：与 create_default_state 的区别是不支持覆盖字段
def get_default_state() -> ImportGraphState:
    """
    返回一个新的状态实例，避免全局变量污染
    """
    return copy.deepcopy(graph_default_state)


# 独立运行本模块时执行简单的自测逻辑：创建默认状态并打印
if __name__ == "__main__":
    """
    测试
    """
    # 创建默认状态
    state = create_default_state(local_file_path="万用表RS-12的使用.pdf")
    logger.info(state)