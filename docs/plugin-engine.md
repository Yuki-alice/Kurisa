# 插件引擎设计与实现

> 输入：`docs/architecture.md` §4、`docs/contract/`（M1 冻结契约）、
> `.workbuddy/refs/ANALYSIS.md`（Venera / Komikku 参考结论）、`venera` 与
> `venera-configs` 参考仓库的**形状分析**（只提炼设计，禁止复制 GPL 代码与源脚本，见 C1）。
> 本文描述宿主侧插件引擎的边界、装配与运行语义；JS 侧声明以 `docs/contract/source.d.ts` 为准。

## 1. 现状与缺口

| 组件 | 已有 | 本次补齐 |
| --- | --- | --- |
| 契约 | `:source:contract` 的 Work/Chapter/SourceMeta/头解析/策略 | 描述符、声明式设置、错误语义、图片加载配置、能力位 |
| 安装 | `SourceInstaller.plan/confirm`（头解析→哈希→ed25519→静态扫描→落库）、`SourceRegistry`（LRU/禁用/回滚） | 头块缺省的老源适配入口、apiVersion 原样透传 |
| Host API | 七命名空间接口 + Ktor/Ksoup/Crypto 实现 | 不改接口；设置读写复用 `HostStorage` 的源私有命名空间 |
| 运行时 | `QuickJsEngine` 单发求值 + 双层桥（同步白名单 / 异步网络） | 描述符探测、Venera 兼容 polyfill、网络参数透传、`fetch`、定时器 |
| 装配 | 无 | `SourceRuntime`（能力门 / 错误分类 / 重放 / 设置 / 图片配置）、`SourceRuntimeManager`（缓存与销毁） |
| 消费端 | `BridgeChapterProvider`、`SourceImageFetcher` | `onImageLoad → ImageRequest` 接线（UA/Cookie 覆盖被剥离） |

引擎沿用 M1 的单发模型（quickjs-kt 构造器私有，`quickJs {}` 阅后即焚）：
**跨调用状态一律经 `loadData/saveData` 落宿主 KV**；装配层缓存的是「脚本 + 描述符 + 宿主配置」，
不是常驻 JS context。这是与 Venera 常驻 Isolate 最大的结构差异，所有设计都以此为前提。

## 2. 组件划分与依赖

```
:source:contract   纯 Kotlin：SourceDescriptor / SourceSetting / SourceCapability /
                   SourceErrorKind / ImageLoadConfig / ContractPolicy（分类与版本集）
        ↑
:source:hostapi    HostApi 七接口（冻结，不新增成员）
        ↑
:source:bridge     QuickJsEngine（polyfill + 双层桥 + describe）
                   SourcePluginEngine（引擎接口，供装配层替换）
                   SourceRuntime（能力门 / 错误 / 重放 / 设置 / 图片配置）
                   SourceRuntimeManager（LRU 装配、按 key 串行、invalidate/dispose）
                   SourceImageConfigBridge（→ :data:images.ImageRequest）
        ↑
:data:images       SourceImageFetcher（白名单 / Cookie / 配对 UA / 审计继承）
```

`SourceRuntime` 只依赖 `SourcePluginEngine` 接口与 `HostStorage`，**不持有 Repository / Database**；
脚本字节、域名白名单、设置 JSON 由调用方以 `PluginRuntimeSpec` 传入（依赖规则 §3.1.4）。

## 3. 生命周期

```
SourceInstaller.plan/confirm（不执行脚本）
   → SourceRecord（script/domains/capabilities/settings_json/apiVersion）
SourceRuntimeManager.acquire(spec)
   ├─ 命中 LRU → 复用 SourceRuntime（同源串行 dispatcher）
   └─ 未命中
       1. QuickJsPluginEngineFactory.create()：QuickJsEngine(sourceKey, HostApi, CookieStore, dispatchers)
       2. engine.load(script)：类发现 → 实例化 → 可选 init()
       3. engine.describe()：只读实例字段 → SourceDescriptor
          （name/key/version/minAppVersion/apiVersion/kind/domains/capabilities/
            settings/translation/explore/category/imageLoad/account/favorites）
       4. 校验：头块声明与实例字段一致（不一致以头块为准并记警告）；
          apiVersion ∈ SUPPORTED_API_VERSIONS；minAppVersion ≤ appVersion
       5. 设置预热：settings_json 覆盖 default，写入 `setting:<key>` KV 命名空间
       6. 缓存；淘汰/禁用/更新时 invalidate(key) → engine.close()
SourceRuntime.call(path, args)
   → 能力门 → engine.invoke → 错误分类 → 可重放错误重放一次 → 原始 JSON
```

`describe()` 在独立的一次求值中完成，不执行任何能力方法；真实源的 `settings` / `explore` 等
字段只是数据或函数引用，序列化时只取形状（函数以布尔存在位表示），不触发网络。

