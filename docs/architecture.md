# 小说漫画聚合阅读器 · 跨平台架构设计

| 项目   | 内容                                                                                  |
| ---- | ----------------------------------------------------------------------------------- |
| 项目名  | **`Kurisa`**（全语言统一写作 `Kurisa`，不译为中文；仅用于 App 显示名、bundle id 后缀、README 标题与发布物名，代码中不出现） |
| 技术栈  | Kotlin Multiplatform + Compose Multiplatform                                        |
| 目标平台 | Android、iOS / iPadOS、Desktop（Windows / macOS / Linux，JVM）                           |
| 设计语言 | Material 3 + Material 3 Expressive（CMP 官方组件 + 薄补缺口层）                                 |
| 扩展模型 | JavaScript 插件（对齐 Venera `ComicSource` 契约），应用本体与源完全分离                                |
| 文档状态 | **制定中**（首个版本尚未发布；后续直接修改本文，不维护版本变更记录）                                                |
| 日期   | 2026-11-10                                                                          |

> 本文是工程设计基线，不是功能清单。重点回答四件事：**扩展契约怎么定、三端 JS 运行时怎么落地、领域模型怎么同时容纳漫画与小说、iOS 合规怎么收口**。

### 命名约定

- 对外名称统一写作 **`Kurisa`**，所有语言 / locale 使用同一字符串，**不提供中文或其它语言译名**；字标如何设计不影响该字符串本身。
- **产品名只落配置层**：`app_name`、bundle id 后缀、README / 文档标题、发布物文件名。
- **代码内一律不出现产品名**：Gradle 模块名保持 `:core:*` / `:data:*` / `:source:*` / `:feature:*` / `:app:*`，模块树根目录 `reader/`，数据库 `reader.db`，包名、URL scheme、源契约标记 `SourceScript`、示例 UA 全部使用中性功能名。
- 后续改名只改配置与目录，不改业务代码。

---

## 0. 阅读指引

- 第 2 章是决策记录（ADR），改架构前先读。
- **第 4 章是全文核心**，扩展契约一旦定错，第 5～9 章全部返工。
- 第 7 章列出了所有 `expect/actual` 边界，是评估工作量的依据。
- 附录 A 是 Venera 源兼容映射表，附录 B 是一个可运行的小说源示例。

---

## 1. 产品定位与约束

### 1.1 定位

一个**本地优先的阅读器 + 可扩展的内容接入框架**。应用本体不提供任何内容站点，只提供：

1. 插件（源）的安装、管理、更新与隔离运行；
2. 统一的书架、搜索、详情、章节、下载、阅读进度；
3. 漫画与小说的本地阅读能力（在线流 + 离线缓存 + 本地文件导入）；
4. 一致的 MD3 / MD3E 交互体验。

### 1.2 目标平台与能力对等信息

| 能力              | Android     | iOS                    | Desktop       |
| --------------- | ----------- | ---------------------- | ------------- |
| JS 源插件          | 完整          | 完整（纯解释器）               | 完整            |
| 在线源仓库订阅         | 完整          | 完整（侧载分发）               | 完整            |
| 本地文件导入          | SAF         | Files / DocumentPicker | 任意路径 + 拖放     |
| 后台下载            | WorkManager | BGTaskScheduler（受限）    | 常驻协程          |
| WebView 登录      | WebView     | WKWebView              | 系统 WebView（helper 进程，ADR-010）；能力缺失时回落 Cookie 导入 |
| 原生 dex / jar 扩展 | 可选增强        | 不支持                    | 可选增强          |

### 1.3 硬约束

- **C1 内容与代码分离**：应用本体不包含任何站点 URL、站点解析逻辑或预置源列表。
- **C2 源不可信**：第三方 JS 代码必须运行在隔离上下文，默认无文件系统、无反射、无任意原生调用权限。
- **C3 三端行为一致**：同一个 `.js` 源在三个平台上必须产生相同结果，禁止"某端专用 API"进入公共契约。
- **C4 本地优先**：断网时书架、已下载章节、阅读进度必须完整可用。
- **C5 三端同源**：iOS 仅侧载分发，与 Android / Desktop 共用同一套 `commonMain`，能力一致，不为分发渠道维护代码分支。

### 1.4 合规红线

- **iOS 不提交 App Store**，仅通过 AltStore / TestFlight / 企业签侧载，因此 App Store 审核指南 2.5.2 与 DPLA 3.3.1(B) 的上架审查不适用；但仍须遵守所选侧载渠道的签名、有效期与分发范围规则（见 4.9）。
- 应用不托管、不缓存、不分发任何受版权保护的内容，仅提供用户自配置的工具能力。源仓库只提供脚本与元数据，不提供内容索引。

---

## 2. 架构决策记录（ADR）

### ADR-001 · 采用 KMP + CMP 而非 Flutter / RN

- **决策**：`commonMain` 承载领域、数据、扩展运行时、阅读器状态机与全部 UI；平台壳只做入口与原生能力适配。
- **理由**：本项目 80% 复杂度在"扩展运行时 + 数据模型 + 阅读器内核"，这些是全平台一致的逻辑，用 Kotlin 一套实现收益最大；CMP 让 UI 也复用，避免三套界面。
- **代价**：material3 随 CMP 官方矩阵走 `1.12.0-alpha03`（alpha 线），API churn 由 Version Catalog 单点锁定与薄封装吸收（见 ADR-004）；iOS 端需接受 `ComposeUIViewController` 与 SwiftUI 互操作成本；桌面端大图内存管理需要单独调优。
- **被否决**：Flutter（生态最接近 Venera，但 Kotlin 侧的原生互操作与桌面成熟度不占优，且用户已选 Kotlin 栈）；纯原生三套（维护成本不可接受）。

### ADR-002 · 扩展采用 JavaScript 插件，对齐 Venera 契约

- **决策**：源为 `.js` 文件，类继承 `ComicSource`，宿主注入全局 Host API。借鉴 Venera 契约并实现兼容层，使 `venera-configs` 生态中的漫画源只需少量适配即可使用；在契约上扩展 `ContentKind` 与小说章节接口。
- **理由**：
  - 表达力足够覆盖签名参数、加密图片、Cloudflare Cookie、WebView 登录、多收藏夹等复杂站点，纯声明式规则做不到；
  - 已有成熟社区生态与文档，不必从零建源生态；
  - QuickJS 是**纯解释器、无 JIT**，在 iOS 上不需要 W^X / JIT entitlement，是三端可统一执行的少数方案之一。
- **代价**：
  - JS 引擎是原生依赖，崩溃风险与调试难度高于纯 Kotlin；三端链接由 `quickjs-kt` 提供，集成成本已大幅下降（见 ADR-003）；
  - iOS 仅侧载分发，需自行维护签名与安装渠道（见 4.9）。
- **被否决**：WASM 沙箱（无现成生态，源要重写）、声明式规则（复杂站点表达力不足）、Android 式 dex 动态加载（iOS 不可行）。


### ADR-003 · 采用 `quickjs-kt` 统一三端 JS 运行时，不自研 JNI/cinterop；仍否决 Zipline

- **决策**：`io.github.dokar3:quickjs-kt:1.0.18`（Apache-2.0），`:source:bridge` 直接依赖，**不再维护任何 C 源码、CMake、JNI 或 cinterop `.def`**。三端仍由同一份 QuickJS C 引擎驱动，C3 的前提不变，只是由依赖保证而非自研保证。
- **理由（2026-11-10 产物核验）**：
  - iOS klib manifest `compiler_version=2.4.21` / `abi_version=2.4.0`，依赖 `kotlin-stdlib:2.4.21` + `kotlinx-coroutines-core:1.11.0`，与本项目 Kotlin 基线一致；
  - Android aar 带 4 个 ABI 的 `libquickjs.so`，PT_LOAD `p_align` 全部 16384（16KB page size 可用）；JVM jar 带 macOS / Linux / Windows 原生库与自动加载器；iOS 由 `quickjs-cinterop-quickjs` klib 提供静态库；
  - 已发布 target 覆盖全部三端：`android / iosArm64 / iosSimulatorArm64 / iosX64 / jvm`；
  - API 逐条覆盖 4.6.3：`QuickJs.create(dispatcher)`、`memoryLimit` / `maxStackSize` / `memoryUsage`、`evaluationTimeoutMillis` / `interruptEvaluation()` / `close()`；`define { function / asyncFunction }` 对应同步桥与异步桥；`JsObject` / `TypeConverter` / `converter-ktxserialization` 对应 Host API 对象与契约模型；`moduleLoader` 支持 ES Module 与字节码缓存；`QuickJsException` 带文件名/行号/栈。
- **仍需自研（契约层，纯 `commonMain`）**：Host API 具体实现与域名白名单、Venera `ComicSource` 类发现与能力包装、每源串行 `SourceDispatcher` 与生命周期、源仓库签名与审计、Android 可选独立 `:source` 进程。这些与「引擎绑定」无关。

#### 对 `cashapp/zipline` 的评估

Zipline 输入为 Kotlin/JS 编译产物、桥为 `ZiplineService`、不提供任意 `eval` 与自定义 global，且官方声明 *"does not offer a sandbox or process-isolation and should not be used to execute untrusted code"*，与 C2 直接冲突；其主打的应用内代码动态下发也不符合本项目的源分发模型（自建签名仓库）。**结论：否决**。可借鉴的只剩单实例 confined 到单线程 dispatcher、manifest 签名与字节码预编译思路——这些现已由 `QuickJs.create(dispatcher)` 与 `moduleLoader` 部分覆盖。

> **安全边界**：QuickJS 本身**不是安全沙箱**，只提供隔离的 JS context。真正的安全边界是 4.6/4.7 的能力白名单与 Android 可选独立 `:source` 进程。换用现成库不降低任何隔离要求。

- **验证前置**：M0 spike 必须在三端跑通「加载 `.js` → `Network.get` Promise → `HtmlDocument` 解析 → 返回 JSON」，并验证 `memoryLimit = 32 MB` 生效、`withTimeout` 能中断 `while(true)`、16KB 镜像下 so 加载、iOS 无 JIT entitlement。跑不通才回退 ADR-002 备选（WASM 沙箱）。


### ADR-004 · 采用 CMP 1.12.2 官方默认组合，直接调用官方 MD3 Expressive 组件

> CMP 1.12.2 的官方发布矩阵把 `org.jetbrains.compose.material3:material3` 定为 `1.12.0-alpha03`（基于 Jetpack Material3 `1.5.0-alpha22`），其中已包含 `MaterialExpressiveTheme`、`HorizontalFloatingToolbar`、`ButtonGroup`、`SplitButtonLayout`、`LoadingIndicator`、`MotionScheme` 等官方 Expressive 组件。material3 与 CMP 主版本解耦发布，不存在 `material3:1.12.2`；CMP Gradle plugin 的 `compose.material3` accessor 固定指向旧的 `1.9.0`，**必须显式写官方矩阵版本**。

- **决策**：Compose 依赖组按官方 1.12.2 矩阵锁定——plugin / runtime / ui / foundation / material = `1.12.2`，material3 = **`1.12.0-alpha03`**。`MaterialExpressiveTheme` 作为主题入口，`FloatingToolbar` / `ButtonGroup` / `SplitButton` / `LoadingIndicator` / `WideNavigationRail` / FAB Menu / `MotionScheme` 直接调用官方实现，三端共用同一份 `commonMain`。`:core:designsystem` 保留薄补缺口层，只做官方未覆盖或本项目特有的形状 morph、表达型容器/卡片与跨页面转场。
- **理由**：跟随官方发布矩阵，不自选版本；省掉大量自绘与截图基线工作，组件行为与 MD3E 规范天然一致；官方组件本身是多平台 artifact，C3（三端视觉一致）不受影响，也不需要 Android `expect/actual` 落 Jetpack 原生实现。CMP 1.12.2 的 skiko 为 0.150.2，与 ZoomImage / Sketch 4 / Coil 3 的 0.150.1 对齐，M0 四 target 编译无任何 Skiko 版本警告。
- **代价 / 风险**：material3 处于 alpha 线，API churn 由 Version Catalog 单点锁定 + `designsystem/theme` 薄封装吸收；补缺口的自绘组件仍需三端截图基线（R5 / R16）。
- **验证**：M0 在 `m0-spike/` 以 `MaterialExpressiveTheme` + 官方 `HorizontalFloatingToolbar` / `LoadingIndicator` 跑通 android / iosArm64 / iosSimulatorArm64 / desktop 四 target 编译，并保留自绘补缺口组件一起编译；三端截图基线仍待 M1 建立。
- **不再采用**：Android 端 `expect/actual` 落 Jetpack 原生 Expressive 组件（会造成三端视觉不一致，违反 C3）。

### ADR-005 · 统一内容模型，漫画与小说共用一条管线

- **决策**：引入 `ContentKind { MANGA, NOVEL }`，领域层用 `Work / WorkDetails / Chapter / ChapterPayload` 统一建模；差异收敛到 `ChapterPayload`：
  - `Images(urls)` → 漫画页；
  - `Text(ref)` → 小说正文（在线 URL 或本地文件区间）。
- **理由**：书架、搜索、下载、进度、同步、插件管理对两类内容完全同构；只有阅读器渲染层不同。若建两套模型，第 5～9 章所有代码翻倍。
- **代价**：插件契约需要同时兼容 Venera 的 `comic` 命名空间与新增的 `novel` 命名空间（见 4.4）。


### ADR-006 · KMP 库优先，`expect/actual` 是最后手段

- **决策**：所有能力先按「纯 Kotlin `commonMain` → 现成 KMP 库 → 平台 `actual`」三级顺序选型。**新增任何 `expect/actual` 必须经过架构评审并说明理由**。
- **理由**：把这些能力逐项放进平台层会形成多套行为，直接威胁 C3，也把工作量摊到三端。核对 kmp-awesome 后发现几乎都有成熟的 KMP 实现：
  | 能力                             | 采用的 KMP 库                                                                       | 来源分类              |
  | ------------------------------ | ------------------------------------------------------------------------------- | ----------------- |
  | 自研纯 Kotlin HTML 解析器（待验证）       | **Ksoup**（fleeksoft，jsoup 的 KMP 移植）                                             | Serializer        |
  | 自研 zip / XML / 编码探测            | **Kompress + XmlUtil + korlibs**                                                | File / Serializer |
  | `Crypto` 放 expect/actual       | **Cryptography-Kotlin / KotlinCrypto hash+MACs / korlibs-crypto**，全在 commonMain | Crypto            |
  | 自研下载队列                         | **Ketch**（并行、暂停续传、队列、限速，Android/iOS/JVM）                                        | Network           |
  | 自研重试 / 限流 / 熔断                 | **kmp-resilient**                                                               | Asynchronous      |
  | 自研 `WebViewLogin`              | **Compose WebView Multiplatform**（仅 Android/iOS，桌面按 ADR-009 不引入）              | Compose UI        |
  | `PdfRenderer` 三端适配             | **ComposePdfReader**（`dev.nucleusframework:pdfium`，KMP）；M0 已验证覆盖 JVM/Android/iOS，不再保留三端 actual 兜底          | Compose UI        |
  | `ImageRegionDecoder` + tile 自研 | **ZoomImage Subsampling**（五平台）                                                  | Compose UI        |
  | 自研文件选择器                        | **FileKit** + **AppDirs** + **kotlinx-io / okio**                               | File / Storage    |
  | 自研 KMP logger                  | **Napier / Kermit**（文件轮转自行补一层）                                                  | Log               |
  | 自研 `SecureStorage`             | **KVault / KSafe**（Android/iOS），桌面加密文件                                          | Storage           |
