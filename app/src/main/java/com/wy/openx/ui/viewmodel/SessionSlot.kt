package com.wy.openx.ui.viewmodel

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.PendingToolCallInfo
import com.wy.openx.data.model.PendingPermissionEval
import com.wy.openx.data.model.QuestionInfo
import com.wy.openx.data.model.TaskItem
import com.wy.openx.ui.state.ChatAttachment
import com.wy.openx.ui.state.ConsoleUiState
import com.wy.openx.ui.state.SessionBadge
import com.wy.openx.ui.state.ThinkingTiming
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/**
 * 单个会话的运行时槽位（多会话并行的地基）。
 *
 * ## 为什么需要它
 * `MainViewModel` 原本是「一个 ViewModel = 一个会话」的硬编码单例：会话身份靠
 * `uiState.currentConversationId` 这一个全局指针，流式执行靠单个 `streamJob`、
 * 流式增量靠单个 `_deltaAccumulator`、撤销栈/压缩锚点/确认回调全是单份。
 * 于是切会话必须先 `stopStreaming()` 把正在跑的请求杀掉——否则 A 的协程会继续
 * 读写「当下属于 B」的那些字段（表现：A 的输出串进 B、A 的文件备份记到 B 的撤销栈、
 * B 的 DB 更新被 A 的 isSending 守卫静默丢弃）。
 *
 * 本类把这些「语义上属于会话、物理上是单例」的状态搬进来，让
 * **切会话 = 换前台投影**，而不是杀流重开。
 *
 * ## 与 uiState 的关系（重要）
 * `uiState` 不是被废弃，而是退化成**前台会话的投影**：
 * - 槽位是真源；`uiState` 的会话级字段永远等于「前台槽位」的对应字段。
 * - 写入统一经 `MainViewModel.uiState` 的 setter 拦截：同会话写回本槽，
 *   切前台则「旧前台存回旧槽 → 新槽覆盖投影」。因此调用方那 40+ 处
 *   `uiState = uiState.copy(...)` 无需逐一定性改造。
 * - **后台会话只写槽位，绝不碰 uiState**（这是最容易出错的地方）。
 *
 * ## 没有搬进来的字段
 * - `lastInputContext` / `sessionRequestCount`：它们的真源已经是
 *   `MainViewModel.conversationCtx: HashMap<Long, CtxRecord>`（按 cid 隔离），
 *   uiState 里那两个只是前台投影。再搬会变成两份真源，反而更糟。
 * - `showDetails`：`/details` 斜杠命令开关，只管「新出现的工具卡片默认展开还是折叠」，
 *   每张卡片自己的展开态另有 `toolGroupState` 记忆。属查看偏好（同字号/主题），留全局。
 * - `currentConversationId`：前台指针本身，必须全局。
 * - `pendingMessageJump`：自带 cid，是一次性导航指令而非会话状态。
 */
class SessionSlot(val conversationId: Long) {

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


    // ─────────────── 请求生命周期句柄 ───────────────

    /** 本会话的流式请求协程。原来是 VM 单例字段，新请求会覆盖句柄导致旧协程失控。 */
    var streamJob: Job? = null

    /** 流式增量落盘的节流协程（150ms 合并一次，避免每个 token 都刷 UI）。 */
    var deltaFlushJob: Job? = null

    /** 本会话的消息 Flow 观察协程。原来 VM 只有一个，观察 B 必然取消观察 A。 */
    var observeJob: Job? = null
    /** 折叠历史（摘要被取代但保留的原文）的独立订阅，与 [observeJob] 分开取消。 */
    var foldedObserveJob: Job? = null

    /** 用户本轮主动停止的标记：区分「取消导致的 Canceled」与真实报错，避免误报。 */
    var stopRequested = false

    /** 流式增量缓冲。两个会话同时吐字若共用一份，会把 A 的文字混进 B 的消息里。 */
    val deltaAccumulator = StringBuilder()

