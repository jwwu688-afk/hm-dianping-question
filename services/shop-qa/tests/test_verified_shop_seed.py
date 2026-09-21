import json
import re
import subprocess
import unittest
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
DATA_PATH = ROOT / "services/shop-qa/data/verified_hangzhou_shops.json"
SQL_PATH = ROOT / "src/main/resources/db/shop_seed_verified_2026.sql"
MARKDOWN_DIR = ROOT / "services/shop-qa/data/shop-markdown"


class VerifiedShopSeedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shops = json.loads(DATA_PATH.read_text(encoding="utf-8"))

    def test_has_fifty_shops_and_five_per_type(self):
        self.assertEqual(len(self.shops), 50)
        self.assertEqual(Counter(s["type_id"] for s in self.shops), Counter({i: 5 for i in range(1, 11)}))

    def test_ids_are_unique_and_contiguous(self):
        self.assertEqual(sorted(s["id"] for s in self.shops), list(range(1001, 1051)))

    def test_verified_facts_are_traceable_and_in_hangzhou(self):
        for shop in self.shops:
            with self.subTest(shop=shop["id"]):
                self.assertIn("杭州市", shop["address"])
                self.assertTrue(118.9 <= shop["x"] <= 120.7)
                self.assertTrue(29.1 <= shop["y"] <= 30.6)
                self.assertRegex(shop["source_url"], r"^https://")
                self.assertEqual(shop["verified_on"], "2026-09-19")
                self.assertTrue(shop["features"].strip())

    def test_sql_is_idempotent_and_contains_every_shop(self):
        sql = SQL_PATH.read_text(encoding="utf-8")
        self.assertIn("ON DUPLICATE KEY UPDATE", sql)
        self.assertIn("部分坐标由公开地址定位，可能存在偏差", sql)
        ids = [int(value) for value in re.findall(r"^\((10\d{2}),", sql, flags=re.MULTILINE)]
        self.assertEqual(ids, list(range(1001, 1051)))

    def test_markdown_contains_source_and_demo_disclosure(self):
        for shop in self.shops:
            path = MARKDOWN_DIR / f"shop-{shop['id']}.md"
            content = path.read_text(encoding="utf-8")
            with self.subTest(shop=shop["id"]):
                self.assertIn(f"# {shop['name']}", content)
                self.assertIn(shop["source_url"], content)
                self.assertIn("核对日期：2026-09-19", content)
                self.assertIn("界面演示字段", content)
                self.assertIn("## 店铺特色", content)
                self.assertIn("部分坐标由公开地址定位，可能存在偏差", content)

    def test_source_data_and_generated_markdown_are_not_git_ignored(self):
        paths = [
            "services/shop-qa/data/verified_hangzhou_shops.json",
            "services/shop-qa/data/shop-markdown/shop-1001.md",
            "services/shop-qa/data/shop-markdown/shop-1050.md",
        ]
        ignored = []
        for path in paths:
            result = subprocess.run(
                ["git", "check-ignore", "--quiet", path], cwd=ROOT, check=False
            )
            if result.returncode == 0:
                ignored.append(path)
        self.assertEqual(ignored, [], "verified RAG inputs must be trackable by Git")


if __name__ == "__main__":
    unittest.main()
