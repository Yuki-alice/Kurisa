package feature.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import core.model.ChapterPayload
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ReadingConfig
import core.reader.ReadingMode
import data.images.FetchedImage
import data.images.ImageFetcher
import data.images.ImageRequest
import data.repository.ProgressRepository
import core.model.ReadingProgress
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO
import androidx.compose.ui.graphics.toAwtImage
import kotlin.test.assertTrue

/**
 * 真实渲染证据测试（desktop，JVM）。
 *
 * 用程序生成的**真实格式文件**（PNG 真实编码字节、最小合法 PDF）驱动完整阅读链：
 * provider → ViewModel 窗口 → Coil 解码 → Compose 渲染 → 截图落盘。
 * 截图同时是给人的视觉证据（`docs/screenshots/reader-*.png` 由 CI/本地脚本拷贝）。
 */
class ReaderScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    // -- 真实 PNG 生成（zlib + CRC 手工封装，无外部资源） --

    private fun pngBytes(w: Int, h: Int, rgb: (x: Int, y: Int) -> Triple<Int, Int, Int>): ByteArray {
        fun chunk(type: ByteArray, data: ByteArray): ByteArray {
            val out = ArrayList<Byte>()
            fun i32(v: Int) {
                out += (v shr 24).toByte(); out += (v shr 16).toByte()
                out += (v shr 8).toByte(); out += v.toByte()
            }
            i32(data.size)
            out += type.toList()
            out += data.toList()
            val crc = CRC32()
            crc.update(type)
            crc.update(data)
            val c = crc.value.toInt()
            i32(c)
            return out.toByteArray()
        }
        val raw = ArrayList<Byte>()
        for (y in 0 until h) {
            raw += 0.toByte()
            for (x in 0 until w) {
                val (r, g, b) = rgb(x, y)
                raw += r.toByte(); raw += g.toByte(); raw += b.toByte()
            }
        }
        val def = Deflater(6)
        def.setInput(raw.toByteArray())
        def.finish()
        val buf = ByteArray(raw.size + 64)
        val n = def.deflate(buf)
        def.end()
        val out = ArrayList<Byte>()
        out += listOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = ByteArray(13)
        fun w32(a: ByteArray, o: Int, v: Int) {
            a[o] = (v shr 24).toByte(); a[o + 1] = (v shr 16).toByte()
            a[o + 2] = (v shr 8).toByte(); a[o + 3] = v.toByte()
        }
        w32(ihdr, 0, w); w32(ihdr, 4, h)
        ihdr[8] = 8; ihdr[9] = 2
        out += chunk("IHDR".toByteArray(), ihdr).toList()
        out += chunk("IDAT".toByteArray(), buf.copyOf(n)).toList()
        out += chunk("IEND".toByteArray(), ByteArray(0)).toList()
        return out.toByteArray()
    }

    /** 白底 + 黑边框 + 顶栏 + 中央色块（模拟漫画页：边框供裁边验证，色块供渲染验证）。 */
    private fun mangaPage(w: Int, h: Int, r: Int, g: Int, b: Int): ByteArray =
        pngBytes(w, h) { x, y ->
            val border = 12
            if (x < border || y < border || x >= w - border || y >= h - border) {
                Triple(255, 255, 255)
            } else if (y < h / 8) {
                Triple(40, 80, 200)
            } else if (x in w / 3..2 * w / 3 && y in h / 3..2 * h / 3) {
                Triple(r, g, b)
            } else {
                Triple(240, 240, 240)
            }
        }

    // -- 最小合法 PDF（xref 偏移实时计算，pdfium 可打开） --

    /** 确定性布局：1 catalog / 2 pages / 3.. 页 / 内容 / 字体交错。 */
    private fun buildDeterministicPdf(pages: Int): ByteArray {
        val out = ArrayList<Byte>()
        fun s(t: String) = out.addAll(t.toByteArray(Charsets.US_ASCII).toList())
        s("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        fun obj(id: Int, body: String) {
            offsets += out.size
            s("$id 0 obj\n$body\nendobj\n")
        }
        val kids = (0 until pages).joinToString(" ") { "${3 + it * 3} 0 R" }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [$kids] /Count $pages >>")
        repeat(pages) { i ->
            val base = 3 + i * 3
            val stream = "1 0 0 rg 0 0 300 400 re f\nBT /F1 24 Tf 50 ${150 + i * 10} Td (Page ${i + 1}) Tj ET"
            obj(base, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents ${base + 1} 0 R /Resources << /Font << /F1 ${base + 2} 0 R >> >> >>")
            obj(base + 1, "<< /Length ${stream.length} >>\nstream\n$stream\nendstream")
            obj(base + 2, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        }
        val xref = out.size
        val total = 2 + pages * 3
        s("xref\n0 ${total + 1}\n0000000000 65535 f \n")
        offsets.forEach { s("%010d 00000 n \n".format(it)) }
        s("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF")
        return out.toByteArray()
    }

    // -- 证据基础设施 --

    private fun saveEvidence(name: String, image: ImageBitmap): File {
        val dir = File("build/screenshots")
        dir.mkdirs()
        val file = File(dir, name)
        ImageIO.write(image.toAwtImage(), "png", file)
        println("SCREENSHOT_SAVED: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    /** 断言图片非空白：采样网格出现 3 种以上颜色（页面+文字/UI缺一不可全同色）。 */
    private fun assertRendered(image: ImageBitmap, label: String, minColors: Int = 3) {
        val map = image.toPixelMap()
        val colors = HashSet<Int>()
        val w = map.width
        val h = map.height
        for (gx in 0 until 8) {
            for (gy in 0 until 8) {
                val x = (gx * w / 8).coerceIn(0, w - 1)
                val y = (gy * h / 8).coerceIn(0, h - 1)
                val c = map[x, y]
                colors += (c.red * 255).toInt() shl 16 or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
                if (colors.size > minColors) break
            }
            if (colors.size > minColors) break
        }
        assertTrue(colors.size >= minColors, "$label 疑似空白图：仅 ${colors.size} 色")
    }

    // -- 用例 --

    class PngProvider(val pages: List<ByteArray>) : ChapterProvider {
        override suspend fun chapters(workId: String) =
            listOf(core.reader.ChapterRef("c1", "第一话", 0))

        override suspend fun payload(workId: String, chapterId: String) =
            ChapterPayload.Images(pages.indices.map { "p$it" })

        override suspend fun rawBytes(workId: String, chapterId: String): ByteArray =
            throw UnsupportedOperationException()
    }

    class PngImages(val pages: List<ByteArray>) : ImageFetcher {
        override suspend fun fetch(request: ImageRequest): FetchedImage {
            val i = request.url.removePrefix("p").toInt()
            return FetchedImage(pages[i], request.url)
        }
    }

    class NoopProgress : ProgressRepository {
        override suspend fun save(progress: ReadingProgress) = Unit
        override suspend fun load(workId: String): ReadingProgress? = null
    }

    private fun vm(
        pages: List<ByteArray>,
        chapterId: String = "c1",
        mode: ReadingMode = ReadingMode.PAGED,
        settingsStore: InMemoryReaderSettingsStore = InMemoryReaderSettingsStore(
            ReadingConfig(mode = mode),
        ),
    ) = ReaderViewModel(
        workId = "w",
        chapterId = chapterId,
        machine = core.reader.ReaderStateMachine(),
        chapters = PngProvider(pages),
        saveProgress = SaveReadingProgressUseCase(NoopProgress()),
        loadProgress = LoadReadingProgressUseCase(NoopProgress()),
        pages = LoadPageBytesUseCase(PngImages(pages)),
        domains = emptySet(),
        settingsStore = settingsStore,
    )

    /** JPEG 版漫画页（korlibs 现场编码；覆盖最常见的漫画图片格式）。 */
    private fun mangaJpeg(w: Int, h: Int, r: Int, g: Int, bl: Int): ByteArray {
        val bmp = korlibs.image.bitmap.Bitmap32(w, h)
        val border = (minOf(w, h) / 20).coerceAtLeast(2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp[x, y] = when {
                    x < border || y < border || x >= w - border || y >= h - border ->
                        korlibs.image.color.RGBA(255, 255, 255, 255)
                    y < h / 8 -> korlibs.image.color.RGBA(40, 80, 200, 255)
                    x in w / 3..2 * w / 3 && y in h / 3..2 * h / 3 ->
                        korlibs.image.color.RGBA(r, g, bl, 255)
                    else -> korlibs.image.color.RGBA(240, 240, 240, 255)
                }
            }
        }
        return korlibs.image.format.JPEG.encode(bmp)
    }

    @Test
    fun pagerRendersRealPagesWithChrome() {
        val pages = listOf(
            mangaJpeg(400, 600, 200, 40, 40),
            mangaJpeg(400, 600, 40, 120, 200),
            mangaJpeg(800, 600, 40, 160, 80), // 横图：宽页独占验证对象
        )
        val m = vm(pages)
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.pages.size >= 3 }
        // 等尺寸全部落定（spreads 重算会重发 PageChanged，先开菜单会被关掉）。
        rule.waitUntil(15_000) { m.state.value.pageSizes.size >= 3 }
        rule.waitForIdle()
        // 等首绘 + Coil 解码完成后再开菜单，避免 Pager 首个 PageChanged 把菜单关掉。
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        m.onIntent(ReaderContract.Intent.ToggleMenu)
        rule.waitForIdle()
        val shot = rule.onRoot().captureToImage()
        assertRendered(shot, "pager+chrome")
        saveEvidence("reader-pager-chrome.png", shot)
    }

    @Test
    fun webtoonRendersRealPages() {
        val pages = listOf(
            mangaPage(400, 800, 200, 40, 40),
            mangaPage(400, 800, 40, 120, 200),
        )
        val m = vm(pages, mode = ReadingMode.CONTINUOUS)
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.pages.size >= 2 }
        // Coil 异步解码完成后截图，否则抓到占位。
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        Thread.sleep(1_500)
        val shot = rule.onRoot().captureToImage()
        saveEvidence("reader-webtoon.png", shot)
        assertRendered(shot, "webtoon")
    }



    @Test
    fun landscapeDoublePageSpread() {
        // 横屏双页（默认测试窗口即横屏）：首封面独占 + 后续两两配对。
        val pages = listOf(
            mangaJpeg(400, 600, 200, 40, 40),
            mangaJpeg(400, 600, 40, 120, 200),
            mangaJpeg(400, 600, 40, 160, 80),
            mangaJpeg(400, 600, 160, 140, 40),
        )
        val m = vm(
            pages,
            settingsStore = InMemoryReaderSettingsStore(
                ReadingConfig(mode = ReadingMode.PAGED, doublePage = true),
            ),
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        // 预载窗口为当前±（前 1 后 2），第 4 页翻到才加载。
        rule.waitUntil(15_000) { m.state.value.pages.size >= 3 }
        rule.waitUntil(15_000) { m.state.value.pageSizes.size >= 3 }
        rule.waitForIdle()
        // 跳到第 2 页所在的双页 spread [1,2]。
        m.onIntent(ReaderContract.Intent.PageChanged(1))
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val shot = rule.onRoot().captureToImage()
        saveEvidence("reader-pager-double.png", shot)
        // 左右半屏应为不同页（颜色不同）。
        val map = shot.toPixelMap()
        val w = map.width
        val h = map.height
        fun sample(x: Int, y: Int): Int {
            val c = map[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)]
            return (c.red * 255).toInt() shl 16 or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
        }
        val left = sample(w / 4, h / 2)
        val right = sample(3 * w / 4, h / 2)
        assertTrue(left != right, "双页 spread 左右应为不同页面：$left vs $right")
    }

    @Test
    fun pdfOpensRealDocument() {
        val pdf = buildDeterministicPdf(2)
        val provider = object : ChapterProvider {
            override suspend fun chapters(workId: String) =
                listOf(core.reader.ChapterRef("doc.pdf", "文档", 0))

            override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
                throw IllegalStateException("PDF 章节请用专用阅读器打开")

            override suspend fun rawBytes(workId: String, chapterId: String): ByteArray = pdf
        }
        val m = ReaderViewModel(
            workId = "w",
            chapterId = "doc.pdf",
            machine = core.reader.ReaderStateMachine(),
            chapters = provider,
            saveProgress = SaveReadingProgressUseCase(NoopProgress()),
            loadProgress = LoadReadingProgressUseCase(NoopProgress()),
            pages = LoadPageBytesUseCase(PngImages(emptyList())),
            domains = emptySet(),
            settingsStore = InMemoryReaderSettingsStore(),
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.pdfBytes != null }
        // pdfium 原生初始化 + 首帧渲染较慢：充足等待后再截图（证据测试可接受）。
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitForIdle()
        Thread.sleep(5_000)
        rule.waitForIdle()
        val shot = rule.onRoot().captureToImage()
        saveEvidence("reader-pdf.png", shot)
        // 红底页 + 阅读器底色至少 2 色。
        assertRendered(shot, "pdf", minColors = 2)
        // PDF 渲染由 pdfium 异步完成，不强制非空白断言（截图人工复核）。
    }
}
