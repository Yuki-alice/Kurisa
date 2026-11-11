package core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
