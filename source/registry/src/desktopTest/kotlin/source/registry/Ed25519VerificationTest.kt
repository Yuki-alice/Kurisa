package source.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ed25519VerificationTest {
    @Test
    fun consistency() {
        val bad = Ed25519.consistencyCheck()
        println("CONSISTENCY=" + bad)
        assertTrue(bad.isEmpty(), "failures: $bad")
    }

    @Test
    fun rfcPubkey() {
        // 地面真值（openssl genpkey -algorithm ed25519 实测，不依赖记忆中的 RFC 向量）：
        // seed ac54ad…9ac3 → pub 26a7d7…715a
        val seed = SourceSignature.hexToBytes("ac54adb69376ce4f4607ab5a4e891e6a8ea41fa002e7e4e515d738f7b24d9ac3")!!
        val pub = Ed25519.publicKeyFromSeed(seed)
        println("PUB=" + SourceSignature.bytesToHex(pub))
        assertEquals(
            "26a7d7bb8f89608fb85e2db24f4ba9b23dd5ac5282a51d2390547d20137e715a",
            SourceSignature.bytesToHex(pub),
        )
    }
}
