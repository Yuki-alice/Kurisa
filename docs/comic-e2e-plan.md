# 漫画全流程验证计划（本地导入 + 插件网络源）

| 项目 | 内容 |
| --- | --- |
| 文档状态 | **执行中（P0/P1 实施中）**（首个版本尚未发布；后续直接改正文，不维护版本号与变更摘要） |
| 范围 | 漫画：本地文件导入 + JS/规则插件网络源。小说 M4 已落地（2026-11-13 合并 867167e，交接文档见第 2 节地图），本计划聚焦漫画两条 walkthrough |
| 目标 | 两条可复现的端到端 walkthrough：从用户入口一路走到阅读器出图，并固化成回归门禁 |
| 定位 | 漫画全流程的**唯一活计划位**；其余文档中的阶段清单不再作为实施依据 |
| 取证 | 2026-11-12 只读源码 + 实测构建/测试，结论均附 `file:line` |
| 日期 | 2026-11-12 |

> 取证结论：插件引擎、安装器、网络出口、阅读器渲染**都已完工**；缺的是把这些能力接成一条用户走得通的路。
> 本文不重构引擎，不扩契约，不做平台特例。

---

## 1. 前置事实（核过，不是假设）

### 1.1 已经打通的部分

| 能力 | 证据 | 说明 |
| --- | --- | --- |
| 本地格式识别 / ZIP 解包 / 扫描 | `data/localimport/src/commonMain/kotlin/data/localimport/FormatDetector.kt:22`、`ArchiveReader.kt:43`、`LocalSourceScanner.kt:85` | CBZ/ZIP、图片目录、EPUB、PDF 可识别；RAR/7Z/TAR 仅识别不解包 |
| 本地章节与页字节 | `data/localimport/src/commonMain/kotlin/data/localimport/LocalChapterProvider.kt:21`、`LocalChapterProvider.kt:38`、`LocalChapterProvider.kt:78` | `ChapterProvider` 实现齐备，`LocalImageFetcher` 支持 `!/` 归档内取字节 |
| PDF 专用阅读器 | `feature/reader/src/commonMain/kotlin/feature/reader/ReaderViewModel.kt:462`、`feature/reader/src/commonMain/kotlin/feature/reader/Reader.kt:334` | `rawBytes` → `PdfReaderView`，已有 `docs/screenshots/reader-pdf.png` |
| 阅读器渲染与截图证据 | `feature/reader/src/desktopTest/kotlin/feature/reader/ReaderScreenshotTest.kt:228`、`docs/screenshots/` | 分页 / 双页 / 条漫 / PDF 四类 PNG 实证 |
| 插件引擎长驻 + 版本路由 + 规则源 | `source/bridge/src/commonMain/kotlin/source/bridge/QuickJsEngine.kt:99`、`VersionedEngines.kt:20`、`SourceRuntimeManager.kt:138` | apiVersion 2/3 走垫片、4 走 strictV4；`rules/v1` 不起 JS |
| 源安装全链路 | `feature/sources/src/commonMain/kotlin/feature/sources/Sources.kt:147`、`Sources.kt:354`、`source/registry/src/commonMain/kotlin/source/registry/SourceInstaller.kt:55`、`app/shared-ui/src/commonMain/kotlin/app/shared/ReaderSourceAssembly.kt:323` | 粘贴 → 头块/静态扫描/签名 → 预览 → 二次确认 → 落盘 → 写库 → 即装即用 |
| 搜索 / 详情 / 章节引擎能力 | `source/bridge/src/commonMain/kotlin/source/bridge/SourceRuntime.kt:161`、`SourceRuntime.kt:194`、`SourceRuntime.kt:199` | 引擎层完备，缺 UI 出口 |
| 网络白名单 + Cookie/UA 配对 | `data/network/src/commonMain/kotlin/data/network/UrlPolicy.kt:21`、`CookieStore.kt:47`、`SourceHttpClient.kt:117` | `cf_clearance` 与 UA 绑定是红线，不得拆开 |
| `onImageLoad` 重写链 | `source/bridge/src/commonMain/kotlin/source/bridge/BridgeChapterProvider.kt:60`、`SourceRuntime.kt:104`、`feature/reader/src/commonMain/kotlin/feature/reader/ReaderViewModel.kt:537`、`ReaderViewModel.kt:659` | 重写 URL / 头 / 重试次数 → 统一图片出口 |
| 测试基线 | 2026-11-12 实测 `desktopTest` + `testAndroidHostTest` + iOS 模拟器编译 + Desktop 编译全绿 | 报告 835 项、0 fail / 0 error；其中 49 处 `SKIP`（46 处 androidHostTest 缺 quickjs native、3 处需外部 refs）；**2026-11-13 复核 453/0/0/0**：全仓 `desktopTest` 453 用例 / 0 失败 / 0 错误 / 0 跳过，BUILD SUCCESSFUL（本轮新增用例前基线，新增后数字上升） |
| 本地导入闭环（双端） | `app/shared-ui/src/commonMain/kotlin/app/shared/App.kt` 书架「本地书」分区 + 导入/删除入口并透传 `localFs`；`app/desktop/src/desktopMain/kotlin/app/desktop/Main.kt:44` FileDialog、`app/android/src/main/kotlin/app/android/MainActivity.kt:53` SAF | 双端均注入 `LocalRoutingChapterProvider` + `localFs` + `localBooks`，本地书入架可导入/删除 |

