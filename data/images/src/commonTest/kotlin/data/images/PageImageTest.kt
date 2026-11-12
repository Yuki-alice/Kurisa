package data.images

import korlibs.image.bitmap.Bitmap32
import korlibs.image.color.RGBA
import korlibs.image.format.ImageDataContainer
import korlibs.image.format.ImageEncodingProps
import korlibs.image.format.JPEGEncoder
import korlibs.image.format.PNG
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageImageTest {

    private suspend fun solidPng(w: Int, h: Int, color: RGBA): ByteArray {
        val bmp = Bitmap32(w, h, color)
        return PNG.encodeSuspend(ImageDataContainer(bmp), ImageEncodingProps(filename = "t.png"))
    }

    private suspend fun borderedPng(): ByteArray {
        val white = RGBA(255, 255, 255, 255)
        val black = RGBA(0, 0, 0, 255)
        val bmp = Bitmap32(20, 20, white)
        for (y in 5..14) {
            for (x in 5..14) {
                bmp[x, y] = black
            }
        }
        return PNG.encodeSuspend(ImageDataContainer(bmp), ImageEncodingProps(filename = "t.png"))
    }

    @Test
    fun sizeOfPng() = runTest {
        val size = PageImageInfo.sizeOf(solidPng(20, 30, RGBA(255, 0, 0, 255)))
        assertEquals(20, size?.width)
        assertEquals(30, size?.height)
        assertEquals(false, size?.isWide)
    }

    @Test
    fun sizeOfJpegHeaderOnly() = runTest {
        // korlibs 编码器产出的真实 JPEG（header 解析不碰熵数据）。
        val jpeg = JPEGEncoder.encode(Bitmap32(300, 400, RGBA(10, 20, 30, 255)))
        val size = PageImageInfo.sizeOf(jpeg)
        assertEquals(300, size?.width)
        assertEquals(400, size?.height)
        assertEquals(false, size?.isWide)
        val wideJpeg = JPEGEncoder.encode(Bitmap32(800, 400, RGBA(10, 20, 30, 255)))
        assertEquals(true, PageImageInfo.sizeOf(wideJpeg)?.isWide)
    }

    @Test
    fun sizeOfGarbageIsNull() = runTest {
        assertNull(PageImageInfo.sizeOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)))
        assertNull(PageImageInfo.sizeOf(byteArrayOf()))
    }

    @Test
    fun cropRemovesUniformBorder() = runTest {
        val cropped = CropBorders.crop(borderedPng())
        assertNotNull(cropped)
        val size = PageImageInfo.sizeOf(cropped)
        assertEquals(10, size?.width)
        assertEquals(10, size?.height)
    }

    @Test
    fun cropNoOpOnBorderless() = runTest {
        assertNull(CropBorders.crop(solidPng(20, 20, RGBA(0, 0, 0, 255))))
    }

    @Test
    fun cropRejectsGarbageAndTiny() = runTest {
        assertNull(CropBorders.crop(byteArrayOf(1, 2, 3)))
        assertNull(CropBorders.crop(solidPng(2, 2, RGBA(255, 255, 255, 255))))
    }

    @Test
    fun cropRespectsMaxPixels() = runTest {
        // 20x20=400px > 上限 100 → 跳过。
        assertNull(CropBorders.crop(borderedPng(), maxPixels = 100))
        assertTrue(CropBorders.crop(borderedPng(), maxPixels = 10_000) != null)
    }
}
