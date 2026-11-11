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

/**
 * HMAC-SHA256（Venera `Convert.hmacString(..., 'sha256')` 的宿主实现）。
 *
 * 复用 `:core:model` 已三端验证的纯 Kotlin SHA-256（[core.model.sha256HexBytes]），
 * 不引入新依赖；输出为小写 hex，与 Dart `Digest.toString()` 一致。
 * 密钥超过块长时先哈希压缩；只用于源脚本的站点签名，不用于宿主鉴权。
 */
internal object HmacSha256 {
    private const val BLOCK = 64

    fun hex(key: ByteArray, value: ByteArray): String {
        val k0 = if (key.size > BLOCK) sha256Bytes(key) else key
        val k = ByteArray(BLOCK) { i -> if (i < k0.size) k0[i] else 0 }
        val inner = ByteArray(BLOCK + value.size)
        val outer = ByteArray(BLOCK + 32)
        for (i in 0 until BLOCK) {
            val b = k[i].toInt() and 0xff
            inner[i] = (b xor 0x36).toByte()
            outer[i] = (b xor 0x5c).toByte()
        }
        value.copyInto(inner, BLOCK)
        sha256Bytes(inner).copyInto(outer, BLOCK)
        return core.model.sha256HexBytes(outer)
    }

    private fun sha256Bytes(bytes: ByteArray): ByteArray =
        hexToBytes(core.model.sha256HexBytes(bytes))

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((nibble(hex[i * 2]) shl 4) or nibble(hex[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun nibble(c: Char): Int = when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> 0
    }
}

/**
 * AES 解密（v4 U1 转正；korlibs-crypto 三端一致）。
 *
 * 约定（文档见 source-v4.d.ts `ConvertV4`）：
 * - 输入 value/key/iv 均为 Base64（与 `encodeBase64` 同一表；Venera 侧 ArrayBuffer 经 Base64 过桥）；
 * - key 长度 16/24/32 字节（AES-128/192/256），iv 16 字节（CBC/CFB/OFB；ECB 忽略）；
 * - 分组填充 PKCS7（Web 站点最常见；与 korlibs `CipherPadding.PKCS7Padding` 一致）；
 * - 输出为 UTF-8 字符串；任何失败（非法 Base64/长度/填充/异常）返回 null，
 *   由调用方按模式处理：垫片回空串保兼容，strictV4 抛 CONTRACT（禁静默伪造）。
 */
internal object AesCrypto {
    fun decryptEcb(valueB64: String, keyB64: String): String? = runCatching {
        val data = Base64Codec.decode(valueB64) ?: return null
        val key = Base64Codec.decode(keyB64) ?: return null
        if (key.size !in setOf(16, 24, 32) || data.isEmpty() || data.size % 16 != 0) return null
        val plain = korlibs.crypto.AES.decryptAesEcb(data, key, korlibs.crypto.CipherPadding.PKCS7Padding)
        plain.decodeToString()
    }.getOrNull()

    fun decryptCbc(valueB64: String, keyB64: String, ivB64: String): String? = runCatching {
        val data = Base64Codec.decode(valueB64) ?: return null
        val key = Base64Codec.decode(keyB64) ?: return null
        val iv = Base64Codec.decode(ivB64) ?: return null
        if (key.size !in setOf(16, 24, 32) || iv.size != 16 || data.isEmpty() || data.size % 16 != 0) return null
        val plain = korlibs.crypto.AES.decryptAesCbc(data, key, iv, korlibs.crypto.CipherPadding.PKCS7Padding)
        plain.decodeToString()
    }.getOrNull()

    fun decryptCfb(valueB64: String, keyB64: String, ivB64: String): String? = runCatching {
        val data = Base64Codec.decode(valueB64) ?: return null
        val key = Base64Codec.decode(keyB64) ?: return null
        val iv = Base64Codec.decode(ivB64) ?: return null
        if (key.size !in setOf(16, 24, 32) || iv.size != 16 || data.isEmpty()) return null
        val plain = korlibs.crypto.AES.decryptAesCfb(data, key, iv, korlibs.crypto.CipherPadding.PKCS7Padding)
        plain.decodeToString()
    }.getOrNull()

    fun decryptOfb(valueB64: String, keyB64: String, ivB64: String): String? = runCatching {
        val data = Base64Codec.decode(valueB64) ?: return null
        val key = Base64Codec.decode(keyB64) ?: return null
        val iv = Base64Codec.decode(ivB64) ?: return null
        if (key.size !in setOf(16, 24, 32) || iv.size != 16 || data.isEmpty()) return null
        val plain = korlibs.crypto.AES.decryptAesOfb(data, key, iv, korlibs.crypto.CipherPadding.PKCS7Padding)
        plain.decodeToString()
    }.getOrNull()
}
