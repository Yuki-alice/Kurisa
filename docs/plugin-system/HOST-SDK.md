# Host SDK 规范（插件唯一 API 面，v2 目标态）

> 状态：**目标态冻结签名**，配套 `DESIGN.md §5`、`SOURCE-PACKAGE.md`。
> 现状基线：`source/hostapi/HostApi.kt` 7 命名空间约 15 方法；`HostResponse` 仅 `{status,body}`；
> `KtorHostNetwork` 仅 `get/post`；`KsoupHostHtml` 仅 `parse/select/dispose`；
> `Convert` 仅 `md5/sha256/base64`；`NoopHostUi`；GBK/RSA 空串桩；错误仍字符串子串分类。
> 本规范只增补、不改冻结语义；旧方法保留兼容入口。
> 日期：2026-11-12。

## 0. 分层与失败语义（全局）

* L2 是唯一对插件暴露的 API 面（`DESIGN.md` 分层约束）：新增能力只在此加方法或规则字段，不改 `L0` 调用形状。
* 全部方法**显式失败**：能力未实现或参数非法抛 `CONTRACT`（`SourceErrorKind.CONTRACT`），禁止回空串/空对象静默桩。
  现状 `GBK/RSA` 空串桩、`UI Noop`、`fetch.headers` 恒空、`Network.put/patch/delete` 回 `UNSUPPORTED_METHOD` 均为本规范首批整改项。
* 错误模型：`{ code, message, cause, retryable }`，`code` 对齐 `SourceErrorKind`；
  兼容期保留 `ContractPolicy.classifyError` 字符串子串 fallback（`Login expired/Rate limited/Not found/...`）；
  `LOGIN_EXPIRED` 仍由 `SourceRuntime.withPolicy` 重放一次，`onRelogin` 由宿主注入。
* 安全红线（任何阶段不得放宽）：`@domains` 首跳+逐跳复核、https→http 降级拒绝、UA/Cookie 统一注入且与 `cf_clearance` 配对、
  危险头剥离（`cookie/user-agent/host/content-length/proxy-authorization`，见 `ImageLoadConfig.sanitized/StreamPlayback.sanitized`）、
  能力 = 探测 ∩ 版本上界 ∩ 安装期声明、源私有 KV + `setting:` 前缀 Kotlin 强制、审计只记域名与状态码。

## 1. `Net`（最高优先，P1 首批）

现状：`HostNetwork.get/post + getCookies/setCookies/deleteCookies`；`handleRequest` 仅 `GET/POST`，回包手拼 `{"status,body,headers:{}}`，
`headers` 恒空，`finalUrl/verdict` 丢失，`MAX_BODY_CHARS=300_000` 静默截断。

目标签名（Kotlin 侧，JS 侧同名）：

```kotlin
suspend fun get(url: String, headers: Map<String,String>): HostResponse
suspend fun post(url: String, headers: Map<String,String>, data: String?): HostResponse
suspend fun put(url: String, headers: Map<String,String>, data: String?): HostResponse
suspend fun patch(url: String, headers: Map<String,String>, data: String?): HostResponse
suspend fun delete(url: String, headers: Map<String,String>): HostResponse
suspend fun sendRequest(method: String, url: String, headers: Map<String,String>, data: String?): HostResponse
suspend fun fetchBytes(method: String, url: String, headers: Map<String,String>, data: String?): HostResponse
suspend fun getHtml(url: String, headers: Map<String,String>): HostResponse   // 文本通道，默认 UTF-8
suspend fun getJson(url: String, headers: Map<String,String>): HostResponse   // 同上，调用方按 JSON 解析
```

```kotlin
data class HostResponse(
  val status: Int,
  val body: String,
  val headers: Map<String,String> = emptyMap(),
  val finalUrl: String = "",
  val verdict: String? = null,   // CloudflareGuard 分类透出
  val truncated: Boolean = false,
)
```

* 宿主负责：全方法、URL 与 query 构造、`@keyword` 编码、相对转绝对、Referer/Cookie/UA 配对、逐跳白名单、重试/退避、TTL 缓存、响应头与 `finalUrl` 回传。
* 插件不再写：各源 `fixUrl/buildQuery/headers()/fetchOk()`、429-503-404 分类（宿主分类 + 结构化错误码，源只调 `throwRateLimited/throwNotFound`）。
* 截断：按媒体类型分级，截断必写脱敏日志（只计数源 key 与长度，不记 URL/正文）且回 `truncated=true`。

## 2. `Parse`（最高优先，P1 首批）

现状：`HostHtml.parse/select/dispose`；`handleHtml` 用 `buildString` 手拼 JSON；`KsoupHostHtml.docs` 无上限（长驻后累积）。

目标：

```kotlin
fun parse(html: String): Int
fun select(doc: Int, css: String): List<Map<String,String>>  // 键：text/html/attr:*
fun selectMany(doc: Int, queries: List<String>): List<List<Map<String,String>>> // 批量，一次跨边界
fun dispose(doc: Int)
fun liveCount(): Int
```

JS 侧（`source-v4.d.ts HtmlHelpers` 已落地 `list/text/attr` 自动 dispose，保持）：

```js
Html.list(html, css, (el,i)=>T): T[]
Html.text(html, css): string
Html.attr(html, css, name): string|null
```

* 宿主负责：一次 parse 多选择器、自动 dispose、图片懒加载回退（`data-src/data-original/src`）、实体与空白清洗、节点级 diagnostics。
* 插件不再写：手工 `new HtmlDocument` 生命周期、重复选择器字符串、元素级 `querySelectorAll` 临时文档样板（`source-compatibility.md #4` 的 547 处调用即此类）。
* 长驻治理：文档池上限 + TTL 自动回收 + 调用结束兜底 dispose（P0 落地）。

