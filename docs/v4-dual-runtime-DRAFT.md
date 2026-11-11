# E2 双运行时路由设计（DRAFT，未评审，实现 ADR 输入）

> 只定路由与分步，不动引擎实现。v4 名字沿 ADR-011 草稿（novelChapter/htmlHelper/cryptoAes），评审可改。
> 相关：SourceEngine.kt（SourcePluginEngine/PluginEngineFactory）、SourceRuntimeManager.kt、
> ADR-011、docs/contract/source-v4.d.ts。

## 1. 现状（只读，不改）

- 装配缝已存在：SourceRuntimeManager.acquire(spec) → engines.create(spec, host) → load/describe/门禁。
- spec 自带 apiVersion；signature() 含版本 + 脚本 + 域名 + 设置，切版本自动重建，无缓存污染。
- SourceRuntime 只依赖 SourcePluginEngine 接口（describe/设置/能力门/重放），与 QuickJsEngine 解耦。
- 结论：路由只需换 PluginEngineFactory 的实现，manager、Runtime、门禁零改。

## 2. 路由设计

- 新增 VersionedPluginEngineFactoryengines：按 spec.apiVersion 选实现。
- 2/3 走 QuickJsPluginEngineFactory（垫片，冻结，只修崩溃）。
- 4 走新 KurisaNativeEngine（E2b 骨架，默认 flag 关闭，打开前行为零变）。
- 未知版本 fail-closed：抛 CONTRACT（未知能力上界不猜），审计留痕。
- minAppVersion 语义、SUPPORTED 接纳 4 的时机（U5）、日落阈值不变，沿 ADR-011。

## 3. 接口复用

- 新引擎同样实现 SourcePluginEngine（describe/applySettings/callSync/callAsync/类型化调用）。
- v4 增量（search.page、format 透出、clean、onImageResolve、小说双位）走 invoke 扩展 + E3 接线，
  不在本设计加新接口方法（名字待评审，避免返工）。
- HostMissingException 与测试 SKIP 语义保留；超时三件套与资源上限同配。

## 4. 隔离

- dispatcher 沿用 DefaultSourceDispatchers（按源 key 串行已保证），双引擎共用无交叉。
- invalidate/close/evict 语义不变；同一 manager 可共存 v3 + v4 运行时，互不干扰（验收必测）。

## 5. 分步（每步独立可验）

- E2a 工厂缝：Versioned 工厂只做委托（2/3 走旧实现），行为零变，全量矩阵重跑。
- E2b 新引擎骨架：KurisaNativeEngine 空壳（load/describe 走通，能力返回空），flag 默认关。
- E2c 路由开：apiVersion=4 进新引擎；未知版本 CONTRACT；双共存测试进 T3。

## 6. 测试（T3 输入）

- 双矩阵：老 34 跑垫片（T1 门禁），新 7 跑 v4（T2 fixture + init 计数）。
- 共存：同 manager 先后装 v3 与 v4 同 key 不同版本，断言各走各的引擎且 invalidate 隔离。
- 未知版本：apiVersion=99 直接 CONTRACT，不触发脚本求值。

## 7. 验收

- E2a：全量矩阵与此前一致（L1/ API_GAP/MISSING 门禁全绿）。
- E2b：flag 关时行为零变；开时 v4 模板源可 describe。
- E2c：共存 + 未知版本 + invalidate 隔离三断言绿。

## 8. 未决

- v4 三个能力位名字（ADR-011 待拍板），影响 helper 全局名，不影响本路由。
- 新引擎与垫片是否共享 CookieStore 实例（建议共享：同一源升级不断登录态，待安全评审）。
