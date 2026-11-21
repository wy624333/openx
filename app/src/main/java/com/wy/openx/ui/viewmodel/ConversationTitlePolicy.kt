package com.wy.openx.ui.viewmodel

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole

/**
 * 会话标题策略（纯函数，便于单测）。
 *
 * 关键口径：**系统提示不是对话内容**。模式切换（「已切换至股票模式（行情分析）」）、
 * 命令回执、停止标记都是 `MessageRole.SYSTEM`，既不能算用户轮数，也不能喂给标题模型
 * ——否则首条是系统消息的会话会被总结成「切换股票模式」这种标题，且一旦定稿就再不改。
 */
internal object ConversationTitlePolicy {
    /** 触发 AI 总结所需的用户轮数。 */
    const val SUMMARY_TURNS = 1

    /** 占位/兜底标题的截取长度。 */
    const val PLACEHOLDER_LIMIT = 20

    /** 送进标题提示词的最大字符数。 */
    const val PROMPT_LIMIT = 3000

    /** 只保留「人在说话」的消息（用户/助手）。 */
    fun dialogOf(messages: List<ChatMessageModel>): List<ChatMessageModel> =
        messages.filter { it.role != MessageRole.SYSTEM }

    /** 真正的人话轮数（压缩摘要那条 USER 消息不算）。 */
    fun userTurns(dialog: List<ChatMessageModel>): Int =
        dialog.count { it.role == MessageRole.USER && !isSummary(it) }

    /** 首条人话截取成占位标题；没有用户发言时返回 null（调用方直接放弃）。 */
    fun placeholder(dialog: List<ChatMessageModel>): String? {
        val text = firstUserText(dialog) ?: return null
        return if (text.length <= PLACEHOLDER_LIMIT) text else text.take(PLACEHOLDER_LIMIT) + "..."
    }

    /** 失败兜底：最后一条用户发言的前 20 字。 */
    fun fallback(dialog: List<ChatMessageModel>): String? {
        val msg = dialog.lastOrNull { it.role == MessageRole.USER && !isSummary(it) } ?: return null
        val text = msg.content.trim()
        if (text.isEmpty()) return null
        return text.take(PLACEHOLDER_LIMIT) + if (text.length > PLACEHOLDER_LIMIT) "..." else ""
    }

    /** 标题提示词里的对话正文（已剔除系统提示）。 */
    fun promptText(dialog: List<ChatMessageModel>): String =
        dialog.joinToString("\n") { m ->
            val who = if (m.role == MessageRole.USER) "用户" else "助手"
            "$who: ${m.content}"
        }.take(PROMPT_LIMIT)

    /** 本轮正在发送、可能还没进槽位快照的用户消息 —— 缺了它，首条消息会被当成「没有用户发言」。 */
    fun mergePending(
        messages: List<ChatMessageModel>,
        pending: ChatMessageModel?
    ): List<ChatMessageModel> =
        if (pending == null || messages.any { it.id == pending.id }) messages else messages + pending

    private fun firstUserText(dialog: List<ChatMessageModel>): String? =
        dialog.firstOrNull { it.role == MessageRole.USER && !isSummary(it) }
            ?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun isSummary(message: ChatMessageModel): Boolean =
        message.content.startsWith(SUMMARY_PREFIX)
}
