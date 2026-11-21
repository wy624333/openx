package com.wy.openx.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Database(entities = [MessageEntity::class, ConversationEntity::class, LlmCallRecordEntity::class, AIProviderEntity::class, TaskEntity::class], version = 21, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun llmCallRecordDao(): LlmCallRecordDao
    abstract fun aiProviderDao(): AIProviderDao
    abstract fun taskDao(): TaskDao

    /**
     * 备份恢复：单事务写入会话 + 消息 + 调用记录。
     *
     * 必须走事务：恢复是「先清空再写入」，若中途失败（数据损坏、磁盘写满），
     * 非事务实现会把用户原有数据清掉却写不进新数据——数据全丢且不可恢复。
     * 事务保证要么整体成功，要么整体回滚到恢复前状态。
     */
    @Transaction
    open suspend fun restoreConversations(
        conversations: List<ConversationEntity>,
        messages: List<MessageEntity>,
        records: List<LlmCallRecordEntity>,
        clearFirst: Boolean
    ) {
        if (clearFirst) {
            conversationDao().clearAll()
            messageDao().clearAll()
            llmCallRecordDao().deleteAll()
        }
        conversationDao().insertAll(conversations)
        messageDao().insertAll(messages)
        llmCallRecordDao().insertAll(records)
    }
}
