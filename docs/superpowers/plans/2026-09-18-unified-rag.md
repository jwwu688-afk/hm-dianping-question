# Unified RAG Implementation Plan

**Goal:** Keep the copied zgws project intact while making MySQL shop data and uploaded PDF/Markdown documents searchable through one shop-aware knowledge base.

**Architecture:** The copied Python service owns a new Milvus collection with stable source keys and optional shop IDs. MySQL sync and uploaded files feed the same indexing function. Existing MinerU, Markdown image and heading-aware split nodes prepare uploaded files. The Java backend proxies authenticated document management and shop questions to the local Python service.

**Tech stack:** Python/FastAPI, existing zgws parsing and embedding utilities, Milvus, SQLite document registry, Spring Boot 2.7.

**Spec:** Approved conversation design on 2026-09-18: one knowledge base, two ingestion routes, one answer route; files may be shop-scoped or global.

## Tasks

- [ ] Add a normalized source schema and indexer: stable `source_key`, `source_type`, `source_id`, `shop_id` (0 means global), `title`, `content`, `part`, `image_urls`, dense and sparse vectors. Test source-specific replacement and shop filtering with fakes before implementation.
- [ ] Adapt MySQL sync to upsert shop and blog sources separately. It must preserve uploaded documents and remove stale shop/blog sources.
- [ ] Add PDF/Markdown upload processing that reuses the copied PDF conversion, Markdown image, and heading-aware split nodes. Add durable document metadata, status/list/delete, safe filenames, file limits, and scoped indexing. Test Markdown splitting and source lifecycle without external services.
- [ ] Route both shop and global questions to the unified collection with source citations, optional HyDE and reranking. Expose synchronous and streaming answers with session history; test scope isolation and empty evidence behavior.
- [ ] Add authenticated Spring proxy endpoints for upload, document management, and global questions. Keep shop questions on `/shop/{shopId}/ask`. Build and verify the Java project.
- [ ] Update configuration and startup documentation, run Python tests and compile checks, then verify original zgws is unchanged.

## Validation constraints

- Never copy secret values to tracked files or command output.
- Do not alter the original `D:\code\zgws` directory.
- Keep existing user changes in `D:\code\hm-dianping`.
- Real model/Milvus/MinerU integration needs running external services; report any unverified path honestly.
