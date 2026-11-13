# 小说阅读器模块编写 · 交接文档

> 交接时间：2026-11-13。用途：M4 已完成模块的历史交接记录与已知坑索引，不再用于任务移交。
> 本文只描述事实与待办，不是架构规范；架构以 `docs/architecture.md` 为准，契约以 `docs/contract/` 为准。
> 当时的配套提示词 `docs/_archive/2026-11-13/novel-reader-agent-prompt.md` 已归档，仅作追溯，不再用于任务移交。

## 0. 交接摘要

- 任务：完成 M4「小说与本地导入」——在线小说章节可分页阅读、本地 TXT/EPUB 与在线书同构、进度/书签可靠、图片受控加载、三端一致。
- 当前状态：**Phase 1/2 已完成并反复全量验证通过，M4 已于 2026-11-13 合并进 main（提交 867167e）**；后续 P2 切片（书签/划线 4 种输入/笔记/搜索批量/富文本全集/按书设置/本地导入端到端 + 删除/排版五参数/渲染基线/外链迁移）全部落地，当次全仓 `desktopTest` **453 用例 / 0 失败 / 0 错误 / 0 跳过（M4 当时基线）**、BUILD SUCCESSFUL + `:app:android:assembleDebug` 成功；当前基线 457/0/0/0 见 `docs/README.md`。
- 当前全仓基线：457/0/0/0（2026-11-13，见 `docs/README.md` 测试门禁口径）；本节仅为历史验证记录。
- 所有改动**已随 M4 合并进 main（提交 867167e）**（交接时工作区状态见 §3）；文档地图已登记，工作区日志在 `.workbuddy/memory/2026-11-13.md`。
- 不要重写已完成部分，不要引入新第三方依赖或新 expect/actual。

## 1. 完成定义（M4 Gate）

| 维度 | 完成标准 |
| --- | --- |
| 在线小说 | `novel.loadChapter` 的 HTML/PLAIN/MARKDOWN 正文经白名单解析 → 分页 → Paged/Continuous 双 Viewer；字号/行距/段距可调，翻页无白屏 |
| 本地书 | TXT / 文本 EPUB / 图片 EPUB / CBZ / PDF 均可读，导入后生成与在线书同构的 `Work/Chapter`，书架统一展示（架构 §12 M4 Gate） |
| 进度 | 章节 + 虚拟页 + 字符锚点，重排/换设备/旋转后位置不漂移；书签/划线可定位 |
| 安全 | 正文不经过 WebView；HTML 白名单；图片 URL 经 `UrlPolicy` 白名单与重定向逐跳复核；`cf_clearance` 与 `pairedUserAgent` 绑定不被破坏 |
| 质量 | 全量 `desktopTest` 0 失败、`:app:android:assembleDebug` 成功、三端截图基线、iOS 编译验证（需 macOS） |

## 2. 已完成内容

### Phase 1（已全量验证）

| 能力 | 落点 |
| --- | --- |
| 正文结构化 | `core/model/NovelDocument.kt`（块 IR、字符坐标、FNV 指纹）、`NovelContentParser.kt`（HTML 白名单扫描器 / PLAIN / MARKDOWN；危险标签连内容丢弃、`on*` 属性不解析、图片协议白名单、2M 上限、实体解码） |
| 分页内核 | `core/reader/NovelPaginator.kt`（`TextMeasure` 接口、按行切页不丢字符、`LayoutKey`、`NovelPageCache`、锚点映射）、`NovelTypography.kt` |
| 状态机 | `ReaderStateMachine.setNovelPageCount()` / `currentNovelPayload()`；`pageCountOf(payload, novelPages)` 让虚拟页数参与 `pageTo`、预加载窗口、页数显示；`ReadingConfig.typography` |
| MVI 与 UI | `ReaderContract` 新增 `novel/novelAnchor/novelLayoutKey` 与 6 个 Intent；`ReaderViewModel` 解析正文、回传虚拟页、保存/恢复锚点；`NovelReader.kt` 用 `rememberTextMeasurer()` 注入测量，HorizontalPager / LazyColumn，切章 `key(chapterId)` 重建 Viewer，设置滑块松手提交 |
| 进度持久化 | `ReadingProgress.charOffset/layoutKey`；`Progress.sq` + `3.sqm` 加列；`RollbackScripts.V4_TO_V3` 可回滚；`ProgressRepositoryImpl` / `ReaderSettingsDto` 透传 |
| 测试 | `NovelContentParserTest`、`NovelPaginatorTest`、`ReaderViewModelNovelTest`、迁移/回滚断言、schema 一致性断言 |

