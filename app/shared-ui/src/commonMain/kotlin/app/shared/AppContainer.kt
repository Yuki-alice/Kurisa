package app.shared

import data.images.ImageFetcher
import data.images.SourceImageFetcher
import data.network.CookieStore
import data.network.SourceHttpClient
import feature.detail.DetailDeps
import feature.detail.DetailViewModel
import feature.search.SearchDeps
import feature.search.SearchViewModel
import io.ktor.client.HttpClient
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 应用级依赖容器（S2 网络栈 + BE-A5 ViewModel 收口）。
 *
 * 为什么要有它：阅读器此前在 [feature.reader.ReaderScreen] 里 `remember { CookieStore() }`
 * 自建了一份网络栈，与源侧的 Cookie/UA 配对完全脱节——`cf_clearance` 与 UA 必须成对，
 * 两套实例等于配对永远失效（B3）。现在 CookieStore / HttpClient / ImageFetcher 全部
 * 由平台壳创建一次，经容器注入，阅读器只消费。
 *
 * BE-A5：屏幕级 ViewModel 也收口到这里。Search / Detail 的依赖是**屏幕级载荷**
 * （可搜索源列表、装配层装载闭包），所以用 `viewModel { params -> … }` 接收参数，
 * 由屏幕侧 `koinViewModel(parameters = …)` 注入；页面不再手写
 * `ViewModelProvider.Factory`。构造签名与三个公开属性保持不变（装配契约）。
 *
 * 容器用 `koin-core`（不引 `koin-compose` 的容器 API，避免把 Compose 树绑到容器）：
 * 组件图在 App 启动时构建一次，Compose 侧按普通对象取用。
 */
class AppContainer(
    cookieStore: CookieStore,
    httpClient: HttpClient,
) {
    private val koin: Koin = startKoin {
        modules(
            module {
                single<CookieStore> { cookieStore }
                single<HttpClient> { httpClient }
                single<ImageFetcher> {
                    // 与源请求共用同一 HttpClient + CookieStore：UA/Cookie 配对跨链路一致。
                    SourceImageFetcher(SourceHttpClient(get(), get()))
                }
                // 屏幕级 ViewModel：构造参数来自 Koin 参数（屏幕级载荷），生命周期由
                // androidx ViewModelStore 管理，容器只负责定义。
                viewModel { params -> SearchViewModel(params.get<SearchDeps>()) }
                viewModel { params -> DetailViewModel(params.get<DetailDeps>()) }
            },
        )
    }.koin

    val cookies: CookieStore get() = koin.get()
    val http: HttpClient get() = koin.get()
    val imageFetcher: ImageFetcher get() = koin.get()

    /** 仅供同模块测试断言组件图；不扩大对外 API（构造签名与三个公开属性不变）。 */
    internal val graph: Koin get() = koin

    fun close() {
        koin.close()
    }
}
