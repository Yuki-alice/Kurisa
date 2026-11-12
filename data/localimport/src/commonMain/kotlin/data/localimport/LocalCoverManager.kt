package data.localimport

/**
 * 本地封面管理。对齐 Komikku `LocalCoverManager`：
 * 查找（`nameWithoutExtension == "cover"` 的图片或 `cover.cbi`）→ 更新
 * （写 `cover.jpg` + 建 `.nomedia` 防图库扫描）→ 回填路径。
 * 加密变体（`cover.cbi`）随 CbzCrypto 列 P1，当前只读识别、不写加密。
 */
class LocalCoverManager(private val fs: LocalSourceFileSystem) {

    /** 在漫画目录内查找封面，返回其路径（无则 null）。 */
    suspend fun findCover(mangaName: String): String? {
        return fs.mangaEntries(mangaName).firstOrNull { entry ->
            !entry.isDirectory && isCoverFile(entry.name)
        }?.let { "$mangaName/${it.name}" }
    }

    /** 写入封面字节并建 `.nomedia`，返回封面路径。 */
    suspend fun updateCover(mangaName: String, bytes: ByteArray): String {
        ensureNoMedia(mangaName)
        val path = "$mangaName/cover.jpg"
        fs.writeBytes(path, bytes)
        return path
    }

    suspend fun ensureNoMedia(mangaName: String) {
        fs.writeBytes("$mangaName/.nomedia", byteArrayOf())
    }

    private fun isCoverFile(name: String): Boolean {
        if (name == "cover.cbi") return true
        return name.substringBeforeLast('.').equals("cover", ignoreCase = true) &&
            FormatDetector.isImageName(name)
    }
}
