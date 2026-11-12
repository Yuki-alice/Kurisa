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
 * 表结构见 `ReaderSetting.sq`；scope 预留按书覆盖（当前固定 global）。
 */
private class SqlDelightReaderSettingsStore(
    private val db: ReaderDatabase,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    initial: ReadingConfig,
) : ReaderSettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<ReadingConfig> = _settings.asStateFlow()

    init {
        scope.launch {
            db.readerSettingQueries.selectReaderSetting(GLOBAL_SCOPE)
                .asFlow()
                .mapToOneOrNull(Dispatchers.Default)
                .collect { row ->
                    if (row != null) {
                        _settings.value = decode(row.config_json)
                    }
                }
        }
    }

    override fun update(transform: (ReadingConfig) -> ReadingConfig) {
        val next = transform(_settings.value)
        _settings.value = next
        scope.launch {
            db.readerSettingQueries.upsertReaderSetting(
                scope = GLOBAL_SCOPE,
                config_json = encode(next),
                updated_at = nowMs(),
            )
        }
    }

    private fun encode(config: ReadingConfig): String =
        json.encodeToString(ReaderSettingsDto.from(config))

    private fun decode(raw: String): ReadingConfig =
        runCatching { json.decodeFromString<ReaderSettingsDto>(raw).toConfig() }
            .getOrElse { _settings.value }

    private companion object {
        const val GLOBAL_SCOPE = "global"
    }
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
        )
    }
}

private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
    runCatching { enumValueOf<T>(value) }.getOrElse { fallback }
