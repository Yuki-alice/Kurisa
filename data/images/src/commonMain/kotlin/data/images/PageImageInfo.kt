package data.images

import core.reader.PageSize
import korlibs.image.format.BMP
import korlibs.image.format.GIF
import korlibs.image.format.ImageFormatsMutable
import korlibs.image.format.JPEG
import korlibs.image.format.PNG
import korlibs.image.format.WEBP
import korlibs.io.stream.openSync

/**
 * 页图元信息（header-only，不解码全图）。
 * 对齐 Komikku `PagerPageHolder` 的宽高比判定（fullPage / isolatedPage），
 * 但前移到字节入库时一次算出，供双页 spread 预排。
 *
 * JPEG EXIF orientation 在 header 层不可靠（korlibs 仅完整 JPEG 路径回填），
 * 此处不做旋转补偿；显示侧 Coil 默认遵 EXIF，误判仅影响 EXIF 旋转图的配对。
 */
object PageImageInfo {
    /** 纯 Kotlin 解码的格式集合（JPEG/PNG/GIF/BMP/WebP）。 */
    private val formats = ImageFormatsMutable().register(PNG, JPEG, GIF, BMP, WEBP)
    /** 图片字节 → 尺寸；未知格式/损坏返回 null（调用方按非宽页处理）。 */
    fun sizeOf(bytes: ByteArray): PageSize? {
        if (bytes.size < 16) return null
        return try {
            val info = formats.decodeHeader(bytes.openSync()) ?: return null
            if (info.width <= 0 || info.height <= 0) return null
            PageSize(info.width, info.height)
        } catch (_: Exception) {
            null
        }
    }
}
