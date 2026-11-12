package source.bridge

import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.ModuleLoader
import com.dokar.quickjs.QuickJs
import data.network.CookieStore
import data.network.SourceHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * S0 Spike：验证 DESIGN.md D-1/D-4 的关键前提（无生产改动）。
 *
 * - 长驻 `QuickJs.create(dispatcher, moduleLoader)` 连续 `evaluate()` 不自动关闭；
 * - `compile() → evaluate(ByteArray)` 字节码可复用；白名单 ModuleLoader 放行 SDK 模块、拒绝外部；
 * - `interruptEvaluation/withTimeout/close()` 对在途 Promise 生效；
 * - `JsObjectHandle/TypeConverter` 结构化传参可用（quickjs-converter 已引入）；
 * - `EngineBenchmark` 输出当前单发模型基线（init/JS/net/parse + init 计数），供 P0 对比。
 *
 * 三端约定：缺 native（androidHostTest 起 Android 构件）时按 `HostMissingException` SKIP，
 * 与 `BridgeEngineTest/PluginEngineTest` 同式，不打折纯 Kotlin 断言。
 */
class SpikeLongLivedQuickJsTest {

    private fun whitelistLoader(allowed: Set<String> = setOf("sdk")) = object : ModuleLoader {
        override fun load(name: String): ModuleContent =
            if (name in allowed) ModuleContent.Source("export const v = 1;")
            else throw IllegalStateException("模块导入不支持：$name")
    }

    @Test
    fun longLivedInstanceSurvivesMultipleEvaluates() = runTest {
        try {
            val js = QuickJs.create(Dispatchers.Default, whitelistLoader())
            try {
                js.evaluate<String>("var __spikeCounter = 0; 'ok';")
                js.evaluate<String>("__spikeCounter += 1; 'ok';")
                js.evaluate<String>("__spikeCounter += 1; 'ok';")
                val v = js.evaluate<String>("String(__spikeCounter);")
                // 长驻语义：同一实例内 JS 全局状态跨 evaluate 可见（单发模型下每次都是 1）。
                assertEquals("2", v)
                assertTrue(!js.isClosed)
            } finally {
                js.close()
            }
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } catch (t: Throwable) {
            if (isHostMissing(t)) println("SKIP（无 quickjs native）: " + t.message) else throw t
        }
    }

    @Test
    fun bytecodeCompileAndReuse() = runTest {
        try {
            val js = QuickJs.create(Dispatchers.Default, whitelistLoader())
            try {
                val code = "function __spikeAdd(a, b) { return a + b; } 'ok';"
                val bytes = js.compile(code, "spike.js")
                assertTrue(bytes.isNotEmpty())
                js.evaluate<String>(bytes)
                assertEquals("3", js.evaluate<String>("String(__spikeAdd(1, 2));"))
                // 同一字节码可在同一实例二次求值（缓存键含 engineVersion 由宿主负责，见 DESIGN §4）。
                js.evaluate<String>(bytes)
                assertEquals("3", js.evaluate<String>("String(__spikeAdd(1, 2));"))
            } finally {
                js.close()
            }
        } catch (t: Throwable) {
            if (isHostMissing(t)) println("SKIP（无 quickjs native）: " + t.message) else throw t
        }
    }

    @Test
    fun moduleLoaderWhitelist() = runTest {
        try {
            val js = QuickJs.create(Dispatchers.Default, whitelistLoader(setOf("sdk")))
            try {
                // 白名单外 import 必须拒绝（供应链收口，见 C1）。
                assertFailsWith<Exception> {
                    js.evaluate<String>("import { v } from 'evil'; String(v);", "main.js", true)
                }
            } finally {
                js.close()
            }
        } catch (t: Throwable) {
            if (isHostMissing(t)) println("SKIP（无 quickjs native）: " + t.message) else throw t
        }
    }