### Phase 2（本轮已完成并全量验证通过，已合并 867167e）

| 能力 | 落点 |
| --- | --- |
| 本地 TXT | `TxtReader.kt`（UTF-8/BOM 优先、非法序列回退 `GbkCodec`、章节标题切分、4M 上限） |
| 文本 EPUB | `EpubTextReader.kt`（复用 `EpubReader.pagePaths()`，spine 逐页输出受限 HTML，img 重写为 `path!/zip 内路径`） |
| 本地章节源 | `LocalChapterProvider.kt` 重写：`chapters()` 追加 `<path>#<index>` 文本章节；纯文本 EPUB 移除同路径漫画章节；`payload()` 对 `#` 后缀返回 `ChapterPayload.Text`；无 `#` 的旧 id 仍可读首章；`rawBytes()` 兼容 `#` |
| 依赖 | `GbkCodec` 由 `internal` 改为 public（纯 Kotlin 表，三端一致）；`data/localimport/build.gradle.kts` 新增 `implementation(project(":data:network"))`；无新第三方依赖、无新 expect/actual |
| 文内图片受控出口 | `ReaderScreen` → `ReaderScreenVm` → `ReaderContent` → `NovelReader` 传递 `images: ImageFetcher?` 与 `domains`；`NovelImage` 只放行 https 与本地 `!/`，经 `ImageFetcher` 加载，本层不传 headers；相对路径/http/file/data 一律占位 |
| 新增测试 | `TextImportTest.kt`（TXT/EPUB/provider 接线）、`LocalChapterProviderTest` 更新断言、`NovelImagePolicyTest.kt`、`ReaderScreenshotTest.novelReaderRendersStructuredText()`（生成 `reader-novel-paged.png` 证据） |
| 文档 | `docs/_archive/2026-11-13/novel-reader-plan.md` 追加 §6 Phase 2 进度 |

## 3. 工作区状态

`git status --short`（交接时）：修改 18 个文件、新增 16 个文件，未提交。主要路径：

- 修改：`core/model/DomainModels.kt`、`core/reader/ReaderStateMachine.kt`、`data/db/{DatabaseFactory.kt,Progress.sq,SchemaConsistencyTest.kt,MigrationAndOfflineTest.kt}`、`data/localimport/{build.gradle.kts,LocalChapterProvider.kt,LocalChapterProviderTest.kt}`、`data/network/GbkCodec.kt`、`data/repository/{Repositories.kt,ReaderSettingsRepository.kt}`、`feature/reader/{Reader.kt,ReaderContract.kt,ReaderViewModel.kt,ReaderScreenshotTest.kt}`、`docs/README.md`
- 新增：`core/model/{NovelDocument.kt,NovelContentParser.kt,NovelContentParserTest.kt}`、`core/reader/{NovelPaginator.kt,NovelTypography.kt,NovelPaginatorTest.kt}`、`data/db/.../migrations/3.sqm`、`data/localimport/{TxtReader.kt,EpubTextReader.kt,TextImportTest.kt}`、`feature/reader/{NovelReader.kt,NovelImagePolicyTest.kt,ReaderViewModelNovelTest.kt}`、`docs/{novel-reader-research.md,novel-reader-plan.md}`（其中 `novel-reader-plan.md` 已归档至 `docs/_archive/2026-11-13/`）

> 2026-11-13 续写后：未提交改动累计约 76 个文件（`git status --short` 实测；含上述全部 + 后续 20+ 轮 P2/P3 切片：书签/划线/搜索/富文本/按书设置/导入 UI 与删除/排版/渲染基线/外链迁移及全部单测与文档）。提交前逐项复核，勿带 `build/` 产物。

文档地图 `docs/README.md` 已登记 research / plan / 本交接文档与提示词。活跃文档 `docs/reader-handoff.md` 未被改动。

## 4. 验证状态记录

