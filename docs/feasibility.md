# 技术栈可行性验证 · 执行清单

> 对应 `docs/architecture.md` 第 14 章。原则：先验四 target 可解析，再验行为一致；P0 全绿才开工，P1/P2 允许降级有主。
>
> 引擎绑定使用现成依赖（quickjs-kt，**不自研**）；CMP 按 1.12.2 **官方发布矩阵**锁定，直接调用官方 MD3 Expressive 组件，自绘层只补缺口。项目名 `Kurisa` 仅用于配置层，工程内一律用中性功能名（见 `architecture.md` 命名约定）。

## 0. 前置

- 空壳 App：单 `NavDisplay` + `rememberNavBackStack` + `ListDetailSceneStrategy`，route keys：`Shelf / Explore(sourceKey) / Search / Detail(workId) / Reader(workId, chapterId) / Sources / Downloads / Settings`
- targets：`android() + iosArm64 + iosSimulatorArm64 + jvm("desktop")`，`applyDefaultHierarchyTemplate()`（iosX64 不在验证范围）
- `gradle/libs.versions.toml` 锁定：**Kotlin 2.4.21 / Gradle 9.4.1 / AGP 9.2.1 / CMP 1.12.2（plugin + runtime/ui/foundation/material）/ material3 1.12.0-alpha03（官方矩阵默认，含 Expressive）/ Navigation3 1.2.0 / Koin 4.2.2 / quickjs-kt 1.0.18** + Ktor / SQLDelight；Decompose、Metro 已删；CMP 无官方 BOM
- 命令：`gradle :shared:compileAndroidMain :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinDesktop` 全过才算可解析（AGP 9 的 `com.android.kotlin.multiplatform.library` 下 Android 编译任务名是 `compileAndroidMain`；自定义 `jvm("desktop")` 的 sourceSet 没有 DSL 访问器，须 `getByName("desktopMain")`）

## 1. Navigation3 自适应 spike（P0）

```
N1 手机单窗格：[Shelf] -> add(Detail) -> removeLast()，断言一次渲染栈顶 1 个
N2 平板双窗格（>600dp）：[Shelf, Detail] 必须 ListDetailScene，窄屏退化单窗格
N3 状态恢复：配置变更/杀进程后 rememberNavBackStack 恢复同一 backstack
N4 深链接：外链 -> [Shelf, Detail]，分享 -> [Shelf, Detail, Reader]
N5 ViewModel 作用域：旋转/resize 后同目的地不重建，跨目的地隔离
```

依赖：CMP 侧 `org.jetbrains.androidx.navigation3:navigation3-ui:1.2.0`（runtime 由 `androidx.navigation3` 传递引入），按需 `lifecycle-viewmodel-navigation3`（随 CMP 1.12.2 发布矩阵）。

## 2. 依赖矩阵（逐行打勾）

> 判据说明：带 `[x]` 且注「行为待验」的项，只表示**四 target 依赖可解析 + 编译 / 符号冒烟通过**，后半段的行为断言尚未执行；M0 整体判绿以第 3 节为准。

- [x] P0 **quickjs-kt 1.0.18**（四 target 编译 + JVM 行为验证通过；三端一致性待验）：四 target 可解析 + `.js` 源多端 JSON 一致 + `memoryLimit = 32 MB` 生效 + `withTimeout` 中断 `while(true)` + 16KB 镜像加载 + `asyncFunction` Promise + `JsObject` 往返
- [x] P0 **CMP 1.12.2 官方矩阵 + 官方 Expressive 组件**（四 target 编译通过；截图基线待验）：plugin/runtime/ui/foundation/material = 1.12.2 + material3 = 1.12.0-alpha03；`MaterialExpressiveTheme` + 官方 FloatingToolbar / ButtonGroup / LoadingIndicator 三端截图一致，自绘层只补缺口；随官方矩阵升级
- [x] P0 Ksoup / XmlUtil / korlibs-encoding（可解析 + 冒烟通过；行为待验）：`Ksoup.parse/select`、`Base64.encode` 三端编译通过；同一 HTML fixture 选择器一致 + dispose 未验
- [x] P0 Ktor3 + serialization（可解析 + 冒烟通过；行为待验）：`ktor-client-core:3.6.1` + OkHttp(android) / Darwin(ios) / CIO(desktop) 三 engine 分 sourceSet 编译通过；白名单 / 重定向二次校验一致未验
- [x] P0 SQLDelight（可解析 + 冒烟通过；行为待验）：`runtime:2.4.1` + android-driver / native-driver / sqlite-driver 三端编译通过；migration 与 Room 小表对照未验
- [x] P0 ZoomImage + Sketch4 / Coil3（可解析 + 冒烟通过；行为待验）：`zoomimage-compose:1.7.0` + coil3/sketch4 桥 + `coil3:3.6.3` + `sketch4:4.7.0`；`ZoomState` / `SubsamplingState` / `ImageLoader` / `Sketch` 编译通过；超大图 OOM + Read Mode + 手势压测未验
- [x] P0 ComposePdfReader（可解析 + 冒烟通过；渲染待验）：实际坐标 `dev.nucleusframework:pdfium:159.0.8138.0`，覆盖 JVM / Android / iOS / Web，三端 variant 齐备，**无需启用「降级三端 actual」**；`PdfReaderState` 冒烟通过
- [x] P0 Compose WebView（可解析 + 冒烟通过；KCEF 待验）：`io.github.kevinnzou:compose-webview-multiplatform:2.0.1`，desktop / iosArm64 / iosSimulatorArm64 / iosX64 / release variants 齐备；`WebViewState` / `WebViewNavigator` 冒烟通过；桌面拦截器记已知降级
- [x] P0 **Koin 4.2.2**（可解析 + 编译通过；ViewModel 工厂待验）：运行时 DI，四 target 编译通过；不引入 Metro / kotlin-inject
- [ ] P1 Ketch：暂停/续传/重启 + download_task；备 DIY-Ktor
- [ ] P1 FileKit/AppDirs/io：选择/保存 + 根路径往返
- [ ] P1 KVault/KSafe 二选一 + 桌面加密文件
- [ ] P1 KMP WorkManager：Android/iOS，桌面常驻协程
- [ ] P2 MaterialKolor/kmPalette/Calf/Haze/Rinku/paging/DND/Markdown/RichEditor/Kim/cache4k：缺 desktop 即降级

