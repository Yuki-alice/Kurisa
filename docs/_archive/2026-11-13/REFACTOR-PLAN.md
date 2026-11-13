# 插件引擎重构规划

> 配套文档：`DESIGN.md`（目标态设计）+ `SOURCE-PACKAGE.md`（一体包规范）+ `HOST-SDK.md`（SDK 冻结签名）。
> 本文只回答「按什么顺序改、改哪些文件、怎么验收、怎么回滚」。
> 状态：**插件系统范围已完工（S0/P0/P1/P2/P3 后端+面板 UI 落地并验证；残余见下）。**
> 每阶段独立可验、可单独回滚；不设时间估计，以验收门为准。
> 日期：2026-11-12。
>
> ## 0. 实施进度（文档随代码走）
>
> - S0（spike+基准）：`SpikeLongLivedQuickJsTest`（长驻/字节码/白名单/中断/转换器/基线）绿。
> - P0（长驻运行时）：`QuickJsEngine` 长驻 + `SourceRuntimeManager` 缓存真实 runtime（`sha256` 签名）+ `VersionedPluginEngineFactory` 接线 + `RegistryChapterProvider` 脚本缓存 + `KsoupHostHtml` 池上限；`initRunsOnceLongLived` 计数断言绿；iOS 模拟器编译通过。
> - P1（桥并发缓存）：`HostResponse{headers,finalUrl,verdict,truncated}` + 全方法 + `selectMany`/`requestAll` + GET 缓存/in-flight 去重 + per-host 限流 + 能力码双轨（`unknown-op`/`too-many-requests`→CONTRACT）；`P1NetworkComplementTest` 绿。
> - P2（一体包+SDK）：`RulesEngine`（HTML+CSS 子集；`settings` 声明→预热→`@setting:key` 模板闭环；`diagnose/diagnoseDetail/diagnoseContent` 三步诊断后端）+ 装配分派（`format` 参与签名）+ 索引 `format` + 安装器规则分支/`scanRules` + 落盘扩展名；`ModelNormalize`（L1 归一化，规则引擎收敛）+ `PureResultCache`（宿主侧纯函数结果 TTL 缓存）+ `Cache.getOrPut`（JS 可见 TTL 记忆化，`source-v4.d.ts` 增补）；对应单测绿；自有 53 源矩阵 `L1 53/53, API_GAP 0, MISSING 空`；官方 venera 36 源矩阵 `L1 33/34（唯一失败 jm.js，外部加密域名列表依赖，见 source-compatibility.md §3 已知项），L2 OK=15 API_GAP=0 FIXTURE=18 SKIP=1, MISSING 空`（`AllSourcesCompatibilityTest -Dkurisa.refsDir=/private/tmp/venera-configs`，与 M1 基线逐项一致；2026-11-12 全量重跑确认无退化）。
> - §7 引用迁移完毕：源码树无 `plugin-engine.md` / `ADR-011` / `v4-dual-runtime-DRAFT` 引用（KDoc 改指 `DESIGN.md`/`HOST-SDK.md` 或直写事实，单测断言同步）。
> - 外部事项（非插件范围，移交并行工作区/M5；2026-11-12 记录）：`feature/reader` 在研手势变更致 `:app:shared-ui` 暂不可编译（含面板接线与热重载单测，此前已绿，并行区合入后重跑即绿）；`data/localimport` 在研测试灯具触发 C1 卫生门禁（`RepoHygieneTest`）。另：导航侧 `ReaderRoute.sourceKey` 接线待 M5 Detail/Reader 屏落实（提供方 `workSourceKey` 缝已就绪）；分段计时 UI 消费待 M5；WASM/独立进程按 ADR-002 明确不实施。
> - P3（可观测性底座 + 规则测试面板 + 按作品路由缝）：`EngineTelemetry/NetworkTelemetry/HtmlTelemetry`（JS 求值耗时 / 真实请求+缓存命中+去重合并+net 耗时 / 解析查询计数+耗时）+ `SourceRuntime.onTrace` 调用链 trace；`RulesEngine.diagnose/diagnoseDetail/diagnoseContent` 三步诊断 + `SourceRuntime.diagnoseSearch/Detail/Content` 门面（仅规则源，JS 源显式 CONTRACT）+ `SourceCatalog.testRules` 装配出口；规则测试面板 UI 已接线（`RuleTestRoute` + `RuleTestScreen` 三步展示 + `parseRuleTestResult` 映射单测 + `NavRoutesTest` 9 路由锁门，源管理页“规则测试”入口）；`RegistryChapterProvider.workSourceKey` 按作品路由（命中作品源，否则回退首个 enabled 源；导航侧 `ReaderRoute.sourceKey` 待 M5 Detail/Reader 落实后接线）；对应单测绿。
> - 残余待办（非插件系统范围，移交 M5/M6）：多源按作品路由（`ReaderRoute.sourceKey` 接线）、热重载/懒激活、分段计时 UI 消费；34 源官方矩阵 refs 环境重跑。

