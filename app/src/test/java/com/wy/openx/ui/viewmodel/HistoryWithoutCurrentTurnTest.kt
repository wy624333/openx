package com.wy.openx.ui.viewmodel

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 请求历史必须精确剔除「本轮用户输入」，不能盲删最后一条。
 *
 * 回归场景（真机实证）：上下文将满时发一条短消息，发送前触发自动压缩。压缩保留尾
 * 正好是本条用户消息，而 `commitCompaction` 把 marker/摘要排在保留尾**之后**
 * （展示序：保留尾 → marker → 摘要）。此时 `dropLast(1)` 删掉的是摘要而非用户消息：
 * 请求里丢了摘要，保留尾的本轮消息又被 `sendMessage` 追加一次 → 两条相同用户消息；
 * 回答还会因为末尾是摘要而被拼进压缩卡片。
 */
class HistoryWithoutCurrentTurnTest {

    private fun m(id: Long, role: MessageRole, content: String, author: String = "我") =
        ChatMessageModel(id, role, author, "10:00", content)

    private fun contents(list: List<ChatMessageModel>) = list.map { it.content }

    @Test
    fun `压缩后只剔除本轮消息，摘要保留且被提到最前`() {
        val retained = m(10, MessageRole.USER, "在吗")
        val marker = m(900, MessageRole.USER, COMPACTION_MARKER, "系统")
        val summary = m(901, MessageRole.ASSISTANT, "之前的摘要", "系统")
        val list = listOf(retained, marker, summary)

        val out = hoistCompactionArtifacts(historyWithoutCurrentTurn(list, 10))

        assertEquals(listOf(COMPACTION_MARKER, "之前的摘要"), contents(out))
    }

    @Test
    fun `普通回合只去掉末尾的本轮消息`() {
        val list = listOf(
            m(1, MessageRole.USER, "上一问"),
            m(2, MessageRole.ASSISTANT, "上一答", "构建助手"),
            m(3, MessageRole.USER, "这一问")
        )

        assertEquals(
            listOf("上一问", "上一答"),
            contents(historyWithoutCurrentTurn(list, 3))
        )
    }

    @Test
    fun `消息不在列表里时原样返回`() {
        val list = listOf(m(1, MessageRole.USER, "a"), m(2, MessageRole.ASSISTANT, "b", "构建助手"))
        assertEquals(list, historyWithoutCurrentTurn(list, 99))
        assertEquals(list, historyWithoutCurrentTurn(list, null))
    }
}
