"""金标测试集构建脚本（零 LLM 消耗）。

用途：
    从 Milvus 知识片段集合（CHUNKS_COLLECTION）中均匀采样 N 个 chunk，
    为每个 chunk 自动构造一条「草稿 query」+ 金标 chunk_id，输出 JSONL 测试集，
    供 run_eval.py 做基线对比评测。

金标构造原理（自检索法，伪相关金标）：
    「query 是从某个 chunk 的标题/内容派生出来的」→ 该 chunk 就是这条 query 的金标答案。
    这是检索评测中常用的低成本建标方法；缺点是 query 偏「标题腔」，
    对稠密/稀疏都偏容易，所以：
    【强烈建议】构建后人工花 15~30 分钟把 query 改写成真实用户会问的口语
    （比如「HAK 180 烫金机 操作说明」→「烫金机怎么开机啊」），
    改写后的数字才更接近真实场景，写简历时也更有说服力。
    同一 parent_title 下的兄弟片段如果内容相关，也可以人工补进 gold_chunk_ids。

为什么不用 LLM 生成 query：
    本脚本刻意做成零 LLM 版本——批量生成会消耗模型额度。
    如果以后想用 LLM 辅助生成口语化 query，请单独向项目负责人确认后再加。

输出格式（JSONL，每行一条）：
    {"query": "...", "gold_chunk_ids": [123], "item_name": "...",
     "parent_title": "...", "auto_generated": true, "needs_review": true}

用法：
    python scripts/eval/build_golden_set.py --num 30          # 均匀采样 30 条
    python scripts/eval/build_golden_set.py --num 50 --seed 7 # 固定随机种子，可复现

流程总览（四步）：
    1) fetch_all_chunks：全量拉取知识库片段；
    2) 按 chunk_id 排序后等距采样（覆盖各店铺/章节）；
    3) make_draft_query：规则式拼出草稿 query；
    4) 该 chunk 自身的 chunk_id 即金标，落盘 JSONL。
"""

import argparse
import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv, find_dotenv

# 加载 .env：Milvus 连接信息在这里，必须先于 import app 执行
load_dotenv(find_dotenv())

from app.clients.milvus_utils import get_milvus_client  # noqa: E402

OUTPUT_PATH = Path(__file__).resolve().parent / "golden_set.jsonl"

# 拉取时需要的字段（与生产 output_fields 一致，多取 title 用于构造草稿 query）
# file_title 是 hmdp_knowledge_v2 集合的「文档标题」字段（对应别处的 parent_title）
FETCH_FIELDS = ["chunk_id", "content", "title", "parent_title", "item_name"]


def fetch_all_chunks() -> list[dict]:
    """从评测目标集合拉取全部 chunk（上限 5000，当前店铺知识库 64 条足够）。

    集合选择与 milvus_search.py 一致：优先 EVAL_COLLECTION，默认 hmdp_knowledge_v2
    （生产上传链路真正写入的集合，检索评测必须和生产行为对齐）。
    """
    # 环境变量在函数内读取：保证先执行了 load_dotenv 再取值
    import os
    collection = os.environ.get("EVAL_COLLECTION") or "hmdp_knowledge_v2"
    client = get_milvus_client()
    # filter="chunk_id >= 0" 是无实际过滤条件的全量查询写法（Milvus query 必须带过滤表达式）
    # limit=5000 是当前规模的宽松上限；知识库远超此量时需分页拉取
    rows = client.query(
        collection_name=collection,
        filter="chunk_id >= 0",
        output_fields=["chunk_id", "content", "title", "file_title", "shop_id"],
        limit=5000,
    )
    print(f"从集合 [{collection}] 拉取到 {len(rows)} 条 chunk")
    return rows


def make_draft_query(chunk: dict) -> str:
    """用规则构造草稿 query：店铺/章节标题 + 内容开头的一段。

    规则式构造的好处是零成本、可复现；坏处是不够口语化，
    所以输出里标记 needs_review=true，提醒使用者人工改写。
    拼接逻辑：标题提供「问什么主题」，内容开头提供「细节线索」，
    两段拼起来已能唯一定位到这个 chunk（这正是自检索法成立的前提）。
    """
    # 标题优先取 chunk 级 title；没有则退回文档级 file_title
    title = (chunk.get("title") or chunk.get("file_title") or "").strip()
    # 清理 content 开头的 markdown 符号/换行，取前 60 字作为语义线索
    # 符号（#/*/- 等）对检索无益且会污染 query，统一替换成空格
    content = (chunk.get("content") or "").strip()
    content = content.replace("\n", " ")
    # 六种常见 markdown 语法符号逐一剔除
    for sym in ("#", "*", "-", "|", ">", "`"):
        content = content.replace(sym, " ")
    # 压缩连续空白再截断 60 字：query 太长会稀释向量表示
    content_head = " ".join(content.split())[:60]
    # 拼接：标题给主题，内容开头给细节线索
    # 兜底：标题和内容都为空时用 chunk_id 拼一个占位 query，保证不产出空行
    query = f"{title} {content_head}".strip()
    return query or f"chunk {chunk.get('chunk_id')}"


def main() -> None:
    """命令行入口：解析参数 → 拉取 → 采样 → 构造 → 写 JSONL。"""
    parser = argparse.ArgumentParser(description="构建金标测试集（零 LLM 消耗）")
    # num 控制金标规模：条数太少指标波动大，太多则人工校对负担重
    parser.add_argument("--num", type=int, default=30, help="采样条数，默认 30")
    # seed 固定随机行为，保证两次运行产出完全相同的测试集
    parser.add_argument("--seed", type=int, default=42, help="随机种子，保证可复现")
    args = parser.parse_args()

    chunks = fetch_all_chunks()
    # 空集合多半是导入链路没跑过，先导数据再建标
    if not chunks:
        print("集合为空，请先通过导入链路写入知识库后再构建金标集")
        return

    # 均匀采样：先按 chunk_id 排序保证顺序稳定，再等距取点（比纯随机更能覆盖各商品/章节）
    # step = 总数/采样数 向上取整至少为 1；切片步长取到 num 条为止
    chunks.sort(key=lambda c: c.get("chunk_id") or 0)
    step = max(1, len(chunks) // args.num)
    sampled = chunks[::step][:args.num]

    records = []
    # 每条记录 = 一条 query + 它的金标（该 chunk 自身的 ID）
    # item_name/parent_title 带出去是给人工校对时快速定位用的
    for c in sampled:
        records.append({
            "query": make_draft_query(c),
            # 金标列表：当前每条只有一个金标；人工可把同章节相关片段补进来
            "gold_chunk_ids": [c.get("chunk_id")],
            "item_name": c.get("file_title") or "",   # 店铺知识库用 file_title 标识来源
            "parent_title": c.get("title") or "",
            # auto_generated 标记「机器生成」；needs_review 标记「待人工改写」
            # 改写完记得把 needs_review 手动改回 false
            "auto_generated": True,
            "needs_review": True,  # 人工改写 query 后请手动改回 false
        })

    # JSONL 落盘：一行一条，run_eval.py 按行读取
    OUTPUT_PATH.write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in records),
        encoding="utf-8",
    )
    print(f"已写出 {len(records)} 条草稿金标 → {OUTPUT_PATH}")
    # 输出后两条提醒是给使用者的行动项：改写 query、补充金标
    print("提醒：当前 query 为「标题腔」草稿，建议人工改写成口语化提问后再跑评测，")
    print("      （改写时保持 gold_chunk_ids 不变；同一章节的相关片段可补进 gold_chunk_ids）")


if __name__ == "__main__":
    main()
