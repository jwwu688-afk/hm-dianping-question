"""Reuse zgws parsing and heading-aware splitting for uploaded documents."""
# 模块职责：上传文档的处理流水线——「店铺 Markdown 上传方案」的核心落地文件。
# 完整链路：上传校验落盘（save_upload）→ 后台异步解析（process_document：PDF 转 MD →
# 图片上传 → 按标题切分）→ 规范化为检索文档（normalize_chunks）→ 写入 Milvus 索引。
# 同时负责删除时的资源清理（remove_document：向量 + 磁盘 + 注册表）。
# 复用 zgws 工程已有的解析/切分节点（import_process 包），保证线上资料与上传文档口径一致。

import re
import shutil
import uuid
from pathlib import Path

from .index import index_source, delete_source
# index_source/delete_source 是 Milvus 索引层的读写入口，本模块只做编排不直接操作 Milvus。
from .registry import DocumentRegistry
# 注册表承载文档状态机（pending/processing/completed/failed）的持久化。

# 数据根目录：services/shop-qa/data，上传文件与 SQLite 元数据都在其下。
# parents[2] 是 shop-qa 服务目录，向上两级定位到 data，与启动工作目录无关。
DATA_ROOT = Path(__file__).resolve().parents[2] / "data"
# 单文件上传上限 20MB：兼顾大 PDF 与内存占用（上传时整体读入内存校验）。
MAX_UPLOAD_BYTES = 20 * 1024 * 1024


def validate_upload(filename: str, payload: bytes, max_bytes: int = MAX_UPLOAD_BYTES) -> str:
    """上传前的三重校验：文件名合法性 → 扩展名白名单 → 内容非空且不超限。

    返回小写扩展名供落盘命名使用；任何一项不通过抛 ValueError（api 层转 400）。
    校验顺序刻意先文件名后内容：路径穿越风险最危险，优先拦截。
    """
    # 文件名必须与 Path(filename).name 一致，且不含路径分隔符——
    # 防止 "../x.md" 或 "a/b.md" 之类路径穿越把文件写到预期目录之外。
    # 空文件名（浏览器未填）同样在此拒绝。
    if not filename or filename != Path(filename).name or "\\" in filename or "/" in filename:
        raise ValueError("invalid filename")
    # 只允许 PDF 与 Markdown：PDF 会被转成 MD，Markdown 直接切分。
    suffix = Path(filename).suffix.lower()
    if suffix not in {".pdf", ".md"}:
        raise ValueError("only PDF and Markdown files are supported")
    # 空文件解析必然产不出文本；超限则拒绝，避免流水线长时间占用资源。
    if not payload or len(payload) > max_bytes:
        raise ValueError("file is empty or exceeds upload limit")
    return suffix


def normalize_chunks(document_id: str, shop_id: int, filename: str, chunks: list[dict]) -> list[dict]:
    """把切分节点产出的 chunk 规范化为可入库的检索文档结构。

    统一补齐 shop_id/source_type/source_id/file_title 等索引字段；
    空内容 chunk 直接丢弃，同时用正则从 Markdown 图片语法中抽取外链图片 URL。
    该函数是纯转换（无 IO），输入 chunk 结构来自 node_document_split 的产出。
    """
    documents = []
    # part 记录 chunk 在原文档中的序号（按列表位置），供检索结果排序与引用定位。
    for part, chunk in enumerate(chunks):
        # 空白 chunk（如纯标题分隔页）对检索无价值，跳过。
        # strip 后仍为空说明原 chunk 只有空白字符，同样跳过。
        content = str(chunk.get("content") or "").strip()
        if not content:
            continue
        # 提取 Markdown 图片外链：!\[alt\](http...) 形式，供前端在引用卡片展示配图。
        # 仅匹配 http/https 绝对链接，本站相对路径图片不进索引。
        image_urls = re.findall(r"!\[[^]]*\]\((https?://[^)]+)\)", content)
        # source_type 固定 document，source_id 是文档 UUID——删除时可整源清空向量。
        documents.append({
            "shop_id": shop_id, "source_type": "document", "source_id": document_id,
            "file_title": filename, "title": str(chunk.get("title") or filename),
            "part": part, "content": content, "image_urls": image_urls,
        })
    return documents


def save_upload(registry: DocumentRegistry, filename: str, payload: bytes, shop_id: int, owner_id: int) -> tuple[str, Path]:
    """校验并把上传内容落盘到独立目录，同时在注册表登记 pending 状态。

    目录结构：data/uploads/<document_id>/source.<ext>，以文档 UUID 隔离，
    删除时整目录清掉即可。返回 (document_id, 落盘路径) 供后台任务使用。
    落盘成功但登记失败时会留下孤儿目录，由删除流程兜底清理。
    """
    # 第一步：复用三重校验，拿到扩展名。
    suffix = validate_upload(filename, payload)
    # 归属参数再校验一次：api 层校验过的参数在这里仍不信任（防御式编程）。
    # shop_id=0 合法（公共资料），负数非法；owner_id 必须为正。
    if shop_id < 0 or owner_id <= 0:
        raise ValueError("invalid shop or owner ID")
    # 文档 ID 用 uuid4：无需数据库自增，天然全局唯一且可作目录名。
    # hex 即 32 位无连字符形式，与 remove_document 的正则校验对应。
    document_id = uuid.uuid4().hex
    directory = DATA_ROOT / "uploads" / document_id
    # exist_ok=False：UUID 碰撞时直接报错而不是写进已有目录（概率极低但要防）。
    directory.mkdir(parents=True, exist_ok=False)
    # 统一命名为 source.<ext>，与原始文件名解耦，避免文件名带来的路径风险。
    path = directory / f"source{suffix}"
    path.write_bytes(payload)
    # 注册表登记初始状态 pending，前端即可开始轮询状态。
    # 注意：登记放最后一步，保证「有记录必有文件」的不变式。
    registry.create(document_id, filename, shop_id, owner_id)
    # 返回给 api 层：document_id 进响应体，path 传给后台解析任务。
    return document_id, path


