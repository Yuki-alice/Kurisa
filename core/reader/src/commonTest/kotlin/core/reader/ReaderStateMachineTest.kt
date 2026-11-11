package core.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderStateMachineTest {

    @Test
    fun idleToReading() {
        val m = ReaderStateMachine()
        assertEquals(ReaderState.Idle, m.state.value)
        m.resolve("w", "c")
        m.loading("w", "c")
        m.ready("w", "c", 10)
        m.pageTo(3)
        val s = m.state.value
        assertTrue(s is ReaderState.Reading)
        assertEquals(3, s.pageIndex)
    }

    @Test
    fun pageIgnoredOutsideReading() {
        val m = ReaderStateMachine()
        m.pageTo(5)
        assertEquals(ReaderState.Idle, m.state.value)
        m.fail("net")
        assertTrue(m.state.value is ReaderState.Error)
        m.reset()
        assertEquals(ReaderState.Idle, m.state.value)
    }
}
