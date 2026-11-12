package core.reader

/** 单页/双页展开口（pages 为原页下标；双页时可能有两个）。 */
data class PageSpread(val pages: List<Int>)

/**
 * 双页展开规则（纯函数，可单测）：
 * - 单页模式：一页一个 spread；
 * - 双页模式：首封面单页，其后两页一组（[(0), (1,2), (3,4)...]），
 *   与主流漫画阅读器一致，避免封面和第一页被强行拼在一起。
 */
fun buildSpreads(pageCount: Int, doublePage: Boolean): List<PageSpread> {
    if (pageCount <= 0) return emptyList()
    if (!doublePage || pageCount == 1) return List(pageCount) { PageSpread(listOf(it)) }
    val result = ArrayList<PageSpread>((pageCount / 2) + 1)
    result += PageSpread(listOf(0))
    var i = 1
    while (i < pageCount) {
        result += if (i + 1 < pageCount) {
            PageSpread(listOf(i, i + 1)).also { i += 2 }
        } else {
            PageSpread(listOf(i)).also { i += 1 }
        }
    }
    return result
}

/** 页下标 → 所在 spread 下标；找不到时返回 0。 */
fun spreadIndexOfPage(spreads: List<PageSpread>, pageIndex: Int): Int =
    spreads.indexOfFirst { pageIndex in it.pages }.coerceAtLeast(0)

/**
 * 双页页码文本（对齐 Komikku currentPageText）：
 * 单页显示 number；双页显示 `"n-n+1"`，顺序由 `LTR xor invert` 决定。
 */
fun currentPageText(spread: PageSpread, rtl: Boolean, invertDoublePages: Boolean = false): String {
    if (spread.pages.size == 1) return "${spread.pages.first() + 1}"
    val ordered = if (rtl xor invertDoublePages) spread.pages.reversed() else spread.pages
    return ordered.joinToString("-") { "${it + 1}" }
}

/**
 * 点按分区判定（对齐 Komikku ViewerNavigation 五实现 + TappingInvertMode）。
 *
 * 坐标归一化到 [0,1]。判定顺序：
 * 1. 菜单打开 → CLOSE_MENU；DISABLED → MENU；
 * 2. 顶部 5% 恒为菜单（`constantMenu`，对齐 Komikku）；
 * 3. invert 镜像坐标（HORIZONTAL 镜像 x，VERTICAL 镜像 y）；
 * 4. 按 mode + direction（RTL 仅影响 LEFT_RIGHT / L_SHAPE 的左右）分区。
 *
 * 分区几何（s1 = smallerTapZone ? 0.25 : 0.33，s2 = 1 - s1）：
 * - LEFT_RIGHT（RightAndLeft）：左带 PREV / 右带 NEXT（RTL 互换），中部 MENU；
 * - L_SHAPE：顶带 PREV / 底带 NEXT；中部左 PREV / 右 NEXT（RTL 互换），中 MENU；
 * - EDGE：左全高 NEXT / 右全高 NEXT / 底部中条 PREV，其余 MENU；
 * - KINDLISH：左下 PREV / 右下 NEXT（固定，不跟随 RTL），顶部 s1 MENU；
 * - 纵向 direction：所有模式统一上 PREV / 下 NEXT / 中 MENU。
 */
fun tapActionAt(
    x: Float,
    y: Float,
    mode: TapZoneMode,
    direction: ReadingDirection,
    menuVisible: Boolean,
    smallerTapZone: Boolean = false,
    invert: TapInvertMode = TapInvertMode.NONE,
): TapAction {
    if (menuVisible) return TapAction.CLOSE_MENU
    if (mode == TapZoneMode.DISABLED) return TapAction.MENU
    var nx = x.coerceIn(0f, 1f)
    var ny = y.coerceIn(0f, 1f)
    if (invert == TapInvertMode.HORIZONTAL || invert == TapInvertMode.BOTH) nx = 1f - nx
    if (invert == TapInvertMode.VERTICAL || invert == TapInvertMode.BOTH) ny = 1f - ny
    // 顶部恒菜单条。
    if (ny < 0.05f) return TapAction.MENU

    val s1 = if (smallerTapZone) 0.25f else 1f / 3f
    val s2 = 1f - s1
    val rtl = direction == ReadingDirection.RTL

    if (direction == ReadingDirection.VERTICAL) {
        return when {
            ny < s1 -> TapAction.PREV
            ny > s2 -> TapAction.NEXT
            else -> TapAction.MENU
        }
    }

    return when (mode) {
        TapZoneMode.DISABLED -> TapAction.MENU
        TapZoneMode.LEFT_RIGHT -> when {
            nx < s1 -> if (rtl) TapAction.NEXT else TapAction.PREV
            nx > s2 -> if (rtl) TapAction.PREV else TapAction.NEXT
            else -> TapAction.MENU
        }
        TapZoneMode.L_SHAPE -> when {
            ny < s1 -> TapAction.PREV
            ny > s2 -> TapAction.NEXT
            nx < s1 -> if (rtl) TapAction.NEXT else TapAction.PREV
            nx > s2 -> if (rtl) TapAction.PREV else TapAction.NEXT
            else -> TapAction.MENU
        }
        TapZoneMode.EDGE -> when {
            nx < s1 -> TapAction.NEXT
            nx > s2 -> TapAction.NEXT
            ny > s2 -> TapAction.PREV
            else -> TapAction.MENU
        }
        TapZoneMode.KINDLISH -> when {
            ny < s1 -> TapAction.MENU
            nx < s1 -> TapAction.PREV
            nx > s2 -> TapAction.NEXT
            else -> TapAction.MENU
        }
    }
}
