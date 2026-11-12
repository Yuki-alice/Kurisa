package core.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderInteractionTest {

    @Test
    fun spreadsSinglePage() {
        assertEquals(emptyList(), buildSpreads(0, doublePage = false))
        assertEquals(listOf(PageSpread(listOf(0))), buildSpreads(1, doublePage = false))
        assertEquals(
            listOf(PageSpread(listOf(0)), PageSpread(listOf(1)), PageSpread(listOf(2))),
            buildSpreads(3, doublePage = false),
        )
    }

    @Test
    fun spreadsDoublePageKeepsCoverAlone() {
        assertEquals(
            listOf(
                PageSpread(listOf(0)),
                PageSpread(listOf(1, 2)),
                PageSpread(listOf(3, 4)),
            ),
            buildSpreads(5, doublePage = true),
        )
        assertEquals(
            listOf(PageSpread(listOf(0)), PageSpread(listOf(1, 2)), PageSpread(listOf(3))),
            buildSpreads(4, doublePage = true),
        )
    }

    @Test
    fun spreadsSkipWidePagesInDoubleMode() {
        // 第 1 页宽图独占，普通页照常配对。
        assertEquals(
            listOf(
                PageSpread(listOf(0)),
                PageSpread(listOf(1)),
                PageSpread(listOf(2, 3)),
                PageSpread(listOf(4)),
            ),
            buildSpreads(5, doublePage = true, isWide = { it == 1 || it == 4 }),
        )
        // 未知尺寸（默认）保持旧行为。
        assertEquals(
            listOf(PageSpread(listOf(0)), PageSpread(listOf(1, 2)), PageSpread(listOf(3, 4))),
            buildSpreads(5, doublePage = true),
        )
    }

    @Test
    fun pageSizeWideMeansLandscape() {
        assertTrue(PageSize(800, 400).isWide)
        assertEquals(false, PageSize(400, 800).isWide)
        assertEquals(false, PageSize(400, 400).isWide)
    }

    @Test
    fun spreadIndexMapsPage() {
        val spreads = buildSpreads(5, doublePage = true)
        assertEquals(0, spreadIndexOfPage(spreads, 0))
        assertEquals(1, spreadIndexOfPage(spreads, 1))
        assertEquals(1, spreadIndexOfPage(spreads, 2))
        assertEquals(2, spreadIndexOfPage(spreads, 4))
        assertEquals(0, spreadIndexOfPage(emptyList(), 3))
    }

    @Test
    fun leftRightTapZonesLtr() {
        assertEquals(TapAction.PREV, tap(0.1f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.LTR))
        assertEquals(TapAction.NEXT, tap(0.9f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.LTR))
        assertEquals(TapAction.MENU, tap(0.5f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.LTR))
    }

    @Test
    fun leftRightTapZonesRtlSwapsEdges() {
        assertEquals(TapAction.NEXT, tap(0.1f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.RTL))
        assertEquals(TapAction.PREV, tap(0.9f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.RTL))
    }

    @Test
    fun verticalTapZonesUseYAxis() {
        assertEquals(TapAction.PREV, tap(0.5f, 0.1f, TapZoneMode.LEFT_RIGHT, ReadingDirection.VERTICAL))
        assertEquals(TapAction.NEXT, tap(0.5f, 0.9f, TapZoneMode.LEFT_RIGHT, ReadingDirection.VERTICAL))
        assertEquals(TapAction.MENU, tap(0.5f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.VERTICAL))
    }

    @Test
    fun lShapeTopBottomAndEdges() {
        assertEquals(TapAction.PREV, tap(0.5f, 0.1f, TapZoneMode.L_SHAPE, ReadingDirection.LTR))
        assertEquals(TapAction.NEXT, tap(0.5f, 0.9f, TapZoneMode.L_SHAPE, ReadingDirection.LTR))
        assertEquals(TapAction.PREV, tap(0.1f, 0.5f, TapZoneMode.L_SHAPE, ReadingDirection.LTR))
        assertEquals(TapAction.NEXT, tap(0.9f, 0.5f, TapZoneMode.L_SHAPE, ReadingDirection.LTR))
        assertEquals(TapAction.MENU, tap(0.5f, 0.5f, TapZoneMode.L_SHAPE, ReadingDirection.LTR))
    }

    @Test
    fun edgeZonesMatchKomikkuGeometry() {
        // 左/右全高带 NEXT，底部中条 PREV，其余 MENU。
        assertEquals(TapAction.NEXT, tap(0.05f, 0.5f, TapZoneMode.EDGE, ReadingDirection.LTR))
        assertEquals(TapAction.NEXT, tap(0.95f, 0.5f, TapZoneMode.EDGE, ReadingDirection.LTR))
        assertEquals(TapAction.PREV, tap(0.5f, 0.95f, TapZoneMode.EDGE, ReadingDirection.LTR))
        assertEquals(TapAction.MENU, tap(0.5f, 0.5f, TapZoneMode.EDGE, ReadingDirection.LTR))
    }

    @Test
    fun kindlishZonesAreFixed() {
        assertEquals(TapAction.PREV, tap(0.1f, 0.5f, TapZoneMode.KINDLISH, ReadingDirection.LTR))
        assertEquals(TapAction.NEXT, tap(0.9f, 0.5f, TapZoneMode.KINDLISH, ReadingDirection.LTR))
        assertEquals(TapAction.MENU, tap(0.5f, 0.1f, TapZoneMode.KINDLISH, ReadingDirection.LTR))
        assertEquals(TapAction.MENU, tap(0.5f, 0.5f, TapZoneMode.KINDLISH, ReadingDirection.LTR))
        // Kindlish 不跟随 RTL。
        assertEquals(TapAction.PREV, tap(0.1f, 0.5f, TapZoneMode.KINDLISH, ReadingDirection.RTL))
    }

    @Test
    fun topStripIsAlwaysMenu() {
        assertEquals(TapAction.MENU, tap(0.5f, 0.02f, TapZoneMode.LEFT_RIGHT, ReadingDirection.LTR))
        assertEquals(TapAction.MENU, tap(0.1f, 0.02f, TapZoneMode.EDGE, ReadingDirection.LTR))
    }

    @Test
    fun smallerTapZoneShrinksHotAreas() {
        assertEquals(TapAction.PREV, tap(0.3f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.LTR))
        assertEquals(
            TapAction.MENU,
            tap(
                0.3f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.LTR,
                smallerTapZone = true,
            ),
        )
    }

    @Test
    fun invertMirrorsCoordinates() {
        assertEquals(
            TapAction.NEXT,
            tap(
                0.1f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.LTR,
                invert = TapInvertMode.HORIZONTAL,
            ),
        )
        assertEquals(
            TapAction.NEXT,
            tap(
                0.5f, 0.1f, TapZoneMode.LEFT_RIGHT, ReadingDirection.VERTICAL,
                invert = TapInvertMode.VERTICAL,
            ),
        )
    }

    @Test
    fun lShapeRtlSwapsSides() {
        assertEquals(TapAction.NEXT, tap(0.1f, 0.5f, TapZoneMode.L_SHAPE, ReadingDirection.RTL))
        assertEquals(TapAction.PREV, tap(0.9f, 0.5f, TapZoneMode.L_SHAPE, ReadingDirection.RTL))
    }

    @Test
    fun currentPageTextFormatsSpreads() {
        assertEquals("5", currentPageText(PageSpread(listOf(4)), rtl = false))
        assertEquals("3-4", currentPageText(PageSpread(listOf(2, 3)), rtl = false))
        assertEquals("4-3", currentPageText(PageSpread(listOf(2, 3)), rtl = true))
        assertEquals(
            "4-3",
            currentPageText(PageSpread(listOf(2, 3)), rtl = false, invertDoublePages = true),
        )
    }

    @Test
    fun menuVisibleTapClosesMenuFirst() {
        assertEquals(
            TapAction.CLOSE_MENU,
            tap(0.9f, 0.5f, TapZoneMode.LEFT_RIGHT, ReadingDirection.LTR, menuVisible = true),
        )
    }

    @Test
    fun disabledZonesOnlyMenu() {
        assertEquals(TapAction.MENU, tap(0.1f, 0.1f, TapZoneMode.DISABLED, ReadingDirection.LTR))
    }

    private fun tap(
        x: Float,
        y: Float,
        mode: TapZoneMode,
        direction: ReadingDirection,
        menuVisible: Boolean = false,
        smallerTapZone: Boolean = false,
        invert: TapInvertMode = TapInvertMode.NONE,
    ): TapAction = tapActionAt(x, y, mode, direction, menuVisible, smallerTapZone, invert)
}
