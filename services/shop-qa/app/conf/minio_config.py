"""
MinIO 对象存储配置：为 clients/minio_utils.py 提供连接与桶参数。

每个字段对应一个环境变量（.env 或系统环境均可）：
    MINIO_ENDPOINT     MinIO 服务地址（host:port 形式）
    MINIO_ACCESS_KEY   访问密钥（S3 兼容 AK）
    MINIO_SECRET_KEY   秘密密钥（S3 兼容 SK）
    MINIO_BUCKET_NAME  默认存储桶（知识库文件专用）
    MINIO_IMG_DIR      桶内图片目录（文档解析产图的存放路径）
    MINIO_SECURE       是否走 HTTPS（True/False，字符串比较）
import 本模块即完成配置加载，调用方直接使用 minio_config 单例。
"""
# 导入核心依赖：数据类、环境变量读取、路径处理
from dataclasses import dataclass
import os
from dotenv import load_dotenv

# 提前加载.env配置文件（确保os.getenv能获取到MinIO相关配置）
load_dotenv()


# 定义MinIO对象存储服务配置（与LLMConfig风格一致，字段对应.env配置项）
@dataclass
class MinIOConfig:
    """MinIO 连接配置对象：六个字段与六个环境变量一一对应。"""
    endpoint: str    # MinIO服务地址（含http/https和端口）
    access_key: str  # MinIO访问密钥（对应MINIO_ACCESS_KEY）
    secret_key: str  # MinIO秘钥（对应MINIO_SECRET_KEY）
    bucket_name: str # MinIO默认存储桶名（知识库文件专用）
    minio_img_dir: str #Minio存储图片的文件夹
    minio_secure: bool # 是否使用ssl加密 http 还是 https


# 实例化MinIO配置对象，自动从.env读取配置并绑定
# minio_secure 用字符串比较转布尔：.env 里约定写 True/False。
minio_config = MinIOConfig(
    endpoint=os.getenv("MINIO_ENDPOINT"),
    access_key=os.getenv("MINIO_ACCESS_KEY"),
    secret_key=os.getenv("MINIO_SECRET_KEY"),
    bucket_name=os.getenv("MINIO_BUCKET_NAME"),
    minio_img_dir=os.getenv("MINIO_IMG_DIR"),
    # "True" 字面比较：只有显式写 True 才启用 HTTPS，其余一律 HTTP。
    minio_secure=os.getenv("MINIO_SECURE") == "True"
)