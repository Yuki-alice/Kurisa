# 源包规范（一体包：`defineSource` 唯一模板）

> 状态：**目标态，与 `DESIGN.md` / `HOST-SDK.md` 同版本**。
> 替代 `DESIGN.md §6` 的“两种形态”描述：不再维护 `规则 JSON` 与 `JS class` 两套包，
> 用一个 `defineSource` 对象统一两者。旧 `class X extends ComicSource` 源继续可用（只读兼容，不再作为模板）。
> 日期：2026-11-12。
>
> 实施说明：当前实现为 `js/v1` 与 `rules/v1` 双形态分派（`detectFormat` 按头块 `@format` / 扩展名识别，`RulesEngine` 执行 `rules/v1`，`js/v1` 走 `QuickJsEngine`）；`defineSource` 一体包仍是本规范的目标态、尚未落地，勿误以为代码已在跑单一模板。

## 0. 为什么只要一种模板

正式代码现状（`Kurisa/docs/contract/manga-example.js`、`novel-example.js`、`v4-example.js`）：

* 作者入口是 `class DemoManga extends ComicSource`，字段为 `search/comic/novel/explore/category/settings/translation`；
* 头块 `// ==SourceScript==`（`source/contract/.../SourceScriptHeader.kt:parse`）是安装期唯一可信输入；
* 索引 `docs/contract/index-schema.json` 要求 `url/filename` 二选一 + `sha256`。

问题：`DESIGN.md L0` 若保留 `R=规则JSON` + `J=JS源` 两套包，头块、索引、签名、静态扫描、测试面板、编辑器提示要维护两套，
且 `search.load/loadNext/page`、`comic vs novel` 双命名空间会继续膨胀（Miru 的教训是四方法同形即可覆盖漫画/小说/音视频）。

裁决：**永远一个文件、一个头、一个索引、一个模板**。纯对象走 Kotlin 规则执行器（不起 QuickJS），
任一字段为函数即切 JS 引擎（逃生舱）。宿主按“是否含函数”自动分派，作者心智只有一套。

## 1. 包形态

```
<key>.js  <- 永远一个文件，与现 `Kurisa-Sources/scripts/<key>.js` 一致
  // ==SourceScript== 头块（不执行，见 §2）
  // @format js/v1 或 rules/v1（仅此一个新增头字段）
  defineSource({ ... })  <- 唯一作者入口
```

* `js/v1`：含函数字段，走长驻 `QuickJs` 实例 + bytecode（`DESIGN.md D-1`）。
* `rules/v1`：纯对象（无函数），走 Kotlin 规则执行器，不起 JS 引擎，又快又省内存。
* 文件扩展名统一 `.js`（规则子集也是合法 JS 对象字面量，便于编辑器提示与 `source.d.ts` 类型检查）；
  若仓库偏好分离存放，允许 `filename` 指向 `.rules.json`，其内容必须与 `defineSource({...})` 的对象体同构（仅无外层调用）。

`Kurisa-Sources/index.json` 条目（`source/contract/.../Models.kt:IndexedSource` + `ContractPolicy.validateIndex`）：

```json
{ "key": "gufengmh.com", "filename": "gufengmh.com.js",
  "format": "js/v1", "version": "1.0.0", "apiVersion": 4,
  "kind": ["manga"], "domains": ["m.gufengmh.com"], "sha256": "<hex>" }
{ "key": "example.site", "filename": "example.site.rules.json",
  "format": "rules/v1", "version": "1.0.0",
  "kind": ["manga"], "domains": ["example.site"], "sha256": "<hex>" }
```

* `format` 为可选字段，缺省按 `js/v1` 处理（老源零改动）。
* 未知 `format` 的旧客户端 fail-closed：拒绝安装，不执行脚本（与未知 `apiVersion` 同策略，见 `VersionedEngines.kt`）。

## 2. 头块（安装期可信输入）

沿用 `SourceScriptHeader.parse`（`BEGIN/END` + `// @key value`），新增：

| 头字段 | 必填 | 说明 |
| --- | --- | --- |
| `@name/@key/@version/@minApp/@apiVersion/@kind/@domains` | 是 | 与现 `ContractPolicy.validateMeta` 一致；`key` 反向域名，`version/minApp` semver |
| `@capabilities` | 是 | 与 `SourceDescriptor` 共用名字（`EngineModels.kt:SourceCapability`）；头块为空时不收窄（`effectiveCapabilities` 语义不变） |
| `@format` | 否 | `js/v1`（缺省）或 `rules/v1`；与索引 `format` 不一致时以索引为准并拒绝（防脚本私自抬高） |
| `@url` | 否 | 更新地址（现 `SourceMeta.url`） |

校验顺序（`SourceInstaller.plan` 不变，仅分支扫描器）：

```
头块解析 → ContractPolicy.validateMeta → 索引对照（key/version/sha256，format 一致性）
→ ed25519（signedMessage = key|version|sha256）→ 静态扫描 → 落盘 + 写库
```

* `rules/v1` 跳过 `eval/Function` 扫描，只做域名对照 + 字段白名单校验。
* `js/v1` 走现有 `SourceStaticScan` 全量规则。

## 3. `defineSource` 模板（唯一模板）

