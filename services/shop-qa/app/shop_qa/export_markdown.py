"""Export readable Markdown from Black Horse Review shop and blog records."""
# 模块职责：把店铺与探店笔记记录导出为可读 Markdown（每店一个文件）。
# 两种数据来源：--seed-sql 直接解析项目自带的 MySQL 建库脚本（离线可用），
# 或不加参数时通过 sync_mysql.load_records 查询运行中的 MySQL（在线）。
# 导出的 Markdown 是「店铺 Markdown 上传方案」的语料形态之一：
# 结构固定为「# 店名 / 店铺信息 / 探店笔记」，并显式标注信息来源与主观性边界。
# 也可作为独立脚本运行：python -m app.shop_qa.export_markdown --seed-sql <path>。

import argparse
import re
from collections import defaultdict
from pathlib import Path
# re：解析 seed SQL 的行级正则；defaultdict：笔记按店铺分组；Path：跨平台路径。
import re
from collections import defaultdict
from pathlib import Path


def render_shop_markdown(shop: dict, blogs: list[dict]) -> str:
    """把单个店铺及其笔记渲染为 Markdown 文本（纯函数）。

    输出结构：# 店名 → 引用说明 → ## 店铺信息（字段列表）→ ## 探店笔记。
    笔记段落显式标注「用户的主观体验」，与店铺平台记录区分，
    对应问答 Prompt 中「不得把用户笔记当作平台核实事实」的信源约束。
    纯函数无副作用，可直接单测生成的 Markdown 文本。
    """
    # 店铺 ID 作为文件名与引用锚点，转 int 保证合法。
    shop_id = int(shop["id"])
    # 店名清洗：换行压成空格 + 去首尾空白，避免标题结构被破坏。
    # 店名缺失时用「店铺资料」占位，保证文件头始终存在。
    name = str(shop.get("name") or "店铺资料").replace("\n", " ").strip()
    # 头部三行：一级标题、来源声明（tb_shop/tb_blog 表名）、店铺信息小节标题。
    # 空字符串元素是 Markdown 的空行，控制段落间距。
    # 引用行（>）让阅读者一眼看清数据边界，也进入 RAG 语料供模型参考。
    lines = [f"# {name}", "", f"> 店铺 ID：{shop_id}；店铺信息来自 tb_shop，探店笔记来自 tb_blog。", "", "## 店铺信息", ""]
    # 关键字段按固定顺序展示（标签, 键名），缺失字段自动跳过。
    # 评分满分 50 是黑马点评项目的既有约定，保持口径一致。
    fields = [
        ("商圈", "area"), ("地址", "address"), ("人均价格（元）", "avg_price"),
        ("评分（满分50）", "score"), ("营业时间", "open_hours"),
    ]
    for label, key in fields:
        # 字段值为 None 或纯空白则不输出该行，保持文档紧凑。
        value = shop.get(key)
        if value is not None and str(value).strip():
            lines.append(f"- {label}：{value}")
    # 只保留属于本店且有正文的笔记：shop_id 匹配 + content 非空。
    # shop_id 缺失按 0 处理，不会误匹配到任何真实店铺。
    relevant = [b for b in blogs if int(b.get("shop_id") or 0) == shop_id and str(b.get("content") or "").strip()]
    lines.extend(["", "## 探店笔记", ""])
    # 无笔记时输出占位说明，避免文档出现空小节。
    # 占位文案本身也是语料：模型据此回答「该店暂无笔记」。
    if not relevant:
        lines.extend(["暂无探店笔记。", ""])
    for blog in relevant:
        # 每篇笔记一个三级标题（含笔记 ID 便于溯源），内容前声明主观性。
        # 标题同样做换行清洗，防止笔记标题破坏 Markdown 层级。
        title = str(blog.get("title") or "探店笔记").replace("\n", " ").strip()
        # 四行一组：标题、空行、主观性声明、空行 + 正文，最后补空行分隔下一篇。
        lines.extend([f"### {title}（笔记 ID：{blog['id']}）", "", "> 以下为用户的主观体验。", "", str(blog["content"]).strip(), ""])
    # 结尾统一收成单换行：rstrip 去尾部空白后补一个换行符。
    # 行间以 \n 拼接（非 \r\n），跨平台 diff 与切分器更友好。
    return "\n".join(lines).rstrip() + "\n"


