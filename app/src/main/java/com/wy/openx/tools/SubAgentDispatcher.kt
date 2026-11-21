package com.wy.openx.tools

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 子代理调度
 *
 * 
 * - （OpenX 侧由 ChatRepository 实现，复用已有的
 *   流式工具循环：SSE、多 Key 轮换、缓存、thinking-only 兜底、可取消，全部白拿）。
 * - `AgentToolCatalogView` → [SubAgentToolPolicy]（按工具名的白名单 + 参数级动作校验）。
 * - `ReportingSubagentCallback` → [SubAgentRunRequest.onProgress]（进度回传，不污染父级流）。
 *
 * 生命周期：父级取消通过 supervisorScope 自然传播——父工具调用被取消时，
 * 所有在飞的子代理协程一起被拆掉，不会留下后台野跑。
 */
data class SubAgentTaskSpec(
    val profileId: String,
    val instruction: String
)

/** 一次子代理运行的结果。 */
data class SubAgentRunResult(
    val subagentId: String,
    val profileId: String,
    val taskIndex: Int,
    val status: String,          // completed / failed / timeout
    val finalContent: String,
    val toolCallSummaries: List<String> = emptyList(),
    val errorMessage: String? = null,
    val rounds: Int = 0,
    /**
     * 本次运行累计的真实 token（含工具循环各轮），供用量统计按模型归集。
     * 仓库层从 streamChat 的 onTokens / onCompletionTokens / onCacheHit 累计填进来。
     */
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheTokens: Int = 0
)

/** 父级请求一次子代理运行所需的全部参数。 */
data class SubAgentRunRequest(
    val subagentId: String,
    val taskIndex: Int,
    val profileId: String,
    val systemPrompt: String,
    val instruction: String,
    /** 已按角色裁剪好的工具 schema；空列表表示不下发 tools（如 planner）。 */
    val tools: List<ToolDef>,
    val maxRounds: Int,
    val timeoutMs: Long,
    val maxTokens: Int?,
    val temperature: Float?,
    /** 进度回调：(kind, summary, toolName)。kind ∈ started/thinking/tool_started/tool_done/done/failed。 */
    val onProgress: (suspend (kind: String, summary: String, toolName: String?) -> Unit)? = null
)

/** 由 ChatRepository 实现；用接口打断 SubAgentTool → Dispatcher → ChatRepository 的构造期循环。 */
interface SubAgentRunner {
    suspend fun runSubagent(req: SubAgentRunRequest): SubAgentRunResult
}

/** 并行度默认值：有状态工具（terminal / browser）并发会互相干扰，故默认封顶 3。 */
private const val MAX_CONCURRENCY = 3

/**
 * cap 的默认值取 [SubAgentLimits.concurrencyCap]（即用户设置）而不是常量 MAX_CONCURRENCY：
 * 这样将来新增调用点忘了传 cap，也不会静默退回 3 而让用户的设置失效。
 */
internal fun resolveSubAgentConcurrency(
    requested: Int?,
    taskCount: Int,
    cap: Int = SubAgentLimits.concurrencyCap()
): Int {
    require(taskCount > 0) { "tasks 不能为空" }
    val effectiveCap = cap.coerceIn(1, 5)
    if (requested != null) {
        require(requested > 0) { "concurrency 必须大于 0" }
        return requested.coerceAtMost(effectiveCap)
    }
    return taskCount.coerceAtMost(effectiveCap)
}

object SubAgentDispatcher {

    /**
     * 分派一批子任务并等待全部结束。
     * 单个子任务失败不影响其他任务（supervisorScope + 内部兜底），失败信息随结果返回给父级。
     */
    suspend fun dispatch(
        tasks: List<SubAgentTaskSpec>,
        concurrency: Int,
        runnerProvider: () -> SubAgentRunner?,
        onEvent: suspend (taskIndex: Int, profileId: String, subagentId: String, kind: String, summary: String, status: String, toolName: String?) -> Unit
    ): List<SubAgentRunResult> {
        if (tasks.isEmpty()) return emptyList()
        val limit = resolveSubAgentConcurrency(concurrency, tasks.size, SubAgentLimits.concurrencyCap())
        val seq = AtomicLong(0)
        tasks.forEachIndexed { i, spec ->
            onEvent(i, spec.profileId, "-", "dispatch_started",
                "正在分派 ${tasks.size} 个子任务（并发 $limit），第 ${i + 1} 个：${SubAgentProfiles.get(spec.profileId).displayName}", "running", null)
        }
        val semaphore = Semaphore(limit)
        return supervisorScope {
            tasks.mapIndexed { index, spec ->
                async {
                    semaphore.withPermit {
                        runOne(index, spec, runnerProvider, seq, onEvent)
                    }
                }
            }.awaitAll().sortedBy { it.taskIndex }
        }
    }

