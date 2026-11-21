package com.wy.openx.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    /**
     * 排序：手动置顶优先 → 最近活跃（发消息刷新）→ 创建顺序倒序。
     * 旧数据 lastActiveAt = 0 会自然沉到各自分组末尾，由 id DESC 兜底，行为与改动前一致。
     *
     * **只返回未归档的**：归档会话收进「历史」页（[observeArchived]），不再出现在侧边栏主列表。
     */
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, lastActiveAt DESC, id DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    /** 归档会话（「历史」页数据源）：最近归档的在前。 */
    @Query("SELECT * FROM conversations WHERE isArchived = 1 ORDER BY lastActiveAt DESC, id DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    /** 全部会话（含归档），备份导出用。 */
    @Query("SELECT * FROM conversations ORDER BY id ASC")
    suspend fun getAll(): List<ConversationEntity>

    /** 全库区间（备份导出用，同样绕开 2MB CursorWindow 窗口）。 */
    @Query("SELECT * FROM conversations WHERE id > :afterId AND id <= :beforeId ORDER BY id ASC")
    suspend fun getAllRange(afterId: Long, beforeId: Long): List<ConversationEntity>

    /** 全库 id（导出分块用）。 */
    @Query("SELECT id FROM conversations ORDER BY id ASC")
    suspend fun getAllIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity): Long

    /** 批量写入（备份恢复用）：保留原 id 以维持与 messages 的会话关联。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<ConversationEntity>)

    @Query("UPDATE conversations SET active = CASE WHEN id = :activeId THEN 1 ELSE 0 END")
    suspend fun setActiveConversation(activeId: Long)

    @Query("DELETE FROM conversations")
    suspend fun clearAll()

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    /**
     * 标记标题已定稿（AI 生成成功或用户手动改名）。置 1 后不再自动生成标题，
     * 且跨 App 重启有效（见 [ConversationEntity.titleFinalized]）。
     * 单独于 [updateTitle]：首轮的截取占位标题**不应**置位。
     */
    @Query("UPDATE conversations SET titleFinalized = :finalized WHERE id = :id")
    suspend fun updateTitleFinalized(id: Long, finalized: Boolean)

    @Query("UPDATE conversations SET requestCount = :count WHERE id = :id")
    suspend fun updateRequestCount(id: Long, count: Int)

    @Query("UPDATE conversations SET lastInputTokens = :tokens WHERE id = :id")
    suspend fun updateLastInputTokens(id: Long, tokens: Int)

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversations SET lastActiveAt = :timestamp WHERE id = :id")
    suspend fun updateLastActiveAt(id: Long, timestamp: Long)

    @Query("UPDATE conversations SET isArchived = :archived WHERE id = :id")
    suspend fun updateArchived(id: Long, archived: Boolean)

    /**
     * 写本会话的模型选择（提供商 id + 模型名）。null 表示清除选择 → 重新跟随全局。
     *
     * 两个字段必须同写：分开写会出现「提供商已换、模型还是别家的」这种拼不出的组合，
     * 而它会直接发到请求里。
     */
    @Query("UPDATE conversations SET providerId = :providerId, model = :model WHERE id = :id")
    suspend fun updateModelOverride(id: Long, providerId: String?, model: String?)

    /**
     * 自动归档：把「未归档 且 最近活跃时间早于 cutoff」的会话批量标记为已归档，返回影响行数。
     *
     * 注意同时看 [lastActiveAt] 与 [id] 兜底：lastActiveAt = 0 的是升级前的旧会话
     * （该列是 Room 9→10 才加的），它们没有可用时间戳，用 id 无法判断新旧，
     * 故**排除**在外——避免升级后老会话被一次性全部归档消失，那对用户是惊吓而非功能。
     */
    @Query(
        "UPDATE conversations SET isArchived = 1 " +
            "WHERE isArchived = 0 AND lastActiveAt > 0 AND lastActiveAt < :cutoff"
    )
    suspend fun archiveInactiveBefore(cutoff: Long): Int
}
