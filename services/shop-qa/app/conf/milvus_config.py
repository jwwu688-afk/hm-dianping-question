"""
Milvus 向量数据库配置：为 clients/milvus_utils.py 提供连接与集合名参数。

四个环境变量：
    MILVUS_URL              Milvus 服务端地址（如 http://127.0.0.1:19530）
    CHUNKS_COLLECTION       文本切片集合（RAG 检索的主要数据源）
    ENTITY_NAME_COLLECTION  预留：实体名称集合（暂未启用）
    ITEM_NAME_COLLECTION    文档对应实体集合名（实体识别结果入库用）
注意：shop_qa/index.py 的统一知识库集合名走 KNOWLEDGE_COLLECTION 环境变量，
本配置面向 zgws 原有导入/查询链路，两套集合互不干扰。
"""
# 导入核心依赖（和其他配置类共用，只需导入一次）
from dataclasses import dataclass
import os
from dotenv import load_dotenv

# 提前加载.env配置文件（全局执行一次即可，无需重复写）
load_dotenv()

# ===================== 其他配置类（LLM/Embedding）可放在上方，保持原有代码不变 =====================
# ... 你的LLMConfig、EmbeddingConfig代码 ...

# 定义Milvus向量数据库配置类
@dataclass
class MilvusConfig:
    """Milvus 连接与集合命名配置：四个字段对应四个环境变量。"""
    milvus_url: str          # Milvus服务端连接地址
    chunks_collection: str   # 存储切片的集合名称
    entity_name_collection: str  # 预留-实体名称集合
    item_name_collection: str    # 存储文档对应实体类的集合名称

# 实例化Milvus配置对象（和其他配置对象命名风格统一）
# import 即加载成单例；字段未配置时为 None，由客户端层判空兜底。
milvus_config = MilvusConfig(
    # 四个集合名均来自 .env；chunks_collection 是检索链路的主集合。
    milvus_url=os.getenv("MILVUS_URL"),
    chunks_collection=os.getenv("CHUNKS_COLLECTION"),
    entity_name_collection=os.getenv("ENTITY_NAME_COLLECTION"),
    item_name_collection=os.getenv("ITEM_NAME_COLLECTION")
)