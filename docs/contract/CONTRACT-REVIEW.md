# M1 契约评审记录（冻结版 v1，apiVersion=2）

> 评审对象：`reader/source/contract` + `docs/contract/{source.d.ts,index-schema.json,novel-example.js,manga-example.js}`。
> 结论：**通过，冻结**。此后契约变更走版本化流程（apiVersion 递增 + 宿主按版本启用能力）。

## 1. 三件套对照

| 维度 | Kotlin（`source.contract`） | TypeScript（source.d.ts） | JSON Schema（index-schema.json） |
|---|---|---|---|
| 作品/章节 | `Work` / `Chapter` / `WorkDetails` | 同名 interface | —（源返回体，宿主归一化） |
| 搜索页 | `SearchPage` | `SearchPage` | — |
| 源元数据 | `SourceMeta` + `SourceScriptHeader.parse` | `ComicSource` 类字段 + `==SourceScript==` 注释块 | `IndexedSource`（仓库侧） |
| 账号声明 | `AccountDecl` | `AccountNamespace` | — |
| 能力/类型 | `ContentKind`（MANGA/NOVEL） | `ContentKind`（'manga'/'novel'） | `kind` 枚举 |
| 规则 | `ContractPolicy`（key/semver/kind/domains/option 默认值/locale） | 注释 + 示例源体现 | url/filename 二选一、sha256 格式 |

## 2. 已验证的冻结语义

1. `optionList` 默认值：取每组首项 value 段（`"any-Any"` → `"any"`），宿主生成数组后传入，传 `null` 会崩（M0 缺口 #3，`ContractPolicy.defaultOptions` + runner 双断言）。
2. 宿主调用脚本必须用 top-level `await` 取 Promise 值（async IIFE 只拿到 Promise 对象）。
3. quickjs-kt typed `asyncFunction` 只收单 JSON 载荷 → Host API 收单个 JSON 字符串。
4. `HtmlDocument` 必须 `dispose()`；复用已 dispose 抛错（harness `disposed-or-unknown` 断言）。
5. `Map chapters` 跨桥经 `Object.fromEntries` 规范化（runner replacer 断言）。
6. `APP = { locale, version, platform }` 必存在；`locale` 形如 `zh_CN`。

## 3. 模块放置说明（偏离架构图的记录）

- 架构图把契约 Kotlin 映射放在 `:core:contract`、发布面放在 `:source:contract`。
  M1 只建一个自包含的 `:source:contract`（模型 + 序列化 + 校验 + 头解析），
  `:core:*` 拆分推迟到 M2（领域模型落地时）。依据：模块化技能的最小粒度原则，
  单一消费者阶段不预拆。
- 发布物保持纯 Kotlin 零引擎依赖；quickjs-kt + ksoup 仅出现在 `commonTest`
  （“契约可执行”验证），由 `ContractHarness` + 合成漫画/小说源驱动。

## 4. Gate 判定

- 漫画 + 小说各跑通一条链路：`ContractRunnerTest` 2/2，
  desktopTest / testAndroidHostTest / iosSimulatorArm64Test 全绿。
- 四 target 可解析 + iOS framework 可链接（`SourceContract.framework` 4.9MB）。
- 真实源兼容（venera-configs 36 源）适配层是 M2 bridge 范围，不属冻结内容。
