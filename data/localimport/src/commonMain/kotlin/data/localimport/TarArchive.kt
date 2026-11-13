package data.localimport

/**
 * 纯 Kotlin 的 ustar（基本型）TAR 解析，零第三方依赖，commonMain 三端一致。
 *
 * 仅支持 512 字节头块的基本型 TAR：
 * - name[0..100) NUL 截断；size[124..136) 八进制 ASCII；typeflag[156]；
 *   magic[257..263] 作宽松校验（见 [hasUstarMagic]）。
 * - 只收普通文件（typeflag '0' 或 NUL）。目录（'5'）、符号链接（'2'）、
 *   pax/GNU 扩展头（'x'/'g'/'L'/'K'）一律跳过：这些扩展头会覆盖后续条目名或
 *   携带长名，基本型解析故意不实现，避免把元数据块误当成漫画页。
 * - 名字按 Latin-1 逐字节还原（ustar 基本型名字本就是 ASCII），带 UTF-8 长名的
 *   pax/GNU 归档不在支持范围内；条目名去掉前导 "./"。
 *
 * 内存取舍：整档字节仅保留一份，条目只存 [TarEntry] 的 name + 数据区 offset/size
 * （懒读索引）；[read] 时才 copyOfRange 出单个条目，与 zip 分支「按需解包单条目」
 * 语义一致，避免把全部图片各拷一份造成内存翻倍。
 */
data class TarEntry(val name: String, val offset: Int, val size: Int)

class TarArchive private constructor(
    private val bytes: ByteArray,
    val entries: List<TarEntry>,
) {
    /** 条目名（归档内顺序）。 */
    fun names(): List<String> = entries.map { it.name }

    /** 按名懒读单个条目；未命中返回 null。 */
    fun read(name: String): ByteArray? {
        val e = entries.firstOrNull { it.name == name } ?: return null
        return bytes.copyOfRange(e.offset, e.offset + e.size)
    }

    companion object {
        const val BLOCK = 512
        /** 整档字节上限，防止超大/伪造 length 把内存吃满。 */
        const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024

        /** 头块 magic[257..263] 宽松校验：命中 "ustar" 认定是 TAR。 */
        fun hasUstarMagic(bytes: ByteArray): Boolean {
            if (bytes.size < 263) return false
            return bytes[257] == 'u'.code.toByte() &&
                bytes[258] == 's'.code.toByte() &&
                bytes[259] == 't'.code.toByte() &&
                bytes[260] == 'a'.code.toByte() &&
                bytes[261] == 'r'.code.toByte()
        }

        /**
         * 解析整档字节；[maxBytes] 为整文件字节上限，超限抛明确异常。
         * 损坏/越界头块优雅停止解析（保留已解析条目），不抛越界/格式化异常。
         */
        fun parse(bytes: ByteArray, maxBytes: Int = MAX_ARCHIVE_BYTES): TarArchive {
            if (bytes.size > maxBytes) {
                throw UnknownFormatException("TAR 超过字节上限 $maxBytes: ${bytes.size}")
            }
            val entries = ArrayList<TarEntry>()
            var pos = 0
            while (pos + BLOCK <= bytes.size) {
                if (isZeroBlock(bytes, pos)) break // 全零头块：归档结束
                val name = parseName(bytes, pos)
                // size 非八进制 / 溢出即视为损坏头块，停止解析且不崩。
                val size = parseOctal(bytes, pos + 124, 12) ?: break
                val dataOffset = pos + BLOCK
                // 数据区越界（含头块声明远超剩余字节）视为损坏，停止解析。
                if (size < 0 || dataOffset.toLong() + size > bytes.size.toLong()) break
                val type = bytes[pos + 156]
                if ((type == 0.toByte() || type == '0'.code.toByte()) && name.isNotEmpty()) {
                    entries += TarEntry(name, dataOffset, size)
                }
                // 数据区按 512 向上对齐；目录/长名/symlink 等跳过但同样前进。
                val padded = (((size.toLong() + BLOCK - 1) / BLOCK) * BLOCK).toInt()
                pos = dataOffset + padded
            }
            return TarArchive(bytes, entries)
        }

        private fun isZeroBlock(bytes: ByteArray, off: Int): Boolean {
            val end = minOf(off + BLOCK, bytes.size)
            for (i in off until end) if (bytes[i] != 0.toByte()) return false
            return true
        }

        /** name[0..100) 逐字节还原，NUL 截断，去掉前导 "./"。 */
        private fun parseName(bytes: ByteArray, off: Int): String {
            val sb = StringBuilder()
            val end = minOf(off + 100, bytes.size)
            var i = off
            while (i < end && bytes[i] != 0.toByte()) {
                sb.append((bytes[i].toInt() and 0xFF).toChar())
                i++
            }
            return sb.toString().removePrefix("./")
        }

        /** size[124..136) 八进制 ASCII；空格/NUL 填充，非法字符返回 null。 */
        private fun parseOctal(bytes: ByteArray, off: Int, len: Int): Int? {
            var value = 0L
            var seen = false
            val end = minOf(off + len, bytes.size)
            for (i in off until end) {
                val c = bytes[i].toInt() and 0xFF
                if (c == 0 || c == 0x20) {
                    if (seen) break
                } else if (c in 0x30..0x37) {
                    seen = true
                    value = value * 8 + (c - 0x30)
                    if (value > Int.MAX_VALUE) return null
                } else {
                    return null
                }
            }
            return value.toInt()
        }
    }
}
