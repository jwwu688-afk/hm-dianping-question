"""PDF 转 Markdown 转换节点模块（node_pdf_to_md）。

基于 MinerU 云端解析服务将 PDF 转换为 Markdown，整体流程：
1. 校验 PDF 路径与输出目录（缺失/无效直接抛异常终止）；
2. 上传 PDF 至 MinerU（先申请预签名URL），按 batch_id 轮询解析任务直至完成；
3. 下载解析结果 ZIP 包并解压，按优先级（同名 → full.md → 首个）定位 MD 文件；
4. 将 MD 文件重命名为与 PDF 同名，路径与全文写入状态（md_path/md_content）。
MinerU 配置缺失或服务不可用均会抛出异常，由上层决定是否降级处理。
"""
# 系统库
# 标准库：os（路径与环境变量）
import os
# 标准库：sys（动态获取当前函数名，用于日志与任务登记）
import sys
# 标准库：time（轮询间隔控制与超时计时）
import time
# 第三方库：requests（调用MinerU HTTP接口：申请链接/上传/轮询/下载）
import requests
# 标准库：zipfile（解压MinerU返回的结果压缩包）
import zipfile
# 标准库：shutil（递归清理旧的解压目录）
import shutil
# 标准库：Path（面向对象的路径操作，替代字符串拼接）
from pathlib import Path

# 项目内部库
# 状态定义与默认状态工厂：主流程使用状态字典，单元测试用它构造初始状态
from app.import_process.agent.state import ImportGraphState, create_default_state
# 状态格式化工具：日志打印时美化状态字典输出
from app.utils.format_utils import format_state
# 任务状态工具：登记/解除节点运行状态，供前端轮询监控
from app.utils.task_utils import add_running_task, add_done_task
# MinerU配置对象：读取 .env 中的服务地址与API令牌
from app.conf.mineru_config import mineru_config
from app.core.logger import logger  # 统一日志工具

# MinerU配置（缓存配置信息）
# 解析服务基础地址：所有接口调用均以它为前缀
MINERU_BASE_URL = mineru_config.base_url
# API访问令牌：Bearer鉴权凭据，缺失时上传环节会直接失败
MINERU_API_TOKEN = mineru_config.api_key


# 路径校验的执行者：仅做参数合法性检查，不含任何IO转换逻辑
def step_1_validate_paths(state):
    """
    步骤1：校验PDF文件路径和输出目录
    核心职责：参数非空校验 | PDF文件有效性校验 | 输出目录自动创建
    返回：合法的PDF文件Path对象、输出目录Path对象
    异常：ValueError(参数缺失)、FileNotFoundError(文件无效)
    """
    # 日志前缀：标记当前步骤，便于在日志流中定位问题
    log_prefix = "[step_1_validate_paths] "
    # 读取状态中的PDF路径与输出目录（去除首尾空白，容忍配置中的误加空格）
    pdf_path = state.get("pdf_path", "").strip()
    local_dir = state.get("local_dir", "").strip()

    # 参数非空校验
    # pdf_path 缺失属于配置错误，直接抛出
    if not pdf_path:
        raise ValueError(f"{log_prefix}工作流状态缺失有效参数：pdf_path，当前值：{repr(pdf_path)}")
    # 输出目录同样不允许为空
    if not local_dir:
        raise ValueError(f"{log_prefix}工作流状态缺失有效参数：local_dir，当前值：{repr(local_dir)}")

    # 转换为Path对象统一处理路径
    pdf_path_obj = Path(pdf_path)
    # 输出目录同样转为Path对象
    output_dir_obj = Path(local_dir)

    # PDF文件有效性校验（存在且为文件，非目录）
    if not pdf_path_obj.exists():
        # 路径指向的对象不存在：抛出文件未找到异常
        raise FileNotFoundError(f"{log_prefix}PDF文件不存在，绝对路径：{pdf_path_obj.absolute()}")
    if not pdf_path_obj.is_file():
        # 路径存在但不是文件（如目录）：同样视为无效输入
        raise FileNotFoundError(f"{log_prefix}指定路径非文件（是目录），绝对路径：{pdf_path_obj.absolute()}")

    # 确保输出目录存在，不存在则递归创建
    if not output_dir_obj.exists():
        logger.info(f"{log_prefix}输出目录不存在，自动创建：{output_dir_obj.absolute()}")
        # parents=True：连同缺失的父目录一并创建
        output_dir_obj.mkdir(parents=True, exist_ok=True)

    # 校验全部通过：返回路径对象供后续步骤使用
    return pdf_path_obj, output_dir_obj


