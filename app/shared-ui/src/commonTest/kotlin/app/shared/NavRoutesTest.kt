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
        DetailRoute("w", "src.key", "标题"),
        ReaderRoute("w", "c", "src.key"),
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

    @Test
    fun routeCountGuard() {
        // 加新目的地必须同步注册（数对不上就去补 appSerializersModule）。
        assertEquals(9, routes.size)
    }

    @Test
    fun sourceKeySurvivesRoundTripAndKeepsLocalDefault() {
        // B4：sourceKey 是多态序列化的真实字段，不能只在内存里传。
        val detail = DetailRoute("w", "ex.b", "B-W1")
        val backDetail = json.decodeFromString(
            PolymorphicSerializer(NavKey::class),
            json.encodeToString(PolymorphicSerializer(NavKey::class), detail),
        )
        assertEquals(detail, backDetail)
        assertEquals("ex.b", (backDetail as DetailRoute).sourceKey)

        val reader = ReaderRoute("w", "c", "ex.b")
        val backReader = json.decodeFromString(
            PolymorphicSerializer(NavKey::class),
            json.encodeToString(PolymorphicSerializer(NavKey::class), reader),
        )
        assertEquals("ex.b", (backReader as ReaderRoute).sourceKey)

        // 本地书无源归属，默认空串，既有调用方零改动。
        assertEquals("", DetailRoute("w").sourceKey)
        assertEquals("", ReaderRoute("w", "c").sourceKey)
    }
}
