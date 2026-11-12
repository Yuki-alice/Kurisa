# 漫画阅读器交接文档

> 状态：真实源装配骨架、源管理页（列表/启用）与源安装器（粘贴/预览/落盘）已落地，
> 可编译、可测试、可运行；仓库订阅/文件选择与多源路由仍待下一轮。
> 架构基线见 `docs/architecture.md`，插件契约见 `docs/contract/`。

## 1. 本轮结论

漫画阅读器已从“状态机内核 + 原型 UI”推进到可实际阅读的模块：

- 分页（LTR / RTL / 纵向）、长条、横屏双页；
- 点按分区、双击/捏合缩放、章节过渡、目录、设置面板；
- 页级加载管线（并发、字节预算缓存、单页重试、下一章预取）；
- `comic.onImageLoad` 已接入真实取图链路；
- 阅读设置持久化 + Android/Desktop 数据库装配；
- 键盘/音量键翻页（桌面端确定可用，Android 音量键取决于系统拦截策略）。

验证结果：`desktopTest` 217 个测试全绿；Android debug APK 可构建。

## 2. 功能清单与关键文件

### 2.1 阅读内核（纯 Kotlin）

| 能力 | 文件 |
| --- | --- |
| 阅读状态机、章节导航、页请求模型 | `core/reader/.../ReaderStateMachine.kt` |
| 点按分区、双页展开等纯逻辑 | `core/reader/.../ReaderInteraction.kt` |
| 阅读设置仓库接口 | `core/reader/.../ReaderSettingsStore.kt` |

要点：

- `ChapterProvider.resolvePage()` 是 `onImageLoad` 的接线点，默认直通 URL，`BridgeChapterProvider` 覆盖为按源配置重写。
- `ReadingConfig` 包含模式、方向、缩放、点按区域、双页、页码、长条边距、主题、灰度、亮度遮罩、章节过渡。
- `buildSpreads()`：双页模式首封面单页，其后两页一组。
- `tapActionAt()`：RTL 物理左右互换，纵向模式按上下判定。

### 2.2 ViewModel 与页加载管线

文件：`feature/reader/.../ReaderViewModel.kt`

- 预加载窗口：当前页前 1 / 后 2；
- 并发：`Semaphore(3)`；
- 缓存：`ByteBudgetLru<ByteArray>`，默认 32 MB，跨章复用；
- 单页失败：`PageLoadState.Error` + `RetryPage(index)` 只重载该页；
- 下一章：临近结尾预取前 2 页；
- 进度：防抖 500 ms，真实时间戳；
- 章节切换：重建 Viewer，避免沿用上一章滚动/分页状态；
- 设置：进程内 StateFlow + 可选 SQLDelight 持久化。

### 2.3 阅读器 UI

文件：`feature/reader/.../Reader.kt`

- Paged：`HorizontalPager`（LTR/RTL）、`VerticalPager`（纵向）；
- Webtoon：`LazyColumn`，图片按真实宽高比，支持侧边距；
- 双页：横屏且开启双页时生效，RTL 下页序反转；
- 沉浸式：顶/底浮动菜单，翻页自动收起；
- 章节过渡卡片：上一章 / 下一章；
- 目录面板：高亮当前章，点击跳转；
- 设置面板：模式、方向、缩放、点按区域、主题、双页、灰度、页码、长条边距、亮度遮罩；
- 页级占位：加载中 / 失败点击重试；
- 键盘：方向键、PageUp/PageDown、空格、音量键、Menu。

### 2.4 `onImageLoad` 接线

| 层 | 文件 |
| --- | --- |
| 契约默认实现 | `source/bridge/.../SourceEngine.kt` |
| 源运行时真实调用 | `source/bridge/.../SourceRuntime.kt` |
| 章节桥接映射 | `source/bridge/.../BridgeChapterProvider.kt` |
| 图片抓取 | `data/images/.../ImageFetcher.kt` |

链路：

```
ReaderViewModel.ensureWindow()
  → ChapterProvider.resolvePage(workId, chapterId, url)
  → BridgeChapterProvider → SourceRuntime.imageLoadConfig()
  → ReaderPageRequest(url, rewriteUrl, headers, maxRetries)
  → LoadPageBytesUseCase → SourceImageFetcher
```

危险头（UA/Cookie）仍由契约层 `sanitized()` 剥离，宿主出口负责注入。

### 2.5 设置持久化与平台装配

| 内容 | 文件 |
| --- | --- |
| 表结构 | `data/db/.../ReaderSetting.sq` |
| 迁移 | `data/db/.../migrations/2.sqm` |
| 仓库实现 | `data/repository/.../ReaderSettingsRepository.kt` |
| Android 装配 | `app/android/.../MainActivity.kt` |
| Desktop 装配 | `app/desktop/.../Main.kt` |
| App 注入点 | `app/shared-ui/.../App.kt` |

