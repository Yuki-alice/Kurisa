# Komikku 阅读器视觉/UI/UX + 本地阅读完美复刻规格

> **状态注记（2026-11-12）**：本文是视觉/UX 规格参考，不是实施计划。阅读器渲染与交互
> 已基本落地，本地导入接线与端到端验收以 `docs/comic-e2e-plan.md` 为准；
> 文中剩余待办不再单独排期。

> 范围（2026-11-12 收敛）：只复刻 **本地阅读体验** 与 **阅读器视觉表现/UI/UX**，
> 其他功能（书架/更新/追踪/备份/扩展仓库等）后续再实现。
> 对标基线：`reference/komikku`；落地位置：`Kurisa/feature/reader`、
> `Kurisa/core/reader`、`Kurisa/data/localimport`、`Kurisa/data/cache`、
> `Kurisa/data/images`；约束见 `Kurisa/docs/architecture.md`（ADR-006/007）与
> `Kurisa/AGENTS.md`（技能路由/安全红线）。

---

## 第一部分 · 阅读器视觉/UI/UX 复刻规格

### 1. 布局结构树与显隐规则

```
Box(fillMaxSize)
├── PagedReader / WebtoonReader          # 内容层（Viewer）
├── ReaderPageIndicator                  # align BottomCenter + navigationBarsPadding
│   └── visible = !menuVisible && showPageNumber
├── ReaderContentOverlay                 # 亮度/颜色滤镜
│   └── + DisplayRefreshHost（flashOnPageChange 时）
└── ReaderAppBars : Column(fillMaxHeight)
    ├── 顶 TopBar（AnimatedVisibility: slideInVertically -it 200ms + fade 150ms）
    │   └── Column { ReaderTopBar(bg=surfaceColorAtElevation(3dp).copy(alpha=.9深色/.95浅色))
    │               ExhUtils（折叠条） }
    ├── 中 weight(1f)：Bottom→Spacer；VerticalLeft/Right→横向 slide+fade 的 ChapterNavigatorVert
    └── 底 BottomBar（AnimatedVisibility 纵向+it）
        └── Column(spacedBy=small) { [Bottom 才有] ChapterNavigator横版
                                     ReaderBottomBar(fillMaxWidth + 同bg + horizontal small
                                                     + navigationBars insets, pointerInput{}吞点) }
└── 弹窗层（ReaderViewModel.Dialog 驱动）：Settings / ReadingMode / Orientation /
    PageActions / ChapterList / AutoScrollHelp / …
```

- `ReaderSettingsDialog` 第 3 页（colorFilter）时 `window.dimAmount=0f + hideMenus`，
  其余页 `0.5f + showMenus`（桌面/iOS 用对应窗口 actual 实现 dim）。
- Kurisa 现状：`ReaderContent` 已有 Paged/Webtoon/Chrome/Indicator/TransitionCard/
  ChapterListSheet/SettingsSheet，缺的是本章各项视觉细节与行为。

### 2. 顶栏 / 底栏 / 章节导航器视觉规格

**ReaderTopBar**：`AppBar(background=Transparent)`；title=作品名，subtitle=章节名；
左返回=onBackPressed；action 仅书签（Bookmark/BookmarkBorder 切换）。
（WebView/Browser/Share 在原版被 SY 注释掉，转到底栏——复刻时同样只留书签。）

**ReaderBottomBar**：全 IconButton，tint=**primary**（KMK 改动，原 Mihon 为 onSurface，
复刻必须用 primary）；顺序按 `enabledButtons` 过滤后依次：章节列表
（FormatListNumbered）→ WebView（Public）→ Browser（Explore）→ Share →
阅读模式（iconRes painter）→ 旋转（icon）→ 裁边（on/off drawable，按当前模式选
cbp/cbc/cbw）→ 页面布局（BookOpen，仅 !dualSplit 且 pager）→ 双页移位
（PageNext，仅 doublePages）→ 设置（恒显）。

