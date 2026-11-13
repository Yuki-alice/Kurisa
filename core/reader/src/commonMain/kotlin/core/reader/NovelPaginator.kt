package core.reader

import core.model.NovelBlockKind
import core.model.NovelDocument
import core.model.NovelSpan

/**
 * 小说分页内核（纯 Kotlin，无 Compose / 平台依赖）。
 *
 * 关键约束：文本测量能力以 [TextMeasure] 注入，唯一实现位于 UI 层的 `rememberTextMeasurer()`，
 * 避免在 commonMain 构造 TextMeasurer 所需的 FontFamily.Resolver / Density（ADR-006）。
 * 因此分页算法可在 commonTest 用假测量器完整验证。
 */

data class NovelViewport(val widthPx: Int, val heightPx: Int)

data class NovelStyleSpec(
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val firstLineIndentPx: Float,
    val kind: NovelBlockKind,
)

/** [start]/[end] 是块内字符偏移（半开区间），[heightPx] 为该行占高。 */
data class MeasuredLine(val start: Int, val end: Int, val heightPx: Float)

data class LineMetrics(val lines: List<MeasuredLine>)

interface TextMeasure {
    fun measure(text: String, style: NovelStyleSpec, maxWidthPx: Int): LineMetrics
}

/** 分页缓存键：任一影响排版的输入变化都必须使键变化。 */
data class LayoutKey(
    val chapterId: String,
    val contentHash: String,
    val viewport: NovelViewport,
    val typography: NovelTypography,
    val density: Float,
    val fontFamilyId: String,
    val localeTag: String,
) {
    fun signature(): String = buildString {
        append(chapterId).append('|').append(contentHash).append('|')
        append(viewport.widthPx).append('x').append(viewport.heightPx).append('|')
        append(typography).append('|')
        append(density).append('|').append(fontFamilyId).append('|').append(localeTag)
    }
}

data class NovelPageBlock(
    val kind: NovelBlockKind,
    val text: String,
    val charStart: Int,
    val imageUrl: String? = null,
    val indentPx: Float = 0f,
    /** 行内样式（块坐标，分页切片时裁剪；合并同类相邻块时后移）。 */
    val spans: List<NovelSpan> = emptyList(),
)

/** 把块坐标样式区间裁剪到行切片 `[start, end)`，并平移到切片坐标。 */
private fun sliceSpans(spans: List<NovelSpan>, start: Int, end: Int): List<NovelSpan> =
    spans.mapNotNull { s ->
        val ns = maxOf(s.start, start) - start
        val ne = minOf(s.end, end) - start
        if (ne <= ns) null else NovelSpan(ns, ne, s.style, s.payload)
    }

data class NovelPage(
    val index: Int,
    val charStart: Int,
    val charEnd: Int,
    val blocks: List<NovelPageBlock>,
)

class NovelPaginator(private val measure: TextMeasure) {

