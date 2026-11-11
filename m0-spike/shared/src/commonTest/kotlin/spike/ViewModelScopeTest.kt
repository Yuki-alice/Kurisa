package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Navigation N5：ViewModel 作用域（commonTest，三端一致）。
 * 规则（ADR-008）：同一目的地旋转/resize 不重建、StateFlow 不丢；跨目的地隔离；
 * 返回即销毁。真实 NavDisplay 集成在 M1 :app:shared-ui 做，这里先锁语义。
 */
class ViewModelScopeTest {

    class FakeVm(val key: String) {
        var state: Int = 0
        var destroyed: Boolean = false
        fun destroy() { destroyed = true }
    }

    class ScopeRegistry {
        private val map = mutableMapOf<String, FakeVm>()
        fun vmFor(route: String): FakeVm = map.getOrPut(route) { FakeVm(route) }
        fun onBack(route: String) { map.remove(route)?.destroy() }
        fun size(): Int = map.size
    }

    @Test
    fun sameDestinationSurvivesResize() {
        val reg = ScopeRegistry()
        val a = reg.vmFor("Detail(w1)")
        a.state = 7
        // 旋转/resize：同一 route 取回同一实例，State 不丢
        val b = reg.vmFor("Detail(w1)")
        assertTrue(a === b)
        assertEquals(7, b.state)
    }

    @Test
    fun crossDestinationIsolated() {
        val reg = ScopeRegistry()
        val a = reg.vmFor("Detail(w1)")
        val b = reg.vmFor("Detail(w2)")
        assertTrue(a !== b)
        assertEquals(2, reg.size())
    }

    @Test
    fun backDestroys() {
        val reg = ScopeRegistry()
        val a = reg.vmFor("Detail(w1)")
        reg.vmFor("Reader(w1,c1)")
        assertEquals(2, reg.size())
        reg.onBack("Reader(w1,c1)")
        assertEquals(1, reg.size())
        assertTrue(a.destroyed == false)
        reg.onBack("Detail(w1)")
        assertTrue(a.destroyed)
    }
}
