package com.wy.openx.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.wy.openx.data.model.AppSettings
import com.wy.openx.privacy.maskedForOutbound
import com.wy.openx.privacy.restoredFromOutbound
import com.wy.openx.data.model.AIProviderConfig
import com.wy.openx.data.provider.ProviderHttp
import com.wy.openx.data.provider.ProviderKeyRotator
import com.wy.openx.data.provider.KeySwitchResult
import com.wy.openx.data.provider.isApiKeyFailure
import com.wy.openx.data.remote.ChatMessageDto
import com.wy.openx.data.remote.ChatRequest
import com.wy.openx.tools.CheckpointManager
import com.wy.openx.tools.ReActToolParser
import com.wy.openx.tools.ToolCall
import com.wy.openx.tools.ToolCallFunction
import com.wy.openx.tools.ToolDef
import com.wy.openx.tools.ToolRegistry
import com.wy.openx.tools.SubAgentRunRequest
import com.wy.openx.tools.SubAgentRunResult
import com.wy.openx.tools.SubAgentToolPolicy
import com.wy.openx.tools.AppContextHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.wy.openx.data.remote.JsonRequestBody
import com.wy.openx.data.remote.jsonPreview
import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * 单次聊天请求的提供商解析上下文。由 [ChatRepository.runChat] 根据「当前激活提供商」解析：
 * 命中已注册 provider 时携带其端点/模型/Key/代理/UA/缓存键，未命中（旧数据）回退 settings 扁平字段。
 */
data class ProviderCallContext(
    val providerId: String,
    val config: AIProviderConfig?,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val useResponseApi: Boolean = false,
    val useFullUrl: Boolean = false,
    val openaiChatCacheKey: Boolean = false,
    val userAgent: String = "",
    val sessionId: String? = null,
    /**
     * 本轮 user 消息 id，决定工具写入的文件快照落进哪个检查点桶。
     * 多会话并行时必须随行：CheckpointManager.currentMsgId 是全局单值，
     * 两会话交替 beginTurn 时它只反映最后一次，会让 A 的写入记到 B 的桶里。
     * 0 = 未指定（子代理/旧语义，沿用全局指针）。
     */
    val checkpointTurnId: Long = 0L,
    /**
     * 本轮生效的思考强度（**会话档**，由 VM 合并进 settings 后传下来）。
     * 不能再去读 `AppContextHolder.currentSettings`——那是全局单槽，
     * 并发时 A、B 交替写入会互相覆盖，读到的是别的会话的选择。
     */
    val reasoningEffort: String = "default",
    /**
     * 本轮所属会话 id（0 = 未知）。HTTP 句柄按它分槽（见 [ChatRepository.currentCalls]），
     * 停止某会话只取消它自己那条连接。子代理/识图等无会话上下文场景传 0，走兜底槽。
     */
    val conversationId: Long = 0L,
    /**
     * 【P4-1】本轮生效的上下文窗口（tokens）。0 = 未传，退回全局
     * `AppContextHolder.contextWindowTokens`。
     *
     * 会话级模型下各会话窗口可能差几倍，就地压缩阈值必须随请求走；
     * 那个全局槽并发时会被另一个会话覆盖，拿它判定会「该压不压 / 提前压缩」。
     */
    val contextWindowTokens: Int = 0
)

/**
 * SSE 流内 error 帧或不可用端点抛出的异常：
 * 携带错误码供上层判定是否可重试，并让上层能区分「网关报错」与「模型正常但空」。
 */
class StreamApiException(
    val code: String?,
    message: String,
    val retryAfterMillis: Long? = null
) : Exception(message.ifBlank { code ?: "stream error" })

/**
 * 请求体过大被网关/上游拒绝（④）。
 *
 * 与 [ContextOverflow] 的分工：那个按「状态码 + 厂商文案」判定，**不含 5xx**
 * （500 多数是服务端故障，拿它当超长只会白等一轮）。但中转层拒绝超大请求体时
 * 恰恰只给一个没有信息量的 500/502/504——文案里什么都没有，正则无从命中。
 *
 * 所以补一条本地已知事实作为信号：**这个请求体本身就大**。
 * 大请求体 + 网关类状态码 = 极可能是体积问题，此时按体积问题上报，
 * 让上层去「强制压缩 + 换个更小的请求体重试」，而不是拿同一个超大 body 继续退避重试。
 */
class RequestBodyTooLargeException(
    message: String,
    val bodyBytes: Int,
    val statusCode: Int?,
    cause: Throwable? = null
) : Exception(message, cause)

class ChatRepository {
    private val gson = Gson()

    /**
     * 出站脱敏器：发给模型的正文/思考/工具参数里的凭据会被换成占位符，回复里再回填。
     *
     * 全库共用一份（而不是按会话重建）：占位符由真值哈希派生，同一真值恒定不变，
     * 跨会话复用反而让 prompt cache 更稳。映射只在内存，进程退出即丢。
     */
    private val outboundMasker = com.wy.openx.privacy.OutboundMasker.create()

    /** 出站脱敏开关（默认**关**）。关掉时所有 mask/restore 都是零开销的直通。 */
    @Volatile
    var maskOutbound: Boolean = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    /**
     * 每会话当前在途的 HTTP 请求句柄。键 = conversationId（0 = 未知会话兜底槽）。
     * 旧版是单个 `currentCall`：并发时后发起的请求会覆盖句柄，`cancelCurrentCall()`
     * 只能取消最后一个 —— 用户在 A 按停止却掉断了 B 正在进行的请求。
     */
    private val currentCalls = java.util.concurrent.ConcurrentHashMap<Long, okhttp3.Call>()
    private fun setCurrentCall(conversationId: Long, call: okhttp3.Call) { currentCalls[conversationId] = call }
    private fun currentCallOf(conversationId: Long): okhttp3.Call? = currentCalls[conversationId]

    /** 多 Key 轮换器：按会话粘性选 Key，失败达阈值自动切换。 */
    private val keyRotator = ProviderKeyRotator()

    /**
     * 网络重连状态回调：开始重试时携带重试次数（第 [attempt]/[maxRetries] 次），
     * 请求成功/放弃时回调 (0, 0)（UI 用于显示"重连中 (第 N/M 次)"）。
     *
     * 必须带 conversationId：多会话并行时若只回调布尔值，A 会话正在退避重连、
     * B 会话请求成功发出的 false 会把全局指示清掉，A 明明还在重试、UI 却显示正常。
     */
    @Volatile
    var onReconnectChange: ((attempt: Int, maxRetries: Int, Long) -> Unit)? = null

    /**
     * 「重试前清空已展示文本」通道。
     *
     * [sseCallWithRetry] 默认在「本次尝试已向 UI 输出过增量」后不再重试，以免重发导致正文重复。
     * 注册本回调后（主链路在 `MainViewModel` 注册），正文**流到一半**断网也会重试：重试前调用
     * 本回调，让 UI 精确回退掉本次尝试已展示的正文，随后由 [sseCallWithRetry] 从头重发。
     * 未注册时维持旧行为（已输出即不重试）。
     *
     * 必须带 conversationId：多会话并行时按会话回退各自的正文，不能误伤别的会话。
     */
    @Volatile
    var onStreamReset: ((conversationId: Long) -> Unit)? = null

    companion object {
        private const val MAX_RETRIES = 6

        /**
         * 「大请求体」阈值：400KB。
         *
         * 参照物：nginx `client_max_body_size` 默认 1M，但中转/反代常配 512K 甚至 256K；
         * 而 openx 单次 `readFile` 就能带回 200KB 工具输出（`ReadFileTool.MAX_BYTES`），
         * 两三次大读取就够越过 400KB——正是「上下文 250 多 K 就 500、压缩一下就好」那个区间。
         */
        private const val LARGE_BODY_BYTES = 400 * 1024

        /**
         * 大请求体失败时的重试上限：只留 1 次。
         *
         * **只针对「疑似体积类」失败**（见 [retryBudgetFor]）：同一个超大 body 连退避重发多次也不会变小。
         * 若失败其实是上游瞬时故障（账号池耗尽等），仍按 [MAX_RETRIES] 正常重试。
         */
        private const val LARGE_BODY_MAX_RETRIES = 1

        /** 常规退避封顶：10s。 */
        private const val RETRY_DELAY_CAP_MS = 10_000L

        /**
         * 大请求体退避封顶：5s。
         *
         * 次数不砍（瞬时故障需要重发机会），只是把单次等待压短，避免大 body 下用户被按在
         * 「重连中…」太久。对「账号池耗尽」这类几秒就恢复的故障，低封顶的多轮重试反而更容易成功。
         */
        private const val LARGE_BODY_RETRY_DELAY_CAP_MS = 5_000L

        /**
         * 会被当成「可能是体积问题」的状态码。
         *
         * - **413**：标准「Payload Too Large」，保留。
         * - **500**：部分中转笼统用 500 表体积超限，保留。
         * - 502/504 **已移除**：HTTP 语义是「网关拿不到上游响应」（Bad Gateway / Timeout），
         *   与体积无关；历史上把「大 body + 502 瞬时故障」误判成体积超限，触发过不可逆的强制压缩。
         * - 503（服务不可用/过载）与 429（限流）同样排除。
         *
         * 注意：状态码只是**候选**，真正确认还要过 [ContextOverflow.isTransientGatewayError] 排除。
         */
        private val GATEWAY_SIZE_CODES = setOf(413, 500)

        /**
         * 工具输出里出现这些特征即视为「被限流」。见 [looksRateLimited]。
         *
         * 只做特征词匹配、不解析结构：工具输出格式各家不同（纯文本 / JSON / 网关 HTML），
         * 统一解析的成本远高于收益。命中后仅用于「建议用户降低并发」，不参与任何流程控制。
         *
         * ⚠ **这里不能放裸的 "429"**：任何工具输出里出现这个数字（搜到的网页、读到的日志、
         * 文件行号、token 数）都会被当成限流。状态码必须带上下文一起匹配；
         * 并且判定只在**工具执行失败**的结果上做（见调用点），两道一起卡。
         */
        private val RATE_LIMIT_MARKERS = listOf(
            "too many requests", "rate limit", "ratelimit", "rate_limit",
            "429 too many", "http 429", "http/1.1 429", "status: 429", "status 429",
            "statuscode: 429", "code: 429",
            "限流", "请求过于频繁", "请求频率", "请求过快"
        )

        private const val TAG = "ChatRepo"
        // 工具循环不设轮次上限，跑到模型不再调用工具为止。终端类任务调用频繁，
        // 固定轮次上限会在任务中途截断，造成「AI 说一句就停」。安全性由每轮 ensureActive()（用户可停止）
        // 与自动压缩（控制上下文预算）保障，不靠轮次熔断。
        // 单个工具结果回传模型的最大字符数（此前 6 万对长日志/大文件读取不够，用户要求加大）
        private const val MAX_TOOL_RESULT_CHARS = 120_000
        // 超限时保留的头部/尾部字数：只留头部会丢掉尾部（报错、结论、diff 末段常在最后）
        private const val TOOL_RESULT_HEAD_CHARS = 20_000
        private const val TOOL_RESULT_TAIL_CHARS = 20_000
        private val REACT_ONLY_PROVIDERS = setOf("FreeModel")
        private fun supportsNativeToolCall(provider: String): Boolean = provider !in REACT_ONLY_PROVIDERS
        // 输出被 max_tokens 截断的 finish_reason 集合：
        // OpenAI 为 "length"，Anthropic 为 "max_tokens"，Gemini 归一后也为 "length"。
        private val TRUNCATION_STOP_REASONS = setOf("length", "max_tokens", "MAX_TOKENS")
        // 拒答/中止类 finish_reason：
        // 命中即终止本轮并给出可读原因，绝不静默退化成「空响应 → 引导重试 → [AI 处理完成]」。
        private val ABORT_STOP_REASONS = setOf(
            "refusal", "model_context_window_exceeded", "SAFETY", "PROHIBITED_CONTENT",
            "BLOCKLIST", "SPII", "failed", "budget_exceeded", "content_filter"
        )
        // 首字节看门狗超时：中转模型首包慢时避免无限等待。
        private const val FIRST_BYTE_TIMEOUT_MS = 120_000L
        // 工具循环上下文预算：超过 [TOOL_LOOP_CHAR_BUDGET] 字符时收缩较早工具结果（结构安全，不删消息）。
        // 保留最近 [KEEP_RECENT_TOOL_RESULTS] 条工具结果完整；被收缩的每条截到 [COMPACTED_TOOL_RESULT_CHARS]。
        private const val TOOL_LOOP_CHAR_BUDGET = 240_000
        private const val KEEP_RECENT_TOOL_RESULTS = 6
        private const val COMPACTED_TOOL_RESULT_CHARS = 4_000


        // 工具循环内「就地压缩」参数：
        // - 触发比例：读取当前设置（compactUseDefault→0.8f，否则用户设定的 contextRatio），
        //   与顶部圆环 / 事后自动压缩同口径，用户设 80% 这里就是 80%。
        // - 压缩后保留最近一段消息不参与摘要（按 token 预算 (阈值/4) 限制在 [2000, 20000] token，
        //   保证模型仍掌握最新上下文；
        //   更早的部分折叠成一条 [对话摘要] 用户消息。
        // - 冷却：一次压缩后至少隔 [TOOL_LOOP_COMPACT_COOLDOWN_ROUNDS] 轮才允许再次压缩，
        //   避免「摘要后仍接近阈值」导致每轮都发起一次摘要请求（既慢又烧额度）。
        private const val TOOL_LOOP_COMPACT_DEFAULT_RATIO = 0.8f
        private const val TOOL_LOOP_COMPACT_COOLDOWN_ROUNDS = 3
        /**
         * 「就地压缩」后保留的最近消息 token 预算上限（绝对量，不随窗口放大）。
         *
         * 压缩后总上下文 = 固定开销（system 提示 + 内置规则 + 记忆 + 工具 schema，实测约 17K，
         * **压不掉**）+ 摘要（约 3K）+ 本预算。取 4K 使总量落在 ~24K，逼近「新会话 + 摘要」。
         * 下限 2000：再小会让尾部只剩一两条，模型对「刚才做到哪」全靠摘要，长工具链易丢步骤。
         *
         * ⚠ 必须与 MainViewModel.COMPACT_KEEP_TOKENS_MAX 保持一致（两处压缩同一口径）。
         */
        private const val TOOL_LOOP_COMPACT_KEEP_TOKENS = 4_000
        // 压缩请求自身可用的输入字符预算上限（防止「会话越满、压缩请求越长」的死结）；
        // 与 estimateMsgTokens 口径一致，取保守的 1 字符 ≈ 1 token。
        private const val TOOL_LOOP_COMPACT_INPUT_CHAR_BUDGET = 60_000
        /** 压缩摘要输出上限兜底（settings 未配置时），须与 AppSettings.compactionMaxTokens 默认一致。 */
        private const val COMPACT_SUMMARY_OUTPUT_TOKENS = 4_096
        /**
         * 归一 tool_call 的 arguments：空串/空白 → "{}"，并确保是合法 JSON 字符串。
         *
         * 背景（400 实锤）：流式响应中某个 tool_call 的 arguments 分片可能一个都不到达（模型只发了
         * id+name 就切到下一个 index），累积结果是空串 ""。空串回传到下一轮请求时，上游报
         * `Assistant tool call *.arguments must be valid JSON.` → HTTP 400 → 工具链中断。
         * 无参数的调用应以 "{}" 形式回传。
         */
        private fun normalizeToolArgs(raw: String?): String {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return "{}"
            // 非法/不完整 JSON（如只收到 "{" 就断流）：也归一为 "{}"，避免把脏数据回传触发 400。
            return try {
                com.google.gson.JsonParser.parseString(s)
                s
            } catch (_: Exception) {
                "{}"
            }
        }
        // SSE 流内 error 帧中「不可重试」的错误码。
        private val NON_RETRYABLE_STREAM_CODES = setOf(
            "cyber_policy", "invalid_request_error", "invalid_request",
            "context_window_exceeded", "quota_exceeded", "usage_not_included",
            "usage_limit_reached", "invalid_image_request"
        )
        /** 终止原因对应的可读中文说明（用于把「沉默」变成「有原因的中断」）。 */
        private fun describeStopReason(reason: String?): String = when (reason) {
            "refusal" -> "模型拒绝回答该请求"
            "model_context_window_exceeded" -> "上下文超出模型窗口，请压缩或新开会话"
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "content_filter" -> "内容被安全策略拦截"
            "budget_exceeded" -> "达到用量预算上限"
            "failed" -> "模型返回失败"
            else -> "模型未返回内容（原因：${reason ?: "未知"}）"
        }

        // 明显「话说到一半、本该继续却没继续」的结尾特征：模型只说了引导语就 stop，没发工具调用。
        // 例：「让我查看实际环境：」「下面我来执行：」「接下来检查一下」等。
        // 命中即触发一次续写轮，逼模型真正执行/收尾，避免「AI 回复又停了」。
        // 结尾引导词（无冒号时兜底匹配，如「…让我先修复这个」「…接下来我会继续」）。
        private val TRAILING_LEAD_WORDS = listOf(
            "让我", "我来", "接下来", "下面", "首先", "然后", "接着", "即将", "正在", "需要修复", "需要"
        )

        /**
         * 回复是否「话说一半就停」（疑似该继续却 stop 了）。
         *
         * 旧实现要求「以冒号结尾 **且** 冒号前含引导/动作词」——实测漏判：模型把完整计划放进
         * `<thinking>`，正文只剩「需要修复布局容器，让它接收外部状态：」，其中「修复/接收」不在
         * 词表里 → 被判为完整结尾直接返回，表现为「AI 回复又停了」。
         *
         * 修正：**整条消息以冒号结尾、后面什么都没有** 本身就是极强的「未说完」信号（完整回复不会
         * 停在一个空冒号上），故不再依赖词表；另对无冒号的引导词结尾做兜底。误判代价仅为多一轮
         * 续写请求，远小于「任务中断」。
         */
        private fun looksIncomplete(text: String): Boolean {
            val t = text.trimEnd()
            if (t.length < 2) return false
            if (t.endsWith("：") || t.endsWith(":")) return true
            val tail = t.takeLast(40)
            return TRAILING_LEAD_WORDS.any { tail.endsWith(it) }
        }
    }

    // 参考 rc10 RetryPolicy：指数退避 500ms * 2^n，上限 10s
    private fun exponentialDelayMillis(attempt: Int, capMillis: Long = RETRY_DELAY_CAP_MS): Long =
        (500L shl attempt).coerceAtMost(capMillis)

    /**
     * 本轮失败该允许的重试次数（在 catch 里按错误类型动态算）。
     *
     * 为什么不能像旧版那样在循环外一次算好：那时只能看体积，看不到错误类型，
     * 于是「大 body ⇒ 必是体积问题」被写死，连上游瞬时故障也被砍成只重试 1 次。
     */
    private fun retryBudgetFor(largeBody: Boolean, e: Exception): Int {
        if (!largeBody) return MAX_RETRIES
        val msg = e.message.orEmpty()
        val code = Regex("HTTP (\\d{3})").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        // 只有「体积类状态码 + 确认不是上游瞬时故障」才收紧；否则重发是有意义的。
        val suspectedSize = code != null && code in GATEWAY_SIZE_CODES &&
            !com.wy.openx.data.util.ContextOverflow.isTransientGatewayError(code, msg)
        return if (suspectedSize) LARGE_BODY_MAX_RETRIES else MAX_RETRIES
    }

