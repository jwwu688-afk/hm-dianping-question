import unittest

from app.shop_qa.documents import build_shop_documents, shop_filter


class ShopDocumentTests(unittest.TestCase):
    def test_documents_keep_shop_and_blog_sources(self):
        shop = {"id": 7, "name": "小馆", "area": "运河", "address": "河边路1号", "avg_price": 65, "score": 45, "open_hours": "10:00-22:00"}
        blogs = [{"id": 12, "shop_id": 7, "title": "好吃", "content": "鱼很嫩，环境安静。"}]

        docs = build_shop_documents(shop, blogs)

        self.assertEqual([d["source_type"] for d in docs], ["shop", "blog"])
        self.assertEqual([d["source_id"] for d in docs], [7, 12])
        self.assertTrue(all(d["shop_id"] == 7 for d in docs))
        self.assertIn("鱼很嫩", docs[1]["content"])

    def test_other_shops_are_excluded(self):
        docs = build_shop_documents({"id": 7, "name": "小馆"}, [{"id": 12, "shop_id": 8, "content": "错店"}])
        self.assertEqual(len(docs), 1)
        self.assertEqual(shop_filter(7), "shop_id == 7")


if __name__ == "__main__":
    unittest.main()
