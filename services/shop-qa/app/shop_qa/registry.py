"""Durable metadata for uploaded knowledge documents."""
# 模块职责：文档状态注册表——「上传 202 异步状态机」与「会话历史」的持久化层。
# 用 SQLite（无外部依赖）落两张表：
#   documents：文档元数据与状态（pending → processing → completed/failed），
#     含 chunk_count（入库分段数）与 error（失败原因，截断 500 字符）；
#   chat_messages：问答会话历史，按 (session_id, shop_id) 归档，支持清空。
# 所有操作走 _connect 上下文管理器：每次短连接、事务自动提交、异常自动回滚。
# 本文件无任何网络/向量操作，是最底层的存储适配器，可独立单测。

import sqlite3
from contextlib import contextmanager
from pathlib import Path
# sqlite3：标准库嵌入式数据库，免部署；contextmanager：把连接管理包装成 with 语法。


class DocumentRegistry:
    """文档元数据与会话历史的 SQLite 注册表（单实例进程内共享）。

    线程安全性：SQLite 连接按操作短开短关（30s 超时），避免长连接锁竞争；
    状态机由上层（document_pipeline）推进，本类只提供原子 CRUD。
    表结构变更需同时兼容旧文件：建表语句全部使用 IF NOT EXISTS。
    """

    def __init__(self, path: Path):
        """初始化：确保父目录存在并创建（幂等）两张表。

        path 通常为 data/documents.sqlite3，由 api.py 的 registry() 单例传入；
        构造完成即建表，调用方拿到实例后无需再做初始化检查。
        """
        self.path = Path(path)
        # 父目录不存在则创建（parents=True 处理多级），保证首次启动可用。
        # exist_ok=True：并发初始化时目录已存在也不报错。
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # 建表用 IF NOT EXISTS：重启幂等，已有数据不受影响。
        with self._connect() as connection:
            # 文档表：id 为 32 位 UUID 主键；status 承载状态机四态；
            # chunk_count 记录成功入库的分段数；error 存失败原因（截断 500 字符）。
            connection.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT PRIMARY KEY, filename TEXT NOT NULL, shop_id INTEGER NOT NULL,
                    owner_id INTEGER NOT NULL, status TEXT NOT NULL,
                    chunk_count INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """)
            # 会话历史表：自增主键保证插入时序，可按 id 排序回放对话。
            connection.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,
                    shop_id INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """)

    @contextmanager
    def _connect(self):
        """短连接上下文管理器：with 语句内自动提交/回滚，退出必关连接。

        timeout=30：写锁冲突时最多等待 30 秒（异步解析与 API 并发写入场景）；
        row_factory=Row：查询结果可按列名取值，并可用 dict(row) 转字典。
        短连接换来的是简单可靠：单机部署下性能足够。
        """
        connection = sqlite3.connect(self.path, timeout=30)
        connection.row_factory = sqlite3.Row
        try:
            # with connection：正常退出提交，异常自动回滚。
            # yield 之后调用方执行完操作，从这里继续收尾。
            with connection:
                yield connection
        finally:
            # 无论成败都关闭连接，防止文件句柄泄漏。
            connection.close()

    def create(self, document_id: str, filename: str, shop_id: int, owner_id: int) -> None:
        """登记新文档，初始状态固定为 pending（状态机起点）。

        主键冲突（同 UUID 重复登记）会抛 IntegrityError，属系统级异常而非业务失败。
        """
        with self._connect() as connection:
            # 状态只写 pending，chunk_count/error 走表默认值。
            connection.execute(
                "INSERT INTO documents(id, filename, shop_id, owner_id, status) VALUES (?, ?, ?, ?, 'pending')",
                (document_id, filename, shop_id, owner_id),
            )

    def set_status(self, document_id: str, status: str, chunk_count: int = 0, error: str = "") -> None:
        """推进文档状态：processing/completed/failed 三种迁移都走这里。

        error 截断 500 字符：防止异常堆栈撑爆 SQLite 行；
        completed 时 chunk_count 为入库分段数，其余迁移保持默认 0。
        """
        with self._connect() as connection:
            # 三字段一次 UPDATE，保持状态与统计的一致性。
            connection.execute(
                "UPDATE documents SET status = ?, chunk_count = ?, error = ? WHERE id = ?",
                (status, chunk_count, error[:500], document_id),
            )

    def get(self, document_id: str, owner_id: int | None = None) -> dict | None:
        """按 ID 查文档；提供 owner_id 时附加归属过滤（越权防护）。

        未命中返回 None，调用方（api 层）转 404；
        owner_id=None 的形式仅供后台任务内部使用，不对外暴露。
        """
        sql = "SELECT * FROM documents WHERE id = ?"
        args = [document_id]
        # owner_id 过滤是可选的：后台任务内部查询不传，API 查询必传。
        if owner_id is not None:
            sql += " AND owner_id = ?"
            args.append(owner_id)
        with self._connect() as connection:
            row = connection.execute(sql, args).fetchone()
        # Row 转 dict 便于 JSON 序列化；无记录返回 None。
        return dict(row) if row else None

    def list_documents(self, owner_id: int | None = None) -> list[dict]:
        """列举文档：可按 owner_id 过滤，按创建时间倒序（新文档在前）。

        owner_id=None 返回全量（管理用途）；正常 API 只按归属人过滤。
        """
        sql = "SELECT * FROM documents"
        args = []
        if owner_id is not None:
            sql += " WHERE owner_id = ?"
            args.append(owner_id)
        # created_at 同秒时再按 id 倒序，保证排序稳定。
        sql += " ORDER BY created_at DESC, id DESC"
        with self._connect() as connection:
            return [dict(row) for row in connection.execute(sql, args)]

    def delete(self, document_id: str, owner_id: int | None = None) -> bool:
        """删除文档记录，返回是否真的删了（rowcount 判定）。

        owner_id 过滤保证只能删自己的文档；rowcount==0 时调用方按 404 处理；
        只删元数据，向量与磁盘文件由 document_pipeline.remove_document 清理。
        """
        sql = "DELETE FROM documents WHERE id = ?"
        args = [document_id]
        if owner_id is not None:
            sql += " AND owner_id = ?"
            args.append(owner_id)
        with self._connect() as connection:
            # rowcount 是实际删除行数：0 表示记录不存在或归属不符。
            return connection.execute(sql, args).rowcount > 0

    def save_message(self, session_id: str, shop_id: int, role: str, text: str) -> None:
        """追加一条会话消息（role 为 user 或 assistant）。

        shop_id=0 表示全局会话；无去重逻辑，同一问题重复询问会各自成对记录。
        """
        with self._connect() as connection:
            # 每次问答写两条（问与答），时序由自增 id 保证。
            connection.execute(
                "INSERT INTO chat_messages(session_id, shop_id, role, text) VALUES (?, ?, ?, ?)",
                (session_id, shop_id, role, text),
            )

    def get_history(self, session_id: str, shop_id: int, limit: int = 12) -> list[dict]:
        """读取会话历史：取最近 limit 条并反转为正序（时间递增）。

        供 _prepare 拼 Prompt：LIMIT 倒序取最新的 N 条，reversed 恢复时序，
        保证多轮对话按真实先后顺序进入上下文。
        shop_id 条件让全局会话与店铺会话历史互相隔离。
        """
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT role, text, created_at FROM chat_messages WHERE session_id = ? AND shop_id = ? "
                "ORDER BY id DESC LIMIT ?", (session_id, shop_id, limit)
            ).fetchall()
        # DESC 取出的是倒序，反转为正序返回。
        return [dict(row) for row in reversed(rows)]

    def clear_history(self, session_id: str, shop_id: int) -> int:
        """清空指定会话（含店铺维度）的历史，返回删除条数。

        返回值供前端确认清空结果（0 表示本就没有历史）。
        """
        with self._connect() as connection:
            # session_id + shop_id 双条件：全局会话与店铺会话互不干扰。
            return connection.execute(
                "DELETE FROM chat_messages WHERE session_id = ? AND shop_id = ?", (session_id, shop_id)
            ).rowcount
