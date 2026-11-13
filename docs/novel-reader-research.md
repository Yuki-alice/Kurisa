# 小说阅读器实现调研（Compose / Kotlin 同类项目 + 本项目落地建议）

> 联网调研时间：2026-11-12。范围：采用 Kotlin + Jetpack Compose / Compose Multiplatform 的开源小说（文字）阅读软件，及其阅读器实现思路；结合本项目现状给出 M4「小说与本地导入」的可执行方案。
> 事实来源分三类：**A** 官方仓库 README / 官方 API 文档；**B** 源码级第三方分析（DeepWiki / Zread / 项目分析站）；**C** 社区文章、统计站（仅作线索，不单独作结论）。本文对关键结论标明来源等级。
> **状态注记（2026-11-13）**：本文是 2026-11-12 的调研快照，文中「尚未 / 待 M5」均为调研时点口径；M4 已落地、
> `ReaderRoute.sourceKey` 已由 B4 接线，当前现状以代码与 `docs/work-plan.md` 为准。

---

## 1. 结论摘要

1. 同类项目里最贴近本项目形态的是 **IReader**（Kotlin + Compose Multiplatform，Android/Desktop，插件即书源）与 **LightNovelReader**（Kotlin + Jetpack Compose，Android，多源插件、离线优先、双阅读模式）；**Legado 3.0 官方/社区主线仍是 Android 原生 View + Kotlin**，其 KMP/CMP 跨平台说法来自若干 fork，不能当作官方稳定版本，但它的**分页与渲染数据模型**仍是中文网文阅读器最成熟的参考。〔A+B〕
2. 共性架构是「**内容管线 + 阅读器外壳 + 可替换 Viewer**」：原始正文 → 净化/结构化 → 内容组件（富文本 IR）→ 分页器 → Pager 或 LazyColumn 渲染，进度统一归一化到「章节 + 位置锚点」。这正是本项目 ADR-005「漫画与小说共用一条管线」已经铺好的路。〔A+B〕
3. 本项目小说链路**只差渲染层**：契约 `novel.loadChapter` → `ChapterPayload.Text(body, format)`、bridge 映射、HTML 清洗、`ReaderContent` 小说分支、`reader_setting` / `reading_progress` / `bookmark` 都已存在；缺的是排版分页、格式渲染、TXT/文本 EPUB 解析、虚拟页与进度锚点、小说设置与测试。`pageCountOf(Text)` 目前恒为 1，`NovelContent` 把整章塞进单个 `Text`，HTML 标签会原样显示。
4. **最大的技术约束**：`TextMeasurer` / `rememberTextMeasurer()` 本身是 Common/All 的多平台 API，但构造需要 `FontFamily.Resolver`、`Density`、`LayoutDirection`（新版还要 `LocaleList`），这些只能从 CompositionLocals 取；在 `core/reader` 的纯 Kotlin 层手动构造会逼出平台相关的 expect/actual，违反 ADR-006「默认不加」。建议：**分页算法留在 `core/reader` 纯 Kotlin，测量能力抽象成接口，由 `feature/reader` 的 Composable 层用 `rememberTextMeasurer()` 注入**。〔A+C〕
5. 进度不能只存虚拟页号：字号/字体/窗口/旋转/换设备都会重排导致页号漂移。建议复用现有 `reading_progress`，通过可回滚迁移增加 `char_offset` + `layout_key`，`bookmark.position` 复用为字符锚点；恢复时以锚点重映射到新分页结果。〔本项目 schema + LightNovelReader 进度做法 B〕
6. 落地顺序建议按 5 批推进（富文本 IR/解析 → 分页内核 → Viewer 接线 → 本地书同构 → 增强能力），每批单测先行；**不要另起一套小说阅读器或第二套数据模型**。

---

## 2. 同类项目调研

### 2.1 对比表

