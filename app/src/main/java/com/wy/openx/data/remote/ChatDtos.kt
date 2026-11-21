package com.wy.openx.data.remote

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.wy.openx.tools.ToolCall
import com.wy.openx.tools.ToolDef
import kotlin.jvm.Transient

data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ChatMessageDto>,
    @SerializedName("temperature") val temperature: Float = 0.7f,
    @SerializedName("stream") val stream: Boolean? = null,
    @SerializedName("stream_options") val streamOptions: StreamOptions? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    @SerializedName("tools") val tools: List<ToolDef>? = null,
    @SerializedName("tool_choice") val toolChoice: String? = null,
    /**
     * 思考强度。null 表示不发送该字段（"模型默认"档）——避免不支持的网关因未知参数报错。
     * OpenAI 兼容网关识别 `reasoning_effort`（low/medium/high 等）。
     */
    @SerializedName("reasoning_effort") val reasoningEffort: String? = null,
    /**
     * 缓存 shard 路由键（prompt_cache_key）。仅 OpenAI Chat Completions 兼容服务
     * （如 DeepInfra/Cerebras）支持；openx 由提供商开关 openaiChatCacheKey 控制，
     * 关闭时传 null 不发该字段。
     */
    @SerializedName("prompt_cache_key") val promptCacheKey: String? = null
)

// OpenAI 流式协议：请求 include_usage 后，最后一个 chunk 的 usage 才返回 token 统计
data class StreamOptions(
    @SerializedName("include_usage") val includeUsage: Boolean = true
)

data class ChatMessageDto(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: JsonElement?,
    @SerializedName("tool_call_id") val toolCallId: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    /**
     * DeepSeek 思考模式的 reasoning_content：带 tool_calls 的 assistant 消息回传时必须携带该字段
     * 
     */
    @SerializedName("reasoning_content") val reasoningContent: String? = null,
    /**
     * **仅进程内使用**：本条消息在 `MainViewModel.slot.messages` 里的来源消息 id（DB 主键）。
     *
     * 存在的唯一理由：让「工具循环内就地压缩」的折叠边界能被**精确**回传给 VM 去落库。
     * 一条 slot 消息在请求里会展开成多条 DTO（工具消息拆成 assistant(tool_calls) + tool(result)），
     * 故「折了多少条 DTO」与「该折多少条 slot 消息」毫无关系——只能靠 id 定位。
     * 用 `contextSummaryCutoffEntryDbId`（消息身份而非数组下标）定位 cutoff。
     *
     * `@Transient` ⇒ Gson 默认 Excluder 跳过 transient 字段，**不会**发给任何网关。
     */
    @Transient val srcMsgId: Long? = null
)

data class ChatResponse(
    @SerializedName("choices") val choices: List<Choice>?,
    @SerializedName("usage") val usage: Usage? = null
)

data class PromptTokensDetails(
    @SerializedName("cached_tokens") val cachedTokens: Int = 0
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int = 0,
    @SerializedName("completion_tokens") val completionTokens: Int = 0,
    @SerializedName("total_tokens") val totalTokens: Int = 0,
    @SerializedName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int = 0,
    @SerializedName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
    @SerializedName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    @SerializedName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null
) {
    /**
     * 前缀缓存**命中**的 prompt token 数，兼容三种主流命名：
     * - DeepSeek 原生：`prompt_cache_hit_tokens`
     * - Anthropic 风格：`cache_read_input_tokens`（缓存读取）
     * - OpenAI 风格：`prompt_tokens_details.cached_tokens`
     *
     * 三者按优先级取第一个非 0 值。它们表示的是同一件事，正常情况下只会出现一种；
     * 用 when 而不是相加，避免某个网关同时返回两个字段时命中量被算成两倍。
     *
     * **不要混入 [cacheCreationInputTokens]**：那是「写入缓存」的 token（首次建立缓存），
     * 不是命中。混进来会让命中率虚高，失去参考意义。
     */
    val effectiveCacheHitTokens: Int
        get() = when {
            promptCacheHitTokens > 0 -> promptCacheHitTokens
            cacheReadInputTokens > 0 -> cacheReadInputTokens
            else -> promptTokensDetails?.cachedTokens ?: 0
        }

    /**
     * 本次请求的上下文占用（ `resolveReportedContextTokens`）：
     * `max(total_tokens, prompt_tokens + completion_tokens)`。
     *
     * 取两者较大而非只用 prompt_tokens：有的 provider 会把本轮输出算进 total 但不在
     * prompt_tokens 里（session-338 实测 prompt=219821/completion=526/total=220347），
     * 只看 prompt 会漏掉这轮输出。两者都缺时返回 0，由调用方回退本地估算。
     *
     * **绝不能加 [effectiveCacheHitTokens]**：实测 prompt+completion==total，
     * cached_tokens 是 prompt 的子集，重复加会让「窗口占用」随缓存命中量剧烈抖动
     * （命中 211456 报 424465、只命中 10240 报 230061，而真实占用始终约 21 万）。
     */
    fun reportedContextTokens(): Int {
        val total = totalTokens.takeIf { it >= 0 }
        val promptPlusCompletion = promptTokens.takeIf { it >= 0 }?.let { prompt ->
            (prompt.toLong() + completionTokens.coerceAtLeast(0).toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return listOfNotNull(total, promptPlusCompletion).maxOrNull() ?: 0
    }
}

data class Choice(
    @SerializedName("message") val message: ResponseMessage?,
    @SerializedName("delta") val delta: Delta? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class ResponseMessage(
    @SerializedName("role") val role: String?,
    @SerializedName("content") val content: String?,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerializedName("reasoning") val reasoning: String? = null,
    @SerializedName("reasoning_content") val reasoningContent: String? = null
)

data class Delta(
    @SerializedName("content") val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<com.wy.openx.tools.ToolCallDelta>? = null
)
