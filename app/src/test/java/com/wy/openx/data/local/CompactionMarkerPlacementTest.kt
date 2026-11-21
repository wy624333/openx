package com.wy.openx.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩卡**位置**的回归：卡片必须紧贴在「触发压缩的那个工具」下面。
 *
 * ## 事故经过（两轮反向翻车）
 *
 * 第一轮：手动压缩过 → 恢复 → 再次工具循环就地压缩 → 卡片滑到会话**最末尾**。
 * 当时把 marker 改成「重排到保留尾之前」，算是修好了。
 *
 * 第二轮（真机反馈）：「触发压缩的执行工具位置往上数第 7 个工具下面显示卡片」。
 * 日志坐实：
 * ```
 * 21:34:36 工具循环: 触发 消息共160条 保留尾17条(≈3890tok) 折叠头134条
 * 21:35:13 就地压缩落库: 边界=113/128
 * 21:35:13 事后: boundary=114 压缩114条 保留14条
 * ```
 * 保留尾 14 条 slot 消息 ≈ **7 组工具**（每组 assistant(tool_calls) + tool(result)），
 * 与用户数的 7 个工具精确吻合 —— 卡片被那 14 条顶到了上方。
 *
 * ## 正确布局
 *
 * 对齐 AiCode-RC2：`ContextCompactor` 给 marker/摘要用 `nextTimestamp()`，严格晚于
 * **包括保留尾在内**的一切；`orderHistoryEntities` 那种「compaction 在前」只用于
 * **构造模型请求**，不用于展示。故：
 *
 * ```
 * 展示（按 seq）：折叠原文(原seq) … <保留尾(原seq)> → marker → summary → 压缩后新消息(seq=0)
 * 模型请求：      marker → summary → …保留尾…
 * ```
 */
class CompactionMarkerPlacementTest {

    private val MARKER = 900L
    private val SUMMARY = 901L

    /**
     * 复刻 `MessageDao.commitCompaction` 事务体的 seq 分配：
     * 折叠只改 folded 标记、**不动任何 seq**；marker/摘要取全表 MAX(seq)+1 递增。
     *
     * 每轮压缩都是**新建**一对 marker/摘要（id 不同），故 id 必须由调用方传入。
     */
    private fun assignSeqs(
        rows: List<Pair<Long, Long>>,
        foldIds: Set<Long>,
        markerId: Long,
        summaryId: Long
    ): Map<Long, Long> {
        val seqs = rows.toMap().toMutableMap()
        // 保留尾的 seq 保持原值（事务不再重排）——这是本次修复的核心。
        var seq = (rows.maxOfOrNull { it.second } ?: 0L) + 1
        seqs[markerId] = seq++
        seqs[summaryId] = seq++
        return seqs
    }

