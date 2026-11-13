package core.model

/**
 * 小说正文解析：HTML → 受限白名单富文本块，PLAIN/MARKDOWN → 段落块。
 *
 * 安全边界（security-audit / MASVS-CODE、MASVS-PLATFORM）：
 * - 不使用正则"剥标签"作为渲染层防线；自研扫描器只识别白名单标签；
 * - `script/style/iframe/object/embed/svg` 连内容一起丢弃；
 * - 任何事件属性（`on*`）都不解析、不保留；未知标签只忽略标签本身，保留纯文本；
 * - 图片 URL 拒绝危险协议，长度受限；本协议检查不替代宿主网络出口的域名白名单；
 * - 输入超长先截断（防畸形载荷导致线性放大与 ReDoS）。
 */
object NovelContentParser {

    /** 与 NovelHost.stripHtml 一致的防御上限。 */
    const val MAX_INPUT_CHARS = 2_000_000

    private val BLOCK_TAGS = setOf("p", "div", "h1", "h2", "h3", "h4", "blockquote", "li", "br")
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4")
    private val DROP_CONTENT_TAGS = setOf("script", "style", "iframe", "object", "embed", "svg", "noscript")
    private val DANGEROUS_SCHEMES = listOf("javascript:", "data:", "file:", "vbscript:")

    fun parse(chapterId: String, body: String, format: TextFormat): NovelDocument {
        val capped = if (body.length > MAX_INPUT_CHARS) body.take(MAX_INPUT_CHARS) else body
        val raw = when (format) {
            TextFormat.HTML -> parseHtml(capped)
            TextFormat.PLAIN, TextFormat.MARKDOWN -> parsePlain(capped, markdown = format == TextFormat.MARKDOWN)
        }
        var cursor = 0
        val blocks = raw.map { rawBlock ->
            val block = NovelBlock(rawBlock.kind, rawBlock.text, cursor, rawBlock.url, rawBlock.spans)
            cursor += rawBlock.text.length + 1
            block
        }
        val normalizedLength = raw.sumOf { it.text.length } + (raw.size - 1).coerceAtLeast(0)
        return NovelDocument(
            chapterId = chapterId,
            format = format,
            blocks = blocks,
            charLength = normalizedLength.coerceAtLeast(0),
            contentHash = hashText(capped),
        )
    }

    /** 未规范化块：类型、原文（待折叠空白）、图片 URL、原文坐标样式区间。 */
    private data class RawBlock(
        val kind: NovelBlockKind,
        val text: String,
        val url: String?,
        val spans: List<NovelSpan>,
    )

