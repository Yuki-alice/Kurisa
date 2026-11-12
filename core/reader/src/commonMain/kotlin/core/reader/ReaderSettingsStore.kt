package core.reader

import kotlinx.coroutines.flow.StateFlow

/**
 * 阅读设置仓库（全局默认；按书覆盖由实现层扩展 scope）。
 * 更新一律走 [update] 的不可变 copy，UI 只订阅 StateFlow。
 */
interface ReaderSettingsStore {
    val settings: StateFlow<ReadingConfig>
    fun update(transform: (ReadingConfig) -> ReadingConfig)
}
