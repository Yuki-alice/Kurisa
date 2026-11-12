package source.bridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 源串行调度器（架构 4.6.3：每个源绑定一个串行 dispatcher）。
 *
 * 同源调用不并发（保护 Cookie / KV 状态）；不同源可并行。
 * quickjs-kt 要求单实例 confined，用方为“串行执行”；
 * `limitedParallelism(1)` 保证无并发（线程身份不断言——若某端强制线程亲和，
 * 经此接口替换为平台线程工厂实现，见 ADR-006）。
 */
interface SourceDispatchers {
    suspend fun dispatcherFor(sourceKey: String): CoroutineDispatcher

    /** 宿主 IO 并发缝（P1：网络/解析走此 dispatcher，JS 求值仍走 per-source 串行）。 */
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

class DefaultSourceDispatchers : SourceDispatchers {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CoroutineDispatcher>()

    override suspend fun dispatcherFor(sourceKey: String): CoroutineDispatcher =
        mutex.withLock {
            cache.getOrPut(sourceKey) { Dispatchers.Default.limitedParallelism(1) }
        }
}