**ChapterNavigator 横版**：FilledIconButton（SkipPrevious/Next，RTL 互换，
container=同 bg 含 disabled 态，content=primary）+ 中间 pill
（clip 24dp + bg + h16）：左 `Box{ currentPageText；透明 totalPages 占宽防抖 }` +
Slider（weight 1，h8）+ totalPages；`totalPages<=1` 则 Spacer。
**纵版**：Column（fillMaxHeight，v24 tablet / 8 其余，h8）：上 prev（rotate90°）；
中 pill（纵向，v16）：currentPageText + 旋转 Slider（rotationZ 90° + layout 宽高互换）
+ total；下 next（rotate90°）。
**Slider**：tachiyomi 定制 Slider，value=currentPage，range=1..totalPages，
onChange→onPageIndexChange(it-1)，拖动触觉 TextHandleMove。

**页码文本**：`currentPageText`：单页=number；双页=`"n-n+1"`（LTR xor
invertDoublePages 定序）；Navigator 与 Indicator 显示同一串。

**ReaderPageIndicator**：`"$current/$total"`，bodySmall/Bold/letterSpacing 1sp，
color=**primary**（KMK），下垫同文 stroke（#2D2D2D，宽 4f）；空串/<=0 不绘制。

**章节过渡卡**：Column（widthIn 460dp + fillMaxWidth）；Next：上 finished（当前）+
下 next（目标），Prev 反之；无章=OutlinedCard（透明底，Row h16/v12 spaced16）：
Info + primary/正文；gap>0 插 Warning + error 色复数 missing 章警告；
ChapterText：header=titleMedium（bottom 4dp），name=titleLarge/20sp/5 行省略
（+下载 CheckCircle 22sp inline），scanlator=bodySmall + secondaryAlpha + top 2dp/2 行。

**DisplayRefresh（翻页闪屏）**：Canvas(fillMaxSize)；PageChanged→host.flash()
（timesCalled % interval == 0 才置 true）；BLACK=黑 / WHITE=白 /
WHITE_BLACK=白→黑各半；总时长 flashDurationMillis（Slider 1..15×100ms）；
interval=flashPageInterval（1..10 页）。

**ContentOverlay**：brightness<0→黑 Canvas alpha=|v|/100；color 非 null→
drawRect（Color(int) + BlendMode，默认 SrcOver）；亮度>0→window.screenBrightness=
v/100，<0→0.01f+黑罩，0→系统。（screenBrightness 走平台 actual，iOS=UIScreen。）

### 3. 设置弹窗信息架构（TabbedDialog 三页，maxH=75%）

**ReadingMode 页**：
- Heading（for_this_series，按书覆写区）；
- ChipRow readingMode → 按书 `readingMode`（6：DEFAULT/LTR/RTL/VERTICAL/
  WEBTOON/CONT_VERT）；
- ChipRow orientation → 按书 `readerOrientation`（7：DEFAULT/FREE/PORTRAIT/
  LANDSCAPE/LOCK_PORT/LOCK_LAND/REVERSE_PORT）；
- Webtoon 系：TapZones（`navigationModeWebtoon`）+ 反转（`tapping_inverted_webtoon`：
  NONE/HORIZ/VERT/BOTH；=Disabled 时隐藏）+ webtoonScaleType（KMK：
  FIT/4:3/3:2/16:9/20:9）+ sidePadding Slider 0..25（%）+ smallerTapZone（KMK）+
  crop_webtoon + smoothAutoScroll（SY）+ transitions_webtoon（SY）+
  dualSplit/Invert_webtoon + rotate/Invert_webtoon + doubleTapZoom +
  pinchToZoom（KMK）+ disableZoomOut；
- Pager 系：TapZones（`navigationModePager`）+ 反转 + imageScaleType（6 项）+
  zoomStart（4 项）+ pageLayout（SY：3 项）+ smallerTapZone + crop +
  landscapeZoom（仅 CENTER_INSIDE/ORIGINAL_SIZE）+ navigateToPan +
  dualSplit/Invert_paged + rotate/Invert + transitions_pager（SY）+
  invertDoublePages（SY）+ pagedDisableZoomIn（KMK）→ pagedDoubleTapZoom（KMK）+
  centerMargin（SY：4 项）；
- CONT_VERT 附 crop_continues_vertical。
- 全局默认：defaultReadingMode=**RTL**，defaultOrientation=FREE。

