package app.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.db.ReaderDatabase
import data.network.CookieStore
import data.network.PersistedCookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BE-B1 真实 DB 往返：装配层适配器 + CookieStore（假加密器只验证搬运语义，
 * 平台 AES-GCM 实现由各自 sourceSet 编译与运行覆盖）。
 */
class DbCookiePersistenceTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    private class MarkerCipher : core.model.SecretCipher {
        override fun encrypt(plain: String): String = "enc:$plain"
        override fun decrypt(payload: String): String? = payload.removePrefix("enc:")
            .takeIf { payload.startsWith("enc:") }
    }

    private fun record(domain: String, name: String, enc: String, ua: String?) = PersistedCookie(
        domain = domain,
        name = name,
        path = "/",
        valueEnc = enc,
        expiresAtEpochMs = null,
        pairedUserAgent = ua,
        source = "WEBVIEW",
        invalidReason = null,
        obtainedAtEpochMs = 1_760_000_000_000L,
        httpOnly = true,
        secure = true,
    )

    @Test
    fun upsertLoadAndDeleteHostRoundTrip() {
        val persistence = DbCookiePersistence(openDb())
        persistence.upsert(record(".example.net", "cf_clearance", "enc:v1", "UA-1"))
        persistence.upsert(record("other.example", "session", "enc:v2", null))

        val all = persistence.loadAll()
        assertEquals(2, all.size)
        val clearance = all.single { it.name == "cf_clearance" }
        assertEquals("UA-1", clearance.pairedUserAgent)
        assertEquals("WEBVIEW", clearance.source)
        assertTrue(clearance.httpOnly && clearance.secure)
        assertTrue(all.all { it.valueEnc.startsWith("enc:") })

        persistence.deleteHost("example.net")
        assertEquals(1, persistence.loadAll().size)
        assertEquals("other.example", persistence.loadAll().single().domain)

        persistence.clear()
        assertTrue(persistence.loadAll().isEmpty())
    }

    @Test
    fun cookieStoreSurvivesDatabaseReopen() {
        val db = openDb()
        val cipher = MarkerCipher()
        CookieStore(DbCookiePersistence(db), cipher).put(
            data.network.seedCookie(
                "example.net",
                "cf_clearance",
                "clearance-value",
                1_760_000_000_000L,
                "UA-PAIRED",
            ),
        )

        // 同一个 DB 句柄模拟重启：新建 CookieStore 只从库里恢复，不依赖进程内内存。
        val restored = CookieStore(DbCookiePersistence(db), cipher)
        assertEquals(
            "cf_clearance=clearance-value",
            restored.headerFor("https://example.net/a", 1_760_000_000_001L),
        )
        assertEquals("UA-PAIRED", restored.pairedUserAgentFor("https://example.net/a", 1_760_000_000_001L))
    }
}
