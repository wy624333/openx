package com.wy.openx.data.model

/**
 * 剥掉模型回复里的思维链标记。
 *
 * **为什么摘要必须过这一关**：上下文压缩是把整段历史发给模型让它写摘要，
 * 带思考的模型（`<think>` / `<thinking>` / `<reasoning>`）会连内心独白一起返回。
 * 那段独白如果原样写进 `[对话摘要]` 占位消息，就等于**进了模型上下文**——
 * 此后每一轮都把压缩器的 monologue 重发给模型：白烧 token，还会让它把
 * 「我上一步在思考什么」当成对话内容。同理也不能进聊天页给用户看。
 *
 * 处理三种情况：
 * 1. 完整闭合 `<think>…</think>`（可能多个、可能跨行）；
 * 2. **未闭合** `<think>…` —— 流被截断/网关吞掉收尾标签时很常见，
 *    此时必须连同其后内容一起丢弃，否则残缺的标签会留在正文里；
 * 3. 只有开标签没有闭标签，或闭标签在前 —— 一律以「丢弃 thinking 段」为准。
 */
internal fun stripThinkingBlocks(raw: String): String {
    if (raw.isEmpty()) return raw
    // **顺序要紧**：先剥已闭合的块，再处理未闭合的开标签。
    // 反过来会出事——未闭合那条正则 `.*` 是贪婪的，会一路吃到文末，
    // 把闭合标签**之后**的正文（真正的摘要）一起吞掉。
    var s = raw
    // 1) 完整闭合 `<think>…</think>`（可能多个、可能跨行）
    s = THINKING_BLOCK.replace(s, "")
    // 2) 剩下的开标签一律视为「未闭合/自闭合错乱」，连同其后内容一起丢
    s = THINKING_OPEN.replace(s, "")
    // 3) 少数模型输出 <thinking/> 之类空标签自闭合
    s = THINKING_EMPTY_TAG.replace(s, "")
    return s.trim()
}

private val THINKING_BLOCK = Regex(
    """<\s*(thinking|think|reasoning)\s*>.*?<\s*/\s*\1\s*>""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
)
private val THINKING_OPEN = Regex(
    """<\s*(thinking|think|reasoning)\s*>.*""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
)
private val THINKING_EMPTY_TAG = Regex("""<\s*/?\s*(thinking|think|reasoning)\s*/?\s*>""", RegexOption.IGNORE_CASE)
