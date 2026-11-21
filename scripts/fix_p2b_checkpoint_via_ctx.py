# -*- coding: utf-8 -*-
"""
改用 ProviderCallContext 承载 checkpointTurnId —— 撤销逐函数加参数的做法。

为什么：executeAndFollowUp 被 syncChat / streamChat / reactChat 共同调用，
逐层加参数要改 4 个签名 + 6 个调用点，且 `onCacheHit, enableCache)` 这类锚点
在全文件出现 10 次（含子代理链路），极易改错。
而这三个函数**都已经收到 ctx**，所以把 turnId 放进 ctx 一处即可全程随行。
"""
CR = 'app/src/main/java/com/wy/openx/data/repository/ChatRepository.kt'
src = open(CR).read()


def rep(old, new, n=1, label=''):
    global src
    k = src.count(old)
    assert k == n, f"[{label}] 锚点出现 {k} 次（期望 {n}）：{old[:60]!r}"
    src = src.replace(old, new)
    print(f"  ok {label}")


# 1) 撤销 executeAndFollowUp 上新加的参数
rep('        toolPolicy: ((String, String) -> String?)? = null,\n'
    '        /** 本轮 user 消息 id：文件快照桶归属。0=沿用全局指针（子代理/旧语义）。 */\n'
    '        checkpointTurnId: Long = 0L\n',
    '        toolPolicy: ((String, String) -> String?)? = null\n',
    label='撤销 executeAndFollowUp 参数')

# 2) 撤销 5 个 executeAndFollowUp 调用点上插入的实参
#    （不能全局替换：`runChat(..., checkpointTurnId)` 也含同一子串，那一个要保留）
lines = src.split('\n')
removed = 0
for i, ln in enumerate(lines):
    if 'executeAndFollowUp(' in ln and ', checkpointTurnId)' in ln:
        lines[i] = ln.replace(', checkpointTurnId)', ')')
        removed += 1
assert removed == 5, f'撤销了 {removed} 处实参（期望 5）'
src = '\n'.join(lines)
print(f"  ok 撤销 {removed} 处实参")

# 3) ProviderCallContext 承载 turnId（默认 0 = 沿用旧的全局指针语义）
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
    ')', label='ProviderCallContext 加 checkpointTurnId')

# 4) runChat 里把参数写进 ctx（一处生效，下游 syncChat/streamChat/reactChat 全部随行）
rep('        val callCtx = resolveCallContext(settings, forcedProvider)',
    '        val callCtx = resolveCallContext(settings, forcedProvider)\n'
    '            .let { if (checkpointTurnId != 0L) it.copy(checkpointTurnId = checkpointTurnId) else it }',
    label='runChat 把 turnId 写入 ctx')

# 5) 快照调用改读 ctx
rep('                CheckpointManager.snapshotBeforeWrite(\n'
    '                    writeTargets,\n'
    '                    if (checkpointTurnId != 0L) checkpointTurnId else null\n'
    '                )',
    '                CheckpointManager.snapshotBeforeWrite(\n'
    '                    writeTargets,\n'
    '                    if (ctx.checkpointTurnId != 0L) ctx.checkpointTurnId else null\n'
    '                )', label='快照调用改读 ctx.checkpointTurnId')

open(CR, 'w').write(src)
print('完成')
