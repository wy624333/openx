package com.wy.openx.ui.viewmodel

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole

/**
 * 「从此处新建分支会话」的纯逻辑（便于单测，不依赖 Room / Android）。
 *
 * 复制目标消息（含）之前的完整历史到新会话。
 * 但 OpenX 有两处必须自己处理：
 * 1. 压缩后的折叠消息在 OpenX 里**仍然显示、也允许被选为分叉点**，
 *    若前缀里没有压缩摘要，直接照搬 folded 会让新会话“有一堆消息、但上下文为空”。
 * 2. 消息 id 是库自增，复制时必须归零重新分配；seq 保留即可（会话内仍单调，新会话
 *    下一次插入会取 MAX(seq)+1）。
 */
internal object ConversationForkPolicy {

    /** 取目标消息（含）之前的全部消息；按传入顺序（聊天页显示序）截取。 */
    fun prefixUpTo(all: List<ChatMessageModel>, targetId: Long): List<ChatMessageModel> {
        val index = all.indexOfFirst { it.id == targetId }
        return if (index < 0) emptyList() else all.subList(0, index + 1)
    }

    /**
     * 把待复制的消息整理成新会话可用的历史：
     * - 裁掉尾部悬挂的工具调用/结果，避免回放时 API 400；
     * - id 归零由库重新分配，queued 清掉（新会话没有队列驱动，否则永远「排队中」）；
     * - 前缀里没有压缩产物时把 folded 全部置 false（恢复成正常上下文）。
     */
    fun prepareCopy(prefix: List<ChatMessageModel>): List<ChatMessageModel> {
        val trimmed = trimIncompleteTurnTail(prefix)
        if (trimmed.isEmpty()) return emptyList()
        // marker/摘要是成对写入的；只要前缀里出现任意一个，就说明这是一段「压缩后」的历史，
        // 必须原样保留 folded，否则摘要 + 原文会同时进请求、上下文翻倍。
        val hasCompaction = trimmed.any {
            it.content == COMPACTION_MARKER || it.content.startsWith(SUMMARY_PREFIX)
        }
        return trimmed.map {
            it.copy(
                id = 0,
                folded = if (hasCompaction) it.folded else false,
                queued = false
            )
        }
    }

    /**
     * 截齐尾部不完整的轮次：末尾是工具结果，或带 toolCall 的助手消息时逐条丢弃。
     * 与 AiCode 的 `trimIncompleteTurnTail` 同口径，只是 OpenX 的工具结果用 SYSTEM 行表示。
     */
    private fun trimIncompleteTurnTail(messages: List<ChatMessageModel>): List<ChatMessageModel> {
        var end = messages.size
        while (end > 0) {
            val last = messages[end - 1]
            val incomplete = when (last.role) {
                MessageRole.ASSISTANT -> !last.toolName.isNullOrBlank() || !last.toolCallId.isNullOrBlank()
                MessageRole.SYSTEM -> !last.toolName.isNullOrBlank() ||
                    !last.toolResult.isNullOrBlank() ||
                    !last.toolCallId.isNullOrBlank()
                MessageRole.USER -> false
            }
            if (!incomplete) break
            end--
        }
        return messages.subList(0, end)
    }
}