- **结果**：平台层仅剩 3 类（见第 7 章），均为桌面端专属补齐。**三端行为一致性由库保证，而不是靠人工同步三份 `actual`**。
- **代价**：引入约 30 个第三方依赖，需要版本目录集中锁定与依赖升级回归；四 target 可解析性已在 M0 验证完成（结论见 `feasibility.md` 第 4 节），仍待验证的是行为一致性（例如 Compose WebView 的桌面端缺失、`RequestInterceptor` 仅 Android/iOS，见 ADR-009）。
- **治理规则**：每个模块在 README 中声明能力来源类别（纯 Kotlin / KMP 库 / 平台 actual）；CI 检查 `:core:model`、`:core:reader`、`:source:hostapi` 不得出现平台 API 依赖。

### ADR-007 · 阅读器拆为「外层状态机」与「单页渲染器」

- **决策**：
  - **外层自研**：章节序列、翻页 / 连续滚动方向、双页拼接、RTL、预加载窗口、进度持久化、章节切换、错误重试 —— 即 6.1 的 `ReaderStateMachine`，纯 Kotlin，无 UI 依赖；
  - **单页渲染器交给库**：`ZoomImage`（Subsampling / Read Mode / Scroll Bar / Locate / Exif Orientation）+ `Sketch` 或 `Coil 3` 作为图片加载器。
- **理由**：ZoomImage 在 Android / iOS / macOS Native / JVM / Web 五平台都提供超大图子采样以避免 OOM，并原生支持长图 Read Mode 与滚动条；这正是最容易在 iOS 上 OOM、也最费工的部分。但它是**单张图的缩放组件**，不理解"章节 / 页序列 / 阅读进度"，因此不能替代整个阅读器。
- **边界**：`ImageLoadingConfig` 的 `onResponse` 字节变换、`modifyImage` 脚本、双页拼接后的合成图，仍由宿主在交给 `ImageSource` 之前处理；若 ZoomImage 的自定义 `ImageSource` / 区域解码扩展点不足以承载这些变换，则保留 `expect/actual` 解码兜底（M0 验证项）。
- **代价**：长图连续滚动（Webtoon）下 ZoomImage 与外层 LazyColumn 的手势冲突需在 M0 压测；必要时单页模式用 ZoomImage、连续模式用自绘。


### ADR-008 · 路由采用官方 Navigation3，否决 Decompose

- **决策**：全 App 单一 `NavBackStack<List<NavKey>>` + 单一 `NavDisplay` + `SceneStrategy`，`feature/*` 不再有 `Component` 接口：
  - 每个目的地是一个 `@Serializable route key`（`Shelf / Explore(sourceKey) / Search / Detail(workId) / Reader(workId, chapterId) / Sources / Downloads / Settings`），导航即对 backstack 列表 `add/remove`；
  - 状态保存用 `rememberNavBackStack(SavedStateConfiguration { serializersModule = ... }, startRoute)`，route key 须以 `SerializersModule` 注册多态序列化（iOS 等非 JVM 平台无反射）；跨配置变更与进程死亡可恢复；不引入 Essenty `Lifecycle/Parcelable`；
  - 自适应用 `ListDetailSceneStrategy`（Material3 Adaptive）：宽 >600dp 且栈顶为 Detail 且栈内有 List 时自动双窗格，其余单窗格；`designsystem/adaptive` 只留 token 与窗口分级，不再自维护 `NavigationSuiteScaffold` 分支逻辑；
  - 每个目的地配一个 `commonMain ViewModel + StateFlow<UiState>`（按需用 `lifecycle-viewmodel-navigation3` 作用域化），`ReaderViewModel` 直接订阅第 6 章 `ReaderStateMachine`；深链接（`Rinku`）即构造 backstack（外链 `linkToId` → `[Shelf, Detail(workId)]`，分享 → `[Shelf, Detail, Reader]`）。
- **理由**：
  - 官方收敛：CMP 1.10+ 与 Android 共用同一套 Navigation3 实现与文档，长期维护成本低于社区 Decompose；版本随 CMP 1.12.2 发布矩阵走；
  - 心智简单：路由状态就是普通列表，可单测（断言 backstack 内容），无需 mock 生命周期组件树；担心“CMP-only 导航削弱 domain 可测性”并不成立——可测的是 `ViewModel + StateFlow + backstack 纯函数`，UI 只做 `NavDisplay` 渲染；
  - 自适应免费：`Scene` 即“一次渲染 N 个 Entry 的视觉状态”，`ListDetailScene` 已覆盖书架→详情、详情→阅读器的双窗格需求。
- **代价/风险**：
  - Navigation3 相对年轻（CMP 侧从 1.10 起），`SceneStrategy` 与 `TypeMap` API 仍在演进，需在 `libs.versions.toml` 锁定版本并在 M0 做自适应 spike（手机单窗格 / 平板双窗格 / Desktop 三窗格 + 后退 + 状态恢复）；
  - ViewModel 作用域与 DI（Koin）集成需在 `:app:shared-ui` 统一工厂，禁止各 feature 自建 scope。
- **被否决**：Decompose + Essenty（不引入）。

---

### ADR-009 · 桌面端不内嵌浏览器，WebView 能力仅落地 Android / iOS

> 2026-11 修订：桌面 WebView 路径已由 **ADR-010** 改为「系统内核 + 独立 helper 进程」；
> 本条决策 3 的 Cookie 导入降级为能力缺失时的兜底，其余（不引入 KCEF/JCEF）继续有效。

- **背景（M0 实测数据）**：
  - **真实源的依赖率极低**：`venera-app/venera-configs` 全量 36 个源中，声明 `account.loginWithWebview` 的仅 5 个（13.9%），其中 4 个同时声明 `loginWithCookies`。使用场景集中在三类：Cookie 登录（nhentai / e-hentai / ccc）、Cloudflare 盾（mycomic）、论坛区登录（manga_dex，非阅读主链路）。
  - **源的登录态判定不依赖 WebView**：`nhentai.js:318` 的 `persistAuthFromCookies()` 从 `Network.getCookies()` 取 token；`mycomic.js:187` 的 `init()` 直接扫描 `cf_clearance`。源只认 CookieJar，WebView 只是「把 Cookie 写进 CookieJar 的一种手段」。
  - **KCEF 依赖链比文档记录的更深**：`compose-webview-multiplatform-desktop:2.0.1` → `dev.datlag:kcef:2024.04.20.3` → `dev.datlag:jcef:2024.04.20.3` → `org.jogamp.gluegen:gluegen-rt:2.5.0` + `org.jogamp.jogl:jogl-all:2.5.0`，两者均为 **runtime scope**（编译期不需要，故 `compileKotlinDesktop` 能过、任何 `JavaExec` 起不来）。
  - **jogamp 缺失是单点而非死局**：Maven Central / Google Maven / 阿里云 / 腾讯云均 404；`jogamp.org` 时通时断；**SciJava 镜像 `maven.scijava.org/content/groups/public` 有完整 2.5.0 构件**（gluegen-rt 388 KB、jogl-all 909 KB，已下载校验）。即：依赖可解，但依赖一个非官方镜像。
  - **CEF 原生二进制需运行时另取**：`dev.datlag:jcef` 无 natives 分类构件，Chromium 内核须首次启动时下载（压缩包约 80–120 MB，解压 200 MB 量级），是用户可见的启动成本与失败点。
  - **能力不对等**：`RequestInterceptor` 仅 Android/iOS；HTML 外部资源桌面未支持；headless CI 无法验证 CEF 渲染。
- **决策**：
  1. **桌面端不引入 KCEF / JCEF / 任何内嵌 Chromium**，`desktopRuntimeClasspath` 保持干净（这也是 M0 里 `offlineDesktopRuntime` 配置的由来）。
     这是**成本选择，不是能力判断**——Venera 桌面端实际内嵌了 WebView（`AppWebview` / `DesktopWebview` 双实现），证明技术上可行；本决策否决的是「为此付出 200 MB 内核 + 非官方镜像」的代价。
  2. `compose-webview-multiplatform` **只进 `androidMain` / `iosMain`，不进 `commonMain`**；WebView 登录通过 `:core:platform` 的 `WebViewLoginHost` 接口暴露，桌面 `actual` 返回 `Unsupported`，由 UI 层切换为下述路径。
  3. `loginWithWebview` 在桌面映射为两条降级路径：
     - **主路径 · Cookie 导入**：读源声明的 `loginWithCookies.fields` 自动生成表单；源未声明 fields 时提供「整串 Cookie 粘贴」输入框，由宿主解析成 `name=value` 列表写入 CookieJar。
     - **辅路径 · 系统浏览器 + loopback 回调**（RFC 8252）：仅适用于 OAuth 型源，**不能**替代 Cookie 场景——deep link 回传的是 code/token，拿不到 `cf_clearance` 这类 Cookie。
  4. 桌面端 `AccountPage` 对 `loginWithWebview` 的呈现改为「本平台无内置浏览器，请按以下方式导入 Cookie」，并给出从浏览器 DevTools 复制的三步指引；**不得**原样展示源的「点击登录唤起内置浏览器」文案（实测 `mycomic` 的错误提示正是这样误导用户的）。
- **理由**：
  - 替代方案在**契约层等价**：M0 的 `accountSpike` 对照实验证明，同一份 `mycomic.js`，空 CookieJar 时源发出 `No cf_clearance cookie found` 警告，注入 `cf_clearance` 后警告消失、`Network.getCookies` 返回该值；带 Cookie 发真实请求时请求头确实携带（`[cookie: 29 chars]`），站点返回 403 说明请求真实到达。
  - 成本收益极不对称：为 13.9% 的源引入 200 MB Chromium 内核 + 一条非官方 Maven 依赖 + 三平台各自的注入配置，且桌面端本就是次要平台。
  - 与桌面端的其余限制（后台任务、托盘、PDF）性质一致，都属于「桌面用等价能力替代而非对齐」，不破坏 1.2 的能力对等原则。
- **代价**：
  - 桌面用户多一步手工操作，首次配置体验低于 Android/iOS。这是**明确接受的取舍**，需在设置页用清晰文案补偿。
  - 源若只声明 `loginWithWebview` 而未声明 `loginWithCookies`（如 nhentai），宿主无法自动推断字段名，只能退化为「整串粘贴」；后续可在源审计阶段标注此类源。
  - Android/iOS 侧 WebView 登录后仍需把 Cookie 同步进 Ktor `CookieJar`，这条链路与桌面共用同一套 `CookieStore`，反向降低了平台差异。
- **被否决**：
  | 方案 | 否决理由 |
  |---|---|
  | 三端统一 KCEF | 依赖非官方镜像单点；200 MB 内核；无 GUI 环境不可测；`RequestInterceptor` 能力缺失 |
  | 仅「系统浏览器 + deep link」 | 只能处理 OAuth 回传，拿不到普通 Cookie，对 Cloudflare 类源完全无效 |
  | 桌面自行封装 WebKitGTK / WebView2 / WKWebView | 三套 JNI 封装、三份维护成本，收益与 KCEF 相同 |
  | JxBrowser | 商业授权，与开源分发目标冲突 |
  | 桌面直接不支持需登录的源 | 会砍掉 5 个源，且云盾类站点在桌面彻底不可用 |
- **社区对照（2026-11 调研）**：
  - **L1（本 ADR 的 Android/iOS 路径）已是行业主流**：Mihon 的 `CloudflareInterceptor` 用隐藏 WebView 过盾 + `AndroidCookieJar` 同步，检测判据同为 `cf-mitigated`；其 host 锁、12s 超时、isolated world、Sec-CH-UA 同步四项工程细节优于本项目现状，需对齐。
  - **L2（请求交由 WebView 的 `fetch()` 发出）已被验证可解 TLS 指纹问题**：keiyoushi `WebViewFetchInterceptor`（PR #11886）与 Litho 的 WebView 网关是两套独立实现，后者给出同网络下 `fetch()` 200 / `URLSession` 403 的对照。因图片可直接由原生加载器发出，L2 只需覆盖 JSON API，成本可控。
  - **L3（改 TLS 栈）平台覆盖不全**：Android 可用 `org.chromium.net:cronet-embedded`（Google Maven 实测有 `143.7445.0`，JA3 与 Chrome 同源）；iOS 系统栈不可改，只能走 L2；JVM 桌面三条路全堵（`ktor-client-curl` 无 `-jvm` 变体、`ktor-impersonate` 未发布、curl-impersonate 需自编 `.so`）。
  - 完整证据与链接：`docs/_archive/2026-11-13/community-solutions.md`（已归档，仅追溯；结论以 `docs/cookie-cloudflare.md` 为准）。
- **验证**：`./gradlew :shared:accountSpike --args="<source.js> [--live]"`，输出空 CookieJar / 注入 Cookie 的对照表；`--live` 追加真实请求以确认 Cookie 进入请求头。
- **落地细节**：Cloudflare 三类拦截（托管挑战 / WAF 阻断 / 限流）的区分、Cookie 与 UA 的绑定存储、粘贴解析的 5 种格式、失效检测策略，见 `docs/cookie-cloudflare.md`；代码在 `m0-spike/.../CookieStore.kt`，24 项断言由 `./gradlew :shared:cookieSpike` 执行。分级降级策略（L1 WebView 过盾 → L2 WebView fetch → L3 换 TLS 栈）的社区方案对照见 `docs/_archive/2026-11-13/community-solutions.md`（仅追溯）。

### ADR-010 · 桌面 WebView 改为「系统内核 + 独立 helper 进程」

> 本条修订 ADR-009 的决策 1 与被否决项「桌面自行封装 WebKitGTK / WebView2 / WKWebView」。
> ADR-009 的 Cookie 导入不再是被唯一路径，降级为**能力缺失时的兜底**。

- **背景（M0 实测）**：
  - **体积对比被低估**：ADR-009 把「自行封装」与「KCEF」按同一收益否定，但两者体积差三个数量级——KCEF 需 150–200 MB Chromium（`libcef` + 资源 + locales），系统内核方案在 Linux 上实测产物 **33 KB**（动态链接 `webkit2gtk-4.1`）。
  - **现成 Java 封装全部不可用**：`avaje-webview`、`webview_java`、`webviewko`、`weblite webviewjar` 都只是 `webview/webview` 的绑定，而上游 `core/include/webview/api.h` **没有任何 Cookie API**。`cf_clearance` / `__cf_bm` 是 HttpOnly，`document.cookie` 读不到，因此这些库对本项目**零价值**。
  - **Venera 的参考实现已验证可行**：`desktop_webview_window`（MIT）就是三平台原生封装，Linux 用 `webkit_cookie_manager_get_cookies`，Windows 用 `ICoreWebView2CookieManager::GetCookies`。M0 按其思路重写 Linux 版，500 行跑通。
  - **主线程冲突是进程模型的硬约束**：macOS 的 WKWebView 必须在 AppKit 主线程，Compose Desktop 的 AWT 已占用；Linux 的 GTK 主循环与 AWT 同进程共存也不稳定。子进程是目前唯一干净的解法。
