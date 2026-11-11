package source.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticScanTest {

    @Test
    fun cleanScriptPasses() {
        val v = SourceStaticScan.scan("class A extends ComicSource {}", listOf("ex.com"))
        assertFalse(v.blocked)
        assertTrue(v.warnings.isEmpty())
        assertFalse(v.requiresDoubleConfirm)
    }

    @Test
    fun evalRequiresConfirm() {
        val v = SourceStaticScan.scan("eval(x)", listOf("ex.com"))
        assertFalse(v.blocked)
        assertTrue(v.requiresDoubleConfirm)
    }

    @Test
    fun outsiderDomainWarns() {
        val v = SourceStaticScan.scan(
            """fetch("https://evil.com/x")""", listOf("ex.com")
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
