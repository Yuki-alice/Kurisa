package feature.reader

import core.model.ChapterPayload
import core.model.ReadingProgress
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ImageScaleType
import core.reader.ReaderPageRequest
import core.reader.ReaderSettingsStore
import core.reader.ReaderStateMachine
import core.reader.ReadingDirection
import core.reader.ReadingMode
import data.images.FetchedImage
import data.images.ImageFetcher
import data.images.ImageRequest
import data.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelEnhancementTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Provider : ChapterProvider {
        override suspend fun chapters(workId: String) =
            listOf(ChapterRef("c1", "第1话", 1), ChapterRef("c2", "第2话", 2))

        override suspend fun payload(workId: String, chapterId: String) =
            ChapterPayload.Images(listOf("u1", "u2", "u3"))

        override suspend fun resolvePage(
            workId: String,
            chapterId: String,
            url: String,
        ) = ReaderPageRequest(
            url = url,
            rewriteUrl = "https://cdn.example.com/$url",
            headers = mapOf("Referer" to "https://example.com/"),
            maxRetries = 2,
        )
    }

    private class CaptureImages(
        private val failUrl: String? = null,
    ) : ImageFetcher {
        val requests = mutableListOf<ImageRequest>()
        var fail = failUrl != null

        override suspend fun fetch(request: ImageRequest): FetchedImage {
            requests += request
            if (fail && request.url.endsWith(failUrl!!)) {
                throw IllegalStateException("boom")
            }
            return FetchedImage(request.url.toByteArray(), request.url)
        }
    }

    private class Progress : ProgressRepository {
        override suspend fun save(progress: ReadingProgress) = Unit
        override suspend fun load(workId: String): ReadingProgress? = null
    }

    private fun vm(
        images: ImageFetcher,
        settings: ReaderSettingsStore = InMemoryReaderSettingsStore(),
        nowMs: () -> Long = { 1_700_000_000_000L },
    ) = ReaderViewModel(
        workId = "w",
        chapterId = "c1",
        machine = ReaderStateMachine(),
        chapters = Provider(),
        saveProgress = SaveReadingProgressUseCase(Progress()),
        loadProgress = LoadReadingProgressUseCase(Progress()),
        pages = LoadPageBytesUseCase(images),
        domains = setOf("cdn.example.com"),
        workScope = kotlinx.coroutines.CoroutineScope(dispatcher),
        settingsStore = settings,
        nowMs = nowMs,
    )

    @Test
    fun pageRequestCarriesResolvedUrlAndHeaders() = runTest(dispatcher) {
        val images = CaptureImages()
        val model = vm(images)
        backgroundScope.launch { model.state.collect {} }
        runCurrent()

        assertEquals(3, images.requests.size)
        val first = images.requests.first()
        assertEquals("https://cdn.example.com/u1", first.url)
        assertEquals("https://example.com/", first.headers["Referer"])
        assertEquals(2, first.config?.maxRetries)
    }

    @Test
    fun retryPageReloadsOnlyFailedPage() = runTest(dispatcher) {
        val images = CaptureImages(failUrl = "u2")
        val model = vm(images)
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()

        assertTrue(states.last().pageStates.containsKey(1))
        images.fail = false
        model.onIntent(ReaderContract.Intent.RetryPage(1))
        runCurrent()

        val last = states.last()
        assertNotNull(last.pages[1])
        assertTrue(!last.pageStates.containsKey(1))
    }

    @Test
    fun settingsIntentsUpdateState() = runTest(dispatcher) {
        val store = InMemoryReaderSettingsStore()
        val model = vm(CaptureImages(), settings = store)
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()

        model.onIntent(ReaderContract.Intent.ModeChanged(ReadingMode.CONTINUOUS))
        model.onIntent(ReaderContract.Intent.DirectionChanged(ReadingDirection.RTL))
        model.onIntent(ReaderContract.Intent.ScaleChanged(ImageScaleType.FIT_WIDTH))
        model.onIntent(ReaderContract.Intent.ToggleGrayscale)
        runCurrent()

        val settings = states.last().settings
        assertEquals(ReadingMode.CONTINUOUS, settings.mode)
        assertEquals(ReadingDirection.RTL, settings.direction)
        assertEquals(ImageScaleType.FIT_WIDTH, settings.scaleType)
        assertTrue(settings.grayscale)
    }

    @Test
    fun menuAndSheetsAreExclusive() = runTest(dispatcher) {
        val model = vm(CaptureImages())
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()

        model.onIntent(ReaderContract.Intent.ToggleMenu)
        runCurrent()
        assertTrue(states.last().menuVisible)

        model.onIntent(ReaderContract.Intent.OpenChapterList)
        runCurrent()
        assertTrue(states.last().chapterListVisible)
        assertTrue(!states.last().menuVisible)

        model.onIntent(ReaderContract.Intent.CloseChapterList)
        model.onIntent(ReaderContract.Intent.OpenSettings)
        runCurrent()
        assertTrue(states.last().settingsVisible)
    }
}