### 1.2 断点（按必修顺序）

| # | 断点 | 证据 | 后果 |
| --- | --- | --- | --- |
| B1 | **已解决（2026-11-13 复核）** 无导入入口；`localFs` 未传阅读器 | 原 `App.kt:127` 无 `ImportRoute`、`Reader.kt:170` `localFs = null`；现 `App.kt` 书架「本地书」分区 + 导入/删除入口透传 `localFs`，`Main.kt:44` FileDialog、`MainActivity.kt:53` SAF 双端落地 | 本地能力已可触达 |
| B2 | **已解决（2026-11-13 复核）** `LocalChapterProvider` 未注入 | 原 `App.kt:79` 默认 `NoopChapterProvider`；现双端壳 `Main.kt:44` / `MainActivity.kt:53` 均注入 `LocalRoutingChapterProvider` + `localFs` + `localBooks` | 本地/在线组合器已就位 |
| B3 | **部分解决（Cookie/HttpClient 已共享，Koin 未接线）** 阅读器自带独立 `CookieStore` | `SourceCatalog` 暴露共享 `cookies` / `httpClient`；`ReaderScreen` 新增注入参数（有默认值，不传则退化独立实例），App 层透传，图片出口与源请求共享 Cookie/配对 UA | 共享链路已打通；Koin 仍未接线、ViewModel 工厂仍是手写 Factory，留作后续项 |
| B4 | **已解决（P0/P1）** 路由不带 `sourceKey` | `ReaderRoute` / `DetailRoute` 新增 `sourceKey`（默认空串，本地书零改动），`App.kt` 全链传递并在进详情/阅读前调 `SourceCatalog.bindWork`；新增 `WorkSourceIndex`（MutableStateFlow 不可变快照、线程安全）接 `RegistryChapterProvider.workSourceKey`；`NavRoutesTest` 增 sourceKey 往返与本地默认值断言 | 多源按作品路由生效，不再回退首个 enabled 源 |
| B5 | **已解决（P0/P1）** 发现页仍是占位 | `SourceCatalog` 新增 `search(keyword,page,sourceKey?)`（跨已启用源聚合、单源失败隔离、结果行带 sourceKey）与 `detail(workId,sourceKey)`（归一化 chapters 三形状）；`Search.kt` / `Detail.kt` 由占位重写为 MVI（Contract + ViewModel + StateFlow + Channel + 手写 Factory），搜索框/结果列表/分页、详情标题/章节列表/点击进阅读全接真实数据 | `searchWorks` / `loadWorkDetails` 已有调用方；feature 仍不反向依赖 app.shared |
| B6 | **已解决（P0/P1）** 验证用源装不上 | `Main.kt:51`、`MainActivity.kt:67`、`ReaderSourceAssembly.kt` 的 4 处默认、`SourceRuntimeManager.kt` 的 `PluginRuntimeSpec.appVersion` 默认全部由 `0.0.0` 统一为 `1.0.0`（与源仓 index.json `minApp=1.0.0` 对齐）；`manga-example.js` 头块 `@capabilities search,chapterImages` 已修为 `search,comic` | 装得上验证用示例源，CONTRACT 硬阻断已解除 |
| B7 | **已解决（P0/P1）** 无真实端到端回归 | 新增 `app/shared-ui/src/desktopTest/kotlin/app/shared/ComicWalkthroughTest.kt`（网络源：安装 → 跨源搜索 → 详情 → bindWork → 章节 → 图片 URL，含多源按作品路由与单源失败隔离）与 `data/localimport/src/commonTest/kotlin/data/localimport/LocalComicWalkthroughTest.kt`（本地 CBZ：扫描 → 组合器 `local:` 分流 → 归档图片 → 取字节）；全 `example.*` 合成域名 + 内存 Vfs fixture（C1） | 两条 walkthrough 已固化为自动回归；截图基线尚未新增 |

