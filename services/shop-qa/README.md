# 黑马点评统一 RAG 服务

此目录是 `D:\code\zgws` 的独立副本。原项目保持不变。店铺资料、探店笔记和用户上传的 PDF/Markdown 文档写入同一个新 Milvus 集合 `hmdp_knowledge_v2`；原集合 `hmdp_shop_qa` 不会被改动。

每次进入 Java 项目后的完整启动顺序见 [`docs/RAG-使用说明.md`](../../docs/RAG-使用说明.md)。当前使用店铺 Markdown 上传方案时，可以共用 `D:\code\zgws\.venv`，不需要运行 `sync_mysql`，也不需要安装 `pymysql`。

## 准备

1. 在 `services/shop-qa` 目录安装 Python 依赖：`.\.venv\Scripts\python.exe -m pip install -e .`。
2. 将 `.env.example` 复制为 `.env`，填写 MySQL、Milvus、BGE-M3、BGE reranker、大模型、MinerU 和 MinIO 配置。PDF 解析依赖 MinerU；带本地图片的文档使用 MinIO 与视觉模型。
3. 启动 MySQL、Milvus、MinIO 和模型服务。原项目的 `zgws` 进程无需启动。
4. 首次或店铺/探店笔记变更后运行 `.\.venv\Scripts\python.exe -m app.shop_qa.sync_mysql`。该命令只读取 MySQL，同时导出每店一份 Markdown 到 `data/shop-markdown/shop-<id>.md`，并按来源更新 Milvus；上传文档不会被店铺同步删除。
5. 运行 `.\.venv\Scripts\python.exe -m uvicorn app.shop_qa.api:app --host 127.0.0.1 --port 8003`。另行启动黑马点评 Spring Boot 后端。

黑马点评前端页面：`http://127.0.0.1:8080/rag.html`，登录后通过 Java 后端上传文档和提问。本机 Python 调试页面仍是 `http://127.0.0.1:8003/`；该页面仅监听本机。

如果暂时没有运行 MySQL，可以从项目自带的初始 SQL 数据直接导出 Markdown：
`python -m app.shop_qa.export_markdown --seed-sql ..\..\src\main\resources\db\hmdp.sql`。
初始数据与当前数据库可能不同；连上 MySQL 后重新运行 `sync_mysql`，Markdown 会按当前数据库覆盖更新。

## 接口

黑马点评后端监听 `8081` 时：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/shop/{shopId}/ask` | 店铺问答，检索该店和全站通用资料；JSON `{"question":"有什么特色？","session_id":null}` |
| POST | `/rag/ask` | 全站知识库问答；JSON `{"question":"...","session_id":null}` |
| POST | `/rag/documents` | 登录后 multipart 上传，字段 `file`、可选 `shopId`；不传 `shopId` 为全站文档 |
| GET | `/rag/documents` | 查看本人上传文档 |
| GET | `/rag/documents/{id}` | 查看导入状态（pending/processing/completed/failed） |
| DELETE | `/rag/documents/{id}` | 删除本人文档的向量数据、记录和上传文件 |

Python 本地 API 还提供 `POST /shops/{id}/stream`、`POST /stream` SSE 流及 `/sessions/{id}/history` 会话历史。文档元数据和会话记录保存在忽略提交的 `data/documents.sqlite3`，原文件保存在 `data/uploads/`。上传上限 20 MB。

### 上传示例（PowerShell）

使用已登录的黑马点评前端或带登录 token 的 HTTP 客户端向 `/rag/documents` 发送表单文件。返回 `document_id` 后轮询 `/rag/documents/{id}`，状态为 `completed` 才会参与问答。

## 检索与来源

- `shop_id = 0` 表示全站文档。问某家店时检索该店及全站文档，其他店的内容不会进入结果。全站问答只检索全站文档。
- 文档沿用 `zgws` 的 PDF 转 Markdown、图片处理和按标题切分节点。店铺及笔记从 MySQL 转为文本切片。两路共用 BGE-M3 混合向量、可配置 HyDE、BGE 重排、来源引用和回答生成。
- 上传文档和店铺数据分别用 `document:<id>`、`shop:<id>`、`blog:<id>` 作为来源键，重新索引时只替换对应来源。
- `RAG_ENABLE_WEB_SEARCH` 默认关闭；配置 MCP 后可在全站问答请求中设置 `allow_web=true` 使用原项目的网页搜索节点。原项目的 Neo4j 节点仍是占位实现。

## 测试

`python -m unittest discover -s tests -p 'test_*.py'` 验证切片元数据、来源隔离、上传记录与检索合并。真实 PDF 解析和回答还需要运行中的 MinerU、Milvus、MinIO、模型服务。
