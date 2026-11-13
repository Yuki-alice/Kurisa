package source.registry

import kotlin.test.Test
import kotlin.test.assertEquals

/** B-8 门禁：索引签名校验读取（分离签名 + 轮换公钥 + 未签名兼容）。 */
class IndexSignatureTest {

    private val indexJson = "{\"version\":1,\"sources\":[]}"
    private val seed = ByteArray(32) { (it + 3).toByte() }
    private fun pubHex(s: ByteArray = seed) = SourceSignature.bytesToHex(Ed25519.publicKeyFromSeed(s))
    private fun sign(json: String, s: ByteArray = seed) =
        Base64Codec.encode(Ed25519.sign(s, SourceIndexSignature.signedBytes(json)))

    @Test
    fun verifiesWithRotatedKeys() {
        val other = ByteArray(32) { (it + 9).toByte() }
        val keys = mapOf("old" to pubHex(other), "current" to pubHex())
        val status = SourceIndexSignature.check(indexJson, sign(indexJson), keys)
        assertEquals(IndexSignatureStatus.Verified("current"), status)
    }

    @Test
    fun missingSignatureIsUnsigned() {
        assertEquals(
            IndexSignatureStatus.Unsigned,
            SourceIndexSignature.check(indexJson, null, mapOf("k" to pubHex()))
        )
        assertEquals(
            IndexSignatureStatus.Unsigned,
            SourceIndexSignature.check(indexJson, "  ", mapOf("k" to pubHex()))
        )
    }

    @Test
    fun noKeysIsInvalid() {
        assertEquals(
            IndexSignatureStatus.Invalid("no-public-key"),
            SourceIndexSignature.check(indexJson, sign(indexJson), emptyMap())
        )
    }

    @Test
    fun surroundingWhitespaceIsInsensitive() {
        val status = SourceIndexSignature.check(indexJson + "  \n", sign(indexJson), mapOf("k" to pubHex()))
        assertEquals(IndexSignatureStatus.Verified("k"), status)
    }

    @Test
    fun tamperedIndexIsInvalid() {
        val bad = SourceIndexSignature.check(
            "{\"version\":2,\"sources\":[]}",
            sign(indexJson),
            mapOf("k" to pubHex())
        )
        assertEquals(IndexSignatureStatus.Invalid("signature-mismatch"), bad)
    }

    @Test
    fun wrongKeyIsInvalid() {
        val other = ByteArray(32) { 42 }
        val status = SourceIndexSignature.check(indexJson, sign(indexJson), mapOf("k" to pubHex(other)))
        assertEquals(IndexSignatureStatus.Invalid("signature-mismatch"), status)
    }
}
