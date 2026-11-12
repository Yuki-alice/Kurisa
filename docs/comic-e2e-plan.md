# 漫画全流程验证计划（本地导入 + 插件网络源）

| 项目 | 内容 |
| --- | --- |
| 文档状态 | **制定中**（首个版本尚未发布；后续直接改正文，不维护版本号与变更摘要） |
| 范围 | 漫画：本地文件导入 + JS/规则插件网络源。**小说排版与小说源延后** |
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
| 测试基线 | 2026-11-12 实测 `desktopTest` + `testAndroidHostTest` + iOS 模拟器编译 + Desktop 编译全绿 | 报告 835 项、0 fail / 0 error；其中 49 处 `SKIP`（46 处 androidHostTest 缺 quickjs native、3 处需外部 refs） |

### 1.2 断点（按必修顺序）

| # | 断点 | 证据 | 后果 |
| --- | --- | --- | --- |
| B1 | 无导入入口；`localFs` 未传阅读器 | `app/shared-ui/src/commonMain/kotlin/app/shared/App.kt:127` 无 `ImportRoute`、`feature/reader/src/commonMain/kotlin/feature/reader/Reader.kt:170` `localFs = null` | 本地能力全在，用户触达不到 |
| B2 | `LocalChapterProvider` 未注入 | `app/shared-ui/src/commonMain/kotlin/app/shared/App.kt:79` 默认 `NoopChapterProvider`；`app/desktop/src/desktopMain/kotlin/app/desktop/Main.kt:47`、`app/android/src/main/kotlin/app/android/MainActivity.kt:46` 只传 `RegistryChapterProvider` | 只有一个 Provider 槽，缺本地/在线组合器 |
| B3 | 阅读器自带独立 `CookieStore` | `feature/reader/src/commonMain/kotlin/feature/reader/Reader.kt:176`、`Reader.kt:178` | 与源侧不共享，配对 UA 无法跨链路生效；Koin 接入时一并收口 |
| B4 | 路由不带 `sourceKey` | `feature/reader/src/commonMain/kotlin/feature/reader/Reader.kt:158`、`app/shared-ui/src/commonMain/kotlin/app/shared/App.kt:123`、`app/shared-ui/src/commonMain/kotlin/app/shared/ReaderSourceAssembly.kt:101`、`ReaderSourceAssembly.kt:111` | `workSourceKey` 恒 `{ null }`，多源下永远回退首个 enabled 源 |
| B5 | 发现页仍是占位 | `feature/search/src/commonMain/kotlin/feature/search/Search.kt:44`、`feature/detail/src/commonMain/kotlin/feature/detail/Detail.kt:30`、`feature/explore/src/commonMain/kotlin/feature/explore/Explore.kt:26`；`ReaderSourceAssembly.kt:277` 只有 `testRules` | 引擎的 `searchWorks` / `loadWorkDetails` 无调用方 |
| B6 | **验证用源装不上** | 示例源 `docs/contract/manga-example.js:6` 与 `v4-example.js:6` 声明 `minAppVersion=1.0.0`；平台壳 `app/desktop/src/desktopMain/kotlin/app/desktop/Main.kt:36`、`app/android/src/main/kotlin/app/android/MainActivity.kt:36` 传 `appVersion="0.0.0"`；`source/bridge/src/commonMain/kotlin/source/bridge/SourceRuntimeManager.kt:159` 比较后抛 CONTRACT | 这是端到端验证的真实硬阻断，必须先解 |
| B7 | 无真实端到端回归 | `app/shared-ui/src/desktopTest/kotlin/app/shared/SourceCatalogTest.kt:109`、`SourceCatalogTest.kt:153` 是 MockEngine + 规则源三步诊断；`source/bridge/src/desktopTest/kotlin/source/bridge/RealSourceE2ETest.kt:48`、`SourcesRepoContractTest.kt:46`、`AllSourcesCompatibilityTest.kt:78` 均需外部 refs 否则 SKIP | 「全绿」不等于全流程跑过 |

次要：`docs/contract/manga-example.js:10` 的 `@capabilities search,chapterImages` 中 `chapterImages` 不在 `SourceCapability` 枚举，装配时会被 `mapNotNull` 静默丢弃；验证用源建议改用 `rules/v1` 演示包或修正头块。

---

## 2. 范围与不做什么

**做**：本地漫画（CBZ/ZIP、图片目录、EPUB 图片页、PDF）与插件网络漫画各一条从入口到出图的完整体验；两条 walkthrough 固化成自动回归；把 Koin 作为同批的解堵手段（ViewModel 工厂 + 共享 `CookieStore`/`HttpClient` 收口到 `:app:shared-ui`）。

**不做**：小说排版 / 小说源章节、引擎重构与契约扩展、iOS Xcode 壳、MD3E 官方主题、源仓库订阅、WebView 过盾、下载落库与 Ketch、真实站点接入。

**小说怎么「摸着石头过河」**：漫画这一轮会把四个接缝跑实并冻结 —— `core/reader/ChapterProvider`（含 `rawBytes` / `resolvePage`）、`data/images/ImageFetcher`、`source/contract` 的模型与能力位、Navigation3 路由键（含 `sourceKey`）。小说后续只需在 `ChapterPayload` 侧补文本格式与排版分页（`LocalChapterProvider.kt:64` 已留下「纯文本 EPUB 随 M4」的入口），不动主干、不重做装配。

---

## 3. 验收：两条 walkthrough

### 3.1 本地漫画（Desktop 先跑通）

