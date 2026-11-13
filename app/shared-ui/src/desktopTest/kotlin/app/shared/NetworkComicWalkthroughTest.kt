package app.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.designsystem.AppTheme
import core.model.ChapterPayload
import data.db.ReaderDatabase
import data.images.SourceImageFetcher
import data.network.CookieStore
import data.network.SourceHttpClient
import feature.detail.DetailChapterUi
import feature.sources.InstallOutcomeUi
import feature.detail.DetailContent
import feature.detail.DetailUiState
import feature.reader.InMemoryReaderSettingsStore
import feature.reader.LoadPageBytesUseCase
import feature.reader.LoadReadingProgressUseCase
import feature.reader.NoopProgressRepository
import feature.reader.ReaderScreenVm
import feature.reader.ReaderViewModel
import feature.reader.SaveReadingProgressUseCase
import feature.search.SearchContent
import feature.search.SearchResultUi
import feature.search.SearchSourceUi
import feature.search.SearchUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 网络漫画端到端 walkthrough（S3 门禁）。
 *
 * 真实链路：安装源（头块 → 静态扫描 → 落盘/入库）→ 搜索 → 详情 → 章节 → 图片出口。
 * 网络全部由 MockEngine 拦截（`example.com` 合成站点，C1），不访问任何真实站点。
 * 三个页面在同一窗口内依次切换并截图，作为「装源 → 搜索 → 详情 → 出图」的证据。
 */
class NetworkComicWalkthroughTest {

    @get:Rule
    val rule = createComposeRule()

    private enum class Screen { SEARCH, DETAIL, READER }

    private val domains = setOf("manga.example.com", "cdn.example.com")

    /** 从内页 URL 里取页号（`.../p3.jpg` → 3）。 */
    private val PAGE_INDEX = Regex("p([0-9]+)\\.jpg")

