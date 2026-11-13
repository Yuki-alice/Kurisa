package data.localimport

import korlibs.io.file.VfsFile
import korlibs.io.file.std.openAsZip
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

/**
 * 归档读取器。对齐 Komikku `core/archive ArchiveReader` + `ArchivePageLoader`：
 * 中央目录枚举条目 → 按需解包单条目（即 Komikku `LOAD_FROM_FILE` 语义：省内存，
 * 翻页有解码延迟；`LOAD_INTO_MEMORY / CACHE_TO_DISK` 后续叠加）。
 *
 * 两类来源互斥，由 [open] 保证其一非空：
 * - zip 系：`root`（korlibs-io ZipVfs，stored + deflated，三端一致）；
 * - TAR 系：`tar`（纯 Kotlin ustar 解析，见 [TarArchive]）。
 * RAR / 7z 仍不支持，由 [FormatDetector.isSupportedArchive] 前置拦截。
 */
class ArchiveReader private constructor(
    private val root: VfsFile?,
    private val tar: TarArchive?,
) {

    /** 图片条目路径（归档内顺序；展示排序由调用方 [NaturalOrder] 做）。 */
    suspend fun imageEntries(): List<String> =
        if (tar != null) {
            tar.names().filter { FormatDetector.isImageName(it) }
        } else {
            root!!.listRecursive()
                .map { it to it.stat() }
                .filter { !it.second.isDirectory && FormatDetector.isImageName(it.first.path) }
                .map { it.first.path }
                .toList()
        }

    /** 全部文件条目路径（含非图片，用于 ComicInfo 查找）。 */
    suspend fun fileEntries(): List<String> =
        if (tar != null) {
            tar.names()
        } else {
            root!!.listRecursive()
                .map { it to it.stat() }
                .filter { !it.second.isDirectory }
                .map { it.first.path }
                .toList()
        }

    /** 按需解包单个条目；path 必须与 [imageEntries]/[fileEntries] 返回的字符串同名可查。 */
    suspend fun readEntry(path: String): ByteArray =
        if (tar != null) {
            tar.read(path) ?: throw UnknownFormatException("TAR entry not found: $path")
        } else {
            VfsFile(root!!.vfs, path).readBytes()
        }

    companion object {
        /** 从归档文件打开；zip / TAR 之外抛 [UnknownFormatException]。 */
        suspend fun open(archiveFile: VfsFile): ArchiveReader {
            val all = archiveFile.readBytes()
            val header = all.take(4).toByteArray()
            if (FormatDetector.byMagic(header) == LocalFormat.ZIP) {
                return ArchiveReader(archiveFile.openAsZip(), null) // zip 分支行为零变
            }
            val ext = FormatDetector.byExtension(archiveFile.path)
            if (ext == LocalFormat.TAR || TarArchive.hasUstarMagic(all)) {
                return ArchiveReader(null, TarArchive.parse(all))
            }
            throw UnknownFormatException(archiveFile.path)
        }
    }
}
