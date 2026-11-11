# Venera 老源转 v4 迁移小抄（DRAFT，随引擎实现更新）

> 给重写源的人看的一页纸。老形状在左，新写法在右；行为参考可以，代码不准抄（GPL）。
> 相关：ADR-011、docs/contract/source.d.ts、docs/contract/source-v4.d.ts。

## 1. 无状态化（最重要）

- 老：constructor 里记 this.baseUrl，init 里挂实例方法，调用靠 this 串状态。
- 新：每次调用参数传全（keyword/page/workId/chapterId），状态只走 loadData/saveData。
- 为什么：单发模型下每次求值重建实例，老写法每调用重跑 init，小说连读即限流。

## 2. 搜索分页

- 老：search.load 与 search.loadNext 二选一，宿主按 hasSearchLoad 路由。
- 新：只写 search.page(keyword, options, pageOrNext)，宿主侧路由。

## 3. 目录形状

- 老：chapters 可为 Map（id 映射标题或对象）或数组。
- 新：统一数组 [{ id, title }]，ordinal 按数组序。Map 写法垫片期兼容，v4 新源请用数组。

## 4. HTML 解析

- 老：new HtmlDocument(html) … querySelectorAll … 别忘 dispose（忘即 disposed-or-unknown）。
- 新：Html.list(html, css, map) / Html.text(html, css) / Html.attr(html, css, name)，内部自动 dispose。

## 5. 错误

- 老：throw 'Login expired' 之类字符串，靠子串分类（大小写不敏感，顺序敏感）。
- 新：throwLoginExpired() / throwNotFound() / throwRateLimited()，分类规则不变。

## 6. 设置

- 老：loadSetting 返回字符串或布尔混杂，switch 请按 == 'true' 判断（宿主统一字符串化）。
- 新：同上，v4 类型已锁死 string | null。save_data 禁写 setting 前缀键。

## 7. 图片

- 老：onImageLoad 可回对象，cookie/user-agent/host 等头会被宿主剥离（白名单出口复核）。
- 新：同上；只给业务头（Referer/Origin）。文内图走 onImageResolve（v4）。

## 8. 密码学现状

- md5/hmacString(sha256)/Base64/UTF-8：可用。
- GBK/SHA-1/512：v4 转正中（U1 前请避开）。
- AES/RSA：未定（U1），不要依赖空串行为——转正或明确报错，二选一定案后本节更新。

## 9. 网络

- 只开 GET/POST；PUT/PATCH/DELETE 明确不支持。请勿在源里拼 method 透传。
- 域名必须在白名单内（含重定向逐跳复核），https 降 http 直接拒。

## 10. 提交前自查

- tsc --noEmit 通过；无 this 挂载；chapters 为数组；Html 走 helper；错误走 helper；
- settings 有 default；onImageLoad 不带 cookie/UA；无 import（ModuleLoader 全拒）。
