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
- 六个配置互相独立、可单独跑任何一个；报告里相对提升以 dense_only 为基线
  （若该配置被 --configs 过滤掉则退化为配置表第一个）。
- 消融逻辑一览：
  * ①② vs ③④：加不加融合（单路 → 混合）能涨多少；
  * ③ vs ④：权重从均权调成 8:2（生产配置）值不值；
  * ④ vs ⑤：Milvus 服务端加权 vs 客户端 RRF，哪种融合方式更好；
  * ④ vs ⑥：精排（rerank）在混合检索之上还能再涨多少。

用法：
    # 0) 先构建/校对金标集（见 build_golden_set.py）
    python scripts/eval/run_eval.py                          # 跑默认 5 个配置
    python scripts/eval/run_eval.py --with-rerank            # 额外跑 rerank 精排配置
    python scripts/eval/run_eval.py --configs dense_only hybrid_w_80_20   # 只跑指定配置
    python scripts/eval/run_eval.py --golden my_golden.jsonl # 指定金标文件
    # 建议顺序：先默认全跑看大盘，再 --with-rerank 补精排数据，最后固定金标写进报告

输出：
    scripts/eval/eval_out/comparison_report.md   对比报告（各配置指标表 + 相对基线的提升）
    scripts/eval/eval_out/results.csv            每条 query × 每个配置的明细（可导入 Excel 分析）

执行流程（main 四阶段）：
    1) 装配配置表（6 个消融配置，--configs 可只跑子集）；
    2) 加载金标 JSONL + 向量化逐条 query（所有配置共享同一份向量，省时间）；
    3) 每个配置 × 每条 query：检索 → （可选 rerank）→ 算 6 项指标 + 记耗时；
    4) 汇总平均指标，产出 Markdown 报告与 CSV 明细，控制台打印摘要。

性能设计：向量化每条 query 只做一次（所有配置共享）；reranker 惰性加载；
单条/单配置失败跳过而不中断——保证评测总能产出完整对比。
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

# 加载 .env：BGE-M3 模型路径与 Milvus 连接信息都在这里
load_dotenv(find_dotenv())

# Embedding 工具来自生产代码：保证评测时的向量化与线上完全一致
from app.lm.embedding_utils import generate_embeddings  # noqa: E402
# 评测封装层（本目录 milvus_search.py）：单路/混合检索的统一入口
from milvus_search import search_dense, search_sparse, search_hybrid  # noqa: E402
# 指标模块（本目录 metrics.py）：hit/recall/mrr/ndcg/延迟全是纯函数
from metrics import (  # noqa: E402
    hit_at_k, recall_at_k, mrr_at_k, ndcg_at_k, latency_summary,
)

# 评测参数（与生产链路对齐：每路召回深度 10，最终截断 10）
# 深度决定了「结果列表」长度，直接影响 hit@k / mrr@k 的计算窗口
# 三个常量都要与生产 node_rrf / node_search_embedding 保持一致，评测才有代表性
PER_WAY_DEPTH = 10     # 单路/混合检索的召回深度（决定 hit@k / mrr@k 的计算窗口）
RRF_K = 60             # RRF 常数，与生产 node_rrf 一致
RRF_MAX_RESULTS = 10   # RRF 融合后保留条数，与生产 node_rrf 一致

# 指标列定义：k 值的选择覆盖「头部命中」「中段命中」「排序质量」三个视角
# 每个 lambda 接收 (结果 ID 序列, 金标 ID 序列)，返回单条 query 的指标值
METRIC_FUNCS = {
    # hit@1/3/5：头部命中率——用户最常看的门槛指标
    "hit@1": lambda ids, gold: hit_at_k(ids, gold, 1),
    "hit@3": lambda ids, gold: hit_at_k(ids, gold, 3),
    "hit@5": lambda ids, gold: hit_at_k(ids, gold, 5),
    # recall@5：多金标时的覆盖比例
    "recall@5": lambda ids, gold: recall_at_k(ids, gold, 5),
    # mrr@10：首个命中的名次倒数——对「最好结果排第几」最敏感
    "mrr@10": lambda ids, gold: mrr_at_k(ids, gold, 10),
    # ndcg@5：带位置折损的排序质量——比 hit 更细粒度地区分排序好坏
    "ndcg@5": lambda ids, gold: ndcg_at_k(ids, gold, 5),
}


