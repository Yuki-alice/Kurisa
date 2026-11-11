package spike

import com.fleeksoft.ksoup.Ksoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M0 Gate ① 地基：同一 HTML fixture 在三端产出一致的选择器结果。
 * Ksoup 是纯 Kotlin，理论三端一致；本测试把“理论”变成可回归的断言。
 * 覆盖：select / text / attr / html。
 * 注意：Ksoup 的 Document 本身无 dispose()；`dispose` 是宿主 HtmlDocument 包装的语义
 * （architecture.md 4.5/5.x），M1 在 :source:hostapi 实现包装后补“dispose 后不可用”断言。
 */
class KsoupConsistencyTest {

    private val html = """
        <html><head><title>one</title></head>
        <body>
          <div class="works">
            <a class="w" href="/m/1" data-id="b23c9bb1">お前じゃん</a>
            <a class="w" href="/m/2" data-id="a1c7c817">海贼王</a>
          </div>
          <p id="desc">Hello <b>World</b></p>
        </body></html>
    """.trimIndent()

    @Test
    fun selectorCountAndText() {
        val doc = Ksoup.parse(html)
        val links = doc.select("div.works a.w")
        assertEquals(2, links.size)
        assertEquals("お前じゃん", links[0].text())
        assertEquals("海贼王", links[1].text())
        assertEquals("/m/1", links[0].attr("href"))
        assertEquals("b23c9bb1", links[0].attr("data-id"))
    }

    @Test
    fun unicodeAndNesting() {
        val doc = Ksoup.parse(html)
        val p = doc.select("p#desc")
        assertEquals(1, p.size)
        assertEquals("Hello World", p[0].text())
        assertTrue(p[0].html().contains("<b>"))
    }

    @Test
    fun emptySelect() {
        val doc = Ksoup.parse("<html><body><p>x</p></body></html>")
        assertEquals(0, doc.select("div.missing").size)
    }
}
