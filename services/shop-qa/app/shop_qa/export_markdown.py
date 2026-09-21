"""Export readable Markdown from Black Horse Review shop and blog records."""

import argparse
import re
from collections import defaultdict
from pathlib import Path


def render_shop_markdown(shop: dict, blogs: list[dict]) -> str:
    shop_id = int(shop["id"])
    name = str(shop.get("name") or "店铺资料").replace("\n", " ").strip()
    lines = [f"# {name}", "", f"> 店铺 ID：{shop_id}；店铺信息来自 tb_shop，探店笔记来自 tb_blog。", "", "## 店铺信息", ""]
    fields = [
        ("商圈", "area"), ("地址", "address"), ("人均价格（元）", "avg_price"),
        ("评分（满分50）", "score"), ("营业时间", "open_hours"),
    ]
    for label, key in fields:
        value = shop.get(key)
        if value is not None and str(value).strip():
            lines.append(f"- {label}：{value}")
    relevant = [b for b in blogs if int(b.get("shop_id") or 0) == shop_id and str(b.get("content") or "").strip()]
    lines.extend(["", "## 探店笔记", ""])
    if not relevant:
        lines.extend(["暂无探店笔记。", ""])
    for blog in relevant:
        title = str(blog.get("title") or "探店笔记").replace("\n", " ").strip()
        lines.extend([f"### {title}（笔记 ID：{blog['id']}）", "", "> 以下为用户的主观体验。", "", str(blog["content"]).strip(), ""])
    return "\n".join(lines).rstrip() + "\n"


def write_shop_markdown(shops: list[dict], blogs: list[dict], output_dir: Path) -> list[Path]:
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    by_shop = defaultdict(list)
    for blog in blogs:
        by_shop[int(blog["shop_id"])].append(blog)
    paths = []
    for shop in shops:
        shop_id = int(shop["id"])
        if shop_id <= 0:
            raise ValueError("invalid shop ID")
        path = output_dir / f"shop-{shop_id}.md"
        path.write_text(render_shop_markdown(shop, by_shop[shop_id]), encoding="utf-8")
        paths.append(path)
    return paths


def _sql_values(row: str) -> list:
    """Read one VALUES tuple from the bundled MySQL seed script."""
    values, token, quoted = [], [], False
    index = 0
    while index < len(row):
        char = row[index]
        if quoted and char == "\\" and index + 1 < len(row):
            index += 1
            token.append({"n": "\n", "r": "\r", "t": "\t", "0": "\0"}.get(row[index], row[index]))
        elif char == "'":
            if quoted and index + 1 < len(row) and row[index + 1] == "'":
                token.append("'")
                index += 1
            else:
                quoted = not quoted
        elif char == "," and not quoted:
            values.append("".join(token).strip())
            token = []
        else:
            token.append(char)
        index += 1
    if quoted:
        raise ValueError("unterminated SQL string")
    values.append("".join(token).strip())
    return [None if value == "NULL" else value for value in values]


def records_from_seed(sql_path: Path) -> tuple[list[dict], list[dict]]:
    shops, blogs = [], []
    pattern = re.compile(r"^INSERT INTO `(?P<table>tb_shop|tb_blog)` VALUES \((?P<row>.*)\);$")
    for line in Path(sql_path).read_text(encoding="utf-8-sig").splitlines():
        match = pattern.match(line)
        if not match:
            continue
        values = _sql_values(match.group("row"))
        if match.group("table") == "tb_shop":
            shops.append(dict(id=int(values[0]), name=values[1], area=values[4], address=values[5],
                              avg_price=values[8], score=values[11], open_hours=values[12]))
        else:
            blogs.append(dict(id=int(values[0]), shop_id=int(values[1]), title=values[3], content=values[5]))
    if not shops:
        raise ValueError("seed SQL contains no shops")
    return shops, blogs


def main() -> None:
    parser = argparse.ArgumentParser(description="Export one Markdown file per shop")
    parser.add_argument("--seed-sql", type=Path, help="Use the bundled SQL seed instead of a running MySQL database")
    parser.add_argument("--output", type=Path, default=Path("data/shop-markdown"))
    args = parser.parse_args()
    if args.seed_sql:
        shops, blogs = records_from_seed(args.seed_sql)
    else:
        from .sync_mysql import load_records
        shops, blogs = load_records()
    paths = write_shop_markdown(shops, blogs, args.output)
    print(f"Exported {len(paths)} Markdown files to {args.output.resolve()}")


if __name__ == "__main__":
    main()
