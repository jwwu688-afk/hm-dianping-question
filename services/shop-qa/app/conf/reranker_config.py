"""
重排序（reranker）模型配置：服务 lm/reranker_utils.py 的 FlagReranker 单例加载。

三个环境变量：
    BGE_RERANKER_LARGE   模型路径或 HF 仓库标识（本地目录优先）
    BGE_RERANKER_DEVICE  推理设备（cuda:0 / cpu）
    BGE_RERANKER_FP16    是否半精度推理（1/True/true 视为开启）
检索链路用该模型对「问题-文档」对打相关性分，实现精排。
"""
# 导入核心依赖：数据类、环境变量读取、路径处理
from dataclasses import dataclass
import os
from dotenv import load_dotenv

# 提前加载.env配置文件（保持和原代码一致，只需执行一次）
load_dotenv()

@dataclass
class RerankerConfig:
    """BGE 重排序模型的加载参数（三项均可空，由加载方决定兜底行为）。"""
    bge_reranker_large: str  # 本地模型路径
    bge_reranker_device: str       # 模型仓库标识
    bge_reranker_fp16: bool    # 是否开启半精度（1=True/0=False）

# 实例化配置对象，和原代码lm_config风格保持一致
reranker_config = RerankerConfig(
    bge_reranker_large=os.getenv("BGE_RERANKER_LARGE"),
    bge_reranker_device=os.getenv("BGE_RERANKER_DEVICE"),
    # 特殊处理：将.env中的1/0转为布尔值，兼容常见的数字/字符串格式
    # 注意：变量未配置时 in 判定为 False，即默认关闭半精度（CPU 场景安全）。
    bge_reranker_fp16=os.getenv("BGE_RERANKER_FP16") in ("1", "True", "true", 1)
)