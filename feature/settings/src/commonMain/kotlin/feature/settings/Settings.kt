package feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import core.designsystem.Tokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.GlobalContext
import org.koin.core.parameter.parametersOf

/** 设置（主题/阅读/网络/存储/关于；MD3E 三端截图基线随 M5）。 */
@Serializable
data object SettingsRoute : NavKey

/** 设置偏好（持久化形态；store 实现决定落盘位置，默认内存）。 */
data class SettingsPrefs(
    /** 按量网络下不自动加载图片/下载（默认关）。 */
    val meteredNetworkRestricted: Boolean = false,
    /** 图片缓存上限 MB（0 = 不限）。 */
    val imageCacheLimitMb: Int = 0,
)

/** 设置存储（平台壳可注入 SQLDelight/file 实现；默认内存态）。 */
interface SettingsStore {
    suspend fun load(): SettingsPrefs

    suspend fun save(prefs: SettingsPrefs)
}

/** 内存实现（装配缺失时的保底，不伪装持久化）。 */
class InMemorySettingsStore(initial: SettingsPrefs = SettingsPrefs()) : SettingsStore {
    private var current = initial
    override suspend fun load(): SettingsPrefs = current
    override suspend fun save(prefs: SettingsPrefs) { current = prefs }
}

/** 设置屏注入载荷（BE-A5 同构：Koin 参数；装配层提供 store/版本/清理出口）。 */
class SettingsDeps(
    val store: SettingsStore,
    val appVersion: String,
    val clearImageCache: (suspend () -> Long)?,
)

data class SettingsUiState(
    val prefs: SettingsPrefs = SettingsPrefs(),
    val appVersion: String = "",
    val cacheClearable: Boolean = false,
    val clearing: Boolean = false,
    val clearedBytes: Long? = null,
    val clearError: String? = null,
    val loading: Boolean = true,
)

/**
 * 设置 ViewModel（MVI 同形：StateFlow 状态 + Intent；B-8 最小可用）。
 *
 * 三组能力：网络偏好（store 持久化）、存储清理（装配层注入的清理出口；null=未装配，入口不渲染）、关于（版本号展示）。
 */
class SettingsViewModel(
    private val deps: SettingsDeps,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SettingsUiState(appVersion = deps.appVersion, cacheClearable = deps.clearImageCache != null),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val prefs = runCatching { deps.store.load() }.getOrElse { SettingsPrefs() }
            _state.update { it.copy(prefs = prefs, loading = false) }
        }
    }

    fun setMeteredRestricted(restricted: Boolean) = updatePrefs { it.copy(meteredNetworkRestricted = restricted) }

    fun setImageCacheLimitMb(mb: Int) = updatePrefs { it.copy(imageCacheLimitMb = mb.coerceAtLeast(0)) }

    private fun updatePrefs(change: (SettingsPrefs) -> SettingsPrefs) {
        _state.update { it.copy(prefs = change(it.prefs)) }
        viewModelScope.launch {
            runCatching { deps.store.save(_state.value.prefs) }
        }
    }

    fun clearCache() {
        val clear = deps.clearImageCache ?: return
        if (_state.value.clearing) return
        _state.update { it.copy(clearing = true, clearedBytes = null, clearError = null) }
        viewModelScope.launch {
            runCatching { clear() }
                .onSuccess { bytes -> _state.update { it.copy(clearing = false, clearedBytes = bytes) } }
                .onFailure { e ->
                    _state.update { it.copy(clearing = false, clearError = e.message ?: "清理失败") }
                }
        }
    }
}

/**
 * 从 Koin 容器取 SettingsViewModel（BE-A5 同构；容器未启动时退化为本地实例）。
 */
@Composable
private fun koinSettingsViewModel(deps: SettingsDeps): SettingsViewModel {
    val koinStarted = remember { GlobalContext.getOrNull() != null }
    return if (!koinStarted) {
        viewModel(key = "settings") { SettingsViewModel(deps) }
    } else {
        koinViewModel<SettingsViewModel>(key = "settings", parameters = { parametersOf(deps) })
    }
}

/**
 * 设置屏（B-8 最小可用：网络 / 存储 / 关于，不做视觉）。
 *
 * 参数全默认：`SettingsScreen()` 即装配缺失态（内存偏好、无清理出口），调用方（App.kt）零改动；
 * 平台壳注入 store/版本/清理出口后即完整可用。
 */
@Composable
fun SettingsScreen(
    store: SettingsStore? = null,
    appVersion: String = "",
    onClearImageCache: (suspend () -> Long)? = null,
) {
    // 闭包可能随重组更新；VM 只构造一次，用 State 间接让最新闭包生效。
    val clearState = rememberUpdatedState(onClearImageCache)
    val realStore = store ?: remember { InMemorySettingsStore() }
    // 未装配清理出口时传 null（入口不渲染，不伪装能力）；非空时经 State 间接保持最新闭包。
    val hasClear = onClearImageCache != null
    val deps = remember(realStore, appVersion, hasClear) {
        val clear: (suspend () -> Long)? =
            if (hasClear) ({ clearState.value?.invoke() ?: 0L }) else null
        SettingsDeps(realStore, appVersion, clear)
    }
    val vm: SettingsViewModel = koinSettingsViewModel(deps)
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onToggleMetered = vm::setMeteredRestricted,
        onSetCacheLimit = vm::setImageCacheLimitMb,
        onClearCache = vm::clearCache,
        onRetry = vm::refresh,
    )
}

/** 纯展示（截图测试直接喂 State，不碰 VM 与存储）。 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onToggleMetered: (Boolean) -> Unit,
    onSetCacheLimit: (Int) -> Unit,
    onClearCache: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        if (state.loading) {
            Text("加载中…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("网络", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("按量网络下不加载图片", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.prefs.meteredNetworkRestricted,
                    onCheckedChange = onToggleMetered,
                )
            }
            HorizontalDivider()
            Text("存储", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("图片缓存上限", style = MaterialTheme.typography.bodyMedium)
                for (mb in CACHE_LIMIT_OPTIONS) {
                    TextButton(onClick = { onSetCacheLimit(mb) }) {
                        Text(
                            if (mb == state.prefs.imageCacheLimitMb) "● " + cacheLimitLabel(mb) else "○ " + cacheLimitLabel(mb),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            if (state.cacheClearable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClearCache, enabled = !state.clearing) {
                        Text(if (state.clearing) "清理中…" else "清除图片缓存")
                    }
                    state.clearedBytes?.let {
                        Text("已清理 " + formatBytes(it), style = MaterialTheme.typography.labelMedium)
                    }
                    state.clearError?.let {
                        Text("清理失败：" + it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider()
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Text(
                "版本 " + state.appVersion.ifBlank { "dev" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "开源许可随 M5 补齐（AboutLibraries）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val CACHE_LIMIT_OPTIONS = listOf(0, 128, 512)

private fun cacheLimitLabel(mb: Int): String = if (mb == 0) "不限" else mb.toString() + " MB"

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val tenthsKb = (bytes * 10) / 1024
    if (tenthsKb < 10240) return "${tenthsKb / 10}.${tenthsKb % 10} KB"
    val tenthsMb = tenthsKb / 1024
    return "${tenthsMb / 10}.${tenthsMb % 10} MB"
}
