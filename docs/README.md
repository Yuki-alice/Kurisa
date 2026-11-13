# 文档地图

> 最后整理：2026-11-12。本文件是 `docs/` 的入口，标明哪些文档是**权威**、哪些是**历史参考**、
> 哪些已**归档**。本次整理依据漫画全流程的源码取证（只读源码 + 实测构建/测试后再校核文档）。

## 1. 权威文档（改动前必读）

| 文档 | 定位 |
| --- | --- |
| `comic-e2e-plan.md` | **漫画全流程唯一活计划位**：本地导入 + 插件网络源的端到端 walkthrough、断点清单（带 `file:line`）、阶段任务与验收门。小说延后 |
| `architecture.md` | 总体架构与 ADR。ADR-002/003、§4.6.3、§4.7 继续有效；§4.3 的单发模型叙述已加指向说明，实现现状以代码为准、目标态以 `plugin-system/DESIGN.md` 为准 |
| `plugin-system/DESIGN.md` | 插件系统设计（目标态）：运行时生命周期、Host SDK、声明式规则层、安全与性能目标 |
| `plugin-system/HOST-SDK.md`、`plugin-system/SOURCE-PACKAGE.md` | Host SDK 冻结签名与一体包（含 `rules/v1`）规范 |
| `contract/source.d.ts`、`contract/source-v4.d.ts` | JS 侧类型声明（v2/v3 冻结、v4 增补），契约形状依据 |
| `contract/index-schema.json` | 源仓库索引 schema |

## 2. 参考与历史资料（保留，非规范）

| 文档 | 说明 |
| --- | --- |
| `plugin-system/REFACTOR-PLAN.md` | 插件引擎重构规划。§0 已记录 S0/P0/P1/P2/P3 完工；**§1 差距矩阵是重构前基线，不是当前现状**，实施依据见 `comic-e2e-plan.md` |
| `komikku-reader-replication.md` | 阅读器视觉/UI/UX 与本地阅读复刻规格。视觉规格已基本落地，剩余待办以 `comic-e2e-plan.md` 为准 |
| `contract/CONTRACT-REVIEW.md` | M1 契约冻结评审记录（apiVersion=2 基线；运行时现支持 2/3/4） |
| `m2-review.md` | M2 数据层评审记录 |
| `m0-verification-report.md`、`m0-webview-host-linux.md`、`feasibility.md` | M0 技术栈/依赖可行性实测，含三端与 WebView 数据，仅作历史追溯 |
| `cookie-cloudflare.md` | Cookie / Cloudflare 结论稿，仍是 `data/network` 行为背景 |
| `community-solutions.md` | 同主题调研过程稿，结论以 `cookie-cloudflare.md` 为准 |
| `novel-reader-research.md` | 小说阅读器实现调研：Compose/Kotlin 同类项目对比、M4 排版分页与进度锚点落地方案（参考，非规范） |
| `novel-reader-plan.md` | M4 实施计划：文件级改动清单、接口签名、验收门与 Phase 2 进度（参考，非规范） |
| `novel-reader-handoff.md` | 小说阅读器模块编写交接：进度、文件清单、验证状态、未完成清单、已知坑（移交用） |
| `novel-reader-agent-prompt.md` | 可直接复制给接手 agent 的自包含提示词 |
| `local-import-picker-matrix.md` | 本地书导入选择器依赖可解析性矩阵（FileKit 选型、风险与实施草案；暂不引入） |
| `source-compatibility.md`、`real-source-e2e.md` | 34 源兼容矩阵与本地 E2E 结果。**属本地门禁基线**：对应测试需外部 refs 目录，CI 不自动执行 |
| `contract/manga-example.js`、`novel-example.js`、`v4-example.js` | 契约示例源（`example.*` 合成站点） |
| `baseline/`、`screenshots/` | 基线与截图资产。`baseline/mangadex-search-one.json` 是 M0 历史实测样本，仅被 `m0-verification-report.md` 引用，**不得复用为新 fixture**（C1） |

### 测试门禁口径

`desktopTest` 中的 `RealSourceE2ETest` / `AllSourcesCompatibilityTest` / `SourcesRepoContractTest`
需 `-Dkurisa.refsDir` 或 `-Dkurisa.sourcesRepoDir` 等外部目录，否则打印 `SKIP` 并按通过计。
「测试全绿」不等于全流程跑过；可复现的漫画端到端回归以 `comic-e2e-plan.md` §3 为准。

## 3. 已归档（`_archive/2026-11-12/`）

采用**无损归档**（`git mv`，不删除）；如需彻底删除，确认后另行执行。归档原因分两类：
① 结论已被代码证伪、会误导后续开发；② 待办已完成或已被在编计划取代。

| 原路径 | 归档原因 |
| --- | --- |
| `plugin-engine.md` | 描述 v1 实现且明确写「quickjs-kt 构造器私有、`quickJs {}` 阅后即焚」「每次求值重跑 init」「`SUPPORTED_API_VERSIONS = {2,3}`」；与上游 1.0.18 API、代码 `{2,3,4}` 冲突。由 `plugin-system/DESIGN.md` 取代 |
| `adr-011-native-extension-v4-DRAFT.md` | 未评审草稿。§2.8「常驻 vs 单发」基于错误前提，D1「调用一律无状态」作废 |
| `v4-dual-runtime-DRAFT.md` | 未评审草稿，把 per-call init 固化为「定义语义」；文中的 E2c 路由断言在当时未接线，现生产装配已接 `VersionedPluginEngineFactory`，草稿不再作为依据 |
| `venera-to-kurisa-migration-DRAFT.md` | 面向源作者的小抄，「无状态化」由单发模型推导；密码学现状与当前代码不符 |
| `source-warehouse-DRAFT.md` | 未评审草稿，源仓库规范已由实际索引落地，未决项过期 |
| `reader-handoff.md` | 环境基线是 Linux 容器 / JDK25（本机为 macOS / JDK21）；`desktopTest 217`、§6「真实源未装配」与 §8 打勾清单自相矛盾；§7 下一轮 P0 三项均已完成 |
| `komikku-reading-parity.md` | B 矩阵中「条漫无捏合 / 自动滚动无 / 无自动裁边」等 ❌ 已被代码推翻，C.4 执行顺序照抄会重做已完工项；A 部分 Komikku 调研留档可追溯，实施依据改指 `comic-e2e-plan.md` |

> 保留未归档：`architecture.md`、`plugin-system/*`、`contract/*`、`komikku-reader-replication.md`
> 及第 2 节历史实测资料。

## 4. 引用迁移

归档区文件**不得作为实现依据**。源码树已无指向 `plugin-engine.md` / `ADR-011` /
`*-DRAFT.md` 的注释（2026-11-12 核验，迁移清单见 `plugin-system/REFACTOR-PLAN.md §7`）；
历史实测文档中保留的归档区路径仅作追溯，不得据此实现。新文档一律引用
`comic-e2e-plan.md` 与 `plugin-system/DESIGN.md`。

## 5. 与架构文档的关系速记

- `architecture.md` ADR-003 与 §4.6.3 原本就要求 `QuickJs.create(dispatcher)`、`memoryUsage`、
  类型化 converter、`moduleLoader` 字节码缓存 —— 长驻实现是回归架构原意。
- `architecture.md` §4.3 的单发叙述是后续实现与文档漂移的起点 —— 已加注指向 `DESIGN.md`。
- ADR-002 中「否决声明式规则」应理解为否决其作为**唯一**机制：`rules/v1` 是 fast path，
  JS 逃生舱保留。
