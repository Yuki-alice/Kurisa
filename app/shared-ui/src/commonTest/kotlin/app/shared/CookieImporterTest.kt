package app.shared

import data.network.CookieStore
import source.bridge.NoopHostUi
import source.hostapi.HostLoginResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BE-C3：用户主动导入与 HostUi 兜底共用同一套白名单 / UA 配对规则。 */
class CookieImporterTest {

    private val domains = setOf("example.net")
    private val now = 1_760_000_000_000L
    private val ua = "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0"

    private fun importer(store: CookieStore) = CookieImporter(store, { domains }, { now })

    @Test
    fun importsIntoCookieStoreWithPairedUserAgent() {
        val store = CookieStore()
        val result = importer(store).import(
            "example.net",
            CookiePaste("cf_clearance=c1; session=s1", ua),
        )

        assertEquals(HostLoginResult.Imported(2, "cookie-header"), result)
        assertTrue(store.headerFor("https://example.net/a", now + 1)?.contains("cf_clearance=c1") == true)
        assertEquals(ua, store.pairedUserAgentFor("https://example.net/a", now + 1))
    }

    @Test
    fun acceptsFullUrlAndDottedDomain() {
        val store = CookieStore()
        val result = importer(store).import(
            "https://.example.net/some/path?q=1",
            CookiePaste("k=v", ua),
        )
        assertEquals(HostLoginResult.Imported(1, "single-pair"), result)
        assertEquals("example.net", store.all().single().domain)
    }

    @Test
    fun outOfWhitelistDomainWritesNothing() {
        val store = CookieStore()
        assertEquals(
            HostLoginResult.Cancelled("domain-not-allowed"),
            importer(store).import("evil.example", CookiePaste("k=v", ua)),
        )
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun clearanceWithoutUserAgentIsRejected() {
        val store = CookieStore()
        assertEquals(
            HostLoginResult.Cancelled("paired-user-agent-required"),
            importer(store).import("example.net", CookiePaste("cf_clearance=c1", null)),
        )
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun invalidPasteWritesNothing() {
        val store = CookieStore()
        assertEquals(
            HostLoginResult.Cancelled("no-valid-cookie"),
            importer(store).import("example.net", CookiePaste("   ", ua)),
        )
        assertEquals(
            HostLoginResult.Cancelled("domain-not-allowed"),
            importer(store).import("", CookiePaste("k=v", ua)),
        )
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun hostUiFallbackAndDirectImportAgree() = kotlinx.coroutines.runBlocking {
        val direct = CookieStore()
        val viaHostUi = CookieStore()
        val paste = CookiePaste("cf_clearance=c1; session=s1", ua)
        val hostUi = CookieImportHostUi(
            cookies = viaHostUi,
            allowedDomains = domains,
            requestPaste = { paste },
            nowMs = { now },
        )

        assertEquals(
            importer(direct).import("example.net", paste),
            hostUi.requestLogin("https://example.net/login"),
        )
        assertEquals(direct.all(), viaHostUi.all())
        assertEquals(HostLoginResult.Unsupported, NoopHostUi().requestLogin("https://example.net/login"))
    }
}
