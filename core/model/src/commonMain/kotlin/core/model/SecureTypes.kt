package core.model

import kotlinx.serialization.Serializable

/** Cookie 来源（对齐 M0 CookieStore 语义，存储时必须成对记 UA）。 */
enum class CookieSource { WEBVIEW, MANUAL_IMPORT, NETWORK, SEED }

/**
 * 已存储 Cookie。
 *
 * 安全红线（security-audit MASVS-STORAGE）：
 * - `value` 为敏感数据，生产环境必须经 [SecureStorage] 加密存放，不进明文 DB；
 * - `pairedUserAgent` 与 `cf_clearance` 绑定存储（架构红线），不得改为裸 Cookie 复用；
 * - 审计日志禁止记录 value。
 */
@Serializable
data class StoredCookie(
    val domain: String,
    val name: String,
    val value: String,
    val path: String = "/",
    val expiresAtEpochMs: Long? = null,
    val obtainedAtEpochMs: Long = 0L,
    val source: CookieSource = CookieSource.SEED,
    val pairedUserAgent: String? = null,
    val invalidReason: String? = null,
    val httpOnly: Boolean = false,
    val secure: Boolean = false,
)

/**
 * 安全存储（敏感数据出口）。
 * Android: Keystore； iOS: Keychain； Desktop: 加密文件（M6 落地）。
 * M2 以内存实现跑通语义，接口形状冻结。
 */
interface SecureStorage {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun delete(key: String)
    suspend fun clear()
}

/** 内存实现（仅测试与 M2 语义跑通，生产必须替换为平台加密实现）。 */
class InMemorySecureStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun set(key: String, value: String) { map[key] = value }
    override suspend fun delete(key: String) { map.remove(key) }
    override suspend fun clear() { map.clear() }
}

/**
 * 脱敏审计事件（R3/架构 4.8）：记录域名统计与错误，不记录 Cookie 与正文。
 * privacy-audit：阅读记录仅本地可用，不上传；删除支持按 workId 清除。
 */
@Serializable
data class SourceAuditEvent(
    val sourceKey: String,
    val host: String,
    val status: Int,
    val atEpochMs: Long,
    val note: String = "",
)

/**
 * 同步敏感值加解密出口（BE-C2，平台注入；ADR-006 不新增 expect/actual）。
 *
 * 与 [SecureStorage] 的区别：[SecureStorage] 是冻结的 suspend 形状，供后续 token / 账号态使用；
 * 本接口是同步的，供 Cookie 仓库在既有同步 API 内把 value 加密后落盘。
 * 实现约定（MASVS-CRYPTO）：AES-256-GCM、每次加密独立随机 IV、密钥不落源码、
 * 密钥由平台密钥库或用户私有目录的受保护文件持有；密文自包含 IV 与认证标签。
 *
 * 平台实现：Android Keystore / 桌面加密文件；iOS Keychain 随 iOS 平台壳落地，
 * 未注入实现的宿主应保持纯内存语义，不得退化为明文落盘。
 */
interface SecretCipher {
    /** 返回自包含密文（随机 IV + 密文 + 认证标签），绝不返回明文。 */
    fun encrypt(plain: String): String

    /** 密文损坏或密钥轮换导致认证失败时返回 null，由调用方按失效丢弃。 */
    fun decrypt(payload: String): String?
}
