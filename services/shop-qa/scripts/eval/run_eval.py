"""检索基线对比评测主脚本：回答「混合检索比单路检索优化多少」。

评测的配置矩阵（消融实验，逐个去掉/替换某个组件，看指标变化）：
    1. dense_only          纯稠密向量检索（基线①：只用 BGE-M3 稠密向量）
    2. sparse_only         纯稀疏向量检索（基线②：只用 BGE-M3 稀疏/BM25 式向量）
    3. hybrid_w_50_50      混合检索 WeightedRanker(0.5, 0.5)（权重消融）
    4. hybrid_w_80_20      混合检索 WeightedRanker(0.8, 0.2)（当前生产配置）
    5. hybrid_rrf          两路各取 Top-N 后用加权 RRF(k=60) 融合（融合方式消融，
                           公式与 node_rrf.reciprocal_rank_fusion 完全一致）
    6. hybrid_w_80_20_rerank  生产配置 + BGE 重排序精排（--with-rerank 时才跑，
                           reranker 在 CPU 上较慢，且走的是本地模型、不消耗 LLM 额度）

注意：
- HyDE 路不纳入默认评测——它需要调用 LLM 生成假设答案，会消耗模型额度；
  如需评测 HyDE 增益，请先确认额度预算再单独扩展。
- 本地模型（BGE-M3 / BGE-reranker）与 Milvus 检索不消耗 LLM 额度，可放心多次跑。

用法：
    # 0) 先构建/校对金标集（见 build_golden_set.py）
    python scripts/eval/run_eval.py                          # 跑默认 5 个配置
    python scripts/eval/run_eval.py --with-rerank            # 额外跑 rerank 精排配置
    python scripts/eval/run_eval.py --configs dense_only hybrid_w_80_20   # 只跑指定配置
    python scripts/eval/run_eval.py --golden my_golden.jsonl # 指定金标文件

输出：
    scripts/eval/eval_out/comparison_report.md   对比报告（各配置指标表 + 相对基线的提升）
    scripts/eval/eval_out/results.csv            每条 query × 每个配置的明细（可导入 Excel 分析）
"""

import argparse
import csv
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv, find_dotenv

load_dotenv(find_dotenv())

from app.lm.embedding_utils import generate_embeddings  # noqa: E402
from milvus_search import search_dense, search_sparse, search_hybrid  # noqa: E402
from metrics import (  # noqa: E402
    hit_at_k, recall_at_k, mrr_at_k, ndcg_at_k, latency_summary,
)

# 评测参数（与生产链路对齐：每路召回深度 10，最终截断 10）
PER_WAY_DEPTH = 10     # 单路/混合检索的召回深度（决定 hit@k / mrr@k 的计算窗口）
RRF_K = 60             # RRF 常数，与生产 node_rrf 一致
RRF_MAX_RESULTS = 10   # RRF 融合后保留条数，与生产 node_rrf 一致

# 指标列定义：k 值的选择覆盖「头部命中」「中段命中」「排序质量」三个视角
METRIC_FUNCS = {
    "hit@1": lambda ids, gold: hit_at_k(ids, gold, 1),
    "hit@3": lambda ids, gold: hit_at_k(ids, gold, 3),
    "hit@5": lambda ids, gold: hit_at_k(ids, gold, 5),
    "recall@5": lambda ids, gold: recall_at_k(ids, gold, 5),
    "mrr@10": lambda ids, gold: mrr_at_k(ids, gold, 10),
    "ndcg@5": lambda ids, gold: ndcg_at_k(ids, gold, 5),
}


def rrf_fuse(dense_hits: list[dict], sparse_hits: list[dict]) -> list[dict]:
    """加权 RRF 融合（与 node_rrf.reciprocal_rank_fusion 同公式，本地实现以避免连带加载 LangGraph 节点依赖）。

    公式：score(chunk) = Σ weight_i / (k + rank_i)，rank 从 1 开始，k=60。
    """
    score_map: dict = {}
    chunk_map: dict = {}
    for docs, weight in [(dense_hits, 1.0), (sparse_hits, 1.0)]:  # 两路权重 1.0，与生产一致
        for rank, doc in enumerate(docs, start=1):
            cid = doc.get("chunk_id")
            if cid is None:
                continue
            score_map[cid] = score_map.get(cid, 0.0) + weight * (1.0 / (RRF_K + rank))
            chunk_map.setdefault(cid, doc)
    merged = sorted(score_map.items(), key=lambda x: x[1], reverse=True)
    return [chunk_map[cid] for cid, _ in merged[:RRF_MAX_RESULTS]]