| 命令 | 结果 |
| --- | --- |
| `:core:model:desktopTest :core:reader:desktopTest` | BUILD SUCCESSFUL（首次因 `attribute()` 未跳过标签名失败，已修） |
| `:feature:reader:desktopTest :data:db:desktopTest :data:repository:desktopTest` | BUILD SUCCESSFUL |
| `desktopTest`（Phase 1 收尾） | **372 用例 / 0 failure / 0 error**（74 个结果文件） |
| `:app:android:assembleDebug`（Phase 1 收尾） | BUILD SUCCESSFUL（420 tasks） |
| `:data:localimport:desktopTest :data:network:desktopTest` | 首次失败：`createZipFromTree()` 是 suspend；已把测试 fixture 改为 suspend |
| `:data:localimport:desktopTest`（修复后） | BUILD SUCCESSFUL，50 tests |
| `:feature:reader:desktopTest`（图片接线后、新增测试前） | BUILD SUCCESSFUL（1m36s） |
| `:feature:reader:desktopTest`（含 `NovelImagePolicyTest` + 小说截图测试） | 首次 **1 失败**：`novelReaderRendersStructuredText` 误判空白（8x8 稀疏采样落在白底，证据图实际有文字）；已修为 24x24 加密采样 + 等虚拟页回传，复跑 **26/0 通过** |
| 全量 `desktopTest` + `:app:android:assembleDebug`（P0 收口，2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **383 用例 / 0 failure / 0 error**（76 文件），Android `assembleDebug` 成功；`UrlPolicyTest` 5/0 无回归，截图证据 `reader-novel-paged.png`（12KB，首章标题+两段正文+1/1）已落盘 |
| P1 接线后全量回归（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **386 用例 / 0 failure**（新增 Bridge 2 + VM 1），`BridgeChapterProviderTest` 11/0，`ReaderViewModelNovelTest` 4/0，`UrlPolicyTest` 5/0，Android 构建成功 |
| P1 补完：业务头透传 + 分页异步化 + 锚点门控（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **386 用例 / 0 failure / 0 error**（76 文件），Android `assembleDebug` 成功；`ReaderViewModelNovelTest` 4/0（含 headers 断言），`ReaderScreenshotTest` 5/0（异步分页下文字正常渲染，证据图人工复核通过），`UrlPolicyTest` 5/0 无回归；`docs/screenshots/reader-novel-paged.png` 基线已发布 |
| P2 切片：书签持久化闭环（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **389 用例 / 0 failure / 0 error**（77 文件），Android `assembleDebug` 成功；新增 `BookmarkRepositoryTest` 2/0、`toggleBookmarkPersistsAndRestores` 1/0；`bookmark.position` 存小说字符锚点/漫画页下标，`excerpt` 120 字窗口本地展示；`MigrationAndOfflineTest` 5/0、`SchemaConsistencyTest` 4/0（`.sq` 仅加查询，无版本变更） |
| P2 切片：本地阅读闭环（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **395 用例 / 0 failure / 0 error**（79 文件），Android `assembleDebug` 成功；新增 `LocalRoutingChapterProviderTest` 4/0、`LocalBookRepositoryTest` 2/0；`local:` 前缀作品走本地源（TXT/文本EPUB/图片EPUB/CBZ/PDF/目录），在线走 bridge，图片重写按 URL 形状分流；双端壳 `books/` 目录 + `localFs` 透传，本地归档图片经 `LocalImageFetcher` 可读 |
| P2 切片：书签列表 UI + 定位跳转（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **400 用例 / 0 failure / 0 error**（80 文件），Android `assembleDebug` 成功；新增 VM 4（列表加载/同章 seek/跨章锚点/漫画翻页）+ `ReaderBookmarkSheetTest` UI 1/0（面板渲染摘要→点击跳转关闭）；同章小说经 `bookmarkSeek` 门控滚动，跨章经 `pendingBookmarkSeek` 在 `onReady` 定锚，漫画 `pageTo` |
| P2 切片：本地库扫描入库（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **403 用例 / 0 failure / 0 error**（81 文件），Android `assembleDebug` 成功；新增 `LocalLibrarySyncTest` 3/0（空库/建书保首扫时间/消失清理保异源行）；`App` 启动自动扫描 `books/`（失败静默不影响阅读），行 `id = local:<目录名>` 与路由前缀同构，标题优先 ComicInfo |
| P2 切片：全文搜索（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **405 用例 / 0 failure / 0 error**（82 文件），Android `assembleDebug` 成功；新增 VM 1（命中/循环/回绕/清空/关闭）+ `ReaderSearchBarTest` UI 1/0（打开→计数→下/上个→关闭）；字面大小写不敏感，上限 500；跳转复用 seek 通道，高亮当前主色/其余次色；空搜索默认渲染路径不变（截图 5/0 佐证） |
| P2 切片：行内富文本（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **412 用例 / 0 failure / 0 error**（82 文件），Android `assembleDebug` 成功；新增解析器 6（HTML 三标签/Markdown 三标记/未闭合字面/下划线守卫/空白折叠映射/PLAIN 无 span）+ 分页切片融合 1；`NovelSpan` 随块坐标，分页裁剪平移、合并融合；渲染 AnnotatedString（加粗/斜体/等宽 + 搜索底色叠加）；首轮全量门曾遇增量编译守护进程脏数据崩溃（非代码问题），清理受影响模块后通过 |
| P2 切片：书签笔记（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **414 用例 / 0 failure / 0 error**（82 文件），Android `assembleDebug` 成功；新增 VM 1（保存截断 2000/取消不改动/空串清除）+ `ReaderBookmarkSheetTest` UI 1/0（编辑对话框→保存→列表显示→仓库落盘）；笔记纯本地展示，上限 2000 字；全套件下搜索条旧用例曾因菜单/合成帧时序抖动失败，已按截图测试惯例加 `mainClock.advanceTimeBy` 稳定 |
| P2 切片：链接/删除线（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **417 用例 / 0 failure / 0 error**（82 文件），Android `assembleDebug` 成功；新增解析器 2（HTML 删除线/外链白名单 + Markdown 删除线/链接/扩展转义）+ 分页 payload 保持 1；`NovelSpan.payload` 仅 LINK 携带 http(s)；渲染 `ClickableText` 外部浏览器打开（有弃用警告，迁移记后续），删除线/链接样式与搜索底色可叠加 |
| P2 切片：按书设置覆写（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **420 用例 / 0 failure / 0 error**（83 文件），Android `assembleDebug` 成功；新增仓库 1（全局回退/按书隔离/重置删行）+ VM 1（跨书隔离/重置回退）+ UI 1（面板提示行/恢复按钮回退字号）；打开章节自动进 `book:<workId>` 域，设置面板按书提示 + 恢复全局；首轮模块门曾因 InMemory 丢 import 与 TestScope 跟踪无限 collect 失败，已修正 |
| P2 切片：列表与导入选型矩阵（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **423 用例 / 0 failure / 0 error**（83 文件），Android `assembleDebug` 成功；新增解析器 2（Markdown 无序列表/`li` 列表， ordered 保持段落）+ 分页 LIST 保持 1；无序列表统一 `• ` 前缀（HTML/EPUB/Markdown 同形），有序列表靠原文数字；`docs/local-import-picker-matrix.md` 完成 FileKit 选型结论（0.17.0，Maven Central 三端覆盖，暂不引入） |
| P2 切片：段落划线（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **430 用例 / 0 failure / 0 error**（85 文件），Android `assembleDebug` 成功；新增 DB 迁移 2 + 一致性 1 + 仓库 2 + VM 1 + UI 1；`highlight` 表 + `4.sqm` + `V5_TO_V4` 回滚；划线模式开关 + 点段落切换（乐观更新），渲染 tertiary 容器底；搜索 UI 时序教训：桌面端注入式点击走命中测试，顶栏覆盖层会吃掉首段点击，点段落前先关菜单 |
| P2 切片：划线列表与跳转（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **432 用例 / 0 failure / 0 error**（85 文件），Android `assembleDebug` 成功；新增 VM 1（跳转 seek/关闭面板/回收/删除同步）+ UI 1（面板点行跳转关闭/重开删除）；书签面板内「本章划线」分区（摘要 + 删除），跳转复用 seek 通道；教训：桌面端注入式点击走命中测试（顶栏覆盖层吃掉首段点击，先关菜单），`stateIn(WhileSubscribed)` 致 `state.value` 滞后一律 `waitUntil`，裸 `assert` 无 `-ea` 时是空操作，先建 VM 后写库存在竞态（落盘/恢复先行） |
| P2 切片：按搜索批量划线（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **434 用例 / 0 failure / 0 error**（85 文件），Android `assembleDebug` 成功；新增 VM 1（批量建条/摘要取命中原文/重复不增）+ UI 1（搜索条「标出」→划线落盘）；`HighlightAllSearchMatches` 按当前匹配生成确定性 id（重复执行幂等）；换章清搜索（匹配是文档坐标） |
| P3 切片：长章性能基准 + 复审（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **435 用例 / 0 failure / 0 error**（85 文件），Android `assembleDebug` 成功；新增 `longChapterPaginatesInReasonableTime`（5000 段 ≈ 0.5MB，实测 35ms/5000 页，阈值 10s）；复审删除 Markdown 解析器死代码 `close()`（单 `*`/`_` 路径改用 `findInlineClose` 后无人调用） |
| P2 切片：Markdown 嵌套与转义（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **437 用例 / 0 failure / 0 error**（85 文件），Android `assembleDebug` 成功；新增解析器 2（跨标记嵌套/ASCII 标点转义）；加粗/删除线内部递归（与单标记分支同构），单标记闭合查找跳过成对标记；同种标记嵌套为首闭合优先（文档化简化） |
| P2 切片：长按圈词划线（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **439 用例 / 0 failure / 0 error**（85 文件），Android `assembleDebug` 成功；新增 `wordRangeAt` CJK 词边界 + 单测（拉丁 run/下划线/标点/全角/空串）+ 长按 UI 1（段落内圈词落盘）；与点段落共用 `ToggleBlockHighlight` 落盘管线（乐观更新）；中日韩独体字按单字成词 |
| P2 切片：排版五参数补齐（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **439 用例 / 0 failure / 0 error**（85 文件），Android `assembleDebug` 成功；新增 `NovelPageMarginChanged` / `NovelParagraphIndentChanged` 意图 + 设置面板两滑块（页边距 0~64dp/首行缩进 0~4em，松手提交）；既有 typography 单测扩展断言 |
| P3 切片：富文本渲染基线（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **440 用例 / 0 failure / 0 error**（85 文件），Android `assembleDebug` 成功；新增 `novelReaderRendersRichTextWithSearchAndHighlight`（标题/加粗/斜体/外链下划线/列表 + 搜索底色 + 划线底色三层同屏，已人工看图确认）；`docs/screenshots/reader-novel-rich.png` 基线已发布 |
| P2 切片：本地导入端到端（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **445 用例 / 0 failure / 0 error**（87 文件），Android `assembleDebug` 成功；FileKit 实测不可用（0.17.0 common 元数据 expect 成员缺失，已撤依赖并记入选型矩阵）；改用平台原生选择器 + `PickLocalFiles` 接口注入（Desktop FileDialog/Android SAF，iOS 暂缺）；新增导入逻辑单测 2 + 书架 UI 单测 3；书架「本地书」分区 + 导入入口 + 首章直达 `ReaderRoute` |
| P3 切片：外链迁移 LinkAnnotation（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **445 用例 / 0 failure / 0 error**（87 文件），Android `assembleDebug` 成功；`ClickableText`（已弃用）→ 普通 `Text` + `addLink(LinkAnnotation.Url)`，点击经 `LocalUriHandler` 打开（行为不变，不经过 WebView）；本版 Builder 方法名为 `addLink`（非文档中的 `addLinkAnnotation`，已实测） |
| P2 切片：本地书删除（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **447 用例 / 0 failure / 0 error**（87 文件），Android `assembleDebug` 成功；`LocalSourceFileSystem.deleteRecursively`（手动递归，含隐藏文件）+ `LocalBookImporter.remove`（目录 + 落库行，幂等）+ 书架行内「删除」按钮 + App 重扫刷新；korlibs 无 `VfsFile.deleteRecursively` 扩展，`node.delete()` 经编译验证可用 |
| P2 切片：划线笔记（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **450 用例 / 0 failure / 0 error**（87 文件），Android `assembleDebug` 成功；`Highlight.note` 落库打通（模型字段 + 仓库读写，免迁移）；`StartEditHighlightNote/SaveHighlightNote/CancelEditHighlightNote` + `HighlightNoteDialog`（与书签笔记同构，2000 字上限）；书签面板划线行展示笔记 + 编辑入口 |
| P2 切片：拖拽圈选划线（2026-11-13） | **BUILD SUCCESSFUL**：`desktopTest` **453 用例 / 0 failure / 0 error**（87 文件），Android `assembleDebug` 成功；`detectDragGesturesAfterLongPress` 统一手势（点按整段/长按圈词/拖拽选区三态，`combinedClickable` 防双重触发）；选区落盘成功能；长按拖拽经 isolation 测试 + 真机链路验证；修重大 bug：if/else 与换行 `.then()` 优先级陷阱曾导致修饰符丢失 |