## 3. 判绿标准

1. 真实 Venera 源三端 JSON 一致（复用 m0-spike fixture）
2. iOS 无 JIT entitlement 正常运行，且 quickjs-kt 的内存上限 / 超时中断 / 16KB so 加载三项通过
3. P0 全绿，P1/P2 降级有主
4. 官方 Expressive 组件三端截图一致
5. ZoomImage 无 OOM 且不与外层滚动冲突
6. KCEF 可初始化、PDF 矩阵确认

> 当前状态：第 3 条「P0 全绿」只完成到**依赖可解析 + 编译 / 符号冒烟**层；第 1/2/4/5/6 条及各 P0 后半段的行为断言均未执行，**M0 未判绿**。

不过即回退：quickjs-kt 不达标 -> 试其它 1.0.x 补丁版，再考虑 WASM 沙箱；material3 官方 alpha 线出现阻塞性回归 -> 锁到 1.12.2 矩阵的其它补丁版或等待官方更新，不自行改用 `1.9.0`（会丢失官方 Expressive）；Navigation3 API 大变 -> 锁版本 + 薄适配层只改一处；PDF 渲染不达标 -> 回到 `PdfRenderer / PDFKit / PDFBox` 三端 actual（库本身已覆盖三端，此处仅指渲染行为）；WebView 桌面缺能力 -> 系统浏览器 + deeplink。

## 4. M0 实测状态

在 `m0-spike/` 空壳工程（单 `shared` 模块，android / iosArm64 / iosSimulatorArm64 / desktop 四 target）上完成核心栈与 P0 依赖矩阵验证。当前基线：**CMP 1.12.2 官方发布矩阵（plugin/runtime/ui/foundation/material 1.12.2 + material3 1.12.0-alpha03）+ Koin 4.2.2 + 官方 MD3 Expressive 组件，自绘层只补缺口**。

### 已通过
- 工具链：Kotlin 2.4.21 / Gradle 9.4.1 / AGP 9.2.1 / CMP 1.12.2 可配置；官方矩阵 `material3:1.12.0-alpha03` 与 `runtime/ui/foundation:1.12.2` 四 target 编译通过。
- 四 target 编译全绿：`compileKotlinDesktop`、`compileAndroidMain`、`compileKotlinIosSimulatorArm64`、`compileKotlinIosArm64`，**无任何 Skiko 版本警告**（1.12.2 用 skiko 0.150.2，图片库绑 0.150.1）。
- 官方 MD3 Expressive：`MaterialExpressiveTheme` + `HorizontalFloatingToolbar` + `LoadingIndicator` 在 commonMain 四 target 编译通过；`material3:1.12.0-alpha03` 产物内含 `ButtonGroup` / `SplitButtonLayout` / `MotionScheme` / `WideNavigationRail` / FAB Menu / `FloatingToolbarDefaults` 等。自绘组件（shape morph 等）保留为补缺口层，一同编译通过。
- quickjs-kt 1.0.18：四 target 可编译；JVM 上跑通 `Network.get` Promise → `Convert.md5` → JSON 全链路；`memoryLimit = 32 MB` 实测为 33554432；`evaluationTimeoutMillis` + `withTimeout` 中断 `while(true)` 抛 `QuickJsInterruptedException`。官方组合下重跑行为验证仍全过。
- P0 依赖矩阵：全部库在 android / ios / jvm 三 target 可解析，无缺失 variant；分 sourceSet 的 Ktor 三 engine（OkHttp / Darwin / CIO）与 SQLDelight 三 driver（android / native / sqlite）均编译通过。
- 符号冒烟（commonMain 真实链接 klib / jar）：`Json`、`Clock`、`HttpClient`、`SqlDriver`、`Koin module`、`Ksoup.parse/select`、`Base64.encode`、`coil3.ImageLoader`、`Sketch`、`ZoomState`、`SubsamplingState`、`WebViewState`、`WebViewNavigator`、`PdfReaderState` 全部编译通过。
- DI：**Koin 4.2.2** 四 target 编译通过；不引入 Metro / kotlin-inject。
- Navigation3 `navigation3-ui:1.2.0` 可解析；AGP 自动安装 Build Tools 36.0.0；Kotlin/Native 2.4.21 自动下载。

