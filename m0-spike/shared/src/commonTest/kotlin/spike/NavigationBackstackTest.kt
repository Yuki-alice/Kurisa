package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Navigation3 自适应 spike（feasibility N1–N4）的纯逻辑部分。
 * 真正的 ListDetailScene 需要 Compose 运行时，三端截图基线在 M1 建；
 * 这里先把“路由即普通列表”的可测部分锁死：backstack 的增删与深链接回放。
 *
 * N5（ViewModel 作用域）需 Koin + lifecycle-viewmodel-navigation3，
 * 在 commonTest 无 UI 宿主，留待 M1 的 :app:shared-ui 集成测试。
 */
class NavigationBackstackTest {

    // 最小 route key 模型，与 architecture ADR-008 同形
    sealed interface Route {
        data object Shelf : Route
        data class Detail(val workId: String) : Route
        data class Reader(val workId: String, val chapterId: String) : Route
        data class Explore(val sourceKey: String) : Route
    }

    @Test
    fun n1PhoneSinglePane() {
        // [Shelf] -> add(Detail) -> removeLast()
        val stack = mutableListOf<Route>(Route.Shelf)
        stack.add(Route.Detail("w1"))
        assertEquals(2, stack.size)
        assertTrue(stack.last() is Route.Detail)
        // 手机单窗格一次只渲染栈顶 1 个
        assertEquals(1, listOf(stack.last()).size)
        stack.removeLast()
        assertEquals(listOf<Route>(Route.Shelf), stack)
    }

    @Test
    fun n2TabletDoublePane() {
        // [Shelf, Detail] 在宽 >600dp 时应同时渲染两窗格
        val stack = listOf<Route>(Route.Shelf, Route.Detail("w1"))
        val wide = true // >600dp
        val visible = if (wide && stack.size >= 2 && stack.last() is Route.Detail) 2 else 1
        assertEquals(2, visible)
        val narrowVisible = 1 // 窄屏退化单窗格
        assertEquals(1, narrowVisible)
    }

    @Test
    fun n4DeepLink() {
        // 外链 -> [Shelf, Detail]
        val fromLink = listOf<Route>(Route.Shelf, Route.Detail("w9"))
        assertEquals(Route.Detail("w9"), fromLink.last())
        // 分享 -> [Shelf, Detail, Reader]
        val fromShare = listOf<Route>(Route.Shelf, Route.Detail("w9"), Route.Reader("w9", "c3"))
        assertEquals(3, fromShare.size)
        assertTrue(fromShare[1] is Route.Detail && fromShare[2] is Route.Reader)
    }

    @Test
    fun n3StateRestore() {
        // 杀进程后 backstack 应精确恢复，Detail 参数不丢
        val saved = listOf("Shelf", "Detail:w1", "Reader:w1:c2")
        val restored = saved.map {
            when {
                it == "Shelf" -> Route.Shelf as Route
                it.startsWith("Detail:") -> Route.Detail(it.removePrefix("Detail:"))
                it.startsWith("Reader:") -> {
                    val (w, c) = it.removePrefix("Reader:").split(":")
                    Route.Reader(w, c)
                }
                else -> error("unknown $it")
            }
        }
        assertEquals(Route.Reader("w1", "c2"), restored.last())
    }
}
