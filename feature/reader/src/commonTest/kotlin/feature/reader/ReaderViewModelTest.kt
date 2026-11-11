package feature.reader

import core.model.ChapterPayload
import core.model.ReadingProgress
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ReaderStateMachine
import core.reader.ReadingMode
import data.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ViewModel 单测（runTest + 后台收集；不用 Turbine）。
 *
 * 调度器纪律：
 * - 共享 dispatcher（setMain + runTest(dispatcher) 同源）；
 * - 无限收集器一律走 backgroundScope；
 * - 防抖 saveScope 传 backgroundScope；断言防抖必须推过期限（499/+5），
 *   恰到期 advance 不触发本工程的虚拟 delay（M3 Slice A 实录）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    class FakeProvider : ChapterProvider {
        val chaptersList = listOf(ChapterRef("c1", "第1话", 1), ChapterRef("c2", "第2话", 2))
        override suspend fun chapters(workId: String) = chaptersList
        override suspend fun payload(workId: String, chapterId: String) =
            ChapterPayload.Images(listOf("u1", "u2", "u3"))
    }

    class FakeProgress : ProgressRepository {
        val saved = mutableListOf<ReadingProgress>()
        var stored: ReadingProgress? = null
        override suspend fun save(progress: ReadingProgress) {
            saved += progress
            stored = progress
        }

        override suspend fun load(workId: String): ReadingProgress? = stored
    }

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        progress: FakeProgress = FakeProgress(),
        chapterId: String = "c1",
        saveScope: kotlinx.coroutines.CoroutineScope? = null,
    ) = ReaderViewModel(
        workId = "w",
        chapterId = chapterId,
        machine = ReaderStateMachine(),
        chapters = FakeProvider(),
        saveProgress = SaveReadingProgressUseCase(progress),
        loadProgress = LoadReadingProgressUseCase(progress),
        saveScope = saveScope,
    )

    @Test
    fun openRestoresProgress() = runTest(dispatcher) {
        val progress = FakeProgress().apply {
            stored = ReadingProgress("w", "c1", 2, null, 0.9f, 1L, false)
        }
        val m = vm(progress, saveScope = backgroundScope)
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { m.state.collect { states += it } }
        runCurrent()
        assertEquals(2, states.last().pageIndex)
        assertEquals(3, states.last().pageCount)
    }

    @Test
    fun pageChangeDebouncesSave() = runTest(dispatcher) {
        val progress = FakeProgress()
        val m = vm(progress, saveScope = backgroundScope)
        backgroundScope.launch { m.state.collect {} }
        runCurrent()
        val initialSaves = progress.saved.size
        m.onIntent(ReaderContract.Intent.PageChanged(1))
        m.onIntent(ReaderContract.Intent.PageChanged(2))
        advanceTimeBy(499)
        assertEquals(initialSaves, progress.saved.size)
        advanceTimeBy(5)
        assertEquals(initialSaves + 1, progress.saved.size)
        assertEquals(2, progress.saved.last().pageIndex)
        assertEquals(1f, progress.saved.last().percent)
    }

    @Test
    fun boundsToastOnce() = runTest(dispatcher) {
        val m = vm()
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { m.state.collect { states += it } }
        val effects = mutableListOf<ReaderContract.Effect>()
        backgroundScope.launch { m.effect.collect { effects += it } }
        runCurrent()
        m.onIntent(ReaderContract.Intent.PrevPage) // 已在第 0 页
        runCurrent()
        assertTrue(effects.filterIsInstance<ReaderContract.Effect.Toast>().isNotEmpty())
        // 切到末章再 next：ChapterEnd → reachedEnd + Toast
        m.onIntent(ReaderContract.Intent.NextChapter)
        runCurrent()
        m.onIntent(ReaderContract.Intent.NextChapter)
        runCurrent()
        assertTrue(states.last().reachedEnd)
        assertTrue(effects.filterIsInstance<ReaderContract.Effect.Toast>().size >= 2)
    }

    @Test
    fun modeSwitch() = runTest(dispatcher) {
        val m = vm()
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { m.state.collect { states += it } }
        runCurrent()
        m.onIntent(ReaderContract.Intent.ModeChanged(ReadingMode.CONTINUOUS))
        runCurrent()
        assertEquals(ReadingMode.CONTINUOUS, states.last().mode)
    }
}
