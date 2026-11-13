package app.shared

import data.network.CookieStore
import kotlinx.coroutines.runBlocking
import source.bridge.NoopHostUi
import source.hostapi.HostLoginResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** BE-C3：桌面 Cookie 导入兜底的域名白名单、UA 配对与 fail-closed 行为。 */
class CookieImportHostUiTest {

    private val domains = setOf("example.net")
    private val now = 1_760_000_000_000L
    private val ua = "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0"

    private fun host(
        store: CookieStore,
        paste: suspend (String) -> CookiePaste? = { CookiePaste("cf_clearance=c1; session=s1", ua) },
        openExternal: (String) -> Unit = {},
    ) = CookieImportHostUi(
        cookies = store,
        allowedDomains = domains,
        requestPaste = paste,
        nowMs = { now },
        openExternal = openExternal,
    )

    @Test
    fun defaultHostUiRejectsLoginByContract() = runBlocking {
        assertEquals(
            HostLoginResult.Unsupported,
            NoopHostUi().requestLogin("https://example.net/login"),
        )
    }

    @Test
    fun importsCookiesWithPairedUserAgent() = runBlocking {
        val store = CookieStore()
        val result = host(store).requestLogin("https://example.net/login")

        assertTrue(result is HostLoginResult.Imported)
        assertEquals(2, result.count)
        assertEquals("cookie-header", result.format)
        assertTrue(store.headerFor("https://example.net/a", now + 1)?.contains("cf_clearance=c1") == true)
        assertEquals(ua, store.pairedUserAgentFor("https://example.net/a", now + 1))
    }

    @Test
    fun clearanceWithoutUserAgentIsRejectedWithoutWriting() = runBlocking {
        val store = CookieStore()
        val result = host(store, paste = { CookiePaste("cf_clearance=c1", null) })
            .requestLogin("https://example.net/login")

        assertEquals(HostLoginResult.Cancelled("paired-user-agent-required"), result)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun outOfWhitelistDomainNeverPrompts() = runBlocking {
        var prompted = false
        val store = CookieStore()
        val ui = host(store, paste = {
            prompted = true
            CookiePaste("k=v", ua)
        })

        val result = ui.requestLogin("https://evil.example/login")
        assertEquals(HostLoginResult.Cancelled("domain-not-allowed"), result)
        assertTrue(!prompted)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun cleartextHttpIsRejected() = runBlocking {
        val store = CookieStore()
        assertEquals(
            HostLoginResult.Cancelled("domain-not-allowed"),
            host(store).requestLogin("http://example.net/login"),
        )
    }

    @Test
    fun invalidOrCancelledPasteWritesNothing() = runBlocking {
        val store = CookieStore()
        assertEquals(
            HostLoginResult.Cancelled("user-cancelled"),
            host(store, paste = { _ -> null }).requestLogin("https://example.net/login"),
        )
        assertEquals(
            HostLoginResult.Cancelled("no-valid-cookie"),
            host(store, paste = { CookiePaste("   ", ua) }).requestLogin("https://example.net/login"),
        )
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun launchUrlRespectsWhitelist() {
        val opened = mutableListOf<String>()
        val ui = CookieImportHostUi(
            cookies = CookieStore(),
            allowedDomains = domains,
            requestPaste = { _ -> null },
            nowMs = { now },
            openExternal = { url -> opened += url },
        )
        runBlocking {
            ui.launchUrl("https://example.net/x")
            assertEquals(listOf("https://example.net/x"), opened)
            assertFailsWith<UnsupportedOperationException> {
                ui.launchUrl("https://evil.example/x")
            }
        }
    }
}
