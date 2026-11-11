package spike

import androidx.compose.foundation.Image
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.github.panpf.zoomimage.compose.ZoomState
import com.github.panpf.zoomimage.compose.subsampling.SubsamplingState
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ZoomImage 真渲染（desktop）：证明 Skiko + 图片管线 headless 可出像素且不 OOM。
 * 策略：Skia 生成 1024×1536 竖图 → Compose Image 离屏渲染 → PNG 落盘；
 * 同时引用 ZoomState/SubsamplingState（库 artifact 真链接，三端编译已过），
 * 超大图分块数学在 commonTest 锁定，真机手势压测留 M3。
 */
class ZoomRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderDoesNotOom() {
        System.setProperty("skiko.renderApi", "SOFTWARE")
        // 引用库类型，防“只编译通过、运行时缺 artifact”
        assertTrue(ZoomState::class.simpleName == "ZoomState")
        assertTrue(SubsamplingState::class.simpleName == "SubsamplingState")

        val w = 1024
        val h = 1536
        val bmp = Bitmap()
        bmp.allocN32Pixels(w, h)
        val canvas = Canvas(bmp)
        val paint = Paint()
        // 简单渐变竖图，模拟漫画页
        var y = 0
        while (y < h) {
            paint.color = (0xFF101014.toInt() + (y % 256 shl 16))
            canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, y.toFloat(), w.toFloat(), 32f), paint)
            y += 32
        }
        val img = bmp.asComposeImageBitmap()
        val out = File(System.getProperty("java.io.tmpdir"), "zoom-spike-${System.currentTimeMillis()}.png")
        val scene = ImageComposeScene(width = w / 2, height = h / 2, density = Density(1f)) {
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
