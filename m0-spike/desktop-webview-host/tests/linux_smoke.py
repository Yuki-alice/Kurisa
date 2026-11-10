#!/usr/bin/env python3
"""
Linux WebView Host 冒烟测试。

验证四件事：
  1. 能起窗口、加载页面（WebKitGTK 真实渲染）
  2. JS 能执行（eval）
  3. HttpOnly Cookie 在 document.cookie 里看不到，但原生 CookieManager 能读到
  4. 协议（stdin/stdout NDJSON）可用，含快照

用法： xvfb-run -a python3 tests/linux_smoke.py [--real-site]
"""

import json
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

HOST_BIN = Path(__file__).resolve().parents[1] / "build/linux/kurisa-webview-host"
OUT_DIR = Path(__file__).resolve().parents[1] / "build/linux/test-output"

PAGE = b"""<!doctype html>
<html><head><meta charset="utf-8"><title>Kurisa Host Smoke</title>
<style>body{font:16px system-ui;background:#111;color:#eee;padding:40px}</style></head>
<body><h1>webview host alive</h1><p id="x">waiting</p>
<script>
  document.cookie = "js_visible=hello; path=/; SameSite=Lax";
  document.getElementById('x').textContent =
      "document.cookie=" + document.cookie;
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        # 关键：HttpOnly + Secure 之外的属性齐全，模拟 cf_clearance 的形态
        self.send_header(
            "Set-Cookie", "cf_fake=OPAQUE378CHARS; Path=/; HttpOnly"
        )
        self.send_header("Content-Length", str(len(PAGE)))
        self.end_headers()
        self.wfile.write(PAGE)

    def log_message(self, *a):
        pass


class Host:
    def __init__(self, env_extra=None):
        env = dict(os.environ)
        env.setdefault("KURISA_WEBVIEW_DATA", "/tmp/kurisa-webview-test")
        env.setdefault("GDK_BACKEND", "x11")
        if env_extra:
            env.update(env_extra)
        self.p = subprocess.Popen(
            [str(HOST_BIN)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
            env=env,
        )
        self.events = []
        self.responses = {}
        self._id = 0
        self._t = threading.Thread(target=self._reader, daemon=True)
        self._t.start()

    def _reader(self):
        for line in self.p.stdout:
            line = line.strip()
            if not line.startswith("{"):
                self.events.append({"ev": "noise", "raw": line})
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                self.events.append({"ev": "noise", "raw": line})
                continue
            if "id" in msg:
                self.responses[msg["id"]] = msg
            else:
                self.events.append(msg)

    def send(self, **kwargs):
        self._id += 1
        kwargs["id"] = self._id
        self.p.stdin.write(json.dumps(kwargs) + "\n")
        self.p.stdin.flush()
        return self._id

    def wait(self, rid, timeout=30):
        end = time.time() + timeout
        while time.time() < end:
            if rid in self.responses:
                return self.responses[rid]
            time.sleep(0.05)
        raise TimeoutError(f"no response for id={rid}")

    def wait_event(self, name, timeout=30):
        end = time.time() + timeout
        while time.time() < end:
            for e in self.events:
                if e.get("ev") == name:
                    return e
            time.sleep(0.05)
        raise TimeoutError(f"no event {name}")

    def close(self):
        try:
            rid = self.send(cmd="quit")
            self.wait(rid, 5)
        except Exception:
            pass
        try:
            self.p.wait(5)
        except subprocess.TimeoutExpired:
            self.p.kill()


def main():
    real_site = "--real-site" in sys.argv
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    srv = HTTPServer(("127.0.0.1", 0), Handler)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    host = Host()
    checks = []

    ready = host.wait_event("ready", 20)
    print("[ready]", json.dumps(ready, ensure_ascii=False))
    checks.append(("启动并握手", True))
    print("[ua]", ready.get("ua"))
    print("[engine]", ready.get("engine"))

    host.send(cmd="open", url=f"http://127.0.0.1:{port}/", width=900, height=600)
    loaded = host.wait_event("loaded", 30)
    print("[loaded]", json.dumps(loaded, ensure_ascii=False))

    time.sleep(0.5)
    rid = host.send(cmd="eval", js="document.getElementById('x').textContent")
    r = host.wait(rid)
    print("[eval]", r)
    checks.append(("JS 可执行", "document.cookie" in str(r.get("ok", ""))))

    rid = host.send(cmd="eval", js="document.cookie")
    r = host.wait(rid)
    cookie_from_js = str(r.get("ok", ""))
    print("[document.cookie]", cookie_from_js)
    checks.append(("JS 看得到普通 cookie", "js_visible=hello" in cookie_from_js))
    checks.append(("JS 看不到 HttpOnly cookie", "cf_fake" not in cookie_from_js))

    rid = host.send(cmd="cookies", url=f"http://127.0.0.1:{port}/")
    r = host.wait(rid)
    print("[cookies]", json.dumps(r, ensure_ascii=False))
    cookies = {c["name"]: c for c in r.get("ok", [])}
    checks.append(("原生读到 HttpOnly cookie", "cf_fake" in cookies))
    checks.append(
        ("httpOnly 标记正确", cookies.get("cf_fake", {}).get("httpOnly") is True)
    )

    rid = host.send(cmd="ua")
    r = host.wait(rid)
    print("[ua-cmd]", r)
    checks.append(("UA 可取", bool(r.get("ok"))))

    shot = OUT_DIR / "host-smoke.png"
    rid = host.send(cmd="snapshot", path=str(shot))
    r = host.wait(rid, 30)
    print("[snapshot]", r)
    checks.append(("快照落盘", shot.exists() and shot.stat().st_size > 5000))

    if real_site:
        # 真实站点：mangadex 走 Cloudflare，用来观察 WebKitGTK 的过盾表现
        host.send(cmd="open", url="https://mangadex.org/", width=1100, height=800)
        try:
            host.wait_event("loaded", 60)
        except TimeoutError:
            print("[warn] 真实站点加载超时")
        time.sleep(6)
        rid = host.send(cmd="eval", js="document.title")
        title = host.wait(rid, 20).get("ok", "")
        rid = host.send(cmd="eval", js="location.href")
        href = host.wait(rid, 20).get("ok", "")
        print("[real]", title, href)
        rid = host.send(cmd="cookies", url="https://mangadex.org/")
        rc = host.wait(rid, 20)
        names = [c["name"] for c in rc.get("ok", [])]
        print("[real cookies]", names)
        shot2 = OUT_DIR / "host-real-site.png"
        host.send(cmd="snapshot", path=str(shot2))
        time.sleep(2)
        checks.append(("真实站点可加载", bool(title)))

    host.close()
    srv.shutdown()

    print("\n==== 结果 ====")
    ok = True
    for name, passed in checks:
        print(("PASS  " if passed else "FAIL  ") + name)
        ok = ok and passed
    print("OVERALL:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
