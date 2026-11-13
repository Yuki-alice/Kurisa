package app.shared

import data.db.ReaderDatabase
import data.network.CookiePersistence
import data.network.PersistedCookie

/**
 * `cookie` 表的持久化适配（BE-B1）。
 *
 * 装配层实现而非放进 :data:network，避免数据层反向依赖 :data:db。
 * 这里只搬运 [PersistedCookie.valueEnc] 密文，不接触 Cookie 明文。
 */
class DbCookiePersistence(private val db: ReaderDatabase) : CookiePersistence {

    override fun loadAll(): List<PersistedCookie> =
        db.cookieQueries.selectAllCookies().executeAsList().map { row ->
            PersistedCookie(
                domain = row.domain,
                name = row.name,
                path = row.path,
                valueEnc = row.value_enc,
                expiresAtEpochMs = row.expires_at,
                pairedUserAgent = row.paired_user_agent,
                source = row.source,
                invalidReason = row.invalid_reason,
                obtainedAtEpochMs = row.obtained_at,
                httpOnly = row.http_only != 0L,
                secure = row.secure != 0L,
            )
        }

    override fun upsert(record: PersistedCookie) {
        db.cookieQueries.upsertCookie(
            record.domain,
            record.name,
            record.path,
            record.valueEnc,
            record.expiresAtEpochMs,
            record.pairedUserAgent,
            record.source,
            record.invalidReason,
            record.obtainedAtEpochMs,
            if (record.httpOnly) 1L else 0L,
            if (record.secure) 1L else 0L,
        )
    }

    override fun deleteHost(host: String) {
        db.cookieQueries.deleteCookiesOfHost(host)
    }

    override fun clear() {
        db.cookieQueries.clearCookies()
    }
}