**General 页**：readerTheme（1黑/2灰/0白/3自动）+ showPageNumber（t）+
forceHorizontalSeekbar（SY，关→再显 landscapeVertical + leftHanded）+
fullscreen（t）+ cutout（仅刘海 + fullscreen）+ keepScreenOn +
readWithLongTap（t）+ alwaysShowTransition（t）+ flashOnPageChange（f）→
duration + interval + flashColor（BLACK/WHITE/WHITE_BLACK）+ autoWebtoon（SY）。

**ColorFilter 页**：customBrightness（f）→ value（-75..100）；colorFilter →
RGBA 各 0..255（位运算）+ mode（SrcOver/Modulate/Screen，加 Overlay/Lighten/
Darken）+ grayscale + invertedColors（硬件 paint 挂 viewer 容器；iOS 用 CI 滤镜
actual，CMP 层用 ColorMatrix）。

复刻到 Kurisa 的机械步骤（`ReaderSettingsSheet` 已有 FilterChip/Switch/Slider
骨架）：① `ReadingConfig` 加字段 ② `ReaderContract.Intent` 加事件 ③
`ReaderViewModel.onIntent → settingsStore.update` ④ Sheet 加控件；`ReaderSetting.sq`
的 scope 列启用按书覆写（global→workId）。

### 4. 手势与导航分区几何

- 基类：`constantMenu=RectF(0,0,1,0.05)` 顶部 5% 恒为菜单；未命中→MENU；
  `invertMode` 按 NONE/H/V/BOTH 镜像；`regionSize1 = smallerTapZone ? 0.25 : 0.33`
  （KMK），`size2 = 1 - size1`。
- 序号→类（0=默认：Pager 横 RightAndLeft / 竖 L，Webtoon 默认 L；1=L 2=Kindlish
  3=Edge 4=RightAndLeft 5=Disabled）：Disabled=[]；
  Edge=NEXT[0,0,s1,1]+PREV[s1,s2,s2,1]底中条+NEXT[s2,0,1,1]；
  Kindlish=NEXT[s1,s1,1,1]+PREV[0,s1,s1,1]（顶 s1 菜单）；
  L=PREV[0,s1,s1,s2]左中+PREV[0,0,1,s1]顶带+NEXT[s2,s1,1,s2]右中+
  NEXT[0,s2,1,1]底带；
  RightAndLeft=LEFT[0,0,s1,1]+RIGHT[s2,0,1,1]（中菜单）。
- 点击归一化（(raw-viewPos)/w,h）后 getAction→MENU toggle / NEXT / PREV / LEFT /
  RIGHT。Kurisa `tapActionAt` 纯函数已对齐 4 模式，需补 Kindlish + inverted +
  smallerTapZone + 顶部恒菜单条。
- 长按：menuVisible || longTapEnabled 才 `onPageLongTap(page, secondPage?)` →
  PageActionsDialog（存图/分享/设封面/复制——本地阅读保留存图/分享）；
  时长=系统 LongPressTimeout。
- 音量：volumeKeysEnabled && !menuVisible 时 DOWN=下/NEXT（UP=上/PREV，
  inverted 互换），需开关；DPAD 同理；N/P 键=下/上一章；双击/捏合可独立开关；
  navigateToPan 开则边缘 pan 翻页。
- Kurisa 现状：`onPreviewKeyEvent` 已有方向键/音量键/PageUpDown/Space/Menu，
  缺各开关 gating（volumeKeys/inverted/longTap）与 Android Activity 拦截转发。

### 5. 章节列表对话框

`ChapterListDialog`：AdaptiveSheet 内 LazyColumn（heightIn 200..500dp，v16，
初滚到 current），key=`chapter-list-id`；downloadState=队列中?其 status:
已下?DOWNLOADED:NOT_DOWN；时间显示：相对时间（EH 系绝对格式无须复刻）。
行布局 `MangaChapterListItem`（SwipeableActionsBox 阈值 56dp 双向动作硬编码
ToggleBookmark，未达阈值 bg=surfaceContainerLowest；CMP 用 AnchoredDraggable
自写 + combinedClickable；click=切章+dismiss）：
- 左 Column（weight 1，spaced 6）：标题行（spaced 2）：未读蓝点（Circle 8dp
  primary）+ 书签标（Filled.Bookmark primary，高=字高-2dp）+ title（bodyMedium
  单行，已读置 DISABLED 色）；
