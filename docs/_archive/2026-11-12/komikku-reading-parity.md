# Komikku 漫画阅读体验分析与 Kurisa 落地对标方案

> 分析对象：`reference/komikku`（Komikku，Mihon / TachiyomiSY 系 Android 漫画阅读器）。
> 目标：让 `Kurisa`（KMP + CMP 三端聚合阅读器）达到 / 超越 Komikku 的漫画阅读体验。
> 本文档分三部分：A. Komikku 全功能与实现机制；B. Kurisa 现状对照与缺口矩阵；
> C. 分阶段落地计划（精确到 Kurisa 模块与接线点）。

---

## A. Komikku 全功能与实现机制

### A.1 阅读器（`app/.../ui/reader` + `presentation/reader`）

阅读模式共 5 种（`ReadingMode` flag 0~5）：L2R / R2L / 纵向（Pager 系，
`DirectionalViewPager`）+ Webtoon / 连续纵向（RecyclerView 系）。
`ReadingMode.toViewer()` 工厂按漫画独立覆写（`SetMangaViewerFlags`）与全局默认；
`useAutoWebtoon` 按漫画类型自动切条漫并 toast 提示。

- **翻页**：Pager `offscreen=1`，idle 后才 `setChapters` 防跳变；Webtoon 复用
  `RecyclerView + WebtoonLayoutManager + WebtoonAdapter`，点按上下滚 3/4 屏，
  `linearScroll` 平滑自滚。
- **缩放**：静态页用 SSIV（最大 5x、双击 2x、`zoomStart` LEFT/RIGHT/CENTER、
  横屏放大 `landscapeZoom`，KMK 可配禁用缩放/双击/横屏缩放类型 FIT|DOUBLE）；
  GIF 切 PhotoView / AppCompatImageView（双击 1x/2x）；条漫侧 `pinchToZoom /
  doubleTapZoom / zoomOutDisabled / sidePadding / scaleType(FIT/4:3/3:2/16:9/20:9)`。
- **双页切割**：`pageLayout = SINGLE / DOUBLE / AUTOMATIC（横屏自动双页）` +
  `dualPageSplit / invert / rotateToFit（±90°）/ invertDoublePages /
  shiftDoublePage / centerMarginType`；`PagerPageHolder` 按宽高比判
  `fullPage / isolatedPage`，`ImageUtil.splitInHalf` 切半，`InsertPage` 补第二半，
  `updateShifting / splitDoublePages / shiftDoublePages` 管理错位；存图可
  `mergeBitmaps` 拼回。
- **预加载**：`HttpPageLoader` 优先级阻塞队列 + `readerThreads`（默认 2）消费者；
  当前页 p=1、后续 `preloadSize`（默认 10）p=0、重试 p=2；`aggressivePageLoading`
  全入队；`boostPage / retryAll`；跨话 `requestPreloadChapter`。
- **自动滚动**：`autoscrollInterval`（float 秒，-1 关）+ `smoothAutoScroll`；
  `ReaderActivity` repeatOnLifecycle 循环：Pager `moveToNext()`，Webtoon
  `linearScroll / scrollDown`。
- **滤镜/背景**：Compose `ReaderContentOverlay`（亮度 -100~100 + 颜色 `BlendMode`）；
  灰度/反色 `ColorMatrixColorFilter` 挂容器硬件层；`readerTheme` 白/黑/0x202125/
  自动 seedColor（KMK 封面取色）；`flashOnPageChange`（+时长/间隔/BLACK/WHITE 模式）。
- **全屏**：`WindowInsetsControllerCompat` show/hide systemBars + `fullscreen /
  drawUnderCutout`；`keepScreenOn`；`ReaderOrientation → requestedOrientation`；
  页码 `ReaderPageIndicator`；底部按钮集 + 横/竖 seekbar。
- **进度**：`onPageSelected → updateChapterProgress`：逐页写 `last_page_read`；
  末页 `read=true` + `updateTrackChapterRead`（各 Tracker 远端同步）+
  `deleteChapterIfNeeded`（读后删）+ 去重联动；`UpsertHistory`（阅读时长）；
  无痕模式跳过；`SavedStateHandle chapter_id / page_index` +
  `preserveReadingPosition / requestedPage` 恢复；`skipRead / skipFiltered /
  skipDupe / downloadedOnly` 过滤章节列表。
- **书签**：`toggleChapterBookmark`（当前）/ `toggleBookmark(id)` → `UpdateChapter`。
- **过渡/目录**：`ViewerChapters(curr/prev/next)` + `ChapterTransition.Prev/Next`
  holder（`alwaysShowChapterTransition`）；`loadNext / loadPrevious` 相邻话预载；
  `ChapterListDialog + ReaderChapterItem`；`getChapterUrl / openInBrowser / WebView /
  share`。
