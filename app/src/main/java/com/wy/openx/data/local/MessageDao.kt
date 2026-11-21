package com.wy.openx.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 消息表访问层。
 *
 * ## 为什么没有「SELECT * 一次读完整个会话」
 *
 * Android 的 `CursorWindow` 只有一个 **2MB** 的窗口，**整个 cursor 的所有行共用它**。
 * 长会话的消息正文（工具输出、大段代码、文件内容）累计轻易超过 2MB，一旦超了，
 * 读某一行就会抛：
 *
 * ```
 * IllegalStateException: Couldn't read row 359, col 0 from CursorWindow.
 * ```
 *
 * 这个崩溃与内存无关、和消息条数也无关，只与**单次查询返回的字节数**有关，
 * 所以删消息、减内存都无效——只有把读取拆成多块才有用。
 *
 * 因此这里的读取一律走两条路：
 * 1. [observeAllIds] / [getIds] / [getSessionIdsIncludingFolded]：只取 id（8B/行，
 *    几千行也只有几十 KB，永不溢出），用来做变更检测；
 * 2. [getChunk]：按 `id > :afterId ORDER BY id LIMIT :limit` 分块取正文。
 *
 * 组装由 `MessageRepository.loadChunked` 负责，并且带**自适应降块**：
 * 某块仍超窗口就折半重试，单行都读不出来时跳过该行并记日志——
 * 绝不让一条畸形消息（超大 content）把整个会话变成读不出来。
 */
@Dao
interface MessageDao {
    // ─────────────── 变更检测（只取 id，不读正文）───────────────
    //
    // ⚠ 全部按 **seq**（会话内单调显示序）排序，不用 id。
    // id 有「库自增」与「内存 nextMessageId=毫秒×1000」两套来源，混在一列里
    // `ORDER BY id` 不等于显示顺序：压缩新插入的 marker/summary 拿到很小的自增 id，
    // 而排在它前面的折叠历史里混着内存大 id → 压缩卡被排到折叠历史之前，
    // 用户看到「卡片跑到最上面、历史消失」。seq 由 [insertOrdered] 统一分配，与 id 解耦。
    // `seq` 相同时（迁移回填的旧数据）用 id 兜底，保证稳定序。

