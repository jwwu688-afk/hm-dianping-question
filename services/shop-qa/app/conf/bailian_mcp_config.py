"""
百炼 WebSearch MCP 的**独立**配置。

设计要点：MCP 的 Key 与端点完全独立，不与任何大模型调用共用。
    - MCP 端点：MCP_DASHSCOPE_BASE_URL
    - MCP 密钥：MCP_DASHSCOPE_API_KEY（推荐单独申请一把，专供 MCP 使用）

注意：**绝不能**回退到 OPENAI_API_KEY —— 那是火山方舟（豆包）的密钥，
拿它去请求阿里云百炼的 MCP 端点只会得到 401 Unauthorized。
"""
from dataclasses import dataclass
import os

from dotenv import load_dotenv

from app.core.logger import logger

load_dotenv()

# 内置默认端点（未配置 MCP_DASHSCOPE_BASE_URL 时兜底使用）
DEFAULT_MCP_BASE_URL = "https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp"


def mask_key(key: str) -> str:
    """密钥脱敏：只保留前 6 位，避免日志泄漏完整密钥"""
    if not key:
        return "(空)"
    return f"{key[:6]}****（长度 {len(key)}）"


@dataclass
class McpConfig:
    mcp_base_url: str
    api_key: str
    api_key_source: str  # 密钥来自哪个环境变量，便于排查配置问题


def _resolve_api_key() -> tuple[str, str]:
    """
    解析 MCP 专用密钥，返回 (密钥, 来源说明)。
    取用优先级：
      1. MCP_DASHSCOPE_API_KEY —— 推荐：MCP 完全独立，与模型调用互不影响
      2. QWEN_API_KEY          —— 兜底：同属阿里云百炼平台，可用但会告警
    """
    key = (os.getenv("MCP_DASHSCOPE_API_KEY") or "").strip()
    if key:
        return key, "MCP_DASHSCOPE_API_KEY"

    fallback = (os.getenv("QWEN_API_KEY") or "").strip()
    if fallback:
        logger.warning(
            "[MCP配置] 未配置 MCP_DASHSCOPE_API_KEY，本次复用 QWEN_API_KEY（同属阿里云百炼）。"
            "若希望 MCP 与模型调用完全隔离，请在 .env 中单独配置 MCP_DASHSCOPE_API_KEY。"
        )
        return fallback, "QWEN_API_KEY(兜底)"

    logger.error(
        "[MCP配置] 未找到可用的百炼密钥：请在 .env 中配置 MCP_DASHSCOPE_API_KEY"
        "（申请地址 https://bailian.console.aliyun.com/ -> 右上角 API-KEY）。"
    )
    return "", "(未配置)"


def _resolve_base_url() -> str:
    """解析 MCP 端点，未配置时使用内置默认值"""
    url = (os.getenv("MCP_DASHSCOPE_BASE_URL") or "").strip()
    if url:
        return url
    logger.warning(f"[MCP配置] 未配置 MCP_DASHSCOPE_BASE_URL，使用内置默认端点：{DEFAULT_MCP_BASE_URL}")
    return DEFAULT_MCP_BASE_URL


_api_key, _api_key_source = _resolve_api_key()

mcp_config = McpConfig(
    mcp_base_url=_resolve_base_url(),
    api_key=_api_key,
    api_key_source=_api_key_source,
)

logger.info(
    f"[MCP配置] 端点={mcp_config.mcp_base_url} | "
    f"密钥来源={mcp_config.api_key_source} | 密钥={mask_key(mcp_config.api_key)}"
)