- **手势**：`ViewerNavigation` 5 实现（RightAndLeft / L / Kindlish / Edge /
  Disabled）RectF 分区 + `TappingInvertMode(NONE/HORZ/VERT/BOTH)` +
  `smallerTapZone`；`GestureDetectorWithLongTap`；音量键/DPAD/鼠标滚轮；
  `cropBorders ×3 + webtoonSidePadding` 经 Coil `cropBorders` 参数；单页拷 stream /
  双页 merge 存图分享（`SaveImageNotifier`）；EH `DataSaver` 省流量；`cacheSize`
  75MB。

图片管线：`getPageList → getImageUrl → getImage → ChapterCache`（DiskLruCache，
`chapter_disk_cache`，key = md5(mangaId+url) / md5(imageUrl)，pageList 存 JSON）；
CBZ `ArchiveReader`（mmap + libarchive）三模式（流直读 / 内存 / 落盘）+ 加密检测；
Coil3（OkHttp fetcher + 自研 AVIF/JXL/HEIF 解码器，并发 fetch 8 / decode 3），
显示层刻意 DISABLED Coil 缓存（ChapterCache 已持久化）。

`ReaderPreferences` 约 50 key（通用过渡/闪屏/双击速度/页码/全屏/刘海/常亮/
缩放/主题/过渡/裁边/平移/横屏放大/双页/长条间距/亮度/滤镜/灰度/跳过已读；
双页 split/invert/rotate；滤镜 brightness/color/grayscale/inverted；
操作 longTap/音量键/导航模式/反转/overlay/点击区；SY/EH threads=2/instantRetry/
aggressive/cache=75MB/autoscroll/preload=10/autoWebtoon 等）。

### A.2 书架 / 详情 / 更新 / 历史 / 统计 / 分类

- **书架**：`LibraryToolbar`（搜索 debounce、多选全选/反选、filter 徽点、
  按分类更新/全局更新、随机漫）→ `HorizontalPager` 按分类分页 + tabs →
  `LazyGrid`（Adaptive128dp / 固定列）/ List / 舒适/紧凑网格；`CommonMangaItem`
 （封面 + 下载/未读/语言/源徽标，长按多选 + 跨页框选 `toggleRangeSelection` +
  Haptic）；`PullRefresh`；`LibraryBottomActionMenu`（改分类/标已读/下载 N 章/
  删除/迁移/`smartSearchMerge` 单条或多条合并/批量更新/去标题符号/推 MangaDex）；
  空库 `EmptyScreen` + 入门链接。
- **详情**：`Scaffold + PullRefresh + VerticalFastScroller + LazyColumn`
 （手机/平板 `TwoPanelBox` 双栏）；`MangaInfoBox`（封面全景/标题/作者/状态/
  fetchInterval/收藏/分享/WebView/编辑 `EditMangaDialog`/合并/迁移）；
  `MangaActionRow`（追踪/收藏/章节设置）；`ExpandableMangaDescription` +
  EH/MangaDex/NH 元数据适配器；`RelatedMangasRow`（推荐/Suggestions）+
  `PagePreviews`；`ChapterHeader`（排序/过滤/显示/扫图组 `ScanlatorFilterDialog`/
  章节设置、缺章数 `missingChaptersCount`）；章节行 `SwipeableActionsBox`
  左右滑 + 长按多选 + `ChapterDownloadIndicator`；底部批量菜单 + FAB 继续阅读。
- **更新页**：`limit=3个月`，按 `dateFetch` 分组（同漫未读升序 + 已读降序 KMK），
  多选下载/标读/书签/删除，`PullRefresh → updateLibrary()`，日历跳 `Upcoming`
  预告页，`UpdatesFilterDialog`。
- **历史页**：按日分组 + 搜索 debounce + `onReselect → resumeLastChapter` 续读 +
  删除/清空/移分类入库，KMK 多选 + `HistoryFilterDialog`（未完结/非书架）。
- **统计页**：Overview / Titles / Chapters / Trackers 四卡。
- **分类页**：拖拽排序（`calvin.reorderable`）、创建/改名/删除，KMK `HideCategory`
  隐藏分类（隐私）。

