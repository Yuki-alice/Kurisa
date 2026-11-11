package core.model

/**
 * SHA-256 纯 Kotlin 实现（commonMain 三端一致，无平台依赖）。
 *
 * 选型依据（ADR-006）：ID 规则与哈希校验先走纯 Kotlin，不引入 KMP 加密库；
 * 若后续 Convert.* 需要更多算法，再评估 Cryptography-Kotlin / korlibs-crypto。
 * 实现为标准 FIPS 180-4 SHA-256，与 JDK / CommonCrypto 结果一致（见 WorkIdsTest 向量）。
 */
internal object Sha256 {
    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    fun digest(input: ByteArray): ByteArray {
        var h0 = 0x6a09e667.toInt()
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372.toInt()
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f.toInt()
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab.toInt()
        var h7 = 0x5be0cd19.toInt()

        val bitLen = input.size.toLong() * 8L
        val paddedLen = (((input.size + 8) / 64) + 1) * 64
        val msg = ByteArray(paddedLen)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            msg[paddedLen - 1 - i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        }

        val w = IntArray(64)
        var off = 0
        while (off < paddedLen) {
            for (i in 0 until 16) {
                val j = off + i * 4
                w[i] = ((msg[j].toInt() and 0xFF) shl 24) or
                    ((msg[j + 1].toInt() and 0xFF) shl 16) or
                    ((msg[j + 2].toInt() and 0xFF) shl 8) or
                    (msg[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7
            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor ((e.inv()) and g)
                val t1 = h + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
            off += 64
        }

        fun Int.toBytes(): ByteArray = byteArrayOf(
            ((this ushr 24) and 0xFF).toByte(),
            ((this ushr 16) and 0xFF).toByte(),
            ((this ushr 8) and 0xFF).toByte(),
            (this and 0xFF).toByte(),
        )
        return h0.toBytes() + h1.toBytes() + h2.toBytes() + h3.toBytes() +
            h4.toBytes() + h5.toBytes() + h6.toBytes() + h7.toBytes()
    }

    fun hex(input: ByteArray): String {
        val out = CharArray(input.size * 2)
        val hex = "0123456789abcdef"
        input.forEachIndexed { i, b ->
            out[i * 2] = hex[(b.toInt() ushr 4) and 0xF]
            out[i * 2 + 1] = hex[b.toInt() and 0xF]
        }
        return out.concatToString()
    }
}

/** UTF-8 编码（commonMain 无平台依赖，避免 expect/actual）。 */
fun String.toUtf8Bytes(): ByteArray {
    val out = mutableListOf<Byte>()
    var i = 0
    while (i < length) {
        val c = this[i].code
        when {
            c < 0x80 -> out += c.toByte()
            c < 0x800 -> {
                out += ((0xC0 or (c shr 6)) and 0xFF).toByte()
                out += ((0x80 or (c and 0x3F)) and 0xFF).toByte()
            }
            this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> {
                val hi = this[i].code - 0xD800
                val lo = this[i + 1].code - 0xDC00
                val cp = 0x10000 + (hi shl 10) + lo
                out += ((0xF0 or (cp shr 18)) and 0xFF).toByte()
                out += ((0x80 or ((cp shr 12) and 0x3F)) and 0xFF).toByte()
                out += ((0x80 or ((cp shr 6) and 0x3F)) and 0xFF).toByte()
                out += ((0x80 or (cp and 0x3F)) and 0xFF).toByte()
                i++
            }
            else -> {
                out += ((0xE0 or (c shr 12)) and 0xFF).toByte()
                out += ((0x80 or ((c shr 6) and 0x3F)) and 0xFF).toByte()
                out += ((0x80 or (c and 0x3F)) and 0xFF).toByte()
            }
        }
        i++
    }
    return out.toByteArray()
}

/**
 * 宿主内部作品 ID（架构 5.1）：`sha256(sourceKey + "|" + sourceWorkId)`。
 * 禁止用 URL 作主键（站点改域名会导致书架丢失）；跨源不冲突、跨端同步稳定。
 */
fun workStableId(sourceKey: String, sourceWorkId: String): String =
    Sha256.hex(Sha256.digest("$sourceKey|$sourceWorkId".toUtf8Bytes()))

/** 任意文本的 sha256 hex（源脚本哈希、仓库签名校验共用）。 */
fun sha256Hex(text: String): String = Sha256.hex(Sha256.digest(text.toUtf8Bytes()))

/** 字节数组的 sha256 hex。 */
fun sha256HexBytes(bytes: ByteArray): String = Sha256.hex(Sha256.digest(bytes))
