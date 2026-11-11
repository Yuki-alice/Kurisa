package source.registry

import core.model.toUtf8Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignatureTest {

    private fun hex(bytes: ByteArray): String = SourceSignature.bytesToHex(bytes)

    @Test
    fun sha256MatchesJdkVector() {
        // 空串向量（与 core.model 一致）
        val empty = ByteArray(0)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SourceSignature.sha256OfScript(empty),
        )
    }

    @Test
    fun signVerifyRoundtrip() {
        val seed = ByteArray(32) { (it + 1).toByte() }
        val pub = Ed25519.publicKeyFromSeed(seed)
        assertEquals(32, pub.size)
        val msg = "com.example.n|1.2.0|abcdef".toUtf8Bytes()
        val sig = Ed25519.sign(seed, msg)
        assertEquals(64, sig.size)
        assertTrue(Ed25519.verify(pub, msg, sig))
    }

    @Test
    fun tamperedMessageFails() {
        val seed = ByteArray(32) { 7 }
        val pub = Ed25519.publicKeyFromSeed(seed)
        val msg = "k|1.0.0|aaa".toUtf8Bytes()
        val sig = Ed25519.sign(seed, msg)
        assertFalse(Ed25519.verify(pub, "k|1.0.0|aab".toUtf8Bytes(), sig))
    }

    @Test
    fun wrongKeyFails() {
        val seedA = ByteArray(32) { 1 }
        val seedB = ByteArray(32) { 2 }
        val pubB = Ed25519.publicKeyFromSeed(seedB)
        val msg = "m".toUtf8Bytes()
        val sig = Ed25519.sign(seedA, msg)
        assertFalse(Ed25519.verify(pubB, msg, sig))
    }

    @Test
    fun base64Roundtrip() {
        val bytes = ByteArray(64) { it.toByte() }
        val enc = Base64Codec.encode(bytes)
        assertEquals(bytes.toList(), Base64Codec.decode(enc)!!.toList())
    }

    @Test
    fun wrapperVerify() {
        val seed = ByteArray(32) { 9 }
        val pubHex = hex(Ed25519.publicKeyFromSeed(seed))
        val msg = SourceSignature.signedMessage("com.example.n", "1.2.0", "a".repeat(64))
        val sigB64 = Base64Codec.encode(Ed25519.sign(seed, msg))
        assertTrue(SourceSignature.verifySignature(pubHex, msg, sigB64))
        assertFalse(SourceSignature.verifySignature(pubHex, "other".toUtf8Bytes(), sigB64))
    }

    @Test
    fun opensslInterop() {
        // 地面真值：openssl pkeyutl -sign（seed ac54…9ac3）对 "hello kurisa" 的签名。
        // Ed25519 确定性签名：自实现输出须与 openssl 逐字节一致（最强断言）。
        val seed = SourceSignature.hexToBytes(
            "ac54adb69376ce4f4607ab5a4e891e6a8ea41fa002e7e4e515d738f7b24d9ac3"
        )!!
        val pub = SourceSignature.hexToBytes(
            "26a7d7bb8f89608fb85e2db24f4ba9b23dd5ac5282a51d2390547d20137e715a"
        )!!
        val sig = SourceSignature.hexToBytes(
            "129625ec3b20f8a5186359954b8b0cac4307a5e604af1f1f974a06c6add20b5fbfa04aed42dcb999d59ae270fdfa11a31ecae052f1257424ea6a766702cc8e05"
        )!!
        val msg = "hello kurisa".toUtf8Bytes()
        assertTrue(Ed25519.verify(pub, msg, sig))
        assertEquals(sig.toList(), Ed25519.sign(seed, msg).toList())
    }
}
