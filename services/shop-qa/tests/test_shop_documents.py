"""店铺文档构建的单元测试：验证 build_shop_documents 与 shop_filter。

被测对象：app/shop_qa/documents.py。
- build_shop_documents(shop, blogs)：把店铺本体 + 关联笔记拼成
  可写入 Milvus 的文档列表，每条带 source_type / source_id / shop_id；
- shop_filter(shop_id)：生成 Milvus 的检索过滤表达式。

两个测试点：
1. 正常数据 → 店铺本体（shop）在前、笔记（blog）在后，内容与 ID 正确；
2. 混入别家店的笔记 → 被排除，只留下店铺本体一条。
"""
import unittest

from app.shop_qa.documents import build_shop_documents, shop_filter


class ShopDocumentTests(unittest.TestCase):
    # 测试点一：店铺本体与笔记都要保留，且来源标识正确
    def test_documents_keep_shop_and_blog_sources(self):
        # 店铺本体数据（含地址、价格等结构化字段）
        shop = {"id": 7, "name": "小馆", "area": "运河", "address": "河边路1号", "avg_price": 65, "score": 45, "open_hours": "10:00-22:00"}
        # 该店关联的一篇笔记
        blogs = [{"id": 12, "shop_id": 7, "title": "好吃", "content": "鱼很嫩，环境安静。"}]

        docs = build_shop_documents(shop, blogs)

        # 断言：店铺在前、笔记在后，source_id 分别取各自主键
        self.assertEqual([d["source_type"] for d in docs], ["shop", "blog"])
        self.assertEqual([d["source_id"] for d in docs], [7, 12])
        # 断言：所有文档都归属店铺 7（检索时按店过滤的依据）
        self.assertTrue(all(d["shop_id"] == 7 for d in docs))
        # 断言：笔记正文完整进入文档内容
        self.assertIn("鱼很嫩", docs[1]["content"])

    # 测试点二：别家店铺的笔记必须被排除
    def test_other_shops_are_excluded(self):
        # 笔记 shop_id=8 与店铺 7 不匹配 → 不应生成对应文档
        docs = build_shop_documents({"id": 7, "name": "小馆"}, [{"id": 12, "shop_id": 8, "content": "错店"}])
        # 只剩店铺本体 1 条；过滤表达式生成的字符串也要与店铺 ID 一致
        self.assertEqual(len(docs), 1)
        self.assertEqual(shop_filter(7), "shop_id == 7")


if __name__ == "__main__":
    unittest.main()
