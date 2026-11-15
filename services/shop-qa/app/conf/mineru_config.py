"""
MinerU PDF 转换服务配置：服务 document_pipeline.py 的「PDF 转 Markdown」环节。

MinerU 是 PDF 解析服务（OCR/版面识别/公式表格还原），
上传的 PDF 先经它转成 Markdown，再进入切分与向量化流程。
两个环境变量：
    MINERU_BASE_URL   MinerU 服务端地址（自建或官方 API）
    MINERU_API_TOKEN  调用凭证（自建部署可为占位值）
"""
# 导入核心依赖：数据类、环境变量读取、路径处理
from dataclasses import dataclass
import os
from dotenv import load_dotenv

# 提前加载.env配置文件（必须在读取环境变量前执行，确保os.getenv能获取到值）
# 若.env不在项目根目录，可指定路径：load_dotenv(dotenv_path=Path(__file__).parent / ".env")
load_dotenv()


# 定义minerU服务配置
@dataclass
class MineruConfig:
    """MinerU 服务连接参数：端点地址 + 调用凭证。"""
    base_url: str
    api_key : str

# 实例化配置对象：import 即加载，调用方直接用 mineru_config 单例。
mineru_config = MineruConfig(
    # 端点与密钥一一对应 .env 变量；未配置时值为 None，由调用方判空处理。
    base_url=os.getenv("MINERU_BASE_URL"),
    api_key=os.getenv("MINERU_API_TOKEN")
)