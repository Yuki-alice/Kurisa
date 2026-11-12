package data.localimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalOrderTest {

    private fun sorted(vararg names: String): List<String> = names.sortedWith(NaturalOrder)

    @Test
    fun numericChunksCompareNumerically() {
        assertEquals(
            listOf("ch1", "ch2", "ch10", "ch20"),
            sorted("ch10", "ch2", "ch20", "ch1"),
        )
    }

    @Test
    fun caseInsensitive() {
        assertEquals(listOf("Ch1", "ch2", "CH10"), sorted("CH10", "ch2", "Ch1"))
        assertTrue(NaturalOrder.compare("Vol.1", "vol.1") == 0)
    }

    @Test
    fun mixedTextAndNumbers() {
        assertEquals(
            listOf("extra", "omake", "special", "vol1 ch2", "vol1 ch10", "vol2 ch1"),
            sorted("vol2 ch1", "vol1 ch10", "special", "vol1 ch2", "omake", "extra"),
        )
    }

    @Test
    fun decimalsAndLeadingZeros() {
        assertEquals(
            listOf("ch1", "ch1.5", "ch01", "ch2"),
            sorted("ch2", "ch1.5", "ch01", "ch1"),
        )
    }
}
