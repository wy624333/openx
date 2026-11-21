package com.wy.openx.ui.viewmodel

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分叉策略：截前缀、裁工具尾、折叠归一化。
 *
 * 关键回归点：OpenX 的折叠历史仍然显示、也能被选为分叉点；若前缀里没有压缩摘要，
 * 必须把 folded 清掉，否则新会话「看起来有历史、模型上下文却是空的」。
 */
class ConversationForkPolicyTest {

    private fun m(
        id: Long,
        role: MessageRole,
        content: String,
        folded: Boolean = false,
        queued: Boolean = false,
        toolName: String? = null,
        toolCallId: String? = null,
        toolResult: String? = null
    ) = ChatMessageModel(
        id = id,
        role = role,
        author = if (role == MessageRole.USER) "我" else "AI",
        time = "10:00",
        content = content,
        folded = folded,
        queued = queued,
        toolName = toolName,
        toolCallId = toolCallId,
        toolResult = toolResult
    )

    @Test
    fun `截取目标消息及之前的历史`() {
        val all = listOf(
            m(1, MessageRole.USER, "问题"),
            m(2, MessageRole.ASSISTANT, "回答"),
            m(3, MessageRole.USER, "追问")
        )
        assertEquals(listOf(1L, 2L), ConversationForkPolicy.prefixUpTo(all, 2).map { it.id })
    }

    @Test
    fun `目标消息不在列表时不复制`() {
        val all = listOf(m(1, MessageRole.USER, "问题"))
        assertTrue(ConversationForkPolicy.prefixUpTo(all, 99).isEmpty())
    }

    @Test
    fun `复制时重置 id 并取消排队`() {
        val prepared = ConversationForkPolicy.prepareCopy(
            listOf(
                m(1, MessageRole.USER, "排队中", queued = true),
                m(2, MessageRole.ASSISTANT, "回答")
            )
        )
        assertEquals(listOf(0L, 0L), prepared.map { it.id })
        assertFalse(prepared.any { it.queued })
    }

    @Test
    fun `裁掉尾部悬挂的工具结果与调用`() {
        val prepared = ConversationForkPolicy.prepareCopy(
            listOf(
                m(1, MessageRole.USER, "查一下"),
                m(2, MessageRole.ASSISTANT, "", toolName = "shell", toolCallId = "call-1"),
                m(3, MessageRole.SYSTEM, "输出", toolName = "shell", toolCallId = "call-1", toolResult = "ok")
            )
        )
        assertEquals(listOf(MessageRole.USER), prepared.map { it.role })
    }

    @Test
    fun `保留完整工具调用轮次`() {
        val prepared = ConversationForkPolicy.prepareCopy(
            listOf(
                m(1, MessageRole.USER, "查一下"),
                m(2, MessageRole.ASSISTANT, "", toolName = "shell", toolCallId = "call-1"),
                m(3, MessageRole.SYSTEM, "输出", toolName = "shell", toolCallId = "call-1", toolResult = "ok"),
                m(4, MessageRole.ASSISTANT, "结论")
            )
        )
        assertEquals(4, prepared.size)
    }

    @Test
    fun `压缩前分叉恢复折叠消息`() {
        val prepared = ConversationForkPolicy.prepareCopy(
            listOf(
                m(1, MessageRole.USER, "旧问题", folded = true),
                m(2, MessageRole.ASSISTANT, "旧回答", folded = true)
            )
        )
        assertFalse(prepared.any { it.folded })
    }

    @Test
    fun `带压缩摘要时保留折叠标记`() {
        val prepared = ConversationForkPolicy.prepareCopy(
            listOf(
                m(1, MessageRole.USER, "旧问题", folded = true),
                m(2, MessageRole.ASSISTANT, "旧回答", folded = true),
                m(3, MessageRole.USER, COMPACTION_MARKER),
                m(4, MessageRole.ASSISTANT, SUMMARY_PREFIX + "摘要内容")
            )
        )
        assertEquals(listOf(true, true, false, false), prepared.map { it.folded })
        assertEquals(listOf(0L, 0L, 0L, 0L), prepared.map { it.id })
    }
}
