# M2 数据层评审记录

> 评审对象：`:core:model`、`:data:db`、`:data:network`、`:data:repository`、
> `:source:registry`（`:source:contract` 为 M1 冻结，不动）。
> 结论：**通过**。Gate 三项（断网书架可用；迁移测试通过含回滚；签名/权限校验生效）
> 均由自动化测试锁定，三端（desktopTest / testAndroidHostTest / iosSimulatorArm64Test）全绿。

## 1. 交付对照（架构 §5 / §8 / 4.2·4.7·4.8）

| 交付 | 落点 | 说明 |
|---|---|---|
| Domain 模型 | `:core:model` | Shelf/ShelfItem/ReadingProgress/DownloadTask/Bookmark/LocalBook/SourceRecord/ChapterPayload/StoredCookie/SecureStorage/审计事件；WorkId 规则 `sha256(sourceKey\|sourceWorkId)`（纯 Kotlin SHA-256，三端向量一致） |
| SQLDelight schema | `:data:db` | 架构 5.2 的 11 表（`.sq` 一表一文件）+ `migrations/1.sqm`（v1→v2 加 `source.settings_json`）；`RollbackScripts` 提供 v2→v1 有损回滚（只丢 settings_json） |
| 统一出口 | `:data:network` | `SourceHttpClient`（Ktor）：首跳+逐跳白名单复核、https→http 降级拒绝、最多 5 跳、Cookie 注入、`cf_clearance` 配对 UA 覆盖、Cloudflare 分类、审计只记域名+状态 |
| 仓库 | `:data:repository` | `ShelfRepository`（SQLDelight Flow 为 source of truth，observe 永不触发网络）+ `ProgressRepository`；映射在 `:data` 边界收口 |
| 源管理与安装 | `:source:registry` | `SourceInstaller.plan/confirm`（头解析→策略校验→sha256→ed25519→静态扫描→版本比较→显式确认→落库）+ `SourceRegistry`（LRU/禁用/回滚） |

## 2. 模块放置说明（偏离架构图的记录，与 M1 同例）

- `:core:model` 不依赖 `:source:contract`（保持 core 独立，依赖规则见架构 3.1）；
  契约类型（Work/ContentKind）与持久化类型的映射收口在 `:data:repository`。
- 单一消费者阶段不预拆 `:api`（接口与实现同模块，实现类 internal）。
- `:source:registry` 依赖 `:data:db`（源记录落库）但不依赖 `:data:network`
  （脚本字节由调用方传入，本模块不碰网络与文件，无平台依赖）。
- 新增 `expect/actual`：无（ADR-006）。驱动按 sourceSet 分置；
  `SourceRegistry` 的 LRU 为手写（`LinkedHashMap.removeEldestEntry` 在 Native 不存在，
  iOS 编译失败倒逼，见构建记录）。

## 3. 安全/隐私自查（security-audit + privacy-audit）

- 仅 https（UrlPolicy 强制）；无自定义 TrustManager；重定向逐跳复核。
- `cf_clearance` 与 UA 绑定存储（红线）；审计日志不记 Cookie 与正文。
- 敏感值（Cookie value、token）走 `SecureStorage` 接口（M2 以内存实现跑通语义，
  平台加密实现随 M6 落地；`cookie` 表存路由元数据，生产切换 SecureStorage）。
- 源安装必须用户显式确认；静态扫描高风险项强制二次确认；旧仓库无签名标记未验证。
- 阅读记录仅本地（`synced` 预留未来同步，本期无上传）；支持按 workId 删除。

## 4. 密码学选型

- SHA-256（ID/哈希）：纯 Kotlin（core.model，已验三端向量）。
- SHA-512：korlibs-crypto 6.0.0（与 M0 已验证的 korlibs-encoding 同族同版本；
  手写实现因常量表缺 4 项已删，教训见构建记录）。
- Ed25519：纯 Kotlin（commonMain，无第三方签名库）——ref10 式域运算 +
  朴素 bignum 标量域 + double-and-add ladder；有限域/群/标量/编解码/曲线方程
  逐层自检（`consistencyCheck` 常驻）+ openssl 双向交叉验证
  （自实现签名与 openssl 输出逐字节一致，见 `SignatureTest.opensslInterop`）。
- 被签名消息（冻结语义）：`"$key|$version|$sha256".toUtf8Bytes()`。

## 5. Gate 判定

- 断网书架可用：`MigrationAndOfflineTest.offlineShelfAvailable` +
  `ShelfRepositoryTest.observeEmitsCachedWithoutNetwork`（desktop 真实 SQLDelight + Flow）。
- 迁移测试通过（含回滚）：`migrateV1ToV2PreservesData` + `rollbackV2ToV1PreservesCore`
  （SQLDelight `verifyMigrations` 因无 v1 快照暂关，v2 快照提交后重开；以手工测试为准）。
- 签名/权限校验生效：`SignatureTest`（7）+ `InstallerTest`（5，sha 不符/坏签名拒绝）+
  `RegistryLifecycleTest`（安装→更新→禁用→回滚）+ `UrlPolicyTest`（5，白名单/重定向/降级）。
- 三端：desktopTest / testAndroidHostTest / iosSimulatorArm64Test 全绿；
  `:source:registry` iOS framework 可链接（含 SQLDelight + korlibs 原生链）。
