package core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
