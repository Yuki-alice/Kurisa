package core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 正文白名单解析：安全边界与字符坐标（commonTest，纯 Kotlin）。 */
class NovelContentParserTest {

    @Test
    fun htmlKeepsAllowedTextAndDropsScriptContent() {
        val html = "<p>第一章 &amp; 开始</p><script>alert('evil')</script>" +
            "<h2>小节</h2><div>正文<b>加粗</b>结束</div>"
        val doc = NovelContentParser.parse("c1", html, TextFormat.HTML)
        val joined = doc.blocks.joinToString("|") { it.text }
        assertTrue(joined.contains("第一章 & 开始"))
        assertTrue(joined.contains("正文加粗结束"))
        assertTrue(!joined.contains("alert"))
        assertTrue(doc.blocks.any { it.kind == NovelBlockKind.HEADING })
        assertTrue(doc.blocks.none { it.kind == NovelBlockKind.IMAGE })
    }

    @Test
    fun dangerousImageSchemesAreBlocked() {
        val html = "<p onclick='evil()'>A" +
            "<img src=\"javascript:alert(1)\">" +
            "<img src='data:image/png;base64,AAAA'>" +
            "<img src=\"https://example.com/a.png\">" +
            "</p>"
        val doc = NovelContentParser.parse("c1", html, TextFormat.HTML)
        val images = doc.blocks.filter { it.kind == NovelBlockKind.IMAGE }
        assertEquals(3, images.size)
        assertEquals(2, images.count { it.imageUrl == null })
        assertEquals("https://example.com/a.png", images.single { it.imageUrl != null }.imageUrl)
        // 事件属性不进入正文
        assertTrue(doc.blocks.none { it.text.contains("evil()") })
    }

    @Test
    fun entitiesAreDecodedAsTextNotMarkup() {
        val doc = NovelContentParser.parse("c1", "&lt;script&gt;&nbsp;&#65;", TextFormat.HTML)
        assertEquals("<script> A", doc.blocks.single().text)
    }

    @Test
    fun plainAndMarkdownProduceContinuousCharCoordinates() {
        val plain = NovelContentParser.parse("c1", "第一段\n\n第二段", TextFormat.PLAIN)
        assertEquals(2, plain.blocks.size)
        assertEquals(0, plain.blocks[0].charStart)
        assertEquals(4, plain.blocks[1].charStart)
        assertEquals(7, plain.charLength)

        val md = NovelContentParser.parse("c1", "# 标题\n> 引用\n**粗体**正文", TextFormat.MARKDOWN)
        assertEquals(3, md.blocks.size)
        assertTrue(md.blocks[0].kind == NovelBlockKind.HEADING)
        assertTrue(md.blocks[1].kind == NovelBlockKind.QUOTE)
        assertEquals("粗体正文", md.blocks[2].text)
    }

    @Test
    fun oversizedInputIsBounded() {
        // 防畸形载荷线性放大：上限是显式常量，解析层先截断再处理。
        assertEquals(2_000_000, NovelContentParser.MAX_INPUT_CHARS)
        val doc = NovelContentParser.parse("c1", "<p>" + "x".repeat(1_000) + "</p>", TextFormat.HTML)
        assertTrue(doc.contentHash.isNotEmpty())
    }

    @Test
    fun unterminatedAngleBracketDoesNotSwallowText() {
        val doc = NovelContentParser.parse("c1", "开头 < 未闭合 结尾", TextFormat.HTML)
        assertTrue(doc.blocks.single().text.contains("结尾"))
    }

    @Test
    fun htmlInlineTagsProduceSpans() {
        val doc = NovelContentParser.parse(
            "c1",
            "<p>普通<b>粗体</b>普通<i>斜体</i><code>代码</code></p>",
            TextFormat.HTML,
        )
        val block = doc.blocks.single()
        assertEquals("普通粗体普通斜体代码", block.text)
        fun spanAt(text: String): NovelSpan? =
            block.spans.firstOrNull { block.text.substring(it.start, it.end) == text }
        assertEquals(NovelInlineStyle.BOLD, spanAt("粗体")?.style)
        assertEquals(NovelInlineStyle.ITALIC, spanAt("斜体")?.style)
        assertEquals(NovelInlineStyle.CODE, spanAt("代码")?.style)
    }

