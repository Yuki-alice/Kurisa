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
import data.images.ImageRequest
import data.localimport.LocalLibrary
import data.localimport.LocalSourceFileSystem
import data.localimport.LocalSourceScanner
import data.localimport.localMangaName
import data.repository.BookmarkRepository
import data.repository.LocalBookImporter
import data.repository.LocalBookRepository
import data.repository.LocalLibrarySync
import data.repository.NoopBookmarkRepository
import data.repository.NoopLocalBookRepository
import core.model.LocalBook
import data.repository.ProgressRepository
import feature.detail.DetailChapterUi
import feature.detail.DetailData
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
import feature.search.SearchPageUi
import feature.search.SearchResultUi
import feature.search.SearchRoute
import feature.search.SearchScreen
import feature.search.SearchSourceUi
import feature.settings.SettingsRoute
import feature.settings.SettingsScreen
import feature.shelf.ShelfRoute
import feature.shelf.ShelfScreen
import feature.shelf.ShelfWorkUi
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
 * ViewModel 工厂集中于此（Koin 容器见 [AppContainer]，M5 起统一从容器取）。
 *
 * [readerChapters]/[readerDomains] 是阅读器的真实源注入点：
 * 平台壳/SourceRuntime 装配好后传入；默认 Noop 保证 C1（主仓零预置源）。
 *
 * [localLibraryRoot]/[onPickLocalDirectory] 是本地导入入口（B1）：
 * 桌面壳提供系统目录选择器；未提供时书架只展示在线内容，不伪造能力。
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
    /** 本地库根目录（null = 未选择；桌面壳可从配置恢复）。 */
    localLibraryRoot: String? = null,
    /** 系统目录选择器（挂起，内部切到平台线程；返回 null = 用户取消）。 */
    onPickLocalDirectory: (suspend () -> String?)? = null,
    /** 应用级依赖容器（S2）：共享 CookieStore/HttpClient/ImageFetcher；null = 无共享栈。 */
    container: AppContainer? = null,
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

        // ---- 本地库（B1/B2）----
        var localRoot by remember { mutableStateOf(localLibraryRoot) }
        var localWorks by remember { mutableStateOf(emptyList<ShelfWorkUi>()) }
        var picking by remember { mutableStateOf(false) }
        val localLibrary = remember(localRoot) {
            localRoot?.takeIf { it.isNotBlank() }?.let {
                LocalLibrary(LocalSourceFileSystem.fromDirectory(it))
            }
        }
        LaunchedEffect(localLibrary) {
            localWorks = if (localLibrary == null) {
                emptyList()
            } else {
                withContext(Dispatchers.Default) {
                    localLibrary.works().map { w ->
                        ShelfWorkUi(
                            workId = w.workId,
                            title = w.title,
                            subtitle = "${w.chapterCount} 话",
                            sourceKey = "",
                            badge = "本地",
                            coverBytes = w.coverBytes,
                        )
                    }
                }
            }
        }
        // 本地/在线组合器：按 workId 前缀分派，阅读器只认一个 ChapterProvider。
        val compositeChapters = remember(localLibrary, readerChapters) {
            localLibrary?.let {
                LocalFirstChapterProvider(it.chapterProvider(), readerChapters)
            } ?: readerChapters
        }
        val pickHandler: (() -> Unit)? = onPickLocalDirectory?.let { pick ->
            {
                scope.launch {
                    picking = true
                    val dir = runCatching { pick() }.getOrNull()
                    if (!dir.isNullOrBlank()) localRoot = dir
                    picking = false
                }
            }
        }

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
                            works = localWorks,
                            localRootLabel = localRoot,
                            picking = picking,
                            onPickDirectory = pickHandler,
                            onOpenWork = { w ->
                                backStack.add(DetailRoute(w.workId, w.sourceKey, w.title))
                            },
                            onOpenSources = { backStack.add(SourcesRoute) },
                            onOpenSearch = { backStack.add(SearchRoute) },
                            // 导入库（M4）与目录扫描（S1）并存；本地 id 必须走组合器，
                            // 否则 `local:` 前缀会被在线源当成站点 id。
                            localBooks = localBookList,
                            pickLocalFiles = pickLocalFiles,
                            onOpenLocalBook = { workId ->
                                scope.launch {
                                    val first = runCatching { compositeChapters.chapters(workId) }
                                        .getOrNull()?.firstOrNull() ?: return@launch
                                    val title = localBookList.firstOrNull { it.id == workId }?.title.orEmpty()
                                    backStack.add(ReaderRoute(workId, first.id, "", title))
                                }
                            },
                            onDeleteLocalBook = { workId ->
                                scope.launch {
                                    withContext(Dispatchers.Default) {
                                        val fs = localFs
                                        if (fs != null) {
                                            runCatching {
                                                LocalBookImporter(fs, localBooks) {
                                                    kotlin.time.Clock.System.now().toEpochMilliseconds()
                                                }.remove(workId)
                                            }
                                            syncLocalLibrary()
                                        }
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
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    is SearchRoute -> NavEntry(key) {
                        val searchSources = remember(sourceItems, sourceCatalog) {
                            sourceCatalog?.enabledSources()
                                ?.map { SearchSourceUi(it.key, it.name) }
                                .orEmpty()
                        }
                        SearchScreen(
                            sources = searchSources,
                            onOpenDetail = { workId, sourceKey, title ->
                                backStack.add(DetailRoute(workId, sourceKey, title))
                            },
                            search = { sourceKey, keyword, page ->
                                sourceCatalog?.search(sourceKey, keyword, page)
                                    ?.let { p ->
                                        val doms = sourceCatalog.currentDomains()
                                        SearchPageUi(
                                            works = p.works.map { w ->
                                                SearchResultUi(
                                                    workId = w.id,
                                                    title = w.title,
                                                    coverBytes = w.cover?.takeIf { it.isNotBlank() }?.let { url ->
                                                        runCatching {
                                                            container?.imageFetcher?.fetch(
                                                                ImageRequest(url = url, domains = doms),
                                                            )?.bytes
                                                        }.getOrNull()
                                                    },
                                                )
                                            },
                                            maxPage = p.maxPage,
                                        )
                                    } ?: SearchPageUi()
                            },
                        )
                    }
                    is DetailRoute -> NavEntry(key) {
                        DetailScreen(
                            workId = key.workId,
                            sourceKey = key.sourceKey,
                            title = key.title,
                            onOpenReader = { w, c, chapterTitle ->
                                backStack.add(
                                    ReaderRoute(w, c, key.sourceKey, chapterTitle.ifBlank { key.title }),
                                )
                            },
                            onBack = { backStack.removeLastOrNull() },
                            load = {
                                val localName = localMangaName(key.workId)
                                if (localName != null) {
                                    val libWork = localLibrary?.work(localName)
                                    val chapters = compositeChapters.chapters(key.workId)
                                    DetailData(
                                        title = libWork?.title ?: key.title,
                                        description = "本地库作品，共 ${chapters.size} 话。",
                                        meta = "${chapters.size} 话 · 本地库",
                                        coverBytes = libWork?.coverBytes,
                                        chapters = chapters.map { DetailChapterUi(it.id, it.title) },
                                    )
                                } else {
                                    val provider = sourceCatalog?.providerFor(key.sourceKey)
                                    val chapters = provider?.chapters(key.workId).orEmpty()
                                    val info = sourceCatalog?.workInfo(key.sourceKey, key.workId)
                                    val coverBytes = info?.cover?.takeIf { it.isNotBlank() }?.let { url ->
                                        runCatching {
                                            container?.imageFetcher?.fetch(
                                                ImageRequest(
                                                    url = url,
                                                    domains = sourceCatalog.currentDomains(),
                                                ),
                                            )?.bytes
                                        }.getOrNull()
                                    }
                                    DetailData(
                                        title = info?.title?.takeIf { it.isNotBlank() } ?: key.title,
                                        description = info?.description.orEmpty(),
                                        // 来源单独用 chip 展示，这里只放话数，避免同一信息出现两次。
                                        meta = if (chapters.isEmpty()) "" else "${chapters.size} 话",
                                        coverBytes = coverBytes,
                                        chapters = chapters.map { DetailChapterUi(it.id, it.title) },
                                    )
                                }
                            },
                        )
                    }
                    is ReaderRoute -> NavEntry(key) {
                        val isLocal = localMangaName(key.workId) != null
                        // 按路由 sourceKey 绑定（B4）；本地作品走组合器，未指定源时沿用默认 provider。
                        val chapters = remember(key.sourceKey, sourceCatalog, compositeChapters, isLocal) {
                            if (isLocal) compositeChapters
                            else sourceCatalog?.providerFor(key.sourceKey) ?: readerChapters
                        }
                        ReaderScreen(
                            key.workId, key.chapterId,
                            onBack = { backStack.removeLastOrNull() },
                            chapters = chapters,
                            domains = domains,
                            progress = readerProgress,
                            settingsStore = readerSettings,
                            bookmarks = bookmarkRepository,
                            // B1：本地作品的图片出口必须叠加本地抓取器，否则出不了图。
                            // 目录扫描（localLibrary.fs）与导入库（平台 fs）都是本地作品。
                            localFs = if (isLocal) (localLibrary?.fs ?: localFs) else null,
                            mangaTitle = key.title,
                            imageFetcher = container?.imageFetcher,
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
