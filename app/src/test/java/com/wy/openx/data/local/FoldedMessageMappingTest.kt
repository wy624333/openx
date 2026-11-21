package com.wy.openx.data.local

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩折叠标记（`messages.folded`）的映射回归。
 *
 * 这条链路一旦断了，后果是**静默**的：折叠标记在落库或回读时丢失 →
 * 被摘要取代的原文重新混进模型请求 → 压缩等于没压（且 UI 上看不出任何异常）。
 * 所以在这里把「写入带标记 / 回读带标记 / 默认未折叠」三件事锁死。
 */
class FoldedMessageMappingTest {

    private fun model(id: Long, folded: Boolean) = ChatMessageModel(
        id = id,
        role = MessageRole.USER,
        author = "我",
        time = "10:00",
        content = "第 $id 条",
        folded = folded
    )

    private fun entity(id: Long, folded: Boolean) = MessageEntity(
        id = id,
        conversationId = 7L,
        role = "user",
        author = "我",
        time = "10:00",
        content = "第 $id 条",
        folded = folded
    )

    @Test
    fun `折叠标记写入实体时保留`() {
        val e = model(1L, folded = true).toEntity(7L)
        assertTrue("压缩后的原文必须带 folded=true 落库，否则会被当成上下文重发", e.folded)
    }

    @Test
    fun `折叠标记从实体回读时保留`() {
        assertTrue("回读丢失标记 = 压缩失效（原文会重新进请求）", entity(1L, folded = true).toModel().folded)
    }

    @Test
    fun `未折叠消息往返后仍是未折叠`() {
        val roundTrip = entity(2L, folded = false).toModel().toEntity(7L)
        assertFalse("正常消息不能被误标折叠，否则会被踢出上下文", roundTrip.folded)
    }

    @Test
    fun `未显式传 folded 时默认未折叠`() {
        // 迁移 14→15 把老数据全置 0；这里保证后续新增构造点漏传时也落在安全一侧
        // （false=仍进上下文，true 漏传会静默丢消息，宁可多发不可少发）。
        val e = MessageEntity(id = 3L, conversationId = 7L, role = "user", author = "我", time = "10:00", content = "x")
        assertFalse(e.folded)
        assertFalse(ChatMessageModel(3L, MessageRole.USER, "我", "10:00", "x").folded)
    }

    @Test
    fun `折叠往返不改动消息内容与 id`() {
        val m = model(42L, folded = true)
        val back = m.toEntity(7L).toModel()
        assertEquals(42L, back.id)
        assertEquals("第 42 条", back.content)
        assertTrue(back.folded)
    }
}
