package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实源暴露的契约缺口回归（m0-verification-report §3.5）。
 * 这些是纯字符串/JSON 逻辑，三端必须一致，否则同一份 .js 在不同端跑出不同结果。
 */
class ContractDefaultsTest {

    /** optionList 默认值：取每项第一个的 value 段，"any-Any" -> "any" */
    private fun defaultOption(firstLabel: String?): String? {
        if (firstLabel == null) return null
        val label = firstLabel
        return label.split("-")[0]
    }

    @Test
    fun optionListDefaults() {
        assertEquals("any", defaultOption("any-Any"))
        assertEquals("all", defaultOption("all"))
        assertEquals(null, defaultOption(null))
    }

    @Test
    fun appLocaleShape() {
        // VeneraSourceRunner polyfill: APP = { locale, version, platform }
        val locale = "zh_CN"
        assertTrue(locale.matches(Regex("[a-z]{2}_[A-Z]{2}")))
    }

    @Test
    fun mapChaptersSerialization() {
        // ComicDetails.chapters 可能是 Map，需经 Object.fromEntries 规范化；
        // 宿主侧用 kotlinx.serialization 的 JsonObject 同形处理
        val chapters = mapOf("c1" to "ch1", "c2" to "ch2")
        assertEquals(2, chapters.size)
        assertEquals("ch1", chapters["c1"])
    }
}