## 1. 差距矩阵

> **历史基线**：本表是重构前（S0 之前）的差距记录，不是当前现状。
> S0/P0/P1/P2/P3 已完工（见 §0）；当前实施依据见 `docs/comic-e2e-plan.md`。

| # | 现状（源码事实） | 目标 | 影响面 |
| --- | --- | --- | --- |
| G1 | `QuickJsEngine.runCall()` 每次调用 `quickJs{}` 新建实例、重注入 11 个绑定、重编译约 350 行 polyfill + 源脚本、`new` + `await init()`，结束 `close()` | 每源长驻实例，`init()` 一次 | 性能最大瓶颈；`load/describe/defaultSearchOptions` 装配期至少重建 3 次 |
| G2 | `SourceRuntimeManager` 的 LRU(8) 缓存的是描述符/配置，不是 JS context | 缓存真实 runtime，淘汰时 `close()` | 命中缓存也重建引擎 |
| G3 | `RegistryChapterProvider.bridgeFor()` 每次 `loadScript()` 读盘 + `script.hashCode()` | signature 改用 DB 的 `sha256/version`，脚本内容按 key 缓存 | 翻页固定磁盘 IO |
| G4 | `createSourceCatalog()` 直接 `QuickJsPluginEngineFactory`；`VersionedPluginEngineFactory` / `strictV4` 生产未接线 | 接上版本路由，apiVersion=4 才走原生策略 | v4 源实际按垫片跑，行为与契约不符 |
| G5 | 所有桥为 `String` payload + JSON.parse；已引入 `quickjs-converter` 却未使用 | 类型化桥 + 批量 API + 大响应句柄化 | 序列化开销、手写拼 JSON 的脆弱性 |
| G6 | `KtorHostNetwork` 丢 `finalUrl` / 响应头 / `CloudflareGuard.verdict`；只开放 GET/POST | Host SDK 回传完整响应，支持全方法 | 每个源自己写状态码分类 |
| G7 | 同源 `limitedParallelism(1)` 全链串行；无 HTTP 缓存、in-flight 去重、per-host 限流、统一重试 | JS 串行 / IO 并发 + 分层缓存 | 队头阻塞、重复请求 |
| G8 | `KsoupHostHtml.docs` 仅 `dispose` 时移除；`RejectModuleLoader` 拒绝一切 import | 文档池上限+TTL；SDK 模块白名单加载 | 长驻后内存累积；SDK 无法模块化 |
| G9 | `HostApi` 7 命名空间约 15 方法；GBK/RSA 空串桩；UI Noop；错误靠字符串子串分类 | Host SDK v2（`HOST-SDK.md` 十组 + 结构化错误） | 插件作者重复写大量样板 |
| G10 | 只有 `class extends ComicSource` 一种模板；单活跃源（取第一个 enabled 源） | 一体包 `defineSource`（`SOURCE-PACKAGE.md`）：纯对象走规则执行器，含函数走 JS；多源按作品路由 | 写源门槛、扩展性 |
| G11 | 无分段计时、无规则测试面板 | trace / 规则测试三步面板 | 排查与"好写"体感 |

## 2. 重构原则

1. **先验证后动工**：阶段 0 的 spike 不通过，不进入阶段 1。
2. **行为零变优先**：P0 只改生命周期，不改 JS 侧可见 API 与返回形状；34 源兼容矩阵是硬门。
3. **红线不动**：域名白名单、Cookie/UA 配对、能力交集、签名/静态扫描、`setting:` 前缀强制、
   审计脱敏，任何阶段都不得放宽。