数据机理：`ScreenModel + StateScreenModel + screenModelScope.launchIO`；
书架三流 combine（过滤 → EXH `SearchEngine.parseQuery` 支持 `id:/src:` 语法 →
分组 `BY_DEFAULT / UNGROUPED / BY_SOURCE / BY_STATUS / BY_TRACK_STATUS` + 排序
含 tracker 均分/随机种子）；章节识别 `ChapterRecognition`（number 双精度解析 +
extra.99 / omake.98 / special.97 后缀规则）；去重同步 `SyncChaptersWithSource`
（url 去重、sanitize、`sourceOrder`、三集合 new/updated/removed、最大上传日期防旧
顶新、空源抛 `NoChaptersException`、已读继承、deleted 集复用 dateFetch 防污染更新
页、EH 接力 lastPageRead、扫图组过滤改名已下载）；智能更新 `FetchInterval`
（近 N 章 upload/fetch 日期中位数间隔、上限 28 天、当天±1 天窗口、miss 翻倍）+
`LibraryUpdateJob`（CoroutineWorker：按分类/分组过滤、完结/已读完/未开始/非发布期
限制、排除源、MangaDex DMCA、每源 `Semaphore(5)` 并发、新章自动下载、失败写
`library_update_errors` KMK）；合并 `MergedMangaReference`（信息源/章节更新/
优先级/下载/排序/去重，MERGED_SOURCE_ID=6969 虚拟条目）。

### A.3 图源 / 浏览 / 扩展 / Feed / 迁移

- **Source 模型**：`Source`（suspend 新 API：popular/latest/search/details/
  chapters/pages/related）+ `CatalogueSource`（Rx 桥）+ `HttpSource`（旧
  fetch*/Request/Parse 对 + KMK related/request 相关）；数据 `SManga`
  （url/title/thumbnail/genre/status/memo + original*）/ `SChapter`（url/name/
  number/scanlator/date_upload/memo）/ `Page`（url/imageUrl + Queue/LoadPage/
  Ready 状态流）/ `Filter`（Header/Select/Text/CheckBox/TriState/Sort/Group/
  SY-AutoComplete）；`SourceFactory.createSources` 一包多源；ABI 约束
  `ExtensionLoader.SUPPORTED_LIB_VERSIONS=[1.4,1.6]`。
- **网络**：单 OkHttp（cookieJar、5MB cache、connect/read 30s call 120s、DoH 12 家、
  Logging + UA 拦截器）；`CloudflareInterceptor`（WebView 隐式过盾 + `cf_clearance`
  回灌，失败抛 `CloudflareBypassException`）；`RateLimitInterceptor`（公平锁 +
  滑动窗口）+ `SpecificHostRateLimitInterceptor`；图片 `newCachelessCallWithProgress`
  带进度；KMK `downloadFileWithResume`（Range + 指数退避）；JS `QuickJS` 供扩展反混淆。
- **EXH 委托源**：`DelegatedHttpSource` 包装 delegate 全转发 + 版本/语言校验；
  内置 EH/NHentai/LANraragi/MangaDex/Pururin；`MergedSource` 多源聚合章节。
- **浏览/搜索/Feed**：`BrowseSourceScreenModel`（Listing/Search + Paging3 +
  `SourcePagingSource`，搜索/genre/筛选/显示模式/隐身/收藏/元数据）+
  `SourceFilterDialog + SavedSearchItem`（筛选持久化 `FilterSerializer` ↔ JSON，
  chips 保存/应用/删除）；`SourcesTab`（语言/置顶/禁用过滤）；`GlobalSearch`
  多源并发；扩展管理（安装/更新/启用/信任 NSFW/语言过滤）+ 仓库 repo
  （`ExtensionStoreRepository`，可增删 repo URL）；Feed（`FeedSavedSearch` 全局表：
  多源聚合、排序、Add/Delete/Reorder、上限、hideInLibrary，备份恢复走常规 Backup）。
- **迁移**：三级（选源范围 → 选漫 → 目标搜索）→ `MigrateMangaUseCase`：
  拉取目标章节 → 按 `chapter_number` 精确搬运（read/dateFetch/bookmark/
  lastPageRead + History，未匹配但 number ≤ maxRead 标已读）→ 分类/追踪
  （EnhancedTracker.migrateTrack）/ 删旧下载 / 拷封面 / 搬 viewerFlags +
  chapterFlags；批量队列 + `MigrationFlag` 位集。

### A.4 下载 / 备份 / 同步 / 追踪 / 后台

- **下载**：`DownloadManager`（队列增删、`startNow` 插队、`queueChapters` 过滤）→
  `Downloader` → `DownloadJob`（WorkManager 前台保活）；状态机
  NOT_DOWNLOADED/QUEUE/DOWNLOADING/DOWNLOADED/ERROR + statusFlow/progressFlow；
  按源分组、每源 1 并发、页级 `flatMapMerge`；超阈（30 队列/单源 15）警告；
  单章流程（建目录 → 查可用空间 <200MB 报错 → `chapter_tmp` → pageList →
  imageUrl 补全 → `.tmp` 写 + retry 3 次 2/4/8s（EH 刷 URL）→ 宽图切分 →
  校验 → `ComicInfo.xml` → CBZ 打包（可选 CbzCrypto 加密）/ 目录改名 →
  cache 登记 + `.nomedia`）；存储 `downloads/<sourceDir>/<mangaDir>/<chapterDir>[.cbz]`，
  章节目录命名 `scanlator_sanitized[_md5(url)6]`（兼容 legacy/hash/ASCII 变体）；
  `DownloadStore`（SharedPrefs 存 mangaId/chapterId/order，重启恢复）+
  `DownloadCache`（三级内存 + ProtoBuf 磁盘 `dl_index_cache_v3`，默认 1h，KMK 可配）。
