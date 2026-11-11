package spike

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZoomImage 手势 + 分块数学（三端一致）。
 * ADR-007：外层自研状态机（章节/预加载/进度），单页渲染交库。
 * 这里锁死外层必须做对的数学，真实像素渲染在 desktopTest。
 */
object ReaderGestureMath {
    fun clampScale(scale: Float, min: Float = 1f, max: Float = 5f): Float = scale.coerceIn(min, max)
    fun doubleTapToggle(current: Float, min: Float = 1f, max: Float = 3f): Float =
        if (current <= min + 0.01f) max else min
    fun tilesFor(w: Long, h: Long, budget: Long): Long {
        val total = w * h * 4
        return (total + budget - 1) / budget
    }
}

class ZoomGestureTest {

    @Test
    fun scaleClamp() {
        assertEquals(1f, ReaderGestureMath.clampScale(0.2f))
        assertEquals(5f, ReaderGestureMath.clampScale(9f))
        assertEquals(2.5f, ReaderGestureMath.clampScale(2.5f))
    }

    @Test
    fun doubleTap() {
        assertEquals(3f, ReaderGestureMath.doubleTapToggle(1f))
        assertEquals(1f, ReaderGestureMath.doubleTapToggle(3f))
    }

    @Test
    fun longStripNeedsSubsampling() {
        // Gate ⑤a：8000×20000 整图 640MB，必分块
        val tiles = ReaderGestureMath.tilesFor(8000, 20000, 256L * 1024 * 1024)
        assertTrue(tiles >= 3, "tiles=$tiles")
        // 普通 1080p 单块即可
        assertEquals(1, ReaderGestureMath.tilesFor(1080, 1920, 256L * 1024 * 1024))
    }

    @Test
    fun flingDecays() {
        var v = 3000f
        repeat(60) { v *= 0.92f }
        assertTrue(abs(v) < 30f, "v=$v")
    }
}