    /**
     * 按视口把文档切成虚拟页。
     * - 以段落块为最小单位测量，按行边界切页，保证规范化全文字符不丢不重；
     * - 段间距计入占用高度；图片块按需占高，不在本层发起网络请求；
     * - 结果幂等：同一 [LayoutKey] 与同一测量器输出一致。
     */
    fun paginate(
        doc: NovelDocument,
        viewport: NovelViewport,
        typography: NovelTypography,
        key: LayoutKey,
    ): List<NovelPage> {
        val type = typography.sanitized()
        val pageHeight = viewport.heightPx.toFloat().coerceAtLeast(1f)
        val pageWidth = viewport.widthPx.coerceAtLeast(1)
        val density = key.density.coerceAtLeast(0.5f)
        val fontSizePx = type.fontSizeSp * density
        val spacingPx = type.paragraphSpacingDp * density
        val pages = mutableListOf<NovelPage>()
        var current = mutableListOf<NovelPageBlock>()
        var used = 0f

        fun flush() {
            if (current.isEmpty()) return
            val first = current.first()
            val last = current.last()
            pages += NovelPage(
                index = pages.size,
                charStart = first.charStart,
                charEnd = last.charStart + last.text.length,
                blocks = current.toList(),
            )
            current = mutableListOf()
            used = 0f
        }

        for (block in doc.blocks) {
            if (block.kind == NovelBlockKind.IMAGE) {
                val imageHeight = (pageWidth * IMAGE_HEIGHT_RATIO).coerceIn(1f, pageHeight * 0.75f)
                if (current.isNotEmpty() && used + imageHeight > pageHeight) flush()
                current += NovelPageBlock(
                    kind = NovelBlockKind.IMAGE,
                    text = block.text,
                    charStart = block.charStart,
                    imageUrl = block.imageUrl,
                )
                used += imageHeight + spacingPx
                continue
            }
            val indentPx = if (block.kind == NovelBlockKind.PARAGRAPH) {
                fontSizePx * type.paragraphIndentEm
            } else {
                0f
            }
            val style = NovelStyleSpec(
                fontSizePx = fontSizePx,
                lineHeightPx = fontSizePx * type.lineHeightMultiple,
                firstLineIndentPx = indentPx,
                kind = block.kind,
            )
            val metrics = runCatching {
                measure.measure(block.text, style, (pageWidth - indentPx).toInt().coerceAtLeast(1))
            }.getOrDefault(LineMetrics(emptyList()))
            val lines = metrics.lines.ifEmpty {
                listOf(MeasuredLine(0, block.text.length, style.lineHeightPx))
            }
            if (current.isNotEmpty() && used + spacingPx + lines.first().heightPx > pageHeight) {
                flush()
            } else if (current.isNotEmpty()) {
                used += spacingPx
            }
            for (line in lines) {
                val start = line.start.coerceIn(0, block.text.length)
                val end = line.end.coerceIn(start, block.text.length)
                if (end <= start) continue
                val lineHeight = line.heightPx.coerceAtLeast(1f)
                // 单行过高时，空页也强制放入，避免死循环。
                if (current.isNotEmpty() && used + lineHeight > pageHeight) flush()
                val slice = block.text.substring(start, end)
                val charStart = block.charStart + start
                val sliceSpans = sliceSpans(block.spans, start, end)
                val last = current.lastOrNull()
                if (last != null && last.kind == block.kind &&
                    last.imageUrl == null && last.charStart + last.text.length == charStart
                ) {
                    val shift = last.text.length
                    val shifted = sliceSpans.map {
                        it.copy(start = it.start + shift, end = it.end + shift)
                    }
                    // 相邻同样式区间融合，避免同一切片链产生碎片 span。
                    val mergedSpans = run {
                        if (shifted.isEmpty() || last.spans.isEmpty()) return@run last.spans + shifted
                        val l = last.spans.last()
                        val f = shifted.first()
                        if (l.style == f.style && l.payload == f.payload && l.end == f.start) {
                            last.spans.dropLast(1) + l.copy(end = f.end) + shifted.drop(1)
                        } else {
                            last.spans + shifted
                        }
                    }
                    current[current.lastIndex] = last.copy(
                        text = last.text + slice,
                        spans = mergedSpans,
                    )
                } else {
                    current += NovelPageBlock(
                        kind = block.kind,
                        text = slice,
                        charStart = charStart,
                        indentPx = indentPx,
                        spans = sliceSpans,
                    )
                }
                used += lineHeight
            }
        }
        flush()
        if (pages.isEmpty()) {
            pages += NovelPage(0, 0, doc.charLength.coerceAtLeast(0), emptyList())
        }
        return pages
    }

    companion object {
        private const val IMAGE_HEIGHT_RATIO = 0.6f

        /** 字符锚点 → 虚拟页；越界收敛到首/末页。 */
        fun pageOfCharOffset(pages: List<NovelPage>, offset: Int): Int {
            if (pages.isEmpty()) return 0
            if (offset <= 0) return 0
            val index = pages.indexOfFirst { offset >= it.charStart && offset < it.charEnd }
            return if (index >= 0) index else pages.lastIndex
        }

        fun charOffsetOfPage(pages: List<NovelPage>, pageIndex: Int): Int =
            pages.getOrNull(pageIndex)?.charStart ?: 0
    }
}

/** 只保留最近若干章的分页结果（内存缓存，不落盘；不依赖平台 LinkedHashMap 扩展点）。 */
class NovelPageCache(private val maxEntries: Int = 3) {
    private val entries = LinkedHashMap<String, List<NovelPage>>()

    fun get(key: LayoutKey): List<NovelPage>? = entries[key.signature()]

    fun put(key: LayoutKey, pages: List<NovelPage>) {
        val signature = key.signature()
        entries.remove(signature)
        entries[signature] = pages
        while (entries.size > maxEntries.coerceAtLeast(1)) {
            val eldest = entries.keys.firstOrNull() ?: return
            entries.remove(eldest)
        }
    }

    fun clear() = entries.clear()
}
