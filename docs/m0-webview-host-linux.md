# M0 · 系统 WebView helper（Linux）验证报告

> 结论先说：**Linux 上可行，且代价极小**——33 KB 的可执行文件，调用系统 WebKitGTK 2.52.6，
> 能打开真实网站、通过 mangadex 的 bot challenge、并用原生 CookieManager 读到
> HttpOnly Cookie。这条路径推翻了 ADR-009「桌面自行封装三平台 WebView」的否决理由，
> 新决策见 ADR-010。

## 1. 目标

桌面端要解决的不是「渲染 HTML」，而是两件事：

1. 用户在桌面完成 **Cloudflare 过盾 / 站点登录**；
2. 把结果（Cookie，尤其是 `cf_clearance`）安全地交回主进程，供 Ktor 请求注入。

`cf_clearance` / `__cf_bm` 是 HttpOnly，`document.cookie` 读不到。这一条直接排除了
所有基于 `webview/webview` 的现成封装（其上游 `core/include/webview/api.h` 无任何 Cookie API），
必须自己调原生 `CookieManager`——这正是 Venera 的 `desktop_webview_window` 在做的事。

## 2. 实现

| 项 | 内容 |
|---|---|
| 源码 | `m0-spike/desktop-webview-host/src/host_gtk.c`（约 500 行，含注释） |
| 内核 | 系统 `webkit2gtk-4.1`，`pkg-config` 链接，**不随包分发** |
| 产物 | `build/linux/kurisa-webview-host`，**33 KB** |
| 通信 | stdin/stdout NDJSON；**不用本地端口**（Windows 会弹防火墙） |
| 进程 | 独立子进程，主进程崩溃/WebView 崩溃互不影响 |
| Cookie 持久化 | `MozillaCookieJar` → SQLite，落在 profile 目录，跨重启复用 `cf_clearance` |
| UA | 从 `WebKitSettings` 取，随 `ready` 事件回传，与 Cookie 一起配对存储 |

与 Venera 的对应关系（可直接对照阅读）：

| Venera (`desktop_webview_window`) | 本项目 |
|---|---|
| `linux/webview_window.cc` `webkit_web_view_new()` + `webkit_cookie_manager_get_cookies` | `host_gtk.c` 同名 API |
| `windows/web_view.cc` `ICoreWebView2CookieManager::GetCookies` | 待实现（协议不变） |
| `macos/.../WebviewWindowController.swift` `WKHTTPCookieStore` | 待实现（协议不变） |
| Flutter MethodChannel | NDJSON over stdio |

## 3. 实测结果

环境：Ubuntu 24.04 / WebKitGTK 2.52.6 / Xvfb（`1280x900x24`）/ 无 GPU（软件渲染）。

### 3.1 端到端断言（`./gradlew :shared:webviewHostSpike`）

```
host ready: engine=2.52.6
ua: Mozilla/5.0 (X11; Ubuntu; Linux x86_64) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/60.5 Safari/605.1.15
PASS  host 启动
PASS  引擎是系统 WebKitGTK
PASS  加载本地页面
PASS  JS 能写普通 cookie
PASS  JS 看不到 HttpOnly cookie
PASS  原生读到 HttpOnly cookie
PASS  Cookie 自动配对 UA
PASS  配对 UA 非空
PASS  能拼出 Cookie 请求头
PASS  快照落盘
PASS  真实站点可加载
PASS  真实站点拿到 HttpOnly cookie
PASS=12 FAIL=0
```

关键一项是第 5、6 条同时成立：同一份 Cookie 集合里，
`document.cookie` 只有 `js_visible=hello`，而原生接口额外返回 `cf_fake`（`httpOnly=true`）。

### 3.2 真实站点

`https://mangadex.org/`：

- 首次进入被重定向到 `https://mangadex.org/edge/challenge?src=...`，标题
  “MangaDex - Browser verification…”，**挑战页在 WebKitGTK 里能自行完成**；
- 约 10–20 秒后落到首页，标题 “MangaDex - The Home of Comics and Manga”，
  侧边栏 / 封面 / 列表全部渲染（见 `shared/build/screenshots/webview-host-real-site.png`）；
