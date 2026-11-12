package source.registry

import core.model.toUtf8Bytes
import source.contract.IndexedSource
import source.contract.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallerTest {

    private fun script(
        key: String = "com.example.novel",
        version: String = "1.2.0",
        domains: String = "example.com",
    ) = """
        // ==SourceScript==
        // @name        N
        // @key         $key
        // @version     $version
        // @apiVersion  2
        // @minApp      1.0.0
        // @kind        novel
        // @domains     $domains
        // @capabilities search
        // @url         https://example.com/n.js
        // ==/SourceScript==
        class N extends ComicSource {}
    """.trimIndent()

    @Test
    fun planOkWithoutIndexMarksUnverified() {
        val r = SourceInstaller.plan(script())
        assertTrue(r is SourceInstaller.PlanResult.Ok)
        val plan = (r as SourceInstaller.PlanResult.Ok).plan
        assertTrue(plan.signatureMissing)
        assertTrue(plan.requiresDoubleConfirm)
    }

    @Test
    fun shaMismatchRejected() {
        val s = script()
        val idx = IndexedSource(
            name = "N", key = "com.example.novel", version = "1.2.0",
            kind = setOf(ContentKind.NOVEL), domains = listOf("example.com"),
            sha256 = "0".repeat(64), url = "https://example.com/n.js",
        )
        val r = SourceInstaller.plan(s, idx)
        assertTrue(r is SourceInstaller.PlanResult.Rejected)
    }

    @Test
    fun shaMatchAndSignatureOk() {
        val s = script()
        val sha = SourceSignature.sha256OfScript(s.toUtf8Bytes())
        val seed = ByteArray(32) { 5 }
        val pubHex = SourceSignature.bytesToHex(Ed25519.publicKeyFromSeed(seed))
        val msg = SourceSignature.signedMessage("com.example.novel", "1.2.0", sha)
        val sig = Base64Codec.encode(Ed25519.sign(seed, msg))
        val idx = IndexedSource(
            name = "N", key = "com.example.novel", version = "1.2.0",
            kind = setOf(ContentKind.NOVEL), domains = listOf("example.com"),
            sha256 = sha, signature = sig, url = "https://example.com/n.js",
        )
        val r = SourceInstaller.plan(s, idx, pubHex)
        assertTrue(r is SourceInstaller.PlanResult.Ok)
        assertEquals(true, (r as SourceInstaller.PlanResult.Ok).plan.signatureOk)
    }

    @Test
    fun badSignatureRejectedKeepsOld() {
        val s = script()
        val sha = SourceSignature.sha256OfScript(s.toUtf8Bytes())
        val seed = ByteArray(32) { 5 }
        val other = ByteArray(32) { 6 }
        val pubHex = SourceSignature.bytesToHex(Ed25519.publicKeyFromSeed(other))
        val msg = SourceSignature.signedMessage("com.example.novel", "1.2.0", sha)
        val sig = Base64Codec.encode(Ed25519.sign(seed, msg))
        val idx = IndexedSource(
            name = "N", key = "com.example.novel", version = "1.2.0",
            kind = setOf(ContentKind.NOVEL), domains = listOf("example.com"),
            sha256 = sha, signature = sig, url = "https://example.com/n.js",
        )
        val r = SourceInstaller.plan(s, idx, pubHex)
        assertTrue(r is SourceInstaller.PlanResult.Rejected)
    }

    @Test
    fun semverCompare() {
        assertTrue(SourceInstaller.compareSemver("1.2.0", "1.1.9") > 0)
        assertTrue(SourceInstaller.compareSemver("1.0.0", "1.0.1") < 0)
        assertEquals(0, SourceInstaller.compareSemver("1.0.0", "1.0.0"))
        assertTrue(SourceInstaller.compareSemver("1.0.0", "1.0.0-beta") > 0)
    }

    private fun rulesScript(
        key: String = "ex.rules",
        version: String = "1.0.0",
        domains: String = "\"cdn.example.com\"",
    ) = """
        {
          "format": "rules/v1", "key": "$key", "name": "R",
          "version": "$version", "kind": ["manga"], "domains": [$domains],
          "baseUrl": "https://cdn.example.com/",
          "search": {"request": {"url": "/s"}, "list": ".a", "fields": {}},
          "detail": {"request": {"url": "/d"}, "chapters": {"list": ".c"}},
          "content": {"images": {"list": "img", "value": "@src | absUrl"}}
        }
    """.trimIndent()

    @Test
    fun rulesPlanOkAndFormatMismatchRejected() {
        val s = rulesScript()
        val ok = SourceInstaller.plan(s)
        assertTrue(ok is SourceInstaller.PlanResult.Ok)
        val plan = (ok as SourceInstaller.PlanResult.Ok).plan
        assertEquals("rules/v1", plan.format)
        assertEquals(listOf("cdn.example.com"), plan.domains)
        assertTrue(plan.requiresDoubleConfirm)

        // 索引 format 与脚本不一致即拒绝。
        val sha = SourceSignature.sha256OfScript(s.toUtf8Bytes())
        val jsIdx = IndexedSource(
            name = "R", key = "ex.rules", version = "1.0.0",
            kind = setOf(ContentKind.MANGA), domains = listOf("cdn.example.com"),
            sha256 = sha, filename = "ex.rules.js",
        )
        assertTrue(SourceInstaller.plan(s, jsIdx) is SourceInstaller.PlanResult.Rejected)

        val rulesIdx = jsIdx.copy(format = "rules/v1", filename = "ex.rules.rules.json")
        assertTrue(SourceInstaller.plan(s, rulesIdx) is SourceInstaller.PlanResult.Ok)
    }

    @Test
    fun rulesInvalidJsonRejected() {
        assertTrue(SourceInstaller.plan("""{"format":"rules/v1"}""") is SourceInstaller.PlanResult.Rejected)
    }
}
