import unittest

from app.shop_qa.index import source_filter, shop_scope_filter, replace_source, image_urls_json
import json


class FakeMilvus:
    def __init__(self):
        self.calls = []

    def delete(self, **kwargs):
        self.calls.append(("delete", kwargs))

    def insert(self, **kwargs):
        self.calls.append(("insert", kwargs))


class UnifiedIndexTests(unittest.TestCase):
    def test_source_replacement_only_deletes_matching_source(self):
        client = FakeMilvus()
        rows = [{"source_key": "blog:12", "shop_id": 7, "content": "鱼很嫩"}]
        replace_source(client, "knowledge", "blog:12", rows)
        self.assertEqual(client.calls[0], ("delete", {"collection_name": "knowledge", "filter": 'source_key == "blog:12"'}))
        self.assertEqual(client.calls[1][0], "insert")

    def test_shop_scope_includes_global_but_excludes_other_shops(self):
        self.assertEqual(shop_scope_filter(7), "shop_id in [0, 7]")
        self.assertEqual(shop_scope_filter(None), "shop_id == 0")
        self.assertEqual(source_filter("document:abc"), 'source_key == "document:abc"')

    def test_image_urls_remain_valid_json_when_field_limit_is_reached(self):
        encoded = image_urls_json(["https://example.com/" + "图" * 100] * 3, max_length=350)
        self.assertEqual(len(json.loads(encoded)), 1)
        self.assertLessEqual(len(encoded.encode("utf-8")), 350)


if __name__ == "__main__":
    unittest.main()
