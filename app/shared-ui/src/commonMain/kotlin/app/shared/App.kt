package app.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import core.designsystem.AppTheme
import core.reader.ChapterProvider
import core.reader.ReaderSettingsStore
import data.localimport.LocalSourceFileSystem
import data.localimport.LocalSourceScanner
import data.repository.BookmarkRepository
import core.model.LocalBook
import data.repository.LocalBookImporter
import data.repository.LocalBookRepository
import data.repository.LocalLibrarySync
import data.repository.NoopBookmarkRepository
import data.repository.NoopLocalBookRepository
import data.repository.ProgressRepository
import feature.detail.DetailRoute
import feature.detail.DetailScreen
import feature.detail.WorkDetailUi
import feature.downloads.DownloadsRoute
import feature.downloads.DownloadsScreen
import feature.explore.ExploreRoute
import feature.explore.ExploreScreen
import feature.reader.DefaultReaderSettingsStore
import feature.reader.NoopChapterProvider
import feature.reader.NoopProgressRepository
import feature.reader.ReaderRoute
import feature.reader.ReaderScreen
import feature.search.SearchPageUi
import feature.search.SearchRoute
import feature.search.SearchScreen
import feature.settings.SettingsRoute
import feature.settings.SettingsScreen
import feature.shelf.ShelfRoute
import feature.shelf.ShelfScreen
import feature.sources.InstallOutcomeUi
import feature.sources.InstallPreviewUi
import feature.sources.RuleTestResultUi
import feature.sources.RuleTestRoute
import feature.sources.RuleTestScreen
import feature.sources.SourceUiItem
import feature.sources.SourcesRoute
import feature.sources.SourcesScreen
import feature.sources.parseRuleTestResult

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
        subclass(RuleTestRoute::class)
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
    /** 书签持久化（平台壳传入 SQLDelight 实现；默认内存态）。 */
    bookmarkRepository: BookmarkRepository = NoopBookmarkRepository,
    /** 本地库文件系统（平台壳传入；为空时本地归档图片不可读）。 */
    localFs: LocalSourceFileSystem? = null,
    /** 本地书仓库（平台壳传入 SQLDelight 实现；为空时跳过启动扫描）。 */
    localBooks: LocalBookRepository = NoopLocalBookRepository,
    /** 本地书选择器（平台壳注入：桌面 FileDialog / Android SAF；iOS 暂缺传 null）。 */
    pickLocalFiles: (suspend () -> List<Pair<String, ByteArray>>)? = null,
    /** 源目录（平台壳装配后传入；null = 无源管理能力，列表空，开关无操作）。 */
    sourceCatalog: SourceCatalog? = null,
    /**
     * 脚本落盘（平台实现：Desktop 写 `~/.kurisa/sources/<key>/<version>.js(.rules.json)`，
     * Android 写 `filesDir/sources/...`；返回 installPath。null = 不支持安装）。
     */
    saveSourceScript: (suspend (key: String, version: String, script: String, format: String) -> String)? = null,
) {
    AppTheme {
        val backStack = rememberNavBackStack(
            SavedStateConfiguration { serializersModule = appSerializersModule },
            ShelfRoute,
        )
        // 源管理页状态（App 层持有，feature 只收纯展示行 + 回调，见 Screen/Content 分离）。
        var sourceItems by remember { mutableStateOf(emptyList<SourceUiItem>()) }
        // 阅读器白名单随启用/禁用刷新；已打开的阅读器沿用旧集合，新打开的生效。
        var domains by remember { mutableStateOf(readerDomains) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(sourceCatalog) {
            sourceItems = withContext(Dispatchers.Default) {
                sourceCatalog?.snapshotUi().orEmpty()
            }
        }
        // 本地库状态（书架同构展示用；导入/扫描后刷新）。
        var localBookList by remember { mutableStateOf(emptyList<LocalBook>()) }
        suspend fun refreshLocalBooks() {
            localBookList = runCatching { localBooks.list() }.getOrDefault(emptyList())
        }
        suspend fun syncLocalLibrary() {
            val fs = localFs ?: return
            runCatching {
                LocalLibrarySync(
                    LocalSourceScanner(fs),
                    localBooks,
                ) { kotlin.time.Clock.System.now().toEpochMilliseconds() }.sync()
            }
            refreshLocalBooks()
        }
        // 本地库启动扫描入库（失败静默，阅读链路不受影响）。
        LaunchedEffect(localFs, localBooks) {
            withContext(Dispatchers.Default) { syncLocalLibrary() }
        }
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = { key: NavKey ->
                when (key) {
                    is ShelfRoute -> NavEntry(key) {
                        ShelfScreen(
                            onOpenDetail = { backStack.add(DetailRoute(it)) },
                            localBooks = localBookList,
                            pickLocalFiles = pickLocalFiles,
                            onOpenLocalBook = { workId ->
                                scope.launch {
                                    val first = runCatching { readerChapters.chapters(workId) }
                                        .getOrNull()?.firstOrNull() ?: return@launch
                                    backStack.add(ReaderRoute(workId, first.id))
                                }
                            },
                            onDeleteLocalBook = { workId ->
                                scope.launch {
                                    withContext(Dispatchers.Default) {
                                        val fs = localFs ?: return@withContext
                                        runCatching {
                                            LocalBookImporter(fs, localBooks) {
                                                kotlin.time.Clock.System.now().toEpochMilliseconds()
                                            }.remove(workId)
                                        }
                                        syncLocalLibrary()
                                    }
                                }
                            },
                            onImportFiles = localFs?.let { fs ->
                                { files: List<Pair<String, ByteArray>> ->
                                    withContext(Dispatchers.Default) {
                                        runCatching {
                                            LocalBookImporter(fs, localBooks) {
                                                kotlin.time.Clock.System.now().toEpochMilliseconds()
                                            }.import(files)
                                        }
                                        syncLocalLibrary()
                                    }
                                }
                            },
                        )
                    }
                    is ExploreRoute -> NavEntry(key) {
                        ExploreScreen(
                            key.sourceKey,
                            onOpenDetail = { workId ->
                                sourceCatalog?.bindWork(workId, key.sourceKey)
                                backStack.add(DetailRoute(workId, key.sourceKey))
                            },
                        )
                    }
                    is SearchRoute -> NavEntry(key) {
                        SearchScreen(
                            loader = { keyword, page ->
                                withContext(Dispatchers.Default) {
                                    sourceCatalog?.search(keyword, page)
                                        ?: SearchPageUi(errors = listOf("未装配源目录"))
                                }
                            },
                            onOpenDetail = { work ->
                                sourceCatalog?.bindWork(work.workId, work.sourceKey)
                                backStack.add(
                                    DetailRoute(work.workId, work.sourceKey, work.title),
                                )
                            },
                        )
                    }
                    is DetailRoute -> NavEntry(key) {
                        sourceCatalog?.bindWork(key.workId, key.sourceKey)
                        DetailScreen(
                            workId = key.workId,
                            sourceKey = key.sourceKey,
                            title = key.title,
                            loader = { workId, sourceKey ->
                                withContext(Dispatchers.Default) {
                                    sourceCatalog?.detail(workId, sourceKey)
                                        ?: WorkDetailUi(workId, sourceKey, workId, error = "未装配源目录")
                                }
                            },
                            onOpenReader = { w, c ->
                                sourceCatalog?.bindWork(w, key.sourceKey)
                                backStack.add(ReaderRoute(w, c, key.sourceKey))
                            },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    is ReaderRoute -> NavEntry(key) {
                        sourceCatalog?.bindWork(key.workId, key.sourceKey)
                        ReaderScreen(
                            key.workId, key.chapterId,
                            onBack = { backStack.removeLastOrNull() },
                            chapters = readerChapters,
                            domains = domains,
                            progress = readerProgress,
                            settingsStore = readerSettings,
                            bookmarks = bookmarkRepository,
                            localFs = localFs,
                            cookies = sourceCatalog?.cookies,
                            httpClient = sourceCatalog?.httpClient,
                        )
                    }
                    is SourcesRoute -> NavEntry(key) {
                        SourcesScreen(
                            items = sourceItems,
                            onOpenRuleTest = { backStack.add(RuleTestRoute) },
                            onToggle = { toggleKey, enabled ->
                                scope.launch {
                                    val ok = withContext(Dispatchers.Default) {
                                        sourceCatalog?.setEnabled(toggleKey, enabled) ?: false
                                    }
                                    if (ok) {
                                        sourceItems = withContext(Dispatchers.Default) {
                                            sourceCatalog?.snapshotUi().orEmpty()
                                        }
                                        domains = withContext(Dispatchers.Default) {
                                            sourceCatalog?.currentDomains() ?: readerDomains
                                        }
                                    }
                                }
                            },
                            onPreviewInstall = { script ->
                                withContext(Dispatchers.Default) {
                                    sourceCatalog?.previewInstall(script)
                                        ?: InstallPreviewUi(error = "未装配")
                                }
                            },
                            onConfirmInstall = { script, confirmed ->
                                val saver = saveSourceScript
                                val outcome = withContext(Dispatchers.Default) {
                                    if (sourceCatalog == null || saver == null) {
                                        InstallOutcomeUi.Rejected("未装配")
                                    } else {
                                        sourceCatalog.installSource(
                                            script,
                                            saveScript = saver,
                                            confirmed = confirmed,
                                        )
                                    }
                                }
                                if (outcome is InstallOutcomeUi.Installed) {
                                    sourceItems = withContext(Dispatchers.Default) {
                                        sourceCatalog?.snapshotUi().orEmpty()
                                    }
                                    domains = withContext(Dispatchers.Default) {
                                        sourceCatalog?.currentDomains() ?: readerDomains
                                    }
                                }
                                outcome
                            },
                        )
                    }
                    is RuleTestRoute -> NavEntry(key) {
                        RuleTestScreen(
                            sources = sourceItems,
                            onBack = { backStack.removeLastOrNull() },
                            onDiagnose = { k, kw, page, w, c ->
                                withContext(Dispatchers.Default) {
                                    val raw = runCatching {
                                        sourceCatalog?.testRules(k, kw, page, w, c)
                                    }.getOrNull()
                                    if (raw == null) {
                                        RuleTestResultUi(error = "未装配")
                                    } else {
                                        parseRuleTestResult(raw)
                                    }
                                }
                            },
                        )
                    }
                    is DownloadsRoute -> NavEntry(key) { DownloadsScreen() }
                    is SettingsRoute -> NavEntry(key) { SettingsScreen() }
                    else -> error("未知路由：$key")
                }
            },
        )
    }
}