    /**
     * 流式正文的**可追写缓冲**（key = 消息 id）。
     *
     * 为什么不直接 `last.copy(content = last.content + text)`：那是平方级分配——
     * 每 150ms 刷新一次，第 N 次要把前面所有字符再拷一遍，1MB 的回复总共要分配
     * 上百 GB 字节（大量垃圾、GC 持续高压，长回复下直接拖成幻灯片）。
     *
     * 这里按消息 id 持一份 StringBuilder，追加是摊销 O(1)；交给 UI/DB 时才 toString() 一次。
     * 清空时机：[MainViewModel.flushDeltaToMessages] 在该消息收尾（非流式）时移除，
     * 以及 [releaseHeavyState]。
     */
    val streamText = HashMap<Long, StringBuilder>()

    /** 取（或建）指定消息的流式正文缓冲。 */
    fun streamTextOf(messageId: Long): StringBuilder =
        streamText.getOrPut(messageId) { StringBuilder() }

    /**
     * 「重试前清空已展示文本」用的展示态快照：一次 SSE 尝试**首次吐字前**的
     * ([messages] 引用, [deltaAccumulator] 内容)。正文流到一半断网触发重试时，据此精确回退到
     * 本次尝试开始前的展示状态——快照不含本次尝试的任何增量，故重发不会与已展示正文重复。
     *
     * null = 本次尝试尚未吐字（无内容可回退）。由 `MainViewModel.onStreamDelta` 在首个增量到达时
     * 采集，`onStreamReset` 消费后退回 null，供下一轮尝试重新采集。
     */
    var streamResetBaseMessages: List<ChatMessageModel>? = null
    var streamResetBaseAcc: String = ""

    // ─────────────── 等待用户确认的回调（与 pending* 配对）───────────────

    /** 工具调用确认：与 [pendingToolCall] 配对。单槽会让第二个会话的确认请求永远挂起。 */
    var toolConfirmDeferred: CompletableDeferred<Boolean>? = null

    /** 终端命令确认：与 [pendingTerminalConfirm] 配对。 */
    var terminalConfirmDeferred: CompletableDeferred<Boolean>? = null

    // ─────────────── 撤销 / 重做 ───────────────

    /**
     * 撤销栈存的是 `List<ChatMessageModel>` 快照。共享一份会让 B 撤销到 A 的历史，
     * 连带 `CheckpointManager` 的文件备份一起串台（A 的文件改动被 B 的 /undo 回滚）。
     */
    val undoStack = mutableListOf<List<ChatMessageModel>>()
    val redoStack = mutableListOf<List<ChatMessageModel>>()

    // ─────────────── 上下文与压缩状态 ───────────────

    /** 自动压缩冷却计数（见 `AUTO_COMPACT_COOLDOWN`）。初值由 VM 建槽时赋值，避免在此重复常量。 */
    var messagesSinceAutoCompact = 0

    /**
     * 轮次代次：每开一轮新发送就 +1（见 `MainViewModel.sendMessage`）。
     *
     * 用于「立即发送打断长工具」场景：旧轮协程正被阻塞性工具卡死（cancel 奈何不了
     * 工具里的阻塞进程），它的 `finally` 要在工具结束后才跑到；此刻新轮已开出并 +1，
     * 旧轮 finally 读到的代次不同 → 判为**过期轮**，其迟到的收尾（复位 isSending /
     * 自动压缩 / 抢出队 / 补工具结果 / 收尾思考）全部作废，不碰新轮状态。
     */
    var runGeneration = 0

    /**
     * 标题是否已定稿：AI 生成成功或用户手动改名。
     *
     * 定稿后不再自动生成标题（避免覆盖用户/AI 已给出的标题）。
     * 与 [com.wy.openx.data.model.ConversationModel.titleFinalized] 同步；
     * 建槽时灌入、变更时落库，故**跨 App 重启有效**（旧实现只在内存，重启即重生成）。
     */
    var titleFinalized = false

    /**
     * 「上一次 provider 真实 usage 的报告点」在**本会话** messages 里的下标锚。
     * 它是下标而非 id，会话一换即失效——这是必须分槽的强证据。
     */

    /** 本轮下发的 tools schema 的 token 估算（每轮全量发送、占固定额度）。 */
    var currentToolSchemaTokens = 0

