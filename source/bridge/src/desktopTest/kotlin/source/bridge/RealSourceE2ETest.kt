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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import source.contract.ContentKind
import source.contract.SourceCapability
import source.hostapi.HostApi
import source.hostapi.HostConvert
import source.hostapi.HostHtml
import source.hostapi.HostNetwork
import source.hostapi.HostNovel
import source.hostapi.HostStorage
import source.hostapi.HostUi
import source.hostapi.HostUtils
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 本地真实源 E2E（仅 desktopTest，且只在运行者显式提供 refs 目录时执行）。
 *
 * 用法：
 * ```
 * ./gradlew :source:bridge:desktopTest \
 *   -Dkurisa.refsDir=F:/Kurisa/.workbuddy/refs/venera-configs \
 *   -Dkurisa.e2eDomains=<由运行者声明的域名白名单>
 * ```
 *
 * 代码库内**不含**任何站点 URL、源脚本或解析逻辑：脚本从 refs 目录读取，
 * 域名由命令行注入（C1）；Venera 源为 GPL-3.0，仅用于本地兼容性验证，不复制、不分发。
 * 未提供参数时直接跳过，CI / 其他机器不受影响。
 */
class RealSourceE2ETest {

    @Test
    fun veneraSourceRunsThroughPluginEngine() = runTest {
        val refs = System.getProperty("kurisa.refsDir")
        if (refs.isNullOrBlank()) {
            println("SKIP（未设置 -Dkurisa.refsDir）：真实源 E2E 仅本地执行")
            return@runTest
        }
        val domains = System.getProperty("kurisa.e2eDomains").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (domains.isEmpty()) {
            println("SKIP（未设置 -Dkurisa.e2eDomains）：域名白名单必须由运行者声明")
            return@runTest
        }
        val scriptFile = java.io.File(refs, "manga_dex.js")
        if (!scriptFile.isFile) {
            println("SKIP（refs 下无目标脚本）")
            return@runTest
        }
        val script = scriptFile.readText()

        val cookies = CookieStore()
        val seen = mutableListOf<String>()
        val mock = MockEngine { request ->
            seen += request.url.toString()
            respond(
                // 同一份 fixture 同时满足列表/章节两种返回形状；URL 用保留域名，
                // 代码库不出现任何真实站点地址（C1）。
                content = ByteReadChannel(
                    """{"data":[],"total":0,"limit":20,"offset":0,"result":"ok",""" +
                        """"baseUrl":"https://example.invalid",""" +
                        """"chapter":{"data":["p1.jpg"],"dataSaver":[],"hash":"h"}}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val network: HostNetwork = KtorHostNetwork(http, cookies, domains) { 0L }
        val convert: HostConvert = CryptoHostConvert()
        val html: HostHtml = KsoupHostHtml()
        val ui: HostUi = NoopHostUi()
        val utils: HostUtils = HostUtilsImpl()
        val storage: HostStorage = InMemoryHostStorage()
        val novel: HostNovel = NovelHost()
        val host = object : HostApi {
            override val network: HostNetwork = network
            override val html: HostHtml = html
            override val convert: HostConvert = convert
            override val ui: HostUi = ui
            override val utils: HostUtils = utils
            override val storage: HostStorage = storage
            override val novel: HostNovel = novel
        }
        val manager = SourceRuntimeManager(
            engines = QuickJsPluginEngineFactory(cookies, DefaultSourceDispatchers()),
            hosts = PluginHostProvider { host },
        )
        val runtime = manager.acquire(
            PluginRuntimeSpec(
                key = "local.e2e",
                script = script,
                domains = domains,
                apiVersion = 3,
                appVersion = "1.6.0",
                settingsJson = """{"image_quality":"Original"}""",
            ),
        )
        try {
            // 1) 描述符：真实源无 ==SourceScript== 头，全部字段靠 describe() 探测。
            val d = runtime.descriptor
            assertTrue(d.name.isNotBlank(), "name 应从类字段读出")
            assertTrue(d.has(SourceCapability.SEARCH), "应探测到 search 能力")
            assertTrue(d.has(SourceCapability.SETTINGS), "应探测到声明式设置")
            assertTrue(d.has(SourceCapability.IMAGE_LOAD), "应探测到 onImageLoad")

            // 2) 主链路：默认搜索选项 + search.load（fetch polyfill + 域名白名单）。
            val opts = runtime.defaultSearchOptions()
            val page = runtime.searchWorks("", opts, 1)
            val parsed = Json.parseToJsonElement(page).jsonObject
            assertTrue(parsed.containsKey("comics") || parsed.containsKey("works"), page.take(400))
            assertTrue(seen.isNotEmpty(), "真实源应至少发起一次网络请求")

            // 3) 章节正文：真实源 loadEp 走 fetch polyfill + loadSetting，
            //    再经 BridgeChapterProvider 归一化成阅读器可消费的 ChapterPayload。
            val ep = BridgeChapterProvider(runtime, ContentKind.MANGA).payload("w", "c")
            assertTrue(ep is core.model.ChapterPayload.Images, "章节应归一化为图片载荷")
            val urls = ep.urls
            assertTrue(urls.isNotEmpty(), "应产出图片 URL")

            // 4) 图片加载配置：真实源返回 Referer/Origin + URL 重写，函数字段被忽略。
            val cfg = requireNotNull(runtime.imageLoadConfig("https://example.invalid/1.jpg", "w", "c")) {
                "onImageLoad 应产出归一化配置"
            }
            assertTrue(cfg.headers.isNotEmpty(), "应保留 Referer/Origin 等业务头")
            assertTrue(
                cfg.headers.keys.none { it.equals("cookie", true) || it.equals("user-agent", true) },
                "宿主注入的头不可被源覆盖",
            )
            println(
                "真实源 E2E 通过：name=${d.name} capabilities=${d.capabilities} " +
                    "requests=${seen.size} settings=${d.settings.size} " +
                    "images=${urls.size}",
            )
        } finally {
            manager.close()
        }
    }
}
