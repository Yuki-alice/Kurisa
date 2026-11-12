package data.localimport

import data.localimport.XmlLeaves.firstText

/**
 * ComicInfo 元数据。对齐 Komikku `copyFromComicInfo` / `setChapterDetails` /
 * `updateMangaInfo` 的字段面（读写同一 schema，保证与 Komikku 本地库互操作）。
 */
data class ComicInfo(
    val title: String? = null,
    val series: String? = null,
    val number: String? = null,
    val summary: String? = null,
    val writer: String? = null,
    val penciller: String? = null,
    val genre: String? = null,
    val publisher: String? = null,
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
) {
    /** 章节详情覆盖：title / number / scanlator（publisher 兼任扫图组）。 */
    fun toChapterDetails() = ChapterDetails(
        title = title,
        number = number?.toDoubleOrNull(),
        scanlator = publisher,
    )

    /** 作品详情覆盖。 */
    fun toMangaDetails() = MangaDetails(
        title = series ?: title,
        author = writer,
        artist = penciller,
        description = summary,
        genre = genre,
    )
}

data class ChapterDetails(
    val title: String? = null,
    val number: Double? = null,
    val scanlator: String? = null,
)

data class MangaDetails(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: String? = null,
)

object ComicInfoParser {
    fun parse(xml: String): ComicInfo {
        val leaves = XmlLeaves.parse(xml)
        return ComicInfo(
            title = leaves.firstText("Title"),
            series = leaves.firstText("Series"),
            number = leaves.firstText("Number"),
            summary = leaves.firstText("Summary"),
            writer = leaves.firstText("Writer"),
            penciller = leaves.firstText("Penciller"),
            genre = leaves.firstText("Genre"),
            publisher = leaves.firstText("Publisher"),
            year = leaves.firstText("Year")?.toIntOrNull(),
            month = leaves.firstText("Month")?.toIntOrNull(),
            day = leaves.firstText("Day")?.toIntOrNull(),
        )
    }
}
