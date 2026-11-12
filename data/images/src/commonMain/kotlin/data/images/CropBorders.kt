package data.images

import korlibs.image.bitmap.Bitmap32
import korlibs.image.format.BMP
import korlibs.image.format.GIF
import korlibs.image.format.ImageDataContainer
import korlibs.image.format.ImageEncodingProps
import korlibs.image.format.ImageFormatsMutable
import korlibs.image.format.JPEG
import korlibs.image.format.PNG
import korlibs.image.format.WEBP
import korlibs.io.stream.openSync

/**
 * 自动裁边。对齐 Komikku `cropBorders`（切除纯白/纯黑边）：
 * 上下左右四边向内扫描，边行/列像素与角像素的差在阈值内即视为边。
 *
 * 约束（解码层安全红线）：
 * - 动图/特殊格式不碰：GIF 多帧、AVIF/HEIC/JXL（common 侧无解码器）直接返回 null；
 *   重编码会丢动画或失败，宁可不裁。
 * - EXIF 非正常方向不碰（header 层方向不可靠，旋转语义待验证）。
 * - 超大图熔断（默认 24MP）：common 全解码常驻内存，长条页走 ZoomImage subsampling
 *   显示即可，不在此处冒险。
 * - 误杀保护：剩余任一边 < 8px 则视为误判，返回 null。
 */
object CropBorders {
    /** 默认最大处理像素（24MP，约 6000x4000）。 */
    const val DEFAULT_MAX_PIXELS = 24_000_000L

    /**
     * @param threshold 通道差阈值（0~255，默认 12）。
     * @return 裁剪后的 PNG 字节；无需裁剪/不支持/失败返回 null（调用方用原字节）。
     */
    suspend fun crop(
        bytes: ByteArray,
        threshold: Int = 12,
        maxPixels: Long = DEFAULT_MAX_PIXELS,
    ): ByteArray? {
        if (bytes.isEmpty()) return null
        return try {
            cropOrNull(bytes, threshold.coerceIn(0, 255), maxPixels)
        } catch (_: Exception) {
            null
        }
    }

    private val formats = ImageFormatsMutable().register(PNG, JPEG, GIF, BMP, WEBP)

    private suspend fun cropOrNull(bytes: ByteArray, threshold: Int, maxPixels: Long): ByteArray? {
        val container = formats.readImageContainer(bytes.openSync())
        // 动图保护：多帧只取首帧会丢动画，直接跳过。
        if (container.imageDatas.any { it.frames.size > 1 }) return null
        val bmp = container.mainBitmap.toBMP32IfRequired()
        if (bmp.width.toLong() * bmp.height > maxPixels) return null
        if (bmp.width < 3 || bmp.height < 3) return null

        val rect = findContentRect(bmp, threshold) ?: return null
        val (left, top, right, bottom) = rect
        // 无需裁剪。
        if (left == 0 && top == 0 && right == bmp.width - 1 && bottom == bmp.height - 1) return null
        // 误杀保护。
        if (right - left + 1 < 8 || bottom - top + 1 < 8) return null

        val cropped = bmp.copySliceWithBounds(left, top, right + 1, bottom + 1)
        return PNG.encodeSuspend(ImageDataContainer(cropped), ImageEncodingProps(filename = "crop.png"))
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun findContentRect(bmp: Bitmap32, threshold: Int): Rect? {
        val w = bmp.width
        val h = bmp.height
        // 四角颜色：取出现最多的角颜色为边色（漫画以白/黑边为主）。
        val edge = bmp[0, 0]
        fun similar(x: Int, y: Int): Boolean = near(bmp[x, y], edge, threshold)

        var top = 0
        while (top < h && (0 until w).all { similar(it, top) }) top++
        var bottom = h - 1
        while (bottom > top && (0 until w).all { similar(it, bottom) }) bottom--
        var left = 0
        while (left < w && (top..bottom).all { similar(left, it) }) left++
        var right = w - 1
        while (right > left && (top..bottom).all { similar(right, it) }) right--
        return Rect(left, top, right, bottom)
    }

    private fun near(a: korlibs.image.color.RGBA, b: korlibs.image.color.RGBA, threshold: Int): Boolean {
        return diff(a.r, b.r) <= threshold &&
            diff(a.g, b.g) <= threshold &&
            diff(a.b, b.b) <= threshold
    }

    private fun diff(a: Int, b: Int): Int = if (a >= b) a - b else b - a
}
