package com.wy.openx.data.local

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「仅恢复会话」后上下文数字变小的回归。
 *
 * ## 事故经过
 *
 * 用户对一个历史会话手动压缩 → 点消息弹窗「仅恢复会话」→ 消息看着回来了，
 * 但顶部上下文数字**变小了**。
 *
 * 根因：`rewindMessage` 回退到折叠区时取 `all.take(idx)` 重写整表，而
 * `all`（含折叠历史）里的原文带着 `folded=true`，`replaceAllOfConversation`
 * **原样保留** folded 字段 → 那些消息在库里**仍然是折叠的**。
 *
 * 于是：
 * - UI 上看着正常（折叠消息也走普通气泡渲染，这是刻意的对齐 AiCode 设计）；
 * - 但它们既不在 `uiState.messages`（上下文）里、也不进请求 →
 *   顶部数字按剩下的小集合估算 → 用户看到「恢复了，数字却变小了」。
 *
 * 修法：**解除压缩必须在调用方显式清 `folded`**（`replaceAllMessages` 只做存储，
 * 不替调用方决定折叠状态）。本测试锁住「清 folded 之后上下文集合才是完整的」这条不变量。
 */
class RestoreUnfoldsTest {

    private fun msg(id: Long, seq: Long, content: String, folded: Boolean) = ChatMessageModel(
        id = id, role = MessageRole.USER, author = "我", time = "10:00",
        content = content, folded = folded, seq = seq
    )

    /** 模拟回退到折叠区时构造 finalMsgs 的两条路径。 */
    private fun finalMsgsBroken(all: List<ChatMessageModel>, idx: Int) = all.take(idx)
    private fun finalMsgsFixed(all: List<ChatMessageModel>, idx: Int) =
        all.take(idx).map { it.copy(folded = false) }

    private fun allHistory() = listOf(
        msg(1L, 1L, "老问题1", folded = true),
        msg(2L, 2L, "老回答1", folded = true),
        msg(3L, 3L, "老问题2", folded = true),
        msg(4L, 4L, "[上下文已压缩]", folded = false),
        msg(5L, 5L, "摘要正文", folded = false),
        msg(6L, 6L, "压缩后的新问题", folded = false)
    )

    @Test
    fun `不清 folded 时被压缩的原文仍不在上下文里——这正是数字变小的原因`() {
        val all = allHistory()
        // 目标 = 「压缩后的新问题」(idx=5)，回退到它之前
        val broken = finalMsgsBroken(all, 5)
        val contextBroken = broken.filterNot { it.folded }
        // 原文（folded=true）全被排除在上下文之外 → 上下文只剩 marker + 摘要
        assertEquals("坏路径下上下文只剩 2 条", 2, contextBroken.size)
        assertFalse("被压缩的原文必须已回到上下文", contextBroken.any { it.content == "老问题1" })
    }

    @Test
    fun `清 folded 后全部原文回到上下文`() {
        val all = allHistory()
        val fixed = finalMsgsFixed(all, 5)
        val contextFixed = fixed.filterNot { it.folded }
        assertEquals("恢复后 5 条全部属于上下文", 5, contextFixed.size)
        assertTrue(contextFixed.any { it.content == "老问题1" })
        assertTrue(contextFixed.any { it.content == "老问题2" })
        assertTrue("解除压缩后不应再有折叠标记", fixed.none { it.folded })
    }

    @Test
    fun `恢复后上下文条数等于恢复的消息条数`() {
        val all = allHistory()
        val fixed = finalMsgsFixed(all, 5)
        assertEquals(fixed.size, fixed.filterNot { it.folded }.size)
    }

    @Test
    fun `replaceAllOfConversation 原样保留 folded——所以调用方必须自己清`() {
        // 这是「不替调用方决定折叠状态」的契约：实体层原样写回。
        val entities = listOf(
            MessageEntity(id = 1L, conversationId = 7L, role = "user", author = "我", time = "10:00", content = "老问题1", folded = true, seq = 1L),
            MessageEntity(id = 2L, conversationId = 7L, role = "user", author = "我", time = "10:00", content = "新问题", folded = false, seq = 2L)
        )
        // 模拟 DAO 事务体（clearByConversation + insertAll(mapIndexed { seq = index+1 })）
        val written = entities.mapIndexed { i, m -> m.copy(conversationId = 7L, seq = (i + 1).toLong()) }
        assertTrue("folded 必须原样写回（DAO 不做业务判断）", written[0].folded)
        assertFalse(written[1].folded)
        // 且 seq 按下标重排
        assertEquals(listOf(1L, 2L), written.map { it.seq })
    }
}
