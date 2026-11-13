package app.shared

import data.network.CookieStore
import feature.detail.DetailData
import feature.detail.DetailDeps
import feature.detail.DetailViewModel
import feature.search.SearchDeps
import feature.search.SearchPageUi
import feature.search.SearchSourceUi
import feature.search.SearchViewModel
import feature.settings.SettingsDeps
import feature.settings.SettingsPrefs
import feature.settings.SettingsStore
import feature.settings.SettingsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * BE-A5 回归：屏幕级 ViewModel 由 [AppContainer] 的 Koin 图提供，页面不再手写 Factory。
 *
 * 断言两件事：定义存在（能按类型取到）、屏幕级载荷经 `parametersOf` 传入构造。
 * `viewModel {}` 在 koin-core-viewmodel 里落到 `factory`，所以这里用
 * `graph.get` 直接取；ViewModelStore 生命周期路径由 Compose 侧 `koinViewModel` 负责。
 */
class AppContainerTest {

    @AfterTest
    fun tearDown() {
        // AppContainer 用 startKoin 注册全局上下文；本类只起一次，结束时清掉，避免污染同 JVM 其它测试。
        stopKoin()
    }

    @Test
    fun screenViewModelsComeFromContainerGraph() {
        val container = AppContainer(CookieStore(), HttpClient(MockEngine { respondOk() }))
        try {
            val searchDeps = SearchDeps(
                sources = listOf(SearchSourceUi("src.example", "示例源")),
            ) { _, _, _ -> SearchPageUi() }
            val searchVm = container.graph.get<SearchViewModel> { parametersOf(searchDeps) }
            assertNotNull(searchVm)
            assertEquals(1, searchVm.sources.size)
            assertEquals("src.example", searchVm.state.value.sourceKey)

            val detailDeps = DetailDeps("路由标题") { DetailData(title = "详情标题") }
            val detailVm = container.graph.get<DetailViewModel> { parametersOf(detailDeps) }
            assertNotNull(detailVm)

            val settingsDeps = SettingsDeps(FakeSettingsStore(), "9.9.9", null)
            val settingsVm = container.graph.get<SettingsViewModel> { parametersOf(settingsDeps) }
            assertNotNull(settingsVm)
            assertEquals("9.9.9", settingsVm.state.value.appVersion)
        } finally {
            container.close()
        }
    }

    class FakeSettingsStore : SettingsStore {
        override suspend fun load(): SettingsPrefs = SettingsPrefs()
        override suspend fun save(prefs: SettingsPrefs) = Unit
    }
}
