package core.model

import kotlinx.serialization.Serializable

/** 小说正文格式（契约 `novel.loadChapter` 的 format 冻结语义）。 */
enum class TextFormat { HTML, MARKDOWN, PLAIN }

/**
 * 章节载体（架构 ADR-005）：漫画与小说共用一条管线，差异收敛到此。
 * `payload_json` 落库，向后兼容（禁止破坏性改列）。
 */
@Serializable
sealed interface ChapterPayload {
    @Serializable
    data class Images(val urls: List<String>) : ChapterPayload

    @Serializable
    data class Text(val body: String, val format: TextFormat = TextFormat.HTML) : ChapterPayload

    /** BE-C4：视频章节（单资源 URL + 可选请求头 / 外挂字幕）。 */
    @Serializable
    data class Video(
        val url: String,
        val mimeType: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val subtitles: List<String> = emptyList(),
    ) : ChapterPayload

    /** BE-C4：音频章节（单资源 URL + 可选元信息）。 */
    @Serializable
    data class Audio(
        val url: String,
        val mimeType: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val title: String? = null,
        val durationMs: Long? = null,
    ) : ChapterPayload
}

/** 受限 HTML 标签白名单（`format=html` 渲染用，禁止 script/style/iframe/on*）。 */
object HtmlAllowlist {
    val tags = setOf("p", "br", "b", "i", "em", "strong", "img", "h1", "h2", "h3", "h4", "blockquote", "a")
    fun isAllowed(tag: String): Boolean = tag.lowercase() in tags
}