    private suspend fun runOne(
        taskIndex: Int,
        spec: SubAgentTaskSpec,
        runnerProvider: () -> SubAgentRunner?,
        seq: AtomicLong,
        onEvent: suspend (Int, String, String, String, String, String, String?) -> Unit
    ): SubAgentRunResult {
        val profile = SubAgentProfiles.get(spec.profileId)
        val subagentId = "subagent-${UUID.randomUUID().toString().take(8)}"
        val emit: suspend (String, String, String, String?) -> Unit = { kind, summary, status, toolName ->
            onEvent(taskIndex, profile.id, subagentId, kind, summary, status, toolName)
        }
        return try {
            val runner = runnerProvider()
                ?: return SubAgentRunResult(subagentId, profile.id, taskIndex, "failed", "",
                    errorMessage = "子代理运行器未就绪（应用初始化未完成）").also {
                        emit("failed", "SubAgent #${taskIndex + 1} 启动失败：运行器未就绪", "failed", null)
                    }
            emit("started", "SubAgent #${taskIndex + 1} 开始：${compact(spec.instruction)}", "running", null)
            val tools = SubAgentToolPolicy.toolsFor(profile.id, availableTools())
            // 空列表 → null：部分网关对 "tools": [] 直接报错
            val result = runner.runSubagent(
                SubAgentRunRequest(
                    subagentId = subagentId,
                    taskIndex = taskIndex,
                    profileId = profile.id,
                    systemPrompt = profile.systemPrompt,
                    instruction = spec.instruction,
                    tools = tools,
                    maxRounds = SubAgentLimits.maxRounds(),
                    timeoutMs = SubAgentLimits.timeoutMs(),
                    maxTokens = SubAgentLimits.MAX_TOKENS,
                    temperature = null,
                    onProgress = { kind, summary, toolName ->
                        emit(kind, summary, if (kind == "tool_done") "completed" else "running", toolName)
                    }
                )
            )
            emit(
                if (result.status == "completed") "done" else "failed",
                "SubAgent #${taskIndex + 1} ${if (result.status == "completed") "完成" else "未完成"}：${compact(result.finalContent.ifBlank { result.errorMessage.orEmpty() })}",
                if (result.status == "completed") "completed" else "failed",
                null
            )
            result.copy(profileId = profile.id, taskIndex = taskIndex)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val msg = e.message ?: "subagent execution failed"
            emit("failed", "SubAgent #${taskIndex + 1} 失败：${compact(msg)}", "failed", null)
            SubAgentRunResult(subagentId, profile.id, taskIndex, "failed", "", errorMessage = msg)
        }
    }

    private fun availableTools(): List<ToolDef> =
        AppContextHolder.toolRegistry?.getApiTools(
            enableWebSearch = true,
            enableFileTools = true,
            enableMultiAgent = false
        ).orEmpty()

    /** 进度文案压成单行短句，避免工具卡片被长思考文本撑爆。 */
    private fun compact(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().take(120)
}

/** 子代理预算。OpenX 主循环不设轮次上限，
 *  子代理在 explore 大仓库上极易空转烧额度，故这里给硬上限。 */
object SubAgentLimits {
    const val TIMEOUT_MS = 1_800_000L        // 单个子代理 30 分钟（未配置时的兜底）
    const val MAX_TOKENS = 8192            // 子代理报告较长，避免被截断

    /** 并发上限（设置 → 子代理设置，未配置/越界时回落到 3，范围 1..5）。 */
    fun concurrencyCap(): Int =
        (AppContextHolder.currentSettings?.subagentConcurrency ?: DEFAULT_CONCURRENCY).coerceIn(1, 5)

    /** 单任务超时（设置 → 子代理设置，未配置/越界时回落到 5 分钟，范围 30..5400 秒）。 */
    fun timeoutMs(): Long =
        ((AppContextHolder.currentSettings?.subagentTimeoutSec ?: DEFAULT_TIMEOUT_SEC).coerceIn(30, 7200)) * 1000L

    /**
     * 单任务工具轮次上限（设置 → 子代理设置，未配置/越界时回落到 12，范围 1..100）。
     *
     * 与 [timeoutMs] 的区别在失败方式：轮次触顶会走一次**无工具的收尾请求**，
     * 模型仍能基于已获信息给出结论；超时则是直接判 timeout，累积上下文全丢。
     */
    fun maxRounds(): Int =
        (AppContextHolder.currentSettings?.subagentMaxRounds ?: DEFAULT_MAX_ROUNDS).coerceIn(1, 200)

    private const val DEFAULT_CONCURRENCY = 3
    private const val DEFAULT_TIMEOUT_SEC = 1800
    private const val DEFAULT_MAX_ROUNDS = 50
}
