"""文档处理管线的单元测试：切片归属、上传校验、注册表持久化与删除权限。

被测对象：app/shop_qa/document_pipeline.py 与 app/shop_qa/registry.py。
覆盖四个环节：
1. normalize_chunks：给切片打上文档身份（document 类型 + source_id + shop_id + 分片号）；
2. validate_upload：拒绝不支持的扩展名与超限体积；
3. DocumentRegistry：SQLite 注册表的建档、状态更新、按 owner 列出、会话消息历史；
4. remove_document：只有 owner 本人能删，删除时同步清 Milvus 源数据与磁盘文件。

这些环节共同保证：上传的 Markdown 知识在「索引层有身份、注册表有状态、
删除时有权限」——检索时才能按 owner 与店铺正确过滤、删除后不留脏数据。

用 tempfile.TemporaryDirectory 隔离磁盘，patch DATA_ROOT 隔离上传目录，
patch delete_source 隔离 Milvus 调用（单测不依赖外部服务）。
四个测试与四个环节一一对应，每个断言都标注了它防住的具体风险。
"""
import tempfile
import unittest
from pathlib import Path

from unittest.mock import patch

from app.shop_qa import document_pipeline
from app.shop_qa.document_pipeline import normalize_chunks, validate_upload
from app.shop_qa.registry import DocumentRegistry


class DocumentPipelineTests(unittest.TestCase):
    # 测试点一：切片必须携带完整「文档身份」，供检索时按来源过滤
    # 身份四元组 = (source_type, source_id, shop_id, part)，缺一不可
    # part 从 0 计数：前端展示「第 N 段」与去重合并都依赖它
    def test_chunks_keep_document_and_shop_identity(self):
        # 输入：属于「菜单」章节的一个切片
        # normalize_chunks 只补身份字段，不改动 title/content 本身
        chunks = [{"title": "招牌菜", "content": "鱼很嫩", "parent_title": "菜单"}]
        # 文档 ID=abc123、店铺 ID=7、文件名=菜单.md
        docs = normalize_chunks("abc123", 7, "菜单.md", chunks)
        # 断言：来源类型为 document、文档 ID 与店铺 ID 正确、分片号从 0 开始
        self.assertEqual(docs[0]["source_type"], "document")
        self.assertEqual(docs[0]["source_id"], "abc123")
        self.assertEqual(docs[0]["shop_id"], 7)
        self.assertEqual(docs[0]["part"], 0)

    # 测试点二：上传校验——扩展名与体积双拦截
    # 校验失败统一抛 ValueError，上层接口转成 4xx 返回
    def test_rejects_unsupported_or_oversized_upload(self):
        # 三个用例分别覆盖：路径穿越、扩展名白名单、大小上限
        # 路径穿越（../）+ 非法扩展名 pdf → 拒绝
        with self.assertRaises(ValueError):
            validate_upload("../other.pdf", b"pdf")
        # .txt 不在白名单（只收 Markdown）→ 拒绝
        with self.assertRaises(ValueError):
            validate_upload("notes.txt", b"text")
        # 合法扩展名但超过 max_bytes 限制 → 拒绝
        with self.assertRaises(ValueError):
            validate_upload("menu.md", b"x" * 11, max_bytes=10)

    # 测试点三：SQLite 注册表落盘持久化（重开连接数据仍在）+ 会话消息历史
    # 注册表是「文档台账」：哪个文档、谁传的、处理到哪一步，全在这里
    # 处理是异步的：上传接口先建档（pending），后台切片完成后改状态（completed）
    def test_registry_persists_status(self):
        # 临时目录里的独立 SQLite 文件，测试结束自动清理
        with tempfile.TemporaryDirectory() as directory:
            # 建库 → 建档（4 个切片待处理）→ 更新为完成态
            path = Path(directory) / "registry.sqlite3"
            registry = DocumentRegistry(path)
            registry.create("abc123", "menu.md", 7, 4)
            registry.set_status("abc123", "completed", chunk_count=2)
            # 重新打开同一个库文件，验证状态真的写进了 SQLite 而不是只在内存
            # （这一步是「持久化」断言的关键：换一个实例读同一份文件）
            reopened = DocumentRegistry(path)
            self.assertEqual(reopened.get("abc123")["status"], "completed")
            self.assertEqual(reopened.get("abc123")["chunk_count"], 2)
            # 按 owner_id 列出文档：只能看到自己名下的
            self.assertEqual(len(reopened.list_documents(owner_id=4)), 1)
            # 会话消息：保存一条用户提问并按会话读回（问答历史的落库路径）
            reopened.save_message("session123", 7, "user", "招牌菜是什么？")
            self.assertEqual(reopened.get_history("session123", 7)[0]["text"], "招牌菜是什么？")

    # 测试点四：删除权限与级联清理（Milvus 源数据 + 磁盘文件）
    def test_delete_removes_only_owned_document_and_file(self):
        # 前置：临时目录 + patch DATA_ROOT + patch delete_source，
        # 三重隔离保证单测不依赖真实数据区与 Milvus 服务
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            registry = DocumentRegistry(root / "db.sqlite3")
            # DATA_ROOT patch 到临时目录，上传文件不会污染真实数据区
            with patch.object(document_pipeline, "DATA_ROOT", root):
                # 先真实走一遍 save_upload：产生磁盘文件 + 注册表记录，作为删除的前置状态
                document_id, path = document_pipeline.save_upload(registry, "menu.md", b"# Menu", 7, 4)
                # delete_source patch 成 mock，只验证「删了谁」而不真连 Milvus
                with patch.object(document_pipeline, "delete_source") as delete_source:
                    # 非 owner（5≠4）删除 → 返回 False，什么都没发生
                    # 这是越权防护断言：别人的文档你删不掉
                    self.assertFalse(document_pipeline.remove_document(registry, document_id, 5))
                    # owner 本人删除 → 返回 True，且按 source_key 清了 Milvus 数据
                    # 精确断言删除参数，防止误删其他来源
                    # remove_document 同时负责删 Milvus、文件与注册表记录，三件事缺一不可
                    self.assertTrue(document_pipeline.remove_document(registry, document_id, 4))
                    delete_source.assert_called_once_with(f"document:{document_id}")
            # 级联断言：磁盘文件已删除、注册表记录也已移除
            # 若只删了 Milvus 而没删文件/记录，下一轮检索仍会召回死数据
            # assertIsNone 覆盖「记录被删除」：get 找不到应返回 None 而不是残留半条
            self.assertFalse(path.exists())
            self.assertIsNone(registry.get(document_id))


if __name__ == "__main__":
    unittest.main()
