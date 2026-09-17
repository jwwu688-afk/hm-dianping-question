"""
小度小度 · 统一工作台服务（total_service）

职责：
    1. 对外提供「统一工作台页面」index.html（智能问答 + 知识库导入 双视图）；
    2. 启动本服务时，自动拉起两个后端业务服务（子进程）：
        - 问答：http://127.0.0.1:8001  （app/query_process/api/query_service.py）
        - 导入：http://127.0.0.1:8002  （app/import_process/api/file_import_service.py）

    页面通过 fetch 直连业务服务，本服务自身不承载业务接口。
    退出本服务（Ctrl+C）时，会自动回收由它拉起的子进程。

访问：http://127.0.0.1:8000/

启动：
    uv run python app/total_service.py              # 页面服务 + 自动拉起问答/导入服务
    uv run python app/total_service.py --no-children # 只启动页面服务（子服务另行手动启动）
"""
import atexit
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from starlette.middleware.cors import CORSMiddleware

# 目录：本文件位于 app/ 下，index.html 位于 app/page/，其余服务在 app/<xxx>_process/api/
APP_DIR = Path(__file__).absolute().parent
PROJECT_ROOT = APP_DIR.parent
PAGE_DIR = APP_DIR / "page"

# 端口分配
SELF_HOST = "127.0.0.1"
# 统一工作台页面端口：默认 8000，可用环境变量 TOTAL_SERVICE_PORT 覆盖
# （便于 8000 被其它程序占用时改用其它端口，无需改代码）
SELF_PORT = int(os.getenv("TOTAL_SERVICE_PORT", "8000"))
QUERY_PORT = 8001  # 问答服务
IMPORT_PORT = 8002  # 文件导入服务（原 8008，已调整）

# 随本服务一起启动的后端业务服务（列表顺序即启动顺序）
CHILD_SERVICES = [
    {
        "name": "问答服务 query_service",
        "script": APP_DIR / "query_process" / "api" / "query_service.py",
        "port": QUERY_PORT,
    },
    {
        "name": "导入服务 file_import_service",
        "script": APP_DIR / "import_process" / "api" / "file_import_service.py",
        "port": IMPORT_PORT,
    },
]

# 已拉起的子进程：[{"proc": Popen, "service": {...}}, ...]
_children: list = []


# --------------------------
# 子服务管理
# --------------------------
def _port_open(host: str, port: int, timeout: float = 0.5) -> bool:
    """探测端口是否已有服务在监听（原生 socket，绕过系统代理）"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        return sock.connect_ex((host, port)) == 0


def _spawn(service: dict) -> subprocess.Popen:
    """以子进程方式启动业务服务：沿用当前解释器，工作目录固定为项目根"""
    env = os.environ.copy()
    # 直接以脚本路径运行时，Python 只把脚本所在目录加入 sys.path，
    # 这里显式补上项目根目录，保证子进程能 `from app.xxx import ...`
    existing = env.get("PYTHONPATH")
    env["PYTHONPATH"] = os.pathsep.join(
        [str(PROJECT_ROOT)] + ([existing] if existing else [])
    )
    # 关闭输出缓冲，子服务日志可与本服务实时交织打印
    env["PYTHONUNBUFFERED"] = "1"
    return subprocess.Popen(
        [sys.executable, str(service["script"])],
        cwd=str(PROJECT_ROOT),
        env=env,
    )


def start_child_services(timeout: float = 30.0) -> None:
    """启动所有后端业务服务，并等待其端口就绪"""
    pending = []
    for service in CHILD_SERVICES:
        name, port, script = service["name"], service["port"], service["script"]
        # 端口已被占用：说明服务已在别处运行，不重复启动
        if _port_open(SELF_HOST, port):
            print(f"[total_service] {name} 已在 {port} 端口运行，跳过启动")
            continue
        if not Path(script).exists():
            print(f"[total_service] 未找到脚本 {script}，跳过 {name}")
            continue
        print(f"[total_service] 正在启动 {name}（端口 {port}）...")
        proc = _spawn(service)
        _children.append({"proc": proc, "service": service})
        pending.append({"proc": proc, "service": service})

    # 等待子服务就绪（最长 timeout 秒，就绪或失败都会提前跳出）
    deadline = time.time() + timeout
    while pending and time.time() < deadline:
        for item in list(pending):
            proc, service = item["proc"], item["service"]
            if _port_open(SELF_HOST, service["port"]):
                print(f"[total_service] [OK] {service['name']} 就绪 → "
                      f"http://{SELF_HOST}:{service['port']}")
                pending.remove(item)
            elif proc.poll() is not None:
                print(f"[total_service] [FAIL] {service['name']} 启动失败"
                      f"（退出码 {proc.returncode}），请查看上方日志")
                pending.remove(item)
        if pending:
            time.sleep(0.5)

    for item in pending:
        print(f"[total_service] [WAIT] {item['service']['name']} 暂未就绪"
              f"（端口 {item['service']['port']} 未监听），可能仍在启动中")


def stop_child_services() -> None:
    """回收由本服务拉起的子进程（退出时自动调用，重复调用安全）"""
    alive = [item for item in _children if item["proc"].poll() is None]
    for item in alive:
        print(f"[total_service] 正在停止 {item['service']['name']} ...")
        item["proc"].terminate()
    # 给子进程一点优雅退出的时间，超时则强制结束
    for _ in range(10):
        if all(item["proc"].poll() is not None for item in alive):
            break
        time.sleep(0.3)
    for item in alive:
        if item["proc"].poll() is None:
            item["proc"].kill()
    _children.clear()


# 兜底：无论以何种方式退出（含异常），都回收子进程
atexit.register(stop_child_services)


# --------------------------
# 页面服务（本服务本体）
# --------------------------
app = FastAPI(title="total service", description="小度小度统一工作台入口！")

# 跨域：页面与业务服务不同源，统一放开
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 禁用缓存：避免前端改动后浏览器仍加载旧页面
NO_CACHE_HEADERS = {
    "Cache-Control": "no-cache, no-store, must-revalidate",
    "Pragma": "no-cache",
    "Expires": "0",
}


@app.get("/")
async def index():
    """返回统一工作台页面 index.html（智能问答 + 知识库导入 双视图）"""
    index_html_path = PAGE_DIR / "index.html"
    if not index_html_path.exists():
        raise HTTPException(status_code=404, detail=f"没有查询到页面，地址为：{index_html_path}！")
    return FileResponse(index_html_path, headers=NO_CACHE_HEADERS)


@app.get("/index.html")
async def index_html():
    """兼容显式访问 /index.html 的方式"""
    return await index()


@app.get("/health")
async def health():
    """服务健康检查"""
    return {"ok": True}


if __name__ == "__main__":
    # 日志重定向到文件时默认是块缓冲，改为按行刷出，保证启动过程可见
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(line_buffering=True)

    # 默认随本服务一起拉起后端业务服务；加 --no-children 可跳过
    if "--no-children" not in sys.argv:
        start_child_services()

    print("\n" + "=" * 62)
    print(f"  小度小度 · 统一工作台已启动：http://{SELF_HOST}:{SELF_PORT}/")
    print(f"  问答页(chat.html)：http://{SELF_HOST}:{QUERY_PORT}/")
    print(f"  导入页(import.html)：http://{SELF_HOST}:{IMPORT_PORT}/")
    print("  按 Ctrl+C 退出（将同时停止它拉起的子服务）")
    print("=" * 62 + "\n")

    try:
        uvicorn.run(app, host=SELF_HOST, port=SELF_PORT)
    finally:
        stop_child_services()
