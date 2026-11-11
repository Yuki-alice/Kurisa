# :core:platform

能力来源：**③ 平台 actual（预留，当前仅接口）**。

`AppDirectories` / `PlatformInfo` 为唯一平台抽象。
实现由各端经 Koin 注入（M6 落地；桌面常驻协程、iOS Keychain/BGTask 细节见架构 7.2）。
**禁止在 `actual` 里写业务逻辑**（见架构 7.5）。