| 项目 | 技术栈 / 平台 | 内容来源与离线 | 阅读器实现要点 | 状态 / 许可 | 对本项目价值 |
| --- | --- | --- | --- | --- | --- |
| **IReader**（`IReaderorg/IReader`） | Kotlin + Compose Multiplatform；Android / Desktop（Windows·macOS·Linux），iOS 模块已完成但未接线；Clean Architecture `:presentation/:domain/:data/:core/:source-api/:plugin-api/:i18n`，另有 `source-runtime-js`（Kotlin→JS 书源）、`benchmark/`、`test_local_novel/`、`compose_stability_config.conf`〔A 开发者文档 + B 目录分析〕 | LNReader 源仓库、Legado 书源导入、JS 扩展；章节下载离线；跨端进度同步、Piper TTS（仅桌面） | 官方 README 强调字体/间距/颜色/主题/手势全可配；源码级阅读器实现细节未在本次检索到的官方文档中展开 | Apache-2.0；Android/Desktop 有 Releases，iOS 缺维护者〔A〕 | **架构形态最像**：插件即源、KMP 共享层、三端同构；分层与模块边界可直接对照 |
| **LightNovelReader**（`dmzz-yyhyy/LightNovelReader`） | Kotlin + **Jetpack Compose**（仅 Android 7.0–15），Hilt + Room + WorkManager；重构版，默认分支为开发中 `dev/1.3`〔A README + B 源码分析〕 | 多数据源插件、离线优先缓存、书架/更新提醒、EPUB 导出；`data/content/ContentComponentRepository` 专管阅读器渲染组件 | `ReaderViewModel` 作协调器，运行时在 `ScrollContentViewModel`（LazyColumn + 3 槽章节缓冲）与 `FlipPageContentViewModel`（`HorizontalPager` + `PagerState`）间切换；`ChapterContentUiState.content: List<AbstractContentComponent<*>>`；`SettingState` 43 项响应式设置；进度单一写入路径 `saveReadingProgress`（滚动节流 120ms / 最长 2.5s 一次，翻页用 `(page+1)/pageCount`，恢复 `notRecoveredProgress → scrollToPage`）；ViewModel scope 到父级路由以保留滚动位置与已加载章节〔B Zread 源码级〕 | Apache-2.0，活跃〔A〕；第三方星数统计互相矛盾，不采信 | **阅读器状态机 / 双 Viewer / 进度策略的最佳参考**（思路借鉴，非 CMP 代码搬运） |
| **Legado 3.0 / 阅读**（官方 `gedoor/legado`、社区 `LegadoTeam/legado`、KMP fork） | 官方仓库 2026-05 起仅剩公告与图片（1 commit）；社区维护主线仍是 **Android 原生 View + Kotlin 89%**，2026-11-01 仍有提交；`Compose Multiplatform` 桌面版 / iOS / 鸿蒙的说法出现在 `fengyie007/legado`、`huajideshutiao/legado` 等 **fork 的 README**，非官方稳定主线〔A〕 | 自定义书源规则（CSS/XPath/JSONPath/Regex/JS，Rhino）、替换净化、本地 TXT/EPUB、离线缓存 | `ContentProcessor`（净化/简繁）→ `ChapterProvider` 分页 → `TextChapter → TextPage → TextLine → TextColumn`（字符/图片列）→ `ContentTextView` Canvas 绘制；首行缩进、两端对齐、标题模式、图片三种显示模式；`TextPageFactory` 预渲染 + Canvas 录制 + 后台协程排版 + `ImageProvider` 缓存；5 种翻页 delegate：覆盖/仿真/滑动/滚动/无动画；长按选择 + 跨章全文搜索〔B DeepWiki 源码级〕 | GNU GPL（社区主线）〔A〕 | **中文正文分页算法与数据结构最成熟**；净化/替换规则、图片混排、翻页模式清单可直接作为需求基线 |
| **OtakuWorld / NovelWorld**（`jakepurple13/OtakuWorld`） | Kotlin + Compose Multiplatform，一套共享代码出 MangaWorld / AnimeWorld / **NovelWorld** 三个 App；`sharedcomponents/`、`kmpuiviews/`、`kmpmodels/`；桌面 `:novelworld:desktop:run`〔B 项目分析 + A README 摘录〕 | 插件式源（兼容 Mihon/Aniyomi 扩展）、收藏库共享 | 强调「同一 UI 骨架跨内容类型」，阅读器实现细节未展开 | Apache-2.0，活跃〔B〕 | 佐证「漫画/小说共用外壳 + 共享模块」在 CMP 上可行，与 ADR-005 同向 |
| **ReadOut**（`tawhidmonowar/ReadOut`） | KMP + CMP，MVI + Clean Architecture，Koin/Ktor/Coil/DataStore〔A README〕 | OpenLibrary 书目、有声书、AI 摘要 | 偏「书目浏览 + 音频」，**不是章节小说正文阅读器** | MIT，早期开发〔A〕 | 仅作 KMP 工程骨架参考，不作阅读器参考 |
| 其它线索 | `xiangzhihong/Novel`：Kotlin+Compose 与 RN 混合，`ReaderViewModel + PageSplitter + BookCacheManager`、六种翻页效果〔A README〕；`Aatricks/Emaki(EasyReader)`：Kotlin+Compose，网页抓取 + 离线 + EPUB/PDF/HTML〔A 镜像 README〕 | — | — | — | 作为「分页器 + 章节缓存 + 翻页效果」命名与拆分的旁证 |

