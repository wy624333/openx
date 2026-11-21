# -*- coding: utf-8 -*-
"""
SettingsScreen 部分重做（上一版步骤⑧边界写反，误删了 SettingsToggleRow）。

修正点：删 SettingsSegRow 时，结束边界必须**从 SettingsToggleRow 往回**找紧邻的 @Composable，
而不是从它往后找 —— 往后找会命中下一个函数，把 ToggleRow 整个吞掉。
"""
S = 'app/src/main/java/com/wy/openx/ui/screen/SettingsScreen.kt'
L = open(S).read().split('\n')


def find(sub, start=0):
    for i in range(start, len(L)):
        if sub in L[i]:
            return i
    raise AssertionError('未找到: %r' % sub)


# ⑧ 删 SettingsSegRow（KDoc + @Composable + 函数体），保留 ToggleRow 的 @Composable
i_doc = find('分段选择行')
start = i_doc
while not L[start].strip().startswith('/**'):
    start -= 1
i_toggle = find('private fun SettingsToggleRow(')
end = i_toggle
while '@Composable' not in L[end]:
    end -= 1                      # ← 往回找
assert start < end, 'SegRow 边界异常'
del L[start:end]
print('  ok 8 删 SettingsSegRow（保留 ToggleRow）')

# ⑦ UI：全局默认档三块 → 两个开关 ToggleRow
i_start = find('// ===== 全局默认档 =====')
i_clear = find('清除所有会话的档位覆盖')
i_end = find('SettingsCard {', i_clear)
NEW_UI = [
    '                    // ===== 全局 / 会话 档位作用范围开关 =====',
    '                    // 聊天页那两个下拉的**作用范围**由这两个开关决定：',
    '                    //   关（默认）= 只改当前会话（会话级 override，互不影响）；',
    '                    //   开        = 改全局默认档，并清空所有会话的 override → 全部统一。',
    '                    // 有了「开」这一档，就不必在设置页重复一套档位选择器，也不必',
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

# ⑥ 播种块（整行替换，避免子串连带改到右侧）
i = find('toolPermissionMode = settings.toolPermissionMode')
L[i] = '        toolPermissionGlobalMode = settings.toolPermissionGlobalMode'
i = find('reasoningEffort = settings.reasoningEffort')
L[i] = '        reasoningEffortGlobalMode = settings.reasoningEffortGlobalMode'
print('  ok 6 播种块')

# ⑤ 自动保存依赖列表
i = find('toolPermissionMode, reasoningEffort,')
L[i] = '        toolPermissionGlobalMode, reasoningEffortGlobalMode,'
print('  ok 5 自动保存依赖')

# ④ composeCurrent
i = find('toolPermissionMode = toolPermissionMode,')
L[i] = '            toolPermissionGlobalMode = toolPermissionGlobalMode,'
i = find('reasoningEffort = reasoningEffort,')
L[i] = '            reasoningEffortGlobalMode = reasoningEffortGlobalMode,'
print('  ok 4 composeCurrent')

# ③ 本地状态
i = find('var toolPermissionMode by remember')
if L[i - 1].strip().startswith('// 全局默认档'):
    L[i - 1] = ('    // 「全局设置模式」开关：开着时聊天页切档改全局并统一所有会话；关着时只改当前会话')
L[i] = '    var toolPermissionGlobalMode by remember { mutableStateOf(settings.toolPermissionGlobalMode) }'
i = find('var reasoningEffort by remember')
L[i] = '    var reasoningEffortGlobalMode by remember { mutableStateOf(settings.reasoningEffortGlobalMode) }'
print('  ok 3 本地状态')

open(S, 'w').write('\n'.join(L))
print('SettingsScreen 完成')
