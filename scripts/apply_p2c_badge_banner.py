# -*- coding: utf-8 -*-
"""
P2c：侧边栏 4 态徽标 + 跨会话「等待你」横幅。

状态源：SessionSlot（真源）→ VM 每次槽位变化重算 → uiState.sessionStatuses / awaitingBanner
→ 侧边栏画小圆点，横幅浮在聊天页顶部。

幂等保护：任一锚点匹配数不符即中止，不写文件。
"""

def rep(path, old, new, n=1, label=''):
    c = open(path).read()
    k = c.count(old)
    assert k == n, f"[{label}] {path} 锚点出现 {k} 次（期望 {n}）：{old[:60]!r}"
    open(path, 'w').write(c.replace(old, new))
    print(f"  ok {label}")


SS = 'app/src/main/java/com/wy/openx/ui/viewmodel/SessionSlot.kt'
CS = 'app/src/main/java/com/wy/openx/ui/state/ConsoleUiState.kt'
VM = 'app/src/main/java/com/wy/openx/ui/viewmodel/MainViewModel.kt'
MD = 'app/src/main/java/com/wy/openx/ui/screen/Models.kt'
ACS = 'app/src/main/java/com/wy/openx/ui/screen/AgentConsoleScreen.kt'

# ═════════════ A. SessionSlot：徽标状态与横幅相关字段 ═════════════
rep(SS,
    'class SessionSlot(val conversationId: Long) {',
    '''class SessionSlot(val conversationId: Long) {

    // ─────────────── 并发可见性（P2c：侧边栏徽标 / 跨会话横幅）───────────────

    /** 上一帧是否在跑，用于捕捉「跑完」的瞬间（true→false）来置未读标记。 */
    var wasSending = false

    /** 后台跑完、用户还没切回来看 → 侧边栏 🔵。切到该会话即清。 */
    var completedUnread = false

    /** 横幅被「忽略」过。忽略只是收起，不影响 🟡（会话仍在等待）。 */
    var bannerDismissed = false

    /**
     * 上一次等待事项的指纹。指纹变化说明 AI 问了**新的**问题/请求了新的确认，
     * 此时要把 bannerDismissed 复位——否则用户忽略过一次后，
     * 后续新的等待事项就再也弹不出横幅了。
     */
    var awaitingSignatureCache = ""

    /**
     * 侧边栏徽标状态。优先级：等待你 > 运行中 > 有新结果 > 空闲。
     * 「等待你」排最前是因为它需要用户动作，压着不显示会让人误以为还在跑、白等。
     */
    fun badge(): SessionBadge = when {
        awaitingAttention() -> SessionBadge.WAITING
        isSending -> SessionBadge.RUNNING
        completedUnread -> SessionBadge.DONE
        else -> SessionBadge.IDLE
    }

    /** 等待事项的指纹（用于检测「换了个问题」）。 */
    fun awaitingSignature(): String = buildString {
        pendingToolCall?.let { append("t:").append(it.toolName).append(':').append(it.arguments.take(48)) }
        pendingTerminalConfirm?.let { append("|c:").append(it.arguments.take(48)) }
        pendingQuestion?.let { append("|q:").append(it.question.take(48)) }
    }

    /** 给横幅用的一行摘要：让用户不切过去也能判断紧急程度。 */
    fun awaitingSummary(): String {
        pendingToolCall?.let { return "想执行 ${it.toolName}：${it.description.take(60)}" }
        pendingTerminalConfirm?.let { return "想执行命令：${it.arguments.take(60)}" }
        pendingQuestion?.let { return "在问：${it.question.take(60)}" }
        return ""
    }
''', label='A SessionSlot 字段与方法')

# ═════════════ B. ConsoleUiState：徽标枚举 + 横幅数据 + 两个字段 ═════════════
rep(CS,
    'data class QuickTask(val title: String, val prompt: String)',
    '''/**
 * 会话在侧边栏上的运行状态（P2c）。
 * 放在 ui.state 而不是 ui.viewmodel：它是「给 UI 消费」的类型，
 * 且避免 ui.state ← ui.viewmodel 的反向依赖。
 */
enum class SessionBadge { IDLE, RUNNING, WAITING, DONE }

/** 跨会话「等待你」横幅的内容。 */
data class AwaitingBanner(
    val sessionId: Long,
    val title: String,
    val summary: String
)

data class QuickTask(val title: String, val prompt: String)''',
    label='B1 徽标枚举与横幅类型')

rep(CS,
    '    // 聊天记录搜索结果跳转目标；被聊天列表消费后置空\n'
    '    val pendingMessageJump: PendingMessageJump? = null\n'
    ')',
    '''    // 聊天记录搜索结果跳转目标；被聊天列表消费后置空
    val pendingMessageJump: PendingMessageJump? = null,

    /**
     * 各会话的运行状态徽标（键 = conversationId）。由 VM 的 `refreshSessionBadges()`
     * 从槽位真源重算，**不属于会话级字段**（它是所有会话的汇总表），故不进 syncFromUi/projectOnto。
     */
    val sessionStatuses: Map<Long, SessionBadge> = emptyMap(),

    /**
     * 跨会话等待提醒横幅：某个**非前台**会话卡在「需要你确认/回答」时显示。
     * 点「忽略」只收起横幅、会话继续等（🟡不消失）；点「前往处理」切过去由用户亲自决定。
     */
    val awaitingBanner: AwaitingBanner? = null
)''', label='B2 uiState 两个字段')

