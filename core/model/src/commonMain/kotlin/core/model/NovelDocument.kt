package core.model

/**
 * 小说正文的结构化表示（M4 排版分页的输入）。
 *
 * 纯 Kotlin，无平台依赖；规范化全文 = [NovelDocument.blocks] 的 text 以 '\n' 连接，
 * [NovelBlock.charStart] 是其中的字符偏移，用于书签/进度锚点在重排后仍稳定。
 */
enum class NovelBlockKind { PARAGRAPH, HEADING, QUOTE, LIST, IMAGE }

/** 行内富文本样式（块内字符区间，半开；纯 Kotlin，与分页坐标同系）。 */
enum class NovelInlineStyle { BOLD, ITALIC, CODE, STRIKETHROUGH, LINK }

/**
 * 块内字符偏移的样式区间（`[start, end)`，块坐标；跨行切分时由分页器裁剪）。
 * `payload` 仅 LINK 使用（点击跳转的 http(s) URL，已过协议白名单）。
 */
data class NovelSpan(val start: Int, val end: Int, val style: NovelInlineStyle, val payload: String? = null)

data class NovelBlock(
    val kind: NovelBlockKind,
    val text: String,
    val charStart: Int,
    /** 仅 IMAGE 块；已通过协议白名单，渲染前仍需宿主网络出口复核域名。 */
    val imageUrl: String? = null,
    /** 行内样式（默认空，既有构造零改动；分页切片时裁剪合并）。 */
    val spans: List<NovelSpan> = emptyList(),
)

data class NovelDocument(
    val chapterId: String,
    val format: TextFormat,
    val blocks: List<NovelBlock>,
    /** 规范化全文长度（含块间 '\n'）。 */
    val charLength: Int,
    /** 正文指纹，作为分页缓存 LayoutKey 的输入。 */
    val contentHash: String,
)

/** 中日韩独体字区间（扩展 A/基本/兼容/假名/谚文/全角形，每个字符独立成词，长按即选单字）。 */
private fun isCjkUnit(c: Char): Boolean = c in '\u3400'..'\u4DBF' ||
    c in '\u4E00'..'\u9FFF' ||
    c in '\uF900'..'\uFAFF' ||
    c in '\u3040'..'\u30FF' ||
    c in '\uAC00'..'\uD7AF' ||
    c in '\uFF00'..'\uFFEF'

/** 词字符：字母数字 + 下划线（`foo_bar123` 整体为一词）。 */
private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/**
 * 长按圈词的词边界（块内闭区间；空文本返回空区间）。
 * - 中日韩独体字/半角全角符号/标点空白：命中字符本身；
 * - 其余词字符：向前向后扩展到连续 run；
 * - 越界下标收敛到文本内。长按划线与点段落共用同一落盘管线（见 `ToggleBlockHighlight`）。
 */
fun wordRangeAt(text: String, offset: Int): IntRange {
    if (text.isEmpty()) return IntRange.EMPTY
    val o = offset.coerceIn(0, text.length - 1)
    val c = text[o]
    if (isCjkUnit(c) || !isWordChar(c)) return o..o
    var s = o
    while (s > 0 && isWordChar(text[s - 1]) && !isCjkUnit(text[s - 1])) s--
    var e = o
    while (e + 1 < text.length && isWordChar(text[e + 1]) && !isCjkUnit(text[e + 1])) e++
    return s..e
}

/** FNV-1a 64 位（Long 溢出即回绕，纯 Kotlin 三端一致），不用于安全场景。 */
fun hashText(text: String): String {
    var hash = -3750763034362895579L
    val prime = 1099511628211L
    for (ch in text) {
        hash = (hash xor ch.code.toLong()) * prime
    }
    return hash.toULong().toString(16)
}
