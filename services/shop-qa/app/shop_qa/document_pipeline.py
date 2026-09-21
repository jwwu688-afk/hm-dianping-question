"""Reuse zgws parsing and heading-aware splitting for uploaded documents."""

import re
import shutil
import uuid
from pathlib import Path

from .index import index_source, delete_source
from .registry import DocumentRegistry

DATA_ROOT = Path(__file__).resolve().parents[2] / "data"
MAX_UPLOAD_BYTES = 20 * 1024 * 1024


def validate_upload(filename: str, payload: bytes, max_bytes: int = MAX_UPLOAD_BYTES) -> str:
    if not filename or filename != Path(filename).name or "\\" in filename or "/" in filename:
        raise ValueError("invalid filename")
    suffix = Path(filename).suffix.lower()
    if suffix not in {".pdf", ".md"}:
        raise ValueError("only PDF and Markdown files are supported")
    if not payload or len(payload) > max_bytes:
        raise ValueError("file is empty or exceeds upload limit")
    return suffix


def normalize_chunks(document_id: str, shop_id: int, filename: str, chunks: list[dict]) -> list[dict]:
    documents = []
    for part, chunk in enumerate(chunks):
        content = str(chunk.get("content") or "").strip()
        if not content:
            continue
        image_urls = re.findall(r"!\[[^]]*\]\((https?://[^)]+)\)", content)
        documents.append({
            "shop_id": shop_id, "source_type": "document", "source_id": document_id,
            "file_title": filename, "title": str(chunk.get("title") or filename),
            "part": part, "content": content, "image_urls": image_urls,
        })
    return documents


def save_upload(registry: DocumentRegistry, filename: str, payload: bytes, shop_id: int, owner_id: int) -> tuple[str, Path]:
    suffix = validate_upload(filename, payload)
    if shop_id < 0 or owner_id <= 0:
        raise ValueError("invalid shop or owner ID")
    document_id = uuid.uuid4().hex
    directory = DATA_ROOT / "uploads" / document_id
    directory.mkdir(parents=True, exist_ok=False)
    path = directory / f"source{suffix}"
    path.write_bytes(payload)
    registry.create(document_id, filename, shop_id, owner_id)
    return document_id, path


def process_document(registry: DocumentRegistry, document_id: str, path: Path) -> None:
    from app.import_process.agent.state import get_default_state
    from app.import_process.agent.nodes.node_entry import node_entry
    from app.import_process.agent.nodes.node_document_split import node_document_split

    record = registry.get(document_id)
    if record is None:
        return
    registry.set_status(document_id, "processing")
    try:
        state = get_default_state()
        state.update(task_id=document_id, local_file_path=str(path), local_dir=str(path.parent))
        state = node_entry(state)
        if path.suffix.lower() == ".pdf":
            from app.import_process.agent.nodes.node_pdf_to_md import node_pdf_to_md
            state = node_pdf_to_md(state)
        if not state.get("md_path"):
            raise RuntimeError("document conversion produced no Markdown")
        markdown = Path(state["md_path"])
        if (markdown.parent / "images").exists():
            from app.import_process.agent.nodes.node_md_img import node_md_img
            state = node_md_img(state)
        else:
            state["md_content"] = markdown.read_text(encoding="utf-8")
        state = node_document_split(state)
        documents = normalize_chunks(document_id, record["shop_id"], record["filename"], state.get("chunks") or [])
        if not documents:
            raise RuntimeError("document parsing produced no searchable text")
        count = index_source(f"document:{document_id}", documents)
        registry.set_status(document_id, "completed", chunk_count=count)
    except Exception as exc:
        registry.set_status(document_id, "failed", error=str(exc))
        raise


def remove_document(registry: DocumentRegistry, document_id: str, owner_id: int) -> bool:
    if not re.fullmatch(r"[0-9a-f]{32}", document_id):
        return False
    record = registry.get(document_id, owner_id)
    if record is None:
        return False
    delete_source(f"document:{document_id}")
    uploads_root = (DATA_ROOT / "uploads").resolve()
    directory = (uploads_root / document_id).resolve()
    if directory.parent != uploads_root:
        raise ValueError("invalid upload directory")
    if directory.exists():
        shutil.rmtree(directory)
    registry.delete(document_id, owner_id)
    return True
