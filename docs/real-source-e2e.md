# 真实源兼容性验证（本地 E2E）

> 对应 `HANDOFF.md` §4 队列第 1 项的后半：装一个真实源，验证插件引擎主链路。
> 本文只记录**验证方法与结论**；不含任何站点 URL、源脚本或解析逻辑（C1）。

## 1. 合规边界

- 参考源脚本来自本地只读目录 `.workbuddy/refs/`（浅克隆，已 gitignore，GPL-3.0）。
  只用于本机加载与调用，**不复制进代码库、不分发、不预置**。
- 域名白名单由运行者通过命令行注入，测试代码里没有任何站点地址；
  网络由 Ktor `MockEngine` 拦截，验证过程不访问外部站点。
- 测试位于 `:source:bridge` 的 `desktopTest`，未提供 refs 目录与域名时自动跳过，
  CI 与其它机器零影响。

## 2. 运行方式

```
./gradlew :source:bridge:desktopTest \
  -Dkurisa.refsDir=<本地 refs 目录> \
  -Dkurisa.e2eDomains=<由运行者声明、与脚本元数据一致的域名白名单，逗号分隔>
```

Gradle 把这两个系统属性转发给测试 JVM（`source/bridge/build.gradle.kts`）；
缺省时测试打印 SKIP 并正常结束。

## 3. 验证范围与结果

| 环节 | 走到的宿主能力 | 结果 |
| --- | --- | --- |
| 加载/实例化 | 类发现、ES2022 class fields、`init()`、单发模型 | 通过 |
| `describe()` | 只读实例字段 → `SourceDescriptor`（能力位/设置/翻译） | 通过，探测出 8 个能力位、1 项声明式设置 |
| 搜索 | `defaultSearchOptions` → `search.load` → `fetch` polyfill → 域名白名单出口 | 通过，返回列表结构 |
| 章节正文 | `comic.loadEp` → `fetch` + `loadSetting('image_quality')` | 通过，产出图片 URL 列表 |
| 归一化 | `BridgeChapterProvider` → `ChapterPayload.Images` | 通过 |
| 图片配置 | `comic.onImageLoad` → `ImageLoadConfig`（Referer/Origin 保留、Cookie/UA 剥离） | 通过 |
| 设置 | `settings` 声明 default + 用户值覆盖 → `setting:` KV 命名空间 | 通过 |

实测输出（本机）：`name=MangaDex capabilities=[SEARCH, EXPLORE, CATEGORY, COMIC,
IMAGE_LOAD, ACCOUNT, SETTINGS, TRANSLATION] requests=2 settings=1 images=1`。

## 4. 兼容性结论

真实生态源一次跑通，说明 `docs/plugin-engine.md` §8 的 polyfill 必备集是够用的：

- `fetch` / `res.ok` / `res.json()`、`Network` Cookie 三件套、`ComicDetails` 构造器；
- `this.loadSetting()` 同步桥、`settings` 声明式配置、`onImageLoad` 对象返回；
- `String.replaceAll`、可选链、模板串、`Map` 章节、类字段初始化等 QuickJS 语言特性；
- 老源没有 `==SourceScript==` 头，`domains` / `kind` / `apiVersion` 由适配层（调用方）声明，
  全部能力由 `describe()` 探测补齐——与 §8「无头老源适配」一致。

未被否认的边界：`loadInfo` 需要按不同 URL 返回不同形状的 fixture（列表是数组、
详情是对象），当前单份 fixture 未覆盖；WebView 登录、`UI.*`、`modifyImage`、
缩略图管线仍属后续能力。

> 单源验证之后已扩展为官方仓库全量矩阵（34 个脚本），结论见
> `docs/source-compatibility.md`；本文保留单源主链路的细粒度验证记录。

## 5. 队列第 1 项剩余部分

引擎侧已就绪，仍需两处产品接线：

1. **源管理页面**（`:feature:sources`）：安装计划预览 → 权限/风险二次确认 →
   列表 / 启用禁用 / 回滚 / 设置编辑 / 更新。
2. **阅读器接线**：`App()` 目前传 `NoopChapterProvider`；需按已启用源装配
   `SourceRuntimeManager` → `SourceRuntime` → `BridgeChapterProvider`，
   并把 `domains` 一并传入阅读器。

两者的共同前置是**平台 SQLDelight driver 注入**：
`SourceInstaller.confirm` / `SourceRegistry` 需要 `ReaderDatabase`，而 driver 只能在
`androidMain` / `desktopMain` 构造（ADR-006 禁止新增 `expect/actual`，走运行时注入）。
建议以 `App(dependencies)` 形式由各平台入口传入，随后删掉 `NoopChapterProvider`。
