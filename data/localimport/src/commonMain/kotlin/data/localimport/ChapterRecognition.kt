package data.localimport

/**
 * 章节号识别。逐行对齐 Komikku
 * `domain/.../chapter/service/ChapterRecognition.kt`（纯 Kotlin，已是 commonMain 安全）：
 * 优先 `Ch.xx` 基例，多数字时先Strip卷/季/版标签；extra/omake/special 后缀映射
 * .99/.98/.97，单字母后缀（x.a→x.1）按字母序映射。
 */
object ChapterRecognition {
    const val UNKNOWN_CHAPTER_NUMBER = -1.0

    private const val NUMBER_PATTERN = """([0-9]+)(\.[0-9]+)?(\.?[a-z]+)?"""

    /** All cases with Ch.xx — e.g. "Mokushiroku Alice Vol.1 Ch. 4" -> 4. */
    private val basic = Regex("""(?<=ch\.) *$NUMBER_PATTERN""")

    /** e.g. "Bleach 567: Down With Snowwhite" -> 567. */
    private val number = Regex(NUMBER_PATTERN)

    /** e.g. "Prison School 12 v.1 vol004 version1243 volume64" -> "Prison School 12". */
    private val unwanted = Regex("""\b(?:v|ver|vol|version|volume|season|s)[^a-z]?[0-9]+""")

    /** e.g. "One Piece 12 special" -> "One Piece 12special". */
    private val unwantedWhiteSpace = Regex("""\s(?=extra|special|omake)""")

    fun parseChapterNumber(
        mangaTitle: String,
        chapterName: String,
        chapterNumber: Double? = null,
    ): Double {
        // If chapter number is known return.
        if (chapterNumber != null && (chapterNumber == -2.0 || chapterNumber > -1.0)) {
            return chapterNumber
        }

        val cleanChapterName = chapterName.lowercase()
            .replace(mangaTitle.lowercase(), "").trim()
            .replace(',', '.')
            .replace('-', '.')
            .replace(unwantedWhiteSpace, "")

        val numberMatch = number.findAll(cleanChapterName)

        when {
            numberMatch.none() -> {
                return chapterNumber ?: UNKNOWN_CHAPTER_NUMBER
            }
            numberMatch.count() > 1 -> {
                unwanted.replace(cleanChapterName, "").let { name ->
                    basic.find(name)?.let { return getChapterNumberFromMatch(it) }
                    // need to find again first number might already removed
                    number.find(name)?.let { return getChapterNumberFromMatch(it) }
                }
            }
        }

        // return the first number encountered
        return getChapterNumberFromMatch(numberMatch.first())
    }

    private fun getChapterNumberFromMatch(match: MatchResult): Double {
        return match.let {
            val initial = it.groups[1]?.value?.toDouble()!!
            val subChapterDecimal = it.groups[2]?.value
            val subChapterAlpha = it.groups[3]?.value
            val addition = checkForDecimal(subChapterDecimal, subChapterAlpha)
            initial.plus(addition)
        }
    }

    private fun checkForDecimal(decimal: String?, alpha: String?): Double {
        if (!decimal.isNullOrEmpty()) {
            return decimal.toDouble()
        }

        if (!alpha.isNullOrEmpty()) {
            if (alpha.contains("extra")) return 0.99
            if (alpha.contains("omake")) return 0.98
            if (alpha.contains("special")) return 0.97

            val trimmedAlpha = alpha.trimStart('.')
            if (trimmedAlpha.length == 1) {
                return parseAlphaPostFix(trimmedAlpha[0])
            }
        }

        return 0.0
    }

    /** x.a -> x.1, x.b -> x.2, etc. */
    private fun parseAlphaPostFix(alpha: Char): Double {
        val number = alpha.code - ('a'.code - 1)
        if (number >= 10) return 0.0
        return number / 10.0
    }
}