P0 两项验证已补齐并通过。

## 5. 未完成清单（按优先级）

### P0 · 收口验证（已完成）
1. 重跑 `:feature:reader:desktopTest`：`NovelImagePolicyTest` 与 `novelReaderRendersStructuredText` 通过，截图采样按文字页实际用 `minColors = 2`。已完成。
2. `desktopTest` 全量 + `:app:android:assembleDebug` 通过，Phase 2 未破坏漫画/网络/迁移路径（`UrlPolicyTest`、`MigrationAndOfflineTest`、`ReaderViewModelTest` 均无回归）。已完成。
3. 全量复核无失败：本次实测全仓 `desktopTest` **453 用例 / 0 失败 / 0 错误 / 0 跳过，BUILD SUCCESSFUL**（2026-11-13 M4 合并 867167e 后基线）。已完成。

### P1 · 运行时图片重写与性能
1. **`novel.onImageResolve` 生产接线**（2026-11-13 已落地最小闭环）：`SourceCalls.novelImageResolve()` 默认 null，`SourceRuntime` 覆盖为真实 JS 调用（`sanitized()` 剥离 UA/Cookie，`PureResultCache` 按源 key+URL 键控）；`ChapterProvider.resolveNovelImage()` 默认直通，`BridgeChapterProvider` 覆盖为按源重写；`ReaderViewModel.onReady` 解析后对 IMAGE 块批量重写（单张失败回退原文），最终可加载性仍由 `isAllowedNovelImageUrl` + `SourceImageFetcher` 白名单/逐跳复核决定。已过 `security-audit` 自查（MASVS-NETWORK/PLATFORM/CODE，见终报），`UrlPolicyTest` 5/0、`BridgeChapterProviderTest` 11/0（含新增 2）。2026-11-13 补完：`State.novelImageHeaders`（key=effectiveUrl）随正文原子发布，`NovelImage` 取图时经 `ImageRequest(headers)` 透传业务头（仍走 `SourceImageFetcher` 白名单/逐跳复核，UA/Cookie 由宿主注入）；`ReaderViewModelNovelTest` 新增 headers 断言。
2. **分页分批异步化**（2026-11-13 已落地）：`NovelReader` 改为 `produceState` 首帧占位 + 组合后发布排版结果（`TextMeasurer` 仍在主线程使用，仅推迟执行，未移出 CompositionLocals）；`NovelPagesReady` 仅在结果就位后发射；Paged/Continuous 双 Viewer 新增 `anchorSynced` 门控——锚点页滚动到位前抑制 `snapshotFlow{currentPage}` 的首发，不再用第 0 页覆盖已恢复的 `novelAnchor`；空页时守卫 `coerceIn` 越界。`ReaderScreenshotTest` 5/0 验证渲染正常。后续可按块分批发布，但需保持门控语义。