**明确排除**：LNReader（React Native，但其插件生态被 IReader 复用）、Readest / Koodo Reader（Tauri / Electron）、Tachiyomi / Mihon / Komikku（漫画 View 体系，本项目已在 `komikku-*.md` 中复刻过）——均非 Kotlin+Compose 小说阅读器。

### 2.2 可复用的共性设计（提炼）

- **正文先结构化再渲染**：LightNovelReader 的 `AbstractContentComponent`、Legado 的 `TextChapter/TextPage/TextLine/TextColumn`，本质都是「正文 → 块/行 IR → 页面」。直接把 HTML 字符串丢给 `Text` 是占位做法，无法做缩进、图片、选择、搜索、书签。
- **外壳与 Viewer 解耦**：顶栏/底栏/目录/设置/点按分区/进度与具体渲染策略无关；滚动与翻页只是两套 Viewer，切换时保留 book/chapter 上下文。
- **章节窗口与预取**：滚动模式维护 prev/current/next 三槽并跨章无缝拼接；翻页模式预载下一章正文。分页结果和正文缓存分离。
- **进度单一写入路径 + 节流**：滚动用 `firstVisibleItemScrollOffset` + 节流，翻页用 `settledPage`；两种 Viewer 都调用同一个 `saveReadingProgress`，避免两套语义。
- **设置即排版输入**：字号、行高、粗细、段距、页边距、背景/颜色等偏好以响应式状态投影到 Viewer，变化后局部重排而非重建 Activity。
- **性能手段**：后台/分批排版、页面预渲染、Canvas 录制缓存、图片缓存、只处理可见内容（Legado）；Compose 侧对应 `TextMeasurer` 内建 LRU 布局缓存、`remember` 缓存、分页结果按 key 复用。〔A 官方 API + B〕

---

## 3. 本项目现状与差距

### 3.1 已具备（可直接复用）

| 层 | 事实 |
| --- | --- |
| 契约 | `docs/contract/source.d.ts`： `NovelNamespace.loadChapter(workId, chapterId) → { text?, content?, format?: 'html'|'markdown'|'plain' }`、`ContentKind = 'manga'|'novel'`；`novel-example.js:62-70` 返回 `{ text: innerHtml, format:'html' }`；`source-v4.d.ts` 增补 `cleanNovelHtml` / `onImageResolve` |
| 模型 | `core/model/ChapterPayload.kt`：`Text(body, format: TextFormat)` + `TextFormat{HTML,MARKDOWN,PLAIN}` + `HtmlAllowlist`（p/br/b/i/em/strong/img/h1-h4/blockquote/a） |
| 桥 | `source/bridge/BridgeChapterProvider.kt:75-101` 按 `ContentKind` 分流 NOVEL→`Text`；`HostApiImpls.kt:390-413` `NovelHost.stripHtml()`（正则剥标签，含 2M 字符 ReDoS 门） |
| 阅读器 | `feature/reader/Reader.kt:260-279` `state.article != null -> NovelContent(...)` 为第一分支；已有 `HorizontalPager`/`VerticalPager`、`LazyColumn`、点按分区、章节过渡卡、目录/设置面板、键盘与音量键、进度防抖 500ms、图片字节预算 LRU |
| 内核 | `core/reader/ReaderStateMachine.kt`：`ReadingConfig`（44-85）、`ChapterProvider`、`pageCountOf()`（133-137，Text=1）、`currentText()`、`preloadWindow()` |
| 数据 | `data/db` 12 张表：`chapter.payload_json`（正文缓存）、`reading_progress(page_index, scroll_offset, percent)`、`bookmark(position)`、`reader_setting(scope, config_json)`；已有 `1.sqm`/`2.sqm` 与 `RollbackScripts` 可回滚迁移 |
| 安全 | `UrlPolicy` 白名单逐跳校验 + `UrlPolicyTest`（5 断言）、`CookieStore.pairedUserAgentFor` 与 `cf_clearance` 绑定、`SourceHttpClient` 统一注入 UA/Cookie |
| 规划 | `architecture.md §6.3`（785-790）已定调：Kompress/XmlUtil/Ksoup 解析、CMP `Paragraph`/`TextLayoutResult` 测量并按视口缓存分页结果、局部重排、受限标签白名单 + `novel.onImageResolve`；`§12` 中 **M4 = 小说与本地导入**（排版分页、书签、导入 UI），M3 是漫画阅读器 |