# ═════════════ C. MainViewModel：重算函数 + 挂接点 ═════════════
rep(VM,
    '    private inline fun editSlot(conversationId: Long, block: SessionSlot.() -> Unit) {\n'
    '        val s = slotOf(conversationId)\n'
    '        s.block()\n'
    '        if (conversationId == _uiState.currentConversationId) {\n'
    '            _uiState = s.projectOnto(_uiState)\n'
    '        }\n'
    '    }',
    '''    private inline fun editSlot(conversationId: Long, block: SessionSlot.() -> Unit) {
        val s = slotOf(conversationId)
        s.block()
        if (conversationId == _uiState.currentConversationId) {
            _uiState = s.projectOnto(_uiState)
        }
        // 徽标/横幅必须**无条件**重算：后台会话的状态变化也要立刻反映到侧边栏，
        // 不能只在「它是前台」时刷。
        refreshSessionBadges()
    }

    /** 防止 refreshSessionBadges 内部写 _uiState 触发再次重算的重入标志。 */
    private var badgesRefreshing = false

    /**
     * 从槽位真源重算侧边栏徽标与跨会话横幅。
     *
     * 挂接点：[editSlot]（所有会话级写入）与 `uiState` 的 setter（兼容仍走 copy 的旧写法）。
     * 这里写的是 `_uiState`（raw backing field），不走自定义 setter，因此不会递归。
     */
    private fun refreshSessionBadges() {
        if (badgesRefreshing) return
        badgesRefreshing = true
        try {
            val fg = _uiState.currentConversationId
            val map = HashMap<Long, SessionBadge>()
            var banner: AwaitingBanner? = null
            for ((cid, s) in slots) {
                // 捕捉「跑完」的瞬间：只在非前台时记未读，前台会话用户已经看到了
                if (s.wasSending && !s.isSending && cid != fg) s.completedUnread = true
                s.wasSending = s.isSending
                map[cid] = s.badge()
                if (cid != fg && s.awaitingAttention()) {
                    val sig = s.awaitingSignature()
                    if (sig != s.awaitingSignatureCache) {
                        // AI 问了新事项 → 撤销上次的「忽略」，重新提醒
                        s.awaitingSignatureCache = sig
                        s.bannerDismissed = false
                    }
                    if (!s.bannerDismissed && banner == null) {
                        banner = AwaitingBanner(
                            sessionId = cid,
                            title = _uiState.conversations.firstOrNull { it.id == cid }?.title ?: "会话",
                            summary = s.awaitingSummary()
                        )
                    }
                }
            }
            _uiState = _uiState.copy(sessionStatuses = map, awaitingBanner = banner)
        } finally {
            badgesRefreshing = false
        }
    }

    /** 横幅点「忽略」：收起提醒，但会话继续等待（侧边栏 🟡 保持）。 */
    fun dismissAwaitingBanner() {
        val b = _uiState.awaitingBanner ?: return
        slotOf(b.sessionId).bannerDismissed = true
        refreshSessionBadges()
    }''',
    label='C1 refreshSessionBadges + dismiss')

# setter 末尾挂一次（覆盖仍走 uiState.copy 的旧写法）
rep(VM,
    '            } else {\n'
    '                _uiState = value\n'
    '                slotOf(nextCid).syncFromUi(value)\n'
    '            }\n'
    '        }',
    '''            } else {
                _uiState = value
                slotOf(nextCid).syncFromUi(value)
            }
            // 有些路径仍走 uiState.copy 改会话级字段（未经 editSlot），这里兜一次重算。
            refreshSessionBadges()
        }''', label='C2 setter 挂重算')

# 切会话清未读
rep(VM,
    '    fun switchConversation(conversationId: Long) {\n'
    '        // P2b：不再 stopStreaming()——切会话只换前台投影，正在跑的会话继续在后台跑。',
    '''    fun switchConversation(conversationId: Long) {
        // 切过来即视为「已读」：🔵 有新结果徽标清掉
        slotOf(conversationId).completedUnread = false
        // P2b：不再 stopStreaming()——切会话只换前台投影，正在跑的会话继续在后台跑。''',
    label='C3 切会话清未读')

# ═════════════ D. Models：ConversationItem 带徽标 ═════════════
rep(MD,
    'import com.wy.openx.data.model.MessageAttachment',
    'import com.wy.openx.data.model.MessageAttachment\nimport com.wy.openx.ui.state.SessionBadge',
    label='D1 import')

