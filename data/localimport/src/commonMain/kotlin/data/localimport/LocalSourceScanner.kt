package data.localimport

/**
 * 本地库扫描器。对齐 Komikku `LocalSource.getChapterList` / 浏览过滤 /
 * `filter/OrderBy` + ComicInfo 三级覆盖（顶层 xml → cbm(仅标记) → 遗留 json →
 * 章节内首个 ComicInfo.xml → `.noxml`），纯 Kotlin。
 *
 * 输出为仓无关的扫描模型，落库映射由 `:data:repository.LocalLibrarySync` 承接。
 */
enum class LocalOrderBy { TITLE, DATE }

data class ScannedChapter(
    val name: String,
    val url: String,
    val dateUploadMillis: Long,
    val number: Double,
    val scanlator: String? = null,
    val titleOverride: String? = null,
)

data class ScannedManga(
    val name: String,
    val chapters: List<ScannedChapter>,
    val coverPath: String?,
    val details: MangaDetails?,
    /** 顶层存在加密 ComicInfo.cbm（P1 解密前仅标记，不解析）。 */
    val hasEncryptedComicInfo: Boolean = false,
)

class LocalSourceScanner(
    private val fs: LocalSourceFileSystem,
    private val covers: LocalCoverManager = LocalCoverManager(fs),
) {
    /** base 扫描：隐藏已滤（fs 层）；TITLE=书名升序，DATE=修改降序。 */
    suspend fun scanBase(orderBy: LocalOrderBy = LocalOrderBy.TITLE): List<LocalEntry> {
        val dirs = fs.baseEntries().filter { it.isDirectory }
        return when (orderBy) {
            LocalOrderBy.TITLE -> dirs.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            LocalOrderBy.DATE -> dirs.sortedByDescending { it.lastModifiedMillis }
        }
    }

    /** 漫画扫描：章节（自然排序降序）+ 封面 + 顶层详情。 */
    suspend fun scanManga(mangaName: String, mangaTitle: String = mangaName): ScannedManga {
        val entries = fs.mangaEntries(mangaName)
        val chapters = entries.mapNotNull { entry ->
            scanEntry(mangaName, mangaTitle, entry)
        }.sortedWith { a, b -> NaturalOrder.compare(b.name, a.name) }

        // 顶层 ComicInfo 层级：xml > cbm(标记) > 遗留 json
        val topXml = entries.firstOrNull { !it.isDirectory && it.name.equals("ComicInfo.xml", ignoreCase = true) }
        val topCbm = entries.firstOrNull { !it.isDirectory && it.name.equals("ComicInfo.cbm", ignoreCase = true) }
        val topJson = entries.firstOrNull { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
        val details = when {
            topXml != null -> readComicInfo("$mangaName/${topXml.name}")?.toMangaDetails()
            topJson != null -> readLegacyJson("$mangaName/${topJson.name}")
            else -> findEmbeddedComicInfo(mangaName, chapters)?.toMangaDetails()
        }

        val cover = covers.findCover(mangaName)
            ?: firstImageCover(mangaName, chapters)

        return ScannedManga(
            name = mangaName,
            chapters = chapters,
            coverPath = cover,
            details = details,
            hasEncryptedComicInfo = topCbm != null && topXml == null,
        )
    }

    private suspend fun scanEntry(
        mangaName: String,
        mangaTitle: String,
        entry: LocalEntry,
    ): ScannedChapter? {
        val header = if (entry.isDirectory) {
            byteArrayOf()
        } else {
            fs.readHeader(entryPath(mangaName, entry))
        }
        val format = FormatDetector.detect(entry.name, header, entry.isDirectory)
        if (entry.isDirectory) {
            if (format != LocalFormat.DIRECTORY) return null
        } else if (!FormatDetector.isSupportedArchive(format) && format != LocalFormat.PDF) {
            // PDF 走按页渲染分支（见 LocalChapterProvider），此处保留。
            return null
        }
        val url = "$mangaName/${entry.name}"
        val baseName = entry.name.substringBeforeLast('.')
        var titleOverride: String? = null
        var number = ChapterRecognition.parseChapterNumber(mangaTitle, baseName)
        var scanlator: String? = null

        // 章节内 ComicInfo 覆盖（目录内 ComicInfo.xml / 归档内首个 ComicInfo.xml）。
        val comicInfo = readChapterComicInfo(url, format)
        if (comicInfo != null) {
            val details = comicInfo.toChapterDetails()
            titleOverride = details.title
            if (details.number != null) number = details.number
            scanlator = details.scanlator
        }

        return ScannedChapter(
            name = entry.name,
            url = url,
            dateUploadMillis = entry.lastModifiedMillis,
            number = number,
            scanlator = scanlator,
            titleOverride = titleOverride,
        )
    }

    private suspend fun readChapterComicInfo(url: String, format: LocalFormat): ComicInfo? {
        return try {
            when (format) {
                LocalFormat.DIRECTORY -> readComicInfo("$url/ComicInfo.xml")
                LocalFormat.EPUB, LocalFormat.CBZ, LocalFormat.ZIP, LocalFormat.TAR -> {
                    val reader = fs.openArchive(url)
                    val entry = reader.fileEntries().firstOrNull {
                        it.endsWith("ComicInfo.xml", ignoreCase = true)
                    } ?: return null
                    ComicInfoParser.parse(reader.readEntry(entry).decodeToString())
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun readComicInfo(path: String): ComicInfo? {
        return try {
            ComicInfoParser.parse(fs.readBytes(path).decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun readLegacyJson(path: String): MangaDetails? {
        // 遗留 MangaDetails json：只取标题/作者/简介键，缺键即 null（写回 xml 列后续）。
        return try {
            val text = fs.readBytes(path).decodeToString()
            fun field(vararg names: String): String? {
                for (name in names) {
                    Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                        .find(text)?.let { return it.groupValues[1] }
                }
                return null
            }
            val title = field("title")
            val author = field("author", "writer")
            val description = field("description", "summary")
            if (title == null && author == null && description == null) return null
            MangaDetails(title = title, author = author, description = description)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findEmbeddedComicInfo(
        mangaName: String,
        chapters: List<ScannedChapter>,
    ): ComicInfo? {
        for (chapter in chapters) {
            val format = FormatDetector.byExtension(chapter.name)
            if (!FormatDetector.isSupportedArchive(format) || format == LocalFormat.DIRECTORY) continue
            val info = readChapterComicInfo(chapter.url, format)
            if (info != null) return info
        }
        return null
    }

    private suspend fun firstImageCover(mangaName: String, chapters: List<ScannedChapter>): String? {
        for (chapter in chapters) {
            val format = FormatDetector.byExtension(chapter.name)
            if (format == LocalFormat.DIRECTORY) {
                val files = fs.listEntries(chapter.url)
                    .filter { !it.isDirectory && FormatDetector.isImageName(it.name) }
                    .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
                if (files.isNotEmpty()) return "${chapter.url}/${files.first().name}"
            } else if (FormatDetector.isSupportedArchive(format)) {
                val reader = try {
                    fs.openArchive(chapter.url)
                } catch (_: Exception) {
                    continue
                }
                val entry = reader.imageEntries()
                    .sortedWith(NaturalOrder)
                    .firstOrNull() ?: continue
                // 封面字节由调用方按需读取；此处返回归档内虚拟路径标记。
                return "${chapter.url}!/${entry.trimStart('/')}"
            }
        }
        return null
    }

    private fun entryPath(mangaName: String, entry: LocalEntry): String =
        if (mangaName.isEmpty()) entry.name else "$mangaName/${entry.name}"
}
