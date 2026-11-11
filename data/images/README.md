# :data:images

能力来源：**① 纯 Kotlin + ② KMP 库**（Ktor 经 `:data:network` 出口）。

图片抓取层（渲染器无关）：请求/白名单/鉴权头/`ImageLoadingConfig` 兼容
（Venera `onImageLoad` 形状：改 url / headers / data / `onResponse` 字节变换 /
`onLoadFailed` 重试，见架构 6.2）+ 分块预算数学。
解码与缩放交给 `:feature:reader` 的 Coil + ZoomImage；
本模块永不碰 Compose（映射在 `:data` 边界收口）。
