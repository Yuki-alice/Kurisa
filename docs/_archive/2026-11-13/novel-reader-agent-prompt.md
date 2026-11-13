# 小说阅读器模块 · 接手 Agent 提示词

> 用法：把下面代码块内的全部文字复制给接手 agent（全新会话、无当前对话上下文也能工作）。
> 详细事实、文件清单、验证记录与已知坑见 `docs/novel-reader-handoff.md`。

```text
你是 Kurisa 项目（Kotlin Multiplatform + Compose Multiplatform 的漫画/小说聚合阅读器）的实现代理。
工作目录：F:/Kurisa（Windows，Git Bash；Gradle 用 `./gradlew -p /f/Kurisa ...`）。
你的任务：接续完成 M4「小说与本地导入」模块的完整编写与验证，直到达到下面的完成标准。

【第一步：必读，未读完不要改代码】
1. F:/Kurisa/AGENTS.md —— 技能路由与安全硬规则（违反即停）。
2. F:/Kurisa/docs/novel-reader-handoff.md —— 交接文档：已完成内容、文件清单、验证状态、未完成清单、已知坑、验收门。
3. F:/Kurisa/docs/novel-reader-plan.md —— Phase 1 实施计划与 §6 Phase 2 进度。
4. F:/Kurisa/docs/novel-reader-research.md —— 同类项目调研与架构建议（重点是第 4 章）。
5. F:/Kurisa/docs/architecture.md §5.1/§5.2、§6.3、§6.4、§9.3、§12；F:/Kurisa/docs/contract/source.d.ts 与 source-v4.d.ts。
6. 按 AGENTS.md 路由加载项目技能：实现 feature 用 `kotlin-project-feature-implementation` + `kmp-clean-architecture`；状态沿用 `kmp-mvi`；SQLDelight 用 `kmp-sqldelight-setup`；碰 JS 桥/网络/白名单必须先加载 `security-audit`（并按 OWASP MASVS 自查）；阅读进度落盘加 `privacy-audit`；Compose UI 用 `compose-ui`；写测试用 `kotlin-testing-kmp`。

【当前事实：不要重复造轮子】
- Phase 1 已完成并全量验证：HTML 白名单正文解析（NovelDocument / NovelContentParser）、纯 Kotlin 分页内核（NovelPaginator / TextMeasure / LayoutKey / 锚点）、Compose 双 Viewer（NovelReader：HorizontalPager / LazyColumn + rememberTextMeasurer 注入）、MVI 状态与虚拟页数、char_offset/layout_key 进度锚点（Progress.sq + 3.sqm + V4_TO_V3 可回滚）、字号/行距/段距设置。
- Phase 2 已完成但最后一次验证被中断：TxtReader（UTF-8/GBK 回退、章节切分）、EpubTextReader（spine 逐页受限 HTML、img 重写为 path!/zip 路径）、LocalChapterProvider 产 `<path>#<index>` 文本章节、小说文内图片经注入的 ImageFetcher + domains 受控加载（只放行 https 与本地 !/ 路径）、新增 TextImportTest / NovelImagePolicyTest / 小说截图测试。
- 最后一条命令 `:feature:reader:desktopTest`（含上述新增测试）被中断、无输出，结果未知；Phase 2 改动后的全量 desktopTest 与 Android 构建尚未执行。所有改动均未提交。
- 已验证基线：Phase 1 收尾时全量 desktopTest 372 用例 0 失败、:app:android:assembleDebug BUILD SUCCESSFUL；图片接线后单独的 :feature:reader:desktopTest 曾 BUILD SUCCESSFUL；:data:localimport:desktopTest 50 tests BUILD SUCCESSFUL。

【完成标准（M4 Gate）】
1. 在线小说 HTML/PLAIN/MARKDOWN 正文均可白名单解析、分页、Paged/Continuous 双模式阅读，字号/行距/段距可调，翻页无白屏。
2. 本地 TXT / 文本 EPUB / 图片 EPUB / CBZ / PDF 均可读，导入后生成与在线书同构的 Work/Chapter，书架统一展示。
3. 进度以章节 + 虚拟页 + 字符锚点保存，重排/换设备/旋转后不漂移；书签可定位。
4. 正文不经过 WebView；图片 URL 经 UrlPolicy 域名白名单与逐跳重定向复核；cf_clearance 与 pairedUserAgent 绑定不破坏。
5. 全量 desktopTest 0 失败、:app:android:assembleDebug 成功、UrlPolicyTest 不回归、小说截图证据存在；iOS 编译验证若环境允许（需 macOS）。

