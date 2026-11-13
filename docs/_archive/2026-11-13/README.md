# 归档快照 · 2026-11-13

此目录中的文件**不再是规范**，不得作为实现依据，也不应在新文档或代码注释中引用为「当前现状」。
保留它们只是为了追溯当时的决策过程；采用 `git mv` 无损迁移，历史可用 `git log --follow <file>` 查看。
**归档不删除任何内容**，如需彻底删除请确认后另行执行。

本轮清理的触发条件：P0 清障 + P1 漫画端到端数据链路已全部落地（主仓 `4b238cf`、`c06ef4f`），
M4 小说阅读器已合并（`867167e`）。下列文档要么是「为已完成工作而写的过程产物」，
要么其结论/差距矩阵已被代码推翻，继续留在 `docs/` 根目录会被误读为待办清单。

| 文件 | 归档理由 | 现行替代 |
| --- | --- | --- |
| `novel-reader-agent-prompt.md` | 给接手 agent 的自包含提示词，含 `F:/Kurisa` 环境路径与「接手第一步」指令；M4 已完成并合并，任务不复存在 | 小说模块事实见 `../../novel-reader-handoff.md`（已知坑），后续工作见 `../../work-plan.md` |
| `novel-reader-plan.md` | M4 实施计划（文件级改动清单、接口签名、Phase 2 进度），全部条目已落地；§6 状态与 handoff 重复 | `../../novel-reader-handoff.md` |
| `m2-review.md` | M2 数据层评审记录，结论已被后续 SQLDelight 迁移、本地书入库与源索引实现覆盖 | `../../architecture.md` §3、`../../feasibility.md` |
| `m0-webview-host-linux.md` | M0 Linux 容器 / WebView helper 进程实测，环境基线（Linux + JDK25）与本机（macOS + JDK21）不符；ADR-009/010 的桌面决策已另有结论，文中「仍未做」清单不再排期 | `../../cookie-cloudflare.md`、`../../architecture.md` ADR-009/010 |
| `community-solutions.md` | 同主题调研过程稿，文档地图原已注明「结论以 `cookie-cloudflare.md` 为准」；与 `architecture.md` ADR 结论重复 | `../../cookie-cloudflare.md` |
| `REFACTOR-PLAN.md` | 插件引擎重构规划，§0 已记 S0/P0/P1/P2/P3 全部完工；§1 差距矩阵是**重构前基线**（单发模型），照抄会重做已完工项。§7 引用迁移清单仍可追溯 | 目标态 `../../plugin-system/DESIGN.md`；现状以代码为准；验收记录 `../../comic-e2e-plan.md` |

> 归档区文件保持归档时原样：其中的「当前 / 待 M5 / 34 源矩阵 / 尚未」等表述均为归档时点口径，
> 不代表当前现状，一律不得据此实现。归档区文件之间允许互相引用作追溯。
> 新文档一律引用 `../../work-plan.md`（唯一活计划位）、`../../architecture.md`
> 与 `../../plugin-system/DESIGN.md`。
