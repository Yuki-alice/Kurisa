package data.localimport

import core.model.ChapterPayload
import data.images.ImageRequest
import korlibs.io.file.std.MemoryVfsMix
import korlibs.io.file.std.asMemoryVfsFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TAR（ustar 基本型）解包：纯 Kotlin 手写 512B 头块构造归档，不依赖任何归档库。
 * 覆盖白名单、ArchiveReader 分支、端到端 payload/抓取、非图片过滤、损坏头块健壮性。
 */
class TarArchiveTest {

    private val page1 = RealImageFixtures.mangaPage(32, 24, 200, 40, 40)
    private val page2 = RealImageFixtures.mangaPage(24, 32, 40, 120, 200)
    private val notes = "not an image".encodeToByteArray()

    /** 手写 512B ustar 基本头块：name / 八进制 size / typeflag / "ustar" magic。 */
    private fun header(name: String, size: Int, type: Char = '0'): ByteArray {
        val h = ByteArray(TarArchive.BLOCK)
        val nb = name.encodeToByteArray()
        nb.copyInto(h, 0, 0, minOf(nb.size, 100))
        val oct = size.toString(8).padStart(11, '0').encodeToByteArray()
        oct.copyInto(h, 124, 0, minOf(oct.size, 12)) // [124..136)：11 位八进制 + NUL
        h[156] = type.code.toByte()
        "ustar".encodeToByteArray().copyInto(h, 257) // [257..263)："ustar\0"
        return h
    }

    private data class Spec(val name: String, val data: ByteArray, val type: Char = '0')

    private fun buildTar(specs: List<Spec>): ByteArray {
        val out = ArrayList<Byte>()
        fun put(b: ByteArray) { out.addAll(b.toList()) }
        for (s in specs) {
            put(header(s.name, s.data.size, s.type))
            put(s.data)
            val pad = (TarArchive.BLOCK - s.data.size % TarArchive.BLOCK) % TarArchive.BLOCK
            repeat(pad) { out.add(0.toByte()) }
        }
        put(ByteArray(TarArchive.BLOCK * 2)) // 两个全零头块结束
        return ByteArray(out.size) { out[it] }
    }

    /** 2 张图片 + 1 个目录（typeflag '5'）+ 1 个 txt 非图片，图片按 tar 内顺序。 */
    private fun sampleTar(): ByteArray = buildTar(
        listOf(
            Spec("./p001.jpg", page1),
            Spec("p002.jpg", page2),
            Spec("sub/", ByteArray(0), '5'),
            Spec("notes.txt", notes),
        ),
    )

    /** 把首块的 size 字段替换为任意 ASCII 串，构造损坏/超大头块。 */
    private fun tarWithBrokenSize(sizeField: String): ByteArray {
        val h = header("p001.jpg", 4)
        val n = minOf(sizeField.length, 12)
        sizeField.encodeToByteArray().copyInto(h, 124, 0, n)
        for (i in 124 + n until 136) h[i] = 0.toByte() // 清掉残留八进制字符
        val out = ArrayList<Byte>()
        out.addAll(h.toList())
        out.addAll(ByteArray(TarArchive.BLOCK).toList()) // 占位数据区
        out.addAll(ByteArray(TarArchive.BLOCK * 2).toList()) // 全零结束块
        return ByteArray(out.size) { out[it] }
    }

    @Test
    fun tarIsSupported() {
        assertTrue(FormatDetector.isSupportedArchive(LocalFormat.TAR))
    }

    @Test
    fun readerListsAndReadsTarEntries() = runTest {
        val reader = ArchiveReader.open(sampleTar().asMemoryVfsFile("vol.cbt"))
        // 目录条目不收、非图片 txt 进 fileEntries 但不进 imageEntries。
        assertEquals(listOf("p001.jpg", "p002.jpg", "notes.txt"), reader.fileEntries())
        assertEquals(listOf("p001.jpg", "p002.jpg"), reader.imageEntries())
        // readEntry 与 imageEntries()/fileEntries() 返回的字符串完全同名可查。
        assertEquals(page1.toList(), reader.readEntry("p001.jpg").toList())
        assertEquals(page2.toList(), reader.readEntry("p002.jpg").toList())
        assertEquals(notes.toList(), reader.readEntry("notes.txt").toList())
    }

    @Test
    fun endToEndPayloadAndFetcher() = runTest {
        val fs = LocalSourceFileSystem(MemoryVfsMix("M/vol.cbt" to sampleTar()))
        val provider = LocalChapterProvider(fs) { it }
        val payload = provider.payload("M", "M/vol.cbt")
        require(payload is ChapterPayload.Images)
        assertEquals(listOf("M/vol.cbt!/p001.jpg", "M/vol.cbt!/p002.jpg"), payload.urls)
        // LocalImageFetcher 按 "!/" 反解调 readEntry，取回原始字节。
        val fetcher = LocalImageFetcher(fs)
        assertEquals(
            page1.toList(),
            fetcher.fetch(ImageRequest("M/vol.cbt!/p001.jpg", emptySet())).bytes.toList(),
        )
        assertEquals(
            page2.toList(),
            fetcher.fetch(ImageRequest("M/vol.cbt!/p002.jpg", emptySet())).bytes.toList(),
        )
    }

    @Test
    fun brokenSizeHeadersDoNotCrash() = runTest {
        // 非法八进制字符、以及溢出 Int 的超大八进制 size：优雅停止解析，不抛越界/格式化异常。
        for (field in listOf("ZZZZZZZZZZZ", "77777777777")) {
            val reader = ArchiveReader.open(tarWithBrokenSize(field).asMemoryVfsFile("bad.cbt"))
            assertTrue(reader.imageEntries().isEmpty())
            assertTrue(reader.fileEntries().isEmpty())
        }
    }

    @Test
    fun truncatedDataHeaderDoesNotCrash() = runTest {
        // 第二个头声明 1MB 数据但数据区远不足：停止解析并保留已解析的 p001.jpg。
        val out = ArrayList<Byte>()
        out.addAll(header("p001.jpg", page1.size).toList())
        out.addAll(page1.toList())
        val pad = (TarArchive.BLOCK - page1.size % TarArchive.BLOCK) % TarArchive.BLOCK
        repeat(pad) { out.add(0.toByte()) }
        out.addAll(header("p002.jpg", 1_000_000).toList())
        out.addAll(ByteArray(TarArchive.BLOCK * 2).toList())
        val reader = ArchiveReader.open(ByteArray(out.size) { out[it] }.asMemoryVfsFile("trunc.cbt"))
        assertEquals(listOf("p001.jpg"), reader.imageEntries())
        assertEquals(page1.toList(), reader.readEntry("p001.jpg").toList())
    }

    @Test
    fun archiveOverLimitThrows() {
        // 整文件字节上限：用小阈值验证「超限抛明确异常」，避免单测里真分配 128MB。
        assertFailsWith<UnknownFormatException> {
            TarArchive.parse(sampleTar(), maxBytes = 64)
        }
    }
}
