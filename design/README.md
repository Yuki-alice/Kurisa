# Kurisa · Material 3 设计原型

以参考应用 **Komikku** 的页面结构与交互为骨架，把组件与视觉语言替换为
**Material 3 Expressive**（`material3 1.5.0-rc01` 线），落到 Kurisa 的产品定位上。

- `index.html` — 设计说明页：设计基准、颜色、组件、13 屏高保真、响应式、Compose 映射
- `prototype.html` — 390×844 可点原型：5 个 tab、书架 → 详情 → 阅读器、多选、阅读设置弹层、搜索、下载
- `assets/` — token、组件、屏模板、原型运行时，以及本地化字体与图标（离线可用）

```bash
open "Kurisa/design/index.html"
open "Kurisa/design/prototype.html"
```

---

## 1. 设计基准：骨架照搬，组件换 M3E

| 层级 | 参考应用 | 本原型 |
| --- | --- | --- |
| 底部导航 | 5 目的地（书架/更新/历史/浏览/更多），80dp `NavigationBar` | 同 5 目的地，**`ShortNavigationBar` 64dp**，指示器 56×32，选中标签 secondary |
| 书架 | TopAppBar（标题 + 数量胶囊 + 搜索/筛选）→ 可滚动分类页签 → 自适应封面网格 | 同结构；封面 3:4（项目 `Tokens.coverAspect = 3f/4f`）、12dp 圆角、网格间距 11dp |
| 详情 | 模糊封面背景 + 居中封面 + 标题/作者/状态 + 操作行 + 摘要 + 标签 + 章节列表 + FAB | 同结构；FAB 用 56dp `SmallExtendedFloatingActionButton`，操作行 5 等分 |
| 阅读器 | 黑场 + 透明顶栏 + 底栏图标 + 章节滑杆 + 页码 | 同结构；底栏图标 on-surface-variant、选中项 secondary-container；滑杆换 **M3E 4×44 手柄**，页码等宽数字 + 描边 |
| 多选 | 长按进入选择态，封面压暗 | 同交互；底部升起 **`HorizontalFloatingToolbar`**（64dp） |
| 更多 | Logo header + 开关行 + 分组设置行（24/12 内边距） | 同结构；分组标题 11.5/600 primary |

**只替换这些，不动操作路径**：长按多选、区间选择、下拉刷新、章节筛选弹层、
双击缩放、左右点按分区，全部保留。

---

## 2. 设计 token

**颜色**（HCT 六组色调板 → 48 角色，明暗两套）

| 组 | 种子（hue/chroma） | 浅色关键值 | 深色关键值 |
| --- | --- | --- | --- |
| primary | 268.5 / 63 | `#0158CA` | `#B0C6FF` |
| secondary | 268.5 / 18 | `#565E74` | `#BEC6E0` |
| tertiary | 145.6 / 52 | `#1E6D25` | `#89D984` |
| neutral | 268 / 4 | `#FEFBFF` | `#1B1B1F` |
| neutral-variant | 268 / 8 | `#E3E2EA` | `#45474E` |
| error | 25 / 84 | `#BA1A1A` | `#FFB4AB` |

primary 的 tone40/80 直接命中参考应用的 `#0058CA` / `#B0C6FF`。
面积分配：surface / container / containerHigh 约 70%，primary 约 12%（主操作、选中、链接），
tertiary 8%（进度条、已下载），其余为描边与错误色。

**字阶**（M3 15 槽 + 强调）：`titleLarge 22/28`（顶栏、标题）、`titleSmall 14/20`（网格标题）、
`bodyMedium 14/20`（列表正文）、`bodySmall 12/16`（元信息）、`labelLarge 14`（按钮）、
`labelMedium 12`（导航标签）。强调不换字号，只用 600 字重（M3E `*Emphasized` 的近似）。

**形状**：封面/卡片 12dp（`CornerMedium`）、弹层顶部 28dp、chip 8dp→全圆（选中态参与表达）、
FAB 16dp、按钮全圆。间距 8dp 栅格（4/8/12/16/24/32），网格 11dp、页边距 12dp。

**动效**：空间位移用过冲弹簧（CSS `cubic-bezier(.32,1.36,.56,1)` 近似
`MotionScheme.expressive().defaultSpatialSpec()`），颜色/透明度用无过冲的
`cubic-bezier(.2,0,0,1)`；阅读器 chrome 200ms slide / 150ms fade（照
`docs/komikku-reader-replication.md` 的复刻规格）。

---

## 3. 界面（13 屏）

