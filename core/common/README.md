# :core:common

能力来源：**① 纯 Kotlin `commonMain`**（零平台依赖）。

调度器门面、重试封装、日志门面。平台差异不得进本模块；
需要平台能力时走 `:core:platform` 的接口注入（ADR-006）。
