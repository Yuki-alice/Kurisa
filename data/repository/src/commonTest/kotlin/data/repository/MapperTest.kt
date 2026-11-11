package data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import source.contract.ContentKind

/**
 * Mapper 边界单测（commonTest 纯逻辑，不碰 driver）。
 * 集成（Flow 自动发射/缓存优先）在 desktopTest 覆盖。
 */
class MapperTest {

    @Test
    fun kindFallback() {
        assertEquals(ContentKind.MANGA, Mappers.normalizeKind("MANGA"))
        assertEquals(ContentKind.MANGA, Mappers.normalizeKind("unknown"))
    }

    @Test
    fun tagsJsonRoundTrip() {
        val tags = listOf("冒险", "热血")
        val encoded = Mappers.encodeTags(tags)
        assertTrue(encoded.contains("冒险"))
        assertEquals(tags, Mappers.decodeTags(encoded))
    }
}
