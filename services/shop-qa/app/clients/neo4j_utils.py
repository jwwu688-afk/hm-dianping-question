# os：从环境变量读取 Neo4j 连接三元组（URI/用户名/密码），凭证不落代码。
import os
# neo4j 官方 Python 驱动：GraphDatabase.driver 是唯一入口。
from neo4j import GraphDatabase

# 模块级驱动单例缓存：None 表示尚未创建。
# Neo4j Driver 内部自带连接池，全局共享一个实例即可，重复创建会浪费连接。
_neo4j_driver = None
def get_neo4j_driver() -> GraphDatabase:
    """
    获取 Neo4j 驱动实例（懒加载单例）。

    首次调用按环境变量 NEO4J_URI / NEO4J_USERNAME / NEO4J_PASSWORD 建立驱动，
    后续调用复用同一实例（Driver 线程安全，可跨协程共享）；
    本函数只创建驱动不建立真实连接——连接在首次 session() 使用时才发生，
    连接失败会在业务会话处暴露，而不是在这里。
    """
    global _neo4j_driver
    # 已有实例直接返回，避免重复建池。
    if _neo4j_driver is None:
        # auth 传 (用户名, 密码) 二元组；URI 常见形态 bolt://host:7687 或 neo4j://。
        _neo4j_driver = GraphDatabase.driver(os.getenv("NEO4J_URI"), auth=(os.getenv("NEO4J_USERNAME"), os.getenv("NEO4J_PASSWORD")))
    return _neo4j_driver