次要（已修）：`docs/contract/manga-example.js:10` 的 `@capabilities search,chapterImages` 曾因 `chapterImages` 不在 `SourceCapability` 枚举被 `mapNotNull` 静默丢弃；本轮已修为 `search,comic`，验证用源可正常装配。

---

## 2. 范围与不做什么

**做**：本地漫画（CBZ/ZIP、图片目录、EPUB 图片页、PDF）与插件网络漫画各一条从入口到出图的完整体验；两条 walkthrough 固化成自动回归；把 Koin 作为同批的解堵手段（ViewModel 工厂 + 共享 `CookieStore`/`HttpClient` 收口到 `:app:shared-ui`）。

**不做**：小说 M4 已落地，本轮不新增小说能力；引擎重构与契约扩展、iOS Xcode 壳、MD3E 官方主题、源仓库订阅、WebView 过盾、下载落库与 Ketch、真实站点接入。

**小说与漫画的四个接缝（已冻结、M4 已在其上落地）**：`core/reader/ChapterProvider`（含 `rawBytes` / `resolvePage`）、`data/images/ImageFetcher`、`source/contract` 的模型与能力位、Navigation3 路由键（含 `sourceKey`）四个接缝本轮已跑实并冻结；小说 M4 已在 `ChapterPayload` 侧补齐文本格式与排版分页并落地（`LocalChapterProvider.kt:64` 的「纯文本 EPUB 随 M4」入口已用上），不动主干、不重做装配。

---

## 3. 验收：两条 walkthrough

### 3.1 本地漫画（双端本地导入已落地）

| 步 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用 `data/localimport/src/commonTest/kotlin/data/localimport/RealImageFixtures.kt:9` 的 `mangaPage()` 现场生成 JPEG，打成 CBZ 放**本地临时目录** | 固件不入仓，不引入站点内容 |
| 2 | App 内选目录 → 扫描 | 列出作品与章节（`LocalSourceScanner`） |
| 3 | 打开章节 | 页字节经 `LocalImageFetcher` 取出，Pager/条漫出图 |
| 4 | 翻页 / 缩放 / 切模式 | 与 `ReaderScreenshotTest.kt:228` 既有行为一致 |
| 5 | 落截图到 `docs/screenshots/` | 新增 `comic-local-cbz.png` 作为基线 |

同一套再各跑一次图片目录与 PDF，确认 `rawBytes` 分支不退化。双端本地导入（Desktop FileDialog / Android SAF）已落地；两条 walkthrough 已固化为自动回归，见 `app/shared-ui/src/desktopTest/kotlin/app/shared/ComicWalkthroughTest.kt` 与 `data/localimport/src/commonTest/kotlin/data/localimport/LocalComicWalkthroughTest.kt`（截图基线待补）。

### 3.2 网络漫画（插件）

| 步 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 准备 `example.*` 域名的演示源（本地 MockEngine / 本地 HTTP 返回 HTML + 图片字节） | 不引入真实站点（C1） |
| 2 | 源管理页粘贴 → 权限预览 → 二次确认 → 落盘入库 → 即装即用 | `Sources.kt:147` 起的既有链路 |
| 3 | 搜索页输入关键词 → 结果列表 → 详情 | 走 `SourceRuntime.kt:161` / `:194` |
| 4 | 章节列表 → 打开阅读 | 按 `sourceKey` 命中正确源，不走首个 enabled 源回退 |
| 5 | 图片经 `onImageLoad` 重写 → 白名单校验 → 出图 | `UrlPolicy.kt:21` 逐跳通过；配对 UA 用假 cookie 验证 |
| 6 | 落截图 + 断言 | 新增 `comic-network-source.png`；失败路径（域名越界 / CONTRACT）各一条断言 |

---

## 4. 阶段与文件级改动