4. **每阶段可回滚**：每阶段以独立提交交付，回滚点见 §8。
5. **文档跟着代码走**：源码 KDoc 中对已归档文档的引用在同阶段迁移（清单见 §7）。

## 3. 阶段 0 · Spike 与基准（无生产改动）

**目标**：证伪或确认 DESIGN.md 的关键前提，并建立可对比的基线。

| 任务 | 验收 |
| --- | --- |
| 新建 `SpikeLongLivedQuickJsTest`：`QuickJs.create(dispatcher, moduleLoader)` 连续多次 `evaluate()` | desktop / androidHost / iOS 三端通过；实例不自动关闭 |
| 验证 `compile()` → `evaluate(ByteArray)`、`addModule(ByteArray)`、`ModuleLoader` 白名单 | 字节码可复用；外部 import 仍拒绝；缓存键含 engineVersion |
| 验证 `interruptEvaluation()` / `withTimeout` / 协程取消 / `close()` 对在途 Promise | 无限循环可中断；取消后无悬挂回调；`close()` 后调用抛 `IllegalStateException` |
| 验证 `JsObjectHandle` / `TypeConverter` 传结构化参数 | 与 `quickjs-converter` 配套可用，能替代 String payload |
| 建立 `EngineBenchmark`（分段计时 init / JS / net / parse）与 `init` 调用计数 | 输出当前基线报告，作为后续验收对照 |
| 跑通现有门禁 | `./gradlew desktopTest testAndroidHostTest`；`AllSourcesCompatibilityTest` 行为不变 |

**Go / No-Go**：任一三端行为不一致且无法用 `SourceDispatchers` 抽象隔离，则回到单发模型并
重新评估（此时 P0 取消，直接从 P1 的桥优化切入）。

## 4. 阶段 P0 · 运行时生命周期（收益最大，改动最小）

| 文件 | 改动 |
| --- | --- |
| `source/bridge/QuickJsEngine.kt` | 持有一个 `QuickJs` 实例；`load()` 内一次性 `define(__host…)` + `evaluate(SDK)` + `evaluate(源脚本)` + `new` + `init()`；`runCall()` 只 `evaluate(snippet)`；`close()` 真正关闭并置空；删除「构造器私有/阅后即焚」注释 |
| `source/bridge/QuickJsEngine.kt`（companion） | polyfill 从 Kotlin 字符串抽为独立 SDK JS 模块，安装期 `compile()` 为 bytecode，按 `engineVersion` 缓存 |
| `source/bridge/SourceRuntimeManager.kt` | 缓存真实 runtime；`signature()` 用 `sha256/version`；`invalidate/close/evict` 调用 `runtime.close()`；装配失败时关闭已创建实例 |
| `app/shared-ui/ReaderSourceAssembly.kt` | `createSourceCatalog()` 接 `VersionedPluginEngineFactory(legacy=…, native=KurisaNative…)`；`bridgeFor()` 复用脚本内容，不每页读盘 |
| `source/bridge/SourceDispatchers.kt` | 保留同源串行，但补充 IO dispatcher 缝（为 P1 准备） |
| `source/bridge/HostApiImpls.kt` | `KsoupHostHtml` 加文档池上限 / TTL / 兜底 dispose |

**验收门**

- 计数器断言：单源连续 N 次 `invoke`，`QuickJs.create` = 1、`init()` = 1、`close()` = 0（直到 invalidate）。
- `SourceRuntimeUnitTest` / `PluginEngineTest` / `VersionedEnginesTest` 全绿，且新增长驻语义用例。
- 34 源兼容矩阵（L1/API_GAP/MISSING 门禁）与基线一致；`RealSourceE2ETest` 行为不变。
- `onImageLoad` 连续调用 N 次，基准报告中 `engineInit` 段耗时归零。
- 三端编译：`:source:bridge:compileKotlinIosSimulatorArm64` 通过。

## 5. 阶段 P1 · 桥、并发与缓存