    private fun parseHtml(input: String): List<RawBlock> {
        val out = mutableListOf<RawBlock>()
        val text = StringBuilder()
        val spanStack = mutableListOf<Triple<Int, NovelInlineStyle, String?>>()
        val spans = mutableListOf<NovelSpan>()
        var kind = NovelBlockKind.PARAGRAPH
        var bullet = false
        var i = 0

        fun closeInline(style: NovelInlineStyle) {
            val at = spanStack.indexOfLast { it.second == style }
            if (at < 0) return
            val (start, _, url) = spanStack.removeAt(at)
            if (text.length > start) spans += NovelSpan(start, text.length, style, url)
        }

        fun flush() {
            // 未闭合的行内标记随块丢弃，不跨块（无效嵌套的保守处理）。
            spanStack.clear()
            val (value, mapped) = normalizeWithSpans(text.toString(), spans)
            if (value.isNotEmpty()) {
                // 无序列表项前缀项目符号（`li` 块独占；`<br>` 切分后后续行不再带符号）。
                if (bullet && kind == NovelBlockKind.PARAGRAPH) {
                    out += RawBlock(
                        NovelBlockKind.LIST,
                        "\u2022 $value",
                        null,
                        mapped.map { it.copy(start = it.start + 2, end = it.end + 2) },
                    )
                } else {
                    out += RawBlock(kind, value, null, mapped)
                }
            }
            text.setLength(0)
            spans.clear()
            kind = NovelBlockKind.PARAGRAPH
            bullet = false
        }

        while (i < input.length) {
            val ch = input[i]
            if (ch == '&') {
                val end = input.indexOf(';', i + 1).takeIf { it in (i + 1)..(i + 12) }
                if (end != null) {
                    val decoded = decodeEntity(input.substring(i, end + 1))
                    if (decoded != null) {
                        text.append(decoded)
                        i = end + 1
                        continue
                    }
                }
                text.append('&')
                i++
                continue
            }
            if (ch != '<') {
                text.append(if (ch == '\n' || ch == '\r' || ch == '\t') ' ' else ch)
                i++
                continue
            }
            if (input.startsWith("<!--", i)) {
                val end = input.indexOf("-->", i + 4)
                i = if (end < 0) input.length else end + 3
                continue
            }
            val tagEnd = findTagEnd(input, i + 1)
            if (tagEnd < 0) {
                // 未闭合的 '<'：当普通文本处理，防止吞掉后续正文。
                text.append(' ')
                i++
                continue
            }
            val rawTag = input.substring(i + 1, tagEnd).trim()
            i = tagEnd + 1
            if (rawTag.isEmpty()) continue
            val closing = rawTag.startsWith('/')
            val name = tagName(rawTag.removePrefix("/"))
            if (name.isEmpty()) continue

            if (name in DROP_CONTENT_TAGS) {
                flush()
                if (!closing) {
                    val close = "</$name"
                    val next = input.indexOf(close, i, ignoreCase = true)
                    val endTag = if (next < 0) -1 else findTagEnd(input, next + 2)
                    i = if (endTag < 0) input.length else endTag + 1
                }
                continue
            }
            if (closing) {
                if (name in BLOCK_TAGS && name != "br") flush()
                else if (name == "a") closeInline(NovelInlineStyle.LINK)
                else inlineStyleOf(name)?.let { closeInline(it) }
                continue
            }
            when {
                name == "br" -> flush()
                name == "img" -> {
                    flush()
                    val src = attribute(rawTag, "src")
                    val safe = src?.let(::safeImageUrl)
                    // 图片占位符占一个规范化字符，保证锚点坐标连续。
                    out += RawBlock(NovelBlockKind.IMAGE, "▩", safe, emptyList())
                }
                name in BLOCK_TAGS -> {
                    flush()
                    if (name in HEADING_TAGS) kind = NovelBlockKind.HEADING
                    if (name == "blockquote") kind = NovelBlockKind.QUOTE
                    if (name == "li") bullet = true
                }
                else -> {
                    // 已知行内样式标签按区间记录；未知标签只忽略标签，正文继续累积。
                    // `<a>` 仅 http(s) 进白名单并记录跳转 URL（外部浏览器打开，不经过 WebView）。
                    if (name == "a") {
                        val href = attribute(rawTag, "href")?.let(::safeLinkUrl)
                        if (href != null) spanStack += Triple(text.length, NovelInlineStyle.LINK, href)
                    } else {
                        inlineStyleOf(name)?.let { spanStack += Triple(text.length, it, null) }
                    }
                }
            }
        }
        flush()
        return out
    }

    private fun parsePlain(input: String, markdown: Boolean): List<RawBlock> =
        input.split('\n').mapNotNull { line ->
            var value = line.trim().trimEnd('\r')
            if (value.isEmpty()) return@mapNotNull null
            var kind = NovelBlockKind.PARAGRAPH
            if (markdown) {
                when {
                    value.startsWith("#") -> {
                        kind = NovelBlockKind.HEADING
                        value = value.trimStart('#').trim()
                    }
                    value.startsWith(">") -> {
                        kind = NovelBlockKind.QUOTE
                        value = value.trimStart('>').trim()
                    }
                    // 无序列表（`-`/`*`/`+` + 空格；有序数字列表保持段落原样，数字即序号）。
                    unorderedListMarker(value) != null -> {
                        kind = NovelBlockKind.LIST
                        value = value.drop(2).trim()
                    }
                }
            }
            if (!markdown) {
                val clean = normalizeWhitespace(value)
                if (clean.isEmpty()) null else RawBlock(kind, clean, null, emptyList())
            } else {
                val (rawText, inlineSpans) = parseInlineMarkdown(value)
                if (kind == NovelBlockKind.LIST && rawText.isEmpty()) return@mapNotNull null
                val (bulletedRaw, bulletedSpans) = if (kind == NovelBlockKind.LIST) {
                    "\u2022 $rawText" to inlineSpans.map { it.copy(start = it.start + 2, end = it.end + 2) }
                } else {
                    rawText to inlineSpans
                }
                val (clean, mapped) = normalizeWithSpans(bulletedRaw, bulletedSpans)
                if (clean.isEmpty()) null else RawBlock(kind, clean, null, mapped)
            }
        }

    /** 折叠连续空白，避免 HTML 缩进/换行把正文切成碎片。 */
    private fun normalizeWhitespace(value: String): String =
        Regex("\\s+").replace(value, " ").trim()