| 阶段 | 解 | 改动点 | 验收门 | 回滚 |
| --- | --- | --- | --- | --- |
| S0 解堵 | B6 B4 **（已完成）** | 统一 `appVersion` 与示例源 `minAppVersion`（全仓 `0.0.0`→`1.0.0`）；`ReaderRoute` / `DetailRoute` 加 `sourceKey` + `App.kt` 全链传递 + `bindWork` + `WorkSourceIndex` 接 `workSourceKey`；`manga-example.js` 头块能力位修为 `search,comic` | 装得上 `manga-example.js`；多源不回退；`NavRoutesTest` 绿（含 sourceKey 往返/本地默认值）；截图基线待补 | 单提交回滚，路由键变更需同步 `appSerializersModule` |
| S1 本地闭环 | B1 B2 **（已完成，2026-11-13 复核）** | 导入入口/组合器已双端落地：`App.kt` 书架「本地书」分区 + 导入/删除，`Main.kt:44` FileDialog / `MainActivity.kt:53` SAF 注入 `LocalRoutingChapterProvider` + `localFs` + `localBooks` | walkthrough 3.1 全通过；`LocalComicWalkthroughTest.kt` 绿；`docs/screenshots/comic-local-cbz.png` 截图基线待补 | 组合器独立文件，可退回单 Provider |
| S2 网络闭环 | B5 B4 **（已完成）**、B3 **（部分：Koin 未接线）** | `SourceCatalog` 补 `search` / `detail` 出口；Search/Detail 由占位重写为 MVI 并接真实数据；`SourceCatalog` 暴露共享 `cookies` / `httpClient`、`ReaderScreen` 注入参数 + App 透传（Koin/手写 Factory 留后续） | walkthrough 3.2 全通过；`ComicWalkthroughTest.kt` 绿；`UrlPolicyTest` 与 `RepoHygieneTest` 绿；截图基线待补 | 每页独立，可逐页关掉 |
| S3 门禁固化 | B7 **（已完成）** | 两条 walkthrough 固化为 `desktopTest`（`ComicWalkthroughTest.kt` 网络源 + `LocalComicWalkthroughTest.kt` 本地 CBZ，全 `example.*` + 内存 Vfs fixture，C1）；3 个条件门禁在 `docs/README.md` 标为「本地门禁」；主仓 CI `.github/workflows/ci.yml` | `desktopTest` 新增用例绿；三端编译绿；0 新增 SKIP；截图基线待补 | 测试与 fixture 独立，不触碰生产代码 |

统一验收：`./gradlew desktopTest testAndroidHostTest :app:shared-ui:compileKotlinIosSimulatorArm64 :app:desktop:compileKotlinDesktop`。改网络必跑 `UrlPolicyTest`，改 schema 必可回滚，改桥/Cookie/白名单前加载 `security-audit`。

---

## 5. 安全与合规红线

- **C1**：主仓不出现真实站点 URL、内容或预置源；验证源与 fixture 一律 `example.*`，本地临时目录不入仓。`docs/baseline/mangadex-search-one.json` 是 M0 历史实测样本，仅被 `m0-verification-report.md:124` 引用，**不得复用为新 fixture**。
- **C2/C3**：域名白名单逐跳复核、`cf_clearance` 与 UA 配对存储、能力交集、签名与静态扫描均不放宽；不新增 `expect/actual`（ADR-006）。
- **C4**：本地链路断网可用；网络链路失败走 `ReaderContract.State.error` 与重试，不留白屏。
- 阅读器状态管理按 `AGENTS.md` 统一 MVI + Koin，不混用裸 `StateHolder` 与 reducer。

---

## 6. 已裁决与残余风险

**已裁决**：`appVersion` 统一到 `1.0.0`（全仓 4 处默认 + `PluginRuntimeSpec.appVersion`，与源仓 index.json `minApp=1.0.0` 对齐）；`manga-example.js` 头块 `@capabilities` 已修为 `search,comic`；选目录 UI Desktop（FileDialog）+ Android（SAF）已落地，iOS DocumentPicker 仍缺；组合器按 `local:` 前缀分流 + 路由 `sourceKey` 已定稿并落地。

**残余风险（后续项）**：Koin 未接线、ViewModel 工厂仍是手写 Factory；`HostUi` / `VIDEO-AUDIO` 播放器未接；iOS 壳与 DocumentPicker 缺（需 macOS）；两条 walkthrough 的截图基线（`comic-local-cbz.png` / `comic-network-source.png`）尚未新增；源仓 schema 双版本（`js/v1` 与 `rules/v1`）漂移需持续对齐。

---

## 7. 文档清理与引用迁移

本轮同步执行（归档采用 `git mv`，不删除任何内容；明细见 `docs/README.md`）：

- 归档：`reader-handoff.md`（环境基线为 Linux/JDK25、待办已完成、结论自相矛盾）、`komikku-reading-parity.md`（B/C 差距矩阵与执行顺序大面积过时，A 部分调研留档可追溯）。
- 降为历史基线：`plugin-system/REFACTOR-PLAN.md`（§0 已记完工，§1 差距矩阵是重构前基线）。
- 保留但加状态注记：`komikku-reader-replication.md`（视觉/UX 规格，实施以本文为准）、`real-source-e2e.md`（修正指向不存在的 `HANDOFF.md` 的引用）。
- 修正 `docs/README.md` 与归档区 README 中已失效的归档理由与「reader-handoff 有未提交修改」陈述。
