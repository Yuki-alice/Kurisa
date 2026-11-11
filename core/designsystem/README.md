# :core:designsystem

能力来源：**② KMP 库**（CMP 官方 material3 + 自绘补缺口，见 ADR-004）。

主题唯一入口 `AppTheme` + 全局 tokens。官方 Expressive 组件
（`MaterialExpressiveTheme`/`FloatingToolbar`/`ButtonGroup` 等）直接调用，
`expressive/` 层只补官方未覆盖的形态——M5 落地，本模块现为可编译骨架。
