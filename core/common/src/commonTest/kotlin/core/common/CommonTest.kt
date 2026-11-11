package core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommonTest {

    @Test
    fun retrySucceedsAfterFailures() = runTest {
        var n = 0
        val v = retryWithBackoff(maxAttempts = 3, baseDelayMs = 1) {
            n++
            if (n < 3) throw IllegalStateException("boom")
            "ok"
        }
        assertEquals("ok", v)
        assertEquals(3, n)
    }

    @Test
    fun retryGivesUp() = runTest {
        var n = 0
        assertFailsWith<IllegalStateException> {
            retryWithBackoff(maxAttempts = 2, baseDelayMs = 1) {
                n++
                throw IllegalStateException("always")
            }
        }
        assertEquals(2, n)
    }

    @Test
    fun noopLogNeverThrows() {
        NoopAppLog.d("t", "m")
        NoopAppLog.e("t", "m", RuntimeException("x"))
        assertTrue(true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dispatchersUsable() = runTest {
        val v = ioRun { 41 + 1 }
        assertEquals(42, v)
    }

    @Test
    fun debouncerCoalescesRapidSubmits() = runTest {
        var runs = 0
        var last = ""
        val d = Debouncer(this, 500) { runs++; last = "run$runs" }
        d.submit()
        d.submit()
        d.submit()
        advanceTimeBy(499)
        assertEquals(0, runs)
        // 注意：本工程 advanceTimeBy 语义为“推过期限才触发恰到期任务”，
        // 断言防抖必须越过 500ms 边界（499+1 不够，+5 稳）。
        advanceTimeBy(5)
        assertEquals(1, runs)
        assertEquals("run1", last)
    }

    @Test
    fun debouncerCancelSuppresses() = runTest {
        var runs = 0
        val d = Debouncer(this, 500) { runs++ }
        d.submit()
        d.cancel()
        advanceTimeBy(1000)
        assertEquals(0, runs)
    }
}
