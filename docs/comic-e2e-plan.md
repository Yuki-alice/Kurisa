# 漫画全流程验证计划（本地导入 + 插件网络源）

| 项目 | 内容 |
| --- | --- |
| 文档状态 | **实施中**：S0–S3 已落地（2026-11-13），断点与验收门见 §1.2 / §4；后续直接改正文，不维护版本号与变更摘要 |
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

| # | 断点 | 证据 | 后果 | 状态（2026-11-13） |
| --- | --- | --- | --- | --- |
| B1 | 无导入入口；`localFs` 未传阅读器 | `app/shared-ui/.../App.kt` 无导入入口、`feature/reader/.../Reader.kt` `localFs = null` | 本地能力全在，用户触达不到 | **已解**：`ShelfScreen` 选目录 → `LocalLibrary` 扫描 → `ReaderRoute` 传 `localFs` |
| B2 | `LocalChapterProvider` 未注入 | 平台壳只传 `RegistryChapterProvider` | 只有一个 Provider 槽，缺本地/在线组合器 | **已解**：`LocalFirstChapterProvider` 按 `local:` 前缀分派 |
| B3 | 阅读器自带独立 `CookieStore` | `Reader.kt` 内 `remember { CookieStore() }` | 与源侧不共享，配对 UA 无法跨链路生效 | **已解**：`AppContainer`（Koin）共享 CookieStore/HttpClient/ImageFetcher |
| B4 | 路由不带 `sourceKey` | `ReaderRoute` / `DetailRoute` 无该字段 | `workSourceKey` 恒 `{ null }`，多源下永远回退首个 enabled 源 | **已解**：两路由带 `sourceKey`，`SourceCatalog.providerFor` 绑定 |
| B5 | 发现页仍是占位 | `Search.kt` / `Detail.kt` / `Explore.kt` 占位 | 引擎的 `searchWorks` / `loadWorkDetails` 无调用方 | **已解（搜索/详情）**：`SourceCatalog.search/workInfo` + MVI 页面；explore 明确标注未接线 |
| B6 | **验证用源装不上** | 示例源 `minAppVersion=1.0.0` vs 壳传 `0.0.0` | 端到端验证的真实硬阻断 | **已解**：`DEFAULT_APP_VERSION=1.0.0` 收口，示例源能力位改为 `search,comic` |
| B7 | 无真实端到端回归 | 条件门禁需外部 refs 否则 SKIP | 「全绿」不等于全流程跑过 | **已解**：`LocalComicWalkthroughTest` / `NetworkComicWalkthroughTest` 两条 desktopTest，含截图证据 |

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


---

## 8. 实施记录（2026-11-13）

### 8.1 落地内容

| 阶段 | 结果 |
| --- | --- |
| S0 解堵 | `DEFAULT_APP_VERSION = "1.0.0"` 单一常量；`DetailRoute` / `ReaderRoute` 增 `sourceKey` / `title`（默认值保证历史路由可反序列化）；`RegistryChapterProvider` 增 `preferredSourceKey`，优先级「路由 > 作品映射 > 首个 enabled」；`NavRoutesTest` 补 3 条断言 |
| S1 本地闭环 | `LocalLibrary`（扫描作品）+ `LocalFirstChapterProvider`（`local:` 前缀分派）+ 书架选目录入口（Desktop `JFileChooser`，IO 线程 + EDT）；阅读器 `localFs` 接线 |
| S2 网络闭环 | `SourceCatalog.search / workInfo / providerFor / enabledSources`；兼容 `works` 与 `comics` 两种返回；`SearchViewModel` / `DetailViewModel`（StateFlow + Intent）；`AppContainer`（koin-core）共享 CookieStore / HttpClient / ImageFetcher，删除阅读器自建实例 |
| S3 门禁固化 | 两条 walkthrough 落 `desktopTest`；截图写入 `docs/screenshots/`；`WalkthroughSupport.assertRendered` 整图采样防空白 |

### 8.2 可复现命令

```bash
# 桌面全量（截图测试需虚拟显示）
xvfb-run -a ./gradlew desktopTest

# 单条 walkthrough
xvfb-run -a ./gradlew :app:shared-ui:desktopTest \
  --tests "app.shared.LocalComicWalkthroughTest" \
  --tests "app.shared.NetworkComicWalkthroughTest"
```

### 8.3 证据图

素材全部来自仓库内真实漫画库 [`docs/sample-library/`](sample-library/README.md)
（Pepper & Carrot，David Revoy，CC-BY 4.0），不是合成占位图。

