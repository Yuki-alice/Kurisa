package spike

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.nucleusframework.pdfium.PdfReader
import dev.nucleusframework.pdfium.PdfReaderState
import dev.nucleusframework.pdfium.rememberPdfReaderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PDF 真渲染（desktop/JVM）：最小 1 页 PDF → rememberPdfReaderState().open →
 * getPageCount/pageText/pageSize 全走 pdfium 原生解析；
 * 再经 PdfReader composable 离屏出像素，证明渲染管线端到端可用。
 * 渐进渲染/文本选择 UI 在 M4 跟阅读器压测；M0 证明工件可加载、可解析、可出像素。
 */
class PdfRenderTest {

    private fun minimalPdf(): ByteArray {
        val header = "%PDF-1.4\n"
        val o1 = "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
        val o2 = "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
        val o3 = "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 400]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n"
        val o4 = "4 0 obj<</Length 44>>stream\nBT /F1 24 Tf 50 200 Td (Hello) Tj ET\nendstream endobj\n"
        val o5 = "5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n"
        val fixed = listOf(header, o1, o2, o3, o4, o5)
        val fstart = mutableListOf<Int>()
        var facc = 0
        for (p in fixed) { fstart += facc; facc += p.toByteArray().size }
        val xrefPos = facc
        val xref = buildString {
            append("xref\n0 6\n0000000000 65535 f \n")
            for (i in 1..5) append(String.format("%010d 00000 n \n", fstart[i]))
            append("trailer\n<</Size 6/Root 1 0 R>>\nstartxref\n$xrefPos\n%%EOF")
        }
        return (fixed.joinToString("") + xref).toByteArray()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun openParseAndRender() = runBlocking {
        val bytes = minimalPdf()
        assertTrue(bytes.decodeToString().startsWith("%PDF-1.4"))
        System.setProperty("skiko.renderApi", "SOFTWARE")
        var captured: PdfReaderState? = null
        val scene = ImageComposeScene(300, 400, Density(1f)) {
            val state = rememberPdfReaderState()
            captured = state
            LaunchedEffect(bytes) { state.open(bytes, null) }
            PdfReader(state = state)
        }
        try {
            // 等内部异步 open 完成（pdfium 原生解析）
            var loaded = false
            for (i in 0 until 20) {
                delay(300)
                scene.render()
                if ((captured?.pageCount ?: 0) > 0) { loaded = true; break }
            }
            assertTrue(loaded, "pdfium 未在 6s 内解析出页面")
            val state = requireNotNull(captured)
            assertEquals(1, state.pageCount)
            val text = state.pageText(0)
            assertTrue(text.contains("Hello"), "text=$text")
            val size = requireNotNull(state.pageSize(0))
            assertTrue(size.widthPoints > 0 && size.heightPoints > 0, "size=$size")
            // 真渲染像素落盘
            val out = File(System.getProperty("java.io.tmpdir"), "pdf-spike-${System.currentTimeMillis()}.png")
            try {
                val data = requireNotNull(scene.render().encodeToData(EncodedImageFormat.PNG))
                out.writeBytes(data.bytes)
                assertTrue(out.isFile && out.length() > 1_000, "shot=${out.length()}")
            } finally {
                out.delete()
            }
        } finally {
            scene.close()
            captured?.dispose()
        }
    }
}
