package app.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import core.designsystem.AppTheme
import data.localimport.LocalImageFetcher
import data.localimport.LocalLibrary
import data.localimport.LocalSourceFileSystem
import data.localimport.localWorkId
import feature.detail.DetailChapterUi
import feature.detail.DetailContent
import feature.detail.DetailUiState
import feature.reader.InMemoryReaderSettingsStore
import feature.reader.LoadPageBytesUseCase
import feature.reader.LoadReadingProgressUseCase
import feature.reader.NoopProgressRepository
import feature.reader.ReaderScreenVm
import feature.reader.ReaderViewModel
import feature.reader.SaveReadingProgressUseCase
import feature.shelf.ShelfScreen
import feature.shelf.ShelfWorkUi
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 本地漫画端到端 walkthrough（S3 门禁）。
 *
 * 真机流程等价路径：选择目录 → 书架列出作品 → 详情列出章节 → 阅读器出图。
 * 素材是仓库内真实漫画库 `docs/sample-library`（Pepper & Carrot，CC-BY 4.0），
 * 复制到临时目录后由产品代码原样扫描——不构造任何替身数据。
 */
class LocalComicWalkthroughTest {

    @get:Rule
    val rule = createComposeRule()

    private enum class Screen { SHELF, DETAIL, READER }

    @Test
    fun localLibraryToReaderEndToEnd() {
        // ---- 1. 真实本地库（复制到临时目录，不动仓库）----
        val root = File.createTempFile("kurisa-local-lib", "").let {
            it.delete(); it.mkdirs(); it
        }
        WalkthroughSupport.stageSampleLibrary(root)

        val fs = LocalSourceFileSystem.fromDirectory(root.absolutePath)
        val library = LocalLibrary(fs)
        val works = runBlocking { library.works() }
        assertTrue(works.size >= 3, "应扫描到至少 3 部作品，实际：${works.map { it.title }}")

        // 每本都应有真实封面字节（真图，不是占位）。
        works.forEach { w ->
            assertNotNull(w.coverBytes, "《${w.title}》缺封面")
            assertTrue(w.coverBytes!!.size > 20_000, "《${w.title}》封面字节过小：${w.coverBytes!!.size}")
        }

        // 取 10 页那一话（CBZ 形态）。
        val first = works.first { it.title.contains("EP06") && !it.title.contains("目录版") }
        assertEquals(1, first.chapterCount, "EP06 是单话归档")

        val shelfWorks = works.map { w ->
            ShelfWorkUi(
                workId = w.workId,
                title = w.title,
                subtitle = "${w.chapterCount} 话",
                badge = "本地",
                coverBytes = w.coverBytes,
            )
        }

        val provider = library.chapterProvider()
        val workId = localWorkId(first.mangaName)
        val chapters = runBlocking { provider.chapters(workId) }
        assertTrue(chapters.isNotEmpty())
        // 章节名是给人看的：不应带 .cbz 扩展名。
        assertTrue(chapters.none { it.title.endsWith(".cbz", true) }, "章节标题仍含扩展名：${chapters.map { it.title }}")

        val images = LocalImageFetcher(fs)
        val vm = ReaderViewModel(
            workId = workId,
            chapterId = chapters.first().id,
            chapters = provider,
            saveProgress = SaveReadingProgressUseCase(NoopProgressRepository),
            loadProgress = LoadReadingProgressUseCase(NoopProgressRepository),
            pages = LoadPageBytesUseCase(images),
            domains = emptySet(),
            settingsStore = InMemoryReaderSettingsStore(),
            mangaTitle = first.title,
        )

        // ---- 2. 同一窗口内依次切换页面（书架 → 详情 → 阅读）----
        var screen by mutableStateOf(Screen.SHELF)
        var detailState by mutableStateOf(DetailUiState(loading = true))

        rule.setContent {
            AppTheme {
                when (screen) {
                    Screen.SHELF -> ShelfScreen(
                        works = shelfWorks,
                        localRootLabel = root.absolutePath,
                        onPickDirectory = {},
                        onOpenWork = {},
                        onOpenSearch = {},
                        onOpenSources = {},
                    )
                    Screen.DETAIL -> DetailContent(
                        state = detailState,
                        sourceKey = "",
                        onRetry = {},
                        onOpenChapter = {},
                        onBack = {},
                    )
                    Screen.READER -> ReaderScreenVm(workId = workId, onBack = {}, vm = vm)
                }
            }
        }
        rule.waitForIdle()

        // ---- 3. 书架：真封面 ----
        val shelfShot = rule.onRoot().captureToImage()
        WalkthroughSupport.save("comic-local-shelf.png", shelfShot)
        WalkthroughSupport.assertRendered(shelfShot, "本地书架")
        // 封面是真图 → 整图色彩必须远超色块级别。
        WalkthroughSupport.assertPhotoLike(shelfShot, "本地书架封面", minColors = 600)

        // ---- 4. 详情：真封面 + 真实章节 ----
        detailState = DetailUiState(
            title = first.title,
            description = "本地库作品，共 ${chapters.size} 话。",
            meta = "${chapters.size} 话 · 本地库",
            coverBytes = first.coverBytes,
            chapters = chapters.map { DetailChapterUi(it.id, it.title) },
            loading = false,
        )
        screen = Screen.DETAIL
        rule.waitForIdle()
        // 封面是 Coil 异步解码：必须推进测试时钟 + 真等 IO，否则截图里只有空占位框。
        rule.mainClock.advanceTimeBy(2_000)
        Thread.sleep(1_500)
        rule.waitForIdle()
        val detailShot = rule.onRoot().captureToImage()
        WalkthroughSupport.save("comic-local-detail.png", detailShot)
        WalkthroughSupport.assertPhotoLike(detailShot, "本地详情封面", minColors = 400)

        // ---- 5. 阅读器：真漫画页 ----
        screen = Screen.READER
        rule.waitUntil(30_000) { vm.state.value.pages.isNotEmpty() }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        // Coil 异步解码完成后再截图，否则只能抓到占位。
        Thread.sleep(2_000)
        rule.waitForIdle()
        val readerShot = rule.onRoot().captureToImage()
        WalkthroughSupport.save("comic-local-cbz.png", readerShot)
        // 这是本次的核心门禁：截图里必须是一张真实漫画页，而不是占位/色块。
        WalkthroughSupport.assertPhotoLike(readerShot, "本地阅读器漫画页", minColors = 1_500)

        val state = vm.state.value
        assertTrue(state.pageCount >= 9, "应加载整话页数，实际 ${state.pageCount}")
        // 分页器只解码当前页与预取邻页，不要求全话同时在内存（10 页全解会浪费）。
        assertTrue(state.pages.isNotEmpty(), "至少当前页应解码出字节")
        assertTrue(state.pages.keys.max() >= 0)
        // 真实 JPEG 单页 ≈ 200KB；单页字节量足以区分真图与占位。
        val totalBytes = state.pages.values.sumOf { it.size }
        assertTrue(totalBytes > 400_000, "页面字节总量过小：$totalBytes")
    }
}
