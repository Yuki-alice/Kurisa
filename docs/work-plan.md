# 工作规划（唯一活计划位）

| 项目 | 内容 |
| --- | --- |
| 文档状态 | **活跃（2026-11-13 起）**。本文是后续实施的唯一计划入口；`comic-e2e-plan.md` 已完成，降为验收记录。**§2 是后端功能专项**，其余章节保留 UI / 平台 / 生态视角，重叠任务以 §2 为准 |
| 范围 | 核心功能（后端优先）、M5（UI / 设计系统）、M6（平台能力）、M7（发布）与 P2 源生态 |
| 前置 | P0 清障 + P1 漫画端到端数据链路已落地（主仓 `4b238cf` / `c06ef4f`）；M4 小说已合并 `867167e` |
| 当前基线 | `desktopTest` **502 用例 / 0 失败 / 0 错误 / 0 跳过**（2026-11-14 凌晨合并远端后复核）；`:app:android:assembleDebug`、iOS 模拟器编译通过；源仓门禁与 `SourcesRepoContractTest`（52 源）真实跑通 |
| 最近交接 | **`handoff-2026-11-13.md`**：本轮已落地的核心能力、四处「已实现但没接上」的缺口、接手顺序与已知偶发问题 |
| 纪律 | `AGENTS.md` 任务→技能路由；C1 主仓零真实站点；ADR-005 漫画/小说共用一条管线；ADR-006 默认不加 `expect/actual`；改桥/Cookie/白名单前先 `security-audit` |

> 本文不维护版本号与变更摘要，只改正文。每阶段独立提交、可单独回滚。

---

## 1. 现状快照

