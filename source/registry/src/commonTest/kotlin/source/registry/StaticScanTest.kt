package source.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticScanTest {

    @Test
    fun cleanScriptPasses() {
        val v = SourceStaticScan.scan("class A extends ComicSource {}", listOf("example.com"))
        assertFalse(v.blocked)
        assertTrue(v.warnings.isEmpty())
        assertFalse(v.requiresDoubleConfirm)
    }

    @Test
    fun evalRequiresConfirm() {
        val v = SourceStaticScan.scan("eval(x)", listOf("example.com"))
        assertFalse(v.blocked)
        assertTrue(v.requiresDoubleConfirm)
    }

    @Test
    fun moduleEscapesRequireConfirmButNotBlocked() {
        // ESM import（含动态 import(）/ CJS require / Deno / Node env / Java.type）。
        for (snippet in listOf(
            "import x from 'y'",
            "import('y')",
            "const a = require('fs')",
            "Deno.readFile('x')",
            "process.env.HOME",
            "Java.type('jsoup.Jsoup')",
        )) {
            val v = SourceStaticScan.scan(snippet, listOf("example.com"))
            assertFalse(v.blocked, snippet)
            assertTrue(v.requiresDoubleConfirm, snippet)
            assertTrue(v.warnings.isNotEmpty(), snippet)
        }
        // 扫描是提示性质：不误杀普通标识符与注释。
        val clean = SourceStaticScan.scan(
            "// important: required fields\nclass A extends ComicSource {}",
            listOf("example.com"),
        )
        assertFalse(clean.requiresDoubleConfirm)
        assertTrue(clean.warnings.isEmpty())
    }

    @Test
    fun outsiderDomainWarns() {
        val v = SourceStaticScan.scan(
            """fetch("https://evil.com/x")""", listOf("example.com")
        )
        assertTrue(v.warnings.any { it.contains("evil.com") })
        assertTrue(v.requiresDoubleConfirm)
    }

    @Test
    fun oversizeBlocked() {
        val big = "a".repeat(SourceSignature.MAX_SCRIPT_BYTES + 1)
        val v = SourceStaticScan.scan(big, emptyList())
        assertTrue(v.blocked)
    }
}