| 任务 | 关键文件 | 验收 |
| --- | --- | --- |
| 类型化桥替换 String payload（分批：`convert` → `html` → `network`） | `QuickJsEngine.kt`、`HostApi.kt`、`HostApiImpls.kt` | 旧 `callSync/callAsync` 保留兼容入口；JS 侧形状不变或经 polyfill 适配 |
| 批量 API：`selectMany` / `requestAll`；响应体句柄化，分级截断并返回 `truncated` | `HostApiImpls.kt`、`SourceHttpClient.kt` | 单测锁定跨边界次数下降；大响应不反复拷贝 |
| 回传 `finalUrl` / 响应头 / `verdict`；开放 PUT/PATCH/DELETE | `SourceHttpClient.kt`、`KtorHostNetwork.kt` | 白名单逐跳复核不变；新方法有测试 |
| JS 串行 / IO 并发分层、每源 2~4 实例池 | `SourceDispatchers.kt`、`SourceRuntimeManager.kt` | 同源状态无竞态；KV 写入串行化测试 |
| per-host 限流 + 熔断 + 统一重试 | `data/network/` 新增组件 | 429/503 分类不变；重试有上限 |
| 分层缓存：响应 TTL/ETag、纯函数结果、in-flight 去重 | 新增 `source/runtime/cache/` | 命中率可观测；相同 URL 并发只发一次请求 |
| 能力门下沉到 host call；结构化错误码（兼容期双轨） | `SourceRuntime.kt`、`SourceErrors.kt`、`ContractPolicy.kt` | 未声明能力调用抛 CONTRACT；字符串分类 fallback 有测试 |

## 6. 阶段 P2 / P3 · SDK、声明式规则与工具链

**P2 · Host SDK v2 + 一体包规则 fast path**

- 按 `HOST-SDK.md` 十组落地 `Net / Parse / Model / Pagination / Codec / Store / Errors / UI / Telemetry / Lifecycle`（`DESIGN.md §5` 为摘要）；
  每组配单测与一个"改造前后源脚本行数"对照（样本仅 `docs/contract/manga-example.js`、`novel-example.js`、`v4-example.js`，不引入站点逻辑到主仓，C1）。
- 按 `SOURCE-PACKAGE.md` 新增 `defineSource` 一体包 + `rules/v1` 执行器（纯对象走 Kotlin，含函数走 JS），复用 `SourceDescriptor`、能力位、`SourceHttpClient`；
  `index.json` 增加 `format`（缺省 `js/v1`，未知 `format` fail-closed），落盘扩展名随之分发（`.js` / `.rules.json`）；不再另设 `rulesFile` 字段（一包一文件，避免双重事实来源）。签名与静态扫描流程不变；旧类源零改动兼容。
- 把 2~3 个结构简单的现有源改写为规则子集对照，验证表达力；能力不够的立即回退 JS，不为它们扩字段。

**P3 · 工具链与剩余扩展性**

- 规则测试面板：后端三步诊断已就绪（`RulesEngine.diagnose/diagnoseDetail/diagnoseContent`，失败走 `{error,code}` 通道）；**Compose UI 已接线**（`RuleTestRoute` + `RuleTestScreen` 三步展示 + `parseRuleTestResult` 映射单测 + `NavRoutesTest` 9 路由锁门，源管理页「规则测试」入口；面板字段即后端返回体，见 `RulesEngineTest.diagnoseExposesThreeSteps`）。
- 分段计时：引擎/网络/解析三段计数与耗时底座已就绪（`Telemetry.kt` + `SourceRuntime.onTrace` 调用链 trace，只记路径/耗时/分类）；UI 日志消费待 M5。
- 多源按作品路由：提供方缝已就绪（`RegistryChapterProvider.workSourceKey`，命中作品源否则回退，单测锁定）；导航侧 `ReaderRoute.sourceKey` 已于本轮 B4 接线（`WorkSourceIndex` + `SourceCatalog.bindWork`，NavRoutesTest 锁门）。以下 P3 正文为历史规划记录，实施状态一律以 §0 与 `docs/comic-e2e-plan.md` 为准。
- 开发期热重载：机制就绪（`installSource` → `manager.invalidate` → 下次调用重装配，无需重启；`SourceCatalogTest.installPersistsAndActivatesImmediately` 锁定）；文件监听式自动重载待开发期工具链。
- 懒激活：机制就绪（`RegistryChapterProvider` 首次阅读时才 `acquire`；无源/禁用源零引擎接触，见 `ReaderSourceAssemblyTest.noSourceDegradesToNoop/disabledSourceExcludedFromProvider`）。
- 评估（不实施）：WASM / 独立进程隔离，作为 ADR-002 的远期复议项。