- **备份**：ProtoBuf + gzip `.tachibk`（`BackupCreator.getFilename()`）；
  内容含书架（url/source/title/作者/genre/status/thumbnail/dateAdded/viewer/
  chapters/categories/tracking/history/updateStrategy/lastModifiedAt/version/
  notes/memo/excludedScanlators + SY customInfo/flatMetadata/mergedRefs +
  J2K custom*）+ 分类/源/偏好/扩展仓库 + SY SavedSearches + KMK Feeds；
  `BackupOptions` 12 开关；自动备份保留 `MAX_AUTO_BACKUPS=4` +
  `BackupFileValidator`；恢复 `sortByNew`（MERGED 置后、已存在置后、lastModifiedAt
  倒序），已存在按 version 大者 `copyFrom`（favorite/og* 取新，OR 合并）；
  `isSync` 直写 read/bookmark；错误写 `komikku_restore_error.txt`。
- **同步**：`SyncManager` 复用 `BackupCreator` 拼 `SyncData(deviceId, backup)`；
  后端 SyncYomi（自建）/ GoogleDrive（`app_sync.proto.gz`）/ KMK WebDAV
  （OkHttp PUT/GET + Basic）；首同步只推远端，否则 `filterFavoritesAndNonFavorites`
  按 version 合并后走 `BackupRestoreJob(start, sync=true)` 落库；触发
  sync_on_chapter_read/open/app_start/resume；周期 `SyncDataJob`；
  `SyncDataJob → LibraryUpdateJob` 链。
- **追踪**：注册表 `TrackerManager` 共 10（MAL/AniList/Kitsu/Shikimori/Bangumi/
  Komga/MangaUpdates/Kavita/Suwayomi/MdList）；`Tracker` 接口
  （search/bind/update/refresh/remoteStatus/LastChapterRead/Score/Dates/Private +
  SY getMangaMetadata/searchById + KMK hasNotStartedReading）；
  `EnhancedTracker`（accept/match/migrate）；`BaseTracker`（client + `TrackPreferences`
  token + 拦截器自动刷新）；OAuth（MAL/AniList/Shikimori/Bangumi/Kitsu 经
  WebView/CustomTab 取 code）/ 自建站账密；双向：读 → 远端 `TrackChapter`
  （到顶自动 Completed）；远端 → 本地 `SyncChapterProgressWithTrack`（连续递增标
  已读，KMK 按 sourceOrder 倒序防卷章乱序，本地大回推远端）；离线队列
  `DelayedTrackingStore + DelayedTrackingUpdateJob`（CONNECTED 约束、指数退避）。
- **后台**：`LibraryUpdateJob`（PeriodicWork interval 小时 + flex 10min；
  约束 UNMETERED/CONNECTED + NOT_VPN/WIFI/NOT_METERED + 充电 + 低电量退避 LINEAR
  10min；`FOREGROUND_SERVICE_TYPE_DATA_SYNC`；target CHAPTERS/SYNC_FOLLOWS/
  PUSH_FAVORITES）+ manual OneTimeWork；其余 MetadataUpdate/SyncData/
  BackupCreate/BackupRestore/DelayedTracking/AppUpdate/EHentaiUpdate Workers；
  `LibraryUpdateStatus/SyncStatus`（KMK）供顶部 banner；
  `LibraryUpdateNotifier` 进度/新增/错误通知。

### A.5 壳 / 导航 / DI / 设置 / 主题

- **启动**：`App`（Injekt importModule：App/Preference/Domain/KMKDomain/
  SYDomain；Coil `newImageLoader`：OkHttp fetcher + 自研 AVIF/JXL/HEIF 解码 +
  disk 2% + memory，并发 IO 8 / 解码 3；通知渠道；`ProcessLifecycleObserver`；
  无痕常驻通知/硬件位图阈值；`Migrator`；WidgetManager；WorkManager +
  SyncDataJob）；`MainActivity`（splash 500~5000ms + Migrator 等待；顶层
  `Navigator(HomeScreen)` + `AppStateBanners`；intent 处理：tab/全库搜索/备份文件/
  加 repo/深链；`CheckForUpdates` + 扩展更新 + Onboarding + WhatsNew）。
