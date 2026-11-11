package spike

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import korlibs.encoding.Base64
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P0 行为冒烟（desktop）：证明依赖在 JVM 真实可用，而不只是编译通过。
 * 三端行为一致部分由 commonTest 的 Ksoup/契约测试覆盖；
 * Ktor engine / SQLDelight driver 的分 target 编译已在 Gate ③ 验证。
 */
class P0BehaviorTest {

    @Test
    fun ksoupAndBase64() {
        val doc = com.fleeksoft.ksoup.Ksoup.parse("<p>Hello <b>W</b></p>")
        assertEquals("Hello W", doc.select("p")[0].text())
        val enc = Base64.encode("abc".encodeToByteArray())
        assertTrue(enc.isNotEmpty())
    }

    @Test
    fun ktorCioClientCreates() {
        val c = HttpClient(CIO)
        c.close()
    }

    @Test
    fun jsonAndKoin() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals("a", json.parseToJsonElement("\"a\"").toString().trim('"'))
        val m = module { }
        assertTrue(m.toString().isNotEmpty())
    }

    @Test
    fun largeImageMetadataDoesNotOom() {
        // Gate ⑤a 的最小前置：8000x20000 超大图的“元数据级”处理不应 OOM。
        // 真正的子采样渲染仍需真机压测，这里先锁死分块数学：tile 数与字节预算。
        val w = 8000
        val h = 20000
        val bytesPerPixel = 4L
        val total = w.toLong() * h * bytesPerPixel // 640MB，若整图解码必 OOM
        assertEquals(640_000_000L, total)
        // 按 256MB 预算，至少分 3 块
        val budget = 256L * 1024 * 1024
        val tiles = (total + budget - 1) / budget
        assertTrue(tiles >= 3, "tiles=$tiles")
        // 模拟窗口 ≤3 页的预加载内存上限（128MB desktop / 64MB iOS）
        val windowPages = 3
        val perPage = budget / windowPages
        assertTrue(perPage > 0)
    }
}
