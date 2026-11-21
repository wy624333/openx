# -*- coding: utf-8 -*-
"""
补上「默认工具权限档 / 默认思考强度」的全局入口（设置页 · 工具分区）。

为什么必须补：聊天页那两个下拉在 P2d 已改成「只写本会话槽、不落全局」，
而设置页本来就没有这两个入口（SettingsScreen L348 注释自陈它们是"界面上未暴露的字段"）。
两头一夹 → **全局默认档再没有任何 UI 能改**，所有新会话永远停在出厂值 "allow"/"default"，
用户想「统一改成弹窗确认」就再也做不到。

同时提供「清除所有会话覆盖」：override 语义下，已手动切过档的会话不会跟随全局默认，
这是设计正确的行为，但用户改全局时往往是真想统一所有会话 —— 所以给一个显式出口。

幂等保护：任一锚点匹配数不符即中止，不写文件。
"""
S = 'app/src/main/java/com/wy/openx/ui/screen/SettingsScreen.kt'
VM = 'app/src/main/java/com/wy/openx/ui/viewmodel/MainViewModel.kt'
MA = 'app/src/main/java/com/wy/openx/MainActivity.kt'


def rep(path, old, new, n=1, label=''):
    c = open(path).read()
    k = c.count(old)
    assert k == n, f"[{label}] {path} 锚点出现 {k} 次（期望 {n}）：{old[:70]!r}"
    open(path, 'w').write(c.replace(old, new))
    print(f"  ok {label}")


# ═══════ 1. 参数：清除覆盖的回调（默认空实现，作者不接也不报错）═══════
rep(S, '    onResetTokenStats: () -> Unit = {},',
    '''    onResetTokenStats: () -> Unit = {},
    /**
     * 清除所有会话对「工具权限档 / 思考强度」的显式覆盖，让它们重新跟随下面的全局默认。
     * 默认空实现：不接也不会破坏既有行为（只是那个按钮按下去没反应）。
     */
    onClearSessionOverrides: () -> Unit = {},''',
    label='1 加 onClearSessionOverrides 参数')

# ═══════ 2. 本地状态 ═══════
rep(S, '    var parallelToolCalls by remember { mutableStateOf(settings.parallelToolCalls) }',
    '''    var parallelToolCalls by remember { mutableStateOf(settings.parallelToolCalls) }
    // 全局默认档（聊天页的下拉只改**当前会话**，改这里才影响所有未覆盖的会话）
    var toolPermissionMode by remember { mutableStateOf(settings.toolPermissionMode) }
    var reasoningEffort by remember { mutableStateOf(settings.reasoningEffort) }''',
    label='2 状态声明')

# ═══════ 3. composeCurrent：必须显式列出，否则被 latestSettings 原样保留、改动丢失 ═══════
rep(S, '            autoToolCall = autoToolCall,\n            parallelToolCalls = parallelToolCalls,',
    '''            autoToolCall = autoToolCall,
            parallelToolCalls = parallelToolCalls,
            toolPermissionMode = toolPermissionMode,
            reasoningEffort = reasoningEffort,''',
    label='3 composeCurrent')

# ═══════ 4. 自动保存依赖 ═══════
rep(S, '        enableStream, autoToolCall, parallelToolCalls, showLogs,',
    '        enableStream, autoToolCall, parallelToolCalls, showLogs,\n'
    '        toolPermissionMode, reasoningEffort,',
    label='4 自动保存依赖')

# ═══════ 5. 备份恢复后重新播种 ═══════
rep(S, '        parallelToolCalls = settings.parallelToolCalls\n        showLogs = settings.showLogs',
    '''        parallelToolCalls = settings.parallelToolCalls
        toolPermissionMode = settings.toolPermissionMode
        reasoningEffort = settings.reasoningEffort
        showLogs = settings.showLogs''',
    label='5 重新播种')