    /** 终端最近一次操作的目标 id。不能跨会话乱跑：A 的命令不能跑到 B 的容器里。 */
    var lastToolTargetId = "main"

    // ─────────────── 会话级 UI 状态（前台时投影进 uiState）───────────────

    var messages: List<ChatMessageModel> = emptyList()

    /**
     * 已被摘要取代、但仍保留在库里的历史消息。
     * 与 [messages] 分开：这份**只进 UI**，绝不进请求——压缩的语义是
     * 「模型只看摘要 + 最近若干条，人还能翻到原文」，两者必须分开存。
     */
    var foldedMessages: List<ChatMessageModel> = emptyList()

    /** 折叠区是否展开（按会话记，切回来保持用户上次的展开状态）。 */
    var foldedExpanded: Boolean = false
    var prompt = ""
    var attachments: List<ChatAttachment> = emptyList()
    var tasks: List<TaskItem> = emptyList()

    /**
     * 任务面板是否展开。**默认折叠**（见 `ConsoleUiState.tasksExpanded` 注释）：
     * AI 建任务或更新清单时不自动撑开，只有用户点标题行才展开。
     */
    var tasksExpanded = false
    /**
     * 排队中的用户消息 id（按发送顺序）。见 [ConsoleUiState.messageQueue]。
     */
    var messageQueue: List<Long> = emptyList()

    /**
     * 排队消息携带的附件（key = 消息 id）。
     *
     * 为什么不只存在消息体里：入队瞬间附件可能还在异步落盘（`localPath` 为空），
     * 而 `MessageAttachment` 不保存原始 `content://` URI 与文本预览。这里保一份完整的
     * [ChatAttachment]，消费队列时优先用它；没有记录时再按消息附件重建。
     */
    val queuedAttachments = mutableMapOf<Long, List<ChatAttachment>>()

    /**
     * 「立即发送」打断当前生成时置位：抑制被打断的那一轮 `finally` 里的自动弹出
     * （[MainViewModel.processNextQueuedMessage]）一次，避免它抢先把队里别的消息发掉、
     * 与立即发送竞态。置位后仅被消费一次（消费方负责复位）。
     */
    var suppressQueueAutoDequeueOnce = false
    var isSending = false
    var currentToolName: String? = null
    var thinkingStartedAt = 0L

    /**
     * 每段思考各自的计时（key = 承载该段思考的助手消息 id）。
     *
     * 与 [thinkingStartedAt]（**整轮**起点，工具循环期间不复位）不同，本表按「思考段」记录，
     * 使「思考→工具→再思考」的多段各自计时，而不是从任务开头累加。
     * 段与消息一一对应，故按消息 id 索引；详见 `ConsoleUiState.thinkingSegments`。
     */
    var thinkingSegments: Map<Long, ThinkingTiming> = emptyMap()
    var reconnecting = false
    /** 当前重试次数（1 起）；reconnecting=false 时为 0。UI 显示「重连中 (第 N/M 次)」。 */
    var reconnectAttempt = 0
    /** 本轮重试上限（第 [reconnectAttempt]/[reconnectMax] 次）。 */
    var reconnectMax = 0
    /**
     * 最近一次请求失败的错误信息（输入框上方错误卡片用）。纯 UI 状态：
     * **不落库、不发模型**；下次发送/刷新时清空。
     */
    var lastRequestError: String? = null
    var isCompacting = false
    var pendingToolCall: PendingToolCallInfo? = null

    /**
     * 本次待确认工具调用的**策略评估结果**（仅 shell 类工具会有）。
     * 「始终允许」时据此决定记忆哪些前缀；为空表示沿用旧的整工具粒度。
     */
    var pendingPermissionEval: PendingPermissionEval? = null
    var pendingTerminalConfirm: PendingToolCallInfo? = null
    var pendingQuestion: QuestionInfo? = null
    var agentMode = "build"

    /**
     * 本会话的工具权限档**覆盖值**；null = 跟随全局默认 `settings.toolPermissionMode`。
     * 生效值在 [projectOnto] 里派生，别在这里直接读它当生效值。
     */
    var toolPermissionModeOverride: String? = null

