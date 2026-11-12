# 归档快照 · 2026-11-12

此目录中的文件**不再是规范**，不得作为实现依据或在新文档/代码注释中引用。
保留它们只是为了追溯当时的决策过程；采用 `git mv` 无损迁移，历史可用 `git log --follow` 查看。

归档理由与逐文件说明见 `../../README.md` 第 3 节；替代文档为
`../../plugin-system/DESIGN.md` 与 `../../plugin-system/REFACTOR-PLAN.md`。

| 文件 | 主要问题 |
| --- | --- |
| `plugin-engine.md` | 基于「quickjs-kt 构造器私有、`quickJs {}` 阅后即焚」的错误前提；`SUPPORTED_API_VERSIONS` 写作 `{2,3}`（代码为 `{2,3,4}`）；把每次求值重跑 `init()` 固化为语义 |
| `adr-011-native-extension-v4-DRAFT.md` | 未评审草稿；§2.8「常驻 vs 单发」取舍结论错误，D1「调用一律无状态」作废 |
| `v4-dual-runtime-DRAFT.md` | 未评审草稿；声称 E2c 路由已在生产开启，与 `createSourceCatalog()` 实际装配不符 |
| `venera-to-kurisa-migration-DRAFT.md` | 面向源作者；「无状态化」由单发模型推导，密码学现状与代码不符，会误导写源 |
| `source-warehouse-DRAFT.md` | 未评审草稿；源仓库规范已由 `Kurisa-Sources/index.json` 落地，未决项过期 |

如需彻底删除这些文件，请确认后执行；归档本身不删除任何内容。
