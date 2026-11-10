package spike

import java.io.File

/**
 * Cookie / Cloudflare 处理的验证入口。
 *
 * 三类断言：
 * 1. 拦截分类：用真实抓取的两类 Cloudflare 页面（WAF 阻断 / 托管挑战）验证识别逻辑；
 * 2. 粘贴解析：覆盖用户可能复制出来的 5 种格式；
 * 3. 存储与 UA 对齐：域匹配、过期、失效标记，以及 cf_clearance 的 UA 绑定。
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { pass++; println("  PASS  $name${if (detail.isEmpty()) "" else "  ($detail)"}") }
        else { fail++; println("  FAIL  $name${if (detail.isEmpty()) "" else "  ($detail)"}") }
    }

    val now = 1_760_000_000_000L
    val root = File(System.getProperty("user.dir"))
    // JavaExec 的 workingDir 可能是子项目目录，两种布局都探测
    val fixtures = listOf(
        File(root, "src/desktopMain/resources/cf-fixtures"),
        File(root, "shared/src/desktopMain/resources/cf-fixtures"),
    ).firstOrNull { it.exists() } ?: File(root, "src/desktopMain/resources/cf-fixtures")
    val blockHtml = File(fixtures, "block-waf.html").takeIf { it.exists() }?.readText().orEmpty()
    val challengeHtml = File(fixtures, "challenge-managed.html").takeIf { it.exists() }?.readText().orEmpty()

    println("========== A. Cloudflare 拦截分类 ==========")
    if (blockHtml.isEmpty() || challengeHtml.isEmpty()) {
        println("  !! 夹具缺失，跳过 A（路径: ${fixtures.absolutePath}）")
        fail += 2
    } else {
        val block = CloudflareGuard.classify(
            403, mapOf("server" to "cloudflare", "cf-ray" to "a58673f9"), blockHtml,
        )
        check("WAF 阻断页 → WAF_BLOCK", block.kind == ChallengeKind.WAF_BLOCK, block.kind.name)
        check("WAF 阻断不需要浏览器", !block.needsBrowser)
        check("WAF 阻断不可重试", !block.retryable)

        val chal = CloudflareGuard.classify(
            403, mapOf("server" to "cloudflare", "cf-mitigated" to "challenge"), challengeHtml,
        )
        check("托管挑战页 → MANAGED_CHALLENGE", chal.kind == ChallengeKind.MANAGED_CHALLENGE, chal.kind.name)
        check("托管挑战需要真实浏览器", chal.needsBrowser)
    }

    check("429 → RATE_LIMITED", CloudflareGuard.classify(429, emptyMap(), "").kind == ChallengeKind.RATE_LIMITED)
    check("200 正常页 → NONE", CloudflareGuard.classify(200, mapOf("server" to "cloudflare"), "<html>ok</html>").kind == ChallengeKind.NONE)
    check(
        "仅凭 cf-mitigated 也能识别（无正文特征）",
        CloudflareGuard.classify(403, mapOf("cf-mitigated" to "challenge"), "").kind == ChallengeKind.MANAGED_CHALLENGE,
    )

    println("\n========== B. 粘贴解析 ==========")
    val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0"

    val r1 = CookieImportParser.parse("cf_clearance=abc123; __cf_bm=def456", "mycomic.com", now, ua)
    check("Cookie 请求头格式", r1.cookies.size == 2 && r1.format == "cookie-header", "${r1.cookies.size} 条 / ${r1.format}")

    val r2 = CookieImportParser.parse("cf_clearance\tabc123\t.mycomic.com\t/\n__cf_bm\tdef456\t.mycomic.com\t/", "mycomic.com", now, ua)
    check("DevTools 表格格式", r2.cookies.size == 2 && r2.format == "tab-separated", "${r2.cookies.size} 条 / ${r2.format}")

    val r3 = CookieImportParser.parse("cf_clearance=abc123\n__cf_bm=def456", "mycomic.com", now, ua)
    check("多行 name=value", r3.cookies.size == 2, "${r3.cookies.size} 条 / ${r3.format}")

    val r4 = CookieImportParser.parse("""[{"name":"cf_clearance","value":"abc123"}]""", "mycomic.com", now, ua)
    check("JSON 数组", r4.cookies.size == 1 && r4.format == "json", "${r4.cookies.size} 条 / ${r4.format}")

    val r5 = CookieImportParser.parse("  cf_clearance = abc ; ;  ", "mycomic.com", now, ua)
    check("脏输入容错（空格/空段）", r5.cookies.size == 1 && r5.cookies[0].name == "cf_clearance", "${r5.cookies.size} 条")

    check("解析结果带上配对 UA", r1.cookies.all { it.pairedUserAgent == ua })

    println("\n========== C. 存储语义 ==========")
    val store = CookieStore()
    store.put(StoredCookie("mycomic.com", "cf_clearance", "v1", pairedUserAgent = ua, obtainedAtEpochMs = now))
    store.put(StoredCookie("mycomic.com", "session", "s1", obtainedAtEpochMs = now))
    store.put(StoredCookie(".mycomic.com", "wide", "w1", obtainedAtEpochMs = now))

    check("host-only 精确匹配", store.headerFor("https://mycomic.com/a", now)?.contains("cf_clearance") == true)
    check("不带点的域不匹配子域", store.matching("https://cdn.mycomic.com/a", now).none { it.name == "session" })
    check("带点前缀匹配子域", store.matching("https://cdn.mycomic.com/a", now).any { it.name == "wide" })
    check("不同站不串 Cookie", store.matching("https://api.mangadex.org/x", now).isEmpty())
    check("配对 UA 可取回", store.pairedUserAgentFor("https://mycomic.com/x", now) == ua)
    check("普通 Cookie 无配对 UA", store.matching("https://mycomic.com/x", now).first { it.name == "session" }.pairedUserAgent == null)

    val expired = CookieStore()
    expired.put(StoredCookie("a.com", "k", "v", expiresAtEpochMs = now - 1, obtainedAtEpochMs = now - 1000))
    check("过期 Cookie 不参与注入", expired.headerFor("https://a.com/", now) == null)

    val invalidCount = store.markInvalid("mycomic.com", "re-challenged", CookieStore.CF_COOKIES)
    check("重新挑战只作废 clearance", invalidCount == 1, "标记 $invalidCount 条")
    check("clearance 不再参与注入", store.matching("https://mycomic.com/a", now).none { it.name == "cf_clearance" })
    check("登录 session 不受影响", store.headerFor("https://mycomic.com/a", now)?.contains("session") == true)

    println("\n========== 结果 ==========")
    println("PASS=$pass  FAIL=$fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