- **Activity 共 11 个**：壳只有 MainActivity（Voyager host）；Reader/WebView/
  Unlock（BiometricPrompt 空白）/ DeepLink（转发 MAIN）/ TrackLogin/GoogleDrive/
  EH/MangaDex/Capture 等 OAuth 登录；阅读器与 WebView 独立于 Voyager。
- **导航**：Voyager `Screen`（`eu.kanade.presentation.util.Screen` 基类）+
  ScreenModel（多为 `StateScreenModel`）；Tab 宿主 `HomeScreen(TabNavigator：
  书架/更新/历史/浏览/更多，平板 Rail/手机 Bar，更新数/扩展更新 Badge）`；
  深链 `DeepLinkScreen`（query → 漫画/阅读器/全局搜索）；Manifest 收 MAIN +
  SHORTCUT + tachiyomi:add-repo + mihon:extension-store + file/content
  （.tachibk）+ komikku/mihon scheme。
- **DI**：`AppModule`（Application/SqlDriver SQLCipher/busy_timeout/DB/
  DatabaseHandler/Json/Xml/ProtoBuf/TempFile/ChapterCache/CoverCache/
  NetworkHelper/JavaScriptEngine/SourceManager/ExtensionManager/
  DownloadProvider/Manager/Cache/TrackerManager/ImageSaver/Storage/LocalFS/
  EHUpdateHelper/PagePreviewCache/BackupRestoreStatus/SyncStatus/
  LibraryUpdateStatus/ConnectionsManager/GoogleDriveService）+
  `PreferenceModule`（各域 *Preferences）+ `DomainModule`（Repo 单例 + ~60
  interactors 动词命名）+ KMK（LibraryUpdateError 系 + HideCategory）+ SY
  （元数据/合并/收藏/搜索/Feed/SavedSearch/CustomManga 等 ~40）。
- **设置页 12 入口**：外观（AppTheme ~20 Monet/Tachiyomi/MidnightDusk/Custom/
  MaterialKolor Expressive + mode/amoled/封面取色/相对时间/平板模式/ Watt…）、
  书架（显示/排序/列数/徽章/分类 tabs/计数/默认分类/更新类别/自动更新间隔限制/
  仅未读/滑动动作/ASCII/分组更新）、阅读（~50 key 见 A.1）、下载（仅 WiFi/CBZ/
  切分/边看边下/读后删/分类自动下/并行源 5 页 5）、追踪（各 tracker token/自动
  同步/分数类型）、浏览（语言/隐藏源/NSFW/迁移排序/扩展 repo/黑名单/dataSaver/
  本地源/MangaDex/related）、数据存储（备份 interval 12h/进度条/存储目录/备份
  创建恢复导出/自建 Sync/WebDAV/GoogleDrive/触发时机）、安全（biometric/延时锁/
  时段天数/FLAG_SECURE/通知隐藏/DB 加密/SQL 密码/下载 CBZ 密码）、高级
  （DoH/UA/verbose/清缓存/WorkInfo/SSL 清理）、EH/MangaDex（登录/H@/画质/标签
  过滤/同步/分类/自动更新）、连接（Discord RPC）、关于（版本/日志/许可/更新器）。
- **主题/i18n/安全/更新器**：`TachiyomiTheme`（Base + Monet + KMK 封面取色重载
  DynamicMaterialExpressive seedColor + Fidelity/Vibrant + amoled 纯黑，阅读器独立
  readerTheme）；三套 moko-resources（MR/SYMR/KMR），新串一律 `i18n-kmk/base`，
  Weblate 管翻译；`SecureActivityDelegate`（FLAG_SECURE + 无痕 + SY 按星期/时段
  可锁 + UnlockActivity BiometricPrompt）+ 隐藏分类；更新器 `AppUpdateChecker`
  （GitHub Release → AppUpdateJob/DownloadJob/Notifier/Broadcast）+ 扩展更新。

---

## B. Kurisa 现状对照与缺口矩阵

### B.1 阅读器：已有 vs 缺失

