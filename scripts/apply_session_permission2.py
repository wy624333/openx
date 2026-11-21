# -*- coding: utf-8 -*-
"""会话级权限档 · 下半：setter 改写槽 + 全部消费点改读会话生效值。"""
VM = 'app/src/main/java/com/wy/openx/ui/viewmodel/MainViewModel.kt'
c = open(VM).read()

def rep(old, new, n=1, label=''):
    global c
    k = c.count(old)
    assert k == n, f"[{label}] 锚点出现 {k} 次（期望 {n}）：{old[:70]!r}"
    c = c.replace(old, new)
    print(f"  ok {label}")

# ── 5. setToolPermissionMode：写会话槽，不再改全局 settings ──
rep('''    fun setToolPermissionMode(mode: String) {
        if (uiState.settings.toolPermissionMode == mode) return
        val newSettings = uiState.settings.copy(toolPermissionMode = mode)
        uiState = uiState.copy(settings = newSettings)
        com.wy.openx.tools.AppContextHolder.currentSettings = newSettings
        viewModelScope.launch { settingsStore.save(newSettings) }
        showToast("工具权限：${toolPermissionLabel(mode)}")
    }''',
'''    /**
     * 切换**当前会话**的工具权限档（不再改全局设置）。
     *
     * 为什么不落 DataStore：会话级选择是临场的工作方式（这个会话想全自动跑、
     * 那个会话想逐步确认），存成全局会让「切会话」和「改设置」两件事耦合。
     * 全局默认档仍在设置页改，未覆盖过的会话跟随它（见 effectivePermissionMode）。
     */
    fun setToolPermissionMode(mode: String) {
        val cid = uiState.currentConversationId
        if (effectivePermissionMode(cid) == mode) return
        editSlot(cid) { toolPermissionModeOverride = mode }
        showToast("本会话工具权限：${toolPermissionLabel(mode)}")
    }''', label='5 setToolPermissionMode 改槽')

# ── 6. setReasoningEffort：同上 ──
rep('''    fun setReasoningEffort(effort: String) {
        if (uiState.settings.reasoningEffort == effort) return
        val newSettings = uiState.settings.copy(reasoningEffort = effort)
        uiState = uiState.copy(settings = newSettings)
        com.wy.openx.tools.AppContextHolder.currentSettings = newSettings
        viewModelScope.launch { settingsStore.save(newSettings) }
        showToast("思考强度：${reasoningEffortLabel(effort)}")
    }''',
'''    /** 切换**当前会话**的思考强度（同 setToolPermissionMode，不落全局设置）。 */
    fun setReasoningEffort(effort: String) {
        val cid = uiState.currentConversationId
        if (effectiveReasoningEffort(cid) == effort) return
        editSlot(cid) { reasoningEffortOverride = effort }
        showToast("本会话思考强度：${reasoningEffortLabel(effort)}")
    }''', label='6 setReasoningEffort 改槽')

# ── 7. 主链路请求：下发会话合并版 settings ──
rep('''            chatRepository.sendMessage(
                uiState.settings, finalUserText, imageBase64 = imageBase64,''',
'''            chatRepository.sendMessage(
                // 会话合并版：权限档/思考强度按本会话生效值下发（其余字段仍是全局设置）。
                settingsFor(requestConversationId), finalUserText, imageBase64 = imageBase64,''',
    label='7 主链路 settingsFor')

# ── 8. 工具下发档位（协程内）：按本会话生效值 ──
rep('''                    agentMode = rq.agentMode,
                    permissionMode = uiState.settings.toolPermissionMode''',
'''                    agentMode = rq.agentMode,
                    permissionMode = effectivePermissionMode(requestConversationId)''',
    label='8 getApiTools 按会话档位')

# ── 9. 标题生成 / 压缩：有 cid 的辅助请求同样按会话 ──
rep('''        val result = chatRepository.sendMessage(
            uiState.settings, prompt,
            forcedProvider = resolveSpecialModel("title")''',
'''        val result = chatRepository.sendMessage(
            settingsFor(cid), prompt,
            forcedProvider = resolveSpecialModel("title")''', label='9.1 标题按会话')
rep('''        val result = chatRepository.sendMessage(
            uiState.settings, userPrompt,
            onTokens = {},''',
'''        val result = chatRepository.sendMessage(
            settingsFor(conversationId), userPrompt,
            onTokens = {},''', label='9.2 压缩按会话')

# ── 10. 审批判定：必须按「发起本轮的会话」的档位 ──
rep('''        if (uiState.settings.toolPermissionMode != "confirm") return null''',
'''        // 按**发起本轮的会话**的档位判定：用全局 settings 或当下前台档，
        // 会让「A 选了弹窗、B 选了全自动」时 B 的审批口去问 A（或反过来该问不问）。
        if (effectivePermissionMode(requestConversationId) != "confirm") return null''',
    label='10.1 buildToolApprover')
rep('''            when (uiState.settings.toolPermissionMode) {''',
'''            when (effectivePermissionMode(requestConversationId)) {''', label='10.2 workflowGate')
rep('''            agentMode = uiState.agentMode,
            permissionMode = uiState.settings.toolPermissionMode
        )
        return estimateToolDefTokens(tools)''',
'''            agentMode = uiState.agentMode,
            permissionMode = uiState.toolPermissionMode
        )
        return estimateToolDefTokens(tools)''', label='10.3 兜底估算用投影值')

open(VM, 'w').write(c)
print('下半完成')