- **决策**：
  1. 桌面端引入 `kurisa-webview-host`：**独立可执行进程**，只负责「开一个系统内核窗口 + 导出 Cookie」，不参与主界面渲染。
  2. 三平台共用一套 **NDJSON over stdin/stdout** 协议；**不使用本地端口**（Windows 监听端口会触发防火墙弹窗）。
  3. 内核一律用系统自带：Linux `webkit2gtk-4.1`、Windows WebView2 Runtime、macOS WKWebView。**不打包、不下载任何浏览器内核**。
  4. 主进程侧只暴露 `WebviewHost` 一个类（`m0-spike/shared/src/desktopMain/.../WebviewHost.kt`），返回的 `StoredCookie` 自动带 `pairedUserAgent`——`cf_clearance` 与 UA/IP 绑定，换 UA 复用会重新挑战。
  5. **能力探测优先**：缺 `webkit2gtk-4.1` 或 WebView2 Runtime 时，回落 ADR-009 的 Cookie 导入，并给出明确提示，不得直接崩溃。
  6. 实现顺序：Linux（已完成）→ Windows（WebView2，COM + 消息泵）→ macOS（WKWebView，helper 内自建 NSApplication）。
- **理由**：
  - **成本结构变了**：三平台差异被压缩在 host 内部（Linux 约 500 行），协议与客户端共用；相比「为 13.9% 的源引入 200 MB 内核」的旧账，现在是「<1 MB 换回完整的过盾与登录能力」。
  - **故障域隔离**：WebView2/WebKit 崩溃只损失一个认证窗口；进程内嵌方案会把整个阅读器拖下水。
  - **可测**：协议是纯文本，可在 CI 里对 host 做无 GUI 断言（Linux 已用 Xvfb + 12 项断言验证）。
- **代价**：
  - 三份原生实现 + 三平台 CI；Windows/macOS 目前未实现，是本 ADR 的最大未完成项。
  - 发行版依赖：Linux 需 `webkit2gtk-4.1`（deb/rpm 声明依赖）；Windows 老机器需装 WebView2 Runtime；macOS helper 需签名与公证。
  - 打包链路变复杂：helper 需按平台随包分发，首次启动解压到可执行目录（macOS 必须放 bundle 内并签名）。
  - UA 随内核版本漂移，Cookie 必须与「产生它的 UA」成对存储，不可硬编码 UA。
- **被否决**：
  | 方案 | 否决理由 |
  |---|---|
  | 进程内嵌 WebView（JCEF / KCEF） | 150–200 MB 内核；jogamp 依赖非官方镜像；macOS 主线程冲突 |
  | 现成 Java/Kotlin webview 封装 | 上游无 Cookie API，拿不到 HttpOnly 的 `cf_clearance`，等于没解决问题 |
  | 本地 HTTP/WebSocket 端口通信 | Windows 防火墙弹窗；端口冲突；多一层鉴权面 |
  | 只保留 Cookie 导入 | 用户拿不到 `cf_clearance` 的场景（mycomic 等云盾站）在桌面彻底不可用 |
- **验证**：`desktop-webview-host/build-linux.sh` + `xvfb-run -a ./gradlew :shared:webviewHostSpike [--args="<url>"]`；12 项断言（含「JS 看不到 HttpOnly / 原生读到 / 自动配对 UA」）与真实站点（mangadex）截图，详见 `docs/_archive/2026-11-13/m0-webview-host-linux.md`（已归档，仅历史环境实测）。
- **遗留**：Windows / macOS 实现、代理注入、发行版依赖探测、打包与签名、三平台 CI。

---

## 3. 总体架构


### 3.1 分层

```
┌─────────────────────────────────────────────────────────────┐
│ 平台壳  androidApp / iosApp(Xcode) / desktopApp(JVM)         │
│ 仅：Application 入口、权限、Info.plist、打包签名、原生入口     │
└─────────────────────────────────────────────────────────────┘
                              ↕
┌─────────────────────────────────────────────────────────────┐
│ commonMain · UI 层 (CMP)                                     │
│  designsystem(md3 + expressive) / feature-*/ reader-ui        │
│  Route keys + ViewModel + StateFlow / NavDisplay + Scene      │
├─────────────────────────────────────────────────────────────┤
│ commonMain · Domain 层 (纯 Kotlin，零平台依赖)               │
│  model / usecase / source-contract / reader-core / policy     │
├─────────────────────────────────────────────────────────────┤
│ commonMain · Data 层                                         │
│  db(SQLDelight) / network(Ktor) / source-runtime(quickjs-kt) │
│  repository / download / cache / local-import / sync         │
└─────────────────────────────────────────────────────────────┘
                              ↕
┌─────────────────────────────────────────────────────────────┐
│ KMP 库层（绝大多数能力在此，仍属 commonMain）               │
│  Ksoup / Kompress / XmlUtil / Cryptography-Kotlin / Ketch    │
│  ZoomImage / Compose WebView / FileKit / Napier / Koin ...    │
├─────────────────────────────────────────────────────────────┤
│ platform · 仅剩 3 类 expect/actual（见第 7 章）             │
│  AppDirectories / DesktopShell / 后台补齐                     │
└─────────────────────────────────────────────────────────────┘
```

> 能力尽可能由 KMP 库在 `commonMain` 提供，平台层只保留「库确实覆盖不到的边界」。这让 C3（三端行为一致）由依赖保证，而不是靠人工同步三份 `actual`。

**依赖规则（用 convention plugin + 自定义 lint 脚本约束）**：

1. `:core:*` 不得依赖 `:data:*`、`:feature:*`、`:app:*` 或任何平台 API；
2. `:feature:*` 可依赖 `:core:*` 与 `:data:repository` 的接口，不得直接调用 `:source:bridge` 或 SQL；
3. `:data:*` 实现 `:core:model` 定义的接口，不反向暴露实现类型；
4. 插件运行时只能通过 `HostApi` 接口访问宿主，**不得持有 Repository / Database 引用**；
5. 除 7.2 列出的 3 类外，禁止新增 `expect/actual`（ADR-006）。


### 3.2 Gradle 模块树

**按能力来源分层 + 单一 umbrella 导出**（避免巨型 `:shared` 模块带来的增量编译慢、边界靠人工、`export()` 到 Swift 时依赖爆炸）：

```
reader/
├── settings.gradle.kts
├── gradle/libs.versions.toml             所有版本与库坐标单点锁定
├── build-logic/                          convention plugins
│   ├── kmp-library.conventions.gradle.kts
│   ├── kmp-compose.conventions.gradle.kts
│   └── kmp-feature.conventions.gradle.kts
│
├── core/                                 ① 纯 Kotlin，零平台依赖
│   ├── model/        :core:model        Work / Chapter / SourceDescriptor / Progress / 结果类型
│   ├── common/       :core:common       dispatchers、 retry 封装、日志门面、kotlinx-datetime
│   ├── contract/     :core:contract     源契约的 Kotlin 映射接口 + `.d.ts` + JSON Schema
│   ├── reader/       :core:reader       ReaderStateMachine、分页/排版抽象（不含 Compose UI）
│   ├── designsystem/ :core:designsystem md3 + expressive + adaptive + tokens
│   └── platform/     :core:platform     仅 4 类 expect 声明与各端 actual（见第 7 章）
│
├── data/                                 ② 数据能力，KMP 库为主
│   ├── db/           :data:db           SQLDelight schema、migration、DAO
│   ├── network/      :data:network      Ktor + SourceHttpClient + kmp-resilient + Cookie
│   ├── download/     :data:download     Ketch 封装 + download_task 持久化
│   ├── localimport/  :data:localimport  Kompress / XmlUtil / korlibs / Ksoup / PDF
│   ├── cache/        :data:cache        cache4k + 封面/页面配额管理
│   └── repository/   :data:repository   实现 core/model 的仓库接口，聚合上述模块
│
├── source/                               ③ 扩展系统，纯 Kotlin（无 native 边界）
│   ├── contract/     :source:contract  面向源作者发布（依赖 :core:contract）
│   ├── hostapi/      :source:hostapi   Network/Html/Convert/UI/Utils/Storage/Novel 实现
│   ├── bridge/       :source:bridge    quickjs-kt 实例封装、双层桥、能力白名单、隔离
│   └── registry/     :source:registry  安装、元数据、签名、仓库订阅、生命周期
│
├── feature/                              ④ CMP 页面，每个目的地独立
│   ├── shelf/ explore/ search/ detail/ sources/ downloads/ settings/ reader/
│   └── 各模块：commonMain 下 Route key + ViewModel + StateFlow<UiState> + Screen（无 Component 接口，见 ADR-008）
│
└── app/                                  ⑤ 平台壳
    ├── shared-ui/    :app:shared-ui    聚合全部 feature route + NavBackStack + NavDisplay + `App()`
    ├── android/      :app:android      Android application，依赖 :app:shared-ui
    ├── desktop/      :app:desktop      JVM application + 窗口/托盘/快捷键/拖放
    └── ios/iosApp/                     Xcode 工程 + ComposeUIViewController
```

**源集组织（利用 `defaultHierarchyTemplate` 的中间源集，减少重复）**：

| 源集                         | 覆盖范围              | 放置内容                                                            |
| -------------------------- | ----------------- | --------------------------------------------------------------- |
| `commonMain`               | 全部                | 绝大多数业务与 UI 代码                                                   |
| `jvmMain`                  | Android + Desktop | OkHttp/CIO 共用配置、kotlinx-io                                      |
| `androidMain`              | Android           | `WorkManager` 绑定、SAF 补充说明、可选独立 `:source` 进程                     |
| `desktopMain`              | JVM Desktop       | 托盘/窗口/快捷键/拖放、Cookie 导入（无内嵌浏览器，见 ADR-009）                        |
| `nativeMain` / `appleMain` | iOS + macOS       | Darwin engine、平台崩溃上报                                            |
| `iosMain`                  | iOS               | Keychain、BGTaskScheduler、WKWebView 细节、`ComposeUIViewController` |

**iOS framework 导出规则**：只有 `:app:shared-ui` 配置为 `binaries.framework`，并显式 `export(:core:model)` 等 Swift 侧真正需要的模块；其余一律 `implementation`。避免把 20+ 模块的类型全暴露进 Swift 头文件。配合 **SKIE**（生成 Swift-friendly API）、**KMP-NativeCoroutines**（Flow → Swift AsyncSequence）、**KMP-ComposeUIViewController**（KSP 生成 `UIViewControllerRepresentable`）降低 iOS 互操作成本。

**依赖方向**：`feature → core + data(接口)`；`data → core:model`；`source:* → core:contract + core:model`；`source:bridge → quickjs-kt`；`app:* → feature + shared-ui`。**`core` 任何模块不得依赖 `data` / `source` / `feature`**；`source:hostapi` 只持有 `HostApi` 接口，不得持有 Repository / Database 引用。

> `:source:contract` 单独成模块并发布 `.d.ts`、JSON Schema 与源 CI 校验器，让源作者有类型提示、也能在提交仓库时静态校验，不依赖宿主 App。

---

## 4. 扩展源系统（核心）

> 宿主侧插件引擎的**目标态设计**见 `docs/plugin-system/DESIGN.md`，重构已完工（S0/P0/P1/P2/P3），实施顺序记录见
> `docs/_archive/2026-11-13/REFACTOR-PLAN.md`（仅追溯）（`docs/plugin-engine.md` 已归档，仅描述 v1 实现）；
> JS 侧声明以 `docs/contract/source.d.ts` 为准。

### 4.1 设计目标与兼容策略

| 目标   | 做法                                                             |
| ---- | -------------------------------------------------------------- |
| 复用生态 | 实现 Venera `ComicSource` 兼容层，`venera-configs` 的漫画源经适配层导入        |
| 支持小说 | 新增 `kind: ["novel"]` 与 `novel.*` 接口，老源按 `kind: ["manga"]` 默认处理 |
| 源可演进 | 契约带 `minAppVersion` / `apiVersion`，宿主按版本启用能力                   |
| 源可审计 | 静态扫描 + 签名 + 权限声明 + 运行时白名单                                      |

**兼容边界（明确不追求 100% 二进制兼容）**：Venera 的 `UI.*`、`ImageLoadingConfig.modifyImage`（新 Isolate 中执行脚本）、`sendMessage` 同步桥属于宿主实现细节，KMP 侧重新实现但保持 JS 调用形状一致；`favorites` / 评论 / 评分等社交能力列为 `SourceCapability`，允许源不支持、宿主不显示对应 UI。


### 4.2 插件包格式与源仓库

**单源**：一个 `.js` 文件，UTF-8，首部为带签名的元数据注释块（便于不执行代码即可读元数据）：

```javascript
// ==SourceScript==
// @name        示例小说源
// @key         com.example.novel
// @version     1.2.0
// @apiVersion  2
// @minApp      1.0.0
// @kind        novel
// @language    zh_CN
// @nsfw        false
// @domains     example.com,cdn.example.com
// @capabilities search,explore,chapterText
// @update      https://example.com/sources/novel.js
// ==/SourceScript==
```

**仓库 `index.json`**（沿用 Venera 形状并扩展，保持 `url` / `filename` 二选一规则）：

```json
{
  "apiVersion": 2,
  "sources": [
    {
      "name": "示例小说源",
      "key": "com.example.novel",
      "url": "https://example.com/sources/novel.js",
      "filename": null,
      "version": "1.2.0",
      "description": "示例",
      "kind": ["novel"],
      "domains": ["example.com", "cdn.example.com"],
      "sha256": "<hex>",
      "signature": "<base64 ed25519>"
    }
  ]
}
```

**安装流程**：解析元数据（不执行）→ 展示权限（域名、能力、nsfw）→ 用户显式确认 → 校验哈希/签名 → 静态扫描 → 写入 `sources/<key>/<version>.js` → 首次加载时实例化。仓库订阅地址由用户自行填写，应用不预置。

### 4.3 生命周期与加载流程

```
SourceInstaller.plan/confirm()
   └─ 元数据解析（不执行） / 签名校验 / 静态扫描 / 落库 + 脚本落盘
SourceRuntimeManager.acquire(spec)
   ├─ 命中 LRU → 复用 SourceRuntime
   └─ 未命中
       ├─ QuickJsEngine.create（memoryLimit / maxStack / interruptHandler，单发求值）
       ├─ 每次求值重建 context：注入全局对象（Network/Html/Convert/UI/Utils/Storage/Novel）
       ├─ eval polyfill（Promise 调度、fetch、ComicSource 基类、sendMessage 同步桥、类型构造器）
       ├─ eval 源脚本 → 找到 class extends ComicSource → new 实例 → 可选 init()
       ├─ describe()：只读实例字段 → SourceDescriptor
       │    （kind / capabilities / settings / translation / imageLoad / explore / category）
       ├─ 校验头块声明与实例字段、apiVersion、minAppVersion；设置 default 预热 KV
       └─ 注册到 LRU 缓存（按 key 串行 dispatcher）
SourceRuntime.call(path, args)
   └─ 能力门 → 引擎调用 → 错误分类 → LOGIN_EXPIRED 重放一次 → 原始 JSON
```

> **状态说明（2026-11-12）**：本节流程图与以下两段描述的是 **v1 实现现状**。其前提
> （quickjs-kt 无法长驻、`quickJs {}` 阅后即焚）已被证伪：上游 1.0.18 提供
> `QuickJs.create(dispatcher)`、字节码缓存与类型化桥，与本文 ADR-003（第 99 行）及
> §4.6.3（第 569 行）一致。**目标态以 `docs/plugin-system/DESIGN.md` 为准**，
> 重构已全部完工，**以下 v1 单发模型描述仅为历史记录，不是当前代码行为**；当前实现为长驻模型（`QuickJsEngine` / `SourceRuntimeManager`），实施记录见 `docs/_archive/2026-11-13/REFACTOR-PLAN.md`，后续工作见 `docs/work-plan.md`。

引擎采用单发模型（quickjs-kt 的 `quickJs {}` 阅后即焚）：缓存的是「脚本 + 描述符 +
宿主配置」，不是常驻 JS context；跨调用状态一律经 `loadData/saveData` 落宿主 KV。
`describe()` 只读取字段形状，不执行任何能力方法、不触发网络。

