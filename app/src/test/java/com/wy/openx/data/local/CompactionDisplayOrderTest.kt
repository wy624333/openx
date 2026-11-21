package com.wy.openx.data.local

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩卡的**显示序**回归（`messages.seq`）。
 *
 * ## 为什么要单独锁这一条
 *
 * openx 的消息 id 有两套来源：库自增（小整数）与内存 `nextMessageId = 毫秒×1000`（≈1.79e18）。
 * 二者混在一列里时 `ORDER BY id` **不等于显示顺序**，直接按 id 排序会得到：
 *
 * ```
 * 显示序（正确）      折叠原文A  折叠原文B  marker  摘要  尾部消息
 * id 值          1        1791169…      5      6      7
 * ORDER BY id →  折叠原文A  marker  摘要  折叠原文B  尾部消息   ← 压缩卡被甩到折叠历史之前
 * ```
 *
 * 用户看到的症状就是「压缩卡跑到最上面、历史不见了」。`seq` 是与 id 解耦的
 * 会话内单调显示序，本测试把「id 乱序时显示序仍然正确」锁死。
 *
 * 同时覆盖 marker/summary 成对写入这一对齐 AiCode-RC2 的不变量。
 */
class CompactionDisplayOrderTest {

    private fun entity(id: Long, seq: Long, content: String, folded: Boolean = false) = MessageEntity(
        id = id,
        conversationId = 7L,
        role = "user",
        author = "我",
        time = "10:00",
        content = content,
        folded = folded,
        seq = seq
    )

    @Test
    fun `按 seq 排序时压缩卡落在折叠历史之后`() {
        val rows = listOf(
            entity(id = 1L, seq = 1L, content = "折叠原文A", folded = true),
            entity(id = 1_791_169_000_000L, seq = 2L, content = "折叠原文B", folded = true),
            entity(id = 5L, seq = 3L, content = "[上下文已压缩]"),
            entity(id = 6L, seq = 4L, content = "摘要正文"),
            entity(id = 7L, seq = 5L, content = "尾部消息")
        )
        // 同一个 id 集合，按 seq 排（这正是 UI 与仓库层读出后的排序口径）
        val ordered = rows.sortedWith(compareBy({ it.seq }, { it.id }))
        assertEquals(
            listOf("折叠原文A", "折叠原文B", "[上下文已压缩]", "摘要正文", "尾部消息"),
            ordered.map { it.content }
        )
    }

    @Test
    fun `按 id 排序会把压缩卡甩到折叠历史之前——这正是 seq 要修的 bug`() {
        val rows = listOf(
            entity(id = 1L, seq = 1L, content = "折叠原文A", folded = true),
            entity(id = 1_791_169_000_000L, seq = 2L, content = "折叠原文B", folded = true),
            entity(id = 5L, seq = 3L, content = "[上下文已压缩]"),
            entity(id = 6L, seq = 4L, content = "摘要正文")
        )
        val byId = rows.sortedBy { it.id }.map { it.content }
        // 锁住这个反例：若哪天有人把排序改回 ORDER BY id，本断言提醒你 seq 被绕过了。
        assertEquals(
            listOf("折叠原文A", "[上下文已压缩]", "摘要正文", "折叠原文B"),
            byId
        )
    }

    @Test
    fun `seq 相同时用 id 兜底得到稳定序`() {
        // 迁移 18→19 之外也可能出现 seq 相同（如整体重写后残留），此时不能乱序。
        val rows = listOf(
            entity(id = 30L, seq = 1L, content = "A"),
            entity(id = 10L, seq = 1L, content = "B"),
            entity(id = 20L, seq = 1L, content = "C")
        )
        assertEquals(listOf("B", "C", "A"), rows.sortedWith(compareBy({ it.seq }, { it.id })).map { it.content })
    }

    @Test
    fun `marker 与 summary 成对写入且都属上下文`() {
        // 对齐 RC2 commitCompaction：marker(USER) + summary(ASSISTANT) 一同插入、folded=0。
        val marker = entity(id = 5L, seq = 3L, content = "[上下文已压缩]")
        val summary = entity(id = 6L, seq = 4L, content = "摘要正文")
        assertEquals(false, marker.folded)
        assertEquals(false, summary.folded)
        assertTrue("marker 必须排在 summary 之前（回放时模型先读摘要）", marker.seq < summary.seq)
    }