### 3.2 明确缺口

- `feature/reader/Reader.kt:1531-1547` `NovelContent` = `Column + verticalScroll + 单个 Text(article)`；注释称「按 format 做最小分流」，实际**未按 format 分支**，HTML 标签原样显示。
- 全库无 `TextLayoutResult` / `Paragraph` 测量、无分页算法、无 `HtmlAllowlist` 调用点、无字号/行距/段距/字体字段、无小说翻页动画与目录分卷、无书签/划线/笔记/全文搜索 UI。
- `ReaderContract.State.article: String?` 丢掉了 `format`；`pageCountOf(Text)=1` 导致小说没有虚拟页，`PageChanged` / `pageIndex` 对小说无意义；`Reader.kt:316` 小说时直接隐藏页码。
- `data/localimport/FormatDetector` 已识别 `.txt`，但 `LocalChapterProvider.kt:59-65` 对纯文本 EPUB 明确抛「纯文本 EPUB 随 M4 小说阅读器支持」，且所有本地分支只产 `Images`；无 TXT 解析器。
- 测试：`ReaderViewModelTest` 全部用 `Images` 载荷；`ReaderScreenshotTest` 4 张截图全是漫画/PDF；无小说分页/渲染用例。`ZoomRenderTest`/`ZoomLongStripTest` 为 M0 spike 历史证据，仓库内已无文件（现为 `SubsamplingTest` 精神续作）。
- 调研时 `ReaderRoute` 尚未带 `sourceKey`（多源按作品路由，当时标为 M5）；**2026-11-13 已由 B4 接线**（`WorkSourceIndex` + `bindWork`）。Koin / 深链接 / `MaterialExpressiveTheme` 仍见 `docs/work-plan.md`。

### 3.3 必须遵守的硬约束（`AGENTS.md`、`architecture.md`）

- **C1** 本体不含任何站点 URL / 解析逻辑 / 预置源；**C3** 三端行为一致；**C4** 本地优先；**ADR-005** 统一内容模型；**ADR-006** expect/actual 默认不加（新增需评审）；**ADR-009** 桌面端不内嵌浏览器 → **HTML 正文绝不能靠 WebView 渲染**。
- 碰 JS 桥 / `NovelHost.stripHtml` / Cookie / Ktor 引擎 / URL 白名单：先加载 `security-audit`（OWASP MASVS 自查）；阅读进度/书签落盘：加 `privacy-audit`；Ktor 改动必须跑 `UrlPolicyTest`；SQLDelight 变更用 `kmp-sqldelight-setup` 且迁移可回滚；状态机沿用现有 MVI（`kotlin-project-state-management` 或 `kmp-mvi` 二选一，不混用）；导航只用官方 Navigation3。
- `cf_clearance` 与 `pairedUserAgent` 绑定是红线；产品名只落配置层，代码内中性命名；架构文档只改正文、不写版本号/变更史。

---

## 4. 推荐实现方案

### 4.1 数据流（一条管线，漫画/小说共用外壳）

```
JS 源 novel.loadChapter ──(quickjs-kt / Host API)──> BridgeChapterProvider
本地 TXT / EPUB ──(TxtReader / EpubTextReader)────> LocalChapterProvider
                          │  ChapterPayload.Text(body, format)
                          ▼
        NovelContentParser（format 分支；HTML 走 Ksoup + HtmlAllowlist 白名单）
                          │  NovelDocument: List<NovelBlock>（块带 charStart/charEnd）
                          ▼
        NovelPaginator（core/reader 纯 Kotlin + TextMeasure 接口 + LayoutKey 缓存）
                          │  List<NovelPage>（charStart..charEnd + 样式化内容 + 图片占位）
                          ▼
     Viewer：Paged(HorizontalPager) / Continuous(LazyColumn) ── 复用现有 Chrome/设置/手势
                          │
                          ▼
   进度/书签：reading_progress(page_index + char_offset + layout_key)，bookmark.position
```

### 4.2 模块落点（不新增顶层模块）