def rerank_docs(question: str, docs: list[dict], reranker) -> list[dict]:
    """BGE 重排序：对 (question, text) 对逐个打分，按分数降序返回。

    注意 (query, passage) 顺序不可颠倒，否则打分失真（与 node_rerank 一致）。
    """
    texts = [d.get("content") or d.get("text") or "" for d in docs]
    pairs = [[question, t] for t in texts]
    scores = reranker.compute_score(pairs)
    for doc, score in zip(docs, scores):
        doc["score"] = float(score)
    return sorted(docs, key=lambda x: x["score"], reverse=True)


# ---------- 各配置的检索函数：入参为 (query, 向量, 深度)，出参为统一 dict 列表 ----------

def run_dense_only(query, vecs, depth):
    return search_dense(vecs["dense"][0], limit=depth)

def run_sparse_only(query, vecs, depth):
    return search_sparse(vecs["sparse"][0], limit=depth)

def run_hybrid_50_50(query, vecs, depth):
    return search_hybrid(vecs["dense"][0], vecs["sparse"][0], weights=(0.5, 0.5), limit=depth)

def run_hybrid_80_20(query, vecs, depth):
    return search_hybrid(vecs["dense"][0], vecs["sparse"][0], weights=(0.8, 0.2), limit=depth)

def run_hybrid_rrf(query, vecs, depth):
    # RRF 版本需要拿到两路的原始排序（各取 depth 条），不能直接用 Milvus 融合结果
    dense_hits = search_dense(vecs["dense"][0], limit=depth)
    sparse_hits = search_sparse(vecs["sparse"][0], limit=depth)
    return rrf_fuse(dense_hits, sparse_hits)


