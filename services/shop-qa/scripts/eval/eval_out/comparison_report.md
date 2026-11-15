# RAG 检索基线对比评测报告

- 金标集：golden_set.jsonl（64 条 query）
- 召回深度：每路 10，RRF k=60
- 延迟为检索链路耗时（含 Milvus 检索；rerank 配置含精排耗时），本机实测

| 配置 | hit@1 | hit@3 | hit@5 | recall@5 | mrr@10 | ndcg@5 | p50(ms) | p95(ms) |
|---|---|---|---|---|---|---|---|---|
| dense_only | 0.9375 | 1.0000 | 1.0000 | 1.0000 | 0.9688 | 0.9769 | 5 | 8 |
| sparse_only | 0.9688 | 1.0000 | 1.0000 | 1.0000 | 0.9844 | 0.9885 | 4 | 6 |
| hybrid_w_50_50 | 0.9688 | 1.0000 | 1.0000 | 1.0000 | 0.9844 | 0.9885 | 6 | 9 |
| hybrid_w_80_20 | 0.9688 | 1.0000 | 1.0000 | 1.0000 | 0.9844 | 0.9885 | 6 | 8 |
| hybrid_rrf | 0.9531 | 1.0000 | 1.0000 | 1.0000 | 0.9766 | 0.9827 | 7 | 9 |
| hybrid_w_80_20_rerank | 0.9531 | 1.0000 | 1.0000 | 1.0000 | 0.9766 | 0.9827 | 33090 | 52447 |

## 相对基线（dense_only）的提升（绝对百分点）

- **sparse_only**：hit@1: +3.1pp，hit@3: +0.0pp，hit@5: +0.0pp，recall@5: +0.0pp，mrr@10: +1.6pp，ndcg@5: +1.2pp
- **hybrid_w_50_50**：hit@1: +3.1pp，hit@3: +0.0pp，hit@5: +0.0pp，recall@5: +0.0pp，mrr@10: +1.6pp，ndcg@5: +1.2pp
- **hybrid_w_80_20**：hit@1: +3.1pp，hit@3: +0.0pp，hit@5: +0.0pp，recall@5: +0.0pp，mrr@10: +1.6pp，ndcg@5: +1.2pp
- **hybrid_rrf**：hit@1: +1.6pp，hit@3: +0.0pp，hit@5: +0.0pp，recall@5: +0.0pp，mrr@10: +0.8pp，ndcg@5: +0.6pp
- **hybrid_w_80_20_rerank**：hit@1: +1.6pp，hit@3: +0.0pp，hit@5: +0.0pp，recall@5: +0.0pp，mrr@10: +0.8pp，ndcg@5: +0.6pp

## 说明
- 金标为「自检索」构造（query 由 chunk 标题/内容派生），偏容易；
  人工把 query 改写成口语化提问后重跑，数字更接近真实场景。
- HyDE 路未纳入（需调用 LLM，避免额度消耗）。