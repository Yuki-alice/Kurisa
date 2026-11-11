# :source:bridge

能力来源：**② KMP 库**（`quickjs-kt` 提供三端 QuickJS 引擎与中断 API，见 ADR-003）。

实例封装、双层桥（同步 `sendMessage` 兼容 + 异步 `__hostCall`）、能力白名单、
隔离（每源独立 runtime + 串行 `SourceDispatcher` + `memoryLimit = 32 MB`）。
M1 的 `ContractHarness` 已验证链路形状；真实源兼容与 HostApi 接线随源运行时落地。

> 安全边界：QuickJS context ≠ 沙箱（ADR-003）；真正边界是白名单与隔离，
> 能力出口强制（见 `:data:network` 与 `:source:hostapi`）。
