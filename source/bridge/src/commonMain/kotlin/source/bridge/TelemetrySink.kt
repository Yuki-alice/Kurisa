package source.bridge

/**
 * 单次源调用的分段耗时（BE-B6 遥测消费出口；`DESIGN.md` S8 四段）。
 *
 * - `jsMs`：JS 引擎纯求值耗时增量（`EngineTelemetry.jsMsTotal` 差值）；
 * - `netMs`：真实网络耗时增量（`NetworkTelemetry.netMsTotal` 差值）；
 * - `parseMs`：HTML 解析耗时增量（`HtmlTelemetry.parseSelectMsTotal` 差值）；
 * - `initMs`：未归因耗时 = total - js - net - parse（下钳 0；含引擎初始化与宿主开销，
 *   非测量值，展示侧必须标注为估算，不能当精确 init 计）；
 * - `totalMs`：`SourceCallTrace.durationMs`（能力门耗时计入，白名单复核与重试退避不计）。
 */
data class CallSegments(
    val sourceKey: String,
    val path: String,
    val ok: Boolean,
    val totalMs: Long,
    val jsMs: Long,
    val netMs: Long,
    val parseMs: Long,
    val initMs: Long,
    val errorKind: source.contract.SourceErrorKind?,
)

/** 三段累计计数器快照（宿主在调用前后各取一次，recorder 按差值归因到本次调用）。 */
data class CounterTrio(
    val engine: EngineTelemetry,
    val network: NetworkTelemetry,
    val html: HtmlTelemetry,
) {
    companion object {
        fun zero() = CounterTrio(EngineTelemetry(), NetworkTelemetry(), HtmlTelemetry())
    }
}

/** 路径聚合（调用次数/成功/失败/总耗时/错误分类计数；UI 面板按源 key 分组展示）。 */
data class PathStats(
    val path: String,
    val calls: Long,
    val ok: Long,
    val errors: Long,
    val totalMs: Long,
    val byKind: Map<String, Long>,
)

/** 一次导出：分段调用明细 + 路径聚合（脱敏：只含耗时与计数，无参数/正文/Cookie）。 */
data class TelemetryReport(
    val calls: List<CallSegments>,
    val byPath: Map<String, PathStats>,
)

/**
 * 遥测消费出口（BE-B6；生产由装配层持有单例，UI 日志面板拉 `export()`）。
 *
 * - `onTrace`：每次源调用的终态 trace（成功/失败各一次；取消与宿主缺 native 不产出，
 *   与 `SourceRuntime.onTrace` 同语义）；
 * - `onCall`：一次调用的分段耗时（宿主按“快照-调用-快照”配方经 `emitCall` 喂入）；
 * - recorder 永不吞异常：聚合只做加法，失败调用照常记录 `ok=false`。
 */
interface TelemetrySink {
    fun onTrace(trace: SourceCallTrace)

    fun onCall(segments: CallSegments)
}

/** 宿主装配配方：调用前后各快照一次，一行产出分段（计数器由宿主实现持有，见 BE-B6）。 */
fun TelemetrySink.emitCall(trace: SourceCallTrace, before: CounterTrio, after: CounterTrio) {
    onCall(TelemetryRecorder.segmentCall(trace, before, after))
}

class TelemetryRecorder : TelemetrySink {
    private val traces = mutableListOf<SourceCallTrace>()
    private val calls = mutableListOf<CallSegments>()

    override fun onTrace(trace: SourceCallTrace) {
        traces += trace
    }

    override fun onCall(segments: CallSegments) {
        calls += segments
    }

    /** 导出并清空分段明细（路径聚合保留，`clear()` 才清零）。 */
    fun export(): TelemetryReport {
        val byPath = traces.groupBy { it.path }.mapValues { (path, items) ->
            PathStats(
                path = path,
                calls = items.size.toLong(),
                ok = items.count { it.ok }.toLong(),
                errors = items.count { !it.ok }.toLong(),
                totalMs = items.sumOf { it.durationMs },
                byKind = items.mapNotNull { it.errorKind }.groupingBy { it.name }.eachCount()
                    .mapValues { it.value.toLong() },
            )
        }
        val out = TelemetryReport(calls.toList(), byPath)
        calls.clear()
        return out
    }

    fun clear() {
        traces.clear()
        calls.clear()
    }

    companion object {
        /**
         * 纯函数：一次调用的 trace + 调用前后计数器快照 → 分段（宿主在 HostApi 装配处调用）。
         * 差值下钳 0（计数器回绕/并发串扰不产生负分段）；init 为未归因余量。
         */
        fun segmentCall(trace: SourceCallTrace, before: CounterTrio, after: CounterTrio): CallSegments {
            val js = (after.engine.jsMsTotal - before.engine.jsMsTotal).coerceAtLeast(0)
            val net = (after.network.netMsTotal - before.network.netMsTotal).coerceAtLeast(0)
            val parse = (after.html.parseSelectMsTotal - before.html.parseSelectMsTotal).coerceAtLeast(0)
            val init = (trace.durationMs - js - net - parse).coerceAtLeast(0)
            return CallSegments(
                sourceKey = trace.sourceKey,
                path = trace.path,
                ok = trace.ok,
                totalMs = trace.durationMs,
                jsMs = js,
                netMs = net,
                parseMs = parse,
                initMs = init,
                errorKind = trace.errorKind,
            )
        }
    }
}
