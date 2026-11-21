# -*- coding: utf-8 -*-
"""
把「工具权限档」与「思考强度」改成会话级（每个会话可不同）。

设计（关键：override 语义，不是拷贝语义）
  SessionSlot 存 `*Override: String?`，null = 跟随全局默认。
  生效值 = slot.override ?: settings.x，在 projectOnto 里**派生**，
  因此用户在设置页改全局默认档时，**没手动切过的会话会跟着变**；
  切过的会话保留自己的选择。若改成「建槽时拷贝一份」，老会话就永远不跟全局走了。

  同步方向：syncFromUi **不搬**这两个派生字段（搬了会把「跟随全局」误固化成永久覆盖）。

幂等保护：任一锚点匹配数不符即中止，不写文件。
"""
SS = 'app/src/main/java/com/wy/openx/ui/viewmodel/SessionSlot.kt'
CS = 'app/src/main/java/com/wy/openx/ui/state/ConsoleUiState.kt'
VM = 'app/src/main/java/com/wy/openx/ui/viewmodel/MainViewModel.kt'


def rep(path, old, new, n=1, label=''):
    c = open(path).read()
    k = c.count(old)
    assert k == n, f"[{label}] {path} 锚点出现 {k} 次（期望 {n}）：{old[:70]!r}"
    open(path, 'w').write(c.replace(old, new))
    print(f"  ok {label}")


# ═════════ 1. SessionSlot：两个覆盖值 + 投影派生 ═════════
rep(SS, '    var agentMode = "build"\n',
'''    var agentMode = "build"

    /**
     * 本会话的工具权限档**覆盖值**；null = 跟随全局默认 `settings.toolPermissionMode`。
     * 生效值在 [projectOnto] 里派生，别在这里直接读它当生效值。
     */
    var toolPermissionModeOverride: String? = null

    /** 本会话的思考强度**覆盖值**；null = 跟随全局默认 `settings.reasoningEffort`。 */
    var reasoningEffortOverride: String? = null
''', label='1.1 SessionSlot 两个 override 字段')

rep(SS, '        pendingQuestion = pendingQuestion,\n        agentMode = agentMode\n    )',
'''        pendingQuestion = pendingQuestion,
        agentMode = agentMode,
        // 派生而非搬运：会话没覆盖过就跟随全局默认档。
        // 所以 syncFromUi 里**不能**写这两个字段——那会把「跟随全局」固化成永久覆盖。
        toolPermissionMode = toolPermissionModeOverride ?: base.settings.toolPermissionMode,
        reasoningEffort = reasoningEffortOverride ?: base.settings.reasoningEffort
    )''', label='1.2 projectOnto 派生生效值')

# ═════════ 2. ConsoleUiState：两个投影字段 ═════════
rep(CS, '    val agentMode: String = "build",',
'''    val agentMode: String = "build",
    /**
     * 当前会话**生效**的工具权限档（= 会话覆盖 ?? 全局默认 `settings.toolPermissionMode`）。
     * UI 与请求侧都读这个，不要再读 settings 里那份（那是全局默认）。
     */
    val toolPermissionMode: String = "allow",
    /** 当前会话**生效**的思考强度（= 会话覆盖 ?? 全局默认）。 */
    val reasoningEffort: String = "default",''', label='2 ConsoleUiState 两个投影字段')

# ═════════ 3. MainViewModel：setter 里跟随全局默认变化 ═════════
rep(VM, '''            } else {
                _uiState = value
                slotOf(nextCid).syncFromUi(value)
            }''',
'''            } else {
                _uiState = value
                slotOf(nextCid).syncFromUi(value)
                // 全局默认档变了（设置页改权限/思考强度、或备份导入）：
                // 未做过会话级覆盖的会话必须跟着变，而这两个字段是派生的，
                // 只有重算投影才能把新的全局默认带进来。
                if (prevSettings != value.settings) {
                    _uiState = slotOf(nextCid).projectOnto(_uiState)
                }
            }''', label='3.1 setter 跟随全局默认')

rep(VM, '''        val prevCid = _uiState.currentConversationId
        val nextCid = value.currentConversationId''',
'''        val prevCid = _uiState.currentConversationId
        val prevSettings = _uiState.settings
        val nextCid = value.currentConversationId''', label='3.2 记录 prevSettings')

# ═════════ 4. MainViewModel：生效值 helper ═════════
rep(VM, '''    /** 把前台槽位重新投影进 uiState。 */''',
'''    /**
     * 某会话**生效**的工具权限档：会话覆盖 ?? 全局默认。
     * 请求侧、审批判定都必须走这里，不要直接读 `settings.toolPermissionMode`
     * （那是全局默认档，并发时会把 A 的选择当成 B 的）。
     */
    private fun effectivePermissionMode(conversationId: Long): String =
        slotOf(conversationId).toolPermissionModeOverride ?: _uiState.settings.toolPermissionMode

    /** 某会话生效的思考强度：会话覆盖 ?? 全局默认。 */
    private fun effectiveReasoningEffort(conversationId: Long): String =
        slotOf(conversationId).reasoningEffortOverride ?: _uiState.settings.reasoningEffort

    /**
     * 本轮请求实际下发的设置：全局设置 + 本会话的两个覆盖档。
     * ChatRepository 收到的就是这份，它内部（含 resolveCallContext）不必再关心会话差异。
     */
    private fun settingsFor(conversationId: Long): AppSettings =
        _uiState.settings.copy(
            toolPermissionMode = effectivePermissionMode(conversationId),
            reasoningEffort = effectiveReasoningEffort(conversationId)
        )

    /** 把前台槽位重新投影进 uiState。 */''', label='4 三个 helper')

print('上半完成')
