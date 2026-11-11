package source.registry

/** Base64（RFC 4648，标准表），供 ed25519 签名（架构：base64）编解码。 */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val INV = IntArray(128) { -1 }.also {
        ALPHABET.forEachIndexed { i, c -> it[c.code] = i }
    }

    fun decode(s: String): ByteArray? {
        val t = s.filterNot { it.isWhitespace() }
        if (t.isEmpty()) return ByteArray(0)
        if (t.length % 4 != 0) return null
        val pad = when {
            t.endsWith("==") -> 2
            t.endsWith("=") -> 1
            else -> 0
        }
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < t.length) {
            val c0 = if (i < t.length) t[i] else 'A'
            val c1 = if (i + 1 < t.length) t[i + 1] else 'A'
            val c2 = if (i + 2 < t.length) t[i + 2] else 'A'
            val c3 = if (i + 3 < t.length) t[i + 3] else 'A'
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
        // 去掉 pad 多算的字节（上式已按 '=' 跳过，长度天然正确）
        return out.toByteArray()
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(ALPHABET[(triple shr 18) and 0x3F])
            sb.append(ALPHABET[(triple shr 12) and 0x3F])
            sb.append(if (i + 1 < bytes.size) ALPHABET[(triple shr 6) and 0x3F] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[triple and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }
}