# ═══════ 6. UI：插在「自动执行已批准工具」之后、「工具白名单」卡片之前 ═══════
rep(S, '''                    SettingsToggleRow("自动执行已批准工具", "每批工具经确认批准后自动执行，无需手动点执行（默认开启）", autoToolCall) { autoToolCall = it }
                    SettingsToggleRow("流式输出", "逐字流式显示回复", enableStream) { enableStream = it }''',
    '''                    SettingsToggleRow("自动执行已批准工具", "每批工具经确认批准后自动执行，无需手动点执行（默认开启）", autoToolCall) { autoToolCall = it }
                    SettingsToggleRow("流式输出", "逐字流式显示回复", enableStream) { enableStream = it }
                    SettingsCardDivider()
                    // ===== 全局默认档 =====
                    // 聊天页那两个下拉只改**当前会话**（见 MainViewModel.setToolPermissionMode），
                    // 这里改的是「新会话的起始档 + 未手动切过档的会话」。两头必须都有入口，
                    // 否则会话级改造会把全局档变成改不动的死值。
                    SettingsSegRow(
                        "默认工具权限档",
                        "新会话的起始档；聊天页切档只影响当前会话",
                        listOf(
                            "readonly" to "只读",
                            "confirm" to "弹窗确认",
                            "allow" to "完全允许"
                        ),
                        selected = toolPermissionMode
                    ) { toolPermissionMode = it }
                    SettingsCardDivider()
                    SettingsSegRow(
                        "默认思考强度",
                        "推理模型的 reasoning effort；「模型默认」表示不发送该字段",
                        listOf(
                            "default" to "默认", "none" to "关闭", "minimal" to "极低",
                            "low" to "低", "medium" to "中", "high" to "高",
                            "xhigh" to "极高", "max" to "最高"
                        ),
                        selected = reasoningEffort
                    ) { reasoningEffort = it }
                    SettingsCardDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onClearSessionOverrides() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("清除所有会话的档位覆盖", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "手动切过档的会话不会跟随上面的默认值；点这里让它们全部重新跟随",
                                fontSize = 11.sp, color = TextSecondary
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary)
                    }''',
    label='6 UI 三块')

# ═══════ 7. 新增分段选择行组件（插在 SettingsToggleRow 定义之前）═══════
rep(S, 'private fun SettingsToggleRow(label: String, subtitle: String, checked: Boolean, onCheck: (Boolean) -> Unit) {',
    '''/**
 * 分段选择行：标题 + 说明 + 一行可横向滚动的档位 Chip。
 * 选项多时（思考强度 8 档）靠 horizontalScroll 兜住，不折行、不挤压。
 */
private fun SettingsSegRow(
    label: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(subtitle, fontSize = 11.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            options.forEach { (value, text) ->
                val active = value == selected
                Surface(
                    onClick = { onSelect(value) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) Accent.copy(alpha = 0.18f) else PanelAlt,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (active) Accent else Border.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = if (active) Accent else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

private fun SettingsToggleRow(label: String, subtitle: String, checked: Boolean, onCheck: (Boolean) -> Unit) {''',
    label='7 SettingsSegRow 组件')

# ═══════ 8. MainViewModel：清除覆盖 ═══════
rep(VM, '''    /** 切换**当前会话**的思考强度（同 setToolPermissionMode，不落全局设置）。 */''',
    '''    /**
     * 清除所有会话对权限档/思考强度的显式覆盖，让它们重新跟随全局默认。
     * 设置页「清除所有会话的档位覆盖」用：用户改全局默认时往往真想统一所有会话。
     */
    fun clearSessionOverrides() {
        val n = slots.values.count {
            it.toolPermissionModeOverride != null || it.reasoningEffortOverride != null
        }
        slots.values.forEach {
            it.toolPermissionModeOverride = null
            it.reasoningEffortOverride = null
        }
        refreshProjection()
        showToast(if (n == 0) "没有会话覆盖过档位" else "已清除 $n 个会话的档位覆盖，全部跟随全局默认")
    }

    /** 切换**当前会话**的思考强度（同 setToolPermissionMode，不落全局设置）。 */''',
    label='8 clearSessionOverrides')

# ═══════ 9. MainActivity 接线 ═══════
rep(MA, '            onResetTokenStats = { vm.resetTokenStats() },',
    '''            onResetTokenStats = { vm.resetTokenStats() },
            onClearSessionOverrides = { vm.clearSessionOverrides() },''',
    label='9 MainActivity 接线')

print('全局默认档入口完成')