def process_document(registry: DocumentRegistry, document_id: str, path: Path) -> None:
    """后台异步解析流水线：PDF→MD 转换 → 图片上传 → 标题切分 → 向量化入库。

    状态机推进：进入时置 processing，成功置 completed（带 chunk 数），
    任何异常置 failed（截断后的错误信息），并向上抛出让调用方感知失败。
    本函数由 api.py 的 BackgroundTasks 在响应返回后调用。
    失败时向量不会部分入库：index_source 先攒齐全部行再一次性替换写入。
    """
    # 延迟导入解析节点：这些节点依赖 MinerU/PDF 处理链，较重且非必需。
    # 放在函数体内，保证上传接口本身不背这些重依赖。
    from app.import_process.agent.state import get_default_state
    from app.import_process.agent.nodes.node_entry import node_entry
    from app.import_process.agent.nodes.node_document_split import node_document_split

    # 文档可能已被并发删除：查不到记录就直接结束，不再推进状态。
    # 这里不校验 owner：后台任务可信（由 api 层校验后派发），只做存在性检查。
    record = registry.get(document_id)
    if record is None:
        return
    # 进入处理态：前端轮询可见，删除请求此时会被 409 拒绝。
    # 先置状态再开始耗时工作，避免处理中状态查询到 pending 的歧义。
    registry.set_status(document_id, "processing")
    try:
        # 复用 zgws 的全局 State 结构：填充任务 ID 与本地文件路径。
        state = get_default_state()
        state.update(task_id=document_id, local_file_path=str(path), local_dir=str(path.parent))
        # 入口节点做基础准备（目录、任务登记等）。
        state = node_entry(state)
        # PDF 先经 MinerU 转 Markdown；.md 文件跳过转换直接用原文。
        # 延迟导入：MinerU 依赖极重，仅 PDF 场景才加载。
        if path.suffix.lower() == ".pdf":
            from app.import_process.agent.nodes.node_pdf_to_md import node_pdf_to_md
            state = node_pdf_to_md(state)
        # 转换后必须有 Markdown 产物，否则后续切分无从谈起。
        if not state.get("md_path"):
            raise RuntimeError("document conversion produced no Markdown")
        markdown = Path(state["md_path"])
        # 若转换过程产出了本地 images 目录，走图片上传节点把图片换成外链；
        # 否则直接读 Markdown 原文进入切分。
        if (markdown.parent / "images").exists():
            from app.import_process.agent.nodes.node_md_img import node_md_img
            state = node_md_img(state)
        else:
            state["md_content"] = markdown.read_text(encoding="utf-8")
        # 按标题层级切分为 chunk，切分粒度与 zgws 线上资料一致。
        state = node_document_split(state)
        # 规范化入库结构；一个 chunk 都没有说明文档解析不出有效文本，判失败。
        # 例如扫描版图片 PDF 转 MD 后无文本层的情况。
        documents = normalize_chunks(document_id, record["shop_id"], record["filename"], state.get("chunks") or [])
        if not documents:
            raise RuntimeError("document parsing produced no searchable text")
        # 向量化并写入 Milvus，source_key=document:<id>，删除时按此整源清理。
        count = index_source(f"document:{document_id}", documents)
        # 全部成功：记录 chunk 数量，前端轮询到 completed 即可停止。
        registry.set_status(document_id, "completed", chunk_count=count)
    except Exception as exc:
        # 失败状态持久化错误信息（截断 500 字符防 SQLite 行过大），再抛出向上传播。
        registry.set_status(document_id, "failed", error=str(exc))
        raise


def remove_document(registry: DocumentRegistry, document_id: str, owner_id: int) -> bool:
    """删除文档的全量清理：Milvus 向量 → 磁盘上传目录 → 注册表记录。

    document_id 不合法或归属不符时返回 False（调用方转 404）；
    目录路径二次校验防止路径穿越删除 uploads 之外的任何内容。
    删除顺序刻意「先向量后磁盘再元数据」：最后删记录，中途失败可重试。
    """
    # UUID 格式前置校验：不合法的 ID 没有对应的目录与向量，直接返回 False。
    # fullmatch 保证 32 位十六进制且无其他字符。
    if not re.fullmatch(r"[0-9a-f]{32}", document_id):
        return False
    # 归属校验：owner_id 不匹配视为不存在，防止越权删除。
    record = registry.get(document_id, owner_id)
    if record is None:
        return False
    # 第一步：按 source_key 清空该文档在 Milvus 中的全部向量。
    delete_source(f"document:{document_id}")
    # 第二步：删除磁盘上传目录。resolve 后比对父目录，杜绝 "docid/../../etc" 穿越删除。
    # 正常 UUID 目录必然是 uploads 的直接子目录，比对失败即视为异常请求。
    uploads_root = (DATA_ROOT / "uploads").resolve()
    directory = (uploads_root / document_id).resolve()
    if directory.parent != uploads_root:
        raise ValueError("invalid upload directory")
    # 目录可能因历史失败而不存在，存在才删。
    if directory.exists():
        shutil.rmtree(directory)
    # 第三步：最后删注册表记录，即使前面某步失败也能重试（记录在则可再删）。
    # 返回 True 表示完整走完清理流程。
    registry.delete(document_id, owner_id)
    return True
