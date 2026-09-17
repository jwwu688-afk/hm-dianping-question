# 黑马点评单店问答服务

此目录从 `D:\code\zgws` 复制业务代码、提示词和依赖配置后独立修改。原 `zgws` 保持原状。新问答入口在 `app/shop_qa/`；原手册问答和导入模块仍在副本内，店铺数据使用独立的 Milvus 集合 `hmdp_shop_qa`。

## 启动

1. 建立 Python 3.11+ 虚拟环境并安装 `pyproject.toml` 依赖：`python -m pip install -e .`。首次安装需下载模型和较大的机器学习依赖。
2. 把 `.env.example` 复制为 `.env`，填写模型、Milvus、MySQL 和 LLM 配置；不要复制原项目的密钥文件。
3. 在此目录运行 `python -m app.shop_qa.sync_mysql`，从 `tb_shop`、`tb_blog` 建立索引。数据更新后重新运行此命令；同一店铺的旧切片会被覆盖。
4. 运行 `python -m uvicorn app.shop_qa.api:app --host 127.0.0.1 --port 8003`。

黑马点评后端提供 `POST /shop/{shopId}/ask`，请求示例：`{"question":"这家店有什么特色？"}`。后端调用本机 `8003` 的 Python 服务，返回 `answer` 和 `sources`。如需更改 Python 地址，可设置 Spring 配置 `shop.qa.url`。

## 边界

- 问答只检索传入 `shopId` 的资料，不使用联网搜索或原项目的主体名称猜测。
- 店铺资料和探店笔记分开标注；回答中的 `[n]` 对应返回的 `sources[].ref`。笔记来源 ID 可用于跳转 `/blog/{id}`。
- 当前采用手动全量同步。新增或修改店铺、笔记后需要重跑索引命令，之后可增加自动增量同步。
- 需要已有 Milvus、MySQL 和模型服务。未配置这些服务时，单元测试可运行，但无法验证真实问答。
