# Kurisa 项目代理规范（M1 起）

> 通用技能纪律见 `~/skills/AGENTS.md`（最小集合、主动触发、子代理点名、合规）。
> 本文件只写本项目特有的路由与硬规则。架构以 `docs/architecture.md` 为准，
> 契约以 `docs/contract/`（source.d.ts / index-schema.json / novel-example.js）为准，
> 长期约定见 `.workbuddy/memory/MEMORY.md`。

## 1. 任务 → 技能路由（先加载再动手）

| 任务 | 先加载 |
|---|---|
| M1 契约/源加载器/Host API（`source:*`） | `kotlin-kmp-code-review` + `kotlin-data-kmp-data-layer`，动手前重读 `docs/contract/source.d.ts` |
| 新 feature 模块/改模块边界 | `kotlin-project-modularization` + `kmp-clean-architecture` + `kotlin-project-feature-implementation` |
| 导航（Route keys/backstack/深链接） | `kotlin-navigation-compose-multiplatform`（非官方 Navigation3 不引入）；深链接加 `kotlin-platform-app-links-and-deep-links` |
| Ktor 网络/白名单/重定向/Cookie | `kmp-network-layer` + `kmp-repository-pattern`；**必须同时跑** `UrlPolicyTest` 对应断言 |
| SQLDelight schema/迁移 | `kmp-sqldelight-setup`；migration 必须可回滚 |
| ViewModel/StateFlow/MVI 状态机 | `kotlin-project-state-management` 或 `kmp-mvi`（二选一，不混用） |
| expect/actual 新增 | 先读 `kmp-expect-actual`；新增必须经架构评审（ADR-006），默认不加 |
| 漫画长图/OOM/手势 | 先跑 `ZoomRenderTest`/`ZoomLongStripTest`，再按需 `compose-performance-skills` 子技能 |
| 发版前评审 | mattpocock `code-review`（定点到 merge-base）+ `kotlin-kmp-code-review` |
| 修 bug（含 Cloudflare/站点适配） | `kotlin-project-bugfix`：先根因最小复现，再最小改动 |

## 2. 安全硬规则（违反即停）

- 碰 WebView host（C/Swift）、JS 桥、Cookie/CookieJar、Ktor 引擎、站点 URL 白名单：
  **必须先加载 `security-audit`**，按 OWASP MASVS 条目自查后再写代码。
- 隐私（账号 token、阅读记录落盘、SecureStorage）：加 `privacy-audit`。
- `cf_clearance` 与 UA 绑定存储（`pairedUserAgent`）是架构红线，不得改为“裸 Cookie 复用”。

## 3. 子代理规则

- 拆子代理时在 prompt 里点名 skill（例：“先加载 kotlin-testing-kmp 再写单测”）；
  评审类任务用隔离上下文跑，不污染主会话。
- 每个子代理只给 1–2 个 skill + 明确验收标准。

## 4. 项目技能子集（`.dsh/skills/`，35 个，rank 100）

来源：`~/skills` 全库 166 个中精选，与 Kurisa M1–M2 强相关：
KMP 导航/模块化/特性实现/数据层/评审/测试/Gradle 治理/桥接/CMP UI/架构评审/状态管理/bugfix/深链接（13）+
CleanArch/DI/协程/SQLDelight/安全/质量/单测/expect-actual/网络层/MVI/仓储/离线优先/分页（13）+
security-audit/privacy-audit/architecture/kotlin-quality/compose-ui（5）+
code-review/compose-expert/material-3/md3e（4）。

- M3（阅读器 UI）/M5（设计系统）需要更多时，回 `~/skills` 按需取，不常驻。
- 同步：上游更新后 `cp -R ~/skills/<collection>/skills/<name> .dsh/skills/<name>`。
- `.dsh/` 仅本地生效（已加入 `.gitignore`），不入库；要分发给其他机器时显式打包。

## 5. 文档纪律

- 架构文档制定中：只改正文，不写版本号/变更史（见 MEMORY.md）。
- 产品名 `Kurisa` 只落配置层，代码内一律中性命名。
