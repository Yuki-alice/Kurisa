# 真实漫画样例库

给「本地导入 → 书架 → 详情 → 阅读器」这条链路做端到端验证用的真实漫画库。
不是生成的占位图——是**已发表作品的原页扫描**，所以截图能直接当效果确认看。

## 内容

| 目录 | 章节 | 页数 | 形态 |
|---|---|---|---|
| `Pepper & Carrot EP06/` | `EP06 - The Potion Contest.cbz` | 10 | CBZ 归档 |
| `Pepper & Carrot EP08/` | `EP08.cbz` | 3 | CBZ 归档 |
| `Pepper & Carrot EP14/` | `EP14.cbz` | 3 | CBZ 归档 |
| `Pepper & Carrot EP06 目录版/` | `第01话/` | 4 | 图片目录 |

两种形态都放，是因为本地库扫描对「归档」和「图片目录」走两条不同的代码路径，
只测其中一条会让另一条长期无人验证。

页面均为 992×1401，JPEG 质量 85（原始素材是 PNG，为控制仓库体积转码，内容未裁剪）。

## 来源与许可

- 作品：[Pepper & Carrot](https://www.peppercarrot.com/)（中文译名：胡椒与胡萝卜）
- 作者：David Revoy
- 许可：**CC-BY 4.0**（署名即可自由使用、修改、再分发）
- 素材来源：作者在 GitHub 的分发仓库
  - [Deevad/peppercarrot_ep06_translation](https://github.com/Deevad/peppercarrot_ep06_translation)
  - [Deevad/peppercarrot_ep08_translation](https://github.com/Deevad/peppercarrot_ep08_translation)
  - [Deevad/peppercarrot_ep14_translation](https://github.com/Deevad/peppercarrot_ep14_translation)

按 CC-BY 4.0 要求保留署名：**漫画 © David Revoy，CC-BY 4.0**。

## 怎么用

桌面端启动后点「选择本地目录」，选中本目录即可——书架会列出四部作品，封面就是真实页面。

自动化测试 `LocalComicWalkthroughTest` 会把这个目录复制到临时目录再扫描，
不写仓库、不依赖网络。

## 为什么不用生成图

早期版本用 Java2D 画「白底 + 蓝条 + 色块」当漫画页。问题不是好不好看，而是
**它证明不了任何事**：4 种颜色的图走不通解码器、暴露不了缩放/配色/裁边问题，
截图也没法给人看。真实素材直接把「渲染链路是否真的通」变成肉眼可判的事实。
