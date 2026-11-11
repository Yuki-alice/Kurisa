package data.images

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubsamplingTest {

    @Test
    fun hugeLongStripTilesWithoutOom() {
        // 8000×20000 长条（M0 P0BehaviorTest 同级覆盖）：采样后仍盖视口，分块≥3，总量进预算。
        val p = SubsamplingCalculator.plan(
            imageWidth = 8000, imageHeight = 20000,
            viewportWidth = 1080, viewportHeight = 2400,
        )
        assertEquals(4, p.sampleSize)
        assertEquals(2000, p.decodedWidth)
        assertEquals(5000, p.decodedHeight)
        assertEquals(1, p.tileCols)
        assertEquals(2, p.tileRows)
        assertEquals(2000L * 5000 * 4, p.estimatedBytes)
        assertTrue(p.fitsBudget)
    }

    @Test
    fun smallImageFullResSingleTile() {
        val p = SubsamplingCalculator.plan(
            imageWidth = 800, imageHeight = 1200,
            viewportWidth = 1080, viewportHeight = 2400,
        )
        assertEquals(1, p.sampleSize)
        assertEquals(1, p.tileCols)
        assertEquals(1, p.tileRows)
        assertTrue(p.fitsBudget)
    }

    @Test
    fun overBudgetFlagged() {
        // 4K 屏全分辨率硬算必然超 64MB：必须被标出来（调用方降采样或拒绝）。
        val p = SubsamplingCalculator.plan(
            imageWidth = 8000, imageHeight = 20000,
            viewportWidth = 8000, viewportHeight = 20000,
            budgetBytes = 64L * 1024 * 1024,
        )
        assertEquals(1, p.sampleSize)
        assertFalse(p.fitsBudget)
    }

    @Test
    fun sampleNeverExceedsEight() {
        val p = SubsamplingCalculator.plan(
            imageWidth = 100_000, imageHeight = 100_000,
            viewportWidth = 100, viewportHeight = 100,
        )
        assertTrue(p.sampleSize <= 8)
    }
}