| 模块 | 新增/改动 | 约束 |
| --- | --- | --- |
| `:core:model` | `NovelBlock`（Paragraph/Heading/Quote/Image/Link，含 `charStart`）、`NovelDocument`（`chapterId`、`contentHash`、`charLength`）、`NovelPage`、`NovelTypography` | 纯 Kotlin，不依赖 Compose |
| `:core:reader` | `TextMeasure` 接口、`NovelPaginator`（切页/孤行控制/锚点映射）、`LayoutKey`、`NovelPageCache`（LRU）、`ReadingConfig` 增加 `typography` / `pageAnimation` / `sepia` | 分页算法可纯单测；不引用 `androidx.compose.ui.*` 平台实现 |
| `:data:localimport` | `TxtReader`（UTF-8/GBK/GB18030 探测 + 章节标题正则）、`EpubTextReader`（container.xml→OPF manifest/spine→XHTML→白名单清洗，一个 spine item 一章）、`LocalChapterProvider.payload()` 返回 `Text` | 复用现有 `EpubReader`/`ArchiveReader`/`FormatDetector` |
| `:source:bridge` | 保持 `NovelHost.stripHtml()` 为源侧纯文本工具；渲染侧另建白名单解析器；`onImageResolve` 图片 URL 重写接 `UrlPolicy` | 改 JS 桥先过 `security-audit` |
| `:feature:reader` | 重写 `NovelContent`；新增 `rememberComposeTextMeasure()`（`rememberTextMeasurer`）、`NovelPagedViewer` / `NovelContinuousViewer`、小说设置面板、`Intent.TypographyChanged` 等；`State.article: String?` → `novel: NovelDocument?`（保留旧字段兼容漫画） | 测量只在 Composable 环境发生；沿用现有 MVI |
| `:data:db` | `3.sqm` 给 `reading_progress` 加列 + `RollbackScripts.V3_TO_V2`（只丢新列） | 迁移可回滚，`SchemaConsistencyTest` / `MigrationAndOfflineTest` 补断言 |
| `:app:shared-ui` | 装配 `TextMeasure` 实现与解析器；`ReaderRoute.sourceKey` 随 M5 接线 | Koin 前沿用现有手写 Factory |

### 4.3 关键技术决策

**① 测量与分层（最高优先级）**
官方 API 显示 `TextMeasurer`、`rememberTextMeasurer()` 均标注 **Common/All**，`measure()` 返回 `TextLayoutResult` 且自带 LRU 布局缓存（仅颜色/阴影等绘制属性变化不触发重排）；但构造函数必须提供 `FontFamily.Resolver`、`Density`、`LayoutDirection`（1.13 起还需 `LocaleList`），`rememberTextMeasurer()` 之所以能免传参，是因为它从 CompositionLocals 读取这些值。Kotlin 官方团队在 Slack 中确认 `Paragraph` / `MultiParagraph` / `TextMeasurer` 都是多平台的并推荐带缓存的 `TextMeasurer`；社区 CMP 实践亦指出「非组合阶段手动构造需要 expect/actual 桥接 Resolver/Density」。〔A+C〕

> 决策：`core/reader` 只定义测量契约（例：`measure(text, style, maxWidthPx): LineMetrics`，返回行数、每行起止字符偏移、行高）；`feature/reader` 在 `@Composable` 中用 `rememberTextMeasurer()` 实现并传入分页器。**既满足 ADR-006 不新增 expect/actual，又让分页算法保持纯 Kotlin 可单测**。落地前按 CMP 1.12.2 实际签名核对 `TextLayoutResult` 的 `lineCount` / `getLineStart` / `getLineEnd` / `getLineTop` / `getLineBottom` / `getLineForOffset` / `getOffsetForPosition` / `getBoundingBox` 等可用方法。

**② 分页算法**
- 以**段落块**为最小单位，不逐字符测量；每段用 `measure(AnnotatedString, placeholders)` 得到行信息，按视口高度累加行高确定页边界；跨页段落用「二分可见字符数」或按行切片，避免每页 O(n) 重测全章。
- 首行缩进、段间距、标题样式、页边距、孤行控制（页末不单留标题/行首不单留段尾）；中英混排、标点悬挂等列 P2。
- 图片：`placeholders` 预留矩形 + `InlineTextContent` 渲染，URL 先经 `novel.onImageResolve` 重写再进 `SourceHttpClient`（白名单/配对 UA 不变）。
- `LayoutKey = hash(chapterId, contentHash, viewportPx, density, fontFamily, fontSize, lineHeight, paragraphSpacing, indent, locale, layoutDirection)`；命中即复用分页结果，变化只重排当前章（架构 §6.3「局部重排」）。分页结果**只在内存 LRU**（建议 3 章窗口），不落盘，避免与屏幕/字体耦合。
- 纯文本构建/解析放 `Dispatchers.Default`；**测量与分页分批在组合阶段驱动**（`LaunchedEffect` 每批让出帧），先出首 1–2 页再增量，避免一次量完整章卡首帧；不要假定 `FontFamily.Resolver` 可跨任意后台线程复用。