### P2 · 导入闭环与增强
1. 本地导入 UI 与 `Work/Chapter` 落库（阅读 + 落库已闭环，2026-11-13）：`LocalRoutingChapterProvider` 按 `local:` 前缀二路路由；双端壳自动建 `books/` 目录并透传 `localFs`；`LocalBookRepository` + `LocalLibrarySync` 启动扫描入库（目录即书，标题优先 ComicInfo，首扫时间保留，消失清理）；`LocalSourceScanner` 落库注释已更新。导入端到端已闭环（2026-11-13）：书架「本地书」分区 + 「导入」入口；平台原生选择器经 `PickLocalFiles` 注入（Desktop FileDialog / Android SAF；iOS 暂缺）；`LocalBookImporter` 落盘 + upsert（幂等，首扫时间保留）；导入后重跑扫描并刷新列表，点书定位首章进 `ReaderRoute`。FileKit 经实测不可用（common 元数据缺 expect 成员，已撤依赖，见选型矩阵）。
2. `MARKDOWN` 富文本（标题/引用/列表/加粗/斜体/代码/删除线/外链/嵌套/转义均已落地，剩余未知元素的保守直通）、两端对齐（Justify 已用）、按书 `scope` 覆写阅读设置（已落地）。
3. 书签/划线/笔记/搜索/富文本/按书设置（已落地：`BookmarkRepository` 持久化 + VM 切换/恢复 + 书签列表 Sheet（含本章划线分区/跳转/删除） + 同章 seek/跨章定锚/漫画翻页跳转 + 笔记编辑对话框（2000 字上限） + 全文搜索条/高亮/计数/循环跳转/按搜索批量划线 + 行内富文本加粗/斜体/代码/删除线/外链/列表/嵌套/转义 + 点击跳转外部浏览器 + 按书设置覆写（作用域隔离/回退/重置 + 面板提示） + 段落级划线（模式开关/点段落切换/落库/重开恢复/tertiary 渲染） + 长按圈词划线（CJK 词边界/长按手势/同管线落盘） + 排版五参数可调（字号/行距/段距/页边距/首行缩进） + 本地导入端到端（书架本地书分区/导入入口/首章直达）；仿真翻页仍待办；本地书删除已落地（目录递归删 + 落库行删除 + 书架行内入口）；划线笔记已落地（落库打通 + 编辑器 + 行内展示）；拖拽圈选已落地（统一三态手势 + 范围落盘 + 「标出所选」确认条）。

