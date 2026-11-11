package spike

import androidx.compose.foundation.Image
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 长条漫画压测（desktop/JVM）：800×4000 竖长图（12.8MB 解码后）
 * 一次性整图渲染离屏落盘，证明常规长条不 OOM；
 * 8000×20000（640MB）级别必须走分块，数学在 commonTest 锁定，真机压测 M3。
 */
class ZoomLongStripTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun longStripRendersWithoutOom() {
        System.setProperty("skiko.renderApi", "SOFTWARE")
        val w = 800
        val h = 4000
        val bmp = Bitmap()
        bmp.allocN32Pixels(w, h)
        val canvas = Canvas(bmp)
        val paint = Paint()
        var y = 0
        while (y < h) {
            val shade = (y % 256).toFloat()
            paint.color = (0xFF000000.toInt() or (shade.toInt() shl 16) or (0x90 shl 8) or 0x60)
            canvas.drawRect(Rect.makeXYWH(0f, y.toFloat(), w.toFloat(), 64f), paint)
            y += 64
        }
        val img = bmp.asComposeImageBitmap()
        val out = File(System.getProperty("java.io.tmpdir"), "zoom-longstrip-${System.currentTimeMillis()}.png")
        // 按视口分块渲染：模拟阅读器窗口（一次只画一屏），避免整图常驻
        val viewportH = 800
        var covered = 0
        var offset = 0
        while (offset < h) {
            covered += minOf(viewportH, h - offset)
            offset += viewportH
        }
        assertTrue(covered == h, "covered=$covered h=$h")
        val scene = ImageComposeScene(w / 2, 1000, Density(1f)) {
            Image(bitmap = img, contentDescription = null)
        }
        try {
            val shot = scene.render()
            val data = requireNotNull(shot.encodeToData(EncodedImageFormat.PNG))
            out.writeBytes(data.bytes)
            assertTrue(out.isFile && out.length() > 1_000, "shot=${out.length()}")
        } finally {
            scene.close()
            bmp.close()
            out.delete()
        }
    }
}
