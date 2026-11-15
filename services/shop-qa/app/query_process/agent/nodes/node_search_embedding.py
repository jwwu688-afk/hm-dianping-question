"""
Embedding 混合检索节点：对改写后的用户问题做 BGE-M3 稠密+稀疏双路混合检索。

检索策略与权重设计（为什么这么配）：
1. 稠密向量（WeightedRanker 权重 0.8）：BGE-M3 的语义向量，擅长同义改写、
   跨表述匹配，是「用户换个说法问同一个问题」仍能召回的根本保证；
2. 稀疏向量（权重 0.2）：BGE-M3 内置的 learned sparse（类 BM25 词权重），
   保留精确关键词（型号、错误码）的强匹配信号，补语义向量对生僻专有名词不敏感的短板。
   0.8:0.2 的配比意味着语义为主、关键词为辅——客服问答多数是自然语言描述，
   而非精确关键词命中；
3. item_name 过滤表达式：知识库按商品名分块存储，检索前用 `item_name in [...]`
   把召回范围收缩到已确认的商品，避免跨商品知识串台（A 商品手册内容混进 B 商品答案）。

召回与返回的数量关系：稠密、稀疏各先召回 10 条（reqs.limit=10），
归一化加权融合后只取 Top5 返回，多留候选给后续 Rerank 阶段精排。
本节点与 HyDE 节点（node_search_embedding_hyde）是并行的两路召回，结果由 RRF 融合。
"""
import sys
import os
from app.utils.task_utils import add_running_task,add_done_task
from app.lm.embedding_utils import generate_embeddings
from app.clients.milvus_utils import create_hybrid_search_requests,hybrid_search,get_milvus_client
from app.core.logger import logger
from dotenv import load_dotenv,find_dotenv
# 环境变量在模块加载时立即生效：CHUNKS_COLLECTION 在函数体内读取，必须先于首次调用加载好
load_dotenv(find_dotenv())


