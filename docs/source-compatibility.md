# 全量 Venera 源兼容性矩阵

> 联网调研 + 本地全量实测，对应「UI 不着急，一个源并不够」的要求。
> 本文只记录方法与结论：不含站点 URL、源脚本或解析逻辑（C1），
> GPL-3.0 源仅在本机只读加载，不入库、不分发。

## 1. 联网调研：源从哪来

| 来源 | 结果 |
| --- | --- |
| 官方 `venera-app/venera-configs` | `index.json` 33 条；本地浅克隆已 `fetch` 到最新，脚本目录共 34 个源（另 1 个 `baihehui.js` 有脚本但未列入索引） |
| 社区文章反复推荐的热门单源 | 拷贝漫画、包子漫画、Komiic、嗨皮漫画、MYCOMIC，全部来自官方仓库，已纳入本次全量测试 |
| `nekoqwq/venera-sources` | `index.json` 返回 404；仓库克隆需认证，判定不可用 |
| `Maplespe/venera-source` | 克隆需认证 / 不可达，判定不可用 |
| `git.nyne.dev` / jsdelivr / ghproxy / gitcode 镜像 | 均为官方仓库的镜像或 CDN，内容同源，不产生新源 |

结论：**可验证的源全集 = 官方仓库 34 个脚本**。第三方订阅地址在当前网络环境不可读取，
若后续可访问，只需把目录传给同一个测试即可增量覆盖，无需改引擎。

## 2. 测试分层

`AllSourcesCompatibilityTest`（`:source:bridge` desktopTest）对每个脚本执行：

- **L1 · 加载/描述**：语法求值 → 实例化 → `init()` → `describe()` 生成描述符（硬指标）。
- **L2 · 搜索冒烟**：有 `search` 能力时调一次；按描述符 `hasSearchLoad` 自动选择
  `searchWorks()` 或 `searchNext()`（游标分页）。结果分 `OK` / `API_GAP`（宿主缺 API）/
  `FIXTURE`（离线响应形状与站点真实结构不符）/ `SKIP`。
- **L3 · 静态 API 盘点**：统计全部源用到的 `Network.*` / `Convert.*` / `UI.*` /
  `Utils.*` 与全局函数，与 polyfill 提供清单求差集。

域名白名单由测试**运行时从脚本里的 URL/域名字面量提取**；网络全部由 Ktor `MockEngine`
拦截，验证过程不访问任何外部站点。未提供 refs 目录时测试自动跳过，CI 零影响。

```
./gradlew :source:bridge:desktopTest \
  -Dkurisa.refsDir=<venera-configs 目录> \
  -Dkurisa.e2eReport=<本地报告路径>
```

## 3. 实测结果（34 个脚本）

| 指标 | 结果 |
| --- | --- |
| L1 加载/描述成功 | **33 / 34** |
| L2 搜索 | OK **15**、API_GAP **0**、FIXTURE 18、SKIP 1 |
| L3 静态 API 缺口 | **0**（`MISSING` 为空） |
| 游标分页源 | 1（只有 `search.loadNext`，宿主自动改走 `searchNext()`） |
| MockEngine 请求 | 61 次，全部命中白名单出口 |

L2 完全跑通的代表：包子漫画、GoDa、H-Comic、爱看漫、jcomic、Komga、MangaDex、
漫画柜、漫画人、18漫画、漫小肆、MYCOMIC、nhentai、少年ジャンプ＋、优酷漫画。

### 唯一 L1 失败：jm.js
`init()` 会先从外部服务拉取一份加密域名列表并解密，再写入实例字段 `apiDomains`；
本地 MockEngine 只能返回通用 JSON，无法提供该列表，导致 `baseUrl` 取到 undefined。
这是**源启动的外部数据依赖**，不是宿主 API 缺失；把该服务的离线 fixture 或 KV 预热
提供给测试即可通过，产品侧无需改动。

