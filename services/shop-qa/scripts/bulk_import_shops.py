"""一次性批量导入 data/shop-markdown/shop-<id>.md 到知识库（走本地 API）。"""

import re
import sys
import time
from pathlib import Path
from urllib import request

API = "http://127.0.0.1:8003"
ROOT = Path(__file__).resolve().parents[1]
MD_DIR = ROOT / "data" / "shop-markdown"


def post_file(path: Path, shop_id: int, owner_id: int = 1) -> str:
    boundary = "----wbimport"
    body = b""
    for name, value in (("shop_id", str(shop_id)), ("owner_id", str(owner_id))):
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n").encode()
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
             f"filename=\"{path.name}\"\r\nContent-Type: text/markdown\r\n\r\n").encode()
    body += path.read_bytes() + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    req = request.Request(f"{API}/documents", data=body, method="POST",
                          headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with request.urlopen(req, timeout=60) as resp:
        return resp.read().decode()


def get_status(doc_id: str) -> dict:
    with request.urlopen(f"{API}/documents/{doc_id}?owner_id=1", timeout=20) as resp:
        import json
        return json.loads(resp.read().decode())


def main() -> None:
    files = sorted(MD_DIR.glob("shop-*.md"), key=lambda p: int(re.findall(r"\d+", p.stem)[0]))
    only = [int(x) for x in sys.argv[1:]] if len(sys.argv) > 1 else None
    if only:
        files = [p for p in files if int(re.findall(r"\d+", p.stem)[0]) in only]
    print(f"待导入 {len(files)} 个文件")
    pending = []
    for path in files:
        shop_id = int(re.findall(r"\d+", path.stem)[0])
        try:
            doc_id = post_file(path, shop_id)
            print(f"  + {path.name} shop_id={shop_id} -> {doc_id}")
            pending.append(doc_id)
        except Exception as exc:  # noqa: BLE001
            print(f"  ! {path.name} 上传失败: {exc}")
        time.sleep(0.05)
    # 等待后台处理完成
    deadline = time.time() + 600
    remaining = set(pending)
    while remaining and time.time() < deadline:
        for doc_id in list(remaining):
            try:
                st = get_status(doc_id)
            except Exception:  # noqa: BLE001
                continue
            if st.get("status") in ("completed", "failed"):
                remaining.discard(doc_id)
                if st.get("status") == "failed":
                    print(f"  ! {st.get('filename')} 处理失败: {st.get('error')}")
        if remaining:
            time.sleep(2)
    print(f"完成，未结束: {len(remaining)}")


if __name__ == "__main__":
    main()
