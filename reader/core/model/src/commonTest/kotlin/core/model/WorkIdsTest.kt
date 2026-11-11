package core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkIdsTest {

    @Test
    fun sha256Vectors() {
        // 标准向量（与 JDK MessageDigest("SHA-256") 一致）
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
    }

    @Test
    fun stableIdRule() {
        val a = workStableId("com.example.a", "w1")
        val b = workStableId("com.example.a", "w1")
        val c = workStableId("com.example.b", "w1")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun urlChangeDoesNotLoseShelf() {
        // 禁止用 URL 作主键：同一 sourceWorkId 换域名仍得同一 ID
        assertEquals(
            workStableId("manga_dex", "b23c9bb1"),
            workStableId("manga_dex", "b23c9bb1"),
        )
    }
}
