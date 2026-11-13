# 文档地图

> 最后整理：2026-11-13。本文件是 `docs/` 的入口，标明哪些文档是**权威**、哪些是**历史参考**、
> 哪些已**归档**。本次整理在 P0 清障 + P1 漫画端到端数据链路落地后执行：已完成的过程文档
> 无损归入 `_archive/2026-11-13/`，并把唯一活计划位从 `comic-e2e-plan.md` 迁到 `work-plan.md`。

## 1. 权威文档（改动前必读）

| 文档 | 定位 |
| --- | --- |
| `handoff-2026-11-13.md` | **最近一次交接**：仓库/门禁状态、已落地能力、已知偶发问题与红线。§3 的四处生产接线缺口**已完成**，仅作追溯；实施依据以 `work-plan.md` 为准 |
| `work-plan.md` | **唯一活计划位**：现状快照；**§2 核心功能定义与后端专项**（能力域地图、源码取证断点、`BE-A1`…`BE-A5` / `BE-B1`…`BE-B8` / `BE-C2`…`BE-C6` 任务分解与依赖/验收门，第一批即 `BE-A1`…`BE-A5`，Koin 接线在首批收尾）；§3 M5/M6/M7 与 P2 优先级任务；验收门、明确不做的事、风险与回滚 |
| `architecture.md` | 总体架构与 ADR。ADR-002/003、§4.6.3、§4.7 继续有效；§4.3 的 v1 单发模型叙述已标为历史记录，实现现状以代码为准、目标态以 `plugin-system/DESIGN.md` 为准 |
| `plugin-system/DESIGN.md` | 插件系统设计（目标态）：运行时生命周期、Host SDK、声明式规则层、安全与性能目标 |
| `plugin-system/HOST-SDK.md`、`plugin-system/SOURCE-PACKAGE.md` | Host SDK 冻结签名与源包规范；当前实现为 `js/v1` + `rules/v1` 双形态分派，`defineSource` 一体包仍是目标态（见 SOURCE-PACKAGE 开头实施说明） |
| `contract/source.d.ts`、`contract/source-v4.d.ts` | JS 侧类型声明（v2/v3 冻结、v4 增补），契约形状依据 |
| `contract/index-schema.json` | 源仓库索引 schema。与 `Kurisa-Sources/index.schema.json` 存在双版本漂移，待 P2-C1 统一（当前不阻断加载） |

## 2. 参考与历史资料（保留，非规范）

| 文档 | 说明 |
| --- | --- |
| `comic-e2e-plan.md` | **已完成（2026-11-13）**：漫画两条 walkthrough 的断点取证、阶段任务与验收记录。不再是活计划位，后续实施依据见 `work-plan.md` |
| `novel-reader-handoff.md` | M4 已完成模块的历史交接记录与**已知坑索引**（§7 仍有价值）；不再是任务移交文档 |
| `novel-reader-research.md` | 小说阅读器实现调研：同类项目对比、排版分页与进度锚点方案（历史参考） |
| `komikku-reader-replication.md` | 阅读器视觉/UI/UX 与本地阅读复刻规格，**M5 视觉规格参考**，排期见 `work-plan.md` |
| `cookie-cloudflare.md` | Cookie / Cloudflare 结论稿，仍是 `data/network` 行为背景；替代已归档的 `community-solutions.md` |
| `contract/CONTRACT-REVIEW.md` | M1 契约冻结评审记录（apiVersion=2 基线；运行时现支持 2/3/4） |
| `m0-verification-report.md`、`feasibility.md` | M0 技术栈/依赖可行性实测，仅作历史追溯（其中引用的 WebView 实测文档已归档） |
| `local-import-picker-matrix.md` | FileKit 选型记录。**结论已定**：0.17.0 common 元数据不可用、依赖已撤，双端改用平台原生选择器；不再作为待办 |
| `source-compatibility.md`、`real-source-e2e.md` | 源兼容矩阵与本地 E2E 结果。**属本地门禁基线**：对应测试需外部 refs 目录，CI 不自动执行 |
| `contract/manga-example.js`、`novel-example.js`、`v4-example.js` | 契约示例源（`example.*` 合成站点）。`manga-example.js` 头块能力位已修为 `search,comic` |
| `baseline/`、`screenshots/` | 基线与截图资产。`baseline/mangadex-search-one.json` 是 M0 历史实测样本，**不得复用为新 fixture**（C1） |
| `../design/README.md` | M5 高保真设计原型（13 屏 M3 Expressive + tokens + 可点原型，纯前端、C1 合成素材），**设计规格非实现现状**。17 个文件已在 `main` 中、工作区干净，但原型版本未定稿，定稿前不作为排期依据；定稿后本条与 `work-plan.md` 的 M5 任务同步刷新 |
| `../data/db/MIGRATIONS.md` | `:data:db` 迁移校验机制：2–6 号 schema 快照、`verifyMigrations` 打开状态、`1.sqm` 无法自动校验的原因、快照再生脚本与新增迁移流程 |