    @Test
    fun installSourceThenSearchDetailRead() {
        val script = File(WalkthroughSupport.repoRoot, "docs/contract/manga-example.js").readText()
        assertTrue(script.contains("demo.manga"), "示例源应含 key")

        // 真实漫画页作为 CDN 响应：网络链路也必须证明「能出真图」，不是能出字节。
        val ep06 = WalkthroughSupport.cbzPages(WalkthroughSupport.cbzOf("Pepper & Carrot EP06"))
        val ep08 = WalkthroughSupport.cbzPages(WalkthroughSupport.cbzOf("Pepper & Carrot EP08"))
        assertTrue(ep06.size >= 8, "EP06 应有至少 8 页，实际 ${ep06.size}")

        val listHtml = """
            <html><body>
              <div class="w">Pepper &amp; Carrot EP06</div>
              <div class="w">Pepper &amp; Carrot EP08</div>
            </body></html>
        """.trimIndent()
        val detailHtml = "<html><body><h1>Pepper &amp; Carrot EP06</h1></body></html>"

        var searchHits = 0
        var imageHits = 0
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("manga.example.com/s") -> {
                    searchHits++
                    respond(
                        listHtml,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
                    )
                }
                url.contains("manga.example.com/m/") -> respond(
                    detailHtml,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
                )
                url.contains("cdn.example.com") -> {
                    imageHits++
                    // 封面走 /cover/N.jpg，内页走 /w1/c1/pN.jpg，各映射到不同真实页面。
                    val bytes = when {
                        url.contains("/cover/2") -> ep08.first()
                        url.contains("/cover/") -> ep06.getOrNull(1) ?: ep06.first()
                        else -> {
                            val n = PAGE_INDEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                            ep06.getOrNull(n - 1) ?: ep06.first()
                        }
                    }
                    respond(
                        bytes,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "image/jpeg"),
                    )
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        // MockEngine 直接返回文本/字节，不需要 ContentNegotiation；
        // followRedirects 必须关闭，出口自行逐跳复核（UrlPolicy）。
        val http = HttpClient(engine) { followRedirects = false }

        // ---- 1. 内存库 + 安装源（真实安装编排）----
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        val db = ReaderDatabase(driver)
        val cookies = CookieStore()
        val catalog = createSourceCatalog(
            db = db,
            httpClient = http,
            cookies = cookies,
            loadScript = { script },
            appVersion = DEFAULT_APP_VERSION,
            nowMs = { 1_700_000_000_000L },
        )
        val installDir = File.createTempFile("kurisa-src", "").let { f -> f.delete(); f.mkdirs(); f }
        val outcome = runBlocking {
            catalog.installSource(
                script = script,
                saveScript = { key, version, content, format ->
                    val f = File(File(installDir, key), "$version.$format")
                    f.parentFile.mkdirs()
                    f.writeText(content)
                    f.absolutePath
                },
                confirmed = true,
            )
        }
        assertTrue(
            outcome is InstallOutcomeUi.Installed,
            "示例源应安装成功（B6 回归）：$outcome",
        )
        assertEquals(listOf("demo.manga"), catalog.snapshotUi().map { it.key })

        // ---- 2. 搜索 / 详情 / 章节 / 载荷（引擎真实调用）----
        val page = runBlocking { catalog.search("demo.manga", "星", 1) }
        assertEquals(2, page.works.size, "应返回 2 个作品：${page.works}")
        assertEquals("Pepper & Carrot EP06", page.works.first().title)

        val info = runBlocking { catalog.workInfo("demo.manga", "w1") }
        assertEquals("Pepper & Carrot EP06", info.title)

        val provider = catalog.providerFor("demo.manga")
        val chapters = runBlocking { provider.chapters("w1") }
        assertEquals(1, chapters.size)
        assertEquals("第1话", chapters.single().title)

        val payload = runBlocking { provider.payload("w1", chapters.single().id) }
        assertTrue(payload is ChapterPayload.Images)
        assertEquals(8, (payload as ChapterPayload.Images).urls.size, "示例源应返回 8 页")
        assertTrue(searchHits >= 1, "搜索应发出请求")

        // ---- 3. 三页在同一窗口内切换并截图 ----
        // 搜索页封面走和详情同一条图片出口（真实字节，不是 URL 直连）。
        val doms = domains
        val fetcher = SourceImageFetcher(SourceHttpClient(http, cookies) { 0L })
        val coverOf: (String?) -> ByteArray? = { url ->
            url?.let { runBlocking { runCatching { fetcher.fetch(data.images.ImageRequest(it, doms)).bytes }.getOrNull() } }
        }

        val searchState = SearchUiState(
            query = "Pepper",
            sourceKey = "demo.manga",
            results = page.works.map { w -> SearchResultUi(w.id, w.title, coverBytes = coverOf(w.cover)) },
            page = 1,
            maxPage = page.maxPage,
            searched = true,
        )
        var screen by mutableStateOf(Screen.SEARCH)
        var detailState by mutableStateOf(
            DetailUiState(
                title = info.title,
                description = info.description,
                meta = "demo.manga · ${chapters.size} 话",
                coverBytes = coverOf(info.cover),
                chapters = chapters.map { DetailChapterUi(it.id, it.title) },
                loading = false,
            ),
        )
        val vm = ReaderViewModel(
            workId = "w1",
            chapterId = chapters.single().id,
            chapters = provider,
            saveProgress = SaveReadingProgressUseCase(NoopProgressRepository),
            loadProgress = LoadReadingProgressUseCase(NoopProgressRepository),
            pages = LoadPageBytesUseCase(SourceImageFetcher(SourceHttpClient(http, cookies) { 0L })),
            domains = domains,
            settingsStore = InMemoryReaderSettingsStore(),
            mangaTitle = info.title,
        )

        rule.setContent {
            AppTheme {
                when (screen) {
                    Screen.SEARCH -> SearchContent(
                        state = searchState,
                        sources = listOf(SearchSourceUi("demo.manga", "演示漫画源")),
                        onQueryChange = {},
                        onSubmit = {},
                        onSelectSource = {},
                        onPrev = {},
                        onNext = {},
                        onOpenWork = {},
                    )
                    Screen.DETAIL -> DetailContent(
                        state = detailState,
                        sourceKey = "demo.manga",
                        onRetry = {},
                        onOpenChapter = {},
                        onBack = {},
                    )
                    Screen.READER -> ReaderScreenVm(workId = "w1", onBack = {}, vm = vm)
                }
            }
        }
        rule.waitForIdle()

        val searchShot = rule.onRoot().captureToImage()
        WalkthroughSupport.save("comic-network-search.png", searchShot)
        WalkthroughSupport.assertRendered(searchShot, "网络搜索")
        WalkthroughSupport.assertPhotoLike(searchShot, "网络搜索封面", minColors = 300)

        screen = Screen.DETAIL
        rule.waitForIdle()
        // 封面是 Coil 异步解码：必须推进测试时钟 + 真等 IO，否则截图里只有空占位框。
        rule.mainClock.advanceTimeBy(2_000)
        Thread.sleep(1_500)
        rule.waitForIdle()
        val detailShot = rule.onRoot().captureToImage()
        WalkthroughSupport.save("comic-network-detail.png", detailShot)
        WalkthroughSupport.assertRendered(detailShot, "网络详情")
        WalkthroughSupport.assertPhotoLike(detailShot, "网络详情封面", minColors = 300)

        screen = Screen.READER
        val loaded = runCatching { rule.waitUntil(30_000) { vm.state.value.pages.isNotEmpty() } }
        if (loaded.isFailure) {
            val st = vm.state.value
            println("VM_DIAG error=${st.error} pageCount=${st.pageCount} chapterCount=${st.chapterCount} pages=${st.pages.size} states=${st.pageStates}")
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        Thread.sleep(1_500)
        rule.waitForIdle()
        val readerShot = rule.onRoot().captureToImage()
        WalkthroughSupport.save("comic-network-source.png", readerShot)
        WalkthroughSupport.assertPhotoLike(readerShot, "网络阅读器漫画页", minColors = 1_500)

        assertTrue(imageHits >= 1, "图片应经统一下载出口")
        assertTrue(vm.state.value.pages.isNotEmpty(), "应解码到图片字节")

        driver.close()
    }
}
