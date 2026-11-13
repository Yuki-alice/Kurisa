package app.shared

import androidx.navigation3.runtime.NavKey
import feature.detail.DetailRoute
import feature.downloads.DownloadsRoute
import feature.explore.ExploreRoute
import feature.reader.ReaderRoute
import feature.search.SearchRoute
import feature.settings.SettingsRoute
import feature.shelf.ShelfRoute
import feature.sources.RuleTestRoute
import feature.sources.SourcesRoute
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 路由多态注册锁门：9 个目的地全部经 appSerializersModule 往返。
 * 少注册一个，就是一次真机启动崩溃（M3 实录：ShelfRoute 未注册 → FATAL）。
 */
class NavRoutesTest {

    private val json = Json {
        serializersModule = appSerializersModule
        classDiscriminator = "type"
    }

    private val routes: List<NavKey> = listOf(
        ShelfRoute,
        ExploreRoute("k"),
        SearchRoute,
        DetailRoute("w"),
        ReaderRoute("w", "c"),
        SourcesRoute,
        RuleTestRoute,
        DownloadsRoute,
        SettingsRoute,
    )

    @Test
    fun allRoutesPolymorphicRoundTrip() {
        for (route in routes) {
            val raw = json.encodeToString(PolymorphicSerializer(NavKey::class), route)
            val back = json.decodeFromString(PolymorphicSerializer(NavKey::class), raw)
            assertEquals(route, back)
        }
    }

    /** B4：路由必须原样带住 sourceKey / title，否则多源并存时静默回退首个启用源。 */
    @Test
    fun sourceKeySurvivesRoundTrip() {
        val detail = DetailRoute("w1", "demo.manga", "演示作品")
        val reader = ReaderRoute("w1", "c1", "demo.manga", "演示作品")
        for (route in listOf<NavKey>(detail, reader)) {
            val raw = json.encodeToString(PolymorphicSerializer(NavKey::class), route)
            assertEquals(route, json.decodeFromString(PolymorphicSerializer(NavKey::class), raw))
        }
        assertEquals("demo.manga", detail.sourceKey)
        assertEquals("演示作品", reader.title)
    }

    /** 默认值兼容：历史路由（不带 sourceKey）反序列化后仍可用。 */
    @Test
    fun legacyRouteJsonStillDecodes() {
        val reader = json.decodeFromString(
            PolymorphicSerializer(NavKey::class),
            """{"type":"feature.reader.ReaderRoute","workId":"w","chapterId":"c"}""",
        ) as ReaderRoute
        assertEquals("", reader.sourceKey)
        assertEquals("", reader.title)
    }

    @Test
    fun routeCountGuard() {
        // 加新目的地必须同步注册（数对不上就去补 appSerializersModule）。
        assertEquals(9, routes.size)
    }
}
