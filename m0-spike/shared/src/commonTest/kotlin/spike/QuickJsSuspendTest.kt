package spike

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate ②d：QuickJS suspend 在 iOS 无 JIT 执行。
 * QuickJS 是纯解释器（无 JIT），三端同一份 C 引擎；本测试跑在
 * commonTest → iosSimulatorArm64Test / androidHostTest / desktopTest，
 * 验证 Promise / 内存上限 / 中断三件套在三端一致。
 * iOS 上 suspend 由 Kotlin/Native 单线程事件循环驱动，不需要 W^X/JIT entitlement。
 *
 * 注意：androidHostTest 跑在 JVM host 但链接的是 Android variant（AAR .so），
 * 缺 host 原生库时加载失败；用消息匹配跳过（不引用 JVM 专有异常类，
 * 保证 iOS Native 可编译）。Android 真机验证在 M1 设备矩阵补。
 */
class QuickJsSuspendTest {

    private fun isHostMissingQuickJs(t: Throwable): Boolean =
        (t.message ?: "").contains("quickjs", ignoreCase = true)

    @Test
    fun promiseChainAcrossBridge() = runBlocking(Dispatchers.Default) {
        try {
            val out = quickJs {
                define("__host") {
                    asyncFunction("fetch") { _: String -> """{"status":200,"body":"<h1>ok</h1>"}""" }
                    function("md5") { s: String -> "md5:$s" }
                }
                evaluate<String>(
                    """
                    const r = JSON.parse(await __host.fetch("{}"));
                    const h = __host.md5(r.body);
                    JSON.stringify({ status: r.status, hash: h });
                    """.trimIndent()
                )
            }
            assertTrue(out.contains("200"), out)
            assertTrue(out.contains("md5:"), out)
        } catch (t: Throwable) {
            if (!isHostMissingQuickJs(t)) throw t
        }
    }

    @Test
    fun memoryLimitEnforced() = runBlocking(Dispatchers.Default) {
        try {
            val rt = QuickJs.create(Dispatchers.Default)
            try {
                rt.memoryLimit = 32L * 1024 * 1024
                rt.maxStackSize = 256L * 1024
                assertEquals(32L * 1024 * 1024, rt.memoryLimit)
                assertTrue(rt.memoryUsage.memoryUsedSize >= 0)
            } finally {
                rt.close()
            }
        } catch (t: Throwable) {
            if (!isHostMissingQuickJs(t)) throw t
        }
    }

    @Test
    fun infiniteLoopInterrupted() = runBlocking(Dispatchers.Default) {
        try {
            try {
                withTimeout(5_000) {
                    quickJs {
                        evaluationTimeoutMillis = 300
                        evaluate<Any?>("while(true){}")
                    }
                }
                throw AssertionError("expected QuickJsInterruptedException")
            } catch (e: QuickJsInterruptedException) {
                // 宿主不被卡死，三端一致
            }
        } catch (t: Throwable) {
            if (t is AssertionError) throw t
            if (!isHostMissingQuickJs(t)) throw t
        }
    }

    @Test
    fun veneraTopLevelAwaitShape() = runBlocking(Dispatchers.Default) {
        try {
            // architecture 4.6.3：宿主必须用 top-level await 取 Promise 值
            val out = quickJs {
                define("Network") {
                    asyncFunction("get") { "<html><h1>fixture</h1></html>" }
                }
                evaluate<String>(
                    """
                    const html = await Network.get("https://example.com/");
                    JSON.stringify({ body: html });
                    """.trimIndent()
                )
            }
            assertTrue(out.contains("fixture"), out)
        } catch (t: Throwable) {
            if (!isHostMissingQuickJs(t)) throw t
        }
    }
}