- Desktop 数据库：`~/.kurisa/reader.db`；
- Android 数据库：`reader.db`（`AndroidSqliteDriver`）；
- 设置表按 `scope` 预留按书覆盖，当前固定 `global`。

## 3. App 注入契约

```kotlin
App(
    readerChapters = catalog.chapters,   // 真实源（首个 enabled 源，无源退化 Noop）
    readerDomains = catalog.currentDomains(), // enabled 源白名单并集
    readerProgress = progressRepository,  // 真实进度仓
    readerSettings = readerSettingsStore, // 持久化设置
    sourceCatalog = catalog,              // 源管理页：全量快照 + 启用/禁用 + 运行时失效
)
```

默认值全部为 Noop / 内存实现，保证主仓零预置源（C1）。

**当前缺口**：仓库订阅（index.json 拉取/签名 TOFU 绑定）与文件选择器（FileKit）
未做，安装入口目前只有脚本粘贴；多源按作品路由（`ReaderRoute` 携带 sourceKey）
需导航签名变更，随订阅同一轮落地。

## 4. 运行与验证

```bash
cd /workspace/Kurisa

# 全部桌面测试
./gradlew desktopTest --console=plain

# Android debug APK
./gradlew :app:android:assembleDebug --console=plain

# 单模块测试
./gradlew :feature:reader:desktopTest --console=plain
./gradlew :source:bridge:desktopTest --console=plain
```

低内存环境（约 6 GB 容器）建议：

```bash
./gradlew desktopTest --console=plain --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=768m" \
  -Dkotlin.daemon.jvmargs="-Xmx1g"
```

## 5. 环境基线

- JDK：25（Gradle 9.4.1 可运行；项目 bytecode target 17）
- Gradle：9.4.1（wrapper）
- Android SDK：`/workspace/android-sdk`
  - platform `android-37.0`
  - build-tools `37.0.0` / `36.0.0`
- `local.properties`：`sdk.dir=/workspace/android-sdk`（已 gitignore）
- 网络代理：Gradle 通过 `~/.gradle/gradle.properties` 的 `systemProp.*.proxyHost/Port` 生效
- iOS 目标需 macOS，Linux 下无法编译/测试

## 6. 已知限制与风险

1. **真实源未装配**：阅读器 UI 已就绪，但平台壳还没有源安装/选择链路，默认 Noop。
2. **Android 音量键**：Compose 焦点路径已处理；若 Activity 拦截音量键，需要在平台层转发。
3. **高级渲染未做**：自动裁白边、反色/棕褐滤镜、Webtoon 捏合缩放、宽页自动拆分。
4. **阅读闭环未做**：已读/未读、下载联动、书签。
5. **系统级阅读**：常亮、系统亮度、全屏系统栏隐藏未接。
6. **iOS 未验证**：需 macOS 与 Xcode 工程（`app/ios` 目前仍只有 README）。
7. **数据库迁移**：Desktop 开发库目前只做“补新增表”，完整 `Schema.migrate` 待接入。
8. **设置 scope**：当前固定 `global`，按书覆盖尚未启用。

## 7. 下一轮建议（按优先级）

### P0

1. 平台装配 `SourceRuntimeManager` → `BridgeChapterProvider`，让 `App(readerChapters=...)` 真正可用。
2. 源管理页接入安装/启用/禁用（`:feature:sources`）。
3. 完善 Desktop 数据库迁移（`Schema.migrate`）。

### P1

1. 自动裁边、反色/棕褐滤镜。
2. Webtoon 捏合缩放与双击缩放。
3. 已读/未读、下载联动。
4. 系统常亮/亮度/全屏。

### P2

1. 宽页自动拆分、双页中心距。
2. 书签/划线/笔记。
3. iOS 壳与三端截图基线。

## 8. 交接检查清单

- [x] `desktopTest` 231/0
- [x] `:app:android:assembleDebug` 成功
- [x] Android/Desktop 数据库与设置仓库已装配
- [x] `onImageLoad` 端到端接通
- [x] 分页 / 长条 / RTL / 双页 / 目录 / 设置面板可用
- [x] 真实源 `ChapterProvider` 装配（单活跃源；无源退化 Noop）
- [x] 源管理页列表/启用/禁用（安装器未做）
- [x] 源安装器（粘贴脚本 → 权限预览 → 二次确认 → 落盘入库 → 即装即用）
- [ ] 仓库订阅/文件选择器（下一轮 P0）
- [ ] 多源按作品路由（`ReaderRoute` 携带 sourceKey，下一轮 P0）
- [ ] iOS 构建验证（需 macOS）
