"""Generate idempotent MySQL seed SQL and RAG Markdown from verified shop data."""

# 从已核对的店铺数据（verified_hangzhou_shops.json，50 家杭州真实门店）
# 一次性生成两份产物：
# 1. src/main/resources/db/shop_seed_verified_2026.sql
#    幂等的 MySQL 种子：INSERT ... ON DUPLICATE KEY UPDATE，可反复执行；
# 2. data/shop-markdown/shop-<id>.md
#    每店一份 Markdown，是店铺问答知识库的检索语料（RAG 输入）。
#
# 数据约定（与 tests/test_verified_shop_seed.py 的断言一一对应）：
# - 店名 / 分类 / 地址 / 来源链接为联网核对的真实信息（核对日 2026-09-19）；
# - 坐标用于地图展示，部分由公开地址定位，允许有偏差；
# - 人均 / 销量 / 评论数 / 评分为界面演示字段，不代表任何平台实时数据；
# - 图片复用项目已有的分类图标，不是门店实景。
#
# 用法：python scripts/generate_verified_shop_seed.py（在 services/shop-qa 下执行）

from __future__ import annotations

import json
from pathlib import Path


# 三个路径锚点：脚本位于 services/shop-qa/scripts/，上溯三级到仓库根目录
REPO_ROOT = Path(__file__).resolve().parents[3]
# 输入：已核对店铺数据（唯一事实来源，两份产物都由它派生）
DATA_PATH = REPO_ROOT / "services/shop-qa/data/verified_hangzhou_shops.json"
# 输出一：Java 侧建库种子（放在 Spring 的 resources/db 下）
SQL_PATH = REPO_ROOT / "src/main/resources/db/shop_seed_verified_2026.sql"
# 输出二：RAG 知识库语料目录，文件名规律 shop-<id>.md
MARKDOWN_DIR = REPO_ROOT / "services/shop-qa/data/shop-markdown"
# 分类 ID → 中文名映射，渲染 Markdown 时展示用（与 tb_shop_type 对应）
TYPE_NAMES = {
    1: "美食", 2: "KTV", 3: "丽人·美发", 4: "健身运动", 5: "按摩·足疗",
    6: "美容SPA", 7: "亲子游乐", 8: "酒吧", 9: "轰趴馆", 10: "美睫·美甲",
}


def sql_string(value: object) -> str:
    """把任意值转成带单引号的 SQL 字符串字面量，并完成三重转义。

    - 反斜杠：MySQL 默认把 \\ 当转义符，必须先翻倍；
    - 单引号：SQL 字符串的定界符，翻倍转义；
    - 换行：种子文件要求单行 INSERT，写成 \\n 字面量。
    导入 MySQL 后 \\n 会被还原成真实换行（见 export_markdown 的解析测试）。
    """
    text = str(value).replace("\\", "\\\\").replace("'", "''").replace("\n", "\\n")
    return f"'{text}'"


