package data.localimport

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.image.format.JPEG

/**
 * 真实格式图片固件（korlibs JPEG 编码器现场生成有效字节，
 * 无外部二进制文件；解码/尺寸/裁剪链路吃到的是真 JPEG）。
 */
object RealImageFixtures {
    /** 白底 + 黑边框 + 中央色块的漫画页。 */
    fun mangaPage(w: Int, h: Int, r: Int, g: Int, b: Int): ByteArray {
        val bmp = Bitmap32(w, h)
        val border = (minOf(w, h) / 20).coerceAtLeast(2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp[x, y] = when {
                    x < border || y < border || x >= w - border || y >= h - border ->
                        RGBA(255, 255, 255, 255)
                    x in w / 3..2 * w / 3 && y in h / 3..2 * h / 3 ->
                        RGBA(r, g, b, 255)
                    else -> RGBA(240, 240, 240, 255)
                }
            }
        }
        return JPEG.encode(bmp)
    }
}
