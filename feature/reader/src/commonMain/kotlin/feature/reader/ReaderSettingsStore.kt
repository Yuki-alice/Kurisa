package feature.reader

import core.reader.ReaderSettingsStore
import core.reader.ReadingConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 内存实现（测试/预览默认；持久化实现见 `:data:repository`）。
 * 支持作用域：各域独立行，首次进入以上下文全局值为起点；`resetScope` 删行并回退。
 */
class InMemoryReaderSettingsStore(
    initial: ReadingConfig = ReadingConfig(),
) : ReaderSettingsStore {
    private val rows = mutableMapOf(ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE to initial)
    private var activeScope = ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<ReadingConfig> = _settings.asStateFlow()

    override fun update(transform: (ReadingConfig) -> ReadingConfig) {
        val next = transform(_settings.value)
        rows[activeScope] = next
        _settings.value = next
    }

    override fun enterScope(scope: String) {
        activeScope = scope
        _settings.value = rows.getOrPut(scope) { rows.getValue(ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE) }
    }

    override fun currentScope(): String = activeScope

    override fun resetScope(scope: String) {
        if (scope == ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE) return
        rows.remove(scope)
        if (activeScope == scope) {
            _settings.value = rows.getValue(ReaderSettingsStore.GLOBAL_SETTINGS_SCOPE)
        }
    }
}

/**
 * 进程级兜底设置：同一次 App 会话内跨章节/跨书保留读者选择。
 * 平台壳应注入 SQLDelight 持久化实现；此默认只在未注入时生效。
 */
val DefaultReaderSettingsStore: ReaderSettingsStore = InMemoryReaderSettingsStore()