### P3 · 三端与发布
小说三端截图基线（`docs/screenshots/reader-novel-paged.png` 基线已发布 12KB，沿用 M5 截图纪律）、iOS/macOS 构建验证（需 macOS，本机不可）、长章性能基准（已落地：5000 段 ≈ 0.5MB 实测 35ms/5000 页，阈值 10s）、`ReaderRoute.sourceKey` 多源路由（M5）。

## 6. 硬约束与红线

- `AGENTS.md`：任务先加载对应技能；ViewModel 沿用现有 MVI（`kmp-mvi` 或 `kotlin-project-state-management` 二选一，不混用）；SQLDelight 迁移必须可回滚；碰 WebView host / JS 桥 / Cookie / Ktor 引擎 / URL 白名单先加载 `security-audit`；隐私（阅读记录落盘）加 `privacy-audit`；导航只用官方 Navigation3。
- **ADR-006**：默认不新增 expect/actual；`TextMeasure` 接口 + UI 层 `rememberTextMeasurer()` 注入就是为此设计的，不要倒退回 core 层直接构造 `TextMeasurer`。
- **ADR-005**：漫画与小说共用一条管线，不新建第二套模型/阅读器；`ADR-009`：桌面不内嵌浏览器，正文绝不交给 WebView。
- **C1** 本体不预置站点/书源；**C3** 三端一致；`cf_clearance` 与 `pairedUserAgent` 绑定是红线，不得改裸 Cookie 复用。
- 产品名只落配置层，代码内中性命名；架构文档只改正文、不写版本号/变更史；`docs/reader-handoff.md` 是活跃文档，勿覆盖。
- `UrlPolicy.isAllowed` 强制 https + 域名白名单，`SourceHttpClient` 逐跳复核重定向并统一注入 UA/Cookie；任何图片/网络改动不得绕过该出口。