def node_search_embedding(state):
    """
    核心节点函数：基于已确认商品名+改写后的用户问题，执行Milvus向量数据库混合检索
    流程：用户问题向量化 → 构造带商品名过滤的混合搜索请求 → 执行稠密+稀疏混合检索 → 返回检索结果
    :param state: Dict - 会话状态字典，包含上游传递的核心信息，关键字段：
                  {
                      "session_id": str,        # 会话唯一标识
                      "rewritten_query": str,   # step3改写后的完整用户问题（含商品名）
                      "item_names": list[str],  # step6已确认的标准化商品名列表
                      "is_stream": bool/None    # 是否为流式响应，可选
                  }
    :return: Dict - 检索结果字典，仅包含embedding_chunks字段，供下游节点使用：
             {
                 "embedding_chunks": List[Dict]  # Milvus检索结果列表，无结果则为空列表
                                                 # 每个元素为一条匹配的向量数据，含业务字段
             }
    """
    logger.info("---search_milvus 开始处理---")
    add_running_task(state["session_id"],sys._getframe().f_code.co_name,state["is_stream"])

    # 1. 从会话状态中提取核心入参，为后续检索做准备
    query = state.get("rewritten_query")  # 提取改写后的用户问题（含商品名，独立完整）
    item_names = state.get("item_names")  # 提取已确认的标准化商品名列表（精准过滤用），这里可能有多个item_name!!!!!!
    
    logger.info(f"核心入参提取: query='{query}', item_names={item_names}")

    # 2. 对改写后的用户问题执行向量化，生成BGEM3稠密+稀疏向量
    logger.info(f"开始为文本获取嵌入值: {query[:50]}..." if len(query) > 50 else f"开始为“{query}”文本获取嵌入值...")
    # 调用向量化函数，入参为列表（支持批量，此处仅单条查询）
    # 生成与商品名匹配的语义向量，用于后续相似性检索
    embeddings = generate_embeddings([query])
    
    dense_vec = embeddings.get("dense")[0]
    sparse_vec = embeddings.get("sparse")[0]
    # generate_embeddings 返回批量结构（每条文本一个向量），这里单条查询故取下标 0
    # 打印稠密/稀疏向量日志，便于调试向量生成结果
    logger.debug(f"向量生成成功: dense_dim={len(dense_vec)}, sparse_len={len(sparse_vec)}")

    # 3. 准备Milvus向量数据库连接相关配置，指定检索的集合
    # 从环境变量中获取Milvus中存储「文本片段向量」的集合名（表名），避免硬编码
    collection_name = os.environ.get("CHUNKS_COLLECTION")
    logger.info(f"正在连接到 Milvus 并准备集合 '{collection_name}'...")

    # 4. 构造Milvus混合搜索请求对象（核心步骤）
    # 先通过辅助函数生成商品名过滤表达式，精准过滤检索范围
    # 'item_name in ["苹果15", "华为P60"]'

    # 若无商品名，直接返回None（不做过滤）
    # 商品名为空意味着上游没有确认到任何商品：全库检索必然跨商品串台，
    # 因此宁可返回空结果，把问题交给上游的反问/拒答分支处理
    if not item_names:
        logger.warning("item_names 为空，跳过检索，返回空结果")
        return {"embedding_chunks": []}
        
    # 对每个商品名添加双引号，拼接为Milvus支持的in语法格式
    quoted = ", ".join(f'"{v}"' for v in item_names)
    # item_names = ["苹果15", "华为P60"]
    # quoted = '"苹果15", "华为P60"'
    # 构造最终过滤表达式
    expr = f"item_name in [{quoted}]"
    # expr = 'item_name in ["苹果15", "华为P60"]'
    logger.info(f"创建搜索请求过滤表达式: {expr}")

    # 构造稠密+稀疏混合搜索请求，整合向量、过滤条件、搜索参数
    reqs = create_hybrid_search_requests(
        dense_vector=dense_vec,  # 取用户问题的稠密向量（单条，故取索引0）
        sparse_vector=sparse_vec,  # 取用户问题的稀疏向量（单条，故取索引0）
        expr=expr,  # 商品名过滤表达式，缩小检索范围（仅检索指定商品名的向量）
        limit=10  # 稠密、稀疏各自先召回 10 条。所有的item_name总共稠密+稀疏20条，底层检索返回数量（后续会再过滤为5，预留更多结果做重排序）
    )

    # 5. 执行Milvus稠密+稀疏混合向量检索（核心调用）
    logger.info("开始执行 Milvus 混合检索...")
    # 客户端全局单例复用：避免每次检索都重新建立 gRPC 连接
    client = get_milvus_client()
    res = hybrid_search(
        client=client,
        collection_name=collection_name,  # 检索的目标集合名（文本片段向量集合）
        reqs=reqs,  # 构造好的混合搜索请求对象（稠密+稀疏）
        ranker_weights=(0.8, 0.2),  # 0.8 给稠密（语义），0.2 给稀疏（关键词）。更看重语义相似，关键词只做辅助。
        norm_score=True,  # 开启评分归一化，将距离值转为0-1区间的相似度评分
        limit=5,  # 最终返回的TOP5相似度最高结果
        output_fields=["chunk_id", "content", "item_name"]  # 指定返回的业务字段
    )
    """
    融合过程是怎样的
    Milvus 的混合检索（hybrid search）内部大致是这样：

    第一步：各自召回
    
    稠密请求 → 拿回 10 条，按稠密相似度排序，每条有一个稠密分数
    稀疏请求 → 拿回 10 条，按稀疏相似度排序，每条有一个稀疏分数
    这 20 条里，有些是重叠的（同一段文本，稠密和稀疏都召回了）。
    
    第二步：归一化
    
    因为稠密分数和稀疏分数的量纲不一样，没法直接比。norm_score=True 会把两组分数分别归一化到 0~1 区间。
    
    第三步：加权融合
    
    对每个出现过的候选（去重后），用它的稠密分数 × 0.8 + 稀疏分数 × 0.2，算出一个综合分数。
    如果某条只被稠密召回、没被稀疏召回，那它的稀疏部分算 0（或按 Milvus 具体实现处理）；反之亦然。
    
    第四步：排序取 Top5
    
    按综合分数降序，取前 5 条返回。
    """

    # 打印节点处理成功日志，输出原始检索结果，便于调试
    # res 是批量搜索结构：即使只查一条，结果也包一层列表，故取 res[0]
    hit_count = len(res[0]) if res and len(res) > 0 else 0
    logger.info(f"节点 search_embedding 处理成功，检索到 {hit_count} 条相关片段")
    if hit_count > 0:
        logger.debug(f"Top1 检索结果示例: {res[0][0]}")
        
    # 标记当前任务完成，更新任务状态
    add_done_task(state["session_id"], sys._getframe().f_code.co_name, state.get("is_stream"))

    # 6. 构造并返回结果：若检索结果非空，取res[0]（适配Milvus批量搜索格式），否则返回空列表
    # res[0]为当前单条查询的检索结果，包含TOP5匹配的向量数据及业务字段
    return {"embedding_chunks": res[0] if res else []}


if __name__ == "__main__":
    # 模拟测试数据
    # 本地自测入口：无需完整 LangGraph 流程，直接构造 state 验证检索链路连通性
    test_state = {
        "session_id": "test_search_embedding_001",
        "rewritten_query": "HAK 180 烫金机使用说明",  # 模拟改写后的查询
        "item_names": ["HAK180烫金机"],  # 模拟已确认的商品名
        "is_stream": False
    }

    print("\n>>> 开始测试 node_search_embedding 节点...")
    try:
        # 执行节点函数
        result = node_search_embedding(test_state)
        logger.info(f"检索结果汇总：{result}")
        # 验证结果
        chunks = result.get("embedding_chunks", [])
        print(f"\n>>> 测试完成！检索到 {len(chunks)} 条结果")
        
        if chunks:
            print("\n>>> Top 1 结果详情:")
            top1 = chunks[0]
            # 打印关键字段（注意：entity字段可能包含具体业务数据）
            # Hit 的外层只有 id/distance 元信息，业务字段全部嵌在 entity 里
            print(f"ID: {top1.get('id')}")
            print(f"Distance: {top1.get('distance')}")
            entity = top1.get('entity', {})
            print(f"Item Name: {entity.get('item_name')}")
            print(f"Content Preview: {entity.get('content', '')[:100]}...")
        else:
            print("\n>>> 警告：未检索到任何结果，请检查 Milvus 数据或 item_names 是否匹配")
            
    except Exception as e:
        logger.error(f"测试运行失败: {e}", exc_info=True)

