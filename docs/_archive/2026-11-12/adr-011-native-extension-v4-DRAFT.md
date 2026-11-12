# ADR-011 插件引擎重构：原生 v4 为主、Venera 薄垫片过渡（DRAFT，未评审）

> 状态：草稿。只定方向与清单，不改引擎实现。
> 已锁定决策（2026-11-11）：D1 老兼容留薄垫片过渡；D2 源放独立仓库、主仓零预置；
> D3 首批按网页情况覆盖选源找引擎缺点；D4 音视频只预留扩展点、本轮不实现。
> 评审通过前不进 docs/architecture.md 正文。
> 相关：ADR-002/003（扩展选型）、ADR-006（库优先）、ADR-009（桌面无内嵌浏览器）、
> docs/plugin-engine.md、docs/source-compatibility.md（34 源矩阵）、docs/contract/source-v4.d.ts。
> Miru 启示见第 2 节（仅提炼设计，AGPL 代码不复制、不入库，见 C1）。

## 1. 决策

- D1 重构引擎：原生 v4 为主。调用一律无状态（参数传全，不依赖 this 挂载与常驻 init），
  漫画小说统一三步出口（搜索、详情、目录/正文），服务写源与渲染两端。
  Venera 形状不再反向塑造引擎。
- D2 老兼容：保留为薄垫片（现有 QuickJsEngine + polyfill 路径冻结，apiVersion 2/3 照跑）。
  只修崩溃不加功能；新源覆盖常用站点后按日落条件删除（见第 4 节）。
- D3 源独立仓库：重写源全部进独立源仓库（索引 + 签名 + CI），主仓库零预置（C1）。
  重写为干净实现（看行为不抄代码，避开 GPL/AGPL 污染）；新源建议 MIT。
- D4 音视频预留（已落类型与能力扩展点，渲染 deferred）：ContentKind.VIDEO/AUDIO +
  SourceCapability.VIDEO/AUDIO（v4 opt-in）已进 ContractPolicy.V4_CAPABILITIES；
  调用侧（SourceRuntime）与归一层（BridgeChapterProvider）对 VIDEO/AUDIO 显式 CONTRACT，
  不写视频/音频源、不接播放器。播放器、代理、元数据抓取归应用层。

## 2. Miru 启示（设计提炼，不复制实现）

对照 `miru-project/miru-app`（Flutter + `flutter_js`，QuickJS/JSC 双后端，类型 `manga/bangumi/fikushon`）：

1. **统一三步 + 剧集分组值得借鉴**：Miru 全类型共用 `search/latest/detail/watch`，
   详情统一为 `episodes[{title, urls[{name, url}]}]` 分组，可表达漫画卷章、小说分卷、
   剧集季/集。Kurisa v4 采纳：`WorkDetails.chapters` 统一数组形态，
   卷章两级用分组字段表达，不再用 Map 兼容形态写新源。
2. **watch 按媒体分形**：Miru `watch` 按类型返回 `ExtensionBangumiWatch{hls/mp4/torrent, url, subtitles[], headers, audioTrack}` /
   `ExtensionMangaWatch{urls[], headers}` / `ExtensionFikushonWatch{content[], title}`。
   Kurisa 对应为 `ChapterPayload` 密封扩展：`Images` / `Text`（本轮）/ `Stream`（预留，
   字段仅 `url/streamType/headers/subtitles/quality`，播放器接线归 M4 之后）。
3. **默认头回退**：Miru 对列表/详情/正文结果执行 `headers ??= {Referer, UA, Cookie}`。
   Kurisa 已有 UA/Cookie 宿主注入 + `sanitized()` 剥离 + 白名单复核，v4 补一条：
   源未给头时宿主自动回退默认头，源给了业务头则合并但不覆盖宿主注入项。
4. **调试双日志 + 热重载**：Miru 的扩展日志窗口 + 网络日志窗口（方法/URL/请求头/响应头/状态码/正文，
   不脱敏是其短板，Kurisa 审计坚持不记 Cookie 与正文）以及目录监听改 `.js` 即重载，
   是“好写”的关键。v4 要求补齐：按源分片的日志上下文 + 脱敏网络日志 + 开发期热重载，
   生产审计沿用现有脱敏规则。
5. **仓库索引模式**：Miru 以仓库地址配 `index.json` 拉全量条目（含 NSFW 过滤），
   与 Kurisa 独立源仓库方向一致。Kurisa 多了 sha256/ed25519/静态扫描/显式确认，
   不退化；NSFW 标记可借鉴为索引可选字段。
6. **过滤器模型更强**：Miru `createFilter -> {title, min, max, default, options}` 支持多选与数量约束，
   强于 Kurisa 现有 `optionList/defaultOptions` 首项语义。v4 过滤器演进（U7）以此为目标，
   但接口另起能力位，不改 v2/v3 冻结语义。
7. **播放器外置是对的**：Miru 内置 `media_kit` + 外播 VLC/mpv/potplayer + 外部字幕下载，
   引擎只给 URL 与头。Kurisa 照抄该分层：引擎不管解码渲染，只保证 Stream 描述完整可播。
   BT/torrent、代理协议（HTTP/SOCKS）、TMDB/AniList 类元数据增强一律不在引擎，
   torrent 明确不做（安全与体积），代理归应用层统一配置。