    /** 本会话的思考强度**覆盖值**；null = 跟随全局默认 `settings.reasoningEffort`。 */
    var reasoningEffortOverride: String? = null

    /**
     * 本会话显式选定的提供商 id（「模型选择模式」开关开启时才有值）；
     * null = 跟随全局当前提供商。真源在 Room（conversations.providerId），这里只是它的内存镜像。
     */
    var providerIdOverride: String? = null

    /** 本会话显式选定的模型名；null = 跟随该提供商的当前选中模型。 */
    var modelOverride: String? = null

    // ─────────────── 槽位自身元数据 ───────────────

    /**
     * 消息是否已从 DB 到位。首次进入一个会话时槽内 `messages` 还是空的，
     * 此时若把它投影出去会让屏幕瞬间空白（等 Flow 首次发射才回来，肉眼可见闪一下）。
     * 观察协程首次发射后置 true，之后投影才带 messages。
     */
    var loaded = false

    /** 最近一次被使用的时间，供内存回收排序用。 */
    var lastTouchedAt = System.currentTimeMillis()

    /**
     * 是否值得继续在内存里保留。
     *
     * 消息本体在 Room 里，丢了可以从 DB 重建；**但草稿（prompt/attachments）没有持久化，
     * 丢了就真丢了**。所以只在满足任一条时保留：
     * 正在跑 / 是前台 / 有未发送草稿 / 在等用户。
     */
    fun isWorthKeeping(foregroundId: Long): Boolean =
        conversationId == foregroundId ||
            streamJob?.isActive == true ||
            isSending ||
            prompt.isNotEmpty() ||
            attachments.isNotEmpty() ||
            pendingToolCall != null ||
            pendingTerminalConfirm != null ||
            pendingQuestion != null

    /** 是否有需要用户处理的事项在等待（供侧边栏「🟡等待你」与跨会话横幅使用）。 */
    fun awaitingAttention(): Boolean =
        pendingToolCall != null || pendingTerminalConfirm != null || pendingQuestion != null

    /**
     * 释放占内存的会话态，但**保留轻量标记**（徽标/未读/模型 override/草稿等）。
     *
     * 为什么要分两层：`messages` / `foldedMessages` 里是完整的工具输出与图片 base64，
     * 一个会话就能占几 MB；而这些**都在 Room 里**，丢了能从 DB 重建（切回时由
     * `switchConversation` 重读）。而 [completedUnread] / [badge] / override 这类
     * 没有持久化（或重建代价高），清掉会让侧边栏徽标和会话级模型选择丢失。
     *
     * 调用方必须先确认 [isWorthKeeping] 为 false 并已取消 [observeJob]，
     * 否则 Room 的下一次发射会立刻把消息又填回来。
     */
    fun releaseHeavyState() {
        messages = emptyList()
        foldedMessages = emptyList()
        foldedExpanded = false
        attachments = emptyList()
        queuedAttachments.clear()
        undoStack.clear()
        redoStack.clear()
        streamResetBaseMessages = null
        streamResetBaseAcc = ""
        deltaAccumulator.setLength(0)
        streamText.clear()
        // 置 false 是关键：让 switchConversation 重新从 DB 加载，
        // 也避免 projectOnto 把空列表当成「这个会话没消息」投出去。
        loaded = false
    }

    /**
     * 从一份 uiState 把会话级字段收回槽位（**只读会话级字段，不碰全局字段**）。
     *
     * 由 `MainViewModel.uiState` 的 setter 调用，使既有那 40+ 处
     * `uiState = uiState.copy(messages = ...)` 之类的写入自动落到槽位里，
     * 无需逐处改造调用方。
     */
    fun syncFromUi(s: ConsoleUiState) {
        messages = s.messages
        prompt = s.prompt
        attachments = s.attachments
        tasks = s.tasks
        tasksExpanded = s.tasksExpanded
        messageQueue = s.messageQueue
        isSending = s.isSending
        currentToolName = s.currentToolName
        thinkingStartedAt = s.thinkingStartedAt
        thinkingSegments = s.thinkingSegments
        reconnecting = s.reconnecting
        reconnectAttempt = s.reconnectAttempt
        reconnectMax = s.reconnectMax
        lastRequestError = s.lastRequestError
        isCompacting = s.isCompacting
        pendingToolCall = s.pendingToolCall
        pendingTerminalConfirm = s.pendingTerminalConfirm
        pendingQuestion = s.pendingQuestion
        agentMode = s.agentMode
        lastTouchedAt = System.currentTimeMillis()
    }

