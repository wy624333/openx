# -*- coding: utf-8 -*-
"""
方案改为用户设计：用「全局设置模式」开关取代 设置页的两个档位选择器 + 清除覆盖按钮。
  开关关（默认）= 聊天页切档只改当前会话
  开关开        = 聊天页切档改全局，并统一所有会话
本脚本负责 AppModels + SettingsScreen。
"""
AM = 'app/src/main/java/com/wy/openx/data/model/AppModels.kt'
S  = 'app/src/main/java/com/wy/openx/ui/screen/SettingsScreen.kt'

def rep(path, old, new, n=1, label=''):
    c = open(path).read()
    k = c.count(old)
    assert k == n, f"[{label}] {path} 锚点 {k} 次（期望 {n}）：{old[:60]!r}"
    open(path, 'w').write(c.replace(old, new))
    print(f"  ok {label}")

# ═══ 1. AppModels：两个持久化开关 ═══
rep(AM, '''    val reasoningEffort: String = "default",
''', '''    val reasoningEffort: String = "default",

    /**
     * 工具权限档的「全局设置模式」开关（默认关）。
     * 关：聊天页切档只改**当前会话**（会话级 override）。
     * 开：聊天页切档改**全局默认**，并把所有会话的 override 清空 → 全部统一跟随。
     */
    val toolPermissionGlobalMode: Boolean = false,

    /** 思考强度的「全局设置模式」开关，语义同 [toolPermissionGlobalMode]。 */
    val reasoningEffortGlobalMode: Boolean = false,
''', label='1 AppModels 两个开关')

# ═══ 2. SettingsScreen：删掉 onClearSessionOverrides 参数 ═══
rep(S, '''    /**
     * 清除所有会话对「工具权限档 / 思考强度」的显式覆盖，让它们重新跟随下面的全局默认。
     * 默认空实现：不接也不会破坏既有行为（只是那个按钮按下去没反应）。
     */
    onClearSessionOverrides: () -> Unit = {},
''', '', label='2 删参数')

# ═══ 3. 本地状态换成两个开关 ═══
rep(S, '''    // 全局默认档（聊天页切档只改**当前会话**，改这里才影响所有未覆盖的会话）
    var toolPermissionMode by remember { mutableStateOf(settings.toolPermissionMode) }
    var reasoningEffort by remember { mutableStateOf(settings.reasoningEffort) }''',
'''    // 「全局设置模式」开关：开着时聊天页切档改全局并统一所有会话；关着时只改当前会话
    var toolPermissionGlobalMode by remember { mutableStateOf(settings.toolPermissionGlobalMode) }
    var reasoningEffortGlobalMode by remember { mutableStateOf(settings.reasoningEffortGlobalMode) }''',
    label='3 状态声明')

# ═══ 4. composeCurrent ═══
rep(S, '''            toolPermissionMode = toolPermissionMode,
            reasoningEffort = reasoningEffort,''',
'''            toolPermissionGlobalMode = toolPermissionGlobalMode,
            reasoningEffortGlobalMode = reasoningEffortGlobalMode,''',
    label='4 composeCurrent')

# ═══ 5. 自动保存依赖列表 ═══
rep(S, '        toolPermissionMode, reasoningEffort,\n',
    '        toolPermissionGlobalMode, reasoningEffortGlobalMode,\n',
    label='5 依赖列表')

# ═══ 6. 重新播种 ═══
rep(S, '''        toolPermissionMode = settings.toolPermissionMode
        reasoningEffort = settings.reasoningEffort''',
'''        toolPermissionGlobalMode = settings.toolPermissionGlobalMode
        reasoningEffortGlobalMode = settings.reasoningEffortGlobalMode''',
    label='6 播种')

# ═══ 7. UI：三块（两个 SegRow + 清除覆盖行）→ 两个开关 ═══
c = open(S).read()
i = c.index('                    // ===== 全局默认档 =====')
j = c.index('                    SettingsCard {\n                        Row(\n                            modifier = Modifier.fillMaxWidth()\n                                .clickable { showToolAllowDialog = true }', i)
NEW = '''                    // ===== 档位的作用范围开关 =====
                    // 聊天页那两个下拉默认只改**当前会话**。想统一所有会话，把对应开关打开，
                    // 再去聊天页点一次即可（会同时清空各会话的单独设置）。
                    SettingsToggleRow(
                        "工具权限模式",
                        "打开后聊天页切「工具权限档」改的是全局默认，并统一所有会话；" +
                            "关闭时只影响当前会话",
                        toolPermissionGlobalMode
                    ) { toolPermissionGlobalMode = it }
                    SettingsCardDivider()
                    SettingsToggleRow(
                        "思考过程模式",
                        "打开后聊天页切「思考强度」改的是全局默认，并统一所有会话；" +
                            "关闭时只影响当前会话",
                        reasoningEffortGlobalMode
                    ) { reasoningEffortGlobalMode = it }
                    SettingsCardDivider()
'''
open(S, 'w').write(c[:i] + NEW + c[j:])
print('  ok 7 UI 三块 → 两个开关')

# ═══ 8. 删掉已无人使用的 SettingsSegRow 组件 ═══
c = open(S).read()
i = c.index('/**\n * 分段选择行：标题 + 说明 + 一行可横向滚动的档位 Chip。')
j = c.index('@Composable\nprivate fun SettingsToggleRow', i)
open(S, 'w').write(c[:i] + c[j:])
print('  ok 8 删 SettingsSegRow')
print('A 段完成')
