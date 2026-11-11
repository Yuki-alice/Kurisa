package source.bridge

import kotlin.math.abs
import kotlin.math.sin

/**
 * MD5 + Base64 纯 Kotlin（commonMain 三端一致，零新依赖）。
 * 选纯手写而非 korlibs 的理由：korlibs 的顶层/扩展调用形状需逐端验证，
 * 而 MD5/Base64 均有牢记的标准向量（见单测），转录错误会被立即捕获。
 *
 * MASVS-CRYPTO C3 豁免声明：MD5 仅供源脚本兼容（Venera Convert.md5，
 * 多为站点签名算法要求），永不用于口令/密钥/宿主完整性判定；
 * 宿主自身哈希一律 SHA-256（见 :core:model）。
 */
internal object Md5 {
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    fun digest(input: ByteArray): ByteArray {
        var a0 = 0x67452301.toInt()
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476
        val bitLen = input.size.toLong() * 8L
        val paddedLen = ((input.size + 8) / 64 + 1) * 64
        val msg = ByteArray(paddedLen)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            msg[paddedLen - 8 + i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        }
        val m = IntArray(16)
        var off = 0
        while (off < paddedLen) {
            for (i in 0 until 16) {
                val j = off + i * 4
                m[i] = (msg[j].toInt() and 0xFF) or
                    ((msg[j + 1].toInt() and 0xFF) shl 8) or
                    ((msg[j + 2].toInt() and 0xFF) shl 16) or
                    ((msg[j + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                var f: Int
                var g = 0
                when {
                    i < 16 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }
                    i < 32 -> {
                        f = (d and b) or (d.inv() and c)
                        g = (5 * i + 1) % 16
                    }
                    i < 48 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) % 16
                    }
                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) % 16
                    }
                }
                // K[i] = floor(2^32 * abs(sin(i+1)))，运行时求值避免 64 常量转录。
                // 跨端确定性由三端同一向量断言锁定（若某端 libm 末位不同，单测即红）。
                val k = (abs(sin((i + 1).toDouble())) * 4294967296.0).toLong()
                    .toInt()
                f += a + k + m[g]
                a = d
                d = c
                c = b
                b += rotl(f, S[i])
            }
            a0 += a; b0 += b; c0 += c; d0 += d
            off += 64
        }
        fun Int.leBytes(): ByteArray = byteArrayOf(
            (this and 0xFF).toByte(),
            ((this ushr 8) and 0xFF).toByte(),
            ((this ushr 16) and 0xFF).toByte(),
            ((this ushr 24) and 0xFF).toByte(),
        )
        return a0.leBytes() + b0.leBytes() + c0.leBytes() + d0.leBytes()
    }

    fun hex(input: ByteArray): String {
        val dig = digest(input)
        val out = CharArray(dig.size * 2)
        val hex = "0123456789abcdef"
        dig.forEachIndexed { i, b ->
            out[i * 2] = hex[(b.toInt() ushr 4) and 0xF]
            out[i * 2 + 1] = hex[b.toInt() and 0xF]
        }
        return out.concatToString()
    }
}

/** Base64（RFC 4648 标准表；解码容忍空白，非法返回 null）。 */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val INV = IntArray(128) { -1 }.also {
        ALPHABET.forEachIndexed { i, c -> it[c.code] = i }
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val t = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(ALPHABET[(t shr 18) and 0x3F])
            sb.append(ALPHABET[(t shr 12) and 0x3F])
            sb.append(if (i + 1 < bytes.size) ALPHABET[(t shr 6) and 0x3F] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[t and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray? {
        val t = s.filterNot { it.isWhitespace() }
        if (t.isEmpty()) return ByteArray(0)
        if (t.length % 4 != 0) return null
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < t.length) {
            val c0 = t[i]; val c1 = t[i + 1]; val c2 = t[i + 2]; val c3 = t[i + 3]
            if (c0.code >= 128 || c1.code >= 128) return null
            val v0 = INV[c0.code]; val v1 = INV[c1.code]
            if (v0 < 0 || v1 < 0) return null
            val v2 = if (c2 == '=') 0 else {
                if (c2.code >= 128 || INV[c2.code] < 0) return null
                INV[c2.code]
            }
            val v3 = if (c3 == '=') 0 else {
                if (c3.code >= 128 || INV[c3.code] < 0) return null
                INV[c3.code]
            }
            val triple = (v0 shl 18) or (v1 shl 12) or (v2 shl 6) or v3
            out += ((triple shr 16) and 0xFF).toByte()
            if (c2 != '=') out += ((triple shr 8) and 0xFF).toByte()
            if (c3 != '=') out += (triple and 0xFF).toByte()
            i += 4
        }
        return out.toByteArray()
    }
}
