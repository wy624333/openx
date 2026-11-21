package com.wy.openx.ui.viewmodel

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标题策略：系统提示（模式切换/命令回执）既不算用户轮数，也不进标题提示词。
 *
 * 回归场景（用户报告）：新会话先切股票模式 → 插入「已切换至股票模式（行情分析）」
 * 系统消息 → 用户才发第一条消息。此时标题要么被总结成「切换股票模式」，要么直接
 * 不生成（槽位被回流覆盖成只剩系统消息）。
 */
class ConversationTitlePolicyTest {

    private fun m(id: Long, role: MessageRole, content: String, author: String = "我") =
        ChatMessageModel(id, role, author, "10:00", content)

    private val modeSwitch = m(1, MessageRole.SYSTEM, "已切换至股票模式（行情分析）", "系统")
    private val userAsk = m(2, MessageRole.USER, "帮我看看贵州茅台最近的走势")

    @Test
    fun `系统消息不计入用户轮数`() {
        val dialog = ConversationTitlePolicy.dialogOf(listOf(modeSwitch, userAsk))
        assertEquals(listOf(userAsk), dialog)
        assertEquals(1, ConversationTitlePolicy.userTurns(dialog))
    }

    @Test
    fun `只有系统消息时没有对话可总结`() {
        val dialog = ConversationTitlePolicy.dialogOf(listOf(modeSwitch))
        assertTrue(dialog.isEmpty())
        assertEquals(0, ConversationTitlePolicy.userTurns(dialog))
        assertNull(ConversationTitlePolicy.placeholder(dialog))
    }

    @Test
    fun `占位标题取用户发言而不是系统提示`() {
        val dialog = ConversationTitlePolicy.dialogOf(listOf(modeSwitch, userAsk))
        assertEquals("帮我看看贵州茅台最近的走势", ConversationTitlePolicy.placeholder(dialog))
    }

    @Test
    fun `占位标题超长截断`() {
        val long = m(3, MessageRole.USER, "一".repeat(30))
        val dialog = ConversationTitlePolicy.dialogOf(listOf(long))
        assertEquals("一".repeat(20) + "...", ConversationTitlePolicy.placeholder(dialog))
    }

    @Test
    fun `标题提示词不包含系统提示`() {
        val dialog = ConversationTitlePolicy.dialogOf(
            listOf(modeSwitch, userAsk, m(4, MessageRole.ASSISTANT, "近期震荡上行", "助手"))
        )
        val text = ConversationTitlePolicy.promptText(dialog)
        assertFalse(text.contains("已切换至"))
        assertTrue(text.contains("用户: 帮我看看贵州茅台最近的走势"))
        assertTrue(text.contains("助手: 近期震荡上行"))
    }

    @Test
    fun `压缩摘要不算用户轮数但仍进提示词`() {
        val summary = m(5, MessageRole.USER, SUMMARY_PREFIX + " 之前聊过做多策略")
        val dialog = ConversationTitlePolicy.dialogOf(listOf(summary, userAsk))
        assertEquals(1, ConversationTitlePolicy.userTurns(dialog))
        assertTrue(ConversationTitlePolicy.promptText(dialog).contains("之前聊过做多策略"))
    }

    @Test
    fun `回流把本轮用户消息冲掉时用 pending 补齐`() {
        val stale = listOf(modeSwitch)
        val merged = ConversationTitlePolicy.mergePending(stale, userAsk)
        assertEquals(listOf(modeSwitch, userAsk), merged)
        assertEquals(1, ConversationTitlePolicy.userTurns(ConversationTitlePolicy.dialogOf(merged)))
    }

    @Test
    fun `pending 已在快照里时不重复追加`() {
        val merged = ConversationTitlePolicy.mergePending(listOf(modeSwitch, userAsk), userAsk)
        assertEquals(2, merged.size)
    }

    @Test
    fun `失败兜底取最后一条用户发言`() {
        val dialog = ConversationTitlePolicy.dialogOf(
            listOf(modeSwitch, userAsk, m(3, MessageRole.USER, "再看看五粮液"))
        )
        assertEquals("再看看五粮液", ConversationTitlePolicy.fallback(dialog))
    }
}
