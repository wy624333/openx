# -*- coding: utf-8 -*-
"""
按用户设计重做「全局 / 会话」档位控制：

  设置页两个开关（默认都关）：
    [工具权限模式]   开 = 聊天页切档改全局并统一所有会话；关 = 只改当前会话
    [思考过程模式]   同上
  删掉我上一轮加的两个 SegRow 档位选择器 + 「清除所有会话的档位覆盖」按钮。

比原方案好在哪：聊天页那个下拉**既是会话入口也是全局入口**（由开关决定作用范围），
不需要在设置页重复一套档位选择器；「清除覆盖」也被「开着开关点一下档位」自然取代。

AppModels 的两个字段已由 apply_global_switch_a.py 加好，本脚本不再动它。

定位方式：全部按**单行子串**找行号，从后往前改，避免多行锚点因不可见字符失配。
"""
S = 'app/src/main/java/com/wy/openx/ui/screen/SettingsScreen.kt'
VM = 'app/src/main/java/com/wy/openx/ui/viewmodel/MainViewModel.kt'
MA = 'app/src/main/java/com/wy/openx/MainActivity.kt'


def lines_of(p):
    return open(p).read().split('\n')


def find(lines, sub, start=0, required=True):
    for i in range(start, len(lines)):
        if sub in lines[i]:
            return i
    if required:
        raise AssertionError('未找到: %r' % sub)
    return None


def save(p, lines):
    open(p, 'w').write('\n'.join(lines))


# ══════════════════════ SettingsScreen ══════════════════════
L = lines_of(S)

# ⑧ 删掉不再使用的 SettingsSegRow 组件（含其上方 KDoc）
i_doc = find(L, '分段选择行')
start = i_doc
while not L[start].strip().startswith('/**'):
    start -= 1
i_toggle = find(L, 'private fun SettingsToggleRow(')
end = i_toggle
while '@Composable' not in L[end]:
    end += 1
assert start < end, 'SegRow 边界异常'
del L[start:end]
print('  ok 8 删 SettingsSegRow 组件')

# ⑦ UI：全局默认档三块 → 两个开关 ToggleRow
i_start = find(L, '// ===== 全局默认档 =====')
i_clear = find(L, '清除所有会话的档位覆盖')
i_end = find(L, 'SettingsCard {', start=i_clear)
NEW_UI = [
    '                    // ===== 全局 / 会话 档位作用范围开关 =====',
    '                    // 聊天页那两个下拉的**作用范围**由这两个开关决定：',
    '                    //   关（默认）= 只改当前会话（会话级 override，互不影响）；',
    '                    //   开        = 改全局默认档，并清空所有会话的 override → 全部统一。',
    '                    // 有了「开」这一档，就不必再在设置页重复一套档位选择器，也不必',
    '                    // 「清除所有会话覆盖」按钮 —— 开着开关点一下档位即等价于清除+统一。',
    '                    SettingsToggleRow(',
    '                        "工具权限模式",',
    '                        "开：聊天页切档改全局并统一所有会话；关（默认）：只改当前会话",',
    '                        toolPermissionGlobalMode',
    '                    ) { toolPermissionGlobalMode = it }',
    '                    SettingsCardDivider()',
    '                    SettingsToggleRow(',
    '                        "思考过程模式",',
    '                        "开：聊天页切强度改全局并统一所有会话；关（默认）：只改当前会话",',
    '                        reasoningEffortGlobalMode',
    '                    ) { reasoningEffortGlobalMode = it }',
    '                    SettingsCardDivider()',
]
L[i_start:i_end] = NEW_UI
print('  ok 7 UI 换成两个开关')

# ⑥ 播种块
for old, new in [
    ('toolPermissionMode = settings.toolPermissionMode',
     'toolPermissionGlobalMode = settings.toolPermissionGlobalMode'),
    ('reasoningEffort = settings.reasoningEffort',
     'reasoningEffortGlobalMode = settings.reasoningEffortGlobalMode'),
]:
    i = find(L, old)
    assert old not in new or True
    L[i] = L[i].replace(old.split(' = ')[0], new.split(' = ')[0]).replace(
        'settings.' + old.split(' = ')[1], 'settings.' + new.split(' = ')[1])
print('  ok 6 播种块')

# ⑤ 自动保存依赖列表
i = find(L, 'toolPermissionMode, reasoningEffort,')
L[i] = L[i].replace('toolPermissionMode, reasoningEffort,',
                    'toolPermissionGlobalMode, reasoningEffortGlobalMode,')
print('  ok 5 自动保存依赖')

# ④ composeCurrent
i = find(L, 'toolPermissionMode = toolPermissionMode,')
L[i] = '            toolPermissionGlobalMode = toolPermissionGlobalMode,'
i = find(L, 'reasoningEffort = reasoningEffort,')
L[i] = '            reasoningEffortGlobalMode = reasoningEffortGlobalMode,'
print('  ok 4 composeCurrent')