**销毁**：源被禁用 / 更新 / 内存压力时 `invalidate(key)` → `engine.close()`；
单次求值的在途 Promise 由 `withTimeout` + `evaluationTimeoutMillis` + `close()` 收敛。


### 4.4 源契约（JS 侧）

#### 4.4.1 元数据（必填）

`name`、`key`（全局唯一，反向域名）、`version`、`minAppVersion`、`url`（更新地址）、`apiVersion`、`kind`（`manga` / `novel` / 两者）、`capabilities`、`domains`（网络白名单，运行时强制）。

#### 4.4.2 能力方法（沿用 Venera 命名，便于兼容）

| 模块                | 方法                                                           | 返回                                | 必填     |
| ----------------- | ------------------------------------------------------------ | --------------------------------- | ------ |
| `explore[]`       | `load(page)` / `loadNext(next)`                              | 分区漫画/小说列表或 `{works, maxPage}`     | 可选     |
| `category`        | 静态配置 + `categoryComics.load(...)`                            | 分类页                               | 可选     |
| `search`          | `load(keyword, options, page)` / `loadNext`                  | `{works, maxPage}`                | **核心** |
| `comic` / `novel` | `loadInfo(id)`                                               | `WorkDetails`                     | **核心** |
| `comic`           | `loadEp(comicId, epId)`                                      | `{images: string[]}`              | 漫画核心   |
| `novel`           | `loadChapter(workId, chapterId)`                             | `{text, format}` 或 `{paragraphs}` | 小说核心   |
| `comic`           | `onImageLoad(url, comicId, epId)`                            | `ImageLoadingConfig`              | 可选     |
| `account`         | `login` / `loginWithWebview` / `loginWithCookies` / `logout` | —                                 | 可选     |
| `favorites`       | `addOrDelFavorite` / `loadFolders` / `loadComics`            | —                                 | 可选     |
| `settings`        | 声明式配置，`this.loadSetting(key)` 读取                             | —                                 | 可选     |
| `translation`     | locale → 字符串映射                                               | —                                 | 可选     |
| `comic.link`      | `domains` + `linkToId(url)`                                  | 外链转 id                            | 可选     |

**能力位与版本**：`@capabilities` 与 `describe()` 探测结果共同决定宿主可调用的方法；
未声明的能力不调用并抛 `SourceErrorKind.CONTRACT`。`apiVersion = 2` 是冻结基线，
`apiVersion = 3` 声明使用扩展能力集；v2 源按能力位降级运行。
`search` / `explore` / `categoryComics` 的分页同时接受页码模型（`load(page)` → `maxPage`）
与游标模型（`loadNext(next)` → `next`），`load` 存在时优先。

**统一返回类型**（宿主侧规范化，JS 侧同时接受 Venera 的 `comics` 与新名 `works`）：

```javascript
Work       { id, title, subtitle, cover, tags, description, stars, language, kind }
WorkDetails{ work, chapters: [{id, title, ordinal}], tags, recommend, ... }
Chapter    { id, title, payload }   // payload: {type:'images', urls} | {type:'text', text, format}
```

**错误约定**：JS 用字符串 `throw` 表达可识别错误，宿主按子串分类为
`LOGIN_EXPIRED`（重登后重放一次）/ `RATE_LIMITED`（交上层退避，不重放）/
`NOT_FOUND`（不重试）/ `PERMISSION` / `TIMEOUT` / `CONTRACT` / `EXECUTION`；
分类与重放边界见 `docs/plugin-system/DESIGN.md` §10。`throw 'Login expired'` 沿用 Venera 行为
（目标态改为结构化错误码，兼容期保留子串分类 fallback）。

#### 4.4.3 小说扩展（新增）

```javascript
novel = {
  // 正文。format: 'html' | 'markdown' | 'plain'
  loadChapter: async (workId, chapterId) => ({ text: '<p>...</p>', format: 'html' }),
  // 可选：目录分卷、正文分页 Token、图片资源重写
  onImageResolve: (url) => ({ url, headers: {} })
}
```

`format: 'html'` 时宿主用受限标签白名单（`p / br / b / i / em / strong / img / h1-h4 / blockquote / a`）渲染，禁止 `script / style / iframe / on*` 属性。


### 4.5 Host API 规范（宿主注入的全局对象）

沿用 Venera `js_api.md` 的形状，增加 `Storage` 与 `Novel`：

| 对象        | 方法                                                                                                                                                                                                                   | 执行方式                    |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------- |
| `Convert` | `encodeUtf8` / `decodeUtf8` / `encodeBase64` / `decodeBase64` / `md5` / `sha1` / `sha256` / `sha512` / `hmac` / `hmacString` / `decryptAes{Ecb,Cbc,Cfb,Ofb}` / `decryptRsa` / `hexEncode`                            | **同步**，纯 Kotlin 实现，不经网络 |
| `Network` | `get / post / put / delete / patch / sendRequest / fetchBytes`、`setCookies / getCookies / deleteCookies`、全局 `fetch`                                                                                                  | **异步 Promise**          |
| `Html`    | `new HtmlDocument(html)`、`querySelector(All)`、`getElementById`、`dispose()`；`HtmlElement` 的 `text / attributes / children / nodes / parent / innerHtml / classNames / id / localName / previousSibling / nextSibling` | **同步**，宿主解析器            |
| `UI`      | `showMessage / showDialog / launchUrl / showLoading / cancelLoading / showInputDialog / showSelectDialog`                                                                                                            | 异步 / 需 UI 上下文，桌面端降级     |
| `Utils`   | `createUuid / randomInt / randomDouble / console.*`                                                                                                                                                                  | 同步                      |
| `Storage` | `get(key) / set(key, value) / delete(key)`（源私有 KV，落 `source_kv` 表）、`loadSetting(key)`                                                                                                                                | 同步（见 4.6）               |
| `Novel`   | `detectEncoding(bytes) / stripHtml(html) / splitChapters(text, pattern)`                                                                                                                                             | 同步                      |

> `HtmlDocument` 由 **Ksoup**（`fleeksoft/ksoup`，jsoup 的 Kotlin Multiplatform 移植）实现，其 `select()` / CSS selector / 属性与文本 API 可直接映射到 Venera 契约的 `querySelector(All)`、`getElementById`、`attributes`、`children`、`innerHtml`，无需自研解析器，也避免各端 DOM 差异（C3）。轻量备选为 `MohamedRejeb/ksoup`。仍必须实现 `dispose()` 并在 context 关闭时强制回收节点，防止泄漏。


### 4.6 JS ↔ Kotlin 桥接协议（关键设计）

Venera 使用 `sendMessage({method, ...})` 做**同步**跨 isolate 调用。KMP 不能直接照搬（会阻塞协程线程、且 QuickJS 无事件循环），因此定义双层桥：

#### 4.6.1 同步桥（兼容 `sendMessage` / `Storage` / `Convert` / `Html` / `Utils`）

- 仅允许**本地、无网络、可快速满足**的方法白名单：`load_data` / `save_data` / `delete_data` / `load_setting` / `isLogged` / `getLocale` / `getPlatform` / `random` / `uuid` / 日志 / 编码转换 / HTML 解析 / Cookie 三件套。
- 设置读取落在 `HostStorage` 的源私有命名空间，键前缀 `setting:` 由 Kotlin 侧强制；
  回退链为「用户设置 → `settings[key].default` → null」，`save_data` 拒绝写入 `setting` 键。
- 宿主在 `SourceDispatcher`（每个源一个串行单线程 dispatcher）上执行，`runBlocking` 只作用于该 dispatcher，**绝不阻塞主线程或 UI 线程**。
- KV 与设置的读写先查内存缓存（源加载时预热），未命中再查 SQLite；写操作「先写内存、后异步落盘」，返回即成功。
- 白名单外的方法调用同步桥 → 直接抛 `SourceContractException`，防误用。

#### 4.6.2 异步桥（网络、WebView、UI、图片变换）

```
JS: Network.get(url, headers)
  → __hostCall('network.get', {url, headers})            返回 Promise
  → Host: JSON 消息入队，Kotlin 协程在 IO dispatcher 执行
      ├─ 域名白名单校验 → 失败 reject(SourcePermissionException)
      ├─ Ktor 请求 → Cookie 注入 → 超时/重试 → 限流
      └─ 调回 SourceDispatcher：__host.resolve(id, payload) / __host.reject(id, err)
  → quickjs-kt 在 suspend 作用域内驱动 pending job，直到 Promise settle 或超时
  → JS Promise settle，async 函数继续
```

- Promise job pump 由 **quickjs-kt** 承担：`asyncFunction` 的 suspend 结果回灌 JS Promise，宿主不再手写 `JS_ExecutePendingJob()` 循环。
- **返回值捕获（M0 实测）**：源契约的 `load` / `loadInfo` 等 async 方法返回 Promise，宿主拼装的调用脚本必须用 **top-level `await`** 取值（`const r = await Network.get(url); JSON.stringify(r);`）；若写成 async IIFE 把 Promise 当作表达式返回，`evaluate<T>()` 只会拿到 Promise 对象本身（实测输出 `Promise { <state>: "fulfilled" }`）。
- `ArrayBuffer` 跨桥统一用 Base64 字符串编码（`fetchBytes` 的请求/响应体同样处理），用 **Kase64** 在 `commonMain` 完成编解码；M0 已用 `com.soywiz:korlibs-encoding:6.0.0` 的 `Base64.encode` 在三端冒烟验证字节编码路径（Kase64 尚未进矩阵）。quickjs-kt 另提供 `ByteArray / UByteArray` 映射，需实测两条路径三端行为一致后定一种。
- 全局 `fetch` 用 JS polyfill 包一层 `__hostCall('network.fetchBytes', ...)`，保持与浏览器形状一致。
- 所有消息带 `id` + `deadline`；超时用 `evaluationTimeoutMillis` + 协程 `withTimeout` + `interruptEvaluation()`，避免恶意 `while(true)` 卡死源线程。QuickJS **不自带 `setTimeout`**，若源脚本需要定时器，由宿主注入受控 polyfill（M0 验证）。

#### 4.6.3 线程与隔离模型

| 维度      | 策略                                                                                                                              |
| ------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Runtime | 每个源一个 `QuickJs.create(dispatcher)` 实例；`memoryLimit = 32 MB`（可配置）+ `memoryUsage` 观测                                       |
| Context | 每实例对应独立 JS context；`modifyImage` 类脚本另开独立实例                                                                                      |
| 线程      | 每个源绑定一个串行 `SourceDispatcher`，同源调用不并发（保护 Cookie / KV 状态）；不同源可并行；quickjs-kt 要求单实例 confined 到单线程，与设计天然一致                           |
| 超时      | 单次调用默认 15 s，网络请求 30 s，整体 eval 10 s；`evaluationTimeoutMillis` + `withTimeout` 协程取消 + `interruptEvaluation()` + `close()` 强制释放 |
| 崩溃隔离    | native 层崩溃不得拖垮宿主进程：Android 侧可选独立 `:source` 进程（AIDL），iOS / 桌面靠严格边界 + 崩溃上报；换用现成库不改变该要求                                            |

### 4.7 沙箱与权限

插件运行时**只能**看到注入的全局对象；不提供 `require` / `import` / `Deno` / `process` / `Java` / 文件 API / 反射。

| 权限         | 声明位置                       | 运行时强制点                          |
| ---------- | -------------------------- | ------------------------------- |
| 网络域名白名单    | 元数据 `@domains`             | `HostApi.Network` 出口统一校验，含重定向目标 |
| 能力开关       | `@capabilities`            | 未声明的能力对应方法不被宿主调用                |
| KV 存储      | 隐式授予，源私有命名空间               | 以 `source_key` 分表隔离，禁止跨源读取      |
| 设置读写       | 隐式                         | 仅本源 `settings` 声明的 key；键前缀宿主强制，源脚本不可伪造 |
| 图片请求头      | `comic.onImageLoad`         | `cookie` / `user-agent` / `host` 被剥离；URL 重写仍过白名单与重定向复核 |
| WebView 登录 | `account.loginWithWebview` | 必须用户点击触发，WebView 独立、可随时关闭       |
| 外部跳转       | `UI.launchUrl`             | 需用户手势 + 域名提示                    |

**静态扫描**（安装时，纯文本，不执行）：检查动态 `eval` / `Function` 构造、`while(true)` 模式、可疑外发域名（与 `@domains` 不符）、超大文件（>512 KB 拒绝）、混淆特征；命中则标记高风险并要求二次确认。静态扫描只是提示，**真正的安全边界在 4.6 / 4.7 的运行时强制**。

### 4.8 签名与审计

- 仓库维护者用 ed25519 签名 `index.json`，公钥随用户添加仓库时绑定并做指纹展示（TOFU）。
- 每个源条目带 `sha256`；自动更新时比对签名与哈希，不一致则拒绝并保留旧版本。
- 源管理页展示：版本、权限、最近更新时间、最近网络请求域名统计、错误日志、一键禁用 / 回滚。
- 所有插件发起的网络请求写入可查询的审计日志（脱敏，不记录 Cookie 与正文）。

### 4.9 iOS 分发与合规策略

**iOS 不提交 App Store**，仅通过 AltStore / TestFlight / 企业签侧载分发；Android 与 Desktop 走开放分发。三端共用同一套 `commonMain`，能力完全一致：源仓库订阅、远程脚本自动更新、用户手动导入 `.js` 全部开启。

| 平台 | 分发渠道 | 源仓库订阅 | 远程脚本自动更新 | 手动导入 `.js` |
| --- | --- | --- | --- | --- |
| iOS / iPadOS | AltStore / TestFlight / 企业签 | 开启 | 开启 | 开启 |
| Android / Desktop | 开放分发 | 开启 | 开启 | 开启 |

- 不走 App Store，审核指南 2.5.2 与 DPLA 3.3.1(B) 的上架审查不再适用，**无需**为上架裁剪能力或预留降级路径，也不需要编译期分发档位开关。
- 侧载渠道自身约束仍须遵守：TestFlight 构建 90 天有效期、企业签仅限组织内分发、AltStore 依赖用户自行安装并定期刷新签名。多渠道并行可降低单渠道政策变化的影响。
- Venera 同样主要通过 AltStore 等侧载渠道分发，可作为分发形态的参照。
- 应用仍不托管、不缓存、不分发任何受版权保护内容（见 1.4）；源仓库只提供脚本与元数据。

---

## 5. 领域模型与持久化


### 5.1 统一领域模型（Kotlin）

```kotlin
enum class ContentKind { MANGA, NOVEL }
enum class SourceCapability { SEARCH, EXPLORE, CATEGORY, LOGIN, FAVORITES, COMMENTS, CHAPTER_TEXT, IMAGE_CONFIG }

data class SourceDescriptor(
    val key: String, val name: String, val version: String, val apiVersion: Int,
    val minAppVersion: String, val updateUrl: String?, val kinds: Set<ContentKind>,
    val capabilities: Set<SourceCapability>, val domains: Set<String>,
    val language: String?, val nsfw: Boolean, val signatureVerified: Boolean
)

data class Work(
    val id: String,                 // 宿主内部 ID = hash(sourceKey + sourceWorkId)
    val sourceKey: String, val sourceWorkId: String,
    val kind: ContentKind, val title: String, val subtitle: String?,
    val cover: String?, val tags: List<String>, val description: String?,
    val stars: Float?, val language: String?, val url: String?
)

data class Chapter(
    val id: String, val workId: String, val sourceChapterId: String,
    val title: String, val ordinal: Int
)

sealed interface ChapterPayload {
    data class Images(val urls: List<String>) : ChapterPayload
    data class Text(val body: String, val format: TextFormat) : ChapterPayload
}
enum class TextFormat { HTML, MARKDOWN, PLAIN }

data class ReadingProgress(
    val workId: String, val chapterId: String,
    val pageIndex: Int?, val scrollOffset: Float?, val percent: Float,
    val updatedAt: Instant, val synced: Boolean
)
```

