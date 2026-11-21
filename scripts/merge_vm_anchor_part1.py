# -*- coding: utf-8 -*-
"""
合并步骤 2（上半）：把作者的 usageAnchors 机制移植进我们的 MainViewModel，
并补上并行必需的 conversationId 参数。

作者方案 vs 我们 P1 方案的差别：
  我们：lastUsageMarkIndex 委托进 SessionSlot，reported 留在 uiState 全局 → 两者分家（忽高忽低的根因）
  作者：UsageAnchor(reported, mark) 成对存进 usageAnchors[cid] → 结构上不可能错配
所以合并 = 采纳作者的，删掉我们的委托。
"""
VM = 'app/src/main/java/com/wy/openx/ui/viewmodel/MainViewModel.kt'
src = open(VM).read()


def rep(old, new, n=1, label=''):
    global src
    k = src.count(old)
    assert k == n, f"[{label}] 锚点出现 {k} 次（期望 {n}）：{old[:70]!r}"
    src = src.replace(old, new)
    print(f"  ok {label}")


# ── 1. 删掉我们的 lastUsageMarkIndex 槽位委托，换成作者的成对锚 ──
rep('''    /**
     * ③「上一次 provider 真实 usage 的报告点」在 uiState.messages 里的下标锚。
     * 0 = 本次进程内还没有可用的报告点（切会话/重启后），此时上下文估算退回全量估算。
     * 它是 messages 的下标，所以必须随会话走。
     */
    private var lastUsageMarkIndex: Int
        get() = slot().lastUsageMarkIndex
        set(v) { slot().lastUsageMarkIndex = v }''',
'''    /**
     * ③ provider 真实 usage 的报告锚。**必须成对读写**：`reported` 描述的是 `mark` 那一刻的
     * 上下文，`effectiveContextTokens()` 靠这两者算出「真实值 + 其后新增」。
     *
     * 分成两处存放正是 09-28 那次「上下文圆环忽高忽低」的根因：mark 进了会话槽、
     * reported 留在 uiState 全局，并发时 A 上报的 reported 会配上 B 的 mark 一起算。
     */
    private data class UsageAnchor(val reported: Int, val mark: Int)

    /**
     * 报告锚**按会话隔离**（作者 2.3.7 的方案）。原先是单个全局变量，切到别的会话后
     * 仍指着上一个会话的 (reported, mark)，于是对当前会话做压缩判定时会拿**别的会话**的
     * 真实值当基数 → 提前触发压缩，或该压却不压。
     */
    private val usageAnchors = java.util.concurrent.ConcurrentHashMap<Long, UsageAnchor>()

    /** 当前（前台）会话的锚；null = 本会话还没有可用报告点。 */
    private val usageAnchor: UsageAnchor? get() = usageAnchors[_uiState.currentConversationId]

    /** ③ 报告点下标锚（由锚派生，不再单独存字段）。0 = 未知。 */
    private val lastUsageMarkIndex: Int get() = usageAnchor?.mark ?: 0

    /**
     * 原子地推进报告锚。[tokens] 是 provider 对 [mark] 那一刻上下文的真实上报。
     * 任何改动上下文占用 / 报告点的地方都必须走这里，否则会把「真实值」和
     * 「其后新增」的起点拆开。
     *
     * ⚠️ [conversationId] 必须由调用方显式传入**本轮请求的会话**。多会话并行时
     *   `currentConversationId` 是「当下前台」，后台会话的 usage 回来若按前台记账，
     *   会把 A 的真实占用记到 B 名下（作者单前台模型下没有这个窗口，放开并行后就有了）。
     */
    private fun advanceUsageAnchor(tokens: Int, mark: Int, conversationId: Long) {
        if (tokens <= 0) return
        usageAnchors[conversationId] = UsageAnchor(tokens, mark)
        // 只有当它确实是前台时才刷新投影：否则后台会话的 usage 会把前台圆环数字带跑。
        if (conversationId == _uiState.currentConversationId) {
            uiState = uiState.copy(lastInputContext = tokens)
        }
    }''', label='1 成对锚 + advanceUsageAnchor(cid)')

# ── 2. historyMark 不再需要（锚改为在「上报时刻」推进），删掉避免未用告警 ──
rep('''            // ③ 的「报告点锚」：本次请求发起时 uiState.messages 里**已有**的消息条数。
            // 之后 usage 上报回来的 prompt_tokens 覆盖的正是这 historyMark 条历史，
            // 下一轮再估上下文时，只要在这条锚之后做增量补估即可（provider 真实值 + 新增内容估算）。
            val historyMark = rq.messages.size - 1

''', '', label='2 删 historyMark')

# ── 3. onTokens 拆分：计费走 onTokens，窗口占用走作者的 onContextTokens ──
rep('''                onTokens = { tokens ->
                    responseTokens = tokens
                    if (tokens > 0) {
                        accumulatedInput += tokens
                        // usage 一上报即更新顶部上下文并落库：即使中途打断/退出，重启后仍读到真实值
                        uiState = uiState.copy(lastInputContext = tokens)
                        // ③ 记录这次真实 usage 的报告点：它覆盖的是本轮 historyMark 条历史，
                        // 下一轮估算 = 该真实值 + 报告点之后新增内容的估算（对齐 OmniBot 的
                        // providerReportedTokens + tokensAddedAfterThatPoint）。
                        // 置 0 = 未知（本次请求没能对应到确定的历史切片），退回全量估算。
                        rq.lastUsageMarkIndex = historyMark
                        if (conversationId >= 0) {
                            viewModelScope.launch {
                                conversationRepository.updateLastInputTokens(conversationId, tokens)
                            }
                        }
                    }
                },''',
'''                // 计费口径：裸 prompt_tokens（缓存命中部分另记 cachedInputTokens，不重复计费）
                onTokens = { tokens ->
                    responseTokens = tokens
                    if (tokens > 0) accumulatedInput += tokens
                },
                // 窗口占用口径（作者 2.3.7 新增回调）：max(total, prompt+completion)，
                // 且**绝不加 cached_tokens**——实测 prompt+completion==total、cached 是 prompt
                // 的子集，重复加会让占用随缓存命中量剧烈抖动（命中 211456 报 424465）。
                onContextTokens = { tokens ->
                    if (tokens > 0) {
                        // 锚取「此刻该会话的消息数」而不是请求发起前的固定值：工具循环里
                        // 每一轮 usage 覆盖的历史都比上一轮多，用固定锚会让 reported 与 delta
                        // 起点错配、判定值凭空少掉一个缓存量级。
                        // 并行改造：按 requestConversationId 记账，且只在该会话是前台时刷投影。
                        advanceUsageAnchor(tokens, rq.messages.size, requestConversationId)
                        if (conversationId >= 0) {
                            viewModelScope.launch {
                                conversationRepository.updateLastInputTokens(conversationId, tokens)
                            }
                        }
                    }
                },''', label='3 onTokens 拆分 + onContextTokens')

open(VM, 'w').write(src)
print('上半完成')
