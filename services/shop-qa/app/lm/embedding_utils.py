# 模块职责：BGE-M3 嵌入向量生成统一入口。
# BGE-M3 是「多语言、多粒度、多功能」模型，一次前向同时产出稠密向量（语义相似）
# 与稀疏向量（类 learned-sparse 词权重），本模块负责单例加载与输出格式适配，
# 上游（检索节点、入库节点）只认 dense/sparse 两个 key 的返回结构。
# 注意：稠密向量适配 IP 内积检索，稀疏向量适配 Milvus 的 SPARSE_INVERTED_INDEX 索引，
# 两者由建库节点分别写入对应字段，本模块只负责「生产」不关心「存储」。
from pymilvus.model.hybrid import BGEM3EmbeddingFunction
from app.core.logger import logger
from app.conf.embedding_config import embedding_config

# 模型单例对象，避免重复初始化
# BGE-M3 加载要数秒到数十秒并占显存/内存，进程级只允许加载一次：
# 之所以不用类封装而用模块级变量，是为了全项目 from ... import 即共享同一实例
_bge_m3_ef = None

def get_bge_m3_ef():
    """
    获取BGE-M3模型单例对象，自动加载环境变量配置
    :return: 初始化完成的BGEM3EmbeddingFunction实例
    """
    global _bge_m3_ef
    # 单例模式：已初始化则直接返回，避免重复加载模型
    # global 声明：函数内要重新赋值模块级变量，否则 Python 会当作局部变量
    if _bge_m3_ef is not None:
        logger.debug("BGE-M3模型单例已存在，直接返回实例")
        return _bge_m3_ef

    # 从环境变量加载配置，无配置则使用默认值
    # 本地有可以使用本地地址！ 没有使用 "BAAI/bge-m3" 会自动下载！ 如果云端部署也可以使用url地址！
    # 三项配置均「或默认值」兜底：任何一项缺失都不阻断启动，宁可先跑在 CPU 上
    model_name = embedding_config.bge_m3_path or "BAAI/bge-m3"
    device = embedding_config.bge_device or "cpu"
    use_fp16 = embedding_config.bge_fp16 or False

    # 打印模型初始化配置，便于问题排查
    # 初始化前打日志：模型加载失败时能立刻看出是哪项配置出了问题（路径错/设备不可用）
    logger.info(
        "开始初始化BGE-M3模型",
        extra={
            "model_name": model_name,
            "device": device,
            "use_fp16": use_fp16,
            "normalize_embeddings": True
        }
    )

    try:
        # 初始化BGE-M3模型，开启原生L2归一化（适配Milvus IP内积检索）
        # use_fp16 只在 GPU 上生效：半精度推理可降显存、提速，CPU 下自动忽略
        _bge_m3_ef = BGEM3EmbeddingFunction(
            model_name=model_name,
            device=device,
            use_fp16=use_fp16,
            normalize_embeddings=True  # 模型原生对稠密+稀疏向量做L2归一化
        )
        logger.success("BGE-M3模型初始化成功，已开启原生L2归一化")
        return _bge_m3_ef
    except Exception as e:
        # 初始化失败不能返回半初始化对象：保持 _bge_m3_ef 为 None，下次调用可重试
        logger.error(f"BGE-M3模型初始化失败：{str(e)}", exc_info=True)
        raise  # 向上抛出异常，由调用方处理