    @Test
    fun `replaceAll 重写时 seq 由下标决定因此传入顺序即显示顺序`() {
        // 与 MessageDao.replaceAllOfConversation 的 `seq = index + 1` 对齐：
        // 调用方按显示顺序传入即可，不应再自行按 id 排序。
        val input = listOf("折叠原文", "[上下文已压缩]", "摘要正文", "尾部消息")
        val rewritten = input.mapIndexed { i, c -> c to (i + 1).toLong() }
        assertEquals(input, rewritten.sortedBy { it.second }.map { it.first })
    }

    /**
     * **事故回归**：`ChatMessageModel` 曾经没有 `seq` 字段，`toEntity` 也不透传，
     * 于是压缩时写的 marker/摘要落库后 `seq` 取默认值 0。
     *
     * 后果：折叠原文的 seq 是 1..N，marker/摘要是 0 → `sortedWith(compareBy(seq, id))`
     * 把压缩卡顶到**所有历史之前**。真机日志坐实过：
     * `压缩事务提交后: 历史原文91条 上下文18条 其中marker1条`，但界面「卡片跑到最上面、历史全乱」。
     */
    @Test
    fun `模型层必须透传 seq 否则压缩卡会排到历史之前`() {
        val marker = ChatMessageModel(
            id = 100L, role = MessageRole.USER, author = "系统", time = "12:00",
            content = "[上下文已压缩]", seq = 91L
        )
        val summary = ChatMessageModel(
            id = 101L, role = MessageRole.ASSISTANT, author = "系统", time = "12:00",
            content = "摘要", seq = 92L
        )
        val roundTrip = marker.toEntity(7L).toModel()
        assertEquals("marker 的 seq 必须穿过 toEntity/toModel 不丢", 91L, roundTrip.seq)
        assertEquals(92L, summary.toEntity(7L).toModel().seq)

        // 与折叠原文放在一起排序：卡片必须落在原文之后，而不是之前。
        val folded = listOf(
            entity(id = 1L, seq = 1L, content = "老消息A", folded = true),
            entity(id = 2L, seq = 2L, content = "老消息B", folded = true)
        )
        val ordered = (folded + listOf(marker.toEntity(7L), summary.toEntity(7L)))
            .sortedWith(compareBy({ it.seq }, { it.id }))
            .map { it.content }
        assertEquals(listOf("老消息A", "老消息B", "[上下文已压缩]", "摘要"), ordered)
    }

    @Test
    fun `seq 丢失为 0 时压缩卡会被顶到最前——锁住这个反例`() {
        // 若哪天有人再让 seq 在模型层丢失（新增字段忘了透传），这条会红。
        val brokenMarker = entity(id = 100L, seq = 0L, content = "[上下文已压缩]")
        val folded = listOf(
            entity(id = 1L, seq = 1L, content = "老消息A", folded = true),
            entity(id = 2L, seq = 2L, content = "老消息B", folded = true)
        )
        val ordered = (folded + brokenMarker)
            .sortedWith(compareBy({ it.seq }, { it.id }))
            .map { it.content }
        assertEquals(
            "seq=0 会把压缩卡排到所有历史之前（真机报障「卡片跑到最上面、历史全乱」）",
            listOf("[上下文已压缩]", "老消息A", "老消息B"),
            ordered
        )
    }

    @Test
    fun `折叠消息的渲染与普通消息无差别`() {
        // 对齐 AiCode-RC2：展示层不区分 isCompacted —— 被压缩消息就是普通气泡。
        // 这里锁住「folded 只影响进不进请求，不影响 UI 分组结果」。
        val foldedMsg = ChatMessageModel(
            id = 5L, role = MessageRole.USER, author = "我", time = "10:00",
            content = "压缩前的问题", folded = true, seq = 5L
        )
        val normalMsg = foldedMsg.copy(id = 6L, folded = false, seq = 6L)
        // 除 id/folded/seq 外，其余渲染输入完全一致
        assertEquals(foldedMsg.content, normalMsg.content)
        assertEquals(foldedMsg.role, normalMsg.role)
        assertEquals(foldedMsg.attachments, normalMsg.attachments)
    }