**③ 跨端一致性（C3）**
显式指定 `FontFamily` 与字号，不依赖平台默认字体（社区 CMP 实测：桌面/Android/iOS 默认字体宽度可差 5–10%，中文标点混排更明显）；`Density` 用 `LocalDensity`，窗口拖动/旋转/系统字体缩放变化时按新 `LayoutKey` 重排；三端截图基线（沿用 M5 纪律，小说先行）+ 同一 fixture 的分页幂等对拍。

**④ HTML 正文安全**
`HtmlAllowlist` 真正接线：Ksoup 解析 → 只保留白名单标签 → 丢弃 `script/style/iframe`、所有 `on*` 属性、`javascript:` / `data:` URL → `img.src` 交给图片管线。保留 `NovelHost.stripHtml()` 的 2M 截断防 ReDoS 思路，但**正则剥标签不能作为渲染层防线**。不引入 WebView（ADR-009），不引入不受控的通用 HTML 渲染器。改桥/Host API 前加载 `security-audit`，进度/书签落盘前加载 `privacy-audit`。

**⑤ 进度与书签**
- 虚拟页号复用 `reading_progress.page_index`，`scroll_offset` 用于滚动模式；`percent` 用「章内 charOffset/章字符数 + 章序」加权得到全书进度（LightNovelReader 用 `maxChapterReadingProgressMap` 聚合，思路可参考）。
- 增加 `char_offset INTEGER NOT NULL DEFAULT -1` 与 `layout_key TEXT NOT NULL DEFAULT ''`；保存时 `PageChanged`/滚动防抖同写两者，恢复时**以 charOffset 为准**在新分页结果中映射回 pageIndex；`bookmark.position` 复用为字符锚点。
- 迁移示例（`3.sqm`，配套回滚脚本只丢新列）：
  ```sql
  ALTER TABLE reading_progress ADD COLUMN char_offset INTEGER NOT NULL DEFAULT -1;
  ALTER TABLE reading_progress ADD COLUMN layout_key TEXT NOT NULL DEFAULT '';
  ```
- 章节正文缓存进 `chapter.payload_json` / `downloads/<workId>/<chapterId>/`（C4）；遵守合规红线，不默认开启全站 HTML 响应缓存。

**⑥ 阅读设置**
`ReadingConfig` 增加 `NovelTypography`（fontSizeSp、lineHeightMultiple、paragraphSpacingDp、paragraphIndentEm、fontFamilyId、textAlign、pageMarginDp、sepia）与 `pageAnimation`；继续存 `ReaderSetting.sq` 的 `config_json`（`scope` 已预留按书覆盖），无需新表。新增 `Intent.TypographyChanged` / `PageAnimationChanged`；漫画专属字段（doublePage / scaleType / webtoonSidePadding）在小说 Viewer 中忽略而非复用。设置面板复用现有 Sheet 骨架。

**⑦ Viewer**
- **Paged**：复用 `HorizontalPager`（`beyondViewportPageCount = 1`，RTL `reverseLayout`）、`tapActionAt` 点按分区、键盘/音量键、章节过渡卡；页码显示「虚拟页 / 本章总页」。
- **Continuous**：`LazyColumn`，item 按块或按页组织，`key` 稳定、`contentType` 区分文字/图片/过渡卡；跨章无缝可参考 LightNovelReader 的 3 槽缓冲 + 相邻章预取。
- **翻页动画**：滑动/覆盖用 Pager + `graphicsLayer` 变换；仿真（page curl）需要页面位图快照（`rememberGraphicsLayer()` + `drawWithContent { graphicsLayer.record{}; drawLayer() }` 一类的方案）或 Canvas 自绘，**列 P2 并先验证 CMP 三端可用**；现成 `pagecurl` 类库是 Android/Jetpack Compose 坐标，CMP 兼容未验证，不建议直接引入。无动画模式为默认。
- 章节列表/目录、书签、夜间/sepia 主题复用现有 Chrome；`ReaderContent` 分支从 `article != null` 改为 `novel != null`，并在 `key(chapterId)` 下重建 Viewer（沿用漫画章节切换的防串位做法）。

