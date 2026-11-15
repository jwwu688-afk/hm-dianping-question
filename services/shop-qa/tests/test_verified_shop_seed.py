"""已验证店铺种子数据的一致性测试：数据文件、SQL 种子、Markdown 三方对齐。

被测的是三份产物（不是某个函数）：
- data/verified_hangzhou_shops.json：50 家已联网核对的杭州店铺数据；
- src/main/resources/db/shop_seed_verified_2026.sql：幂等 MySQL 种子；
- data/shop-markdown/shop-<id>.md：RAG 知识库用的单店 Markdown。

六个测试点分别校验：数量与分类分布、ID 连续唯一、事实可溯源（地址/
坐标/来源链接/核对日期）、SQL 幂等且覆盖全量、MD 含来源与数据说明、
三份产物未被 .gitignore 排除（RAG 输入必须可被 Git 追踪）。
运行前置：项目根目录需为 Git 仓库（test_source_data... 会调 git check-ignore）。

背景：这批数据是「联网核对过的真实门店」，店名/地址/分类来自公开来源，
价格与评分是界面演示字段——所以每份产物都必须自带溯源与免责声明，
本测试保证这一约定不被后续改动破坏。任何一项断言失败都意味着
「源头数据、建库种子、检索语料」三者之间出现了不一致。
"""
import json
import re
import subprocess
import unittest
from collections import Counter
from pathlib import Path


# 项目根目录（本文件位于 services/shop-qa/tests/ 上三级）与三份产物的路径
# 三份产物必须同时存在且内容一致：JSON 是源头，SQL 供建库，MD 供 RAG 检索
# 其中 SQL 路径指向 Java 侧的 resources 目录，体现「种子同时服务建库与检索」
ROOT = Path(__file__).resolve().parents[3]
DATA_PATH = ROOT / "services/shop-qa/data/verified_hangzhou_shops.json"
SQL_PATH = ROOT / "src/main/resources/db/shop_seed_verified_2026.sql"
MARKDOWN_DIR = ROOT / "services/shop-qa/data/shop-markdown"


class VerifiedShopSeedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # 整个测试类只读一次 JSON（50 条店铺数据），各测试共享
        # read_text 显式指定 UTF-8：Windows 默认编码会把中文读成乱码
        cls.shops = json.loads(DATA_PATH.read_text(encoding="utf-8"))

    # 测试点一：总量 50 家，且 10 个分类（type_id 1~10）每类恰好 5 家
    # 数量与分布是演示数据的硬约定：保证每个分类页都有内容可查
    def test_has_fifty_shops_and_five_per_type(self):
        # 总量断言：不足 50 说明生成脚本漏跑或数据被误删
        self.assertEqual(len(self.shops), 50)
        # Counter 比对：每个 type_id 的出现次数都应等于 5
        self.assertEqual(Counter(s["type_id"] for s in self.shops), Counter({i: 5 for i in range(1, 11)}))

    # 测试点二：ID 从 1001 到 1050 连续且无重复（排序后恰为连续区间）
    # 连续 ID 让前端可以按 shop-<id>.md 规律寻址 Markdown 文件
    # 排序后比对连续区间，等价于「无重复 + 无空洞 + 范围正确」三重校验
    def test_ids_are_unique_and_contiguous(self):
        self.assertEqual(sorted(s["id"] for s in self.shops), list(range(1001, 1051)))

    # 测试点三：每家店的事实字段可溯源——地址在杭州、坐标落在杭州经纬度范围、
    # 来源链接是 https、核对日期统一、特色描述非空
    # 可溯源的意义：用户追问「这是哪来的」时，RAG 能答出核对来源与日期
    def test_verified_facts_are_traceable_and_in_hangzhou(self):
        for shop in self.shops:
            # subTest 保证某一家店失败时其余店仍会继续校验、完整报告
            with self.subTest(shop=shop["id"]):
                # 地址含「杭州市」：确保没有混入其他城市的门店
                self.assertIn("杭州市", shop["address"])
                # 经度 118.9~120.7、纬度 29.1~30.6：杭州及下辖区域的合理范围
                # 坐标是 GCJ-02 展示用，只校验大致落点、不校验精确性（见数据说明）
                self.assertTrue(118.9 <= shop["x"] <= 120.7)
                self.assertTrue(29.1 <= shop["y"] <= 30.6)
                # 来源必须是 https 外链（http 或空串都算不合格）
                self.assertRegex(shop["source_url"], r"^https://")
                # 全量数据一次核对完成，核对日期应完全一致
                self.assertEqual(shop["verified_on"], "2026-09-19")
                # 特色描述非空：空串会导致 MD 的「店铺特色」章节没有检索价值
                self.assertTrue(shop["features"].strip())

    # 测试点四：SQL 种子幂等（ON DUPLICATE KEY UPDATE）且 50 家店一条不少
    # 幂等的意义：演示环境可反复执行 seed 脚本而不会报错或产生重复行
    def test_sql_is_idempotent_and_contains_every_shop(self):
        sql = SQL_PATH.read_text(encoding="utf-8")
        # 幂等关键：重复执行时按主键更新而不是报主键冲突
        self.assertIn("ON DUPLICATE KEY UPDATE", sql)
        # 数据免责声明必须写进 SQL 注释（建库的人也会看到）
        self.assertIn("部分坐标由公开地址定位，可能存在偏差", sql)
        # 从每个 VALUES 行首提取店铺 ID，应恰好是 1001~1050 全量有序
        # 正则 ^(10\d{2}) 只匹配行首括号里的 10xx ID，避免误抓图片 URL 里的数字
        # MULTILINE 让 ^ 匹配每个 VALUES 行的行首（整个 INSERT 是一条多行语句）
        ids = [int(value) for value in re.findall(r"^\((10\d{2}),", sql, flags=re.MULTILINE)]
        self.assertEqual(ids, list(range(1001, 1051)))

    # 测试点五：每份 Markdown 都含店名标题、来源链接、核对日期、
    # 演示字段说明与特色章节（RAG 检索到的内容必须带溯源信息）
    # 这份 MD 会按「一份一店」写入知识库，格式损坏直接影响回答质量
    def test_markdown_contains_source_and_demo_disclosure(self):
        for shop in self.shops:
            # 逐店找到对应的 shop-<id>.md 再校验内容（缺文件会直接抛 IOError）
            path = MARKDOWN_DIR / f"shop-{shop['id']}.md"
            content = path.read_text(encoding="utf-8")
            with self.subTest(shop=shop["id"]):
                # 标题、来源 URL、核对日期逐项核对，保证能回答「你是从哪查的」
                self.assertIn(f"# {shop['name']}", content)
                self.assertIn(shop["source_url"], content)
                # 核对日期与 JSON 里的 verified_on 字段保持同一约定值
                self.assertIn("核对日期：2026-09-19", content)
                # 界面演示字段（价格/评分等非真实数据）与坐标偏差声明必须注明
                # 两份声明缺一不可：前者防「数据可信度」误解，后者防「地图精确性」误解
                self.assertIn("界面演示字段", content)
                # 特色章节是检索的主要内容来源，标题缺失说明模板坏了
                self.assertIn("## 店铺特色", content)
                self.assertIn("部分坐标由公开地址定位，可能存在偏差", content)

    # 测试点六：三份产物不能被 .gitignore 排除——它们是 RAG 检索的输入，
    # 被忽略会导致别人 clone 后知识库空缺、检索结果无法复现
    def test_source_data_and_generated_markdown_are_not_git_ignored(self):
        # 抽查首、中、尾三个文件（1001 / 首个 MD / 1050）代表全部产物
        # JSON + 首尾两份 MD：若忽略规则按目录/通配符命中，这三个样本足够暴露
        paths = [
            "services/shop-qa/data/verified_hangzhou_shops.json",
            "services/shop-qa/data/shop-markdown/shop-1001.md",
            "services/shop-qa/data/shop-markdown/shop-1050.md",
        ]
        # 逐个调用 git check-ignore：returncode==0 表示该路径被忽略规则命中
        # --quiet 不打印匹配的规则名，我们只关心「是否被忽略」
        ignored = []
        for path in paths:
            result = subprocess.run(
                ["git", "check-ignore", "--quiet", path], cwd=ROOT, check=False
            )
            if result.returncode == 0:
                ignored.append(path)
        # 断言没有任何一个文件被忽略，否则 RAG 输入不可追踪
        # 失败信息里带英文结论，方便直接贴到提交说明里解释为什么必须提交
        self.assertEqual(ignored, [], "verified RAG inputs must be trackable by Git")


if __name__ == "__main__":
    unittest.main()
