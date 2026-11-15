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
# uvicorn：ASGI 服务器（本服务自身用它跑）；CORSMiddleware：跨域放行中间件。

# 目录：本文件位于 app/ 下，index.html 位于 app/page/，其余服务在 app/<xxx>_process/api/
# 三个路径常量都用 __file__ 锚定，与启动时的工作目录无关。
APP_DIR = Path(__file__).absolute().parent
# 项目根：子进程以它为 cwd 与 PYTHONPATH 前缀。
PROJECT_ROOT = APP_DIR.parent
# 页面目录：本服务唯一的静态资源来源。
PAGE_DIR = APP_DIR / "page"

# 端口分配
SELF_HOST = "127.0.0.1"
# 统一工作台页面端口：默认 8000，可用环境变量 TOTAL_SERVICE_PORT 覆盖
# （便于 8000 被其它程序占用时改用其它端口，无需改代码）
# int() 转换失败会抛异常：端口配置错误宁可启动失败也要暴露。
SELF_PORT = int(os.getenv("TOTAL_SERVICE_PORT", "8000"))
# 固定端口约定：前端页面里 fetch 直连这两个地址。
QUERY_PORT = 8001  # 问答服务
IMPORT_PORT = 8002  # 文件导入服务（原 8008，已调整）

# 随本服务一起启动的后端业务服务（列表顺序即启动顺序）
# 每项含 name（日志展示）、script（入口脚本绝对路径）、port（就绪探测端口）。
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
# 进程句柄必须持有：Popen 对象被回收会导致 stop 阶段拿不到 returncode。
# 列表在 stop_child_services 清空，生命周期与主进程一致。
_children: list = []


# --------------------------
# 子服务管理
# --------------------------
def _port_open(host: str, port: int, timeout: float = 0.5) -> bool:
    """探测端口是否已有服务在监听（原生 socket，绕过系统代理）

    用 connect_ex 而非 connect：连不上返回错误码而不是抛异常，
    探测失败是常态（服务未起），不该走异常路径。
    """
    # connect_ex 返回 0 表示连接成功（端口有人监听），非 0 表示连不上。
    # 0.5 秒超时：探测失败即认为无服务，不拖慢启动流程。
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        return sock.connect_ex((host, port)) == 0


def _spawn(service: dict) -> subprocess.Popen:
    """以子进程方式启动业务服务：沿用当前解释器，工作目录固定为项目根

    环境变量处理三件事：PYTHONPATH 补项目根、日志不缓冲、其余全量继承。
    """
    # 复制当前环境：子服务沿用父进程的全部环境变量（含 .env 注入的配置）。
    env = os.environ.copy()
    # 直接以脚本路径运行时，Python 只把脚本所在目录加入 sys.path，
    # 这里显式补上项目根目录，保证子进程能 `from app.xxx import ...`
    # 追加而非覆盖：尊重外部已配置的 PYTHONPATH。
    # os.pathsep 兼容 Windows（;）与 POSIX（:）的分隔符差异。
    existing = env.get("PYTHONPATH")
    env["PYTHONPATH"] = os.pathsep.join(
        [str(PROJECT_ROOT)] + ([existing] if existing else [])
    )
    # 关闭输出缓冲，子服务日志可与本服务实时交织打印
    env["PYTHONUNBUFFERED"] = "1"
    # sys.executable：用同一个 Python 解释器启动，避免多版本环境错乱；
    # cwd 固定项目根：脚本内相对路径（如 data/）行为一致。
    # 不捕获输出：子服务日志直接继承父进程的 stdout/stderr，交织可见。
    return subprocess.Popen(
        [sys.executable, str(service["script"])],
        cwd=str(PROJECT_ROOT),
        env=env,
    )


