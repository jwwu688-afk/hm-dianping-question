# FlagEmbedding 的 FlagReranker：BGE 系列交叉编码器重排序模型封装。
from FlagEmbedding import FlagReranker
# 重排序配置：模型路径、推理设备、半精度开关（详见 conf/reranker_config.py）。
from app.conf.reranker_config import reranker_config

# 模块级单例缓存：None 表示尚未加载；进程内只加载一次，避免重复占显存。
# 懒加载而非 import 时加载：不调检索的进程（如导出脚本）零开销。
_reranker_model = None

def get_reranker_model():
    """获取 FlagReranker 重排序模型单例（懒加载）。

    首次调用触发模型加载（BGE-reranker-large 约 1.3GB），后续调用直接复用；
    加载失败会抛异常向上传播，由调用方决定降级策略。
    检索链路（retrieval.py）用它对 [query, doc] 对打相关性分再精排，
    用法示例：scores = model.compute_score([[question, doc_content], ...])，
    返回与输入对等长的分数列表（分数越高越相关）。
    """
    global _reranker_model
    # global 声明：懒加载要写回模块级缓存变量。
    # 双检查惯用写法：已有实例立即返回，不重复加锁/加载。
    if _reranker_model is None:
        # 三个参数均来自配置对象：模型路径（本地目录或 HF 名称）、
        # 推理设备（cuda/cpu）、use_fp16 半精度（GPU 上省显存提速，CPU 下无效）。
        _reranker_model= FlagReranker(
            model_name_or_path=reranker_config.bge_reranker_large,
            device=reranker_config.bge_reranker_device,
            use_fp16=reranker_config.bge_reranker_fp16
        )
    return _reranker_model