**ID 规则**：`Work.id = sha256(sourceKey + "|" + sourceWorkId)`，保证跨源不冲突、跨端同步稳定；禁止用 URL 作为主键（站点改域名会导致书架丢失）。


### 5.2 数据库 Schema（SQLDelight）

```sql
CREATE TABLE source (
  key TEXT PRIMARY KEY, name TEXT NOT NULL, version TEXT NOT NULL,
  api_version INTEGER NOT NULL, kinds TEXT NOT NULL, capabilities TEXT NOT NULL,
  domains TEXT NOT NULL, install_path TEXT NOT NULL, sha256 TEXT NOT NULL,
  signature_ok INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
  settings_json TEXT NOT NULL DEFAULT '{}', updated_at INTEGER NOT NULL
);

CREATE TABLE work (
  id TEXT PRIMARY KEY, source_key TEXT NOT NULL, source_work_id TEXT NOT NULL,
  kind TEXT NOT NULL, title TEXT NOT NULL, subtitle TEXT, cover TEXT,
  description TEXT, tags_json TEXT NOT NULL DEFAULT '[]', stars REAL,
  language TEXT, url TEXT, added_at INTEGER NOT NULL, last_read_at INTEGER,
  UNIQUE(source_key, source_work_id)
);
CREATE INDEX idx_work_source ON work(source_key);
CREATE INDEX idx_work_read ON work(last_read_at DESC);

CREATE TABLE chapter (
  id TEXT PRIMARY KEY, work_id TEXT NOT NULL, source_chapter_id TEXT NOT NULL,
  title TEXT NOT NULL, ordinal INTEGER NOT NULL, payload_json TEXT,
  downloaded INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
  UNIQUE(work_id, source_chapter_id)
);
CREATE INDEX idx_chapter_work ON chapter(work_id, ordinal);

CREATE TABLE reading_progress (
  work_id TEXT PRIMARY KEY, chapter_id TEXT NOT NULL, page_index INTEGER,
  scroll_offset REAL, percent REAL NOT NULL, updated_at INTEGER NOT NULL,
  synced INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE shelf (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, ordinal INTEGER NOT NULL, source_folder_id TEXT
);
CREATE TABLE shelf_item (
  shelf_id TEXT NOT NULL, work_id TEXT NOT NULL, added_at INTEGER NOT NULL,
  PRIMARY KEY(shelf_id, work_id)
);

CREATE TABLE download_task (
  id TEXT PRIMARY KEY, work_id TEXT NOT NULL, chapter_id TEXT NOT NULL,
  status TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 0,
  done INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0,
  error TEXT, created_at INTEGER NOT NULL
);

CREATE TABLE source_kv (
  source_key TEXT NOT NULL, data_key TEXT NOT NULL, value_json TEXT NOT NULL,
  PRIMARY KEY(source_key, data_key)
);
CREATE TABLE cookie (
  domain TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL,
  expires_at INTEGER, PRIMARY KEY(domain, name)
);
CREATE TABLE bookmark (
  id TEXT PRIMARY KEY, work_id TEXT NOT NULL, chapter_id TEXT NOT NULL,
  position INTEGER NOT NULL, excerpt TEXT, note TEXT, created_at INTEGER NOT NULL
);
CREATE TABLE local_book (
  id TEXT PRIMARY KEY, path TEXT NOT NULL, format TEXT NOT NULL,
  title TEXT NOT NULL, cover TEXT, imported_at INTEGER NOT NULL
);
```

**迁移原则**：所有 schema 变更走 versioned `.sqm` 迁移；`payload_json` 等扩展字段保持向后兼容，禁止破坏性改列。

### 5.3 缓存与文件布局

```
<appData>/
├── database/reader.db
├── sources/<key>/<version>.js         源脚本（只读，签名校验后落盘）
├── cache/
│   ├── cover/                          封面，LRU 上限可配置
│   └── html/                           可选的响应缓存（默认关闭，避免落敏感内容）
├── downloads/<workId>/<chapterId>/    离线章节（图片或正文）
├── local/<bookId>/                     本地导入书籍
└── logs/source-<key>.log               审计日志（轮转、脱敏）
```

- 目录根路径由 **Kotlin Multiplatform AppDirs**（桌面 Linux/macOS/Windows + Android）与 iOS 的一小段 `actual`（`Application Support`，不进 iCloud 备份）提供；文件/文件夹选择与保存统一用 **FileKit**，I/O 用 **kotlinx-io / Okio**。路径差异被压缩成 `:core:platform` 的一个 `AppDirectories` 接口。
- 缓存配额与清理策略全局统一，下载目录可迁移（迁移时只改根路径记录）。
- 敏感数据（Cookie、账号 token）存 `SecureStorage`（Keystore / Keychain），不进普通 DB 明文。

### 5.4 同步策略

- 阅读进度以 `updated_at` 做 last-write-wins，保留 `synced` 标记供后续接入端到端同步（WebDAV / 自建服务 / iCloud），本期不实现服务端。
- 章节更新检测：按源配置的轮询周期（后台任务）比对 `chapters`，产生"有更新"角标；iOS 受 BGTaskScheduler 调度限制，退化为进入前台时检查。

---

## 6. 阅读器内核

### 6.1 状态机（漫画与小说共用骨架）

```
Idle → ResolvingChapter → Loading → Ready → Reading
                                      ├→ Preloading（并行）
                                      ├→ PageChanged → 持久化进度（防抖 500ms）
                                      ├→ ChapterEnd → NextChapter → ResolvingChapter
                                      └→ Error → Retry / Fallback
```

- 状态由 `ReaderStateMachine`（纯 Kotlin + StateFlow）持有，UI 只渲染状态；保证三端行为一致、可单元测试。
- 章节解析与渲染解耦：`ChapterResolver` 负责调插件取 payload，`PageSource` 负责把 payload 变成可渲染单元。

### 6.2 漫画阅读器

- **模式**：`Paged`（单页 / 双页）、`Continuous`（Webtoon 长条）、RTL 翻页方向、双页拼接（跨页合并规则）。
- **预加载**：当前页前 1 / 后 2，窗口随滑动动态调整；内存缓存按字节预算（默认 Android/Desktop 128 MB，iOS 64 MB），LRU 淘汰并显式回收解码后的位图。
- **单页渲染器**：用 **ZoomImage** 的 `Subsampling`（Android / iOS / macOS Native / JVM / Web 全平台）做超大图分块与避免 OOM，配合 `Read Mode`（长图初始充满屏幕）、`Scroll Bar`、`Locate`、Exif Orientation；图片加载器用 **Sketch 4**（与 ZoomImage 深度集成、支持大图与 Exif）或 **Coil 3**。超过阈值的超长图交给 subsampling，禁止一次性整图解码。外层仍需自研章节序列、预加载窗口与进度（ADR-007）。
- **图片请求配置**：兼容 `onImageLoad` 返回的 `ImageLoadingConfig`（自定义 url / method / headers / data / `onResponse` 字节变换 / `onLoadFailed` 重试）；`modifyImage` 脚本在**独立 context + 独立超时**中执行，失败不影响主阅读器。
- **显示增强**：裁边、色彩滤镜、灰度、亮度、锐化；设置按源 / 按书 / 全局三级覆盖。

### 6.3 小说阅读器

- **解析**：EPUB / CBZ / ZIP 容器用 **korlibs-io**（`openAsZip` + `localVfs`，项目既有依赖，不另引 Kompress），OPF / NCX / XHTML 用 **XmlUtil** 或 **Ksoup**，TXT 编码探测（UTF-8 / GBK / GB18030）用 **korlibs** 的 Charsets/Encodings（`GbkCodec` 纯 Kotlin 表），章节标题正则自行定义；CBZ 内图片按文件名自然排序转图片页。全部在 `commonMain`，无平台依赖。
- **排版分页**：基于 CMP `Paragraph` / `TextLayoutResult` 测量，按视口尺寸缓存分页结果；字号/行距/段距/字体变化后局部重排，避免全书重算。
- **能力**：目录跳转、书签、划线、笔记、全文搜索、夜间/ sepia 主题、翻页动画（仿真 / 滑动 / 无）、进度百分比与剩余阅读时间估算。
- **HTML 正文**：走受限标签白名单渲染，图片资源经 `novel.onImageResolve` 重写后交给图片管线。

### 6.4 本地文件导入

| 格式                     | 解析                       | 平台适配                                                                                                                 |
| ---------------------- | ------------------------ | -------------------------------------------------------------------------------------------------------------------- |
| EPUB / TXT / CBZ / ZIP | 纯 Kotlin（zip + XML + 文本） | 无                                                                                                                    |
| PDF                    | KMP 渲染               | **ComposePdfReader**（`dev.nucleusframework:pdfium`，Compose-first、渐进渲染、可选文本）；M0 已确认覆盖 JVM / Android / iOS，无需 `PdfRenderer` / `PDFKit` / `PDFBox` 三端 actual |
| 图片目录 / 文件夹             | 文件枚举 + 自然排序              | `FileSystem` expect/actual                                                                                           |
| 文件选择导入                 | 桌面 FileDialog / Android SAF（经 `PickLocalFiles` 接口注入； iOS 待验证） | 平台壳提供选择器实现，`LocalBookImporter` 负责落盘 + 入库（幂等）；拖放导入暂不做 |

导入后生成与在线作品同构的 `Work / Chapter`，书架统一展示，来源标记为 `local`。

---

## 7. 平台适配层

### 7.1 能力来源分层（治理模型）

每个模块必须在 README 声明自己属于哪一类；CI 校验 `:core:model` / `:core:reader` / `:source:hostapi` 不含平台 API 依赖。

| 类别                      | 承载内容                                              | 变更成本      |
| ----------------------- | ------------------------------------------------- | --------- |
| ① 纯 Kotlin `commonMain` | 领域模型、用例、阅读器状态机、分页排版、桥协议、权限校验、契约规范化                | 一次实现，三端一致 |
| ② KMP 库                 | 网络、DB、HTML、压缩、加密、下载、图片、WebView、PDF、文件、日志、DI、导航、测试 | 一次引入，三端一致 |
| ③ 平台 `actual`           | 仅 3 类，见 7.2                                       | 三份，但极薄    |

QuickJS 引擎与三端链接由 `quickjs-kt` 依赖提供，归入 ②，无需 native C 层。

**原则：能用 ① / ② 解决的，禁止写 ③。** 新增 `expect/actual` 需要 ADR + 评审。

### 7.2 现存 `expect/actual` 清单（3 类）

| 接口                | Android                             | iOS                           | Desktop JVM                       | 说明                                                                                     |
| ----------------- | ----------------------------------- | ----------------------------- | --------------------------------- | -------------------------------------------------------------------------------------- |
| `AppDirectories`  | app-specific + SAF 导出               | `Application Support`         | `AppDirs`                         | 选择经 `PickLocalFiles` 接口（桌面 FileDialog / Android SAF，FileKit 经实测不可用已撤）；本地书读写走 `korlibs` VfsFile |
| `DesktopShell`    | —                                   | —                             | 窗口尺寸 / 系统托盘 / 菜单栏 / 快捷键 / 拖放      | 桌面专属，其他端空实现；拖放可用 **Compose DND**                                                       |
| `PlatformRuntime` | 独立 `:source` 进程（可选）+ WorkManager 绑定 | BGTaskScheduler 绑定 + Keychain | 协程常驻 + 加密文件（KVault/KSafe 不覆盖 JVM） | 后台调度主体用 **KMP WorkManager**（Android/iOS），安全存储用 **KVault / KSafe**（Android/iOS），此处只补齐缺口 |

> `Ktor` 的 engine 差异（OkHttp / Darwin / CIO）不算 `expect/actual`：由 Ktor 自身在各 target 解析，业务代码只见 `HttpClient`。


### 7.3 由 KMP 库承担的能力

| 能力                                 | 承担者                                                                                                                                                                          | kmp-awesome 分类 |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| `HtmlParser`                       | **Ksoup**（fleeksoft）                                                                                                                                                         | Serializer     |
| `Crypto`（`Convert.*`、ed25519 验签）   | **Cryptography-Kotlin** + **KotlinCrypto hash / MACs** + **korlibs-crypto**，全部 commonMain                                                                                    | Crypto         |
| `ImageRegionDecoder`               | **ZoomImage Subsampling** + **Sketch / Coil 3**                                                                                                                              | Compose UI     |
| `PdfRenderer`                      | **ComposePdfReader**（M0 已验证三端覆盖）                                                                                                                                           | Compose UI     |
| `WebViewLogin`                     | **Compose WebView Multiplatform**：`WebViewState.pageTitle` + `lastLoadedUrl` 正好实现 Venera 的 `checkStatus(url, title)`；`evaluateJavaScript` / JS Bridge / Cookie management 可用 | Compose UI     |
| `FileSystem`（选择器）                  | **FileKit**；目录根路径用 **Kotlin Multiplatform AppDirs**                                                                                                                          | File / Storage |
| `SecureStorage`（Android/iOS）       | **KVault** 或 **KSafe**                                                                                                                                                       | Storage        |
| `BackgroundScheduler`（Android/iOS） | **KMP WorkManager**（一次性/周期任务、约束、任务链）                                                                                                                                         | Device         |
| `Logger`                           | **Napier / Kermit / kotlin-logging**，文件轮转自行补一层                                                                                                                               | Log            |
| 重试 / 限流 / 熔断                       | **kmp-resilient**                                                                                                                                                            | Asynchronous   |
| 下载队列                               | **Ketch**                                                                                                                                                                    | Network        |
| 内存 LRU                             | **cache4k**（或 Store 5 / Universal-Cache）                                                                                                                                     | Storage        |
| URI / 域名校验                         | **Uri KMP**；版本号比对用 **kotlin-semver**                                                                                                                                         | Utils          |
| 深链接                                | **Rinku**                                                                                                                                                                    | Architecture   |
| 列表分页                               | **multiplatform-paging** / **lazy-pagination-compose**                                                                                                                       | Architecture   |
| `QuickJsEngine` 类能力                | **quickjs-kt 1.0.18**（KMP 库，自带三端原生产物）                                                                                                                                        | Runtime        |

### 7.4 桌面端已知限制（必须进 M0 验证）

