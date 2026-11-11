package source.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import korlibs.crypto.sha1
import korlibs.crypto.sha512

class CryptoTest {

    @Test
    fun md5Vectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(byteArrayOf()))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc".toUtf8BytesCompat()))
        assertEquals("5d41402abc4b2a76b9719d911017c592", Md5.hex("hello".toUtf8BytesCompat()))
    }

    @Test
    fun hmacSha256Rfc4231Vectors() {
        // RFC 4231 TC1 / TC2：确保 Venera Convert.hmacString 的宿主实现三端一致。
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            HmacSha256.hex(ByteArray(20) { 0x0b.toByte() }, "Hi There".toUtf8BytesCompat()),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            HmacSha256.hex(
                "Jefe".toUtf8BytesCompat(),
                "what do ya want for nothing?".toUtf8BytesCompat(),
            ),
        )
    }

    @Test
    fun base64RoundTrip() {
        assertEquals("", Base64Codec.encode(byteArrayOf()))
        assertEquals("TWFu", Base64Codec.encode("Man".toUtf8BytesCompat()))
        assertEquals("TWE=", Base64Codec.encode("Ma".toUtf8BytesCompat()))
        assertEquals("TQ==", Base64Codec.encode("M".toUtf8BytesCompat()))
        assertEquals(listOf<Byte>(77, 97, 110), Base64Codec.decode("TWFu")!!.toList())
        assertEquals(null, Base64Codec.decode("!!!"))
        assertEquals(null, Base64Codec.decode("abc"))
    }

    @Test
    fun convertFacade() {
        val c = CryptoHostConvert()
        assertEquals("900150983cd24fb0d6963f7d28e17f72", c.md5("abc"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            c.sha256("abc"),
        )
        assertEquals("TWFu", c.encodeBase64("Man".toUtf8BytesCompat()))
        assertEquals("Man", c.decodeBase64("TWFu").decodeToStringCompat())
        assertFailsWith<IllegalArgumentException> { c.decodeBase64("!!!") }
    }

    @Test
    fun sha1Rfc3174Vectors() {
        // RFC 3174：空串 / 短串 / 56 字节边界，确保 Convert.sha1 三端一致。
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", ByteArray(0).sha1().hexLower)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", "abc".toUtf8BytesCompat().sha1().hexLower)
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toUtf8BytesCompat().sha1().hexLower,
        )
    }

    @Test
    fun sha512Fips180Vectors() {
        // FIPS 180-4 空串/短串（经 JDK SunJCE 与 korlibs 双实现交叉验证），确保 Convert.sha512 三端一致。
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            ByteArray(0).sha512().hexLower,
        )
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            "abc".toUtf8BytesCompat().sha512().hexLower,
        )
    }

    @Test
    fun novelStrip() {
        val n = NovelHost()
        assertEquals(
            "标题\n第一段 第二段",
            n.stripHtml("<h1>标题</h1><script>evil()</script><p>第一段&nbsp;第二段</p>"),
        )
        assertEquals("", n.stripHtml("<div></div>"))
    }

    @Test
    fun htmlDisposeSemantics() {
        val h = KsoupHostHtml()
        val id = h.parse("<div><a href=\"/x\">T</a></div>")
        val els = h.select(id, "a")
        assertEquals(1, els.size)
        assertEquals("T", els[0]["text"])
        assertEquals("/x", els[0]["attr:href"])
        h.dispose(id)
        assertEquals(0, h.liveCount())
        assertFailsWith<IllegalStateException> { h.select(id, "a") }
    }
}

private fun String.toUtf8BytesCompat(): ByteArray {
    val out = ByteArray(length * 3)
    var n = 0
    for (ch in this) {
        val c = ch.code
        when {
            c < 0x80 -> out[n++] = c.toByte()
            c < 0x800 -> {
                out[n++] = (0xC0 or (c shr 6)).toByte()
                out[n++] = (0x80 or (c and 0x3F)).toByte()
            }
            else -> {
                out[n++] = (0xE0 or (c shr 12)).toByte()
                out[n++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                out[n++] = (0x80 or (c and 0x3F)).toByte()
            }
        }
    }
    return out.copyOf(n)
}

private fun ByteArray.decodeToStringCompat(): String {
    val sb = StringBuilder()
    var i = 0
    while (i < size) {
        val b = this[i].toInt() and 0xFF
        when {
            b < 0x80 -> {
                sb.append(b.toChar())
                i++
            }
            b < 0xE0 -> {
                sb.append(((b and 0x1F) shl 6 or (this[i + 1].toInt() and 0x3F)).toChar())
                i += 2
            }
            else -> {
                sb.append(
                    ((b and 0x0F) shl 12 or ((this[i + 1].toInt() and 0x3F) shl 6) or (this[i + 2].toInt() and 0x3F)).toChar()
                )
                i += 3
            }
        }
    }
    return sb.toString()
}