    /** 无序列表标记（`-`/`*`/`+` 后跟空格；返回含尾空格的标记原文）。有序数字列表不在此列。 */
    private fun unorderedListMarker(value: String): String? {
        if (value.length >= 2 && value[0] in "-*+" && value[1] == ' ') return value.substring(0, 2)
        return null
    }

    /** 行内样式标签白名单（`b/strong/i/em/code/s/strike/del`；`a` 单独处理，见正文注释）。 */
    private fun inlineStyleOf(tag: String): NovelInlineStyle? = when (tag) {
        "b", "strong" -> NovelInlineStyle.BOLD
        "i", "em" -> NovelInlineStyle.ITALIC
        "code" -> NovelInlineStyle.CODE
        "s", "strike", "del" -> NovelInlineStyle.STRIKETHROUGH
        else -> null
    }

    /**
     * 外链白名单（点击跳转外部浏览器用）：仅 http(s)，拒绝危险协议与控制字符。
     * 与图片协议白名单同集合（[`DANGEROUS_SCHEMES`]），相对路径不收录（站内锚点无跳转目标）。
     */
    private fun safeLinkUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 2048) return null
        val lower = value.lowercase()
        if (DANGEROUS_SCHEMES.any { lower.startsWith(it) }) return null
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return null
        if (value.any { it.code < 0x20 }) return null
        return value
    }

    /** 与正文正则同字符类的 ASCII 空白（`\\s` 的 Java 语义，不含 NBSP 等 Unicode 空白）。 */
    private fun isHtmlSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    /**
     * 折叠连续空白（与 [normalizeWhitespace] 同语义），并记录每个规范化字符的原文下标。
     * 样式区间重映射用：规范化字符取其来源 run 的末尾原文下标近似（紧贴内容的区间边界不左偏）。
     */
    private fun normalizeWithMap(raw: String): Pair<String, IntArray> {
        val out = StringBuilder()
        val map = mutableListOf<Int>()
        var i = 0
        while (i < raw.length && isHtmlSpace(raw[i])) i++
        var pending = false
        while (i < raw.length) {
            val c = raw[i]
            if (isHtmlSpace(c)) {
                pending = true
                i++
                continue
            }
            if (pending) {
                out.append(' ')
                // 折叠空格映射到空白 run 的末尾，使紧贴内容的区间边界不左偏。
                map.add(i - 1)
                pending = false
            }
            out.append(c)
            map.add(i)
            i++
        }
        return out.toString() to map.toIntArray()
    }

    /** 规范化文本并把原文坐标样式区间重映射到规范化坐标；落在空白里的区间直接丢弃。 */
    private fun normalizeWithSpans(raw: String, spans: List<NovelSpan>): Pair<String, List<NovelSpan>> {
        if (spans.isEmpty()) return normalizeWhitespace(raw) to emptyList()
        val (value, map) = normalizeWithMap(raw)
        if (value.isEmpty()) return value to emptyList()
        val mapped = spans.mapNotNull { s ->
            val ns = map.indexOfFirst { it >= s.start }.takeIf { it >= 0 } ?: return@mapNotNull null
            val ne = map.indexOfLast { it < s.end }.takeIf { it >= 0 }?.plus(1) ?: return@mapNotNull null
            if (ne <= ns) null else NovelSpan(ns, ne, s.style, s.payload)
        }
        return value to mapped
    }

    /**
     * Markdown 行内标记扫描（返回去标记文本 + 原文坐标样式区间）。
     * 支持 `**粗体**` / `__粗体__` / `*斜体*` / `` `代码` `` / `~~删除线~~` / `[文本](http(s)://…)`；
     * 未闭合的标记按字面保留。`_` 需满足左邻非字母数字（避免 snake_case 被误判）；`*` 允许词内强调。
     * 链接仅 http(s) 生效（外部浏览器打开），其余按字面保留。
     */
    private fun parseInlineMarkdown(line: String): Pair<String, List<NovelSpan>> {
        val text = StringBuilder()
        val spans = mutableListOf<NovelSpan>()
        val stack = mutableListOf<Pair<Int, NovelInlineStyle>>()
        var i = 0

        fun openAt(style: NovelInlineStyle): Boolean {
            val prev = if (text.isEmpty()) ' ' else text[text.length - 1]
            if ((style == NovelInlineStyle.ITALIC) && prev.isLetterOrDigit()) return false
            stack += text.length to style
            return true
        }

        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length && isEscapable(line[i + 1])) {
                text.append(line[i + 1])
                i += 2
                continue
            }
            if (c == '`') {
                val close = line.indexOf('`', i + 1)
                if (close < 0) {
                    text.append(c)
                    i++
                } else {
                    val start = text.length
                    text.append(line.substring(i + 1, close))
                    if (text.length > start) spans += NovelSpan(start, text.length, NovelInlineStyle.CODE)
                    i = close + 1
                }
                continue
            }
            if (c == '~' && i + 1 < line.length && line[i + 1] == '~') {
                // `~~删除线~~`：无闭合按字面保留。
                val close = line.indexOf("~~", i + 2)
                if (close < 0) {
                    text.append("~~")
                    i += 2
                } else {
                    val (inner, innerSpans) = parseInlineMarkdown(line.substring(i + 2, close))
                    if (inner.isEmpty()) {
                        text.append("~~~~")
                    } else {
                        val start = text.length
                        text.append(inner)
                        for (s in innerSpans) spans += s.copy(start = s.start + start, end = s.end + start)
                        spans += NovelSpan(start, text.length, NovelInlineStyle.STRIKETHROUGH)
                    }
                    i = close + 2
                }
                continue
            }
            if (c == '[') {
                // `[文本](http(s)://…)`：仅 http(s) 建链接（外部浏览器打开）；其余按字面保留。
                val closeBracket = line.indexOf(']', i + 1)
                val openParen = if (closeBracket > i + 1) closeBracket + 1 else -1
                if (openParen in 0 until line.length && line[openParen] == '(') {
                    val closeParen = line.indexOf(')', openParen + 1)
                    if (closeParen > openParen + 1) {
                        val url = safeLinkUrl(line.substring(openParen + 1, closeParen))
                        if (url != null) {
                            val (inner, innerSpans) = parseInlineMarkdown(line.substring(i + 1, closeBracket))
                            if (inner.isNotEmpty()) {
                                val base = text.length
                                text.append(inner)
                                for (s in innerSpans) spans += s.copy(start = s.start + base, end = s.end + base)
                                spans += NovelSpan(base, text.length, NovelInlineStyle.LINK, url)
                                i = closeParen + 1
                                continue
                            }
                        }
                    }
                }
                text.append(c)
                i++
                continue
            }
            if (c == '*' || c == '_') {
                val doubled = i + 1 < line.length && line[i + 1] == c
                if (doubled) {
                    // `**粗体**`/`__粗体__`：`__` 需左邻非字母数字（避 snake_case），无闭合按字面保留。
                    val marker = "$c$c"
                    val prevIsWord = !text.isEmpty() && text[text.length - 1].isLetterOrDigit()
                    val allowed = c == '*' || !prevIsWord
                    val close = if (allowed) line.indexOf(marker, i + 2) else -1
                    if (close < 0) {
                        text.append(marker)
                        i += 2
                    } else {
                        // 内部递归（如 `**a *b* c**` 内层斜体），与单标记分支同构。
                        val (inner, innerSpans) = parseInlineMarkdown(line.substring(i + 2, close))
                        if (inner.isEmpty()) {
                            text.append(marker + marker)
                        } else {
                            val start = text.length
                            text.append(inner)
                            for (s in innerSpans) spans += s.copy(start = s.start + start, end = s.end + start)
                            spans += NovelSpan(start, text.length, NovelInlineStyle.BOLD)
                        }
                        i = close + 2
                    }
                    continue
                }
                // 单个标记：`*` 无条件、`_` 需左邻非字母数字。
                if (c == '*' || openAt(NovelInlineStyle.ITALIC)) {
                    val style = NovelInlineStyle.ITALIC
                    if (c == '*') stack += text.length to style
                    i++
                    val innerClose = findInlineClose(line, i, c)
                    if (innerClose < 0) {
                        stack.removeAt(stack.lastIndex)
                        text.append(c)
                    } else {
                        val start = stack.removeAt(stack.lastIndex).first
                        // 递归解析内部（如 `*a **b** c*` 内部加粗）。
                        val (inner, innerSpans) = parseInlineMarkdown(line.substring(i, innerClose))
                        val base = text.length
                        text.append(inner)
                        for (s in innerSpans) spans += s.copy(start = s.start + base, end = s.end + base)
                        if (text.length > start) spans += NovelSpan(start, text.length, style)
                        else text.append(c)
                        i = innerClose + 1
                    }
                    continue
                }
                text.append(c)
                i++
                continue
            }
            text.append(c)
            i++
        }
        // 未闭合的 opening 标记按字面补回。
        for ((start, _) in stack.sortedByDescending { it.first }) {
            text.insert(start, "*")
        }
        return text.toString() to spans
    }

    /** 反斜杠转义（CommonMark 语义）：ASCII 标点取字面，其余保留反斜杠。 */
    private fun isEscapable(c: Char): Boolean =
        c.code < 128 && !c.isLetterOrDigit() && !c.isWhitespace()

    /** 单字符强调的闭合查找（`*` 任意闭合；`_` 需右邻非字母数字且非空白；成对标记跳过，只与单标记配对）。 */
    private fun findInlineClose(line: String, from: Int, marker: Char): Int {
        var i = line.indexOf(marker, from)
        while (i >= 0) {
            if (i + 1 < line.length && line[i + 1] == marker) {
                i = line.indexOf(marker, i + 2)
                continue
            }
            if (marker == '*') return i
            val next = if (i + 1 < line.length) line[i + 1] else ' '
            if (!next.isLetterOrDigit() && !next.isWhitespace()) return i
            if (next.isWhitespace() || i + 1 >= line.length) return i
            i = line.indexOf(marker, i + 1)
        }
        return -1
    }

    private fun findTagEnd(input: String, from: Int): Int {
        var quote: Char? = null
        for (i in from until input.length) {
            val c = input[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '>' -> return i
                '<' -> return -1
            }
        }
        return -1
    }

    private fun tagName(raw: String): String {
        val name = StringBuilder()
        for (c in raw) {
            if (c.isLetterOrDigit() || c == '-') name.append(c.lowercaseChar()) else break
        }
        return name.toString()
    }

    /** 只读取 src；标签名与事件属性一律不参与解析。 */
    private fun attribute(rawTag: String, name: String): String? {
        var i = rawTag.indexOfFirst { it.isWhitespace() }
        if (i < 0) return null
        while (i < rawTag.length) {
            while (i < rawTag.length && rawTag[i].isWhitespace()) i++
            if (i >= rawTag.length) return null
            val nameStart = i
            while (i < rawTag.length && !rawTag[i].isWhitespace() && rawTag[i] != '=') i++
            val key = rawTag.substring(nameStart, i).lowercase()
            while (i < rawTag.length && rawTag[i].isWhitespace()) i++
            if (i >= rawTag.length || rawTag[i] != '=') {
                // 无值属性（如 disabled）：跳过该 token，继续找下一个。
                while (i < rawTag.length && !rawTag[i].isWhitespace()) i++
                continue
            }
            i++
            while (i < rawTag.length && rawTag[i].isWhitespace()) i++
            if (i >= rawTag.length) return null
            val value: String
            if (rawTag[i] == '"' || rawTag[i] == '\'') {
                val quote = rawTag[i]
                val end = rawTag.indexOf(quote, i + 1)
                if (end < 0) return null
                value = rawTag.substring(i + 1, end)
                i = end + 1
            } else {
                var end = i
                while (end < rawTag.length && !rawTag[end].isWhitespace()) end++
                value = rawTag.substring(i, end)
                i = end
            }
            if (key == name) return decodeEntities(value)
        }
        return null
    }

    private fun safeImageUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 2048) return null
        val lower = value.lowercase()
        if (DANGEROUS_SCHEMES.any { lower.startsWith(it) }) return null
        // 控制字符不进入 URL（防日志/请求走私）。
        if (value.any { it.code < 0x20 }) return null
        return value
    }

    private fun decodeEntities(value: String): String {
        var out = value
        var i = 0
        while (i < out.length) {
            if (out[i] != '&') {
                i++
                continue
            }
            val end = out.indexOf(';', i + 1).takeIf { it in (i + 1)..(i + 12) } ?: run {
                i++
                continue
            }
            val decoded = decodeEntity(out.substring(i, end + 1))
            if (decoded == null) {
                i++
            } else {
                out = out.replaceRange(i, end + 1, decoded)
                i += decoded.length
            }
        }
        return out
    }

    private fun decodeEntity(entity: String): String? = when (entity.lowercase()) {
        "&nbsp;" -> " "
        "&amp;" -> "&"
        "&lt;" -> "<"
        "&gt;" -> ">"
        "&quot;" -> "\""
        "&apos;" -> "'"
        else -> {
            if (!entity.startsWith("&#") || !entity.endsWith(";")) return null
            val digits = entity.substring(2, entity.length - 1)
            val code = when {
                digits.startsWith("x", ignoreCase = true) -> digits.substring(1).toIntOrNull(16)
                else -> digits.toIntOrNull()
            } ?: return null
            if (code !in 0..0x10FFFF) return null
            code.toChar().toString()
        }
    }
}
