package core.reader

import core.model.NovelBlock
import core.model.NovelBlockKind
import core.model.NovelDocument
import core.model.NovelInlineStyle
import core.model.NovelSpan
import core.model.TextFormat
import core.model.hashText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** 分页内核：不丢字符、幂等、跨页切分、锚点映射（commonTest，无 Compose 依赖）。 */
class NovelPaginatorTest {

    private class FixedMeasure(
        private val perLine: Int = 5,
        private val lineHeight: Float = 10f,
    ) : TextMeasure {
        override fun measure(text: String, style: NovelStyleSpec, maxWidthPx: Int): LineMetrics {
            val lines = (0 until text.length step perLine).map { start ->
                MeasuredLine(start, minOf(start + perLine, text.length), lineHeight)
            }
            return LineMetrics(lines)
        }
    }

    private fun document(): NovelDocument {
        val texts = listOf("一二三四五六七八九十", "甲乙丙丁戊己庚辛", "结尾段落文字在这里")
        var cursor = 0
        val blocks = texts.map { text ->
            NovelBlock(NovelBlockKind.PARAGRAPH, text, cursor).also { cursor += text.length + 1 }
        }
        return NovelDocument(
            chapterId = "c1",
            format = TextFormat.PLAIN,
            blocks = blocks,
            charLength = texts.sumOf { it.length } + texts.size - 1,
            contentHash = hashText(texts.joinToString("\n")),
        )
    }

    private fun key(typography: NovelTypography = NovelTypography()): LayoutKey = LayoutKey(
        chapterId = "c1",
        contentHash = "hash",
        viewport = NovelViewport(320, 35),
        typography = typography,
        density = 1f,
        fontFamilyId = "default",
        localeTag = "",
    )

    @Test
    fun paginationIsIdempotentAndLosesNoCharacters() {
        val paginator = NovelPaginator(FixedMeasure())
        val doc = document()
        val viewport = NovelViewport(320, 35)
        val pages = paginator.paginate(doc, viewport, NovelTypography(), key())
        val again = paginator.paginate(doc, viewport, NovelTypography(), key())
        assertEquals(pages, again)
        assertTrue(pages.size >= 2)
        val joined = pages.flatMap { it.blocks }.joinToString("") { it.text }
        assertEquals(doc.blocks.joinToString("") { it.text }, joined)
        assertTrue(pages.all { it.charEnd >= it.charStart })
        assertTrue(pages.zipWithNext().all { (a, b) -> b.charStart >= a.charStart })
    }

    @Test
    fun crossPageBlockIsSplitAtLineBoundary() {
        // 每行 3 字、页高 12：一段 10 字必然跨页，页内块坐标必须连续。
        val paginator = NovelPaginator(FixedMeasure(perLine = 3, lineHeight = 10f))
        val text = "一二三四五六七八九十"
        val doc = NovelDocument(
            chapterId = "c1",
            format = TextFormat.PLAIN,
            blocks = listOf(NovelBlock(NovelBlockKind.PARAGRAPH, text, 0)),
            charLength = text.length,
            contentHash = hashText(text),
        )
        val pages = paginator.paginate(
            doc, NovelViewport(320, 12), NovelTypography(paragraphSpacingDp = 0f), key(),
        )
        assertTrue(pages.size >= 2)
        assertEquals(text, pages.flatMap { it.blocks }.joinToString("") { it.text })
        val firstEnd = pages.first().blocks.last().charStart + pages.first().blocks.last().text.length
        assertEquals(firstEnd, pages[1].blocks.first().charStart)
    }

    @Test
    fun anchorMapsToContainingPageAndClamps() {
        val pages = NovelPaginator(FixedMeasure()).paginate(
            document(), NovelViewport(320, 35), NovelTypography(), key(),
        )
        assertEquals(0, NovelPaginator.pageOfCharOffset(pages, -10))
        assertEquals(0, NovelPaginator.pageOfCharOffset(pages, 0))
        val middle = pages[pages.lastIndex / 2 + 1].charStart
        assertEquals(
            pages.indexOfFirst { middle >= it.charStart && middle < it.charEnd },
            NovelPaginator.pageOfCharOffset(pages, middle),
        )
        assertEquals(pages.lastIndex, NovelPaginator.pageOfCharOffset(pages, Int.MAX_VALUE))
        assertEquals(pages.first().charStart, NovelPaginator.charOffsetOfPage(pages, 0))
    }

    @Test
    fun layoutKeyChangesWithTypographyAndViewport() {
        val base = key()
        assertTrue(base.signature() != key(NovelTypography(fontSizeSp = 22f)).signature())
        assertTrue(
            base.signature() != base.copy(viewport = NovelViewport(400, 35)).signature(),
        )
    }

