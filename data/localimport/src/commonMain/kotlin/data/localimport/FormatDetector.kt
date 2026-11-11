package data.localimport

/** 本地书格式（M4 扩展解析器；识别层先行）。 */
enum class LocalFormat { EPUB, TXT, CBZ, ZIP, PDF, IMAGE_DIR, UNKNOWN }

/** 格式识别：扩展名优先，魔数兜底（纯 Kotlin，三端一致）。 */
object FormatDetector {
    fun byExtension(name: String): LocalFormat {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "epub" -> LocalFormat.EPUB
            "txt", "text" -> LocalFormat.TXT
            "cbz" -> LocalFormat.CBZ
            "zip" -> LocalFormat.ZIP
            "pdf" -> LocalFormat.PDF
            else -> LocalFormat.UNKNOWN
        }
    }

    /** 魔数（取文件头即可，不读全文件）。 */
    fun byMagic(header: ByteArray): LocalFormat {
        if (header.size >= 4 &&
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        ) {
            return LocalFormat.ZIP // EPUB/CBZ 同为 zip 容器，由扩展名/内部结构再分
        }
        if (header.size >= 5 &&
            header[0] == 0x25.toByte() && header[1] == 0x50.toByte() &&
            header[2] == 0x44.toByte() && header[3] == 0x46.toByte() &&
            header[4] == 0x2D.toByte()
        ) {
            return LocalFormat.PDF // %PDF-
        }
        return LocalFormat.UNKNOWN
    }

    /** 综合判定：扩展名未知时用魔数；zip 容器以扩展名细分。 */
    fun detect(name: String, header: ByteArray): LocalFormat {
        val ext = byExtension(name)
        if (ext != LocalFormat.UNKNOWN && ext != LocalFormat.ZIP) return ext
        val magic = byMagic(header)
        if (magic == LocalFormat.ZIP) {
            // zip 容器：扩展名说了算（epub/cbz），裸 .zip 保持 ZIP
            return if (ext == LocalFormat.ZIP) LocalFormat.ZIP else ext
        }
        if (magic != LocalFormat.UNKNOWN) return magic
        return ext
    }
}