    /** 复刻 `AgentConsoleScreen.displayMessages`：按 (seq, id) 排，seq==0 排最后。 */
    private fun displayOrder(
        seqs: Map<Long, Long>,
        notPersisted: List<Long> = emptyList()
    ): List<Long> {
        val all = (seqs.keys + notPersisted).map { id ->
            Triple(if (id in notPersisted) Long.MAX_VALUE else seqs.getValue(id), id, id)
        }
        return all.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    @Test
    fun `卡片紧贴触发压缩的工具而不是被保留尾顶上去`() {
        // 就地压缩：折叠 1..98，保留尾 99..119（最后一条 119 即触发压缩的那个工具）
        val rows = (1L..119L).map { it to it }
        val seqs = assignSeqs(rows, (1L..98L).toSet(), MARKER, SUMMARY)
        val order = displayOrder(seqs)

        assertEquals("摘要紧跟 marker", order.indexOf(MARKER) + 1, order.indexOf(SUMMARY))
        assertEquals(
            "marker 必须紧跟在保留尾最后一条(119=触发工具)之后",
            order.indexOf(119L) + 1,
            order.indexOf(MARKER)
        )
    }

    @Test
    fun `真机场景 保留尾七组工具时卡片不再上移`() {
        // 复刻 21:34:36 那次：保留尾 14 条 ≈ 7 组工具，触发工具是最后一条。
        val tail = (115L..128L).toList()
        val rows = (1L..128L).map { it to it }
        val seqs = assignSeqs(rows, (1L..114L).toSet(), MARKER, SUMMARY)
        val order = displayOrder(seqs)

        // 卡片下方不得再出现任何「压缩前就存在」的消息
        val afterMarker = order.drop(order.indexOf(MARKER) + 1)
        assertTrue(
            "marker 之后不该再有保留尾消息，实际尾部=$afterMarker",
            tail.none { it in afterMarker }
        )
        assertEquals("触发工具(128)与卡片相邻", order.indexOf(128L) + 1, order.indexOf(MARKER))
    }

    @Test
    fun `压缩后新到的消息显示在卡片下面`() {
        val rows = (1L..10L).map { it to it }
        val seqs = assignSeqs(rows, (1L..6L).toSet(), MARKER, SUMMARY)
        // 压缩后 AI 继续执行工具，这些消息尚未落库（seq==0）
        val order = displayOrder(seqs, notPersisted = listOf(5001L, 5002L))

        assertEquals(
            "顺序应为 折叠原文 → 保留尾 → marker → 摘要 → 新消息",
            listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, MARKER, SUMMARY, 5001L, 5002L),
            order
        )
    }

    @Test
    fun `折叠原文与保留尾的相对顺序都不变`() {
        val rows = (1L..119L).map { it to it }
        val seqs = assignSeqs(rows, (1L..98L).toSet(), MARKER, SUMMARY)
        val order = displayOrder(seqs)

        assertEquals("折叠原文顺序不变", (1L..98L).toList(), order.filter { it in 1L..98L })
        assertEquals("保留尾顺序不变", (99L..119L).toList(), order.filter { it in 99L..119L })
    }

    @Test
    fun `seq 唯一且不与既有消息重叠`() {
        val rows = (1L..119L).map { it to it }
        val seqs = assignSeqs(rows, (1L..98L).toSet(), MARKER, SUMMARY)
        assertEquals("seq 必须唯一", seqs.size, seqs.values.toSet().size)
        assertEquals("折叠原文仍占 1..98", 98L, seqs[98L])
        assertEquals("marker 取 MAX+1", 120L, seqs[MARKER])
        assertEquals("摘要取 MAX+2", 121L, seqs[SUMMARY])
    }

    @Test
    fun `二次压缩时上一轮的卡片仍留在它原来的折叠位置`() {
        val MARKER2 = 902L
        val SUMMARY2 = 903L
        // 第一轮：折叠 1..98，marker=900/summary=901 拿到 120/121
        val first = assignSeqs((1L..119L).map { it to it }, (1L..98L).toSet(), MARKER, SUMMARY)
        // 第一轮之后又来了 4 条工具消息，落库拿到 122..125
        val after = mutableMapOf<Long, Long>().apply {
            putAll(first)
            (1L..4L).forEach { put(2000L + it, 122L + it) }
        }
        // 第二轮**新建**一对产物：把 1..121（含上一轮 marker/摘要）折进去，保留尾 122..125
        val second = assignSeqs(
            after.entries.map { it.key to it.value },
            (1L..121L).toSet(),
            MARKER2, SUMMARY2
        )
        val order = displayOrder(second)

        // 上一轮产物已成折叠原文，位置不动
        assertTrue(order.indexOf(MARKER) < order.indexOf(2001L))
        // 新卡片紧贴新的触发工具
        assertEquals(order.indexOf(2004L) + 1, order.indexOf(MARKER2))
    }

    @Test
    fun `全折叠时保留尾为空也不出错`() {
        val rows = (1L..5L).map { it to it }
        val seqs = assignSeqs(rows, (1L..5L).toSet(), MARKER, SUMMARY)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, MARKER, SUMMARY), displayOrder(seqs))
    }
}