**⑧ 本地导入**
`TxtReader`（编码探测 + 章节正则）与 `EpubTextReader`（spine 顺序 → XHTML → 白名单 IR）产出与在线源同构的 `Work/Chapter`；`LocalChapterProvider.payload()` 去掉纯文本 EPUB 的异常分支。验收以 M4 Gate「本地书与在线书在书架同构展示」为准。

### 4.4 接口草图（示意，中性命名）

```kotlin
// :core:model —— 纯 Kotlin
data class NovelBlock(val kind: BlockKind, val text: String, val charStart: Int, val imageUrl: String? = null)
data class NovelDocument(val chapterId: String, val blocks: List<NovelBlock>, val charLength: Int, val contentHash: String)
data class NovelPage(val index: Int, val charStart: Int, val charEnd: Int, val blocks: List<NovelBlock>)

// :core:reader —— 算法纯 Kotlin，测量由 UI 注入
interface TextMeasure {
    fun measure(text: String, style: TextStyleSpec, maxWidthPx: Int): LineMetrics
}
class NovelPaginator(private val measure: TextMeasure) {
    fun paginate(doc: NovelDocument, viewport: Viewport, typography: NovelTypography, key: LayoutKey): List<NovelPage>
    fun pageOfCharOffset(pages: List<NovelPage>, offset: Int): Int
}

// :feature:reader —— 唯一接触 Compose 文本测量处
@Composable
fun rememberComposeTextMeasure(): TextMeasure { val m = rememberTextMeasurer(); /* ... */ }
```

### 4.5 测试计划

- **纯 Kotlin 单测（先行）**：分页不丢字符 / 不重不漏、同一 `LayoutKey` 幂等、段落跨页切分、首行缩进与孤行、中英混排与超长段落、图片占位、`LayoutKey` 变化触发失效、`charOffset ↔ pageIndex` 双向映射；用 fake `TextMeasure` 固定行宽。
- **安全单测**：`<script>` / `onerror` / `javascript:` / 外链图片被丢弃或重写；`UrlPolicyTest` 全绿（改 Ktor 时必跑）；`cf_clearance` 配对 UA 断言不回归。
- **ViewModel 单测**：补 `ChapterPayload.Text` 用例（章节切换、进度保存/恢复、设置变更触发重排且锚点不漂移）。
- **UI/截图**：小说分页、连续滚动、夜间/sepia、设置面板 4 张桌面截图；按架构 §9.3 验收「缓存冷启动首屏 < 1s、翻页无白屏、进度不丢、内存不持续增长」；三端一致性对拍（iOS 需 macOS）。
- **迁移测试**：`3.sqm` 升级 + `V3_TO_V2` 回滚后旧列数据完整；离线书架可用。

### 4.6 落地批次与技能路由

| 批次 | 交付 | 验收 | 先加载（`AGENTS.md`） |
| --- | --- | --- | --- |
| ① 正文结构化 | `NovelDocument` IR、白名单解析、TXT/EPUB 文本解析、`State.article → novel` | 单测全绿；HTML 不再原样显示 | `kotlin-kmp-code-review` + `kotlin-data-kmp-data-layer`；改桥加 `security-audit` |
| ② 分页内核 | `TextMeasure` / `NovelPaginator` / `LayoutKey` / LRU | 假测量器下分页幂等、锚点映射正确；无新增 expect/actual | `kotlin-project-state-management` 或 `kmp-mvi`（二选一） |
| ③ Viewer 接线 | 重写 `NovelContent`、Paged/Continuous Viewer、进度锚点、小说设置、DB `3.sqm` | 章节/翻页/进度/重启恢复闭环；`desktopTest` 全绿 | `kmp-sqldelight-setup`（可回滚）+ `compose-ui`；进度落盘加 `privacy-audit` |
| ④ 本地同构 | 导入 UI、`payload_json` 离线缓存、书架同构 | 本地 TXT/EPUB 与在线书同样可读可缓存 | `kotlin-project-feature-implementation` |
| ⑤ 增强（P1/P2） | 书签/划线/笔记、全文搜索、主题、翻页动画、TTS 预留 | 截图基线 + 性能门槛 | `compose-performance-skills` 按需 |

