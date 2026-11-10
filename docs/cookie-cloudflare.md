# Cloudflare 验证与登录 Cookie 处理

> 本文是 ADR-009（桌面端不内嵌浏览器）的落地细节。所有结论均来自 M0 实测，
> 可复现命令见文末。

## 0. 一句话结论

**Cloudflare 的三种拦截必须分开处理，Cookie 必须与产生它的 User-Agent 一起存。**
把「过盾」当成「登录」，或者把 `cf_clearance` 当成普通 Cookie 搬运，是最常见的两个失败原因。

---

## 1. 先分清三类拦截

这三种情况表现都是 403，但处置方式完全相反。

| 类型 | 判定信号 | 能否靠 Cookie 解决 | 处置 |
|---|---|---|---|
| **托管挑战** | 响应头 `cf-mitigated: challenge`；正文含 `Just a moment` / `Checking your browser` / `challenge-platform` | 能，但必须先有一次真实浏览器通过 | 让用户在真浏览器里过一次，取回 `cf_clearance` |
| **WAF 阻断** | 正文含 `Sorry, you have been blocked`；或 `error code: 1020 / 1012 / 1009` | **不能**。这是按来源 IP 信誉直接拒绝 | 换网络；重复请求会加重封禁 |
| **限流** | `429` | 不适用 | 退避重试，**不要**重置 Cookie |

判定优先级按上表从上到下：先排 WAF 阻断，再判挑战。因为阻断页里也可能出现 `challenge-platform` 字样，误判成「可过盾」会让用户白折腾。

`cf-mitigated` 是比正文匹配可靠得多的信号，实测中带这个头的响应一定是挑战。正文匹配只作兜底，因为各站挑战页模板差异很大。

### 实测样本

| 站点 | 状态 | 实际响应 | 分类结果 |
|---|---|---|---|
| `nopecha.com/demo/cloudflare` | 403 | `cf-mitigated: challenge` + `Just a moment` | `MANAGED_CHALLENGE` |
| `mycomic.com`（curl 无 Cookie） | 403 | `Sorry, you have been blocked` | `WAF_BLOCK` |
| `mycomic.com`（App 带 Cookie） | 403 | 同一 IP、加了 Cookie 后变成挑战 | `MANAGED_CHALLENGE` |
| `nowsecure.nl` | 200 | 直接通过并下发 `cf_clearance` | `NONE` |

第三行值得注意：**Cloudflare 的判定是动态的**，同一个站点、同一个 IP，请求形态不同会得到不同类型。所以分类必须在**每次响应**上做，不能缓存站点级的结论。

---

## 2. Cookie 从哪来

### Android / iOS：WebView

这是过盾成功率最高的路径，因为 WebView 就是真实浏览器内核。流程：

```
用户点「登录/验证」
  → 打开 WebView，加载源声明的 account.loginWithWebview.url
  → 轮询源声明的 checkStatus(url, title) 判断是否通过
  → 通过后：从系统 CookieManager 导出该域全部 Cookie
  → 写入 CookieStore（source = WEBVIEW，记录当前 WebView 的 UA）
  → 关闭 WebView
```

`checkStatus` 是源自己提供的判据，必须用它而不是猜。例如 mycomic 的判断是「标题不含 moment / Cloudflare / challenge，且 URL 在 mycomic.com 下」。

**必须记录 WebView 的 UA**：它就是赚到 `cf_clearance` 的那个浏览器身份，后续 HTTP 请求要沿用它。Android 用 `WebSettings.userAgentString`，iOS 用 `WKWebView.customUserAgent`（为空时取系统默认）。

### Desktop：粘贴导入（主）/ loopback 回调（辅）

- **粘贴导入**：读取源声明的 `loginWithCookies.fields` 生成结构化表单；源没声明时给一个「整串粘贴」输入框。
- **loopback 回调**：仅适用于 OAuth 型源（RFC 8252）。**不能**替代 Cookie 场景——deep link 回传的是 code/token，拿不到 `cf_clearance`。

粘贴内容是五花八门的，解析器要能吞下这几种：

| 用户从哪里复制 | 实际格式 | 解析结果 |
|---|---|---|
| Network 面板的 Cookie 请求头 | `a=1; b=2` | `cookie-header` |
| Application → Cookies 表格（整块选中） | `name\tvalue\t.domain\t/`（制表符分隔，多行） | `tab-separated` |
| 手写 / 笔记 | 每行一个 `name=value` | `multi-line` |
| 浏览器扩展导出 | `[{"name":..,"value":..}]` | `json` |
| 带空格、多余分号 | `  a = 1 ; ;  ` | 容错后仍能解析 |

解析时要过滤非法名字（只允许 `[A-Za-z0-9_-.#$%]`），并对同名去重取后者。

---

## 3. 存什么：`pairedUserAgent` 是关键

```
StoredCookie
├─ domain / path          域与路径（决定发不发）
├─ name / value           本体
├─ expiresAtEpochMs       服务端给的过期时间（可能没有）
├─ obtainedAtEpochMs      本地获取时间（用于时间兜底）
├─ source                 WEBVIEW / MANUAL_IMPORT / NETWORK
├─ pairedUserAgent        ★ 产生这条 Cookie 的浏览器 UA
└─ invalidReason          非空表示已失效，不再参与注入
```

**为什么必须有 `pairedUserAgent`**：`cf_clearance` 与赚到它的浏览器身份绑定。换一个 UA 发同一份 Cookie，Cloudflare 会判定为重放，直接重新挑战——用户会觉得「明明刚验证过，怎么又要验证」。

绑定关系（社区一致观察，非官方文档）：

