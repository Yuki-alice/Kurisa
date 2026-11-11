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
import source.contract.SourceCapability
import source.hostapi.HostApi
import source.hostapi.HostConvert
import source.hostapi.HostHtml
import source.hostapi.HostNetwork
import source.hostapi.HostNovel
import source.hostapi.HostStorage
import source.hostapi.HostUi
import source.hostapi.HostUtils
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 全量源兼容性矩阵（仅 desktopTest，refs 目录由运行者注入）。
 *
 * 用法：
 * ```
 * ./gradlew :source:bridge:desktopTest \
 *   -Dkurisa.refsDir=<venera-configs 目录> \
 *   -Dkurisa.e2eReport=<本地报告输出路径>
 * ```
 *
 * 分三层，避免把「fixture 形状不匹配」误判成引擎缺陷：
 * - L1 加载/实例化/describe：语法、class fields、基类、能力探测（硬指标）
 * - L2 搜索冒烟：只区分 API 缺口（polyfill 未提供）与业务 fixture 不匹配
 * - L3 静态 API 统计：所有源用到的宿主全局对象 vs polyfill 已提供清单
 *
 * 域名白名单从脚本内 URL 静态提取，不在代码库内置任何站点地址（C1）；
 * 源脚本只在本机只读加载，不入库、不分发（GPL-3.0）。
 */
class AllSourcesCompatibilityTest {

    private data class Row(
        val file: String,
        val l1: Boolean,
        val name: String,
        val caps: String,
        val settings: Int,
        val domains: Int,
        val l2: String,
        val error: String,
    )

    /** polyfill 当前提供的宿主 API（与 QuickJsEngine.polyfillOf 同步维护）。 */
    private val providedApi = setOf(
        "Network.get", "Network.post", "Network.put", "Network.patch", "Network.delete",
        "Network.sendRequest", "Network.fetchBytes", "Network.getCookies",
        "Network.setCookies", "Network.deleteCookies",
        "Convert.md5", "Convert.sha1", "Convert.sha256", "Convert.sha512",
        "Convert.encodeBase64", "Convert.decodeBase64",
        "Convert.encodeUtf8", "Convert.decodeUtf8", "Convert.encodeGbk", "Convert.decodeGbk",
        "Convert.hexEncode", "Convert.hmacString", "Convert.hmac",
        "Convert.decryptAesEcb", "Convert.decryptAesCbc", "Convert.decryptAesCfb",
        "Convert.decryptAesOfb", "Convert.decryptRsa",
        "Html.list", "Html.text", "Html.attr",
        "Utils.createUuid", "Utils.randomInt", "Utils.randomDouble",
        "UI.showMessage", "UI.showDialog", "UI.launchUrl", "UI.showLoading",
        "UI.cancelLoading", "UI.showInputDialog", "UI.showSelectDialog",
    )

