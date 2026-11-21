package com.wy.openx.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 按天（本地时区）聚合的单日调用统计，供趋势图使用。day 为本地时区下的 epoch 天序号。 */
data class DayCallStats(
    val day: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val calls: Int,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?
)

/** 按渠道（provider / apiPreset 名）聚合的调用统计，供渠道排行使用。 */
data class ProviderCallStats(
    val providerId: String?,
    val providerName: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val errors: Int
)

/** 按模型聚合的调用统计，供模型排行使用。 */
data class ModelCallStats(
    val model: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    /** 写入缓存的 token 数之和，按单独单价计入费用估算。 */
    val cacheCreationTokens: Long,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val errors: Int
)

/**
 * 按「渠道 + 模型」聚合的用量，供 `/usage` 按各自单价估算费用。
 * 与 [ModelCallStats] 的区别：多带 `providerId`，同名模型挂在不同渠道时能取到对应单价。
 */
data class ModelProviderCostStats(
    val model: String?,
    val providerId: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val cacheCreationTokens: Long
)

/** 当前周期的整体汇总，供概览卡片使用。 */
data class CallSummary(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?
)

/** 调用明细分页记录：单次调用 + 渠道名（渠道名即 providerId，apiPreset 名）。 */
data class RecentCallRecord(
    @Embedded val record: LlmCallRecordEntity,
    val providerName: String?
)

/** 单个会话（sessionId）的调用汇总：请求数=记录条数，输入/输出为每次真实 usage 之和（聊天页上下文栏菜单用）。 */
data class SessionCallSummary(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long
)

@Dao
interface LlmCallRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LlmCallRecordEntity)

    /** 全量读取（备份导出用）。 */
    @Query("SELECT * FROM llm_call_records ORDER BY id ASC")
    suspend fun getAll(): List<LlmCallRecordEntity>

    /** 全库区间（备份导出用，同样绕开 2MB CursorWindow 窗口）。 */
    @Query("SELECT * FROM llm_call_records WHERE id > :afterId AND id <= :beforeId ORDER BY id ASC")
    suspend fun getAllRange(afterId: Long, beforeId: Long): List<LlmCallRecordEntity>

    /** 全库 id（导出分块用）。 */
    @Query("SELECT id FROM llm_call_records ORDER BY id ASC")
    suspend fun getAllIds(): List<Long>

    /** 批量写入（备份恢复用）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<LlmCallRecordEntity>)

    @Query("DELETE FROM llm_call_records")
    suspend fun deleteAll()

    /**
     * 按天聚合（本地时区：把 createdAt 平移 [tzOffsetMillis] 后再按 86400000 取整切天）。
     * [tzOffsetMillis] 由调用方按当前系统时区提供（TimeZone.getDefault().getOffset(now)）。
     */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 86400000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getDayStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** 按小时聚合（本地时区），「今天」周期用小时粒度展示趋势。 */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 3600000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getHourStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** 按渠道（provider / apiPreset 名）聚合，按总消耗倒序。 */
    @Query(
        """
        SELECT providerId AS providerId,
               providerId AS providerName,
               COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
        GROUP BY providerId
        ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC
        """
    )
    fun getProviderStats(start: Long, providerId: String? = null): Flow<List<ProviderCallStats>>

    /** 按模型聚合，按总消耗倒序。 */
    @Query(
        """
        SELECT model AS model,
               COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY model
        ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC
        """
    )
    fun getModelStats(start: Long, providerId: String? = null, model: String? = null): Flow<List<ModelCallStats>>

    /** 按「渠道 + 模型」聚合，`/usage` 据此按各自单价估算费用。 */
    @Query(
        """
        SELECT model AS model,
               providerId AS providerId,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY providerId, model
        """
    )
    fun getModelProviderCostStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ModelProviderCostStats>>

    /** 周期整体汇总（无 GROUP BY，恒返回一行）。 */
    @Query(
        """
        SELECT COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        """
    )
    fun getSummary(start: Long, providerId: String? = null, model: String? = null): Flow<CallSummary>

    /** 当前周期调用总数，供分页显示总页数。 */
    @Query(
        """
        SELECT COUNT(*) FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        """
    )
    fun getCallsCount(start: Long, providerId: String? = null, model: String? = null): Flow<Int>

    /** 调用明细分页（倒序，[offset] 起取 [limit] 条）。渠道名即 providerId（apiPreset 名）。 */
    @Query(
        """
        SELECT providerId AS providerName, *
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        ORDER BY createdAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getRecentCalls(
        start: Long,
        limit: Int,
        offset: Int,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<RecentCallRecord>>

    /** 单个会话总览（sessionId='会话id'），与 Token 统计弹窗同口径：真实 usage 累计，非消息单值估算。 */
    @Query(
        """
        SELECT COUNT(*) AS calls,
               COALESCE(SUM(inputTokens), 0) AS inputTokens,
               COALESCE(SUM(outputTokens), 0) AS outputTokens,
               COALESCE(SUM(cachedInputTokens), 0) AS cachedInputTokens
        FROM llm_call_records
        WHERE sessionId = :sessionId
        """
    )
    fun getSessionSummary(sessionId: String): Flow<SessionCallSummary>

    /**
     * 删除 [before] 之前的记录（保留策略）。
     *
     * 这张表只增不减：每次 LLM 调用（含工具循环里的每一轮）都插一行，
     * 而所有统计查询都带 `createdAt >= :start`（按天/周/月聚合），历史再老也不会被读到，
     * 留着只是白占空间并拖慢全表扫描。
     */
    @Query("DELETE FROM llm_call_records WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /** 表内最早一条的时间，用于只在该必要时清理。 */
    @Query("SELECT MIN(createdAt) FROM llm_call_records")
    suspend fun getOldestCreatedAt(): Long?
}