    private fun isRetryable(e: Exception): Boolean {
        if (e is StreamApiException) return e.code !in NON_RETRYABLE_STREAM_CODES
        if (e is IOException) return true
        val msg = e.message ?: return false
        val code = Regex("HTTP (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
        return code == 429 || (code != null && code in 500..599)
    }

    /**
     * 构造一条 **App 自动追加的内部引导语**。
     *
     * 为什么仍用 user 角色：部分渠道对「user 之后又插一条 system」不响应甚至直接报错，
     * 而这类引导轮（空响应重试、汇总轮、续写）全靠模型给出回应，换角色风险大于收益。
     * 所以改用**正文打标**的方式告诉模型这不是用户说的话——否则模型会在思考里把它
     * 当成用户消息去解释（实测出现过「你消息末尾那段…与本次任务无关」这种困惑输出）。
     *
     * 另：文案里**不要写死具体工具名**（历史遗留的「股票行情 / stock 工具」就属于此类），
     * 这些引导轮在写代码、跑终端等所有场景都会走到，写死的工具名会污染上下文，
     * 还可能把模型引向一个跟当前任务无关的工具。工具清单本来就随请求发给模型了。
     */
    private fun guideMsg(text: String) =
        ChatMessageDto("user", JsonPrimitive("【系统自动提示，非用户消息】$text"))

    /**
     * 判定一次**工具执行失败**的输出是否像「被限流（429）」。特征表见 [RATE_LIMIT_MARKERS]。
     *
     * 只在失败结果上调用：成功的工具输出里出现这些字样多半是内容本身（搜到的网页、
     * 读到的日志、代码常量），拿它判定会误报。
     */
    private fun looksRateLimited(result: String): Boolean {
        val text = result.lowercase()
        return RATE_LIMIT_MARKERS.any { text.contains(it) }
    }

    // ===== 提供商端点/认证/UA/代理：统一构造请求=====
    /** 按提供商配置解析聊天端点：useFullUrl 直接用 baseUrl，否则按类型拼 API 路径；无 config 时回退旧行为。 */
    private fun resolveChatUrl(ctx: ProviderCallContext): String {
        val cfg = ctx.config
        return if (cfg != null) ProviderHttp.resolveChatUrl(cfg)
        else "${ctx.baseUrl.trimEnd('/')}/chat/completions"
    }

    /** 按提供商选 client（代理开关）；无 config 时用直连 client。 */
    private fun clientFor(ctx: ProviderCallContext): OkHttpClient =
        ctx.config?.let { ProviderHttp.clientFor(it) } ?: client

    /** 构造一次聊天请求：URL/认证头/UA 全部走提供商配置。 */
    private fun buildChatRequest(
        ctx: ProviderCallContext, body: RequestBody, enableCache: Boolean, apiKey: String
    ): Request {
        val builder = Request.Builder().url(resolveChatUrl(ctx))
        val type = ctx.config?.type
        if (type != null) ProviderHttp.applyAuth(builder, apiKey, type)
        else builder.addHeader("Authorization", "Bearer $apiKey")
        builder.addHeader("Content-Type", "application/json")
        if (enableCache) builder.addHeader("X-Cache-Prompt", "true")
        ProviderHttp.applyUserAgent(builder, ctx.userAgent)
        return builder.post(body).build()
    }

    /**
     * 带多 Key 轮换的重试：每次尝试用 [block] 传入当前 Key 重建请求。
     * 命中可归因于 Key 的失败（401/402/403/429/余额）时上报轮换器；达到阈值即切到下一个 Key 重试。
     * 失败切换后把「已切换到第 N/M 个 Key」写进日志/重连提示。
     */
    private suspend fun <T> retryWithKeyRotation(
        ctx: ProviderCallContext,
        onKeySwitch: ((KeySwitchResult) -> Unit)? = null,
        block: suspend (String) -> T
    ): T {
        var key = ctx.apiKey
        var lastError: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                val result = block(key)
                onReconnectChange?.invoke(0, 0, ctx.conversationId)
                ctx.config?.takeIf { it.multiKeyEnabled }?.let { keyRotator.reportSuccess(it.id, key) }
                return result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentCallOf(ctx.conversationId)?.isCanceled() == true) throw e
                // Key 可归因失败：上报轮换器，达阈值则换 Key 继续
                if (e.isApiKeyFailure()) {
                    ctx.config?.takeIf { it.multiKeyEnabled && it.effectiveApiKeys.size > 1 }?.let { cfg ->
                        keyRotator.reportFailure(cfg.id, ctx.sessionId, key)?.let { sw ->
                            Log.w(TAG, "API Key 失败，切换 Key: 第 ${sw.newIndex}/${sw.total} 个")
                            onKeySwitch?.invoke(sw)
                            key = sw.newKey
                        }
                    }
                }
                if (!isRetryable(e) || attempt >= MAX_RETRIES) {
                    onReconnectChange?.invoke(0, 0, ctx.conversationId)
                    throw e
                }
                lastError = e
                onReconnectChange?.invoke(attempt + 1, MAX_RETRIES, ctx.conversationId)
                delay(exponentialDelayMillis(attempt))
            }
        }
        onReconnectChange?.invoke(0, 0, ctx.conversationId)
        throw lastError ?: IllegalStateException("重试失败")
    }

    /**
     * 首字节看门狗：超过 [FIRST_BYTE_TIMEOUT_MS] 未收到任何内容块则关闭流，逼出可重试的 IOException。
     */
    private suspend fun launchFirstByteWatchdog(
        close: () -> Unit,
        isFirstByteReceived: () -> Boolean
    ): Job = CoroutineScope(currentCoroutineContext()).launch {
        delay(FIRST_BYTE_TIMEOUT_MS)
        if (!isFirstByteReceived()) runCatching { close() }
    }

    /**
     * 检查 SSE 流内 error 帧：网关错误帧不再被当成空 delta 丢掉。
     * 命中 [error] 字段时抛 [StreamApiException] 携带错误码，让上层按可重试性处理。
     */
    private fun throwIfStreamError(obj: JsonObject) {
        val errEl = obj.get("error")?.takeIf { !it.isJsonNull } ?: return
        val code: String?; val msg: String
        if (errEl.isJsonObject) {
            val eo = errEl.asJsonObject
            code = eo.get("code")?.takeIf { !it.isJsonNull }?.asString
                ?: eo.get("type")?.takeIf { !it.isJsonNull }?.asString
            msg = eo.get("message")?.takeIf { !it.isJsonNull }?.asString ?: (code ?: "stream error")
        } else {
            code = null; msg = errEl.asString
        }
        throw StreamApiException(code, msg)
    }

    /**
     * 读取 SSE 流：以 `readLine()` 逐行读，`readLine()` 返回 null 且从未收到
     * `[DONE]` 即视为**流被异常截断**并抛 IOException（让重试/失败链路接管），
     * 避免把半截回复当正常结果返回（「AI 说一句就停」）。收到 `[DONE]` 才正常结束。
     * [onDataLine] 负责解析单行 JSON（含 error 帧检查）；单行解析异常由其内部吞掉。
     */
    private inline fun readSse(reader: java.io.BufferedReader, onDataLine: (String) -> Unit) {
        while (true) {
            val line = reader.readLine()
                ?: throw IOException("SSE 流被中断：未收到 [DONE] 结束标记（疑似网络断开）")
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") return
            onDataLine(data)
        }
    }

    /** SSE 解析累积器：一次流式尝试的正文/工具调用/用量/finish_reason。 */
    private class SseAcc {
        val fullText = StringBuilder()
        /** 纯思考内容（不含 <thinking> 包裹），用于 DeepSeek 等模型的 reasoning_content 回传。 */
        val reasoningText = StringBuilder()
        /** 仅累计 content 增量（不含注入的 <thinking> 标签），用于 XML 工具调用抑制与展示。 */
        val rawContent = StringBuilder()
        /** rawContent 中已向 UI 输出的字符数。 */
        var contentEmitted = 0
        /** XML 标记扫描已推进到的位置，避免每个 delta 都对累积正文做全文扫描。 */
        var markerScanPos = 0
        /** 一旦检测到 XML 工具调用标记，停止后续正文输出（避免把调用文本刷进聊天）。 */
        var xmlSuppressed = false
        val toolAcc = LinkedHashMap<Int, com.wy.openx.tools.ToolCallDelta>()
        var hasToolCalls = false
        var finishReason: String? = null
        /** 上下文占用：max(total_tokens, prompt+completion)，逐条 usage 取大者。 */
        var contextTokens: Int = 0
        var cacheHitTokens = 0
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var gotFirstByte = false
        var thinkingStarted = false
        var thinkingClosed = false
        /**
         * content 流中模型自带的 `<thinking>/<think>` 块剥离器。
         * 模型 会把思考同时写进 reasoning_content 和 content 的 <thinking> 块
         * （且 content 里还可能重复多份），导致 UI 显示多份重复思考；这里在 reasoning_content
         * 已承载思考的前提下，把 content 里的 <thinking> 块剥掉，只保留真正正文。
         */
        val contentThinkingFilter = ContentThinkingFilter()
        /** 原始 SSE 行累积（供 AILogger 落盘完整响应流，便于离线诊断）。 */
        val rawSse = StringBuilder()
        /** 已向 UI 输出过任何增量（用于「重试仅在未输出前」判定，避免重复文本）。 */
        var emittedAny = false
        /** 流式总字符预算（防止长思考链导致 OOM）。 */
        val streamBudget = com.wy.openx.data.remote.StreamBudget()

        /**
         * 把累积的文本与工具参数里的占位符换回真值。
         *
         * 只在整段流结束后调（此时文本已定稿），用 [OutboundMasker.restoreFully] 保证
         * 不被「半截占位符」的处理吞掉末尾字符。
         * `rawSse` 不还原：那是诊断日志，留着占位符反而不把原文写进日志。
         */
        fun restoreFrom(masker: com.wy.openx.privacy.OutboundMasker) {
            fun StringBuilder.rewrite() {
                val fixed = masker.restoreFully(toString())
                setLength(0)
                append(fixed)
            }
            fullText.rewrite()
            reasoningText.rewrite()
            rawContent.rewrite()
            for ((idx, delta) in toolAcc) {
                val fn = delta.function ?: continue
                val args = fn.arguments ?: continue
                toolAcc[idx] = delta.copy(function = fn.copy(arguments = masker.restoreFully(args)))
            }
        }
    }

    /**
     * 流式剥离 content 里模型自带的 `<thinking>/<think>` 块。
     *
     * 背景：agnes-2.5-flash 等模型/网关会把思考内容同时放进 `reasoning_content` 字段和
     * content 的 `<thinking>` 标签里（甚至 content 里重复多份）。reasoning_content 通道已正确
     * 承载思考并手动包了 `<thinking>` 展示；若 content 里的 `<thinking>` 再原样进 fullText，
     * UI 的 `extractThinkingContent` 会提取出多份相同思考 → 「每个思考重复 3 次」。
     *
     * 因此：当 reasoning_content 非空（思考已由该通道承载）时，把 content 里出现的
     * `<thinking>...</thinking>` / `<think>...</think>` 整体剥掉，只保留块外的正文。
     * 标记可能跨 SSE 分片（如 `<thinking` 与 `>` 分两次到达），故用暂扣缓冲做状态机识别。
     */
    private class ContentThinkingFilter {
        private val pending = StringBuilder()
        private var inThinking = false
        /** 剥掉的思考文本（当前仅用于日志/诊断，不回填展示——reasoning_content 已负责展示）。 */
        val strippedThinking = StringBuilder()

        private companion object {
            val OPENS = listOf("<thinking>", "<think>")
            val CLOSES = listOf("</thinking>", "</think>")
        }

        /** 喂入一个 content 分片，返回剥离 thinking 块后的正文（可能为空）。 */
        fun feed(chunk: String): String {
            pending.append(chunk)
            val out = StringBuilder()
            while (pending.isNotEmpty()) {
                if (!inThinking) {
                    val hit = earliest(pending, OPENS)
                    if (hit != null) {
                        out.append(pending, 0, hit.first)
                        pending.delete(0, hit.first + hit.second)
                        inThinking = true
                    } else {
                        val keep = longestSuffixPrefix(pending, OPENS)
                        val cut = pending.length - keep
                        if (cut > 0) { out.append(pending, 0, cut); pending.delete(0, cut) }
                        break
                    }
                } else {
                    val hit = earliest(pending, CLOSES)
                    if (hit != null) {
                        strippedThinking.append(pending, 0, hit.first)
                        pending.delete(0, hit.first + hit.second)
                        inThinking = false
                    } else {
                        val keep = longestSuffixPrefix(pending, CLOSES)
                        val cut = pending.length - keep
                        if (cut > 0) { strippedThinking.append(pending, 0, cut); pending.delete(0, cut) }
                        break
                    }
                }
            }
            return out.toString()
        }

        /** 在 [buf] 中找任一标记的最早完整出现位置，返回 (index, len) 或 null。 */
        private fun earliest(buf: StringBuilder, marks: List<String>): Pair<Int, Int>? {
            var bestIdx = -1
            var bestLen = 0
            for (m in marks) {
                val i = buf.indexOf(m)
                if (i >= 0 && (bestIdx < 0 || i < bestIdx)) { bestIdx = i; bestLen = m.length }
            }
            return if (bestIdx >= 0) bestIdx to bestLen else null
        }

        /** [s] 结尾与某标记前缀的最长匹配长度（用于暂扣可能跨分片的标记前缀）。 */
        private fun longestSuffixPrefix(s: CharSequence, marks: List<String>): Int {
            var best = 0
            for (m in marks) {
                for (len in 1 until m.length) {
                    if (s.length >= len && s.endsWith(m.substring(0, len))) best = maxOf(best, len)
                }
            }
            return best.coerceAtMost(s.length)
        }
    }

    /** 抑制 XML 工具调用时保留的尾部字符数（最长标记长度减一，避免把可能的分片标记前缀漏出去）。 */
    private val XML_MARKER_HOLDBACK = 11

    /** 解析单条 SSE data 行并累积到 [acc]，同时通过 [emit] 输出增量（内部包 Main 线程）。 */
    /**
     * 从 usage 对象里取「前缀缓存命中」的 token 数。
     *
     * 兼容三种主流命名（同一件事，正常只会出现一种）：
     * - DeepSeek 原生：`prompt_cache_hit_tokens`
     * - Anthropic 风格：`cache_read_input_tokens`
     * - OpenAI 风格：`prompt_tokens_details.cached_tokens`
     *
     * 抽成函数是因为流式与非流式兜底两条路径都手写解析 usage（不走 [Usage] DTO），
     * 两处各写一份容易漂移——之前正是两处都漏了 Anthropic 那个字段，
     * 导致这类渠道的命中量恒为 0。
     *
     * 取第一个非 0 值而不相加：避免网关同时返回多个字段时命中量被算成两倍。
     */
    private fun cacheHitTokensOf(u: JsonObject): Int {
        fun intOr0(key: String): Int =
            runCatching { u.get(key)?.takeIf { it !is JsonNull }?.asInt ?: 0 }.getOrDefault(0)
        intOr0("prompt_cache_hit_tokens").takeIf { it > 0 }?.let { return it }
        intOr0("cache_read_input_tokens").takeIf { it > 0 }?.let { return it }
        return runCatching {
            u.get("prompt_tokens_details")?.takeIf { it is JsonObject }?.asJsonObject
                ?.get("cached_tokens")?.takeIf { it !is JsonNull }?.asInt ?: 0
        }.getOrDefault(0)
    }

    private suspend fun handleSseObject(
        obj: JsonObject, acc: SseAcc, emit: ((String) -> Unit)?
    ) {
        throwIfStreamError(obj)
        obj.get("usage")?.takeIf { it is JsonObject }?.asJsonObject?.let { u ->
            val hits = cacheHitTokensOf(u)
            if (hits > 0) acc.cacheHitTokens = hits
            val tt = u.get("total_tokens")?.takeIf { it !is JsonNull }?.asInt ?: 0
            var pt = u.get("prompt_tokens")?.takeIf { it !is JsonNull }?.asInt ?: 0
            val ctNow = u.get("completion_tokens")?.takeIf { it !is JsonNull }?.asInt ?: 0
            if (pt <= 0) pt = (tt - ctNow).coerceAtLeast(0)
            if (pt > 0) acc.promptTokens = pt
            if (ctNow > 0) acc.completionTokens = ctNow
            // 上下文占用逐条 usage 累积取大
            val ctxNow = com.wy.openx.data.remote.Usage(
                promptTokens = pt, completionTokens = ctNow, totalTokens = tt
            ).reportedContextTokens()
            if (ctxNow > acc.contextTokens) acc.contextTokens = ctxNow
        }
        val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: return
        choice.get("finish_reason")?.takeIf { it !is JsonNull }?.asString?.let { acc.finishReason = it }
        val delta = choice.getAsJsonObject("delta") ?: return

        val reasoningEl = delta.get("reasoning") ?: delta.get("reasoning_content")
        val reasoning = reasoningEl?.let { if (it is JsonNull) null else it.asString }
        if (!reasoning.isNullOrEmpty()) {
            acc.streamBudget.add(reasoning)
            acc.gotFirstByte = true
            acc.reasoningText.append(reasoning)
            if (!acc.thinkingStarted) {
                acc.thinkingStarted = true
                acc.fullText.append("<thinking>")
                acc.emittedAny = true
                emit?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { it("<thinking>") } }
            }
            acc.fullText.append(reasoning)
            acc.emittedAny = true
            emit?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { it(reasoning) } }
        }
        val contentEl = delta.get("content")
        val rawChunk = if (contentEl == null || contentEl is JsonNull) null else contentEl.asString
        if (!rawChunk.isNullOrEmpty()) {
            acc.streamBudget.add(rawChunk)
            acc.gotFirstByte = true
            // 当 reasoning_content 已承载思考时，剥掉 content 里模型自带的 <thinking> 块，
            // 避免「reasoning_content 1 份 + content 里 N 份」重复显示（见 ContentThinkingFilter）。
            val content = if (acc.reasoningText.isNotEmpty()) acc.contentThinkingFilter.feed(rawChunk) else rawChunk
            // 剥完后若本轮全是思考、没有正文，则不触发 thinking 闭合、也不 append/emit，
            // 但仍需继续走到下面的 tool_calls 处理（不能提前 return）。
            if (content.isNotEmpty()) {
                if (acc.thinkingStarted && !acc.thinkingClosed) {
                    acc.thinkingClosed = true
                    acc.fullText.append("</thinking>")
                    emit?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { it("</thinking>") } }
                }
                acc.fullText.append(content)
                acc.rawContent.append(content)
                acc.emittedAny = true
                // XML 工具调用抑制：模型把工具调用写成正文文本（<tool_calls><invoke ...>）时，
                // 不能把这些标记刷进聊天页；一旦检测到标记即停止后续正文输出。
                // 检测前保留 XML_MARKER_HOLDBACK 个字符的「暂扣」，避免把分片到一半的标记前缀漏出去。
                if (!acc.xmlSuppressed) {
                    // 只扫「上次扫过之后」的新增窗口，而不是每次都把累积正文 toString() 再全文找标记：
                    // 原写法每个 delta 都做一次全量拷贝 + 全文扫描，正文越长越慢（O(n²)），
                    // 长回复时足以拖垮流式渲染。StringBuilder.substring(start) 不拷贝前段。
                    val scanned = acc.markerScanPos
                    // 标记最长不超过 32，取一段重叠尾巴，避免分片边界把标记切成两半而漏检。
                    val scanFrom = (scanned - 32).coerceAtLeast(0)
                    val window = acc.rawContent.substring(scanFrom)
                    val foundInWindow = com.wy.openx.tools.XmlToolCallParser.firstMarkerIndex(window)
                    if (foundInWindow >= 0) {
                        val markerIdx = scanFrom + foundInWindow
                        if (markerIdx > acc.contentEmitted) {
                            val safe = acc.rawContent.substring(acc.contentEmitted, markerIdx)
                            if (safe.isNotEmpty()) emit?.let { e -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { e(safe) } }
                        }
                        acc.contentEmitted = markerIdx
                        acc.xmlSuppressed = true
                    } else {
                        acc.markerScanPos = acc.rawContent.length
                        val safeEnd = (acc.rawContent.length - XML_MARKER_HOLDBACK).coerceAtLeast(acc.contentEmitted)
                        if (safeEnd > acc.contentEmitted) {
                            val safe = acc.rawContent.substring(acc.contentEmitted, safeEnd)
                            emit?.let { e -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { e(safe) } }
                            acc.contentEmitted = safeEnd
                        }
                    }
                }
            }
        }
        delta.getAsJsonArray("tool_calls")?.let { tcArr ->
            acc.hasToolCalls = true
            for (e in tcArr) {
                val o = e.asJsonObject; val idx = o.get("index")?.asDouble?.toInt() ?: 0
                val ex = acc.toolAcc[idx] ?: com.wy.openx.tools.ToolCallDelta(index = idx)
                // DeepSeek 等网关：非首 chunk 的增量里 id/name 可能以占位空串 "" 出现，
                // 必须以非空判断保护首 chunk 收到的有效值，否则会被覆盖成空名 → 工具无法路由。
                val fn = o.getAsJsonObject("function")
                val newId = o.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() } ?: ex.id
                val newName = (fn?.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() } ?: ex.function?.name)?.removePrefix("functions.")
                val argsDelta = fn?.get("arguments")?.takeIf { !it.isJsonNull }?.asString
                if (!argsDelta.isNullOrEmpty()) acc.streamBudget.add(argsDelta)
                val newArgs = (ex.function?.arguments ?: "") + (argsDelta ?: "")
                acc.toolAcc[idx] = com.wy.openx.tools.ToolCallDelta(
                    index = idx, id = newId,
                    function = com.wy.openx.tools.ToolCallDeltaFunction(name = newName, arguments = newArgs))
            }
        }
    }

    /** 单次 SSE 尝试：建请求 → 执行 → 首字节看门狗 → 逐行读取解析。失败抛异常由外层重试。 */
    private suspend fun sseOnce(
        ctx: ProviderCallContext, body: RequestBody, bodyPreview: String, enableCache: Boolean,
        emit: ((String) -> Unit)?, key: String
    ): SseAcc {
        val request = buildChatRequest(ctx, body, enableCache, key)
        // AI 完整请求日志：记录 URL 与请求体预览（不含 Key），返回序号用于配对响应/错误。
        // 传预览而不是完整 body：长会话的 body 可达数 MB，整个拼出来只为写日志不值当。
        val seq = com.wy.openx.data.log.AILogger.logRequest(
            ctx.sessionId, ctx.config?.name ?: ctx.providerId, ctx.model, "POST", request.url.toString(), bodyPreview
        )
        val call = clientFor(ctx).newCall(request)
        setCurrentCall(ctx.conversationId, call)
        val resp = try {
            call.execute()
        } catch (e: Exception) {
            com.wy.openx.data.log.AILogger.logError(ctx.sessionId, ctx.config?.name ?: ctx.providerId, e, seq)
            throw e
        }
        if (!resp.isSuccessful) {
            val err = resp.use { it.body?.string() }
            val msg = "HTTP ${resp.code}" + (if (!err.isNullOrBlank()) ": ${err.take(2000)}" else "")
            com.wy.openx.data.log.AILogger.logResponse(ctx.sessionId, ctx.config?.name ?: ctx.providerId, "HTTP ${resp.code}\n$err", seq)
            throw IllegalStateException(msg)
        }
        resp.use { r ->
            val rb = r.body ?: throw IllegalStateException("流式响应为空")
            val acc = SseAcc()
            val reader = rb.charStream().buffered()
            // 首字节看门狗：超时关闭流 → readLine 抛 IOException → 触发重试（仅在未输出内容时重试）。
            val watchdog = launchFirstByteWatchdog({ runCatching { rb.close() } }) { acc.gotFirstByte }
            try {
                readSse(reader) { data ->
                    if (acc.rawSse.length < 2_000_000) { acc.rawSse.append(data).append('\n') }
                    val obj = runCatching { gson.fromJson(data, JsonObject::class.java) }.getOrNull() ?: return@readSse
                    try {
                        handleSseObject(obj, acc, emit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: StreamApiException) {
                        throw e
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        // 单行解析异常跳过，不影响已累积文本
                    }
                }
            } catch (e: Exception) {
                com.wy.openx.data.log.AILogger.logError(ctx.sessionId, ctx.config?.name ?: ctx.providerId, e, seq)
                throw e
            } finally {
                watchdog.cancel()
                com.wy.openx.data.log.AILogger.logResponseStream(ctx.sessionId, ctx.config?.name ?: ctx.providerId, acc.rawSse.toString(), seq)
                if (acc.thinkingStarted && !acc.thinkingClosed) {
                    acc.thinkingClosed = true
                    acc.fullText.append("</thinking>")
                }
                // 冲刷暂扣的正文尾部：非 XML 路径下，最后 XML_MARKER_HOLDBACK 个字符尚未输出，
                // 流结束时补发；XML 路径下这些是调用文本的一部分，已被抑制、不补发。
                if (!acc.xmlSuppressed) {
                    val raw = acc.rawContent.toString()
                    if (raw.length > acc.contentEmitted) {
                        val tail = raw.substring(acc.contentEmitted)
                        emit?.let { e -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { e(tail) } }
                        acc.contentEmitted = raw.length
                    }
                }
            }
            return acc
        }
    }

    /**
     * 整个流式请求可重试：建连/首字节/读流中途的瞬时故障
     * 都能重试。默认仅在「尚未向 UI 输出任何增量前」重试，避免已显示文本重复；但若注册了
     * [onStreamReset]（主链路已注册），则**正文流到一半**断网也重试——重试前先经该通道清空本次
     * 尝试已展示的正文，再从头重发，做到既不留半截、也不叠字。同时做多 Key 轮换。
     */
    private suspend fun sseCallWithRetry(
        ctx: ProviderCallContext, payload: Any, enableCache: Boolean,
        emit: ((String) -> Unit)?, onKeySwitch: ((KeySwitchResult) -> Unit)? = null
    ): SseAcc {
        var key = ctx.apiKey
        var attempt = 0
        // ④ 请求体偏大的处理（策略：次数不砍、只压低退避封顶）：
        //   - 重试次数改为在 catch 里按错误类型动态算（retryBudgetFor）：只有「疑似体积类」才收紧到 1 次，
        //     上游瞬时故障仍可重发多次；
        //   - 退避封顶降到 5s，避免大 body 下用户被按在「重连中…」太久。
        // 早期版本在循环外一次性砍到 1 次，会把「等会儿就好」的临时故障也一并放弃。
        // 请求体按需序列化：不在内存里拼完整 JSON 字符串。长会话（几百条消息）的 body 可达数 MB，
        // gson.toJson 的 StringBuilder 增长峰值约最终长度的 2~3 倍，再叠加日志与字节数组拷贝，
        // 真机上就是崩在这里（OOM at AbstractStringBuilder.append）。
        val body = JsonRequestBody(gson, payload, JSON)
        val bodyBytes = body.contentLength().toInt()
        val bodyPreview = jsonPreview(gson, payload)
        val largeBody = bodyBytes >= LARGE_BODY_BYTES
        if (largeBody) Log.w(TAG, "请求体 ${bodyBytes / 1024}KB ≥ ${LARGE_BODY_BYTES / 1024}KB，退避封顶 ${LARGE_BODY_RETRY_DELAY_CAP_MS / 1000}s；疑似体积失败重试上限 $LARGE_BODY_MAX_RETRIES")
        while (true) {
            var emitted = false
            // 入站回填：把占位符换回真值。套在 emit 这一层而不是收流结束后，
            // 是为了让用户看到的增量就是原文（否则流式过程中会先闪一段 {{SECRET_xxx}}）。
            // 占位符可能被切在两个 chunk 之间，由 masker 内部的 pending 机制拼回来。
            val wrapped: ((String) -> Unit)? = emit?.let { inner ->
                { s -> emitted = true; inner(if (maskOutbound) outboundMasker.restore(s) else s) }
            }
            try {
                val acc = sseOnce(ctx, body, bodyPreview, enableCache, wrapped, key)
                if (maskOutbound) {
                    // 1) 增量流末尾被 restore() 抱住的尾巴补给 UI（否则最后几个字会丢）
                    val tail = outboundMasker.flush()
                    if (tail.isNotEmpty()) emit?.invoke(tail)
                    // 2) 累积器里的文本还原成真值：下游要拿它落库、拼历史、执行工具，
                    //    留着占位符会让重启后会话里显示 {{SECRET_xxx}}、工具拿到假参数。
                    //    （回灌模型时 maskedForOutbound 会重新掩成占位符，闭环正确。）
                    acc.restoreFrom(outboundMasker)
                }
                onReconnectChange?.invoke(0, 0, ctx.conversationId)
                ctx.config?.takeIf { it.multiKeyEnabled }?.let { keyRotator.reportSuccess(it.id, key) }
                // 成功收尾后清掉句柄：currentCalls 按会话分槽，而cancelCurrentCall 只 remove
                // 被取消的那一个，正常完成的Call 没人 remove —— 不清就会每轮请求堆一个，长期使用必累积。
                currentCalls.remove(ctx.conversationId)
                return acc
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentCallOf(ctx.conversationId)?.isCanceled() == true) throw e
                if (e.isApiKeyFailure()) {
                    ctx.config?.takeIf { it.multiKeyEnabled && it.effectiveApiKeys.size > 1 }?.let { cfg ->
                        keyRotator.reportFailure(cfg.id, ctx.sessionId, key)?.let { sw ->
                            Log.w(TAG, "API Key 失败，切换 Key: 第 ${sw.newIndex}/${sw.total} 个")
                            onKeySwitch?.invoke(sw)
                            key = sw.newKey
                        }
                    }
                }
                // 已输出内容后：仅当注册了「重试前清空已展示文本」通道（onStreamReset）时才重试，
                // 否则维持旧行为——不重试，避免重发把正文叠成两遍。
                val canRetry = isRetryable(e) && attempt < retryBudgetFor(largeBody, e) &&
                    (!emitted || onStreamReset != null)
                if (!canRetry) {
                    onReconnectChange?.invoke(0, 0, ctx.conversationId)
                    val code = Regex("HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    if (largeBody && code != null && code in GATEWAY_SIZE_CODES) {
                        val upstreamDiag = e.message?.take(400).orEmpty()
                        // 排除「上游瞬时故障」误判（与 ContextOverflow.isTransientGatewayError 同一套信号）：
                        // 账号池耗尽 / upstream_error 这类响应带着**结构化上游错误体**，说明请求已经越过
                        // 网关入口的体积检查，不可能是体积超限。若仅凭「大 body + 5xx」就判体积超限，
                        // 会把一次「等会儿重试就好」的临时故障升级成不可逆的强制压缩（真机踩过）。
                        if (!com.wy.openx.data.util.ContextOverflow.isTransientGatewayError(code, upstreamDiag)) {
                            throw RequestBodyTooLargeException(
                                message = "请求体过大被上游拒绝（HTTP $code，${bodyBytes / 1024}KB）：" +
                                    upstreamDiag.take(200),
                                bodyBytes = bodyBytes, statusCode = code, cause = e
                            )
                        }
                        Log.w(TAG, "大请求体 + HTTP $code，但响应是上游瞬时故障，按普通错误处理（不触发压缩）: ${upstreamDiag.take(120)}")
                    }
                    throw e
                }
                // 本次尝试已流出正文（emitted）：先让 UI 回退到本轮展示前的状态，再从头重发，
                // 避免重试后正文与已展示部分叠字。未流出（emitted=false）时无需清空。
                if (emitted) onStreamReset?.invoke(ctx.conversationId)
                attempt++
                onReconnectChange?.invoke(attempt, retryBudgetFor(largeBody, e), ctx.conversationId)
                delay(exponentialDelayMillis(attempt, if (largeBody) LARGE_BODY_RETRY_DELAY_CAP_MS else RETRY_DELAY_CAP_MS))
            }
        }
    }

    fun cancelCurrentCall(conversationId: Long = 0L) {
        currentCalls.remove(conversationId)?.cancel()
    }

    private fun buildUserContent(text: String, imageBase64: List<String>?): JsonElement {
        if (imageBase64.isNullOrEmpty()) return JsonPrimitive(text)
        val arr = JsonArray()
        arr.add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
        imageBase64.forEach { data ->
            arr.add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply { addProperty("url", data) })
            })
        }
        return arr
    }

    suspend fun sendMessage(
        settings: AppSettings,
        userMessage: String,
        imageBase64: List<String>? = null,
        tools: List<ToolDef>? = null,
        toolRegistry: ToolRegistry? = null,
        onToolCall: (suspend (String, String, String) -> Unit)? = null,
        onToolResult: (suspend (String, String, String) -> Unit)? = null,
        onTokens: ((Int) -> Unit)? = null,
        onContextTokens: ((Int) -> Unit)? = null,
        onCompletionTokens: ((Int) -> Unit)? = null,
        onStreamDelta: ((String) -> Unit)? = null,
        onToolCallRequest: (suspend (String, String) -> Boolean)? = null,
        onCacheHit: ((Boolean, Int) -> Unit)? = null,
        historyMessages: List<ChatMessageDto> = emptyList(),
        /** 强制使用指定提供商（如「默认模型」里的压缩/起标题专用模型，selectedModel 已就位）。null 沿用当前激活提供商。 */
        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null,
        /**
         * 本轮 user 消息 id，决定文件快照桶归属。0 = 沿用全局指针（旧语义）。
         * 多会话并行时主链路必须传自己那一轮。
         */
        checkpointTurnId: Long = 0L,
        /** 本轮所属会话 id，用于 HTTP 句柄分槽（停止只停本会话）。 */
        conversationId: Long = 0L,
        /** 【P4-1】本会话生效的上下文窗口，供工具循环内就地压缩定阈值。0=沿用全局值。 */
        contextWindowTokens: Int = 0
    ): Result<String> {
        return try {
            Result.success(runChat(settings, userMessage, imageBase64, tools, toolRegistry, onToolCall, onToolResult, onTokens, onContextTokens, onCompletionTokens, onStreamDelta, onToolCallRequest, onCacheHit, historyMessages, forcedProvider, checkpointTurnId, conversationId, contextWindowTokens))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    /**
     * 解析单次调用的提供商上下文：优先「专用模型」forcedProvider（识图/压缩/起标题/子代理），
     * 否则当前激活提供商，再回退 settings 扁平字段。主链路与子代理共用这一套解析，
     * 保证子代理不会绕过自定义接口/多 Key 轮换/UA 等全部提供商级配置。
     */
    private fun resolveCallContext(
        settings: AppSettings,
        forcedProvider: com.wy.openx.data.model.AIProviderConfig?,
        /**
         * 本轮所属会话 id（0 = 未知）。
         *
         * 用来按会话取上下文窗口：子代理/识图/标题等没有 UI 上下文，只能靠调用方把
         * 会话 id 递进来（主聊天路径传 requestConversationId，子代理传 subCid）。
         * 传 0 就退回全局单槽兜底，与改动前一致。
         */
        conversationId: Long = 0L
    ): ProviderCallContext {
        val isCustom = settings.selectedApiPreset == "自定义" || settings.selectedApiPreset == "自定义接口"
        val baseUrl = if (isCustom && settings.customApiUrl.isNotBlank()) settings.customApiUrl else settings.baseUrl
        val model = if (isCustom && settings.customApiModel.isNotBlank()) settings.customApiModel else settings.model
        val apiKey = settings.apiKey

        // 提供商级配置（若已注册）：优先用它覆盖 baseUrl/apiKey/model/端点/缓存键/UA。
        // 未注册（旧数据 / 未迁移）时回退到 settings 里的扁平字段，行为与改造前一致。
        // 强制指定（专用模型：压缩/起标题）时以 forcedProvider 为准。
        val provider = forcedProvider ?: com.wy.openx.tools.AppContextHolder.currentProvider
        val resolvedBaseUrl = provider?.baseUrl?.takeIf { it.isNotBlank() } ?: baseUrl
        val resolvedModel = provider?.effectiveModel?.takeIf { it.isNotBlank() } ?: model
        val resolvedKey = provider?.firstUsableApiKey?.takeIf { it.isNotBlank() } ?: apiKey
        val resolvedUseResponseApi = provider?.useResponseApi ?: false
        val resolvedUseFullUrl = provider?.useFullUrl ?: false
        val resolvedOpenaiChatCacheKey = provider?.openaiChatCacheKey ?: false
        val resolvedUserAgent = provider?.userAgent ?: ""
        val sessionId = com.wy.openx.tools.AppContextHolder.currentConversationId?.toString()

        // 多 Key 轮换：命中提供商且启用多 Key 时按会话粘性取 Key。
        val effectiveKey = if (provider != null && provider.multiKeyEnabled && provider.effectiveApiKeys.size > 1) {
            keyRotator.activeKey(provider, sessionId) ?: resolvedKey
        } else {
            resolvedKey
        }

        return ProviderCallContext(
            providerId = provider?.id ?: "",
            config = provider,
            baseUrl = resolvedBaseUrl,
            apiKey = effectiveKey,
            model = resolvedModel,
            useResponseApi = resolvedUseResponseApi,
            useFullUrl = resolvedUseFullUrl,
            openaiChatCacheKey = resolvedOpenaiChatCacheKey,
            userAgent = resolvedUserAgent,
            sessionId = sessionId,
            reasoningEffort = settings.reasoningEffort,
            // 【子代理/专用模型窗口按会话】就地压缩阈值必须随请求走。
            //
            // 原来不传，走默认 0 → compactToolLoopContext 退回全局单槽
            // `AppContextHolder.contextWindowTokens`，并发时那是**别的会话**写的值：
            // 拿大了该压不压（可能直接超窗报错），拿小了提前压缩（白烧一次摘要请求）。
            // 主聊天路径由 VM 显式传（MainViewModel 2219 行），子代理/识图/标题等
            // 一律走这个兜底入口，所以在**构造 ctx 的地方**按会话 id 取一次。
            //
            // 仓库层算不出窗口（`contextWindowTokensFor` 在 VM 里），故用钩子注入，
            // 与既有的 subAgentProviderProvider 同一套路。钩子未注册（无会话场景，
            // 如测试直调）就保持 0 走原有兜底，行为不变。
            conversationId = conversationId,
            contextWindowTokens = AppContextHolder.contextWindowProvider
                ?.invoke(conversationId)?.takeIf { it > 0 } ?: 0
        )
    }

    private suspend fun runChat(
        settings: AppSettings,
        userMessage: String,
        imageBase64: List<String>?,
        tools: List<ToolDef>?,
        toolRegistry: ToolRegistry?,
        onToolCall: (suspend (String, String, String) -> Unit)?,
        onToolResult: (suspend (String, String, String) -> Unit)?,
        onTokens: ((Int) -> Unit)?,
        onContextTokens: ((Int) -> Unit)? = null,
        onCompletionTokens: ((Int) -> Unit)? = null,
        onStreamDelta: ((String) -> Unit)?,
        onToolCallRequest: (suspend (String, String) -> Boolean)? = null,
        onCacheHit: ((Boolean, Int) -> Unit)? = null,
        historyMessages: List<ChatMessageDto>,
        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null,
        checkpointTurnId: Long = 0L,
        conversationId: Long = 0L,
        contextWindowTokens: Int = 0
    ): String {
        com.wy.openx.tools.AppContextHolder.currentSettings = settings
        val baseSystemPrompt = if (settings.rules.isNotBlank())
            "${settings.systemPrompt}\n\n==== 项目规划说明 ====\n${settings.rules}" else settings.systemPrompt
        // 十一期：插件可向系统提示词追加内容。没插件注册这个钩子时直接短路，零开销。
        val fullSystemPrompt = com.wy.openx.plugin.PluginHookManager
            .systemPromptInject(baseSystemPrompt, conversationId)
            ?.let { "$baseSystemPrompt\n\n$it" }
            ?: baseSystemPrompt
        val enableCache = true
        val useReactFallback = !supportsNativeToolCall(settings.provider) && !tools.isNullOrEmpty() && toolRegistry != null
        val callCtx = resolveCallContext(settings, forcedProvider, conversationId)
            .let {
                var c = it
                if (checkpointTurnId != 0L) c = c.copy(checkpointTurnId = checkpointTurnId)
                if (conversationId != 0L) c = c.copy(conversationId = conversationId)
                // VM 显式传的值优先（主聊天路径已经是权威值）；为 0 时用 resolveCallContext
                // 从钩子按会话取的兜底值，避免退回全局单槽。
                if (contextWindowTokens > 0) c = c.copy(contextWindowTokens = contextWindowTokens)
                c
            }

        return if (useReactFallback) {
            reactChat(callCtx, fullSystemPrompt, userMessage, imageBase64,
                settings.temperature, settings.maxTokens.toIntOrNull(), tools, toolRegistry,
                onToolCall, onToolResult, onTokens, onContextTokens, onCompletionTokens, onStreamDelta, onToolCallRequest, onCacheHit, historyMessages, enableCache)
        } else if (settings.enableStream && onStreamDelta != null) {
            streamChat(callCtx, fullSystemPrompt, userMessage, imageBase64,
                settings.temperature, settings.maxTokens.toIntOrNull(), onStreamDelta, tools, toolRegistry, onToolCall, onToolResult, onTokens, onContextTokens, onCompletionTokens, onToolCallRequest, onCacheHit, historyMessages, enableCache)
        } else {
            syncChat(callCtx, fullSystemPrompt, userMessage, imageBase64,
                settings.temperature, settings.maxTokens.toIntOrNull(), tools, toolRegistry, onToolCall, onToolResult, onTokens, onContextTokens, onCompletionTokens, onToolCallRequest, onCacheHit, historyMessages, enableCache)
        }
    }

    /**
     * 子代理运行入口（实现 tools 侧 [com.wy.openx.tools.SubAgentRunner]）。
     *
     * 关键点：**直接复用主链路的 streamChat**，而不是像老 call_sub_agent 那样自己 new 一个
     * OkHttpClient 重写一遍。于是子代理白拿：SSE 流式、多 Key 轮换、提示词缓存、
     * thinking-only 兜底、每轮 ensureActive 可取消、工具参数截断修复。
     *
     * 与主链路的差异只有三处，都是刻意的：
     *  1. historyMessages 恒为空 —— 每个子任务独立上下文，天然不串味（老实现的 messages 是常驻字段）；
     *  2. 不流式回吐文本（onStreamDelta=null）—— 子代理中间产物只走 onToolProgress，不污染父级气泡；
     *  3. 带 maxToolRounds 与 toolPolicy —— 子代理有预算与角色权限，主链路没有。
     */
    suspend fun runSubagent(req: SubAgentRunRequest): SubAgentRunResult {
        val registry = AppContextHolder.toolRegistry
        val tools = req.tools.takeIf { it.isNotEmpty() && registry != null }
        val usedTools = ConcurrentLinkedQueue<String>()
        // 本次子代理运行的用量：子代理也能用「默认模型→子代理模型」的专用模型，
        // 那些模型的消耗必须进用量统计（否则统计里永远是零）。
        var tokIn = 0
        var tokOut = 0
        var tokCache = 0
        val fail = { status: String, msg: String ->
            SubAgentRunResult(
                subagentId = req.subagentId, profileId = req.profileId, taskIndex = req.taskIndex,
                status = status, finalContent = "", toolCallSummaries = usedTools.toList(), errorMessage = msg,
                inputTokens = tokIn, outputTokens = tokOut, cacheTokens = tokCache
            )
        }
        return try {
            val settings = AppContextHolder.currentSettings
                ?: return fail("failed", "应用设置未就绪，子代理无法发起请求")
            // 子代理专用模型（设置页可配）；未配置则沿用当前会话的聊天模型。
            // cid 从协程上下文取（子代理在发起会话的协程树内跑，P3-a 注入的元素会继承）。
            val subCid = kotlin.coroutines.coroutineContext[AppContextHolder.ConversationId]?.id ?: 0L
            val ctx = resolveCallContext(
                settings,
                AppContextHolder.subAgentProviderProvider?.invoke(subCid),
                // 把会话 id 递进去，窗口才按**本会话**取（子代理自己的模型窗口可能与父会话差几倍）
                conversationId = subCid
            )
            val text = withTimeoutOrNull(req.timeoutMs) {
                streamChat(
                    ctx = ctx,
                    systemPrompt = req.systemPrompt,
                    userMessage = req.instruction,
                    imageBase64 = null,
                    temperature = req.temperature ?: 0.3f,
                    maxTokens = req.maxTokens,
                    // 子代理正文不回吐到 UI（否则会污染父级气泡），仅进度走 onToolProgress
                    onStreamDelta = { },
                    tools = tools,
                    toolRegistry = registry,
                    onToolCall = { name, _, args ->
                        usedTools.add(name)
                        req.onProgress?.invoke(
                            "tool_started",
                            "调用 $name · ${SubAgentToolPolicy.prettyArgs(args).take(100)}",
                            name
                        )
                    },
                    onToolResult = { name, _, _ -> req.onProgress?.invoke("tool_done", "$name 已返回", name) },
                    onTokens = { tokIn += it },
                    // 权限确认不能对子任务失效：复用父级同一套审批（弹窗/白名单）。
                    // 未注册审批通道时，只读工具放行、写/执行类一律拒绝。
                    onToolCallRequest = { name, args ->
                        val approver = AppContextHolder.subAgentToolApprover
                        when {
                            approver != null -> approver(name, args)
                            // 父级档位本身就是「全自动」：approver 为 null 不是因为没通道，
                            // 而是因为不需要逐条问——此时子代理也直接放行。
                            AppContextHolder.subAgentToolAutoApprove -> true
                            else -> registry?.isWriteOrExecTool(name) != true
                        }
                    },
                    historyMessages = emptyList(),
                    enableCache = false,
                    onCompletionTokens = { tokOut += it },
                    onCacheHit = { hit, tokens -> if (hit && tokens > 0) tokCache += tokens },
                    onToolProgress = { name, line -> req.onProgress?.invoke("tool_progress", line, name) },
                    maxToolRounds = req.maxRounds,
                    toolPolicy = { name, args -> SubAgentToolPolicy.validate(req.profileId, name, args) }
                )
            }
            if (text == null) {
                req.onProgress?.invoke("failed", "超时（${req.timeoutMs / 1000}s）未完成", null)
                return fail("timeout", "子代理超时（${req.timeoutMs / 1000} 秒）未完成")
            }
            val visible = visibleText(text)
            if (visible.isBlank()) {
                req.onProgress?.invoke("failed", "未返回可见内容", null)
                return fail("failed", "子代理未返回可见内容（模型可能只输出了思考内容）")
            }
            SubAgentRunResult(
                subagentId = req.subagentId, profileId = req.profileId, taskIndex = req.taskIndex,
                status = "completed", finalContent = visible.trim(),
                toolCallSummaries = usedTools.toList(),
                inputTokens = tokIn, outputTokens = tokOut, cacheTokens = tokCache
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            fail("failed", e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun syncChat(
        ctx: ProviderCallContext, systemPrompt: String,
        userMessage: String, imageBase64: List<String>?, temperature: Float, maxTokens: Int?, tools: List<ToolDef>?,
        toolRegistry: ToolRegistry?, onToolCall: (suspend (String, String, String) -> Unit)?,
        onToolResult: (suspend (String, String, String) -> Unit)?,
        onTokens: ((Int) -> Unit)?,
        onContextTokens: ((Int) -> Unit)? = null,
        onCompletionTokens: ((Int) -> Unit)? = null,
        onToolCallRequest: (suspend (String, String) -> Boolean)? = null,
        onCacheHit: ((Boolean, Int) -> Unit)? = null,
        historyMessages: List<ChatMessageDto> = emptyList(),
        enableCache: Boolean = false
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val baseUrl = ctx.baseUrl; val apiKey = ctx.apiKey; val model = ctx.model
        val msgs = mutableListOf<ChatMessageDto>().apply {
            add(ChatMessageDto("system", JsonPrimitive(systemPrompt)))
            addAll(historyMessages)
            add(ChatMessageDto("user", buildUserContent(userMessage, imageBase64)))
        }
        val (resp, usage) = try {
            postChat(ctx, ChatRequest(model, msgs, temperature = temperature,
                maxTokens = maxTokens, tools = tools, toolChoice = if (tools != null) "auto" else null, reasoningEffort = currentReasoningEffort(ctx)), enableCache)
        } catch (e: Exception) {
            // HTTP 400 时尝试去掉 tools 降级重试
            if (tools != null && e.message?.contains("HTTP 400") == true) {
                Log.w(TAG, "syncChat 请求失败(400)，降级重试(无tools): ${e.message?.take(100)}")
                postChat(ctx, ChatRequest(model, msgs, temperature = temperature,
                    maxTokens = maxTokens, tools = null, toolChoice = null, reasoningEffort = currentReasoningEffort(ctx)), enableCache)
            } else {
                throw e
            }
        }
        val msg0 = resp?.choices?.firstOrNull()?.message
        var content = msg0?.content
        val reasoningMsg = msg0?.reasoning
        val toolCalls = msg0?.toolCalls
        if (!reasoningMsg.isNullOrBlank() && content != null) {
            content = "<thinking>$reasoningMsg</thinking>" + content
        }

        if (usage != null) {
            val inUsage = if (usage.promptTokens > 0) usage.promptTokens
                else (usage.totalTokens - usage.completionTokens).coerceAtLeast(0)
            if (inUsage > 0) onTokens?.invoke(inUsage)
            // 上下文占用 = max(total_tokens, prompt+completion)
            // resolveReportedContextTokens。仍**不加 cached_tokens**（它是 prompt 的子集，
            // 重复加会让窗口占用随缓存命中量抖动）。缓存只经 onCacheHit 进计费统计。
            val ctxTokens = usage.reportedContextTokens()
            if (ctxTokens > 0) onContextTokens?.invoke(ctxTokens)
            if (usage.effectiveCacheHitTokens > 0) onCacheHit?.invoke(true, usage.effectiveCacheHitTokens)
            if (usage.completionTokens > 0) onCompletionTokens?.invoke(usage.completionTokens)
        }

        if (!toolCalls.isNullOrEmpty() && toolRegistry != null) {
            // 保留该轮正文：带 tool_calls 的 assistant 消息不应丢弃 content，
            // 否则「先说一句再调工具」的正文缺失，历史与用户所见不一致。
            val safeCalls = toolCalls.map { it.copy(function = it.function.copy(arguments = normalizeToolArgs(it.function.arguments))) }
            msgs.add(ChatMessageDto(
                "assistant",
                if (!content.isNullOrBlank()) JsonPrimitive(content) else JsonNull.INSTANCE,
                toolCalls = safeCalls
            ))
            return@withContext executeAndFollowUp(ctx, msgs, temperature, maxTokens, tools, safeCalls, toolRegistry, onToolCall, onToolResult, onTokens, onContextTokens, onCompletionTokens, null, onToolCallRequest, onCacheHit, enableCache)
        }
        if (!content.isNullOrBlank()) return@withContext content
        Log.w(TAG, "syncChat 初始响应为空，尝试引导重试")
        val guideMsgs = mutableListOf<ChatMessageDto>().apply {
            add(ChatMessageDto("system", JsonPrimitive(systemPrompt)))
            addAll(historyMessages)
            add(ChatMessageDto("user", buildUserContent(userMessage, imageBase64)))
            add(guideMsg("请分析上述查询。如需实时数据（如最新信息、行情等），请调用合适的工具。"))
        }
        val guideResp = postChat(ctx, ChatRequest(model, guideMsgs, temperature = temperature, maxTokens = maxTokens, tools = tools, toolChoice = if (tools != null) "auto" else null, reasoningEffort = currentReasoningEffort(ctx)), enableCache)
        val guideContent = guideResp.first?.choices?.firstOrNull()?.message?.content
        val guideToolCalls = guideResp.first?.choices?.firstOrNull()?.message?.toolCalls
        if (!guideToolCalls.isNullOrEmpty() && toolRegistry != null) {
            val safeGuideCalls = guideToolCalls.map { it.copy(function = it.function.copy(arguments = normalizeToolArgs(it.function.arguments))) }
            guideMsgs.add(ChatMessageDto("assistant", JsonNull.INSTANCE, toolCalls = safeGuideCalls))
            return@withContext executeAndFollowUp(ctx, guideMsgs, temperature, maxTokens, tools, safeGuideCalls, toolRegistry, onToolCall, onToolResult, onTokens, onContextTokens, onCompletionTokens, null, onToolCallRequest, onCacheHit, enableCache)
        }
        if (!guideContent.isNullOrBlank()) return@withContext guideContent
        val retryResp = postChat(ctx, ChatRequest(model, msgs, temperature = temperature, maxTokens = maxTokens, tools = null, reasoningEffort = currentReasoningEffort(ctx)), enableCache)
        val retryContent = retryResp.first?.choices?.firstOrNull()?.message?.content
        if (!retryContent.isNullOrBlank()) retryContent else "[AI 处理完成，未返回可显示内容]"
    }

    private suspend fun streamChat(
        ctx: ProviderCallContext, systemPrompt: String,
        userMessage: String, imageBase64: List<String>?, temperature: Float, maxTokens: Int?,
        onStreamDelta: ((String) -> Unit), tools: List<ToolDef>?,
        toolRegistry: ToolRegistry?, onToolCall: (suspend (String, String, String) -> Unit)?,
        onToolResult: (suspend (String, String, String) -> Unit)?,
        onTokens: ((Int) -> Unit)?,
        onContextTokens: ((Int) -> Unit)? = null,
        onCompletionTokens: ((Int) -> Unit)? = null,
        onToolCallRequest: (suspend (String, String) -> Boolean)? = null,
        onCacheHit: ((Boolean, Int) -> Unit)? = null,
        historyMessages: List<ChatMessageDto> = emptyList(),
        enableCache: Boolean = false,
        // ↓ 子代理专用（主链路全部传 null，行为与改造前完全一致）
        /** 子代理进度回传：(toolName, 文本)。主链路为 null。 */
        onToolProgress: (suspend (String, String) -> Unit)? = null,
        /** 子代理工具轮次上限；0 = 不限（沿用主链路「跑到模型不再调工具」）。 */
        maxToolRounds: Int = 0,
        /** 子代理角色权限：返回非 null 表示拒绝该调用（工具名, 参数 JSON）。 */
        toolPolicy: ((String, String) -> String?)? = null
    ): String {
        val model = ctx.model
        val msgs = buildList {
            add(ChatMessageDto("system", JsonPrimitive(systemPrompt)))
            addAll(historyMessages)
            add(ChatMessageDto("user", buildUserContent(userMessage, imageBase64)))
        }
        // 出站脱敏：正文/思考/工具参数里的凭据换成占位符，供应商拿不到原文
        val outMsgs = if (maskOutbound) msgs.maskedForOutbound(outboundMasker) else msgs
        val payload = ChatRequest(model, outMsgs, temperature = temperature, stream = true,
            streamOptions = com.wy.openx.data.remote.StreamOptions(),
            maxTokens = maxTokens, tools = tools, toolChoice = if (tools != null) "auto" else null, reasoningEffort = currentReasoningEffort(ctx),
            promptCacheKey = if (ctx.openaiChatCacheKey) ctx.sessionId else null)
        Log.d(TAG, "streamChat payload: ${jsonPreview(gson, payload, 500)}")

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val acc = try {
                sseCallWithRetry(ctx, payload, enableCache, onStreamDelta)
            } catch (e: Exception) {
                // HTTP 400 时尝试去掉 tools 降级重试（兼容不支持工具调用的模型）
                if (tools != null && e.message?.contains("HTTP 400") == true) {
                    Log.w(TAG, "streamChat 请求失败(400)，降级重试(无tools): ${e.message?.take(100)}")
                    val fallbackPayload = ChatRequest(model, outMsgs, temperature = temperature, stream = true,
                        streamOptions = com.wy.openx.data.remote.StreamOptions(),
                        maxTokens = maxTokens, tools = null, toolChoice = null, reasoningEffort = currentReasoningEffort(ctx),
                        promptCacheKey = if (ctx.openaiChatCacheKey) ctx.sessionId else null)
                    try {
                        sseCallWithRetry(ctx, fallbackPayload, enableCache, onStreamDelta)
                    } catch (fe: Exception) {
                        // 流式(无tools)仍失败 → 完全放弃流式，改用非流式 syncChat 兜底（兼容不支持流式/工具组合的模型）
                        Log.w(TAG, "streamChat 降级(无tools,流式)也失败，改用非流式兜底: ${fe.message?.take(100)}")
                        return@withContext syncChat(ctx, systemPrompt, userMessage, imageBase64,
                            temperature, maxTokens, tools, toolRegistry, onToolCall, onToolResult,
                            onTokens, onContextTokens, onCompletionTokens, onToolCallRequest, onCacheHit, historyMessages, enableCache)
                    }
                } else {
                    throw e
                }
            }
            val fullText = acc.fullText
            val accToolCalls = acc.toolAcc
            val hasToolCalls = acc.hasToolCalls
            val finishReason = acc.finishReason

            if (acc.cacheHitTokens > 0) onCacheHit?.invoke(true, acc.cacheHitTokens)
            acc.promptTokens?.let { onTokens?.invoke(it) }
            if (acc.contextTokens > 0) onContextTokens?.invoke(acc.contextTokens)
            acc.completionTokens?.let { onCompletionTokens?.invoke(it) }
            Log.d(TAG, "streamChat 本轮结束: finishReason=$finishReason, hasToolCalls=$hasToolCalls, textLen=${fullText.length}")

        // 工具调用来源二选一：原生 tool_calls 字段优先；若模型把调用写成 XML 正文
        // （<tool_calls><invoke name="terminal"><parameter ...>），则从正文文本解析——
        // 此前这种文本只当普通回复显示、不触发执行，表现为「AI 突然停在半途」。
        val nativeToolCalls = if (hasToolCalls && accToolCalls.isNotEmpty()) {
            accToolCalls.entries.map { (_, d) ->
                com.wy.openx.tools.ToolCall(
                    id = d.id ?: "call_${d.index}",
                    // 空名兜底为 ""（而非 "unknown"）：下面的 filter 用 isNotBlank 过滤空名调用，
                    // 若兜底成 "unknown" 会**绕过过滤**被当真实工具执行，返回「未找到工具 unknown」噪音。
                    function = com.wy.openx.tools.ToolCallFunction(
                        name = d.function?.name.orEmpty(),
                        arguments = normalizeToolArgs(d.function?.arguments)
                    )
                )
            }.filter { it.function.name.isNotBlank() }
        } else emptyList()
        val xmlCalls = if (nativeToolCalls.isEmpty()) com.wy.openx.tools.XmlToolCallParser.parse(acc.rawContent.toString()) else emptyList()
        val asstToolCalls = if (nativeToolCalls.isNotEmpty()) nativeToolCalls
            else xmlCalls.mapIndexed { i, c ->
                com.wy.openx.tools.ToolCall(
                    id = "call_xml_${System.nanoTime()}_$i",
                    function = com.wy.openx.tools.ToolCallFunction(name = c.name, arguments = c.arguments)
                )
            }
        if (xmlCalls.isNotEmpty()) {
            Log.w(TAG, "streamChat 从正文文本解析到 XML 工具调用 ${xmlCalls.size} 个: ${xmlCalls.map { it.name }}")
        }
        if (asstToolCalls.isNotEmpty() && toolRegistry != null) {
                // 发给模型的历史 assistant 正文剥离 XML 调用文本（避免污染上下文/重复触发）
                val assistantBody = if (xmlCalls.isNotEmpty()) com.wy.openx.tools.XmlToolCallParser.strip(acc.rawContent.toString()) else null

                val msgList = mutableListOf<ChatMessageDto>().apply {
                    add(ChatMessageDto("system", JsonPrimitive(systemPrompt)))
                    addAll(historyMessages)
                    add(ChatMessageDto("user", buildUserContent(userMessage, imageBase64)))
                    // 保留该轮正文：
                    // 此前传 JsonNull 丢弃「先说一句判断再调工具」的正文，导致历史与用户所见不一致、模型重复劳动。
                    // XML 路径用剥离调用文本后的正文（assistantBody），避免把 <invoke> 文本回灌上下文。
                    val body = assistantBody ?: fullText.toString()
                    add(ChatMessageDto(
                        "assistant",
                        if (body.isNotBlank()) JsonPrimitive(body) else JsonNull.INSTANCE,
                        toolCalls = asstToolCalls
                    ))
                }
                // 展示兜底：XML 路径下完整正文（fullText）含调用文本，最终返回值改用剥离后的正文。
                val prevText = assistantBody ?: fullText.toString()
                var followUpContent = executeAndFollowUp(ctx, msgList, temperature, maxTokens, tools, asstToolCalls, toolRegistry, onToolCall, onToolResult, onTokens, onContextTokens, onCompletionTokens, onStreamDelta, onToolCallRequest, onCacheHit, enableCache, onToolProgress, maxToolRounds, toolPolicy)
                if (!followUpContent.isNullOrBlank() && followUpContent != "[AI 处理完成]" &&
                    visibleText(followUpContent).isNotBlank()
                ) return@withContext followUpContent
                // follow-up 未能产出正文（空/[AI 处理完成]/工具循环耗尽）：不能直接把「开场白」当最终回复返回，
                // 否则表现为 AI 说「…让我诊断问题：」后就停住。必须先强制一次「汇总轮」，让模型基于已执行的
                // 工具结果收尾；只有汇总轮也彻底失败，才退而返回已有正文。
                Log.w(TAG, "streamChat follow-up 返回空或 [AI 处理完成]，先强制汇总轮，不直接回退开场白")
                val retryMsgList = msgList.toMutableList()
                retryMsgList.add(guideMsg("请根据上述工具执行结果，用中文给出完整的分析和回复。不要调用工具。"))
                val (retryContent, _) = streamFollowUpRound(ctx, retryMsgList, temperature, maxTokens, null, enableCache, onStreamDelta, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                if (visibleText(retryContent).isNotBlank()) { Log.d(TAG, "streamChat 汇总轮1成功: ${retryContent!!.take(50)}"); return@withContext retryContent }
                Log.w(TAG, "streamChat 汇总轮1失败，尝试汇总轮2")
                val (retry2Content, _) = streamFollowUpRound(ctx, retryMsgList, temperature, maxTokens, null, enableCache, null, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                if (visibleText(retry2Content).isNotBlank()) { Log.d(TAG, "streamChat 汇总轮2成功"); return@withContext retry2Content!! }
                // 汇总轮全部失败：退而返回已有正文（至少保留开场白，好过空白）
                if (prevText.isNotBlank()) return@withContext prevText
                Log.e(TAG, "streamChat 汇总轮全部失败，返回 [AI 处理完成]")
                return@withContext "[AI 处理完成]"
            }

            if (visibleText(fullText.toString()).isEmpty() && toolRegistry != null && finishReason !in ABORT_STOP_REASONS) {
                Log.w(TAG, "streamChat 初始响应为空（无内容无工具调用），开始引导重试")
                val guideMsgs = mutableListOf<ChatMessageDto>().apply {
                    add(ChatMessageDto("system", JsonPrimitive(systemPrompt)))
                    addAll(historyMessages)
                    add(ChatMessageDto("user", buildUserContent(userMessage, imageBase64)))
                    add(guideMsg("请分析上述查询。如需实时数据（如最新信息、行情等），请调用合适的工具。"))
                }
                val (rcTxt, rcTcs) = streamFollowUpRound(ctx, guideMsgs, temperature, maxTokens, tools, enableCache, onStreamDelta, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                if (visibleText(rcTxt).isNotBlank()) return@withContext rcTxt!!
                if (!rcTcs.isNullOrEmpty()) {
                    guideMsgs.add(ChatMessageDto("assistant", JsonNull.INSTANCE, toolCalls = rcTcs))
                    val guideResult = executeAndFollowUp(ctx, guideMsgs, temperature, maxTokens, tools, rcTcs, toolRegistry, onToolCall, onToolResult, onTokens, onContextTokens, onCompletionTokens, onStreamDelta, onToolCallRequest, onCacheHit, enableCache)
                    if (visibleText(guideResult).isNotBlank() && guideResult != "[AI 处理完成]") return@withContext guideResult!!
                }
                val forceMsgs = mutableListOf<ChatMessageDto>().apply {
                    add(ChatMessageDto("system", JsonPrimitive(systemPrompt)))
                    addAll(historyMessages)
                    add(ChatMessageDto("user", buildUserContent(userMessage, imageBase64)))
                    add(guideMsg("请直接回答用户的问题，不要调用任何工具。如果不知道具体数据，请给出一般性分析和建议。"))
                }
                val (rc2, _) = streamFollowUpRound(ctx, forceMsgs, temperature, maxTokens, null, enableCache, null, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                if (visibleText(rc2).isNotBlank()) return@withContext rc2!!
                val (rc3, _) = streamFollowUpRound(ctx, forceMsgs, temperature, maxTokens, null, enableCache, onStreamDelta, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                if (visibleText(rc3).isNotBlank()) return@withContext rc3!!
            }
            // 拒答/安全拦截/上下文超限等「中止」类终止：不能静默退化成空回复，
            // 明确返回可读原因（UI 会作为正文气泡展示），且不进入引导/强制重试（重试无意义且浪费额度）。
            if (finishReason in ABORT_STOP_REASONS) {
                val notice = describeStopReason(finishReason)
                Log.w(TAG, "streamChat 模型中止(finishReason=$finishReason)：$notice")
                if (fullText.isNotBlank()) return@withContext fullText.toString() + "\n\n（$notice）"
                return@withContext "（$notice）"
            }
            // 输出被 max_tokens 截断（finish_reason=length/max_tokens）时，半截回复不能就此停住——
            // 追加「请从截断处继续」再续写一轮，拼接后返回。仅文本路径需要（工具路径另走 follow-up）。
            val finalText = fullText.toString()
            // 「话说一半就停」兜底：模型只输出引导语（以冒号结尾）就 stop、且没发工具调用时，
            // 追加一次「请继续」轮**保留 tools**，逼它真正执行/给出结论，避免「AI 回复又停了」。
            if (finalText.isNotBlank() && toolRegistry != null &&
                looksIncomplete(com.wy.openx.data.util.ChatUtils.removeThinkingContent(finalText))) {
                Log.w(TAG, "streamChat 回复疑似未完成（引导语结尾且无工具调用），追加继续轮")
                val contMsgs = msgs.toMutableList().apply {
                    add(ChatMessageDto("assistant", JsonPrimitive(finalText)))
                    add(guideMsg("请继续（如需执行命令/读写文件请直接调用工具，不要只说「让我…」就停）。"))
                }
                val (contContent, contTools) = try {
                    streamFollowUpRound(ctx, contMsgs, temperature, maxTokens, tools, enableCache, onStreamDelta, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "streamChat 继续轮失败，返回已有正文: ${e.message}")
                    null to null
                }
                if (!contTools.isNullOrEmpty() && toolRegistry != null) {
                    // 继续轮发起了工具调用 → 进入正常工具循环
                    contMsgs.add(ChatMessageDto("assistant", contContent?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE, toolCalls = contTools))
                    val follow = executeAndFollowUp(ctx, contMsgs, temperature, maxTokens, tools, contTools, toolRegistry, onToolCall, onToolResult, onTokens, onContextTokens, onCompletionTokens, onStreamDelta, onToolCallRequest, onCacheHit, enableCache)
                    val merged = buildString {
                        append(finalText); append('\n')
                        if (!contContent.isNullOrBlank()) append(contContent).append('\n')
                        if (!follow.isNullOrBlank() && follow != "[AI 处理完成]") append(follow)
                    }.trim()
                    if (merged.isNotBlank()) return@withContext merged
                }
                if (visibleText(contContent).isNotBlank()) return@withContext finalText + "\n" + contContent
            }
            if (finalText.isNotBlank() && finishReason in TRUNCATION_STOP_REASONS) {
                Log.w(TAG, "streamChat 输出被截断(finishReason=$finishReason)，追加续写轮")
                val contMsgs = msgs.toMutableList().apply {
                    add(ChatMessageDto("assistant", JsonPrimitive(finalText)))
                    add(guideMsg("你的回复因长度限制被截断了，请从截断处继续，不要重复已有内容。"))
                }
                val contPair = try {
                    streamFollowUpRound(ctx, contMsgs, temperature, maxTokens, null, enableCache, onStreamDelta, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "streamChat 续写轮失败，返回已截断正文: ${e.message}")
                    null
                }
                val contContent = contPair?.first
                if (!contContent.isNullOrBlank()) return@withContext finalText + contContent
            }
            // 展示兜底：若正文里含 XML 工具调用文本（但未被执行，如工具列表为空/解析失败），
            // 剥离调用标记再返回，避免把 <invoke> 原文刷进聊天页。
            val displayText = if (com.wy.openx.tools.XmlToolCallParser.hasMarker(finalText))
                com.wy.openx.tools.XmlToolCallParser.strip(finalText) else finalText
            if (visibleText(displayText).isBlank()) "[AI 处理完成]" else displayText
        }
    }

    /**
     * 当前会话的思考强度（对应 `reasoning_effort`）。"default" 表示模型默认，返回 null 不发送该字段。
     * 沿用本仓库既有的 [AppContextHolder.currentSettings] 隐式注入惯例（同 parallelToolCalls）。
     */
    private fun currentReasoningEffort(ctx: ProviderCallContext): String? =
        ctx.reasoningEffort.takeIf { it.isNotBlank() && it != "default" }

    private suspend fun postChat(ctx: ProviderCallContext, req: ChatRequest, enableCache: Boolean = false): Pair<com.wy.openx.data.remote.ChatResponse?, com.wy.openx.data.remote.Usage?> {
        // 出站脱敏：非流式是所有旁路请求（标题、压缩摘要、记忆整理等）的共同出口
        val safeReq = if (maskOutbound) req.copy(messages = req.messages.maskedForOutbound(outboundMasker)) else req
        val payload = safeReq.copy(stream = null)
        val payloadPreview = jsonPreview(gson, payload, 500)
        // 在循环外建一次：JsonRequestBody 会把算好的字节数缓存住，
        // 放在循环内会因多 Key 轮换而反复整趟遍历计数。
        val requestBody = JsonRequestBody(gson, payload, JSON)
        return retryWithKeyRotation(ctx) { key ->
            Log.d(TAG, "postChat body: $payloadPreview")
            val request = buildChatRequest(ctx, requestBody, enableCache, key)
            val seq = com.wy.openx.data.log.AILogger.logRequest(
                ctx.sessionId, ctx.config?.name ?: ctx.providerId, ctx.model, "POST(non-stream)", request.url.toString(), jsonPreview(gson, payload)
            )
            val call = clientFor(ctx).newCall(request)
            // 注册句柄，否则「停止」对非流式请求完全无效：execute() 阻塞在 IO 线程，
            // cancelCurrentCall 拿不到句柄，只能干等超时（最长 300s）。
            setCurrentCall(ctx.conversationId, call)
            val response = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { call.execute() }
            } catch (e: Exception) {
                // execute() 本身失败：没有响应可读，直接注销句柄。
                currentCalls.remove(ctx.conversationId, call)
                com.wy.openx.data.log.AILogger.logError(ctx.sessionId, ctx.config?.name ?: ctx.providerId, e, seq)
                throw e
            }
            // 句柄必须等响应体读完再注销：body.string() 是**第二个**阻塞点（大响应/慢网络下
            // 可能持续数秒），若在 execute() 的 finally 里提前 remove，这段窗口内按「停止」依旧无效。
            val body = try {
                response.use { it.body?.string() }
            } finally {
                currentCalls.remove(ctx.conversationId, call)
            }
            if (!response.isSuccessful) {
                com.wy.openx.data.log.AILogger.logResponse(ctx.sessionId, ctx.config?.name ?: ctx.providerId, "HTTP ${response.code}\n$body", seq)
                throw IllegalStateException("HTTP ${response.code}: ${body?.take(200) ?: response.message}")
            }
            if (body == null) throw IllegalStateException("响应为空")
            com.wy.openx.data.log.AILogger.logResponse(ctx.sessionId, ctx.config?.name ?: ctx.providerId, body, seq)
            try {
                val chatResp = gson.fromJson(body, com.wy.openx.data.remote.ChatResponse::class.java)
                // 入站回填：非流式一次性拿完整响应，在这里把占位符换回真值
                Pair(if (maskOutbound) chatResp?.restoredFromOutbound(outboundMasker) else chatResp, chatResp?.usage)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                throw IllegalStateException("JSON 解析失败: ${e.message?.take(100)}\n响应原文: ${body?.take(500)}")
            }
        }
    }

    /**
     * 工具循环无法由模型收尾时的兜底文案。**绝不回吐原始工具输出**——否则会在聊天页
     * 刷出上百 KB 的终端/文件内容，用户会误以为「AI 中断、把工具内容显示在聊天里」。
     * 只给一条简短中文状态，说明已执行轮数并引导用户重试/分步。
     */
    private fun toolLoopFallbackSummary(toolCount: Int, reason: String): String =
        if (toolCount <= 0) "[AI 处理完成]"
        else "（已连续执行 $toolCount 次工具调用，但模型未能生成最终回复：$reason。为避免刷屏，此处不展开原始结果；请重试，或让我把任务拆小分步完成。）"

    /**
     * 把「即将发往模型」的消息列表投影成**实际发送形态**，供工具循环内的每一轮复用，
     * 使其与回合起点（`MainViewModel.buildTurnHistory` + `boundToolOutputsForRequest`）的
     * 口径**完全一致**。
     *
     * ## 为什么必须统一（这是一个真实故障的根因）
     * openx 有**两条组包路径**：
     *   1. 回合起点：VM 从库重建历史（`buildTurnHistory` 剥 `<thinking>`）再做工具输出预算投影；
     *   2. 工具循环内：仓库层往 `msgs` 里**增量追加** assistant/工具结果，直接发出去。
     * 旧实现只有前者做投影，于是**同一段历史**在「回合内」与「下一回合起点」发出去的内容不同：
     * 回合内带 `<thinking>`、大工具结果未落盘；回合起点两样都被投影掉。顶部上下文数字
     * （=`prompt_tokens` 真实值）因此在「暂停→继续」时跳变（实测 45.9K → 38.8K，消息一条没少）。
     *
     * **投影是发请求前的纯函数，所有发送路径共用同一份**。
     *
     * ## 两条不变量
     * 1. **只投影发出去的副本**：绝不改 `msgs` 本身、不落库。工具循环后续轮次、压缩边界、
     *    UI 展示与备份导出仍基于完整内容。
     * 2. **落盘失败不阻断发送**：返回未投影的 `stripped`（宁可多发一次可能超限的请求，
     *    也不能把内容换成一句指向不存在文件的提示）。
     *
     * 投影内容与 [MainViewModel.buildTurnHistory]/[MainViewModel.boundToolOutputsForRequest] 一致：
     * - 剥 assistant 正文里的 `<thinking>/<search>`（残留标签会让部分模型 HTTP 400）；
     * - 超预算的工具结果落盘、正文换成「已保存到 <路径>」提示（防止请求体撞网关上限）。
     */
    private fun projectForSend(
        msgs: List<ChatMessageDto>,
        ctx: ProviderCallContext
    ): List<ChatMessageDto> {
        if (msgs.isEmpty()) return msgs
        // ① 剥思考标签：与 buildTurnHistory 同款。纯思考且无 tool_calls 的 assistant 消息直接丢弃
        //   （与 buildTurnHistory 的 `takeIf { it.isNotBlank() }` 对齐），避免发出「空 content 空 tool_calls」。
        //   ⚠ 但「需要回传思考」的模型（DeepSeek 思考模式等）**不剥**：其 API 要求 assistant(tool_calls)
        //   携带 reasoning，剥掉会 400（见 [ChatUtils.modelKeepsThinking]）。
        val keepThinking = com.wy.openx.data.util.ChatUtils.modelKeepsThinking(ctx.model)
        val stripped = ArrayList<ChatMessageDto>(msgs.size)
        for (m in msgs) {
            if (m.role != "assistant") { stripped.add(m); continue }
            // 思考可能以两种形态存在，且**都要**按同一判据处理，否则循环内与回合起点仍不一致：
            //   ① content 里内联的 `<thinking>` 块；
            //   ② 独立字段 `reasoning_content`（流式 reasoning 走这条，见 streamChat 的 acc.reasoningText）。
            // 实测遗漏 ② 导致 4K 落差：循环内带着 reasoning_content、回合起点从不设置它。
            if (keepThinking) { stripped.add(m); continue }
            val c = m.content
            var nm: ChatMessageDto? = if (m.reasoningContent != null) m.copy(reasoningContent = null) else m
            if (c is JsonPrimitive && c.isString) {
                val clean = com.wy.openx.data.util.ChatUtils.removeThinkingContent(c.asString)
                val hasTools = !m.toolCalls.isNullOrEmpty()
                val base = nm
                nm = when {
                    clean == c.asString -> base
                    clean.isBlank() && !hasTools -> null   // 丢弃：纯思考、无工具
                    clean.isBlank() -> base?.copy(content = JsonNull.INSTANCE)
                    else -> base?.copy(content = JsonPrimitive(clean))
                }
            }
            if (nm != null) stripped.add(nm)
        }
        // ② 超预算工具结果落盘：与 VM.boundToolOutputsForRequest 同款、同一预算。
        val context = com.wy.openx.tools.AppContextHolder.context ?: return stripped
        if (ctx.conversationId <= 0) return stripped
        return try {
            val offloader = com.wy.openx.data.util.ToolOutputOffload(context)
            val projected = com.wy.openx.data.util.ToolOutputBudget.project(
                history = stripped,
                toolNameOf = { dto -> dto.toolCallId ?: "tool" },
                offload = { toolName, content -> offloader.offload(ctx.conversationId, toolName, content) }
            )
            offloader.trim(ctx.conversationId)
            projected
        } catch (e: Exception) {
            Log.w(TAG, "工具循环内投影落盘失败，本轮不投影: ${e.message}")
            stripped
        }
    }

    /**
     * 工具循环的上下文预算守卫。
     *
     * openx 的工具循环运行在仓库层、与 UI 状态解耦，
     * 这里做**结构安全**的降级——只收缩「较早的工具结果消息」正文（保留消息本身与其 toolCallId），
     * 绝不删除消息，因此不会破坏 assistant(tool_calls)/tool(result) 的配对（删消息会导致 400）。
     * 最近 [KEEP_RECENT_TOOL_RESULTS] 条工具结果保持完整，保证模型仍能看到最新探测结果。
     */
    private fun enforceContextBudget(msgs: MutableList<ChatMessageDto>, charBudget: Int) {
        fun contentChars(c: JsonElement?): Int = when (c) {
            null, is JsonNull -> 0
            is JsonPrimitive -> if (c.isString) c.asString.length else 8
            // 非字符串的 JsonElement（多模态 parts 数组）需完整序列化才能量准，这是它 O(n) 的根源，
            // 所以只在全量求和时做一次，循环内只处理字符串型工具结果。
            else -> c.toString().length
        }
        var remaining = msgs.sumOf { contentChars(it.content) }
        if (remaining <= charBudget) return
        val toolIdx = msgs.indices.filter { msgs[it].role == "tool" }
        val shrinkable = toolIdx.dropLast(KEEP_RECENT_TOOL_RESULTS)
        for (i in shrinkable) {
            if (remaining <= charBudget) break
            val m = msgs[i]
            val c = m.content
            if (c is JsonPrimitive && c.isString && c.asString.length > COMPACTED_TOOL_RESULT_CHARS) {
                val kept = c.asString.take(COMPACTED_TOOL_RESULT_CHARS)
                val compacted = "$kept\n\n[较早的工具输出已省略以节省上下文]"
                // 增量扣减：旧写法在循环里每轮都全量重算 totalChars()，工具结果一多就是 O(n²)。
                remaining -= c.asString.length - compacted.length
                msgs[i] = m.copy(content = JsonPrimitive(compacted))
            }
        }
    }

    /**
     * 工具循环「就地压缩」。
     *
     * 背景：openx 的自动压缩只在「用户消息发送前」与「整轮 finally」各跑一次；而单个任务的长工具
     * 循环（几十轮工具调用、可长达数分钟）中途**完全没有压缩检查**，上下文会一路涨到窗口上限甚至
     * 溢出，直到用户手动停止才在 finally 里补压——表现为「跑到 90%+ 都没压缩」。
     *
     * 这里在工具循环每轮 LLM 调用之前，用**上一轮真实 prompt_tokens** 判定阈值，超阈值就把
     * 循环 msgs 的较早部分折叠成一条 [对话摘要] 用户消息，真正缩小本轮发出的上下文。
     *
     * 结构安全：只从**头部**整体折叠、保留最近的连续尾部，且尾部起点避开孤立的 tool 结果消息
     * （否则 tool result 失去配对的 assistant(tool_calls) → API 400）。
     *
     * @param lastInputTokens 上一轮 API 返回的真实 prompt_tokens；≤0 时退化为本地估算。
     * @return [CompactionOutcome]：COMPACTED 已压缩 / FAILED 尝试但失败（触发失败闩锁）/ SKIPPED 未达条件。
     */
    private suspend fun compactToolLoopContext(
        ctx: ProviderCallContext,
        msgs: MutableList<ChatMessageDto>,
        lastInputTokens: Int,
        enableCache: Boolean
    ): CompactionOutcome {
        // 【P4-1】优先用本轮请求自带的窗口（会话级模型下各会话不同），
        // 没有才退回全局槽（子代理/旧调用方仍可用）。
        val window = ctx.contextWindowTokens.takeIf { it > 0 }
            ?: com.wy.openx.tools.AppContextHolder.contextWindowTokens
        if (window <= 0) return CompactionOutcome.SKIPPED
        val settings = com.wy.openx.tools.AppContextHolder.currentSettings
        val ratio = if (settings?.compactUseDefault != false) TOOL_LOOP_COMPACT_DEFAULT_RATIO
            else settings.contextRatio
        if (ratio <= 0f) return CompactionOutcome.SKIPPED
        val threshold = (window * ratio).toInt()
        val current = if (lastInputTokens > 0) lastInputTokens else estimateMsgTokens(msgs)
        // 【诊断】判定的输入与结论全部留痕。
        //
        // 此前 `current < threshold` 直接静默 SKIPPED，日志里连一行都没有——实测出现
        // 「UI 显示 83K、阈值 80K，工具循环却连续 6 轮不压缩」时无从定位（是 window 解析错、
        // ratio 取错、还是 current 用了过期值）。这类「该压不压」必须有据可查。
        //
        // ⚠ 只打**数值与开关**，绝不打 `ctx` 整体：它内含 AIProviderConfig，直接 toString
        // 会把 **apiKey 明文**写进日志文件（曾误打过一次）。
        com.wy.openx.data.log.AppLogStore.info(
            "压缩诊断",
            "工具循环判定: window=$window(本轮=${ctx.contextWindowTokens} 全局=${com.wy.openx.tools.AppContextHolder.contextWindowTokens}) " +
                "settings=${if (settings == null) "null" else "compactUseDefault=${settings.compactUseDefault} contextRatio=${settings.contextRatio}"} " +
                "→ ratio=$ratio threshold=$threshold current=$current(lastInputTokens=$lastInputTokens) " +
                "→ ${if (current < threshold) "跳过(未达阈值)" else "触发"}"
        )
        if (current < threshold) return CompactionOutcome.SKIPPED
        // 保留**全部** leading system 消息，而不是只有 msgs[0]。
        //
        // openx 的请求里 system 不止一条：`streamChat` 先 add systemPrompt，再 addAll(historyMessages)，
        // 而 historyMessages（VM 的 buildTurnHistory）开头还带一串 system——模式、工具纪律、
        // 记忆规则、**内置 AI 规则**、长期/项目记忆、技能清单、工作区说明、todo 提示。
        // 旧实现只保留 msgs[0]，把下标 1..cut 的 system 全折进摘要 → 内置规则从此从请求里消失
        // （只剩摘要里的转述），是实打实的行为退化。
        val leadingSystemCount = msgs.takeWhile { it.role == "system" }.size
        val leadingSystems = msgs.take(leadingSystemCount)

        // 折叠边界：从尾部向前按 token 预算保留最近消息，更早的 head 折叠成一条摘要。
        // 预算见 [TOOL_LOOP_COMPACT_KEEP_TOKENS]；不再用 threshold 比例（窗口越大残留越大）。
        val preserveBudget = TOOL_LOOP_COMPACT_KEEP_TOKENS.coerceAtLeast(2_000)
        var total = 0
        var cut = msgs.size
        for (i in msgs.indices.reversed()) {
            if (i < leadingSystemCount) break          // leading system 不参与「保留尾部」计数
            val next = estimateMsgTokens(listOf(msgs[i]))
            if (total + next > preserveBudget && cut < msgs.size) break
            total += next
            cut = i
        }
        if (cut <= leadingSystemCount) return CompactionOutcome.SKIPPED
        // 避免尾部以孤立 tool 结果开场（tool result 必须有配对的 assistant(tool_calls)，否则 400）。
        while (cut > leadingSystemCount && msgs[cut].role == "tool") cut--
        if (cut <= leadingSystemCount) return CompactionOutcome.SKIPPED

        // head 只包含「非 system 且将被折叠」的那段（leading system 原样保留，绝不写进摘要）。
        val head = msgs.subList(leadingSystemCount, cut).toList()
        if (head.isEmpty()) return CompactionOutcome.SKIPPED
        val fullText = head.joinToString("\n") { formatMsgForSummary(it) }
        val (text, truncated) = truncateText(fullText, TOOL_LOOP_COMPACT_INPUT_CHAR_BUDGET)
        val truncNote = if (truncated) "\n\n（注意：待压缩历史过长，最早部分已省略，请基于可见内容尽力总结。）" else ""
        val prompt = "以下是AI助手与用户的对话历史。请将以上对话压缩为一段简洁的摘要，保留：\n" +
            "1. 用户的核心需求与约束\n2. 已确认的关键决策\n3. 已修改/创建的文件及其作用\n" +
            "4. 已执行命令的重要结果与结论\n5. 尚未完成的事项\n\n$text$truncNote"

        Log.w(TAG, "工具循环就地压缩: current=$current ≥ threshold=$threshold (window=$window, ratio=$ratio)，折叠 $cut 条 → 摘要")
        // 【诊断】把压缩前后 token 构成落进 AppLogStore（原先只有 logcat，日志文件里看不到，
        // 导致「压缩后仍 119K」无从定位）。正式定调后可删。
        com.wy.openx.data.log.AppLogStore.info(
            "压缩诊断",
            "工具循环: 触发 current=$current ≥ threshold=$threshold (window=$window ratio=$ratio) " +
                "preserveBudget=$preserveBudget | 消息共${msgs.size}条 leadingSystem=$leadingSystemCount 条 " +
                "保留尾${msgs.size - cut}条(≈${estimateMsgTokens(msgs.subList(cut, msgs.size))}tok) " +
                "折叠头${head.size}条(≈${estimateMsgTokens(head)}tok)"
        )
        // 通知 UI 进入「正在压缩」态（对齐 opencode context-compaction marker）：摘要生成是网络请求，
        // 先切主线程让聊天页渲染「正在压缩」标记，再阻塞生成摘要。
        notifyToolLoopCompaction("compressing")
        val summary = summarizeForCompaction(
            ctx, prompt,
            // 摘要输出上限独立于聊天「最大输出」，用 settings.compactionMaxTokens（默认 4096）。
            settings?.compactionMaxTokens?.toIntOrNull()?.takeIf { it > 0 } ?: COMPACT_SUMMARY_OUTPUT_TOKENS,
            enableCache
        ) ?: run {
            Log.w(TAG, "工具循环就地压缩：摘要生成失败，本轮先收缩较早工具结果兜底")
            enforceContextBudget(msgs, TOOL_LOOP_CHAR_BUDGET)
            notifyToolLoopCompaction("failed")
            return CompactionOutcome.FAILED
        }
        val summaryMsg = ChatMessageDto("user", JsonPrimitive("[对话摘要] $summary"))
        val tail = msgs.subList(cut, msgs.size).toList()
        msgs.clear()
        // 原样保留**全部** leading system（模式/工具纪律/记忆/内置规则/工作区说明…），
        // 再接摘要与保留的尾部。少留任何一条都会丢行为准则。
        msgs.addAll(leadingSystems)
        msgs.add(summaryMsg)
        msgs.addAll(tail)
        Log.w(TAG, "工具循环就地压缩完成：${head.size} 条 → 1 条摘要，剩余 ${msgs.size} 条")
        com.wy.openx.data.log.AppLogStore.info(
            "压缩诊断",
            "工具循环完成: 摘要${summary.length}字符(≈${estimateMsgTokens(listOf(summaryMsg))}tok) " +
                "system保留${leadingSystemCount}条 重建后消息${msgs.size}条(≈${estimateMsgTokens(msgs)}tok)"
        )
        // 【关键】把压缩结果登记为「待落库」。登记必须在 `notifyToolLoopCompaction("completed")`
        // **之前**——因为 VM 收到 completed 后会**立即**消费它落库（压缩完立刻在
        // 消息流里显示压缩卡，而不是拖到回合末）。若登记晚于通知，VM 那一刻读到的 pending 还是
        // null，落库就落空了。
        //
        // 不做这一步，本次压缩只改了请求副本，历史一条没少 → 下一轮又从完整历史重建，
        // 上下文涨回压缩前量级、反复压缩。
        //
        // ⚠ 边界用 `srcMsgId`（来源 slot 消息 id）而**不是条数**：一条 slot 消息在请求里会
        // 展开成多条 DTO（工具消息拆 assistant(tool_calls) + tool(result)），故「折了多少条 DTO」
        // 与「该折多少条 slot 消息」根本不是一回事。上一版按条数折，实测把 358 条折成 1 条、
        // 历史尽毁；再一版退回本地预算算法，同样错折。**只有 id 定位是精确的**
        // —— 定位 cutoff 同一思路。
        // ⚠ 边界优先取**重建前** tail 的首条 `srcMsgId`，即折叠/保留的分界消息 `msgs[cut]`。
        // 旧实现写成 `msgs.getOrNull(cut)`，却在 `msgs.clear()/addAll(...)` **之后**才求值：
        // 那时 msgs 已是重建结果，长度从 N 掉到 leadingSystem+1+tail，而 cut 仍是**旧索引**
        // （如 N=154、cut=133 > 新长度 32）→ getOrNull 返回 null → 边界「无」→ 落库整条跳过。
        //
        // ⚠ 但只靠 srcMsgId 还不够：工具循环**中途**压缩时，边界常落在**本轮刚追加**的
        // assistant(tool_calls)/tool 结果 DTO 上——这些 DTO 由仓库层即时构造、**没有 srcMsgId**
        // （只有从库重建的历史 DTO 才有）。实测整轮跑到 190 条时边界落在第 153 条（本轮的 terminal
        // 调用），`srcMsgId` 恒为 null → 仍然跳过。故补一个**工具调用 id** 回退：DTO 带
        // `toolCallId`，槽位消息也存 `toolCallId`，据此可精确回定位。
        val boundaryMsg = tail.firstOrNull()
        val boundarySrcMsgId = boundaryMsg?.srcMsgId
        // 边界可能是 assistant(tool_calls)（本轮即时追加）——它的调用 id 在 toolCalls 里；
        // 也可能是 tool(result)——它在 toolCallId 里。两者都试，槽位消息两处都存了同一个 id。
        val boundaryToolCallId = boundaryMsg?.toolCallId
            ?: boundaryMsg?.toolCalls?.firstOrNull()?.id
        com.wy.openx.tools.AppContextHolder.pendingToolLoopCompaction =
            com.wy.openx.tools.AppContextHolder.PendingToolLoopCompaction(
                conversationId = ctx.conversationId,
                summary = summary,
                boundarySrcMsgId = boundarySrcMsgId,
                boundaryToolCallId = boundaryToolCallId
            )
        // 登记侧留痕：与 VM 的「就地压缩落库」日志配对。若只有这条、没有对面那条，
        // 就说明**登记了但没被消费**（回合异常退出/被下一次登记覆盖），一眼可查。
        com.wy.openx.data.log.AppLogStore.info(
            "压缩诊断",
            "就地压缩登记待落库: cid=${ctx.conversationId} " +
                "边界srcMsgId=${boundarySrcMsgId ?: "无"} 边界toolCallId=${boundaryToolCallId ?: "无"} " +
                "折叠头${head.size}条"
        )
        // 登记完成**之后**才通知：VM 的 notifier 在 completed 分支里读
        // `pendingToolLoopCompaction?.conversationId` 来启动落库协程。顺序反了它读到 null，
        // 立即落库永不启动，只能等回合 `finally` 兜底 → 卡片要等用户按停止才出现
        // （真机日志实证：21:01:19 压缩完成，21:01:31 AI 已在用压缩后上下文跑工具，
        //   直到 21:02:01 用户手动停止才出现「就地压缩落库」）。
        notifyToolLoopCompaction("completed", summary, head.size)
        return CompactionOutcome.COMPACTED
    }

    /** 把压缩状态切到主线程通知 UI（回调可能未注册，静默忽略）。 */
    private suspend fun notifyToolLoopCompaction(status: String, summary: String? = null, foldedCount: Int = 0) {
        com.wy.openx.tools.AppContextHolder.toolLoopCompactionNotifier?.let { cb ->
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { cb(status, summary, foldedCount) }
            }
        }
    }

    /** 工具循环就地压缩的结果：分别用于「重置冷却」「本轮兜底」「触发失败闩锁」。 */
    private enum class CompactionOutcome { COMPACTED, FAILED, SKIPPED }

    /** 生成压缩摘要：优先流式（部分网关仅支持流式），失败再退非流式；都失败返回 null。 */
    private suspend fun summarizeForCompaction(
        ctx: ProviderCallContext, prompt: String, maxTokens: Int?, enableCache: Boolean
    ): String? {
        // 「默认模型→压缩模型」：配置了专用 provider+model 且 provider 有效时，压缩请求改用专用模型
        // （省主模型额度），否则沿用当前聊天模型（ctx）。
        val summaryCtx = resolveSpecializedCtx(ctx, "compaction")
        val summaryMsgs = listOf(
            ChatMessageDto("system", JsonPrimitive("你是一个上下文压缩引擎。本次请求中的对话历史仅作为输入材料，不要继续其中任何任务，不要调用任何工具，只输出接手摘要。")),
            ChatMessageDto("user", JsonPrimitive(prompt))
        )
        val streamPayload = ChatRequest(summaryCtx.model, summaryMsgs, temperature = 0.3f, stream = true,
            streamOptions = com.wy.openx.data.remote.StreamOptions(), maxTokens = maxTokens,
            tools = null, toolChoice = null, reasoningEffort = null,
            promptCacheKey = if (summaryCtx.openaiChatCacheKey) summaryCtx.sessionId else null)
        val streamed = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                sseCallWithRetry(summaryCtx, streamPayload, enableCache, null).fullText.toString().ifBlank { null }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "工具循环压缩（流式）失败: ${e.message?.take(100)}")
            null
        }
        // ⚠ 摘要必须**剥掉思维链**再返回。流式 `fullText` 把 reasoning 包进了 `<thinking>` 块，
        // 直接当摘要用会把模型的内心独白整段写进 `[对话摘要]` —— 摘要从 ~1.5K 膨胀到 11K，
        // 且此后每轮都重发这段独白（白烧 token、模型把「上一步在想什么」当成对话内容）。
        // 实测：工具循环压缩产出的摘要带完整 `<thinking>`（11026 字符），是「一个超大摘要」的根因。
        // 与 VM 侧 performCompaction 的 stripThinkingBlocks 口径一致。
        if (!streamed.isNullOrBlank()) {
            val cleaned = com.wy.openx.data.util.ChatUtils.removeThinkingContent(streamed)
            if (cleaned.isNotBlank()) return cleaned
            // 剥完为空 = 模型只吐了思维链、没给摘要 → 视为失败（返回 null 让调用方走兜底），
            // 绝不返回带 thinking 的原文。
            Log.w(TAG, "工具循环压缩：摘要仅含思维链，剥除后为空，视为失败")
        }
        return try {
            val (resp, _) = postChat(summaryCtx, ChatRequest(summaryCtx.model, summaryMsgs, temperature = 0.3f,
                maxTokens = maxTokens, tools = null, toolChoice = null, reasoningEffort = null,
                promptCacheKey = if (summaryCtx.openaiChatCacheKey) summaryCtx.sessionId else null), enableCache)
            resp?.choices?.firstOrNull()?.message?.content
                ?.let { com.wy.openx.data.util.ChatUtils.removeThinkingContent(it) }
                ?.ifBlank { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "工具循环压缩（非流式）失败: ${e.message?.take(100)}")
            null
        }
    }

    /**
     * 按「默认模型」配置解析专用模型上下文：kind ∈ "compaction"/"vision"/"title"。
     * settings 里对应 providerId+model 均非空、且 provider 存在并启用、有可用 Key 时，
     * 返回以该专用模型构造的独立上下文；否则原样返回 [ctx]（跟随当前聊天模型）。
     */
    private fun resolveSpecializedCtx(base: ProviderCallContext, kind: String): ProviderCallContext {
        val s = com.wy.openx.tools.AppContextHolder.currentSettings ?: return base
        val (pid, model) = when (kind) {
            "compaction" -> s.compactionProviderId to s.compactionModel
            "vision" -> s.visionProviderId to s.visionModel
            else -> s.titleProviderId to s.titleModel
        }
        if (pid.isBlank() || model.isBlank()) return base
        val p = com.wy.openx.tools.AppContextHolder.allProviders.firstOrNull { it.id == pid } ?: return base
        if (!p.isEnabled) return base
        val key = p.firstUsableApiKey
        if (key.isBlank()) return base
        return ProviderCallContext(
            providerId = p.id,
            config = p.copy(selectedModel = model),
            baseUrl = p.baseUrl.takeIf { it.isNotBlank() } ?: base.baseUrl,
            apiKey = key,
            model = model,
            useResponseApi = p.useResponseApi,
            useFullUrl = p.useFullUrl,
            openaiChatCacheKey = p.openaiChatCacheKey,
            userAgent = p.userAgent,
            sessionId = base.sessionId,
            // 【同源修复】专用模型 ctx 原来只搬 sessionId，把 conversationId 和
            // contextWindowTokens 全丢了 —— 默认值 0 → 压缩/识图/起标题这些路径
            // 一律退回全局单槽阈值，并发时算错。本轮只是换模型，会话归属没变，
            // 这两个字段应当原样继承 base。
            conversationId = base.conversationId,
            contextWindowTokens = base.contextWindowTokens
        )
    }

    /**
     * 一批消息的 token 粗估（**单一事实来源** [TokenEstimator]，与 MainViewModel 同口径）。
     *
     * ⚠ 旧实现曾**漏算 tool_calls.arguments**，导致工具循环内的「就地压缩」量错尺子：旧代码
     * 只 `other += 16`/个调用，而 writeFile/appendFile 的文件正文**全在 arguments 里**
     * （一次写 2000 字就是 2000 字）。于是重度写文件的会话，真实 127K 被估成 42K（差 3 倍）。
     * 压缩据此选保留尾巴 → 以为留了 20K、实际留了 110K，压缩「看起来执行了」但真实上下文
     * 纹丝不动（日志实测：估 24K / 真实 131K）。
     *
     * 口径与真正被 provider 计入的请求一致：content + tool_calls(name+arguments)。
     *
     * ⚠ **reasoning_content 不再计入**（2026-10-07 修正）：原先按「回传该字段也占额度」计入了它，
     * 但真机日志证明 provider 端**不把它计入 prompt token**——某会话 388K 字符的 reasoning
     * （≈580K 估算）实际整段未计入，reported prompt 仅 148K；把它估上后长思考会话被系统性
     * 高估 4.3 倍（估 641K / 真实 148K），切到小窗口模型时直接误判超窗、把会话压掉。
     * 不含 reasoning 的估算（content+args+schema）≈155K，与真实 148K 吻合。
     * 不含 reasoning 会「保守偏低」：仅可能更晚触发压缩，不会误压，是安全方向。
     */
    private fun estimateMsgTokens(msgs: List<ChatMessageDto>): Int {
        val bodies = ArrayList<String>(msgs.size * 2)
        var extra = 0
        for (m in msgs) {
            when (val c = m.content) {
                null, is JsonNull -> {}
                is JsonPrimitive -> if (c.isString) c.asString.takeIf { it.isNotBlank() }?.let(bodies::add)
                else -> bodies.add(c.toString())
            }
            m.toolCalls?.forEach { tc ->
                bodies.add(tc.function.name)
                bodies.add(tc.function.arguments)
                extra += 16   // 协议固定开销（id/type/字段名等）
            }
        }
        return com.wy.openx.data.util.TokenEstimator.messagesTokens(bodies, extraTokens = extra)
    }

    /** 把单条 msgs 消息格式化为摘要输入文本：工具消息带上参数与结果（截断）。 */
    private fun formatMsgForSummary(m: ChatMessageDto): String = buildString {
        append("[").append(m.role).append("] ")
        val body = when (val c = m.content) {
            null, is JsonNull -> ""
            is JsonPrimitive -> if (c.isString) c.asString else c.toString()
            else -> c.toString()
        }
        if (body.isNotBlank()) append(body.take(6_000))
        m.toolCalls?.forEach { tc ->
            append("\n  <调用工具 ").append(tc.function.name).append("> ")
            append(tc.function.arguments.take(COMPACTED_TOOL_RESULT_CHARS))
        }
    }

    /** 保留靠近当前的尾部、截到预算内并标注省略；返回（文本, 是否截断）。 */
    private fun truncateText(text: String, budget: Int): Pair<String, Boolean> {
        if (text.length <= budget) return text to false
        val kept = text.takeLast(budget).substringAfter('\n')
        return "（前段历史已省略）\n$kept" to true
    }


    /**
     * 可见正文判定：剥掉 <think>/<thinking> 思考块后的文本。
     *
     * 背景（真实故障）：推理模型（agnes-2.5-flash 等）有时把**整段回答**塞进 content 的
     * <thinking> 块就 finish_reason=stop，聊天页把思考块过滤掉 → 气泡全空 → 表现为「AI 又停了」。
     * 原判空只看 content.isNullOrBlank()，非空的 thinking 文本被当成正常回复直接返回，绕过了
     * 后面所有"空回复→引导/汇总轮"兜底。故所有"模型是否给出了可见回复"的判断统一走这里。
     */
    private fun visibleText(s: String?): String =
        s?.let { com.wy.openx.data.util.ChatUtils.removeThinkingContent(it) }.orEmpty().trim()

    private suspend fun executeAndFollowUp(
        ctx: ProviderCallContext,
        msgs: MutableList<ChatMessageDto>, temperature: Float, maxTokens: Int?,
        tools: List<ToolDef>?, toolCalls: List<ToolCall>, toolRegistry: ToolRegistry,
        onToolCall: (suspend (String, String, String) -> Unit)?,
        onToolResult: (suspend (String, String, String) -> Unit)? = null,
        onTokens: ((Int) -> Unit)? = null,
        onContextTokens: ((Int) -> Unit)? = null,
        onCompletionTokens: ((Int) -> Unit)? = null,
        onStreamDelta: ((String) -> Unit)? = null,
        onToolCallRequest: (suspend (String, String) -> Boolean)? = null,
        onCacheHit: ((Boolean, Int) -> Unit)? = null,
        enableCache: Boolean = false,
        // ↓ 子代理专用（主链路传 null/0/null）
        onToolProgress: (suspend (String, String) -> Unit)? = null,
        maxToolRounds: Int = 0,
        toolPolicy: ((String, String) -> String?)? = null
    ): String {
        var currentToolCalls = toolCalls
        var roundIndex = 0
        val toolOutputs = mutableListOf<String>()
        // 上一轮真实输入 token（prompt_tokens）：循环内「就地压缩」的阈值判定来源。
        // 首轮无真实值，退化为本地估算；之后每轮由 onTokens 更新。
        var lastPromptTokens = estimateMsgTokens(msgs)
        // 就地压缩冷却：一次压缩后至少隔 N 轮才允许再次压缩，避免每轮都发摘要请求。
        var compactCooldown = 0
        // 压缩失败闩锁：本轮一旦压缩失败，后续不再重试，
        // 避免每轮白试一次摘要请求。仅对「本轮工具循环」有效。
        var compactionFailed = false
        // 不设轮次上限，直到模型不再发起工具调用（currentToolCalls 为空）自然结束。
        // 期间每轮都 ensureActive()，用户「停止」可随时取消；异常有兜底、预算由自动压缩兜底，不会无限跑。
        while (currentToolCalls.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            // 子代理预算熔断：主链路 maxToolRounds=0 不受影响。子代理（尤其 explore 扫大仓库）
            // 极易空转烧额度，触顶后强制一次无工具的收尾请求，拿不到结论就返回兜底摘要。
            if (maxToolRounds > 0 && roundIndex >= maxToolRounds) {
                Log.w(TAG, "子代理达到工具轮次上限 $maxToolRounds，强制收尾")
                onToolProgress?.invoke("subagent_dispatch", "达到轮次上限 $maxToolRounds，强制收尾")
                msgs.add(guideMsg(
                    "你已达到本任务允许的工具调用轮次上限（$maxToolRounds 轮）。请立即停止调用工具，" +
                        "基于已获得的信息给出最终结论；若信息不足，明确写出还缺什么。"
                ))
                val forced = try {
                    streamFollowUpRound(ctx, msgs.toList(), temperature, maxTokens, null, enableCache, onStreamDelta, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "子代理强制收尾请求失败: ${e.message}")
                    null to null
                }
                val forcedText = forced.first
                return@executeAndFollowUp if (visibleText(forcedText).isNotBlank()) forcedText!!
                else toolLoopFallbackSummary(toolOutputs.size, "达到轮次上限且收尾请求无有效响应")
            }
            roundIndex++
            // Phase 1: 串行审批本批调用（保持 UI 回调顺序、不并发触碰 UI 状态）；
            //          开始上报与执行放在 Phase 2 逐个进行，UI 上逐个"开始→完成"
            data class Pending(val tc: ToolCall, val approved: Boolean, val rejectReason: String? = null)
            val pendings = currentToolCalls.map { tc ->
                currentCoroutineContext().ensureActive()
                val toolName = tc.function.name.removePrefix("functions.")
                val toolArgs = tc.function.arguments
                val approved = onToolCallRequest?.invoke(toolName, toolArgs) ?: true
                // 子代理角色权限：不下发只是第一道，执行前必须再挡一次——
                // 父级 ToolRegistry 是全量的，光靠不下发挡不住幻觉/越界调用。
                val rejectReason = if (approved) toolPolicy?.invoke(toolName, toolArgs) else null
                Pending(tc, approved, rejectReason)
            }
            val rejectedById = pendings.filter { it.rejectReason != null }.associate { it.tc.id to it.rejectReason!! }
            // Phase 1: 批前对将写入的文件统一打快照（editFile/writeFile/batch_edit，跳过预览），
            //          确保所有快照先于任何写入发生
            val writeTargets = pendings.filter { it.approved && it.rejectReason == null }
                .flatMap { toolRegistry.collectWriteTargets(it.tc) }
            if (writeTargets.isNotEmpty()) {
                CheckpointManager.snapshotBeforeWrite(
                    writeTargets,
                    if (ctx.checkpointTurnId != 0L) ctx.checkpointTurnId else null
                )
            }
            // Phase 2: 本批已批准的调用按显示节奏处理：
            //          先整批上报"开始"（UI 一次显示同批全部工具的执行中占位），
            //          再逐个串行执行并回结果（模型一次发起的同批
            //          工具可能隐含依赖，如"先下载成功再检查结果"，并行会造成快工具
            //          先返回、慢工具后完成而把矛盾结果回传给模型）。
            // 本批是否含任务面板工具（快照式的 `todo`）。含则**整批强制串行**，
            // 即使开了「并行执行批内工具」。
            //
            // 为什么：`todo` 是**整体替换**语义（提交一份完整清单覆盖当前清单）。
            // 同一批里若出现**多个** todo 调用，并发执行下最终留下哪一份由线程抢占决定
            // （后完成的那次覆盖前一次），结果不可预期。串行分支是
            // `for (tc in currentToolCalls)` 按模型给的顺序逐个执行，
            // 于是「最后一份清单胜出」是确定的，模型也能预期自己上一份被覆盖。
            //
            // ⚠ 与旧实现（逐条 add/update/remove 三工具）的区别：
            // 那时串行是为了让**顺序**正确（并发写入次序随机 → 面板乱序）；
            // 现在顺序由 `TaskItem.order`（= 数组下标）**结构化保证**，与执行时序无关，
            // 所以串行在这里只解决「多份快照谁胜出」的确定性问题。
            //
            // 为什么整批降级而不是只让 todo 串行：同批可能混着别类工具
            // （如 [todo(A), readFile(慢), todo(B)]）。只串行 todo 的话，
            // 两者与 readFile 的相对先后取决于谁先动，A/B 谁最终生效仍不确定。
            //
            // 代价可忽略：任务工具是纯内存操作（微秒级），并发收益接近零。
            val batchHasTaskTool = currentToolCalls.any { toolRegistry.isTaskTool(it.function.name) }
            val parallelMode = (com.wy.openx.tools.AppContextHolder.currentSettings?.parallelToolCalls ?: true) &&
                !batchHasTaskTool
            val approvedById = pendings.filter { it.approved && it.rejectReason == null }.associateBy { it.tc.id }
            for (tc in currentToolCalls) {
                currentCoroutineContext().ensureActive()
                if (approvedById[tc.id] != null) {
                    val n = tc.function.name.removePrefix("functions.")
                    onToolCall?.invoke(n, tc.id, tc.function.arguments)
                    onToolProgress?.invoke(n, "第 ${roundIndex} 轮 · 正在调用 $n")
                }
                rejectedById[tc.id]?.let { reason ->
                    val n = tc.function.name.removePrefix("functions.")
                    onToolProgress?.invoke(n, "第 ${roundIndex} 轮 · 已拒绝调用 $n（角色权限）")
                }
            }
                if (parallelMode) {
                    // ===== 并行模式（默认开启，可在设置或限流提示窗里改回串行）=====
                    // 说明：模型一次发起的同批工具可能隐含依赖（如"先下载成功再检查结果"），
                    //       并行执行会让快工具先返回、慢工具后完成而把矛盾结果回传给模型。
                    //       因此这里仅把"执行"并发化；msgs 仍严格按 tc 原始顺序回填（不并发写），
                    //       结果按 toolCallId 与模型发起的 tool_calls 一一对应——
                    //       即使并行，模型看到的仍是与批内原始顺序一致、无矛盾的稳定回包。
                    val approvedIds = currentToolCalls.filter { approvedById[it.id] != null }
                    // 值 = (结果文本, 是否执行失败)。留着失败标记，供下面判定限流时排除成功输出。
                    val resultsById: Map<String, Pair<String, Boolean>> = coroutineScope {
                        approvedIds.map { tc ->
                            async {
                                val outcome = try {
                                    toolRegistry.executeToolCall(tc) to false
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e  // 取消必须重新抛出，绝不能被 runCatching 吞掉
                                } catch (e: Exception) {
                                    "工具执行失败：${e.message}" to true
                                }
                                tc.id to outcome
                            }
                        }.awaitAll().toMap()
                    }
                    // 并行把同批工具一起发出去，瞬时并发最容易超过供应商容忍度而吃 429；
                    // 但用户只会看到「某几个工具失败了」，看不出真正原因是并发。
                    // 这里统计本批命中数上报，由 VM 累计到阈值后提示是否切回串行。
                    //
                    // ⚠ 只统计**执行失败**的那几个：限流一定表现为调用抛错，而成功的工具输出里
                    // 出现 "429"/"限流" 只是内容巧合（搜到的网页、读到的日志、代码常量）。
                    // 早先对全部结果做特征匹配，导致并行跑一批含这类内容的工具就误弹提示。
                    val rateLimitedHits = resultsById.values.count { (text, failed) -> failed && looksRateLimited(text) }
                    if (rateLimitedHits > 0) {
                        // 带上会话 id：用户在某会话选过「保持并行」后只静默该会话。
                        com.wy.openx.tools.AppContextHolder.rateLimitNotifier?.invoke(ctx.conversationId, rateLimitedHits)
                    }
                    for (tc in currentToolCalls) {
                        currentCoroutineContext().ensureActive()
                        val toolName = tc.function.name.removePrefix("functions.")
                        val policyReason = rejectedById[tc.id]
                        if (policyReason != null) {
                            onToolCall?.invoke(toolName, tc.id, tc.function.arguments)
                            msgs.add(ChatMessageDto("tool", JsonPrimitive("错误：$policyReason 请改用本轮允许的工具。"), toolCallId = tc.id))
                            onToolResult?.invoke(toolName, tc.id, "错误：$policyReason")
                            continue
                        }
                        if (approvedById[tc.id] == null) {
                            onToolCall?.invoke(toolName, tc.id, tc.function.arguments)
                            msgs.add(ChatMessageDto("tool", JsonPrimitive("用户拒绝了此操作，请向用户说明原因并询问替代方案"), toolCallId = tc.id))
                            onToolResult?.invoke(toolName, tc.id, "用户已拒绝")
                            continue
                        }
                        val result = resultsById[tc.id]?.first ?: continue
                        val truncatedResult = projectToolResult(ctx.conversationId, toolName, result)
                        msgs.add(ChatMessageDto("tool", JsonPrimitive(truncatedResult), toolCallId = tc.id))
                        // ⚠ 传给 VM 落库/显示的必须是**与请求里同一份** `truncatedResult`，不能用
                        // `result.take(2000)`：那会让库里只存 2000 字符，而本轮请求发的是完整版 →
                        // 下一回合从库重建时工具结果整体缩水，上下文数字凭空变小（实测 44K→38.8K），
                        // 且超过 2000 字符的结果**永久丢失**（未落盘、不可恢复）。
                        // 显示层的截断交给 UI（ToolCardMergedSection 自带折叠）。
                        onToolResult?.invoke(toolName, tc.id, truncatedResult)
                        toolOutputs.add(truncatedResult)
                    }
                
            } else {
                for (tc in currentToolCalls) {
                    currentCoroutineContext().ensureActive()
                    val toolName = tc.function.name.removePrefix("functions.")
                    val policyReason = rejectedById[tc.id]
                    if (policyReason != null) {
                        onToolCall?.invoke(toolName, tc.id, tc.function.arguments)
                        msgs.add(ChatMessageDto("tool", JsonPrimitive("错误：$policyReason 请改用本轮允许的工具。"), toolCallId = tc.id))
                        onToolResult?.invoke(toolName, tc.id, "错误：$policyReason")
                        continue
                    }
                    if (approvedById[tc.id] == null) {
                        onToolCall?.invoke(toolName, tc.id, tc.function.arguments)
                        msgs.add(ChatMessageDto("tool", JsonPrimitive("用户拒绝了此操作，请向用户说明原因并询问替代方案"), toolCallId = tc.id))
                        onToolResult?.invoke(toolName, tc.id, "用户已拒绝")
                        continue
                    }
                    val result = try {
                        toolRegistry.executeToolCall(tc)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 工具自身异常不打断工具链：作为结果回填给模型继续。
                        "工具执行失败：${e.message ?: e.javaClass.simpleName}"
                    }
                    val truncatedResult = if (result.length > MAX_TOOL_RESULT_CHARS) result.take(MAX_TOOL_RESULT_CHARS) + "\n\n[结果过长，已截断]" else result
                    msgs.add(ChatMessageDto("tool", JsonPrimitive(truncatedResult), toolCallId = tc.id))
                    // 同上：回调传完整结果，保证「库里存的 == 本轮发的」，避免跨回合缩水与数据丢失。
                    onToolResult?.invoke(toolName, tc.id, truncatedResult)
                    onToolProgress?.invoke(toolName, "第 ${roundIndex} 轮 · $toolName 完成（${result.length} 字符）")
                    toolOutputs.add(truncatedResult)
                }
            }
            // 后台任务完成通知**搭车注入**：本轮工具循环里，把该会话攒下的通知作为 system 消息
            // 追加到工具结果之后，AI 当轮即可感知（省掉「等本轮结束再起一轮」的 LLM 往返）。
            // 只有真正进入请求后才 ack；本轮被取消时通知仍在队列，交给兜底路径。
            if (ctx.conversationId != 0L) {
                val pendingNotifs = com.wy.openx.tools.AgentNotificationCenter.peek(ctx.conversationId)
                if (pendingNotifs.isNotEmpty()) {
                    msgs.add(ChatMessageDto("system", JsonPrimitive(
                        com.wy.openx.tools.AgentNotificationCenter.render(pendingNotifs)
                    )))
                    com.wy.openx.tools.AgentNotificationCenter.ack(ctx.conversationId, pendingNotifs.map { it.seq })
                }
            }
            // 每轮 LLM 调用前做上下文预算守卫（「每次 CallLlm 前检查」）：
            // 工具循环期间输入会持续膨胀（尤其 12 万字符级的工具结果），不设防必然撞窗口 → 400 → 断链。
            enforceContextBudget(msgs, TOOL_LOOP_CHAR_BUDGET)
            // 就地压缩（ 「每次 CallLlm 前 compactIfNeeded」）：用上一轮真实 prompt_tokens
            // 判定是否超过用户设定阈值，超过则把较早部分折叠成摘要，真正缩小本轮发出的上下文。
            // 这是长任务中途能压缩的关键——事后（整轮 finally）自动压缩发现时为时已晚。
            if (compactionFailed) {
                // 本轮压缩已失败：不再重试，仅做结构安全的结果收缩兜底。
                enforceContextBudget(msgs, TOOL_LOOP_CHAR_BUDGET)
            } else if (compactCooldown > 0) {
                compactCooldown--
            } else when (compactToolLoopContext(ctx, msgs, lastPromptTokens, enableCache)) {
                CompactionOutcome.COMPACTED -> {
                    compactCooldown = TOOL_LOOP_COMPACT_COOLDOWN_ROUNDS
                    lastPromptTokens = estimateMsgTokens(msgs)
                }
                CompactionOutcome.FAILED -> compactionFailed = true
                CompactionOutcome.SKIPPED -> {}
            }
            // 本轮 LLM 调用的输入 token 上报（onTokens）就是下一轮压缩判定的依据，这里截获更新。
            val tokTap: (Int) -> Unit = { t -> lastPromptTokens = t; onTokens?.invoke(t); onContextTokens?.invoke(t) }
            val roundReasoning = StringBuilder()
            val (content, newToolCalls) = try {
                streamFollowUpRound(
                    ctx, msgs.toList(), temperature, maxTokens, tools, enableCache,
                    onStreamDelta, tokTap, onContextTokens, onCompletionTokens, onCacheHit, roundReasoning
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // continue 轮 LLM 请求彻底失败（非 400 已降级）：不能把整条链路都带崩——
                //  LlmError 语义：保留已收集的工具输出与已流式输出的正文，
                // 回给模型一条「上轮调用失败」的 tool 结果，让模型基于现状收尾或重试，
                // 而不是让 executeAndFollowUp 断裂、消息停在半句上。
                Log.w(TAG, "executeAndFollowUp follow-up 请求失败，构造兜底继续: ${e.message}")
                onStreamDelta?.invoke("\n\n[工具链中断：后续请求出错 ${e.message ?: e.javaClass.simpleName}，基于已获得的信息继续。]")
                val failureMsg = "注意：上一条请求在继续执行工具时失败，错误：${e.message ?: e.javaClass.simpleName}。请基于已获得的信息给出结论；若确实需要重试某项操作，请明确重试。"
                msgs.add(guideMsg(failureMsg))
                val (retryContent, retryTools) = try {
                    streamFollowUpRound(
                        ctx, msgs.toList(), temperature, maxTokens, null, enableCache,
                        onStreamDelta, onTokens, onContextTokens, onCompletionTokens, onCacheHit
                    )
                } catch (e2: kotlinx.coroutines.CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    Log.e(TAG, "executeAndFollowUp 兜底请求也失败，返回简短状态（不回吐原始输出）", e2)
                    return@executeAndFollowUp toolLoopFallbackSummary(toolOutputs.size, "后续请求反复失败")
                }
                if (visibleText(retryContent).isEmpty() && retryTools.isNullOrEmpty()) {
                    return@executeAndFollowUp toolLoopFallbackSummary(toolOutputs.size, "后续请求无有效响应")
                }
                retryContent to retryTools
            }
            if (newToolCalls.isNullOrEmpty()) {
                // 「话说一半就停」兜底：工具轮结束后的收尾正文若以引导语结尾（「让我…：」）且未再发工具，
                // 追加一次继续轮（保留 tools），逼模型真正执行/收尾，避免「AI 回复又停了」。
                val contentVisible = visibleText(content)
                if (contentVisible.isNotBlank()) {
                    if (looksIncomplete(contentVisible)) {
                        Log.w(TAG, "executeAndFollowUp 收尾正文疑似未完成（引导语结尾），追加继续轮")
                        val contMsgs = msgs.toMutableList().apply {
                            add(ChatMessageDto("assistant", JsonPrimitive(content)))
                            add(guideMsg("请继续（如需执行命令/读写文件请直接调用工具，不要只说「让我…」就停）。"))
                        }
                        val (contContent, contTools) = try {
                            streamFollowUpRound(ctx, contMsgs.toList(), temperature, maxTokens, tools, enableCache, onStreamDelta, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "executeAndFollowUp 继续轮失败: ${e.message}")
                            null to null
                        }
                        if (!contTools.isNullOrEmpty()) {
                            contMsgs.add(ChatMessageDto("assistant", contContent?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE, toolCalls = contTools))
                            currentToolCalls = contTools
                            msgs.clear(); msgs.addAll(contMsgs)
                            continue
                        }
                        if (visibleText(contContent).isNotBlank()) return content + "\n" + contContent
                    }
                    return content!!
                }
                Log.w(TAG, "executeAndFollowUp follow-up 返回空，toolOutputs.size=${toolOutputs.size}")
                if (toolOutputs.isNotEmpty()) {
                    val finalPrompt = "请根据上述所有工具执行结果，用中文给出完整的分析和回复。不要调用工具。"
                    msgs.add(guideMsg(finalPrompt))
                    val (retryContent, _) = streamFollowUpRound(
                        ctx, msgs.toList(), temperature, maxTokens, null, enableCache,
                        onStreamDelta, onTokens, onContextTokens, onCompletionTokens, onCacheHit
                    )
                    if (visibleText(retryContent).isNotBlank()) return retryContent!!
                    val (retry2Content, _) = streamFollowUpRound(
                        ctx, msgs.toList(), temperature, maxTokens, null, enableCache,
                        null, onTokens, onContextTokens, onCompletionTokens, onCacheHit
                    )
                    if (visibleText(retry2Content).isNotBlank()) return retry2Content!!
                    Log.d(TAG, "executeAndFollowUp 全部重试失败，返回简短状态（不回吐原始输出）")
                    return toolLoopFallbackSummary(toolOutputs.size, "模型未给出最终回复")
                }
                Log.e(TAG, "executeAndFollowUp 无工具输出，返回 [AI 处理完成]")
                return "[AI 处理完成]"
            }
            currentToolCalls = newToolCalls
            // 保留该轮正文：模型「先说一句再调工具」时，这句话必须进历史，
            // 否则历史与用户所见不一致、模型重复劳动。此前传 JsonNull 丢弃了 content。
            // 同时携带 reasoning_content（S8）：DeepSeek 思考模型的 assistant(带tool_calls) 消息
            // 回传时缺该字段会 400；非空才发（避免给不支持的网关塞未知字段）。
            msgs.add(ChatMessageDto(
                "assistant",
                if (!content.isNullOrBlank()) JsonPrimitive(content) else JsonNull.INSTANCE,
                toolCalls = newToolCalls,
                reasoningContent = roundReasoning.toString().ifBlank { null }
            ))
        }
        // 无轮次上限，循环内各出口均已 return；此处仅为编译期兜底，不回吐原始工具输出。
        Log.w(TAG, "executeAndFollowUp 循环自然结束（无待执行工具且无正文），toolOutputs=${toolOutputs.size}")
        return toolLoopFallbackSummary(toolOutputs.size, "模型未给出最终回复")
    }

    private suspend fun streamFollowUpRound(
        ctx: ProviderCallContext,
        msgs: List<ChatMessageDto>, temperature: Float, maxTokens: Int?,
        tools: List<ToolDef>?, enableCache: Boolean,
        onStreamDelta: ((String) -> Unit)?,
        onTokens: ((Int) -> Unit)?,
        onContextTokens: ((Int) -> Unit)? = null,
        onCompletionTokens: ((Int) -> Unit)? = null,
        onCacheHit: ((Boolean, Int) -> Unit)?,
        /** 可选出参：回填本轮纯思考内容（供 DeepSeek 等模型的 reasoning_content 回传，见 S8）。 */
        reasoningOut: StringBuilder? = null
    ): Pair<String?, List<ToolCall>?> {
        val model = ctx.model
        // 【口径统一】工具循环内也走与回合起点相同的投影（剥思考 + 工具结果落盘）。
        // 旧实现只有回合起点投影，于是同一段历史在「循环内」与「下一回合起点」发出去的内容不同，
        // 顶部上下文数字随之跳变（暂停 45.9K → 继续 38.8K，消息一条没少）。
        val sendMsgs = projectForSend(msgs, ctx)
        val payload = ChatRequest(ctx.model, sendMsgs, temperature = temperature, stream = true,
            streamOptions = com.wy.openx.data.remote.StreamOptions(),
            maxTokens = maxTokens, tools = tools, toolChoice = if (tools != null) "auto" else null, reasoningEffort = currentReasoningEffort(ctx),
            promptCacheKey = if (ctx.openaiChatCacheKey) ctx.sessionId else null)

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val acc = try {
                sseCallWithRetry(ctx, payload, enableCache, onStreamDelta)
            } catch (e: Exception) {
                if (tools != null && e.message?.contains("HTTP 400") == true) {
                    Log.w(TAG, "streamFollowUpRound 请求失败(400)，降级重试(无tools): ${e.message?.take(100)}")
                    val fallbackPayload = ChatRequest(model, sendMsgs, temperature = temperature, stream = true,
                        streamOptions = com.wy.openx.data.remote.StreamOptions(),
                        maxTokens = maxTokens, tools = null, toolChoice = null, reasoningEffort = currentReasoningEffort(ctx),
                        promptCacheKey = if (ctx.openaiChatCacheKey) ctx.sessionId else null)
                    try {
                        sseCallWithRetry(ctx, fallbackPayload, enableCache, onStreamDelta)
                    } catch (fe: Exception) {
                        // 流式(无tools)仍失败 → 非流式同步兜底（保留 tools → 再去 tools），兼容不支持流式/工具组合的模型
                        Log.w(TAG, "streamFollowUpRound 降级(无tools,流式)也失败，尝试非流式补充: ${fe.message?.take(100)}")
                        return@withContext syncFollowUpFallback(ctx, model, sendMsgs, temperature, maxTokens, tools, enableCache, onTokens, onContextTokens, onCompletionTokens, onCacheHit)
                    }
                } else {
                    throw e
                }
            }

            if (acc.cacheHitTokens > 0) onCacheHit?.invoke(true, acc.cacheHitTokens)
            acc.promptTokens?.let { onTokens?.invoke(it) }
            if (acc.contextTokens > 0) onContextTokens?.invoke(acc.contextTokens)
            acc.completionTokens?.let { onCompletionTokens?.invoke(it) }
            reasoningOut?.append(acc.reasoningText)
            Log.d(TAG, "streamFollowUpRound 本轮结束: finishReason=${acc.finishReason}, hasToolCalls=${acc.hasToolCalls}, textLen=${acc.fullText.length}")

            if (acc.hasToolCalls && acc.toolAcc.isNotEmpty()) {
                val result = acc.toolAcc.entries.map { (_, d) ->
                    ToolCall(
                        id = d.id ?: "call_${d.index}",
                        // 空名兜底为 ""（见 streamChat 同处注释）：否则绕过 isNotBlank 过滤被执行成 unknown。
                        function = ToolCallFunction(
                            name = d.function?.name.orEmpty(),
                            arguments = normalizeToolArgs(d.function?.arguments)
                        )
                    )
                }.filter { it.function.name.isNotBlank() }
                if (result.isEmpty()) {
                    Log.w(TAG, "streamFollowUpRound 工具调用均为空名，忽略本轮 tool_calls")
                }
                Pair(acc.fullText.toString().ifBlank { null }, result.ifEmpty { null })
            } else {
                // 原生 tool_calls 缺失：检查是否把调用写成了 XML 正文（ streamChat 同处处理）。
                val xml = com.wy.openx.tools.XmlToolCallParser.parse(acc.rawContent.toString())
                if (xml.isNotEmpty()) {
                    val calls = xml.mapIndexed { i, c ->
                        ToolCall(
                            id = "call_xml_${System.nanoTime()}_$i",
                            function = ToolCallFunction(name = c.name, arguments = c.arguments)
                        )
                    }
                    Log.w(TAG, "streamFollowUpRound 从正文解析到 XML 工具调用 ${calls.size} 个: ${calls.map { it.function.name }}")
                    Pair(com.wy.openx.tools.XmlToolCallParser.strip(acc.rawContent.toString()).ifBlank { null }, calls)
                } else {
                    Pair(acc.fullText.toString().ifBlank { null }, null)
                }
            }
        }
    }

    /** 非流式兜底：先保留 tools，仍失败再去 tools；成功则返回正文与工具调用。 */
    private suspend fun syncFollowUpFallback(
        ctx: ProviderCallContext, model: String, msgs: List<ChatMessageDto>,
        temperature: Float, maxTokens: Int?, tools: List<ToolDef>?, enableCache: Boolean,
        onTokens: ((Int) -> Unit)?,
        onContextTokens: ((Int) -> Unit)? = null, onCompletionTokens: ((Int) -> Unit)?,
        onCacheHit: ((Boolean, Int) -> Unit)? = null
    ): Pair<String?, List<ToolCall>?> {
        suspend fun attempt(withTools: List<ToolDef>?): Pair<String?, List<ToolCall>?> {
            val payload = ChatRequest(model, msgs, temperature = temperature, stream = null,
                maxTokens = maxTokens, tools = withTools, toolChoice = if (withTools != null) "auto" else null,
                reasoningEffort = currentReasoningEffort(ctx),
                promptCacheKey = if (ctx.openaiChatCacheKey) ctx.sessionId else null)
            val request = buildChatRequest(ctx, JsonRequestBody(gson, payload, JSON), enableCache, ctx.apiKey)
            val call = clientFor(ctx).newCall(request)
            // 同 postChat：非流式也必须注册句柄，「停止」才有效。
            setCurrentCall(ctx.conversationId, call)
            // 句柄延后到响应体读完后注销（同 postChat）：body.string() 是第二个阻塞点，
            // 提前 remove 会让读体期间按「停止」失效。
            val bodyStr: String
            try {
                val resp = call.execute()
                if (!resp.isSuccessful) {
                    val err = resp.use { it.body?.string() }
                    throw IllegalStateException("HTTP ${resp.code}" + (if (!err.isNullOrBlank()) ": ${err.take(2000)}" else ""))
                }
                bodyStr = resp.use { it.body?.string() } ?: ""
            } finally {
                currentCalls.remove(ctx.conversationId, call)
            }
            val obj = gson.fromJson(bodyStr, JsonObject::class.java)
            val usageObj = obj.get("usage")?.takeIf { it !is JsonNull }?.asJsonObject
            val pt = usageObj?.get("prompt_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val ct = usageObj?.get("completion_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            if (pt > 0) onTokens?.invoke(pt)
            if (ct > 0) onCompletionTokens?.invoke(ct)
            // 缓存命中口径同流式（DeepSeek prompt_cache_hit_tokens / Anthropic
            // cache_read_input_tokens / OpenAI cached_tokens），
            // 否则此兜底路径只记 inputTokens 不记缓存，会拉低统计页命中率。
            val cached = usageObj?.let { cacheHitTokensOf(it) } ?: 0
            if (cached > 0) onCacheHit?.invoke(true, cached)
            val msg = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")
            val text = msg?.get("content")?.takeIf { it is JsonPrimitive && !it.isJsonNull }?.asString
            val tcs = msg?.getAsJsonArray("tool_calls")?.map { el ->
                val o = el.asJsonObject
                ToolCall(
                    id = o.get("id")?.asString ?: "call_${System.nanoTime()}",
                    function = ToolCallFunction(
                        // 空名兜底为 ""（见 streamChat 同处注释）：否则绕过 isNotBlank 过滤被执行成 unknown。
                        name = o.getAsJsonObject("function")?.get("name")?.asString?.removePrefix("functions.").orEmpty(),
                        arguments = normalizeToolArgs(o.getAsJsonObject("function")?.get("arguments")?.asString)
                    )
                )
            }.orEmpty().filter { it.function.name.isNotBlank() }
            return text to tcs.ifEmpty { null }
        }
        return try {
            attempt(tools)
        } catch (se: Exception) {
            Log.w(TAG, "streamFollowUpRound 非流式(保留tools)失败，彻底无tools: ${se.message?.take(100)}")
            attempt(null)
        }
    }

    private suspend fun reactChat(
        ctx: ProviderCallContext, systemPrompt: String,
        userMessage: String, imageBase64: List<String>?, temperature: Float, maxTokens: Int?,
        tools: List<ToolDef>?, toolRegistry: ToolRegistry?,
        onToolCall: (suspend (String, String, String) -> Unit)?,
        onToolResult: (suspend (String, String, String) -> Unit)?,
        onTokens: ((Int) -> Unit)?,
        onContextTokens: ((Int) -> Unit)? = null,
        onCompletionTokens: ((Int) -> Unit)? = null,
        onStreamDelta: ((String) -> Unit)?,
        onToolCallRequest: (suspend (String, String) -> Boolean)?,
        onCacheHit: ((Boolean, Int) -> Unit)?,
        historyMessages: List<ChatMessageDto>,
        enableCache: Boolean
    ): String {
        val parser = ReActToolParser()
        val toolPrompt = tools?.let { parser.buildToolPrompt(it) } ?: ""
        val fullSystemPrompt = systemPrompt + toolPrompt
        val msgs = mutableListOf<ChatMessageDto>().apply {
            add(ChatMessageDto("system", JsonPrimitive(fullSystemPrompt)))
            addAll(historyMessages)
            add(ChatMessageDto("user", buildUserContent(userMessage, imageBase64)))
        }
        val toolOutputs = mutableListOf<String>()

        var reactRetryCount = 0
        // 上一轮真实输入 token（prompt_tokens）：循环内「就地压缩」的阈值判定来源（同 executeAndFollowUp）。
        var lastPromptTokens = estimateMsgTokens(msgs)
        var compactCooldown = 0
        var compactionFailed = false
        // 不设轮次上限，直到模型不再返回 TOOL_CALL（calls 为空）自然结束。
        // 每轮 ensureActive()，用户「停止」可随时取消。
        var round = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            round++
            val streamBuffer = StringBuilder()
            // 每轮 LLM 调用前：上下文预算守卫 + 就地压缩（每次 CallLlm 前的检查）。
            enforceContextBudget(msgs, TOOL_LOOP_CHAR_BUDGET)
            if (compactionFailed) {
                // 已失败：不再重试压缩。
            } else if (compactCooldown > 0) {
                compactCooldown--
            } else when (compactToolLoopContext(ctx, msgs, lastPromptTokens, enableCache)) {
                CompactionOutcome.COMPACTED -> {
                    compactCooldown = TOOL_LOOP_COMPACT_COOLDOWN_ROUNDS
                    lastPromptTokens = estimateMsgTokens(msgs)
                }
                CompactionOutcome.FAILED -> compactionFailed = true
                CompactionOutcome.SKIPPED -> {}
            }
            val tokTap: (Int) -> Unit = { t -> lastPromptTokens = t; onTokens?.invoke(t); onContextTokens?.invoke(t) }
            // 第1轮可能含TOOL_CALL需缓冲后剥离，后续轮次直接流式转发
            val bufferOnly = round == 1 || reactRetryCount > 0
            val deltaCallback: ((String) -> Unit)? = if (onStreamDelta != null) {
                { chunk -> streamBuffer.append(chunk); if (!bufferOnly) onStreamDelta(chunk) }
            } else null

            val responseText = try {
                if (deltaCallback != null) {
                    reactRequestStream(ctx, msgs, temperature, maxTokens, deltaCallback, tokTap, onContextTokens, onCompletionTokens, onCacheHit, enableCache)
                } else {
                    reactRequestSync(ctx, msgs, temperature, maxTokens, tokTap, onContextTokens, onCompletionTokens, onCacheHit, enableCache)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // ReAct 路径单轮请求失败不抛穿整链，
                // 保留已获得的工具输出并让模型收尾（此前这里无保护，一次网络失败即整链崩）。
                Log.w(TAG, "reactChat 第${round}轮请求失败，构造兜底收尾: ${e.message}")
                if (toolOutputs.isNotEmpty()) {
                    onStreamDelta?.invoke("\n\n[工具链中断：后续请求出错 ${e.message ?: e.javaClass.simpleName}，基于已获得的信息继续。]")
                    msgs.add(guideMsg("注意：上一条请求失败（${e.message ?: e.javaClass.simpleName}）。请基于已获得的信息给出结论。"))
                    val fallback = try {
                        if (deltaCallback != null) reactRequestStream(ctx, msgs, temperature, maxTokens, deltaCallback, onTokens, onContextTokens, onCompletionTokens, onCacheHit, enableCache)
                        else reactRequestSync(ctx, msgs, temperature, maxTokens, onTokens, onContextTokens, onCompletionTokens, onCacheHit, enableCache)
                    } catch (e2: kotlinx.coroutines.CancellationException) {
                        throw e2
                    } catch (e2: Exception) {
                        null
                    }
                    val fbStripped = fallback?.let { parser.stripToolCalls(it) }
                    if (!fbStripped.isNullOrBlank()) return fbStripped
                    return toolLoopFallbackSummary(toolOutputs.size, "后续请求反复失败")
                }
                return toolLoopFallbackSummary(toolOutputs.size, "请求失败：${e.message ?: e.javaClass.simpleName}")
            }

            val calls = parser.parseToolCalls(responseText)
            if (calls.isEmpty()) {
                val stripped = parser.stripToolCalls(responseText)
                if (stripped.isNotBlank()) {
                    if (bufferOnly && onStreamDelta != null && streamBuffer.isNotEmpty()) {
                        val cleanStream = parser.stripToolCalls(streamBuffer.toString())
                        if (cleanStream.isNotBlank()) onStreamDelta(cleanStream)
                    }
                    return stripped
                }
                if (round > 1 && toolOutputs.isNotEmpty()) {
                    msgs.add(guideMsg("请根据上述工具执行结果，用中文给出完整的分析和回复。不要调用工具。"))
                    val retry = if (deltaCallback != null) reactRequestStream(ctx, msgs, temperature, maxTokens, deltaCallback, onTokens, onContextTokens, onCompletionTokens, onCacheHit, enableCache)
                    else reactRequestSync(ctx, msgs, temperature, maxTokens, onTokens, onContextTokens, onCompletionTokens, onCacheHit, enableCache)
                    val retryStripped = parser.stripToolCalls(retry)
                    if (bufferOnly && onStreamDelta != null && streamBuffer.isNotEmpty()) {
                        val cleanRetry = parser.stripToolCalls(streamBuffer.toString())
                        if (cleanRetry.isNotBlank()) onStreamDelta(cleanRetry)
                    }
                    if (retryStripped.isNotBlank()) return retryStripped
                    return toolLoopFallbackSummary(toolOutputs.size, "模型未给出最终回复")
                }
                if (reactRetryCount < 2) {
                    reactRetryCount++
                    Log.w(TAG, "reactChat 第${round}轮返回空白，retry=$reactRetryCount，添加引导提示")
                    msgs.add(guideMsg("请根据用户的问题，使用 TOOL_CALL 格式调用合适的工具来获取所需数据。格式示例：TOOL_CALL: {\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}。如果不需要工具，请直接回答。"))
                    continue
                }
                return "[AI 处理完成]"
            }

            val cleanText = parser.stripToolCalls(responseText)
            if (bufferOnly && onStreamDelta != null && streamBuffer.isNotEmpty()) {
                val cleanStream = parser.stripToolCalls(streamBuffer.toString())
                if (cleanStream.isNotBlank()) onStreamDelta(cleanStream)
            }
            val toolCallList = calls.map { pc ->
                ToolCall(id = "react_r${round}_${pc.name}", function = ToolCallFunction(pc.name, pc.rawArguments))
            }

            msgs.add(ChatMessageDto("assistant", JsonPrimitive(cleanText)))

            val pendings = toolCallList.map { tc ->
                val toolName = tc.function.name
                val toolArgs = tc.function.arguments
                tc to (onToolCallRequest?.invoke(toolName, toolArgs) ?: true)
            }
            for ((tc, approved) in pendings) {
                if (approved) onToolCall?.invoke(tc.function.name, tc.id, tc.function.arguments)
            }
            for ((tc, approved) in pendings) {
                val toolName = tc.function.name
                if (!approved) {
                    onToolCall?.invoke(toolName, tc.id, tc.function.arguments)
                    msgs.add(ChatMessageDto("user", JsonPrimitive("用户拒绝了工具 $toolName 的调用，请询问替代方案")))
                    onToolResult?.invoke(toolName, tc.id, "用户已拒绝")
                    continue
                }
                val result = try {
                    toolRegistry?.executeToolCall(tc) ?: "错误：ToolRegistry 不可用"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 工具异常回填为结果（，不打断 ReAct 链
                    "工具执行失败：${e.message ?: e.javaClass.simpleName}"
                }
                val truncated = if (result.length > MAX_TOOL_RESULT_CHARS) result.take(MAX_TOOL_RESULT_CHARS) + "\n\n[结果过长，已截断]" else result
                msgs.add(ChatMessageDto("user", JsonPrimitive("工具 [$toolName] 返回结果：\n$truncated")))
                // 同上：回调传完整结果，保证「库里存的 == 本轮发的」。
                onToolResult?.invoke(toolName, tc.id, truncated)
                toolOutputs.add(truncated)
            }
            // 每轮前做上下文预算守卫（与 executeAndFollowUp 同款，结构安全）
            enforceContextBudget(msgs, TOOL_LOOP_CHAR_BUDGET)
        }
    }

    private suspend fun reactRequestSync(
        ctx: ProviderCallContext,
        msgs: List<ChatMessageDto>, temperature: Float, maxTokens: Int?,
        onTokens: ((Int) -> Unit)?,
        onContextTokens: ((Int) -> Unit)? = null,
        onCompletionTokens: ((Int) -> Unit)? = null,
        onCacheHit: ((Boolean, Int) -> Unit)?,
        enableCache: Boolean
    ): String {
        val req = ChatRequest(ctx.model, msgs, temperature = temperature, maxTokens = maxTokens, tools = null, reasoningEffort = currentReasoningEffort(ctx),
            promptCacheKey = if (ctx.openaiChatCacheKey) ctx.sessionId else null)
        val (resp, usage) = postChat(ctx, req, enableCache)
        if (usage != null) {
            val inUsage = if (usage.promptTokens > 0) usage.promptTokens
                else (usage.totalTokens - usage.completionTokens).coerceAtLeast(0)
            if (inUsage > 0) onTokens?.invoke(inUsage)
            // 上下文占用 = max(total_tokens, prompt+completion)，
            // resolveReportedContextTokens。仍**不加 cached_tokens**（它是 prompt 的子集，
            // 重复加会让窗口占用随缓存命中量抖动）。缓存只经 onCacheHit 进计费统计。
            val ctxTokens = usage.reportedContextTokens()
            if (ctxTokens > 0) onContextTokens?.invoke(ctxTokens)
            if (usage.effectiveCacheHitTokens > 0) onCacheHit?.invoke(true, usage.effectiveCacheHitTokens)
            if (usage.completionTokens > 0) onCompletionTokens?.invoke(usage.completionTokens)
        }
        return resp?.choices?.firstOrNull()?.message?.content ?: ""
    }

    private suspend fun reactRequestStream(
        ctx: ProviderCallContext,
        msgs: List<ChatMessageDto>, temperature: Float, maxTokens: Int?,
        onStreamDelta: ((String) -> Unit),
        onTokens: ((Int) -> Unit)?,
        onContextTokens: ((Int) -> Unit)? = null,
        onCompletionTokens: ((Int) -> Unit)? = null,
        onCacheHit: ((Boolean, Int) -> Unit)?,
        enableCache: Boolean
    ): String {
        val payload = ChatRequest(ctx.model, msgs, temperature = temperature, stream = true,
            streamOptions = com.wy.openx.data.remote.StreamOptions(),
            maxTokens = maxTokens, tools = null, reasoningEffort = currentReasoningEffort(ctx),
            promptCacheKey = if (ctx.openaiChatCacheKey) ctx.sessionId else null)

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // ReAct 走纯文本 streaming：整轮可重试（未输出前），SSE 完整性校验与 [DONE]/error 帧处理与主路径一致。
            val acc = sseCallWithRetry(ctx, payload, enableCache, onStreamDelta)
            if (acc.cacheHitTokens > 0) onCacheHit?.invoke(true, acc.cacheHitTokens)
            acc.promptTokens?.let { onTokens?.invoke(it) }
            if (acc.contextTokens > 0) onContextTokens?.invoke(acc.contextTokens)
            acc.completionTokens?.let { onCompletionTokens?.invoke(it) }
            acc.fullText.toString()
        }
    }
    /**
     * 单条工具结果的投影：超限时保**头 20K + 尾 20K**，全文落盘并给出路径。
     *
     * 为什么不再只 `take(120_000)`：尾部往往是最有价值的部分（编译错误的最后几条、
     * 测试失败汇总、diff 末尾），砍掉尾部等于让模型永远看不到结论。
     * 落盘复用 [ToolOutputOffload]（原子写），失败时退化为「头+尾」内联，不把内容静默丢掉。
     */
    private fun projectToolResult(conversationId: Long, toolName: String, result: String): String {
        if (result.length <= MAX_TOOL_RESULT_CHARS) return result
        val head = result.take(TOOL_RESULT_HEAD_CHARS)
        val tail = result.takeLast(TOOL_RESULT_TAIL_CHARS)
        val ctx = AppContextHolder.context
        val path = ctx?.let {
            runCatching {
                com.wy.openx.data.util.ToolOutputOffload(it)
                    .offload(conversationId, "$toolName-full", result)
            }.getOrNull()
        }
        val meta = buildString {
            append("\n\n[输出过长：共 ").append(result.length).append(" 字符，中间部分已省略")
            if (path != null) append("；完整输出已存 ").append(path)
            append("]\n\n")
        }
        return head + meta + tail
    }

}
