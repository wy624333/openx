package com.wy.openx.data.repository

import com.wy.openx.data.local.TaskDao
import com.wy.openx.data.local.toEntity
import com.wy.openx.data.local.toItem
import com.wy.openx.data.model.TaskItem

/**
 * 任务面板的持久化仓库。
 *
 * 写入是**整体替换**语义（与快照式 `todo` 工具一致）：把一份完整清单覆盖到库里。
 * 逐条 upsert 是不行的——清单里被移除的条目删不掉。
 *
 * 所有写操作都走 [TaskDao.replaceTasks]，它自带事务（先清空再写入），
 * 中途失败会整体回滚，不会留下一份被清空的清单。
 */
class TaskRepository(private val dao: TaskDao) {

    /** 读某会话的任务清单（已按显示顺序升序）。 */
    suspend fun getByConversation(conversationId: Long): List<TaskItem> =
        dao.getByConversation(conversationId).map { it.toItem() }

    /** 用一份完整清单整体替换某会话的任务。 */
    suspend fun replace(conversationId: Long, tasks: List<TaskItem>) {
        dao.replaceTasks(conversationId, tasks.map { it.toEntity(conversationId) })
    }

    /** 某会话的任务全部清空（删会话时用；也可直接靠 [TaskDao.deleteOrphans] 兜底）。 */
    suspend fun clear(conversationId: Long) = dao.clearConversation(conversationId)

    /** 清空全部会话的任务（「清空所有对话」时用）。 */
    suspend fun clearAll() = dao.clearAll()

    /** 清掉归属已不存在会话的任务（防御孤儿数据）。 */
    suspend fun deleteOrphans() = dao.deleteOrphans()
}
