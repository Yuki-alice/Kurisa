# app/ios（Xcode 壳，M5/M6 落地）

iOS 仅侧载分发（AltStore / TestFlight / 企业签），与 Android/Desktop 共用
同一套 `commonMain`（见架构 1.4/4.9/C5）。

## 落地步骤（M5 自适应 UI 就绪后执行）

1. Xcode 新建 iOS App 工程（Bundle id 后缀 `Kurisa`，见命名约定）；
2. 以 CocoaPods 或 SPM 集成 `:app:shared-ui` 的 `SharedUI.framework`
  （`./gradlew :app:shared-ui:linkDebugFrameworkIosSimulatorArm64` 验证链接）；
3. `ContentView` 经 `ComposeUIViewController { App() }` 挂载
  （KMP-ComposeUIViewController，见架构 3.2）；
4. `Info.plist`：后台模式（BGTaskScheduler 声明）、文件访问描述；
5. 签名：TestFlight 90 天有效期 / 企业签组织内 / AltStore 刷新（见 4.9）。

本目录在工程落地前仅保留本说明，不放半成品 Xcode 文件。
SKIE + KMP-NativeCoroutines 互操作优化随 M5 一并接入。
