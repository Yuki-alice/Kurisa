# 小说阅读器 M4 实施计划（Phase 1）

> 依据：`docs/novel-reader-research.md` 第 4 章、`docs/architecture.md` §6.3/§12、`AGENTS.md`。
> 本文只写文件级改动清单与验收门，不是架构规范，也不记录变更史。

## 1. 本轮范围

实现「小说章节可分页阅读 + 进度可恢复」的最小完整闭环，全部落在 `commonMain`，不新增模块、不新增 expect/actual、不引入第三方依赖。

| 做 | 不做（Phase 2） |
| --- | --- |
| HTML 白名单解析 → 富文本块 IR | 本地 TXT / 文本 EPUB 导入（`TxtReader` / `EpubTextReader`） |
| 纯 Kotlin 分页内核 + 测量接口 + LayoutKey | 图片正文真实网络加载（先用占位符，URL 已做协议白名单） |
| Compose 双 Viewer（Pager / LazyColumn） | 仿真/3D 翻页、划线笔记、全文搜索、TTS |
| 字号/行距/段距设置与响应式重排 | 按书 scope 覆写、字体文件选择、两端对齐精调 |
| `char_offset` + `layout_key` 进度锚点（可回滚迁移） | 跨设备同步、书签 UI |
| 单元/迁移测试 + 编译验证 | 三端截图基线、iOS 真机验证（需 macOS） |

## 2. 文件级改动清单

### 新建

| 文件 | 内容 |
| --- | --- |
| `core/model/.../NovelDocument.kt` | `NovelBlockKind`、`NovelBlock`、`NovelDocument`、`hashText()`（FNV-1a，纯 Kotlin） |
| `core/model/.../NovelContentParser.kt` | `NovelContentParser.parse(chapterId, body, format)`：HTML 白名单扫描器 / PLAIN / MARKDOWN 简化解析；危险标签内容丢弃、URL 协议白名单、实体解码、2M 长度上限 |
| `core/reader/.../NovelTypography.kt` | `NovelTypography`（字号/行距倍数/段距/页边距/首行缩进） |
| `core/reader/.../NovelPaginator.kt` | `TextMeasure`、`MeasuredLine`、`LineMetrics`、`NovelStyleSpec`、`NovelViewport`、`LayoutKey`、`NovelPage`、`NovelPaginator`、`NovelPageCache`、锚点映射 |
| `feature/reader/.../NovelReader.kt` | `rememberComposeTextMeasure()`（`rememberTextMeasurer` 注入）、`NovelReader`、Paged/Continuous Viewer、页内容渲染、点按分区复用 |
| `core/model/src/commonTest/.../NovelContentParserTest.kt` | 白名单/实体/协议/长度/坐标单测 |
| `core/reader/src/commonTest/.../NovelPaginatorTest.kt` | 分页幂等、不丢字符、跨页切分、锚点映射、LayoutKey 单测 |
| `feature/reader/src/commonTest/.../ReaderViewModelNovelTest.kt` | Text 载荷 → 结构化文档、虚拟页数回传、锚点保存/恢复 |
| `data/db/.../migrations/3.sqm` | `reading_progress` 加 `char_offset` / `layout_key` |

### 修改

| 文件 | 改动 |
| --- | --- |
| `core/model/.../DomainModels.kt` | `ReadingProgress` 末尾加 `charOffset: Int = -1`、`layoutKey: String = ""`（向后兼容位置参数） |
| `core/reader/.../ReaderStateMachine.kt` | `setNovelPageCount()`（虚拟页数）、`currentNovelPayload()`；页数计算统一走有效页数 |
| `core/reader/.../ReaderStateMachine.kt`（ReadingConfig） | 增加 `typography: NovelTypography = NovelTypography()` |
| `feature/reader/.../ReaderContract.kt` | `State` 增加 `novel` / `novelAnchor` / `novelLayoutKey`；`Intent` 增加 `NovelPagesReady`、`NovelAnchorChanged`、三个排版 Intent |
| `feature/reader/.../ReaderViewModel.kt` | 解析正文、保存/恢复锚点、虚拟页数回传、排版设置持久化 |
| `feature/reader/.../Reader.kt` | `ReaderContent` 小说分支改走 `NovelReader`；页码指示器对小说生效；设置面板增加小说排版区 |
| `data/db/.../Progress.sq` | 表与 `upsertProgress` 增加两列 |
| `data/db/.../DatabaseFactory.kt` | `RollbackScripts.V4_TO_V3`（重建表，只丢新列） |
| `data/repository/.../Repositories.kt` | `ProgressRepositoryImpl.save/load` 透传新字段 |
| `data/repository/.../ReaderSettingsRepository.kt` | `ReaderSettingsDto` 增加排版字段并 coerce |
| `data/db` / `data/repository` 测试 | 迁移与回滚断言、`upsertProgress` 参数 |
| `docs/README.md` | 文档地图登记 |

## 3. 接口与关键决策

