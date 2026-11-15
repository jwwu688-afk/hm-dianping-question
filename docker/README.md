# 本地依赖容器（MySQL / Redis / RabbitMQ + Milvus / MinIO / MongoDB）

黑马点评跑起来需要的全部中间件，统一由本目录的 `docker-compose.yml` 管理。
一个 compose 项目名 `hmdp`，在 Docker Desktop 的 **Containers 页面会显示成一个分组**，
点分组上的启动按钮即可一次性拉起全部 8 个容器，不用再一个个 `docker run`。

## 一键启动

Docker Desktop → Containers 页面 → 找到 `hmdp` 分组 → 点击启动。

命令行等价操作（在本目录执行）：

```bash
docker compose up -d      # 启动全部
docker compose stop       # 停止（保留容器和数据）
docker compose down       # 删除容器（./data 下的数据仍保留）
docker compose logs -f    # 看日志
docker compose ps         # 看状态
```

## 容器与连接参数

### Java 后端用（`src/main/resources/application.yaml`）

| 服务 | 容器名 | 端口 | 账号 | 对应配置 |
| --- | --- | --- | --- | --- |
| MySQL 8.0 | `hmdp-mysql` | 3306 | root / 123456，库 `hmdp` | `spring.datasource` |
| Redis 6.2.6 | `hmdp-redis` | 6379 | 密码 123456，开启 AOF | `spring.redis` |
| RabbitMQ 3.8 | `hmdp-rabbitmq` | 5672 / 15672 | root / 123456 | `spring.rabbitmq` |

### Python RAG 服务用（`services/shop-qa/.env`）

| 服务 | 容器名 | 端口 | 账号 | 对应配置 |
| --- | --- | --- | --- | --- |
| Milvus 2.4.11 | `hmdp-milvus` | 19530 / 9091 | — | `MILVUS_URL` |
| etcd 3.5.5 | `hmdp-etcd` | 内部 2379 | — | Milvus 元数据 |
| milvus-minio | `hmdp-milvus-minio` | 9002 / 9003 | minioadmin / minioadmin | Milvus 内部对象存储 |
| MinIO | `hmdp-minio` | 9000 / 9001 | admin / MyPassword123 | `MINIO_ENDPOINT` |
| MongoDB 8.3 | `hmdp-mongo` | 27017 | 无认证，库 `kb002` | `MONGO_URL` |

> 两个 MinIO 不要混：`hmdp-milvus-minio`（9002/9003）是 Milvus 自己的存储，
> `hmdp-minio`（9000/9001）才是 shop-qa 上传文档/图片用的那个桶 `knowledge-base-files`。

可用入口：
- MinIO 控制台 <http://localhost:9001>（admin / MyPassword123）
- RabbitMQ 控制台 <http://localhost:15672>（root / 123456）
- Milvus 健康检查 <http://localhost:9091/healthz>
- Milvus 可视化：旧的 `attu` 容器仍可用，`docker start attu` 后打开 <http://localhost:8000>

## 这些容器之外，还要手动启动的

| 组件 | 启动方式 |
| --- | --- |
| Spring Boot 后端（:8081） | IDEA 里运行启动类，或 `mvn spring-boot:run` |
| Python RAG 服务（:8003） | `D:\code\zgws\.venv\Scripts\python.exe -m uvicorn app.shop_qa.api:app --host 127.0.0.1 --port 8003`（在 `services/shop-qa` 下执行） |
| Nginx 前端 | `D:\code\nginx-1.18.0\nginx.exe` |

Python 服务没有容器化：它要加载本机 `D:/ai_models` 下的 BGE-M3、reranker 模型，
直接跑本地 venv 改代码重启最快，容器化反而多一层构建和挂载。

## 目录结构

```
docker/
├── docker-compose.yml       # 8 个服务的完整定义
├── init/01-hmdp.sql         # 建库建表兜底脚本（仅当 data/mysql 为空时执行）
└── data/
    ├── mysql/               # 455M，原 mysql8 容器数据（11 张表、64 店铺、1007 用户）
    ├── redis/               # Redis AOF
    ├── rabbitmq/            # RabbitMQ 数据
    ├── milvus/              # 146M，向量库数据（hmdp_knowledge_v2 65 条等）
    ├── milvus-etcd/         # 62M，Milvus 元数据
    ├── milvus-minio/        # Milvus 内部对象存储
    ├── minio/               # 2.6M，桶 knowledge-base-files / zgws
    └── mongo/               # 236M，库 kb002（chat_message 23 条）
```

数据全部落在本目录 `./data` 下，整个 `docker` 文件夹可以整体备份/迁移。
以上数据都是从原容器/原目录完整复制过来的，原位置保留未动。

## 注意事项

- **旧的单容器和旧 compose 项目不要再启动**，端口会冲突：
  - 单容器：`mysql8`、`redis-6.2.6`、`rabbitmq3.8`、`milvus-standalone`、`milvus-etcd`、
    `milvus-minio`、`minio`、`mongo`、`attu`
  - compose 项目：`C:\Users\Jarvis\milvus`（项目名 `milvus`，端口 19530/9002/9003 完全一致）

  数据已全部复制到本目录，确认新环境跑通后可清理：

  ```bash
  docker rm mysql8 redis-6.2.6 rabbitmq3.8 milvus-standalone milvus-etcd milvus-minio minio mongo
  ```

  `attu` 建议留着（只是 Web UI，可随时连新 Milvus 用）。

- **MySQL 镜像必须钉 `mysql:8.0`**：数据文件来自 8.0.46，换成 `mysql:8`（现为 8.4）
  会因拒绝降级而启动失败。同理 `mongo:8.3` 不能降。

- **MinIO 镜像用 `RELEASE.2025-09-07`**：这是最后一个社区版，官方仓库已下架，
  镜像已在本机，不要 `docker pull` 同名其它 tag。

- 想从零重建数据库（会清空业务数据）：停容器 → 删除 `data/mysql` 目录 → `docker compose up -d`，
  此时会执行 `init/01-hmdp.sql` 重新建库建表。Milvus 的向量数据无法用 SQL 重建，
  清空 `data/milvus` 后需要重跑 shop-qa 的索引脚本。
