# app/utils/path_utils.py
"""
项目路径定位工具：统一解决「如何可靠地找到项目根目录」的问题。

为什么不直接用相对路径：服务的启动方式多样（IDE 直接跑、uvicorn 托管、Docker 容器），
进程工作目录并不固定，相对路径会随启动方式漂移；因此统一以本文件的绝对位置
（Path(__file__)）为锚点向上推导，或把根目录下的 .env 文件当作「根目录标记」递归查找。
导入本模块即得到 PROJECT_ROOT 常量，供 logger、load_prompt 等模块拼接日志/模板路径。
"""
from pathlib import Path
from dotenv import load_dotenv
import os
from pathlib import Path

def get_path_dir(ps:int = 0)->Path:
    # 供需要「相对本文件上 N 级目录」的场景使用（如从 app/xxx.py 定位到 services/ 根），
    # 避免链式 .parent.parent.parent 既难读又容易数错层级
    """
    pathlib.Path 提供了 parents 属性，这是一个有序的路径上级目录迭代器，直接通过索引取值就能快速获取「上 N 级目录」，完美解决多层 .parent 繁琐的问题，这也是官方推荐的简化写法！
    核心规则：parents[N] 索引对应「向上的层级数」
    parents[0] → 等价于 .parent（当前路径的上 1 级目录）
    parents[1] → 等价于 .parent.parent（当前路径的上 2 级目录）
    parents[2] → 等价于 .parent.parent.parent（当前路径的上 3 级目录）
    以此类推，parents[N] → 直接获取上 N+1 级目录，索引越⼤，层级越靠上
    :param ps:
    :return:
    """
    dir_path = Path(__file__).parents[ps]
    return dir_path


def get_project_root(identifier: str = ".env") -> Path:
    # 查找策略按优先级分三步：环境变量 > 加载 .env > 递归向上找 .env 标记文件，
    # 前两级面向生产部署（运维显式指定根目录），第三级是开发环境的自动兜底
    # 第一步：优先读取环境变量（生产环境用）
    env_root = os.getenv("PROJECT_ROOT")
    if env_root and Path(env_root).absolute().exists():
        return Path(env_root).absolute()

    # 第二步：加载根目录的.env文件（为了后续逻辑，也可省略）
    # 从本文件所在目录开始逐级向上找 .env：找到即 load_dotenv 注入环境变量并停止
    current_dir = Path(__file__).absolute().parent
    while current_dir != current_dir.parent:
        # current_dir != current_dir.parent：到达文件系统根（如 C:\ 或 /）时两者相等，
        # 以此作为循环终止条件，防止越找越上导致死循环
        if (current_dir / identifier).exists():
            load_dotenv(dotenv_path=current_dir / identifier)
            break
        current_dir = current_dir.parent

    # 第三步：递归查找标识（兜底，开发环境用）
    # 必须重置 current_dir 重新向上找：上一步的 while 已经把游标走到了根目录
    current_dir = Path(__file__).absolute().parent
    while current_dir != current_dir.parent:
        # 与第二步相比仅少了 load_dotenv：这里只负责「定位」，返回目录供调用方使用
        if (current_dir / identifier).exists():
            return current_dir
        current_dir = current_dir.parent

    raise FileNotFoundError(f"未找到项目根目录标识「{identifier}」，且环境变量PROJECT_ROOT未配置")


# 导入即完成根目录定位：以 .env 为标记，其他模块直接 from ... import PROJECT_ROOT 使用
PROJECT_ROOT = get_project_root(".env")