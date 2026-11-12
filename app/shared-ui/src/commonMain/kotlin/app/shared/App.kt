package app.shared

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import core.designsystem.AppTheme
import core.reader.ChapterProvider
import core.reader.ReaderSettingsStore
import data.repository.ProgressRepository
import feature.detail.DetailRoute
import feature.detail.DetailScreen
import feature.downloads.DownloadsRoute
import feature.downloads.DownloadsScreen
import feature.explore.ExploreRoute
import feature.explore.ExploreScreen
import feature.reader.DefaultReaderSettingsStore
import feature.reader.NoopChapterProvider
import feature.reader.NoopProgressRepository
import feature.reader.ReaderRoute
import feature.reader.ReaderScreen
import feature.search.SearchRoute
import feature.search.SearchScreen
import feature.settings.SettingsRoute
import feature.settings.SettingsScreen
import feature.shelf.ShelfRoute
import feature.shelf.ShelfScreen
import feature.sources.SourcesRoute
import feature.sources.SourcesScreen

/**
 * 全 App 路由多态模块（ADR-008：iOS 等非 JVM 平台无反射，route key 必须显式注册；
 * 少注册一个就是一次启动崩溃——见 M3 真机实录，已由 NavRoutesTest 锁门）。
 */
val appSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(ShelfRoute::class)
        subclass(ExploreRoute::class)
        subclass(SearchRoute::class)
        subclass(DetailRoute::class)
        subclass(ReaderRoute::class)
        subclass(SourcesRoute::class)
        subclass(DownloadsRoute::class)
        subclass(SettingsRoute::class)
    }
}

/**
 * 全 App 入口（ADR-008）。
 * ViewModel 工厂集中于此（配合 Koin，M5 接入）；各 feature 禁止自建 scope。
 *
 * [readerChapters]/[readerDomains] 是阅读器的真实源注入点：
 * 平台壳/SourceRuntime 装配好后传入；默认 Noop 保证 C1（主仓零预置源）。
 */
@Composable
fun App(
    readerChapters: ChapterProvider = NoopChapterProvider,
    readerDomains: Set<String> = emptySet(),
    readerProgress: ProgressRepository = NoopProgressRepository,
    readerSettings: ReaderSettingsStore = DefaultReaderSettingsStore,
) {
    AppTheme {
        val backStack = rememberNavBackStack(
            SavedStateConfiguration { serializersModule = appSerializersModule },
            ShelfRoute,
        )
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = { key: NavKey ->
                when (key) {
                    is ShelfRoute -> NavEntry(key) {
                        ShelfScreen(onOpenDetail = { backStack.add(DetailRoute(it)) })
                    }
                    is ExploreRoute -> NavEntry(key) {
                        ExploreScreen(key.sourceKey, onOpenDetail = { backStack.add(DetailRoute(it)) })
                    }
                    is SearchRoute -> NavEntry(key) {
                        SearchScreen(onOpenDetail = { backStack.add(DetailRoute(it)) })
                    }
                    is DetailRoute -> NavEntry(key) {
                        DetailScreen(
                            workId = key.workId,
                            onOpenReader = { w, c -> backStack.add(ReaderRoute(w, c)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    is ReaderRoute -> NavEntry(key) {
                        ReaderScreen(
                            key.workId, key.chapterId,
                            onBack = { backStack.removeLastOrNull() },
                            chapters = readerChapters,
                            domains = readerDomains,
                            progress = readerProgress,
                            settingsStore = readerSettings,
                        )
                    }
                    is SourcesRoute -> NavEntry(key) { SourcesScreen() }
                    is DownloadsRoute -> NavEntry(key) { DownloadsScreen() }
                    is SettingsRoute -> NavEntry(key) { SettingsScreen() }
                    else -> error("未知路由：$key")
                }
            },
        )
    }
}
