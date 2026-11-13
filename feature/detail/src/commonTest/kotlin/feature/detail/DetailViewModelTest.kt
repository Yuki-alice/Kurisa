package feature.detail

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
 * 详情 ViewModel 单测（BE-A5 收口回归）：装载结果、标题兜底、失败态、重试。
 *
 * `DetailViewModel.init` 会立即刷新，因此每个用例都要先 `advanceUntilIdle()`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsChaptersAndMeta() = runTest(dispatcher) {
        val vm = DetailViewModel(
            DetailDeps("路由标题") {
                DetailData(
                    title = "真实标题",
                    description = "简介",
                    meta = "2 话",
                    chapters = listOf(DetailChapterUi("c1", "第1话"), DetailChapterUi("c2", "第2话")),
                )
            },
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("真实标题", state.title)
        assertEquals("2 话", state.meta)
        assertEquals(listOf("c1", "c2"), state.chapters.map { it.id })
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test
    fun fallsBackToRouteTitleWhenLoaderTitleBlank() = runTest(dispatcher) {
        val vm = DetailViewModel(DetailDeps("路由标题") { DetailData(title = "  ") })
        advanceUntilIdle()
        assertEquals("路由标题", vm.state.value.title)
    }

    @Test
    fun failureSetsErrorAndKeepsLoadingFalse() = runTest(dispatcher) {
        val vm = DetailViewModel(DetailDeps("路由标题") { throw IllegalStateException("目录缺失") })
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals("目录缺失", state.error)
        assertEquals("路由标题", state.title)
    }

    @Test
    fun refreshRetriesAfterFailure() = runTest(dispatcher) {
        var attempt = 0
        val vm = DetailViewModel(
            DetailDeps("路由标题") {
                attempt++
                if (attempt == 1) throw IllegalStateException("首次失败")
                DetailData(title = "恢复标题", chapters = listOf(DetailChapterUi("c1", "第1话")))
            },
        )
        advanceUntilIdle()
        assertEquals("首次失败", vm.state.value.error)

        vm.refresh()
        advanceUntilIdle()

        val state = vm.state.value
        assertNull(state.error)
        assertEquals("恢复标题", state.title)
        assertTrue(state.chapters.isNotEmpty())
    }
}