### 实测得出的约束（已写入 architecture.md）
- **material3 必须显式写官方矩阵版本**：`org.jetbrains.compose.material3:material3:1.12.2` **不存在**；CMP Gradle plugin 的 `compose.material3` accessor 固定指向旧的 `1.9.0`（基于 Jetpack M3 1.4.0，无官方 Expressive 组件），**不随 plugin 版本走**。按 1.12.2 官方矩阵锁 `1.12.0-alpha03`（基于 Jetpack M3 1.5.0-alpha22，含官方 Expressive）；不要为追求"稳定线"自行降到 1.9.0，那会丢掉官方 Expressive。
- Android 编译任务名为 `compileAndroidMain`；自定义 `jvm("desktop")` 的 sourceSet 没有 DSL 访问器，须 `getByName("desktopMain")`。
- quickjs-kt 的 `memoryLimit` / `maxStackSize` / `evaluationTimeoutMillis` / `memoryUsage` 是 Kotlin 属性，不是 `setXxx`；`evaluate` 是成员函数。
- 宿主调用脚本必须用 top-level `await` 才能取到 Promise resolve 后的值。
- Navigation3 在非 JVM 平台需 `SavedStateConfiguration` + `SerializersModule` 注册 route 多态序列化。
- kotlinx-datetime 0.8.0 的 `Clock` / `Instant` 已迁到 Kotlin 2.4 的 `kotlin.time`；Android 端 `Clock.System` 直接 unresolved，正式工程应改用标准库或锁 0.7.x。
- Coil 3 的网络组件现名 `coil-network-ktor3`（`coil-network-ktor` 停在 3.0.0-alpha08）；`korlibs-encoding` 的真实 group 是 `com.soywiz`；PDF 库真实坐标为 `dev.nucleusframework:pdfium`，覆盖 JVM / Android / iOS，**无需启用「降级三端 actual」回退**；`PdfDocument` 是 internal，不作公开契约引用。
- **KCEF 仓库**：WebView desktop 的 KCEF 传递依赖 `gluegen-rt:2.5.0` / `jogl-all:2.5.0` 只发布在 `https://jogamp.org/deployment/maven`，Google Maven / Maven Central 都没有；缺该仓库连 `desktopRuntimeClasspath` 都无法解析（M0 实测），已写进 `settings.gradle.kts`。
- 本机 `ANDROID_HOME` 未导出，靠 `local.properties` 的 `sdk.dir` 解决；`platforms/android-37` 与 package id `android-37.0` 不一致仅产生无害警告。

### 未消除的风险
- **material3 alpha 线 API churn**（R5）：`1.12.0-alpha03` 属官方矩阵里的 alpha，升级由 Version Catalog 单点锁定 + `designsystem/theme` 薄封装吸收；跟随官方矩阵，不自选版本。
- **补缺口组件规范漂移**（R16）：官方组件优先，自绘层只覆盖官方未覆盖的形态，用 token 化 + Compose Unstyled 骨架 + 三端截图基线约束。
- **旧 Compose 库运行期二进制兼容**（R21）：WebView 2.0.1 基于 1.8.0、PDF 1.10.3、Koin 1.10.2、ZoomImage 1.12.0 编译，Gradle 取最高 1.12.2 后四 target 编译通过，但实际渲染前不能判定二进制兼容，M1 首个页面即跑三端截图基线。
- Skiko 版本漂移（R20）在 1.12.2 下已关闭：四 target 编译未出现任何版本警告。

### 未验（M0 尚未判绿）
- 行为一致性：真实 Venera 源四端 JSON 一致、同一 HTML fixture 选择器一致、Ktor 白名单 / 重定向二次校验一致、SQLDelight migration、ZoomImage 长图 OOM / Read Mode / 手势压测、PDF 渲染、KCEF 实际初始化。
- **官方 Expressive 组件三端截图基线**（M1 建立）。
- iOS 真机无 JIT entitlement 运行、16KB page size 镜像下 so 加载（仅静态确认四个 ABI 的 `.so` 的 `p_align=16384`）。
- P1 / P2 依赖（Ketch / FileKit / KSafe / KMP WorkManager / MaterialKolor / kmPalette / Haze / paging / DND / Markdown / RichEditor 等）尚未进矩阵。