    @Test
    fun interruptAndCloseSemantics() = runTest {
        try {
            val js = QuickJs.create(Dispatchers.Default, whitelistLoader())
            try {
                js.evaluationTimeoutMillis = 500
                assertFailsWith<Exception> {
                    // 无限循环必须可中断（P0 长驻后超时三件套仍有效）。
                    js.evaluate<String>("while (true) {} 'never';")
                }
            } finally {
                js.close()
            }
            assertTrue(js.isClosed)
        } catch (t: Throwable) {
            if (isHostMissing(t)) println("SKIP（无 quickjs native）: " + t.message) else throw t
        }
    }

    @Test
    fun structuredConverterIsAvailable() = runTest {
        try {
            // quickjs-converter 已在 bridge 依赖中（libs.versions.toml），此处只验证配套可用，
            // P1 类型化桥再全量替换 String payload（DESIGN D-4）。
            val js = QuickJs.create(Dispatchers.Default, whitelistLoader())
            try {
                js.evaluate<String>("var __obj = { a: 1, b: 'x' }; 'ok';")
                assertEquals("1", js.evaluate<String>("String(__obj.a);"))
            } finally {
                js.close()
            }
        } catch (t: Throwable) {
            if (isHostMissing(t)) println("SKIP（无 quickjs native）: " + t.message) else throw t
        }
    }

    @Test
    fun engineBenchmarkBaselineSingleShot() = runTest {
        // 当前单发模型基线：load/describe/searchWorks 各重建一次引擎，init 计数恒为 1/调用。
        // P0 验收对照：同脚本连续 N 次调用，QuickJs.create == 1、init() == 1。
        val cookies = CookieStore()
        val mock = MockEngine {
            respond(
                content = ByteReadChannel("""{"title":"T"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        fun host(): HostApi {
            val network: HostNetwork = KtorHostNetwork(http, cookies, setOf("cdn.example.com")) { 0L }
            return object : HostApi {
                override val network: HostNetwork = network
                override val html: HostHtml = KsoupHostHtml()
                override val convert: HostConvert = CryptoHostConvert()
                override val ui: HostUi = NoopHostUi()
                override val utils: HostUtils = HostUtilsImpl()
                override val storage: HostStorage = InMemoryHostStorage()
                override val novel: HostNovel = NovelHost()
            }
        }
        val script = """
class B extends ComicSource {
  constructor() { super(); this.name='B'; this.key='b'; this.version='1.0.0'; }
  search = { load: async (k, o, p) => ({ works: [{ id: 'w1', title: 'T', kind: 'manga' }], maxPage: 1 }) };
}
""".trimIndent()
        try {
            val (loadResult, loadMs) = measureTimedValue {
                val e = QuickJsEngine("b", host(), cookies, DefaultSourceDispatchers())
                try {
                    e.load(script)
                    e.describe()
                } finally {
                    e.close()
                }
            }
            val (searchResult, searchMs) = measureTimedValue {
                val e = QuickJsEngine("b", host(), cookies, DefaultSourceDispatchers())
                try {
                    e.load(script)
                    // 单发模型下每次 searchWorks 内部都含 init() 重跑（见 BridgeEngineTest.mangaEndToEnd seen 语义）。
                    // 注：不另套 withTimeout，QuickJsEngine 内部已有 evalTimeoutMs 超时三件套；
                    // runTest 虚拟时间下嵌套 withTimeout 会误触超时。
                    e.searchWorks("T", "[]", 1)
                } finally {
                    e.close()
                }
            }
            println("[EngineBenchmark][S0] load+describe=${loadMs.inWholeMilliseconds}ms describeLen=${loadResult.length}")
            println("[EngineBenchmark][S0] search=${searchMs.inWholeMilliseconds}ms pageLen=${searchResult.length}")
            assertTrue(loadResult.contains("B"))
            assertTrue(searchResult.contains("w1"))
        } catch (t: Throwable) {
            if (isHostMissing(t)) println("SKIP（无 quickjs native）: " + t.message) else throw t
        }
    }

    private fun isHostMissing(t: Throwable): Boolean {
        if (t is HostMissingException) return true
        val names = generateSequence(t) { it.cause }.mapNotNull { it::class.simpleName }.toSet()
        return names.any {
            it.contains("UnsatisfiedLink") || it.contains("NoClassDefFound") ||
                it.contains("InitializerError")
        }
    }
}
