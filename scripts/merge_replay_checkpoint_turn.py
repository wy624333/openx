# -*- coding: utf-8 -*-
"""
合并步骤 1：作者版 ChatRepository.kt（含 onContextTokens 全链）+ 重放我们的 checkpointTurnId。

我们的改动只有 5 处、且都是机械插入，所以「取作者整份 + 重放我们」比
「在我们的文件上补作者的 15+ 处 onContextTokens」安全得多。

幂等保护：任一锚点匹配数不符即中止，不写文件。
"""
CR = 'app/src/main/java/com/wy/openx/data/repository/ChatRepository.kt'
src = open(CR).read()


def rep(old, new, n=1, label=''):
    global src
    k = src.count(old)
    assert k == n, f"[{label}] 锚点出现 {k} 次（期望 {n}）：{old[:70]!r}"
    src = src.replace(old, new)
    print(f"  ok {label}")


# ① ProviderCallContext 承载 turnId
rep('    val userAgent: String = "",\n'
    '    val sessionId: String? = null\n'
    ')',
    '    val userAgent: String = "",\n'
    '    val sessionId: String? = null,\n'
    '    /**\n'
    '     * 本轮 user 消息 id，决定工具写入的文件快照落进哪个检查点桶。\n'
    '     * 多会话并行时必须随行：CheckpointManager.currentMsgId 是全局单值，\n'
    '     * 两会话交替 beginTurn 时它只反映最后一次，会让 A 的写入记到 B 的桶里。\n'
    '     * 0 = 未指定（子代理/旧语义，沿用全局指针）。\n'
    '     */\n'
    '    val checkpointTurnId: Long = 0L\n'
    ')', label='① ProviderCallContext.checkpointTurnId')

# ② sendMessage 参数（用返回类型 Result<String> 与 runChat 区分）
rep('        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null\n'
    '    ): Result<String> {',
    '        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null,\n'
    '        /**\n'
    '         * 本轮 user 消息 id，决定文件快照桶归属。0 = 沿用全局指针（旧语义）。\n'
    '         * 多会话并行时主链路必须传自己那一轮。\n'
    '         */\n'
    '        checkpointTurnId: Long = 0L\n'
    '    ): Result<String> {', label='② sendMessage.checkpointTurnId')

# ③ 透传给 runChat
rep('historyMessages, forcedProvider))',
    'historyMessages, forcedProvider, checkpointTurnId))',
    label='③ runChat 调用透传')

# ④ runChat 参数（用返回类型 String 区分）
rep('        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null\n'
    '    ): String {',
    '        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null,\n'
    '        checkpointTurnId: Long = 0L\n'
    '    ): String {', label='④ runChat.checkpointTurnId')

# ⑤ 一处写入 ctx，下游 syncChat/streamChat/reactChat/executeAndFollowUp 全部随行
#    （比逐层加参数安全：`onCacheHit, enableCache)` 这类锚点在全文件出现 10+ 次）
rep('        val callCtx = resolveCallContext(settings, forcedProvider)',
    '        val callCtx = resolveCallContext(settings, forcedProvider)\n'
    '            .let { if (checkpointTurnId != 0L) it.copy(checkpointTurnId = checkpointTurnId) else it }',
    label='⑤ callCtx 带上 turnId')

# ⑥ 快照按本轮桶归属
rep('CheckpointManager.snapshotBeforeWrite(writeTargets)',
    'CheckpointManager.snapshotBeforeWrite(\n'
    '                    writeTargets,\n'
    '                    if (ctx.checkpointTurnId != 0L) ctx.checkpointTurnId else null\n'
    '                )', label='⑥ snapshotBeforeWrite 读 ctx')

open(CR, 'w').write(src)
print('ChatRepository 合并完成')
