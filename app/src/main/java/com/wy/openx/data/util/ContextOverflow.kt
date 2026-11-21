package com.wy.openx.data.util

/**
 * 「提示词/请求过大」的错误识别。转写自 OmniBot 的 `AgentContextOverflow`
 * （转自 pi-mono 的 `utils/overflow.ts`，MIT）。
 *
 * ## 为什么要它
 * 上游拒绝超长请求时，各家文案五花八门，而且**状态码不可靠**：
 * 有的回 400，有的回 413，有的回 422，还有一批中转网关笼统回 **500**。
 * 靠状态码判断既会漏（500）又会误伤（429 限流、503 不可用都不是体积问题）。
 * 所以这里用「状态码白名单 + 错误文案模式表」双条件，宁可漏判也不误判：
 * 漏判的后果是多压缩一次；误判的后果是把限流当超长、疯狂压缩。
 *
 * ## 与 5xx 的关系
 * [isOverflow] 只在 400/413/422 上生效，**500 不算**。这条边界是刻意保留的：
 * 500 基本都是服务端/网关侧故障（请求体超网关上限就是这一类），拿它去触发
 * 「压缩后重试」只能白等一轮。所以 5xx 走的是退避重试，而不是压缩重试。
 */
object ContextOverflow {

    /** 可能代表「请求过大」的状态码。刻意不含 5xx。 */
    private val CANDIDATE_STATUS = setOf(400, 413, 422)

    /** 明确**不是**体积问题的信号：限流与服务不可用。 */
    private val NOT_OVERFLOW = listOf(
        Regex("^(Throttling error|Service unavailable):", RegexOption.IGNORE_CASE),
        Regex("rate limit", RegexOption.IGNORE_CASE),
        Regex("too many requests", RegexOption.IGNORE_CASE)
    ).map { it }

    /** 各家厂商的「提示词过长」文案。 */
    private val OVERFLOW = listOf(
        "prompt is too long", "request_too_large", "input is too long for requested model",
        "exceeds the context window",
        "exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))",
        "input token count.*exceeds the maximum", "maximum prompt length is \\d+",
        "reduce the length of the messages", "maximum context length is \\d+ tokens",
        "exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?",
        "input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)",
        "exceeds the limit of \\d+", "exceeds the available context size",
        "greater than the context length", "context window exceeds limit", "exceeded model token limit",
        "too large for model with \\d+ maximum context length",
        "prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?",
        "model_context_window_exceeded", "prompt too long; exceeded (?:max )?context length",
        "range of input length should be", "context[_ ]length[_ ]exceeded",
        "too many tokens", "token limit exceeded",
        "prompt exceeds max length", "input length [\\d,]+ exceeds the maximum length [\\d,]+",
        // 中转/自建网关常见的无信息体
        "^(4(?:00|13|22))\\s*(?:status code)?\\s*\\(no body\\)"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /**
     * 明确是「网关/中转侧瞬时故障」的信号：账号池耗尽、上游过载、上游返回错误。
     *
     * ## 为什么要单列这一组
     * 「大请求体 + 网关类状态码 ⇒ 体积超限」这个启发式（见 ChatRepository）有个致命盲区：
     * 502/504 既可能是网关按体积拒收，也可能是**上游/中转临时故障**。二者的响应体长得完全不同——
     * 前者是网关自己吐的裸页面（无 JSON）；后者是**已经穿透到上游**才拿到的结构化 JSON 错误体
     * （`{"error":{"message":"没有可用账号","type":"upstream_error"}}`）。
     *
     * 既然响应里带着上游的结构化错误，就证明请求**越过了网关入口的体积检查**，绝不可能是体积问题。
     * 把这种临时故障误判成超限，代价是把「等一会儿重试就好」升级成**不可逆的强制压缩**（丢历史）。
     *
     * 典型来源：new-api / one-api 中转的账号池轮询（没有可用账号、分组上游负载已饱和）。
     */
    private val TRANSIENT_GATEWAY = listOf(
        "没有可用账号", "无可用账号", "no available account", "no account available",
        "no available channel", "无可用渠道", "当前分组上游负载已饱和", "上游负载已饱和",
        "账号池", "channel (?:is )?exhausted", "all channels are (?:down|unavailable)",
        "upstream_error", "upstream error", "upstream request failed", "upstream failed",
        "service temporarily unavailable", "temporarily overloaded"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /**
     * 诊断文本是否是「网关/中转瞬时故障」。
     *
     * 与 [isOverflow] 不同，本函数**不限定状态码白名单**：上游故障可能是 502/503/504/500，
     * 甚至被中转笼统包成 400。只看响应文本里有没有明确的瞬时故障特征。
     *
     * @param statusCode HTTP 状态码；null 表示非 HTTP 失败。
     * @param diagnostic 错误原因 + 响应体正文。
     */
    fun isTransientGatewayError(statusCode: Int?, diagnostic: String): Boolean {
        // 体积问题只会由 4xx/5xx 触发；2xx/3xx 不该走这条路径（防调用方误传）。
        if (statusCode != null && statusCode < 400) return false
        val text = diagnostic.take(4000)
        return TRANSIENT_GATEWAY.any { it.containsMatchIn(text) }
    }

    /**
     * @param statusCode HTTP 状态码；null 表示非 HTTP 失败（如本地异常）。
     * @param diagnostic 错误原因 + 响应体正文。
     */
    fun isOverflow(statusCode: Int?, diagnostic: String): Boolean {
        if (statusCode == null) return false
        if (statusCode !in CANDIDATE_STATUS) return false
        val text = diagnostic.take(4000)
        // 瞬时故障优先排除：带结构化上游错误体的响应，绝不是体积问题。
        if (TRANSIENT_GATEWAY.any { it.containsMatchIn(text) }) return false
        if (NOT_OVERFLOW.any { it.containsMatchIn(text) }) return false
        return OVERFLOW.any { it.containsMatchIn(text) }
    }
}
