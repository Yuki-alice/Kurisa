package spike

import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 真实源加载入口：`sourceSpike <source.js> [keyword]`
 * 源文件不进入仓库（C1：应用本体不包含任何源），运行时从外部路径读取。
 */
fun main(args: Array<String>) = runBlocking {
    val path = args.getOrNull(0) ?: error("usage: sourceSpike <source.js> [keyword]")
    val keyword = args.getOrNull(1) ?: "ワンピース"
    val code = File(path).readText()

    val started = System.currentTimeMillis()
    val out = VeneraSourceRunner(JdkSourceHttp()).run(code, keyword)
    println("SOURCE_RESULT=$out")
    println("ELAPSED_MS=${System.currentTimeMillis() - started}")
}
