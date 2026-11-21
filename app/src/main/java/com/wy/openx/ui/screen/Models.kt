package com.wy.openx.ui.screen

import com.wy.openx.data.model.MessageAttachment
import com.wy.openx.ui.state.SessionBadge

data class ConversationItem(
    val id: Long,
    val title: String,
    val meta: String,
    val active: Boolean = false,
    val pinned: Boolean = false,
    /** 最近活跃时间（epoch millis）：侧边栏按时间分组用。 */
    val lastActiveAt: Long = 0,
    /** 该会话的运行状态（多会话并行时侧边栏用它画圆点徽标）。 */
    val badge: SessionBadge = SessionBadge.IDLE
)

data class AgentItem(
    val name: String,
    val desc: String,
    val status: String,
    val selected: Boolean = false
)

data class ChatMessage(
    val role: String,
    val author: String,
    val time: String,
    val content: String,
    val isCode: Boolean = false,
    val isSteps: Boolean = false,
    val steps: List<String> = emptyList(),
    val imageUri: String? = null,
    val attachments: List<MessageAttachment> = emptyList(),
    val tokens: Int? = null,
    val inputTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val timeMs: Long? = null,
    val toolName: String? = null,
    val toolResult: String? = null
)

data class ToolItem(
    val name: String,
    val detail: String,
    val state: String
)