# 上传与轮询的执行者：内部封装配置校验/申请链接/上传重试/状态轮询四个子环节
def step_2_upload_and_poll(pdf_path_obj: Path, output_dir_obj: Path):
    """
    步骤2：上传PDF至MinerU并轮询解析任务状态
    核心流程：配置校验 → 获取上传链接 → 文件上传（含重试） → 任务轮询（直至完成/失败/超时）
    参数：pdf_path_obj-已校验的PDF Path对象；output_dir_obj-输出目录Path对象
    返回：解析结果ZIP包下载链接full_zip_url
    异常：ValueError(配置缺失)、RuntimeError(请求/上传失败)、TimeoutError(任务超时)
    """
    # 前置配置校验，拦截无效配置
    if not MINERU_BASE_URL or not MINERU_API_TOKEN:
        # 服务地址或令牌缺失：后续所有接口调用必然失败，提前终止并给出配置指引
        raise ValueError("MinerU配置缺失：请在.env中正确配置MINERU_BASE_URL和MINERU_API_TOKEN")
    # 配置齐全：进入实际处理环节
    logger.info(f"[配置校验] MinerU基础配置加载成功，开始处理文件：{pdf_path_obj.name}")

    # 构造请求头（符合HTTP规范，Bearer鉴权）
    request_headers = {
        # 声明请求体为JSON格式
        "Content-Type": "application/json",
        # Bearer令牌：MinerU API的鉴权方式
        "Authorization": f"Bearer {MINERU_API_TOKEN}"
    }

    # 1. 调用批量接口，获取上传Signed URL和任务batch_id
    # 批量任务申请接口：返回预签名上传链接与batch_id
    url_get_upload = f"{MINERU_BASE_URL}/file-urls/batch"
    # 请求体：文件名列表 + 解析模型版本
    req_data = {
        # files 数组：本流程每次仅提交一个文件
        "files": [{"name": pdf_path_obj.name}],
        "model_version": "vlm"  # 官方推荐解析模型
        # vlm 版本模型：版面/公式/表格识别效果更好
    }
    # 记录请求明细：便于排查接口地址/参数配置问题
    logger.debug(f"[获取上传链接] 调用接口：{url_get_upload}，请求参数：{req_data}")
    # 发起批量任务申请：30秒超时覆盖常规网络延迟
    resp = requests.post(url=url_get_upload, headers=request_headers, json=req_data, timeout=30)

    # 响应校验：先验HTTP状态，再验业务返回码
    if resp.status_code != 200:
        # HTTP层面失败（网络/鉴权问题）：直接抛出并附带响应内容
        raise RuntimeError(f"[获取上传链接] 网络请求失败，状态码：{resp.status_code}，响应内容：{resp.text}")

    # 解析JSON响应体，提取上传链接与任务ID
    resp_data = resp.json()
    # 业务码非0表示MinerU服务端拒绝了请求（如令牌无效）
    if resp_data["code"] != 0:
        raise RuntimeError(f"[获取上传链接] API业务错误，返回数据：{resp_data}")

    # 提取核心数据：上传链接和任务唯一标识
    # 预签名上传地址：PUT直传MinerU背后的对象存储
    signed_url = resp_data["data"]["file_urls"][0]
    # 任务批次ID：后续轮询解析状态的唯一凭据
    batch_id = resp_data["data"]["batch_id"]
    # 申请成功日志：输出batch_id便于在MinerU控制台追踪任务
    logger.info(f"[获取上传链接] 成功，batch_id：{batch_id}，上传链接已生成")

    # 2. 读取PDF二进制数据，准备上传
    logger.info(f"[文件上传] 开始读取PDF文件：{pdf_path_obj.name}")
    # 以二进制模式读取整个文件（上传接口要求完整字节流）
    with open(pdf_path_obj, "rb") as f:
        file_data = f.read()

    # 创建Session（复用TCP连接，禁用代理避免签名验证失败）
    upload_session = requests.Session()
    # 禁用系统代理：预签名URL必须直连，代理会破坏请求签名
    upload_session.trust_env = False

    try:
        # 首次上传：自动识别文件类型
        put_resp = upload_session.put(url=signed_url, data=file_data, timeout=60)
        # 重试逻辑：首次失败则强制指定PDF的Content-Type
        if put_resp.status_code != 200:
            # 记录首次失败的状态码，为重试提供依据
            logger.warning(f"[文件上传] 首次上传失败（状态码：{put_resp.status_code}），强制指定PDF类型重试")
            # 显式声明application/pdf：部分对象存储依赖Content-Type校验文件类型
            pdf_headers = {"Content-Type": "application/pdf"}
            # 携带PDF类型头重试上传
            put_resp = upload_session.put(url=signed_url, data=file_data, headers=pdf_headers, timeout=60)
            # 重试仍失败则抛出异常
            if put_resp.status_code != 200:
                raise RuntimeError(f"[文件上传] 重试后仍失败，状态码：{put_resp.status_code}，响应内容：{put_resp.text}")
        # 首次/重试上传均成功：记录入库完成
        logger.info(f"[文件上传] 成功，文件{pdf_path_obj.name}已存入云存储")
    except Exception as e:
        # 上传环节的任何网络异常统一包装为RuntimeError
        raise RuntimeError(f"[文件上传] 网络异常导致上传失败，错误信息：{str(e)}")
    finally:
        # 无论成败，关闭Session释放网络连接，避免资源泄漏
        upload_session.close()

    # 3. 根据batch_id轮询任务状态，直至完成/失败/超时
    poll_url = f"{MINERU_BASE_URL}/extract-results/batch/{batch_id}"
    # 轮询起始时间戳：用于计算累计耗时与超时判断
    start_time = time.time()
    # 最大超时10分钟：适配600页内PDF的解析耗时上限
    timeout_seconds = 600  # 最大超时时间10分钟（适配600页内PDF）
    # 轮询间隔3秒：平衡查询频率与服务端压力
    poll_interval = 3      # 轮询间隔3秒（平衡查询频率和服务端压力）
    logger.info(f"[任务轮询] 开始监控任务状态，batch_id：{batch_id}，最大超时：{timeout_seconds}s")

    # 无限循环轮询：终态（done/failed）或超时时退出
    while True:
        # 超时检查：超过最大时间直接终止轮询
        elapsed_time = time.time() - start_time
        # 累计耗时超限：抛出TimeoutError，避免任务卡死占用资源
        if elapsed_time > timeout_seconds:
            raise TimeoutError(f"[任务轮询] 超时！任务处理超{int(timeout_seconds)}秒，batch_id：{batch_id}")

        # 发起轮询请求，短超时10秒，异常则重试
        try:
            poll_resp = requests.get(url=poll_url, headers=request_headers, timeout=10)
        except Exception as e:
            # 请求异常（超时/连接中断）：记录后等待重试
            logger.warning(f"[任务轮询] 网络请求异常，{poll_interval}秒后重试：{str(e)}")
            # 网络抖动属临时故障：等待后重试而非立即失败
            time.sleep(poll_interval)
            continue

        # 处理HTTP响应错误：5xx服务端繁忙则重试，其他错误直接抛出
        if poll_resp.status_code != 200:
            # 5xx为服务端临时故障：等待后重试
            if 500 <= poll_resp.status_code < 600:
                logger.warning(f"[任务轮询] 服务端繁忙（状态码：{poll_resp.status_code}），{poll_interval}秒后重试")
                time.sleep(poll_interval)
                # 等待后进入下一轮轮询
                continue
            else:
                # 4xx等其他状态码为请求层面错误：重试无意义，直接失败
                raise RuntimeError(f"[任务轮询] HTTP请求失败，状态码：{poll_resp.status_code}，响应内容：{poll_resp.text}")

        # 解析轮询结果，校验业务状态
        poll_data = poll_resp.json()
        # 业务码异常：轮询请求本身被服务端拒绝
        if poll_data["code"] != 0:
            raise RuntimeError(f"[任务轮询] API业务错误，返回数据：{poll_data}")

        # 解析结果列表：包含每个文件的处理状态
        extract_results = poll_data["data"]["extract_result"]
        # 结果暂空，继续轮询
        if not extract_results:
            # 中间态日志：记录已耗时便于判断是否卡死
            logger.debug(f"[任务轮询] 结果暂为空，已耗时{int(elapsed_time)}s，继续等待")
            time.sleep(poll_interval)
            # 空结果属正常中间态：等待下一轮
            continue
        # 解析任务状态，分支处理
        # 取第一个文件的结果（本流程单文件提交，仅一个元素）
        result_item = extract_results[0]
        # 读取任务状态字段：done/failed/处理中
        state_status = result_item["state"]
        # 状态1：任务完成，提取ZIP下载链接
        if state_status == "done":
            # 终态之一：输出总耗时后提取结果
            logger.info(f"[任务轮询] 解析任务完成！总耗时：{int(elapsed_time)}s，batch_id：{batch_id}")
            # 从结果项中提取结果压缩包的下载地址
            full_zip_url = result_item.get("full_zip_url")
            # 完成但无下载链接属于服务端异常结果：直接失败
            if not full_zip_url:
                raise RuntimeError("[任务轮询] 任务完成但未返回ZIP包下载链接，batch_id：{batch_id}")
            logger.info(f"[任务轮询] 结果ZIP包下载链接：{full_zip_url}...")
            # 链接已获取：本流程在此返回终态结果
            # 终态返回：ZIP包下载链接交给步骤3
            return full_zip_url
        # 状态2：任务失败，提取错误信息抛出
        elif state_status == "failed":
            # 读取服务端返回的错误描述（缺失时使用占位文案）
            err_msg = result_item.get("err_msg", "未知错误，无具体信息")
            # 解析失败属于终态：抛出异常终止流程
            raise RuntimeError(f"[任务轮询] 解析任务失败，batch_id：{batch_id}，错误信息：{err_msg}")
        # 状态3：处理中，实时打印进度（覆盖当前行）
        else:
            # 用 \r 覆盖同一行输出，避免日志刷屏
            logger.debug(
                f"[任务轮询] 处理中（已耗时{int(elapsed_time)}s），状态：{state_status} | 刷新间隔{poll_interval}s",
                end="\r"
            )
            # 未到终态：等待下一轮轮询
            time.sleep(poll_interval)

