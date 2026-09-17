"""One-shot full rebuild from the project's MySQL shop and blog tables."""

import os
from collections import defaultdict

from dotenv import load_dotenv

from .index import index_shop


def main():
    import pymysql

    load_dotenv()
    connection = pymysql.connect(
        host=os.getenv("HMDP_DB_HOST", "127.0.0.1"),
        port=int(os.getenv("HMDP_DB_PORT", "3306")),
        user=os.getenv("HMDP_DB_USER", "root"),
        password=os.getenv("HMDP_DB_PASSWORD", ""),
        database=os.getenv("HMDP_DB_NAME", "hmdp"),
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT id, name, area, address, avg_price, score, open_hours FROM tb_shop")
            shops = cursor.fetchall()
            cursor.execute("SELECT id, shop_id, title, content FROM tb_blog")
            blogs = cursor.fetchall()
    finally:
        connection.close()
    by_shop = defaultdict(list)
    for blog in blogs:
        by_shop[blog["shop_id"]].append(blog)
    total = 0
    for shop in shops:
        total += index_shop(shop, by_shop[shop["id"]])
        print(f"indexed shop {shop['id']}")
    print(f"indexed {len(shops)} shops and {total} documents")


if __name__ == "__main__":
    main()
