package data.localimport

import korlibs.io.file.VfsFile
import korlibs.io.file.baseName
import korlibs.io.file.std.localVfs

/**
 * 本地源文件系统。对齐 Komikku `LocalSourceFileSystem`（base/漫画/章节三级），
 * 但实现为纯 `commonMain`（korlibs-io `localVfs`，三端一致），**不引入 expect/actual**：
 * 平台差异只在根目录字符串（由 `core.platform.AppDirectories` 各端实现提供）。
 *
 * 隐藏文件（`.` 开头）默认过滤（对齐 Komikku，SY `allowHiddenFiles` 后续加开关）。
 */
class LocalSourceFileSystem(
    private val root: VfsFile,
    private val allowHiddenFiles: Boolean = false,
) {
    suspend fun baseEntries(): List<LocalEntry> = listEntries("")

    suspend fun mangaEntries(mangaName: String): List<LocalEntry> = listEntries(mangaName)

    suspend fun listEntries(dir: String): List<LocalEntry> = visibleChildren(root[dir])

    suspend fun readBytes(path: String): ByteArray = root[path].readBytes()

    suspend fun writeBytes(path: String, bytes: ByteArray) {
        root[path].writeBytes(bytes)
    }

    /** 打开归档（zip 系；RAR/7z/TAR 由调用方前置 [FormatDetector.isSupportedArchive] 拦截）。 */
    suspend fun openArchive(path: String): ArchiveReader = ArchiveReader.open(root[path])

    suspend fun readHeader(path: String, size: Int = 8): ByteArray {
        val bytes = root[path].readBytes()
        return if (bytes.size <= size) bytes else bytes.copyOf(size)
    }

    private suspend fun visibleChildren(dir: VfsFile): List<LocalEntry> {
        return dir.listSimple().mapNotNull { file ->
            val name = file.baseName
            if (!allowHiddenFiles && name.startsWith(".")) return@mapNotNull null
            val stat = file.stat()
            LocalEntry(
                name = name,
                path = file.path,
                isDirectory = stat.isDirectory,
                lastModifiedMillis = stat.modifiedTime.unixMillisLong,
            )
        }
    }

    companion object {
        /** 生产入口：根目录由调用方（AppDirectories + 用户选择的本地库目录）传入。 */
        fun fromDirectory(rootDir: String, allowHiddenFiles: Boolean = false) =
            LocalSourceFileSystem(localVfs(rootDir), allowHiddenFiles)
    }
}

/** base/漫画/章节任一级的条目（对齐 Komikku `UniFile` 的使用面：名/目录/修改时间）。 */
data class LocalEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val lastModifiedMillis: Long,
)
