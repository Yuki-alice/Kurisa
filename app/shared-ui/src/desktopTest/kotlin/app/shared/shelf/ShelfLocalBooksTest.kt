package app.shared.shelf

import feature.shelf.ShelfScreen

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import core.model.LocalBook
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * 书架本地书区 UI 测试（desktop，JVM）：列表渲染、点行打开、导入回调接线。
 * 原生文件对话框无法在无头环境弹出——选择器本身由平台壳提供可注入的 suspend lambda，此处只验证接线。
 */
class ShelfLocalBooksTest {

    @get:Rule
    val rule = createComposeRule()

    private fun book(id: String, title: String) = LocalBook(
        id = id,
        path = title,
        format = "epub",
        title = title,
        importedAtEpochMs = 1L,
    )

    @Test
    fun localBookRowTapOpensBook() {
        var opened: String? = null
        rule.setContent {
            ShelfScreen(
                localBooks = listOf(book("local:A", "A书")),
                onOpenLocalBook = { opened = it },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("A书").assertIsDisplayed()
        rule.onNodeWithText("A书").performClick()
        rule.waitForIdle()
        assertEquals("local:A", opened)
    }

    @Test
    fun importButtonWiresPickerToCallback() {
        var received: List<Pair<String, ByteArray>>? = null
        rule.setContent {
            ShelfScreen(
                localBooks = emptyList(),
                pickLocalFiles = { listOf("x.epub" to ByteArray(3)) },
                onImportFiles = { received = it },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("导入").assertIsDisplayed()
        rule.onNodeWithText("导入").performClick()
        rule.waitUntil(15_000) { received != null }
        assertEquals(listOf("x.epub"), received!!.map { it.first })
        assertEquals(3, received!!.single().second.size)
        // 落盘 + 扫描入库是 App 层职责（LocalBookImporter 单测覆盖）；此处只断言数据透传。
    }

    @Test
    fun deleteButtonCallsRemove() {
        var deleted: String? = null
        rule.setContent {
            ShelfScreen(
                localBooks = listOf(book("local:B", "B书")),
                onDeleteLocalBook = { deleted = it },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("删除").assertIsDisplayed()
        rule.onNodeWithText("删除").performClick()
        rule.waitForIdle()
        assertEquals("local:B", deleted)
    }

    @Test
    fun importEntryHiddenWithoutCallbacks() {
        rule.setContent {
            ShelfScreen()
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("导入").assertCountEquals(0)
    }
}
