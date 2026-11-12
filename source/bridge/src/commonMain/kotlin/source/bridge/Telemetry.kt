package source.bridge

/**
 * P3 可观测性底座（`DESIGN.md` §8 分段计时 + `HOST-SDK.md` § Telemetry）。
 *
 * 三段按层归属，避免跨边界时钟对齐：
 * - JS 段：[EngineTelemetry]（`QuickJsEngine` 每次 `evaluate` 纯求值耗时，不含宿主 IO 等待；
 *   宿主 IO 在协程挂起，不占 quickjs 线程）。
 * - net 段：[NetworkTelemetry]（`KtorHostNetwork` 真实 `http.send` 耗时 + 缓存/去重命中）。
 * - parse 段：[HtmlTelemetry]（`KsoupHostHtml` 解析/查询耗时与次数；`dispose` 后复用仍抛错，语义不变）。
 *
 * UI 消费时按源 key 聚合展示；审计日志维持脱敏（此处只记耗时与计数，不记 URL/正文/Cookie）。
 */
data class EngineTelemetry(
    val calls: Long = 0,
    val jsMsTotal: Long = 0,
    val jsMsMax: Long = 0,
    val timeouts: Long = 0,
    val errors: Long = 0,
)

data class NetworkTelemetry(
    /** 进入网络层的逻辑调用数（含 `requestAll` 逐条）。 */
    val requests: Long = 0,
    /** GET 200 命中 TTL 缓存，未发真实请求。 */
    val cacheHits: Long = 0,
    /** 并发同 key 合并为一次真实请求（等待共享 in-flight 者）。 */
    val inflightCoalesced: Long = 0,
    /** 真实 `http.send` 累计耗时（ms，白名单/重定向复核在内，重试退避不计）。 */
    val netMsTotal: Long = 0,
)

data class HtmlTelemetry(
    val parses: Long = 0,
    val selects: Long = 0,
    val selectManys: Long = 0,
    val disposes: Long = 0,
    val parseSelectMsTotal: Long = 0,
)
