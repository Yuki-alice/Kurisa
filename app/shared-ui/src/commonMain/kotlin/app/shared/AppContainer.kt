package app.shared

import data.images.ImageFetcher
import data.images.SourceImageFetcher
import data.network.CookieStore
import data.network.SourceHttpClient
import io.ktor.client.HttpClient
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * 应用级依赖容器（S2：Koin 收口共享网络栈）。
 *
 * 为什么要有它：阅读器此前在 [feature.reader.ReaderScreen] 里 `remember { CookieStore() }`
 * 自建了一份网络栈，与源侧的 Cookie/UA 配对完全脱节——`cf_clearance` 与 UA 必须成对，
 * 两套实例等于配对永远失效（B3）。现在 CookieStore / HttpClient / ImageFetcher 全部
 * 由平台壳创建一次，经容器注入，阅读器只消费。
 *
 * 容器用 `koin-core`（不引 `koin-compose`，避免 CMP 与 Native 的额外集成面）：
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
            },
        )
    }.koin

    val cookies: CookieStore get() = koin.get()
    val http: HttpClient get() = koin.get()
    val imageFetcher: ImageFetcher get() = koin.get()

    fun close() {
        koin.close()
    }
}
