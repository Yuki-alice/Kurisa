package feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B-8 门禁：设置状态机（偏好持久化、清理确认流、缺装配退化）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    class FakeStore(var prefs: SettingsPrefs = SettingsPrefs()) : SettingsStore {
        val saved = mutableListOf<SettingsPrefs>()
        var failLoad = false
        var failSave = false
        override suspend fun load(): SettingsPrefs {
            if (failLoad) throw IllegalStateException("db down")
            return prefs
        }
        override suspend fun save(p: SettingsPrefs) {
            if (failSave) throw IllegalStateException("disk full")
            saved += p
            prefs = p
        }
    }

    private fun vm(
        store: SettingsStore = FakeStore(),
        clear: (suspend () -> Long)? = null,
    ) = SettingsViewModel(SettingsDeps(store, "9.9.9", clear))

    @Test
    fun loadsInitialPrefs() = runTest(dispatcher) {
        val store = FakeStore(SettingsPrefs(meteredNetworkRestricted = true, imageCacheLimitMb = 128))
        val vm = vm(store)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.prefs.meteredNetworkRestricted)
        assertEquals(128, vm.state.value.prefs.imageCacheLimitMb)
        assertEquals("9.9.9", vm.state.value.appVersion)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.cacheClearable)
    }

    @Test
    fun toggleMeteredPersists() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = vm(store)
        advanceUntilIdle()
        vm.setMeteredRestricted(true)
        advanceUntilIdle()
        assertTrue(vm.state.value.prefs.meteredNetworkRestricted)
        assertEquals(1, store.saved.size)
        assertTrue(store.saved[0].meteredNetworkRestricted)
    }

    @Test
    fun cacheLimitClampedAndPersists() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = vm(store)
        advanceUntilIdle()
        vm.setImageCacheLimitMb(-5)
        advanceUntilIdle()
        assertEquals(0, vm.state.value.prefs.imageCacheLimitMb)
        vm.setImageCacheLimitMb(512)
        advanceUntilIdle()
        assertEquals(512, store.prefs.imageCacheLimitMb)
    }

    @Test
    fun saveFailureKeepsUiState() = runTest(dispatcher) {
        val store = FakeStore().apply { failSave = true }
        val vm = vm(store)
        advanceUntilIdle()
        vm.setMeteredRestricted(true)
        advanceUntilIdle()
        // UI 已更新（乐观），存储失败不回滚、不崩。
        assertTrue(vm.state.value.prefs.meteredNetworkRestricted)
    }

    @Test
    fun loadFailureFallsBackToDefaults() = runTest(dispatcher) {
        val store = FakeStore().apply { failLoad = true }
        val vm = vm(store)
        advanceUntilIdle()
        assertEquals(SettingsPrefs(), vm.state.value.prefs)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun clearCacheReportsBytes() = runTest(dispatcher) {
        val vm = vm(clear = { 12_345L })
        advanceUntilIdle()
        assertTrue(vm.state.value.cacheClearable)
        vm.clearCache()
        advanceUntilIdle()
        assertEquals(12_345L, vm.state.value.clearedBytes)
        assertNull(vm.state.value.clearError)
        assertFalse(vm.state.value.clearing)
    }

    @Test
    fun clearCacheFailureSurfacesError() = runTest(dispatcher) {
        val vm = vm(clear = { throw IllegalStateException("io") })
        advanceUntilIdle()
        vm.clearCache()
        advanceUntilIdle()
        assertEquals("io", vm.state.value.clearError)
        assertNull(vm.state.value.clearedBytes)
    }

    @Test
    fun noClearImplIsNoop() = runTest(dispatcher) {
        val vm = vm(clear = null)
        advanceUntilIdle()
        assertFalse(vm.state.value.cacheClearable)
        vm.clearCache()
        advanceUntilIdle()
        assertNull(vm.state.value.clearedBytes)
        assertNull(vm.state.value.clearError)
    }
}