def render_sql(shops: list[dict]) -> str:
    """渲染整份种子 SQL：头部免责注释 + 全量 INSERT + 幂等更新子句。

    幂等性来自 ON DUPLICATE KEY UPDATE：主键（id）已存在时改走 UPDATE 分支，
    重复执行不报错也不产生重复行，每个字段都更新为 VALUES 里的新值。
    """
    # 列清单与 tb_shop 表结构对齐；字段顺序决定了 VALUES 的拼装顺序
    columns = "id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours"
    rows = []
    for shop in shops:
        # 每家店拼一个 VALUES 元组：字符串走 sql_string 转义，数值直接转 str
        values = [
            str(shop["id"]), sql_string(shop["name"]), str(shop["type_id"]), sql_string(shop["images"]),
            sql_string(shop["area"]), sql_string(shop["address"]), str(shop["x"]), str(shop["y"]),
            str(shop["avg_price"]), str(shop["sold"]), str(shop["comments"]), str(shop["score"]),
            sql_string(shop["open_hours"]),
        ]
        rows.append("(" + ", ".join(values) + ")")
    # 更新子句：每个非主键列都写回最新值，保证「重跑 = 覆盖」
    updates = [
        "name = VALUES(name)", "type_id = VALUES(type_id)", "images = VALUES(images)",
        "area = VALUES(area)", "address = VALUES(address)", "x = VALUES(x)", "y = VALUES(y)",
        "avg_price = VALUES(avg_price)", "sold = VALUES(sold)", "comments = VALUES(comments)",
        "score = VALUES(score)", "open_hours = VALUES(open_hours)",
    ]
    # 组装完整 SQL 文本：四行免责注释在前面，导入的人一眼能看到数据边界
    return "\n".join([
        # 以下四行注释随 SQL 一起入库到脚本文件，声明数据口径
        "-- 杭州真实门店演示数据；名称、地址和分类已于 2026-09-19 联网核对。",
        "-- 坐标用于地图展示；部分坐标由公开地址定位，可能存在偏差。",
        "-- avg_price、sold、comments、score 为界面演示字段，不代表第三方平台实时数据。",
        "-- 图片使用项目已有分类图标作为示意图。脚本可重复执行。",
        "SET NAMES utf8mb4;",
        "",
        f"INSERT INTO tb_shop ({columns}) VALUES",
        ",\n".join(rows),
        "ON DUPLICATE KEY UPDATE",
        "    " + ",\n    ".join(updates) + ";",
        "",
    ])


def render_markdown(shop: dict) -> str:
    """渲染单店 Markdown：RAG 检索语料的统一模板。

    章节结构固定为：标题（店名）→ 店铺信息（商圈/地址/营业时间/坐标）→
    店铺特色（核对到的介绍）→ 核对来源（外链 + 日期）→ 数据说明（免责声明）。
    结构稳定很重要：检索切片按标题切分，模板乱改会破坏切片质量。
    """
    # 分类 ID 翻译成中文名，写进标题下方的引言行
    type_name = TYPE_NAMES[shop["type_id"]]
    return f"""# {shop['name']}

> 店铺 ID：{shop['id']}；分类：{type_name}；地点：杭州。

## 店铺信息

- 商圈：{shop['area']}
- 地址：{shop['address']}
- 营业时间：{shop['open_hours']}
- 地图坐标（GCJ-02）：{shop['x']}, {shop['y']}

## 店铺特色

{shop['features']}

## 核对来源

- 来源：[{shop['source_title']}]({shop['source_url']})
- 核对日期：{shop['verified_on']}

## 数据说明

- 店名、分类和地址用于本地教学项目，来源见上方链接；门店营业状态和营业时间请以到店前查询结果为准。
- 地图坐标用于位置展示；部分坐标由公开地址定位，可能存在偏差。
- 人均价格、评分、销量和评论数属于界面演示字段，不代表大众点评、高德地图或其他平台的实时数据。
- 图片为项目已有的分类示意图，不是该门店实景照片。
"""


def main() -> None:
    """读 JSON → 写 SQL 种子 → 逐店写 Markdown，两份产物一次生成。"""
    # 唯一事实来源：所有产物都从这份 JSON 派生，保证口径一致
    shops = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    # 目录不存在则创建（首次运行或目录被清理后也能直接跑）
    SQL_PATH.parent.mkdir(parents=True, exist_ok=True)
    SQL_PATH.write_text(render_sql(shops), encoding="utf-8")
    MARKDOWN_DIR.mkdir(parents=True, exist_ok=True)
    # 每店一份 Markdown，文件名 shop-<id>.md 是检索与前端寻址的约定
    for shop in shops:
        (MARKDOWN_DIR / f"shop-{shop['id']}.md").write_text(render_markdown(shop), encoding="utf-8")
    print(f"generated {len(shops)} shops: {SQL_PATH} and {MARKDOWN_DIR}")


# 独立运行入口：重复执行安全（产物整体覆盖，SQL 幂等）
if __name__ == "__main__":
    main()
