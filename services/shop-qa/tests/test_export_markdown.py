"""Markdown 导出功能的单元测试：验证渲染内容与 SQL 转义解析。

被测对象：app/shop_qa/export_markdown.py 的三个函数：
- render_shop_markdown(shop, blogs)：把店铺 + 笔记渲染成单店 MD 文本；
- write_shop_markdown(shops, blogs, dir)：批量写盘为 shop-<id>.md；
- _sql_values(line)：解析 SQL 种子文件里 INSERT 行的值列表，
  其中要正确处理 MySQL 转义的 \\n（字符串内换行）。

测试点：
1. 渲染文本保留店铺事实（标题、地址）并标注笔记来源；
2. 写盘后的文件名与内容与渲染结果一致；
3. SQL 值解析能还原转义换行、识别 NULL。

\\n 转义还原的意义：种子 SQL 里换行写作字面 \\n，导入 MySQL 后
变成真实换行；导出 MD 时必须逆向还原，否则笔记正文挤成一行。
"""
import tempfile
import unittest
from pathlib import Path

from app.shop_qa.export_markdown import _sql_values, render_shop_markdown, write_shop_markdown


class ExportMarkdownTests(unittest.TestCase):
    # 测试点一：渲染保留店铺事实 + 笔记来源可追溯 + 写盘结果一致
    def test_render_keeps_shop_facts_and_blog_source(self):
        # 构造带地址/价格/评分的店铺与一篇多行内容的笔记
        shop = {"id": 7, "name": "测试店", "address": "中山路", "avg_price": 42, "score": 46}
        blogs = [{"id": 9, "shop_id": 7, "title": "招牌菜", "content": "鱼很嫩\n服务不错"}]
        result = render_shop_markdown(shop, blogs)
        # 内容断言：一级标题是店名、地址进列表项、笔记标注来源 ID、正文原样保留
        # （含换行的正文要原样保留，证明渲染没有吞掉或改写笔记内容）
        self.assertIn("# 测试店", result)
        self.assertIn("- 地址：中山路", result)
        self.assertIn("笔记 ID：9", result)
        self.assertIn("鱼很嫩\n服务不错", result)
        # 写盘断言：在临时目录里实际写文件，核对文件名与内容逐字一致
        # （用完即焚的临时目录，不产生测试残留文件）
        with tempfile.TemporaryDirectory() as directory:
            paths = write_shop_markdown([shop], blogs, Path(directory))
            self.assertEqual(paths[0].name, "shop-7.md")
            self.assertEqual(paths[0].read_text(encoding="utf-8"), result)

    # 测试点二：SQL 种子行的解析——MySQL 的 \n 转义要还原成真实换行
    def test_seed_parser_decodes_mysql_escaped_newlines(self):
        # 输入一行 INSERT 值：数字、字符串（含转义 \n）、NULL 三种形态
        # 期望输出：数字仍是字符串形式、NULL 转成 Python None、\n 变成真实换行
        self.assertEqual(_sql_values("4, '餐厅', '第一行\\n第二行', NULL"),
                         ["4", "餐厅", "第一行\n第二行", None])


if __name__ == "__main__":
    unittest.main()