# 结果下载与提取的执行者：ZIP下载 → 解压 → MD定位 → 重命名
def step_3_download_and_extract(zip_url: str, output_dir_obj: Path, pdf_stem: str) -> str:
    """
    步骤3：下载MinerU解析结果ZIP包并解压，提取目标MD文件（重命名统一规范）
    核心流程：下载ZIP → 清理旧目录并解压 → 查找MD文件（按优先级） → 重命名统一为PDF同名
    参数：zip_url-ZIP包下载链接；output_dir_obj-输出目录Path；pdf_stem-PDF无后缀纯名称
    返回：最终MD文件的字符串格式绝对路径
    异常：RuntimeError(下载失败)、FileNotFoundError(无MD文件)
    """
    # 步骤启动日志：标记结果处理阶段开始
    logger.info(f"===== 开始处理[{pdf_stem}]的MinerU解析结果 =====")

    # 1. 下载解析结果ZIP包，120秒超时适配大文件
    # 输出下载链接便于追踪
    logger.info(f"[步骤1/4] 开始下载ZIP包，链接：{zip_url}...")
    # 下载结果压缩包（大文件放宽超时至120秒）
    resp = requests.get(zip_url, timeout=120)
    # 下载失败直接终止：无包可解压
    if resp.status_code != 200:
        # 状态码异常：附带状态码方便定位
        raise RuntimeError(f"[步骤1/4] ZIP包下载失败，HTTP状态码：{resp.status_code}")

    # 拼接ZIP包保存路径，按PDF名称唯一命名
    zip_save_path = output_dir_obj / f"{pdf_stem}_result.zip"
    # 将响应字节流写入本地ZIP文件
    with open(zip_save_path, "wb") as f:
        f.write(resp.content)
    # 落盘成功：记录保存位置
    logger.info(f"[步骤1/4] ZIP包下载成功，保存路径：{zip_save_path}")

    # 2. 清理旧解压目录并解压ZIP包（避免旧文件干扰，为每个PDF创建专属目录）
    # 解压进度日志
    logger.info(f"[步骤2/4] 开始解压ZIP包...")
    # 每个PDF使用同名专属解压目录，避免相互覆盖
    extract_target_dir = output_dir_obj / pdf_stem

    # 清理旧目录，异常则警告不终止
    if extract_target_dir.exists():
        try:
            # 递归删除整个目录树，包括目录本身及其所有子目录和文件。
            shutil.rmtree(extract_target_dir)
            # 旧目录清理完成
            logger.info(f"[步骤2/4] 已清理旧的解压目录：{extract_target_dir}")
        except Exception as e:
            # 旧目录占用/权限不足时仅告警：新解压通常仍能完成
            logger.warning(f"[步骤2/4] 清理旧目录失败，可能不影响新文件解压：{str(e)}")

    # 重新创建解压目录
    extract_target_dir.mkdir(parents=True, exist_ok=True)

    # 核心解压操作，保留原目录结构
    with zipfile.ZipFile(zip_save_path, 'r') as zip_file_obj:
        # 解压全部内容到专属目录
        zip_file_obj.extractall(extract_target_dir)
    # 解压完成日志：输出目标目录便于人工核对
    logger.info(f"[步骤2/4] ZIP包解压完成，解压目录：{extract_target_dir}")

    # 3. 递归查找解压目录下所有MD文件（适配子目录结构）
    logger.info(f"[步骤3/4] 开始查找解压目录中的MD文件...")
    # rglob递归匹配：子目录中的MD文件也能被发现
    md_file_list = list(extract_target_dir.rglob("*.md"))
    # 未找到任何MD文件：说明解析结果异常，流程无法继续
    if not md_file_list:
        raise FileNotFoundError(f"[步骤3/4] 解压目录中未找到任何.md格式文件：{extract_target_dir}")
    # 找到MD文件：输出数量供核对解析产物
    logger.info(f"[步骤3/4] 共找到{len(md_file_list)}个MD文件，按优先级匹配目标文件")

    # 4. 按优先级匹配目标MD文件（同名→full.md→第一个，兜底避免流程中断）
    # 目标MD文件引用：初始为空，依次按三级优先级匹配
    target_md_file = None
    # 优先级1：与PDF纯名称完全同名的MD文件
    for md_file in md_file_list:
        # 文件主名与PDF主名一致即为最匹配目标
        if md_file.stem == pdf_stem:
            # 命中即采用：记录目标文件
            target_md_file = md_file
            # 最优先命中：直接采用，跳出循环
            logger.info(f"[步骤4/4] 匹配到优先级1目标：与PDF同名的MD文件 {target_md_file.name}")
            # 已确定目标，无需继续遍历
            break
    # 优先级2：MinerU默认生成的full.md（不区分大小写）
    if not target_md_file:
        # 优先级2遍历：查找MinerU默认主结果文件
        for md_file in md_file_list:
            # MinerU约定主结果文件名为full.md，大小写均兼容
            if md_file.name.lower() == "full.md":
                # 命中即采用：记录目标文件
                target_md_file = md_file
                # 次优先命中：MinerU标准产物，直接采用
                logger.info(f"[步骤4/4] 匹配到优先级2目标：MinerU默认文件 {target_md_file.name}")
                # 已确定目标，无需继续遍历
                break
    # 优先级3：兜底取第一个MD文件
    if not target_md_file:
        # 直接取第一个元素：保证有可用结果
        target_md_file = md_file_list[0]
        # 兜底取第一个：宁可内容不完全匹配也不中断流程
        logger.info(f"[步骤4/4] 未匹配到前两级目标，兜底取第一个MD文件 {target_md_file.name}")

    # 重命名MD文件：统一为PDF纯名称，便于后续流程处理（仅不同名时执行）
    if target_md_file.stem != pdf_stem:
        logger.info(f"[步骤4/4] 开始重命名MD文件，统一为PDF同名：{pdf_stem}.md")
        # 计算新路径：同目录下替换主名为PDF主名
        new_md_path = target_md_file.with_name(f"{pdf_stem}.md")
        # 重命名操作独立捕获异常：失败不影响主流程
        try:
            # 将磁盘上的文件进行重命名
            target_md_file.rename(new_md_path)
            # 更新变量引用
            target_md_file = new_md_path
            # 重命名成功：后续统一使用新路径
            logger.info(f"[步骤4/4] MD文件重命名成功：{pdf_stem}.md")
        # 捕获文件系统层面的重命名失败（如目标文件被占用）
        except OSError as e:
            # 重命名失败（如文件被占用）不影响流程：继续使用原文件名
            logger.warning(f"[步骤4/4] MD文件重命名失败，将使用原文件名继续流程：{str(e)}")

    # 转换为字符串绝对路径返回，适配后续仅支持字符串路径的函数
    final_md_path = str(target_md_file.absolute())
    # 整个结果处理流程收尾：输出最终路径
    logger.info(f"===== [{pdf_stem}]解析结果处理完成，最终MD文件路径：{final_md_path} =====")
    # 返回最终MD路径，供主节点写入状态
    return final_md_path

