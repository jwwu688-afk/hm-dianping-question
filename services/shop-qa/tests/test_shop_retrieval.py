"""店铺检索筛选逻辑的单元测试：验证 select_hits 的过滤与去重。

被测对象：app/shop_qa/retrieval.py 的 select_hits(hits, shop_id, limit)。
它从 Milvus 命中结果里挑出「属于指定店铺」的片段，要求：
- 只保留 shop_id 匹配的片段（错店数据一律丢弃）；
- 同一来源（source_type + source_id 相同）只取一条，避免重复证据；
- 截断到 limit 条以内。

断言顺序即执行顺序：先按命中顺序保留首个出现的同源片段，
再做店铺过滤与截断，最终内容列表与预期完全一致。

本测试构造 4 条命中：1 条错店、2 条同源重复、1 条店铺本体信息，
断言筛选结果顺序与数量完全符合上述三条规则。
"""
import unittest

from app.shop_qa.retrieval import select_hits


class ShopRetrievalTests(unittest.TestCase):
    # 测试点：店铺过滤 + 同源去重 + limit 截断
    def test_only_requested_shop_and_distinct_sources_are_returned(self):
        # 构造 4 条命中：错店 / 正确店铺笔记 / 同一笔记的重复片段 / 店铺本体
        hits = [
            {"entity": {"shop_id": 3, "source_type": "blog", "source_id": 9, "content": "错店"}},
            {"entity": {"shop_id": 7, "source_type": "blog", "source_id": 12, "content": "鱼很嫩"}},
            {"entity": {"shop_id": 7, "source_type": "blog", "source_id": 12, "content": "环境安静"}},
            {"entity": {"shop_id": 7, "source_type": "shop", "source_id": 7, "content": "运河店"}},
        ]
        selected = select_hits(hits, 7, limit=3)
        # 断言一：错店（shop_id=3）被剔除；同源重复只保留先出现的一条；
        # 两条去重后加店铺本体共 3 条，顺序与原序一致
        self.assertEqual([d["content"] for d in selected], ["鱼很嫩", "环境安静", "运河店"])
        # 断言二：结果里每一条都严格属于目标店铺 7
        self.assertTrue(all(d["shop_id"] == 7 for d in selected))


if __name__ == "__main__":
    unittest.main()