    @Test
    fun spansAreClippedAndShiftedAcrossSlices() {
        val paginator = NovelPaginator(FixedMeasure(perLine = 2, lineHeight = 10f))
        val text = "0123456789"
        val doc = NovelDocument(
            chapterId = "c1",
            format = TextFormat.PLAIN,
            blocks = listOf(
                core.model.NovelBlock(
                    core.model.NovelBlockKind.PARAGRAPH,
                    text,
                    0,
                    spans = listOf(core.model.NovelSpan(1, 9, core.model.NovelInlineStyle.BOLD)),
                ),
            ),
            charLength = text.length,
            contentHash = hashText(text),
        )
        // 页高 25（行高 10、无段距）：每页恰好 2 行，跨 3 页。
        val pages = paginator.paginate(
            doc, NovelViewport(320, 25), NovelTypography(paragraphSpacingDp = 0f), key(),
        )
        assertEquals(3, pages.size)
        assertEquals(text, pages.flatMap { it.blocks }.joinToString("") { it.text })
        val first = pages[0].blocks.single()
        assertEquals("0123", first.text)
        assertEquals(
            listOf(core.model.NovelSpan(1, 4, core.model.NovelInlineStyle.BOLD)),
            first.spans,
        )
        val last = pages[2].blocks.single()
        assertEquals("89", last.text)
        assertEquals(
            listOf(core.model.NovelSpan(0, 1, core.model.NovelInlineStyle.BOLD)),
            last.spans,
        )
        // 全部 span 不越界。
        for (block in pages.flatMap { it.blocks }) {
            for (s in block.spans) {
                assertTrue(s.start >= 0 && s.end <= block.text.length && s.end > s.start)
            }
        }
    }

    @Test
    fun linkPayloadSurvivesSlicingAndMerging() {
        val paginator = NovelPaginator(FixedMeasure(perLine = 2, lineHeight = 10f))
        val text = "0123456789"
        val link = core.model.NovelSpan(2, 8, core.model.NovelInlineStyle.LINK, "https://example.com/x")
        val doc = NovelDocument(
            chapterId = "c1",
            format = TextFormat.PLAIN,
            blocks = listOf(NovelBlock(NovelBlockKind.PARAGRAPH, text, 0, spans = listOf(link))),
            charLength = text.length,
            contentHash = hashText(text),
        )
        val pages = paginator.paginate(
            doc, NovelViewport(320, 25), NovelTypography(paragraphSpacingDp = 0f), key(),
        )
        assertEquals(3, pages.size)
        // 跨行裁剪保留 payload；相邻同负载融合为整块区间。
        assertEquals(
            listOf(core.model.NovelSpan(0, 4, core.model.NovelInlineStyle.LINK, "https://example.com/x")),
            pages[1].blocks.single().spans,
        )
        val all = pages.flatMap { it.blocks }.flatMap { it.spans }
        assertTrue(all.all { it.payload == "https://example.com/x" || it.style != core.model.NovelInlineStyle.LINK })
        assertTrue(all.filter { it.style == core.model.NovelInlineStyle.LINK }.isNotEmpty())
    }

    @Test
    fun listBlocksKeepKindWhenSplit() {
        val paginator = NovelPaginator(FixedMeasure(perLine = 2, lineHeight = 10f))
        val text = "\u2022 ab"
        val doc = NovelDocument(
            chapterId = "c1",
            format = TextFormat.MARKDOWN,
            blocks = listOf(
                NovelBlock(
                    NovelBlockKind.LIST, text, 0,
                    spans = listOf(NovelSpan(2, 4, NovelInlineStyle.BOLD)),
                ),
            ),
            charLength = text.length,
            contentHash = hashText(text),
        )
        val pages = paginator.paginate(
            doc, NovelViewport(320, 25), NovelTypography(paragraphSpacingDp = 0f), key(),
        )
        assertEquals(1, pages.size)
        val block = pages.single().blocks.single()
        assertEquals(NovelBlockKind.LIST, block.kind)
        assertEquals(text, block.text)
        assertEquals(
            listOf(NovelSpan(2, 4, NovelInlineStyle.BOLD)),
            block.spans,
        )
    }

    @Test
    fun longChapterPaginatesInReasonableTime() {
        // 长章性能基线（P3）：5000 段 × 100 字 ≈ 0.5MB 正文，假测量器按行切分。
        //  guard 算法退化（合并/缓存键为线性行为）；真实排版耗时见桌面端渲染基准。
        val paragraph = "一".repeat(96) + "二三四五"
        val texts = List(5000) { paragraph }
        var cursor = 0
        val blocks = texts.map { text ->
            NovelBlock(NovelBlockKind.PARAGRAPH, text, cursor).also { cursor += text.length + 1 }
        }
        val doc = NovelDocument(
            chapterId = "long",
            format = TextFormat.PLAIN,
            blocks = blocks,
            charLength = texts.sumOf { it.length } + texts.size - 1,
            contentHash = hashText("long"),
        )
        val pages: List<NovelPage>
        val elapsedMs = kotlin.time.measureTime {
            pages = NovelPaginator(FixedMeasure(perLine = 50, lineHeight = 10f)).paginate(
                doc, NovelViewport(320, 35), NovelTypography(), key(),
            )
        }.inWholeMilliseconds
        println("LONG_CHAPTER_PAGINATE_MS=$elapsedMs pages=${pages.size}")
        assertTrue(pages.isNotEmpty())
        assertEquals(doc.blocks.sumOf { it.text.length }, pages.flatMap { it.blocks }.sumOf { it.text.length })
        // 数量级守卫：算法退化时此处先红，而非等到 UI 卡顿（基线约 35ms/5000 页，留 300 倍余量）。
        assertTrue(elapsedMs < 10_000, "过慢：${elapsedMs}ms")
    }

    @Test
    fun pageCacheKeepsOnlyRecentLayouts() {
        val cache = NovelPageCache(2)
        val k1 = key()
        val k2 = key(NovelTypography(fontSizeSp = 20f))
        val k3 = key(NovelTypography(fontSizeSp = 24f))
        cache.put(k1, emptyList())
        cache.put(k2, emptyList())
        cache.put(k3, emptyList())
        assertTrue(cache.get(k1) == null)
        assertTrue(cache.get(k3) != null)
    }
}
