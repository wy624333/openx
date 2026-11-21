package com.wy.openx.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ⚠ [indices] 必须与 `DatabaseProvider.MIGRATION_18_19` 里 `CREATE INDEX` 的**名字与列完全一致**。
 *
 * Room 在每次打开数据库时会把**实际表结构**与实体声明比对，多一个索引就判定
 * schema 不一致并抛 `IllegalStateException`（表现为「一开 APP 就崩」）。
 * 名字也必须一致——Room 不会把「同样两列、不同名字」视为同一个索引。
 */
@Entity(
    tableName = "messages",
    indices = [Index(name = "index_messages_conversation_seq", value = ["conversationId", "seq"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val author: String,
    val time: String,
    val content: String,
    val isCode: Boolean = false,
    val isSteps: Boolean = false,
    val steps: String = "",
    val imageUri: String? = null,
    val attachmentsJson: String? = null,
    val tokens: Int? = null,
    val inputTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val timeMs: Long? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArguments: String? = null,
    val toolResult: String? = null,
    // 压缩折叠标记：true = 已被摘要取代，**只留在库里供用户翻历史，不再进请求**。
    // 压缩前是「删库重写」（原始消息永久丢失、备份仅存内存），改为「打标记」后
    // 原始消息永久保留、可展开可恢复，重启也不丢。
    val folded: Boolean = false,
    // 排队中：用户在 AI 生成期间发出的消息，已落库并显示在聊天页，但尚未发给模型。
    // 消费队列时置false 并照常插入。
    val queued: Boolean = false,
    // 会话内单调递增的**显示序号**。
    //
    // 为什么必须有它：openx 的消息 id 有两套来源——库自增（小整数）与内存
    // `nextMessageId = 毫秒×1000`（≈1.79e18）。两者混在一列里，`ORDER BY id` 并**不等于**
    // 显示顺序：压缩时新插入的 marker/summary 拿到一个很小的自增 id，而它前面的折叠历史里
    // 混着内存大 id → 按 id 排序会把压缩卡甩到**折叠历史之前**，用户看到「卡片跑到最上面」。
    // 显示序必须有一个与 id 解耦、由库统一分配的单调列。
    //
    // 由 [MessageDao.insertOrdered] 在写事务里赋 `MAX(seq)+1`，调用方无需关心；
    // 全库导出（跨会话）仍按 id 分块，因为 seq 只在会话内有意义。
    val seq: Long = 0
)
