"""
LLM（大语言模型）配置：为 lm/lm_utils.py 的 get_llm_client 提供供应商参数。

默认供应商：火山方舟（豆包系），承载问答生成、HyDE、图片摘要等所有通用调用；
另预留两个专用供应商槽位（均为可选，缺省自动回退默认供应商）：
    阿里云百炼（通义千问）：仅 item_name 识别节点使用
    火山方舟视觉模型：仅「图片摘要」节点使用
环境变量一览：
    OPENAI_BASE_URL / OPENAI_API_KEY   默认供应商端点与密钥（OpenAI 兼容协议）
    VL_MODEL                           默认视觉语言模型名
    LLM_DEFAULT_MODEL                  默认文本模型名
    LLM_DEFAULT_TEMPERATURE            采样温度（float）
    QWEN_BASE_URL / QWEN_API_KEY / ITEM_NAME_MODEL   百炼槽位
    VL_BASE_URL / VL_API_KEY           图片摘要视觉模型槽位
"""
# 导入核心依赖：数据类、环境变量读取、路径处理
from dataclasses import dataclass
import os
from dotenv import load_dotenv

# 提前加载.env配置文件（必须在读取环境变量前执行，确保os.getenv能获取到值）
# 若.env不在项目根目录，可指定路径：load_dotenv(dotenv_path=Path(__file__).parent / ".env")
load_dotenv()


# 定义minerU服务配置
# 类名沿用历史（注释笔误未更名）：实际承载的是 LLM 供应商配置。
@dataclass
class LLMConfig:
    """LLM 供应商配置对象：默认供应商必填，两个专用槽位可缺省。"""
    base_url: str
    api_key : str
    lv_model: str
    llm_model: str
    llm_temperature: float
    # ---- 第二供应商：阿里云百炼（通义千问）----
    # 用途：仅给 item_name 识别节点使用（node_item_name_recognition），
    #       与默认供应商（火山方舟/豆包）并存，互不影响。
    # 三个变量任一为空时，item_name 识别会自动回退到默认供应商，保证导入流程不中断。
    qwen_base_url: str = ""
    qwen_api_key: str = ""
    item_name_model: str = ""
    # ---- 图片摘要专用供应商：火山方舟（豆包视觉模型）----
    # 用途：仅给「图片摘要」节点（node_md_img.summarize_image）使用，
    #       通过 get_llm_client 的 base_url / api_key 参数实现跨供应商混搭。
    #       两者任一为空时，图片摘要会回退到默认供应商（主 LLM 的地址与密钥），
    #       不会因为缺配置直接报错。
    vl_base_url: str = ""
    vl_api_key: str = ""

# 实例化配置对象：import 即加载；温度显式转 float（.env 里是字符串）。
# 两个可选槽位用 or "" 兜底：os.getenv 返回 None 时统一为空串，
# 下游以「空串」判定槽位未启用并回退默认供应商。
lm_config = LLMConfig(
    # 默认供应商四项：端点/密钥/视觉模型/文本模型 + 采样温度。
    base_url=os.getenv("OPENAI_BASE_URL"),
    api_key=os.getenv("OPENAI_API_KEY"),
    lv_model=os.getenv("VL_MODEL"),
    llm_model=os.getenv("LLM_DEFAULT_MODEL"),
    # 温度转 float 失败会抛异常：属于配置错误，宁可启动失败也不静默。
    llm_temperature=float(os.getenv("LLM_DEFAULT_TEMPERATURE")),
    qwen_base_url=os.getenv("QWEN_BASE_URL") or "",
    qwen_api_key=os.getenv("QWEN_API_KEY") or "",
    item_name_model=os.getenv("ITEM_NAME_MODEL") or "",
    vl_base_url=os.getenv("VL_BASE_URL") or "",
    vl_api_key=os.getenv("VL_API_KEY") or "",
)
