package spike

import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Koin 真工厂验证（commonTest，三端同跑）。
 * N5 语义（同屏不重建/跨屏隔离）在 ViewModelScopeTest 锁定；
 * 这里证明 M1 将使用的 Koin single/factory/scope 机制本身三端可用。
 * 注：不用 startKoin/GlobalContext（Native 无该入口），改用 koinApplication。
 */
class KoinFactoryTest {

    class ShelfVm(val id: Int)
    class DetailVm(val workId: String)

    @Test
    fun singleAndFactory() {
        var nextId = 0
        val app = koinApplication {
            modules(
                module {
                    single { ShelfVm(nextId++) }
                    factory { (workId: String) -> DetailVm(workId) }
                }
            )
        }
        try {
            val koin = app.koin
            val a: ShelfVm = koin.get()
            val b: ShelfVm = koin.get()
            assertTrue(a === b, "single 必须同一实例")
            assertEquals(a.id, b.id)
            val d1: DetailVm = koin.get { parametersOf("w1") }
            val d2: DetailVm = koin.get { parametersOf("w1") }
            assertTrue(d1 !== d2, "factory 每次新建")
            assertEquals("w1", d1.workId)
        } finally {
            app.close()
        }
    }

    @Test
    fun scopePerRoute() {
        val app = koinApplication {
            modules(
                module {
                    scope<RouteScope> {
                        scoped { DetailVm("scoped") }
                    }
                }
            )
        }
        try {
            val koin = app.koin
            val s1 = koin.createScope<RouteScope>("r1")
            val s2 = koin.createScope<RouteScope>("r2")
            val v1: DetailVm = s1.get()
            val v1b: DetailVm = s1.get()
            val v2: DetailVm = s2.get()
            assertTrue(v1 === v1b, "同 scope 同实例")
            assertTrue(v1 !== v2, "跨 scope 隔离")
            s1.close()
            s2.close()
        } finally {
            app.close()
        }
    }

    class RouteScope
}