## 7. 已知坑（踩过，勿重复）

- HTML `attribute()` 解析必须先跳过标签名，否则 `img src=...` 的 key 会变成 `"img src"`，所有图片被误拦截。
- `korlibs` 的 `VfsFile.createZipFromTree()` 是 **suspend**，测试 fixture 函数需声明 `suspend`。
- Ksoup `doc.body()` 返回非空 `Element`，`?.html()` 会产生编译警告。
- `java.lang.Long.toHexString()`、`String.format()` 都不是 commonMain 可用 API；用 `Long.toULong().toString(16)` 与字符串插值。
- 纯文本 EPUB 必须在章节列表中移除扫描器生成的同路径漫画章节，否则目录出现空图片章。
- `ReaderScreenVm` / `ReaderContent` 新增的 `images`/`domains` 都有默认值，现有测试直接调用不会破坏。
- 截图测试复用 `InMemoryReaderSettingsStore(ReadingConfig(theme = WHITE))`；文字页颜色数可能只有 2，`assertRendered(..., minColors = 2)`。
- `NovelPaginator.paginate()` 的 `key` 参数用于 density 换算与缓存键，不要删。
- UI 注入式点击走命中测试：顶栏/横幅覆盖层会吃掉首段点击，点段落前先关菜单；`stateIn(WhileSubscribed)` 使 `state.value` 滞后于 `local`，断言一律用 `waitUntil` 而非裸 `assert`（JUnit `assert` 在无 `-ea` 时是空操作）。
- UI 测试时序抖动统一用 `mainClock.advanceTimeBy + waitForIdle`（菜单进场动画 200ms 与新合成帧场景只靠 `waitForIdle` 不够；`onNode` 报 unmerged-tree 命中时先加时钟推进再考虑 `useUnmergedTree`）。
- `ClickableText` 在本仓 Compose 版本已弃用（官方指引迁 `Text/BasicText + LinkAnnotation`）；当前按可验证写法保留，版本升级时迁移。
- SQLDelight 表名下划线保留进生成类名（`local_book` → `Local_book`，不是 `LocalBook`）；`XXXQueries` 接口名才是驼峰。
- 全量门若报 `FqNames can't be derived from DirtyData`（Kotlin 增量编译守护进程脏数据，非代码问题），清理改动涉及的模块（`:core:model:clean` 等）后重跑。
- `assertDoesNotExist` 在当前 Compose 测试版本不可直接 import，改用 `onAllNodesWithText(...).assertCountEquals(0)`；跨模块 public 属性不可智能转换，先取值到局部变量。
- Compose `produceState` 命名参数后不可跟位置参数（如 `initialValue = null, signature` 非法）；多键用 `key1 = / key2 =` 具名，且最多 3 个具名键（第 4 个起改用 `vararg` 位置形式或合并为 Pair）。
- `Int.coerceIn(0, pages.lastIndex)` 在空列表（lastIndex=-1）会抛参异常，异步分页下必须先判空。

## 8. 验收门命令