- **Compose WebView Multiplatform**：Desktop 从 1.7.0 起改用 KCEF，依赖链 `kcef → dev.datlag:jcef → org.jogamp.gluegen:gluegen-rt:2.5.0 / org.jogamp.jogl:jogl-all:2.5.0`（**runtime scope**）。jogamp 在 Maven Central / Google Maven / 阿里云 / 腾讯云均 404，仅 `jogamp.org`（时通时断）与 **SciJava 镜像 `maven.scijava.org/content/groups/public`** 有完整构件（M0 已下载校验）；`dev.datlag:jcef` 无 natives 分类构件，Chromium 内核需运行时下载（200 MB 量级）。**按 ADR-009，桌面端不引入内嵌浏览器，WebView 只进 `androidMain` / `iosMain`**，`loginWithWebview` 在桌面降级为「Cookie 导入（主）+ 系统浏览器 loopback 回调（辅，仅 OAuth 型源）」。`RequestInterceptor` 仅 Android/iOS。
- **ComposePdfReader**：坐标 `dev.nucleusframework:pdfium:159.0.8138.0`，构建时自动拉取 pdfium 二进制；M0 已确认 JVM / Android / iOS 三端 variant 齐备且 `PdfReaderState` 冒烟通过，不再保留 `PdfRenderer / PDFKit / PDFBox` 三端兜底。`PdfDocument` 为 internal，不在业务代码引用。
- **KMP WorkManager** 仅覆盖 Android/iOS；桌面后台由 `PlatformRuntime` 的常驻协程承担。
- **ZoomImage** 与外层 `LazyColumn` 在长图连续滚动下的手势竞争需压测；必要时单页模式用 ZoomImage、连续模式自绘。
- Ksoup / Kompress / Ketch 在 JVM target 的性能基准与内存占用需实测。

### 7.5 约定

`expect` 声明集中在 `:core:platform`，`actual` 只做薄封装，**禁止在 `actual` 里写业务逻辑**（否则三端行为漂移、无法单测）。iOS 侧通过 SKIE / KMP-NativeCoroutines 暴露 Swift-friendly API，避免在 Swift 里重复实现逻辑。

---

## 8. 网络、下载与后台

- **统一出口**：所有插件网络请求必须经过 `HostApi.Network` → `SourceHttpClient` → `Ktor`（OkHttp / Darwin / CIO）。宿主负责 Cookie 注入、UA、Referer、每源限流（默认 4 并发）、DNS/代理设置、重定向域名二次校验。
- **弹性策略**：重试（指数退避）、超时、熔断、限流、舱壁、降级交给 **kmp-resilient** 声明式组合，不再自研；域名白名单校验仍由宿主强制执行，不能外包给库。
- **禁止绕过**：插件不得调用原生网络 API；`fetch` polyfill 内部也走 `__hostCall`。
- **下载队列**：以 **Ketch**（并行下载、暂停/续传、队列、限速、Android/iOS/JVM）为执行引擎，宿主只负责优先级编排（用户手动 > 当前阅读章节预下载 > 后台批量）、任务状态落 `download_task` 表、进程重启恢复、Wi-Fi Only 与磁盘配额检查。
- **缓存**：封面与页面内存 LRU 用 **cache4k**（按字节预算），跨源请求去重可用 Store 5 / Universal-Cache；Cookie 与设置分别落 `cookie` 表与 **Multiplatform-Settings**。
- **后台**：**KMP WorkManager**（Android/iOS，一次性/周期、约束、任务链）承载「章节更新检查 / 队列续传 / 源更新检查」，桌面由 `PlatformRuntime` 常驻协程补齐；iOS 需明确告知用户后台更新不保证实时。

---

## 9. UI 架构与设计系统


### 9.1 MD3 / MD3E 落地

```
designsystem/
├── theme/        MaterialExpressiveTheme + ColorScheme / Typography / Shapes / MotionScheme
├── tokens/       统一的 spacing / elevation / duration / shape token
└── expressive/   补缺口层：形状 morph、表达型容器/卡片、跨页面转场
（FloatingToolbar / ButtonGroup / SplitButton / LoadingIndicator / WideNavigationRail /
 FAB Menu / MotionScheme 等由官方 material3 1.12.0-alpha03 直接提供）
```

- Compose 依赖组按 CMP 1.12.2 **官方矩阵**锁定：plugin / runtime / ui / foundation / material = `1.12.2`，material3 = `1.12.0-alpha03`（基于 Jetpack Material3 `1.5.0-alpha22`）。基础组件与官方 Expressive 组件直接调用；自建 `expressive` 层只补官方未覆盖的部分（ADR-004）。
- **可复用的基础设施**：
  - **material3-windowsizeclass-multiplatform**（chrisbanes）+ **Calf**：窗口尺寸分级与自适应布局；
  - **Compose Unstyled**：无样式、可访问的底层组件，作为补缺口组件的骨架；
  - **compose-stable-marker** + **compose-stability-analyzer**：跨模块稳定性标记与重组分析，保证补缺口组件可跳过重组；
  - **MaterialKolor**：种子色生成 MD3 ColorScheme（桌面/无动态取色的兜底）；**kmPalette**：从封面取色做表达型背景；
  - **material-motion-compose / orbitral** + **Haze**：共享元素转场与模糊背景。
- 动效统一由官方 `MotionScheme.expressive()` / `.standard()` 提供 spatial / effects / spring 参数，禁止散落硬编码 `tween`。
- **版本锁定纪律**：alpha 线的 API churn 由 `libs.versions.toml` 单点锁定吸收；`designsystem/theme` 对 `MaterialExpressiveTheme` 做一层薄封装，升级时只改一处；截图基线的目标从「校验自绘是否贴近规范」改为「校验三端是否一致」。
- 桌面端额外处理：窗口尺寸记忆、菜单栏 / 快捷键、鼠标悬停态、右键菜单、拖放（**Compose DND**）；独立成 `:core:platform` 的 `DesktopShell`，不污染 design system。
- 小说正文 `markdown` 格式可用 **Multiplatform Markdown Renderer**；笔记/划线编辑可用 **Compose Rich Editor**。


### 9.2 导航结构（Navigation3，单 backstack + Scene）

```
NavBackStack<List<NavKey>>（rememberNavBackStack，状态可保存）
├── Shelf                     书架（分类 / 排序 / 网格或列表 / 批量操作）
├── Explore(sourceKey)        按源的探索页 / 分类页 / 榜单
├── Search                    全局搜索（跨源聚合、去重、筛选）
├── Detail(workId)            作品详情（章节列表 / 标签 / 下载 / 加入书架）
├── Sources                   源管理（安装 / 更新 / 权限 / 设置 / 日志）
├── Reader(workId, chapterId) 阅读器（漫画 & 小说共用外壳，内部分支）
├── Downloads                 下载队列
└── Settings                  主题 / 阅读 / 网络 / 存储 / 关于

NavDisplay(entryProvider) + SceneStrategy
├── 单窗格（手机）：一次渲染栈顶 1 个 Entry
└── ListDetailScene（平板/Desktop 宽 >600dp）：同时渲染 [List, Detail]
    ├── [Shelf, Detail(workId)] → 双窗格
    └── [Detail, Reader] → 按阅读器全屏策略单窗格（阅读时不分窗）
```

- 每个目的地 = `Route key（@Serializable data class/object）+ ViewModel + Screen`；`ViewModel` 持有 `StateFlow<UiState>` 与事件通道；`ReaderViewModel` 直接订阅第 6 章的 `ReaderStateMachine`。
- 导航即列表操作：`backStack.add(Detail(workId))` 前进，`backStack.removeLast()` 后退；底部 Tab 切换即 `backStack = listOf(Shelf)` 等重置；禁止各 feature 自建 scope，ViewModel 工厂集中在 `:app:shared-ui`（配合 Koin）。
- 深链接（`Rinku`）：源外链 `linkToId` → `backStack = [Shelf, Detail(workId)]`；本地导入 / 分享 → `backStack = [Shelf, Detail, Reader]`。
- 自适应：`ListDetailSceneStrategy` 来自 Material3 Adaptive，`designsystem/adaptive` 只留窗口分级 token，不再自维护 `NavigationSuiteScaffold` 分支。

### 9.3 关键页面验收点

| 页面  | 必须满足                                 |
| --- | ------------------------------------ |
| 书架  | 断网可用、万级条目不卡顿（分页加载 + 封面懒加载）、批量操作      |
| 源管理 | 权限可见、可回滚版本、可查看审计日志、一键禁用              |
| 详情  | 章节列表虚拟化、更新角标、下载粒度（单章 / 范围 / 全部）      |
| 阅读器 | 冷启动到首屏 < 1 s（已缓存）、翻页无白屏、进度不丢、内存不持续增长 |
| 设置  | MD3E 组件在三端视觉一致（截图基线校验）               |

---

## 10. 技术选型与版本基线

选型依据为 `terrakok/kmp-awesome` 的 21 个分类逐项核对。下表逐项列出选型与理由 / 约束。


### 10.1 运行时、扩展与基础设施

| 领域                | 选型                                                                                    | 理由 / 约束                                                                                          |
| ----------------- | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| 语言 / 构建           | **Kotlin 2.4.21** + Gradle 9.4.1 + Version Catalog + `build-logic` convention plugins | 版本与依赖坐标单点锁定；quickjs-kt 1.0.18 的 klib 由同版本 Kotlin 编译                                              |
| JS 引擎             | **`io.github.dokar3:quickjs-kt:1.0.18`**（Apache-2.0）                                  | 纯解释器无 JIT；三端同一份 QuickJS C 由依赖保证；含 4 个 Android ABI（16KB 对齐）、JVM 全平台原生库、iOS cinterop 静态库。见 ADR-003 |
| 扩展运行时             | **否决 Zipline 作为主运行时**                                                                 | 仅支持 Kotlin/JS 产物与 `ZiplineService` 桥，无任意 eval / 全局注入，且官方声明不执行不可信代码                               |
| JS 契约转换           | `quickjs-kt-converter-ktxserialization`（按需）                                           | Host API 与 `:source:contract` 的 `@Serializable` 模型直接互转，省掉手写 JSON 映射；M0 验证                        |
| HTML 解析           | **Ksoup**（fleeksoft，jsoup KMP 移植）                                                     | 直接映射 Venera `HtmlDocument.querySelector` 契约，替代"自研解析器"                                            |
| 加密 / 哈希           | **Cryptography-Kotlin** + **KotlinCrypto hash / MACs** + **korlibs-crypto**           | `Convert.md5/sha/hmac/AES` 与 ed25519 验签全部 commonMain，移出平台层                                       |
| 序列化               | kotlinx.serialization                                                                 | 桥协议、源元数据、DB JSON 字段                                                                              |
| Base64 / URI / 版本 | **Kase64**（待验）/ **korlibs-encoding**（`com.soywiz:korlibs-encoding:6.0.0`，M0 三端通过）/ **Uri KMP** / **kotlin-semver** | 桥协议字节编码、域名白名单解析、`minAppVersion` 比对；M0 用 `Base64.encode` 冒烟验证编码路径，Kase64 尚未进矩阵 |
| 日志                | **Napier** 或 **Kermit** + 自补文件轮转                                                      | 源日志与宿主日志按 `source_key` 分文件                                                                       |
| 日期时间              | **`kotlin.time`**（Kotlin 2.4 标准库）；kotlinx-datetime 仅按需                        | 0.8.0 的 `Clock` / `Instant` 已迁入 `kotlin.time`，Android 端旧 `Clock.System` 直接 unresolved；阅读进度、章节更新时间、缓存 TTL |
| DI                | **Koin 4.2.2**                                                                            | 运行时 DI，四 target 编译验证通过；ViewModel 工厂集中在 `:app:shared-ui`。不引入编译期 DI（Metro / kotlin-inject）                    |
| 后台                | **KMP WorkManager**（Android/iOS）+ 桌面常驻协程                                              | 一次性/周期任务、约束、任务链，减少三端自研                                                                           |
| 崩溃上报              | Sentry Kotlin Multiplatform（可选）                                                       | native 层崩溃需单独观测（R7）                                                                              |

### 10.2 数据、网络与文件

| 领域        | 选型                                                        | 理由 / 约束                                                         |
| --------- | --------------------------------------------------------- | --------------------------------------------------------------- |
| 数据库       | **SQLDelight 2.x**                                        | schema / migration 编译期校验；Room KMP 为备选但 iOS 链接配置更重               |
| KV / 设置   | **Multiplatform-Settings** / **KStore**                   | 源设置、应用偏好                                                        |
| 安全存储      | **KVault** 或 **KSafe**（Android/iOS）+ 桌面加密文件               | Cookie / token 不进明文 DB                                          |
| 网络        | **Ktor 3.6.1**（OkHttp / Darwin / CIO）                     | engine 差异由 Ktor 解析，业务只见 `HttpClient`；三 engine 按 `androidMain` / `iosMain` / `desktopMain` 分置，M0 三端编译通过 |
| 弹性        | **kmp-resilient**                                         | Timeout / Retry / CircuitBreaker / RateLimiter / Bulkhead 声明式组合 |
| 下载        | **Ketch**                                                 | 并行、暂停续传、队列、限速；宿主只做优先级与持久化                                       |
| 缓存        | **cache4k**（内存 LRU），可选 Store 5 / Universal-Cache          | 按字节预算淘汰，控制 iOS 内存                                               |
| I/O       | **kotlinx-io / Okio** + **korlibs**（Charsets / Encodings） | TXT 的 GBK/GB18030 编码探测                                          |
| 压缩归档      | **Kompress**                                              | EPUB / CBZ / ZIP，纯 Kotlin                                       |
| XML       | **XmlUtil** 或 Ksoup                                       | OPF / NCX / XHTML                                               |
| 文件选择 / 目录 | **FileKit** + **Kotlin Multiplatform AppDirs**            | 替代自研 `FileSystem` expect/actual                                 |
| 归档元数据     | **Kim**（图片元数据 / EXIF）                                     | 漫画图片方向与尺寸预判                                                     |


### 10.3 UI、设计系统与阅读器

| 领域         | 选型                                                                                       | 理由 / 约束                                                                                                                                                                                                 |
| ---------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| UI 框架      | **CMP 1.12.2**（Android / iOS / Desktop；Web 不在范围）                                        | plugin + runtime/ui/foundation/material 全稳定线；material3 按官方矩阵锁 `1.12.0-alpha03`（解耦发布）                                    |
| 设计系统       | **官方 `MaterialExpressiveTheme` + 官方 Expressive 组件** + 薄补缺口层                            | material3 1.12.0-alpha03 基于 Jetpack M3 1.5.0-alpha22；FloatingToolbar / ButtonGroup / SplitButton / LoadingIndicator 等直接调用，三端一致（ADR-004） |
| 组件骨架       | **Compose Unstyled** + **lumo-ui**（组件库脚手架，可选）                                            | 仅用于补缺口组件                                                                                                                                                                                                |
| 自适应        | **material3-windowsizeclass-multiplatform** + **Calf**                                   | 三窗格自适应、平台观感适配                                                                                                                                                                                           |
| 配色         | **MaterialKolor** + **kmPalette**                                                        | 种子色配色、封面取色                                                                                                                                                                                              |
| 动效         | **material-motion-compose** / **orbitral** / **Haze**                                    | 配合自建 `MotionScheme`                                                                                                                                                                                     |
| 稳定性        | **compose-stable-marker** / **compose-stability-analyzer**                               | 跨模块稳定性标记与重组分析                                                                                                                                                                                           |
| 导航         | **Navigation3**（CMP 侧 `navigation3-ui`，按需 `lifecycle-viewmodel-navigation3`） | 官方收敛，backstack 即列表，`rememberNavBackStack` 保活，`Scene/ListDetailSceneStrategy` 做自适应；CMP 侧坐标为 `org.jetbrains.androidx.navigation3:navigation3-ui:1.2.0`（runtime 由 `androidx.navigation3` 传递引入）；非 JVM 平台需 `SavedStateConfiguration` + `SerializersModule`；不引入 Decompose + Essenty，见 ADR-008 |
| 深链接 / 分页   | **Rinku** + **multiplatform-paging / lazy-pagination-compose**                           | 外链转作品、书架与章节列表分页                                                                                                                                                                                         |
| 封面图片       | **Coil 3.6.3**                                                                            | 常规加载；网络组件现名 `coil-network-ktor3`（`coil-network-ktor` 停在 3.0.0-alpha08）                                       |
| 阅读器图片      | **Sketch 4.7.0** 或 Coil 3 + **ZoomImage 1.7.0 Subsampling**                             | 五平台超大图分块、长图 Read Mode、Scroll Bar（ADR-007）；三者均绑定 skiko 0.150.1，与 CMP 的 0.156.1 不一致，见风险表 R20 |
| PDF        | **ComposePdfReader**（`dev.nucleusframework:pdfium:159.0.8138.0`）                       | 覆盖 JVM / Android / iOS / Web，三 target variant 齐备且 `PdfReaderState` 冒烟通过，**无需启用「降级三端 actual」**；`PdfDocument` 为 internal，不直接引用 |
| WebView 登录 | **Compose WebView Multiplatform**（`io.github.kevinnzou:compose-webview-multiplatform:2.0.1`） | `pageTitle` / `lastLoadedUrl` / `evaluateJavaScript` / Cookie；**只依赖 android / iosArm64 / iosSimulatorArm64 / iosX64 变体，桌面不引入**（ADR-009：桌面改 Cookie 导入 + loopback） |
| 拖放         | **Compose DND**                                                                          | 桌面本地导入                                                                                                                                                                                                  |
| 富文本        | **Multiplatform Markdown Renderer** / **Compose Rich Editor**                            | 小说 markdown 正文、笔记编辑                                                                                                                                                                                     |