8. **常驻与单发的取舍**：Miru 每个源常驻一个 runtime（`runtimes[package]`，`load()` 跑一次，
   实例常驻，后续直接调实例方法），性能好、写法自然（可 `this` 挂载），
   代价是状态污染面大、Linux 端曾因 quickjs 依赖无法启动而缺席。
   Kurisa 单发模型（`quickJs{}` 阅后即焚 + 每次重跑 `init` + KV 落盘）隔离性与三端一致性更强，
   符合 C2/C3。v4 不改单发；U3 决议见 §3（逐调用 init 为定义语义，init-once 与 fresh context 互斥）。

## 3. 新引擎设计（v4 native，要点）

- 无状态调用：search/detail/chapters/content 每次参数传全；状态只走 loadData/saveData（KV 已有）。
  init 语义（U3 决议）：单发每次求值重建全新 JS context，`init` 必须在本次上下文重跑
  （否则 init 挂载的实例方法/字段丢失，见兼容性矩阵 #6 漫蛙吧案；fresh context 与 init-once 互斥，
  init-once 方案否决）。连读限流的治法不是缓存 init，而是要求 v4 `init` 无网络副作用
  （仅 KV 预热；网络一律走能力调用），使重跑廉价无害；跨调用状态一律经 KV 落盘。
  逐调用 init 由单测锁定（load→1、invoke→2/3 计数）。
- 统一三步 + 三种正文形态：搜索、详情、目录三端同形；正文为密封载荷
  `Images`（漫画图片数组）/ `Text`（小说带格式正文，html 进 clean、plain 原样、markdown 预留 M4）/
  `Stream`（预留，见 D4）。文内图统一走资源解析（图片/文内图/音视频共用同一归一与白名单复核）。
- 写源高级货：Html 三 helper（list/text/attr，自动 dispose）、统一分页 page()
  （host 按 hasSearchLoad/Next 路由页码与游标）、过滤器演进（U7）、
  throwLoginExpired/throwNotFound/throwRateLimited（替代口口相传的字符串分类）。
- 密码学：SHA-1/512 经 korlibs-crypto 转正；AES（API 已确认存在）下轮对向量实现（U1）；
  GBK 无三端库延期（U1b）；转正前一律显式拒绝、不回空串（禁静默桩）。
- 网络：只开 GET/POST，PUT/PATCH/DELETE 回明确不支持；UA 与 Cookie 宿主注入，源不可覆盖
  （cf_clearance 配对 UA 红线）。默认头回退（第 2 节第 3 条）。
  资源上限与超时不放宽，ModuleLoader 继续拒绝一切 import。
  300KB 截断策略按 U2 定案（小说/流媒体描述与漫画不同策略，截断必须显式可观测）。

## 4. Venera 垫片（过渡）

- 范围：现有引擎路径原样保留，双运行时按 apiVersion 路由（2/3 走垫片，4 走新引擎）。
- 纪律：垫片只接受崩溃修复，不再补新形状；新 helper 一律只进 v4。
- 日落条件（待评审定阈值）：新源覆盖常用站点且稳定两个版本后，删除垫片并关闭 2/3 路由。

## 5. 源仓库（独立，与主仓分离）

- 布局：index.json（元数据 + sha256 + 版本）/ scripts（源脚本）/ signatures（ed25519 分离签名）。
- 安装沿 M2 链路：头解析、哈希、验签、静态扫描、用户显式确认、落库；CI 卡 d.ts 校验与扫描。
- 合规：主仓不出现任何站点 URL 与解析逻辑；GPL/AGPL 源只在 refs 只读，不入库不分发。
- 借鉴 Miru：索引支持 NSFW 可选字段与仓库地址可配；卸载时清该源设置键（对标 cleanSettings）。

## 6. 首批重写：网页情况覆盖矩阵

漫画维度：M1 数据形态（JSON API / 服务端 HTML / 混杂）；M2 分页（页码 / 游标 / 全量）；
M3 图片（直链 / 防盗链 Referer / 多线路画质 / onImageLoad 动态）；M4 反爬（无 / Cloudflare 挑战 / WAF）；
M5 登录（无 / Cookie / WebView）；M6 发现（explore / category / favorites 有无）；
M7 特殊（HMAC 签名 / 外部加密域名启动依赖）。

漫画首批（34 源内挑 4 个）：
- S-M1：JSON API + 偏移分页 + 多线路画质 + 声明式设置 + onImageLoad。
  覆盖 M1-JSON、M2-页码、M3-多线路、声明式设置。
- S-M2：init 内 HMAC 签名 + 账号体系。覆盖 M7-签名、M5-登录。
- S-M3：Cloudflare 挑战与 WAF 分类 + 防盗链。覆盖 M4-反爬、M3-防盗链。
- S-M4：只有 loadNext 的游标分页。覆盖 M2-游标（宿主 searchNext 路由）。
- 次批（已知难啃，首批不碰）：纯 WebView 登录源、外部加密域名启动依赖源。

