# 本地书导入选择器 · 依赖可解析性矩阵（M4 P2-1 前置）

> 依据硬约束：引入新第三方依赖前必须先做依赖可解析性矩阵并说明理由。
> 本轮只做矩阵与方案，不引入依赖、不写导入 UI（入口需书架/M5 接入点）。
> 联网调研时间：2026-11-13；直连抓取受限，以下事实来自 Maven Central 索引页与官方文档摘要（等级 B），标有“待验证”的需接入后实测。

## 1. 结论

- 首选 **FileKit**（`io.github.vinceglb:filekit-compose` + `filekit-core`，最新 0.17.0）：
  Compose Multiplatform 一套 API，Android / iOS / JVM（含桌面三 OS）原生对话框，
  支持文件单选/多选 + 目录选择 + 扩展名过滤，正好覆盖“选书（txt/epub/cbz/zip/pdf/目录）→ 拷贝 `books/`”链路。
- **暂不引入**：导入 UI 的展示位在书架（M5 存根），现在引入 picker 也无处挂载；
  待书架列表与 `ReaderRoute` 入口就绪后，按 §4 接入（预计 1 个版本目录条目 + 1 个 feature 内调用点）。

## 2. 可解析性矩阵

| 项 | 结论 | 依据 |
| --- | --- | --- |
| 发布仓库 | Maven Central，group `io.github.vinceglb` 真实存在（`filekit-core` / `filekit-compose` artifact 页可查，0.16.0/0.17.0 均有 release 记录） | central.sonatype.com 索引〔B〕 |
| 本仓连通性 | 可直连 Maven Central（korlibs/ksoup/xmlutil 同源解析成功，无需额外镜像） | 本机多轮构建实测 |
| 目标覆盖 | Android / iOS / JVM（含 macOS/Windows/Linux）三端全覆盖，与本项目 target 集合一致 | 官方 platform-support 矩阵 + klibs.io 三端徽标〔B〕；iOS klib 随主 artifact 发布为 FileKit 既有发布惯例，**接入后必跑四 target 编译门验证** |
| 版本目录接入 | `filekit = "0.17.0"` + `filekit-compose = { module = "io.github.vinceglb:filekit-compose", version.ref = "filekit" }`，与现有 `ksoup`/`korlibs-*` 条目同形 | libs.versions.toml 惯例 |
| API 面 | `rememberFilePickerLauncher(type = FileKitType.File(extensions = listOf("txt", "epub", "cbz", "zip", "pdf")))` 选文件；`rememberDirectoryPickerLauncher()` 选目录；`PlatformFile.readBytes()` 后自行拷贝 | 官方 file-picker/quickstart 文档〔B〕 |
| 与现有管线衔接 | picker 返回路径 → 拷贝到 `books/` → `LocalLibrarySync.sync()` 入库 → `local:` 路由打开（P2 已落地，无需改动） | 本轮之前 P2 落地 |

## 3. 风险（待验证）

1. **iOS 自定义类型可见性**：社区 issue 记载 iOS 上 `FileType.File()` 对 `.bin` 等非常见类型可能不可见（Android 正常）；
   我们的 txt/epub/cbz/zip/pdf 同属非常见类型，**必须在 macOS 上真机/模拟器验证**，必要时改用目录选择或 UTI 声明兜底。
2. **Android SAF**：11+ 分区存储下目录选择需持久化 URI 权限；文件选择走系统 picker 无此问题（优先文件选择）。
3. **体积**：FileKit 拉起各端原生桥，APK/IPA 增量待接入后实测（基线对比）。
4. **API  churn**：FileKit 处 0.x 线，版本目录单点锁定 + 薄封装（沿 ADR-004 纪律）。

## 4. 实装结论修正（2026-11-13 接入验证后更新，等级 A：本仓实测）

- **坐标勘误**：矩阵初版写的 `filekit-compose:0.17.0` 不存在——该产品线停在 0.8.8；
  0.17.0 实际 artifact 是 `filekit-dialogs-compose`（与 `filekit-core` 同版本线，官方 README 主推）。
- **接入失败**：`filekit-dialogs-compose:0.17.0` 可解析、desktop/Android 可编译，
  但 `PlatformFile.name/readBytes/size` 等 expect 成员在 commonMain 不可见
  （类本身可见、成员全部缺失；JVM jar 内字节码齐全，系 common 元数据消费问题）。
- **决议：不引入 FileKit**，改用平台原生选择器 + 接口注入：
  - `feature:shelf` 定义 `PickLocalFiles`（`suspend () -> List<Pair<文件名, 字节>>`），UI 只管按钮与展示；
  - Desktop 用 `java.awt.FileDialog`（多选 + 扩展名过滤），Android 用 SAF `OpenMultipleDocuments` + `ContentResolver`；
  - suspend 桥天然可测（fake picker 注入），原生对话框本身不可在无头 CI 弹出（与 FileKit 方案同等限制）；
  - iOS 暂传 null（无导入入口，待 macOS 验证 UIDocumentPicker 后补）。
- 本文件 §1–§3 保留为选型过程记录；§4 草案作废，以本节为准。

## 5. 导入 UI 实施（已落地，2026-11-13）

1. 书架页「本地书」分区 + 「导入」入口（`ShelfScreen`，回调均有默认空实现）。
2. 平台壳注入 `PickLocalFiles`：Desktop FileDialog / Android SAF；读字节后调 `LocalBookImporter.import`（净化文件名 → 同名目录归档 → 写盘 → upsert，幂等）。
3. App 层导入后重跑 `LocalLibrarySync.sync()` + 刷新列表；点书经 `readerChapters`（含 `local:` 路由）定位首章进 `ReaderRoute`。
4. 验收：导入逻辑单测（内存 Vfs + 真库）+ 书架 UI 单测（列表/打开/导入接线）+ 全量 `desktopTest` + Android 构建；iOS 选择器待 macOS 验证。

1. 书架页“导入本地书”入口 → `rememberFilePickerLauncher` 选文件（扩展名白名单同上）。
2. 拷贝到平台 `books/` 目录（Desktop `~/.kurisa/books`、Android `filesDir/books`，双端壳已建目录）。
3. 调 `LocalLibrarySync.sync()` 增量入库（已落地，失败静默）。
4. 书架 `local:` 列表展示 → 详情 → `ReaderRoute` 打开（路由已落地）。
5. 验收：新增桌面端单测（内存 Vfs 拷贝 + sync 行断言）+ 全量 `desktopTest` + Android 构建；iOS 选文件实测（需 macOS）。
