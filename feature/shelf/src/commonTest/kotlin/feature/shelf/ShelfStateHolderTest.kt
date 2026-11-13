package feature.shelf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 书架状态持有者单测（thin：受控组件 + 单向 `submit`）。
 *
 * 在线 / 本地两路数据源在装配层合并后一次性提交，页面不持有仓储，
 * 因此这里只锁「提交即替换 + loading 透传」两件事。
 */
class ShelfStateHolderTest {

    private fun work(id: String, sourceKey: String = "ex.a") =
        ShelfWorkUi(workId = id, title = id, sourceKey = sourceKey)

    @Test
    fun submitReplacesWorksAndClearsLoading() {
        val holder = ShelfStateHolder()

        holder.submit(listOf(work("w1")), loading = true)
        assertTrue(holder.state.value.loading)
        assertEquals(listOf("w1"), holder.state.value.works.map { it.workId })

        holder.submit(listOf(work("w2")))
        assertFalse(holder.state.value.loading)
        assertEquals(listOf("w2"), holder.state.value.works.map { it.workId })
    }

    @Test
    fun emptySubmitProducesEmptyState() {
        val holder = ShelfStateHolder()
        holder.submit(emptyList())
        assertTrue(holder.state.value.works.isEmpty())
        assertFalse(holder.state.value.loading)
    }
}