小说维度：N1 形态（JSON API / HTML 正文 / TXT）；N2 编码（UTF-8 / GBK）；
N3 目录（单级 / 卷章两级 / 单章多页）；N4 加密（明文 / AES 章节加密）；
N5 混排（纯文字 / 文内插图）；N6 门槛（公开 / 登录付费墙）；N7 发现（排行更新 explore 有无）。

小说首批（3 个，待定）：N-JSON（无状态 JSON 源，覆盖 N1-JSON + N3-单级 + N5-纯文字）；
N-GBK（中文 HTML 站，覆盖 N1-HTML + N2-GBK + N3-卷章）；N-ENC（加密或多页章节，覆盖 N4 或单章多页 + N5-混排）。
具体站点待 refs 落定后按矩阵挑。

视频/音频：本轮只做 `Stream` 类型与能力位预留，不选源、不写源、不接播放器。

## 7. 工作分解

- E 引擎：E1 v4 类型定稿（已落地）；E2 双运行时路由（E2a 委托 + E2b 原生 strictV4 +
  E2c 未知版本 fail-closed，均已落地；不存在第二套 JS 运行时，见 v4-dual-runtime §5）；
  E3 小说链（format 透出 + clean + 资源解析统一，已落地；GBK deferred 无三端库）；
  E4 密码学（AES-ECB/CBC/CFB/OFB 已转正；RSA deferred 无三端库）；
  E5 通道策略（300KB 截断可观测已落地；init 语义按 U3 决议逐调用重跑；分媒体阈值待产品输入）；
  E6 媒体预留类型（Stream 描述 + video/audio 能力位，无播放器接线，已落地类型与显式 deferred 门）。
- S 源重写：S1 仓库骨架（索引 + 签名 + CI）；S2 漫画 4 个；S3 小说 3 个；S4 写源指南
  （含调试双日志与热重载用法）。
- T 测试：T1 门禁收紧（missingApi 与 API_GAP 断言、白名单显式化）；
  T2 小说专用 fixture（四形状 + per-call init 语义测试）；T3 双运行时矩阵（老 34 跑垫片，新 7 跑 v4）；
  T4 媒体预留类型编译门（`StreamPlayback` 可序列化、可审计 + sanitized，不跑真实播放，已绿）。
- R 渲染：阅读器小说分页与混排渲染（M4 依赖 v4 format 输出）；音视频播放器 deferred。

## 8. 验收

- 合成源全绿：5 类 archetype E2E（漫画 JSON/HTML/过盾 + 小说 JSON/HTML）+
  v4-example DocTest + V4 主链路；per-call init、format 透出、游标/页码统一分页、
  过盾 RATE_LIMITED 分类均有单测锁定（GBK 除外：deferred）。
- 老 34 垫片回归不跌（L1 成功数不低于基线；本地 refs 复跑多轮一致）。
- 主仓扫描零站点 URL（RepoHygieneTest 门禁 + 灯具去真实站点化）；
  源仓库已建仓；写源指南可用（v4-example.js 可被引擎真实加载执行）。
- `Stream` 预留类型可编译、可序列化、可审计（StreamPlayback + sanitized 单测），
  不触发播放器依赖；torrent 不在验收内。

## 9. 未决与已决

- Q1：小说 3 个具体站点（需 refs 落定后按矩阵挑；S3 重写待定）。
- U1：AES 已转正（ECB/CBC/CFB/OFB，Base64 进出 + PKCS7，CryptoTest 回环锁定）；
  RSA 终策为 deferred（korlibs-crypto 6.0.0 经 jar 核验无 RSA 实现；
  strictV4 显式 CONTRACT，垫片回空串保 34 源兼容）。
- U1b：GBK 终策为 deferred（korlibs-encoding 6.0.0 仅 ASCII/Base64/Hex，经 jar 核验无 GBK；
  strictV4 显式 CONTRACT，垫片回输入本身保兼容；自带码表/平台注入二选一待架构评审，不在本轮）。
- U2：300KB 截断可观测已落地（超限写脱敏调试日志：源 key + 原长度，不含 URL/正文；
  常量收口 `QuickJsEngine.MAX_BODY_CHARS`，单测锁定）；分媒体差异策略待定。
- U3：已决议（逐调用 init 为单发定义语义，KV 为唯一跨调用状态通道；
  v4 `init` 须无网络副作用；init-once 与 fresh context 互斥，否决；单测锁定计数）。
- U4：markdown 渲染归 M4。U5：SUPPORTED 已接纳 4（R1 落地）；垫片日落阈值待定。
- U6：已冻结（`StreamPlayback` + `StreamType` hls/mp4/dash + 字幕/清晰度字段；
  EngineModelsTest 序列化往返 + sanitized 单测锁定；d.ts 同步）。
- U7：过滤器模型演进（对标 createFilter 的 min/max/default/options，接口另起能力位）。
- U8：代理归应用层统一配置，引擎不感知；外播 player 归渲染层。
