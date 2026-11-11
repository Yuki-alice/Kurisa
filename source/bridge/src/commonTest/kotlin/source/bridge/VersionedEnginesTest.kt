package source.bridge

import source.contract.ContentKind
import source.contract.SourceErrorKind
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
import kotlin.test.assertTrue

/**
 * E2a 路由单测（纯 Kotlin，三端可跑，不做 SKIP 打折）。
 * 断言：2/3 走垫片；4 无原生时明确拒绝；未知版本求值前拒绝且不触碰垫片。
 */
class VersionedEnginesTest {

    private class StubEngine(val tag: String) : SourcePluginEngine {
        override suspend fun load(script: String) {}
        override fun callSync(method: String, payload: String): String = ""
        override suspend fun callAsync(method: String, payload: String, timeoutMs: Long): String = ""
        override fun close() {}
        override suspend fun describe(): String = "{}"
        override fun applySettings(settingsJson: String) {}
        override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int): String = "{}"
        override suspend fun defaultSearchOptions(): String = "[]"
        override suspend fun loadWorkDetails(workId: String, kind: ContentKind): String = "{}"
        override suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind): String = "{}"
        override suspend fun invoke(path: String, argsJson: String): String = "{}"
    }

    private class StubHost : HostApi {
        override val network = object : HostNetwork {
            override suspend fun get(url: String, headers: Map<String, String>) = HostResponse(0, "")
            override suspend fun post(url: String, headers: Map<String, String>, data: String?) = HostResponse(0, "")
            override suspend fun getCookies(url: String): List<HostCookie> = emptyList()
            override suspend fun setCookies(url: String, list: List<HostCookie>) = true
            override suspend fun deleteCookies(url: String) = true
        }
        override val html = object : HostHtml {
            override fun parse(html: String) = 1
            override fun select(doc: Int, css: String): List<Map<String, String>> = emptyList()
            override fun dispose(doc: Int) {}
        }
        override val convert = object : HostConvert {
            override fun md5(text: String) = ""
            override fun sha256(text: String) = ""
            override fun encodeBase64(bytes: ByteArray) = ""
            override fun decodeBase64(text: String) = ByteArray(0)
        }
        override val ui = object : HostUi {
            override suspend fun showMessage(text: String) {}
            override suspend fun launchUrl(url: String) {}
        }
        override val utils = object : HostUtils {
            override fun createUuid() = "00000000-0000-4000-8000-000000000000"
            override fun log(text: String) {}
        }
        override val storage = object : HostStorage {
            override fun get(sourceKey: String, key: String): String? = null
            override fun set(sourceKey: String, key: String, value: String) {}
            override fun delete(sourceKey: String, key: String) {}
        }
        override val novel = object : HostNovel {
            override fun stripHtml(html: String) = ""
        }
    }

    private fun spec(apiVersion: Int) = PluginRuntimeSpec(
        key = "k", script = "class T extends ComicSource {}",
        domains = setOf("example.com"), apiVersion = apiVersion,
    )

    private fun legacyFactory(seen: MutableList<Int>) = object : PluginEngineFactory {
        override fun create(spec: PluginRuntimeSpec, host: HostApi): SourcePluginEngine {
            seen += spec.apiVersion
            return StubEngine("legacy")
        }
    }

    @Test
    fun routesV2AndV3ToLegacy() {
        val seen = mutableListOf<Int>()
        val f = VersionedPluginEngineFactory(legacyFactory(seen))
        val host = StubHost()
        for (v in listOf(2, 3)) {
            val engine = f.create(spec(v), host) as StubEngine
            assertEquals("legacy", engine.tag)
        }
        assertEquals(listOf(2, 3), seen)
    }

    @Test
    fun rejectsV4WhenNativeAbsent() {
        val f = VersionedPluginEngineFactory(legacyFactory(mutableListOf()))
        val e = assertFailsWith<SourcePluginException> { f.create(spec(4), StubHost()) }
        assertEquals(SourceErrorKind.CONTRACT, e.kind)
        assertTrue(e.message!!.contains("E2b"))
    }

    @Test
    fun rejectsUnknownVersionWithoutTouchingLegacy() {
        val seen = mutableListOf<Int>()
        val f = VersionedPluginEngineFactory(legacyFactory(seen))
        for (v in listOf(1, 99)) {
            val e = assertFailsWith<SourcePluginException> { f.create(spec(v), StubHost()) }
            assertEquals(SourceErrorKind.CONTRACT, e.kind)
            assertTrue(e.message!!.contains("apiVersion=$v"))
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun routesV4ToNativeWhenPresent() {
        val native = object : PluginEngineFactory {
            override fun create(spec: PluginRuntimeSpec, host: HostApi): SourcePluginEngine = StubEngine("native")
        }
        val f = VersionedPluginEngineFactory(legacyFactory(mutableListOf()), native)
        val engine = f.create(spec(4), StubHost()) as StubEngine
        assertEquals("native", engine.tag)
    }
}