- 副标题（bodySmall，已读 DISABLED / 未读 SECONDARY）：date • readProgress •
  scanlator；
- 右 `ChapterDownloadIndicator`（start 4dp）显态 + 百分比。
本地阅读复刻：downloadState 恒 DOWNLOADED（本地章），保留书签 swipe + 未读点。

### 6. CMP 可移植性结论

- **纯 Compose 近逐行搬**（commonMain）：ReaderAppBars/TopBar/BottomBar/
  ChapterNavigator（横+纵）/ModeSelectionDialog/ReadingModeSelect/
  OrientationSelect/章节列表骨架 + 行布局/ChapterTransition/
  ReaderContentOverlay/ReaderPageIndicator/三设置页/ExhUtils/TabbedDialog +
  AdaptiveSheet（window dim 用 expect/actual）。
- **Android View 必替**：ViewPager2+SSIV/`ReaderPageImageView`+Recycler →
  `foundation Pager + Coil3 AsyncImage + Telephoto/zoomable ZoomableImage
  （subsampling）`；Webtoon Recycler → LazyColumn + zoomable + nestedScroll；
  ReaderProgressIndicator（AbstractComposeView）→ 纯 Compose
  CircularProgressIndicator；ReaderNavigationOverlayView → Compose Canvas；
  saket SwipeableActionsBox → AnchoredDraggable 自写；
  WindowInsetsControllerCompat → CMP WindowInsets/systemUiController；
  screenBrightness/灰度反色 paint → expect/actual；
  分享/剪贴板 → 各平台 actual；TapZones RectF/PointF → compose.geometry Rect。

---

## 第二部分 · 本地阅读复刻规格

### 7. 本地源目录契约（必须逐项复刻）

**层级模板**：`base/<MangaDir>/<ChapterEntry>`。
- 漫画目录 = base 下子目录；章节 `url = "${mangaDir}/${chapterFileName}"`；
  回查时按 url split('/', limit=2) 定位文件。
- 浏览过滤 `.` 开头隐藏（SY `allowHiddenFiles` 开关可放行，后续实现）；
  Popular 再按 query 过滤，Latest 按 `lastModified >= now-7d` 过滤。

**Format 判定**（优先级）：`isDirectory → Directory`；`ext == "epub" → Epub`；
`ext ∈ {zip,cbz,rar,cbr,7z,cb7,tar,cbt} → Archive`；否则抛
`UnknownFormatException`。注意 EPUB 优先于 Archive。

**章节扫描**：过滤 `.` 开头，保留目录/Archive/EPUB；
`name = dir.name ?: file.nameWithoutExtension`，`date_upload = lastModified()`，
`chapter_number = ChapterRecognition.parseChapterNumber(manga.title, name, 0)`；
按**自然排序降序**（natsort 大小写不敏感，Kurisa 需纯 Kotlin 移植，
`NaturalOrder.kt`）。
EPUB 走 `fillMetadata`，其余走章节内 `ComicInfo.xml → setChapterDetails
（title/number/scanlator）`；若作品封面空，取最后一话 `updateCover` 补封面。

**ComicInfo 覆盖规则**（优先级从高到低）：
1. 顶层 `ComicInfo.xml` → 写详情，顺手删 `.noxml`；
2. `ComicInfo.cbm`（加密 zip，内含 ComicInfo.xml）→ 解密读后同上；
3. 遗留 `*.json`（MangaDetails）→ 改写为 ComicInfo.xml 并删 json；
4. 无上两者且无 `.noxml` → 遍历 Archive 章节，首个含 ComicInfo.xml 的拷贝到顶层
   （加密写 `ComicInfo.cbm`，否则写 `ComicInfo.xml`），再回填详情；
   全无则建 `.noxml` 避免重扫。
`updateMangaInfo()` 反向把内存详情写回顶层 xml/cbm。

**封面规则**（`LocalCoverManager`）：`find` = 枚举漫画目录
`nameWithoutExtension == "cover"` 且为图片（含 `cover.cbi`）的首个；
`update`：无则建 `cover.jpg`（加密建 `cover.cbi`），覆盖写流 +
建 `.nomedia`（`DiskUtil.NOMEDIA_FILE`，防图库扫描）+ 回填 `thumbnail_url`。
`updateCover` 按 Format 取首图：目录按自然排序首个图片；Archive 按条目自然排序
首个图片；EPUB 取 `getImagesFromPages().firstOrNull()`。

