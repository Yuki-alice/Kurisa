package core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** BE-B6 门禁：内存后端/扇出/前缀装饰（纯逻辑，三端可跑）。 */
class AppLogTest {

    @Test
    fun inMemoryKeepsEntriesAndDropsOldest() {
        var now = 1000L
        val log = InMemoryAppLog(capacity = 2, nowMs = { now++ })
        log.d("a", "one")
        log.w("b", "two")
        log.e("c", "three", IllegalStateException("boom"))
        val entries = log.entries()
        assertEquals(2, entries.size)
        assertEquals("W", entries[0].level)
        assertEquals("two", entries[0].message)
        assertEquals("E", entries[1].level)
        assertTrue(entries[1].throwable!!.contains("IllegalStateException"))
        assertTrue(entries[1].throwable!!.contains("boom"))
        assertEquals(1001L, entries[0].atEpochMs)
    }

    @Test
    fun noopNeverThrows() {
        NoopAppLog.d("t", "m")
        NoopAppLog.w("t", "m")
        NoopAppLog.e("t", "m", RuntimeException("x"))
        assertTrue(true)
    }

    @Test
    fun multiplexFansOutAndRethrowsFirst() {
        val good = InMemoryAppLog()
        var second = 0
        val bad = object : AppLog {
            override fun d(tag: String, message: String) = throw IllegalStateException("bad")
            override fun w(tag: String, message: String) { second++ }
            override fun e(tag: String, message: String, throwable: Throwable?) { second++ }
        }
        val mux = MultiplexAppLog(good, bad)
        assertFailsWith<IllegalStateException> { mux.d("t", "m") }
        // 坏后端抛了，但排在前面的好后端仍收到（不饿死其余后端，也不吞异常）。
        assertEquals(1, good.entries().size)
        mux.w("t", "m")
        assertEquals(1, second)
    }

    @Test
    fun withPrefixDecoratesAllLevels() {
        val mem = InMemoryAppLog()
        val log = mem.withPrefix("net")
        log.d("send", "m")
        log.w("retry", "m")
        log.e("fail", "m", null)
        assertEquals(
            listOf("net/send", "net/retry", "net/fail"),
            mem.entries().map { it.tag },
        )
    }

    @Test
    fun rejectsBadCapacity() {
        var failed = false
        runCatching { InMemoryAppLog(0) }.onFailure { failed = true }
        assertTrue(failed)
    }
}