| Komikku 能力 | Kurisa 现状 |
|---|---|
| 5 阅读模式（LTR/RTL/纵向/条漫/连续）+ 按书覆写 + 自动条漫 | ✅ `PAGED/CONTINUOUS × LTR/RTL/VERTICAL` 已有；缺按书覆写 scope（`ReaderSetting.sq` scope 列已预留，当前固定 global）与自动条漫判定 |
| Pager 翻页 + idle 后 setChapters | ✅ HorizontalPager/VerticalPager + `key(chapterId)` 重建，`beyondViewportPageCount=1` |
| 条漫连续滚动 + 点按滚 3/4 屏 | ✅ LazyColumn + 0.75 屏跳进；缺定时自动滚动 |
| 双击/捏合缩放（SSIV 5x/双击 2x/zoomStart/横屏放大） | △ 分页有 CoilZoom（panpf），条漫无捏合；`SMART_FIT` 为桩 |
| 宽页拆分/双页中缝/封面独占/RTL 倒序 | △ 仅 spread 配对（封面独占 + RTL reversed）；无宽页横向拆分、无中缝参数 |
| 预载窗口 + 并发 + 跨话预取 | ✅ before=1/after=2 + Semaphore(3) + 尾部取下章 2 页；缺整章预载可配/WiFi 开关 |
| 单页/章节两级重试 + boost | △ 章节重试 + 单页重试 + maxRetries≤4；无 boost/换线路/浏览器兜底（仅 `needsBrowser` hint） |
| 自动滚动（定时 + 平滑） | ❌ 无 |
| 亮度/颜色滤镜 + 灰度/反色 + 主题画布 | △ 黑遮罩 `dimLevel` + 灰度 ColorMatrix + BLACK/DARK/WHITE 画布；无反色/棕褐/系统亮度 |
| 全屏沉浸/常亮/方向锁定/页码 | △ `contentWindowInsets(0)` + 页码指示；无系统栏隐藏/KeepScreenOn/亮度/方向锁 |
| 逐页进度 + 末页已读 + 时长 + 无痕跳过 | △ 防抖 500ms + percent + 真实时间戳；无已读标记回写（目录无已读态）、无时长统计、无痕即不存 |
| 书签 | ❌ `bookmark` 表已建，无仓/UI |
| 章节过渡卡 + 目录 + 章节跳转 | ✅ TransitionCard + ModalBottomSheet 目录 + 高亮跳转；目录无已读徽标/排序/过滤 |
| 跳过已读/已过滤/重复话 | ❌ `ChapterRef` 裸 title/ordinal，无 isRead/downloaded 字段 |
| 5 区点按 + 反转 + 缩小热区 + 音量键 | △ 4 固定模式 + RTL/纵向适配 + 音量键（Android Activity 可能拦截）；无自定义映射/反转开关 |
| 裁边（白边/黑边×3 + 条漫边距） | ❌ 仅条漫边距参数，无自动裁边 |
| 存图/分享/复制 | ❌ 字节不出 State，无入口 |
| DataSaver 画质切换 | ❌ 无 quality 透传 |
| 追踪双向同步/离线队列 | ❌ `synced=false` 占位 |
| 阅读统计 | ❌ 无 |

### B.2 书架 / 详情 / 系统：已有 vs 缺失

| Komikku 能力 | Kurisa 现状 |
|---|---|
| 分类建/删/改名/排序/批量移动 + 隐藏分类 | △ `shelf/shelf_item` 表 + ensure/addToShelf/removeFromShelf；缺列表/改名/排序/跨架移动/UI，缺 hidden 列（需 migration，加法 + 回滚脚本，沿 M2 纪律） |
| 智能更新 + 更新页 + Upcoming 预告 | ❌ 无 worker/增量拉取/表查询/UI |
| 历史页 + 续读 | ❌ `last_read_at` 列闲置，无写入/查询/UI |
| 阅读统计页 | ❌ 无 |
| 下载队列 UI（暂停/续传/优先级/按章） | △ `download_task` 表 + 内存 controller（互不接线）；无 UI，Ketch 未接 |
| 备份恢复 | ❌ 无（但模型小：shelf/progress/source/settings 均有表/仓） |
| 追踪同步 | ❌ 无 |
| 合并条目/换源迁移 | ❌ stableId 按源隔离；单活跃源架构即堵点（见 C.2-P0①） |
| 全局搜索跨源聚合 | △ 引擎有（searchPage + 游标互补），UI 骨架 |
| 保存的搜索/Feed | ❌ 契约 `enableRankingPage` 字段级 |
| 扩展仓库订阅/更新检查 | △ 粘贴安装 + 签名/扫描/回滚全；缺订阅拉取/版本检查 |
| 多源按作品路由 | ❌ 首个 enabled 源独占；`ReaderRoute/DetailRoute` 不带 sourceKey |
| 本地导入 | △ `local_book` 表 + FormatDetector；无仓/UI |
| 登录（WebView/Cookie/OAuth） | △ Cookie 表/Store/导入解析器；WebView 登录 defer M6 |
| 底部 Tab/深链/Koin/ViewModel 作用域 | △ Navigation3 8 路由全注册 + 测试；无 Tab 脚手架/深链/Koin（M5） |

### B.3 不需要照搬的部分（架构已替代）

- **APK 扩展动态加载** → Kurisa 用 JS 插件 + 签名仓库，整类问题消失；投入应放在
  源能力补齐（explore/category/account/favorites 类型化调用 + UI）。
