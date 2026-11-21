package com.wy.openx.data.repository

import com.wy.openx.data.local.ChunkedReader
import com.wy.openx.data.local.MessageDao
import com.wy.openx.data.local.toEntity
import com.wy.openx.data.local.toModel
import com.wy.openx.data.model.ChatMessageModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest

/**
 * 消息仓库。
 *
 * 公开方法签名保持不变，但**所有整表读取都改成了按 id 区间分块**：
 * Android 的 CursorWindow 只有 2MB 且整个 cursor 共用，长会话一次 `SELECT *`
 * 必然触发 `Couldn't read row N from CursorWindow` 崩溃。细节见 [ChunkedReader]。
 *
 * 流也换成了「id 轻查询做变更检测 + 分块取正文」：id 列表几十 KB，
 * 永远不会因为窗口溢出而断流。
 */
class MessageRepository(private val dao: MessageDao) {
    /**
     * **UI 唯一的消息流**：本会话全部消息（含折叠历史），按显示序 [MessageEntity.seq] 升序。
     *
     *  `getMessagesBySession`（不分 `isCompacted`，一条流喂 UI）。
     *
     * 为什么要合并成一路：原先 UI 订阅两路（`observeIds` + `observeFoldedIds`），压缩时还要
     * 手工 cancel 两路 → 写库 → 手工把内存对齐 → 再重建两路。这套 cancel/recreate 是
     * 「用户按暂停历史就消失」的根源——取消会命中中间那个挂起点，两路状态就此错位，
     * 且重建失败时界面永久停在「折叠区空 = 历史全没了」。
     *
     * 一路之后：压缩只是一次普通写（[commitCompaction] 事务），Room 发射一次、这里更新一次，
     * 不存在需要手工对齐的第二份状态，暂停也无处可破坏。
     *
     * ## ⚠ 为什么这里必须 `.conflate()` + 去重
     *
     * Room 的 `createFlow` 是**表级失效**驱动：`messages` 表任何一次写（工具调用插一条、
     * 工具结果更新一条）都会触发发射，**不带 `distinctUntilChanged`**。而本函数每次发射都要
     * 分块读回**整个会话**（含数百条折叠历史）再排序、逐条 `toModel()`（内含附件 JSON 解码）。
     * 一轮工具循环里这样的写有几十次 → 几十次全量重读 → 消息区明显卡顿、新消息迟迟不显示。
     *
     * 两道防护：
     * 1. `.conflate()`：加载没跑完又来新发射时，**丢掉中间的**、只保留最新一次，
     *    而不是排队把每一次都完整跑一遍。
     * 2. 按 **id 列表**去重（`distinctUntilChanged`）：id 集合没变（例如只是工具结果内容更新）
     *    就不重复读正文 —— 这种情况下 UI 由内存里的流式状态推进，不需要库回流。
     */
    fun observeAllMessages(conversationId: Long): Flow<List<ChatMessageModel>> =
        dao.observeAllIds(conversationId)
            .distinctUntilChanged()
            .conflate()
            .mapLatest { loadAllMessagesOfConversation(conversationId) }

    /** 一次性读取本会话全部消息（含折叠），按显示序。切会话预载用。 */
    suspend fun getAllMessagesOfConversation(conversationId: Long): List<ChatMessageModel> =
        loadAllMessagesOfConversation(conversationId)

    /** 一次性读取会话**上下文**消息（未折叠，进模型请求那份）。 */
    suspend fun getMessages(conversationId: Long): List<ChatMessageModel> = loadContextMessages(conversationId)

    /** 一次性读取会话**折叠历史**（已被摘要取代但仍保留、可往上翻阅的那些）。 */
    suspend fun getFoldedMessages(conversationId: Long): List<ChatMessageModel> = loadFoldedMessages(conversationId)

    /**
     * 一次性读取会话**全部**消息（含折叠历史），按显示序。
     * 搜索 / 会话回退定位必须走这个：否则压缩后被折叠的老消息再也搜不到，等于历史白留。
     */
    suspend fun getAllMessagesIncludingFolded(conversationId: Long): List<ChatMessageModel> =
        loadAllMessagesOfConversation(conversationId)

    /** 备份导出：全库消息（含折叠历史），分块读取。 */
    suspend fun getAllMessages(): List<ChatMessageModel> {
        val ids = dao.getAllIds()
        return ChunkedReader.readAll(ids, "全库消息") { after, before ->
            dao.getAllRange(after, before)
        }.map { it.toModel() }
    }

    private suspend fun loadContextMessages(conversationId: Long): List<ChatMessageModel> {
        val ids = dao.getIds(conversationId)
        return ChunkedReader.readAll(ids, "上下文消息") { after, before ->
            dao.getContextRange(conversationId, after, before)
        }.map { it.toModel() }
    }

    private suspend fun loadFoldedMessages(conversationId: Long): List<ChatMessageModel> {
        val ids = dao.getFoldedIds(conversationId)
        return ChunkedReader.readAll(ids, "折叠历史") { after, before ->
            dao.getFoldedRange(conversationId, after, before)
        }.map { it.toModel() }
    }

    private suspend fun loadAllMessagesOfConversation(conversationId: Long): List<ChatMessageModel> {
        val ids = dao.getSessionIdsIncludingFolded(conversationId)
        if (ids.isEmpty()) return emptyList()
        // 分块按 id 区间切（避开 CursorWindow 2MB 窗口），但**最终按 seq 排**：
        // id 与显示序解耦（见 MessageEntity.seq），直接拼块会得到「按 id 有序、
        // 按显示序无序」的结果 —— 压缩卡会因此跑到折叠历史之前。
        return ChunkedReader.readAll(ids, "会话全部消息") { after, before ->
            dao.getSessionRangeIncludingFolded(conversationId, after, before)
        }
            .sortedWith(compareBy({ it.seq }, { it.id }))
            .map { it.toModel() }
    }

