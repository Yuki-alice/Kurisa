package app.shared

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/**
 * 端到端 walkthrough 的共用固件（S3）。
 *
 * 素材来源：[sampleLibrary] 是仓库内的真实漫画样例库
 * （Pepper & Carrot，David Revoy，CC-BY 4.0，见该目录 README）。
 * 之前这里用 Java2D 画色块当"漫画页"——测试能过，但截图证明不了任何事：
 * 一张 4 色的图既不能验证解码器，也不能验证渲染管线，更不能给人看。
 * 现在测试输入就是真漫画，截图即真实阅读效果。
 */
object WalkthroughSupport {

    /** 仓库根（从测试工作目录向上找 `docs/contract/manga-example.js`）。 */
    val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "docs/contract/manga-example.js").isFile }
            ?: error("未找到仓库根（docs/contract/manga-example.js）")
    }

    val screenshotsDir: File by lazy {
        File(repoRoot, "docs/screenshots").apply { mkdirs() }
    }

    /** 真实漫画样例库（`docs/sample-library`，三话 + 一话目录版）。 */
    val sampleLibrary: File by lazy {
        File(repoRoot, "docs/sample-library").also {
            check(it.isDirectory) { "缺少真实漫画样例库：${it.absolutePath}" }
        }
    }

    /**
     * 把样例库复制到可写的临时目录（测试不写仓库，也不改用户库）。
     *
     * 复制而非软链：Windows 上软链要管理员权限，CI 不应依赖平台特性。
     */
    fun stageSampleLibrary(target: File): File {
        target.mkdirs()
        sampleLibrary.listFiles().orEmpty().filter { it.isDirectory }.forEach { work ->
            work.copyRecursively(File(target, work.name), overwrite = true)
        }
        return target
    }

    /**
     * 样例库中某一话的 CBZ。
     *
     * 先精确匹配目录名，再退回「前缀匹配且目录内确有 CBZ」：库里有
     * `Pepper & Carrot EP06` 与 `Pepper & Carrot EP06 目录版` 两个同前缀目录，
     * 只按前缀取首个会命中目录形态（没有 CBZ）而失败。
     */
    fun cbzOf(workDirPrefix: String): File {
        val dirs = sampleLibrary.listFiles().orEmpty().filter { it.isDirectory }
        fun cbzIn(dir: File) = dir.listFiles().orEmpty().firstOrNull { it.extension.equals("cbz", true) }
        val dir = dirs.firstOrNull { it.name == workDirPrefix }
            ?: dirs.firstOrNull { it.name.startsWith(workDirPrefix) && cbzIn(it) != null }
            ?: error("样例库缺少作品：$workDirPrefix")
        return cbzIn(dir) ?: error("作品目录内无 CBZ：${dir.absolutePath}")
    }

    /** 解出 CBZ 内的全部页面字节（自然序），供网络链路复用同一批真实素材。 */
    fun cbzPages(cbz: File): List<ByteArray> =
        java.util.zip.ZipFile(cbz).use { zip ->
            zip.entries().toList()
                .filter { !it.isDirectory }
                .sortedBy { it.name }
                .map { zip.getInputStream(it).readBytes() }
        }

    /** 截图落盘：`docs/screenshots/`（证据）与 `build/screenshots/`（构建产物）。 */
    fun save(name: String, image: ImageBitmap): File {
        val file = File(screenshotsDir, name)
        ImageIO.write(image.toAwtImage(), "png", file)
        val buildDir = File("build/screenshots").apply { mkdirs() }
        ImageIO.write(image.toAwtImage(), "png", File(buildDir, name))
        println("SCREENSHOT_SAVED: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    /**
     * 非空白断言：整图按步长 2 采样，颜色数不足即判空白。
     *
     * 不能用「固定网格」——真实页面（尤其详情页）内容集中在上半屏，网格采样会
     * 全部落在背景上，把正常渲染误判成空白。
     */
    fun assertRendered(image: ImageBitmap, label: String, minColors: Int = 6) {
        val colors = countColors(image)
        assertTrue(colors >= minColors, "$label 疑似空白：仅 $colors 色")
    }

    /**
     * 「真的画出了照片级内容」断言：整图不同颜色数。
     *
     * 这是区分真实漫画与合成色块的关键门禁——色块图（白底 + 蓝条 + 单色块）
     * 全图不超过 5 色，而真实手绘扫描页在 JPEG 质量 85 下通常有数千色。
     * 阈值取 800：既能挡住占位/纯色，也不会因主题色深浅误伤。
     */
    fun assertPhotoLike(image: ImageBitmap, label: String, minColors: Int = 800) {
        val colors = countColors(image)
        assertTrue(colors >= minColors, "$label 不像真实画面：仅 $colors 色（阈值 $minColors）")
    }

    /** 按步长 2 采样统计不同颜色数（封顶 4096，够判真假且不拖慢测试）。 */
    private fun countColors(image: ImageBitmap): Int {
        val map = image.toPixelMap()
        val colors = HashSet<Int>()
        var y = 0
        while (y < map.height) {
            var x = 0
            while (x < map.width) {
                val c = map[x, y]
                colors += (c.red * 255).toInt() shl 16 or
                    ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
                if (colors.size >= 4096) return colors.size
                x += 2
            }
            y += 2
        }
        return colors.size
    }
}
