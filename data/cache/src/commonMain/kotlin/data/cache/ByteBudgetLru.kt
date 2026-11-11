package data.cache

/** 平台默认字节预算（架构 6.2；iOS 内存取半）。 */
object CacheBudgets {
    const val ANDROID_DESKTOP: Long = 128 * 1024 * 1024
    const val IOS: Long = 64 * 1024 * 1024
}

/**
 * 字节预算 LRU（cache4k 接入前的语义占位；淘汰策略与配额接口冻结）。
 * V 为不透明载荷；sizeOf 由调用方给出（封面/页面按解码字节）。
 */
class ByteBudgetLru<V>(private val maxBytes: Long) {
    private data class Entry<V>(val value: V, val bytes: Long)

    private val map = LinkedHashMap<String, Entry<V>>()
    private var usedBytes = 0L

    fun put(key: String, value: V, bytes: Long) {
        require(bytes >= 0)
        map.remove(key)?.let { usedBytes -= it.bytes }
        map[key] = Entry(value, bytes)
        usedBytes += bytes
        while (usedBytes > maxBytes && map.isNotEmpty()) {
            val eldest = map.keys.first()
            usedBytes -= map.remove(eldest)!!.bytes
        }
    }

    fun get(key: String): V? {
        val e = map.remove(key) ?: return null
        map[key] = e // 访问即最近
        return e.value
    }

    fun used(): Long = usedBytes
    fun size(): Int = map.size
    fun clear() {
        map.clear()
        usedBytes = 0
    }
}
