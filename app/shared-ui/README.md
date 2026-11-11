# :app:shared-ui

应用装配层（全 App 单一 `NavBackStack` + 单一 `NavDisplay`，见 ADR-008）。

- 聚合 8 个 feature 的 route key；导航即 backstack 列表 `add/remove`。
- 自适应（`ListDetailSceneStrategy`）与深链接（Rinku）随 M5 接入；
  当前单窗格渲染栈顶（手机行为），可单测（断言 backstack 内容）。
- iOS framework 唯一出口（其余模块 `implementation`，不污染 Swift 头文件）。
