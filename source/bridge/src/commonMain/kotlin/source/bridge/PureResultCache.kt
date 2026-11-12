package source.bridge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 纯函数结果缓存（`DESIGN.md` §8 分层缓存 + `HOST-SDK.md` § Store）。
 *
 * 覆盖：`onImageLoad` / `onImageResolve` / `translate`（按源 key + 参数键控，无副作用、可安全复用）。
 * 写操作永不进此缓存（KV 写走 `HostStorage` 串行通道）。`nowMs() == 0` 视为时间冻结（单测），永不过期。
 */
class PureResultCache(
    private val ttlMs: Long = 300_000,
    private val maxEntries: Int = 512,
    private val nowMs: () -> Long = { 0L },
) {
    private data class Entry(val value: String, val expiresAt: Long)

    private val mutex = Mutex()
    private val map = LinkedHashMap<String, Entry>()
    var hits: Long = 0L
        private set
    var misses: Long = 0L
        private set

    private fun expired(e: Entry, now: Long): Boolean =
        ttlMs > 0 && now != 0L && now > e.expiresAt

    suspend fun getOrPut(key: String, block: suspend () -> String): String {
        mutex.withLock {
            val e = map[key]
            if (e != null && !expired(e, nowMs())) {
                map.remove(key)
                map[key] = e
                hits++
                return e.value
            } else if (e != null) {
                map.remove(key)
            }
            misses++
        }
        val v = block()
        mutex.withLock {
            if (map.size >= maxEntries) {
                map.keys.firstOrNull()?.let { map.remove(it) }
            }
            val now = nowMs()
            map.remove(key)
            map[key] = Entry(v, if (now == 0L) Long.MAX_VALUE else now + ttlMs)
        }
        return v
    }

    suspend fun invalidatePrefix(prefix: String) {
        mutex.withLock {
            val dead = map.keys.filter { it.startsWith(prefix) }
            dead.forEach { map.remove(it) }
        }
    }

    suspend fun clear() {
        mutex.withLock { map.clear() }
    }

    fun hitRate(): Double {
        val h = hits
        val m = misses
        return if (h + m == 0L) 0.0 else h.toDouble() / (h + m)
    }
}