## 4. 契约版本与能力位

- `ContractPolicy.CURRENT_API_VERSION = 2` 保持 M1 冻结基线（缺省值、索引校验、示例源不变）。
- `SUPPORTED_API_VERSIONS = {2, 3}`：`@apiVersion 3` 表示源声明使用扩展能力集；
  v2 源仍可安装运行，宿主按能力位降级——**版本只是能力上界，不是准入闸门**。
- 已冻结字段的语义变更才走新的大版本；新增可选能力通过 `@capabilities` 位与描述符探测启用。
- 能力位：`search / explore / category / comic / novel / account / favorites /
  settings / translation / imageLoad`。宿主未发现的位即隐藏入口；调用未声明能力抛
  `SourceErrorKind.CONTRACT`，不静默返回空。
- `comic.loadInfo` / `comic.loadEp` / `novel.loadInfo` / `novel.loadChapter` 是主链路，
  描述符按命名空间存在性给出 `COMIC` / `NOVEL` 位；缺位时该 kind 视为不可用。

## 5. 声明式设置

源侧 `settings = { [key]: { title, type, options, default, validator, buttonText } }`，
`type ∈ select | switch | input | callback`；`select.options` 为 `{value, text?}` 对象数组，
`default` 对 select 是 value 而不是显示文本。

宿主侧回退链（与 Venera `load_setting` 语义一致）：

```
storage["setting:<key>"]（用户在源管理页设置）
  → descriptor.settings[key].default（装配时预热）
  → null（未声明的 key）
```

- 存储复用 `HostStorage` 的 `sourceKey` 隔离命名空间，键前缀 `setting:` 由 Kotlin 侧强制，
  脚本侧不可伪造；`save_data` 的 `data_key === 'setting'` 被拒绝（沿用 Venera 约束）。
- `loadSetting()` 同步返回字符串或布尔（switch 为 `"true"/"false"` 字符串化，由 JS 侧 `=== true`
  兼容需求决定；本实现统一返回字符串，源作者按 `== 'true'` 判断，写入 `.d.ts`）。
- `settings_json` 列已存在（v1→v2 迁移），设置 UI（M5）只需读写该列并调用
  `SourceRuntime.updateSettings(json)` 使运行态即时生效。

## 6. 错误语义

JS 用字符串 `throw` 表达可识别错误；宿主在桥边界按**子串**分类（Venera 用 `contains`）：

| 标记（不区分大小写） | `SourceErrorKind` | 宿主策略 |
| --- | --- | --- |
| `Login expired` | `LOGIN_EXPIRED` | 触发 `onRelogin()`，成功后**重放一次**原调用；仍失败则抛出 |
| `Rate limited` / `429` / `Cloudflare` | `RATE_LIMITED` | 不重放，交由上层退避；错误面保留原文 |
| `Not found` / `NotFound` | `NOT_FOUND` | 不重试，UI 提示 |
| `domain-blocked` / `disposed-or-unknown` | `PERMISSION` / `CONTRACT` | 不重试，写审计 |
| 超时 / 中断 | `TIMEOUT` | 不重试；`close()` 释放上下文 |
| 其它 | `EXECUTION` | 原样包装，JS 错误文本保留在 message |

`SourcePluginException(message, kind, cause)` 是所有对外错误的统一类型；
`SourceBridgeException` 保留为桥内部异常，由装配层转换，避免调用方见到两套错误面。
重放只做一次、只针对 `LOGIN_EXPIRED`，且 `onRelogin` 由宿主注入（引擎不自己触发登录 UI）。

## 7. 图片加载配置

`comic.onImageLoad(url, comicId, epId)` 可同步或异步返回对象：

```
{ url?, method?, headers?, referer?, data?, maxRetries? }
   → contract.ImageLoadConfig
   → SourceImageConfigBridge.toImageRequest(url, domains)
   → :data:images.SourceImageFetcher.fetch()
```

- 归一化时**剥离** `cookie` / `user-agent` / `host` / `content-length` 头：UA 与 Cookie 由宿主
  统一注入，`cf_clearance` 与 UA 的绑定（`pairedUserAgent`）是架构红线，源脚本不得覆盖。
- URL 重写后仍须经过 `UrlPolicy.isAllowed`（首跳 + 逐跳重定向）复核，白名单在出口强制。
- `maxRetries` 有界（0..3，总尝试 ≤4）；`modifyImage` / `onResponse` 字节变换属独立
  context + 独立超时能力，当前不开放，配置中出现的函数字段一律忽略（不是失败）。
- `onThumbnailLoad` 与 `onLoadFailed` 暂不接线（缩略图管线在 M5），形状在 `.d.ts` 声明。

## 8. Venera 兼容层

