#!/usr/bin/env python3
"""
macOS WebView Host 冒烟测试（与 linux_smoke.py 同断言）。
用法： python3 tests/mac_smoke.py [--bin <path>] [--real-site]
"""
import json
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

BIN_ARG = None
args = sys.argv[1:]
if "--bin" in args:
    BIN_ARG = Path(args[args.index("--bin") + 1])
REAL_SITE = "--real-site" in args
HOST_BIN = BIN_ARG or (Path(__file__).resolve().parents[1] / "build/macos/kurisa-webview-host")
OUT_DIR = Path(__file__).resolve().parents[1] / "build/macos/test-output"

PAGE = b"""<!doctype html>
<html><head><meta charset="utf-8"><title>Kurisa Mac Smoke</title></head>
<body><h1>mac webview works</h1><p id="x">waiting</p>
<script>
document.cookie = "js_visible=hello; path=/; SameSite=Lax";
document.getElementById('x').textContent = "document.cookie=" + document.cookie;
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Set-Cookie", "cf_fake=OPAQUE378CHARS; Path=/; HttpOnly")
        self.send_header("Content-Length", str(len(PAGE)))
        self.end_headers()
        self.wfile.write(PAGE)

    def log_message(self, *a):
        pass


class Host:
    def __init__(self):
        env = dict(os.environ)
        env.setdefault("KURISA_WEBVIEW_DATA", "/tmp/kurisa-webview-mac")
        self.p = subprocess.Popen(
            [str(HOST_BIN)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, bufsize=1, env=env,
        )
        self.events = []
        self.responses = {}
        self._id = 0
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        for line in self.p.stdout:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            if "id" in msg:
                self.responses[msg["id"]] = msg
            else:
                self.events.append(msg)

    def send(self, **kw):
        self._id += 1
        kw["id"] = self._id
        self.p.stdin.write(json.dumps(kw) + "\n")
        self.p.stdin.flush()
        return self._id

    def wait(self, rid, timeout=30):
        end = time.time() + timeout
        while time.time() < end:
            if rid in self.responses:
                return self.responses[rid]
            time.sleep(0.05)
        raise TimeoutError(f"no response id={rid}")

    def wait_ev(self, name, timeout=20):
        end = time.time() + timeout
        while time.time() < end:
            for e in self.events:
                if e.get("ev") == name:
                    return e
            time.sleep(0.05)
        raise TimeoutError(f"no event {name}")

    def close(self):
        try:
            self.p.stdin.write(json.dumps({"cmd": "quit", "id": 9999}) + "\n")
            self.p.stdin.flush()
        except Exception:
            pass
        try:
            self.p.wait(5)
        except subprocess.TimeoutExpired:
            self.p.kill()


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    srv = HTTPServer(("127.0.0.1", 0), Handler)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    host = Host()
    checks = []
    try:
        ready = host.wait_ev("ready", 20)
        print("[ready]", ready)
        checks.append(("ready proto=1", ready.get("proto") == 1))
        checks.append(("ua non-empty", bool(ready.get("ua"))))
        host.send(cmd="open", url=f"http://127.0.0.1:{port}/", width=900, height=600)
        loaded = host.wait_ev("loaded", 30)
        print("[loaded]", loaded)
        time.sleep(1.0)
        r = host.wait(host.send(cmd="eval", js="document.getElementById('x').textContent"))
        print("[eval]", r)
        checks.append(("js exec", "document.cookie" in str(r.get("ok", ""))))
        r = host.wait(host.send(cmd="eval", js="document.cookie"))
        js = str(r.get("ok", ""))
        print("[cookie]", js)
        checks.append(("js sees normal", "js_visible=hello" in js))
        checks.append(("js hides HttpOnly", "cf_fake" not in js))
        r = host.wait(host.send(cmd="cookies", url=f"http://127.0.0.1:{port}/"))
        print("[cookies]", json.dumps(r)[:400])
        names = [c["name"] for c in r.get("ok", [])]
        checks.append(("native HttpOnly", "cf_fake" in names))
        r = host.wait(host.send(cmd="ua"))
        checks.append(("ua cmd", bool(r.get("ok"))))
        shot = OUT_DIR / "mac-host-smoke.png"
        r = host.wait(host.send(cmd="snapshot", path=str(shot)), 30)
        print("[shot]", r)
        checks.append(("snapshot", shot.exists() and shot.stat().st_size > 5000))
    finally:
        host.close()
        srv.shutdown()
    print("\n==== RESULT ====")
    ok = True
    for name, passed in checks:
        print(("PASS  " if passed else "FAIL  ") + name)
        ok = ok and passed
    print("OVERALL:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
