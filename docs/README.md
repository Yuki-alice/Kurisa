# 文档地图

> 最后整理：2026-11-12。本文件是 `docs/` 的入口，标明哪些文档是**权威**、哪些是**历史参考**、
> 哪些已**归档**。整理依据是本轮插件引擎源码评审（只读源码与依赖产物得出结论后，再校核文档）。

## 1. 权威文档（改动前必读）

| 文档 | 定位 |
| --- | --- |
| `plugin-system/DESIGN.md` | **插件系统设计（目标态）**，取代 `plugin-engine.md`；运行时生命周期、Host SDK、声明式规则层、安全与性能目标 |
| `plugin-system/REFACTOR-PLAN.md` | **重构规划**：差距矩阵、阶段任务、文件级改动点、验收门、文档引用迁移清单、风险与回滚 |
| `architecture.md` | 总体架构与 ADR。其中 **ADR-002/003、§4.6.3、§4.7 继续有效**；§4.3 的单发模型叙述已加指向说明，实现现状以此为准、目标态以 `DESIGN.md` 为准 |
| `contract/source.d.ts`、`contract/source-v4.d.ts` | JS 侧类型声明（v2/v3 冻结、v4 增补），仍是契约形状依据 |
| `contract/index-schema.json` | 源仓库索引 schema |
| `reader-handoff.md` | 阅读器交接文档（**当前有未提交修改，属活跃文档，勿移动/覆盖**） |

## 2. 参考与历史资料（保留，非规范）

| 文档 | 说明 |
| --- | --- |
| `contract/CONTRACT-REVIEW.md` | M1 契约冻结评审记录（apiVersion=2 基线） |
| `m2-review.md` | M2 数据层评审记录 |
| `m0-verification-report.md`、`m0-webview-host-linux.md`、`feasibility.md` | M0 技术栈/依赖可行性实测记录，含三端与 WebView 数据，仅作历史追溯 |
| `cookie-cloudflare.md` | Cookie / Cloudflare 结论稿，仍是 `data/network` 行为背景 |
| `community-solutions.md` | 同主题调研过程稿，结论以 `cookie-cloudflare.md` 为准 |
| `source-compatibility.md`、`real-source-e2e.md` | 34 源兼容矩阵与本地 E2E 结果，回归门的历史基线 |
| `contract/manga-example.js`、`novel-example.js`、`v4-example.js` | 契约示例源 |
| `baseline/`、`screenshots/` | 基线与截图资产 |

## 3. 已归档（`_archive/2026-11-12/`）

采用**无损归档**（`git mv`，不删除）；如需彻底删除，确认后另行执行。归档原因分为两类：
① 建立在已被证伪的「单发模型」前提上，会误导后续开发；② 内容已被实现或新文档取代。

| 原路径 | 归档原因 |
| --- | --- |
| `plugin-engine.md` | 描述 v1 实现且明确写「quickjs-kt 构造器私有、`quickJs {}` 阅后即焚」「每次求值重跑 init」「`SUPPORTED_API_VERSIONS = {2,3}`」；与上游 1.0.18 API、代码 `{2,3,4}`、以及 `architecture.md §4.6.3` 冲突。由 `plugin-system/DESIGN.md` 取代 |
| `adr-011-native-extension-v4-DRAFT.md` | 未评审草稿。§2.8「常驻 vs 单发」基于错误前提，D1「调用一律无状态」作废；D2/D3/D4 的方向由新设计与重构规划承接 |
| `v4-dual-runtime-DRAFT.md` | 未评审草稿。声称「E2c 路由已开（4 走原生）」，但生产装配 `createSourceCatalog()` 仍直接使用 `QuickJsPluginEngineFactory`；且把 per-call init 固化为「定义语义」 |
| `venera-to-kurisa-migration-DRAFT.md` | 面向源作者的小抄，「无状态化（最重要）」直接由单发模型推导；密码学现状（AES/RSA/GBK）与当前代码不符。会误导写源，必须撤下 |
| `source-warehouse-DRAFT.md` | 未评审草稿。源仓库规范已由 `Kurisa-Sources/index.json` 实际落地；文中「许可证待定」「写源指南引用 S4 迁移小抄」等未决项已过期 |

> 保留未归档：`architecture.md`（ADR 基线，仅局部加注）、`reader-handoff.md`（活跃）、
> `contract/*`（契约仍有效）、以及第 2 节的历史实测资料。

## 4. 归档后的引用迁移

源码 KDoc 与契约文件中仍有指向 `docs/plugin-engine.md`、`ADR-011`、`*-DRAFT.md` 的注释。
完整清单与逐文件处理方式见 `plugin-system/REFACTOR-PLAN.md §7`，在对应重构阶段同步迁移，
不要留下指向不存在文件的引用。

## 5. 与架构文档的关系速记

- `architecture.md:99`（ADR-003）与 `:569`（§4.6.3）原本就要求 `QuickJs.create(dispatcher)`、
  `memoryUsage`、类型化 converter、`moduleLoader` 字节码缓存 —— **新设计是回归架构原意**。
- `architecture.md:452-453`（§4.3）的单发叙述是后续实现与文档漂移的起点 —— 已加注指向 `DESIGN.md`。
- ADR-002 中「否决声明式规则」建议按 `DESIGN.md §12` 修订措辞：否决其作为**唯一**机制，
  允许作为 fast path 并保留 JS 逃生舱。
