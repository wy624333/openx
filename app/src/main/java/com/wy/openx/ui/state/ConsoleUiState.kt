package com.wy.openx.ui.state

import android.net.Uri
import com.wy.openx.data.model.AgentModel
import com.wy.openx.data.model.ApiPreset
import com.wy.openx.data.model.AppSettings
import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.ConversationModel
import com.wy.openx.data.model.PendingToolCallInfo
import com.wy.openx.data.model.QuestionInfo
import com.wy.openx.data.model.SkillPreset
import com.wy.openx.data.model.StatModel
import com.wy.openx.data.model.TaskItem
import com.wy.openx.data.model.ToolCallModel
import com.wy.openx.data.store.McpServerEntry
import com.wy.openx.data.store.McpTestResult
import com.wy.openx.data.log.LogEntry

/**
 * 会话在侧边栏上的运行状态（P2c）。
 * 放在 ui.state 而不是 ui.viewmodel：它是「给 UI 消费」的类型，
 * 且避免 ui.state ← ui.viewmodel 的反向依赖。
 */
enum class SessionBadge { IDLE, RUNNING, WAITING, DONE }

/**
 * 一段思考的计时（见 [ConsoleUiState.thinkingSegments]）。
 *
 * @property startAt 该段开始的绝对时间戳（epoch millis）。VM 在**这段思考首次产出内容时**记录。
 * @property endAt 该段结束的绝对时间戳；null = 仍在进行中。
 *   VM 在「发起工具调用」与「整轮结束」两处收尾时落定。
 */
data class ThinkingTiming(
    val startAt: Long,
    val endAt: Long? = null
) {
    /** 该段用时（秒）。`endAt` 为空（进行中）时按「到现在」算。 */
    fun elapsedSec(now: Long = System.currentTimeMillis()): Int =
        (((endAt ?: now) - startAt) / 1000).toInt().coerceAtLeast(0)

    /** 是否已结束（有明确用时，UI 应停止跳秒）。 */
    val finished: Boolean get() = endAt != null
}

/** 跨会话「等待你」横幅的内容。 */
data class AwaitingBanner(
    val sessionId: Long,
    val title: String,
    val summary: String
)

data class QuickTask(val title: String, val prompt: String)
data class FileRefSuggestion(val name: String, val path: String)

/** 输入区待发送的附件（图片或文本文件）。图片会同时以 base64 发给模型、并落盘到容器可读目录。 */
data class ChatAttachment(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val isImage: Boolean,
    /** 宿主绝对路径（已复制到工作区内，容器内可见）。 */
    val localPath: String = "",
    /** 容器内路径，如 /root/workspace/.openx/uploads/xxx.png。 */
    val containerPath: String = "",
    /** 文本文件的读取预览（最多 4000 字）。 */
    val textPreview: String = "",
    /** 落盘后的字节大小（未落盘为 0）。 */
    val sizeBytes: Long = 0
)

/** 聊天记录搜索命中后待跳转的目标：nonce 保证连续跳同一条也能重新触发。 */
data class PendingMessageJump(
    val conversationId: Long,
    val messageId: Long,
    val nonce: Long = 0L
)

