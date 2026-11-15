"""
项目日志工具类
基于loguru实现，支持.env配置控制台/文件双输出，自动生成logs/app_年月日.log
特性：
1. 配置驱动：通过.env开关输出、修改日志级别
2. 自动路径：文件日志默认输出到 项目根/logs/app_YYYYMMDD.log
3. 自动清理：按配置保留日志，自动删除过期文件
4. 中文友好：utf-8编码，彻底解决中文乱码
5. 异步安全：开启异步入队，支持多线程/异步场景，避免日志错乱
6. 开箱即用：项目所有模块直接导入logger即可使用
7. 位置终极精准：穿透loguru内部+工具类自身，完美显示业务模块实际调用位置
补充说明（为什么选 loguru 而非标准 logging）：
- logging 配置繁琐（handler/formatter/文件轮转都要手写），loguru 一行 add() 全搞定；
- 本模块在 import 时即完成初始化，业务方无需任何 setup 代码；
- enqueue=True 把日志写入交给后台线程，多线程/协程下也不会交叉错乱。
"""
import sys
import inspect
from pathlib import Path
import os
from dotenv import load_dotenv
from loguru import logger


# -------------------------- 第一步：加载.env配置文件 --------------------------
# 日志模块自身也要读配置：.env 的日志开关/级别在此处生效，先于任何 logger.add
load_dotenv()

# -------------------------- 第二步：读取.env配置（带默认值，防止配置缺失） --------------------------
# .lower() == "true" 的写法：把 True/true/TRUE 统一判真，布尔开关对大小写宽容
LOG_CONSOLE_ENABLE = os.getenv("LOG_CONSOLE_ENABLE", "True").lower() == "true"
# 级别统一 .upper()：DEBUG 与 debug 等价，loguru 只认大写级别名
LOG_CONSOLE_LEVEL = os.getenv("LOG_CONSOLE_LEVEL", "INFO").upper()
LOG_FILE_ENABLE = os.getenv("LOG_FILE_ENABLE", "True").lower() == "true"
LOG_FILE_LEVEL = os.getenv("LOG_FILE_LEVEL", "INFO").upper()
# 保留策略支持 loguru 时长语法（如 "7 days"、"1 week"），过期日志文件自动删除
LOG_FILE_RETENTION = os.getenv("LOG_FILE_RETENTION", "7 days")

# -------------------------- 第三步：定义日志路径（自动推导项目根） --------------------------
# logger.py 位于 app/core/ 下：parent(0)=core, parent(1)=app, parent(2)=项目根
# 这里不复用 path_util，避免 utils 层与 core 层之间形成循环导入
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
LOG_DIR = PROJECT_ROOT / "logs"
# 文件名内嵌 {time:YYYYMMDD}：loguru 按天生成新文件，配合 rotation="00:00" 零点切换
LOG_FILE_NAME = "app_{time:YYYYMMDD}.log"
LOG_FILE_PATH = LOG_DIR / LOG_FILE_NAME

# -------------------------- 第四步：定义日志格式（彩色、结构化、易读） --------------------------
# 格式中的 <green>/<cyan>/<level> 是 loguru 标记语言：
# 控制台模式下渲染为 ANSI 颜色码，文件模式下自动剥离，两个 sink 可共用同一格式
# {name}:{function}:{line} 用于定位日志来源（后续 fix_log_position 会修正为真实业务位置）
LOG_FORMAT = (
    "<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
    "<level>{level: <8}</level> | "
    "<cyan>{name: <20}</cyan>:<cyan>{function: <15}</cyan>:<cyan>{line: <4}</cyan> - "
    "<level>{message}</level>"
)

# -------------------------- 第五步：初始化日志配置（核心方法） --------------------------
def init_logger():
    """
    初始化全局日志配置
    1. 移除loguru默认控制台输出（避免重复打印）
    2. 根据.env配置开启/关闭控制台输出
    3. 根据.env配置开启/关闭文件输出（自动创建logs文件夹）
    4. 配置日志格式、级别、分割、保留策略
    :return: 配置完成的loguru logger实例
    """
    # 控制台与文件是两个独立 sink：可分别开关、分别设级别（如文件记 DEBUG、控制台只看 INFO）
    # 1. 移除loguru默认的控制台输出
    # loguru 预置了一个默认 sink，不移除会与下面 add 的重复打印双份日志
    logger.remove()

    # 2. 配置控制台输出（若.env开启）
    # enqueue=True：写日志进队列由后台线程落盘/打印，多线程并发下不阻塞业务、不错乱
    if LOG_CONSOLE_ENABLE:
        logger.add(
            sink=sys.stdout,
            level=LOG_CONSOLE_LEVEL,
            format=LOG_FORMAT,
            colorize=True,
            enqueue=True
        )

    # 3. 配置文件输出（若.env开启）
    # mkdir 提前建目录：loguru 不支持自动创建多级缺失目录，logs/ 不存在会直接报错
    if LOG_FILE_ENABLE:
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        logger.add(
            sink=LOG_FILE_PATH,
            level=LOG_FILE_LEVEL,
            format=LOG_FORMAT,
            # rotation="00:00"：每天零点切新文件，单文件不会无限膨胀
            # retention：过期文件自动清理，配合 .env 可调保留时长
            # encoding="utf-8"：强制 utf-8，Windows 默认 GBK 会让中文日志乱码
            rotation="00:00",
            retention=LOG_FILE_RETENTION,
            encoding="utf-8",
            enqueue=True,
            # backtrace：异常时打印完整调用栈，便于排查深层报错
            # diagnose：异常时展开变量值（生产环境可关闭，防止敏感信息落盘）
            backtrace=True,
            diagnose=True
        )

    return logger

# -------------------------- 第六步：初始化并终极修正全局logger --------------------------
# import 本模块时立即执行：业务方 from app.core.logger import logger 拿到的就是配置完成的实例
base_logger = init_logger()

def fix_log_position(record):
    """遍历调用栈，跳过loguru内部帧+工具类自身帧，提取业务代码实际调用位置"""
    # 为什么需要修正：logger 被封装后，loguru 记录的 {name}/{function}/{line} 是
    # 封装层内部的位置（loguru 源码或本文件），对业务排查毫无意义；
    # 通过 patch 在每条日志生成时回溯 inspect.stack()，找到第一个「业务帧」覆盖这些字段
    for frame in inspect.stack():
        # 终极过滤：排除loguru内部 + 排除工具类logger.py自身，直接定位业务模块
        if ("_logger.py" in frame.filename or frame.function == "_log") or "logger.py" in frame.filename:
            continue
        # 更新日志字段为业务代码实际位置
        # 同时兼容 / 与 \ 分隔：Windows 路径取 \ 前最后一段，POSIX 取 / 前最后一段
        record.update(
            name=frame.filename.split("/")[-1].split("\\")[-1],
            function=frame.function,
            line=frame.lineno
        )
        break

# 应用终极修复，导出全局可用的logger
# patch 返回新实例：必须在赋值后再导出，直接 patch 原地不改不会生效
logger = base_logger.patch(fix_log_position)

# -------------------------- 测试代码（验证修复效果） --------------------------
if __name__ == '__main__':
    logger.info("【测试】logger.py内部调用（仅测试，业务模块调用会显示正确文件名）")
    print(f"日志文件输出路径：{LOG_FILE_PATH}")