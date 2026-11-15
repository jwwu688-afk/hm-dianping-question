"""文档导入流水线入口节点模块。

负责工作流的参数初始化与输入校验，是 LangGraph 编排图（main_graph）的第一个节点：
1. 校验 local_file_path 非空，缺失时终止流程；
2. 按文件后缀（.pdf/.md）开启对应的解析开关，供条件路由选择分支；
3. 提取文件无后缀纯名称 file_title，作为贯穿全流程的业务标识。
被 main_graph 注册为 "node_entry"，执行后由 route_after_entry 条件路由决定后续走向。
"""
# 标准库：os（文件名/路径处理）、sys（动态获取当前函数名）
import os
import sys
# 分离文件主名与后缀的工具（如 doc.pdf → doc）
from os.path import splitext

# 项目统一日志工具
from app.core.logger import logger
# 状态定义与默认状态工厂：单元测试用它构造初始状态
from app.import_process.agent.state import ImportGraphState, create_default_state
# 状态格式化工具：日志打印时美化状态字典输出
from app.utils.format_utils import format_state
# 任务状态工具：登记/解除节点运行状态，供前端轮询监控
from app.utils.task_utils import add_running_task, add_done_task

# 入口节点函数：被 main_graph 注册为 "node_entry"，是整条流水线的第一个执行单元
# 返回更新后的状态字典，LangGraph 自动将新增键合并回全局状态
def node_entry(state: ImportGraphState) -> ImportGraphState:
    """
    LangGraph知识库导入工作流 - 入口节点
    核心职责：初始化参数校验 | 自动判断文件类型(PDF/MD) | 设置解析开关 | 提取业务标识
    入参：ImportGraphState - 必须包含 local_file_path(文件路径)、task_id(任务ID)
    出参：ImportGraphState - 新增/更新 is_pdf_read_enabled/is_md_read_enabled/pdf_path/md_path/file_title
    执行链路：__start__ → 本节点 → route_after_entry(条件路由) → 对应解析节点/流程终止
    """

    # 动态获取函数名避免硬编码
    func_name = sys._getframe().f_code.co_name

    # 节点启动日志，打印当前工作流状态
    logger.debug(f"【{func_name}】节点启动，\n当前工作流状态：{format_state(state)}")

    # 开始：记录节点运行状态
    add_running_task(state["task_id"], func_name)


    # 1. 核心参数提取与非空校验
    document_path = state.get("local_file_path", "")
    # 参数缺失属于致命输入错误：直接返回原状态，条件路由将因两个开关均关闭而终止流程
    if not document_path:
        logger.error(f"【{func_name}】核心参数缺失：工作流状态中未配置local_file_path，文件路径为空")
        return state

    # 2. 根据文件后缀判断类型，设置对应解析开关
    # 分支结构：.pdf/.md 之外的格式进入 else 分支仅告警
    if document_path.endswith(".pdf"):
        # PDF路径：需先经 MinerU 转换为 Markdown 再进入后续流程
        logger.info(f"【{func_name}】文件类型校验通过：{document_path} → PDF格式，开启PDF解析流程")
        # 打开PDF读取开关：条件路由据此走 node_pdf_to_md 分支
        state["is_pdf_read_enabled"] = True
        # 记录PDF路径，供转换节点读取原始文件
        state["pdf_path"] = document_path
    elif document_path.endswith(".md"):
        # MD路径：已是目标格式，跳过转换直达图片处理节点
        logger.info(f"【{func_name}】文件类型校验通过：{document_path} → MD格式，开启MD解析流程")
        # 打开MD读取开关：条件路由据此直接走 node_md_img 分支（跳过PDF转换）
        state["is_md_read_enabled"] = True
        # 记录MD路径，供图片处理节点读取
        state["md_path"] = document_path
    else:
        # 不支持的格式：两个开关都不打开，条件路由返回END终止工作流
        logger.warning(f"【{func_name}】文件类型校验失败：{document_path} → 不支持的格式，仅支持.pdf/.md")

    # 3. 提取文件无后缀纯名称，作为全局业务标识
    file_name = os.path.basename(document_path)
    # file_title 将贯穿全流程：切分归属标记、Milvus入库、检索过滤均依赖该字段
    state["file_title"] = splitext(file_name)[0]
    logger.info(f"【{func_name}】文件业务标识提取完成：file_title = {state['file_title']}")

    # 至此入口参数初始化完毕，交由条件路由 route_after_entry 决定下一跳节点
    # 结束：记录节点运行状态
    add_done_task(state["task_id"], func_name)

    # 节点完成日志，打印当前工作流状态
    logger.debug(f"【{func_name}】节点执行完成，\n更新后工作流状态：{format_state(state)}")

    return state

# 独立运行本模块时执行自测：模拟三种文件类型验证入口节点的开关设置逻辑
if __name__ == '__main__':

    # 单元测试：覆盖不支持类型、MD、PDF三种场景
    logger.info("===== 开始node_entry节点单元测试 =====")
    # 三个用例分别验证：未知格式不开启开关、MD开启MD开关、PDF开启PDF开关

    # 测试1: 不支持的TXT文件
    # 用例预期：两个开关均不开启，流程终止
    test_state1 = create_default_state(
        task_id="test_task_001",
        local_file_path="联想海豚用户手册.txt"
    )
    # 执行入口节点：TXT不会开启任何解析开关
    node_entry(test_state1)

    # 测试2: MD文件
    # 用例预期：开启is_md_read_enabled开关
    test_state2 = create_default_state(
        task_id="test_task_002",
        local_file_path="小米用户手册.md"
    )
    # 执行入口节点：走MD直传路径
    node_entry(test_state2)

    # 测试3: PDF文件
    # 用例预期：开启is_pdf_read_enabled开关
    test_state3 = create_default_state(
        task_id="test_task_003",
        local_file_path="万用表的使用.pdf"
    )
    # 执行入口节点：走PDF转换路径
    node_entry(test_state3)

    # 依次运行三个用例，观察日志中开关状态与file_title是否符合预期
    logger.info("===== 结束node_entry节点单元测试 =====")