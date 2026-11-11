package source.registry

import core.model.sha256HexBytes
import core.model.toUtf8Bytes

/**
 * 源签名校验（架构 4.8）。
 *
 * 被签名消息（冻结语义）：`"$key|$version|$sha256".toUtf8Bytes()`。
 * 绑定 key+version+hash，防止跨源/跨版本混用签名。
 * - `sha256` 为 `.js` 文件 UTF-8 字节的 sha256 hex（大小写不敏感）；
 * - `signature` 为 base64 ed25519（缺失时标记未验证，兼容旧仓库）；
 * - 公钥为仓库添加时绑定的 32 字节 hex（TOFU，指纹展示由 UI 层负责）。
 */
object SourceSignature {
    const val MAX_SCRIPT_BYTES = 512 * 1024

    fun sha256OfScript(scriptUtf8: ByteArray): String =
        sha256HexBytes(scriptUtf8)

    fun signedMessage(key: String, version: String, sha256: String): ByteArray =
        "$key|$version|${sha256.lowercase()}".toUtf8Bytes()

    fun verifySha256(scriptUtf8: ByteArray, expectedHex: String): Boolean =
        sha256OfScript(scriptUtf8).equals(expectedHex.trim(), ignoreCase = true)

    fun verifySignature(pubKeyHex: String, message: ByteArray, signatureB64: String): Boolean {
        val pub = hexToBytes(pubKeyHex) ?: return false
        if (pub.size != 32) return false
        val sig = Base64Codec.decode(signatureB64) ?: return false
        if (sig.size != 64) return false
        return Ed25519.verify(pub, message, sig)
    }

    fun hexToBytes(hex: String): ByteArray? {
        val t = hex.trim()
        if (t.length % 2 != 0) return null
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(t.length / 2) { i ->
            ((t[i * 2].digitToInt(16) shl 4) or t[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = hex[(b.toInt() ushr 4) and 0xF]
            out[i * 2 + 1] = hex[b.toInt() and 0xF]
        }
        return out.concatToString()
    }

    private fun sha256HexBytesLocal(bytes: ByteArray): String = sha256HexBytes(bytes)
}

private fun ByteArray.decodeToStringSafe(): String {
    // UTF-8 解码（脚本已是 UTF-8 文本，此处仅为复用 core 的 String 入口）
    var i = 0
    val sb = StringBuilder()
    while (i < size) {
        val b0 = this[i].toInt() and 0xFF
        when {
            b0 < 0x80 -> { sb.append(b0.toChar()); i++ }
            b0 < 0xE0 && i + 1 < size -> {
                val c = ((b0 and 0x1F) shl 6) or ((this[i + 1].toInt() and 0x3F))
                sb.append(c.toChar()); i += 2
            }
            b0 < 0xF0 && i + 2 < size -> {
                val c = ((b0 and 0x0F) shl 12) or (((this[i + 1].toInt() and 0x3F)) shl 6) or ((this[i + 2].toInt() and 0x3F))
                sb.append(c.toChar()); i += 3
            }
            i + 3 < size -> {
                val cp = ((b0 and 0x07) shl 18) or (((this[i + 1].toInt() and 0x3F)) shl 12) or
                    (((this[i + 2].toInt() and 0x3F)) shl 6) or ((this[i + 3].toInt() and 0x3F))
                val v = cp - 0x10000
                sb.append(((v shr 10) + 0xD800).toChar())
                sb.append(((v and 0x3FF) + 0xDC00).toChar())
                i += 4
            }
            else -> { sb.append('?'); i++ }
        }
    }
    return sb.toString()
}
