# -*- coding: utf-8 -*-
"""
P2b 补充：把「文件检查点桶」按轮显式归属，消除并发串台。

问题：CheckpointManager.currentMsgId 是全局单值「开门指针」。
并发时 A beginTurn(msgA) → B beginTurn(msgB) 会把指针覆盖成 msgB，
于是 A 的写工具打的快照落进 **B 的桶**；用户撤销 B 会回滚 A 改的文件。
（桶本身按 msgId 存，是好的；坏的只有那个指针。）

修法：把 turnMsgId 沿 sendMessage → runChat → snapshotBeforeWrite 显式传下去，
指针只作为「未传时的兼容回退」（子代理/标题/压缩等非会话主链路仍走旧路径）。

幂等保护：锚点匹配数不符即中止。
"""

def rep(path, old, new, n=1, label=''):
    c = open(path).read()
    k = c.count(old)
    assert k == n, f"[{label}] {path} 锚点出现 {k} 次（期望 {n}）：{old[:70]!r}"
    open(path, 'w').write(c.replace(old, new))
    print(f"  ok {label}")


CR = 'app/src/main/java/com/wy/openx/data/repository/ChatRepository.kt'
CK = 'app/src/main/java/com/wy/openx/tools/CheckpointManager.kt'
VM = 'app/src/main/java/com/wy/openx/ui/viewmodel/MainViewModel.kt'

# ── 1. CheckpointManager：snapshotBeforeWrite 接受显式 turnMsgId ──
rep(CK,
    '    suspend fun snapshotBeforeWrite(rawTargets: List<String>): Unit = withContext(Dispatchers.IO) {\n'
    '        val msgId = synchronized(lock) { currentMsgId } ?: return@withContext',
    '    /**\n'
    '     * @param turnMsgId 本轮 user 消息 id。**并发时必须由调用方显式传入**：\n'
    '     *   `currentMsgId` 是全局单值，两会话交替 beginTurn 时它只反映最后一次，\n'
    '     *   于是 A 的写入会被记到 B 的桶里（撤销 B 会回滚 A 的文件）。\n'
    '     *   传 null 时退回旧的全局指针语义（子代理/压缩等非主链路，保持原行为）。\n'
    '     */\n'
    '    suspend fun snapshotBeforeWrite(rawTargets: List<String>, turnMsgId: Long? = null): Unit = withContext(Dispatchers.IO) {\n'
    '        val msgId = turnMsgId ?: synchronized(lock) { currentMsgId } ?: return@withContext',
    label='CheckpointManager.snapshotBeforeWrite 加 turnMsgId')

# ── 2. ChatRepository.sendMessage：新增可选参数并透传 ──
rep(CR,
    '        /** 强制使用指定提供商（如「默认模型」里的压缩/起标题专用模型，selectedModel 已就位）。null 沿用当前激活提供商。 */\n'
    '        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null\n'
    '    ): Result<String> {',
    '        /** 强制使用指定提供商（如「默认模型」里的压缩/起标题专用模型，selectedModel 已就位）。null 沿用当前激活提供商。 */\n'
    '        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null,\n'
    '        /**\n'
    '         * 本轮 user 消息 id，决定工具写入的文件快照落进哪个检查点桶。\n'
    '         * 0 = 沿用全局指针（旧语义）。多会话并行时主链路必须传自己那一轮。\n'
    '         */\n'
    '        checkpointTurnId: Long = 0L\n'
    '    ): Result<String> {',
    label='sendMessage 加 checkpointTurnId')

rep(CR,
    'onCacheHit, historyMessages, forcedProvider))',
    'onCacheHit, historyMessages, forcedProvider, checkpointTurnId))',
    label='runChat 调用透传')

# ── 3. runChat 签名 + 使用点 ──
rep(CR,
    '        historyMessages: List<ChatMessageDto>,\n'
    '        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null\n'
    '    ): String {',
    '        historyMessages: List<ChatMessageDto>,\n'
    '        forcedProvider: com.wy.openx.data.model.AIProviderConfig? = null,\n'
    '        checkpointTurnId: Long = 0L\n'
    '    ): String {',
    label='runChat 加参数')

rep(CR,
    '                CheckpointManager.snapshotBeforeWrite(writeTargets)',
    '                CheckpointManager.snapshotBeforeWrite(\n'
    '                    writeTargets,\n'
    '                    if (checkpointTurnId != 0L) checkpointTurnId else null\n'
    '                )',
    label='snapshotBeforeWrite 传本轮桶')

# ── 4. MainViewModel 主链路传本轮 user 消息 id ──
rep(VM,
    '                historyMessages = projectedHistory',
    '                historyMessages = projectedHistory,\n'
    '                // 检查点桶按本轮归属：并发下不能让 A 的文件写入落进 B 的桶\n'
    '                checkpointTurnId = userMessage.id',
    label='VM 主链路传 checkpointTurnId')

print('P2b 检查点桶归属改造完成')
