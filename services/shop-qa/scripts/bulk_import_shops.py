"""一次性批量导入 data/shop-markdown/shop-<id>.md 到知识库（走本地 API）。"""

# 背景：generate_verified_shop_seed.py 会为 50~64 家店铺各生成一份
# shop-<id>.md；本脚本把这些 Markdown 逐个上传到本地 8003 端口的
# documents 接口（POST multipart），触发后台切片 + 向量化 + 写 Milvus，
# 之后轮询文档状态直到全部 completed / failed。
#
# 使用前置：
# - 本地服务已启动（如 uvicorn app.total_service:app --port 8003）；
# - Milvus / Embedding 服务可用（上传后是异步处理，失败要在状态轮询阶段暴露）。
#
# 用法：
#     python scripts/bulk_import_shops.py            # 导入全部 shop-*.md
#     python scripts/bulk_import_shops.py 1001 1002  # 只导入指定店铺 ID
#
# 实现说明：刻意只用标准库（urllib 不用 requests），保证脚本在裸环境也能跑。
# 与正规上传链路的差异：这里是「脚本侧手工拼 multipart」，省去浏览器；
# 服务端处理逻辑（切片、Embedding、写 Milvus、注册表登记）与页面上传完全相同，
# 所以导入产物可以直接用于问答与检索评测。

import re
import sys
import time
from pathlib import Path
# 用标准库 urllib 发 HTTP（不引 requests）：脚本保持零第三方依赖
from urllib import request

# 本地问答/文档服务的根地址（只支持本机导入，避免误传到远端环境）
API = "http://127.0.0.1:8003"
# 脚本位于 scripts/ 下，上溯一级即 shop-qa 目录，Markdown 在 data/shop-markdown/
ROOT = Path(__file__).resolve().parents[1]
MD_DIR = ROOT / "data" / "shop-markdown"


def post_file(path: Path, shop_id: int, owner_id: int = 1) -> str:
    """用 multipart/form-data 上传一份店铺 Markdown，返回服务端分配的文档 ID。

    等价于 curl -F shop_id=xx -F file=@xx.md http://127.0.0.1:8003/documents；
    手工拼 multipart body 而不是用 requests，是为了零第三方依赖。
    owner_id 固定 1：种子数据统一挂在演示账号名下。
    """
    # multipart 分隔符：保证不会与文件内容冲突即可
    boundary = "----wbimport"
    # 两段普通表单字段（shop_id / owner_id），服务端据此归属文档
    # multipart 每段格式：--boundary + 头部 + 空行 + 内容 + CRLF
    body = b""
    for name, value in (("shop_id", str(shop_id)), ("owner_id", str(owner_id))):
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n").encode()
    # 文件段：声明文件名与 Markdown MIME 类型，随后跟文件原始字节
    # 文件内容必须单独成段且以 CRLF 结尾，服务端按 boundary 切分后解析
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
             f"filename=\"{path.name}\"\r\nContent-Type: text/markdown\r\n\r\n").encode()
    body += path.read_bytes() + b"\r\n"
    # 结尾分隔符（比普通分隔符多两个连字符）标志 multipart 报文结束
    # 少了这一段服务端会认为报文被截断，直接返回 400
    body += f"--{boundary}--\r\n".encode()
    req = request.Request(f"{API}/documents", data=body, method="POST",
                          headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    # 60 秒超时：上传本身很快，超时多半意味着服务没起
    with request.urlopen(req, timeout=60) as resp:
        return resp.read().decode()


def get_status(doc_id: str) -> dict:
    """查询文档处理状态：pending / completed / failed（含 error 信息）。"""
    # owner_id=1 与上传时保持一致：接口按 owner 鉴权，传错会查不到
    with request.urlopen(f"{API}/documents/{doc_id}?owner_id=1", timeout=20) as resp:
        import json
        return json.loads(resp.read().decode())


def main() -> None:
    """两阶段主流程：先全部上传（快速返回），再统一轮询处理状态直至收敛。"""
    # 收集待导入文件：按文件名里的店铺 ID 数字排序，保证输出顺序稳定
    # glob 只匹配 shop-*.md，其他实验性 Markdown 不会被误导入
    files = sorted(MD_DIR.glob("shop-*.md"), key=lambda p: int(re.findall(r"\d+", p.stem)[0]))
    # 命令行参数可指定只导入部分店铺 ID（便于失败重跑单店）
    # 例如：python bulk_import_shops.py 1001 1002 只处理这两家店
    only = [int(x) for x in sys.argv[1:]] if len(sys.argv) > 1 else None
    if only:
        files = [p for p in files if int(re.findall(r"\d+", p.stem)[0]) in only]
    print(f"待导入 {len(files)} 个文件")
    # 记录已受理但未完成的文档 ID，第二阶段逐个轮询
    # 用列表收集 doc_id 字符串，转 set 去重后轮询
    pending = []
    # 阶段一：逐个上传，两次之间 sleep 50ms 轻微限流，避免压垮本地服务
    # （服务端对每份文档要做切片 + 向量化，一次灌入太多会排队很久）
    for path in files:
        # 文件名 shop-1007.md → 店铺 ID 1007
        shop_id = int(re.findall(r"\d+", path.stem)[0])
        try:
            doc_id = post_file(path, shop_id)
            print(f"  + {path.name} shop_id={shop_id} -> {doc_id}")
            pending.append(doc_id)
        except Exception as exc:  # noqa: BLE001
            # 单个失败不中断整体（比如某文件已导入过），最后统一看未收敛的
            print(f"  ! {path.name} 上传失败: {exc}")
        time.sleep(0.05)
    # 等待后台处理完成
    # 阶段二：轮询直到所有文档进入终态，总超时 10 分钟兜底
    # （上传是异步的：接口返回只代表受理，切片/向量/入库在后台进行）
    deadline = time.time() + 600
    remaining = set(pending)
    while remaining and time.time() < deadline:
        for doc_id in list(remaining):
            try:
                st = get_status(doc_id)
            except Exception:  # noqa: BLE001
                # 状态查询偶发失败（网络抖动）不算终态，下轮再查
                continue
            # completed / failed 都是终态：从待查集合移除
            if st.get("status") in ("completed", "failed"):
                remaining.discard(doc_id)
                if st.get("status") == "failed":
                    # 失败要打印 filename + error，便于定位是哪份文档出了问题
                    print(f"  ! {st.get('filename')} 处理失败: {st.get('error')}")
        if remaining:
            # 每 2 秒轮询一轮，节奏足够且不刷屏
            time.sleep(2)
    # 收尾输出：remaining 非空说明有文档超时未终态（或查询一直失败），需要人工排查
    print(f"完成，未结束: {len(remaining)}")


# 独立运行入口：可在 shop-qa 目录下直接执行，无需额外环境变量
if __name__ == "__main__":
    main()