- **SQLCipher / CBZ 密码 / FLAG_SECURE 按时段锁** → 对应 Kurisa 的 SecureStorage
 （KVault/KSafe/桌面加密文件，M6）与平台安全能力，按需接入，不前置。
- **Discord RPC / MangaDex 专用推送（SYNC_FOLLOWS）/ EHentai H@H** →
  站点专属能力，归入源 `capabilities`，不进宿主主线。
- **`.tachibk` 二进制格式兼容** → 不做二进制兼容，做语义级备份（JSON 导出，
  含源 key + 版本 + work stableId + progress + settings），跨端可读、可手改。

---

## C. 分阶段落地计划

> 约束重申：expect/actual 新增需评审（ADR-006），默认走 KMP 库；
> 安全（WebView/桥/Cookie/引擎/白名单）与隐私（token/阅读记录/SecureStorage）
> 开工前加载 `security-audit` / `privacy-audit`；状态机用 `kotlin-project-state-management`
> 或 `kmp-mvi` 二选一；导航改动用 `kotlin-navigation-compose-multiplatform`；
> 网络改动跑 `UrlPolicyTest`；SQLDelight 变更走 `kmp-sqldelight-setup`（migration 可回滚）。

### C.0 地基（各阶段前置，约 1 轮）

1. **Koin 接入**（`App.kt` 注释已预留 M5）：ViewModel 工厂集中 `:app:shared-ui`，
   先接 `ReaderViewModel` + `Sources` + 新增的 Shelf/Detail ViewModel。
2. **多源路由**（堵点中的堵点）：`ReaderRoute/DetailRoute` 加 `sourceKey`，
   `RegistryChapterProvider` 按作品选源而非首个 enabled 源；`NavRoutesTest`
   补 backstack 断言。否则合并/迁移/多源详情无从谈起。
3. **下载落库接线**：controller 写透 `download_task`（状态/进度/错误），
   内存执行可保留，重启恢复先行；为阅读器"离线续读"入口铺路。
4. **底部 Tab 脚手架**：Shelf / Search / Sources / Downloads / Settings，
   栈顶路由与 Tab 同步（Navigation3 backstack 操作 + 测试）。

### C.1 阅读体验对齐（M3 范畴，最高优先级）

按"感知强度 × 成本"排序，每项精确到文件：

1. **条漫捏合缩放**（差距最大）：`feature/reader/Reader.kt/WebtoonReader` 换 Zoom
   容器（ZoomImage Read Mode）；手势冲突按 ADR-007/R13 压测，必要时单页 ZoomImage +
   连续自绘。附 `ZoomRenderTest/ZoomLongStripTest`。
2. **目录已读态 + 跳过已读**：`core/reader/ChapterRef` 加 `isRead/downloaded`；
   `ChapterProvider` 扩展来源（progress + download_task），`BridgeChapterProvider`
   落 `chapter` 表；目录加已读点 + 过滤 chips；`openChapter` 支持 skipRead。
3. **整章预载可配**：`prefetchNextChapter` 加开关（整章/WiFi 判责）→ 走 C.0③ 的
   download_task；`ensureWindow` 窗口参数可配化（当前 before=1/after=2 写死）。
4. **系统级阅读**：`:core:platform` 加 `KeepAwake/ImmersiveBrightness/OrientationLock`
  （ADR-006 评审，三端薄 actual：Android WIC + `FLAG_KEEP_SCREEN_ON`，
   iOS homeIndicator + brightness，Desktop undecorated + 亮度提示）；`dimLevel`
   保留为内容级。
5. **滤镜补齐**：`imageColorFilter` 加反色/棕褐 ColorMatrix；`SMART_FIT` 真实现
   （宽图 FIT_WIDTH + 长图分段，复用 `Subsampling.kt` 阈值）。
6. **宽页拆分 + 双页中缝**：`ReaderInteraction.buildSpreads` 加宽高比判定拆分 +
   `centerMargin` 参数；纯函数先行单测，不碰 UI。
7. **自动裁边**：解码前按阈值裁白/黑边开关，走 `ImageFetcher.transform` 管线。
8. **自动滚动**：Webtoon 定时 `animateScrollBy` + Pager `moveToNext`，
   interval/开关进 `ReadingConfig`（四步机械法：Config→Intent→ViewModel→Sheet）。
9. **书签**：`bookmark` 表已有 → 补 Repository + `ReaderContract.Intent`（页级锚点
   position/excerpt/note）+ 长按菜单入口。
10. **存图/分享**：`state.pages[index]` 取字节走 FileKit 保存 + 平台分享
    （DesktopShell/实际分享通道按 7.2 补）。
