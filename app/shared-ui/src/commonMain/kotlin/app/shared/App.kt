package app.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import core.designsystem.AppTheme
import core.designsystem.Tokens
import core.model.DownloadStatus
import core.model.DownloadTask
import core.reader.ChapterProvider
import core.reader.ReaderSettingsStore
import data.download.DownloadController
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
import feature.downloads.DownloadTaskUi
import feature.downloads.DownloadsRoute
import feature.downloads.DownloadsScreen
import feature.explore.ExplorePageUi
import feature.explore.ExploreRoute
import feature.explore.ExploreScreen
import feature.explore.ExploreWorkUi
import feature.reader.DefaultReaderSettingsStore
import feature.reader.NoopChapterProvider
import feature.reader.NoopProgressRepository
import feature.reader.ReaderRoute
import feature.reader.ReaderScreen
import feature.search.SearchPageUi
import feature.search.SearchWorkUi
import feature.search.SearchRoute
import feature.search.SearchScreen
import feature.settings.SettingsRoute
import feature.settings.SettingsScreen
import feature.shelf.ShelfRoute
import feature.shelf.ShelfScreen
import feature.sources.InstallOutcomeUi
import feature.sources.InstallPreviewUi
import feature.sources.RepoUpdateRowUi
import feature.sources.RepoUpdatesUi
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
    /** 书架装配（平台壳传入 SQLDelight 实现；null = 在线收藏分区不展示）。 */
    shelf: ShelfBindings? = null,
    /** 本地书选择器（平台壳注入：桌面 FileDialog / Android SAF；iOS 暂缺传 null）。 */
    pickLocalFiles: (suspend () -> List<Pair<String, ByteArray>>)? = null,
    /** 源目录（平台壳装配后传入；null = 无源管理能力，列表空，开关无操作）。 */
    sourceCatalog: SourceCatalog? = null,
    /**
     * 脚本落盘（平台实现：Desktop 写 `~/.kurisa/sources/<key>/<version>.js(.rules.json)`，
     * Android 写 `filesDir/sources/...`；返回 installPath。null = 不支持安装）。
     */
    saveSourceScript: (suspend (key: String, version: String, script: String, format: String) -> String)? = null,
    /** 下载编排（平台壳构造；null = 下载页空态、详情页不显示「下载本章」）。 */
    downloads: DownloadController? = null,
    /**
     * 源仓库索引读取（宿主注入，**只读本地文件、不发起任意 URL 请求**）。
     * null = 「检查更新」返回未配置，fail-closed 不联网。
     */
    fetchRepoIndex: (suspend () -> String)? = null,
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
        val localBookFlow = remember { MutableStateFlow(emptyList<LocalBook>()) }
        suspend fun refreshLocalBooks() {
            localBookFlow.value = runCatching { localBooks.list() }.getOrDefault(emptyList())
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
        // 默认书架幂等保证（老库首次启动补行）；在线收藏流由 ViewModel 消费。
        LaunchedEffect(shelf) {
            runCatching { shelf?.ensureDefault() }
        }
        val onlineShelf = remember(shelf) {
            shelf?.observeUi() ?: flowOf(emptyList())
        }
        val coverLoader: (suspend (String) -> ByteArray?)? = remember(sourceCatalog) {
            sourceCatalog?.let { catalog -> { url: String -> catalog.coverBytes(url) } }
        }
        // 最小导航壳：顶层四页签 + 顶栏「源管理 / 设置」。视觉待原型定稿后整体换样，
        // 这里只为让后端能力可触达（详情 / 阅读器仍全屏沉浸，不显示导航壳）。
        val current = backStack.lastOrNull()
        Column(Modifier.fillMaxSize()) {
            if (current.showsAppChrome()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Tokens.spacingL),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { backStack.add(SourcesRoute) }) { Text("源管理") }
                    TextButton(onClick = { backStack.add(SettingsRoute) }) { Text("设置") }
                }
            }
            Box(Modifier.weight(1f)) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = { key: NavKey ->
                        when (key) {
                            is ShelfRoute -> NavEntry(key) {
                                ShelfScreen(
                                    onOpenDetail = { workId, sourceKey ->
                                        sourceCatalog?.bindWork(workId, sourceKey)
                                        backStack.add(DetailRoute(workId, sourceKey))
                                    },
                                    onlineWorks = onlineShelf,
                                    localBooks = localBookFlow,
                                    pickLocalFiles = pickLocalFiles,
                                    coverLoader = coverLoader,
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
                                    onRemoveFromShelf = shelf?.let { bindings ->
                                        { workId, sourceKey ->
                                            scope.launch {
                                                bindings.removeFromSource(sourceKey, workId)
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
                                    loader = { src, page ->
                                        withContext(Dispatchers.Default) {
                                            val result = sourceCatalog?.explore(sourceKey = src, page = page)
                                                ?: SearchPageUi(errors = listOf("未装配源目录"))
                                            ExplorePageUi(
                                                works = result.works.map {
                                                    ExploreWorkUi(it.workId, it.title, it.sourceKey)
                                                },
                                                errors = result.errors,
                                            )
                                        }
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
                                    onAddToShelf = shelf?.let { bindings ->
                                        { work -> scope.launch { bindings.addFromSearch(work) } }
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
                                    onAddToShelf = shelf?.let { bindings ->
                                        {
                                            scope.launch {
                                                bindings.addFromSearch(
                                                    SearchWorkUi(
                                                        workId = key.workId,
                                                        sourceKey = key.sourceKey,
                                                        title = key.title,
                                                    ),
                                                )
                                            }
                                        }
                                    },
                                    onDownloadChapter = downloads?.let { controller ->
                                        { chapterId ->
                                            scope.launch {
                                                controller.enqueue(
                                                    DownloadTask(
                                                        id = "${key.sourceKey}|${key.workId}|$chapterId",
                                                        workId = key.workId,
                                                        chapterId = chapterId,
                                                        priority = 10,
                                                        createdAtEpochMs = kotlin.time.Clock.System
                                                            .now().toEpochMilliseconds(),
                                                    ),
                                                )
                                            }
                                        }
                                    },
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
                                    onCheckUpdates = sourceCatalog?.let { catalog ->
                                        {
                                            val fetcher = fetchRepoIndex
                                            if (fetcher == null) {
                                                RepoUpdatesUi(error = "未配置源仓库索引（宿主未注入本地索引读取）")
                                            } else {
                                                // 索引只由宿主注入（本地文件），本链路不发起任意 URL 请求。
                                                val result = catalog.refreshRepoUpdates { fetcher() }
                                                RepoUpdatesUi(
                                                    rows = result.candidates.map { candidate ->
                                                        RepoUpdateRowUi(
                                                            key = candidate.key,
                                                            version = candidate.version,
                                                            installedVersion = candidate.installedVersion,
                                                            reason = candidate.reason,
                                                        )
                                                    },
                                                    error = result.error,
                                                )
                                            }
                                        }
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
                            is DownloadsRoute -> NavEntry(key) {
                                val taskFlow = remember(downloads) {
                                    downloads?.tasks?.map { list -> list.map { it.toUi() } }
                                        ?: flowOf(emptyList())
                                }
                                DownloadsScreen(
                                    tasks = taskFlow,
                                    onPause = { id ->
                                        downloads?.let { c -> scope.launch { c.pause(id) } }
                                    },
                                    onResume = { id ->
                                        downloads?.let { c -> scope.launch { c.resume(id) } }
                                    },
                                    onCancel = { id ->
                                        downloads?.let { c -> scope.launch { c.cancel(id) } }
                                    },
                                )
                            }
                            is SettingsRoute -> NavEntry(key) { SettingsScreen() }
                            else -> error("未知路由：$key")
                        }
                    },
                )
            }
            if (current.showsAppChrome()) {
                NavigationBar {
                    TopTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab.matches(current),
                            onClick = {
                                if (!tab.matches(current)) {
                                    // 回到根再进目标，避免返回栈无限堆积。
                                    while (backStack.size > 1) backStack.removeLastOrNull()
                                    if (tab != TopTab.Shelf) backStack.add(tab.route())
                                }
                            },
                            icon = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    }
}

/** 顶层页签；`Explore` 用空 sourceKey 表示「聚合全部启用源」。 */
private enum class TopTab(val label: String) {
    Shelf("书架"),
    Search("搜索"),
    Explore("探索"),
    Downloads("下载"),
    ;

    fun route(): NavKey = when (this) {
        Shelf -> ShelfRoute
        Search -> SearchRoute
        Explore -> ExploreRoute("")
        Downloads -> DownloadsRoute
    }

    fun matches(key: NavKey?): Boolean = when (this) {
        Shelf -> key is ShelfRoute
        Search -> key is SearchRoute
        Explore -> key is ExploreRoute
        Downloads -> key is DownloadsRoute
    }
}

/** 哪些路由显示导航壳（详情 / 阅读器保持全屏）。 */
private fun NavKey?.showsAppChrome(): Boolean =
    this is ShelfRoute || this is SearchRoute || this is ExploreRoute ||
        this is DownloadsRoute || this is SourcesRoute || this is SettingsRoute

/** 下载任务领域行 → 下载页展示行（映射收口在 App 层，feature 不认识领域枚举）。 */
internal fun DownloadTask.toUi(): DownloadTaskUi = DownloadTaskUi(
    id = id,
    title = "$workId / $chapterId",
    statusLabel = status.name,
    done = done,
    total = total,
    error = error,
    canPause = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING,
    canResume = status == DownloadStatus.PAUSED || status == DownloadStatus.FAILED,
)
