package com.wy.openx.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 任务面板表访问层。
 *
 * 写入是**整体替换**语义（与快照式 `todo` 工具一致）：先删该会话全部，再插入新清单。
 * 不用逐条 upsert，因为快照本身就是「完整清单」，整体替换才能保证删除也生效
 *（逐条 upsert 删不掉已从清单里移除的条目）。
 *
 * 这两步必须**在同一事务**里（见 [replaceTasks]），否则中途失败会留下空清单。
 */
@Dao
interface TaskDao {

    /** 读某会话的任务清单，按显示顺序升序。 */
    @Query("SELECT * FROM tasks WHERE conversationId = :conversationId ORDER BY taskOrder ASC")
    suspend fun getByConversation(conversationId: Long): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TaskEntity>)

    /**
     * 整体替换某会话的任务清单（**单事务**）。
     *
     * 必须走事务：先清空再写入，中途失败（磁盘满等）若不回滚，
     * 用户会得到一份被清空的清单——比不持久化更糟。
     */
    @androidx.room.Transaction
    suspend fun replaceTasks(conversationId: Long, items: List<TaskEntity>) {
        clearConversation(conversationId)
        if (items.isNotEmpty()) insertAll(items)
    }

    /** 清空全部任务（「清空所有对话」时用）。 */
    @Query("DELETE FROM tasks")
    suspend fun clearAll()

    /** 删除会话时一并清掉它的任务（避免孤儿数据长期堆积）。 */
    @Query("DELETE FROM tasks WHERE conversationId NOT IN (SELECT id FROM conversations)")
    suspend fun deleteOrphans()
}