    @Test
    fun htmlUnclosedInlineTagDoesNotLeakAcrossBlocks() {
        val doc = NovelContentParser.parse("c1", "<p>a<b>粗", TextFormat.HTML)
        // 块内 `<b>` 有闭合就记录；此处无闭合标签，按保守策略丢弃（不跨块）。
        assertTrue(doc.blocks.single().spans.isEmpty())
        // `<p>a</p><b>粗</b>` 闭合则记录。
        val closed = NovelContentParser.parse("c1", "<p>a</p><b>粗</b>", TextFormat.HTML)
        assertTrue(closed.blocks.any { it.spans.isNotEmpty() })
    }

    @Test
    fun markdownInlineMarkersProduceSpans() {
        val doc = NovelContentParser.parse(
            "c1",
            "**粗体**和*斜体*和`代码`",
            TextFormat.MARKDOWN,
        )
        val block = doc.blocks.single()
        assertEquals("粗体和斜体和代码", block.text)
        fun spanAt(text: String): NovelSpan? =
            block.spans.firstOrNull { block.text.substring(it.start, it.end) == text }
        assertEquals(NovelInlineStyle.BOLD, spanAt("粗体")?.style)
        assertEquals(NovelInlineStyle.ITALIC, spanAt("斜体")?.style)
        assertEquals(NovelInlineStyle.CODE, spanAt("代码")?.style)
    }

    @Test
    fun markdownUnclosedMarkersStayLiteral() {
        val doc = NovelContentParser.parse("c1", "**未闭合", TextFormat.MARKDOWN)
        assertEquals("**未闭合", doc.blocks.single().text)
        assertTrue(doc.blocks.single().spans.isEmpty())
        // snake_case 的下划线不触发斜体。
        val snake = NovelContentParser.parse("c1", "foo_bar", TextFormat.MARKDOWN)
        assertEquals("foo_bar", snake.blocks.single().text)
        assertTrue(snake.blocks.single().spans.isEmpty())
    }

    @Test
    fun spansSurviveWhitespaceCollapsing() {
        val doc = NovelContentParser.parse(
            "c1",
            "<p>  前  <b>  粗  </b>  后  </p>",
            TextFormat.HTML,
        )
        val block = doc.blocks.single()
        assertEquals("前 粗 后", block.text)
        val span = block.spans.single()
        assertEquals(NovelInlineStyle.BOLD, span.style)
        assertEquals("粗", block.text.substring(span.start, span.end).trim())
    }

    @Test
    fun htmlStrikeAndLinkSpans() {
        val doc = NovelContentParser.parse(
            "c1",
            "<p><s>删</s><a href=\"https://example.com/x\">跳</a>" +
                "<a href=\"javascript:evil()\">坏</a><a href=\"/inner\">内</a></p>",
            TextFormat.HTML,
        )
        val block = doc.blocks.single()
        assertEquals("删跳坏内", block.text)
        val byText = { t: String -> block.spans.firstOrNull { block.text.substring(it.start, it.end) == t } }
        assertEquals(NovelInlineStyle.STRIKETHROUGH, byText("删")?.style)
        val link = byText("跳")
        assertEquals(NovelInlineStyle.LINK, link?.style)
        assertEquals("https://example.com/x", link?.payload)
        // 危险协议与相对路径不建链接，只留纯文本。
        assertTrue(byText("坏") == null)
        assertTrue(byText("内") == null)
    }

    @Test
    fun markdownStrikeLinkAndEscapes() {
        val doc = NovelContentParser.parse("c1", "~~删~~和[跳](https://example.com/x)还有\\*星\\*", TextFormat.MARKDOWN)
        val block = doc.blocks.single()
        assertEquals("删和跳还有*星*", block.text)
        fun spanAt(t: String): NovelSpan? =
            block.spans.firstOrNull { block.text.substring(it.start, it.end) == t }
        assertEquals(NovelInlineStyle.STRIKETHROUGH, spanAt("删")?.style)
        val link = spanAt("跳")
        assertEquals(NovelInlineStyle.LINK, link?.style)
        assertEquals("https://example.com/x", link?.payload)
        // 非 http 链接与未闭合删除线按字面保留（链接无 span）。
        val plain = NovelContentParser.parse("c1", "[文](/inner)与~~开", TextFormat.MARKDOWN)
        assertTrue(plain.blocks.single().spans.none { it.style == NovelInlineStyle.LINK })
        assertTrue(plain.blocks.single().text.contains("~~开"))
    }