## 3. `Model`（高优先，P1 首批）

现状：无，散落在各源 `parseCard/parseManga` 映射样板；`BridgeChapterProvider` 承担归一化（`chapters` 三形状、`images`/`content|text` 双键）。

目标（纯 Kotlin + JS 轻封装，不新增跨边界）：

```kotlin
Work.of(id,title,cover?,...)      // ID 稳定化、URL 绝对化、去重、缺省标题、kind 归一化
Chapter.of(id,title,...)          // 章节号解析
Page.of(url,...)                  // 图片 URL 绝对化 + 危险头剥离
```

* 与 `BridgeChapterProvider` 分工：SDK 做构造期归一化，Provider 做消费期容错解析（`Map chapters` 经 `Object.fromEntries` 规范化语义不变，见 `CONTRACT-REVIEW.md`）。
* 规则层过滤器（`trim/absUrl/fileName`）即 `Model` 的子集，见 `SOURCE-PACKAGE.md §3`。

## 4. `Pagination`（高优先，P1/P2）

现状：`search.load/loadNext/page` 三入口并存（`SourceRuntime.searchWorks/searchNext/searchPage/invokePage` + `EngineModels.hasSearchLoad/Next/Page`）；
`source-compatibility.md #7` 已修“只有 loadNext 被误调 load”。

目标：调用侧统一 `searchPage(keyword,options,page|next)`（现已实现，保持）；源侧主推 `page(keyword,options,pageOrNext)`（`SearchNamespaceV4.page`），
`load/loadNext` 标 legacy 但永久兼容。宿主负责自动循环、去重、`maxPage` 推断、上限保护；规则层 `pagination: {maxPage:'auto'}` 即此能力。

## 5. `Codec`（分批，AES 已转正）

现状：`CryptoHostConvert` 仅 `md5/sha256/base64`；`QuickJsEngine.handleConvert` 内 `sha1/sha512/HMAC-SHA256/AES-ECB/CBC/CFB/OFB`（korlibs）已实现；
`encodeGbk/decodeGbk` 回输入本身、`decryptRsa` 回空串（垫片保 34 源兼容）；`strictV4` 下 GBK/RSA/非 sha256 hmac 显式 `CONTRACT`（`source-v4.d.ts ConvertV4`）。

目标：三端一致实现，非法输入显式 `CONTRACT`；`md5/sha1/sha256/sha512/AES/RSA/GBK/Base64/HMAC/Hex/HTML实体` 全量后，
源内加解密兼容代码删除。GBK（korlibs-encoding 无 GBK）与 RSA（korlibs-crypto 无 RSA）终策 deferred，保持“严格模式拒绝、垫片保兼容”不变。

## 6. `Store`（中优先，P1/P2）

现状：`HostStorage.get/set/delete`（`source_key` 隔离）+ `QuickJsEngine.applySettings`（default+用户值合并写 `setting:` 命名空间）；
`loadData/saveData/loadSetting/isLogged` 同步桥（`architecture.md §4.6.1` 白名单）。

目标：`kv.get/set/delete` + `cache.getOrPut(ttl)` + `settings.get`（回退链 用户值→default→null 不变）；
跨调用状态以宿主 KV 为主通道，长驻后允许 JS 内缓存但重建时须可恢复（`DESIGN.md §4` 已决议，旧“调用一律无状态”作废）。

## 7. `Errors`（高优先，P1 首批）

现状：`throwLoginExpired/throwNotFound/throwRateLimited + cleanNovelHtml` 已有 polyfill（`source-v4.d.ts`），
宿主仍走 `ContractPolicy.classifyError` 子串分类。

目标：新 SDK 一律产出结构化错误，宿主分类双轨（新码优先，字符串 fallback 保 Venera 兼容）；
`LOGIN_EXPIRED` 重放一次边界不变（`SourceRuntime.withPolicy`）。

## 8. `UI`（低优先，P3/平台实现）

现状：`HostUi.showMessage/launchUrl` + `NoopHostUi`（桌面降级，`ADR-009`）；`handleUi` 不弹窗不跳转只记日志。

目标：`showMessage/launchUrl/webviewLogin` 需用户手势 + 独立 WebView 上下文 + Cookie 回写；
规则层不暴露 UI 能力（测试面板除外）。

## 9. `Telemetry`（中优先，P1 打点 + P3 面板）

* 每次调用 trace：`engineInit/JS/net/parse` 分段、`cache` 命中、跳过节点诊断、截断标记；审计脱敏不变。
* P1 先落打点（`EngineBenchmark` 口径），P3 接规则测试面板（请求→解析→内容三步，展示 `rawResponse/matchedFragments/diagnostics`，参考 Kazumi）。

## 10. `Lifecycle`（P0/P3）

* P0：`init()` 每源一次（长驻语义；`source-v4.d.ts KurisaSource` 注释中“单发每次重跑”作废，以本规范为准）；
  `activate/deactivate` 懒激活、按能力预加载。
* P3：开发期热重载（bytecode 缓存失效策略：`scriptSha256+engineVersion+moduleName`）。

## 11. 验收对照（每组必备）

* 单测锁定跨边界次数下降（`selectMany/requestAll`）、回包含 `finalUrl/headers/truncated`、新方法白名单逐跳复核。
* 未声明能力 host call 抛 `CONTRACT`（能力门下沉到具体 call，而非路径首段）。
* `AllSourcesCompatibilityTest` L1/API_GAP/MISSING 零变；`RealSourceE2ETest` 行为不变。
