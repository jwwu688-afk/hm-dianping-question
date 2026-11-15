"""统一索引层的单元测试：源替换、店铺范围过滤、图片字段编码。

被测对象：app/shop_qa/index.py 的四个工具函数 + 一个 FakeMilvus 假客户端。
- replace_source：重建某来源时应「先按 source_key 精确删除、再插入新行」；
- shop_scope_filter：检索范围是「本店 + 全局文档（shop_id==0）」，不含别家店；
- source_filter：按 source_key 精确定位单个来源；
- image_urls_json：图片列表编码为 JSON 时须控制字节长度并保证 JSON 合法。

FakeMilvus 只记录 delete/insert 的调用参数，避免单测真连 Milvus。
三个测试点对应三种风险：替换时误删他人数据、检索时越权看到别家店、
图片超长把 JSON 字段截断导致解析失败。
"""
import unittest

from app.shop_qa.index import source_filter, shop_scope_filter, replace_source, image_urls_json
import json


class FakeMilvus:
    """假 Milvus 客户端：只记录调用，不执行真实删写。"""

    def __init__(self):
        # 每次调用追加一条 ("delete"/"insert", 参数字典)，供断言核对调用序列
        self.calls = []

    def delete(self, **kwargs):
        self.calls.append(("delete", kwargs))

    def insert(self, **kwargs):
        self.calls.append(("insert", kwargs))


class UnifiedIndexTests(unittest.TestCase):
    # 测试点一：源替换 = 先精确删除旧来源，再插入新数据（顺序不可反）
    # 若删除条件写宽了（如按 shop_id 删）会把别的来源一起误删——本测试防的就是它
    def test_source_replacement_only_deletes_matching_source(self):
        client = FakeMilvus()
        rows = [{"source_key": "blog:12", "shop_id": 7, "content": "鱼很嫩"}]
        replace_source(client, "knowledge", "blog:12", rows)
        # 断言一：第一次调用是 delete，且过滤条件精确锁定 blog:12，不会误删其他来源
        self.assertEqual(client.calls[0], ("delete", {"collection_name": "knowledge", "filter": 'source_key == "blog:12"'}))
        # 断言二：第二次调用是 insert（新数据写入）
        self.assertEqual(client.calls[1][0], "insert")

    # 测试点二：检索范围过滤表达式——全局文档永远可见，别家店不可见
    # shop_id==0 是全局共享文档（平台公告等），任何店铺会话都允许检索到
    def test_shop_scope_includes_global_but_excludes_other_shops(self):
        # 店铺 7 的检索范围：全局文档（shop_id==0）+ 本店（7）
        self.assertEqual(shop_scope_filter(7), "shop_id in [0, 7]")
        # 未指定店铺时只看全局文档
        self.assertEqual(shop_scope_filter(None), "shop_id == 0")
        # source_filter 生成按来源精确删除/查询的表达式
        self.assertEqual(source_filter("document:abc"), 'source_key == "document:abc"')

    # 测试点三：图片字段超长时的降级编码——宁可丢图也不能让 JSON 被截断损坏
    # 背景：Milvus 字段有字节上限，超限写入会直接报错
    def test_image_urls_remain_valid_json_when_field_limit_is_reached(self):
        # 3 个超长 URL，总字节必然超过 350 的字段上限
        encoded = image_urls_json(["https://example.com/" + "图" * 100] * 3, max_length=350)
        # 断言一：编码结果仍是合法 JSON，且按空间只保留了放得下的 1 个
        self.assertEqual(len(json.loads(encoded)), 1)
        # 断言二：最终字节长度严格遵守字段上限
        self.assertLessEqual(len(encoded.encode("utf-8")), 350)


if __name__ == "__main__":
    unittest.main()