### 测试门禁口径

- 当前基线：全仓 `desktopTest` **608 用例 / 0 失败 / 0 错误 / 0 跳过**（2026-11-14 双 Agent 合并后本地全量实测，118 个测试类），`:app:android:assembleDebug` 与 `verifyCommonMainReaderDatabaseMigration` 通过。
- 迁移校验：`data/db/build.gradle.kts` 的 `verifyMigrations` 已打开，2–6 号快照、`1.sqm` 未纳入自动校验的原因与快照再生脚本见 `data/db/MIGRATIONS.md`；新增迁移需带 `--rerun-tasks` 复验。
  本轮新增 31 条：`DownloadManagerTest` 5、`TarArchiveTest` 6、`ShelfViewModelTest` 3、`ShelfBindingsTest` 3、`SourceRepoSubscriptionTest` 9、`SourceRepoUpdatesTest` 5。
- 两条 walkthrough 已有自动回归：`app/shared-ui/src/desktopTest/kotlin/app/shared/ComicWalkthroughTest.kt`（网络源）与 `data/localimport/src/commonTest/kotlin/data/localimport/LocalComicWalkthroughTest.kt`（本地 CBZ），均为 `example.*` 合成数据 / 内存 Vfs，**仍不等于真实站点跑通**。截图基线待补。
- 条件门禁：`RealSourceE2ETest` / `AllSourcesCompatibilityTest` 需 `-Dkurisa.refsDir`，`SourcesRepoContractTest` 需 `-Dkurisa.sourcesRepoDir` 或环境变量 `KURISA_SOURCES_REPO`；缺目录时打印 `SKIP` 并按通过计，**CI 全绿不等于全流程跑过**。本次带源仓目录实跑 `SourcesRepoContractTest`（2 源 / 2 索引，非 SKIP），源仓 `npm test` 通过；历史文档「52 源」基线在本机 refs（34 个 venera 脚本）无法复现，不虚报。
- 主仓 CI 见 `.github/workflows/ci.yml`（`desktopTest` + `:app:android:assembleDebug`）；源仓门禁见 `Kurisa-Sources/.github/workflows/validate.yml`（`npm test`）。

漫画两条 walkthrough 已于 2026-11-13 固化为**无条件执行**的 desktopTest（不需要外部 refs）：
`app/shared-ui/src/desktopTest/.../LocalComicWalkthroughTest.kt`（复制 `docs/sample-library/`
真实漫画库后扫描）与 `NetworkComicWalkthroughTest.kt`（MockEngine + `example.com` 合成站点，
CDN 返回的也是真实漫画页）。二者逐页截图到 `docs/screenshots/comic-*.png`，
并断言「整图颜色数 ≥ 800（阅读器 ≥ 1500）」——色块占位图必然失败，真图必然通过。
截图用例断言「页面不是单色」，无头环境不缺显示器但**缺中文字体**——CI 已装 `fonts-noto-cjk`
（见 `.github/workflows/ci.yml`）；本机没有 CJK 字体时同样会误报空白。