### 10.4 工程化、测试与 iOS 互操作

| 领域      | 选型                                                                                   | 理由 / 约束                                                   |
| ------- | ------------------------------------------------------------------------------------ | --------------------------------------------------------- |
| 测试框架    | **Kotest**（或 TestBalloon）+ **Turbine** + **Assertk**                                 | 多平台断言与 Flow 测试                                            |
| Mock    | **Mokkery**（或 Mockative / MocKMP）                                                    | KMP/Native 可用的 mock，供契约与解释器测试                             |
| 调试      | **Power-Assert** / **Kotlin Native Xcode Support** / **rebugger**                    | 断言上下文、Xcode 断点、重组原因                                       |
| 截图基线    | Compose UI 测试 + 自建截图对比                                                               | MD3E 组件三端一致性                                              |
| 资源      | CMP `components.resources` / **Libres**                                              | 字符串与图片资源、本地化；MOKO Resources 已归档不再采用                       |
| iOS 互操作 | **SKIE** + **KMP-NativeCoroutines** + **KMP-ComposeUIViewController** + CocoaPods 集成 | Swift-friendly API、Flow → AsyncSequence、ViewController 生成 |
| 文档 / 许可 | **Dokka** + **AboutLibraries**                                                       | 契约 API 文档、开源许可页                                           |
| 契约发布    | `:source:contract` → `.d.ts` + JSON Schema + 源 CI 校验器                                | 让源作者独立校验                                                  |

### 10.5 明确否决的候选

| 候选                                                                     | 否决理由                                                                                                     |
| ---------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| **Zipline** 作为扩展运行时                                                    | 见 ADR-003：输入模型、桥接方式、信任模型、合规四项均不匹配                                                                        |
| **Room KMP** 替代 SQLDelight                                             | iOS SQLite 链接配置更重，且本项目 schema 以 SQL 为中心，SQLDelight 编译期校验更契合                                              |
| **Voyager / Precompose / Tiamat / Decompose + Essenty** 替代 Navigation3 | Navigation3 为官方收敛（CMP 1.10+ 与 Android 同源），backstack 可单测、自适应由 Scene 覆盖；Decompose 的独立生命周期组件树不再需要，见 ADR-008 |
| **Kamel / Compose-Imageloader** 作为阅读器加载器                               | 缺少超大图 subsampling 与长图 Read Mode；封面可用，阅读器交给 ZoomImage + Sketch/Coil                                       |
| **Realm / Kotbase** 替代关系型存储                                            | 书架、进度、下载任务高度关系化且需复杂索引，SQL 更直接                                                                            |
| 采用 Zipline loader 下发源脚本                                                | Zipline 的 loader / 服务桥 / 信任模型均不适用（见 ADR-003）；源分发走自建签名仓库                                                                          |
| 自研 QuickJS JNI/cinterop 绑定                                             | quickjs-kt 已提供三端原生产物、隔离与中断 API，自研无收益                                                                     |

### 10.6 版本基线（`gradle/libs.versions.toml` 单点锁定）

| 项                                                                      | 版本                                                        | 依据                                                      |
| ---------------------------------------------------------------------- | --------------------------------------------------------- | ------------------------------------------------------- |
| Kotlin / KMP plugin                                                    | `2.4.21`                                                  | 官方稳定版；quickjs-kt 1.0.18 klib 的 `compiler_version` 同为该版本 |
| Gradle                                                                 | `9.4.1`                                                   | 本机已缓存；在 Kotlin 2.4.21 的 7.6.3–9.7.0 范围内，也是 AGP 9.2 线的最低/默认版本 |
| AGP                                                                    | `9.2.1`                                                   | 支持 Gradle 9.4.1 的最新稳定版（9.2 线最低 Gradle 9.4.1；9.3+ 要求 ≥9.5.0），且在 Kotlin 2.4.21 的 8.5.2–9.3.1 区间内 |
| CMP Gradle plugin + runtime/ui/foundation/material                                    | `1.12.2`                                                   | 官方稳定线；material3 按官方矩阵单独一行锁定                                        |
| material3（官方矩阵，解耦发布）                                                    | `1.12.0-alpha03`                                           | CMP 1.12.2 发布矩阵默认；基于 Jetpack M3 1.5.0-alpha22，含官方 Expressive 组件             |
| Material3 Adaptive / Navigation3 / Lifecycle / Navigation / Savedstate          | `1.3.0` / `1.2.0` / `2.11.0` / `2.10.0-rc01` / `1.4.0` | 随 CMP 1.12.2 发布矩阵；Navigation3 实测用 `1.2.0` 稳定版                          |
| quickjs-kt                                                             | `1.0.18`                                                  | Apache-2.0，三端原生产物齐备                                     |
| Ktor / serialization / coroutines / datetime                           | `3.6.1` / `1.12.0` / `1.11.0` / `0.8.0`                   | 三 target 可解析；datetime 0.8.0 的 Clock/Instant 已迁入 `kotlin.time`    |
| Ksoup / XmlUtil / korlibs-encoding                                        | `0.2.6` / `1.0.2` / `6.0.0`                               | `com.fleeksoft.ksoup:ksoup` / `io.github.pdvrieze.xmlutil:core` / `com.soywiz:korlibs-encoding`；三端冒烟通过 |
| SQLDelight                                                             | `2.4.1`                                                   | runtime + android-driver / native-driver / sqlite-driver 分 sourceSet，三端编译通过 |
| Coil 3 / Sketch 4 / ZoomImage                                           | `3.6.3` / `4.7.0` / `1.7.0`                               | 三者绑 skiko 0.150.1，CMP 1.12.2 用 0.150.2；M0 四 target 编译无版本警告            |
| WebView / PDF                                                          | `2.0.1` / `159.0.8138.0`                                  | `io.github.kevinnzou:compose-webview-multiplatform` / `dev.nucleusframework:pdfium` |
| Koin                                                                  | `4.2.2`                                                   | 运行时 DI，四 target 编译通过；不引入 Metro / kotlin-inject                        |

> **material3 必须显式写官方矩阵版本（M0 实测）**：CMP 的 material3 与主版本解耦发布，`org.jetbrains.compose.material3:material3:1.12.2` **不存在**；CMP Gradle plugin 的 `compose.material3` accessor 固定指向旧的 `1.9.0`（基于 Jetpack M3 1.4.0，无官方 Expressive 组件，`MaterialExpressiveTheme` 为 internal），**不随 plugin 版本走**。本项目按 1.12.2 官方矩阵锁 `1.12.0-alpha03`（基于 Jetpack M3 1.5.0-alpha22，含官方 Expressive），M0 已验证其与 `runtime/ui/foundation:1.12.2` 四 target 编译通过。
>
> AGP 9.2 要求 JDK 17+（本机 21）、NDK `28.2.13676358`（本机已装）、最高 API 37（本机已装）；默认 SDK Build Tools 为 36.0.0，本机装的是 37.0.0，首次同步会让 AGP 自动下载 36.0.0，也可在 `android` 块显式指定 37.0.0。
>
> **CMP 没有官方 `compose-bom`**（`org.jetbrains.compose` 下不存在该 artifact），版本只能靠 Version Catalog 逐项锁定；全文版本措辞统一以此表为准。本文锁定**选型、边界与约束**；P0 依赖已在 M0 完成四 target 可解析性 + 编译 / 符号冒烟验证，实测状态见 `feasibility.md` 第 4 节。稳定线下 Skiko 版本警告已消失（R20 关闭），剩余的是 material3 alpha 线 API churn（R5）、补缺口组件规范漂移（R16）与旧 Compose 库的运行期二进制兼容（R21）。

---


## 11. 测试策略

| 层次               | 内容                                                                    | 关键用例                                                                   |
| ---------------- | --------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| 契约测试             | `:source-contract` 的 TypeScript 声明与 Kotlin 模型对拍                       | 所有 Host API 签名、返回结构、错误码                                                |
| **跨端一致性测试**      | 同一组 `.js` 源在三端跑同一批 fixture，比对 JSON 输出                                 | `Network` / `Html` / `Convert` / Promise 调度 / Unicode / 大整数 / 日期       |
| 解释器测试            | QuickJS 封装层                                                           | 超时中断、内存上限、dispose 回收、异常不泄漏、并发隔离                                        |
| 沙箱测试             | 权限强制                                                                  | 越域请求被拒、静态扫描命中、`sendMessage` 白名单外抛错                                     |
| 领域测试             | 状态机与仓库                                                                | 进度合并、章节更新、ID 稳定性、迁移                                                    |
| 阅读器测试            | 分页与内存                                                                 | 超长图 tile、双页拼接、连续滚动预加载、EPUB 分页幂等                                        |
| UI 测试            | 组件与截图基线                                                               | MD3E 组件三端截图、自适应布局、导航 backstack 断言 + 深链接回放                              |
| 分发测试             | 侧载包签名与源仓库签名                                                      | 侧载包可安装、签名刷新可复现、仓库签名校验生效、App 内无预置源                          |
| **依赖可解析性测试**（新增） | `libs.versions.toml` 全量依赖在 android / ios / jvm target 均可解析            | 防止「库只支持 Android+iOS」导致 Desktop 构建失败（kmp-awesome 明确以 iOS/Android 为首要筛选） |
| **库行为一致性测试**（新增） | Ksoup / Kompress / Ketch / ZoomImage / Compose WebView 在三端跑同一 fixture | 选择器结果、归档条目顺序、下载续传、大图采样、页面标题回调                                          |

测试栈：**Kotest**（或 TestBalloon）+ **Turbine**（Flow）+ **Mokkery**（KMP mock）+ **Assertk**（断言）+ Compose UI 测试；调试用 Power-Assert 与 Kotlin Native Xcode Support。

**M0 必须先建立跨端一致性测试夹具与依赖可解析性矩阵**，否则后续所有源相关开发都会踩平台差异。

---


## 12. 里程碑与验收门槛

| 阶段              | 交付物                                                                         | 验收门槛（Gate）                                                                                                                                                                                                                                 |
| --------------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **M0 技术验证**     | quickjs-kt 三端集成 spike、官方 Expressive 组件三端渲染验证、Promise 桥、Ksoup 契约对齐、依赖可解析性矩阵、一致性测试夹具 | ① 一个真实 Venera 漫画源在三端产出**完全一致**的 JSON；② `memoryLimit = 32 MB` 生效、`withTimeout` 能中断 `while(true)`、16KB 镜像下 so 加载、iOS 无 JIT entitlement；③ 全部依赖在 android/ios/jvm 三 target 可解析；④ 官方 Expressive 组件三端截图一致；⑤ ZoomImage 无 OOM、PDF 平台矩阵确认（KCEF 已由 ADR-009 移出范围） |
| **M1 契约冻结**     | `:source-contract`、`.d.ts`、JSON Schema、示例源、契约文档                             | 契约评审通过；漫画 + 小说各一个源跑通；此后契约变更需走版本化流程                                                                                                                                                                                                         |
| **M2 数据层**      | Domain 模型、SQLDelight schema、仓库、源管理与安装流程                                     | 断网书架可用；迁移测试通过；签名/权限校验生效                                                                                                                                                                                                                    |
| **M3 漫画阅读器**    | ReaderStateMachine、解码层、分页/长条/预加载、下载队列                                       | 超大图不 OOM、翻页无白屏、进度不丢                                                                                                                                                                                                                        |
| **M4 小说与本地导入**  | EPUB/TXT/CBZ/PDF 解析、排版分页、书签、导入 UI                                           | 本地书与在线书在书架同构展示                                                                                                                                                                                                                             |
| **M5 设计系统与 UI** | MD3E 组件库、全部 feature 目的地、Navigation3 自适应                                     | 三端截图基线通过；手机单窗格 / 平板双窗格 / Desktop 三窗格后退与状态恢复一致；桌面快捷键与拖放可用                                                                                                                                                                                   |
| **M6 平台与后台**    | expect/actual 全清单、后台任务、WebView 登录、通知/托盘                                     | 三端后台行为符合 1.2 能力表                                                                                                                                                                                                                           |
| **M7 安全与发布**    | 沙箱审计、静态扫描、侧载分发与源仓库签名、合规评审                                  | 越权用例全部被拒；侧载包可安装且签名刷新流程可复现；App 内无预置源                                                        |

**顺序不可调换的理由**：M1 的契约是 M2～M5 的共同输入；M0 若证明 quickjs-kt 在 iOS 侧无法稳定运行，需回到 ADR-002 / ADR-003 重新决策，此时只有 spike 成本。

---


## 13. 风险登记表

