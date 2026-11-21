package com.wy.openx.tools

import android.content.Context
import com.wy.openx.data.model.AIProviderConfig
import com.wy.openx.data.model.AppSettings
import kotlinx.coroutines.CompletableDeferred

internal object AppContextHolder {
    @Volatile
    var context: Context? = null
    @Volatile
    var currentSettings: AppSettings? = null
    /** 当前会话使用的提供商配置（MainViewModel 据 selectedProviderId 解析后设置）。 */
    @Volatile
    var currentProvider: AIProviderConfig? = null
    /**
     * 全部提供商快照（MainViewModel 在 Provider Flow 变化时更新）。仓库层查询专用模型
     * （识图/压缩/起标题）需要按 id 查找任意提供商，但不能异步访问 Repository。
     */
    @Volatile
    var allProviders: List<AIProviderConfig> = emptyList()
    /** 当前会话 id（Long 字符串），供多 Key 轮换的会话粘性使用。 */
    @Volatile
    var currentConversationId: Long? = null
    /**
     * 当前模型的上下文窗口（token），由 MainViewModel 解析后推送（口径与顶部横条/自动压缩一致）。
     * 工具循环在仓库层运行、无法直接读 UI 状态，需要它来做「每轮 LLM 调用前的就地压缩」判定。
     * 0 表示未解析 → 循环内不做压缩（退化为仅靠循环结束后的自动压缩）。
     */
    @Volatile
    var contextWindowTokens: Int = 0
    @Volatile
    var toolRegistry: ToolRegistry? = null
    @Volatile
    var terminalEngine: TerminalEngine? = null
    @Volatile
    var containerEngine: com.wy.openx.container.LinuxContainerEngine? = null
    /**
     * 每会话待回答的 ask_user 通道。键 = conversationId（0 = 无会话上下文的兜底）。
     * 旧版是单个 `pendingQuestionDeferred`：并发时第二个会话的 ask_user 会被
     * 「已有一个待回答提问」挡掉、或覆盖第一个 → 第一个永挂。按会话分槽后各自独立。
     */
    private val pendingQuestions = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<String>>()
    fun setPendingQuestion(conversationId: Long, deferred: CompletableDeferred<String>) { pendingQuestions[conversationId] = deferred }
    fun hasPendingQuestion(conversationId: Long): Boolean = pendingQuestions[conversationId] != null
    fun completePendingQuestion(conversationId: Long, answer: String): Boolean =
        pendingQuestions.remove(conversationId)?.complete(answer) ?: false
    /** 只清理仍属于自己的那条，避免误清同会话新发起的提问。 */
    fun clearPendingQuestionIfOwned(conversationId: Long, deferred: CompletableDeferred<String>) {
        if (pendingQuestions[conversationId] === deferred) pendingQuestions.remove(conversationId)
    }