**已接通**：插件引擎长驻运行时 + 版本路由 + `rules/v1`；源安装/预览/二次确认/落盘入库；
`SourceCatalog.search/detail/bindWork` 与按作品路由 `WorkSourceIndex`；Search/Detail 两个 MVI 屏；
阅读器章节/正文/图片出口（共享 `CookieStore`/`HttpClient`）；本地 CBZ/图片目录/EPUB/PDF/**TAR/CBT** 导入（Desktop FileDialog + Android SAF）；
小说 M4 排版/书签/划线/笔记；两条 walkthrough 自动回归；主仓 CI。

**第一批已接通**（2026-11-13 夜，`BE-A1`…`BE-A4`）：下载编排 `createDownloadController` 双端接线、
下载页 tasks / 暂停 / 续传 / 取消、详情页「下载本章」；书架加入与移出入口；
源管理页「检查更新」（索引由宿主注入、只读本地文件）；`SourceCatalog.explore` 与最小发现页入口。
另补**最小导航壳**：顶层四页签（书架 / 搜索 / 探索 / 下载）+ 顶栏「源管理 / 设置」，此前 9 条路由注册却无入口可到达。

**Koin 已部分落地**：`AppContainer`（`app/shared-ui`，`koin-core`）已接管共享网络栈
（`CookieStore` / `HttpClient` / `ImageFetcher` 一份），阅读器不再自建 CookieStore；
**剩余** `BE-A5`：Search / Detail 两个 VM 工厂仍手写，`koin-compose-viewmodel` 未引入。

**未完成**（本规划要解决的）：
`BE-A5` 剩余部分（VM 工厂收口）；`settings` 屏仍是 `TODO` 占位；
`HostUi` 默认拒绝（WebView 登录 M6）；VIDEO/AUDIO 渲染显式抛 CONTRACT；iOS Xcode 壳与三端截图基线；
截图基线未补进 walkthrough；发版签名与源签名信任模型；两仓 `index schema` 双版本漂移；
`rules/v1` 覆盖率 1/52；真实站点可用性零回归。

---

## 2. 核心功能与后端专项

### 2.1 核心功能的定义（用户口径）

**核心功能 = 用户能完整走通的能力闭环**，与视觉表现无关：内容接入（插件源 / 本地文件）→ 发现（搜索 / 浏览 / 订阅更新）→ 阅读（漫画 / 小说共用管线）→ 沉淀（书架 / 进度 / 书签）→ 离线（下载 / 缓存）→ 数据保护（迁移 / 备份 / 登录态）。

**表现层后置**：`design/` 的 M3 Expressive 原型仍在重新设计，当前所有 UI 只做功能接线与最简可用形态，
定稿后整体换样。因此本节只规划后端（`core:*`/`data:*`/`source:*` 与 `app` 的装配层），
不排期任何视觉还原；UI 侧只保留「让后端能力可触达」所必需的最小入口。

### 2.2 后端能力域地图（源码取证，非文档转述）

| 能力域 | 模块 | 已实装 | 状态 / 证据 |
| --- | --- | --- | --- |
| 领域模型 / 稳定 ID | `core/model` | `DomainModels`、`SecureTypes`、`WorkIds`、`ChapterPayload` | `InMemorySecureStorage`（`SecureTypes.kt:44`）是内存壳，平台后端缺失 |
| 阅读内核 | `core/reader` | 状态机 / 交互 / 设置持久化接口 | 漫画小说共用一条 `ChapterProvider`（ADR-005） |
| 基础设施 | `core/common` | 调度 / 重试 / 防抖 | `NoopAppLog`（`Common.kt:63`）无日志后端；`core/platform` 仅接口 |
| 持久化 | `data/db` | 13 表、`migrations/1-4.sqm`、可回滚脚本 | `verifyMigrations.set(false)`（`data/db/build.gradle.kts:67`）；`cookie` 表零读写、`chapter` 表仅测试引用 |
| 网络出口 | `data/network` | 白名单逐跳复核 / CF 判定 / GBK / Cookie 导入 | `CookieStore`（`CookieStore.kt:15`）纯内存，重启丢 `cf_clearance` 与配对 UA |
| 仓储 | `data/repository` | Shelf / Progress / Kv / DownloadTask / **Bookmark**（`BookmarkRepository.kt:22`）/ Highlight / LocalBook / LocalLibrarySync / ReaderSettings | 均已实装并接入装配层 |
| 下载 | `data/download` | `DownloadManager`（`DownloadManager.kt:82`）、RangeDownload、Controller 接口 | 顺序分片循环（`DownloadManager.kt:185`）；已接入生产（`BE-A1`） |
| 本地导入 | `data/localimport` | 格式识别 / ZIP+TAR 归档 / EPUB / ComicInfo / 章节识别 / 扫描 / 封面 / `LocalChapterProvider` / `LocalImageFetcher` | 完整，无 stub |
| 图片与缓存 | `data/images`、`data/cache` | ImageFetcher / Subsampling / CropBorders / ByteBudgetLru | 完整 |
| 契约与策略 | `source/contract` | 模型 / 能力位 / 错误码 / 规则模型 / 归一化 | `SUPPORTED_API_VERSIONS = {2,3,4}` |
| Host API | `source/hostapi` | 七命名空间接口 | `NoopHostUi`（`HostApiImpls.kt:349`）默认拒绝 WebView 登录 |
| 插件引擎 | `source/bridge` | 长驻 QuickJS / `rules/v1` / 版本路由 / 遥测 / 纯函数缓存 | VIDEO/AUDIO 渲染抛 CONTRACT（`SourceRuntime.kt:207`） |
| 源注册与订阅 | `source/registry` | 安装 / 签名 / 静态扫描 / `SourceIndexParser`（`SourceRepoSubscription.kt:36`）/ `SourceRepoPlanner`（`:100`）/ `SourceRepoSubscription`（`:170`） | 订阅链路已接源管理页「检查更新」（`BE-A3`，索引只读本地文件） |
| 装配层 | `app/shared-ui` | `SourceCatalog`（`ReaderSourceAssembly.kt:311`，工厂 `:675`）、`ShelfBindings`、`DownloadBindings` | 搜索 `:342` / 探索 `:370` / 详情 `:396` / 封面 `:485` / 订阅 `:510`、`:524` / 安装 `:587` / 按作品路由 `:330` 均已接通；下载工厂 `DownloadBindings.kt:70` 由双端壳调用 |

### 2.3 后端断点（按「让用户真正用得到」排序）

> A 类四项已于 2026-11-13 夜接通（状态见 §2.4 注记），下表保留取证与仍未解的断点。

| 类 | 断点 | 证据 |
| --- | --- | --- |
| A ✅ 已接通 | 下载编排进生产 | `DownloadBindings.kt:70` 工厂已由双端平台壳调用，`App` 接 `DownloadsScreen`（`BE-A1`） |
| A ✅ | 加入 / 移出书架 | `ShelfBindings.kt:44`、`:75` 已由搜索 / 详情 / 书架屏回调触发（`BE-A2`） |
| A ✅ | 源订阅入口 | `ReaderSourceAssembly.kt:510`、`:524` 已由源管理页「检查更新」触发（`BE-A3`） |
| A ✅ | Explore 后端出口 | `SourceCatalog.explore`（`ReaderSourceAssembly.kt:370`）已补，`ExploreScreen` 消费（`BE-A4`） |
| A 部分落地 | Koin 只接管了共享网络栈；Search / Detail 的 VM 工厂仍手写 | `app/shared-ui/.../AppContainer.kt:27` 有 `startKoin`；`Search.kt:143`、`Detail.kt:132` 仍是手写 Factory（`BE-A5`） |
| B 能力缺失 | Cookie 持久化 / 章节表落地 / 更新调度 / 备份恢复 / 历史统计 / 日志后端 / 遥测消费出口 | `CookieStore.kt:15`、`data/db/build.gradle.kts:67`、`Common.kt:63` |
| B | SecureStorage 平台后端、HostUi / WebView 登录 | `SecureTypes.kt:44`、`HostApiImpls.kt:349` |
| C 架构收口 | `verifyMigrations` 关闭、两仓 schema 漂移、VIDEO/AUDIO 分支、真实站点零回归 | `data/db/build.gradle.kts:67`、`SourceRuntime.kt:207` |

### 2.4 后端任务分解

> 依赖列指向本节任务 ID；每项独立提交、可单独回滚。`A*` 即**第一批**：A1–A4 是「已实装但没接上」，
> A5 是 Koin 架构收口，整体投入产出最高，先做。
> 表中「对应 §3」列给出与 UI / 平台视角任务的映射，避免两处重复排期。

| ID | 任务 | 改动点 | 依赖 | 验收门 | 对应 §3 |
| --- | --- | --- | --- | --- | --- |
| **BE-A1** | 下载编排进生产 | 双端构造 `createDownloadController`：`KtorRangeFetcher` + `CallbackDownloadSink`（JVM `File` 分片，不新增 `expect/actual`）+ `resolveUrls` 过滤 `http(s)` + `currentDomains()`；`App` 加 `downloads` 参数并接 `DownloadsScreen` 的 tasks/暂停/续传/取消 | — | 下载页可见真实任务、暂停后续传、重启恢复；`desktopTest` 不回归 | A4 |
| **BE-A2** | 书架闭环 | `SearchScreen` 结果行 / `DetailScreen` 加 `onAddToShelf`，`ShelfWorkRow` 加「移出」；`App` 接 `addFromSearch` / `removeFromSource` | — | 搜索加入 → 书架可见 → 打开详情 → 移出生效 | A3 |
| **BE-A3** | 源订阅入口 | `SourcesScreen`「检查更新」→ `planRepoUpdates` / `refreshRepoUpdates`；`fetchIndex` 由宿主注入并**先过 `security-audit` + `UrlPolicy`**（这是唯一引入用户任意 URL 的链路） | — | 坏索引不崩、minApp 过滤生效、候选可展示；`UrlPolicyTest` 绿 | — |
| **BE-A4** | Explore 后端出口 | 在 `SourceCatalog` 补 `explore/category` 出口，复用 `runtimeFor` 装配与能力门；结果带 `sourceKey` | — | 有源 / 无源 / 单源失败三态用例；不影响现有 search 基线 | A2 |
| **BE-A5** | Koin 收口（剩余部分） | 共享网络栈已由 `AppContainer` 接管（`koin-core`）；**剩余**：补 `koin-compose-viewmodel:4.2.2`，把 `SearchViewModelFactory`（`Search.kt:143`）与 `DetailViewModelFactory`（`Detail.kt:132`）换成 `viewModelOf` / `koinViewModel` | BE-A1, BE-A2 | 逐模块替换、每步全量回归；用例数只增不减 | B1 |
| **BE-B1** | Cookie 持久化 | `CookieStore` 落 `cookie` 表，`cf_clearance` 与 `pairedUserAgent` 绑定不变；先 `security-audit` + `privacy-audit` | BE-A3 | 重启后登录态与配对 UA 可恢复；`CookieStoreTest` / `UrlPolicyTest` 绿 | B2 |
| **BE-B2** | 章节缓存与更新对比 | `chapter` 表接 `BridgeChapterProvider.chapters`，写 `updated_at` 供增量对比 | — | 章节列表离线可读；更新对比用例 | — |
| **BE-B3** | 更新调度器 | 纯 Kotlin 轮询编排（按源 → `chapter.updated_at` 对比 → 更新候选），平台触发走接口注入；ADR-006 不加 `expect/actual` | BE-B2 | 调度器单测 + 三端可编译；不依赖真实站点 | — |
| **BE-B4** | 备份 / 恢复 | JSON 导出（shelf/work/progress/source/settings + 源版本 pin），按 `stableId` 合并、last-write-wins | — | 导出→清空→恢复后数据一致；格式可手改 | — |
| **BE-B5** | 历史 / 阅读统计 | `last_read_at` 写透 + 时长累计 + 历史数据源 | — | 续读与按日分组用例 | — |
| **BE-B6** | 日志与遥测后端 | `NoopAppLog` → 平台后端；现有 `EngineTelemetry`/`NetworkTelemetry`/`onTrace` 增加消费出口 | — | 一次调用可导出 init/net/parse 分段；不吞异常 | B4 |
| **BE-B7** | 下载增强 | 顺序分片（`DownloadManager.kt:185`）→ 可配并发；同步修正 `DownloadSink.completedParts` 的「分片连续」假设 | BE-A1 | 乱序完成不丢片；续传用例绿 | — |
| **BE-B8** | 迁移校验 | 补 schema 快照并打开 `verifyMigrations`（`data/db/build.gradle.kts:67`），migration 必须可回滚 | — | 4 个迁移全链校验通过 | — |
| **BE-C2** | SecureStorage 平台后端 | `InMemorySecureStorage` → Android Keystore / iOS Keychain / 桌面加密文件 | BE-B1 | 隐私评审通过；token 不落明文 | — |
| **BE-C3** | HostUi / WebView 登录 | `NoopHostUi` → 平台实现，桌面走 Cookie 导入兜底（ADR-009） | BE-B1 | 安全评审 + `UrlPolicyTest` / `CookieStoreTest` 绿 | B2 |
| **BE-C4** | VIDEO/AUDIO 渲染分支 | `SourceRuntime.kt:207` 起的抛错分支补类型化 payload 后再允许声明能力 | — | 两类 payload 用例；不影响 MANGA/NOVEL | B3 |
| **BE-C5** | 两仓 schema 统一 | 主仓 `contract/index-schema.json` 与源仓 `index.schema.json` 合一 | — | `npm test` + `SourcesRepoContractTest` 双绿 | C1 |
| **BE-C6** | 真实站点回归门禁 | C1 边界内（本地 refs、不写真实站点进主仓）补选择器/取图回归样本 | BE-C5 | 规则源覆盖率有明确数字；0 新增 SKIP | C2 |

**执行顺序**：BE-A1 → BE-A2 → BE-A4 → BE-A3（安全评审前置）→ **BE-A5**（Koin 收口，第一批最后一项）
→ BE-B1（与 A3 同批评审）→ BE-B2/B3/B4/B5/B6/B7/B8 → BE-C2/C3/C4/C5/C6。

**第一批 = `BE-A1`…`BE-A5`**，不依赖设计稿，是「先完成核心功能」的本批。Koin 放在本批收尾，
理由是 B 批还要新增页面与仓储，先有 DI 提供方就不必先写手工厂再返工。B/C 批按安全与依赖排序，可分多次会话推进。

> **A 批当前状态（已接通，2026-11-13 夜）**：`BE-A1` 下载编排进生产（双端平台壳构造 `createDownloadController`，
> 详情页「下载本章」入队、下载页消费 tasks 与暂停/续传/取消）；`BE-A2` 搜索/详情加入书架与书架移出；
> `BE-A3` 源管理页「检查更新」（索引由宿主注入、**只读本地文件**，不发起任意 URL 请求）；
> `BE-A4` `SourceCatalog.explore` 出口 + 三态聚合 + 最小发现页入口。
> **另补最小导航壳**：`App.kt` 顶层四页签（书架 / 搜索 / 探索 / 下载）+ 顶栏「源管理 / 设置」，
> 详情与阅读器仍全屏沉浸。此前 9 条路由全部注册但**无任何入口可到达**，A 批能力用户实际摸不到；
> 该壳是临时形态，原型定稿后整体换样。
> 安全口径：下载复用 `KtorRangeFetcher` 既有的白名单逐跳复核与 `cf_clearance`/配对 UA，
> 订阅链路 fail-closed 不引入新网络出口。回归见 `BatchAWiringTest` / `ExploreAggregationTest`。
> **第一批剩余**：`BE-A5`（Koin 收口）——共享网络栈已由 `AppContainer` 接管，
> 只剩 Search / Detail 两个 VM 工厂待换 `viewModelOf`；做完即第一批闭环。
> 上述 A1–A4 能力已于 2026-11-14 凌晨在远端重做的 UI / 装配基线上重放并回归
> （在线书架、加入/移出、检查更新、Explore、下载编排、最小导航壳均按新接口重新接线）。

---

## 3. 阶段任务（UI / 平台 / 生态视角）

> 本节保留 UI 与平台视角的排期；**属于后端的任务已在 §2.4 统一拆解**，重叠项以 §2 为准，
> 本节只描述表现层 / 平台壳 / 源生态的互补工作。

### P2-A · 把 UI 占位屏接通（M5）

> **优先级调整（2026-11-13 夜，用户口径）**：设计原型仍在重做，**视觉表现层全部后置**。
> 当前先做「核心功能的生产接线」——即 `handoff-2026-11-13.md` §3 的四处缺口（下载编排、
> 加入/移出书架、源订阅入口），它们不依赖设计稿，且决定用户能否真正用到已落地的能力。
> 本节 A2–A4 中标注「数据层已完成」的部分属于该批接线；纯视觉还原等 A1 定稿。

| 任务 | 改动点 | 验收门 | 回滚 |
| --- | --- | --- | --- |
| A1 设计原型定稿 | `design/`（13 屏 M3 Expressive 原型 + tokens）**当前在工作树中整体为未提交删除**，属原型重做中的状态。定稿前不按它排期、不做视觉还原；这批删除由用户自行处理，本规划不提交、不恢复、不引用 | 原型定稿并明确版本；`docs/README.md` 的 design 条目与本表同步 | 无（不排期即无改动） |
| A2 Explore 屏 | **已完成最小入口**：`SourceCatalog.explore`（`ReaderSourceAssembly.kt`，走 `categoryComics.load`）+ `aggregateExplore` 三态聚合；`ExploreScreen` 由 App 层注入 loader，空 `sourceKey` 表示聚合全部启用源。视觉待定稿 | 有源 / 无源 / 单源失败三态（`ExploreAggregationTest`）；`NavRoutesTest` 不回归 | 独立提交，可单屏关闭 |
| A3 Shelf 屏（数据层已完成） | `ShelfScreen` 已重写（在线收藏 + 本地书同构分区、封面、导入、删除）并双端注入；加入 / 移出入口已接（`BE-A2`） | 搜索加入 → 书架可见 → 打开详情 → 移出生效；`ShelfBindingsTest` 绿 | 同上 |
| A4 Downloads / Settings | **已完成接线**：双端构造 `createDownloadController`，详情页「下载本章」入队，下载页消费 tasks 流与暂停/续传/取消（`BE-A1`）。`feature/settings/Settings.kt` 仍是占位 | 下载页能看到真实任务、重启可续传；Settings 状态机测试 | 同上 |

统一形态：按 `AGENTS.md` 定稿，MVI 是模式、`ViewModel`/`StateFlow`/`Channel<Effect>` 是机制，
以 `feature/reader/ReaderViewModel` 为模板；**禁止同类页面混用裸 `StateHolder` 与 reducer**。
Koin 接线已排进第一批（`BE-A5`）：接线完成后统一走 `viewModelOf` / `koinViewModel`，
不再新增手写 Factory；接线前不改动现有 Search/Detail 的工厂写法。

### P2-B · 平台能力与架构收口（M6）

| 任务 | 改动点 | 验收门 | 风险 |
| --- | --- | --- | --- |
| B1 Koin 收口 | 见 `BE-A5`：共享网络栈已在容器内，剩余两个 VM 工厂（Search / Detail）改 `viewModelOf`（**第一批收尾项**） | 全量 502 用例不回归；三端编译绿 | 逐模块替换，禁止一次性改全部屏 |
| B2 HostUi / WebView 登录 | `source/bridge/.../HostApiImpls.kt:349` 的 `NoopHostUi` 默认拒绝；按 `security-audit`（MASVS-NETWORK/PLATFORM/CODE）落地 `cf_clearance` 与 `pairedUserAgent` 绑定，桌面走 Cookie 导入兜底（ADR-009） | 安全评审通过 + `UrlPolicyTest` 与 `CookieStoreTest` 绿 | 安全红线，先评审后编码 |
| B3 VIDEO/AUDIO 渲染 | `SourceRuntime.pathForDetails/pathForPayload` 与 `BridgeChapterProvider.payload` 目前对 VIDEO/AUDIO 抛 CONTRACT；见 `BE-C4` | 新增用例覆盖两类 payload；不影响 MANGA/NOVEL 基线 | 独立于阅读器主干，可暂不启用 |
| B4 分段计时 UI | `EngineTelemetry` / `NetworkTelemetry` / `HtmlTelemetry` 与 `SourceRuntime.onTrace` 已有底座，缺消费面板（见 `BE-B6`） | 面板能展示一次调用的 init/net/parse 分段 | 纯展示，风险低 |

### P2-C · 源生态与发布（M7 / 长期）

| 任务 | 说明 | 验收门 |
| --- | --- | --- |
| C1 统一两仓 schema | 见 `BE-C5`：主仓 `docs/contract/index-schema.json` 与源仓 `Kurisa-Sources/index.schema.json` 双版本漂移；当前**不阻断**加载（52 源门禁真实通过），但新增字段时会分叉 | 两仓同一份 schema + `npm test` + `SourcesRepoContractTest` 双绿 |
| C2 扩 `rules/v1` 与真实站点回归 | 见 `BE-C6`：现仅 1/52 规则源；在 C1 边界内（本地 refs、不把站点内容写进主仓）补选择器回归样本 | 规则源数量与回归覆盖率有明确数字；不引入真实站点 URL |
| C3 签名信任模型 | 源仓 README 自述「无签名机制，信任=仓库 + PR 评审」；索引无 `signature`，`SourceInstaller` 全部走 `requiresDoubleConfirm` | 签名生成/校验闭环 + 二次确认路径不退化 |
| C4 三端与发版 | iOS Xcode 壳、三端截图基线（含两条 walkthrough 的 `comic-local-cbz.png` / `comic-network-source.png`）、`app/android` 的 `applicationId/versionCode/versionName` 占位替换、签名配置 | 三端编译 + 截图基线入库 + 签名包可安装 |

---

## 4. 明确不做

- **不把真实站点 URL / 内容 / 预置源写进主仓**（C1）；验证与 fixture 一律 `example.*` 或本地只读 refs。
- **不新建第二套阅读器或第二套模型**（ADR-005）；漫画与小说共用 `ChapterProvider` 一条管线。
- **默认不新增 `expect/actual`**（ADR-006）；平台差异走接口注入。
- **不上 WASM / 独立进程隔离**（ADR-002 已明确不实施）；**桌面不内嵌浏览器**（ADR-009）。
- **不在本轮重构插件引擎 / 扩 JS 契约**：S0/P0/P1/P2/P3 已完工，改动须以基准测试证明收益。
- **不在设计原型定稿前做视觉还原**（用户口径）；UI 只补齐让后端能力可触达的最小入口。

---

## 5. 风险与待拍板

1. **A1 设计原型重做中**：`design/` 的 17 个已跟踪文件当前在工作树中全部为**未提交删除**。**表现层任务全部后置**，不按旧原型排期；这批删除由用户自行处理，本规划不提交、不恢复、不引用。
2. **订阅 URL 是新的安全边界**：`BE-A3` 的 `fetchIndex` 是唯一引入「用户输入的任意 URL」的链路，`SourceRepoSubscription.refresh` 目前未过 `UrlPolicy`；安全评审前不要合并。
3. **Cookie 持久化牵动红线**：`BE-B1` 必须保持 `cf_clearance` 与 UA 配对、重定向逐跳复核，先 `security-audit` + `privacy-audit`。
4. **CI 首次运行**：`.github/workflows/ci.yml` 在 GitHub Actions 上需 Android SDK 37 与 Gradle wrapper 镜像可用；本地 `assembleDebug` 已通过，云端未实测。
5. **条件门禁仍会 SKIP**：`RealSourceE2ETest` / `AllSourcesCompatibilityTest` / `SourcesRepoContractTest` 缺外部目录时打印 SKIP 按通过计。CI 全绿 ≠ 真实站点全流程跑通；本轮本地已真实跑通 `SourcesRepoContractTest`（52 源）。
6. **Koin 迁移面**：三个 VM 工厂 + 共享网络栈同时在多个模块，逐模块替换、每步回归。
7. **schema 变更可回滚**：改索引形状必须先有迁移路径与旧客户端 fail-closed 语义；`verifyMigrations` 打开前需补快照。
8. **下载分片连续性假设**：`DownloadSink.completedParts` 隐含分片从 0 连续，`BE-B7` 引入乱序完成时必须同步改。

---

## 6. 统一验收门

```bash
# 主仓
./gradlew desktopTest :app:android:assembleDebug --console=plain --no-daemon
# 改网络必跑 UrlPolicyTest；改桥/Cookie/白名单前加载 security-audit

# 源仓
npm test
KURISA_SOURCES_REPO=<源仓绝对路径> ./gradlew :source:bridge:desktopTest \
  --tests "source.bridge.SourcesRepoContractTest"
```

- 用例数只增不减（当前 488 基线）；0 新增 SKIP；截图基线随 walkthrough 一起入库。
- 每个任务独立提交，回滚点写进提交信息；改 schema 必须可回滚。
- 新文档一律引用本文（`docs/work-plan.md`）、`docs/architecture.md` 与
  `docs/plugin-system/DESIGN.md`；归档区文件不得作为实现依据。