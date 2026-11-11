# E2 双运行时路由设计（DRAFT，未评审，实现 ADR 输入）

> 状态：E2a 已落地（纯委托）；E2b-minimal 已落地（strictV4 + KurisaNative工厂）；
> E2c 路由已开（4 走原生、未知版本 CONTRACT、共存/invalidate 隔离有单测）。
> v4 名字已锁定（novelChapter/htmlHelper/cryptoAes，ContractPolicy.V4_CAPABILITIES 已同步）。
> 相关：SourceEngine.kt（SourcePluginEngine/PluginEngineFactory）、SourceRuntimeManager.kt、
> ADR-011、docs/contract/source-v4.d.ts。

## 1. 现状（只读，不改）

- 装配缝已存在：SourceRuntimeManager.acquire(spec) → engines.create(spec, host) → load/describe/门禁。
- spec 自带 apiVersion；signature() 含版本 + 脚本 + 域名 + 设置，切版本自动重建，无缓存污染。
- SourceRuntime 只依赖 SourcePluginEngine 接口（describe/设置/能力门/重放），与 QuickJsEngine 解耦。
- 结论：路由只需换 PluginEngineFactory 的实现，manager、Runtime、门禁零改。

## 2. 路由设计

- `VersionedPluginEngineFactory`：按 spec.apiVersion 选实现（4 优先判原生，再走 SUPPORTED 垫片）。
- 2/3 走 QuickJsPluginEngineFactory（垫片，冻结，只修崩溃；Convert 形状桩保兼容）。
- 4 走 KurisaNativePluginEngineFactory（E2b-minimal：复用 QuickJsEngine + strictV4；
  未转正密码学显式 CONTRACT，禁静默桩）。
- 未知版本 fail-closed：抛 CONTRACT（未知能力上界不猜），审计留痕；Versioned 不触碰 legacy（有单测锁定）。
- minAppVersion 语义沿 ADR-011；SUPPORTED 已接纳 4（ContractPolicy）；日落阈值待 U5。

## 3. 接口复用

- 新引擎同样实现 SourcePluginEngine（describe/applySettings/callSync/callAsync/类型化调用）。
- v4 增量（search.page、format 透出、clean、onImageResolve、小说双位）走 invoke 扩展 + E3 接线，
  不在本设计加新接口方法（名字待评审，避免返工）。
- HostMissingException 与测试 SKIP 语义保留；超时三件套与资源上限同配。

## 4. 隔离

- dispatcher 沿用 DefaultSourceDispatchers（按源 key 串行已保证），双引擎共用无交叉。
- invalidate/close/evict 语义不变；同一 manager 可共存 v3 + v4 运行时，互不干扰（验收必测）。

## 5. 分步（每步独立可验）

- E2a 工厂缝：Versioned 工厂只做委托（2/3 走旧实现），行为零变，全量矩阵重跑。已落地。
- E2b 原生路由（终态）：KurisaNative 工厂复用 QuickJsEngine + strictV4（U1/U1b 显式 CONTRACT）。
  说明：依 ADR-003 三端同一份 QuickJS C，不存在第二套 JS 运行时；
  “原生引擎”即同一引擎 + v4 严格策略（无 second runtime）。
  无状态调用强化 = 已落地的三件套（per-call init 单测锁定 + KV 唯一状态通道 +
  v4 禁止 init 挂载），无需也不应另起引擎。
- E2c 路由开：apiVersion=4 进原生；未知版本 CONTRACT 且不触碰 legacy；
  双共存 + invalidate 隔离有单测（SourceRuntimeUnitTest）。已落地路由与隔离测试。

## 6. 测试（T3 输入）

- 双矩阵：老 34 跑垫片（T1 门禁），新 7 跑 v4（T2 fixture + per-call init 单测）。
- 共存：同 manager 装 v3 + v4 不同 key，断言各走各的引擎且 invalidate 隔离
 （SourceRuntimeUnitTest.managerCoexistsV3AndV4AndInvalidatesIndependently）。
- 未知版本：apiVersion=99 直接 CONTRACT，不触碰 legacy（VersionedEnginesTest）。
- v4 严格拒绝：AES/RSA/GBK/非 sha256 hmac 在 strictV4 下 CONTRACT（V4HelpersTest）。

## 7. 验收

- E2a：全量矩阵与此前一致（L1/ API_GAP/MISSING 门禁全绿）。已绿。
- E2b-minimal：4 经原生工厂可 acquire/describe/主链路调用；strictV4 拒绝未转正密码学。已绿。
- E2c：共存 + 未知版本 + invalidate 隔离三断言绿。已绿（单测层；
  全量矩阵已用本地 refs 复跑多轮一致：L1 33/34、API_GAP 0）。

## 8. 未决

- U2 分媒体策略（截断可观测已落地：脱敏日志 + MAX_BODY_CHARS + 单测；分媒体阈值待产品输入）。
- E2b 终态说明见 §5：不存在第二套 JS 运行时（ADR-003），无后续引擎项。
- AES 已转正（ECB/CBC/CFB/OFB，Base64+PKCS7）；RSA deferred（无三端库）；
  GBK deferred（无三端 Charset）。strictV4 显式 CONTRACT，垫片保兼容。
- 新引擎与垫片共享 CookieStore 实例（建议共享：同一源升级不断登录态，待安全评审）。
- 全量矩阵待 refs 落定重跑；垫片日落阈值待 U5。
