package com.wy.openx.data.util

/**
 * token 粗估（**单一事实来源**）。
 *
 * （转写自 Gemini CLI 的 tokenCalculation.ts）：
 * ASCII 按 4 字符 ≈ 1 token，非 ASCII 按 1.5 token/字。
 *
 * 之前 openx 有两套估算并存，且对中文都偏乐观：
 *  - MainViewModel.estimateContextTokens：中文 1.0 + 英文 /4，最后全局 ×1.1
 *  - ChatRepository 内的同类估算：同样系数
 * 结果是**中文会话的实际占用被系统性低估**，压缩触发得太晚。中文内容越多低估越多——
 * 工具输出里又以中文说明为主，所以这个偏差正好落在最该拦住的地方。
 *
 * 这里只做估算，永远不用 provider 上报的 usage 冒充精确值；有真实 usage 时以真实值为准
 * （见 MainViewModel.maybeAutoCompact：lastInputContext 优先，本类只作兜底与增量补估）。
 */
object TokenEstimator {

    /** 每条消息的协议固定开销（role/分隔符等） */
    private const val PER_MESSAGE_OVERHEAD = 4

    /** 图片按固定值计，不把 base64 当文本算 */
    const val IMAGE_TOKENS = 1200

    /**
     * 单段文本的 token 粗估：ASCII 4 字符 ≈ 1 token，非 ASCII 1.5 token/字。
     * 用整数运算避免浮点累加误差。
     */
    fun textTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var quarters = 0L
        for (ch in text) quarters += if (ch.code <= 127) 1 else 6
        // ceil(quarters / 4)
        return ((quarters + 3) / 4).toInt()
    }

    /**
     * 一批正文的 token 粗估。中文密集的工具输出里 [textTokens] 逐段调用会漏掉段间开销，
     * 这里统一补上 [PER_MESSAGE_OVERHEAD] × 段数。
     */
    fun bodyTokens(text: String): Int =
        if (text.isBlank()) 0 else textTokens(text) + PER_MESSAGE_OVERHEAD

    /**
     * 消息列表的 token 粗估，字段口径必须与真正发出去的请求一致：
     * content + reasoning_content + tool_calls 的 name/arguments。
     *
     * @param toolTokens 工具定义（tools schema）的 token 数——每轮都全量下发，
     *   属于「不随会话增长但永远占着」的固定开销，不计入就会系统性偏松。
     */
    fun messagesTokens(
        contents: List<String>,
        extraTokens: Int = 0,
        toolTokens: Int = 0
    ): Int {
        var total = toolTokens
        for (c in contents) total += bodyTokens(c)
        return (total + extraTokens).coerceAtLeast(0)
    }
}
