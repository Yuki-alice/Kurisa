package spike

import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Navigation3 真 NavDisplay 集成（desktop/headless）。
 * N1–N4 的纯列表语义在 commonTest 锁定；这里证明同一套 route keys +
 * entryProvider + NavDisplay 在真实 Compose 运行时可渲染、不崩，
 * 窄/宽两档各出一张像素截图（M1 建三端截图基线时复用）。
 */
@Serializable
data object Shelf : NavKey

@Serializable
data class Detail(val workId: String) : NavKey

@Serializable
data class Reader(val workId: String, val chapterId: String) : NavKey

private val navSerializers = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(Shelf::class, Shelf.serializer())
        subclass(Detail::class, Detail.serializer())
        subclass(Reader::class, Reader.serializer())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
class NavDisplayRenderTest {

    private fun render(routes: List<NavKey>, width: Int, height: Int, out: File) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
        out.parentFile?.mkdirs()
        val config = SavedStateConfiguration { serializersModule = navSerializers }
        // NavDisplay 内部 rememberLifecycleOwner 要求 Desktop 主线程（EDT），
        // 整个 scene 创建 + 渲染都在 EDT 内执行（JUnit 工作线程会抛 addObserver 异常）。
        val holder = arrayOf<ByteArray?>(null)
        var thrown: Throwable? = null
        javax.swing.SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width, height, Density(1f)) {
                val backStack = rememberNavBackStack(config, *routes.toTypedArray())
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = entryProvider {
                        entry<Shelf> { Text("Shelf") }
                        entry<Detail> { Text("Detail ${it.workId}") }
                        entry<Reader> { Text("Reader ${it.workId}/${it.chapterId}") }
                    }
                )
            }
            try {
                val img = scene.render()
                holder[0] = requireNotNull(img.encodeToData(EncodedImageFormat.PNG)).bytes
            } catch (t: Throwable) {
                thrown = t
            } finally {
                scene.close()
            }
        }
        thrown?.let { throw it }
        out.writeBytes(requireNotNull(holder[0]) { "render produced no bytes" })
    }

    @Test
    fun phoneSinglePaneRenders() {
        val out = File(System.getProperty("java.io.tmpdir"), "nav-spike-phone-${System.currentTimeMillis()}.png")
        try {
            render(listOf(Shelf), 400, 800, out)
            assertTrue(out.isFile && out.length() > 1_000, "shot=${out.length()}")
        } finally {
            out.delete()
        }
    }

    @Test
    fun tabletTwoEntriesRender() {
        val out = File(System.getProperty("java.io.tmpdir"), "nav-spike-tablet-${System.currentTimeMillis()}.png")
        try {
            render(listOf(Shelf, Detail("w1")), 1000, 800, out)
            assertTrue(out.isFile && out.length() > 1_000, "shot=${out.length()}")
        } finally {
            out.delete()
        }
    }

    @Test
    fun deepLinkStackRenders() {
        // N4：外链回放 [Shelf, Detail, Reader] 直接作为初始栈渲染
        val out = File(System.getProperty("java.io.tmpdir"), "nav-spike-deeplink-${System.currentTimeMillis()}.png")
        try {
            render(listOf(Shelf, Detail("w9"), Reader("w9", "c3")), 400, 800, out)
            assertTrue(out.isFile && out.length() > 1_000, "shot=${out.length()}")
        } finally {
            out.delete()
        }
    }
}
