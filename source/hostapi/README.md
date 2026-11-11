# :source:hostapi

能力来源：**① 纯 Kotlin `commonMain`**（宿主注入的全局对象形状）。

`HostApi` 门面：Network / Html / Convert / UI / Utils / Storage / Novel
（见架构 4.5；`Network` 出口强制走 `:data:network` 的白名单，
`Html` 由 Ksoup 实现，`Convert` 纯 Kotlin——随源运行时落地）。
**插件运行时只能通过本接口访问宿主，不得持有 Repository / Database 引用**（架构 3.1）。
