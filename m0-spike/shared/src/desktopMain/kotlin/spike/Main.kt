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

fun main(): Unit = runBlocking(Dispatchers.Default) {
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
    println("FULL_CHAIN=$result")

    val runtime = QuickJs.create(Dispatchers.Default)
    runtime.memoryLimit = 32L * 1024 * 1024
    runtime.evaluationTimeoutMillis = 10_000
    println("MEMORY_LIMIT=${runtime.memoryLimit} TIMEOUT=${runtime.evaluationTimeoutMillis} USED=${runtime.memoryUsage.memoryUsedSize}")
    runtime.close()

    try {
        withTimeout(2_000) {
            quickJs {
                evaluationTimeoutMillis = 300
                evaluate<Any?>("while(true){}")
            }
        }
        println("INTERRUPT=NOT_TRIGGERED")
    } catch (e: QuickJsInterruptedException) {
        println("INTERRUPT=OK QuickJsInterruptedException")
    } catch (e: Exception) {
        println("INTERRUPT=${e::class.qualifiedName} :: ${e.message}")
    }
}