def write_shop_markdown(shops: list[dict], blogs: list[dict], output_dir: Path) -> list[Path]:
    """批量导出：每店一个 shop-<id>.md 文件，按 shop_id 分组笔记。

    返回写入的文件路径列表；目录不存在时自动创建。
    与在线/离线数据源解耦：只要求 shops/blogs 的字段结构一致。
    """
    output_dir = Path(output_dir)
    # 输出目录不存在则创建（parents=True 支持多级路径）。
    output_dir.mkdir(parents=True, exist_ok=True)
    # 笔记按店铺分组：defaultdict 避免重复初始化列表的样板代码。
    # 没有笔记的店铺在 render 中会输出「暂无探店笔记」占位。
    by_shop = defaultdict(list)
    for blog in blogs:
        # shop_id 强转 int：seed 解析与 MySQL DictCursor 都可能是数字形态。
        by_shop[int(blog["shop_id"])].append(blog)
    paths = []
    for shop in shops:
        # 逐店渲染落盘；by_shop[shop_id] 缺键会抛 KeyError，
        # 但 defaultdict 已保证每个出现过的 shop_id 都有组。
        shop_id = int(shop["id"])
        # 非法店铺 ID（0/负数）会污染文件名，直接报错而非跳过。
        # 报错比静默跳过安全：调用方能立刻发现数据质量问题。
        if shop_id <= 0:
            raise ValueError("invalid shop ID")
        path = output_dir / f"shop-{shop_id}.md"
        # 写入 UTF-8：Windows 下显式指定编码，避免默认 GBK 乱码。
        # 每店独立成文件，上传方案可直接按文件粒度入库。
        path.write_text(render_shop_markdown(shop, by_shop[shop_id]), encoding="utf-8")
        paths.append(path)
    return paths


def _sql_values(row: str) -> list:
    """Read one VALUES tuple from the bundled MySQL seed script.
    解析 seed SQL 中一条 INSERT 的 VALUES 元组（手写迷你 SQL 值解析器）。

    正确处理转义序列（\n \r \t \0 与 \'）、单引号转义（''）、
    引号内的逗号（不切分）与 NULL 字面量；引号未闭合抛 ValueError。
    项目 seed 数据是逐行 INSERT，因此按行解析即可，无需完整 SQL 词法。
    返回值统一为字符串列表（NULL → None），调用方按列序取值。
    """
    values, token, quoted = [], [], False
    index = 0
    # 逐字符状态机扫描：quoted 标记当前是否处于单引号字符串内部。
    # token 是当前字段的字符缓冲，逗号或行尾时收束成一个值。
    # 时间复杂度 O(n)，行级 INSERT 长度有限，无需正则优化。
    while index < len(row):
        char = row[index]
        # 引号内的反斜杠转义：查映射表还原常见控制字符，未知的原样保留。
        # 只在 quoted 状态处理：字段值外的反斜杠（如路径）不需要转义。
        if quoted and char == "\\" and index + 1 < len(row):
            index += 1
            token.append({"n": "\n", "r": "\r", "t": "\t", "0": "\0"}.get(row[index], row[index]))
        elif char == "'":
            # SQL 的双单引号转义（''）：在引号内遇到 '' 输出一个字面引号。
            # index+1 越界保护：行尾悬挂引号会落到 quoted 翻转分支，由收尾校验兜底。
            if quoted and index + 1 < len(row) and row[index + 1] == "'":
                token.append("'")
                index += 1
            else:
                # 其余单引号是字符串边界的开/闭切换。
                quoted = not quoted
        elif char == "," and not quoted:
            # 引号外的逗号是字段分隔符：当前 token 收束为一个值。
            # strip 掉 token 前后的空格（SQL 风格的 ", " 分隔）。
            values.append("".join(token).strip())
            token = []
        else:
            token.append(char)
        index += 1
    # 扫描结束仍在引号内说明 SQL 行损坏，拒绝输出半截数据。
    if quoted:
        raise ValueError("unterminated SQL string")
    # 最后一个 token 是行尾字段（无后续逗号），在此收束。
    values.append("".join(token).strip())
    # NULL 字面量统一转 None，语义上等价于「字段缺失」。
    # 下游 render/markdown 对 None 的处理与在线查询结果一致。
    return [None if value == "NULL" else value for value in values]


