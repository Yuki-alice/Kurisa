# ADR-011 插件引擎重构：原生 v4 为主、Venera 薄垫片过渡（DRAFT，未评审）

> 状态：草稿。本文是重构版（替代 11 月 11 日晚的增补版），只定方向与清单，不改引擎实现。
> 已锁定决策（2026-11-11）：D1 老兼容留薄垫片过渡；D2 源放独立仓库、主仓零预置；
> D3 首批按网页情况覆盖选源找引擎缺点。评审通过前不进 docs/architecture.md 正文。
> 相关：ADR-002/003（扩展选型）、ADR-006（库优先）、ADR-009（桌面无内嵌浏览器）、
> docs/plugin-engine.md、docs/source-compatibility.md（34 源矩阵）、docs/contract/source-v4.d.ts。

## 1. 决策

- D1 重构引擎：原生 v4 为主。调用一律无状态（参数传全，不依赖 this 挂载与常驻 init），
  漫画小说统一出口，服务写源与阅读器渲染两端。Venera 形状不再反向塑造引擎。
- D2 老兼容：保留为薄垫片（现有 QuickJsEngine + polyfill 路径冻结，apiVersion 2/3 照跑）。
  只修崩溃不加功能；新源覆盖常用站点后按日落条件删除（见第 3 节）。
- D3 源独立仓库：重写源全部进独立源仓库（索引 + 签名 + CI），主仓库零预置（C1）。
  重写为干净实现（看行为不抄代码，避开 GPL 污染）；新源建议 MIT。

## 2. 新引擎设计（v4 native，要点）

- 无状态调用：search/detail/chapters/content 每次参数传全；状态只走 loadData/saveData（KV 已有）。
  同 runtime 缓存期内 init 只跑一次，跨 invalidate 才重跑（治小说连读限流）。
- 统一三步 + 两种正文：搜索、详情、目录两边同形；正文漫画吐图片数组，
  小说吐带格式正文（html 进 clean、plain 原样、markdown 预留 M4）+ 文内图走 onImageResolve。
- 写源高级货：Html 三 helper（list/text/attr，自动 dispose）、统一分页 page()（ host 路由页码与游标）、
  throwLoginExpired/throwNotFound/throwRateLimited（替代口口相传的字符串分类）。
- 密码学：GBK 与 SHA-1/512 转正；AES/RSA 要么真做要么明确报暂不支持，禁止回空串。
- 网络：只开 GET/POST，PUT/PATCH/DELETE 回明确不支持；UA 与 Cookie 宿主注入，源不可覆盖
  （cf_clearance 配对 UA 红线）。资源上限与超时不放宽，ModuleLoader 继续拒绝一切 import。

## 3. Venera 垫片（过渡）

- 范围：现有引擎路径原样保留，双运行时按 apiVersion 路由（2/3 走垫片，4 走新引擎）。
- 纪律：垫片只接受崩溃修复，不再补新形状；新 helper 一律只进 v4。
- 日落条件（待评审定阈值）：新源覆盖常用站点且稳定两个版本后，删除垫片并关闭 2/3 路由。

## 4. 源仓库（独立，与主仓分离）

- 布局：index.json（元数据 + sha256 + 版本）/ scripts（源脚本）/ signatures（ed25519 分离签名）。
- 安装沿 M2 链路：头解析、哈希、验签、静态扫描、用户显式确认、落库；CI 卡 d.ts 校验与扫描。
- 合规：主仓不出现任何站点 URL 与解析逻辑；GPL 源只在 refs 只读，不入库不分发。

## 5. 首批重写：网页情况覆盖矩阵

漫画维度：M1 数据形态（JSON API / 服务端 HTML / 混杂）；M2 分页（页码 / 游标 / 全量）；
M3 图片（直链 / 防盗链 Referer / 多线路画质 / onImageLoad 动态）；M4 反爬（无 / Cloudflare 挑战 / WAF）；
M5 登录（无 / Cookie / WebView）；M6 发现（explore / category / favorites 有无）；
M7 特殊（HMAC 签名 / 外部加密域名启动依赖）。

漫画首批（34 源内挑，按文档实录映射）：
- S-M1 manga_dex：JSON API + 偏移分页 + 多线路画质 + 声明式设置 + onImageLoad。
  覆盖 M1-JSON、M2-页码、M3-多线路、声明式设置。
- S-M2 拷贝漫画：init 内 HMAC 签名 + 账号体系。覆盖 M7-签名、M5-登录。
- S-M3 MYCOMIC：Cloudflare 挑战与 WAF 分类 + 防盗链。覆盖 M4-反爬、M3-防盗链。
- S-M4 ehentai：只有 loadNext 的游标分页。覆盖 M2-游标（宿主 searchNext 路由）。
- 次批（已知难啃，首批不碰）：nhentai（纯 WebView 登录）、jm（外部加密域名启动依赖）。

小说维度：N1 形态（JSON API / HTML 正文 / TXT）；N2 编码（UTF-8 / GBK）；
N3 目录（单级 / 卷章两级 / 单章多页）；N4 加密（明文 / AES 章节加密）；
N5 混排（纯文字 / 文内插图）；N6 门槛（公开 / 登录付费墙）；N7 发现（排行更新 explore 有无）。

小说首批（3 个，待定）：N-JSON（Miru 风格无状态 JSON 源，覆盖 N1-JSON + N3-单级 + N5-纯文字）；
N-GBK（中文 HTML 站，覆盖 N1-HTML + N2-GBK + N3-卷章）；N-ENC（加密或多页章节，覆盖 N4 或单章多页 + N5-混排）。
具体站点待 refs 落定后按矩阵挑，网络受限日本地无法拉取 Miru 生态，需输入源地址。

## 6. 工作分解

- E 引擎：E1 v4 类型定稿；E2 新运行时（无状态调用 + 双运行时路由）；
  E3 小说链（format 透出 + clean + onImageResolve + GBK）；E4 密码学定案（U1）；
  E5 通道策略（300KB 与 init 缓存，U2/U3）。
- S 源重写：S1 仓库骨架（索引 + 签名 + CI）；S2 漫画 4 个；S3 小说 3 个；S4 写源指南。
- T 测试：T1 门禁收紧（missingApi 与 API_GAP 断言、白名单显式化）；
  T2 小说专用 fixture（四形状 + init 计数）；T3 双运行时矩阵（老 34 跑垫片，新 7 跑 v4）。
- R 渲染：阅读器小说分页与混排渲染（M4 依赖 v4 format 输出）。

## 7. 验收

- 7 个首批源全绿（含 init 计数、format 透出、GBK、游标、过盾分类）。
- 老 34 垫片回归不跌（L1 成功数不低于基线）。
- 主仓扫描零站点 URL；源仓库签名链可复现；写源指南可用（新人照着写出第 8 个源）。

## 8. 未决

- Q1：小说 3 个具体站点（需 Miru 生态地址或本地 refs 路径，网络受限待输入）。
- U1：AES/RSA 转正选型或显式拒绝。
- U2：300KB 截断对小说通道策略。U3：init 缓存语义与计数断言。
- U4：markdown 渲染归 M4。U5：SUPPORTED 接纳 4 的时机与垫片日落阈值。