```bash
cd /f/Kurisa   # Windows Git Bash；或在项目根用 -p 指定
./gradlew :feature:reader:desktopTest --console=plain --no-daemon --max-workers=2
./gradlew desktopTest :app:android:assembleDebug --console=plain --no-daemon --max-workers=2
./gradlew :data:localimport:desktopTest :data:network:desktopTest --console=plain --no-daemon --max-workers=2
```

全量测试统计（Phase 1 基线）：372 用例 / 0 失败。新增用例后应只增不减；`UrlPolicyTest` 5 个断言不得回归。

## 9. 技能路由

| 阶段 | 加载 |
| --- | --- |
| 分层/feature | `kmp-clean-architecture`、`kotlin-project-feature-implementation` |
| 状态 | `kmp-mvi`（不与 `kotlin-project-state-management` 混用） |
| SQLDelight | `kmp-sqldelight-setup` |
| 网络/白名单/JS 桥 | `kmp-network-layer` + `kmp-repository-pattern` + `security-audit`；必跑 `UrlPolicyTest` |
| 正文安全 | `security-audit`（MASVS-CODE/PLATFORM） |
| 进度/书签落盘 | `privacy-audit` |
| Compose UI | `compose-ui`（性能按需 `compose-expert`） |
| 测试 | `kotlin-testing-kmp`（`kotlin.test`，commonTest 为主） |
| 发版前评审 | `code-review`（定点 merge-base）+ `kotlin-kmp-code-review` |

## 10. 交接检查清单

- [x] 重跑 `:feature:reader:desktopTest`，记录截图/策略测试结果（26/0，截图采样已修）
- [x] 跑全量 `desktopTest` + Android 构建，0 失败（386/0，含 P1 新增 3）
- [x] 确认 `docs/screenshots/` 是否产出 `reader-novel-paged.png` 证据（已搬入 `docs/screenshots/` 12KB 基线）
- [x] 决定 `novel.onImageResolve` 接线范围并过安全自查（URL 重写 + 业务头透传均已落地）
- [x] 决定分页分批异步化的锚点门控方案（`produceState` + `anchorSynced` 门控已落地并验证）
- [x] 书签持久化闭环（Repository+VM+双端壳，389/0；列表 UI 与定位跳转留后续）
- [x] 本地阅读闭环（路由+fs透传+落库，395/0；导入 UI 与书架展示留后续）
- [x] 书签列表 UI + 定位跳转（400/0；划线/笔记/搜索/仿真翻页留后续）
- [x] 本地库扫描入库（403/0；导入 UI 与书架展示留后续）
- [x] 全文搜索（405/0；划线/笔记/仿真翻页留后续）
- [x] 行内富文本（412/0；划线/仿真翻页留后续）
- [x] 书签笔记（414/0；划线/仿真翻页留后续）
- [x] 链接/删除线（417/0；划线/仿真翻页留后续）
- [x] 按书设置覆写（420/0；划线/仿真翻页留后续）
- [x] 列表与导入选型矩阵（423/0；划线/仿真翻页留后续）
- [x] 段落级划线（430/0；任意区间划线/仿真翻页留后续）
- [x] 划线列表与跳转（432/0；手势任意区间/仿真翻页留后续）
- [x] 按搜索批量划线（434/0；手势任意区间/仿真翻页留后续）
- [x] 长章性能基准 + 复审（435/0；死代码已删）
- [x] Markdown 嵌套与转义（437/0；手势任意区间/仿真翻页留后续）
- [x] 长按圈词划线（439/0；手势拖拽区间/仿真翻页留后续）
- [x] 排版五参数补齐（439/0；手势拖拽区间/仿真翻页留后续）
- [x] 富文本渲染基线（440/0；手势拖拽区间/仿真翻页留后续）
- [x] 本地书删除（447/0；手势拖拽区间/仿真翻页留后续）
- [x] 划线笔记（450/0；手势拖拽区间/仿真翻页留后续）
- [x] 拖拽圈选划线（453/0；仿真翻页留后续）
- [x] 本地导入端到端（445/0；FileKit 不可用已撤依赖；手势拖拽区间/仿真翻页留后续）
- [x] 外链迁移 LinkAnnotation（445/0；行为不变；手势拖拽区间/仿真翻页留后续）
- [x] 完成本地导入 UI / 书架同构展示（445/0；iOS 选择器待 macOS 验证）
- [x] 更新 `docs/_archive/2026-11-13/novel-reader-plan.md` §6 与本文档的验证状态
- [ ] 追加 `.workbuddy/memory/2026-11-13.md` 工作日志
- [ ] 提交前复核 `git status`，确认无构建产物/临时文件入库
