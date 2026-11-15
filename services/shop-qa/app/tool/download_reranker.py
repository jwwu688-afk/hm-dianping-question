"""
BGE-Reranker 重排序模型下载脚本（一次性独立运行的工具，不属于服务运行时依赖）。

作用：通过 ModelScope 的 snapshot_download 把 BAAI/bge-reranker-large 拉取到本地缓存目录，
解决线上/内网环境无法联网下载模型的问题。
打印出的 snapshots 子目录路径需手工复制到 .env 的 BGE_RERANKER_LARGE 配置项，
供 reranker_utils 初始化 FlagReranker 时按本地路径加载，避免每次启动都联网下载。
"""
from modelscope.hub.snapshot_download import snapshot_download

# ModelScope snapshot_download 使用 cache_dir 时，会按 HuggingFace 风格创建
# `cache_dir/models--{org}--{name}/snapshots/{commit}/` 多层目录。
# 因此代码里使用的实际模型路径应取该目录下的 `snapshots/<commit>` 子目录，
# 否则 FlagReranker 找不到 config.json 等必要文件。
# 下载缓存目录：ModelScope 不会把模型直接放到该目录，而是按 HuggingFace 风格再套多层子目录
cache_dir = r"D:\ai_models\modelscope_cache\models\rerank"
final_path = snapshot_download(
    model_id="BAAI/bge-reranker-large",
    cache_dir=cache_dir,
)

print("下载完成，模型目录：", final_path)
print("（请将上面这行完整路径复制到 .env 的 BGE_RERANKER_LARGE 配置项）")