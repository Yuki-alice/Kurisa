# 工作规划（唯一活计划位）

| 项目 | 内容 |
| --- | --- |
| 文档状态 | **活跃（2026-11-13 起）**。本文是后续实施的唯一计划入口；`comic-e2e-plan.md` 已完成，降为验收记录 |
| 范围 | M5（UI / 设计系统）、M6（平台能力）、M7（发布）与 P2 源生态 |
| 前置 | P0 清障 + P1 漫画端到端数据链路已落地（主仓 `4b238cf` / `c06ef4f`）；M4 小说已合并 `867167e` |
| 当前基线 | `desktopTest` **457 用例 / 0 失败 / 0 错误 / 0 跳过**；`:app:android:assembleDebug` 通过；源仓门禁与 `SourcesRepoContractTest`（52 源）真实跑通 |
| 纪律 | `AGENTS.md` 任务→技能路由；C1 主仓零真实站点；ADR-005 漫画/小说共用一条管线；ADR-006 默认不加 `expect/actual`；改桥/Cookie/白名单前先 `security-audit` |

> 本文不维护版本号与变更摘要，只改正文。每阶段独立提交、可单独回滚。

---

## 1. 现状快照

**已接通**：插件引擎长驻运行时 + 版本路由 + `rules/v1`；源安装/预览/二次确认/落盘入库；
`SourceCatalog.search/detail/bindWork` 与按作品路由 `WorkSourceIndex`；Search/Detail 两个 MVI 屏；
阅读器章节/正文/图片出口（共享 `CookieStore`/`HttpClient`）；本地 CBZ/图片目录/EPUB/PDF 导入（Desktop FileDialog + Android SAF）；
小说 M4 排版/书签/划线/笔记；两条 walkthrough 自动回归；主仓 CI。

**未完成**（本规划要解决的）：
Koin 未接线（VM 工厂仍手写）；`explore / shelf / settings / downloads` 四屏仍是 `TODO` 占位；
`HostUi` 默认拒绝（WebView 登录 M6）；VIDEO/AUDIO 渲染显式抛 CONTRACT；iOS Xcode 壳与三端截图基线；
截图基线未补进 walkthrough；发版签名与源签名信任模型；两仓 `index schema` 双版本漂移；
`rules/v1` 覆盖率 1/52；真实站点可用性零回归。

---

## 2. 阶段任务（按优先级）

### P2-A · 把 UI 占位屏接通（M5，收益最直接）

| 任务 | 改动点 | 验收门 | 回滚 |
| --- | --- | --- | --- |
| A1 设计资产状态确认 | `design/`（13 屏 M3 Expressive 原型 + tokens）当前**工作区缺失**：17 个已跟踪文件被删除且未提交。先确认恢复（`git restore design/`）或正式移除；未定前不按它排期 | 工作区不再有意外的 `D design/*`；`docs/README.md` 的 design 条目与实际一致 | 单条 `git restore` |
| A2 Explore 屏 | `feature/explore/Explore.kt`：按 `ExploreRoute.sourceKey` 调 `SourceCatalog` 的 explore/category 出口（缺出口则在 `SourceCatalog` 补，复用 `runtimeFor`），结果带 `sourceKey` 进 `DetailRoute` | 新 UI 测试覆盖「有源 / 无源 / 单源失败」三态；`NavRoutesTest` 不回归 | 独立提交，可单屏关闭 |
| A3 Shelf 屏 | `feature/shelf/Shelf.kt`：接 `ShelfRepository.observeShelf` + 封面/分页，本地书分区与在线书同构；`bindWork` 在打开详情前调用 | 本地书与在线书都能从书架走到阅读器；`RepoHygieneTest` 绿 | 同上 |
| A4 Settings / Downloads | `feature/settings/Settings.kt`（主题/阅读/网络/存储/关于）、`feature/downloads/Downloads.kt`（接 `DownloadController.tasks` 的优先级/暂停/续传） | 各自 `commonTest`/`desktopTest` 覆盖状态机；不影响阅读器 | 同上 |

统一形态：按 `AGENTS.md` 定稿，MVI 是模式、`ViewModel`/`StateFlow`/`Channel<Effect>` 是机制，
以 `feature/reader/ReaderViewModel` 为模板；**禁止同类页面混用裸 `StateHolder` 与 reducer**。
Koin 接入前沿用上一轮 Search/Detail 的手写 Factory 形态。

### P2-B · 平台能力与架构收口（M6）

