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
 * 实现为纯 `commonMain`（korlibs-io ZipVfs，stored + deflated，三端一致），
 * RAR/7z/TAR 不在 Zip 系内，由 [FormatDetector.isSupportedArchive] 前置拦截。
 */
class ArchiveReader private constructor(private val root: VfsFile) {

    /** 图片条目路径（中央目录顺序；展示排序由调用方 [NaturalOrder] 做）。 */
    suspend fun imageEntries(): List<String> =
        root.listRecursive()
            .map { it to it.stat() }
            .filter { !it.second.isDirectory && FormatDetector.isImageName(it.first.path) }
            .map { it.first.path }
            .toList()

    /** 全部文件条目路径（含非图片，用于 ComicInfo 查找）。 */
    suspend fun fileEntries(): List<String> =
        root.listRecursive()
            .map { it to it.stat() }
            .filter { !it.second.isDirectory }
            .map { it.first.path }
            .toList()

    /** 按需解包单个条目。 */
    suspend fun readEntry(path: String): ByteArray =
        VfsFile(root.vfs, path).readBytes()

    companion object {
        /** 从归档文件打开；非 zip 容器抛 [UnknownFormatException]。 */
        suspend fun open(archiveFile: VfsFile): ArchiveReader {
            val header = archiveFile.readBytes().take(4).toByteArray()
            if (FormatDetector.byMagic(header) != LocalFormat.ZIP) {
                throw UnknownFormatException(archiveFile.path)
            }
            return ArchiveReader(archiveFile.openAsZip())
        }
    }
}
