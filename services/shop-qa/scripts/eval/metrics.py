"""评测指标模块：纯函数实现，与业务代码零耦合，方便单独测试或复用。

指标选型说明（对应简历上的数字）：
- hit@k（检索命中率）：Top-k 里只要出现 1 个金标 chunk 即算命中，最直观的「准确率」指标；
- recall@k：Top-k 覆盖了多少比例的金标（金标可以有多条）；
- mrr@k（平均倒数排名）：首个命中的位置的倒数，衡量「最好的结果排得靠不靠前」；
- ndcg@k：带位置折扣的排序质量分，比 hit@k 更细粒度地反映排序好坏；
- 延迟统计：p50 / p95 / 均值，用来回答「优化有没有付出性能代价」。

金标格式：gold_chunk_ids 是一个列表（一条 query 可能对应多个相关片段）。
"""

from typing import Sequence


def hit_at_k(ranked_ids: Sequence, gold_ids: Sequence, k: int) -> int:
    """hit@k：Top-k 中是否出现任一金标 chunk_id。命中返回 1，否则 0（多金标时任一命中即算）。"""
    topk = set(ranked_ids[:k])
    return 1 if topk.intersection(gold_ids) else 0


def recall_at_k(ranked_ids: Sequence, gold_ids: Sequence, k: int) -> float:
    """recall@k：Top-k 命中的金标数 / 金标总数。金标为空时返回 0（不参与统计由上层过滤）。"""
    if not gold_ids:
        return 0.0
    topk = set(ranked_ids[:k])
    return len(topk.intersection(gold_ids)) / len(set(gold_ids))


def mrr_at_k(ranked_ids: Sequence, gold_ids: Sequence, k: int) -> float:
    """MRR@k：第一个命中的金标在 Top-k 中的排名倒数；若 k 内无命中则为 0。

    例：金标排在第 1 位 → 1.0；第 3 位 → 0.33；Top-k 内没有 → 0。
    """
    gold_set = set(gold_ids)
    for rank, cid in enumerate(ranked_ids[:k], start=1):
        if cid in gold_set:
            return 1.0 / rank
    return 0.0


def ndcg_at_k(ranked_ids: Sequence, gold_ids: Sequence, k: int) -> float:
    """nDCG@k：二值相关性的归一化折损累计增益。

    DCG  = Σ (rel_i) / log2(i + 1)，i 从 1 开始；rel_i ∈ {0, 1}（金标=1）
    IDCG = 理想排序（所有金标挤在头部）的 DCG；nDCG = DCG / IDCG，范围 [0, 1]。
    """
    if not gold_ids:
        return 0.0
    gold_set = set(gold_ids)
    dcg = 0.0
    for rank, cid in enumerate(ranked_ids[:k], start=1):
        if cid in gold_set:
            dcg += 1.0 / _log2(rank + 1)
    # 理想情况：min(金标数, k) 个相关文档全部排在最前面
    ideal_hits = min(len(gold_set), k)
    idcg = sum(1.0 / _log2(r + 1) for r in range(1, ideal_hits + 1))
    return dcg / idcg if idcg > 0 else 0.0


def _log2(x: float) -> float:
    """log2 的简单封装，避免整个模块引入 math 依赖（其实引入也没问题，这里为了纯函数自洽）。"""
    import math
    return math.log2(x)


def latency_summary(times_ms: Sequence[float]) -> dict:
    """延迟统计：返回均值 / p50 / p95（毫秒）。

    实现方式：排序后按百分位取值。样本量小时 p95 就是「接近最慢的那次」，
    足够用来对比「优化前后延迟是否在同一个量级」。
    """
    if not times_ms:
        return {"mean": 0.0, "p50": 0.0, "p95": 0.0, "n": 0}
    ordered = sorted(times_ms)

    def _pct(p: float) -> float:
        # 线性插值取百分位：索引 = p * (n-1)，对样本量小的情况更平滑
        idx = p * (len(ordered) - 1)
        low, high = int(idx), min(int(idx) + 1, len(ordered) - 1)
        frac = idx - low
        return ordered[low] * (1 - frac) + ordered[high] * frac

    return {
        "mean": sum(ordered) / len(ordered),
        "p50": _pct(0.50),
        "p95": _pct(0.95),
        "n": len(ordered),
    }