以现 `gufengmh.com.js` 的 `fixUrl/headers/fetchOk/parseCard + new HtmlDocument/dispose` 为反例，
以下样板全部下沉 Host（`HOST-SDK.md`），作者只填三段：

```js
// ==SourceScript==
// @name 古风漫画
// @key gufengmh.com
// @version 1.0.0
// @format js/v1
// @apiVersion 4
// @kind manga
// @domains m.gufengmh.com
// @capabilities search,comic
// ==/SourceScript==

defineSource({
  baseUrl: 'https://m.gufengmh.com',
  search: {
    request: (kw, page) => `/search/${encodeURIComponent(kw)}/${page}/`,
    list: '.comic-list li',
    fields: {
      id: 'a@href | fileName',      // 宿主做：相对转绝对、取文件名、去重
      title: 'a@text | trim',
      cover: 'img@data-src|@src | absUrl',
    },
    pagination: { maxPage: 'auto' }, // 宿主推断，不手写 page+1
  },
  detail: {
    request: (id) => `/comic/${id}/`,
    title: 'h1@text',
    cover: '.book-cover img@src | absUrl',
    chapters: { list: '#chapter-list a', id: '@href | fileName', title: '@text | trim' },
  },
  content: {
    images: { list: '#chapter-images img', value: '@data-src|@src | absUrl' },
    // 需要登录/签名/解密时，把上面任意字段替换成函数即自动切 JS 引擎：
    // images: async (ctx, Net, Parse) => { const body = await Net.getHtml(...); ... }
  },
})
```

字段表（冻结子集，超出即按 `js/v1` 处理）：

| 段 | 纯对象形态（规则） | 函数形态（JS） |
| --- | --- | --- |
| `search` | `{ request, list, fields{id,title,cover}, pagination }` | 任一字段为函数 |
| `detail` | `{ request, title, cover, chapters{list,id,title} }` | 同上 |
| `content.images` | `{ list, value }`（漫画） | 同上 |
| `content.text` | `{ select, clean: true }`（小说；`format` 由宿主按清洗通道透出 `html/plain`） | 同上 |
| `content.stream` | `{ url, type: 'hls\|mp4\|dash' }`（视频/音频预留类型，不接播放器，运行时显式 `CONTRACT`，与 `BridgeChapterProvider.payload` 一致） | 同上 |

* 小说 `content.text` 清洗沿用 `NovelHost.stripHtml` 语义（`script/style` 丢弃、块级换行、实体还原），渲染层受限标签白名单不变（`architecture.md §4.4.3`）。
* 管道符 `|` 后的过滤器（`trim/absUrl/fileName`）由宿主实现，规则层不引入表达式语言（防 Kazumi 式字段膨胀）。
* 规则表达不了的能力（登录、签名参数、加密章节、图片解密、字体反爬）一律写函数，不为它们扩字段（`DESIGN.md D-2`）。

## 4. 运行时分派（宿主视角）

```
acquire(spec)
  → describe() → SourceDescriptor（存在位，与现 EngineModels.kt 一致，不含函数对象）
  → capabilities = 探测 ∩ 版本上界 ∩ 头块声明（ContractPolicy.effectiveCapabilities 不变）
  → 若 defineSource 体无函数 → Kotlin 规则执行器（复用 SourceDescriptor/能力位/SourceHttpClient）
  → 若含函数 → QuickJs 长驻实例（P0 生命周期，bytecode 缓存键 scriptSha256+engineVersion+moduleName）
```

* `SourceRuntime` 门面不变（能力门 + 错误分类 + `LOGIN_EXPIRED` 重放一次 + `parseImageConfig` 归一化）。
* `BridgeChapterProvider` 映射不变（`chapters` 三形状兼容、`images`/`content|text` 双键兼容、`VIDEO/AUDIO` 显式失败）。
* `RegistryChapterProvider.bridgeFor` 当前每次 `chapters/payload/resolvePage` 都 `loadScript(record.installPath)`（`ReaderSourceAssembly.kt:98-116`），
  P0 必须加脚本内容缓存（按 `key+sha256`），否则规则路径也付出每页读盘 IO（`DESIGN.md §4` 已记录）。

## 5. 迁移与兼容

* 现 34 个 `class extends ComicSource` 源：零改动继续可用；模板文档主推 `defineSource`，不再更新旧类模板。
* `manga-example.js / novel-example.js / v4-example.js`：P2 起改为 `defineSource` 写法并保持可跑断言（`ContractRunnerTest` fixture 版）。
* `Kurisa-Sources/scripts/_template.*`：合并为单个 `_template.source.js`（本规范 §3 形状），复制后改 `key/domains` + 三段选择器即可跑。
* 规则 `schema` 版本独立于 `apiVersion`（`rules/v1 → v2`），宿主按 `format` 分派执行器；`apiVersion` 仍按 `ContractPolicy.SUPPORTED_API_VERSIONS` 校验。

## 6. 开放问题（转自 DESIGN §13，本规范落地前必须结论）

1. `captchaScript` 类 JS 片段放在哪个上下文执行（独立实例 / 主实例快照 / 拒绝）。
2. 规则 `select` 方言：CSS 子集 + XPath 子集的最小交集（以 Ksoup 已支持为准，不另起解析器）。
3. `index.json` 的 `format` 与旧客户端兼容性提示文案（fail-closed 时的用户可见错误）。