# ③ 本地状态
i = find(L, 'var toolPermissionMode by remember')
# 顺带把它上面那行注释换掉
if L[i - 1].strip().startswith('// 全局默认档'):
    L[i - 1] = ('    // 「全局设置模式」开关：开着时聊天页切档改全局并统一所有会话；'
                '关着时只改当前会话')
L[i] = '    var toolPermissionGlobalMode by remember { mutableStateOf(settings.toolPermissionGlobalMode) }'
i = find(L, 'var reasoningEffort by remember')
L[i] = '    var reasoningEffortGlobalMode by remember { mutableStateOf(settings.reasoningEffortGlobalMode) }'
print('  ok 3 本地状态')

# ② 删掉 onClearSessionOverrides 参数（含上方 KDoc）—— 可能已被前一个脚本删过，故可选
i = find(L, 'onClearSessionOverrides', required=False)
if i is None:
    print('  -- 2 onClearSessionOverrides 参数已不存在，跳过')
else:
    start = i
    while start > 0 and not L[start].strip().startswith('/**'):
        start -= 1
    if L[start].strip().startswith('/**'):
        del L[start:i + 1]
    else:
        del L[i]
    print('  ok 2 删 onClearSessionOverrides 参数')

save(S, L)

# ══════════════════════ MainViewModel ══════════════════════
M = lines_of(VM)

# ① setToolPermissionMode：按开关分流
i = find(M, '    fun setToolPermissionMode(mode: String) {')
j = find(M, '    }', start=i)
M[i:j + 1] = [
    '    /**',
    '     * 切换工具权限档。**作用范围由设置页「工具权限模式」开关决定**：',
    '     *  - 开关开：改全局默认档，并清空所有会话的 override → 所有会话立即统一；',
    '     *  - 开关关（默认）：只改当前会话，其他会话不受影响。',
    '     */',
    '    fun setToolPermissionMode(mode: String) {',
    '        if (uiState.settings.toolPermissionGlobalMode) {',
    '            if (uiState.settings.toolPermissionMode == mode) return',
    '            val newSettings = uiState.settings.copy(toolPermissionMode = mode)',
    '            uiState = uiState.copy(settings = newSettings)',
    '            com.wy.openx.tools.AppContextHolder.currentSettings = newSettings',
    '            // 全局模式：把各会话的显式覆盖清掉，否则切过的会话仍留自己的档，',
    '            // 用户会觉得「改了全局怎么没统一」。这正是原先「清除覆盖」按钮的活儿。',
    '            slots.values.forEach { it.toolPermissionModeOverride = null }',
    '            refreshProjection()',
    '            viewModelScope.launch { settingsStore.save(newSettings) }',
    '            showToast("工具权限（全局）：${toolPermissionLabel(mode)}")',
    '        } else {',
    '            val cid = uiState.currentConversationId',
    '            if (effectivePermissionMode(cid) == mode) return',
    '            editSlot(cid) { toolPermissionModeOverride = mode }',
    '            showToast("本会话工具权限：${toolPermissionLabel(mode)}")',
    '        }',
    '    }',
]
print('  ok 1 setToolPermissionMode 按开关分流')

# ② setReasoningEffort：同样分流
i = find(M, '    fun setReasoningEffort(effort: String) {')
j = find(M, '    }', start=i)
M[i:j + 1] = [
    '    /** 切换思考强度。作用范围由设置页「思考过程模式」开关决定，语义同 [setToolPermissionMode]。 */',
    '    fun setReasoningEffort(effort: String) {',
    '        if (uiState.settings.reasoningEffortGlobalMode) {',
    '            if (uiState.settings.reasoningEffort == effort) return',
    '            val newSettings = uiState.settings.copy(reasoningEffort = effort)',
    '            uiState = uiState.copy(settings = newSettings)',
    '            com.wy.openx.tools.AppContextHolder.currentSettings = newSettings',
    '            slots.values.forEach { it.reasoningEffortOverride = null }',
    '            refreshProjection()',
    '            viewModelScope.launch { settingsStore.save(newSettings) }',
    '            showToast("思考强度（全局）：${reasoningEffortLabel(effort)}")',
    '        } else {',
    '            val cid = uiState.currentConversationId',
    '            if (effectiveReasoningEffort(cid) == effort) return',
    '            editSlot(cid) { reasoningEffortOverride = effort }',
    '            showToast("本会话思考强度：${reasoningEffortLabel(effort)}")',
    '        }',
    '    }',
]
print('  ok 2 setReasoningEffort 按开关分流')

# ③ 删掉 clearSessionOverrides（含上方 KDoc）
i = find(M, '    fun clearSessionOverrides() {')
start = i
while not M[start].strip().startswith('/**'):
    start -= 1
j = find(M, '    }', start=i)
del M[start:j + 1]
print('  ok 3 删 clearSessionOverrides')

save(VM, M)

# ══════════════════════ MainActivity ══════════════════════
A = lines_of(MA)
i = find(A, 'onClearSessionOverrides', required=False)
if i is not None:
    del A[i]
    print('  ok 删 MainActivity 的 onClearSessionOverrides 接线')
save(MA, A)

print('全局/会话开关改造完成')