| 任务 | 改动点 | 验收门 | 风险 |
| --- | --- | --- | --- |
| B1 Koin 接线 | `app/shared-ui` 集中 VM 工厂，把 `SearchViewModelFactory` / `DetailViewModelFactory` / `ReaderViewModelFactory` 换成 `viewModelOf`；共享 `CookieStore`/`HttpClient` 从参数注入改为 DI 提供 | 全量 457+ 用例不回归；三端编译绿 | 逐模块替换，禁止一次性改全部屏 |
| B2 HostUi / WebView 登录 | `source/hostapi/HostApiImpls.kt:39` 现在默认拒绝；按 `security-audit`（MASVS-NETWORK/PLATFORM/CODE）落地 `cf_clearance` 与 `pairedUserAgent` 绑定，桌面走 Cookie 导入兜底（ADR-009） | 安全评审通过 + `UrlPolicyTest` 与 `CookieStoreTest` 绿 | 安全红线，先评审后编码 |
| B3 VIDEO/AUDIO 渲染 | `SourceRuntime.pathForDetails/pathForPayload` 与 `BridgeChapterProvider.payload` 目前对 VIDEO/AUDIO 抛 CONTRACT（`:208-225`、`:110-112`）；补类型分支后才允许声明该能力 | 新增用例覆盖两类 payload；不影响 MANGA/NOVEL 基线 | 独立于阅读器主干，可暂不启用 |
| B4 分段计时 UI | `EngineTelemetry` / `NetworkTelemetry` / `HtmlTelemetry` 与 `SourceRuntime.onTrace` 已有底座，缺消费面板 | 面板能展示一次调用的 init/net/parse 分段 | 纯展示，风险低 |

### P2-C · 源生态与发布（M7 / 长期）

| 任务 | 说明 | 验收门 |
| --- | --- | --- |
| C1 统一两仓 schema | 主仓 `docs/contract/index-schema.json`（required `apiVersion`、`oneOf(url/filename)`、`signature`、capabilities 枚举）与源仓 `Kurisa-Sources/index.schema.json`（required `version`、条目必填 `filename/apiVersion/minApp`）双版本漂移；当前**不阻断**加载（52 源门禁真实通过），但新增字段时会分叉 | 两仓同一份 schema + `npm test` + `SourcesRepoContractTest` 双绿 |
| C2 扩 `rules/v1` 与真实站点回归 | 现仅 1/52 规则源；门禁纯静态零网络，`com.example.v4demo.js` 合成站点计入索引。在 C1 边界内（本地 refs、不把站点内容写进主仓）补选择器回归样本 | 规则源数量与回归覆盖率有明确数字；不引入真实站点 URL |
| C3 签名信任模型 | 源仓 README 自述「无签名机制，信任=仓库 + PR 评审」；索引无 `signature`，`SourceInstaller` 全部走 `requiresDoubleConfirm` | 签名生成/校验闭环 + 二次确认路径不退化 |
| C4 三端与发版 | iOS Xcode 壳、三端截图基线（含两条 walkthrough 的 `comic-local-cbz.png` / `comic-network-source.png`）、`app/android` 的 `applicationId/versionCode/versionName` 占位替换、签名配置 | 三端编译 + 截图基线入库 + 签名包可安装 |

---

## 3. 明确不做

- **不把真实站点 URL / 内容 / 预置源写进主仓**（C1）；验证与 fixture 一律 `example.*` 或本地只读 refs。
- **不新建第二套阅读器或第二套模型**（ADR-005）；漫画与小说共用 `ChapterProvider` 一条管线。
- **默认不新增 `expect/actual`**（ADR-006）；平台差异走接口注入。
- **不上 WASM / 独立进程隔离**（ADR-002 已明确不实施）；**桌面不内嵌浏览器**（ADR-009）。
- **不在本轮重构插件引擎 / 扩 JS 契约**：S0/P0/P1/P2/P3 已完工，改动须以基准测试证明收益。

---

## 4. 风险与待拍板

1. **A1 设计资产缺失**：`design/` 17 个已跟踪文件在工作区被删除且未提交，与上一轮「已入库」的结论冲突。必须先确认是恢复还是正式移除，否则 M5 屏失去规格来源。
2. **CI 首次运行**：`.github/workflows/ci.yml` 在 GitHub Actions 上需 Android SDK 37 与 Gradle wrapper 镜像可用；本地 `assembleDebug` 已通过，但云端环境未实测。
3. **条件门禁仍会 SKIP**：`RealSourceE2ETest` / `AllSourcesCompatibilityTest` / `SourcesRepoContractTest` 缺外部目录时打印 SKIP 按通过计。CI 全绿 ≠ 真实站点全流程跑通；本轮本地已真实跑通 `SourcesRepoContractTest`（52 源）。
4. **Koin 迁移面**：三个 VM 工厂 + 共享网络栈同时在多个模块，逐模块替换、每步回归，避免一次性切换导致三端编译差异。
5. **schema 变更可回滚**：改索引形状必须先有迁移路径与旧客户端 fail-closed 语义，不可直接放宽必填字段。

---

## 5. 统一验收门

```bash
# 主仓
./gradlew desktopTest :app:android:assembleDebug --console=plain --no-daemon
# 改网络必跑 UrlPolicyTest；改桥/Cookie/白名单前加载 security-audit

# 源仓
npm test
KURISA_SOURCES_REPO=<源仓绝对路径> ./gradlew :source:bridge:desktopTest \
  --tests "source.bridge.SourcesRepoContractTest"
```

- 用例数只增不减（当前 457 基线）；0 新增 SKIP；截图基线随 walkthrough 一起入库。
- 每个阶段独立提交，回滚点写进提交信息；改 schema 必须可回滚。
- 新文档一律引用本文（`docs/work-plan.md`）、`docs/architecture.md` 与
  `docs/plugin-system/DESIGN.md`；归档区文件不得作为实现依据。
