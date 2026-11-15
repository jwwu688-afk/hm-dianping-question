# 黑马点评 RAG 使用说明

本文说明每次打开 `D:\code\hm-dianping` 后，如何启动并使用 RAG。当前方案直接上传已经生成的店铺 Markdown，不需要运行 MySQL 同步，也不需要为 RAG 安装 `pymysql`。

## 一、首次使用时做一次

1. 检查 `D:\code\hm-dianping\services\shop-qa\.env` 已填写 Milvus、BGE-M3、BGE reranker 和大模型配置。
2. 启动 Milvus 和大模型 API。仅上传当前这些纯文字 Markdown 时，不需要启动 MinerU 和 MinIO；上传 PDF 或包含本地图片的文档时才需要相应服务。
3. 确认原 `zgws` 的虚拟环境存在：

   ```powershell
   Test-Path D:\code\zgws\.venv\Scripts\python.exe
   ```

   输出 `True` 即可。

## 二、每次开发时的启动顺序

### 1. 启动基础服务

先启动 Java 项目本身需要的 MySQL、Redis，以及 RAG 需要的 Milvus和大模型 API。数据库密码和端口以 `src/main/resources/application.yaml` 与 `services/shop-qa/.env` 为准。

### 2. 启动 RAG Python 服务

新开一个 PowerShell 窗口并保持运行：

```powershell
cd D:\code\hm-dianping\services\shop-qa
D:\code\zgws\.venv\Scripts\python.exe -m uvicorn app.shop_qa.api:app --host 127.0.0.1 --port 8003
```

看到下面的信息表示服务已启动：

```text
Uvicorn running on http://127.0.0.1:8003
```

可以在另一个 PowerShell 窗口检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8003/health
```

返回 `ok : True` 表示正常。

### 3. 启动 Java 项目

在 IDEA 中运行黑马点评的 Spring Boot 启动类。默认端口是 `8081`，Java 后端默认通过 `http://127.0.0.1:8003` 调用 RAG，不需要另外填写地址。

如果从 PowerShell 启动 Java，也可以运行：

```powershell
cd D:\code\hm-dianping
mvn spring-boot:run
```

### 4. 启动 Nginx 前端

新开一个 PowerShell 窗口并保持运行：

```powershell
cd D:\code\nginx-1.18.0
.\nginx.exe
```

如果 Nginx 已经在运行，修改前端文件后可执行 `.\nginx.exe -s reload`。

> 前端源码位置：新版商城前台已于 2026-10-10 迁到 `D:\code\hm-dianping\frontend`（原 `D:\code\nginx-1.18.0\html\hmdp-next`），与 Java 后端同仓库管理；`nginx.conf` 的 `root` 已指向该绝对路径。旧版「智能问答」前端仍在 `D:\code\nginx-1.18.0\html\hmdp`。改完前端记得 `-s reload`，浏览器再 Ctrl+F5 硬刷新。

### 5. 使用 RAG

- 登录黑马点评后打开前端智能问答页面：`http://127.0.0.1:8080/rag.html`
- 首页底部的“智能问答”也可以进入该页面。
- Python 本机调试页面仍是：`http://127.0.0.1:8003/`
- 店铺 Markdown 目录：`D:\code\hm-dianping\services\shop-qa\data\shop-markdown`
- 上传 `shop-1.md` 时填写店铺 ID `1`，上传 `shop-2.md` 时填写店铺 ID `2`，以此类推。
- 等文档状态变成 `completed` 后再提问。
- 通过 Java 后端问某家店：`POST http://127.0.0.1:8081/shop/{shopId}/ask`
- 通过 Java 后端进行全站知识问答：`POST http://127.0.0.1:8081/rag/ask`

店铺文档成功上传一次后，向量保存在 Milvus，文档记录保存在 `services/shop-qa/data/documents.sqlite3`。只要没有删除 Milvus 数据、SQLite 文件或文档记录，以后启动项目时不需要重新上传。

## 三、停止服务

在运行 RAG 的 PowerShell 窗口按 `Ctrl+C`，再停止 IDEA 中的 Spring Boot 程序。在 Nginx 目录运行 `.\nginx.exe -s stop` 停止前端服务。Milvus、MySQL、Redis和大模型服务按各自的启动方式停止。

## 四、常见问题

### 页面打不开

先运行：

```powershell
Invoke-RestMethod http://127.0.0.1:8003/health
```

如果连接失败，检查运行 RAG 的 PowerShell 窗口中的错误。

### 文档一直导入失败

检查 Milvus 是否运行，以及 `.env` 中的 `MILVUS_URL`、`BGE_M3_PATH` 是否正确。PDF 还需要 MinerU，带本地图片的 Markdown 还需要 MinIO。

### 问答提示服务不可用

依次确认 RAG 的 `/health`、Milvus和大模型 API 是否可用，并查看 RAG PowerShell 窗口中的报错。

### 是否需要启动原 zgws 项目

不需要。这里只借用 `D:\code\zgws\.venv` 的 Python 解释器和已安装依赖，实际运行的是 `D:\code\hm-dianping\services\shop-qa` 中的代码。

### 是否需要 pymysql 或 sync_mysql

当前采用上传 Markdown 的方式，不需要。只有希望重新从 MySQL 读取最新店铺和探店笔记时，才需要 `pymysql` 和 `sync_mysql`。