**OrderBy**：`Filter.Sort("OrderBy", [Title, Date])`；Popular=Title 升序
（书名大小写不敏感），Latest=Date 降序（lastModified）。

**EPUB 解析**：`META-INF/container.xml → rootfile@full-path`（失败回退
`OEBPS/content.opf`）；manifest `item[media-type=xhtml]` + spine `itemref@idref`
顺序得页面列表；每页取 `img@src + image@xlink:href`，经 zip 路径规范化拼条目路径。
`fillMetadata`：`dc:creator→author`，`dc:description→description`，
`dc:title→chapter.name`，`publisher ?: creator→scanlator`，
`dc:date ?: meta[dcterms:modified]`（`yyyy-MM-dd'T'HH:mm:ssZ`）→ date_upload。

### 8. 四路 PageLoader 路由表

`ChapterLoader.getPageLoader(chapter)`：先算 `isDownloaded`
（下载目录命中，命名规则见 §9）。

| 输入条件 | loader | ReaderPage 形态 |
|---|---|---|
| MergedSource（多源合并，后续实现，先留分支抛错） | 按 merged 引用回查子 source 再分发 | — |
| isDownloaded == true | DownloadPageLoader | 文件→Archive 复用；目录→uri 流，全部 Ready 直返 |
| LocalSource → getFormat | Directory / Archive / EpubPageLoader | `ReaderPage(i){ stream=open/getInputStream; status=Ready }`，isLocal=true，自然排序 |
| HttpSource（在线，后续实现） | HttpPageLoader | `ReaderPage(index,url,imageUrl)`，stream 走 ChapterCache，队列异步变 Ready |
| StubSource / 未知 | error | source_not_installed / loader_not_implemented |

- `loadChapter`：Loaded 且 pageLoader 非空直接返回；否则 Loading→getPages
  （空抛 page_list_empty），按阅读位设 requestedPage → Loaded/Error。
- `ReaderChapter(Wait/Loading/Loaded/Error + ref/unref→recycle)`；
  `ViewerChapters(curr/prev/next)` 同步引用计数。
- 宽页补位 `InsertPage` 逻辑在 Pager 双页拆分时处理（见 §3 设置页 pageLayout，
  Kurisa `buildSpreads` 扩展位）。

### 9. 离线读取链

**下载目录命名**：`downloads/<sourceDir>/<mangaDir>/<chapterDir>[.cbz]`。
- sourceDir/mangaDir = 文件名合法化标题（`disallowNonAscii` 可配）；
- chapterDir = sanitize（空→"Chapter"）`[scanlator_]` + 合法化（240-11 截断）+
  `_md5(url).take(6)`（hash 开关 SY，默认开）；
- 兼容名：`[cur, cur.cbz] + legacy（v1/v1.cbz/ASCII 与 hash 变体 +.cbz）`，
  `findChapterDir` 取首命中；`*_tmp` 供清理。
- Kurisa 复刻：`DownloadProvider.kt` 同模板；本地源章节按 url split 直查。

**ChapterCache**：DiskLruCache（appVersion=1，大小可配，默认 75MB）；
pageList key=`md5(mangaId+url)` 存 JSON，image key=`md5(imageUrl)` 存文件；
`putPageList/putImageToCache`，`recycle()` 回写内存 pageList。
Kurisa 复刻：cache4k/DiskLru + 同 key 规则（`ChapterCache.kt`）。

**CBZ 加密**：构造即遍历条目 `isEncrypted` 首个→encrypted=true，再试读
（passphrase 错→wrongPassword）；口令存系统钥匙串（Kurisa：SecureStorage
KVault/KSafe/桌面加密文件），加密写 `ComicInfo.cbm/cover.cbi`。
首版可先实现"检测 + 提示 + 明文"，加密读写列 P1（Cryptography-Kotlin +
SecureStorage，M6 安全评审）。

