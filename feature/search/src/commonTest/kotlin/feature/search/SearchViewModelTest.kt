package feature.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 搜索 ViewModel 单测（BE-A5 收口回归）。
 *
 * 调度器纪律同 `ReaderViewModelTest`：`setMain` 与 `runTest(dispatcher)` 同源，
 * 不引入 Turbine；断言只看 StateFlow 快照。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun newVm(
        search: suspend (sourceKey: String, keyword: String, page: Int) -> SearchPageUi,
    ) = SearchViewModel(SearchDeps(listOf(SearchSourceUi("src.example", "示例源")), search))

    @Test
    fun submitLoadsFirstPageWithTrimmedKeyword() = runTest(dispatcher) {
        var seen: Triple<String, String, Int>? = null
        val vm = newVm { key, keyword, page ->
            seen = Triple(key, keyword, page)
            SearchPageUi(listOf(SearchResultUi("w1", "作品一")), maxPage = 3)
        }
        vm.onQueryChange("  关键词  ")
        vm.submit()
        advanceUntilIdle()

        assertEquals(Triple("src.example", "关键词", 1), seen)
        val state = vm.state.value
        assertEquals(listOf("w1"), state.results.map { it.workId })
        assertEquals(3, state.maxPage)
        assertEquals(1, state.page)
        assertTrue(state.searched)
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test
    fun blankQueryDoesNotCallSource() = runTest(dispatcher) {
        var calls = 0
        val vm = newVm { _, _, _ ->
            calls++
            SearchPageUi()
        }
        vm.onQueryChange("   ")
        vm.submit()
        advanceUntilIdle()
        assertEquals(0, calls)
        assertFalse(vm.state.value.searched)
    }

    @Test
    fun failureSurfacesErrorAndClearsLoading() = runTest(dispatcher) {
        val vm = newVm { _, _, _ -> throw IllegalStateException("源执行失败") }
        vm.onQueryChange("关键词")
        vm.submit()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals("源执行失败", state.error)
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun pagingStopsAtMaxPage() = runTest(dispatcher) {
        val pages = mutableListOf<Int>()
        val vm = newVm { _, _, page ->
            pages += page
            SearchPageUi(listOf(SearchResultUi("w$page", "第$page 页")), maxPage = 2)
        }
        vm.onQueryChange("关键词")
        vm.submit()
        advanceUntilIdle()
        vm.nextPage()
        advanceUntilIdle()
        vm.nextPage()
        advanceUntilIdle()
        vm.prevPage()
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 1), pages)
        assertEquals(1, vm.state.value.page)
    }

    @Test
    fun selectSourceSwitchesActiveSource() = runTest(dispatcher) {
        var seenKey: String? = null
        val vm = newVm { key, _, _ ->
            seenKey = key
            SearchPageUi()
        }
        vm.selectSource("src.example")
        vm.onQueryChange("关键词")
        vm.submit()
        advanceUntilIdle()
        assertEquals("src.example", seenKey)
    }
}
