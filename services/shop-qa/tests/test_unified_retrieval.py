import unittest

from app.shop_qa.retrieval import merge_ranked


class UnifiedRetrievalTests(unittest.TestCase):
    def test_merges_normal_and_hyde_hits_without_crossing_sources(self):
        normal = [{"entity": {"source_key": "blog:1", "part": 0, "shop_id": 7, "content": "鱼很嫩"}},
                  {"entity": {"source_key": "document:abc", "part": 1, "shop_id": 0, "content": "特色鱼"}}]
        hyde = [{"entity": {"source_key": "blog:1", "part": 0, "shop_id": 7, "content": "鱼很嫩"}}]
        merged = merge_ranked([normal, hyde], 7)
        self.assertEqual(len(merged), 2)
        self.assertEqual(merged[0]["source_key"], "blog:1")


if __name__ == "__main__":
    unittest.main()
