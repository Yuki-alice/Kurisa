# Kurisa 交接文档（换设备开工用）

> 基线：`main` @ `34b8111`（源运行时 bridge 落地），三端全绿
> （desktop 136 / android-host 124 / iOS-sim 124，零失败）。
> 上次验证：2026-11-11 本机；CI 暂无（已投票延后），一切以本地验证为准。

## 1. 新机器开工（按序）

1. `git clone https://github.com/Yuki-alice/Kurisa.git`（分支只有 `main`）。
2. 装 **JDK 21**（本机用 Corretto 21.0.12 LTS；Gradle 9.4.1 要求 17+，AGP 9 要求 17+，统一 21 最省心）。
3. 装 Android SDK：`platforms;android-37` + `build-tools;37.0.0` + `platform-tools` + `emulator`（要跑模拟器再加对应 `system-images`）。
   项目根建 `local.properties`（**不入库**，自己写）：
   `sdk.dir=/Users/<你>/Library/Android/sdk`
4. Gradle wrapper 会自己下（`gradle-9.4.1-bin.zip`；本机配的是腾讯镜像，国外机器改回官方 `services.gradle.org`）。
5. 首次构建极慢（依赖约数 GB，无代理会更久），先跑：
   `./gradlew :app:android:assembleDebug`
6. 全量验证（约 2–10 分钟，看机器）：
   `./gradlew desktopTest testAndroidHostTest iosSimulatorArm64Test :app:android:assembleDebug`
   iOS Ruse 需要 Xcode（framework 链接任务：`:app:shared-ui:linkDebugFrameworkIosSimulatorArm64`）。

## 2. 工程地图（28 目录，约 6900 行 Kotlin，31 个单测文件）

| 层 | 模块 | 一句话 |
|---|---|---|
| core | model / common / reader / designsystem / platform | 模型+调度器/Debouncer/阅读状态机/主题占位/纯接口 |
| data | db / network / repository / download / localimport / cache / images | SQLDelight/白名单出口/仓/PageRange下载/魔数识别/LRU/图片抓取 |
| source | contract / registry / hostapi / bridge | 冻结契约/源安装验签/Host七接口/QuickJS引擎 |
| feature | shelf/explore/search/detail/sources/downloads/settings/reader | 只有 reader 有逻辑，其余全是路由占位 |
| app | shared-ui / android / desktop / ios(仅README) | 单一导航栈/Android壳/桌面壳/iOS落地说明 |

依赖铁律：feature 禁止直调 bridge；data 不碰 UI；契约冻结在 `:source:contract`（M1 评审结论）。

## 3. 铁律（都是拿真金白银的调试时间换的）

1. **`advanceTimeBy` 必须推过期限**：本工程虚拟时钟是严格小于语义，`delay(500)` 要用 `499/+5` 两段断言，恰到期（+1）不触发。
2. **Native 下没有 `String.toByteArray()/Charsets`**：编码用 `:core:model` 的 `toUtf8Bytes()`，测试自备 ASCII 编解码。
3. **`Dispatchers.Main` 在单测里是 TestMainDispatcher 包装**：永远别用 `===` 比较它；行为（分发+延迟委托）正常。
4. **引擎是单发模型**：quickjs 构造器私有、`quickJs{}` 阅后即焚——跨调用状态必须经 loadData 落 KV，禁止假设 JS 全局常驻。
5. **C1**：本体零站点 URL/解析逻辑/预置源；产品名只落配置层；路由新增必须同步注册 `appSerializersModule`（有单测锁门）。
6. **提交纪律**：`git push origin main` 直接推（确认远端 200 即同步）；commit 信息写清"做了什么+实录"。
7. **改 `source:*`/桥/Cookie/白名单前先加载 `security-audit`**（AGENTS.md §2 硬规则），另加 `kotlin-kmp-code-review` + `kotlin-data-kmp-data-layer`。

## 4. 下一步队列（按已投票顺序）

1. **源管理页面 + 真实源 E2E**：registry 只有数据层，缺安装/更新/禁用 UI；装一个真实源（如 manga_dex）后把阅读器接到 `BridgeChapterProvider`，删 `NoopChapterProvider`。
2. **M3 真机 hardening  backlog**：捏合手势（adb 做不出双指）、超大图 OOM 实测、状态栏 edge-to-edge。
3. **工程地基**（投票延后，非紧急）：CI workflow、Koin 接线（现全手写 Factory）、SQLDelight `verifyMigrations` 快照、i18n（现硬编码中文）。
4. **永不复活**：`build-logic` 约定插件（AGP 9 marker 无解已回退）、m0-spike（历史在 git 里）。

## 5. 已知坑位速查

- 模拟器 System UI 偶发 ANR（与 app 无关，点 Wait 继续）；`adb input tap` 坐标以 `uiautomator dump` 为准，目测偏 50px 级。
- `CoilZoomState` 是幽灵类型（桥接签名残留，四个产物 jar 均无定义）——`CoilZoomAsyncImage` 全默认参数绕行。
- `:app:android` 必须是纯 application 模块（AGP 9 禁 KMP+application 同模块）；`kotlin.android` 插件已死（内置 Kotlin）。
- `rememberNavBackStack` 必须带 `SavedStateConfiguration`（无参重载不存在）。
