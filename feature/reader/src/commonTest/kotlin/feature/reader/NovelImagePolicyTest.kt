package feature.reader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 小说文内图片 URL 策略（security-audit / MASVS-NETWORK、MASVS-PLATFORM）：
 * 阅读器层只放行 https 与本地归档虚拟路径，其余一律拦截为占位符，
 * 域名白名单与重定向复核仍由 SourceImageFetcher / UrlPolicy 在出口执行。
 */
class NovelImagePolicyTest {

    @Test
    fun httpsIsAllowed() {
        assertTrue(isAllowedNovelImageUrl("https://cdn.example.com/a.png"))
        assertTrue(isAllowedNovelImageUrl("HTTPS://cdn.example.com/a.png"))
    }

    @Test
    fun localArchiveVirtualPathIsAllowed() {
        assertTrue(isAllowedNovelImageUrl("M/book.epub!/OEBPS/i/a.jpg"))
    }

    @Test
    fun cleartextAndArbitraryLocalPathsAreBlocked() {
        assertFalse(isAllowedNovelImageUrl("http://cdn.example.com/a.png"))
        assertFalse(isAllowedNovelImageUrl("/images/a.png"))
        assertFalse(isAllowedNovelImageUrl("images/a.png"))
        assertFalse(isAllowedNovelImageUrl("file:///etc/passwd"))
        assertFalse(isAllowedNovelImageUrl("data:image/png;base64,AAAA"))
        assertFalse(isAllowedNovelImageUrl("javascript:alert(1)"))
    }
}