| #   | 风险                                             | 影响 | 概率 | 缓解措施                                                                                                                          |
| --- | ---------------------------------------------- | -- | -- | ----------------------------------------------------------------------------------------------------------------------------- |
| R1  | iOS 侧载渠道政策变化或签名失效                     | 中  | 中  | 并行保留 AltStore / TestFlight / 企业签；签名刷新自动化；关注侧载政策变化                                               |
| R2  | quickjs-kt 在三端行为不一致、内存/中断 API 不符预期             | 高  | 中  | M0 spike 前置；同一份 QuickJS C 由依赖保证；一致性测试夹具；备选 WASM 沙箱或换回自研绑定                                                                     |
| R3  | 插件恶意代码 / 隐私外发                                  | 高  | 中  | 域名白名单、KV 隔离、静态扫描、审计日志、用户显式安装、一键禁用                                                                                             |
| R4  | 大图导致 OOM（尤其 iOS）                               | 高  | 高  | 分块解码、字节预算 LRU、窗口 ≤3 页、显式回收、内存警告监听                                                                                             |
| R5  | material3 1.12.0-alpha03 属 alpha 线，API churn / 回归  | 中  | 中  | Version Catalog 单点锁定 + `designsystem/theme` 薄封装；截图基线；随 CMP 1.12.2 官方矩阵升级，不自选版本                                    |
| R6  | Venera 源兼容不完整导致生态复用打折                          | 中  | 中  | 明确兼容边界（4.1）；提供适配层与源 CI 校验工具；优先兼容 `explore/search/loadInfo/loadEp` 主链路                                                         |
| R7  | native 崩溃拖垮宿主进程                                | 中  | 中  | Android 独立 `:source` 进程可选；中断/超时/dispose；崩溃上报与自动禁用异常源                                                                          |
| R8  | 桌面端 HTML 解析/图片解码性能                             | 中  | 中  | 纯 Kotlin 解析器 + JVM 解码基准测试；缓存解析结果                                                                                              |
| R9  | 源站点改版导致大面积失效                                   | 中  | 高  | 源版本化与回滚、错误日志、源更新检查、社区仓库机制                                                                                                     |
| R10 | 版权与内容合规                                        | 高  | 低  | 不预置源、不托管内容、仅用户自配置；文档明确工具定位                                                                                                    |
| R11 | 误把 quickjs-kt 的隔离 context 当成安全沙箱               | 高  | 中  | ADR-003 明确隔离 context ≠ 沙箱；能力白名单在 Host API 出口强制；Android 可选独立 `:source` 进程                                                      |
| R12 | 第三方库在 Desktop target 缺 artifact 或能力降级          | 中  | 中  | M0 依赖可解析性矩阵；7.4 逐项列出降级路径（WebView → Cookie 导入，ADR-009；PDF → 三端兜底；后台 → 常驻协程）                                                                |
| R13 | ZoomImage 与连续滚动手势冲突、自定义 `ImageSource` 不足       | 中  | 中  | M0 压测；保留 `expect/actual` 解码兜底与自绘连续模式                                                                                          |
| R14 | 多模块导致 iOS framework 导出爆炸 / Swift API 难用        | 中  | 中  | 单一 umbrella `:app:shared-ui` + 显式 `export()`；SKIE / KMP-NativeCoroutines；CI 检查导出面（Navigation3 不新增导出面，route key 留在 commonMain） |
| R17 | Navigation3 在 CMP 侧仍年轻，Scene/TypeMap API 演进    | 中  | 中  | 随 CMP 1.12.2 矩阵锁 `navigation3-ui:1.2.0`；M0 先跑自适应 spike（单/双/三窗格 + 后退 + 状态恢复）；`SceneStrategy` 薄适配 |
| R15 | 约 30 个依赖带来升级回归与体积膨胀                            | 中  | 中  | Version Catalog 单点锁定、依赖可解析性测试、按模块最小化 `api` 暴露、桌面包体积基线                                                                         |
| R16 | 补缺口的 MD3E 组件与官方规范漂移                            | 中  | 低  | 官方组件优先；补缺口层用 Compose Unstyled 骨架 + token 化 + 三端截图基线 + 稳定性分析                                                          |
| R18 | quickjs-kt 为单人维护的第三方库，Kotlin 版本跟随可能滞后          | 中  | 中  | 锁 1.0.18；保留 WASM 沙箱备选；M0 验证 2.4.21 klib 可链接；每次升级重跑一致性夹具                                                                       |
| R19 | CMP 稳定线与其它 KMP 库编译时 Kotlin 版本不一致          | 中  | 中  | 已完成 M0 四 target 依赖可解析性矩阵；Version Catalog 单点锁；必要时 `disableNativeCache` 临时规避                                     |
| R20 | UI 库绑定 skiko 0.150.1 与 CMP 版本不一致（已关闭）       | 低  | 低  | CMP 1.12.2 用 skiko 0.150.2，仅差一个补丁号；M0 四 target 编译**未出现任何 Skiko 警告**。升级 CMP 时重新检查该矩阵                          |
| R21 | WebView / PDF / Koin / ZoomImage 基于旧 Compose（1.8.0~1.12.0）编译                | 中  | 中  | 1.12.2 稳定线与这些库的版本距离较 beta 线更近；Gradle 取最高版本后四 target 编译通过，实际渲染前不可判定二进制兼容，M1 首个页面即跑三端截图基线；桌面端已按 ADR-009 移出，风险面收窄到 android/ios |

---

## 14. 技术栈可行性验证（先验证，后开工）

> 用户要求：**先补文档，再验可行性**。本章是 M0 的执行清单，不写实现，只定“验什么、怎么算过、不过怎么降级”。

### 14.1 原则

1. 文档冻结选型，验证只回答“在 android / ios / jvm 三 target 是否可解析、行为是否一致”；
2. 任一库缺 Desktop artifact 即记为降级，不阻主链（降级路径见 7.4）；
3. Navigation3 与依赖矩阵并行验证，共用同一个空壳 App（单 `NavDisplay` + 一条 fixture backstack）；
4. 引擎绑定与 MD3E 组件均使用现成依赖，M0 只验证「现成依赖是否满足 4.6.3 / 9.1 的约束」。

### 14.2 Navigation3 自适应 spike（P0）

| #  | 用例                                                          | 断言                                                          |
| -- | ----------------------------------------------------------- | ----------------------------------------------------------- |
| N1 | 手机单窗格：`[Shelf]` → `add(Detail)` → `removeLast()`            | 一次只渲染栈顶 1 个 Entry，后退回到 Shelf                                |
| N2 | 平板双窗格（宽 >600dp）：`[Shelf, Detail]`                           | `ListDetailScene` 同时渲染两窗格，窄屏自动退化单窗格                         |
| N3 | 状态恢复：杀进程 / 配置变更后重建                                          | `rememberNavBackStack` 恢复同一 backstack，`Detail(workId)` 参数不丢 |
| N4 | 深链接回放：外链 → `[Shelf, Detail]`；分享 → `[Shelf, Detail, Reader]` | backstack 内容精确等于预期                                              |
| N5 | ViewModel 作用域：旋转 / 窗口resize 后                               | 同一目的地 ViewModel 不重建，`StateFlow` 不丢；不同目的地隔离                  |

版本：随 CMP 1.12.2 发布矩阵锁 `org.jetbrains.androidx.navigation3:navigation3-ui` 为 `1.2.0`（runtime 由 `androidx.navigation3` 传递引入），按需 `lifecycle-viewmodel-navigation3`。


### 14.3 依赖可解析性矩阵（P0/P1/P2）

> ✅ 表示 M0 已实测「四 target 依赖可解析 + 编译 / 符号冒烟通过」；「行为一致用例」列尚未执行，判绿以 `feasibility.md` 第 3 节为准。

| 组  | 库                                                                                  | 三 target 可解析                     | 行为一致用例                                                                                                                            | 缺口降级                                   |
| -- | ---------------------------------------------------------------------------------- | -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- |
| P0 | **quickjs-kt 1.0.18**                                                              | ✅ 四 target 编译 + JVM 行为通过     | `.js` 源三端 JSON 一致；`memoryLimit = 32 MB` 生效；`withTimeout` 中断 `while(true)`；16KB 镜像 so 加载；`asyncFunction` 驱动 Promise；`JsObject` 往返 | 自研绑定 / WASM 沙箱                         |
| P0 | **CMP 1.12.2 官方矩阵 + 官方 Expressive 组件**                                             | ✅ 四 target 编译通过                 | `MaterialExpressiveTheme` + 官方 FloatingToolbar / LoadingIndicator 三端截图一致；补缺口组件贴近规范                                          | 随官方矩阵升级                          |
| P0 | Ksoup / XmlUtil / korlibs-encoding                                                 | ✅ 可解析 + 冒烟通过                  | 同一 HTML fixture 选择器结果一致、`dispose` 后不可用                                                                                            | —（必须过）                                 |
| P0 | Ktor 3（OkHttp/Darwin/CIO）+ kotlinx-serialization                                   | ✅ 三 engine 分置编译通过             | 白名单/重定向二次校验一致                                                                                                                     | —                                      |
| P0 | SQLDelight（Room 小表并行对照）                                                            | ✅ 三 driver 编译；migration 未验     | 同一 `.sq` 增删改查一致                                                                                                                   | Room 备选                                |
| P0 | ZoomImage + Sketch/Coil3                                                           | ✅ 可解析 + 冒烟通过                  | 超大图无 OOM、长图 Read Mode、手势不冲突                                                                                                       | 自绘连续模式兜底                               |
| P0 | ComposePdfReader                                                                   | ✅ 三端 variant 齐备                  | 渐进渲染 + 文本选择                                                                                                                       | 无需降级（已覆盖三端）                        |
| P0 | Compose WebView（Android/iOS）                                                        | ✅ android/ios variant 齐备           | `pageTitle/lastLoadedUrl/evaluateJavaScript`                                                                                      | 桌面不引入，改 Cookie 导入（ADR-009）          |
| P0 | Koin（ViewModel 工厂）                                                             | ✅ 四 target 编译通过                 | 同一 ViewModel 三端创建                                                                                                                 | —                                      |
| P1 | Ketch                                                                              | JVM artifact + Android/iOS       | 暂停/续传/重启 + `download_task`                                                                                                        | DIY-Ktor 兜底                            |
| P1 | FileKit/AppDirs/io                                                                 | 三 target                         | 选择/保存 + 根路径往返                                                                                                                     | iOS `Application Support` actual       |
| P1 | KVault/KSafe（二选一）                                                                  | Android/iOS + 桌面加密文件             | 往返 + 备份排除                                                                                                                         | 桌面加密文件                                 |
| P1 | KMP WorkManager                                                                    | Android/iOS                      | 语义文档化（iOS≠WorkManager）                                                                                                            | 桌面常驻协程                                 |
| P2 | MaterialKolor/kmPalette/Calf/Haze/Rinku/paging/DND/Markdown/RichEditor/Kim/cache4k | 逐个三 target                       | 缺 desktop 即降级，不阻主链                                                                                                                | 按 7.4 降级                               |

判绿标准：M0 Gate ①真实 Venera 源三端 JSON 一致 ②iOS 无 JIT + 内存/中断/16KB 镜像三项通过 ③上表 P0 全绿（P1/P2 允许降级有主）④官方 Expressive 组件三端截图一致 ⑤ZoomImage 无 OOM ⑥PDF 平台矩阵确认（KCEF 按 ADR-009 移出范围）。

**回退顺序**：quickjs-kt 不达标 → 先试 1.0.x 其它补丁版，再考虑 WASM 沙箱或自研绑定；material3 官方 alpha 线出现阻塞性回归 → 锁到 1.12.2 矩阵的其它补丁版或等待官方更新，**不自行改用 `1.9.0`**（会丢失官方 Expressive 组件）。

---

## 附录 A · Venera 源兼容映射表

| Venera                                                 | 本文                               | 处理方式                               |
| ------------------------------------------------------ | -------------------------------- | ---------------------------------- |
| `class X extends ComicSource`                          | 同                                | 注入同名基类，新增 `kind` / `apiVersion` 字段 |
| `Comic` / `ComicDetails`                               | `Work` / `WorkDetails`           | 宿主规范化：`comics` 与 `works` 均接受       |
| `comic.loadInfo(id)`                                   | 同                                | 保留                                 |
| `comic.loadEp(comicId, epId)`                          | `ChapterPayload.Images`          | 保留 JS 形状，宿主转模型                     |
| `explore.load` / `search.load` / `categoryComics.load` | 同                                | 保留，返回结构规范化                         |
| `account.*` / `favorites.*` / 评论评分                     | `SourceCapability`               | 保留，未实现则宿主隐藏 UI                     |
| `settings` + `this.loadSetting`                        | 同                                | 同步桥白名单                             |
| `sendMessage({method:'load_data'...})`                 | 同步桥                              | **重新实现**，仅本地白名单                    |
| `Network.get/post/...`                                 | 同                                | 异步桥 + 域名白名单 + 审计                   |
| `HtmlDocument` / `HtmlElement`                         | 同                                | 纯 Kotlin 解析器实现，需 `dispose()`       |
| `Convert.*`                                            | 同                                | 纯 Kotlin 实现，不经平台差异                 |
| `UI.*`                                                 | 同                                | 需 UI 上下文；桌面端降级                     |
| `ImageLoadingConfig.modifyImage`                       | 同                                | 独立 context + 独立超时                  |
| `index.json`（`url` / `filename` 二选一）                   | 同 + 签名字段                         | 兼容旧仓库（签名字段可选，标记未验证）                |
| `flutter_qjs`                                          | `quickjs-kt`（QuickJS C + KMP 绑定） | 引擎替换，JS 契约不变                       |


## 附录 B · 示例小说源（契约示意）

```javascript
// ==SourceScript==
// @name        示例小说源
// @key         com.example.novel
// @version     1.0.0
// @apiVersion  2
// @minApp      1.0.0
// @kind        novel
// @domains     example.com
// @capabilities search,chapterText
// ==/SourceScript==

class ExampleNovel extends ComicSource {
  name = '示例小说源'
  key = 'com.example.novel'
  version = '1.0.0'
  minAppVersion = '1.0.0'
  apiVersion = 2
  kind = ['novel']
  url = 'https://example.com/sources/novel.js'

  search = {
    load: async (keyword, options, page) => {
      const res = await Network.get(
        `https://example.com/search?q=${encodeURIComponent(keyword)}&page=${page}`,
        { 'User-Agent': 'Mozilla/5.0' }
      )
      const doc = new HtmlDocument(res.body)
      const works = doc.querySelectorAll('.book-item').map(el => ({
        id: el.querySelector('a').attributes.href.split('/').pop(),
        title: el.querySelector('.title').text,
        subtitle: el.querySelector('.author')?.text,
        cover: el.querySelector('img')?.attributes.src,
        tags: [],
        kind: 'novel'
      }))
      doc.dispose()
      return { works, maxPage: 1 }
    }
  }

  novel = {
    loadInfo: async (id) => {
      const res = await Network.get(`https://example.com/book/${id}`, {})
      const doc = new HtmlDocument(res.body)
      const chapters = doc.querySelectorAll('#chapter-list a').map((el, i) => ({
        id: el.attributes.href.split('/').pop(),
        title: el.text,
        ordinal: i
      }))
      const details = {
        work: {
          id, title: doc.querySelector('h1').text,
          cover: doc.querySelector('.cover img').attributes.src,
          tags: [], kind: 'novel'
        },
        chapters
      }
      doc.dispose()
      return details
    },
    loadChapter: async (workId, chapterId) => {
      const res = await Network.get(
        `https://example.com/book/${workId}/${chapterId}`, {}
      )
      const doc = new HtmlDocument(res.body)
      const text = doc.querySelector('#content').innerHtml
      doc.dispose()
      return { text, format: 'html' }
    }
  }
}
```

## 附录 C · 术语

| 术语                 | 含义                                                                         |
| ------------------ | -------------------------------------------------------------------------- |
| 源 / Source         | 一个 `.js` 插件，负责把某个站点映射为统一领域模型                                               |
| Host API           | 宿主注入到 JS 上下文的全局对象集合                                                        |
| 同步桥 / 异步桥          | `sendMessage` 兼容通道 / `__hostCall` Promise 通道                               |
| SourceDispatcher   | 每个源绑定的串行调度器，隔离状态与并发                                                        |
| ChapterPayload     | 章节载体的密封类型，区分图片页与文本正文                                                       |
| Work               | 统一作品实体（漫画或小说）                                                              |
| 能力来源分层             | ① 纯 Kotlin `commonMain` ② KMP 库 ③ 平台 `actual`；新增 ③ 需评审（ADR-006）                                        |
| 单页渲染器              | ZoomImage 承载单页大图采样与缩放；外层自研状态机负责章节序列与进度（ADR-007）                            |
| umbrella 导出        | 仅 `:app:shared-ui` 编为 iOS framework 并显式 `export()` 必要模块                    |
| 双层桥                | 同步桥（`sendMessage` 兼容）+ 异步桥（`__hostCall` Promise），见 4.6                     |
| 依赖可解析性矩阵           | M0 验证 `libs.versions.toml` 全部依赖在 android / ios / jvm target 均有 artifact    |
