package core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 调度器门面（可替换，供测试注入 TestDispatcher）。
 * 上层（仓库/数据源）的主线程安全由本门面保证，调用方不再关心平台线程细节。
 */
interface AppDispatchers {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

object DefaultAppDispatchers : AppDispatchers {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val default: CoroutineDispatcher = Dispatchers.Default
    // Dispatchers.IO 在 Kotlin/Native 为 internal：commonMain 统一用 Default；
    // JVM/Android 的 IO 优化由调用方经 Koin 注入替换（M6 平台装配）。
    override val io: CoroutineDispatcher = Dispatchers.Default
}

/**
 * 指数退避重试（纯逻辑；网络层的熔断/限流后续交 kmp-resilient，见架构第 8 章）。
 * @param maxAttempts 总尝试次数（含首次，≥1）
 * @param baseDelayMs 首次退避；每次翻倍
 */
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 500,
    block: suspend () -> T,
): T {
    require(maxAttempts >= 1)
    var attempt = 0
    var delayMs = baseDelayMs
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            attempt++
            if (attempt >= maxAttempts) throw e
            if (e is kotlinx.coroutines.CancellationException) throw e
            delay(delayMs)
            delayMs *= 2
        }
    }
}

/** 日志门面（后端由 Napier/Kermit 实现，M6 接入；此处仅形状，防各模块直调 println）。 */
interface AppLog {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/** 空实现（测试与初始化期占位；生产必须替换为平台后端）。 */
object NoopAppLog : AppLog {
    override fun d(tag: String, message: String) = Unit
    override fun w(tag: String, message: String) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

/** IO 调度执行（JVM/Android 走 Dispatchers.IO；调用方一律经此，不直写 withContext）。 */
suspend fun <T> ioRun(dispatchers: AppDispatchers = DefaultAppDispatchers, block: suspend () -> T): T =
    withContext(dispatchers.io) { block() }

/**
 * 防抖器（提交后静默满 waitMs 才执行一次；重复提交取消前一次）。
 * scope 由调用方注入——生产传 viewModelScope，测试传 TestScope，
 * 不依赖 Dispatchers.Main 的 delay 语义（见 M3 记录）。
 */
class Debouncer(
    private val scope: CoroutineScope,
    private val waitMs: Long,
    private val action: suspend () -> Unit,
) {
    private var job: Job? = null

    fun submit() {
        job?.cancel()
        job = scope.launch {
            delay(waitMs)
            action()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
/**
 * 日志条目（`InMemoryAppLog.entries()` 导出用；throwable 只记类型与消息，不记堆栈与参数）。
 */
data class LogEntry(
    val level: String,
    val tag: String,
    val message: String,
    val throwable: String?,
    val atEpochMs: Long,
)

/**
 * 内存日志后端（BE-B6：`NoopAppLog` 的可注入替代）。
 *
 * 环形缓冲（默认 200 条，超限丢最旧）；`entries()` 返回快照，可导出文本。
 * 生产由平台后端替换（Napier/Kermit，M6），桌面调试与单测用此实现断言。
 * 调用约定与 `DbKvStore` 一致：只在串行上下文调用，不做跨线程同步。
 */
class InMemoryAppLog(
    private val capacity: Int = 200,
    private val nowMs: () -> Long = { 0L },
) : AppLog {
    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val buf = ArrayDeque<LogEntry>(capacity)

    override fun d(tag: String, message: String) = add("D", tag, message, null)

    override fun w(tag: String, message: String) = add("W", tag, message, null)

    override fun e(tag: String, message: String, throwable: Throwable?) =
        add("E", tag, message, throwable?.let { it::class.simpleName + ": " + it.message })

    /** 快照（调用方只读，不影响缓冲）。 */
    fun entries(): List<LogEntry> = buf.toList()

    fun clear() = buf.clear()

    private fun add(level: String, tag: String, message: String, throwable: String?) {
        if (buf.size >= capacity) buf.removeFirst()
        buf.addLast(LogEntry(level, tag, message, throwable, nowMs()))
    }
}

/**
 * 扇出日志（BE-B6：同时喂内存缓冲与平台后端）。
 *
 * 一个后端抛异常不影响其余后端（逐个投递），最后把首个异常重新抛出——
 * 不吞异常，但也不让一个坏后端饿死其它后端。
 */
class MultiplexAppLog(private vararg val sinks: AppLog) : AppLog {
    override fun d(tag: String, message: String) = each { it.d(tag, message) }

    override fun w(tag: String, message: String) = each { it.w(tag, message) }

    override fun e(tag: String, message: String, throwable: Throwable?) =
        each { it.e(tag, message, throwable) }

    private inline fun each(block: (AppLog) -> Unit) {
        var first: Throwable? = null
        for (s in sinks) {
            try {
                block(s)
            } catch (t: Throwable) {
                if (first == null) first = t
            }
        }
        if (first != null) throw first
    }
}

/** 标签前缀装饰（`log.withPrefix("net")` 让 tag 变为 `net/xxx`，三级别一致加前缀）。 */
fun AppLog.withPrefix(prefix: String): AppLog {
    val outer = this
    return object : AppLog {
        override fun d(tag: String, message: String) = outer.d(prefix + "/" + tag, message)

        override fun w(tag: String, message: String) = outer.w(prefix + "/" + tag, message)

        override fun e(tag: String, message: String, throwable: Throwable?) =
            outer.e(prefix + "/" + tag, message, throwable)
    }
}
