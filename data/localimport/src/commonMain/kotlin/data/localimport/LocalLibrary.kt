package data.localimport

import data.images.ImageRequest

/**
 * 本地库作品（书架/详情页的展示模型，纯数据，不依赖 UI 与仓储）。
 *
 * [workId] 用 `local:<漫画目录名>`：本地作品没有站点 sourceWorkId，也无法从
 * `workStableId` 的 sha256 反解，因此约定前缀由组合器按前缀分派
 * （见 `docs/comic-e2e-plan.md` §6.3 的决策）。
 */
data class LocalWork(
    val workId: String,
    val mangaName: String,
    val title: String,
    val chapterCount: Int,
    val coverPath: String? = null,
    val modifiedAtMillis: Long = 0L,
    /**
     * 封面原始字节（归档首页 / 目录首图），供书架网格直接渲染。
     *
     * 读不到（无图、格式不支持）时为 null，UI 退回首字占位——封面不是阅读的前置条件。
     */
    val coverBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalWork) return false
        return workId == other.workId &&
            mangaName == other.mangaName &&
            title == other.title &&
            chapterCount == other.chapterCount &&
            coverPath == other.coverPath &&
            modifiedAtMillis == other.modifiedAtMillis
    }

    override fun hashCode(): Int {
        var result = workId.hashCode()
        result = 31 * result + mangaName.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + chapterCount
        result = 31 * result + (coverPath?.hashCode() ?: 0)
        result = 31 * result + modifiedAtMillis.hashCode()
        return result
    }
}

// `LOCAL_WORK_PREFIX` 定义在 LocalRoutingChapterProvider.kt（M4 两侧共用一份）。

/** 本地作品 id 构造。 */
fun localWorkId(mangaName: String): String = LOCAL_WORK_PREFIX + mangaName

/** 从本地作品 id 还原目录名；非本地 id 回 null。 */
fun localMangaName(workId: String): String? =
    workId.takeIf { it.startsWith(LOCAL_WORK_PREFIX) }?.removePrefix(LOCAL_WORK_PREFIX)

/**
 * 本地库目录扫描（B1/B2 的数据出口）。
 *
 * 与 [LocalChapterProvider] 共用同一个 [LocalSourceFileSystem]：列表页只读
 * 目录元数据与章节数，章节内容仍在打开时由 provider 惰性读取。
 * 隐藏目录、非目录条目一律过滤（对齐 Komikku 的 base 扫描语义）。
 */
class LocalLibrary(
    val fs: LocalSourceFileSystem,
    private val scanner: LocalSourceScanner = LocalSourceScanner(fs),
) {
    /** 封面读取复用图片出口（归档内条目 / 目录文件同一套规则）。 */
    private val images = LocalImageFetcher(fs)

    /** 封面字节缓存：同一库反复刷新时避免重复解包读取。 */
    private val coverCache = HashMap<String, ByteArray?>()

    /** 扫描根目录下的全部作品（按标题自然序，与书架展示一致）。 */
    suspend fun works(): List<LocalWork> =
        scanner.scanBase()
            .filter { it.isDirectory }
            .map { entry ->
                val scanned = scanner.scanManga(entry.name)
                LocalWork(
                    workId = localWorkId(entry.name),
                    mangaName = entry.name,
                    title = scanned.details?.title?.takeIf { it.isNotBlank() } ?: entry.name,
                    chapterCount = scanned.chapters.size,
                    coverPath = scanned.coverPath,
                    modifiedAtMillis = entry.lastModifiedMillis,
                    coverBytes = scanned.coverPath?.let { readCover(it) },
                )
            }

    /** 单个作品（详情页刷新用；未知目录返回 null）。 */
    suspend fun work(mangaName: String): LocalWork? =
        works().firstOrNull { it.mangaName == mangaName }

    /** 本库的章节提供方（workId 前缀分派在组合器完成）。 */
    fun chapterProvider(): LocalChapterProvider =
        LocalChapterProvider(fs, scanner) { workId ->
            localMangaName(workId) ?: workId
        }

    /**
     * 读封面字节；失败一律 null（封面只影响观感，不能阻断书架）。
     *
     * 返回原图字节而非缩略图：本地库单本封面通常 100KB–3MB，Coil 侧按目标尺寸
     * 采样解码；真正需要缩略图缓存时随 M5 的封面仓储一起做。
     */
    private suspend fun readCover(path: String): ByteArray? =
        coverCache.getOrPut(path) {
            runCatching { images.fetch(ImageRequest(path, emptySet())).bytes }.getOrNull()
        }
}