def start_child_services(timeout: float = 30.0) -> None:
    """启动所有后端业务服务，并等待其端口就绪

    就绪判定用「端口是否监听」而非子进程是否存活：
    能监听端口才是服务真正可用的信号。
    """
    # pending 记录「已派发但尚未确认就绪」的子进程，供就绪等待循环消费。
    pending = []
    for service in CHILD_SERVICES:
        # 解包三要素：名称用于日志，端口用于探测，脚本用于启动。
        name, port, script = service["name"], service["port"], service["script"]
        # 端口已被占用：说明服务已在别处运行，不重复启动
        # 这是幂等设计：total_service 与子服务可以被单独重启而互不干扰。
        if _port_open(SELF_HOST, port):
            print(f"[total_service] {name} 已在 {port} 端口运行，跳过启动")
            continue
        if not Path(script).exists():
            # 脚本缺失多为部署不完整：跳过而非崩溃，页面服务仍可用。
            print(f"[total_service] 未找到脚本 {script}，跳过 {name}")
            continue
        print(f"[total_service] 正在启动 {name}（端口 {port}）...")
        # 派发子进程并登记到 _children（退出回收用）与 pending（就绪探测用）。
        # 同一个 Popen 对象进两个列表：回收与探测各持一份引用。
        proc = _spawn(service)
        # 登记回收用：atexit/finally 阶段逐个 terminate/kill。
        _children.append({"proc": proc, "service": service})
        # 登记探测用：start 阶段轮询端口确认就绪。
        pending.append({"proc": proc, "service": service})

    # 等待子服务就绪（最长 timeout 秒，就绪或失败都会提前跳出）
    # 轮询间隔 0.5s：既保证响应及时，又避免空转烧 CPU。
    # while 条件双保险：pending 清空（全部就绪/失败）或超时都退出。
    deadline = time.time() + timeout
    while pending and time.time() < deadline:
        # 遍历副本：循环体内会从 pending 移除元素，直接遍历会跳项。
        # list(pending) 每轮重新拷贝，开销可忽略（元素最多 2 个）。
        for item in list(pending):
            proc, service = item["proc"], item["service"]
            if _port_open(SELF_HOST, service["port"]):
                # 就绪判定：端口开始监听即视为可用（比子进程打印更客观）。
                print(f"[total_service] [OK] {service['name']} 就绪 → "
                      f"http://{SELF_HOST}:{service['port']}")
                pending.remove(item)
            elif proc.poll() is not None:
                # poll() 非 None 说明子进程已退出：端口不会再监听，提前判失败。
                # 退出码透出给用户：非 0 多为脚本内异常（依赖缺失/端口冲突）。
                print(f"[total_service] [FAIL] {service['name']} 启动失败"
                      f"（退出码 {proc.returncode}），请查看上方日志")
                pending.remove(item)
        if pending:
            # 尚有未就绪项：小睡一会再轮询，避免热循环。
            time.sleep(0.5)

    for item in pending:
        # 超时兜底：不判失败也不判成功，留给用户观察后续日志。
        # 常见原因：子服务冷启动慢（加载模型/连数据库）。
        print(f"[total_service] [WAIT] {item['service']['name']} 暂未就绪"
              f"（端口 {item['service']['port']} 未监听），可能仍在启动中")


def stop_child_services() -> None:
    """回收由本服务拉起的子进程（退出时自动调用，重复调用安全）

    停止策略：先 SIGTERM 优雅退出，最多等 3 秒，再 SIGKILL 强杀。
    只回收 _children 中登记的进程，外部自行启动的服务不受影响。
    """
    # 先筛出仍存活的子进程：已退出的无需再 terminate。
    # alive 为空时后续循环都是空操作，天然幂等。
    # poll() 返回 None 表示进程仍在运行，非 None 是退出码。
    alive = [item for item in _children if item["proc"].poll() is None]
    for item in alive:
        print(f"[total_service] 正在停止 {item['service']['name']} ...")
        # terminate 发 SIGTERM：给子进程优雅收尾的机会（flush 日志、释放端口）。
        item["proc"].terminate()
    # 给子进程一点优雅退出的时间，超时则强制结束
    # 最多等 10 * 0.3 = 3 秒，之后升级为 kill（SIGKILL，不可拦截）。
    # all(...) 快捷判定：全部退出立即跳出循环，不白等。
    for _ in range(10):
        if all(item["proc"].poll() is not None for item in alive):
            break
        time.sleep(0.3)
    for item in alive:
        # 还没退出的进程强杀，确保端口一定被释放。
        if item["proc"].poll() is None:
            item["proc"].kill()
    # 清空记录：让重复调用 stop 变成无操作（幂等）。
    # 注意必须在 kill 之后清空：保证回收流程完整走完。
    _children.clear()


