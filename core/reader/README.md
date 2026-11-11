# :core:reader

能力来源：**① 纯 Kotlin `commonMain`**（零平台依赖、零 Compose 依赖）。

`ReaderStateMachine`（外层状态机，架构 6.1）与分页/排版抽象。
单页渲染器（ZoomImage）与图片加载器在 M3 接入；本模块只定状态形状与流转，
UI 只渲染状态（`ReaderViewModel` 订阅本机，见 ADR-007）。