| 文件 | 展示内容 |
| --- | --- |
| `docs/screenshots/comic-local-shelf.png` | 书架：4 部本地作品的**真实封面**网格（CBZ 与图片目录两种形态） |
| `docs/screenshots/comic-local-detail.png` | 本地详情：封面 + 标题 + 元信息 + 「开始阅读」+ 目录 |
| `docs/screenshots/comic-local-cbz.png` | CBZ 章节出图：真实漫画整页（992×1401，10 页，页码 1/10） |
| `docs/screenshots/comic-network-search.png` | 装源后搜索：2 条结果各带真实封面缩略图 |
| `docs/screenshots/comic-network-detail.png` | 源详情：封面取自 `ComicDetails.cover`（走同一图片出口） |
| `docs/screenshots/comic-network-source.png` | 网络章节出图：`loadEp` 8 页 → 图片出口 → 解码（页码 1/8） |
| `docs/screenshots/app-desktop-shelf.png` | **真实桌面进程**（`xvfb-run ./gradlew :app:desktop:run` + `KURISA_LIBRARY`）启动后的书架 |
| `docs/screenshots/app-desktop-detail.png` | 同一进程内**真实点击**首本书后的详情页 |

KURISA_LIBRARY 支持见 `app/desktop/.../Main.kt`：`-Dkurisa.library=<dir>` 或环境变量
`KURISA_LIBRARY=<dir>` 可直接打开指定库（既方便日常使用，也让自动化能起真窗口验证）。

> 真实窗口的阅读器截图没拿到：Xvfb 无窗口管理器，`xdotool` 合成的点击只在首次
> （窗口激活那一击）被接收，之后详情页的按钮不再响应；键盘 Tab/Enter 同样无效。
> 阅读器画面由 `LocalComicWalkthroughTest` 用同一 Compose 渲染管线出图，内容与真机一致。

### 8.4 与计划的偏差（如实记录）

1. **Koin 范围**：只引入 `koin-core` 收口共享网络栈（CookieStore / HttpClient / ImageFetcher）；
   `ReaderViewModel` 改用 lifecycle 的 `viewModel(key) { ... }` initializer 构造，**手写
   `ReaderViewModelFactory` 已删除**。未引入 `koin-compose-viewmodel`（`koinViewModel` 需该构件，
   与本项目 CMP 1.12.2 / Native 组合未验证，不在本轮冒险引入）。
2. **explore 页**：只做诚实标注，未接 `explore/category`。walkthrough 3.2 不覆盖该页。
3. **本地封面**：已接入（`LocalWork.coverBytes` + Coil），首字渐变只作无封面时的兜底。
   过程中修掉一个真实缺陷：目录型作品（`漫画/第01话/01.jpg`）的封面**始终为 null**——
   `firstImageCover` 用 `byExtension(章节名)` 判断形态，而目录名没有扩展名，
   于是既不是 `DIRECTORY` 也不进归档分支，整条分支被跳过。改为「先试列目录」后修复。
4. **章节标题**：已去扩展名（`LocalChapterProvider.displayTitle`），`.cbz/.zip/.epub/.pdf`
   不再露给读者；id 仍是原路径，读写不受影响。

### 8.5 关于「闭环」与素材的返工记录

首版 S3 的截图被判定为「没眼看」，理由成立，记录在此避免重犯：

1. **素材是假的。** 页面由 `mangaJpeg()` 用 Java2D 画「白底 + 蓝条 + 居中色块」，
   全图不超过 5 种颜色。这种输入**证明不了渲染链路**：解码器、缩放、配色、裁边、
   双页合并全都测不到，截图也无法作为效果确认。
2. **UI 只是骨架。** 书架是一行行文字卡片、详情是裸文字列表、搜索是纯文本行。
   漫画是视觉品类，没有封面就没有「这是哪本书」的信息。
3. **「闭环」只到数据层。** 链路确实通了（扫描 → 章节 → 字节 → 解码 → 上屏），
   但屏幕上没有一张漫画，用户看不到闭环。

本轮改为：

| 项 | 改法 |
| --- | --- |
| 素材 | `docs/sample-library/` 真实漫画库（3 话 16 页 + 1 话图片目录形态），测试直接复制后扫描 |
| 书架 | `LazyVerticalGrid` 封面网格 + 真实封面 + 来源徽标 + 定高文本块 |
| 详情 | 封面 + 标题 + 元信息 + 「开始阅读」+ 编号目录 |
| 搜索 | 结果卡片带封面缩略图（封面走同一图片出口，不直连 URL） |
| 断言 | 新增 `assertPhotoLike`：整图颜色数 ≥ 800（阅读器 ≥ 1500）。色块图必然失败，真图必然通过——这才是「真的渲染了」的门禁 |

宽屏下三页都限宽居中（书架 1180dp、详情/搜索 820dp），避免桌面端内容全挤在左侧。
