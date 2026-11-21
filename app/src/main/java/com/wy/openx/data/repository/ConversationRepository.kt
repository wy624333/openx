package com.wy.openx.data.repository

import com.wy.openx.data.local.ConversationDao
import com.wy.openx.data.local.toEntity
import com.wy.openx.data.local.toModel
import com.wy.openx.data.model.ConversationModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationRepository(private val dao: ConversationDao) {
    fun observeConversations(): Flow<List<ConversationModel>> = dao.observeAll().map { list ->
        list.map { it.toModel() }
    }

    /** 归档会话列表（「历史」页）。 */
    fun observeArchivedConversations(): Flow<List<ConversationModel>> = dao.observeArchived().map { list ->
        list.map { it.toModel() }
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        dao.updateArchived(id, archived)
    }

    /**
     * 自动归档：把「最近活跃时间早于 [cutoff]」的未归档会话批量归档，返回归档条数。
     * 供「偏好设置 → 自动归档」调用。
     */
    suspend fun archiveInactiveBefore(cutoff: Long): Int = dao.archiveInactiveBefore(cutoff)

    suspend fun insert(conversation: ConversationModel): Long {
        return dao.insert(conversation.toEntity())
    }

    suspend fun setActiveConversation(id: Long) {
        dao.setActiveConversation(id)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }

    suspend fun updateTitle(id: Long, title: String) {
        dao.updateTitle(id, title)
    }

    /** 标记标题已定稿（AI 生成成功或用户手动改名）。跨重启有效。 */
    suspend fun updateTitleFinalized(id: Long, finalized: Boolean) {
        dao.updateTitleFinalized(id, finalized)
    }

    suspend fun updateRequestCount(id: Long, count: Int) {
        dao.updateRequestCount(id, count)
    }

    suspend fun updateLastInputTokens(id: Long, tokens: Int) {
        dao.updateLastInputTokens(id, tokens)
    }

    /**
     * 持久化本会话的模型选择（「模型选择模式」开关开启时调用）。
     * 传 null 则清除选择，该会话重新跟随全局当前提供商。
     */
    suspend fun setModelOverride(id: Long, providerId: String?, model: String?) {
        if (id <= 0L) return   // 会话尚未入库（id=0）：写了也没人读，由调用方先建会话
        dao.updateModelOverride(id, providerId, model)
    }

    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.updatePinned(id, pinned)
    }

    /** 刷新会话最近活跃时间，使其在列表中上浮（置顶会话仍常驻最前）。 */
    suspend fun touchLastActive(id: Long, timestamp: Long = System.currentTimeMillis()) {
        dao.updateLastActiveAt(id, timestamp)
    }
}