    @Test
    fun allInstalledShapesLoadAndDescribe() = runTest {
        val refs = System.getProperty("kurisa.refsDir")
        if (refs.isNullOrBlank()) {
            println("SKIP（未设置 -Dkurisa.refsDir）：全量源矩阵仅本地执行")
            return@runTest
        }
        val dir = File(refs)
        val scripts = (dir.listFiles { _, n -> n.endsWith(".js") && !n.startsWith("_") } ?: emptyArray())
            .sortedBy { it.name }
        if (scripts.isEmpty()) {
            println("SKIP（refs 下无源脚本）")
            return@runTest
        }

        val cookies = CookieStore()
        val seen = mutableListOf<String>()
        val mock = MockEngine { request ->
            seen += request.url.toString()
            respond(
                content = ByteReadChannel(
                    """{"data":[],"total":0,"limit":20,"offset":0,""" +
                        """"result":[],"results":{"api":[["https://example.invalid"]]},""" +
                        """"list":[],"docs":[],"comics":[],"works":[],"maxPage":1,""" +
                        """"baseUrl":"https://example.invalid",""" +
                        """"chapter":{"data":["p1.jpg"],"dataSaver":[],"hash":"h"}}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        fun hostFor(domains: Set<String>): HostApi {
            val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
            val network: HostNetwork = KtorHostNetwork(http, cookies, domains) { 0L }
            val convert: HostConvert = CryptoHostConvert()
            val html: HostHtml = KsoupHostHtml()
            val ui: HostUi = NoopHostUi()
            val utils: HostUtils = HostUtilsImpl()
            val kv: HostStorage = InMemoryHostStorage()
            val novel: HostNovel = NovelHost()
            return object : HostApi {
                override val network: HostNetwork = network
                override val html: HostHtml = html
                override val convert: HostConvert = convert
                override val ui: HostUi = ui
                override val utils: HostUtils = utils
                override val storage: HostStorage = kv
                override val novel: HostNovel = novel
            }
        }

        val manager = SourceRuntimeManager(
            engines = QuickJsPluginEngineFactory(cookies, DefaultSourceDispatchers()),
            hosts = PluginHostProvider { spec -> hostFor(spec.domains) },
            maxCached = scripts.size + 1,
        )
        // 运行时从脚本提取域名（含模板串里的裸域名常量）；代码库不内置任何站点地址。
        val urlHost = Regex("""(?:https?://)?([A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9-]+)+)""")
        val rows = mutableListOf<Row>()
        var cursorCount = 0
        val apiUsage = mutableMapOf<String, Int>()
        val globalUsage = mutableMapOf<String, Int>()
        val apiPattern = Regex("""\b(Network|Convert|UI|Utils|Html)\.([A-Za-z_][A-Za-z0-9_]*)""")
        val globalPattern = Regex(
            """\b(fetch|setTimeout|setInterval|createUuid|randomInt|randomDouble|sendMessage|HtmlDocument|throwLoginExpired|throwNotFound|throwRateLimited|cleanNovelHtml)\s*\(""",
        )

        scripts.forEach { file ->
            val script = file.readText()
            apiPattern.findAll(script).forEach { m ->
                val key = m.groupValues[1] + "." + m.groupValues[2]
                apiUsage[key] = (apiUsage[key] ?: 0) + 1
            }
            globalPattern.findAll(script).forEach { m ->
                globalUsage[m.groupValues[1]] = (globalUsage[m.groupValues[1]] ?: 0) + 1
            }
            val domains = urlHost.findAll(script).map { it.groupValues[1] }.toSet()
            var l1 = false
            var name = ""
            var caps = ""
            var settings = 0
            var error = ""
            var l2 = "SKIP"
            // fail-closed：提不出域名的脚本不执行（白名单为空即全拦截，此处显式记录）。
            if (domains.isEmpty()) {
                error = "NO_DOMAINS：脚本内未提取到任何域名，白名单为空"
            } else try {
                val runtime = manager.acquire(
                    PluginRuntimeSpec(
                        key = "compat." + file.nameWithoutExtension,
                        script = script,
                        domains = domains,
                        apiVersion = 3,
                        appVersion = "9.9.9",
                    ),
                )
                val d = runtime.descriptor
                l1 = true
                name = d.name
                caps = d.capabilities.joinToString("+") { it.serial }
                settings = d.settings.size
                if (d.has(SourceCapability.SEARCH)) {
                    l2 = try {
                        if (!d.hasSearchLoad && d.hasSearchNext) {
                            // Venera 游标分页源（如 ehentai）：宿主应走 searchNext 而非 searchWorks。
                            cursorCount++
                            runtime.searchNext("test", runtime.defaultSearchOptions(), null)
                        } else {
                            runtime.searchWorks("test", runtime.defaultSearchOptions(), 1)
                        }
                        "OK"
                    } catch (t: Throwable) {
                        val msg = (t.message ?: t.toString())
                        error = msg.take(180)
                        if (looksLikeApiGap(msg)) "API_GAP" else "FIXTURE"
                    }
                }
            } catch (t: Throwable) {
                error = (t.message ?: t.toString()).take(180)
            }
            rows += Row(file.name, l1, name, caps, settings, domains.size, l2, error.replace('\n', ' '))
        }
        manager.close()

        val l1Ok = rows.count { it.l1 }
        val gaps = rows.filter { it.l2 == "API_GAP" }
        val missingApi = apiUsage.keys.filter { it !in providedApi }.sorted()
        val lines = buildList {
            add("=== 全量源兼容矩阵（${rows.size} 个脚本）===")
            add("L1 加载/描述成功: $l1Ok / ${rows.size}")
            add("L2 搜索: OK=${rows.count { it.l2 == "OK" }} " +
                "API_GAP=${gaps.size} FIXTURE=${rows.count { it.l2 == "FIXTURE" }} " +
                "SKIP=${rows.count { it.l2 == "SKIP" }}")
            add("MockEngine 请求数: ${seen.size}；游标分页源: $cursorCount")
            add("--- 逐源 ---")
            rows.forEach { r ->
                add("SRC|${r.file}|L1=${if (r.l1) "OK" else "FAIL"}|${r.name}|" +
                    "caps=${r.caps}|settings=${r.settings}|domains=${r.domains}|L2=${r.l2}|err=${r.error}")
            }
            add("--- API 使用频次 ---")
            apiUsage.entries.sortedByDescending { it.value }.forEach { (k, v) -> add("API|$k|$v") }
            globalUsage.entries.sortedByDescending { it.value }.forEach { (k, v) -> add("GLOBAL|$k|$v") }
            add("--- polyfill 未提供（按名字）---")
            add("MISSING|" + missingApi.joinToString(","))
        }
        val report = lines.joinToString("\n")
        val out = System.getProperty("kurisa.e2eReport")
        if (!out.isNullOrBlank()) File(out).writeText(report + "\n")
        println(report)

        assertTrue(rows.size >= 20, "应扫描到官方仓库的源脚本全量，实际 ${rows.size}")
        // 动态门：允许 1 个已知失败（jm 启动依赖外部加密域名列表，离线 fixture 无法提供），
        // 引擎回归导致第 2 个源加载失败时立即红灯；源仓库增删条目不会误报。
        assertTrue(
            l1Ok >= rows.size - 1,
            "L1 失败数超过已知基线：$l1Ok / ${rows.size}（应 >= ${rows.size - 1}）",
        )
        // T1 收紧：API 缺口零容忍（此前只打印 MISSING/API_GAP，不锁门）。
        assertTrue(
            gaps.isEmpty(),
            "存在宿主 API 缺口（API_GAP ${gaps.size}）：${gaps.take(3).joinToString { it.file + ":" + it.error }}",
        )
        assertTrue(
            missingApi.isEmpty(),
            "静态扫描发现 polyfill 未提供的方法：${missingApi.joinToString()}",
        )
    }

    private fun looksLikeApiGap(message: String): Boolean {
        val m = message.lowercase()
        return listOf(
            "not a function", "is not a function", "is not defined", "not defined",
            "undefined is not", "unsupported method", "unsupported-hostcall",
            "unknown-op", "not allowed",
        ).any { it in m }
    }

}
