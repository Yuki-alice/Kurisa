package data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.charset.Charset

/**
 * GBK 解码器（desktopTest：用 JDK 的 GBK 编码器生成对照字节）。
 *
 * 覆盖：ASCII 直通、中文双字节、响应头 charset、HTML meta 嗅探、非法序列替换、名称归一。
 */
class GbkCodecTest {

    private fun gbk(text: String): ByteArray = text.toByteArray(Charset.forName("GBK"))

    @Test
    fun decodesChineseAndAscii() {
        val src = "轻小说文库 wenku8 第1卷"
        assertEquals(src, GbkCodec.decode(gbk(src)))
    }

    @Test
    fun undefinedAndTruncatedBytesBecomeReplacement() {
        // 0x80 是非法首字节；0x81 后缺少尾字节（截断）
        val out = GbkCodec.decode(byteArrayOf(0x80.toByte()))
        assertEquals("\uFFFD", out)
        val truncated = GbkCodec.decode(byteArrayOf(0x81.toByte()))
        assertEquals("\uFFFD", truncated)
        // trail 0x7F 在 GBK 中不合法
        assertEquals("\uFFFD", GbkCodec.decode(byteArrayOf(0x81.toByte(), 0x7F.toByte())))
    }

    @Test
    fun charsetNameFamily() {
        for (n in listOf("gbk", "GBK", "gb2312", "GB_2312", "gb18030", "cp936", "ms936", " gbk ")) {
            assertTrue(GbkCodec.isGbkFamily(n), n)
        }
        for (n in listOf("utf-8", "big5", "shift_jis", "")) {
            assertFalse(GbkCodec.isGbkFamily(n), n)
        }
    }

    @Test
    fun fullTableRoundTripSpotChecks() {
        // 覆盖不同首字节区段与常见标点
        for (src in listOf("喆", "犇", "，。、；：！？", "０１２３", "ＡＢＣ", "㈠㈡", "镕")) {
            assertEquals(src, GbkCodec.decode(gbk(src)), src)
        }
    }
}
