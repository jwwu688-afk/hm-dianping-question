"""
百炼 WebSearch MCP 的**独立**配置。

设计要点：MCP 的 Key 与端点完全独立，不与任何大模型调用共用。
    - MCP 端点：MCP_DASHSCOPE_BASE_URL
    - MCP 密钥：MCP_DASHSCOPE_API_KEY（推荐单独申请一把，专供 MCP 使用）
服务对象：clients/bailian_mcp_client.py（联网搜索的唯一配置来源）。

注意：**绝不能**回退到 OPENAI_API_KEY —— 那是火山方舟（豆包）的密钥，
拿它去请求阿里云百炼的 MCP 端点只会得到 401 Unauthorized。
"""
from dataclasses import dataclass
import os
# os 读取环境变量；dataclass 生成配置对象。

from dotenv import load_dotenv

from app.core.logger import logger

# import 即加载 .env：配置模块的唯一副作用，保证后续 getenv 拿到值。
load_dotenv()

# 内置默认端点（未配置 MCP_DASHSCOPE_BASE_URL 时兜底使用）
# 端点指向阿里云百炼官方 WebSearch MCP（streamable HTTP 协议）。
DEFAULT_MCP_BASE_URL = "https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp"


def mask_key(key: str) -> str:
    """密钥脱敏：只保留前 6 位，避免日志泄漏完整密钥"""
    # 空值兜底：未配置时显示「(空)」而不是空字符串，日志更易读。
    if not key:
        return "(空)"
    # 只暴露前缀：长度信息帮助确认密钥是否完整（复制遗漏会变短）。
    # 前 6 位不足以反推密钥，同时足够区分「用错了哪把 Key」。
    return f"{key[:6]}****（长度 {len(key)}）"


@dataclass
class McpConfig:
    """MCP 端点与密钥配置对象：密钥来源一并记录，便于排查 401 问题。"""
    mcp_base_url: str
    api_key: str
    api_key_source: str  # 密钥来自哪个环境变量，便于排查配置问题


def _resolve_api_key() -> tuple[str, str]:
    """
    解析 MCP 专用密钥，返回 (密钥, 来源说明)。
    取用优先级：
      1. MCP_DASHSCOPE_API_KEY —— 推荐：MCP 完全独立，与模型调用互不影响
      2. QWEN_API_KEY          —— 兜底：同属阿里云百炼平台，可用但会告警
    返回空串表示两者都未配置，调用方据此跳过 MCP 请求。
    """
    key = (os.getenv("MCP_DASHSCOPE_API_KEY") or "").strip()
    # strip 防止 .env 里尾随空格导致 401（最常见的配置事故）。
    # or "" 把 None 转成空串，让 strip 永远可调用。
    if key:
        return key, "MCP_DASHSCOPE_API_KEY"

    # 兜底路径：QWEN_API_KEY 同属百炼平台，签名协议一致所以能用。
    fallback = (os.getenv("QWEN_API_KEY") or "").strip()
    if fallback:
        # 用兜底密钥会与模型调用共用配额，告警提醒运维尽快独立配置。
        # 告警是 warning 而非 error：功能可用，只是隔离性变差。
        logger.warning(
            "[MCP配置] 未配置 MCP_DASHSCOPE_API_KEY，本次复用 QWEN_API_KEY（同属阿里云百炼）。"
            "若希望 MCP 与模型调用完全隔离，请在 .env 中单独配置 MCP_DASHSCOPE_API_KEY。"
        )
        return fallback, "QWEN_API_KEY(兜底)"

    # 两处都未配置：打错误日志指导申请路径，返回空串（调用方判空跳过 MCP）。
    # 空串而非抛异常：联网搜索缺失不应让服务起不来。
    logger.error(
        "[MCP配置] 未找到可用的百炼密钥：请在 .env 中配置 MCP_DASHSCOPE_API_KEY"
        "（申请地址 https://bailian.console.aliyun.com/ -> 右上角 API-KEY）。"
    )
    return "", "(未配置)"


def _resolve_base_url() -> str:
    """解析 MCP 端点，未配置时使用内置默认值"""
    # 同样 strip：端点带空格会让请求 404。
    url = (os.getenv("MCP_DASHSCOPE_BASE_URL") or "").strip()
    if url:
        return url
    # 默认端点兜底：官方 WebSearch MCP 地址，普通部署无需配置。
    # warning 级别：行为正常但提示当前用的是默认值。
    logger.warning(f"[MCP配置] 未配置 MCP_DASHSCOPE_BASE_URL，使用内置默认端点：{DEFAULT_MCP_BASE_URL}")
    return DEFAULT_MCP_BASE_URL


# import 时一次性解析密钥与端点（配置模块的固有模式）。
# 解析结果存模块级变量，避免单例构造时重复调用函数。
_api_key, _api_key_source = _resolve_api_key()

# 进程级单例：全项目共用这一份 MCP 配置（与 *_config 命名风格统一）。
mcp_config = McpConfig(
    # 端点解析兜底 + 密钥解析优先级，结果固化为进程级单例。
    mcp_base_url=_resolve_base_url(),
    api_key=_api_key,
    api_key_source=_api_key_source,
)

# 启动日志：端点 + 密钥来源 + 脱敏密钥，部署后一眼确认 MCP 配置是否生效。
logger.info(
    f"[MCP配置] 端点={mcp_config.mcp_base_url} | "
    f"密钥来源={mcp_config.api_key_source} | 密钥={mask_key(mcp_config.api_key)}"
)
