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
 * 点按分区判定（对齐 Komikku 的 RightAndLeft / LNavigation / EdgeNavigation）。
 *
 * 坐标归一化到 [0,1]。RTL 时物理左/右互换；纵向模式改按上/下判定。
 * 菜单打开时任意点按先关闭菜单（避免误翻页）。
 */
fun tapActionAt(
    x: Float,
    y: Float,
    mode: TapZoneMode,
    direction: ReadingDirection,
    menuVisible: Boolean,
): TapAction {
    if (menuVisible) return TapAction.CLOSE_MENU
    if (mode == TapZoneMode.DISABLED) return TapAction.MENU
    val nx = x.coerceIn(0f, 1f)
    val ny = y.coerceIn(0f, 1f)
    val rtl = direction == ReadingDirection.RTL

    fun horizontal(left: TapAction, right: TapAction): TapAction =
        if (rtl) {
            when {
                nx < 1f / 3f -> right
                nx > 2f / 3f -> left
                else -> TapAction.MENU
            }
        } else {
            when {
                nx < 1f / 3f -> left
                nx > 2f / 3f -> right
                else -> TapAction.MENU
            }
        }

    return when (mode) {
        TapZoneMode.DISABLED -> TapAction.MENU
        TapZoneMode.LEFT_RIGHT -> {
            if (direction == ReadingDirection.VERTICAL) {
                when {
                    ny < 1f / 3f -> TapAction.PREV
                    ny > 2f / 3f -> TapAction.NEXT
                    else -> TapAction.MENU
                }
            } else {
                horizontal(TapAction.PREV, TapAction.NEXT)
            }
        }
        TapZoneMode.L_SHAPE -> {
            if (direction == ReadingDirection.VERTICAL) {
                when {
                    ny < 1f / 3f -> TapAction.PREV
                    ny > 2f / 3f -> TapAction.NEXT
                    nx < 1f / 3f -> TapAction.PREV
                    nx > 2f / 3f -> TapAction.NEXT
                    else -> TapAction.MENU
                }
            } else {
                when {
                    ny < 1f / 3f -> TapAction.PREV
                    ny > 2f / 3f -> TapAction.NEXT
                    nx < 1f / 3f -> if (rtl) TapAction.NEXT else TapAction.PREV
                    nx > 2f / 3f -> if (rtl) TapAction.PREV else TapAction.NEXT
                    else -> TapAction.MENU
                }
            }
        }
        TapZoneMode.EDGE -> when {
            nx < 0.1f -> if (rtl) TapAction.NEXT else TapAction.PREV
            nx > 0.9f -> if (rtl) TapAction.PREV else TapAction.NEXT
            else -> TapAction.MENU
        }
    }
}
