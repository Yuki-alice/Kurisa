package core.reader

/**
 * 小说排版参数（漫画字段不复用；持久化见 `ReaderSettingsDto.config_json`）。
 * 全部为密度无关的逻辑值，UI 层按 LocalDensity 换算成 px。
 */
data class NovelTypography(
    val fontSizeSp: Float = 18f,
    val lineHeightMultiple: Float = 1.6f,
    val paragraphSpacingDp: Float = 12f,
    val pageMarginDp: Float = 20f,
    val paragraphIndentEm: Float = 2f,
) {
    fun sanitized(): NovelTypography = copy(
        fontSizeSp = fontSizeSp.coerceIn(12f, 36f),
        lineHeightMultiple = lineHeightMultiple.coerceIn(1f, 2.5f),
        paragraphSpacingDp = paragraphSpacingDp.coerceIn(0f, 48f),
        pageMarginDp = pageMarginDp.coerceIn(0f, 64f),
        paragraphIndentEm = paragraphIndentEm.coerceIn(0f, 4f),
    )
}
