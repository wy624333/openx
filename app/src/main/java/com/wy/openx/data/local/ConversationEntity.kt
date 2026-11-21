package com.wy.openx.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val meta: String,
    val active: Boolean = false,
    val requestCount: Int = 0,
    val lastInputTokens: Int = 0,
    /** 手动置顶：常驻列表最前（置顶组内仍按最近活跃排序）。 */
    val isPinned: Boolean = false,
    /** 最近活跃时间（epoch millis）：发消息时刷新，用于列表「最近聊天上浮」。0 表示旧数据。 */
    val lastActiveAt: Long = 0,
    /**
     * 已归档：从侧边栏主列表收进「历史」页，数据保留（**不是删除**）。
     *
     * 两个来源：用户手动归档；以及「偏好设置 → 自动归档」按天数把长期未更新的会话批量归档。
     */
    val isArchived: Boolean = false,
    /**
     * 本会话显式选定的提供商 id（「模型选择模式」开关**开启**时写入）。
     *
     * null = 未选择过 → 跟随全局当前提供商（开关关闭时的行为）。
     * 与 [model] 成对使用：提供商与模型必须一起记，否则会出现
     * 「提供商是 A、模型是 B 家的」这种拼不出的组合。
     */
    val providerId: String? = null,
    /** 本会话显式选定的模型名（见 [providerId]）。null = 跟随该提供商的当前选中模型。 */
    val model: String? = null,
    /**
     * 标题是否已「定稿」——AI 生成成功（含已过 stripThinkingBlocks）或用户手动改名。
     *
     * 为什么必须持久化：判定标志原先只活在内存 `SessionSlot.titleGeneratedByAi`，
     * App 一重启就丢 → 在任意已有会话里发消息会**重新生成并覆盖**旧标题，
     * 违背「每个会话只生成一次」。同时手动改名也得置位，否则改名后 AI 仍会覆盖。
     *
     * 注意：首轮的「截取前 20 字占位标题」**不**置位（否则会挡住后续 AI 生成）。
     */
    val titleFinalized: Boolean = false
)
