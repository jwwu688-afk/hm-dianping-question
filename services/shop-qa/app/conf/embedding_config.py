"""
Embedding 模型配置：适配 BGE-M3，服务 lm/embedding_utils.py 的向量化调用。

BGE-M3 同时输出稠密与稀疏两种向量（Milvus 混合检索的双路来源），
四个环境变量：
    BGE_M3_PATH   本地模型路径（离线部署优先）
    BGE_M3        HF 仓库标识（本地路径不存在时回退）
    BGE_DEVICE    推理设备（cuda:0 / cpu）
    BGE_FP16      半精度开关（1/True/true 视为开启）
"""
# 导入核心依赖：数据类、环境变量读取、路径处理
from dataclasses import dataclass
import os
from dotenv import load_dotenv

# 提前加载.env配置文件（保持和原代码一致，只需执行一次）
load_dotenv()

# 定义Embedding配置（适配BGE-M3的所有配置，类名embedding_config）
@dataclass
class EmbeddingConfig:
    """BGE-M3 向量模型的加载参数：路径、仓库标识、设备与精度。"""
    bge_m3_path: str  # 本地模型路径
    bge_m3: str       # 模型仓库标识
    bge_device: str   # 运行设备(cuda:0/cpu)
    bge_fp16: bool    # 是否开启半精度（1=True/0=False）

# 实例化配置对象，和原代码lm_config风格保持一致
embedding_config = EmbeddingConfig(
    bge_m3_path=os.getenv("BGE_M3_PATH"),
    bge_m3=os.getenv("BGE_M3"),
    bge_device=os.getenv("BGE_DEVICE"),
    # 特殊处理：将.env中的1/0转为布尔值，兼容常见的数字/字符串格式
    # 未配置时视为 False：CPU 推理不支持 fp16，默认关闭最安全。
    bge_fp16=os.getenv("BGE_FP16") in ("1", "True", "true", 1)
)