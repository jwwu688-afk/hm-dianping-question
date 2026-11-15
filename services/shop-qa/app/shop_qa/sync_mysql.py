"""Sync MySQL shops and blogs to Milvus and export readable Markdown."""
# 模块职责：在线数据同步入口——从运行中的 MySQL（hmdp 库）拉取店铺与探店笔记，
# 做两件事：1) 导出到 data/shop-markdown 的 Markdown 文件；2) 逐店写入 Milvus 索引。
# 与 export_markdown.py 的离线 seed 模式互补：本文件是「连库」的正路子。
# 用法：python -m app.shop_qa.sync_mysql（需先配置 HMDP_DB_* 环境变量或 .env）。

import os
from collections import defaultdict

from dotenv import load_dotenv
# load_dotenv：启动时把 .env 文件注入环境变量，方便本地开发免导出。
from .index import index_shop
# index_shop：单店入库（店铺主文档 + 各笔记独立 source_key）。


def load_records() -> tuple[list[dict], list[dict]]:
    """连接 MySQL 拉取全量店铺与笔记，返回 (shops, blogs) 字典列表。

    连接参数从 HMDP_DB_HOST/PORT/USER/PASSWORD/NAME 环境变量读取，
    均有本地开发默认值（127.0.0.1:3306 root 空密码 hmdp 库）；
    用完即关连接，不做连接池——同步脚本是低频一次性任务。
    密码默认空串是为开箱即用：生产环境必须通过环境变量注入。
    返回结构兼容 export_markdown.write_shop_markdown 与 index_shop。
    """
    import pymysql

    # 延迟导入：模块被 export_markdown 间接引用时不强制加载 pymysql。
    # 先加载 .env，再读变量：保证本地 .env 配置优先生效。
    # 放在函数内调用，避免 import 副作用污染其他进程。
    load_dotenv()
    # DictCursor：查询结果按列名取值，与 seed 解析的 dict 结构保持一致。
    # 五个连接参数全部走环境变量 + 默认值，零配置也能连本地库。
    connection = pymysql.connect(
        host=os.getenv("HMDP_DB_HOST", "127.0.0.1"),
        port=int(os.getenv("HMDP_DB_PORT", "3306")),
        user=os.getenv("HMDP_DB_USER", "root"),
        password=os.getenv("HMDP_DB_PASSWORD", ""),
        database=os.getenv("HMDP_DB_NAME", "hmdp"),
        charset="utf8mb4", cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        # 只取问答需要的列：店铺基础信息 + 笔记标题与正文，不搬运全部字段。
        # 精选列减少网络传输，也让下游 dict 结构稳定可控。
        with connection.cursor() as cursor:
            cursor.execute("SELECT id, name, area, address, avg_price, score, open_hours FROM tb_shop")
            # fetchall 一次性取全量：店铺规模有限（演示项目数百条），无分页必要。
            shops = cursor.fetchall()
            # 笔记只取 id/shop_id/title/content：正文是语料，其余字段问答用不到。
            cursor.execute("SELECT id, shop_id, title, content FROM tb_blog")
            blogs = cursor.fetchall()
    finally:
        # 无论查询成败都关闭连接，防止脚本退出时悬挂连接。
        # 放 finally 是因为查询异常时同样需要释放。
        connection.close()
    return shops, blogs


def main():
    """同步主流程：先导出 Markdown，再逐店索引进 Milvus，最后打印统计。

    三步顺序刻意「先导出后索引」：导出失败说明数据有问题，
    先失败早暴露，避免把脏数据写进向量库。
    """
    # 延迟导入：只有走命令行主流程才需要导出能力。
    from .export_markdown import write_shop_markdown

    # 第一步：拉取在线数据。
    shops, blogs = load_records()
    # 第二步：导出 Markdown（供人工检查语料质量，也是上传方案的素材）。
    paths = write_shop_markdown(shops, blogs, "data/shop-markdown")
    print(f"exported {len(paths)} Markdown files to data/shop-markdown")
    # 笔记按店铺分组：索引与导出都需要店铺维度的笔记集合。
    # DictCursor 返回的 shop_id 是 int，可直接作键。
    by_shop = defaultdict(list)
    for blog in blogs:
        by_shop[blog["shop_id"]].append(blog)
    # 第三步：逐店写入 Milvus；index_shop 按来源拆分，重复运行幂等。
    # 单店失败会中断循环——同步脚本宁可停也不静默跳过。
    total = 0
    for shop in shops:
        total += index_shop(shop, by_shop[shop["id"]])
        print(f"indexed shop {shop['id']}")
    # 汇总：店铺数与文档 chunk 总数。
    print(f"indexed {len(shops)} shops and {total} documents")


if __name__ == "__main__":
    main()
