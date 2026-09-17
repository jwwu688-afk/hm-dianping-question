from modelscope.hub.snapshot_download import snapshot_download

# ModelScope snapshot_download 使用 cache_dir 时，会按 HuggingFace 风格创建
# `cache_dir/models--{org}--{name}/snapshots/{commit}/` 多层目录。
# 因此代码里使用的实际模型路径应取该目录下的 `snapshots/<commit>` 子目录，
# 否则 FlagReranker 找不到 config.json 等必要文件。
cache_dir = r"D:\ai_models\modelscope_cache\models\rerank"
final_path = snapshot_download(
    model_id="BAAI/bge-reranker-large",
    cache_dir=cache_dir,
)

print("下载完成，模型目录：", final_path)
print("（请将上面这行完整路径复制到 .env 的 BGE_RERANKER_LARGE 配置项）")