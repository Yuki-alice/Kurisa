package spike

import coil3.ImageLoader
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.select.Elements
import com.github.panpf.sketch.Sketch
import com.github.panpf.zoomimage.compose.ZoomState
import com.github.panpf.zoomimage.compose.subsampling.SubsamplingState
import com.multiplatform.webview.web.WebViewNavigator
import com.multiplatform.webview.web.WebViewState
import dev.nucleusframework.pdfium.PdfReaderState
import korlibs.encoding.Base64

/**
 * M0 P0 组件冒烟：把 UI 库的公开类型与入口函数真正链接进三端 klib / jar。
 * 运行时渲染行为（截图基线、OOM 压测）不在本轮范围。
 */
@Suppress("unused")
internal object UiSmoke {
    fun parseHtml(html: String): Elements = Ksoup.parse(html).select("a")
    fun documentName(d: Document): String = d.toString()
    fun base64Encode(text: String): String = Base64.encode(text.encodeToByteArray())
    fun coilLoader(l: ImageLoader): String = l.toString()
    fun sketch(s: Sketch): String = s.toString()
    fun zoomState(z: ZoomState): String = z.toString()
    fun subsamplingState(s: SubsamplingState): String = s.toString()
    fun webViewState(s: WebViewState): String = s.toString()
    fun webViewNavigator(n: WebViewNavigator): String = n.toString()
    fun pdfReaderState(s: PdfReaderState): String = s.toString()
    // dev.nucleusframework.pdfium.PdfDocument 是 internal，不作为公开契约冒烟
}
