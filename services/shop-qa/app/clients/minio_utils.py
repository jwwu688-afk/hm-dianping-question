# 导入Python内置模块
import os
import json
# json 用于把桶策略 dict 序列化为 JSON 字符串传给 SDK。
# os 备用于后续环境变量读取；当前配置统一走 minio_config。
# 导入MinIO官方Python SDK核心类
from minio import Minio
# 项目内部配置与日志
from app.conf.minio_config import minio_config
from app.core.logger import logger

# 全局MinIO客户端对象，初始化后供全项目调用
# import 时即初始化（模块级副作用）：失败不抛出而是置 None，
# 调用方通过 get_minio_client() 判空后自行降级。
# 桶名/凭证等参数全部来自 conf/minio_config.py，不在本文件硬编码。
minio_client = None

try:
    # 初始化MinIO客户端实例
    # endpoint 形如 host:port；secure=False 走 HTTP，仅限内网/本地部署。
    # Minio SDK 自带连通性保障：真正的网络异常要到桶操作时才暴露，由 except 兜底。
    minio_client = Minio(
        endpoint=minio_config.endpoint,
        access_key=minio_config.access_key,
        secret_key=minio_config.secret_key,
        secure=False  # 内网/本地部署用HTTP，公网部署需改为True并配置SSL
    )
    bucket_name = minio_config.bucket_name

    # 检查存储桶是否存在，不存在则自动创建
    # 幂等逻辑：重复启动不会因桶已存在而报错。
    # bucket_exists 有一次真实网络往返，失败同样落入 except 分支。
    # 创建动作只发生在首次部署；多实例并发创建时 MinIO 端会幂等处理。
    if not minio_client.bucket_exists(bucket_name):
        logger.info(f"MinIO存储桶[{bucket_name}]不存在，开始创建")
        minio_client.make_bucket(bucket_name)
        logger.info(f"MinIO存储桶[{bucket_name}]创建成功")
    else:
        # 桶已存在的分支只打日志：不重复设置策略是省一次网络往返，
        # 策略设置放在分支外，两个分支都会执行到。
        logger.info(f"MinIO存储桶[{bucket_name}]已存在，无需重复创建")

    # 配置存储桶公网只读策略：允许匿名用户通过URL直接访问桶内文件
    # 用途：文档解析产出的图片需以直链嵌入 Markdown，前端/模型无需带凭证。
    # 策略结构遵循 AWS S3 Policy 规范（MinIO 完全兼容）：版本号 + 授权语句列表。
    # Version 固定 "2012-10-17" 是 S3 策略的规范版本号，不是创建日期。
    bucket_policy = {
        "Version": "2012-10-17",
        "Statement": [{
            "Effect": "Allow",
            "Principal": {"AWS": ["*"]},  # *表示所有匿名用户（S3兼容标识）
            "Action": ["s3:GetObject"],   # 仅授权文件获取/访问操作，不开放上传与删除
            # ARN 格式固定：桶名 + /* 表示桶内所有对象；策略只影响本桶。
            "Resource": [f"arn:aws:s3:::{bucket_name}/*"]
        }]
    }
    # Resource 覆盖桶内全部对象（/* 通配）；粒度控制在单桶，不影响其他桶。
    # set_bucket_policy 只授 GetObject：写操作仍需带 access_key 的签名请求。
    minio_client.set_bucket_policy(bucket_name, json.dumps(bucket_policy))
    # 策略生效即完成：此后所有对象 URL（http://endpoint/bucket/object）可直接匿名访问。
    logger.info(f"MinIO存储桶[{bucket_name}]已配置公网只读策略，支持匿名URL访问")

except Exception as e:
    # 捕获初始化异常，记录错误日志并置空客户端
    # 网络不通/凭证错误都不让进程崩溃，服务可启动但文件功能不可用。
    # exc_info=True：把完整异常堆栈写进日志，便于定位是连接还是权限问题。
    logger.error(f"MinIO客户端初始化失败，错误信息：{str(e)}", exc_info=True)
    minio_client = None


def get_minio_client():
    """
    获取全局初始化的MinIO客户端实例
    :return: 已初始化的Minio对象 / None（初始化失败时）

    建议用法：client = get_minio_client() 后先判空再调用桶操作，
    否则 None 上调方法会抛 AttributeError。
    """
    # 返回 None 时调用方必须显式处理（降级/报错），本函数不做重试。
    return minio_client