| 步 | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用 `data/localimport/src/commonTest/kotlin/data/localimport/RealImageFixtures.kt:9` 的 `mangaPage()` 现场生成 JPEG，打成 CBZ 放**本地临时目录** | 固件不入仓，不引入站点内容 |
| 2 | App 内选目录 → 扫描 | 列出作品与章节（`LocalSourceScanner`） |
| 3 | 打开章节 | 页字节经 `LocalImageFetcher` 取出，Pager/条漫出图 |
| 4 | 翻页 / 缩放 / 切模式 | 与 `ReaderScreenshotTest.kt:228` 既有行为一致 |
| 5 | 落截图到 `docs/screenshots/` | 新增 `comic-local-cbz.png` 作为基线 |

同一套再各跑一次图片目录与 PDF，确认 `rawBytes` 分支不退化。

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
| S0 解堵 | B6 B4 | 统一 `appVersion` 与示例源 `minAppVersion`；`ReaderRoute` / `DetailRoute` 加 `sourceKey`；`App.kt:123` 传递；`ReaderSourceAssembly.kt:101` 的 `workSourceKey` 接真映射；`NavRoutesTest` 补 backstack 断言 | 装得上 `manga-example.js`；多源不回退；`NavRoutesTest` 绿 | 单提交回滚，路由键变更需同步 `appSerializersModule` |
| S1 本地闭环 | B1 B2 | 新增导入入口/选目录（Desktop 先行）；`ChapterProvider` 组合器按 workId 分派本地/在线；`App.kt:79` 注入组合器并把 `localFs` 传入 `ReaderScreen`（`Reader.kt:170`） | walkthrough 3.1 全通过；`docs/screenshots/comic-local-cbz.png` | 组合器独立文件，可退回单 Provider |
| S2 网络闭环 | B5 B3 B4 | `SourceCatalog` 补搜索/详情出口（复用 `ReaderSourceAssembly.kt:277` 的 `specFor` + `manager.acquire` 同形装配）；Search/Detail 接 MVI + 结果/章节渲染；Koin 收口 VM 工厂与共享 `CookieStore`/`HttpClient`（删 `Reader.kt:176` 自建实例与 `ReaderViewModel.kt:673` 手写 Factory） | walkthrough 3.2 全通过；`UrlPolicyTest` 与 `RepoHygieneTest` 绿 | 每页独立，可逐页关掉 |
| S3 门禁固化 | B7 | 两条 walkthrough 改造成可自动跑的 `desktopTest`（MockEngine + 临时目录 fixture）；截图基线入库；3 个条件门禁在 `docs/README.md` 明确标为「本地门禁」 | `desktopTest` 新增用例绿；三端编译绿；0 新增 SKIP | 测试与 fixture 独立，不触碰生产代码 |

统一验收：`./gradlew desktopTest testAndroidHostTest :app:shared-ui:compileKotlinIosSimulatorArm64 :app:desktop:compileKotlinDesktop`。改网络必跑 `UrlPolicyTest`，改 schema 必可回滚，改桥/Cookie/白名单前加载 `security-audit`。

---

## 5. 安全与合规红线

- **C1**：主仓不出现真实站点 URL、内容或预置源；验证源与 fixture 一律 `example.*`，本地临时目录不入仓。`docs/baseline/mangadex-search-one.json` 是 M0 历史实测样本，仅被 `m0-verification-report.md:124` 引用，**不得复用为新 fixture**。
- **C2/C3**：域名白名单逐跳复核、`cf_clearance` 与 UA 配对存储、能力交集、签名与静态扫描均不放宽；不新增 `expect/actual`（ADR-006）。
- **C4**：本地链路断网可用；网络链路失败走 `ReaderContract.State.error` 与重试，不留白屏。
- 阅读器状态管理按 `AGENTS.md` 统一 MVI + Koin，不混用裸 `StateHolder` 与 reducer。

---

## 6. 风险与待拍板

1. `appVersion` 取值：`0.0.0` → `1.0.0` 会影响已安装源的 `minAppVersion` 判定与索引兼容；也可只改验证用示例源的头块。倾向**统一到 1.0.0**，但需确认是否影响外部源仓库索引。
2. 选目录 UI 的三端能力：Desktop 文件选择器 / Android SAF / iOS DocumentPicker。第一批只做 Desktop 走通，Android/iOS 记为已知缺口，不为了「三端齐」拖住验证。
3. 组合器分派语义：按 `workId` 前缀（如 `local://`）还是路由携带 provider 类型，需与 `sourceKey` 一并定，避免二次改路由。
4. `manga-example.js` 头块能力位与枚举不一致，验证源用 `rules/v1` 演示包还是修头块，S0 前定。

---

## 7. 文档清理与引用迁移

本轮同步执行（归档采用 `git mv`，不删除任何内容；明细见 `docs/README.md`）：

- 归档：`reader-handoff.md`（环境基线为 Linux/JDK25、待办已完成、结论自相矛盾）、`komikku-reading-parity.md`（B/C 差距矩阵与执行顺序大面积过时，A 部分调研留档可追溯）。
- 降为历史基线：`plugin-system/REFACTOR-PLAN.md`（§0 已记完工，§1 差距矩阵是重构前基线）。
- 保留但加状态注记：`komikku-reader-replication.md`（视觉/UX 规格，实施以本文为准）、`real-source-e2e.md`（修正指向不存在的 `HANDOFF.md` 的引用）。
- 修正 `docs/README.md` 与归档区 README 中已失效的归档理由与「reader-handoff 有未提交修改」陈述。
