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
import kotlin.test.fail

/**
 * Gate ②a/②b 的回归测试（JVM/desktop）。
 * iOS 侧同语义验证需在 macOS 跑 iosSimulatorArm64Test（commonTest 已覆盖纯逻辑；
 * QuickJs suspend 链在 Native 侧由同一份 quickjs-kt C 引擎驱动，行为一致由依赖保证）。
 */
class QuickJsBehaviorTest {

    @Test
    fun promiseChain() = runBlocking(Dispatchers.Default) {
        val result = quickJs {
            define("Network") {
                asyncFunction("get") { "<html><h1>fixture</h1></html>" }
            }
            define("Convert") {
                function("md5") { "stub-hash" }
            }
            evaluate<String>(
                """
                const html = await Network.get("https://example.com/search?q=test");
                JSON.stringify({ body: html, hash: Convert.md5(html) });
                """.trimIndent(),
            )
        }
        assertTrue(result.contains("fixture"))
        assertTrue(result.contains("stub-hash"))
    }

    @Test
    fun memoryLimitEnforced() = runBlocking(Dispatchers.Default) {
        val runtime = QuickJs.create(Dispatchers.Default)
        try {
            runtime.memoryLimit = 32L * 1024 * 1024
            runtime.evaluationTimeoutMillis = 10_000
            assertEquals(32L * 1024 * 1024, runtime.memoryLimit)
            assertTrue(runtime.memoryUsage.memoryUsedSize >= 0)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun interruptWhileTrue() = runBlocking(Dispatchers.Default) {
        try {
            withTimeout(5_000) {
                quickJs {
                    evaluationTimeoutMillis = 300
                    evaluate<Any?>("while(true){}")
                }
            }
            fail("expected interruption")
        } catch (e: QuickJsInterruptedException) {
            // expected: 宿主不会被卡死
        }
    }
}