# 节点函数：被 main_graph 注册为 "node_pdf_to_md"，仅 PDF 分支会执行到本节点
def node_pdf_to_md(state: ImportGraphState) -> ImportGraphState:
    """
    LangGraph工作流节点：PDF转MD核心处理节点
    核心流程：路径校验 → MinerU上传解析 → 结果下载解压 → 读取MD内容并更新工作流状态
    参数：state-工作流状态对象，需包含pdf_path/local_dir/task_id
    返回：更新后的工作流状态，新增md_path/md_content
    """

    # 动态获取函数名避免硬编码
    func_name = sys._getframe().f_code.co_name

    # 节点启动日志，打印当前工作流状态
    logger.debug(f"【{func_name}】节点启动，\n当前工作流状态：{format_state(state)}")

    # 开始：记录节点运行状态
    # 登记信息供前端轮询展示节点级进度
    add_running_task(state["task_id"], func_name)


    try:
        # 步骤1：校验PDF路径和输出目录
        pdf_path_obj, output_dir_obj = step_1_validate_paths(state)

        # 步骤2：上传PDF至MinerU并轮询解析结果
        zip_url = step_2_upload_and_poll(pdf_path_obj, output_dir_obj)

        # 步骤3：下载ZIP包并提取MD文件
        md_path = step_3_download_and_extract(zip_url, output_dir_obj, pdf_path_obj.stem)

        # 更新工作流状态：记录MD文件路径和内容
        state["md_path"] = md_path
        # 转换成功：记录最终MD路径
        logger.info(f"【{func_name}】MD文件生成成功，路径：{md_path}")

        # 读取MD文件内容，捕获异常仅警告不终止
        try:
            # 读取转换后的MD全文，写入状态供切分节点使用
            with open(md_path, "r", encoding="utf-8") as f:
                state["md_content"] = f.read()
            logger.debug(f"【{func_name}】MD文件内容读取成功，内容长度：{len(state['md_content'])}字符")
        except Exception as e:
            # 内容读取失败仅告警：md_path已可用，下游可重新读取
            logger.error(f"【{func_name}】读取MD文件内容失败：{str(e)}")

        # 节点收尾日志
        logger.info(f"【{func_name}】节点执行完成，更新后工作流状态键：{list(state.keys())}")

    except Exception as e:
        # 异常日志分级，精准提示配置问题
        # 主流程异常统一在此处理：记录堆栈后重新抛出
        logger.error(f"【{func_name}】PDF转MD流程执行失败：{str(e)}", exc_info=True)
        # 重新抛出异常：终止工作流，避免下游节点拿到不完整状态
        raise  # 抛出异常，终止工作流
    finally:

        # 结束：记录节点运行状态
        add_done_task(state["task_id"], func_name)

        # 节点完成日志，打印当前工作流状态
        logger.debug(f"【{func_name}】节点执行完成，\n更新后工作流状态：{format_state(state)}")

    # 返回更新后的状态（md_path/md_content 已写入）
    return state

