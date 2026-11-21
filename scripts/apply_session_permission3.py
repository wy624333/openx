# -*- coding: utf-8 -*-
"""
会话级权限档 · 第三段：请求侧 reasoningEffort 离开全局 AppContextHolder。

为什么必须改：runChat 里 `AppContextHolder.currentSettings = settings` 是全局单槽，
并发时 A、B 交替写入会互相覆盖，`currentReasoningEffort()` 就会读到别的会话的值。
而 VM 现在传下来的 settings 已是「会话合并版」，所以让它随 ProviderCallContext 走即可。

顺序敏感：先把定义改成带参（此时不再匹配 `currentReasoningEffort()`），再全局替换调用点。
"""
CR = 'app/src/main/java/com/wy/openx/data/repository/ChatRepository.kt'
MA = 'app/src/main/java/com/wy/openx/MainActivity.kt'
c = open(CR).read()

def rep(old, new, n=1, label=''):
    global c
    k = c.count(old)
    assert k == n, f"[{label}] 锚点出现 {k} 次（期望 {n}）：{old[:70]!r}"
    c = c.replace(old, new)
    print(f"  ok {label}")

# ① ProviderCallContext 承载会话生效的思考强度
rep('''    val checkpointTurnId: Long = 0L
)''',
'''    val checkpointTurnId: Long = 0L,
    /**
     * 本轮生效的思考强度（**会话档**，由 VM 合并进 settings 后传下来）。
     * 不能再去读 `AppContextHolder.currentSettings`——那是全局单槽，
     * 并发时 A、B 交替写入会互相覆盖，读到的是别的会话的选择。
     */
    val reasoningEffort: String = "default"
)''', label='① ctx 加 reasoningEffort')

# ② resolveCallContext 从传入 settings 取
rep('''            userAgent = resolvedUserAgent,
            sessionId = sessionId
        )''',
'''            userAgent = resolvedUserAgent,
            sessionId = sessionId,
            reasoningEffort = settings.reasoningEffort
        )''', label='② resolveCallContext 带上')

# ③ 定义改带参（顺序敏感，必须在替换调用点之前）
rep('''    private fun currentReasoningEffort(): String? =
        com.wy.openx.tools.AppContextHolder.currentSettings?.reasoningEffort
            ?.takeIf { it.isNotBlank() && it != "default" }''',
'''    private fun currentReasoningEffort(ctx: ProviderCallContext): String? =
        ctx.reasoningEffort.takeIf { it.isNotBlank() && it != "default" }''',
    label='③ 定义改带参')

# ④ 11 处调用点
k = c.count('currentReasoningEffort()')
assert k == 11, f'调用点 {k} 处（期望 11）'
c = c.replace('currentReasoningEffort()', 'currentReasoningEffort(ctx)')
print(f"  ok ④ {k} 处调用点改传 ctx")

open(CR, 'w').write(c)

# ⑤ UI 读会话生效值（不再是全局 settings）
m = open(MA).read()
for old, new, label in [
    ('toolPermissionMode = vm.uiState.settings.toolPermissionMode,',
     'toolPermissionMode = vm.uiState.toolPermissionMode,', '⑤.1 权限档'),
    ('reasoningEffort = vm.uiState.settings.reasoningEffort,',
     'reasoningEffort = vm.uiState.reasoningEffort,', '⑤.2 思考强度'),
]:
    assert m.count(old) == 1, f'[{label}] MainActivity 锚点 {m.count(old)} 次'
    m = m.replace(old, new)
    print(f"  ok {label}")
open(MA, 'w').write(m)
print('第三段完成')
