package com.wy.openx.data.util

object ChatUtils {

    /**
     * thinking 标签的正则（**预编译常量**，绝不要在函数内 `toRegex()`）。
     *
     * 踩过的坑：这些正则原先在 `removeThinkingContent` / `extractThinkingContent` **函数体内**
     * 每次调用都 `toRegex()` 重新编译。而 `groupMessages` 对**每条** assistant 消息要调两次
     * `extractThinkingContent`（取思考、取正文），且它在每次重组（流式每帧）都会重跑整个列表 ——
     * 一个几百条消息、含大段工具输出的会话，每帧要编译上千个正则再全文扫描，
     * 直接表现为「发消息后聊天页显示很慢，等一会才显示」。
     *
     * 这些 pattern 是无状态的，编译一次即可全程复用。正则本身语义**未改动**。
     */
    private const val OPEN_TAG = "<think(?:ing)?(?::[A-Za-z0-9_\\-]*)?>"
    private const val CLOSE_TAG = "</think(?:ing)?(?::[A-Za-z0-9_\\-]*)?>"

    /** 贪婪匹配（`removeThinkingContent` 用）：把开标签到最后一个闭标签之间整段剥掉。 */
    private val THINK_GREEDY = "$OPEN_TAG.*?($CLOSE_TAG|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL)

    /** 非贪婪捕获（`extractThinkingContent` 用）：逐个 `<thinking>…</thinking>` 块取出内容。 */
    private val THINK_CAPTURE = "$OPEN_TAG([\\s\\S]*?)($CLOSE_TAG|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL)

    /** 孤立的闭合标签（某些网关只发 `</think:hash>` 不带开标签，属噪声）。 */
    private val LONE_CLOSE_TAG = Regex(CLOSE_TAG)

    private val SEARCH_GREEDY = "<search>.*?(</search>|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL)

    /**
     * 该模型是否**要求历史里保留完整思考（reasoning）**，从而不该剥 `<thinking>`。
     *
     * （`OpenAIAdapter` 对 assistant 消息的处理）：DeepSeek 思考模式
     * 要求带 tool_calls 的 assistant 消息回传 `reasoning_content`，缺失时 API 报 400；其余模型
     * 思考为空则不回传。openx 的历史把思考内联在 content 的 `<thinking>` 里，故对这类模型
     * **不剥**（等价于保留回传），其余一律剥掉以避免污染与多余 token。
     *
     * 判据同时覆盖「模型名」与「已配置的提供商标识」，避免只配了 baseUrl 而模型名不带关键字时漏判。
     */
    fun modelKeepsThinking(model: String): Boolean {
        val m = model.lowercase()
        return "deepseek" in m ||
            "reasoner" in m ||
            "thinking" in m ||
            "o1-" in m || "o3-" in m || "o4-" in m
    }

    /** 移除 content 中的 thinking 标签及内容（含未闭合情况），并移除 search 标签 */
    fun removeThinkingContent(content: String): String {
        // 兼容三种标记形式：
        // - `<thinking>` / `<think>`（OpenAI/Claude 风格）
        // - `<think:hash>` / `<thinking:hash>`（腾讯混元等网关带随机后缀，如 `</think:6124c78e>`）
        // - 孤立的闭合标签（某些网关只发 `</think:hash>` 不带开标签，属噪声）
        return content
            .replace(THINK_GREEDY, "")
            .replace(LONE_CLOSE_TAG, "")
            .replace(SEARCH_GREEDY, "")
            .trim()
    }

    /**
     * 提取 think 标签内的内容（用于 DeepSeek 的 reasoning_content 等）
     * 兼容流式输出中尚未闭合的 `<thinking>` 标签，保证思考过程可即时展示
     * @param content 包含 think 标签的内容
     * @return Pair(移除 think 标签后的内容, think 标签内的内容)
     */
    fun extractThinkingContent(content: String): Pair<String, String> =
        extractThinkingContent(content, cacheable = true)

    /**
     * @param cacheable 内容是否会稳定下来。**流式中那条每帧都在变**，缓存它毫无收益却会把
     *   历史消息的条目全挤出去（一次流式就有几十上百个中间态），导致每帧全量重算。
     *   故正在流式的那一条传 false，直接算不缓存。
     */
    fun extractThinkingContent(content: String, cacheable: Boolean): Pair<String, String> {
        if (!cacheable || content.length > EXTRACT_CACHE_MAX_LEN) {
            return doExtractThinkingContent(content)
        }
        // 以 content 为键缓存：groupMessages 每帧对**每条** assistant 消息调两次本函数
        // （取思考 + 取正文），而流式期间只有最后那条在变、历史消息 content 完全不变。
        // 缓存后历史条目 O(1) 命中，避免每帧重复全文正则扫描（长会话每帧上千次扫描是
        // 卡顿主因之一）。Pair 不可变、同入同出，缓存同一实例安全。
        synchronized(extractCache) {
            extractCache[content]?.let { return it }
            val result = doExtractThinkingContent(content)
            extractCache[content] = result
            return result
        }
    }

