package data.network

import core.model.SecretCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BE-B1：Cookie 持久化只落密文、重启可恢复、失效与删除同步到盘。
 * 这里用可逆的假加密器验证「调用方语义」；真实 AES-GCM 强度由平台实现与
 * `DbCookiePersistenceTest` 的真实 DB 往返覆盖。
 */
class CookieStorePersistenceTest {

    private class MemoryPersistence : CookiePersistence {
        val rows = linkedMapOf<Triple<String, String, String>, PersistedCookie>()

        override fun loadAll(): List<PersistedCookie> = rows.values.toList()

        override fun upsert(record: PersistedCookie) {
            rows[Triple(record.domain, record.name, record.path)] = record
        }

        override fun delete(domain: String, name: String, path: String) {
            rows.remove(Triple(domain, name, path))
        }

        override fun deleteHost(host: String) {
            rows.entries.removeAll { (_, row) ->
                row.domain.removePrefix(".").equals(host.removePrefix("."), true)
            }
        }

        override fun clear() = rows.clear()
    }

    /** 可逆但绝不保留明文形状，便于断言 DB 里没有 value 原文。 */
    private class ReversibleCipher(var broken: Boolean = false) : SecretCipher {
        override fun encrypt(plain: String): String = "enc:" + plain.reversed()

        override fun decrypt(payload: String): String? =
            if (broken || !payload.startsWith("enc:")) null
            else payload.removePrefix("enc:").reversed()
    }

    private val now = 1_760_000_000_000L
    private val ua = "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0"

    @Test
    fun valueNeverPersistedInPlaintextAndPairedUaSurvives() {
        val disk = MemoryPersistence()
        val store = CookieStore(disk, ReversibleCipher())
        store.put(seedCookie("example.net", "cf_clearance", "SECRET-CLEARANCE", now, ua))

        val row = disk.rows.values.single()
        assertFalse(row.valueEnc.contains("SECRET-CLEARANCE"))
        assertEquals("enc:" + "SECRET-CLEARANCE".reversed(), row.valueEnc)
        assertEquals(ua, row.pairedUserAgent)
        assertEquals("SEED", row.source)
        assertEquals("/", row.path)
    }

    @Test
    fun restartRestoresCookiesAndPairedUserAgent() {
        val disk = MemoryPersistence()
        val cipher = ReversibleCipher()
        CookieStore(disk, cipher).apply {
            put(seedCookie("example.net", "cf_clearance", "clearance-1", now, ua))
            put(seedCookie("example.net", "session", "sess-1", now))
        }

        val restored = CookieStore(disk, cipher)
        val header = requireNotNull(restored.headerFor("https://example.net/a", now))
        assertTrue(header.contains("cf_clearance=clearance-1"))
        assertTrue(header.contains("session=sess-1"))
        assertEquals(ua, restored.pairedUserAgentFor("https://example.net/a", now))
    }

    @Test
    fun persistenceWithoutCipherFailsClosed() {
        assertFailsWith<IllegalArgumentException> { CookieStore(MemoryPersistence()) }
    }

    @Test
    fun undecryptableRowsAreDroppedNotUsedAsPlaintext() {
        val disk = MemoryPersistence()
        val cipher = ReversibleCipher()
        CookieStore(disk, cipher).put(seedCookie("example.net", "cf_clearance", "v1", now, ua))

        cipher.broken = true
        val restored = CookieStore(disk, cipher)
        assertTrue(restored.all().isEmpty())
        assertNull(restored.headerFor("https://example.net/a", now))
    }

    @Test
    fun markInvalidIsPersistedAcrossRestart() {
        val disk = MemoryPersistence()
        val cipher = ReversibleCipher()
        CookieStore(disk, cipher).put(seedCookie("example.net", "cf_clearance", "v1", now, ua))

        val reopened = CookieStore(disk, cipher)
        assertEquals(1, reopened.markInvalid("example.net", "re-challenged", CookieStore.CF_COOKIES))
        val third = CookieStore(disk, cipher)
        assertNull(third.headerFor("https://example.net/a", now))
        assertEquals("re-challenged", third.all().single().invalidReason)
    }

    @Test
    fun removeAndClearPropagateToDisk() {
        val disk = MemoryPersistence()
        val cipher = ReversibleCipher()
        val store = CookieStore(disk, cipher)
        store.put(seedCookie(".example.net", "wide", "w1", now))
        store.put(seedCookie("other.example", "k", "v", now))

        assertEquals(1, store.remove("example.net"))
        assertEquals(1, disk.rows.size)
        assertTrue(CookieStore(disk, cipher).all().single().domain == "other.example")

        store.clear()
        assertTrue(disk.rows.isEmpty())
        assertTrue(CookieStore(disk, cipher).all().isEmpty())
    }

    @Test
    fun putOverwritesSameDomainNamePathKey() {
        val disk = MemoryPersistence()
        val cipher = ReversibleCipher()
        val store = CookieStore(disk, cipher)
        store.put(seedCookie("example.net", "session", "old", now))
        store.put(seedCookie("example.net", "session", "new", now))

        assertEquals(1, disk.rows.size)
        assertEquals("new", CookieStore(disk, cipher).headerFor("https://example.net/", now)
            ?.removePrefix("session="))
    }

    @Test
    fun pureMemoryModeStillAvailable() {
        val store = CookieStore()
        store.put(seedCookie("example.net", "k", "v", now))
        assertTrue(store.headerFor("https://example.net/", now)?.contains("k=v") == true)
        store.clear()
        assertNull(store.headerFor("https://example.net/", now))
    }

    @Test
    fun purgeExpiredRemovesFromMemoryAndDisk() {
        val disk = MemoryPersistence()
        val cipher = ReversibleCipher()
        val store = CookieStore(disk, cipher)
        store.put(seedCookie("example.net", "expired", "e", now).copy(expiresAtEpochMs = now - 1))
        store.put(seedCookie("example.net", "invalid", "i", now).copy(invalidReason = "re-challenged"))
        store.put(seedCookie("example.net", "fresh", "f", now).copy(expiresAtEpochMs = now + 10_000))
        assertEquals(3, disk.rows.size)

        assertEquals(2, store.purgeExpired(now))
        assertEquals(1, disk.rows.size)
        assertEquals("fresh", store.all().single().name)

        // 重启后只剩有效项，过期 / 失效记录已从表中精确删除。
        val restored = CookieStore(disk, cipher)
        assertEquals(1, restored.all().size)
        assertEquals("fresh", restored.all().single().name)
        assertEquals(0, restored.purgeExpired(now))
    }
}