data class ConsoleUiState(
    val title: String = "OpenX",
    val subtitle: String = "模型：Qwen / DeepSeek · 模式：Agent · 工具可用",
    val prompt: String = "",
    val currentConversationId: Long = 1,
    val conversations: List<ConversationModel> = emptyList(),
    /** 已归档会话（「历史」页数据源）；主列表 [conversations] 不含它们。 */
    val archivedConversations: List<ConversationModel> = emptyList(),
    val agents: List<AgentModel> = emptyList(),
    val messages: List<ChatMessageModel> = emptyList(),
    /** 压缩折叠区：已折叠但仍可翻阅的历史消息（渲染在 [messages] 之上，不进模型请求）。 */
    val foldedMessages: List<ChatMessageModel> = emptyList(),
    val foldedExpanded: Boolean = false,
    val stats: List<StatModel> = emptyList(),
    val tools: List<ToolCallModel> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val apiPresets: List<ApiPreset> = emptyList(),
    val skills: List<SkillPreset> = emptyList(),
    /** 当前项目的项目技能（按 currentProject 隔离，与 skills 全局列表同构）。 */
    val projectSkills: List<SkillPreset> = emptyList(),
    // [PLUGIN-MOD] 插件列表（数据源 PluginStore）
    val plugins: List<com.wy.openx.plugin.InstalledPlugin> = emptyList(),
    /** 服务型插件的运行状态：pluginId → 监听端口（0/不在表 = 未运行）。 */
    val pluginServices: Map<String, Int> = emptyMap(),
    val pluginServiceStarting: Set<String> = emptySet(),
    val pluginServiceErrors: Map<String, String> = emptyMap(),
    /**
     * 插件的 env 快照：pluginId → (key → value)。
     * 「插件配置」页里清单声明的 `settings` 从这里读当前值。
     */
    val pluginEnv: Map<String, Map<String, String>> = emptyMap(),
    /** 所有已启用插件的快捷项，供侧边栏快捷区渲染。 */
    val pluginQuickGroups: List<com.wy.openx.plugin.PluginQuickGroup> = emptyList(),
    /** 工作流管理页列表（数据源 WorkflowStore）。 */
    val workflows: List<com.wy.openx.data.store.Workflow> = emptyList(),
    /** 正在执行的工作流名；非 null 时管理页的「运行」按钮禁用，防重复触发。 */
    val runningWorkflowName: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val quickTasks: List<QuickTask> = emptyList(),
    val showDetails: Boolean = false,
    val agentMode: String = "build",
    /**
     * 当前会话**生效**的工具权限档（= 会话覆盖 ?? 全局默认 `settings.toolPermissionMode`）。
     * UI 与请求侧都读这个，不要再读 settings 里那份（那是全局默认）。
     */
    val toolPermissionMode: String = "allow",
    /** 当前会话**生效**的思考强度（= 会话覆盖 ?? 全局默认）。 */
    val reasoningEffort: String = "default",
    /**
     * 当前会话**生效**的提供商 id / 模型名（= 会话选择 ?? 全局当前）。
     *
     * 「模型选择模式」开关开启且本会话选过模型时是会话自己的值，否则跟随全局。
     * 顶栏副标题、模型标签、上下文窗口换算都应该读这两个，而不是 `settings.model`。
     * 它们是**派生展示值**，不写回槽位。
     */
    val effectiveProviderId: String = "",
    val effectiveModel: String = "",
    val showCommandPalette: Boolean = false,
    val isSending: Boolean = false,
    val currentToolName: String? = null,
    // AI 开始思考的绝对时间戳（epoch millis），用于「AI 正在思考」计时在离开/返回聊天页时保持连续
    val thinkingStartedAt: Long = 0L,
    /**
     * **每段思考各自的计时**（key = 承载该段思考的助手消息 id）。
     *
     * 一轮任务里 AI 常「思考 → 调工具 → 再思考 → 再调工具」，这是**多段**思考。
     * 每段落在**各自的助手消息**上：工具调用前会把已生成的思考消息落库
     * （`MainViewModel` 的 `onToolCallRequest`），之后新一轮思考由
     * `flushDeltaToMessages` 新建一条 assistant 消息。所以**按消息 id 天然就能分段**。
     *
     * ⚠ 为什么不能直接用 [thinkingStartedAt]：那是**整轮**的起点，在工具循环期间
     * **不复位**（只在整轮结束时归零）。拿它给每段计时，后续每段都会从「任务开头」
     * 累加，于是出现 10 → 30 → 50 秒的递增；用户要的是「每段思考各自的用时」。
     * 本表提供每段自己的起止。
     * 分卡计时（每张卡自带 `startTime` / `endTime`）。
     *
     * ⚠ 结束时刻（`endAt`）**必须由 VM 记录**，不能只靠 UI 端自己定格：
     * 思考块是 LazyColumn 的 item，用户滚走时该 item 会被销毁、计时协程随即取消，
     * 定格值只停在「滚走前的最后一秒」而非真实结束时刻（会少算几秒）。
     */
    val thinkingSegments: Map<Long, ThinkingTiming> = emptyMap(),
    // 网络重连中：请求失败正在指数退避重试，UI 显示"重连中 (第 N/M 次)"
    val reconnecting: Boolean = false,
    /** 当前重试次数（1 起）；reconnecting=false 时为 0。 */
    val reconnectAttempt: Int = 0,
    /** 本轮重试上限（第 [reconnectAttempt]/[reconnectMax] 次）。 */
    val reconnectMax: Int = 0,
    /**
     * 最近一次请求失败的错误信息（输入框上方错误卡片用）。纯 UI 状态：不落库、不发模型，
     * 一直显示直到下次发送/操作。
     */
    val lastRequestError: String? = null,
    val toast: String? = null,
    /**
     * 最近一次插件导入的结果（非 null 时管理页弹结果窗）。
     *
     * 放在这里而不是管理页局部状态：导入由 ViewModel 异步完成，弹窗要等它回来才出现。
     * 与 toast 一样是纯 UI 状态，不落库、不进 [SessionSlot]（不属于会话级字段）。
     */
    val pluginInstallResult: com.wy.openx.plugin.PluginInstallResult? = null,
    val memoryItems: List<com.wy.openx.data.store.MemoryItem> = emptyList(),
    /**
     * 知识库条目（从历史对话里提炼的经验/踩坑/约定，全局共用；存 `openx/knowledge/`）。
     * 与 memoryItems 不同：它**不逐条注入提示词**，只注入目录，正文靠 knowledge_search 查。
     */
    val knowledgeItems: List<com.wy.openx.data.store.KnowledgeEntry> = emptyList(),
    /** 当前项目的项目记忆（按 settings.currentProject 隔离）；未选项目时为空。 */
    val projectMemoryItems: List<com.wy.openx.data.store.MemoryItem> = emptyList(),
    /**
     * 内置 AI 规则（`memory/agent/` 下的文档，随 APK 发布；用户改动写 `memory/agent-custom/` 覆盖层）。
     * 每次请求**完整注入** system prompt，不占长期记忆的注入条数名额。
     *
     * 这里始终是**全部**（含已停用的）：设置页「提示词」子页要拿它渲染列表，
     * 开关只在注入处过滤（见 MainViewModel 组装 system prompt 的地方）。
     */
    val agentRuleItems: List<com.wy.openx.data.store.AgentRule> = emptyList(),
    val totalTokens: Long = 0L,
    val totalInputTokens: Long = 0L,
    val tokenDetails: Map<String, Long> = emptyMap(),
    val cacheHitTokens: Long = 0L,
    val cacheHitDetails: Map<String, Long> = emptyMap(),
    val lastInputContext: Int = 0,
    val sessionRequestCount: Int = 0,
    val pendingToolCall: PendingToolCallInfo? = null,
    val pendingTerminalConfirm: PendingToolCallInfo? = null,
    val pendingQuestion: QuestionInfo? = null,
    val tasks: List<TaskItem> = emptyList(),
    /**
     * 任务面板是否展开。**默认折叠**（实现 TodoDashboardBar 的 `mutableStateOf(false)`）。
     *
     * 用户要求「创建任务后自动折叠」：AI 建任务/每更新一步都不该撑开面板挤占输入区。
     * 折叠时标题行仍显示「N/M 完成」与当前进行中的任务，信息足够；
     * 想看全量清单由用户点标题行展开（展开态只由用户掌控）。
     */
    val tasksExpanded: Boolean = false,

    /**
     * 排队中的用户消息 id（按发送顺序）。
     * 存 id 而非文本：消息本体已经作为气泡进了 messages 且已落库，
     * 队列只负责顺序，指针失效时能从 messages 里复原而不会丢内容。
     */
    val messageQueue: List<Long> = emptyList(),

    val mcpServers: List<McpServerEntry> = emptyList(),
    /** 正在压缩上下文（供聊天页把「AI 正在思考」换成「正在压缩上下文」） */
    val isCompacting: Boolean = false,
    /** 各 MCP Server 的最近一次连接测试结果（键为 server 名）；仅内存，重启清空 */
    val mcpTestResults: Map<String, McpTestResult> = emptyMap(),
    /** 正在测试连接的 MCP Server 名集合 */
    val mcpTesting: Set<String> = emptySet(),

    // 聊天记录搜索结果跳转目标；被聊天列表消费后置空
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
    val awaitingBanner: AwaitingBanner? = null,

    /**
     * 并行执行批内工具时频繁撞 429 限流，给出「是否切回串行」的提示弹窗。
     *
     * 纯 UI 提示：不落库、不进模型、不参与会话槽位同步（关闭即消失）。
     * 之所以不静默改设置，是因为串行会让同批工具耗时变长，这个取舍该由用户拍板。
     */
    val rateLimitWarning: Boolean = false
)
