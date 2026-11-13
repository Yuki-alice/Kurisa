package data.repository

import core.model.LocalBook
import data.localimport.FormatDetector
import data.localimport.LOCAL_WORK_PREFIX
import data.localimport.LocalFormat
import data.localimport.LocalSourceFileSystem

/** 导入结果（已入库书 + 因格式不支持而跳过的文件名）。 */
data class LocalImportResult(val imported: List<LocalBook>, val skipped: List<String>)

/**
 * 本地书导入（选择器 UI 只负责选文件 + 读字节；落盘与入库在此处）。
 *
 * 约定（与 [`LocalRoutingChapterProvider`] / [`LocalLibrarySync`] 同构）：
 * - 每个文件落为 `books/<净书名>/<原文件名>`，书 id = `"local:<净书名>"`；
 * - 净书名 = 去扩展名的文件名经非法字符替换（保留 CJK/空格/点/横线/下划线）；
 * - 仅接受 TXT/EPUB/CBZ/ZIP/PDF（归档读取器仅支持 zip 系 + 纯文本；RAR/7z/TAR 拒绝）；
 * - 同名覆盖（幂等：重复导入同一文件结果一致）；
 * - `imported_at` 保留首次值（已存在行不刷新）。
 */
class LocalBookImporter(
    private val fs: LocalSourceFileSystem,
    private val books: LocalBookRepository,
    private val nowMs: () -> Long,
) {
    suspend fun import(files: List<Pair<String, ByteArray>>): LocalImportResult {
        val imported = mutableListOf<LocalBook>()
        val skipped = mutableListOf<String>()
        for ((rawName, bytes) in files) {
            val fileName = sanitizeFileName(rawName.substringAfterLast('/').substringAfterLast('\\'))
            if (fileName.isEmpty()) {
                skipped += rawName
                continue
            }
            val format = FormatDetector.byExtension(fileName)
            if (format !in SUPPORTED_IMPORT_FORMATS) {
                skipped += fileName
                continue
            }
            val dirName = sanitizeFileName(fileName.substringBeforeLast('.', fileName))
            if (dirName.isEmpty()) {
                skipped += fileName
                continue
            }
            runCatching {
                fs.ensureDir(dirName)
                fs.writeBytes("$dirName/$fileName", bytes)
            }.getOrElse {
                skipped += fileName
                continue
            }
            val id = "$LOCAL_WORK_PREFIX$dirName"
            val existing = books.find(id)
            val book = LocalBook(
                id = id,
                path = dirName,
                format = format.name.lowercase(),
                title = dirName,
                cover = existing?.cover,
                importedAtEpochMs = existing?.importedAtEpochMs ?: nowMs(),
            )
            runCatching { books.upsert(book) }.getOrElse {
                skipped += fileName
                continue
            }
            imported += book
        }
        return LocalImportResult(imported, skipped)
    }

    /**
     * 删除本地书（目录递归删 + 落库行删除；不存在即视为成功）。
     * 调用方随后重跑 [`LocalLibrarySync`] 刷新展示。
     */
    suspend fun remove(workId: String): Boolean {
        val book = books.find(workId) ?: return true
        runCatching { fs.deleteRecursively(book.path) }
        runCatching { books.remove(workId) }
        return books.find(workId) == null
    }

    /** 净化文件名：去路径分隔符与控制字符，保留 CJK/空格/点/横线/下划线，上限 100 字。 */
    internal fun sanitizeFileName(raw: String): String {
        val cleaned = raw.map { c ->
            if (c == '/' || c == '\\' || c.code < 0x20) '_' else c
        }.joinToString("").trim().trim('.', ' ', '_')
        return cleaned.take(100)
    }

    companion object {
        val SUPPORTED_IMPORT_FORMATS = setOf(
            LocalFormat.TXT,
            LocalFormat.EPUB,
            LocalFormat.CBZ,
            LocalFormat.ZIP,
            LocalFormat.PDF,
        )
    }
}