def generate_embeddings(texts):
    """
    为文本列表生成稠密+稀疏混合向量嵌入（模型原生L2归一化）
    :param texts: 要生成嵌入的文本列表，单文本也需封装为列表
    :return: 字典格式的向量结果，key为dense/sparse，对应嵌套列表/字典列表
    :raise: 向量生成过程中的异常，由调用方捕获处理
    """
    # 返回结构约定：dense 为嵌套列表（第 i 项对应第 i 条文本），sparse 为字典列表，
    # 调用方统一按 generate_embeddings(x)["dense"][0] / ["sparse"][0] 取值
    # 入参合法性校验：调用方经常误传单个字符串而非列表，提前拦截给出明确错误信息
    # 入参合法性校验
    if not isinstance(texts, list) or len(texts) == 0:
        logger.warning("生成向量入参不合法，texts必须为非空列表")
        raise ValueError("参数texts必须是包含文本的非空列表")

    logger.info(f"开始为{len(texts)}条文本生成混合向量嵌入")
    try:
        # 加载BGE-M3模型单例
        model = get_bge_m3_ef()
        # 模型编码生成向量，返回dense（稠密向量）+sparse（CSR格式稀疏向量）
        # 统一使用 encode_documents：与文档入库节点走同一编码入口，保证查询向量与
        # 库内向量的表示空间严格一致（BGE-M3 对 documents/queries 的编码略有差异，
        # 一旦两侧入口不一致，检索精度会明显下降）
        embeddings = model.encode_documents(texts)
        logger.debug(f"模型编码完成，开始解析稀疏向量格式，共{len(texts)}条")

        # 初始化稀疏向量处理结果，解析为字典格式（适配序列化/存储）
        # CSR（压缩稀疏行）格式解读：indptr 是行指针数组，第 i 条文本的非零元素
        # 位于 [indptr[i], indptr[i+1]) 区间；indices 存特征下标（列号），data 存对应权重。
        # Milvus 稀疏向量要求以 {特征id: 权重} 的 dict 形式传入，因此必须逐行展开
        processed_sparse = []
        for i in range(len(texts)):
            # 提取第i个文本的稀疏向量索引：np.int64 → Python int（满足字典key可哈希要求）
            # 不加 .tolist() 的话 np.int64 虽可哈希，但作为 dict key 跨进程/序列化后会失配
            sparse_indices = embeddings["sparse"].indices[
                embeddings["sparse"].indptr[i]:embeddings["sparse"].indptr[i + 1]
            ].tolist()
            # 提取第i个文本的稀疏向量权重：np.float32 → Python float（适配JSON序列化/接口返回）
            # 与 indices 切片用同一对 indptr 边界：保证下标与权重按位置严格对齐
            sparse_data = embeddings["sparse"].data[
                embeddings["sparse"].indptr[i]:embeddings["sparse"].indptr[i + 1]
            ].tolist()
            # 构造{特征索引: 归一化权重}的稀疏向量字典
            # zip(indices, data) 一一配对：同一条 CSR 行内两个数组按位置对应
            sparse_dict = {k: v for k, v in zip(sparse_indices, sparse_data)}
            processed_sparse.append(sparse_dict)

        # 构造最终返回结果，稠密向量转列表（解决numpy数组不可序列化问题）
        # dense 用 .tolist()：numpy.float32 数组无法直接 JSON 序列化、也不能进 Milvus SDK
        result = {
            "dense": [emb.tolist() for emb in embeddings["dense"]],  # 嵌套列表，与输入文本一一对应
            "sparse": processed_sparse  # 字典列表，模型已做L2归一化
        }
        logger.success(f"{len(texts)}条文本向量生成完成，格式已适配工业级使用")
        return result

    except Exception as e:
        logger.error(f"文本向量生成失败：{str(e)}", exc_info=True)
        raise  # 不吞异常，向上传递让调用方做重试/降级处理
        # 不在这里返回空向量：空向量入库/检索会静默污染知识库，宁可让上层显式失败


"""
核心设计亮点&适配说明：
1. 模型原生归一化：开启normalize_embeddings = True，自动对稠密+稀疏向量做L2归一化，完美适配Milvus IP内积检索（单位化后IP等价于余弦，计算更快）；
2. 彻底解决NumPy类型做key问题：sparse_indices加.tolist()，将np.int64转为Python原生int，满足字典key的可哈希要求，无报错风险；
3. 稀疏值适配序列化：sparse_data加.tolist()，将np.float32转为Python原生float，支持JSON写入/接口返回/Milvus入库等所有场景；
4. 单例模式优化：模型仅初始化一次，避免重复加载耗时耗资源，提升批量处理效率；
5. 格式匹配业务调用：返回dense嵌套列表、sparse字典列表，与vector_result["dense"][0]/sparse_vector["sparse"][0]取值逻辑完美契合；
6. 分级日志覆盖：从模型初始化、向量生成到异常报错，全流程日志记录，便于生产环境问题排查；
7. 入参合法性校验：防止空列表/非列表入参导致的内部报错，提升工具类健壮性。
"""