    /**
     * 把本槽的会话级字段叠到一份 uiState 上（前台投影）。
     *
     * @param base 调用方刚写入的那份状态（含新的 currentConversationId 与全部全局字段）
     * @return 会话级字段已被本槽覆盖的新状态
     */
    fun projectOnto(base: ConsoleUiState): ConsoleUiState = base.copy(
        messages = if (loaded) messages else base.messages,
        foldedMessages = if (loaded) foldedMessages else base.foldedMessages,
        foldedExpanded = foldedExpanded,
        prompt = prompt,
        attachments = attachments,
        tasks = tasks,
        tasksExpanded = tasksExpanded,
        messageQueue = messageQueue,
        isSending = isSending,
        currentToolName = currentToolName,
        thinkingStartedAt = thinkingStartedAt,
        thinkingSegments = thinkingSegments,
        reconnecting = reconnecting,
        reconnectAttempt = reconnectAttempt,
        reconnectMax = reconnectMax,
        lastRequestError = lastRequestError,
        isCompacting = isCompacting,
        pendingToolCall = pendingToolCall,
        pendingTerminalConfirm = pendingTerminalConfirm,
        pendingQuestion = pendingQuestion,
        agentMode = agentMode,
        // 派生而非搬运：会话没覆盖过就跟随全局默认档。
        // 所以 syncFromUi 里**不能**写这两个字段——那会把「跟随全局」固化成永久覆盖。
        toolPermissionMode = toolPermissionModeOverride ?: base.settings.toolPermissionMode,
        reasoningEffort = reasoningEffortOverride ?: base.settings.reasoningEffort,
        // 模型选择同理：没选过就跟随全局（base.settings 那份是当前生效的提供商/模型）。
        // 注意这两个字段是**展示用派生值**，syncFromUi 不搬。
        //
        // ⚠ 必须受「模型选择模式」开关门控：开关关掉后请求侧已回到全局提供商
        //（effectiveProviderFor 直接返回 null），若这里仍拿旧 override 展示，
        // 就会出现「顶栏写着会话自己的模型、实际发的是全局模型」——比不隔离更坑。
        // 数据不删（override 仍在 Room），重新打开开关时各会话的选择直接恢复。
        //
        // 【新会话默认不选模型】会话级模式下，override 为空**不再回落全局**。
        // 原来这里 `?: base.settings.selectedProviderId` / `?: base.settings.model`，
        // 于是新会话（override 天然为 null）会直接继承「上一个会话最后选的模型」——
        // 用户反馈：不想让最后选择的模型延续到新对话。
        //
        // 现在会话级模式下 override 为空就是**真的没选**：展示层据此显示
        // `[未选择模型]`（AgentConsoleScreen 顶栏），发送前由 resolveProviderBlock
        // 拦下并提示先选模型，不会拿着上一个会话的模型偷偷发出去。
        // 手动选过之后写进 override（selectProviderModel），该会话此后一直沿用自己那份。
        //
        // 全局模式下行为**完全不变**：继续跟随全局当前模型（`else` 分支）。
        effectiveProviderId =
            // 会话级模式（modelGlobalMode=false）取会话 override；全局模式取全局当前提供商
            if (!base.settings.modelGlobalMode)
                providerIdOverride?.takeIf { it.isNotBlank() } ?: ""
            else base.settings.selectedProviderId,
        effectiveModel =
            if (!base.settings.modelGlobalMode)
                modelOverride?.takeIf { it.isNotBlank() } ?: ""
            else base.settings.model
    )
}
