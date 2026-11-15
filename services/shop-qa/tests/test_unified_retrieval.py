"""统一检索合并逻辑的单元测试：验证 merge_ranked 的多路合并行为。

被测对象：app/shop_qa/retrieval.py 的 merge_ranked(rank_lists, shop_id)。
它把普通向量检索与 HyDE 检索两路命中结果合并成一路去重序列，
合并时只保留 shop_id 匹配（或全局文档 shop_id==0）的片段。

本测试构造了两路假数据：
- normal 与 hyde 都命中 blog:1 片段（应跨路去重只留一份）；
- normal 里还混入一条 document:abc 全局文档（不同来源，允许保留）。
断言：合并后共 2 条、且排名首位的是 blog:1。
"""
import unittest

from app.shop_qa.retrieval import merge_ranked


class UnifiedRetrievalTests(unittest.TestCase):
    # 测试点：多路合并 → 跨路去重 + 来源不串
    def test_merges_normal_and_hyde_hits_without_crossing_sources(self):
        # 第一路：普通向量检索——一条店铺笔记 + 一条全局文档
        normal = [{"entity": {"source_key": "blog:1", "part": 0, "shop_id": 7, "content": "鱼很嫩"}},
                  {"entity": {"source_key": "document:abc", "part": 1, "shop_id": 0, "content": "特色鱼"}}]
        # 第二路：HyDE 检索——与第一路命中同一个片段（source_key 相同）
        hyde = [{"entity": {"source_key": "blog:1", "part": 0, "shop_id": 7, "content": "鱼很嫩"}}]
        merged = merge_ranked([normal, hyde], 7)
        # 去重断言：blog:1 两路重复只算一条，加上全局文档共 2 条
        self.assertEqual(len(merged), 2)
        # 排序断言：两路都命中的片段相关性更高，应排在第一位
        self.assertEqual(merged[0]["source_key"], "blog:1")


if __name__ == "__main__":
    unittest.main()