    @Test
    fun markdownUnorderedListsBecomeBulletedBlocks() {
        val doc = NovelContentParser.parse("c1", "- 苹果\n- **香蕉**甜\n1. 有序保持", TextFormat.MARKDOWN)
        assertEquals(3, doc.blocks.size)
        assertEquals(NovelBlockKind.LIST, doc.blocks[0].kind)
        assertEquals("\u2022 苹果", doc.blocks[0].text)
        assertEquals("\u2022 香蕉甜", doc.blocks[1].text)
        val bold = doc.blocks[1].spans.single()
        assertEquals(NovelInlineStyle.BOLD, bold.style)
        assertEquals("香蕉", doc.blocks[1].text.substring(bold.start, bold.end))
        // 有序数字列表保持段落原样（数字即序号，无需富文本）。
        assertEquals(NovelBlockKind.PARAGRAPH, doc.blocks[2].kind)
        assertEquals("1. 有序保持", doc.blocks[2].text)
    }

    @Test
    fun htmlListItemsBecomeBulletedBlocks() {
        val doc = NovelContentParser.parse(
            "c1",
            "<ul><li>甲</li><li>乙<b>丙</b></li></ul>",
            TextFormat.HTML,
        )
        val lists = doc.blocks.filter { it.kind == NovelBlockKind.LIST }
        assertEquals(2, lists.size)
        assertEquals("\u2022 甲", lists[0].text)
        assertEquals("\u2022 乙丙", lists[1].text)
        assertTrue(lists[1].spans.any { it.style == NovelInlineStyle.BOLD })
    }

    @Test
    fun nestedInlineMarkersProduceNestedSpans() {
        val doc = NovelContentParser.parse("c1", "**a *b* c**", TextFormat.MARKDOWN)
        val block = doc.blocks.single()
        assertEquals("a b c", block.text)
        val byText = { t: String -> block.spans.firstOrNull { block.text.substring(it.start, it.end) == t } }
        assertEquals(NovelInlineStyle.BOLD, byText("a b c")?.style)
        assertEquals(NovelInlineStyle.ITALIC, byText("b")?.style)
        val swapped = NovelContentParser.parse("c1", "*a **b** c*", TextFormat.MARKDOWN)
        val sb = swapped.blocks.single()
        assertEquals("a b c", sb.text)
        assertEquals(NovelInlineStyle.ITALIC, sb.spans.firstOrNull { sb.text.substring(it.start, it.end) == "a b c" }?.style)
        assertEquals(NovelInlineStyle.BOLD, sb.spans.firstOrNull { sb.text.substring(it.start, it.end) == "b" }?.style)
    }

    @Test
    fun asciiPunctuationEscapesToLiteral() {
        val doc = NovelContentParser.parse("c1", "\\# \\* \\\\[x]", TextFormat.MARKDOWN)
        assertEquals("# * \\[x]", doc.blocks.single().text)
        assertTrue(doc.blocks.single().spans.isEmpty())
    }

    @Test
    fun wordRangeAtHandlesCjkLatinAndPunctuation() {
        // 中日韩独体字：单字成词。
        assertEquals(2..2, wordRangeAt("正文内容", 2))
        assertEquals(0..0, wordRangeAt("正文内容", -5))
        assertEquals(3..3, wordRangeAt("正文内容", 99))
        // 拉丁字母数字 run：整体成词。
        assertEquals(0..13, wordRangeAt("hello_world123 x", 5))
        assertEquals(0..13, wordRangeAt("hello_world123 x", 0))
        // 标点/空格/全角符号：命中字符本身。
        assertEquals(2..2, wordRangeAt("ab，cd", 2))
        assertEquals(2..2, wordRangeAt("ab cd", 2))
        assertEquals(1..1, wordRangeAt("ＡＢＣ", 1))
        // 空文本返回空区间。
        assertTrue(wordRangeAt("", 0).isEmpty())
    }

    @Test
    fun plainTextHasNoSpans() {
        val doc = NovelContentParser.parse("c1", "**不是**标记", TextFormat.PLAIN)
        assertEquals("**不是**标记", doc.blocks.single().text)
        assertTrue(doc.blocks.single().spans.isEmpty())
    }
}