rep(MD,
    '    /** 最近活跃时间（epoch millis）：侧边栏按时间分组用。 */\n'
    '    val lastActiveAt: Long = 0\n'
    ')',
    '''    /** 最近活跃时间（epoch millis）：侧边栏按时间分组用。 */
    val lastActiveAt: Long = 0,
    /** 该会话的运行状态（多会话并行时侧边栏用它画圆点徽标）。 */
    val badge: SessionBadge = SessionBadge.IDLE
)''', label='D2 badge 字段')

# ═════════════ E. AgentConsoleScreen ═════════════
# E1 参数（带默认值，不破坏既有调用）
rep(ACS, '    onSwitchConversation: (Long) -> Unit,',
    '    onSwitchConversation: (Long) -> Unit,\n'
    '    /** 跨会话「等待你」横幅点「忽略」：只收起提醒，不打断那个会话。 */\n'
    '    onDismissAwaitingBanner: () -> Unit = {},',
    label='E1 新回调参数')

# E2 注入徽标
rep(ACS, '            conversations = uiState.conversations.map { it.toLegacy() },',
    '            conversations = uiState.conversations.map { c ->\n'
    '                c.toLegacy().copy(badge = uiState.sessionStatuses[c.id] ?: SessionBadge.IDLE)\n'
    '            },',
    label='E2 注入徽标')

# E3 列表项画圆点（插在置顶图标之前）
rep(ACS,
    '''                                            if (item.pinned) {
                                                Icon(
                                                    Icons.Filled.PushPin,''',
    '''                                            // 多会话并行徽标：等待你(橙·静止) > 运行中(绿·呼吸) > 有新结果(蓝·静止)
                                            when (item.badge) {
                                                SessionBadge.WAITING -> Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(Warning)
                                                )
                                                SessionBadge.RUNNING -> {
                                                    // 呼吸圆点：透明度往复渐变，表示「这个会话正在后台跑」
                                                    val pulse = rememberInfiniteTransition(label = "runBadge")
                                                    val a = pulse.animateFloat(
                                                        initialValue = 0.3f, targetValue = 1f,
                                                        animationSpec = infiniteRepeatable(
                                                            animation = androidx.compose.animation.core.LinearEasing(600),
                                                            repeatMode = RepeatMode.Reverse
                                                        ),
                                                        label = "runBadgeAlpha"
                                                    )
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(CircleShape)
                                                            .background(Success.copy(alpha = a.value))
                                                    )
                                                }
                                                SessionBadge.DONE -> Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(Accent)
                                                )
                                                SessionBadge.IDLE -> {}
                                            }
                                            if (item.badge != SessionBadge.IDLE) Spacer(Modifier.width(5.dp))
                                            if (item.pinned) {
                                                Icon(
                                                    Icons.Filled.PushPin,''',
    label='E3 圆点徽标')

# E4 跨会话「等待你」横幅（插在消息 Box 的悬浮层里，位于 Toast 之后、需求6 之前）
rep(ACS,
    '''                // ── 需求6：横屏把顶栏与输入框叠在消息区上方 ──''',
    '''                // ── P2c：跨会话「等待你」横幅 ──
                // 某个**非前台**会话卡在「需要确认 / 需要回答」时浮在聊天页顶部。
                // 「忽略」只收起提醒（会话继续等、侧边栏 🟡 不消失）；
                // 「前往处理」切过去由用户亲自决定——绝不在横幅上替用户批准/拒绝。
                // 用 contentAlignment（同需求6 的作用域原因）；zIndex 4f 压在输入框(2f)/顶栏之上。
                Box(
                    modifier = Modifier.fillMaxSize().zIndex(4f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    AnimatedVisibility(
                        visible = uiState.awaitingBanner != null,
                        enter = fadeIn() + slideInVertically { -it / 2 },
                        exit = fadeOut() + slideOutVertically { -it / 2 }
                    ) {
                        uiState.awaitingBanner?.let { b ->
                            Column(
                                modifier = Modifier
                                    .padding(top = 6.dp, horizontal = 10.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF2A2A2E))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier.size(8.dp).clip(CircleShape)
                                            .background(Warning)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "会话「${b.title}」需要处理",
                                        color = Color.White, fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (b.summary.isNotEmpty()) {
                                    Text(
                                        b.summary, color = Color(0xFFBFBFBF), fontSize = 11.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        "忽略",
                                        color = Color(0xFF9A9A9A), fontSize = 12.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onDismissAwaitingBanner() }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "前往处理 →",
                                        color = Warning, fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onSwitchConversation(b.sessionId) }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 需求6：横屏把顶栏与输入框叠在消息区上方 ──''',
    label='E4 跨会话横幅')

# E5 ACS 需要 SessionBadge 符号
rep(ACS, 'import com.wy.openx.ui.state.ConsoleUiState',
    'import com.wy.openx.ui.state.ConsoleUiState\nimport com.wy.openx.ui.state.SessionBadge',
    label='E5 import SessionBadge')

print('P2c 全部完成')
