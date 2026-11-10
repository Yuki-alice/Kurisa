package spike

import com.dokar.quickjs.quickJs

suspend fun evaluateSource(script: String): Any? = quickJs {
    memoryLimit = 32L * 1024 * 1024
    maxStackSize = 256L * 1024
    evaluationTimeoutMillis = 10_000
    evaluate<Any?>(script)
}