if __name__ == "__main__":

    # 单元测试：验证PDF转MD全流程
    logger.info("===== 开始node_pdf_to_md节点单元测试 =====")

    # 测试依赖：项目根目录常量，用于拼接测试PDF路径
    from app.utils.path_util import PROJECT_ROOT
    # 打印根目录便于核对测试文件位置
    logger.info(f"测试获取根地址：{PROJECT_ROOT}")

    # 测试文件相对路径（需提前将测试PDF放入doc目录）
    test_pdf_name = os.path.join("doc", "万用表RS-12的使用.pdf")
    # 拼接测试文件的绝对路径
    test_pdf_path = os.path.join(PROJECT_ROOT, test_pdf_name)

    # 构造测试状态
    # 测试入参：任务ID、PDF路径、输出目录
    test_state = create_default_state(
        task_id="test_pdf2md_task_001",
        pdf_path=test_pdf_path,
        local_dir=os.path.join(PROJECT_ROOT, "output")
    )

    # 执行PDF转MD节点：验证校验/上传/轮询/解压全链路
    # 执行PDF转MD节点：验证校验/上传/轮询/解压全链路
    node_pdf_to_md(test_state)

    # 全部用例执行完毕
    logger.info("===== 结束node_pdf_to_md节点单元测试 =====")