def records_from_seed(sql_path: Path) -> tuple[list[dict], list[dict]]:
    """从 seed SQL 文件提取店铺与笔记记录（离线导出用，不连数据库）。

    用 utf-8-sig 读取以兼容带 BOM 的导出文件；
    只匹配 tb_shop/tb_blog 两类 INSERT，其他语句忽略；
    字段下标对应 seed 脚本的建表列序， shops 缺店铺直接报错。
    返回 (shops, blogs) 两个 dict 列表，结构与在线查询的结果字段一致。
    """
    shops, blogs = [], []
    # 行级正则：只吃「INSERT INTO 表 VALUES (...);」整行，其余 DDL/注释自然跳过。
    # 命名分组 table/row 让后续分支更易读。
    pattern = re.compile(r"^INSERT INTO `(?P<table>tb_shop|tb_blog)` VALUES \((?P<row>.*)\);$")
    for line in Path(sql_path).read_text(encoding="utf-8-sig").splitlines():
        match = pattern.match(line)
        if not match:
            # 非 INSERT 行（建表、注释、其他表）直接忽略。
            continue
        # 拆出该行的值列表（已处理转义与 NULL）。
        values = _sql_values(match.group("row"))
        # tb_shop 列序：id, name, ..., area(4), address(5), ..., avg_price(8), score(11), open_hours(12)。
        # 下标来自 seed 脚本的建表列序，改表结构需同步这里。
        if match.group("table") == "tb_shop":
            shops.append(dict(id=int(values[0]), name=values[1], area=values[4], address=values[5],
                              avg_price=values[8], score=values[11], open_hours=values[12]))
        else:
            # tb_blog 列序：id(0), shop_id(1), ..., title(3), ..., content(5)。
            blogs.append(dict(id=int(values[0]), shop_id=int(values[1]), title=values[3], content=values[5]))
    # 没解析到任何店铺说明文件不对（空文件/格式变更），快速失败。
    # 笔记可以为空（纯店铺资料导出也合法）。
    if not shops:
        raise ValueError("seed SQL contains no shops")
    return shops, blogs


def main() -> None:
    """命令行入口：--seed-sql 离线解析 seed 脚本，否则连在线 MySQL。

    输出目录默认 data/shop-markdown；导出完成打印文件数与绝对路径。
    """
    parser = argparse.ArgumentParser(description="Export one Markdown file per shop")
    parser.add_argument("--seed-sql", type=Path, help="Use the bundled SQL seed instead of a running MySQL database")
    # 默认输出到 data/shop-markdown，与 index 索引的数据目录平级。
    parser.add_argument("--output", type=Path, default=Path("data/shop-markdown"))
    args = parser.parse_args()
    # 数据来源二选一：给了 seed 文件走离线解析，否则在线查库。
    # 离线路径不依赖 MySQL 服务，适合初始化演示数据或 CI 环境。
    if args.seed_sql:
        # seed 路径透传给解析器；文件不存在由 read_text 自然抛错。
        shops, blogs = records_from_seed(args.seed_sql)
    else:
        # 在线模式：load_records 内部读环境变量建 MySQL 连接。
        from .sync_mysql import load_records
        shops, blogs = load_records()
    # 统一走 write_shop_markdown 落盘，最后打印汇总信息。
    # 延迟导入 sync_mysql：避免离线模式也加载 pymysql。
    paths = write_shop_markdown(shops, blogs, args.output)
    # resolve 打印绝对路径：无论从哪个目录启动，输出位置都清晰可查。
    print(f"Exported {len(paths)} Markdown files to {args.output.resolve()}")


if __name__ == "__main__":
    main()
