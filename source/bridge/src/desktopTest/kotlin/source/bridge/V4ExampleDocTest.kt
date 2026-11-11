package source.bridge

import data.network.CookieStore
import data.network.SourceHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import source.contract.SourceCapability
import source.contract.SourceErrorKind
import source.hostapi.HostApi
import source.hostapi.HostConvert
import source.hostapi.HostHtml
import source.hostapi.HostNetwork
import source.hostapi.HostNovel
import source.hostapi.HostStorage
import source.hostapi.HostUi
import source.hostapi.HostUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 写源指南示例的真实加载测试（仅 desktopTest）。
 *
 * `docs/contract/v4-example.js` 是新人照抄起点；本测试保证它不是摆设：
 * 真机加载 → describe 探测 → 主链路调用，全走 MockEngine（无外部站点，C1）。
 * 文件缺失时 SKIP（文档与测试可独立演进，不互相锁死）。
 */
class V4ExampleDocTest {

    private fun locate(): java.io.File? {
        val override = System.getProperty("kurisa.docsDir")
        if (!override.isNullOrBlank()) {
            val f = java.io.File(override, "contract/v4-example.js")
            if (f.isFile) return f
        }
        // Gradle Test 工作目录 = 模块目录（source/bridge），上两级即仓库根。
        val candidates = listOf(
            java.io.File("../../docs/contract/v4-example.js"),
            java.io.File("../docs/contract/v4-example.js"),
            java.io.File("docs/contract/v4-example.js"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    @Test
    fun v4ExampleLoadsAndRunsMainFlow() = runTest {
        val file = locate()
        if (file == null) {
            println("SKIP（未找到 docs/contract/v4-example.js）")
            return@runTest
        }
        val script = file.readText()
        val cookies = CookieStore()
        val mock = MockEngine { request ->
            respond(
                content = ByteReadChannel("""<div class="w">T</div>"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val domains = setOf("cdn.example.com")
        val host = object : HostApi {
            override val network: HostNetwork = KtorHostNetwork(http, cookies, domains) { 0L }
            override val html: HostHtml = KsoupHostHtml()
            override val convert: HostConvert = CryptoHostConvert()
            override val ui: HostUi = NoopHostUi()
            override val utils: HostUtils = HostUtilsImpl()
            override val storage: HostStorage = InMemoryHostStorage()
            override val novel: HostNovel = NovelHost()
        }
        val engine = QuickJsEngine(
            sourceKey = "com.example.v4demo",
            host = host,
            cookies = cookies,
            dispatchers = DefaultSourceDispatchers(),
        )
        try {
            engine.load(script)
            val d = source.contract.SourceDescriptor.fromJson(engine.describe(), apiVersionOverride = 4)
            assertEquals("com.example.v4demo", d.key)
            assertTrue(d.hasNovelLoadInfo, d.toString())
            assertTrue(d.hasNovelLoadChapter, d.toString())
            assertTrue(d.has(SourceCapability.NOVEL_CHAPTER), d.capabilities.toString())
            assertTrue(d.has(SourceCapability.HTML_HELPER), d.capabilities.toString())
            val runtime = SourceRuntime(descriptor = d, engine = engine)
            // 搜索主链路（Html.list 真实走通）。
            val page = runtime.searchPage("k", "[]", page = 1)
            assertTrue(page.contains("w1"), page)
            // 小说 plain 分支 format 透出。
            val payload = BridgeChapterProvider(runtime, ContentKind.NOVEL).payload("w", "plain")
            assertIs<core.model.ChapterPayload.Text>(payload)
            assertEquals(core.model.TextFormat.PLAIN, payload.format)
            // 错误 helper 分类。
            val login = assertFailsWith<SourcePluginException> {
                runtime.invoke("probeError", """["login"]""")
            }
            assertEquals(SourceErrorKind.LOGIN_EXPIRED, login.kind)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }
}