11. **画质切换**：`ImageLoadingConfig + resolvePage` 加 `quality: LOW/HIGH`
    （对齐 EH DataSaver 语义，源侧 `onImageLoad` 已有重写点）。
12. **时长统计 + 历史写入**：`saveNow` 加 `durationMs`；`last_read_at` 写透，
    为历史页铺路。追踪同步（AniList/MAL）单列 P2——需 OAuth + SecureStorage，
    不与本地闭环同轮。

验收：沿用架构 9.3——冷启动首屏 <1s（已缓存）、翻页无白屏、进度不丢、
内存不持续增长 + 三端截图基线。

### C.2 主链路页面（M5 范畴，紧随 C.1）

1. **Shelf 接线**：`observeShelf` + 封面分页懒加载（Coil）+ 万级虚拟化；
   分类管理 UI（建/删/改名/排序/跨架移动，`reorderable` 类库选型走 designsystem）；
   隐藏分类（shelf 加 `hidden` 列 + migration + 回滚脚本）。
2. **Detail 接线**：章节列表虚拟化（`BridgeChapterProvider.chapters` 落 `chapter`
   表，updated_at 供更新对比）+ 已读/下载徽标 + 下载粒度（单章/范围/全部）+
   加架 + 相关推荐（源 `recommend`/`categoryComics` 有则显）。
3. **Search 接线**：`searchPage` 调通 + 跨源聚合扇出（`SourceRuntimeManager` 多源
   并发 + 去重 stableId）+ 筛选（源语言/NSFW）。
4. **Explore 接线**：`explore/categoryComics` 类型化调用 + 分页
  （multiplatform-paging / lazy-pagination-compose 二选一）。
5. **Downloads UI**：任务列表（暂停/续传/优先级/按章删）+ Ketch 真执行替换内存版。
6. **Settings**：主题（MaterialExpressiveTheme 切换 + 封面取色 kmPalette）/
   阅读（ReadingConfig 全量）/ 网络（DoH/UA/代理沿 SourceHttpClient）/
   存储（目录迁移 + 配额）/ 关于（AboutLibraries）。
7. **更新调度 + 更新页**：KMP WorkManager（Android/iOS）+ 桌面常驻协程，按源
   轮询 → `chapter.updated_at` 对比 → 更新角标 + 更新页分组（未读升序/已读降序
   照搬 Komikku 分组语义）；Upcoming 预告后置。
8. **历史页**：`last_read_at` 倒序 + 续读 + 按日分组 + 清理。
9. **备份恢复**：JSON 导出（shelf/work/progress/source/settings + 源脚本版本 pin），
   恢复按 stableId 合并（last-write-wins，读记录 OR 合并——语义照搬 A.4，
   格式不兼容 `.tachibk`）。
10. **源设置页**：`descriptor.settings` 声明式渲染（沿同步桥回读链）。

### C.3 生态与长期（M6/M7 范畴）

- 仓库订阅 + 更新检查（一键批量升级）→ 源生态复用（Kurisa-Sources 适配层已验证
  E2E，见 `docs/real-source-e2e.md`）。
- 保存的搜索 / Feed（含 ranking）。
- 阅读统计页（时长/章节数/Tracker 均分，UI 照搬 Overview/Titles/Chapters/Trackers 四卡）。
- 追踪同步（AniList/MAL/Kitsu…）：`SourceCapability` 外的新 `TrackerCapability` +
  OAuth（CustomTab/ASWebAuthentication/loopback）+ SecureStorage + 双向规则
  （本地大回推/远端连续递增标已读/离线队列，语义照搬 A.4）。
- 换源迁移 / 多源合并（stableId 跨源映射 + 进度搬运；依赖 C.0②）。
- WebView 登录（Compose WebView Multiplatform，Android/iOS；桌面 helper 进程
  按 ADR-010）。
- 本地导入 UI（CBZ/EPUB/TXT/PDF + 拖放，Kompress/XmlUtil/PdfReader 均已选型）。
- iOS 壳 + 三端截图基线 + 侧载分发（`app/ios` 目前仅 README）。

### C.4 建议的执行顺序（一句话版）

C.0（路由+Koin+下载落库+Tab，解堵）→ C.1①②③（条漫缩放/已读/预载，体验落差最大处）
→ C.1④⑤⑥⑦⑧（系统级+滤镜+拆分+裁边+自滚）→ C.2①②③（书架/详情/搜索主链路可用）
→ C.1⑨⑩⑪⑫ + C.2⑤⑥⑦⑧⑨⑩（书签/分享/画质/时长 + 下载/设置/更新/历史/备份）
→ C.3（生态长期）。每批沿用现有纪律：先补单测（desktopTest 217 绿线），
Android debug APK 可构建，iOS 待 macOS 验证。


