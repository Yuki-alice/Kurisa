package data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import core.reader.ImageScaleType
import core.reader.ReaderSettingsStore
import core.reader.ReaderTheme
import core.reader.TapInvertMode
import core.reader.ReadingConfig
import core.reader.ReadingDirection
import core.reader.ReadingMode
import core.reader.NovelTypography
import core.reader.TapZoneMode
import data.db.ReaderDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 阅读设置的进程内事实源：内存 StateFlow 立即可读，异步落 SQLDelight。
 * 表结构见 `ReaderSetting.sq`；scope 按书覆盖（`book:<workId>`），全局行兜底。
 * 切换作用域时读库（缺行回退全局行，再缺行保持内存值）；更新写入当前作用域。
 */
private class SqlDelightReaderSettingsStore(
    private val db: ReaderDatabase,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val initial: ReadingConfig,
) : ReaderSettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<ReadingConfig> = _settings.asStateFlow()
    private var activeScope = ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE

    init {
        scope.launch {
            db.readerSettingQueries.selectReaderSetting(ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE)
                .asFlow()
                .mapToOneOrNull(Dispatchers.Default)
                .collect { row ->
                    if (row != null && activeScope == ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE) {
                        _settings.value = decode(row.config_json)
                    }
                }
        }
    }

    override fun update(transform: (ReadingConfig) -> ReadingConfig) {
        val target = activeScope
        val next = transform(_settings.value)
        _settings.value = next
        scope.launch {
            db.readerSettingQueries.upsertReaderSetting(
                scope = target,
                config_json = encode(next),
                updated_at = nowMs(),
            )
        }
    }

    override fun enterScope(scope: String) {
        activeScope = scope
        // 与 update() 同纪律：DB 直落在调用方 scope（生产为 Default 池），不另切调度。
        this.scope.launch {
            val row = db.readerSettingQueries.selectReaderSetting(scope).executeAsOneOrNull()
                ?: db.readerSettingQueries
                    .selectReaderSetting(ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE)
                    .executeAsOneOrNull()
            if (row != null && activeScope == scope) {
                _settings.value = decode(row.config_json)
            }
        }
    }

    override fun currentScope(): String = activeScope

    override fun resetScope(scope: String) {
        if (scope == ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE) return
        this.scope.launch {
            db.readerSettingQueries.deleteReaderSetting(scope)
            if (activeScope == scope) enterScope(scope)
        }
    }

    private fun encode(config: ReadingConfig): String =
        json.encodeToString(ReaderSettingsDto.from(config))

    private fun decode(raw: String): ReadingConfig =
        runCatching { json.decodeFromString<ReaderSettingsDto>(raw).toConfig() }
            .getOrElse { _settings.value }
}

/**
 * 创建持久化阅读设置仓库。平台壳传入数据库、进程级 scope 与时钟；
 * 未注入时 feature 层使用内存实现兜底（App 会话内有效）。
 */
fun createReaderSettingsStore(
    db: ReaderDatabase,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    nowMs: () -> Long = { 0L },
    initial: ReadingConfig = ReadingConfig(),
): ReaderSettingsStore = SqlDelightReaderSettingsStore(db, scope, nowMs, initial)