# 兜底：无论以何种方式退出（含异常），都回收子进程
# atexit 在解释器正常退出时触发；uvicorn finally 是主路径，此处是保险。
# 注意：atexit 不覆盖 kill -9 等强杀场景，进程组管理需 systemd 等。
atexit.register(stop_child_services)


# --------------------------
# 页面服务（本服务本体）
# --------------------------
app = FastAPI(title="total service", description="小度小度统一工作台入口！")

# 跨域：页面与业务服务不同源，统一放开
# allow_origins=["*"]：内网工具页可接受全放行；公网部署应收紧来源列表。
# 方法与头全放开：业务接口需携带任意自定义头（如鉴权字段）。
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 禁用缓存：避免前端改动后浏览器仍加载旧页面
# 三个响应头组合覆盖各浏览器的历史行为（HTTP/1.0 与 1.1）。
# 只作用于本服务返回的页面；业务服务的响应缓存由其自行管理。
# 定义为模块级常量：index 与 index_html 两个路由共用。
NO_CACHE_HEADERS = {
    "Cache-Control": "no-cache, no-store, must-revalidate",
    "Pragma": "no-cache",
    "Expires": "0",
}


@app.get("/")
async def index():
    """返回统一工作台页面 index.html（智能问答 + 知识库导入 双视图）

    页面内的 fetch 请求直连 8001/8002 业务服务，本服务只负责静态文件。
    异步声明仅为 FastAPI 惯例：FileResponse 内部自行处理 IO。
    404 响应体携带期望路径：部署不完整时可直接定位问题。
    """
    index_html_path = PAGE_DIR / "index.html"
    # 页面文件缺失（部署不完整）直接 404，并带出期望路径方便排查。
    if not index_html_path.exists():
        raise HTTPException(status_code=404, detail=f"没有查询到页面，地址为：{index_html_path}！")
    # FileResponse 走静态文件流；附 NO_CACHE_HEADERS 保证改动即时生效。
    # 这里不做模板渲染：index.html 是纯静态页，数据全靠前端 fetch。
    return FileResponse(index_html_path, headers=NO_CACHE_HEADERS)


@app.get("/index.html")
async def index_html():
    """兼容显式访问 /index.html 的方式"""
    # 复用 / 的处理逻辑：同一份文件、同一组响应头。
    return await index()


@app.get("/health")
async def health():
    """服务健康检查

    页面服务自身恒返回 ok：不探测子服务，避免互相依赖导致误报。
    子服务的健康检查由各自 /health 端点提供。
    """
    # 页面服务自身恒返回 ok：不探测子服务，避免互相依赖导致误报。
    return {"ok": True}


if __name__ == "__main__":
    # 日志重定向到文件时默认是块缓冲，改为按行刷出，保证启动过程可见
    # hasattr 判断兼容性：个别环境（如某些 IDE 重定向）可能没有 reconfigure。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(line_buffering=True)

    # 默认随本服务一起拉起后端业务服务；加 --no-children 可跳过
    # --no-children 场景：子服务由独立终端/守护进程管理，避免端口冲突。
    if "--no-children" not in sys.argv:
        start_child_services()
    print("\n" + "=" * 62)
    # 横幅打印三个入口地址：页面服务 + 两个子服务直连地址。
    print(f"  小度小度 · 统一工作台已启动：http://{SELF_HOST}:{SELF_PORT}/")
    # 子服务页面（chat.html / import.html）由各自服务直接提供。
    print(f"  问答页(chat.html)：http://{SELF_HOST}:{QUERY_PORT}/")
    print(f"  导入页(import.html)：http://{SELF_HOST}:{IMPORT_PORT}/")
    # 提示退出方式：Ctrl+C 会连带回收子服务。
    # SIGINT 会先中断 uvicorn.run，再走 finally 清理子进程。
    print("  按 Ctrl+C 退出（将同时停止它拉起的子服务）")
    print("=" * 62 + "\n")

    try:
        # 阻塞在本服务的 ASGI 运行上；Ctrl+C 会抛 KeyboardInterrupt 走 finally。
        # host 绑定 127.0.0.1：本服务是本地工作台入口，不对外网开放。
        uvicorn.run(app, host=SELF_HOST, port=SELF_PORT)
    finally:
        # 无论正常退出还是被中断，都兜底回收子进程（与 atexit 双保险）。
        stop_child_services()