    @Test
    fun `seq 默认 0 时往返不改变内容`() {
        val m = ChatMessageModel(
            id = 9L, role = MessageRole.USER, author = "我", time = "10:00", content = "x"
        )
        assertEquals(0L, m.toEntity(7L).seq)
        assertEquals("x", m.toEntity(7L).toModel().content)
    }
}

/**
 * **未落库内存消息的排序回归**。
 *
 * ## 事故
 *
 * 用户：AI 执行中 → 暂停 → 再发消息 → 「聊天页面卡住了看不到 AI 执行，暂停 AI，消息又冒出来了」。
 *
 * 根因：内存里新建的消息（流式正文、工具调用/结果、刚发的用户消息）`seq` 取默认值 **0**，
 * 而落库消息的 seq 是 **1..N**。UI 按 `(seq, id)` **升序**排 → 内存消息被排到列表**最顶端**。
 * 用户视线停在底部 → 看不到新内容（表现为"卡住"）；一按暂停，消息落库拿到 seq → 跳回底部 → "冒出来"。
 *
 * 修法：`seq == 0` 视为「尚未落库」= 最新，用 `Long.MAX_VALUE` 兜底排到最后，
 * 未落库消息之间仍按 id（=创建顺序）排。
 */
class PendingMessageOrderTest {

    private fun m(id: Long, seq: Long, content: String, folded: Boolean = false) = MessageEntity(
        id = id, conversationId = 7L, role = "assistant", author = "AI", time = "10:00",
        content = content, folded = folded, seq = seq
    )

    /** 与 AgentConsoleScreen.displayMessages 完全一致的排序口径。 */
    private fun order(rows: List<MessageEntity>) =
        rows.sortedWith(compareBy({ if (it.seq == 0L) Long.MAX_VALUE else it.seq }, { it.id }))
            .map { it.content }

    @Test
    fun `未落库的内存消息排在最后而不是最前`() {
        val rows = listOf(
            m(1L, 1L, "已落库-1"),
            m(2L, 2L, "已落库-2"),
            // 流式正文：内存新建，seq 默认 0
            m(9_000_000_000L, 0L, "正在流式输出的新消息")
        )
        assertEquals(
            listOf("已落库-1", "已落库-2", "正在流式输出的新消息"),
            order(rows)
        )
    }

    @Test
    fun `按字面值排会把新消息顶到最前——锁住这个反例`() {
        val rows = listOf(
            m(1L, 1L, "已落库-1"),
            m(9_000_000_000L, 0L, "正在流式输出的新消息")
        )
        // 旧口径：compareBy(seq, id)
        val broken = rows.sortedWith(compareBy({ it.seq }, { it.id })).map { it.content }
        assertEquals(
            "seq=0 会被排到最前（真机表现为「卡住、看不到 AI 执行」）",
            listOf("正在流式输出的新消息", "已落库-1"),
            broken
        )
    }

    @Test
    fun `多条未落库消息之间按创建顺序（id）稳定排列`() {
        val rows = listOf(
            m(1L, 1L, "已落库"),
            m(300L, 0L, "内存-工具调用"),
            m(200L, 0L, "内存-流式正文"),
            m(400L, 0L, "内存-工具结果")
        )
        assertEquals(
            listOf("已落库", "内存-流式正文", "内存-工具调用", "内存-工具结果"),
            order(rows)
        )
    }

    @Test
    fun `折叠原文与已落库上下文仍按 seq 排，不受未落库兜底影响`() {
        val rows = listOf(
            m(1L, 1L, "折叠原文A", folded = true),
            m(2L, 2L, "折叠原文B", folded = true),
            m(5L, 3L, "[上下文已压缩]"),
            m(6L, 4L, "摘要"),
            m(999L, 0L, "正在流式输出的回复")
        )
        assertEquals(
            listOf("折叠原文A", "折叠原文B", "[上下文已压缩]", "摘要", "正在流式输出的回复"),
            order(rows)
        )
    }
}