| # | 屏幕 | 要点 |
| --- | --- | --- |
| S01 | 书架 | 5 目的地短导航；分类页签不带计数；封面内叠未读角标、进度条、续读圆钮 |
| S02 | 书架 · 多选 | 顶栏换「已选 3 项」，封面 3dp primary 描边，底部浮动工具条 |
| S03 | 更新 | 按「今天 / 昨天」分组，组头数量胶囊，行右侧逐章下载 |
| S04 | 历史 | 同一行组件 + 3dp 进度 + 百分比 |
| S05 | 浏览 · 源 | 40dp 圆形字母图标、版本/语言/类型、开关；未签名源顶部 errorContainer 警告 |
| S06 | 作品详情 | 模糊封面 + 双层渐变、196×261 封面、5 操作行、摘要 3 行、章节列表、FAB |
| S07 | 阅读器 | 纯黑画布、透明顶栏、底栏 primary tint、章节滑杆 4×44、页码 `8 / 24` |
| S08 | 阅读设置 | ModalBottomSheet，三页签，FilterChip 组，亮度滑杆，开关 |
| S09 | 小说阅读器 | 同一外壳，正文宋体 16.5/30，划线用 primary 10% 底 |
| S10 | 搜索 | 48dp 搜索框、最近/热门 chip、结果带来源徽标 |
| S11 | 下载 | 摘要卡 + 波浪进度（M3E `LinearWavyProgressIndicator`）+ 暂停/继续 |
| S12 | 更多 | Logo header + 开关行 + 分组设置行 |
| S13 | 书架 · 深色 | surface `#1B1B1F`、container `#1F1F23`、primary `#B0C6FF` |

另有 **live 自适应 demo**（index.html §05）：滑杆拖拽 390–1100dp，导航按官方窗口类切换
（ShortNavigationBar → NavigationRail 80dp → WideNavigationRail 240dp + 336dp 详情副窗格），
网格 3 / 4 / 3–4 / 4 列，边距/沟槽 16/8 → 24/16 → 24/24，全部由 container queries 真实重排。

---

## 4. 组件清单

**使用**：`ShortNavigationBar`、`TopAppBar`、`PrimaryScrollableTabRow`、`FilterChip`/`AssistChip`、
`Button`/`FilledTonalButton`/`OutlinedButton`/`TextButton`、`IconButton` 40dp、
`ButtonGroup`(connected)、`FAB` 56dp / `SmallExtendedFloatingActionButton`、
`HorizontalFloatingToolbar`、`ModalBottomSheet`、`Slider`(4×44)、`Switch` 52×32、
`LinearProgressIndicator`、`LinearWavyProgressIndicator`、`Badge`、`List item`、
`WideNavigationRail`。

**未使用（已淘汰或不再推荐）**：`NavigationBar` 基线 80dp、`BottomAppBar`、`SegmentedButton`、
`MediumTopAppBar`/`LargeTopAppBar`、`SmallFloatingActionButton`(40dp)、`TopSearchBar`、
`ModalNavigationDrawer`。

---

## 5. 落地到 Compose

`core/designsystem` 目前只有 `Theme.kt`。原型按 M5 计划给出三层落点：

| 原型 | Compose |
| --- | --- |
| `--md-*` 48 角色 | `MaterialExpressiveTheme(colorScheme = …)`，MaterialKolor / HCT 种子生成 |
| `.m3-navbar` 64dp | `ShortNavigationBar` + `ShortNavigationBarItem` |
| `.m3-tabrow` 48dp | `PrimaryScrollableTabRow` |
| `.lib-grid` | `LazyVerticalGrid(Adaptive(128.dp))`，3:4 封面 12dp |
| `.sel-toolbar` | `HorizontalFloatingToolbar` |
| `.det-hero` | `Modifier.blur` + 竖向渐变 Brush |
| `.m3-chip` | `FilterChip(selectedShape = CircleShape)` |
| `.m3-fab.ext` | `SmallExtendedFloatingActionButton` |
| `.m3-sheet` | `ModalBottomSheet` |
| `.m3-wavy` | `LinearWavyProgressIndicator(progress = { … })` |
| `.m3-slider` | `Slider`（M3E 手柄） |
| 动效 token | `MaterialTheme.motionScheme`（spatial / effects × default / fast / slow） |

详见 `index.html` 第 06 节的完整表格。

---

## 6. 约束

- 封面、漫画页、书名与文案均为程序生成的合成素材，不含第三方内容（符合 C1）。
- 字体：Roboto（OFL）、Material Symbols Outlined（Apache 2.0），已本地化为 woff2。
- 颜色可复现：`tools/generate_tokens.py`（需 `material-color-utilities`）由 HCT 种子重算
  `assets/tokens.css` 与 `tokens.js`。
- 原型覆盖的是「设计」，不代表仓库实现现状：除阅读器与源管理外多数屏幕仍为占位
  （见 `docs/comic-e2e-plan.md`）。