其余 18 个 L2=FIXTURE 均为离线响应形状不匹配（`data.list` / `data.comics` /
HTML 结构等），表现为源内部的 `cannot read property 'map' of undefined` 之类；
在真实网络下由各站点的实际响应决定，不应据此判定引擎不兼容。

## 4. 本轮由全量测试驱动修复的引擎缺陷

| # | 问题 | 影响 | 修法 |
| --- | --- | --- | --- |
| 1 | `evaluate<String>(script)` 遇到顶层 `for` 循环（完成值是 long）无类型转换器 | GoDa 等源整个加载失败 | 脚本求值末尾追加 `;'';`，结果恒为字符串 |
| 2 | `Convert.hmacString` 缺失 | 拷贝漫画、多账号版、Picacg 在 `init()` / 签名处崩溃 | 纯 Kotlin HMAC-SHA256，RFC 4231 双向量单测，输出小写 hex |
| 3 | `Convert.*` / `UI.*` 方法名不全 | 3 源加载失败、多源运行期 `not a function` | 补齐全部 Venera 方法名；未开放能力返回空串形状桩而非伪造结果；UI 桌面端降级 |
| 4 | `HtmlElement` 无 `querySelector/All/getElementById` | 19 个源、547 处调用会抛 `not a function` | 以自身 `innerHtml` 建临时文档做元素级查询，结束即 `dispose()` |
| 5 | 类型化调用硬编码 `.then(...)` | 同步返回的源方法调用失败 | `Promise.resolve(...)` 包裹 `searchWorks` / `loadWorkDetails` / `loadChapterPayload` |
| 6 | 单发模型下 `init()` 只在 `load()` 那次求值执行，之后的求值重建实例却丢状态 | 在 init 中挂载实例方法的源（如漫蛙吧）主链路失效 | 每次求值的上下文里统一执行 `init()`；重状态仍要求源经 `loadData/saveData` 落 KV |
| 7 | 能力探测只看 `search` 命名空间存在性 | 只有 `loadNext` 的源被误调 `search.load` | 描述符新增 `hasSearchLoad`，`searchWorks` 给出明确 CONTRACT 提示，宿主自动路由到 `searchNext()` |

修复后 L1 从 30/34 提升到 33/34，L2 的 API_GAP 从 3 降到 0，静态 API 缺口清零。

## 5. 回归门

测试内置动态断言：扫描到 ≥20 个源，且 `L1 成功数 ≥ 源数 - 1`（允许 jm 这一个
已知外部依赖失败）。引擎改动若导致第 2 个源加载失败即红灯；源仓库增删条目不会误报。
T1 收紧（v4 兼容门禁前置）：API_GAP 必须为 0、静态扫描 MISSING 必须为空（此前只打印不锁门）；
提不出域名的脚本记 NO_DOMAINS 且不执行（fail-closed），逐源行带 domains 计数。
HMAC 另有 RFC 4231 向量单测（`CryptoTest`），三端可跑。

## 6. 已知边界

- `Convert.sha1/sha512` 已转正（korlibs-crypto 三端一致，RFC/FIPS 向量锁定）；
  `AES-ECB/CBC/CFB/OFB` 已转正（Base64 进出 + PKCS7，失败垫片回空串，strictV4 抛 CONTRACT）；
  `RSA/GBK` 与 `UI` 弹窗外链仍是**形状兼容桩**（RSA 垫片回空串、GBK 垫片回输入本身；
  strictV4 均抛 CONTRACT），不提供真实密码学或 UI 能力；
  依赖它们的源在离线 fixture 下仍可能报错（如 CCC 追漫台的章节解密）。
- 单份通用 fixture 无法同时满足所有站点的响应结构，L2 的 `FIXTURE` 不等于产品缺陷。
- `Network.put/delete/patch` 当前安全出口只开放 GET/POST，返回明确 status 0。
- iOS 测试任务需 macOS；本次矩阵在 desktopTest 执行，commonMain 改动已由
  `compileKotlinIosSimulatorArm64` 验证可编译。
