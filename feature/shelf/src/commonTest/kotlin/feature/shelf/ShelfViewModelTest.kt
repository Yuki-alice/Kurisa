package feature.shelf

import core.model.LocalBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 书架状态机单测（thin ViewModel：两个上游流合并为一个 StateFlow）。
 * 调度器纪律与 ReaderViewModelTest 一致：setMain 与 runTest 共享同一 StandardTestDispatcher。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun book(id: String, title: String) = LocalBook(
        id = id,
        path = title,
        format = "epub",
        title = title,
        importedAtEpochMs = 1L,
    )

    @Test
    fun combinesOnlineAndLocalIntoOneState() = runTest(dispatcher) {
        val online = MutableStateFlow(
            listOf(ShelfWorkUi(workId = "w1", sourceKey = "ex.a", title = "A", cover = "https://a.example/c.jpg")),
        )
        val local = MutableStateFlow(listOf(book("local:L", "本地书")))

        val vm = ShelfViewModel(online, local)
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(listOf("w1"), state.works.map { it.workId })
        assertEquals("ex.a", state.works.single().sourceKey)
        assertEquals(listOf("local:L"), state.localBooks.map { it.workId })
        assertEquals("epub", state.localBooks.single().format)
    }

    @Test
    fun upstreamFailureSurfacesErrorState() = runTest(dispatcher) {
        val broken = flow<List<ShelfWorkUi>> { throw IllegalStateException("db 损坏") }

        val vm = ShelfViewModel(broken, MutableStateFlow(emptyList()))
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals("db 损坏", state.error)
    }

    @Test
    fun emptySourcesProduceNonLoadingEmptyState() = runTest(dispatcher) {
        val vm = ShelfViewModel(MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(emptyList(), state.works)
        assertEquals(emptyList(), state.localBooks)
        assertEquals(null, state.error)
    }
}
