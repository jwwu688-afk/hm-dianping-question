import tempfile
import unittest
from pathlib import Path

from app.shop_qa.export_markdown import _sql_values, render_shop_markdown, write_shop_markdown


class ExportMarkdownTests(unittest.TestCase):
    def test_render_keeps_shop_facts_and_blog_source(self):
        shop = {"id": 7, "name": "测试店", "address": "中山路", "avg_price": 42, "score": 46}
        blogs = [{"id": 9, "shop_id": 7, "title": "招牌菜", "content": "鱼很嫩\n服务不错"}]
        result = render_shop_markdown(shop, blogs)
        self.assertIn("# 测试店", result)
        self.assertIn("- 地址：中山路", result)
        self.assertIn("笔记 ID：9", result)
        self.assertIn("鱼很嫩\n服务不错", result)
        with tempfile.TemporaryDirectory() as directory:
            paths = write_shop_markdown([shop], blogs, Path(directory))
            self.assertEqual(paths[0].name, "shop-7.md")
            self.assertEqual(paths[0].read_text(encoding="utf-8"), result)

    def test_seed_parser_decodes_mysql_escaped_newlines(self):
        self.assertEqual(_sql_values("4, '餐厅', '第一行\\n第二行', NULL"),
                         ["4", "餐厅", "第一行\n第二行", None])


if __name__ == "__main__":
    unittest.main()
