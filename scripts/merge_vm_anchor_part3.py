# -*- coding: utf-8 -*-
"""合并步骤 2（第三段）：回读锚、清锚、删死代码、SessionSlot 去字段。"""
VM = 'app/src/main/java/com/wy/openx/ui/viewmodel/MainViewModel.kt'
SS = 'app/src/main/java/com/wy/openx/ui/viewmodel/SessionSlot.kt'
src = open(VM).read()

def rep(old, new, n=1, label=''):
    global src
    k = src.count(old)
    assert k == n, f"[{label}] 锚点出现 {k} 次（期望 {n}）：{old[:70]!r}"
    src = src.replace(old, new)
    print(f"  ok {label}")

# ── 8. 删掉已无人使用的派生 getter（判定处已直接读 anchor）──
rep('''    /** ③ 报告点下标锚（由锚派生，不再单独存字段）。0 = 未知。 */
    private val lastUsageMarkIndex: Int get() = usageAnchor?.mark ?: 0

''', '', label='8 删死 getter')

# ── 9. restoreConversationCtx：按显式 cid 建锚（作者的方案）──
rep('''        // 重启后本会话尚无内存记录时，以持久化请求数建立，保证后续请求数累加正确
        if (rec == null && persistedReq > 0) {
            conversationCtx[conversationId] = CtxRecord(input = restoredInput, requestCount = persistedReq)
        }
    }''',
'''        // 重启后本会话尚无内存记录时，以持久化请求数建立，保证后续请求数累加正确
        if (rec == null && persistedReq > 0) {
            conversationCtx[conversationId] = CtxRecord(input = restoredInput, requestCount = persistedReq)
        }
        // 一并恢复**报告锚**，否则该会话锚为 null → effectiveContextTokens() 退回全量估算，
        // 会把上下文高估到 2.5 倍真实值、提前触发压缩。
        //
        // mark 取「回读消息数 - 1」：最后那次请求的 usage 覆盖的是它自己回复之前的历史，
        // 末尾那条助手回复并没被覆盖，交给 delta 补上。刻意**低估一格**——
        // 漏算一条消息的代价远小于多算。
        //
        // ⚠️ 必须用显式 conversationId 查键，不能走 `usageAnchor` getter（那个按
        // currentConversationId 取）：并行时本函数会在「前台还没切到 targetId」时被调进来
        // （启动恢复路径、以及后台会话的观察），用 getter 会读到**别的会话**的锚、
        // 误判成非空而跳过建锚。
        if (usageAnchors[conversationId] == null && restoredInput > 0) {
            val restoredMark = (slotOf(conversationId).messages.size - 1).coerceAtLeast(0)
            usageAnchors[conversationId] = UsageAnchor(restoredInput, restoredMark)
        }
    }''', label='9 回读报告锚')

# ── 10. resetInputContext：锚一并按 cid 清 ──
rep('''        if (forConversation != null) conversationCtx.remove(forConversation)
        uiState = uiState.copy(lastInputContext = 0, sessionRequestCount = 0)''',
'''        if (forConversation != null) {
            conversationCtx.remove(forConversation)
            // 锚必须一并清：新建/清空历史/删会话后旧 mark 仍指向已不存在的消息下标，
            // 留着会让 effectiveContextTokens() 拿旧 reported 叠加到空列表上。
            usageAnchors.remove(forConversation)
        }
        // 只在该会话是前台时才归零显示值：否则清 B 的历史会把 A 的圆环也清零。
        if (forConversation == null || forConversation == _uiState.currentConversationId) {
            uiState = uiState.copy(lastInputContext = 0, sessionRequestCount = 0)
        }''', label='10 清锚按 cid')

open(VM, 'w').write(src)

# ── 11. SessionSlot：lastUsageMarkIndex 已由 usageAnchors 承载，删字段 ──
s2 = open(SS).read()
assert s2.count('    var lastUsageMarkIndex = 0\n') == 1, 'SessionSlot 锚字段锚点异常'
s2 = s2.replace('    var lastUsageMarkIndex = 0\n', '')
open(SS, 'w').write(s2)
print('  ok 11 SessionSlot 删 lastUsageMarkIndex')
print('第三段完成')
