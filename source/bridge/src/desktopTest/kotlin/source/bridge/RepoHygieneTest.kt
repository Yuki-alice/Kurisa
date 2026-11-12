package source.bridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * C1 门禁（主仓零站点 URL）：
 * 扫描仓库内 `*.kt` 的 http(s) 字面量，宿主只允许保留/测试域名。
 *
 * 允许：example.com/net/org（及子域名）、example.invalid、保留顶级域 .example 下任意主机
 * （如 a.example；RFC 2606 文档保留）、localhost/127.0.0.1、
 * evil.com（攻击方占位符，非内容站点）。
 * 其余一律红灯——新测试灯具请用 example.* 系列，真实站点只允许出现在
 * `.workbuddy/refs/` 只读目录（不入库）与兼容性报告文本中。
 *
 * 另做回归锁：`mangadex` / `mycomic` 不得重回 `*.kt`（R17 清理，
 * 旧灯具曾硬编码真实站点域名；本文件自身除外）。
 *
 * 仅 desktopTest（需 java.io.File 遍历仓库）；找不到仓库根时 SKIP。
 */
class RepoHygieneTest {

    private val allowedBases = setOf(
        "example.com", "example.net", "example.org", "example.invalid",
        "localhost", "127.0.0.1", "evil.com",
    )

    private fun hostAllowed(host: String): Boolean {
        val h = host.lowercase()
        // 非常规宿主（正则片段等）不由本门禁判定，交由人工评审。
        if (h.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) return true
        if (h == "example" || h.endsWith(".example")) return true
        return allowedBases.any { b -> h == b || h.endsWith(".$b") }
    }

    private fun findRepoRoot(): File? {
        var dir = File(System.getProperty("user.dir"))
        repeat(8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun noRealSiteUrlsInKotlinSources() {
        val root = findRepoRoot()
        if (root == null) {
            println("SKIP（未找到仓库根）：C1 门禁仅本地执行")
            return
        }
        val urlPattern = Regex("""https?://([A-Za-z0-9.\-]+)""")
        val violations = mutableListOf<String>()
        val legacy = mutableListOf<String>()
        val modules = listOf("source", "core", "feature", "app", "data")
        for (module in modules) {
            File(root, module).walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") && it.name != "RepoHygieneTest.kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        urlPattern.findAll(line).forEach { m ->
                            if (!hostAllowed(m.groupValues[1])) {
                                violations += "${file.relativeTo(root)}:${index + 1}: ${m.value.take(80)}"
                            }
                        }
                        val lower = line.lowercase()
                        if ("mangadex" in lower || "mycomic" in lower) {
                            legacy += "${file.relativeTo(root)}:${index + 1}"
                        }
                    }
                }
        }
        assertTrue(
            violations.isEmpty(),
            "C1 违规：*.kt 含非白名单站点 URL（测试灯具请用 example.*）：\n" +
                violations.take(20).joinToString("\n"),
        )
        assertTrue(
            legacy.isEmpty(),
            "回归：*.kt 重现真实站点字面量（R17 已清理，禁止回退）：\n" +
                legacy.take(20).joinToString("\n"),
        )
    }
}
