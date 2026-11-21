package com.wy.openx.ui.screen

import com.wy.openx.data.model.AgentModel
import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.ConversationModel
import com.wy.openx.data.model.MessageRole
import com.wy.openx.data.model.ToolCallModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话副标题：`相对时间 · 模型名`，**全部实时计算**。
 *
 * 为什么不读 Room 里存的那份 `meta`：它是建会话时写死的一次性字符串
 *（`createNewConversation` 里拼「刚刚 · [未选择模型]」），之后任何更新都不碰它 ——
 * DAO 里根本没有更新 meta 的语句。结果就是用户在模型选择器里选了模型，
 * 侧边栏仍永远显示建会话那一刻的「[未选择模型]」。
 *
 * 这里改成按需派生：模型名从会话自己的 [ConversationModel.model]（会话级模式下
 * 是持久化的本会话 override）或全局当前模型取；时间从 [ConversationModel.lastActiveAt]
 * 现算。两者都随数据变化自动更新，不存在"忘了同步"的路径。
 *
 * @param sessionMode 「模型选择模式」开关。开启时新会话未选模型是**真的没选**
 *   （显示 `[未选择模型]`，与顶栏口径一致）；关闭时统一跟随全局当前模型。
 * @param globalModel 全局当前模型名（仅 [sessionMode] 为 false 时使用）。
 */
fun conversationMetaLabel(
    conversation: ConversationModel,
    sessionMode: Boolean,
    globalModel: String
): String {
    val model = if (sessionMode) {
        conversation.model?.takeIf { it.isNotBlank() } ?: "[未选择模型]"
    } else {
        globalModel.ifBlank { "[未选择模型]" }
    }
    val time = relativeTimeLabel(conversation.lastActiveAt)
    return if (time.isEmpty()) model else "$time · $model"
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 绝对日期。 */
private fun relativeTimeLabel(timestamp: Long): String {
    // 0 = 升级前的旧会话（lastActiveAt 是 Room 9→10 才加的列），没有可用时间戳，不显示时间。
    if (timestamp <= 0L) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

fun ConversationModel.toLegacy() = ConversationItem(id = id, title = title, meta = meta, active = active, pinned = isPinned, lastActiveAt = lastActiveAt)

fun AgentModel.toLegacy() = AgentItem(name = name, desc = desc, status = status, selected = selected)

fun ToolCallModel.toLegacy() = ToolItem(name = name, detail = detail, state = state)

fun ChatMessageModel.toLegacy() = ChatMessage(
    role = when (role) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.SYSTEM -> "system"
    },
    author = author,
    time = time,
    content = content,
    isCode = isCode,
    isSteps = isSteps,
    steps = steps,
    imageUri = imageUri,
    attachments = attachments,
    tokens = tokens,
    inputTokens = inputTokens,
    cachedInputTokens = cachedInputTokens,
    timeMs = timeMs,
    toolName = toolName,
    toolResult = toolResult
)
