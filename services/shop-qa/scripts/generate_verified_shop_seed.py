"""Generate idempotent MySQL seed SQL and RAG Markdown from verified shop data."""

from __future__ import annotations

import json
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
DATA_PATH = REPO_ROOT / "services/shop-qa/data/verified_hangzhou_shops.json"
SQL_PATH = REPO_ROOT / "src/main/resources/db/shop_seed_verified_2026.sql"
MARKDOWN_DIR = REPO_ROOT / "services/shop-qa/data/shop-markdown"
TYPE_NAMES = {
    1: "美食", 2: "KTV", 3: "丽人·美发", 4: "健身运动", 5: "按摩·足疗",
    6: "美容SPA", 7: "亲子游乐", 8: "酒吧", 9: "轰趴馆", 10: "美睫·美甲",
}


def sql_string(value: object) -> str:
    text = str(value).replace("\\", "\\\\").replace("'", "''").replace("\n", "\\n")
    return f"'{text}'"


def render_sql(shops: list[dict]) -> str:
    columns = "id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours"
    rows = []
    for shop in shops:
        values = [
            str(shop["id"]), sql_string(shop["name"]), str(shop["type_id"]), sql_string(shop["images"]),
            sql_string(shop["area"]), sql_string(shop["address"]), str(shop["x"]), str(shop["y"]),
            str(shop["avg_price"]), str(shop["sold"]), str(shop["comments"]), str(shop["score"]),
            sql_string(shop["open_hours"]),
        ]
        rows.append("(" + ", ".join(values) + ")")
    updates = [
        "name = VALUES(name)", "type_id = VALUES(type_id)", "images = VALUES(images)",
        "area = VALUES(area)", "address = VALUES(address)", "x = VALUES(x)", "y = VALUES(y)",
        "avg_price = VALUES(avg_price)", "sold = VALUES(sold)", "comments = VALUES(comments)",
        "score = VALUES(score)", "open_hours = VALUES(open_hours)",
    ]
    return "\n".join([
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
    shops = json.loads(DATA_PATH.read_text(encoding="utf-8"))
    SQL_PATH.parent.mkdir(parents=True, exist_ok=True)
    SQL_PATH.write_text(render_sql(shops), encoding="utf-8")
    MARKDOWN_DIR.mkdir(parents=True, exist_ok=True)
    for shop in shops:
        (MARKDOWN_DIR / f"shop-{shop['id']}.md").write_text(render_markdown(shop), encoding="utf-8")
    print(f"generated {len(shops)} shops: {SQL_PATH} and {MARKDOWN_DIR}")


if __name__ == "__main__":
    main()
