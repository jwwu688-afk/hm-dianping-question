import tempfile
import unittest
from pathlib import Path

from unittest.mock import patch

from app.shop_qa import document_pipeline
from app.shop_qa.document_pipeline import normalize_chunks, validate_upload
from app.shop_qa.registry import DocumentRegistry


class DocumentPipelineTests(unittest.TestCase):
    def test_chunks_keep_document_and_shop_identity(self):
        chunks = [{"title": "招牌菜", "content": "鱼很嫩", "parent_title": "菜单"}]
        docs = normalize_chunks("abc123", 7, "菜单.md", chunks)
        self.assertEqual(docs[0]["source_type"], "document")
        self.assertEqual(docs[0]["source_id"], "abc123")
        self.assertEqual(docs[0]["shop_id"], 7)
        self.assertEqual(docs[0]["part"], 0)

    def test_rejects_unsupported_or_oversized_upload(self):
        with self.assertRaises(ValueError):
            validate_upload("../other.pdf", b"pdf")
        with self.assertRaises(ValueError):
            validate_upload("notes.txt", b"text")
        with self.assertRaises(ValueError):
            validate_upload("menu.md", b"x" * 11, max_bytes=10)

    def test_registry_persists_status(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "registry.sqlite3"
            registry = DocumentRegistry(path)
            registry.create("abc123", "menu.md", 7, 4)
            registry.set_status("abc123", "completed", chunk_count=2)
            reopened = DocumentRegistry(path)
            self.assertEqual(reopened.get("abc123")["status"], "completed")
            self.assertEqual(reopened.get("abc123")["chunk_count"], 2)
            self.assertEqual(len(reopened.list_documents(owner_id=4)), 1)
            reopened.save_message("session123", 7, "user", "招牌菜是什么？")
            self.assertEqual(reopened.get_history("session123", 7)[0]["text"], "招牌菜是什么？")

    def test_delete_removes_only_owned_document_and_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            registry = DocumentRegistry(root / "db.sqlite3")
            with patch.object(document_pipeline, "DATA_ROOT", root):
                document_id, path = document_pipeline.save_upload(registry, "menu.md", b"# Menu", 7, 4)
                with patch.object(document_pipeline, "delete_source") as delete_source:
                    self.assertFalse(document_pipeline.remove_document(registry, document_id, 5))
                    self.assertTrue(document_pipeline.remove_document(registry, document_id, 4))
                    delete_source.assert_called_once_with(f"document:{document_id}")
            self.assertFalse(path.exists())
            self.assertIsNone(registry.get(document_id))


if __name__ == "__main__":
    unittest.main()