- **测量边界**：`TextMeasure` 是 `core/reader` 的纯 Kotlin 接口；唯一实现 `ComposeTextMeasure` 位于 `feature/reader`，用 `rememberTextMeasurer()` + `LocalDensity` 构造，避免 ADR-006 禁止的平台 expect/actual。
- **坐标**：`charStart` 是「规范化全文」偏移，文本 = 各块 `text` 以 `\n` 连接。分页不丢字符；`NovelPaginator.pageOfCharOffset()` 把锚点映射回虚拟页。
- **LayoutKey**：`chapterId + contentHash + viewportPx + typography + density + fontFamilyId + localeTag`；命中则复用分页结果，字号/窗口变化只重排当前章。
- **HTML 安全**：自研扫描器只解析白名单标签；`script/style/iframe/object/embed/svg` 连内容一起丢弃；忽略全部 `on*` 属性；图片 URL 拒绝 `javascript:`/`data:`/`file:`/`vbscript:`；本轮图片只渲染占位符，不发起网络请求（`UrlPolicy` 接线留 Phase 2）。
- **进度**：`reading_progress.page_index` 存虚拟页，`char_offset` 存字符锚点，`layout_key` 标记排版版本；恢复以锚点为准，避免重排后漂移。迁移 `3.sqm` + 可回滚 `V4_TO_V3`。
- **状态机**：小说虚拟页数由 Viewer 回传 `NovelPagesReady` → `machine.setNovelPageCount()`；`pageTo`/`preloadWindow`/`currentPageCount` 统一使用有效页数，漫画路径不变。
- **ViewModel**：沿用 MVI（`ReaderContract.Intent` + StateFlow + Channel），不引入第二套状态框架；设置经 `ReaderSettingsStore.update` 走既有 SQLDelight 落盘。

## 4. 验收门

```bash
./gradlew :core:model:desktopTest :core:reader:desktopTest :feature:reader:desktopTest \
  :data:db:desktopTest :data:repository:desktopTest --console=plain
```

- 分页单测：同一 LayoutKey 幂等、块字符区间连续不丢、跨页段落正确切分、锚点映射稳定。
- 解析单测：`<script>`/事件属性/`javascript:` 图片被丢弃，实体解码，PLAIN 分段，超长输入截断。
- ViewModel 单测：Text 载荷产生结构化文档、虚拟页数回传、锚点保存与恢复。
- 迁移单测：v3→v4 保留旧数据、v4→v3 回滚保留核心列。
- 不改变任何漫画路径行为：`desktopTest` 既有测试全绿；`UrlPolicyTest` 不回归。

## 5. 技能路由（`AGENTS.md`）

| 阶段 | 加载 |
| --- | --- |
| 分层/模块 | `kmp-clean-architecture` + `kotlin-project-feature-implementation` |
| 状态 | `kmp-mvi`（与现有契约一致，不与 `kotlin-project-state-management` 混用） |
| SQLDelight | `kmp-sqldelight-setup`（迁移可回滚） |
| HTML / 桥 / 正文安全 | `security-audit`（MASVS-CODE/PLATFORM 自查） |
| 进度落盘 | `privacy-audit`（数据最小化） |
| Compose UI | `compose-ui` |
| 测试 | `kotlin-testing-kmp`（`kotlin.test`，commonTest 为主） |

## 6. Phase 2 进度

| 项 | 状态 | 落点 |
| --- | --- | --- |
| 本地阅读闭环（2026-11-13） | 完成 | `LocalRoutingChapterProvider`（`local:` 前缀路由，图片重写按 URL 分流）+ 双端壳 `books/` + `localFs` 透传 + `LocalBookRepository` + `LocalLibrarySync` 启动扫描入库；导入 UI/书架展示待办 |
| 本地 TXT 正文 | 完成 | `TxtReader`（UTF-8/BOM/GBK 回退、章节标题切分）；`LocalChapterProvider` 产 `<path>#<index>` 章节与 `ChapterPayload.Text(PLAIN)` |
| 文本 EPUB | 完成 | `EpubTextReader`（spine 逐页 → 受限 HTML，img 重写为 `path!/zip 内路径`）；纯文本 EPUB 从目录移除图片章节并按 spine 分章；旧 chapterId 无 `#` 仍可读首章 |
| 文内图片受控加载 | 完成 | `NovelReader` 注入 `ImageFetcher` + `domains`；仅 https 与本地 `!/` 路径放行，`SourceImageFetcher` 继续承担 UrlPolicy 白名单、重定向逐跳复核、配对 UA/Cookie；本层不传 headers |
| `novel.onImageResolve` 生产接线 | 完成（含业务头透传，2026-11-13） | `SourceCalls.novelImageResolve` 默认 null → `SourceRuntime` 覆盖；`ChapterProvider.resolveNovelImage` 默认直通 → `BridgeChapterProvider` 覆盖；`ReaderViewModel` 解析后批量重写 IMAGE 块并随 `State.novelImageHeaders` 发布业务头，`NovelImage` 经 `ImageRequest(headers)` 取图；`UrlPolicyTest` 5/0、`BridgeChapterProviderTest` 11/0、`ReaderViewModelNovelTest` 4/0 |
| 分页分批异步化 | 完成（首帧占位 + 锚点门控，2026-11-13） | `produceState` 首帧占位、结果就位后发布（`TextMeasurer` 仍主线程）；双 Viewer `anchorSynced` 门控抑制分批期间首发 0 页覆盖锚点；空页守卫 `coerceIn` 越界；截图 5/0 验证 |
| 三端截图基线 / 书签 / 全文搜索 / 仿真翻页 | 大部分完成 | 小说截图基线已发布；书签完成持久化 + 列表 UI + 定位跳转（划线/笔记待办）；全文搜索完成（搜索条/高亮/循环跳转）；行内富文本完成（加粗/斜体/代码解析+分页+渲染）；仿真翻页待办（书签笔记/链接删除线/按书设置/列表/段落划线/划线列表/批量划线/长章基准/Markdown 嵌套转义/长按圈词/排版五参数/富文本渲染基线/本地导入端到端/本地书删除/划线笔记/拖拽圈选已落地，FileKit 不可用已撤依赖，iOS 选择器待验证） |

补充约定：`data/localimport` 复用 `data/network.GbkCodec`（改为 public，纯 Kotlin 表，三端一致），
因此新增 `implementation(project(":data:network"))`；未引入新第三方依赖，未新增 expect/actual。