def main() -> None:
    parser = argparse.ArgumentParser(description="检索基线对比评测")
    parser.add_argument("--golden", type=str, default="golden_set.jsonl",
                        help="金标测试集路径（相对本目录），默认 golden_set.jsonl")
    parser.add_argument("--configs", nargs="*", default=None,
                        help="只跑指定配置（dense_only sparse_only hybrid_w_50_50 hybrid_w_80_20 hybrid_rrf），默认全跑")
    parser.add_argument("--with-rerank", action="store_true",
                        help="额外跑 hybrid_w_80_20_rerank 配置（本地 BGE reranker，CPU 较慢）")
    args = parser.parse_args()

    # 装配配置表：name -> (检索函数, 是否走 rerank)
    configs = {
        "dense_only": (run_dense_only, False),
        "sparse_only": (run_sparse_only, False),
        "hybrid_w_50_50": (run_hybrid_50_50, False),
        "hybrid_w_80_20": (run_hybrid_80_20, False),
        "hybrid_rrf": (run_hybrid_rrf, False),
    }
    if args.with_rerank:
        # rerank 版本基于生产权重（0.8, 0.2）的混合检索结果做精排
        configs["hybrid_w_80_20_rerank"] = (run_hybrid_80_20, True)
    if args.configs:
        unknown = [c for c in args.configs if c not in configs]
        if unknown:
            parser.error(f"未知配置: {unknown}，可选: {list(configs.keys())}")
        configs = {k: configs[k] for k in args.configs}

    # 读取金标集（过滤掉空 query / 空 gold 的行）
    golden_path = Path(__file__).resolve().parent / args.golden
    records = []
    for line in golden_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        if r.get("query") and r.get("gold_chunk_ids"):
            records.append(r)
    print(f"加载金标集 {golden_path.name}：{len(records)} 条 query")

    # reranker 惰性加载：只在需要时才初始化（模型加载要十几秒）
    reranker = None
    if args.with_rerank:
        from app.lm.reranker_utils import get_reranker_model
        print("正在加载 BGE reranker（本地模型，首次加载较慢）...")
        reranker = get_reranker_model()

    # 逐条 query：向量化一次（所有配置共享，省时间），再跑每个配置
    # 结果结构：per_config[config_name] = {"metrics": {指标: 值列表}, "latency": [毫秒]}
    per_config = {name: {"metrics": {m: [] for m in METRIC_FUNCS}, "latency": []}
                  for name in configs}
    detail_rows = []  # CSV 明细行

    for i, rec in enumerate(records, 1):
        query, gold = rec["query"], rec["gold_chunk_ids"]
        try:
            vecs = generate_embeddings([query])
        except Exception as e:
            print(f"[{i}/{len(records)}] 向量化失败，跳过: {query[:30]}... ({e})")
            continue

        for name, (fn, use_rerank) in configs.items():
            try:
                t0 = time.perf_counter()
                hits = fn(query, vecs, PER_WAY_DEPTH)
                if use_rerank:
                    hits = rerank_docs(query, hits, reranker)
                elapsed_ms = (time.perf_counter() - t0) * 1000

                ranked_ids = [h.get("chunk_id") for h in hits]
                row = {"query": query, "config": name}
                for metric, func in METRIC_FUNCS.items():
                    val = func(ranked_ids, gold)
                    per_config[name]["metrics"][metric].append(val)
                    row[metric] = val
                per_config[name]["latency"].append(elapsed_ms)
                detail_rows.append(row)
            except Exception as e:
                print(f"[{i}/{len(records)}] 配置 {name} 检索失败，跳过: {e}")
        print(f"[{i}/{len(records)}] 完成: {query[:40]}")

    # ---------- 汇总与输出 ----------
    out_dir = Path(__file__).resolve().parent / "eval_out"
    out_dir.mkdir(exist_ok=True)

    # 1) 汇总表：每个配置的平均指标 + 延迟
    summary = {}
    for name, data in per_config.items():
        row = {}
        for metric, vals in data["metrics"].items():
            row[metric] = sum(vals) / len(vals) if vals else 0.0
        row.update(latency_summary(data["latency"]))
        summary[name] = row

    metric_names = list(METRIC_FUNCS.keys())
    baseline_name = "dense_only" if "dense_only" in summary else list(summary)[0]

    # 2) Markdown 报告：指标表 + 相对基线的提升（绝对百分点）
    lines = [
        "# RAG 检索基线对比评测报告",
        "",
        f"- 金标集：{golden_path.name}（{len(records)} 条 query）",
        f"- 召回深度：每路 {PER_WAY_DEPTH}，RRF k={RRF_K}",
        "- 延迟为检索链路耗时（含 Milvus 检索；rerank 配置含精排耗时），本机实测",
        "",
        "| 配置 | " + " | ".join(metric_names) + " | p50(ms) | p95(ms) |",
        "|" + "---|" * (len(metric_names) + 3),
    ]
    for name, row in summary.items():
        cells = [f"{row[m]:.4f}" if m in row else "-" for m in metric_names]
        lines.append(f"| {name} | " + " | ".join(cells) + f" | {row['p50']:.0f} | {row['p95']:.0f} |")

    lines += ["", "## 相对基线（{}）的提升（绝对百分点）".format(baseline_name), ""]
    for name, row in summary.items():
        if name == baseline_name:
            continue
        base = summary[baseline_name]
        deltas = [f"{m}: {100 * (row[m] - base[m]):+.1f}pp" for m in metric_names]
        lines.append(f"- **{name}**：" + "，".join(deltas))
    lines += [
        "",
        "## 说明",
        "- 金标为「自检索」构造（query 由 chunk 标题/内容派生），偏容易；",
        "  人工把 query 改写成口语化提问后重跑，数字更接近真实场景。",
        "- HyDE 路未纳入（需调用 LLM，避免额度消耗）。",
    ]
    report_path = out_dir / "comparison_report.md"
    report_path.write_text("\n".join(lines), encoding="utf-8")

    # 3) CSV 明细：每条 query × 每个配置一行，方便导入 Excel 做逐条分析
    csv_path = out_dir / "results.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=["query", "config"] + metric_names)
        writer.writeheader()
        writer.writerows(detail_rows)

    # 4) 控制台摘要
    print("\n===== 评测结果摘要 =====")
    for name, row in summary.items():
        mstr = "  ".join(f"{m}={row[m]:.3f}" for m in metric_names)
        print(f"{name:24s} {mstr}  p50={row['p50']:.0f}ms p95={row['p95']:.0f}ms")
    print(f"\n报告: {report_path}\n明细: {csv_path}")


if __name__ == "__main__":
    main()
