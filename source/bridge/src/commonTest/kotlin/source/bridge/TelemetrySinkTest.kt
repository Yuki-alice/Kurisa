package source.bridge

import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import source.contract.SourceCapability
import source.contract.SourceDescriptor
import source.contract.SourceErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** BE-B6 门禁：一次调用导出 init/net/parse 分段；失败照常记录，不吞异常。 */
class TelemetrySinkTest {

    private fun trio(js: Long, net: Long, parse: Long) = CounterTrio(
        EngineTelemetry(jsMsTotal = js),
        NetworkTelemetry(netMsTotal = net),
        HtmlTelemetry(parseSelectMsTotal = parse),
    )

    private fun trace(
        path: String = "comic.loadEp",
        ms: Long = 100L,
        ok: Boolean = true,
        kind: SourceErrorKind? = null,
    ) = SourceCallTrace("src.example", path, ms, ok, kind)

    @Test
    fun segmentCallSplitsJsNetParseAndInit() {
        val seg = TelemetryRecorder.segmentCall(trace(ms = 100L), trio(10, 20, 30), trio(15, 50, 45))
        assertEquals(5L, seg.jsMs)
        assertEquals(30L, seg.netMs)
        assertEquals(15L, seg.parseMs)
        // init = 未归因余量 100 - (5 + 30 + 15)。
        assertEquals(50L, seg.initMs)
        assertEquals(100L, seg.totalMs)
        assertTrue(seg.ok)
    }

    @Test
    fun negativeDeltasClampToZero() {
        val seg = TelemetryRecorder.segmentCall(trace(ms = 10L), trio(100, 100, 100), trio(0, 0, 0))
        assertEquals(0L, seg.jsMs)
        assertEquals(0L, seg.netMs)
        assertEquals(0L, seg.parseMs)
        assertEquals(10L, seg.initMs)
    }

    @Test
    fun emitCallFeedsRecorderAndExportDrains() {
        val rec = TelemetryRecorder()
        rec.emitCall(trace(), trio(0, 0, 0), trio(5, 50, 15))
        val rep = rec.export()
        assertEquals(1, rep.calls.size)
        assertEquals(30L, rep.calls[0].initMs)
        assertTrue(rec.export().calls.isEmpty())
    }

    @Test
    fun exportAggregatesByPath() {
        val rec = TelemetryRecorder()
        rec.onTrace(trace("comic.loadEp", 10L, true))
        rec.onTrace(trace("comic.loadEp", 20L, false, SourceErrorKind.TIMEOUT))
        rec.onTrace(trace("search.load", 5L, true))
        val rep = rec.export()
        assertEquals(2, rep.byPath.size)
        val ep = rep.byPath["comic.loadEp"]!!
        assertEquals(2L, ep.calls)
        assertEquals(1L, ep.ok)
        assertEquals(1L, ep.errors)
        assertEquals(30L, ep.totalMs)
        assertEquals(1L, ep.byKind["TIMEOUT"])
    }

    @Test
    fun failureCallRecordedNotSwallowed() {
        val rec = TelemetryRecorder()
        val seg = TelemetryRecorder.segmentCall(
            trace(ok = false, kind = SourceErrorKind.LOGIN_EXPIRED),
            trio(0, 0, 0),
            trio(0, 0, 0),
        )
        rec.onCall(seg)
        val rep = rec.export()
        assertEquals(SourceErrorKind.LOGIN_EXPIRED, rep.calls.single().errorKind)
        assertEquals(false, rep.calls.single().ok)
    }

    private open class FakeEngine : SourcePluginEngine {
        override suspend fun load(script: String) = Unit
        override fun callSync(method: String, payload: String): String = ""
        override suspend fun callAsync(method: String, payload: String, timeoutMs: Long): String = ""
        override fun close() = Unit
        override suspend fun describe(): String = "{}"
        override fun applySettings(settingsJson: String) = Unit
        override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int): String = "{}"
        override suspend fun defaultSearchOptions(): String = "[]"
        override suspend fun loadWorkDetails(workId: String, kind: ContentKind): String = "{}"
        override suspend fun loadChapterPayload(
            workId: String,
            chapterId: String,
            kind: ContentKind,
        ): String = "{}"
        override suspend fun invoke(path: String, argsJson: String): String = "{}"
    }

    private fun descriptor() = SourceDescriptor(
        key = "com.example.test",
        name = "T",
        version = "1.0.0",
        apiVersion = 3,
        kinds = setOf(ContentKind.MANGA),
        domains = listOf("example.com"),
        capabilities = setOf(SourceCapability.SEARCH, SourceCapability.COMIC),
    )

    @Test
    fun sourceRuntimeForwardsTracesToSink() = runTest {
        val rec = TelemetryRecorder()
        val runtime = SourceRuntime(descriptor(), FakeEngine(), telemetry = rec)
        runtime.invoke("search.load", "[]")
        val rep = rec.export()
        assertEquals(1L, rep.byPath["search.load"]?.calls)
        assertEquals(1L, rep.byPath["search.load"]?.ok)
    }

    @Test
    fun sourceRuntimeForwardsFailureTraces() = runTest {
        val rec = TelemetryRecorder()
        val failing = object : FakeEngine() {
            override suspend fun invoke(path: String, argsJson: String): String =
                throw SourcePluginException("boom", SourceErrorKind.TIMEOUT)
        }
        val runtime = SourceRuntime(descriptor(), failing, telemetry = rec)
        assertFailsWith<SourcePluginException> { runtime.invoke("search.load", "[]") }
        val rep = rec.export()
        assertEquals(1L, rep.byPath["search.load"]?.errors)
    }
}