def rrf_fuse(dense_hits: list[dict], sparse_hits: list[dict]) -> list[dict]:
    """加权 RRF 融合（与 node_rrf.reciprocal_rank_fusion 同公式，本地实现以避免连带加载 LangGraph 节点依赖）。

    本地重写的唯一原因：import 生产 node_rrf 会连带 import LangGraph 全家桶，
    评测脚本没必要拖起整张图；公式与常量逐行对齐，保证可比。
    公式：score(chunk) = Σ weight_i / (k + rank_i)，rank 从 1 开始，k=60。
    与 WeightedRanker 的本质区别：RRF 只用「名次」不用「原始分数」，
    天然规避两路 score 量纲不可比的问题。
    """
    # score_map 累加每个 chunk 的 RRF 得分；chunk_map 保留原始 doc 供输出
    score_map: dict = {}
    chunk_map: dict = {}
    # 两路权重均取 1.0（与生产 node_rrf 一致）：名次倒数已经隐含了位置信息
    for docs, weight in [(dense_hits, 1.0), (sparse_hits, 1.0)]:  # 两路权重 1.0，与生产一致
        # rank 从 1 开始：第 1 名贡献 1/(60+1)，第 10 名贡献 1/70
        for rank, doc in enumerate(docs, start=1):
            cid = doc.get("chunk_id")
            # 缺 ID 的脏数据直接跳过，不能进融合
            if cid is None:
                continue
            # 同一 chunk 两路都命中时得分累加 → 天然把「双命中」排到前面
            score_map[cid] = score_map.get(cid, 0.0) + weight * (1.0 / (RRF_K + rank))
            chunk_map.setdefault(cid, doc)
    # 按累计得分降序，截断到与生产一致的条数
    merged = sorted(score_map.items(), key=lambda x: x[1], reverse=True)
    return [chunk_map[cid] for cid, _ in merged[:RRF_MAX_RESULTS]]


def rerank_docs(question: str, docs: list[dict], reranker) -> list[dict]:
    """BGE 重排序：对 (question, text) 对逐个打分，按分数降序返回。

    精排是「召回之后、生成之前」的环节：混合检索负责召回候选项，
    cross-encoder 逐对精细打分负责把最相关的顶到最前面。
    注意 (query, passage) 顺序不可颠倒，否则打分失真（与 node_rerank 一致）。
    """
    # 参与精排的文本：优先 content 字段，兜底 text（兼容不同来源的 dict 结构）
    texts = [d.get("content") or d.get("text") or "" for d in docs]
    # BGE reranker 的输入是 [query, passage] 对，顺序固定不能反
    pairs = [[question, t] for t in texts]
    # compute_score 一次算完所有对，CPU 上较慢但离线评测可接受
    scores = reranker.compute_score(pairs)
    # 把精排分数写回 doc 并按分数降序——这就是「精排后的新顺序」
    for doc, score in zip(docs, scores):
        doc["score"] = float(score)
    return sorted(docs, key=lambda x: x["score"], reverse=True)


# ---------- 各配置的检索函数：入参为 (query, 向量, 深度)，出参为统一 dict 列表 ----------
# 五个函数签名完全一致，这样主循环可以用同一套代码驱动任意配置

def run_dense_only(query, vecs, depth):
    """配置①：纯稠密向量检索，只取 dense 路结果。"""
    return search_dense(vecs["dense"][0], limit=depth)

def run_sparse_only(query, vecs, depth):
    """配置②：纯稀疏向量检索，只取 sparse 路结果。"""
    return search_sparse(vecs["sparse"][0], limit=depth)

def run_hybrid_50_50(query, vecs, depth):
    """配置③：Milvus 服务端 WeightedRanker 均权融合 (0.5, 0.5)——权重消融。"""
    return search_hybrid(vecs["dense"][0], vecs["sparse"][0], weights=(0.5, 0.5), limit=depth)

def run_hybrid_80_20(query, vecs, depth):
    """配置④：Milvus 服务端加权融合 (0.8, 0.2)——当前生产配置，报告的对照基线之一。"""
    return search_hybrid(vecs["dense"][0], vecs["sparse"][0], weights=(0.8, 0.2), limit=depth)

