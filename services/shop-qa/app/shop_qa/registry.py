"""Durable metadata for uploaded knowledge documents."""

import sqlite3
from contextlib import contextmanager
from pathlib import Path


class DocumentRegistry:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as connection:
            connection.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT PRIMARY KEY, filename TEXT NOT NULL, shop_id INTEGER NOT NULL,
                    owner_id INTEGER NOT NULL, status TEXT NOT NULL,
                    chunk_count INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """)
            connection.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,
                    shop_id INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """)

    @contextmanager
    def _connect(self):
        connection = sqlite3.connect(self.path, timeout=30)
        connection.row_factory = sqlite3.Row
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def create(self, document_id: str, filename: str, shop_id: int, owner_id: int) -> None:
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO documents(id, filename, shop_id, owner_id, status) VALUES (?, ?, ?, ?, 'pending')",
                (document_id, filename, shop_id, owner_id),
            )

    def set_status(self, document_id: str, status: str, chunk_count: int = 0, error: str = "") -> None:
        with self._connect() as connection:
            connection.execute(
                "UPDATE documents SET status = ?, chunk_count = ?, error = ? WHERE id = ?",
                (status, chunk_count, error[:500], document_id),
            )

    def get(self, document_id: str, owner_id: int | None = None) -> dict | None:
        sql = "SELECT * FROM documents WHERE id = ?"
        args = [document_id]
        if owner_id is not None:
            sql += " AND owner_id = ?"
            args.append(owner_id)
        with self._connect() as connection:
            row = connection.execute(sql, args).fetchone()
        return dict(row) if row else None

    def list_documents(self, owner_id: int | None = None) -> list[dict]:
        sql = "SELECT * FROM documents"
        args = []
        if owner_id is not None:
            sql += " WHERE owner_id = ?"
            args.append(owner_id)
        sql += " ORDER BY created_at DESC, id DESC"
        with self._connect() as connection:
            return [dict(row) for row in connection.execute(sql, args)]

    def delete(self, document_id: str, owner_id: int | None = None) -> bool:
        sql = "DELETE FROM documents WHERE id = ?"
        args = [document_id]
        if owner_id is not None:
            sql += " AND owner_id = ?"
            args.append(owner_id)
        with self._connect() as connection:
            return connection.execute(sql, args).rowcount > 0

    def save_message(self, session_id: str, shop_id: int, role: str, text: str) -> None:
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO chat_messages(session_id, shop_id, role, text) VALUES (?, ?, ?, ?)",
                (session_id, shop_id, role, text),
            )

    def get_history(self, session_id: str, shop_id: int, limit: int = 12) -> list[dict]:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT role, text, created_at FROM chat_messages WHERE session_id = ? AND shop_id = ? "
                "ORDER BY id DESC LIMIT ?", (session_id, shop_id, limit)
            ).fetchall()
        return [dict(row) for row in reversed(rows)]

    def clear_history(self, session_id: str, shop_id: int) -> int:
        with self._connect() as connection:
            return connection.execute(
                "DELETE FROM chat_messages WHERE session_id = ? AND shop_id = ?", (session_id, shop_id)
            ).rowcount
