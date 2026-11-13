package data.localimport

import data.network.GbkCodec

/**
 * 本地 TXT 正文解析（纯 Kotlin，commonMain）。
 *
 * - 编码：UTF-8 优先（含 BOM），非法序列出现时回退 GBK 表解码（复用
 *   `data.network.GbkCodec`，三端一致，不引入平台 Charset，符合 C3 / ADR-006）；
 * - 章节：按常见中文/英文标题行切分；无标题时整本作为一章；
 * - 输出 PLAIN 文本，段落与富文本渲染交给 `NovelContentParser`（M4 Phase 1）。
 */
data class TxtChapter(val title: String, val body: String)

object TxtReader {

    /** 防畸形超大文件撑爆内存；与 HTML 解析层同量级上限。 */
    const val MAX_CHARS = 4_000_000

    private val TITLE = Regex(
        """^\s*(第[0-9零一二三四五六七八九十百千〇两]+[章节回卷部篇集话][^\n]{0,40}|Chapter\s+\d+[^\n]{0,40}|序章|楔子|引子|尾声|后记|番外[^\n]{0,20})\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** UTF-8 合法则直接用；出现替换字符说明是 GBK/GB18030 系编码。 */
    fun decode(bytes: ByteArray): String {
        val utf8 = bytes.decodeToString()
        if ('\uFFFD' !in utf8) return utf8.trimStart('\uFEFF')
        return GbkCodec.decode(bytes).trimStart('\uFEFF')
    }

    fun parse(bytes: ByteArray, fallbackTitle: String): List<TxtChapter> {
        val raw = decode(bytes)
        val text = if (raw.length > MAX_CHARS) raw.take(MAX_CHARS) else raw
        val chapters = mutableListOf<TxtChapter>()
        var title = fallbackTitle
        val body = StringBuilder()

        fun flush() {
            val value = body.toString().trim()
            if (value.isNotEmpty()) chapters += TxtChapter(title, value)
            body.setLength(0)
        }

        for (line in text.lines()) {
            val trimmed = line.trim().trimEnd('\r')
            if (trimmed.length <= 48 && TITLE.matches(trimmed)) {
                flush()
                title = trimmed
            } else {
                body.append(trimmed).append('\n')
            }
        }
        flush()
        if (chapters.isEmpty()) chapters += TxtChapter(fallbackTitle, text.trim())
        return chapters
    }
}
