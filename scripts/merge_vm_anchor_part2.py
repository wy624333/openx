# -*- coding: utf-8 -*-
"""合并步骤 2（下半）：请求结束记账 / effectiveContextTokens / 压缩 / 回读锚 / 清锚。"""
VM = 'app/src/main/java/com/wy/openx/ui/viewmodel/MainViewModel.kt'
src = open(VM).read()

def rep(old, new, n=1, label=''):
    global src
    k = src.count(old)
    assert k == n, f"[{label}] 锚点出现 {k} 次（期望 {n}）：{old[:70]!r}"
    src = src.replace(old, new)
    print(f"  ok {label}")

# ── 4. 请求结束：记账优先取锚值（作者口径），显示只在「本轮是前台」时刷 ──
rep('''            // 本次请求最后一轮的输入上下文（onTokens 传真实 prompt_tokens），用于顶部条/压缩判断（当前窗口占用）
            val currentInputContext = if (finalResponseTokens != null) finalResponseTokens else 0
            // 输入 token：取最后一轮 API 真实 prompt_tokens（对齐 RC7），与顶部上下文横条同值
            val perMessageInputTokens = finalResponseTokens
            android.util.Log.i("AutoCompact", "请求结束: currentInputContext=$currentInputContext (responseTokens=$responseTokens)")
            // 仅在拿到真实 usage 时刷新顶部上下文（未取到时保留上一真实值，避免退化成本地估算）
            if (currentInputContext > 0) {
                uiState = uiState.copy(lastInputContext = currentInputContext)
            }
            val prevReq = conversationCtx[conversationId]?.requestCount ?: 0
            val newReq = prevReq + turnRequests
            conversationCtx[conversationId] = CtxRecord(input = currentInputContext, requestCount = newReq)
            uiState = uiState.copy(sessionRequestCount = newReq)''',
'''            // 输入 token：取最后一轮 API 真实 prompt_tokens 裸值（对齐 RC7 消息展示口径）。
            // 顶部圆环/压缩判定**不用**这个值——那条链路走报告锚（真实窗口占用）。
            val perMessageInputTokens = finalResponseTokens
            // 必须走 AppLogStore 而不是 android.util.Log —— 后者只进 logcat、不落日志文件，
            // 导出后看不到，验收无从对账。
            AppLogStore.info(
                "自动压缩",
                "请求结束: 上报锚=${usageAnchors[requestConversationId]?.reported} " +
                    "mark=${usageAnchors[requestConversationId]?.mark} 裸prompt=$responseTokens " +
                    "判定=${effectiveContextTokens(requestConversationId, rq.messages)}"
            )
            // 落库/记账用值：优先报告锚的真实窗口占用；锚缺失（本轮没拿到 usage）才退回裸值兜底。
            // 绝不能用裸 prompt_tokens 落库——否则重启后回读到的占用偏小、自动压缩持续滞后。
            val currentInputContext = usageAnchors[requestConversationId]?.reported
                ?: (if (finalResponseTokens != null) finalResponseTokens else 0)
            val prevReq = conversationCtx[conversationId]?.requestCount ?: 0
            val newReq = prevReq + turnRequests
            conversationCtx[conversationId] = CtxRecord(input = currentInputContext, requestCount = newReq)
            // 并行改造：这两个显示值只在「本轮确实是前台会话」时刷，否则后台请求收尾会把
            // 前台的圆环/请求数改成别的会话的值（就是「忽高忽低」的另一半来源）。
            if (requestConversationId == _uiState.currentConversationId) {
                if (currentInputContext > 0) uiState = uiState.copy(lastInputContext = currentInputContext)
                uiState = uiState.copy(sessionRequestCount = newReq)
            }''', label='4 请求结束记账')

# ── 5. effectiveContextTokens：按 cid 取锚，不再混用全局 reported + 前台 mark ──
rep('''    private fun effectiveContextTokens(msgs: List<ChatMessageModel> = uiState.messages): Int {
        val reported = uiState.lastInputContext
        val mark = lastUsageMarkIndex
        if (reported <= 0 || mark <= 0) return estimateContextTokens(msgs)
        if (mark >= msgs.size) return reported
        val delta = estimateBodiesTokens(msgs.subList(mark, msgs.size))
        return reported + delta
    }''',
'''    private fun effectiveContextTokens(conversationId: Long, msgs: List<ChatMessageModel>): Int {
        val anchor = usageAnchors[conversationId] ?: return estimateContextTokens(msgs)
        if (anchor.reported <= 0 || anchor.mark <= 0) return estimateContextTokens(msgs)
        if (anchor.mark >= msgs.size) return anchor.reported
        val delta = estimateBodiesTokens(msgs.subList(anchor.mark, msgs.size))
        return anchor.reported + delta
    }''', label='5 effectiveContextTokens 按 cid')

# ── 6. maybeAutoCompact：判定与取值都按本轮会话的锚 ──
rep('''        val current = effectiveContextTokens(msgs)
        val threshold = (window * ratio).toInt()
        val src = when {
            uiState.lastInputContext <= 0 || slot.lastUsageMarkIndex <= 0 -> "estimate"
            slot.lastUsageMarkIndex >= msgs.size -> "lastInputContext(报告点)"
            else -> "lastInputContext+增量(${msgs.size - slot.lastUsageMarkIndex}条)"
        }''',
'''        val current = effectiveContextTokens(conversationId, msgs)
        val threshold = (window * ratio).toInt()
        val anchor = usageAnchors[conversationId]
        val src = when {
            anchor == null || anchor.reported <= 0 || anchor.mark <= 0 -> "estimate"
            anchor.mark >= msgs.size -> "报告锚(无增量)"
            else -> "报告锚+增量(${msgs.size - anchor.mark}条)"
        }''', label='6 压缩判定按 cid 取锚')

rep('''            uiState = uiState.copy(lastInputContext = effectiveContextTokens())
            slot.lastUsageMarkIndex = 0''',
'''            if (conversationId == _uiState.currentConversationId) {
                uiState = uiState.copy(lastInputContext = effectiveContextTokens(conversationId, slot.messages))
            }
            usageAnchors.remove(conversationId)''', label='6b 压缩失败清锚')

# ── 7. performCompaction：清锚按 cid，显示只在前台刷 ──
rep('''        uiState = uiState.copy(lastInputContext = estimateContextTokens(compacted))
        // 压缩后这个数是**估算**（还没有新的 provider usage），报告点随之作废，
        // 否则下一轮会拿「旧 usage + 增量」去加，基数是压缩前的量级。
        slot.lastUsageMarkIndex = 0''',
'''        // 压缩后这个数是**估算**（还没有新的 provider usage），报告锚随之作废，
        // 否则下一轮会拿「旧 reported + 增量」去加，基数是压缩前的量级。
        usageAnchors.remove(conversationId)
        if (conversationId == _uiState.currentConversationId) {
            uiState = uiState.copy(lastInputContext = estimateContextTokens(compacted))
        }''', label='7 压缩成功清锚')

open(VM, 'w').write(src)
print('下半 A 完成')
