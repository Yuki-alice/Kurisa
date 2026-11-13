package core.reader

import kotlinx.coroutines.flow.StateFlow

/**
 * 阅读设置仓库（全局默认；按书覆盖由作用域实现）。
 * 更新一律走 [update] 的不可变 copy，UI 只订阅 StateFlow。
 *
 * 按书覆写语义：[enterScope] 切换当前作用域，首次进入以上下文全局值为起点；
 * [update] 写入当前作用域；[resetScope] 删除覆写（正处该域时回退全局值）。
 * 不支持作用域的实现保持默认空实现（全局语义不变）。
 */
interface ReaderSettingsStore {
    val settings: StateFlow<ReadingConfig>
    fun update(transform: (ReadingConfig) -> ReadingConfig)

    /** 进入作用域（书为 book 加作品 id）；不支持时无操作。 */
    fun enterScope(scope: String) {}

    /** 当前作用域（默认全局）。 */
    fun currentScope(): String = GLOBAL_SETTINGS_SCOPE

    /** 删除指定作用域覆写（默认无操作；正处该域的实现应回退全局值）。 */
    fun resetScope(scope: String) {}

    companion object {
        const val GLOBAL_SETTINGS_SCOPE = "global"
    }
}
