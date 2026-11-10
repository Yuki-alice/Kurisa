# kurisa-webview-host

用**系统自带**的 WebView 打开一个真实浏览器窗口，让用户完成 Cloudflare 验证或站点登录，
再把 Cookie（**含 HttpOnly**）交回主进程。

不打包浏览器内核：Linux 链接 `webkit2gtk-4.1`，Windows 用系统 WebView2，macOS 用系统 WKWebView。
随包体积增加为 **几十 KB 到几百 KB**，而不是 JCEF 的 150–200 MB。

## 为什么需要它

`cf_clearance` / `__cf_bm` 是 HttpOnly，页面里 `document.cookie` 永远读不到。
所有基于 `webview/webview` 的现成 Java/Kotlin 封装（avaje-webview、webview_java、webviewko、
weblite webviewjar）都没有 Cookie API——它们的上游 `api.h` 里连 `cookie` 这个词都没有。
所以必须自己调原生 `CookieManager`。

## 进程模型

```
Compose Desktop (JVM)
   └─ spawn ──> kurisa-webview-host           独立进程
                  ├─ Linux : WebKitGTK 4.1
                  ├─ Windows: WebView2 (待实现)
                  └─ macOS : WKWebView (待实现)
   stdin/stdout  ── NDJSON ──
```

- **独立进程**：macOS 的 WKWebView 必须在 AppKit 主线程，和 AWT 抢主线程必死；Linux 的
  GTK 主循环与 AWT 同进程共存也不稳。放子进程一举绕开，顺带隔离 WebView 崩溃。
- **stdio 而不是本地端口**：Windows 上监听端口会弹防火墙授权框。

## 协议（NDJSON）

命令（stdin）：

| cmd | 参数 | 说明 |
|---|---|---|
| `open` | `url`, `width?`, `height?` | 显示窗口并导航 |
| `cookies` | `url?` | 读原生 CookieManager（含 HttpOnly），缺省用当前页 |
| `eval` | `js` | 在页面执行 JS，返回字符串 |
| `ua` | — | 当前 UA |
| `snapshot` | `path` | 截当前可视区为 PNG |
| `close` | — | 隐藏窗口 |
| `quit` | — | 退出 |

事件/响应（stdout）：

```jsonl
{"ev":"ready","ua":"Mozilla/5.0 ...","engine":"2.52.6"}
{"ev":"loaded","url":"https://...","title":"..."}
{"ev":"load-failed","url":"...","error":"..."}
{"id":1,"ok":"opened"}
{"id":2,"ok":[{"name":"cf_clearance","value":"...","domain":".x.org","httpOnly":true,"secure":true,"session":false,"expiresAt":1760000000000}]}
```

非 JSON 行（GTK / EGL 警告）由客户端忽略。

## 构建与运行（Linux）

```bash
sudo apt install libwebkit2gtk-4.1-dev   # 发行版依赖，运行时同样需要
./build-linux.sh                          # 产物 build/linux/kurisa-webview-host

# 端到端（含 HttpOnly 断言，无需联网）
cd ../../ && xvfb-run -a ./gradlew :shared:webviewHostSpike
# 真实站点
xvfb-run -a ./gradlew :shared:webviewHostSpike --args="https://mangadex.org/"
```

> 容器 / CI 里没有 user namespace，WebKit 的 WebProcess 起不来，需要
> `KURISA_WEBVIEW_NO_SANDBOX=1`（Gradle 任务已内置）。发行版环境请保持沙箱开启。

profile 数据（Cookie / localStorage / cache）落在
`$XDG_DATA_HOME/kurisa/webview/<profile>/`，可用 `KURISA_WEBVIEW_DATA` 覆盖。

## 现状（2026-11，Linux）

- WebKitGTK **2.52.6**，可执行文件 **33 KB**，只动态链接系统库。
- 端到端 10 项断言通过（含「JS 看不到 HttpOnly / 原生能读到 / 自动配对 UA」）。
- 真实站点：mangadex.org 的 `edge/challenge` 可通过并渲染完整首页，拿到 HttpOnly 的
  `MD-Edge-ID`（64 字节）。
- 未实现：代理注入、下载/打印、Windows/macOS 实现、多窗口。

细节与踩坑见 `docs/m0-webview-host-linux.md`。