def run_hybrid_rrf(query, vecs, depth):
    """配置⑤：两路各取 Top-N 后客户端加权 RRF(k=60) 融合——融合方式消融。"""
    # RRF 版本需要拿到两路的原始排序（各取 depth 条），不能直接用 Milvus 融合结果
    # 所以这里显式跑两次单路检索再本地融合（多一次网络往返，延迟自然更高）
    dense_hits = search_dense(vecs["dense"][0], limit=depth)
    sparse_hits = search_sparse(vecs["sparse"][0], limit=depth)
    return rrf_fuse(dense_hits, sparse_hits)


def main() -> None:
    """评测主流程：装配配置 → 读金标 → 逐条向量化并跑各配置 → 汇总输出。

    三个输出物：
    - eval_out/comparison_report.md：各配置平均指标表 + 相对基线的绝对百分点提升；
    - eval_out/results.csv：query × 配置粒度的明细行（utf-8-sig 方便 Excel 打开）；
    - 控制台摘要：每配置一行核心指标。
    """
    # 参数说明：--golden 选金标、--configs 选配置子集、--with-rerank 加精排
    # 配置表 + rerank 开关在装配阶段决定最终要跑哪些配置
    parser = argparse.ArgumentParser(description="检索基线对比评测")
    # 金标文件默认放在本目录（scripts/eval/golden_set.jsonl）
    parser.add_argument("--golden", type=str, default="golden_set.jsonl",
                        help="金标测试集路径（相对本目录），默认 golden_set.jsonl")
    # 不传 --configs 时跑全部基础配置；传了则只跑子集（快速迭代某几个对比）
    parser.add_argument("--configs", nargs="*", default=None,
                        help="只跑指定配置（dense_only sparse_only hybrid_w_50_50 hybrid_w_80_20 hybrid_rrf），默认全跑")
    # rerank 是可选项：本地 BGE 模型加载慢、CPU 推理慢，默认不跑
    parser.add_argument("--with-rerank", action="store_true",
                        help="额外跑 hybrid_w_80_20_rerank 配置（本地 BGE reranker，CPU 较慢）")
    args = parser.parse_args()

    # 装配配置表：name -> (检索函数, 是否走 rerank)
    # 五个基础配置固定；rerank 配置只有显式 --with-rerank 才追加
    configs = {
        "dense_only": (run_dense_only, False),
        "sparse_only": (run_sparse_only, False),
        "hybrid_w_50_50": (run_hybrid_50_50, False),
        "hybrid_w_80_20": (run_hybrid_80_20, False),
        "hybrid_rrf": (run_hybrid_rrf, False),
    }
    if args.with_rerank:
        # rerank 版本基于生产权重（0.8, 0.2）的混合检索结果做精排
        # 检索函数复用 run_hybrid_80_20，use_rerank=True 触发主循环里的精排分支
        configs["hybrid_w_80_20_rerank"] = (run_hybrid_80_20, True)
    if args.configs:
        # --configs 指定子集：先校验配置名都存在，再按用户给的顺序重排
        # 顺序保留用户输入顺序，报告表格的行序随之变化
        unknown = [c for c in args.configs if c not in configs]
        if unknown:
            parser.error(f"未知配置: {unknown}，可选: {list(configs.keys())}")
        configs = {k: configs[k] for k in args.configs}

    # 读取金标集（过滤掉空 query / 空 gold 的行）
    # JSONL 逐行解析：一行一条 {"query":..., "gold_chunk_ids":[...]}
    # 读金标要在加载模型之前：文件有问题就尽早报错，不浪费加载时间
    golden_path = Path(__file__).resolve().parent / args.golden
    records = []
    for line in golden_path.read_text(encoding="utf-8").splitlines():
        # 跳过空行（文件末尾常有）
        if not line.strip():
            continue
        r = json.loads(line)
        # 双重有效性过滤：query 与金标缺一不可，否则指标没意义
        if r.get("query") and r.get("gold_chunk_ids"):
            records.append(r)
    print(f"加载金标集 {golden_path.name}：{len(records)} 条 query")

    # reranker 惰性加载：只在需要时才初始化（模型加载要十几秒）
    # 放在金标校验之后：金标有问题就先报错退出，别白白等模型加载
    reranker = None
    if args.with_rerank:
        # reranker 工具同样来自生产代码，与 node_rerank 用同一个模型
        # 加载日志明确「本地模型」：提醒使用者这一步不花 LLM 额度
        from app.lm.reranker_utils import get_reranker_model
        print("正在加载 BGE reranker（本地模型，首次加载较慢）...")
        reranker = get_reranker_model()

    # 逐条 query：向量化一次（所有配置共享，省时间），再跑每个配置
    # 结果结构：per_config[config_name] = {"metrics": {指标: 值列表}, "latency": [毫秒]}
    # 值列表在最后统一求平均，等价于逐条指标的平均值
    # dict 初始化：给每个配置预建空指标桶，主循环只往里 append
    per_config = {name: {"metrics": {m: [] for m in METRIC_FUNCS}, "latency": []}
                  for name in configs}
    detail_rows = []  # CSV 明细行

    # 主循环：外层遍历金标 query，内层遍历配置
    for i, rec in enumerate(records, 1):
        query, gold = rec["query"], rec["gold_chunk_ids"]
        # 向量化失败不致命：跳过该条 query，其余继续（保证评测能跑完）
        # Embedding 服务抖动时宁可少几条样本也不能让整个评测中断
        try:
            # 一次生成 dense + sparse 双路向量，所有配置共用
            # 这是评测效率的关键设计：向量化（含模型推理）每条 query 只做一次
            vecs = generate_embeddings([query])
        except Exception as e:
            print(f"[{i}/{len(records)}] 向量化失败，跳过: {query[:30]}... ({e})")
            continue

        for name, (fn, use_rerank) in configs.items():
            try:
                # 计时起点：包含检索（rerank 配置还包含精排）的整段链路耗时
                # perf_counter 单调时钟，精度远高于 time.time，适合毫秒级测量
                t0 = time.perf_counter()
                hits = fn(query, vecs, PER_WAY_DEPTH)
                # rerank 配置：在混合检索结果上再做一次精排
                # 精排耗时计入 elapsed_ms，所以 rerank 行的延迟天然比纯检索高
                if use_rerank:
                    hits = rerank_docs(query, hits, reranker)
                elapsed_ms = (time.perf_counter() - t0) * 1000

                # 指标只关心 chunk_id 顺序：dict 列表压成 ID 序列
                # （rerank 会改变顺序，所以必须在精排之后再压平）
                ranked_ids = [h.get("chunk_id") for h in hits]
                row = {"query": query, "config": name}
                # 逐指标计算并累计到 per_config，同时记入 CSV 明细行
                for metric, func in METRIC_FUNCS.items():
                    val = func(ranked_ids, gold)
                    per_config[name]["metrics"][metric].append(val)
                    row[metric] = val
                # 延迟单独累计，与指标分开汇总
                # （延迟与指标是两条独立的结论线：性能代价要单独看）
                per_config[name]["latency"].append(elapsed_ms)
                detail_rows.append(row)
            except Exception as e:
                # 单配置失败不影响其他配置：评测要跑完才有对比价值
                # 失败行不会进 detail_rows，汇总时该配置的均值基于成功样本
                print(f"[{i}/{len(records)}] 配置 {name} 检索失败，跳过: {e}")
        # 进度行截断 query 到 40 字，避免刷屏
        # 进度信息里带序号与总数，长评测时可估算剩余时间
        print(f"[{i}/{len(records)}] 完成: {query[:40]}")

    # ---------- 汇总与输出 ----------
    # 循环结束后进入纯计算阶段：不再访问 Milvus / 模型
    # 输出目录固定在本目录 eval_out/ 下，不存在则创建
    out_dir = Path(__file__).resolve().parent / "eval_out"
    out_dir.mkdir(exist_ok=True)

    # 1) 汇总表：每个配置的平均指标 + 延迟
    # 平均方式：所有有效 query 的指标值简单算术平均（宏观平均）
    summary = {}
    for name, data in per_config.items():
        row = {}
        for metric, vals in data["metrics"].items():
            # 该指标没有任何有效样本时记 0，避免除零
            row[metric] = sum(vals) / len(vals) if vals else 0.0
        # 延迟统计（mean/p50/p95/n）并入同一行，报告表格一并展示
        row.update(latency_summary(data["latency"]))
        # summary 里混了指标与延迟两类数据，报告输出时按需取列
        summary[name] = row

    metric_names = list(METRIC_FUNCS.keys())
    # 相对提升的参照基线：优先 dense_only（纯稠密是最常见的起点）
    # 若 dense_only 被 --configs 过滤掉，就退化为配置表里的第一个
    # 基线选择要写进报告标题，读者才知道「提升」是相对谁而言的
    baseline_name = "dense_only" if "dense_only" in summary else list(summary)[0]

    # 2) Markdown 报告：指标表 + 相对基线的提升（绝对百分点）
    # 报告头部记录评测条件（金标集、召回深度），保证结果可复现
    lines = [
        "# RAG 检索基线对比评测报告",
        "",
        f"- 金标集：{golden_path.name}（{len(records)} 条 query）",
        f"- 召回深度：每路 {PER_WAY_DEPTH}，RRF k={RRF_K}",
        # 延迟口径要写清楚：含 Milvus 检索；rerank 配置含精排耗时
        "- 延迟为检索链路耗时（含 Milvus 检索；rerank 配置含精排耗时），本机实测",
        "",
        # Markdown 表头：指标列 + p50/p95 两列延迟
        "| 配置 | " + " | ".join(metric_names) + " | p50(ms) | p95(ms) |",
        "|" + "---|" * (len(metric_names) + 3),
    ]
    # 表体：每个配置一行，指标保留 4 位小数、延迟取整到毫秒
    # 指标缺失（该配置没跑出数据）时以 "-" 占位，表格不会因此断行
    for name, row in summary.items():
        cells = [f"{row[m]:.4f}" if m in row else "-" for m in metric_names]
        lines.append(f"| {name} | " + " | ".join(cells) + f" | {row['p50']:.0f} | {row['p95']:.0f} |")

    # 提升小节：以基线为参照，逐配置列出各指标的绝对百分点差（pp）
    # 绝对百分点（+x.x pp）比相对百分比更直观、不易夸大
    # 例：hit@5 从 0.60 升到 0.72 → "+12.0pp"，一眼看出涨了多少
    lines += ["", "## 相对基线（{}）的提升（绝对百分点）".format(baseline_name), ""]
    for name, row in summary.items():
        # 基线自己不和自己比
        if name == baseline_name:
            continue
        base = summary[baseline_name]
        deltas = [f"{m}: {100 * (row[m] - base[m]):+.1f}pp" for m in metric_names]
        lines.append(f"- **{name}**：" + "，".join(deltas))
    # 报告尾部追加的说明段：交代金标口径与未覆盖项，防止误读数字
    # 这两段话直接回应「金标偏简单」与「HyDE 没测」两个常见质疑
    lines += [
        "",
        "## 说明",
        "- 金标为「自检索」构造（query 由 chunk 标题/内容派生），偏容易；",
        "  人工把 query 改写成口语化提问后重跑，数字更接近真实场景。",
        "- HyDE 路未纳入（需调用 LLM，避免额度消耗）。",
    ]
    # Markdown 报告落盘
    report_path = out_dir / "comparison_report.md"
    report_path.write_text("\n".join(lines), encoding="utf-8")

    # 3) CSV 明细：每条 query × 每个配置一行，方便导入 Excel 做逐条分析
    # utf-8-sig 带 BOM：Windows Excel 双击打开不乱码
    # 明细行数 = 有效 query 数 × 成功配置数，可用来核对是否有配置整体失败
    csv_path = out_dir / "results.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as f:
        # 列 = query、config、六个指标，与 detail_rows 的键一一对应
        writer = csv.DictWriter(f, fieldnames=["query", "config"] + metric_names)
        writer.writeheader()
        writer.writerows(detail_rows)

    # 4) 控制台摘要
    # 每配置一行，指标 3 位小数 + 两个延迟分位
    # 控制台只给结论级数字；完整对比看 Markdown 报告，逐条分析看 CSV
    print("\n===== 评测结果摘要 =====")
    for name, row in summary.items():
        mstr = "  ".join(f"{m}={row[m]:.3f}" for m in metric_names)
        print(f"{name:24s} {mstr}  p50={row['p50']:.0f}ms p95={row['p95']:.0f}ms")
    # 最后打印两个产出文件路径，方便直接打开
    print(f"\n报告: {report_path}\n明细: {csv_path}")


if __name__ == "__main__":
    main()
