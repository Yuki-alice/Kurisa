package data.network

import core.model.CookieSource
import core.model.StoredCookie

/**
 * Cookie 落盘记录（BE-B1）。
 *
 * 安全红线（MASVS-STORAGE）：[valueEnc] 只存 [core.model.SecretCipher] 的输出，
 * 任何情况下都不得把 Cookie 明文写进数据库；`pairedUserAgent` 不敏感，可明文保存，
 * 但必须与 [valueEnc] 同行，保证 `cf_clearance` 与 UA 配对不因重启而拆散。
 */
data class PersistedCookie(
    val domain: String,
    val name: String,
    val path: String,
    val valueEnc: String,
    val expiresAtEpochMs: Long?,
    val pairedUserAgent: String?,
    val source: String,
    val invalidReason: String?,
    val obtainedAtEpochMs: Long,
    val httpOnly: Boolean,
    val secure: Boolean,
)

/**
 * Cookie 持久化出口。由装配层用 `cookie` 表实现（:app:shared-ui），
 * :data:network 不反向依赖 :data:db；同步接口与 CookieStore 的冻结 API 对齐。
 */
interface CookiePersistence {
    fun loadAll(): List<PersistedCookie>
    fun upsert(record: PersistedCookie)
    fun deleteHost(host: String)
    fun clear()
}

internal fun StoredCookie.toPersisted(valueEnc: String): PersistedCookie = PersistedCookie(
    domain = domain,
    name = name,
    path = path,
    valueEnc = valueEnc,
    expiresAtEpochMs = expiresAtEpochMs,
    pairedUserAgent = pairedUserAgent,
    source = source.name,
    invalidReason = invalidReason,
    obtainedAtEpochMs = obtainedAtEpochMs,
    httpOnly = httpOnly,
    secure = secure,
)

internal fun PersistedCookie.toStored(value: String): StoredCookie = StoredCookie(
    domain = domain,
    name = name,
    value = value,
    path = path,
    expiresAtEpochMs = expiresAtEpochMs,
    obtainedAtEpochMs = obtainedAtEpochMs,
    source = runCatching { CookieSource.valueOf(source) }.getOrDefault(CookieSource.SEED),
    pairedUserAgent = pairedUserAgent,
    invalidReason = invalidReason,
    httpOnly = httpOnly,
    secure = secure,
)
