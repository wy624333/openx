package com.wy.openx.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考折叠态的淘汰上限。
 *
 * 背景：旧实现往 SharedPreferences 写「每条消息一个 key」（`thinking_fold_<msgId>`）且无上限。
 * SharedPreferences 首次访问把整表读进内存常驻、每次 `apply()` 再整体复制一份，
 * 于是长会话滚动到最后必然 `OutOfMemoryError`（栈在 `HashMap.putMapEntries`
 * ← `SharedPreferencesImpl$EditorImpl.apply`）。v2 换文件 + 只存用户手动改过的状态，
 * 这条淘汰逻辑是防止 v2 重蹈 v1 覆辙的兜底——删掉它，崩溃会慢慢回来。
 *
 * 契约：只管 `thinking_fold_` 前缀的 key；**外部 key 一律不删**。
 */
class ThinkingFoldKeysToDropTest {

    private fun keys(n: Int, startId: Long = 1000L) =
        (0 until n).map { "thinking_fold_${startId + it}" }.toSet()

    @Test
    fun `未超上限时不淘汰`() {
        assertTrue(thinkingFoldKeysToDrop(keys(10), 3000).isEmpty())
    }

    @Test
    fun `正好等于上限时不淘汰`() {
        assertTrue(thinkingFoldKeysToDrop(keys(100), 100).isEmpty())
    }

    @Test
    fun `超上限时按 msgId 从小到大淘汰`() {
        val drop = thinkingFoldKeysToDrop(keys(10, startId = 100L), 6)
        // 10 条、上限 6 → 淘汰最旧 4 条（id 100/101/102/103；id 随时间递增）
        assertEquals(
            listOf("thinking_fold_100", "thinking_fold_101", "thinking_fold_102", "thinking_fold_103"),
            drop
        )
    }

    @Test
    fun `淘汰后剩余条数正好等于上限`() {
        val n = 3500
        val drop = thinkingFoldKeysToDrop(keys(n), 3000)
        assertEquals(500, drop.size)
        assertEquals(3000, n - drop.size)
    }

    @Test
    fun `不误删非折叠态的外部 key`() {
        val mixed = keys(5) + setOf("some_other_flag", "another_key")
        val drop = thinkingFoldKeysToDrop(mixed, 2)
        assertTrue("外部 key 不属于本函数管辖，不能删", drop.none { !it.startsWith("thinking_fold_") })
    }

    @Test
    fun `解析不出 msgId 的脏 key 排在可解析之后淘汰`() {
        // 脏 key 排在最后：先按 msgId 由旧到新删，删到还超上限才轮到它。
        val mixed = keys(3) + setOf("thinking_fold_notanumber")
        val drop = thinkingFoldKeysToDrop(mixed, 1)
        // 4 个 key、上限 1 → 删 3 个：三个可解析的（1000/1001/1002），脏 key 留下
        assertEquals(
            listOf("thinking_fold_1000", "thinking_fold_1001", "thinking_fold_1002"),
            drop
        )
        assertEquals(1, mixed.size - drop.size)
    }

    @Test
    fun `上限为 0 时连脏 key 一起清掉`() {
        val mixed = keys(2) + setOf("thinking_fold_notanumber")
        val drop = thinkingFoldKeysToDrop(mixed, 0)
        assertEquals(3, drop.size)
        assertEquals(0, mixed.size - drop.size)
    }

    @Test
    fun `全是我们自己的 key 时删完一定不超上限`() {
        // 核心不变量。改动排序/过滤逻辑时，这条比逐个 case 更能挡住「漏删」。
        val cases = listOf(10 to 3, 3001 to 3000, 2 to 1, 1 to 0, 5000 to 3000)
        for ((n, max) in cases) {
            val input = keys(n)
            val drop = thinkingFoldKeysToDrop(input, max)
            val remaining = input.size - drop.size
            assertTrue("删完仍超上限：${input.size} → $remaining，上限 $max", remaining <= max)
            assertFalse("不得返回重复 key", drop.size != drop.toSet().size)
            assertEquals("不得返回不存在的 key", 0, drop.count { it !in input })
        }
    }
}
