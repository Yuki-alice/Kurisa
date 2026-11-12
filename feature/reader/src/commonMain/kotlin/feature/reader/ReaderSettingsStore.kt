package feature.reader

import core.reader.ReadingConfig
import core.reader.ReaderSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 内存实现（测试/预览默认；持久化实现见 `:data:repository`）。 */
class InMemoryReaderSettingsStore(
    initial: ReadingConfig = ReadingConfig(),
) : ReaderSettingsStore {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<ReadingConfig> = _settings.asStateFlow()

    override fun update(transform: (ReadingConfig) -> ReadingConfig) {
        _settings.update(transform)
    }
}

/**
 * 进程级兜底设置：同一次 App 会话内跨章节/跨书保留读者选择。
 * 平台壳应注入 SQLDelight 持久化实现；此默认只在未注入时生效。
 */
val DefaultReaderSettingsStore: ReaderSettingsStore = InMemoryReaderSettingsStore()