    /** 会话内全部消息 id（**不分折叠**）：UI 单一列表的变更检测源（对齐 RC2 的 `getMessagesBySession`）。 */
    @Query("SELECT id FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeAllIds(conversationId: Long): Flow<List<Long>>

    /**
     * 会话内全部消息 id（一次性，不分折叠）。
     *
     * ⚠ 这里**必须按 id 升序**：返回值要交给 [ChunkedReader] 当分块区间表的上下界
     * （`ids[i]-1` 作开区间下界、`ids[end-1]` 作闭区间上界），按 seq 排会让区间算错、
     * 漏读重读。显示序在读出正文后再按 seq 排（见 `MessageRepository`）。
     */
    @Query("SELECT id FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getSessionIdsIncludingFolded(conversationId: Long): List<Long>

    // ⚠ 会话内一次性取 id 的查询**全部按 id 升序**，不用 seq：返回值要交给 [ChunkedReader]
    // 当分块区间表的上下界（`ids[i]-1` 作开区间下界、`ids[end-1]` 作闭区间上界），
    // 按 seq 排会让区间算错、漏读重读。显示序在读出正文后再按 seq 排。

    @Query("SELECT id FROM messages WHERE conversationId = :conversationId AND folded = 0 ORDER BY id ASC")
    suspend fun getIds(conversationId: Long): List<Long>

    @Query("SELECT id FROM messages WHERE conversationId = :conversationId AND folded = 1 ORDER BY id ASC")
    suspend fun getFoldedIds(conversationId: Long): List<Long>

    // ─────────────── 分块取正文（CursorWindow 安全）───────────────
    //
    // 全部按 id **闭区间**取，而不是 LIMIT/OFFSET：区间两端已知，
    // 某块读炸时可精确折半重试；OFFSET 在大表上要扫描跳过，且分块期间
    // 若有插入/删除会整体错位。见 ChunkedReader。

    /** 区间内未折叠消息（进模型上下文的那份）。 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND folded = 0 AND id > :afterId AND id <= :beforeId ORDER BY seq ASC, id ASC")
    suspend fun getContextRange(conversationId: Long, afterId: Long, beforeId: Long): List<MessageEntity>

    /** 区间内已折叠历史（UI 翻历史用，不进模型）。 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND folded = 1 AND id > :afterId AND id <= :beforeId ORDER BY seq ASC, id ASC")
    suspend fun getFoldedRange(conversationId: Long, afterId: Long, beforeId: Long): List<MessageEntity>

    /** 区间内全部消息（**不分折叠**）：UI 单一列表的正文读取（对齐 RC2 的 `getMessagesBySession`）。 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND id > :afterId AND id <= :beforeId ORDER BY seq ASC, id ASC")
    suspend fun getSessionRangeIncludingFolded(conversationId: Long, afterId: Long, beforeId: Long): List<MessageEntity>

    /**
     * 取某会话最近 N 条消息（插件读聊天上下文用）。
     *
     * 必须用 LIMIT：`getSessionRangeIncludingFolded` 会取整会话，长会话直接把内存吃满
     * （真实崩溃过：CursorWindow 取字符串时 OOM）。插件接口一律只能取尾部。
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentMessages(conversationId: Long, limit: Int): List<MessageEntity>

    /** 全库区间（备份导出用，同样绕开 2MB 窗口）。 */
    @Query("SELECT * FROM messages WHERE id > :afterId AND id <= :beforeId ORDER BY id ASC")
    suspend fun getAllRange(afterId: Long, beforeId: Long): List<MessageEntity>

    /** 全库 id（导出分块用）。 */
    @Query("SELECT id FROM messages ORDER BY id ASC")
    suspend fun getAllIds(): List<Long>

    // ─────────────── 压缩折叠标记 ───────────────

    /**
     * 按**显式 id 集合**折叠（压缩时调用）。
     *
     * 用「按 id 集合」而不是删库重写：原始消息永久保留，可往上翻阅、可恢复。
     *
     * ⚠ 绝不能用 `id <= maxOf{id}` 划界：openx 的消息 id 有「库自增」与「内存
     * `nextMessageId=毫秒×1000`」两套来源，`<=` 划界假设「id 顺序 == 显示顺序」，
     * 混用时 `maxOf` 会取到天文数字，把**该保留的尾部也一并折叠**，用户看到「消息全没了」。
     * 按集合折叠与顺序解耦。
     */
    @Query("UPDATE messages SET folded = 1 WHERE conversationId = :conversationId AND folded = 0 AND id IN (:ids)")
    suspend fun markFoldedByIds(conversationId: Long, ids: List<Long>)

    /** 取消全部折叠（恢复压缩前对话时调用）。 */
    @Query("UPDATE messages SET folded = 0 WHERE conversationId = :conversationId")
    suspend fun unmarkAllFolded(conversationId: Long)

    /**
     * **一次事务**提交一次上下文压缩。
     *
     * 折叠 [foldIds] + 写入 [marker]（压缩锚点）+ 写入 [summary]（摘要）三步在**同一个事务**里。
     *
     * 为什么必须是单个事务（这是「用户按暂停历史就消失」的根治点）：
     * 压缩常在回合 `finally` 里跑（工具循环后的落库、自动压缩），此刻用户按「停止」会取消
     * 本轮协程。若折叠与插摘要分成多次独立写，取消会命中中间那个挂起点：
     *   · 折叠已提交但摘要没插 → 上下文里既没摘要、又没有那段历史 → **消息整段消失**；
     *   · 摘要已插但折叠没提交 → 摘要与原文重复计入上下文，白占 token；
     *   · 两边都没写完 → 界面停在「只剩一张卡」的坏状态。
     * 放进单个 Room 事务后，要么整体生效、要么整体回滚，**取消碰不到它**——
     * 用户在压缩瞬间按暂停也不会出现半成品状态。
     *
     * 事务只提交一次 InvalidationTracker，UI 的单一 Flow 因此只发射一次，
     * 压缩卡「立即出现」而不需要任何内存侧手工对齐。
     *
     * @param foldIds 被折叠（不再进请求、但永久保留可翻阅）的消息 id 集合。
     *   用显式集合而非 `id <= X`：id 两套来源混用时 `<=` 划界会把该保留的尾部也折掉。
     * @param marker 压缩锚点行（USER，`COMPACTION_MARKER`）。它是压缩卡的**时序锚点**：
     *   插在压缩发生的那一刻，故卡片正好落在「最后一个执行完的工具」下面。
     * @param summary 摘要行（ASSISTANT）。与 marker 成对写入，回放时排在最前。
     *
     * ⚠ `seq` 布局由这里统一分配，**顺序**是这套机制里最容易错的一环：
     *   折叠原文(保留原 seq) < marker < 摘要 < 保留尾(重排到其后)
     * 见 [commitCompaction] 事务体的注释——踩过的坑是 marker 只取 MAX+1 而没搬保留尾，
     * 卡片会跑到会话最末尾。
     */
    @Transaction
    suspend fun commitCompaction(
        conversationId: Long,
        foldIds: List<Long>,
        marker: MessageEntity,
        summary: MessageEntity
    ) {
        foldIds.chunked(SQLITE_MAX_VARIABLES).forEach { markFoldedByIds(conversationId, it) }
        // ⚠ marker/摘要取**全表 MAX(seq)+1**，即排在包括保留尾在内的**所有消息之后**。
        //
        //用 `nextTimestamp()` 给 marker/摘要取时间戳：
        // 严格大于库里已有的一切（含保留尾）→ 卡片落在**会话末尾**，也就是「最后一个执行完的
        // 工具」下面，正是压缩发生的那一刻。
        //
        // 
        // 
        //   · 展示（按 seq）…保留尾… → marker → summary → 压缩后新消息
        //   · 模型请求          marker → summary → …保留尾…
        //
        // 【踩过的坑】曾把保留尾搬到 marker/摘要**之后**（让 marker 紧贴折叠边界），结果卡片
        // 跑到触发点**上方 7 个工具**处——因为保留尾本身就是「最后 4000 tok ≈ 7 组工具」。
        // 用户真机反馈：「触发压缩的执行工具位置往上数第 7 个工具下面显示卡片」。
        // 保留尾必须留在 marker 之前，卡片才会紧贴触发点。
        var seq = maxSeq(conversationId) + 1
        insert(marker.copy(conversationId = conversationId, seq = seq++))
        insert(summary.copy(conversationId = conversationId, seq = seq++))
    }

    /**
     * **一次事务**回退到指定消息之前。
     *
     * 四步原子：删压缩产物（marker + 摘要 + 失败通知）→ 取消全部折叠 → 删 cutoff 之后的消息。
     * 同样是为了让「停止/取消」碰不到中间态：分步写会出现「已解折叠但摘要还在」
     * （历史重复）或「已删尾部但仍折叠着」（尾部消息不可见）。
     *
     * @param cutoffId 保留到的消息 id（含）；该 id 之后的消息全部删除。
     * @param deletePrefixes 要删的内容前缀（压缩 marker / 摘要 / 压缩通知），作为**参数**传入
     *   而不是拼进 SQL 字面量：写这些内容的地方与删它们的地方共用同一常量，
     *   改文案时不会出现「写得出来、删不掉」的不对称。
     */
    @Transaction
    suspend fun rewindConversation(
        conversationId: Long,
        cutoffId: Long,
        deletePrefixes: List<String>
    ) {
        deletePrefixes.forEach { deleteByContentPrefix(conversationId, it) }
        unmarkAllFolded(conversationId)
        deleteAfterId(conversationId, cutoffId)
    }

    /** 删除 id 大于 [id] 的消息（回退时截断尾部）。 */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :id")
    suspend fun deleteAfterId(conversationId: Long, id: Long)

    /** 批量删除指定 id 集合（清理「超过压缩层数上限」的最老压缩轮次）。 */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id IN (:ids)")
    suspend fun deleteRowsByIds(conversationId: Long, ids: List<Long>)

    /**
     * **一次事务**把本会话消息整体重写为 [messages]（清空 + 按给定顺序写入）。
     *
     * 用于「撤销 / 回退 / 编辑 / 重新生成 / 删除」这 5 条改写历史的路径。
     *
     * 必须是单个事务的原因同 [commitCompaction]：原先这里是 `clearByConversation` + 逐条
     * `insert`（几十上百次独立写），中间任何时刻被取消都会留下「已清空但没写完」的残局——
     * 整段历史凭空消失。为此不得不先 cancel 掉消息 observer 再手工重建，
     * 而「cancel 之后重建失败」正是用户报障的根因。
     *
     * 收进一个事务后：要么整体生效、要么整体回滚，Room 只发射一次，
     * observer **完全不需要**被 cancel，界面也不会停在任何中间态。
     *
     * [seq] 按传入下标重新分配（1,2,3…），因此传进来的顺序就是显示顺序。
     *
     * ⚠ `folded` **原样保留**（本函数不替调用方决定折叠状态）。所以传进来的必须是「新状态」的
     * 完整快照：要解除压缩就得先把 `folded` 清成 false 再传，否则那些消息在库里**仍然是折叠的**。
     * 踩过的坑：回退到折叠区时传的是 `all.take(idx)`，原文带着 `folded=true` 被原样写回——
     * 「仅恢复会话」后消息在库里仍折叠（不进 `uiState.messages`、不进请求），
     * 顶部上下文数字按剩下的小集合算，用户看到「恢复了，数字却变小了」。
     */
    @Transaction
    suspend fun replaceAllOfConversation(conversationId: Long, messages: List<MessageEntity>) {
        clearByConversation(conversationId)
        if (messages.isNotEmpty()) {
            insertAll(messages.mapIndexed { index, m -> m.copy(conversationId = conversationId, seq = (index + 1).toLong()) })
        }
    }

    /**
     * 按内容前缀删除本会话的消息（恢复压缩时用）。
     *
     * 前缀作为**参数**传入而不是拼进 SQL 字面量：写通知的地方和删通知的地方
     * 共用同一个常量（MainViewModel 的 COMPACTION_NOTICE_PREFIX / SUMMARY_PREFIX），
     * 改文案时不会出现「写得出来、删不掉」的不对称。
     *
     * @return 删除条数
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND content LIKE :prefix || '%'")
    suspend fun deleteByContentPrefix(conversationId: Long, prefix: String): Int

    // ─────────────── 写入 ───────────────

    /** 批量写入（备份恢复用）：保留原 id 以维持会话内顺序与工具调用关联。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    /** 会话内下一个显示序号。必须在写事务里读，保证与同事务内的插入不打架。 */
    @Query("SELECT IFNULL(MAX(seq), 0) + 1 FROM messages WHERE conversationId = :conversationId")
    suspend fun nextSeq(conversationId: Long): Long

    /**
     * 按会话顺序插入：在写事务里给 [message] 赋 `MAX(seq)+1` 再插。
     *
     * 调用方**永远不要**自己填 `seq`——必须由库分配，否则两条并发插入会拿到同一个序号，
     * 显示序就乱了（这正是本次要根治的「压缩卡跑到最上面」的同源问题）。
     *
     * 之所以要一个专门的插入入口而不是在调用方算：算 seq 需要读库，必须与插入同事务，
     * 否则「读 seq」和「插入」之间夹进另一条消息就会重号。
     */
    @Transaction
    suspend fun insertOrdered(conversationId: Long, message: MessageEntity): Long =
        insert(message.copy(conversationId = conversationId, seq = nextSeq(conversationId)))

    /** 会话内当前最大的显示序号（空会话为 0）。 */
    @Query("SELECT IFNULL(MAX(seq), 0) FROM messages WHERE conversationId = :conversationId")
    suspend fun maxSeq(conversationId: Long): Long

    /**
     * 未折叠消息（= 当前上下文）的 id，按显示序 `seq, id` 升序。
     *
     * [commitCompaction] 用它在插入 marker/摘要**之前**取「保留尾」的原始顺序：
     * 保留尾的 seq 还是压缩前的原值，必须按当前显示序搬走，否则 marker 会被排到它们后面。
     */
    @Query("SELECT id FROM messages WHERE conversationId = :conversationId AND folded = 0 ORDER BY seq ASC, id ASC")
    suspend fun unfoldedIdsOrdered(conversationId: Long): List<Long>

    /** 重排显示序号（仅供 [commitCompaction] 把保留尾挪到 marker/摘要之后）。 */
    @Query("UPDATE messages SET seq = :seq WHERE id = :id AND conversationId = :conversationId")
    suspend fun setSeq(conversationId: Long, id: Long, seq: Long)

    @Query("UPDATE messages SET toolResult = :result WHERE id = :id")
    suspend fun updateToolResult(id: Long, result: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearByConversation(conversationId: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    companion object {
        /**
         * SQLite 单条语句的绑定变量上限（默认 999）。`IN (:ids)` 折叠几百条消息会超限，
         * 故所有按 id 集合的写操作都要切块。
         */
        const val SQLITE_MAX_VARIABLES = 900
    }
}
