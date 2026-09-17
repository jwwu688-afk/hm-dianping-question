import unittest

from app.shop_qa.retrieval import select_hits


class ShopRetrievalTests(unittest.TestCase):
    def test_only_requested_shop_and_distinct_sources_are_returned(self):
        hits = [
            {"entity": {"shop_id": 3, "source_type": "blog", "source_id": 9, "content": "错店"}},
            {"entity": {"shop_id": 7, "source_type": "blog", "source_id": 12, "content": "鱼很嫩"}},
            {"entity": {"shop_id": 7, "source_type": "blog", "source_id": 12, "content": "环境安静"}},
            {"entity": {"shop_id": 7, "source_type": "shop", "source_id": 7, "content": "运河店"}},
        ]
        selected = select_hits(hits, 7, limit=3)
        self.assertEqual([d["content"] for d in selected], ["鱼很嫩", "环境安静", "运河店"])
        self.assertTrue(all(d["shop_id"] == 7 for d in selected))


if __name__ == "__main__":
    unittest.main()