@Serializable
private data class ReaderSettingsDto(
    val mode: String = "PAGED",
    val direction: String = "LTR",
    val scaleType: String = "FIT",
    val tapZone: String = "LEFT_RIGHT",
    val doublePage: Boolean = false,
    val showPageNumber: Boolean = true,
    val webtoonSidePaddingPercent: Int = 0,
    val theme: String = "BLACK",
    val grayscale: Boolean = false,
    val dimLevel: Int = 0,
    val chapterTransition: Boolean = true,
    val tapInvert: String = "NONE",
    val smallerTapZone: Boolean = false,
    val invertDoublePages: Boolean = false,
    val volumeKeysEnabled: Boolean = true,
    val volumeKeysInverted: Boolean = false,
    val readWithLongTap: Boolean = true,
    val invertedColors: Boolean = false,
    val autoScroll: Boolean = false,
    val autoScrollIntervalSec: Float = 3f,
    val tintColorArgb: Long? = null,
    val flashOnPageChange: Boolean = false,
    val flashDurationMs: Int = 100,
    val flashWhite: Boolean = false,
    val cropBorders: Boolean = false,
    val novelFontSizeSp: Float = 18f,
    val novelLineHeightMultiple: Float = 1.6f,
    val novelParagraphSpacingDp: Float = 12f,
    val novelPageMarginDp: Float = 20f,
    val novelParagraphIndentEm: Float = 2f,
) {
    fun toConfig(): ReadingConfig = ReadingConfig(
        mode = enumOr(mode, ReadingMode.PAGED),
        direction = enumOr(direction, ReadingDirection.LTR),
        scaleType = enumOr(scaleType, ImageScaleType.FIT),
        tapZone = enumOr(tapZone, TapZoneMode.LEFT_RIGHT),
        doublePage = doublePage,
        showPageNumber = showPageNumber,
        webtoonSidePaddingPercent = webtoonSidePaddingPercent.coerceIn(0, 25),
        theme = enumOr(theme, ReaderTheme.BLACK),
        grayscale = grayscale,
        dimLevel = dimLevel.coerceIn(0, 60),
        chapterTransition = chapterTransition,
        tapInvert = enumOr(tapInvert, TapInvertMode.NONE),
        smallerTapZone = smallerTapZone,
        invertDoublePages = invertDoublePages,
        volumeKeysEnabled = volumeKeysEnabled,
        volumeKeysInverted = volumeKeysInverted,
        readWithLongTap = readWithLongTap,
        invertedColors = invertedColors,
        autoScroll = autoScroll,
        autoScrollIntervalSec = autoScrollIntervalSec.coerceIn(1f, 10f),
        tintColorArgb = tintColorArgb,
        flashOnPageChange = flashOnPageChange,
        flashDurationMs = flashDurationMs.coerceIn(100, 1500),
        flashWhite = flashWhite,
        cropBorders = cropBorders,
        typography = NovelTypography(
            fontSizeSp = novelFontSizeSp,
            lineHeightMultiple = novelLineHeightMultiple,
            paragraphSpacingDp = novelParagraphSpacingDp,
            pageMarginDp = novelPageMarginDp,
            paragraphIndentEm = novelParagraphIndentEm,
        ),
    )

    companion object {
        fun from(config: ReadingConfig): ReaderSettingsDto = ReaderSettingsDto(
            mode = config.mode.name,
            direction = config.direction.name,
            scaleType = config.scaleType.name,
            tapZone = config.tapZone.name,
            doublePage = config.doublePage,
            showPageNumber = config.showPageNumber,
            webtoonSidePaddingPercent = config.webtoonSidePaddingPercent,
            theme = config.theme.name,
            grayscale = config.grayscale,
            dimLevel = config.dimLevel,
            chapterTransition = config.chapterTransition,
            tapInvert = config.tapInvert.name,
            smallerTapZone = config.smallerTapZone,
            invertDoublePages = config.invertDoublePages,
            volumeKeysEnabled = config.volumeKeysEnabled,
            volumeKeysInverted = config.volumeKeysInverted,
            readWithLongTap = config.readWithLongTap,
            invertedColors = config.invertedColors,
            autoScroll = config.autoScroll,
            autoScrollIntervalSec = config.autoScrollIntervalSec,
            tintColorArgb = config.tintColorArgb,
            flashOnPageChange = config.flashOnPageChange,
            flashDurationMs = config.flashDurationMs,
            flashWhite = config.flashWhite,
            cropBorders = config.cropBorders,
            novelFontSizeSp = config.typography.fontSizeSp,
            novelLineHeightMultiple = config.typography.lineHeightMultiple,
            novelParagraphSpacingDp = config.typography.paragraphSpacingDp,
            novelPageMarginDp = config.typography.pageMarginDp,
            novelParagraphIndentEm = config.typography.paragraphIndentEm,
        )
    }
}

private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
    runCatching { enumValueOf<T>(value) }.getOrElse { fallback }