【执行顺序】
P0 收口验证（先做）：
  - 跑 `./gradlew -p /f/Kurisa :feature:reader:desktopTest --console=plain --no-daemon --max-workers=2`；确认 NovelImagePolicyTest 与 novelReaderRendersStructuredText 通过（文字页颜色数少，assertRendered 用 minColors = 2）。
  - 跑 `./gradlew -p /f/Kurisa desktopTest :app:android:assembleDebug --console=plain --no-daemon --max-workers=2`；确认漫画/网络/迁移路径无回归。
  - 修任何编译或测试失败，统计用例数并写入交接文档的验证状态。
P1 运行时图片重写与性能：
  - 接通 `novel.onImageResolve`：`SourceRuntime.novelImageResolve()` 已存在且有单测，但 SourceCalls / ChapterProvider / BridgeChapterProvider / ReaderViewModel 无生产消费入口。最小方案：SourceCalls 加默认方法、SourceRuntime 覆盖，ChapterProvider 加默认 resolveNovelImage，BridgeChapterProvider 覆盖，ViewModel 解析正文后对 IMAGE 块重写并复核。改 JS 桥前必须加载 security-audit，完成后跑 UrlPolicyTest 与 BridgeChapterProviderTest；ImageLoadConfig.sanitized() 会剥离 UA/Cookie，宿主出口统一注入，不要自行传 headers。
  - 分页分批异步化：当前 NovelReader 在组合阶段同步 paginate 整章，超长章会卡首帧。改为分批发布，但必须先解决「首屏页与 novelAnchor 恢复」的同步：rememberPagerState 的 initialPage 只在创建时生效，分批期间 snapshotFlow{currentPage} 的首发可能把锚点覆盖为第 0 页；要加「锚点所在页到达后才发射 PageChanged / NovelAnchorChanged」的门控。不要把 TextMeasurer 移到任意后台线程。
P2 导入闭环与增强：
  - 本地导入 UI 与 Work/Chapter 落库（LocalSourceScanner 的 ScannedManga 尚未映射为 work/chapter 表；App 未传 localFs，ReaderScreen.localFs 默认 null，本地归档图片暂不可读），达到 M4 Gate「本地书与在线书在书架同构展示」。
  - MARKDOWN 富文本、两端对齐精调、按书 scope 覆写设置；书签/划线/笔记（复用 bookmark.position 作为字符锚点）、全文搜索、仿真翻页（第三方 pagecurl 是 Android 库，先验证 CMP 兼容，否则自绘）。
P3 三端截图基线（docs/screenshots/ 目前无小说基线）、iOS 验证、长章性能基准、ReaderRoute.sourceKey 多源路由（M5）。

【硬约束（违反即停）】
- ADR-006 默认不新增 expect/actual；测量由 feature/reader 的 rememberTextMeasurer() 注入 TextMeasure 接口，不要让 core 层直接构造 TextMeasurer。
- ADR-005 漫画与小说共用一条管线，不新建第二套模型或阅读器；ADR-009 桌面不内嵌浏览器，正文绝不交给 WebView。
- C1 本体不预置任何站点/书源；C3 三端一致；cf_clearance 与 pairedUserAgent 绑定是红线。
- ViewModel 沿用现有 MVI（kmp-mvi 或 kotlin-project-state-management 二选一，不混用）；导航只用官方 Navigation3；SQLDelight 迁移必须可回滚。
- 产品名只落配置层、代码内中性命名；架构文档只改正文、不写版本号/变更史；不要覆盖 docs/reader-handoff.md。
- 不引入新第三方依赖（除非先做依赖可解析性矩阵并说明理由）；不改漫画路径的既有行为。

【交付要求】
- 小步提交式改动：每批先写单测，再跑相关模块测试，最后跑全量验收门。
- 更新 docs/novel-reader-handoff.md 的验证状态与检查清单、docs/novel-reader-plan.md §6 进度；如新增文档，登记到 docs/README.md。
- 追加 F:/Kurisa/.workbuddy/memory/<当天日期>.md 工作日志（append-only，只记有长期价值的事实）。
- 最终报告用中文，包含：改了哪些文件、每个未完成项的落地情况、验证命令与结果（用例数/失败数）、遗留风险与下一步建议。不要声称完成任何未经验证的项目。

【已知坑】
- HTML attribute 解析必须先跳过标签名（否则 `img src` 的 key 变成 "img src"）。
- korlibs `createZipFromTree()` 是 suspend；Ksoup `doc.body()` 非空；java.lang.Long / String.format 不是 commonMain API。
- 纯文本 EPUB 必须从章节列表移除扫描器生成的同路径漫画章节。
- ReaderScreenVm / ReaderContent 新增的 images/domains 参数有默认值，现有截图测试可直接调用。
```