    private fun doExtractThinkingContent(content: String): Pair<String, String> {
        // 超长内容直接当正文，不扫 think 标签。
        //
        // 为什么必须设这道闸：下面一旦命中 think 标签就要分配 `StringBuilder(content.length)`
        // 的副本，加上 thinking 本身，单次处理会同时存在 2〜3 份与 content 等长的字符串。
        // content 是几十 MB 的工具转储时，光这一步就吃掉上百 MB——真机 OOM 现场正是
        // 崩在本函数的 StringBuilder.append → Arrays.copyOf 上（GC 后只剩 3.7MB，连 1136 字节都申请不到）。
        // 20 万字≈几百页，正常回复与思考链远远到不了；到了这个量级的内容本来就是粘贴的文件或转储，
        // 拆不拆思考块对可读性没意义，而代价是把堆顶穿。
        if (content.length > MAX_THINK_SCAN_CHARS) return Pair(content, "")
        // 单遍扫描同时产出「思考」与「剔除后的正文」。
        //
        // 旧写法是 findAll（一遍）+ replace(THINK_CAPTURE)（又一遍 + 全量拷贝）
        // + replace(LONE_CLOSE_TAG)（第三遍 + 再一次全量拷贝）。历史消息有缓存兜着，但
        // **正在流式的那一条每帧内容都在变、必然 miss**，于是每 150ms 就要对整篇思考
        // 文本跑三遍全文扫描并分配两份副本——这才是长思考链下整个页面卡顿的直接来源。
        val thinking = StringBuilder()
        // rest **延迟创建**：没有 think 标签时（绝大多数回复）根本用不上它。
        // 原先无条件 `StringBuilder(content.length)` + 全文 append，等于凭空多一份与
        // content 等长的副本，长会话里正是这一份把已经吃紧的堆顶穿（崩溃时 GC 后只剩 2MB）。
        var rest: StringBuilder? = null
        var last = 0
        var firstMatch = true
        for (m in THINK_CAPTURE.findAll(content)) {
            if (!firstMatch) thinking.append('\n')
            firstMatch = false
            // 用 groups[1] 而非 groupValues[1]：后者会把**整段匹配**（group 0，即
            // `<thinking>…</thinking>` 全长）一并拷成 String，而我们只要捕获组，白白多一份等长副本。
            thinking.append(m.groups?.get(1)?.value.orEmpty().trim())
            val sb = rest ?: StringBuilder(content.length).also { rest = it }
            sb.append(content, last, m.range.first)
            last = m.range.last + 1
        }
        if (rest == null) {
            // 无 think 标签：content 本身就是正文。trim() 在两端无空白时返回 this（零拷贝）。
            if (!LONE_CLOSE_TAG.containsMatchIn(content)) return Pair(content.trim(), "")
            return Pair(content.replace(LONE_CLOSE_TAG, "").trim(), "")
        }
        rest.append(content, last, content.length)
        // 孤立闭合标签只会落在未被匹到的区间里（如一个开标签后面跟了两个闭标签）
        val stripped = if (LONE_CLOSE_TAG.containsMatchIn(rest)) rest.toString().replace(LONE_CLOSE_TAG, "") else rest.toString()
        return Pair(stripped.trim(), thinking.toString())
    }

    /**
     * 超过此长度的 content 不做 think 标签解析（只当正文），避免双份等长副本把堆顶穿。
     *
     * 取 100 万字：think 解析峰值约 3 份等长字符串，即此值约 6MB——完全可控；
     * 而真危险的是几十 MB 的工具转储（那边由 `clampToolResult` 的 20 万字在入口处先挡了）。
     * 正常回复与思考链远到不了这里，只有「粘贴了整个文件」这类异常形态才会命中。
     */
    private const val MAX_THINK_SCAN_CHARS = 1_000_000

    /**
     * 缓存条目数上限（次要约束，真正的闸门是下面的字符预算）。
     */
    private const val EXTRACT_CACHE_MAX_ENTRIES = 256

    /**
     * 缓存**总字符预算**：key（原文）+ 正文 + 思考三份都算在内。
     *
     * 只有条目数上限是不够的——本条就踩了：512 条 × 单条 200KB × 3 份 ≈ 300MB，
     * 直接顶穿 256MB 堆，崩在 `StringBuilder(content.length)` 上（只申请 9KB 也失败，
     * 因为 GC 后只剩 1.8MB）。这里按字符总量封顶，与 MarkdownParseCache 同一套策略。
     */
    private const val EXTRACT_CACHE_TOTAL_CHARS = 1_500_000

    /** 超过此长度的 content 不缓存（避免极长工具输出堆积占用内存）。 */
    private const val EXTRACT_CACHE_MAX_LEN = 64_000

    /** accessOrder=true 的 LRU；get/put 均会改结构，故调用方必须持锁。 */
    private val extractCache = object : LinkedHashMap<String, Pair<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?): Boolean {
            if (size > EXTRACT_CACHE_MAX_ENTRIES) return true
            // LinkedHashMap 不记总权重，超预算时在这里从最旧的开始踢。accessOrder=true
            // 保证迭代序就是「最久未用在前」，踢掉的一定是冷数据。
            while (totalChars() > EXTRACT_CACHE_TOTAL_CHARS && isNotEmpty()) {
                val it = iterator()
                it.next()
                it.remove()
            }
            return false
        }

        /** 三份字符串（原文 + 正文 + 思考）的字符总数。 */
        private fun totalChars(): Long = entries.sumOf {
            it.key.length.toLong() + it.value.first.length + it.value.second.length
        }
    }
}
