package source.bridge

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import source.contract.ContentKind
import source.contract.SourceCapability
import source.contract.SourceDescriptor
import source.contract.SourceErrorKind
import source.contract.SourceSetting
import source.contract.SettingType
import source.hostapi.HostApi
import source.hostapi.HostConvert
import source.hostapi.HostCookie
import source.hostapi.HostHtml
import source.hostapi.HostNetwork
import source.hostapi.HostNovel
import source.hostapi.HostResponse
import source.hostapi.HostStorage
import source.hostapi.HostUi
import source.hostapi.HostUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 装配层纯逻辑测试（不需要 quickjs native，三端都真正断言，不做 SKIP 打折）。
 * 覆盖：能力门、错误分类/重放、设置回退链、图片配置归一化、LRU 生命周期、可信版本覆盖。
 */
class SourceRuntimeUnitTest {

    private class FakeEngine(
        var describeJson: String = "{}",
        var invokeBody: suspend (String, String) -> String = { _, _ -> "{}" },
    ) : SourcePluginEngine {
        val applied = mutableListOf<String>()
        var closed = false
            private set
        var loaded = 0
            private set

        override suspend fun load(script: String) { loaded++ }
        override fun callSync(method: String, payload: String): String = ""
        override suspend fun callAsync(method: String, payload: String, timeoutMs: Long): String = ""
        override fun close() { closed = true }
        override suspend fun describe(): String = describeJson
        override fun applySettings(settingsJson: String) { applied += settingsJson }
        override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int): String = "{}"
        override suspend fun defaultSearchOptions(): String = "[]"
        override suspend fun loadWorkDetails(workId: String, kind: ContentKind): String = "{}"
        override suspend fun loadChapterPayload(
            workId: String,
            chapterId: String,
            kind: ContentKind,
        ): String = "{}"
        override suspend fun invoke(path: String, argsJson: String): String = invokeBody(path, argsJson)
    }

    private fun descriptor(
        capabilities: Set<SourceCapability> = setOf(SourceCapability.SEARCH, SourceCapability.COMIC),
        settings: List<SourceSetting> = emptyList(),
        hasImageLoad: Boolean = false,
    ) = SourceDescriptor(
        key = "com.example.test",
        name = "T",
        version = "1.0.0",
        apiVersion = 3,
        kinds = setOf(ContentKind.MANGA),
        domains = listOf("example.com"),
        capabilities = capabilities,
        settings = settings,
        hasImageLoad = hasImageLoad,
    )

    @Test
    fun capabilityGateBlocksUndeclaredAbility() = runTest {
        val runtime = SourceRuntime(
            descriptor(capabilities = setOf(SourceCapability.SEARCH)), FakeEngine(),
        )
        val e = assertFailsWith<SourcePluginException> {
            runtime.call("comic.loadEp", "[\"w\",\"c\"]")
        }
        assertEquals(SourceErrorKind.CONTRACT, e.kind)
        assertTrue(e.message!!.contains("能力未声明"))
        val ok = assertFailsWith<SourcePluginException> {
            runtime.searchNext("q", "[]", null)
        }
        assertEquals(SourceErrorKind.CONTRACT, ok.kind)
    }

    @Test
    fun loginExpiredReplaysExactlyOnce() = runTest {
        var calls = 0
        var relogins = 0
        val engine = FakeEngine { _, _ ->
            calls++
            if (calls == 1) throw SourceBridgeException("JSException: Login expired") else "{\"ok\":true}"
        }
        val errors = mutableListOf<SourcePluginException>()
        val runtime = SourceRuntime(
            descriptor(), engine,
            onRelogin = { relogins++; true },
            onError = { errors += it },
        )
        assertEquals("{\"ok\":true}", runtime.call("search.load", "[]"))
        assertEquals(1, relogins)
        assertEquals(2, calls)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun loginExpiredFailureIsNotWrappedOrRetriedTwice() = runTest {
        var calls = 0
        var relogins = 0
        val engine = FakeEngine { _, _ ->
            calls++
            throw SourceBridgeException("Login expired and re-login failed")
        }
        val runtime = SourceRuntime(descriptor(), engine, onRelogin = { relogins++; true })
        val e = assertFailsWith<SourcePluginException> { runtime.call("search.load", "[]") }
        assertEquals(SourceErrorKind.LOGIN_EXPIRED, e.kind)
        assertEquals(1, relogins)
        assertEquals(2, calls) // 原调用 + 一次重放，不再重试
    }

    @Test
    fun otherErrorsAreClassifiedWithoutReplay() = runTest {
        var relogins = 0
        listOf(
            "Rate limited by site" to SourceErrorKind.RATE_LIMITED,
            "NotFound" to SourceErrorKind.NOT_FOUND,
            "domain-blocked:https://evil.com" to SourceErrorKind.PERMISSION,
            "源调用超时（800ms）" to SourceErrorKind.TIMEOUT,
            "TypeError: undefined" to SourceErrorKind.EXECUTION,
        ).forEach { (message, kind) ->
            val engine = FakeEngine { _, _ -> throw SourceBridgeException(message) }
            val runtime = SourceRuntime(descriptor(), engine, onRelogin = { relogins++; true })
            assertEquals(kind, assertFailsWith<SourcePluginException> {
                runtime.call("search.load", "[]")
            }.kind)
        }
        assertEquals(0, relogins)
    }

    @Test
    fun hostMissingIsNotWrappedAsSourceError() = runTest {
        val engine = FakeEngine { _, _ -> throw HostMissingException("no native") }
        val runtime = SourceRuntime(descriptor(), engine)
        assertFailsWith<HostMissingException> { runtime.call("search.load", "[]") }
    }

    @Test
    fun settingsFallbackChainIsApplied() {
        val engine = FakeEngine()
        val runtime = SourceRuntime(
            descriptor(
                settings = listOf(
                    SourceSetting("quality", "画质", SettingType.SELECT, default = "orig"),
                    SourceSetting("adult", "R18", SettingType.SWITCH, default = "false"),
                ),
            ),
            engine,
        )
        runtime.updateSettings("""{"quality":"low"}""")
        val applied = Json.parseToJsonElement(engine.applied.last()).jsonObject
        assertEquals("low", applied["quality"]?.jsonPrimitive?.contentOrNull)
        assertEquals("false", applied["adult"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun imageConfigIsSanitizedAndWired() = runTest {
        val engine = FakeEngine { _, _ ->
            """{"url":"https://cdn.example.com/1.jpg","method":"POST","headers":{"Cookie":"cf=x","User-Agent":"evil","X-Site":"ok"},"referer":"https://example.com/","maxRetries":99}"""
        }
        val runtime = SourceRuntime(descriptor(hasImageLoad = true), engine)
        val cfg = runtime.imageLoadConfig("https://example.com/1.jpg", "w", "c")!!
        assertEquals("https://cdn.example.com/1.jpg", cfg.url)
        assertEquals("POST", cfg.method)
        assertEquals(3, cfg.maxRetries)
        assertFalse(cfg.headers.keys.any { it.equals("cookie", true) || it.equals("user-agent", true) })
        assertEquals("ok", cfg.headers["X-Site"])
        assertEquals("https://example.com/", cfg.headers["Referer"])
        val request = runtime.imageRequest("https://example.com/1.jpg", "w", "c", setOf("cdn.example.com"))
        assertEquals("https://cdn.example.com/1.jpg", request.config?.url)
        assertFalse(request.config?.headers.orEmpty().keys.any { it.equals("cookie", true) })
    }

    @Test
    fun noImageLoadMeansNoConfigCall() = runTest {
        var invoked = false
        val engine = FakeEngine { _, _ -> invoked = true; "{}" }
        val runtime = SourceRuntime(descriptor(hasImageLoad = false), engine)
        assertNull(runtime.imageLoadConfig("u", "w", "c"))
        assertFalse(invoked)
    }

    @Test
    fun managerCachesInvalidatesAndRebuilds() = runTest {
        val created = mutableListOf<FakeEngine>()
        val manager = SourceRuntimeManager(
            engines = object : PluginEngineFactory {
                override fun create(spec: PluginRuntimeSpec, host: HostApi): SourcePluginEngine =
                    FakeEngine(describeJson = PROBE).also { created += it }
            },
            hosts = PluginHostProvider { StubHost() },
        )
        val spec = PluginRuntimeSpec(
            key = "com.example.test", script = "class T extends ComicSource {}",
            domains = setOf("example.com"), apiVersion = 3, appVersion = "1.6.0",
            capabilities = emptySet(),
        )
        val first = manager.acquire(spec)
        assertSame(first, manager.acquire(spec))
        assertEquals(1, created.size)
        assertEquals(1, created[0].loaded)

        val changed = spec.copy(settingsJson = """{"quality":"low"}""")
        val rebuilt = manager.acquire(changed)
        assertNotSame(first, rebuilt)
        assertTrue(created[0].closed)
        assertEquals(2, created.size)

        manager.invalidate(spec.key)
        assertTrue(created[1].closed)
        assertTrue(manager.cachedKeys().isEmpty())
        manager.close()
    }

    @Test
    fun managerTrustsInstallMetadataOverScriptClaims() = runTest {
        val manager = SourceRuntimeManager(
            engines = object : PluginEngineFactory {
                override fun create(spec: PluginRuntimeSpec, host: HostApi): SourcePluginEngine =
                    FakeEngine(describeJson = PROBE)
            },
            hosts = PluginHostProvider { StubHost() },
        )
        val spec = PluginRuntimeSpec(
            key = "trusted.key",
            script = "class T extends ComicSource {}",
            domains = setOf("trusted.example.com"),
            apiVersion = 2,
            appVersion = "1.6.0",
            capabilities = setOf(SourceCapability.SEARCH),
        )
        val d = manager.acquire(spec).descriptor
        assertEquals("trusted.key", d.key)
        assertEquals(listOf("trusted.example.com"), d.domains)
        assertEquals(2, d.apiVersion)
        // 脚本自报 apiVersion 3 + settings/explore，但安装期声明为 v2 且只放行 search。
        assertEquals(setOf(SourceCapability.SEARCH), d.capabilities)
        manager.close()
    }

    /** 装配层 fake HostApi：本测试只驱动引擎接口，不触碰网络/存储实现。 */
    private class StubHost : HostApi {
        override val network = object : HostNetwork {
            override suspend fun get(url: String, headers: Map<String, String>) = HostResponse(0, "")
            override suspend fun post(url: String, headers: Map<String, String>, data: String?) =
                HostResponse(0, "")
            override suspend fun getCookies(url: String): List<HostCookie> = emptyList()
            override suspend fun setCookies(url: String, list: List<HostCookie>) = true
            override suspend fun deleteCookies(url: String) = true
        }
        override val html = object : HostHtml {
            override fun parse(html: String) = 1
            override fun select(doc: Int, css: String): List<Map<String, String>> = emptyList()
            override fun dispose(doc: Int) = Unit
        }
        override val convert = object : HostConvert {
            override fun md5(text: String) = ""
            override fun sha256(text: String) = ""
            override fun encodeBase64(bytes: ByteArray) = ""
            override fun decodeBase64(text: String) = ByteArray(0)
        }
        override val ui = object : HostUi {
            override suspend fun showMessage(text: String) = Unit
            override suspend fun launchUrl(url: String) = Unit
        }
        override val utils = object : HostUtils {
            override fun createUuid() = "stub-uuid"
            override fun log(text: String) = Unit
        }
        override val storage = object : HostStorage {
            override fun get(sourceKey: String, key: String): String? = null
            override fun set(sourceKey: String, key: String, value: String) = Unit
            override fun delete(sourceKey: String, key: String) = Unit
        }
        override val novel = object : HostNovel {
            override fun stripHtml(html: String) = ""
        }
    }

    private companion object {
        const val PROBE = """
        {
          "key":"script.claims", "name":"T", "version":"1.0.0", "minAppVersion":"1.6.0",
          "apiVersion":3, "kinds":["manga"], "domains":["evil.example.com"],
          "capabilities":["search","settings","explore"],
          "settings":{"quality":{"title":"画质","type":"select","default":"orig"}},
          "translationLocales":["zh_CN"],
          "hasInit":false, "hasImageLoad":false, "hasSearchNext":false,
          "hasSearch":true, "hasComic":true, "hasExplore":true,
          "hasCategory":false, "hasAccount":false, "hasFavorites":false
        }
        """
    }
}