## 7. 文档与源码引用迁移清单

归档文档的引用已随各阶段改动迁移完毕（2026-11-12 核验：源码树无 `plugin-engine.md` / `ADR-011` / `v4-dual-runtime-DRAFT` 引用）：

| 位置 | 迁移后指向 |
| --- | --- |
| `source/bridge/QuickJsEngine.kt` | `DESIGN.md` §4 / `HOST-SDK.md`（长驻语义、严格模式） |
| `source/bridge/SourceRuntime.kt` | `DESIGN.md` §4/§10；视频/音频“渲染未接线”直写 |
| `source/bridge/SourceRuntimeManager.kt` | `DESIGN.md` §4 |
| `source/bridge/BridgeChapterProvider.kt` | “渲染未接线”直写 |
| `source/bridge/SourceImageConfigBridge.kt` | `HOST-SDK.md` § Model |
| `source/bridge/VersionedEngines.kt` | `DESIGN.md` §11；`ContractPolicy.V4_CAPABILITIES` |
| `source/contract/EngineModels.kt`、`Models.kt` | “渲染未接线”直写；错误分类指 `DESIGN.md` §10 |
| `source/bridge/desktopTest/RepoHygieneTest.kt` | C1 门禁直写（去草稿引用） |
| docs 内引用（已修正） | `architecture.md`、`contract/*.d.ts`、`real-source-e2e.md`、`source-compatibility.md` 已改指新文档 |

迁移原则已落实：删除对未评审草稿的引用；事实性描述直接写在 KDoc 里
（"视频/音频渲染未接线"），不再指向草稿；契约文件保留冻结语义说明。

## 8. 风险与回滚

| 风险 | 触发信号 | 对策 / 回滚点 |
| --- | --- | --- |
| 长驻实例跨调用状态污染 | 源 B 的调用看到源 A 的全局；`init()` 结果残留 | 全局冻结 + 实例健康探测 + 异常时丢弃重建；最坏回滚 P0，恢复单发（保留 P1 收益） |
| iOS/Kotlin-Native 线程亲和或 `close()` 行为异常 | 阶段 0 spike 失败 | 不进 P0；`SourceDispatchers` 抽象隔离，仍保留单发路径 |
| 字节码与引擎版本绑定导致升级后崩溃 | quickjs-kt 升版后启动异常 | 缓存键含 `engineVersion`，升版自动失效并回退源码编译 |
| 类型化桥引入 JS 侧形状变化 | 34 源矩阵出现 API_GAP | 分批替换 + 保留旧入口 + polyfill 适配，逐组回滚 |
| 并发放开后 Cookie/KV 竞态 | 同源并发出现串号 | 状态写入仍串行；只在只读调用上放开并发 |
| 声明式规则表达力不足导致返工 | 规则源改写频繁触发逃生舱 | 保持 schema 小；逃生舱优先；不为复杂站点扩字段 |
| 归档文档引用失效造成误导 | KDoc 指向不存在的文件 | §7 清单逐项迁移，CI 加一条"文档引用存在性"检查（可选） |

## 9. 阶段依赖

```mermaid
flowchart LR
  S0["阶段 0 Spike + 基准"] --> P0["P0 长驻实例 + bytecode + 路由接线"]
  P0 --> P1["P1 类型化桥 / 并发 / 缓存"]
  P1 --> P2["P2 Host SDK v2 + 规则 fast path"]
  P2 --> P3["P3 测试面板 / 多源路由 / 可观测性"]
  S0 -. No-Go .-> P1
```

依赖说明：P1 的缓存与并发需要 P0 的稳定生命周期为前提；P2 的 SDK 需要 P1 的桥能力；
P3 的测试面板依赖 P2 的 trace 数据结构。阶段 0 若 No-Go，则跳过 P0，P1 单独成立。

## 10. 明确不做

- 不重写 34 个已兼容源；只在 P2 挑 2~3 个做规则形态对照。
- 不引入第二套 JS 运行时、不自研 JNI/cinterop（ADR-003）。
- 不把站点 URL 或解析逻辑带进主仓（C1）。
- 不在本轮删除 v2/v3 垫片，也不改 `SUPPORTED_API_VERSIONS` 的既有语义。
- 不实施 WASM 沙箱 / 独立 `:source` 进程，仅保留评估。