**ArchiveReader 三模式**（`archiveReaderMode` 偏好）：
0 `LOAD_FROM_FILE` 每次直读（省内存，翻页有解码延迟）；
1 `LOAD_INTO_MEMORY` 初始化全读内存（最快，大卷易 OOM）；
2 `CACHE_TO_DISK` 解压到外部缓存后转 Directory（首开慢，适合超大包）。
Kurisa 复刻：Kompress 解包（替代 mmap/libarchive），三模式同语义，
默认 0，设置页暴露选项。

### 10. Kurisa 现状对照与接线任务

现状：`FormatDetector` 仅认 epub/txt/cbz/zip/pdf（缺 rar/cbr/7z/cb7/tar/cbt，
无 Directory 分支）；`local_book` 缺作者/简介/状态/lastModified 字段；
`chapter` 缺 url/chapter_number/scanlator/date_upload/read/last_page_read/
bookmark/sourceOrder；`download_task.status` 自由文本未收敛；
`ChapterPayload.Images` 仅 urls（表达不了 stream/status/双页标记）。

按依赖排序的复刻任务（库依据架构 §6.4/§7.3，expect 新增走 ADR-006 评审）：

1. `LocalSourceFileSystem` expect 化（`data/localimport` 新建 expect +
   三端 thin actual：AppDirectories + FileKit + kotlinx-io/okio）。
2. `Format` 对齐（`FormatDetector` 加 DIRECTORY + 8 种归档 + UnknownFormat 语义）。
3. `ArchiveReader`（Kompress，`useEntries/getInputStream/close` 同形）+
   `NaturalOrder` 纯 Kotlin 自然排序。
4. `ComicInfo`（XmlUtil）：顶层/cbm/章节内三级覆盖 + `.noxml` + legacy json
   迁移 + `updateMangaInfo` 回写。
5. `EpubReader`（Kompress + Ksoup/XmlUtil）：container/manifest/spine/img 解析
   + `fillMetadata`。
6. `LocalCoverManager` + `.nomedia`（FileKit 落盘）。
7. `LocalSourceScanner`（隐藏过滤 + Title/Date OrderBy + 7 天 Latest 阈值 +
   自然排序降序）+ `ChapterRecognition`（双精度解析 + extra.99/omake.98/
   special.97 后缀）；`local_book/chapter.sq` migration 加字段（可回滚）。
8. 四路 `PageLoader`（`core/reader` 新建：Directory/Archive/Epub/Download +
   Http 预留；`ReaderPage(stream/status)` + `ReaderChapter(ref/recycle)` +
   `ViewerChapters`）。
9. `DownloadProvider`（同模板命名 + ValidNames 兼容）+ `ChapterCache`
   （同 key 规则）+ `download_task.status` 收敛 + controller→Ketch 接线。
10. 加密（P1）：Cryptography-Kotlin + SecureStorage 存口令，Kompress 加密写
    cbm/cbi。

---

## 第三部分 · 实施计划（只含本地阅读 + 阅读器视觉/UI/UX）

> 技能路由（`Kurisa/AGENTS.md`）：阅读器 UI 用 `compose-ui`（+ `material-3/md3e` 按需）；
> 状态机用 `kotlin-project-state-management` 或 `kmp-mvi` 二选一；
> SQLDelight 变更用 `kmp-sqldelight-setup`（migration 可回滚）；
> 网络/白名单改动跑 `UrlPolicyTest`；expect 新增先读 `kmp-expect-actual` 并经评审；
> 碰 WebView/桥/Cookie/引擎/白名单先加载 `security-audit`。

### 阶段一 · 本地阅读闭环（先行：无它则阅读器无内容可读）

1. **FS 抽象 + Format 对齐**（任务 1+2）：`LocalSourceFileSystem` expect/actual
   + `FormatDetector` 补全；单测：8 种扩展 + Directory + Unknown。
2. **解包与排序**（任务 3）：`ArchiveReader`（Kompress）+ `NaturalOrder`；
   单测：fixture zip/cbz 条目顺序 + 自然排序（`10` > `2`）。
3. **ComicInfo + EPUB + 封面**（任务 4+5+6）：XmlUtil 解析 + 三级覆盖 +
   `.noxml` + EPUB `fillMetadata` + `LocalCoverManager` + `.nomedia`；
   单测：fixture ComicInfo.xml/cbm/json 迁移 + EPUB 解析。