    /**
     * 按**显式 id 集合**折叠这些消息。
     *
     * 为什么不用 `id <= maxId`：消息 id 有库自增与内存时间戳两套来源，
     * `<=` 划界假设「id 顺序 == 显示顺序」，一旦 `maxOf{id}` 取到内存大 id，`id <= 天文数字`
     * 会把**该保留的尾部也折叠掉**（用户看到「消息全没了、只剩摘要」）。
     * 按集合折叠只折我们真正要折的那些，与顺序解耦
     *
     *
     * 分块：SQLite 绑定变量上限 999，折叠数百条会超限。
     *
     * 注：压缩主路径走 [commitCompaction]（折叠与插 marker/摘要同事务），
     * 本方法留给只需单独折叠的场景。
     */
    suspend fun markFoldedByIds(conversationId: Long, ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(MessageDao.SQLITE_MAX_VARIABLES).forEach { dao.markFoldedByIds(conversationId, it) }
    }

    /** 取消全部折叠（恢复压缩前的对话）。 */
    suspend fun unmarkAllFolded(conversationId: Long) = dao.unmarkAllFolded(conversationId)

    /**
     * 原子提交一次压缩：折叠 [foldIds] + 写 marker + 写摘要，**单个 Room 事务**。
     *
     *  `commitCompaction`。这里就是「用户按暂停历史就消失」的根治点：
     * 压缩常在回合 `finally` 里跑，此刻「停止」会取消本轮协程；若折叠与插摘要是多次独立写，
     * 取消会命中中间挂起点，留下「折叠已提交但摘要没插」（那段历史两头不见）或
     * 「摘要已插但折叠没提交」（摘要与原文重复占 token）的半成品状态。
     * 放进一个事务后要么整体生效、要么整体回滚，取消完全碰不到它；
     * 且只触发一次 InvalidationTracker，UI 的单一 Flow 只发射一次，压缩卡**立即出现**，
     * 不再需要任何内存侧手工对齐。
     */
    suspend fun commitCompaction(
        conversationId: Long,
        foldIds: List<Long>,
        marker: ChatMessageModel,
        summary: ChatMessageModel
    ) {
        dao.commitCompaction(
            conversationId = conversationId,
            foldIds = foldIds,
            marker = marker.toEntity(conversationId),
            summary = summary.toEntity(conversationId)
        )
    }

    /**
     * 原子回退到 [cutoffId] 之前：删压缩产物 + 取消全部折叠 + 截断尾部。
     * 
     *
     * @param deletePrefixes 压缩 marker / 摘要 / 压缩通知的内容前缀。
     */
    suspend fun rewindConversation(conversationId: Long, cutoffId: Long, deletePrefixes: List<String>) =
        dao.rewindConversation(conversationId, cutoffId, deletePrefixes)

    /**
     * **原子**把本会话消息整体重写为 [messages]（清空 + 按给定顺序写入，单个 Room 事务）。
     *
     * 用于撤销 / 回退 / 编辑 / 重新生成 / 删除。传进来的顺序即显示顺序
     * （`seq` 由下标重新分配），调用方不必再按 id 排序——id 有库自增与内存时间戳两套来源，
     * 混排会把折叠历史甩到错误位置。
     *
     * 收进一个事务的收益不只是性能：原先「清空 + 逐条插入」是几十上百次独立写，
     * 中途被取消会留下「已清空但没写完」的残局（整段历史消失），为补救不得不先 cancel
     * 消息 observer 再手工重建，而重建失败就是用户看到的坏状态。现在整体生效或整体回滚，
     * observer 完全不用动。
     */
    suspend fun replaceAllMessages(conversationId: Long, messages: List<ChatMessageModel>) =
        dao.replaceAllOfConversation(conversationId, messages.map { it.toEntity(conversationId) })

    suspend fun deleteByContentPrefix(conversationId: Long, prefix: String) =
        dao.deleteByContentPrefix(conversationId, prefix)

    suspend fun insert(conversationId: Long, message: ChatMessageModel): Long {
        return dao.insertOrdered(conversationId, message.toEntity(conversationId))
    }

    /**
     * 分支会话：把整理好的消息整批复制进新会话（单事务）。
     *
     * 调用方负责把 id 归零、处理 folded/queued（见 `ConversationForkPolicy`）；
     * 这里保留 seq 原值，复制后的显示序与源会话一致，新会话下一次插入仍取 `MAX(seq)+1`。
     */
    suspend fun insertAll(conversationId: Long, messages: List<ChatMessageModel>) {
        if (messages.isEmpty()) return
        dao.insertAll(messages.map { it.toEntity(conversationId) })
    }

    suspend fun updateToolResult(messageId: Long, result: String) {
        dao.updateToolResult(messageId, result)
    }

    suspend fun clearByConversation(conversationId: Long) {
        dao.clearByConversation(conversationId)
    }

    suspend fun deleteById(messageId: Long) {
        dao.deleteById(messageId)
    }

    suspend fun deleteByIds(conversationId: Long, ids: List<Long>) {
        ids.chunked(MessageDao.SQLITE_MAX_VARIABLES).forEach { dao.deleteRowsByIds(conversationId, it) }
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