    /**
     * 随协程上下文下传的「本轮会话 id」元素。MainViewModel 在发起请求的协程上注入，
     * UserQuestionTool 据此把自己的提问归到正确会话——不能读全局 `currentConversationId`
     *（那是「当下前台」，并发时后台会话的 ask_user 会记到前台会话名下）。
     */
    class ConversationId(val id: Long) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<ConversationId>
    }
    /** 由 MainViewModel 注册：AI terminal 危险命令弹确认框（返回是否允许执行）。 */
    @Volatile
    var terminalConfirmRequester: (suspend (command: String, reason: String) -> Boolean)? = null
    /**
     * 由 MainViewModel 注册：工具循环内「就地压缩」状态回调
     * status ∈ "compressing" / "completed" / "failed"。
     * 仓库层在压缩开始前 / 结束后分别调用，UI 据此插入/原地更新一条「压缩标记」消息，
     * 让长任务中途的自动压缩对用户可见（正在压缩 → 已压缩/压缩失败），不刷摘要全文。
     */
    @Volatile
    var toolLoopCompactionNotifier: ((status: String, summary: String?, foldedCount: Int) -> Unit)? = null

    /**
     * 由 MainViewModel 注册：并行执行批内工具时撞到 429 限流的上报通道。
     *
     * 参数：本批所属会话 id（`ProviderCallContext.conversationId`，0 = 未知）+ 本批命中的工具数。
     * **必须带会话 id**：用户在某会话选过「保持并行」后只静默那个会话，而并发场景下
     * 「当前前台会话」与「上报的会话」未必是同一个，靠 UI 侧读当前会话会张冠李戴。
     *
     * 为什么需要它：并行把同批工具一次性发出去，瞬时并发容易超过供应商容忍度而吃 429，
     * 但表现只是「某几个工具失败」（各自在结果里写自己的错），用户看不到「并发太高」这个
     * 真正原因。仓库层无权改设置，只能上报，由 UI 决定是否建议切回串行。
     */
    @Volatile
    var rateLimitNotifier: ((conversationId: Long, hitCount: Int) -> Unit)? = null

    /**
     * 工具循环内「就地压缩」的结果，**等待回合结束后由 MainViewModel 落库**。
     *
     * ## 为什么需要这一层中转
     * 就地压缩只重建了**本次请求的消息副本**，历史一条没少。若不落库，下一轮
     * `buildTurnHistory()` 又从完整历史重建 → 上下文涨回压缩前的量级 → 反复压缩
     * （实测：压完显示 25K，下一轮真实请求又是 168K）。
     * 顺手把摘要与 cutoff 落库」，下一轮 SQL `id > cutoff` 直接不读已折叠行。
     *
     * openx 用 `folded=1` 只需把
     * 「摘要 + 折叠边界」交回 VM 走既有的 `markFoldedUpTo` 落库路径。
     *
     * ## 为什么推迟到回合结束
     * 压缩发生在工具循环**中途**，此时 VM 仍在通过 `editSlot` 往 `slot.messages` 追加
     * 本轮的工具结果。此刻整体替换 `slot.messages` 会与那些追加竞争、丢消息。
     * 等 `executeAndFollowUp` 返回、流式写入结束再落库，则槽位已稳定。
     *
     * @param boundarySrcMsgId 折叠边界：保留下来的尾部**第一条**消息在 `slot.messages` 里的
     *        来源 id（[com.wy.openx.data.remote.ChatMessageDto.srcMsgId]）。
     *        VM 据此在槽位里精确定位。**不能用条数**：一条 slot 消息会展开成多条 DTO
     *        （工具消息拆 assistant + tool），两个「条数」毫无关系。
     *        null = 边界消息不带来源 id（如本轮新追加的 system 消息），此时退回 [boundaryToolCallId]。
     *        宁可这一轮不跨轮生效，也不能猜错边界把历史折坏——上一版正是猜错导致 358 条折成 1 条。
     * @param boundaryToolCallId 边界的**工具调用 id** 回退定位。工具循环中途压缩时，边界常落在
     *        **本轮刚追加**的 assistant(tool_calls)/tool 结果 DTO 上——这些 DTO 由仓库层即时构造，
     *        **没有** [boundarySrcMsgId]（只有从库重建的历史 DTO 才有）。但它们携带 `toolCallId`，
     *        而对应的槽位消息同样存着 `toolCallId`，故可据此精确回定位。
     *        实测：只用 srcMsgId 时边界恒「无」→ 落库整条跳过 → 反复压缩（本字段即为修此而加）。
     */
    @Volatile
    var pendingToolLoopCompaction: PendingToolLoopCompaction? = null

    /** 工具循环压缩的待落库结果（见 [pendingToolLoopCompaction]）。 */
    data class PendingToolLoopCompaction(
        val conversationId: Long,
        val summary: String,
        val boundarySrcMsgId: Long?,
        val boundaryToolCallId: String? = null
    )

    /**
     * 由 MainViewModel 注册：识图专用模型调用（「默认模型→识图模型」）。view_image 工具
     * 读好图片 dataUrl 后回调，返回模型对图片的文本描述；未注册视为识图功能不可用。
     */
    @Volatile
    var visionAnalyzer: (suspend (imageDataUrl: String, query: String) -> String)? = null

    /**
     * 插件发起的 AI 任务（`ctx.chat.runTask`）。由 MainViewModel 注册。
     *
     * 返回 `(conversationId, started)`：started=false 表示没起成（未选模型、
     * 并发闸门满等），此时 conversationId 为 0。
     *
     * **只发起、不等结果**：任务跑在独立会话里，用户去那个会话看输出。
     * 这样不会阻塞插件的定时回调，也不会往用户当前对话里插话。
     */
    @Volatile
    var pluginChatRunner: (suspend (prompt: String, newConversation: Boolean, workspacePath: String?) -> Pair<Long, Boolean>)? = null

    var taskExecutor: (suspend (operation: String, args: Map<String, Any>) -> String)? = null
    /**
     * 子代理运行器（ChatRepository 注册）。SubAgentTool → SubAgentDispatcher → ChatRepository
     * 构成构造期循环（ToolRegistry 由 MainViewModel 先建、ChatRepository 后建），
     * 故用延迟查找打断：注册晚于工具注册，真正调用时才取。
     */
    @Volatile
    var subAgentRunner: SubAgentRunner? = null
    /**
     * 子代理提供商解析（MainViewModel 注册）。参数 = 发起子代理的 **AI 会话 id**：
     * 会话级模型模式下要取该会话自己选的模型（否则子代理会跳到全局提供商）。
     * 返回 null 表示沿用当前聊天模型。
     */
    @Volatile
    var subAgentProviderProvider: ((Long) -> AIProviderConfig?)? = null
    /**
     * 按会话解析上下文窗口（MainViewModel 注册）。参数 = **AI 会话 id**，返回该会话
     * 生效模型的窗口（token），算不出可返回 0。
     *
     * 为什么需要它：仓库层（子代理/识图/起标题）要判定「就地压缩」阈值，但
     * `contextWindowTokensFor` 在 VM 里，仓库层够不着。原先只能退回下面的全局单槽
     * [contextWindowTokens]，而并发时那是**别的会话**写的值——拿大了该压不压
     * （可能直接超窗报错），拿小了提前压缩（白烧一次摘要请求）。
     *
     * 与 [subAgentProviderProvider] 同一套路：延迟注入，避免 VM ↔ 仓库的构造期循环。
     * 未注册时调用方退回 [contextWindowTokens]，行为与改动前一致。
     */
    @Volatile
    var contextWindowProvider: ((Long) -> Int)? = null
    /**
     * 子代理进度文本出口（MainViewModel 注册）。子代理运行在仓库层、拿不到 UI 回调，
     * 故用单通道把「SubAgent #n 开始/调用某工具/完成」等进度串到父级 subagent_dispatch 的工具卡片上，
     * 避免并行子任务变成一段黑盒。
     */
    @Volatile
    var subAgentProgressSink: ((String) -> Unit)? = null
    /**
     * 子代理的工具审批通道（MainViewModel 在发送期间注入父级同一套规则）。
     * 安全关键：general 角色继承父级全部工具（含 terminal / writeFile），
     * 若子代理绕过确认直接执行，等于把「弹窗确认」这道闸门对子任务失效。
     * 未注册时 runSubagent 会拒绝执行写/执行类工具而不是默默放行。
     */
    @Volatile
    var subAgentToolApprover: (suspend (toolName: String, toolArgs: String) -> Boolean)? = null
    /**
     * 父级权限档位是否为「不需逐条审批」那种（MainViewModel 发送期间注入）。
     *
     * 与 [subAgentToolApprover] 成对使用：approver 只负责 confirm / workspace 两档，
     * 其余档它返回 null——那不是「没有审批通道」，而是「父级本来就不问」，子代理应当**放行**写/执行类工具。
     * 用「approver == null」表达这件事会与「通道未注册」撞车，
     * 后果是 auto 档下子代理写文件/跑命令全被拒（实测：writeFile 与 terminal 连续被拒）。
     */
    @Volatile
    var subAgentToolAutoApprove: Boolean = false
    /**
     * 工作流的工具审批通道（MainViewModel 注入）。
     * 安全关键：工作流步骤里可以带 terminal / writeFile / git，引擎直接调 ToolRegistry 执行，
     * **不会**经过主链路的 `buildToolApprover()`，所以这条通道就是唯一一道闸门。
     * 未注册时（`WorkflowTool` 取不到）一律拒绝写/执行类工具——与子代理同样的失败关闭取向。
     */
    @Volatile
    var workflowStepGate: WorkflowStepGate? = null
}
