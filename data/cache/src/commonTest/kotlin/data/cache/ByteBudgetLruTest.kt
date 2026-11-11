package data.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ByteBudgetLruTest {

    @Test
    fun evictsEldestOverBudget() {
        val c = ByteBudgetLru<String>(100)
        c.put("a", "a", 60)
        c.put("b", "b", 60)
        assertNull(c.get("a"))
        assertEquals("b", c.get("b"))
        assertEquals(60, c.used())
    }

    @Test
    fun accessRefreshes() {
        val c = ByteBudgetLru<String>(100)
        c.put("a", "a", 60)
        c.put("b", "b", 30)
        c.get("a")
        c.put("c", "c", 40) // 60+30+40=130 → 淘汰 b（a 刚被访问）
        assertEquals("a", c.get("a"))
        assertNull(c.get("b"))
    }
}