> 素材是真实的：`docs/sample-library/` 放的是 Pepper & Carrot（David Revoy，CC-BY 4.0）
> 已发表作品的原页。早期版本用 Java2D 画色块当漫画页，测试能过但截图证明不了渲染，
> 已于 2026-11-13 全部替换（见 `comic-e2e-plan.md` §8.5）。

## 3. 已归档

采用**无损归档**（`git mv`，不删除）；如需彻底删除，确认后另行执行。归档理由分两类：
① 结论已被代码证伪、会误导后续开发；② 待办已完成或已被在编计划取代。

### `_archive/2026-11-12/`（7 份，插件引擎 v1 错误前提与过时草稿）

`plugin-engine.md`（单发模型错误前提）、`adr-011-native-extension-v4-DRAFT.md`、`v4-dual-runtime-DRAFT.md`、
`venera-to-kurisa-migration-DRAFT.md`、`source-warehouse-DRAFT.md`、`reader-handoff.md`（Linux/JDK25 环境基线 + 待办已完成）、
`komikku-reading-parity.md`（❌ 矩阵已被代码推翻）。逐条理由见该目录 README。

### `_archive/2026-11-13/`（6 份，已完工的过程产物）

| 文件 | 归档理由 |
| --- | --- |
| `novel-reader-agent-prompt.md` | 给接手 agent 的提示词（含 `F:/Kurisa` 路径），M4 已完成并合并，任务不复存在 |
| `novel-reader-plan.md` | M4 实施计划全部条目已落地，§6 进度与 handoff 重复 |
| `m2-review.md` | M2 数据层评审记录，结论已被后续实现覆盖 |
| `m0-webview-host-linux.md` | M0 Linux 容器 / WebView helper 实测，环境基线与本机不符，桌面决策已另有结论 |
| `community-solutions.md` | 调研过程稿，文档地图原已注明结论以 `cookie-cloudflare.md` 为准 |
| `REFACTOR-PLAN.md` | 插件引擎重构规划 S0/P0/P1/P2/P3 已完工；§1 差距矩阵是重构前基线，照抄会重做已完工项；§7 引用迁移清单仍可追溯 |

> 保留未归档：`architecture.md`、`comic-e2e-plan.md`、`work-plan.md`、`plugin-system/{DESIGN,HOST-SDK,SOURCE-PACKAGE}.md`、
> `contract/*`、`komikku-reader-replication.md` 及第 2 节历史实测资料。

## 4. 引用迁移

归档区文件**不得作为实现依据**。2026-11-13 核验：源码树无把归档区文件当作「当前现状」的注释；
`architecture.md`、`plugin-system/DESIGN.md`、`m0-verification-report.md`、`comic-e2e-plan.md`、
`novel-reader-handoff.md`、`komikku-reader-replication.md`、`real-source-e2e.md` 中指向归档区的路径
已标注「仅追溯」。历史文档中保留的归档路径同样只作追溯。

新文档一律引用 `work-plan.md`（唯一活计划位）、`architecture.md` 与 `plugin-system/DESIGN.md`。
`comic-e2e-plan.md` 仅作已完成验收记录引用。

## 5. 与架构文档的关系速记

- `architecture.md` ADR-003 与 §4.6.3 原本就要求 `QuickJs.create(dispatcher)`、`memoryUsage`、
  类型化 converter、`moduleLoader` 字节码缓存 —— 长驻实现是回归架构原意。
- `architecture.md` §4.3 的 v1 单发叙述是后续实现与文档漂移的起点 —— 已标为历史记录并指向 `DESIGN.md`。
- ADR-002 中「否决声明式规则」应理解为否决其作为**唯一**机制：`rules/v1` 是 fast path，JS 逃生舱保留。
