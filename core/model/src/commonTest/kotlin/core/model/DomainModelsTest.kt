package core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainModelsTest {

    @Test
    fun progressPercentBounds() {
        ReadingProgress("w", "c", percent = 0f, updatedAtEpochMs = 1).let {
            assertEquals(0f, it.percent)
        }
        assertFailsWith<IllegalArgumentException> {
            ReadingProgress("w", "c", percent = 1.5f, updatedAtEpochMs = 1)
        }
    }

    @Test
    fun htmlAllowlistBlocksScript() {
        assertTrue(HtmlAllowlist.isAllowed("p"))
        assertTrue(HtmlAllowlist.isAllowed("IMG"))
        assertFalse(HtmlAllowlist.isAllowed("script"))
        assertFalse(HtmlAllowlist.isAllowed("iframe"))
        assertFalse(HtmlAllowlist.isAllowed("style"))
    }

    @Test
    fun payloadSealed() {
        val i: ChapterPayload = ChapterPayload.Images(listOf("https://example.com/1.jpg"))
        val t: ChapterPayload = ChapterPayload.Text("<p>hi</p>", TextFormat.HTML)
        assertTrue(i is ChapterPayload.Images)
        assertTrue(t is ChapterPayload.Text)
        assertEquals(1, (i as ChapterPayload.Images).urls.size)
    }
}