- Cookie：`MD-Edge-ID`（**HttpOnly**，64 字节，`.mangadex.org`）+ GA 系列；
  请求头可拼出 `cf_fake=...; js_visible=...`。
- 注：mangadex 已从 Cloudflare 迁到自研 edge challenge，因此没有 `cf_clearance`；
  但 HttpOnly 的读取路径与 `cf_clearance` 完全一致。

### 3.3 体积

| 项 | 大小 |
|---|---|
| `kurisa-webview-host` | **33 KB** |
| 运行时依赖 | 系统 `libwebkit2gtk-4.1`（发行版包，不计入安装包） |
| 对比 JCEF | `libcef.dll` + 资源约 150–200 MB |

## 4. 踩坑记录（都已在代码里处理）

1. **GIOChannel 读 stdin 会饿死 WebKit 的 IPC**。
   最初用 `g_io_add_watch` 在主循环里读命令，结果 `load-changed` 一次都不触发、
   `g_timeout` 也不触发，进程 CPU 却是 0%。改成「独立线程 `getline` + `g_idle_add` 投递」
   后一切正常。这类问题在真实工程里会表现为「窗口白屏但没报错」，很难查。
2. **容器里沙箱起不来**。WebKitGTK 2.40+ 已废弃 `WEBKIT_FORCE_SANDBOX`，必须用
   `WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1`；否则 WebProcess 直接不启动，页面永远空白。
3. **`SameSite=None` 不带 `Secure` 会被丢弃**。测试用的 `Set-Cookie` 最初写成
   `HttpOnly; SameSite=None`，Cookie 根本没落库，误判成「读不到 HttpOnly」。
4. **`webkit_web_view_get_snapshot` 在 2.52 变成异步**，且 `webkit_website_data_manager_set_base_data_directory`
   已不存在——必须在构造 `WebKitWebsiteDataManager` 时传 `base-data-directory`。
5. **`load-changed: finished` 时 `get_title()` 可能还是空**，标题晚一拍；客户端在标题为空时回查
   `document.title` 兜底。

## 5. 与 ADR-009 的关系

ADR-009 把「桌面自行封装 WebKitGTK / WebView2 / WKWebView」列为否决项，理由是
「三套 JNI 封装、三份维护成本，收益与 KCEF 相同」。M0 实测后，这个理由的两半都不成立：

- **收益不同**：KCEF 是 150–200 MB 内核，系统 WebView 是 33 KB；
- **成本可控**：三平台共用一套 NDJSON 协议，差异被压缩在 host 内部（Linux 约 500 行），
  客户端只有 `WebviewHost.kt` 一个类。

因此新增 **ADR-010**，把桌面 WebView 路径改为「系统内核 + 独立 helper 进程」，
并把 ADR-009 的 Cookie 导入保留为 **能力缺失时的降级路径**（发行版没装 webkit2gtk、
Windows 没装 WebView2 Runtime 时）。

## 6. 未完成 / 风险

| 项 | 状态 | 说明 |
|---|---|---|
| Windows (WebView2) | 未实现 | 协议不变，需 COM + 消息泵，约 500 行；需 Windows 机器或 CI 验证 |
| macOS (WKWebView) | 未实现 | 需在 helper 内自建 NSApplication；主应用侧已由进程隔离规避主线程冲突 |
| 代理注入 | 未实现 | WebKitGTK 走 `webkit_web_context_set_network_proxy_settings`；WebView2 走 `--proxy-server` |
| 发行版依赖 | 运行时探测 | 缺 `webkit2gtk-4.1` 时应提示并回落到 Cookie 导入，不能直接崩 |
| 打包 | 未做 | helper 需按平台放资源目录并在首次启动解压（要可执行权限）；macOS 需签名/公证 |
| CI | 未做 | 建议三平台各一个 job，跑同一套断言 |

另：`MD-Edge-ID` 这类挑战 Cookie 与 UA、IP 都可能绑定，所以 `WebviewHost.cookies()`
返回的每条 `StoredCookie` 都带 `pairedUserAgent`；换 UA 复用会重新触发挑战。