4. **扫库**（任务 7）：`LocalSourceScanner`（隐藏/OrderBy/7 天/Latest 降序）+
   `ChapterRecognition` + DB migration（加字段 + 回滚脚本）；
   单测：识别用例（extra.99/omake.98/special.97/卷话前缀）。
5. **PageLoader 四路**（任务 8）：Directory/Archive/Epub/Download +
   `ReaderChapter/ViewerChapters` ref 计数；单测：空页抛错、recycle 释放。
6. **离线命名 + 缓存**（任务 9）：`DownloadProvider` + `ChapterCache` +
   status 收敛；单测：命名模板 + legacy 兼容名命中。
7. **阅读器本地接线**：`BridgeChapterProvider`（或新增 `LocalChapterProvider`）
   走本地 PageLoader → 现有 `ReaderViewModel.ensureWindow` 复用；
   `ChapterListSheet` 显示本地章节（downloadState 恒已下）+ 进度存取照旧。
8. 加密（任务 10）列 P1，不堵主链。

验收：fixture 本地库（目录 + CBZ + EPUB + ComicInfo/cbm/json 各一）在三端
扫库→详情→翻页→进度→重启恢复全通；`desktopTest` 绿；Android APK 可装。

### 阶段二 · 阅读器视觉/UI/UX 复刻（与阶段一可部分并行）

按"视觉落差 × 成本"排序，全部落在 `feature/reader/Reader.kt` +
`core/reader/ReaderInteraction.kt` + `ReadingConfig` 四步机械法：

1. **顶栏/底栏/导航器**（§2 全量）：ReaderTopBar（透明 + 双行标题 + 书签）+
   ReaderBottomBar（primary tint + 10 按钮按 `enabledButtons` 过滤排布，
   本地阅读隐藏 WebView/Browser/Share 在线按钮）+ ChapterNavigator 横/纵版
   （pill + Slider + 拖动触觉）+ `currentPageText` 双页 `"n-n+1"` 语义。
2. **页码指示器**（§2）：`"$current/$total"` + primary + 描边；显隐规则照搬。
3. **过渡卡**（§2）：finished/next 双行 + 无章 OutlinedCard + 缺章警告；
   `alwaysShowTransition` 开关。
4. **手势分区补齐**（§4）：Kindlish + inverted（H/V/BOTH）+ smallerTapZone +
   顶部 5% 恒菜单；`tapActionAt` 单测先行。
5. **按键 gating**：volumeKeys/inverted/longTap 开关 + PageActionsDialog
   （存图/分享/设封面；本地保留存图/分享，走 FileKit + 平台分享 actual）。
6. **设置弹窗三页**（§3 全量）：ReadingMode（按书 6 模式 + 7 方向 + 系别选项组，
   全局默认 RTL/FREE）/ General / ColorFilter；scope 列启用按书覆写。
7. **双页切割**（§3 pageLayout + §8 InsertPage）：AUTOMATIC 横屏双页 +
   split/invert/rotate/shift + 宽页判 fullPage/isolatedPage + 中缝；
   纯逻辑先进 `ReaderInteraction` 单测。
8. **条漫捏合缩放 + 自动滚动 + 裁边 + 反色/棕褐**（C.1 遗留四项，复刻必备）：
   Zoom 容器进 WebtoonReader；定时自滚；解码前裁边开关；ColorMatrix 反色/棕褐。
9. **系统级**：沉浸系统栏 + KeepScreenOn + 系统亮度滑杆 + 方向锁定
  （`:core:platform` 薄 actual，ADR-006 评审）。
10. **翻页闪屏 + 亮度/颜色 Overlay**（§2）：DisplayRefreshHost + ContentOverlay
    照搬（含 ColorFilter 页 dimAmount 0f/0.5f 规则）。

验收：与 Komikku 同一本地书做并排走查——顶/底栏、导航器、指示器、过渡卡、
设置三页、手势五区、双页、条漫、滤镜逐项一致；三端截图基线（沿用 M5 截图纪律，
阅读器先行）。

### 执行顺序一句话

阶段一 1→2→3→4→5→6→7（加密 P1）→ 阶段二 1→2→3→4→5→6→7→8→9→10；
阶段一完成 1~4 后阶段二 1~6 可并行。每批：单测先行 → desktopTest 绿 →
Android APK 走查。