| 变更项 | Cookie 是否失效 |
|---|---|
| User-Agent | **失效** |
| 出口 IP | **失效** |
| TLS 指纹 | 通常也失效（握手阶段就能识别） |
| 发送的 hostname | 失效（每个站独立） |
| 时间 | 到期失效，默认 30 分钟（Challenge Passage，站点可配） |
| 多带无关 Cookie | 不影响 |

只有 UA 和 IP 是宿主能控制的，所以这两项要尽量对齐；TLS 指纹在 Ktor 上做不到，见第 6 节。

---

## 4. 发请求时的顺序（不能颠倒）

```
1. 取该 URL 生效的 Cookie（未过期、未失效、域与路径匹配）
2. 若其中存在带 pairedUserAgent 的 clearance → 用配对 UA 覆盖本次 User-Agent
3. 把 Cookie 拼成 `k=v; k2=v2` 注入 Cookie 头
4. 拿到响应后立刻分类（CloudflareGuard）
5. 若判定为 MANAGED_CHALLENGE → 只把 clearance 类 Cookie 标记失效，触发重新验证
```

第 5 步的范围很重要：**过盾失败不等于登录失效**。只作废 `cf_clearance` / `__cf_bm`，登录 session 必须保留，否则用户每过一次盾就要重新登录一次。

实现对应 `CookieStore.markInvalid(domain, reason, only = CF_COOKIES)`。

---

## 5. 失效检测与恢复

三条线并行：

| 手段 | 触发 | 说明 |
|---|---|---|
| 被动 | 响应被分类为 `MANAGED_CHALLENGE` | 最准确，但已经浪费了一次请求 |
| 时间兜底 | `obtainedAt + 25min` | 实测 `cf_clearance` 是不可解析的不透明串，拿不到真实过期时间，只能保守估计 |
| 用户主动 | 设置页「重新验证」按钮 | 兜底手段 |

**不要做盲目重试**。被判定挑战后立刻重发，只会加速升级为 WAF 阻断。

### 关于 `cf_clearance` 能不能解析

网上流传它形如 `v1.<base64 payload>.<hmac>`，payload 里带 `ip` 和 `exp`。**实测不成立**：从 `nowsecure.nl` 真实取得的 `cf_clearance` 是 378 字符的单段不透明串，没有任何分隔符，base64 解码无意义。所以不要指望解析出 IP 或过期时间，按时间兜底。

---

## 6. 尚未解决的三件事

**1. TLS 指纹对不齐。** Ktor 在 Android 用 OkHttp、iOS 用 Darwin、Desktop 用 CIO/JDK，TLS 握手特征与 Chrome 不同。`cf_clearance` 主要校验 IP + UA，TLS 是次要因子（社区观察为「通常也失效」，但不是必然）。这意味着**移动端 WebView 过盾 → Ktor 请求**这条主链路有可能被拒。必须在真机上用开启 Bot Fight 的站点验证，这是 M1 的必测项。

**2. 数据中心 IP 无法验证此类场景。** 本机所有实验都从机房出口发起，`mycomic.com` 直接返回 WAF 阻断。真实用户在移动网络下是住宅 IP，处境完全不同。**结论：Cloudflare 相关能力无法在 CI / 沙箱中验证，只有真机测试有效**，这一点要写进测试策略，不能划到「自动化覆盖」里。

**3. 自动化浏览器过不了托管挑战。** 实测 Chromium（headless 与 xvfb 有头模式各一次）在 `nopecha.com/demo/cloudflare` 上均停在 `Just a moment`，30 秒超时。这从反面印证了 ADR-009：即使桌面端硬上 KCEF，它作为可被识别的自动化内核，过盾成功率也不会比 WebView 高，却要付出 200 MB 内核的代价。

---

## 7. 源码对照

| 关注点 | 实现 | 位置 |
|---|---|---|
| Cookie 存储、域/路径/过期匹配、失效标记 | `CookieStore` | `m0-spike/shared/src/commonMain/kotlin/spike/CookieStore.kt` |
| 三类拦截分类 | `CloudflareGuard.classify` | 同上 |
| 粘贴解析（5 种格式） | `CookieImportParser` | 同上 |
| 请求注入顺序、UA 覆盖、失效回写 | `VeneraSourceRunner.handleRequest` | `m0-spike/shared/src/commonMain/kotlin/spike/VeneraSourceRunner.kt` |
| 验证用例（24 项） | `CookieSpike` | `m0-spike/shared/src/desktopMain/kotlin/spike/CookieSpike.kt` |
| 真实拦截页夹具 | `cf-fixtures/` | `m0-spike/shared/src/desktopMain/resources/` |

复现：

```bash
./gradlew :shared:cookieSpike                              # 24 项断言
./gradlew :shared:accountSpike --args="<source.js> --live" # 真实请求上的拦截分类
```

---

## 8. 落地顺序建议

1. `CookieStore` 落到 SQLDelight（`architecture.md` 5.2 的 `cookie` 表），补 `paired_user_agent`、`obtained_at`、`invalid_reason` 三列；
2. 敏感值（session、cf_clearance）进 KVault / Keychain，DB 只存索引与元数据；
3. `WebViewLoginHost` 接口定稿，Android/iOS 各自实现「打开 → 轮询 checkStatus → 导出 Cookie → 回写 Store」；
4. 桌面 `AccountPage` 接 `CookieImportParser`，并把源的「请通过内置浏览器验证」文案覆写掉；
5. 真机验证 TLS 指纹这一关，决定是否需要引入指纹伪装（若需要，那是独立 ADR）。