只提炼形状，不复制代码。polyfill 必备集（按 34 个真实源的使用覆盖率排序）：

1. `class X extends ComicSource` + class 字段初始化 + `static sources`（100%）；
   基类提供 `loadData/saveData/deleteData/loadSetting/isLogged/translate/init`。
2. `Network.get/post/put/delete/patch/sendRequest/fetchBytes` + Cookie 三件套（100%）；
   **修复已存在的缺陷**：`get` 的 headers、`post` 的 data 必须透传到宿主。
3. `JSON` / `Map` / 模板串 / 可选链 / `async-await`（QuickJS 语言层，无需 polyfill）。
4. `new HtmlDocument` + `querySelector(All)` / `getElementById` / `text` / `innerHTML` /
   `attributes` / `attr:*` / `dispose()`（半数源）。
5. `settings` + `loadSetting`、`onImageLoad` / `ImageLoadingConfig`（各约 60% / 53%）。
6. `console.log/error/warn`、`Convert` 常用子集、`Utils`、`APP`、`fetch`（全局）、
   受控 `setTimeout`。

`sendMessage` 同步桥按架构 §4.6.1 白名单实现：本地方法（`load_data` / `save_data` /
`delete_data` / `load_setting` / `isLogged` / `convert` / `html` / `cookie` / `log` /
`random` / `uuid` / `getLocale` / `getPlatform`）同步返回；网络 / UI / `delay` 返回 Promise；
白名单外直接抛错。真实源均通过 init.js 封装调用，polyfill 不向源作者暴露 `sendMessage` 之外的能力。

**无头老源适配**：`venera-configs` 的脚本没有 `==SourceScript==` 头、无 `domains` / `kind` /
`apiVersion` / `sha256`。适配入口不猜站点逻辑（C1）：导入时由调用方提供
`domains`（用户确认或仓库索引声明）、`kind` 缺省 `["manga"]`、`apiVersion` 缺省 2、
Capabilities 由 `describe()` 探测补齐；无 `sha256` 的索引条目标记为未验证并要求二次确认。
本体不预置任何源、URL 或解析逻辑。

**全量验证**（见 `docs/source-compatibility.md`）：官方源仓库 34 个脚本逐个跑
L1 加载/描述、L2 搜索冒烟、L3 静态 API 盘点。实测 33/34 通过 L1、API 缺口 0。
由该矩阵补齐的兼容要点：`HtmlElement` 元素级选择器（19 源、547 处调用）、
`Convert.hmacString` 真实 HMAC-SHA256、全部 `Convert.*` / `UI.*` 形状、
脚本求值末尾字符串收尾、类型化调用允许同步返回、**每次求值重跑 `init()`**
（单发模型下源在 init 挂载的实例字段不会跨调用保留），以及 `hasSearchLoad`
区分页码与游标分页。唯一未通过 L1 的源依赖外部加密域名列表，属源启动数据依赖。

## 9. 安全边界（MASVS 自查）

| 项 | 强制点 |
| --- | --- |
| 网络 | `HostApi.Network` 出口的域名白名单（含重定向逐跳）；脚本给出的 headers 中 UA/Cookie 被宿主覆盖 |
| 隔离 | 每源一个串行 dispatcher；`loadData/saveData` 以 `sourceKey` 分表；设置键由 Kotlin 加前缀 |
| 能力 | 未声明能力不调用；`invoke(path)` 只允许标识符点分路径 |
| 资源 | 32 MB 内存上限 + 256 KB 栈 + `evaluationTimeoutMillis` + 协程 `withTimeout` + `close()` |
| 供应链 | `ModuleLoader` 拒绝一切 import；安装时 sha256 / ed25519 / 静态扫描 / 用户显式确认 |
| 定时器 | `setTimeout` 受单次 eval deadline 约束，不跨调用边界常驻（单发模型） |
| 日志 | 源脚本日志经 `HostUtils.log`；审计只记域名与状态码，不记 Cookie 与正文 |

## 10. 验收

- `:source:contract`：版本集、能力集、错误分类、设置默认值、描述符解析（纯 Kotlin，三端可跑）。
- `:source:bridge`：描述符探测、能力门、设置回退链、错误分类与一次重放、
  `onImageLoad` 头过滤与 URL 重写、网络 headers 透传、`fetch` / `sendMessage` / `Map` /
  `HtmlDocument` / `setTimeout` 兼容形状、LRU 复用与 invalidate。
- 命令：`./gradlew desktopTest testAndroidHostTest`（native 缺失时引擎 E2E 按既有
  `HostMissingException` 语义跳过，纯 Kotlin 断言不打折）；iOS 目标编译
  `:source:bridge:compileKotlinIosSimulatorArm64` 验证 commonMain 无平台专有依赖。
```
