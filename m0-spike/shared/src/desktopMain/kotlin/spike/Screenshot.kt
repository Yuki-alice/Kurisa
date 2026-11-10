package spike

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * M0 渲染验证：用 ImageComposeScene 离屏渲染 SpikeApp 并导出 PNG。
 *
 * 不走窗口系统，headless 环境（CI / 容器）可直接运行，用于把
 * "MD3E 组件编译通过" 升级为 "MD3E 组件真实渲染出像素"。
 * 强制 SOFTWARE 渲染，避免无 GPU 环境初始化 Skiko 失败。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    System.setProperty("skiko.renderApi", "SOFTWARE")

    val out = File(args.firstOrNull() ?: "build/screenshots/spike-desktop.png")
    out.parentFile?.mkdirs()

    val scene = ImageComposeScene(
        width = 1000,
        height = 720,
        density = Density(2f),
    ) {
        SpikeApp()
    }

    try {
        val image = scene.render()
        val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) {
            "PNG encode returned null"
        }
        out.writeBytes(data.bytes)
        println("SCREENSHOT=${out.absolutePath}")
        println("SCREENSHOT_BYTES=${data.size}")
    } finally {
        scene.close()
    }
}