### 4.7 风险登记

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| `TextMeasurer` 构造需平台资源，纯 Kotlin 层直接引用会破坏 ADR-006 / 三端一致 | 高 | 测量接口下沉到 UI 层注入（§4.3①）；若确需 expect/actual，先走 ADR-006 评审 |
| 三端默认字体/Density 差异导致分页不一致 | 高 | 显式字体族与字号、`LocalDensity`、LayoutKey 含环境参数、三端对拍 |
| 长章（数十万字）一次性测量导致首帧卡顿/内存增长 | 高 | 按块增量分页、分批让出帧、只保留 3 章窗口、TextMeasurer 自带缓存、首屏优先 |
| HTML 白名单漏判 / ReDoS / 图片 URL 越权 | 高 | Ksoup 结构化解析 + 属性/协议白名单 + 长度上限 + `UrlPolicy` 逐跳复核；`security-audit` |
| 重排后页号漂移、进度丢失 | 中 | charOffset 锚点 + layout_key，恢复时重映射 |
| `material3 1.12.0-alpha03` / 第三方库（pagecurl 等）与 CMP 1.12.2 二进制兼容未实测 | 中 | 动画自绘优先，第三方库先做依赖可解析性矩阵；升级矩阵单点锁 `libs.versions.toml` |
| 正文整章落 `payload_json` 使 DB 膨胀 | 中 | 配额 + LRU 清理；仅缓存用户主动阅读/下载的章节 |
| iOS 未验证、桌面无 WebView | 中 | 正文渲染全走 commonMain Compose；iOS 验证需 macOS 与 Xcode 工程 |

### 4.8 明确不做

不新建第二套阅读器/模型（ADR-005）；不用 WebView 渲染正文（ADR-009）；不默认新增 expect/actual（ADR-006）；不把整章塞进单个 `Text`；不引入非官方 Navigation3 / 新状态管理框架；不预置任何书源或站点 URL（C1）；不把 `HtmlAllowlist` 变成「通用 HTML 渲染器」。

---

## 5. 参考来源

**官方 / 一手**
- IReader 仓库与开发者文档：<https://github.com/IReaderorg/IReader> 、<https://github.com/IReaderorg/IReader/blob/master/docs/developer/README.md> 、<https://ireaderorg.netlify.app/>
- LightNovelReader 仓库：<https://github.com/dmzz-yyhyy/LightNovelReader> 、官网 <https://lnr.nariko.org/>
- Legado 官方仓库 <https://github.com/gedoor/legado> 、社区主线 <https://github.com/LegadoTeam/legado> 、KMP fork README <https://github.com/fengyie007/legado> / <https://de.github.com/huajideshutiao/legado>
- OtakuWorld 仓库线索、`ReadOut` README：<https://github.com/tawhidmonowar/ReadOut>
- AndroidX API：`TextMeasurer` <https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextMeasurer> 、`rememberTextMeasurer` <https://developer.android.com/reference/kotlin/androidx/compose/ui/text/rememberTextMeasurer.composable> 、`TextLayoutResult` <https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextLayoutResult>
- Kotlin Slack 官方团队关于多平台文本测量的答复：<https://slack-chats.kotlinlang.org/t/10276320/>
- Compose 翻页动画实践：<https://www.sinasamaki.com/page-flip-3d-animation-in-jetpack-compose/> 、PageCurl 库 <https://github.com/oleksandrbalan/pagecurl>

**源码级分析（二手，已交叉核对）**
- Legado 分页与渲染：<https://deepwiki.com/catamountlin/legado/2.1-page-rendering-and-display> 、架构 <https://deepwiki.com/catamountlin/legado/1.1-architecture-overview>
- LightNovelReader 阅读器屏幕与 ViewModels：<https://zread.ai/dmzz-yyhyy/LightNovelReader/15-reader-screen-and-viewmodels> 、项目概述 <https://zread.ai/dmzz-yyhyy/LightNovelReader>
- IReader / LightNovelReader / OtakuWorld 目录与状态分析：`hysenlabs.com/projects/...`（第三方分析站，star 数与维护状态仅作参考）

**需谨慎对待**
- 若干 CSDN / 统计站文章对 IReader 的「SQLDelight/Room 数据层」「Room + Paging3」「具体 star 数」给出互相矛盾的描述，疑为自动生成内容，本文未据此下结论；不同来源给出的 star 数差异较大，一律不采信为事实。
- Legado 官方仓库当前只剩公告（1 commit），社区主线与 KMP fork 并存；引用其「KMP 版已支持多端」时必须注明是 fork 状态。

**本项目文档**
- `docs/architecture.md` §5.1/§5.2、§6.3、§9.1/§9.3、§12；`docs/contract/source.d.ts`、`source-v4.d.ts`、`novel-example.js`；`docs/reader-handoff.md`；`docs/komikku-reader-replication.md`；`AGENTS.md`。
