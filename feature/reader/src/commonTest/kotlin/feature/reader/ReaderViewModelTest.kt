package feature.reader

import core.model.ChapterPayload
import core.model.ReadingProgress
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ReaderStateMachine
import core.reader.ReadingMode
import data.images.ImageFetcher
import data.images.ImageRequest
import data.images.FetchedImage
import data.repository.ProgressRepository
import kotlinx.coroutines.CompletableDeferred
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

/** ASCII 编解码（测试 URL 全 ASCII；Native 下不用碰 Charsets）。 */
private fun String.toAsciiBytes(): ByteArray {
    require(all { it.code < 128 })
    return ByteArray(length) { this[it].code.toByte() }
}

private fun ByteArray.asciiString(): String {
    require(all { it.toInt() and 0x80 == 0 })
    return CharArray(size) { this[it].toInt().toChar() }.concatToString()
}

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

    /** 可门控抓取器（-nil gate 为直通；stale 丢弃测试用 gate 挂起）。 */
    class FakeImages(
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ImageFetcher {
        val calls = mutableListOf<String>()
        override suspend fun fetch(request: ImageRequest): FetchedImage {
            calls += request.url
            gate?.await()
            return FetchedImage(
                ("bytes:" + request.url).toAsciiBytes(),
                request.url,
            )
        }
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
        images: ImageFetcher = FakeImages(),
        saveScope: kotlinx.coroutines.CoroutineScope? = null,
    ) = ReaderViewModel(
        workId = "w",
        chapterId = chapterId,
        machine = ReaderStateMachine(),
        chapters = FakeProvider(),
        saveProgress = SaveReadingProgressUseCase(progress),
        loadProgress = LoadReadingProgressUseCase(progress),
        pages = LoadPageBytesUseCase(images),
        domains = setOf("example.com"),
        workScope = saveScope,
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

    @Test
    fun windowFillsAndDrops() = runTest(dispatcher) {
        val images = FakeImages()
        val m = ReaderViewModel(
            workId = "w", chapterId = "c1", machine = ReaderStateMachine(),
            chapters = FakeProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(images),
            domains = setOf("example.com"),
            workScope = backgroundScope,
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { m.state.collect { states += it } }
        runCurrent()
        // 打开即填满窗口 [0..2]（3 页全在窗内）。
        assertEquals(setOf(0, 1, 2), states.last().pages.keys)
        assertEquals(listOf("u1", "u2", "u3"), images.calls.sorted())
        // 切章：窗外字节驱逐，新窗重填。
        m.onIntent(ReaderContract.Intent.NextChapter)
        runCurrent()
        val last = states.last()
        assertTrue(last.pages.keys.all { it in 0..2 })
        assertTrue(images.calls.size >= 3)
    }

    @Test
    fun staleFetchDropped() = runTest(dispatcher) {
        val chapterUrls = mapOf(
            "c1" to listOf("c1u1", "c1u2", "c1u3"),
            "c2" to listOf("c2u1", "c2u2", "c2u3"),
        )
        val chapterProvider = object : ChapterProvider {
            override suspend fun chapters(workId: String) =
                listOf(ChapterRef("c1", "第1话", 1), ChapterRef("c2", "第2话", 2))

            override suspend fun payload(workId: String, chapterId: String) =
                ChapterPayload.Images(chapterUrls.getValue(chapterId))
        }
        val gate = CompletableDeferred<Unit>()
        val images = FakeImages(gate)
        val m = ReaderViewModel(
            workId = "w", chapterId = "c1", machine = ReaderStateMachine(),
            chapters = chapterProvider,
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(images),
            domains = setOf("example.com"),
            workScope = backgroundScope,
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { m.state.collect { states += it } }
        runCurrent()
        // c1 窗口抓取挂起中 → 切 c2（版本推进）→ 放行：c1 结果必须丢弃。
        assertTrue(states.last().pages.isEmpty())
        m.onIntent(ReaderContract.Intent.NextChapter)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        val got = states.last().pages.values.map { it.asciiString() }
        assertTrue(got.none { it.contains("c1u") }, "stale c1 bytes leaked: $got")
        assertTrue(got.any { it.contains("c2u") }, "c2 window missing: $got")
    }
}
