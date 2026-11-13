package app.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 下载分片目录名：`taskId` 由源返回的 workId/chapterId 拼成，属不可信输入，
 * 必须保证无法逃出下载根目录、且不同任务不会共享同一目录。
 */
class DownloadTaskDirNameTest {

    @Test
    fun keepsSafeCharsAndReplacesSeparators() {
        val name = downloadTaskDirName("src.a/work-1|chapter-2")
        assertTrue(name.startsWith("src_a_work-1_chapter-2-"), "实际：$name")
        assertTrue(name.all { it.isLetterOrDigit() || it == '_' || it == '-' }, "实际：$name")
    }

    @Test
    fun dotDotCannotTraverse() {
        val name = downloadTaskDirName("../../etc/passwd")
        assertFalse(name.contains(".."), "目录名不得保留 .. 段：$name")
        assertFalse(name.contains("/"), "目录名不得含路径分隔符：$name")
        assertFalse(name.contains("\\"), "目录名不得含反斜杠：$name")
        assertTrue(name.isNotBlank())
    }

    @Test
    fun blankFallsBackToPlaceholder() {
        assertTrue(downloadTaskDirName("").startsWith("_-"), downloadTaskDirName(""))
        assertTrue(downloadTaskDirName("///").startsWith("___-"), downloadTaskDirName("///"))
    }

    @Test
    fun differentTaskIdsNeverCollide() {
        // 规范化会把不同分隔符映射成 `_`，仅靠白名单两者会撞同一目录。
        assertNotEquals(
            downloadTaskDirName("a_b|c"),
            downloadTaskDirName("a|b_c"),
        )
    }

    @Test
    fun longIdsAreTruncatedButStillUnique() {
        val prefix = "x".repeat(300)
        val a = downloadTaskDirName(prefix + "1")
        val b = downloadTaskDirName(prefix + "2")
        assertTrue(a.length < 80, "长度：${a.length}")
        assertNotEquals(a, b)
        assertEquals(a, downloadTaskDirName(prefix + "1"), "同一 taskId 必须稳定映射")
    }
}
