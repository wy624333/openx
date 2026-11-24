package com.wy.openx.ui.viewmodel

import android.app.Application
import android.content.Context
import android.media.RingtoneManager
import android.util.Log
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonPrimitive
import com.wy.openx.data.local.DatabaseProvider
import com.wy.openx.data.local.LlmCallRecordEntity
import com.wy.openx.data.model.ApiPreset
import com.wy.openx.data.model.AIProviderConfig
import com.wy.openx.data.model.PROVIDER_SOURCE_PLUGIN
import com.wy.openx.data.model.ProviderType
import com.wy.openx.data.model.AppSettings
import com.wy.openx.data.model.stripThinkingBlocks
import com.wy.openx.data.model.sanitized
import com.wy.openx.data.util.ChatUtils
import com.wy.openx.container.FileLogger
import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.service.FloatingOverlayController
import com.wy.openx.service.OverlaySession
import com.wy.openx.service.OverlaySessionState
import com.wy.openx.data.model.ConversationModel
import com.wy.openx.data.model.MessageRole
import com.wy.openx.data.model.ModelMetaFetcher
import com.wy.openx.data.model.PendingPermissionEval
import com.wy.openx.data.model.PendingToolCallInfo
import com.wy.openx.data.model.QuestionInfo
import com.wy.openx.data.model.TaskItem
import com.wy.openx.data.model.TaskStatus
import com.wy.openx.ui.navigation.AppScreen
import com.wy.openx.data.repository.ChatRepository
import com.wy.openx.data.repository.ConversationRepository
import com.wy.openx.data.repository.TaskRepository
import com.wy.openx.data.repository.MessageRepository
import com.wy.openx.data.remote.ChatMessageDto
import com.wy.openx.data.store.McpServerConfig
import com.wy.openx.data.store.McpServerEntry
import com.wy.openx.data.store.McpScope
import com.wy.openx.data.store.McpServerStore
import com.wy.openx.data.store.McpTestResult
import com.wy.openx.data.log.AppLogStore
import com.wy.openx.data.log.plus
import com.wy.openx.data.store.SettingsStore
import com.wy.openx.data.util.FileContentReader
import com.wy.openx.tools.AppContextHolder
import com.wy.openx.tools.CheckpointManager
import com.wy.openx.tools.TerminalEngine
import com.wy.openx.tools.QuickJsMcpClient
import com.wy.openx.tools.ToolCall
import com.wy.openx.tools.ToolCallFunction
import com.wy.openx.tools.ToolPermissionPolicyEngine
import com.wy.openx.tools.WorkspacePathPolicy
import com.wy.openx.tools.ToolRegistry

import com.wy.openx.ui.state.AwaitingBanner
import com.wy.openx.ui.state.ConsoleUiState
import com.wy.openx.ui.state.PendingMessageJump
import com.wy.openx.ui.state.SessionBadge
import com.wy.openx.ui.state.ThinkingTiming
import com.wy.openx.ui.command.buildSlashHelp
import com.wy.openx.ui.command.canonicalBuiltinCommand
import com.wy.openx.ui.screen.formatTokens
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import com.wy.openx.plugin.PluginInstallResult
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 检查点与撤销选项 */
enum class RewindOption {    RESTORE_CODE_AND_CONVERSATION,
    RESTORE_CONVERSATION,
    RESTORE_CODE
}

/** 「全部测试」的进度快照。 */
data class TestAllProgress(val done: Int, val total: Int)

/**
 * 摘要占位消息前缀。
 *
 * 「写摘要」「渲染摘要块」「恢复时删摘要」三处都必须用同一个常量：字面量各写一份时，
 * 改文案就会漏掉某一处——曾经就因为只删了摘要、没删压缩通知，导致恢复后聊天页
 * 还挂着「已压缩成摘要」的假提示。
 *
 * ⚠ 新压缩**不再写**这个前缀：改成 marker + summary 成对两行
 * （见 [COMPACTION_MARKER]）。本常量仅保留用于**清理升级前的旧摘要行**与若干
 * 「不算用户提问」的判定。
 */
internal const val SUMMARY_PREFIX = "[对话摘要]"

/**
 * 压缩锚点行的内容。
 *
 * 每次压缩写入一对行：marker（USER，本常量）+ summary（ASSISTANT，摘要正文）。
 * marker 的作用是给压缩卡一个**稳定的时序锚点**——它落在压缩发生的那一刻，
 * 所以卡片正好显示在「最后一个执行完的工具」下面；summary 紧随其后成为折叠历史的替代物。
 *
 * 旧实现只有一行 `[对话摘要]…`，靠内容前缀嗅探定位它在列表里的位置，而摘要 id 是压缩时
 * 新建的（可能很小）、排在它前面的折叠历史里又混着内存大 id → 卡片会跑到折叠历史**之前**。
 * 有 marker + 会话内单调 `seq` 之后，位置由库统一决定，UI 不再需要猜。
 */
internal const val COMPACTION_MARKER = "[上下文已压缩]"

/**
 * **请求侧**顺序：把压缩产物（marker + 紧随其后的摘要）提到历史最前，其余按原序跟随。
 *
 * `partition { isCompactionMarker || isContextSummary }`
 * 再 `compaction + retained`。模型必须先读摘要、再读保留历史 —— 摘要描述的是保留尾**之前**
 * 发生的事，留在末尾会被当成最后一条发言。
 *
 * 与**展示**顺序故意相反，两套顺序各司其职（详见 `MessageDao.commitCompaction`）：
 *   · 展示（按 seq）：…保留尾… → marker → summary → 压缩后新消息
 *     （卡片紧贴「触发压缩的那个工具」）
 *   · 模型请求：marker → summary → …保留尾…
 *
 * 按**下标**而非 id 判定：会话里存在未落库消息（id 可能重复或为 0），
 * id 级去重会把不相干的消息误当压缩产物提上来。
 */
internal fun hoistCompactionArtifacts(list: List<com.wy.openx.data.model.ChatMessageModel>): List<com.wy.openx.data.model.ChatMessageModel> {
    val idx = list.indices.filter { list[it].content == COMPACTION_MARKER }
    if (idx.isEmpty()) return list
    val hoist = (idx + idx.map { it + 1 }).filter { it in list.indices }.toSet()
    val (head, rest) = list.withIndex().partition { it.index in hoist }
    return head.map { it.value } + rest.map { it.value }
}

/**
 * 从请求历史里剔除「本轮用户输入」那条消息。
 *
 * 不能再用 `dropLast(1)`：压缩提交后展示序是「保留尾 → marker → 摘要」
 * （见 [com.wy.openx.data.local.MessageDao.commitCompaction]），最后一条可能是摘要，
 * 而不是本轮用户消息。盲删会把摘要从请求里删掉；同时保留尾里的本轮消息仍会被
 * `sendMessage` 追加一次 → 模型收到两条相同的用户消息、却看不到摘要
 * （真机实证：发「在吗」触发压缩后只剩压缩卡片，回答也被拼进了摘要）。
 */
internal fun historyWithoutCurrentTurn(
    messages: List<com.wy.openx.data.model.ChatMessageModel>,
    currentTurnMsgId: Long?
): List<com.wy.openx.data.model.ChatMessageModel> =
    if (currentTurnMsgId == null) messages else messages.filterNot { it.id == currentTurnMsgId }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val chatRepository = ChatRepository()

    init {
        // 会话状态快照：给插件读的**聚合状态**（忙闲 / 正在跑的工具 / 上下文用量 / 待确认类型）。
        // 只给状态不给内容，见 HostSessionState 的说明。
        // lambda 延迟读 uiState：本 init 块执行时 _uiState 尚未构造完。
        com.wy.openx.plugin.HostSessionState.register {
            val s = uiState
            com.wy.openx.plugin.HostSessionSnapshot(
                busy = s.isSending,
                // “思考中”：整轮已开始，但还没进到具体工具（否则算工作）
                thinking = s.thinkingStartedAt > 0L && s.currentToolName == null,
                tool = s.currentToolName,
                pending = when {
                    s.pendingToolCall != null -> "approval"
                    s.pendingTerminalConfirm != null -> "terminal"
                    s.pendingQuestion != null -> "question"
                    else -> null
                },
                contextTokens = s.lastInputContext,
                reconnecting = s.reconnecting
            )
        }
        // 子代理接线：运行器（延迟查找，打断 SubAgentTool→Dispatcher→ChatRepository 的构造期循环）
        // 与专用模型（设置页「子代理模型」，未配置则沿用当前聊天模型）。
        com.wy.openx.tools.AppContextHolder.subAgentRunner = object : com.wy.openx.tools.SubAgentRunner {
            override suspend fun runSubagent(req: com.wy.openx.tools.SubAgentRunRequest): com.wy.openx.tools.SubAgentRunResult {
                // 子代理是真实调用（且常用「默认模型→子代理模型」的专用模型），
                // 用量要进统计：kind=subagent，归属与实际请求同源（subAgentProviderProvider 同口径）。
                // 会话 id 从协程上下文取——子代理在发起会话的协程树内跑。
                val subCid = kotlin.coroutines.coroutineContext[com.wy.openx.tools.AppContextHolder.ConversationId]?.id ?: 0L
                val subT0 = System.currentTimeMillis()
                val result = chatRepository.runSubagent(req)
                recordLlmCall(
                    kind = "subagent",
                    conversationId = subCid,
                    forced = resolveSpecialModel("subagent") ?: effectiveProviderFor(subCid),
                    inputTokens = result.inputTokens,
                    outputTokens = result.outputTokens,
                    cachedInputTokens = result.cacheTokens,
                    startedAt = subT0,
                    durationMillis = (System.currentTimeMillis() - subT0).toInt(),
                    success = result.status == "completed",
                    errorMessage = result.errorMessage
                )
                return result
            }
        }
        com.wy.openx.tools.AppContextHolder.subAgentProviderProvider = { cid: Long ->
            resolveSpecialModel("subagent") ?: effectiveProviderFor(cid)
        }
        // 仓库层要判就地压缩阈值却够不着 VM 的 contextWindowTokensFor，
        // 原先只能读全局单槽 AppContextHolder.contextWindowTokens——并发时那是别的会话的值。
        // 注入按会话的解析口径，子代理/识图/起标题都走这条。
        com.wy.openx.tools.AppContextHolder.contextWindowProvider = { cid: Long ->
            if (cid > 0L) contextWindowTokensFor(cid) else 0
        }
        // 知识提炼在后台线程落盘，完成后回主线程通知这里重扫知识库，让管理页/提示词目录跟上。
        com.wy.openx.data.knowledge.KnowledgeWorker.onExtracted = { loadKnowledge() }
    }
    private val appContext = application.applicationContext
    /** 记忆索引单条内联上限（字符）；超过则截断，正文靠 memory_read 按需取。 */
    private val MEMORY_INLINE_MAX = 180

    /**
     * 每轮注入 prompt 的清单条数上限：长期记忆 20、项目记忆 50、知识库目录 50。
     *
     * 三者都只注「索引」（名称/标题 + 短摘要），正文按需用 memory_read / knowledge_search 取。
     * 但它们是**每轮固定开销**：最坏 120 行 × [MEMORY_INLINE_MAX] 字符，量级 8~9K token；
     * 要压上下文先看这里。
     */
    private val MEMORY_GLOBAL_LIMIT = 20
    private val MEMORY_PROJECT_LIMIT = 50
    private val KNOWLEDGE_INDEX_LIMIT = 50
    private val db = DatabaseProvider.get(appContext)
    private val messageRepository = MessageRepository(db.messageDao())
    private val conversationRepository = ConversationRepository(db.conversationDao())
    private val taskRepository = TaskRepository(db.taskDao())
    val llmCallRecordDao = db.llmCallRecordDao()

    /**
     * 调用记录保留策略：只保留最近 [CALL_RECORD_RETENTION_MS]，更早的行删掉。
     *
     * 这张表原本只增不减，而统计页所有查询都按 `createdAt >= start` 过滤
     * （日/周/月聚合），超期数据永远不会被读到，却会一直占库并拖慢全表扫描。
     */
    /**
     * 工具结果硬上限（字符）。
     *
     * 20 万字≈50K token，正常工具输出（含 git diff、日志）远低于此；到了这个量级基本就是
     * 整个文件/整个页面源码的转储，再大也只会把堆顶穿。超限时保留头尾，中间用省略标记。
     */
    private val MAX_TOOL_RESULT_CHARS = 200_000

    /**
     * 限幅工具结果。
     *
     * 结果同时进内存消息列表、进 DB、并在下一轮拼进请求，三处都是按全长算的，
     * 所以必须在入口处就封顶，而不是等渲染时再想办法。
     */
    private fun clampToolResult(raw: String): String {
        if (raw.length <= MAX_TOOL_RESULT_CHARS) return raw
        val head = MAX_TOOL_RESULT_CHARS * 3 / 4
        val tail = MAX_TOOL_RESULT_CHARS - head
        return buildString(MAX_TOOL_RESULT_CHARS + 96) {
            append(raw, 0, head)
            append("\n\n…（工具输出过长，已截断 ${raw.length - MAX_TOOL_RESULT_CHARS} 字符）\n\n")
            append(raw, raw.length - tail, raw.length)
        }
    }

    private suspend fun pruneCallRecords() {
        runCatching {
            val cutoff = System.currentTimeMillis() - CALL_RECORD_RETENTION_MS
            llmCallRecordDao.deleteOlderThan(cutoff)
        }
    }

    /**
     * Token 统计埋点：写一条单次 LLM 调用记录。
     *
     * [forced] 传「本次请求实际用的」提供商（专用模型 / 会话级 override 的解析结果），
     * 归属就按它算；不传则用本会话生效设置。**不能读 `uiState.settings`** —— 那是前台槽
     * 的投影，会话级模型模式或多会话并行时（尤其是后台会话请求收尾那一瞬），
     * 用量会被记到当时前台那个模型名下，表现就是「有的模型不统计」。
     *
     * @param kind 调用类型：chat / title / compaction / vision。
     */
    private fun recordLlmCall(
        kind: String,
        conversationId: Long,
        forced: com.wy.openx.data.model.AIProviderConfig?,
        inputTokens: Int,
        outputTokens: Int,
        cachedInputTokens: Int,
        startedAt: Long,
        durationMillis: Int,
        success: Boolean,
        errorMessage: String? = null
    ) {
        val s = settingsFor(conversationId)
        val provider = forced?.name?.takeIf { it.isNotBlank() } ?: s.selectedApiPreset
        val model = forced?.effectiveModel?.takeIf { it.isNotBlank() } ?: s.model
        viewModelScope.launch {
            try {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = conversationId.toString(),
                        providerId = provider,
                        model = model,
                        kind = kind,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        cachedInputTokens = cachedInputTokens,
                        durationMillis = durationMillis,
                        status = if (success) "success" else "error",
                        errorMessage = if (success) null else errorMessage,
                        createdAt = startedAt
                    )
                )
                pruneCallRecords()
            } catch (_: Exception) {
            }
        }
    }
    /**
     * 前台会话的消息观察协程**别名**：真源已按会话存在 [SessionSlot.observeJob]。
     * 旧调用点都是「操作当下前台会话」，委托后语义不变；
     * 但 `observeMessagesForConversation` 内部必须显式用 `slotOf(conversationId)`
     * （那里 cid 已被提前改成新会话，走别名会取消错槽）。
     */
    private var messageObserveJob: Job?
        get() = slot().observeJob
        set(v) { slot().observeJob = v }

    /**
     * 「启动时是否恢复上次对话」只在首次收到会话列表时判定一次。
     * 之后列表每次变化都沿用原逻辑，否则用户手动切会话会被反复新建覆盖。
     */
    private var startupConversationResolved = false

    /** MCP 自动测试任务：离开页面时取消，避免它在页面关闭后把结果写回、与下次进入的测试打架。 */
    private var mcpAutoTestJob: Job? = null
    // 多会话槽位：本轮流式任务的句柄，按会话隔离（详见 [slots]）。
    private var streamJob: Job?
        get() = slot().streamJob
        set(v) { slot().streamJob = v }

    /**
     * 持久化的 settings 是否已至少加载一次。
     *
     * 关键：`resolveActiveProvider()` 会持久化 selectedProviderId。若在 settingsFlow 首次发出**之前**
     * 就因 Room providers 先到位而调用它，此时 `uiState.settings.selectedProviderId` 还是默认空串，
     * 会误判「未选择」→ 回退到第一个 provider 并持久化，把用户上次保存的选择覆盖掉
     * （表现为「重启后总是变成列表第一个模型」）。用该标记挡住，等持久化值到位再解析。
     */
    private var persistedSettingsLoaded = false

    /**
     * 模型元数据（能力标签 / 上下文窗口）是否已做过一次启动加载。
     * 该数据是纯内存态、重启即空；需在提供商就绪后补载一次，否则聊天页模型列表只剩模型名。
     */
    private var modelMetadataLoadedOnce = false
    /** 用户本轮主动停止的标记：用于把「取消导致的 Canceled」与真实报错区分开，避免误报错误。 */
    private var stopRequested: Boolean
        get() = slot().stopRequested
        set(v) { slot().stopRequested = v }
    // 流式增量缓冲：两个会话同时吐字时必须各自一份，否则正文串到对方消息上。
    private val _deltaAccumulator: StringBuilder get() = slot().deltaAccumulator
    private val deltaAccMax = 64 * 1024
    private var _deltaFlushJob: Job?
        get() = slot().deltaFlushJob
        set(v) { slot().deltaFlushJob = v }
    // 撤销/重做栈存的是 List<ChatMessageModel> 快照，不分槽会让 B 撤销到 A 的历史。
    private val undoStack: MutableList<List<ChatMessageModel>> get() = slot().undoStack
    private val redoStack: MutableList<List<ChatMessageModel>> get() = slot().redoStack
    val toolRegistry = ToolRegistry(appContext).also {
        com.wy.openx.tools.AppContextHolder.toolRegistry = it
        it.init()
        bindTaskExecutor()
        bindPluginChatRunner()
    }
    // 容器引擎必须在 terminalEngine 初始化之前装配：TerminalEngine.initialize() 会读取
    // AppContextHolder.containerEngine 决定走容器还是原生 shell，属性按声明顺序初始化，
    // 若放在其后，启动时 getOrCreate() 会拿到 null 而错误地拉起原生 Termux bash 会话。
    // 单例，供终端（PTY）与 AI 命令工具共享同一安装/进度/执行后端。
    private val containerOsDetector = com.wy.openx.container.ContainerOsDetector(appContext)
    private val containerInstaller = com.wy.openx.container.ContainerInstaller(appContext, containerOsDetector)
    private val containerSettingsRepository = com.wy.openx.container.DataContainerSettingsRepository(appContext)
    private val containerImageCatalogProvider = com.wy.openx.container.ContainerImageCatalog(appContext)
    private val containerImageDownloader = com.wy.openx.container.ContainerImageDownloader(appContext)
    private val containerEngine = com.wy.openx.container.LinuxContainerEngine(
        appContext,
        containerInstaller,
        containerOsDetector,
        containerSettingsRepository,
        com.wy.openx.container.SimplePathHomeResolver()
    ).also {
        com.wy.openx.tools.AppContextHolder.containerEngine = it
    }
    /** 侧边栏「文件」Tab 的容器根目录浏览后端（rootfs + 绑定合并视图）。 */
    val browseFileSystem = com.wy.openx.container.BrowseFileSystem(
        appContext,
        containerInstaller,
        containerSettingsRepository
    )
    /** 全局唯一的终端引擎。界面必须用这个实例，不要自己 new（见 MainActivity 里 TerminalScreen 的传参）。 */
    val terminal: TerminalEngine = TerminalEngine.getOrCreate().also {
        AppContextHolder.terminalEngine = it
    }

    /**
     * Gradle 堆配置的落点：容器内的 `~/.gradle/gradle.properties`（用户级）。
     *
     * 写这里而不是项目级：项目级会覆盖用户级，而每个工程都有自己的 `gradle.properties`
     * （openx 自己那个也曾写死过 -Xmx8g），设置项根本管不到它们。
     */
    private val gradleHeapPropsPath = "/root/.gradle/gradle.properties"

    /** 读当前 Gradle 堆上限（MB）；没配过返回 null，表示交给 gradlew 按设备内存自动决定。 */
    fun readGradleHeapMb(): Int? = runCatching {
        val fs = browseFileSystem
        if (!fs.exists(gradleHeapPropsPath)) return@runCatching null
        val m = GRADLE_XMX_REGEX.find(fs.readFile(gradleHeapPropsPath)) ?: return@runCatching null
        val n = m.groupValues[1].toIntOrNull() ?: return@runCatching null
        if (m.groupValues[2].equals("g", ignoreCase = true)) n * 1024 else n
    }.getOrNull()

    /**
     * 写 Gradle 堆上限（MB）。
     *
     * 保留文件里的其他行，只替换/追加 `org.gradle.jvmargs`：gradlew 一旦看到这个键存在，
     * 就不再按设备内存自动算——命令行 -D 的优先级高于 properties，它不判断的话会反过来
     * 把用户在这里选的值盖掉。
     */
    fun writeGradleHeapMb(mb: Int) {
        runCatching {
            val existing = runCatching {
                if (browseFileSystem.exists(gradleHeapPropsPath)) browseFileSystem.readFile(gradleHeapPropsPath) else ""
            }.getOrDefault("")
            val line = "org.gradle.jvmargs=-Xmx${mb}m -Dfile.encoding=UTF-8"
            val kept = existing.lines().filterNot { GRADLE_JVMARGS_LINE.matches(it) }.filter { it.isNotBlank() }
            browseFileSystem.writeFile(gradleHeapPropsPath, (listOf(line) + kept).joinToString("\n") + "\n", overwrite = true)
        }.onFailure {
            showToast("写入失败：${it.message ?: "未知错误"}")
        }
    }
    private val terminalEngine: TerminalEngine get() = terminal
    /** 内置浏览器引擎（进程级单例）：供浏览器页与 AI browser 工具共用。 */
    val browserManager = com.wy.openx.browser.BrowserManager.getOrCreate(application).also {
        // 注入容器路径解析后端：地址栏输入 `/root/...`、`~/.openx/...` 等容器路径时，
        // 浏览器据此映射到宿主真实文件再以 file:// 加载（否则 WebView 看不到 PRoot 视图，404）。
        it.browseFileSystem = browseFileSystem
    }

    /** AI 运行进度悬浮窗（进程级单例，偏好设置开关控制）。 */
    private val overlayController = FloatingOverlayController.getOrCreate(application)

    private val memoryStore = com.wy.openx.data.store.MemoryStore(application)
    private val knowledgeStore = com.wy.openx.data.store.KnowledgeStore(application)
    private val skillStore = com.wy.openx.data.store.SkillStore(application)
    private val workflowStore = com.wy.openx.data.store.WorkflowStore(application)
    private val mcpServerStore = McpServerStore(application)
    /**
     * 工具授权规则存储（指令前缀粒度）。
     *
     * 刻意存宿主而非容器：授权规则是安全边界本身，容器内的 AI 不能读写它给自己扩权。
     */
    private val toolPermissionRuleStore = com.wy.openx.tools.ToolPermissionRuleStore(application)
    private val tokenPrefs = application.getSharedPreferences("openx_stats", android.content.Context.MODE_PRIVATE)
    // 工具确认回调：单槽会让两个会话同时请求确认时第一个永挂，故按会话隔离。
    private var toolConfirmDeferred: CompletableDeferred<Boolean>?
        get() = slot().toolConfirmDeferred
        set(v) { slot().toolConfirmDeferred = v }
    /** 工作流并行步骤的审批弹窗串行锁，防多步同时抢 pendingToolCall 槽位导致挂死。 */
    private val workflowConfirmMutex = Mutex()

    /**
     * 工作流产物目录约定（注入系统提示）。
     *
     * 只写进系统提示是不够的：AI 会把 `path` 固化进工作流存储的 JSON，提示的约束力有限，
     * 所以同一句约定也写进了 `WorkflowTool.description`（那才是约束「建工作流」的入口，
     * 那里只说相对规则「当前工作区下 workflows/」，绝对路径由本函数按分支给出）。
     * 仍然**不**在引擎里自动改写 path：terminal 的路径藏在 `command` 字符串里
     * （如 `echo x > /root/a.md`），只改 writeFile 的 path 会造成「以为管住了」的错觉。
     */
    private fun workflowOutputNote(workspaceContainerPath: String): String =
        "工作流生成的文档、报告等产物请放在 $workspaceContainerPath/workflows/ 下，" +
            "不要写到 /root/ 或其它容器 rootfs 目录（那些文件在设置页「删除当前容器 rootfs,重新安装」时会被抹掉）。"
    private var terminalConfirmDeferred: CompletableDeferred<Boolean>?
        get() = slot().terminalConfirmDeferred
        set(v) { slot().terminalConfirmDeferred = v }
    private var toastJob: Job? = null

    /** 更新包下载中，防重复点击。 */
    private var updateDownloading = false
    private var nextMessageId = AtomicLong(System.currentTimeMillis() * 1000)
    // 终端目标不能跳会话乱跑（A 的命令不应跑到 B 的容器）。
    private var lastToolTargetId: String
        get() = slot().lastToolTargetId
        set(v) { slot().lastToolTargetId = v }

    /**
     * 工具调用 id → 工具消息 id 映射。
     *
     * 彻底修复「工具状态点一直闪」：工具消息改为**单条就地更新**——
     * onToolCall 插入一条带 `[running]` 标记的占位消息，onToolResult 用**同一消息 id** REPLACE
     * 覆盖成最终结果。状态圆点据此判定，不再依赖 call/result 两条消息的 toolCallId 事后配对
     * （网关返回 id 偶发不一致时会配对失败 → 圆点永久闪）。
     */
    private val toolMessageIds = HashMap<String, Long>()

    var screen by mutableStateOf<AppScreen>(AppScreen.Console)
        private set

    /**
     * 进入设置页时要直接打开的分区（`api` / `container` 等）。
     * 首次启动引导里点「去配置」用它一步跳到目标分区，用完即清。
     */
    var settingsInitialSection by mutableStateOf<String?>(null)
        private set

    /**
     * `uiState` 已退化为**前台投影**，真源在各 [SessionSlot]：
     * - 同会话写入（`currentConversationId` 不变）→ 顺手把会话级字段收回该会话槽位。
     * - 切前台（`currentConversationId` 变化）→ 旧前台存回旧槽，新槽覆盖投影。
     *
     * 这样既有那 170+ 处 `uiState = uiState.copy(...)` 无需逐处改造即自动落到槽位。
     *
     * ⚠️ 字段清单维护提醒：新增会话级字段需同时加到
     * [ConsoleUiState]、[SessionSlot.syncFromUi]、[SessionSlot.projectOnto] 三处。
     * 漏加的后果不是丢状态，而是该字段**没有会话隔离**（继续当全局字段用，同 P1 之前）。
     */
    private var _uiState by mutableStateOf(ConsoleUiState())

    var uiState: ConsoleUiState
        get() = _uiState
        private set(value) {
            val prevCid = _uiState.currentConversationId
            val prevSettings = _uiState.settings
            val prevConversations = _uiState.conversations
            val nextCid = value.currentConversationId
            if (prevCid != nextCid) {
                // 切前台：先把当前投影存回旧槽，否则旧会话未落盘的会话级状态会丢
                slotOf(prevCid).syncFromUi(_uiState)
                slotOf(nextCid).lastTouchedAt = System.currentTimeMillis()
                _uiState = slotOf(nextCid).projectOnto(value)
            } else {
                _uiState = value
                slotOf(nextCid).syncFromUi(value)
                // 全局默认档变了（设置页改权限/思考强度、或备份导入）：
                // 未做过会话级覆盖的会话必须跟着变，而这两个字段是派生的，
                // 只有重算投影才能把新的全局默认带进来。
                if (prevSettings != value.settings) {
                    _uiState = slotOf(nextCid).projectOnto(_uiState)
                }
            }
            // 会话列表变化（Room Flow 重发 / 本地更新）：把持久化的会话模型选择同步进槽位镜像。
            // 用引用比较而非 equals：列表每次发射都是新实例，逐项比会白白 O(n) 而且总是相等时无意义。
            if (prevConversations !== value.conversations && syncModelOverridesIntoSlots()) {
                _uiState = slotOf(nextCid).projectOnto(_uiState)
            }
            // 有些路径仍走 uiState.copy 改会话级字段（未经 editSlot），这里兜一次重算。
            refreshSessionBadges()
        }

    /**
     * 某会话**生效**的工具权限档：会话覆盖 ?? 全局默认。
     * 请求侧、审批判定都必须走这里，不要直接读 `settings.toolPermissionMode`
     * （那是全局默认档，并发时会把 A 的选择当成 B 的）。
     */
    private fun effectivePermissionMode(conversationId: Long): String =
        slotOf(conversationId).toolPermissionModeOverride ?: _uiState.settings.toolPermissionMode

    /**
     * 某会话生效的思考强度：会话覆盖 ?? 全局默认。
     *
     * 顺带把历史遗留的 `"none"` 归一成标准的 `"minimal"`：`none` 是本项目自造值，
     * 网关不认（等于「选了等于没选」），而"关闭思考"的规范档位是 `minimal`。
     * 归一后老设置立刻恢复成"真正生效的最低档"，而不是留一个永远无效的值。
     */
    private fun effectiveReasoningEffort(conversationId: Long): String {
        val raw = slotOf(conversationId).reasoningEffortOverride ?: _uiState.settings.reasoningEffort
        return normalizeReasoningEffort(raw)
    }

    private fun normalizeReasoningEffort(value: String): String =
        if (value == "none") "minimal" else value

    /**
     * 把 Room（conversations.providerId/model）里持久化的会话模型选择同步进各槽的内存镜像。
     *
     * 只碰**已存在的槽**和前台槽：给每个会话都建槽会把几百个会话的消息列表引用全拉进内存。
     * 未建过槽的会话不需要镜像——切过去时 `slotOf` 新建，再由本函数（下一次列表发射）补上；
     * 而前台槽在此时已存在，所以展示不会错。
     *
     * @return 前台会话的镜像是否发生变化（调用方据此决定是否重算投影）
     */
    private fun syncModelOverridesIntoSlots(): Boolean {
        val fg = _uiState.currentConversationId
        var foregroundChanged = false
        for (c in _uiState.conversations) {
            val s = slots[c.id] ?: if (c.id == fg) slotOf(c.id) else continue
            if (s.providerIdOverride != c.providerId || s.modelOverride != c.model) {
                s.providerIdOverride = c.providerId
                s.modelOverride = c.model
                if (c.id == fg) foregroundChanged = true
            }
            // 标题定稿标志：**单向**同步（只从 DB 拉 true 上来）。
            //
            // 不能双向赋值：slot 置 true 后落库是异步的，期间若来一次会话列表发射，
            // 双向同步会用 DB 里尚未更新的 false 把 slot 打回 → 标题被重复生成。
            // 该标志语义上只 false→true，单向合并天然安全。
            if (c.titleFinalized && !s.titleFinalized) s.titleFinalized = true
        }
        return foregroundChanged
    }

    /**
     * 某会话生效的提供商配置（「模型选择模式」开启且本会话选过模型时），否则 null = 用全局当前提供商。
     *
     * 为什么返回整个配置而不是只改 settings.model：提供商带着 baseUrl / apiKey / 端点开关 /
     * 缓存键策略 / UA，只换模型名会得到「模型是 B 家、地址和 Key 是 A 家」这种发不出去的组合。
     * 调用方把它作为 forcedProvider 传给仓库层——`resolveCallContext` 本来就是
     * `forcedProvider ?: AppContextHolder.currentProvider`，顺带绕开了那个全局单槽在并发下被
     * 别的会话覆盖的问题。
     */
    private fun effectiveProviderFor(conversationId: Long): com.wy.openx.data.model.AIProviderConfig? {
        // 会话级模式（modelGlobalMode=false）才用会话 override；全局模式返回 null 令调用点回落全局
        if (_uiState.settings.modelGlobalMode) return null
        val s = slots[conversationId] ?: return null
        val pid = s.providerIdOverride?.takeIf { it.isNotBlank() } ?: return null
        val p = providers.firstOrNull { it.id == pid } ?: return null
        val m = s.modelOverride?.takeIf { it.isNotBlank() }
        return if (m != null && m != p.effectiveModel) p.copy(selectedModel = m) else p
    }

    /**
     * 本轮请求实际下发的设置：全局设置 + 本会话的两个覆盖档。
     * ChatRepository 收到的就是这份，它内部（含 resolveCallContext）不必再关心会话差异。
     */
    private fun settingsFor(conversationId: Long): AppSettings {
        var s = _uiState.settings.copy(
            toolPermissionMode = effectivePermissionMode(conversationId),
            reasoningEffort = effectiveReasoningEffort(conversationId)
        )
        // 【会话级模型】把本会话选定的提供商「整套」摊平进来（与 resolveActiveProvider 同口径）。
        // 不能只改 model：否则日志/记账/回退路径里的 baseUrl、Key 仍是全局那家，
        // 会出现「模型是 B 家、地址和 Key 是 A 家」这种不一致记录。
        // 真正的请求仍靠 forcedProvider 覆盖，这里保证任何读 settings 的地方也一致。
        effectiveProviderFor(conversationId)?.let { p ->
            s = s.copy(
                provider = p.name,
                baseUrl = p.baseUrl,
                apiKey = p.firstUsableApiKey,
                model = p.effectiveModel.ifBlank { s.model },
                selectedApiPreset = p.name,
                selectedProviderId = p.id
            )
        }
        return s
    }

    /** 把前台槽位重新投影进 uiState。 */
    private fun refreshProjection() {
        _uiState = slotOf(_uiState.currentConversationId).projectOnto(_uiState)
    }

    /**
     * 定向修改指定会话的会话级字段：**仅当它是前台时才刷新投影**。
     * 后台会话只写槽位、绝不碰 uiState —— 这是多会话并行不串台的关键约束。
     */
    private inline fun editSlot(conversationId: Long, block: SessionSlot.() -> Unit) {
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
     * 回收长期不用会话槽位的重数据（消息正文、附件、撤销快照）。
     *
     * 保留规则（任一成立就不动）：前台会话、正在跑（[SessionSlot.isWorthKeeping]）、
     * 以及最近用过的 [IDLE_SLOTS_WITH_MESSAGES] 个会话。其余只清重数据、保留轻量标记，
     * 切回去时由 [switchConversation] 从 Room 重建。
     *
     * 必须先取消 `observeJob`：那个协程还挂着 Room 的 Flow，不清的话下一次发射
     * 会立刻把刚释放的消息又填回来，白忙一场。
     */
    private fun evictIdleSlots() {
        if (slots.isEmpty()) return
        val fg = _uiState.currentConversationId
        val idle = slots.values.filter { !it.isWorthKeeping(fg) }
        if (idle.isEmpty()) return
        val keep = idle.sortedByDescending { it.lastTouchedAt }
            .take(IDLE_SLOTS_WITH_MESSAGES)
            .map { it.conversationId }
            .toSet()
        for (s in idle) {
            if (s.conversationId in keep) continue
            // 已经空了就不重复处理（槽位本身留着，徽标/未读/模型 override 还要用）
            if (!s.loaded && s.messages.isEmpty() && s.foldedMessages.isEmpty()) continue
            s.observeJob?.cancel()
            s.observeJob = null
            s.foldedObserveJob?.cancel()
            s.foldedObserveJob = null
            s.releaseHeavyState()
        }
    }

    /**
     * openx 是否在前台（由 `MainActivity.onStart/onStop` 同步）。
     *
     * 为什么 VM 也要知道：完成标记 [SessionSlot.completedUnread] 的判据是「用户没看到结果」，
     * 而「看到」不只取决于是否切换会话——**app 在后台时用户根本没在看**，此时当前活动会话
     * 跑完也必须标记为已完成未读，否则悬浮窗会在任务结束时立即消失（用户来不及看结果）。
     * 默认 true：进程刚起、还没收到 onStart 时不误判为后台。
     */
    @Volatile
    var appInForeground: Boolean = true
        private set

    /** 由 `MainActivity` 在 onStart/onStop 调用，同步进程前后台状态。 */
    fun setAppInForeground(value: Boolean) {
        if (appInForeground == value) return
        appInForeground = value
        // 回到前台：当前会话就在用户眼前，清掉它的「后台完成未读」标记（侧边栏 🔵 随之消失）。
        // 只清当前会话——其它会话仍是用户没看的，保持未读。
        if (value) {
            slots[_uiState.currentConversationId]?.completedUnread = false
            // 已完成通知在用户回到 App 后就失去意义了
            com.wy.openx.service.CompletionNotifier.cancel(appContext)
        }
        refreshSessionBadges()
    }

    /**
     * 从槽位真源重算侧边栏徽标与跨会话横幅。
     *
     * 挂接点：[editSlot]（所有会话级写入）与 `uiState` 的 setter（兼容仍走 copy 的旧写法）。
     * 这里写的是 `_uiState`（raw backing field），不走自定义 setter，因此不会递归。
     */
    private fun refreshSessionBadges() {
        if (badgesRefreshing) return
        badgesRefreshing = true
        // 有会话刚跑完 → 结束后回收不用的槽位（见 evictIdleSlots）。
        // 声明在 try 外：下面「状态没变就 return」会提前退出，写在 try 内会漏掉。
        var anyFinished = false
        try {
            val fg = _uiState.currentConversationId
            val map = HashMap<Long, SessionBadge>()
            var banner: AwaitingBanner? = null
            for ((cid, s) in slots) {
                // 捕捉「跑完」的瞬间：当前会话在前台时用户已经看到了，不记未读；
                // 但只要 app 在后台（用户没在看），当前会话跑完同样要记——
                // 否则悬浮窗会在任务结束那一刻因列表变空而立刻消失。
                if (s.wasSending && !s.isSending && (cid != fg || !appInForeground)) s.completedUnread = true
                if (s.wasSending && !s.isSending) anyFinished = true
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
            // 结果与当前一致就别重新 copy：uiState 的 setter 每次都调本函数，
            // 流式期间每 150ms 一次写入，无条件重建 map + copy 会把整个 state 对象换掉，
            // 迫使所有 Composable 重组（这是长会话掉帧的元凶之一）。
            if (map == _uiState.sessionStatuses && banner == _uiState.awaitingBanner) return
            _uiState = _uiState.copy(sessionStatuses = map, awaitingBanner = banner)
        } finally {
            badgesRefreshing = false
            // 悬浮窗的唯一重算信号：这里覆盖了**所有**会话级写入（editSlot 与 uiState setter），
            // 前台后台都在内。槽位字段是普通 var、观察不到，必须靠这个显式信号唤醒 snapshotFlow。
            bumpOverlayRevision()
            // 回收必须在 badgesRefreshing 复位之后：evictIdleSlots 会取消 observeJob，
            // 那些协程若在 _uiState 上做收尾写入，会重入 refreshSessionBadges。
            if (anyFinished) evictIdleSlots()
        }
    }

    /** 横幅点「忽略」：收起提醒，但会话继续等待（侧边栏 🟡 保持）。 */
    fun dismissAwaitingBanner() {
        val b = _uiState.awaitingBanner ?: return
        slotOf(b.sessionId).bannerDismissed = true
        refreshSessionBadges()
    }

    // ═══════════════════ 多会话槽位（P1 地基）═══════════════════
    /**
     * 按会话 id 持有的运行时槽位。**槽位是真源**，`uiState` 里的会话级字段是「前台槽位的投影」。
     *
     * P2a：`uiState` 已退化为前台投影（写入经 setter 自动落槽），切会话仍会 stopStreaming。
     * P2b：已去掉切会话杀流，真正允许后台并行；并发上限由 [concurrencyGate] 控制。
     * 本容器把那些「语义上属于会话、物理上是单例」的字段搬到按 cid 隔离的位置，
     * 并修掉因此产生的两个现存 bug：
     * - 撤销栈跨会话串台（在 B 按撤销会恢复 A 的消息列表）
     * - 切回会话重复生成标题（旧代码靠切会话时手动复位 `titleGeneratedByAi`，导致回到 A 又调一次 LLM；
     *   现标题定稿标志改为 [`ConversationModel.titleFinalized`] 持久化，并按槽位隔离，无需复位）
     *
     * `messageObserveJob` 现在是 `slot().observeJob` 的**别名**：旧代码里那 4 处 cancel 都是
     * 「操作当下前台会话」，语义不变；而 `observeMessagesForConversation` 内部显式用
     * `slotOf(conversationId).observeJob` 定位，所以后台会话的观察协程不会被误取消。
     */
    private val slots = HashMap<Long, SessionSlot>()

    /**
     * 除前台会话外，最多保留几个会话的**完整消息**在内存里。
     *
     * 其余槽位保留轻量标记、但释放消息正文（见 [SessionSlot.releaseHeavyState]）。
     * 为什么必须限：`messages` 里是完整工具输出与图片 base64，单会话可占几 MB；
     * 槽位原本只增不删，开得越多堆越大，最终在 Compose 重组时直接 OOM
     * （真机崩溃：`Failed to allocate a 40 byte allocation` 于会话抽屉重组）。
     */
    private val IDLE_SLOTS_WITH_MESSAGES = 3

    /** 取（或建）指定会话的槽位。新建时把自动压缩冷却计数初始化为「允许压缩」，与旧的全局默认值一致。 */
    private fun slotOf(conversationId: Long): SessionSlot =
        slots.getOrPut(conversationId) {
            SessionSlot(conversationId).also {
                it.messagesSinceAutoCompact = AUTO_COMPACT_COOLDOWN
                // 【重启后会话级模型丢失】建槽时立即从会话行灌入持久化的模型选择。
                //
                // 原来只在 [syncModelOverridesIntoSlots] 里灌，而那个函数在 combine 的下游跑：
                // App 启动时会话列表首次发射时 `currentConversationId` 还是 0，
                // `slots[c.id] ?: if (c.id == fg) ... else continue` 会把所有非前台会话 continue 掉；
                // 随后 [restoreConversationCtx] 建起前台槽——但它只恢复 token/usage，**不碰 override**。
                // 于是前台槽的 providerIdOverride/modelOverride 一直是 null，
                // 直到会话列表**再次**发射才会被灌上。用户反馈：选好模型、退出重进，顶栏显示
                // 「[未选择模型]」而不是退出前选的那个。
                //
                // （会话级模式下空 override 已不再回落全局，所以这个空值会直接显示成
                // 「[未选择模型]」，把原本只在启动时可见的时序问题暴露了出来。）
                //
                // 建槽时兜住，之后 syncModelOverridesIntoSlots 的常规同步仍然照跑（备份导入等
                // 批量改库的场景靠它）。已建好的槽不会被这里覆盖，不影响运行中的会话。
                val c = _uiState.conversations.firstOrNull { it.id == conversationId }
                if (c != null) {
                    it.providerIdOverride = c.providerId
                    it.modelOverride = c.model
                    // 标题定稿标志同样建槽即灌：否则重启后槽位默认 false，
                    // 在已有会话里发一条消息就会重新生成标题并覆盖旧标题。
                    it.titleFinalized = c.titleFinalized
                }
            }
        }

    /** 前台会话的槽位——旧代码里那些单例字段的替代品，靠 currentConversationId 定位。 */
    private fun slot(): SessionSlot = slotOf(uiState.currentConversationId)

    // 保存的模型单项测速状态（key = preset.name）：设置页「保存的模型」列表按钮前显示毫秒
    var presetTestStates by mutableStateOf<Map<String, PresetTestState>>(emptyMap())
        private set

    // ===== 提供商独立管理（阶段 2/3/4/5 新增） =====
    private val aiProviderRepository = com.wy.openx.data.local.AIProviderRepository(db.aiProviderDao(), db)
    private val modelMetadataService = com.wy.openx.data.provider.ModelMetadataService(appContext)
    private val customModelMetadataStore = com.wy.openx.data.provider.CustomModelMetadataStore(appContext)

    /** 全部提供商（Room Flow 驱动）。 */
    var providers by mutableStateOf<List<com.wy.openx.data.model.AIProviderConfig>>(emptyList())
        private set

    /** 模型拉取状态（设置页 ProviderEditor 拉取模型列表弹窗用）。 */
    sealed interface ProviderFetchState {
        data object Idle : ProviderFetchState
        data object Loading : ProviderFetchState
        data class Success(val models: List<String>, val debugInfo: com.wy.openx.data.provider.ModelTestResult? = null) : ProviderFetchState
        data class Error(val message: String, val debugInfo: com.wy.openx.data.provider.ModelTestResult? = null) : ProviderFetchState
    }
    var providerFetchState by mutableStateOf<ProviderFetchState>(ProviderFetchState.Idle)
        private set

    /** 模型连通测试结果（key = 模型名）。 */
    var providerTestResults by mutableStateOf<Map<String, com.wy.openx.data.provider.ModelTestResult>>(emptyMap())
        private set

    /** 正在测试的模型名集合。 */
    var providerTesting by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 模型元数据（key = 模型名，来源 models.dev + 自定义）。 */
    var providerModelMetadata by mutableStateOf<Map<String, com.wy.openx.data.model.ModelMetadata>>(emptyMap())
        private set

    // 项目工作区列表（filesDir/workspace/projects 下的项目目录名）
    var projects by mutableStateOf<List<String>>(emptyList())
        private set

    // ===== 容器设置（全局设置页使用） =====
    var containerApkMirrorId by mutableStateOf(com.wy.openx.container.ApkMirrors.OFFICIAL_ID)
        private set
    var containerResetBusy by mutableStateOf(false)
        private set
    var containerProfiles by mutableStateOf<List<com.wy.openx.container.ContainerProfile>>(emptyList())
        private set

    init {
        // 订阅必须在声明之后（原因同 npmRegistryId 的 init 注释）：构造函数开头的 init 块
        // 先于 698 行的本声明执行，Dispatchers.Main.immediate 下 emit 可同步命中 null 委托。
        viewModelScope.launch {
            containerSettingsRepository.customProfilesFlow.collect { list ->
                containerProfiles = list.sortedByDescending { it.createdAt }
            }
        }
    }
    var activeContainerId by mutableStateOf(com.wy.openx.container.ContainerProfile.BUILTIN_ID)
        private set

    // ===== 容器镜像下载（全局设置页使用） =====
    var containerImageCatalog by mutableStateOf<List<com.wy.openx.container.ContainerImageEntry>>(emptyList())
        private set
    var containerImageSources by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var downloadedImages by mutableStateOf<Map<String, com.wy.openx.container.DownloadedImageRecord>>(emptyMap())
        private set
    var containerDownloadState by mutableStateOf<ContainerDownloadUiState>(ContainerDownloadUiState.Idle)
        private set
    private var downloadJob: Job? = null
    /** 并行工具执行中撞 429 的累计次数；达 [RATE_LIMIT_HIT_THRESHOLD] 后提示一次并清零。 */
    private val rateLimitHits = java.util.concurrent.atomic.AtomicInteger(0)
    /**
     * 用户点过「保持并行」的会话：这些会话内不再弹限流提示（其他会话照常）。
     *
     * 只活在内存里：重启后重置。它记录的是「当下别烦我」的决定，不是一条要长期生效的设置
     * （要长期生效就去设置页关掉并行）。
     */
    private val rateLimitKeptParallel = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    /** 当前弹窗针对的会话（上报时记下），「保持并行」按它归档。 */
    private var rateLimitWarningConvId: Long? = null

    val containerInstalled: Boolean
        get() = containerEngine.isContainerInstalled()

    init {
        // 注册 AI terminal 危险命令确认处理器（TerminalTool 守卫命中 Confirm 时调用）
        com.wy.openx.tools.AppContextHolder.terminalConfirmRequester = { command, reason ->
            requestTerminalConfirm(command, reason)
        }
        // 注册「并行执行批内工具撞限流」上报：仓库层的并行分支把（会话 id, 本批命中数）推过来。
        // 累计到阈值弹**一次**提示（用户可一键切回串行）；提示后清零，避免长期累积后
        // 「刚关掉又马上弹」。弹窗已开着时不再重复设置 state（省一次无谓重组）。
        // 用户点过「保持并行」的会话直接跳过：那是他的明确选择，之后不再打扰。
        com.wy.openx.tools.AppContextHolder.rateLimitNotifier = { conversationId, hitCount ->
            if (conversationId !in rateLimitKeptParallel) {
                // 回调来自仓库层协程，多个会话并行时可能同时打进来，故用原子量累加。
                if (rateLimitHits.addAndGet(hitCount) >= RATE_LIMIT_HIT_THRESHOLD) {
                    rateLimitHits.set(0)
                    if (!uiState.rateLimitWarning) {
                        rateLimitWarningConvId = conversationId
                        uiState = uiState.copy(rateLimitWarning = true)
                    }
                }
            }
        }
        // 注册工具循环内「就地压缩」通知：
        //  compressing → 聊天页输入框上方的思考指示器切到「正在压缩上下文」（isCompacting，不落库）；
        //  completed / failed → 复位指示器，并**立即落库**（见下）。
        //
        // 【关键】completed 时立刻把「就地压缩」结果落进会话历史，而不是拖到回合末。
        // 压缩一完成就在消息流里显示压缩卡，AI 随后继续执行任务——用户能马上
        // 看到压缩发生的位置。旧实现拖到回合 `finally` 落库，一旦用户在此之前按「停止」取消协程，
        // 落库被取消打断 → 折叠没写成 → **历史消息全部消失**（用户实测报障）。
        // 立即落库同时消除了这个时序窗口。
        //
        // 用独立的 `viewModelScope.launch`：本回调运行在仓库层的工具循环里（其协程可能随后
        // 被取消），落库必须有自己的生命期；内部再用 NonCancellable 保证原子。
        com.wy.openx.tools.AppContextHolder.toolLoopCompactionNotifier = { status, _, _ ->
            if (status == "compressing") {
                if (!uiState.isCompacting) {
                    uiState = uiState.copy(isCompacting = true)
                }
            } else {
                if (uiState.isCompacting) {
                    uiState = uiState.copy(isCompacting = false)
                }
                if (status == "completed") {
                    // 用 pending **自己的** conversationId，不能用 foreground currentConversationId：
                    // 多会话并行时压缩可能发生在后台会话，用前台 id 会被 persistPendingToolLoopCompaction
                    // 的会话校验挡掉（会话不匹配 → 跳过落库）。
                    val cid = com.wy.openx.tools.AppContextHolder.pendingToolLoopCompaction?.conversationId
                    if (cid != null && cid >= 0) {
                        viewModelScope.launch { persistPendingToolLoopCompaction(cid) }
                    }
                }
            }
        }
        // 注册识图模型调用（「默认模型→识图模型」）：view_image 工具读到图片 dataUrl 后回调。
        // 配置了专用 provider+model 时走专用模型，否则跟随当前聊天模型。
        com.wy.openx.tools.AppContextHolder.visionAnalyzer = { dataUrl, query ->
            // 识图发生在某个会话的工具调用里 → 按该会话生效的提供商/模型走（会话级模式下
            // resolveSpecialModel 已返回 null，回落的就是本会话自己的模型）。
            // 不传 conversationId：那是嵌套请求，与主请求共用句柄槽会互相覆盖。
            val vcid = kotlin.coroutines.coroutineContext[AppContextHolder.ConversationId]?.id
                ?: uiState.currentConversationId
            val visionProvider = resolveSpecialModel("vision") ?: effectiveProviderFor(vcid)
            val visionT0 = System.currentTimeMillis()
            var visionIn = 0
            var visionOut = 0
            var visionCache = 0
            val result = chatRepository.sendMessage(
                settingsFor(vcid),
                "请查看这张图片，回答下列问题。只输出问题所要求的答案，不要多余解释。\n问题：$query",
                imageBase64 = listOf(dataUrl),
                forcedProvider = visionProvider,
                onTokens = { visionIn += it },
                onCompletionTokens = { visionOut += it },
                onCacheHit = { hit, tokens -> if (hit && tokens > 0) visionCache += tokens }
            )
            // 识图调用的用量同样进统计（kind=vision）
            recordLlmCall("vision", vcid, visionProvider, visionIn, visionOut, visionCache, visionT0,
                (System.currentTimeMillis() - visionT0).toInt(), result.isSuccess, result.exceptionOrNull()?.message)
            result.getOrElse { "识图失败：${it.message ?: it.javaClass.simpleName}" }
        }
        // 后台任务完成通知：订阅 TerminalEngine 的完成事件，转成待送 AI 的通知。
        // AI 忙 → 入队（由工具循环搭车注入，见 ChatRepository.executeAndFollowUp）；
        // AI 闲 → 直接为**原会话**唤醒一轮，让 AI 主动处理结果。
        viewModelScope.launch {
            terminalEngine.tabFinishedEvents.collect { event ->
                onBackgroundTaskFinished(event)
            }
        }
        viewModelScope.launch {
            containerSettingsRepository.apkMirrorIdFlow.collect { containerApkMirrorId = it }
        }
        viewModelScope.launch {
            containerSettingsRepository.activeProfileIdFlow.collect { activeContainerId = it }
        }
        viewModelScope.launch {
            containerSettingsRepository.downloadedImagesFlow.collect { downloadedImages = it }
        }
        viewModelScope.launch {
            containerSettingsRepository.ensureBuiltinDefault()
        }
        viewModelScope.launch {
            val entries = containerImageCatalogProvider.load()
            containerImageCatalog = entries
            containerImageSources = containerImageCatalogProvider.sourceIds.map {
                it to containerImageCatalogProvider.sourceLabel(it)
            }
        }
        refreshProjects()
        chatRepository.onReconnectChange = { attempt, maxRetries, cid ->
            // 回调在 withContext(Dispatchers.IO) 内触发（网络重试），而 _uiState 是 Compose
            // snapshot 状态，跨线程写会触发快照失效异常；且必须按会话投影到槽位，
            // 否则 A 会话的重连指示会被 B 会话的成功回调清掉（多会话并行串台）。
            // attempt=0 表示「不在重连」（成功/放弃），>0 表示第 N 次重试（UI 显示「第 N/M 次」）。
            viewModelScope.launch {
                editSlot(cid) {
                    this.reconnecting = attempt > 0
                    this.reconnectAttempt = attempt
                    this.reconnectMax = maxRetries
                    // attempt=0 = 本轮已落定（成功/放弃）：丢弃展示态快照，下一轮尝试重新采集。
                    if (attempt == 0) {
                        this.streamResetBaseMessages = null
                        this.streamResetBaseAcc = ""
                    }
                }
            }
        }
        // 正文已流出后断网重试：重试前回退本次尝试已展示的正文。
        // 回调在 withContext(Dispatchers.IO) 内触发，故切回主线程改状态。
        chatRepository.onStreamReset = { cid ->
            viewModelScope.launch {
                val s = slotOf(cid)
                // base 为 null 表示本次尝试没经可见流式通道吐字（例如子代理 onStreamDelta 为空），
                // 无可回退，直接跳过——避免误清别的会话的增量缓冲。
                val base = s.streamResetBaseMessages ?: return@launch
                // 取消节流落盘，避免旧增量在回退后又被补写进消息。
                s.deltaFlushJob?.cancel()
                s.deltaFlushJob = null
                // 用快照整体覆盖：一次性抹掉本次尝试写入的缓冲与消息（含 append 到末条 assistant 的部分）。
                s.deltaAccumulator.clear()
                s.deltaAccumulator.append(s.streamResetBaseAcc)
                s.streamResetBaseMessages = null
                s.streamResetBaseAcc = ""
                s.messages = base
                if (cid == _uiState.currentConversationId) refreshProjection()
                bumpOverlayRevision()
            }
        }
        // 提供商列表（Room Flow）。旧版扁平配置首次迁移时，若库表为空则从 settings/apiPresets seed。
        viewModelScope.launch {
            aiProviderRepository.getAllProviders().collect { list ->
                providers = list
                // 快照给仓库层（工具循环就地压缩/识图专用模型需按 id 查任意提供商）
                com.wy.openx.tools.AppContextHolder.allProviders = list
                // 只在持久化 settings 到位后解析：否则 selectedProviderId 还是默认空串，
                // 会误回退到第一个 provider 并持久化，覆盖用户上次的选择。
                if (list.isNotEmpty() && persistedSettingsLoaded) {
                    resolveActiveProvider()
                }
                // 元数据（能力标签/上下文窗口）是纯内存态，重启后为空。
                // 提供商就绪后补一次：先读回磁盘上的自定义元数据，再解析内置（models.dev）元数据，
                // 否则聊天页模型列表重启后只剩模型名、下方小字（Image/Tools/窗口）全没了。
                if (list.isNotEmpty() && !modelMetadataLoadedOnce) {
                    modelMetadataLoadedOnce = true
                    loadCustomModelMetadata()
                    loadAllProviderModelMetadata()
                }
            }
        }
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { s ->
                // 首次收到 settings 后若提供商表为空，执行一次性迁移
                if (providers.isEmpty()) {
                    seedProvidersFromLegacy(s)
                }
            }
        }
        // 偏好设置「自动归档」：启动时跑一次。
        // 等 settingsFlow 首次发出再判断——init 时 uiState.settings 还是默认值（autoArchiveDays=0），
        // 立刻判断会永远认为"未开启"。
        viewModelScope.launch {
            val days = settingsStore.settingsFlow.first().autoArchiveDays
            if (days > 0) runAutoArchiveIfNeeded(days)
        }
    }

    // ═══════════════ 后台任务完成通知（AI 主动感知异步结果）═══════════════

    /**
     * 后台终端命令跑完（`terminal(start_background, notify=true)`）时的处理：
     * 转成待送通知；AI 忙则入队搭车，闲则唤醒原会话跑一轮。
     */
    private fun onBackgroundTaskFinished(event: com.wy.openx.tools.TabFinishedEvent) {
        val cid = event.ownerConversationId
        // 无主（用户手动开的后台标签）不通知 AI：没有会话可归属，硬塞给前台会话会串台。
        if (cid <= 0L) return
        val notification = com.wy.openx.tools.AgentNotification(
            conversationId = cid,
            tabId = event.tabId,
            title = event.title,
            command = event.command,
            exitCode = event.exitCode,
            tailOutput = event.tailOutput
        )
        val slot = slotOf(cid)
        if (slot.isSending || slot.streamJob?.isActive == true) {
            // AI 正忙：入队，等本轮工具循环把它搭车注入（省一次 LLM 往返）。
            com.wy.openx.tools.AgentNotificationCenter.enqueue(notification)
        } else {
            // AI 空闲：直接为原会话唤醒一轮。失败（如并发闸门已满）则退回入队，
            // 由后续任一时机再送，绝不静默丢弃。
            wakeConversationForNotifications(cid, listOf(notification))
        }
    }

    /**
     * 把通知合并成一条消息，为指定会话触发新一轮生成。
     *
     * 与用户发送共用 [sendMessage] 的 `overrideText` + `targetConversationId` 路径：
     * 后台会话也能被唤醒，且不依赖它是否在前台。
     */
    private fun wakeConversationForNotifications(
        cid: Long,
        notifications: List<com.wy.openx.tools.AgentNotification>
    ) {
        if (notifications.isEmpty()) return
        val text = com.wy.openx.tools.AgentNotificationCenter.render(notifications)
        val started = sendMessage(overrideText = text, targetConversationId = cid)
        if (started) {
            // 已真正起一轮：这些通知的正文已进上下文，可以确认送达。
            com.wy.openx.tools.AgentNotificationCenter.ack(cid, notifications.map { it.seq })
        } else {
            // 没起来（并发闸门满、未选模型等）：留在队列，下个时机再送。
            com.wy.openx.tools.AgentNotificationCenter.enqueueAll(cid, notifications)
        }
    }

    /**
     * 本轮结束后的通知兜底送达：把该会话仍留在队列里的通知合并成一条，唤醒会话。
     * 已被工具循环搭车送达的此时已 ack 移除，drain 到空则什么都不做。
     */
    private fun flushPendingNotificationsFor(cid: Long) {
        val items = com.wy.openx.tools.AgentNotificationCenter.drain(cid)
        if (items.isEmpty()) return
        wakeConversationForNotifications(cid, items)
    }

    /** 切换 apk 镜像源：立即生效（持久化，重置容器后保留）。 */
    fun changeContainerMirror(id: String) {
        if (id == containerApkMirrorId) return
        viewModelScope.launch {
            runCatching { containerEngine.applyApkMirror(id) }
                .onSuccess { containerApkMirrorId = id }
        }
    }

    /** 应用指定容器 profile 为激活容器：先停掉旧 profile 的会话，下次打开终端页用新 profile。 */
    fun applyContainerProfile(id: String, onApplied: (Boolean) -> Unit) {
        viewModelScope.launch {
            var ok = false
            try {
                terminalEngine.stop()
                containerSettingsRepository.setActiveProfile(id)
                // 必须等引擎的 currentProfile 真正切过去再报成功：它是 flow collector
                // 异步更新的（Dispatchers.IO），不等的话用户紧接着开终端会拿到旧容器的会话。
                containerEngine.awaitProfileSwitched(id)
                ok = true
            } catch (_: Exception) {
            }
            onApplied(ok)
        }
    }

    /** 保存（新增或覆盖）容器 profile；根镜像变了时删旧 rootfs 触发重新解压；勾选则设为激活。 */
    fun saveContainerProfile(profile: com.wy.openx.container.ContainerProfile, setActive: Boolean, isNew: Boolean) {
        viewModelScope.launch {
            if (!isNew) {
                val old = containerProfiles.firstOrNull { it.id == profile.id }
                val oldUri = (old?.rootfsSource as? com.wy.openx.container.RootfsSource.LocalFile)?.uri
                val newUri = (profile.rootfsSource as? com.wy.openx.container.RootfsSource.LocalFile)?.uri
                if (old != null && oldUri != newUri) {
                    containerInstaller.deleteCustomRootfs(profile)
                    containerEngine.forgetProfile(profile.id)
                }
            }
            containerSettingsRepository.upsertCustomProfile(profile)
            if (setActive) containerSettingsRepository.setActiveProfile(profile.id)
        }
    }

    /** 删除自定义容器 profile（内置也可删，删光由空态恢复）；删除的是激活项时回退第一个/内置。 */
    fun deleteContainerProfile(profile: com.wy.openx.container.ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.deleteCustomProfile(profile.id)
            containerInstaller.deleteCustomRootfs(profile)
            containerEngine.forgetProfile(profile.id)
            if (activeContainerId == profile.id) {
                val remaining = containerProfiles.filterNot { it.id == profile.id }
                containerSettingsRepository.setActiveProfile(
                    remaining.firstOrNull()?.id ?: com.wy.openx.container.ContainerProfile.BUILTIN_ID
                )
            }
        }
    }

    /** 空态恢复：把内置 Alpine 加回列表。 */
    fun restoreBuiltinAlpine() {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(com.wy.openx.container.ContainerProfile.BUILTIN_ALPINE)
        }
    }

    /** 开始下载目录镜像（单任务）；进度经 [containerDownloadState] 上报。 */
    fun downloadContainerImage(entry: com.wy.openx.container.ContainerImageEntry, sourceId: String) {
        if (downloadJob?.isActive == true) return
        val url = containerImageCatalogProvider.urlFor(
            entry, sourceId, com.wy.openx.container.ContainerImageCatalog.CURRENT_ABI
        )
        if (url == null) {
            containerDownloadState = ContainerDownloadUiState.Error(entry.id, "该源不提供此发行版/架构")
            return
        }
        containerDownloadState = ContainerDownloadUiState.Downloading(entry.id, 0L, 0L)
        downloadJob = viewModelScope.launch {
            try {
                val uri = containerImageDownloader.download(
                    url, "download_${entry.id}.tar.gz"
                ) { read, total ->
                    containerDownloadState = ContainerDownloadUiState.Downloading(entry.id, read, total)
                }
                containerSettingsRepository.upsertDownloadedImage(
                    com.wy.openx.container.DownloadedImageRecord(entry.id, uri, installed = false)
                )
                containerDownloadState = ContainerDownloadUiState.Idle
            } catch (e: CancellationException) {
                containerDownloadState = ContainerDownloadUiState.Idle
                throw e
            } catch (e: Exception) {
                containerDownloadState =
                    ContainerDownloadUiState.Error(entry.id, e.message ?: "下载失败，请检查网络")
            }
        }
    }

    /** 取消正在进行的下载（半成品文件由下载器清理）。 */
    fun cancelContainerImageDownload() {
        downloadJob?.cancel()
        downloadJob = null
        containerDownloadState = ContainerDownloadUiState.Idle
    }

    /**
     * 把已下载的镜像作为自定义 profile 导入容器列表（可重复导入，每次新建 profile），
     * 默认带 --link2symlink（Android 宿主硬链接模拟），与手动导入策略一致。
     */
    fun importDownloadedImage(entryId: String, fileUri: String) {
        val entry = containerImageCatalog.firstOrNull { it.id == entryId } ?: return
        val profile = com.wy.openx.container.ContainerProfile(
            id = "custom-${System.currentTimeMillis()}",
            name = "${entry.name} ${entry.version}",
            rootfsSource = com.wy.openx.container.RootfsSource.LocalFile(fileUri),
            shellPath = null,
            extraArgs = listOf("--link2symlink"),
            isBuiltin = false
        )
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(profile)
            containerSettingsRepository.upsertDownloadedImage(
                com.wy.openx.container.DownloadedImageRecord(entryId, fileUri, installed = true)
            )
        }
    }

    /** 删除已下载的镜像：清记录 + 删下载文件（仅限 rootfs_images 目录，防误删）。 */
    fun deleteDownloadedImage(entryId: String) {
        val record = downloadedImages[entryId] ?: return
        if ((containerDownloadState as? ContainerDownloadUiState.Downloading)?.entryId == entryId) {
            cancelContainerImageDownload()
        }
        viewModelScope.launch {
            runCatching {
                val file = java.io.File(android.net.Uri.parse(record.fileUri).path ?: return@runCatching)
                if (file.parentFile?.name == "rootfs_images") file.delete()
            }
            containerSettingsRepository.removeDownloadedImage(entryId)
        }
    }

    /**
     * 重置容器：停掉全部 PTY 会话后删除 rootfs/proot，下次打开终端页重新安装。
     * [onResult] 返回是否成功，供设置页展示反馈。
     */
    fun resetContainer(onResult: (Boolean) -> Unit) {
        if (containerResetBusy) return
        viewModelScope.launch {
            containerResetBusy = true
            var ok = false
            try {
                terminalEngine.stop()
                delay(300)
                withContext(Dispatchers.IO) { containerEngine.resetContainer() }
                ok = true
            } catch (_: Exception) {
            }
            containerResetBusy = false
            onResult(ok)
        }
    }

    // 自动压缩冷却：最近一次自动压缩后已发送的消息数，避免接近阈值时每条消息都触发压缩产生连篇摘要
    private var messagesSinceAutoCompact: Int
        get() = slot().messagesSinceAutoCompact
        set(v) { slot().messagesSinceAutoCompact = v }

    /**
     * ③ provider 真实 usage 的报告锚。**必须成对读写**：`reported` 描述的是 `mark` 那一刻的
     * 上下文，`effectiveContextTokens()` 靠这两者算出「真实值 + 其后新增」。
     *
     * 分成两处存放正是 09-28 那次「上下文圆环忽高忽低」的根因：mark 进了会话槽、
     * reported 留在 uiState 全局，并发时 A 上报的 reported 会配上 B 的 mark 一起算。
     */
    private data class UsageAnchor(val reported: Int, val baselineEstimate: Int)

    /**
     * 报告锚**按会话隔离**（作者 2.3.7 的方案）。原先是单个全局变量，切到别的会话后
     * 仍指着上一个会话的 (reported, mark)，于是对当前会话做压缩判定时会拿**别的会话**的
     * 真实值当基数 → 提前触发压缩，或该压却不压。
     */
    private val usageAnchors = java.util.concurrent.ConcurrentHashMap<Long, UsageAnchor>()

    /**
     * 「抑制建锚」的会话集合：历史被结构性改动（回退/编辑/删除/恢复）后加入，
     * 直到下一轮真实 usage 回来才移除。抑制期内 [ensureUsageAnchor] 不建锚，
     * 使 [effectiveContextTokens] 退回全量估算——避免用改动前的真实 usage 配新历史、
     * 把旧的大值贴回来（详见 [refreshContextAfterHistoryChange]）。
     */
    private val anchorSuppressed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())

    /**
     * 原子地推进报告锚。[tokens] 是 provider 对 [mark] 那一刻上下文的真实上报。
     * 任何改动上下文占用 / 报告点的地方都必须走这里，否则会把「真实值」和
     * 「其后新增」的起点拆开。
     *
     * ⚠️ [conversationId] 必须由调用方显式传入**本轮请求的会话**。多会话并行时
     *   `currentConversationId` 是「当下前台」，后台会话的 usage 回来若按前台记账，
     *   会把 A 的真实占用记到 B 名下（作者单前台模型下没有这个窗口，放开并行后就有了）。
     */
    private fun advanceUsageAnchor(tokens: Int, requestEstimate: Int, conversationId: Long) {
        if (tokens <= 0) return
        // 新一轮真实 usage 回来 = 已按当前历史重新计量，抑制解除（见 refreshContextAfterHistoryChange）。
        anchorSuppressed.remove(conversationId)
        // baselineEstimate 必须是**发出这次请求时**那份消息列表的估算（与 tokens 同口径），
        // 之后两者相减才等于「这次请求新增了多少」。用「此刻」的估算会把它算错。
        usageAnchors[conversationId] = UsageAnchor(tokens, requestEstimate)
        // 只有当它确实是前台时才刷新投影：否则后台会话的 usage 会把前台圆环数字带跑。
        if (conversationId == _uiState.currentConversationId) {
            uiState = uiState.copy(lastInputContext = tokens)
        }
    }

    /**
     * 历史被**结构性改动**（回退/编辑/重新生成/删除/按消息恢复）后，作废报告锚并**立刻重算**
     * 顶部上下文数字。
     *
     * 为什么必须做：`effectiveContextTokens` 用「报告锚(reported) + 锚点之后新增内容的估算」。
     * 一旦历史被截断，旧锚的 `reported/mark` 就指向了**一份不存在的历史**——
     *   - 旧实现里 `anchor.mark >= msgs.size` 时它直接返回旧的 `anchor.reported`（**旧的大值**）；
     *   - 或把已删除段落之后的内容当成「新增」，估出一个既非旧值也非新值的数。
     * 两者都会让顶部数字**继续停在不该停的地方**，用户看到「回退了/恢复了，数字却没降」。
     * 这正是 rewind/edit/regenerate/delete 长期没有刷新数字的根因。
     *
     * 处理分三步：
     *  1. 作废锚；
     *  2. 把该会话标记为「抑制建锚」——否则 [observeMessagesForConversation] 里紧接着的
     *     [ensureUsageAnchor] 会拿**改动前**的真实 usage（`conversationCtx` / DB `lastInputTokens`）
     *     配上一个新的小 `mark` 重新建锚，等于把旧的大 `reported` 又贴回新历史上，数字照样错。
     *     抑制持续到下一次真实 usage 回来（[advanceUsageAnchor] 解除）；
     *  3. 按当前消息全量重算并写回前台显示值（这是抑制期内唯一可信的口径）。
     */
    private fun refreshContextAfterHistoryChange(conversationId: Long, msgs: List<ChatMessageModel>) {
        usageAnchors.remove(conversationId)
        anchorSuppressed.add(conversationId)
        // conversationCtx 里存的 input 是「上一轮真实 usage」，对截断后的历史已失真；
        // 清掉其 input 只保留 requestCount，避免 ensureUsageAnchor 从它复活旧锚。
        conversationCtx[conversationId]?.let { conversationCtx[conversationId] = CtxRecord(input = 0, requestCount = it.requestCount) }
        if (conversationId == _uiState.currentConversationId) {
            uiState = uiState.copy(lastInputContext = estimateContextTokens(msgs))
        }
    }

    /** ② 本轮下发的 tools schema 的 token 估算（每轮全量发送、占着固定额度）。 */
    private var currentToolSchemaTokens: Int
        get() = slot().currentToolSchemaTokens
        set(v) { slot().currentToolSchemaTokens = v }

    /** 并发闸门：卡发送入口，第 4 个会话点发送会被拒绝（而非静默排队）。 */
    private val concurrencyGate = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_SESSIONS)

    /**
     * 当前在跑的请求数（主线程读写，viewModelScope 默认 Main.immediate，无需加锁）。
     * 用于判定「最后一个在跑的结束了」才清理 AppContextHolder 的全局 sink。
     */
    private var activeRequests = 0

    private companion object {
        const val AUTO_COMPACT_COOLDOWN = 6
        const val TAG = "MainViewModel"
        /** 插件声明的供应商在 `ai_providers` 里的主键前缀；避开用户手填的 id。 */
        const val PLUGIN_PROVIDER_ID_PREFIX = "plugin:"
        /**
         * 并行批内工具累计撞到几次 429 才提示「切回串行」。
         *
         * 取 3 而不是 1：偶发一次 429（供应商抖动、单次突发）不值得打断用户，
         * 切串行要付「同批工具耗时变长」的代价；连着 3 次才说明是并发度本身过高。
         */
        const val RATE_LIMIT_HIT_THRESHOLD = 3
        /**
         * 同时允许几个会话在跑。手机上的成本是真实的：每个跑着的会话背后是一条网络长连接
         * + 可能的 proot 容器进程 + 内存里的增量缓冲，设太大容易 OOM。
         *
         * 原为 3，实测偏紧（同时开 4 个就被拒），提到 6；再往上仍要谨慎——单会话成本是真实的，
         * 满 6 个已足够占满一台中端机的内存预算。
         */
        const val MAX_CONCURRENT_SESSIONS = 6
        // 标题触发轮数见 ConversationTitlePolicy.SUMMARY_TURNS（第一轮即触发，失败后续轮次重试）。

        /** 调用记录保留期：90 天。统计页最大按月聚合，再久的数据不会被读到。 */
        const val CALL_RECORD_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        /**
         * 悬浮窗状态行的工具名映射（与聊天页 `ClaudeThinkingIndicator` 内的同名表一致）。
         * 聊天页那份是函数内 `remember` 的局部表，无法直接复用，故此处保留一份；
         * 改动时两处需同步。
         */
        val OVERLAY_TOOL_NAMES = mapOf(
            "web_search" to "正在搜索互联网",
            "web_fetch" to "正在读取网页",
            "file_manager" to "正在操作文件",
            "file_analyzer" to "正在分析文件",
            "app_launcher" to "正在操作应用",
            "phone_operator" to "正在操作手机",
            "device_info" to "正在获取设备信息"
        )

        /** 悬浮窗正文最多保留的工具轨迹条数（调用 + 结果各算一条，实际取 `2N` 条消息兜底）。 */
        const val OVERLAY_MAX_TOOL_LINES = 4

        /**
         * 压缩后保留的**最近消息** token 预算上限（绝对量，不再随窗口放大）。
         *
         * 压缩后总上下文 = 固定开销（system 提示 + 内置规则 + 记忆 + 工具 schema，实测约 17K，
         * **压不掉**）+ 摘要（约 3K）+ 本预算。取 4K 使总量落在 ~24K，逼近「新会话 + 摘要」。
         *
         * 原先用 `窗口 × COMPACT_KEEP_RATIO`：窗口越大保留越多，200K 窗口下光尾部就 30K，
         * 加固定开销直接 47K+，与「压缩 ≈ 新会话 + 摘要」的预期严重不符。
         *
         * ⚠ 必须与 ChatRepository.TOOL_LOOP_COMPACT_KEEP_TOKENS 保持一致（两处压缩同一口径）。
         */
        const val COMPACT_KEEP_TOKENS_MAX = 4_000

        /**
         * 单会话最多保留的**压缩层数**（历史轮次上限）。
         *
         * 每次压缩新增一层 `折叠原文 + marker + 摘要`；长跑会话会无限叠层、存储越滚越大。
         * 第 `MAX_COMPACTION_LAYERS + 1` 次压缩后，自动删除**最老的一层**（含其折叠原文、
         * marker、摘要），窗口永远只留最新 5 次压缩的记忆。压缩本身不限次数，只是旧层会清理。
         */
        const val MAX_COMPACTION_LAYERS = 5

        /**
         * 保留的最近对话**轮**数下限：token 预算再紧，也至少留这么多轮，保证对话连贯。
         */
        const val COMPACT_KEEP_MIN_TURNS = 1

        /**
         * 压缩通知消息的**稳定前缀**，用户能直接看到。
         *
         * 恢复压缩时靠它把这些通知一并删掉：只删 `[对话摘要]` 占位的话，
         * 「📦 上下文已压缩（N 条 → 摘要）」会留下来——而摘要已经没了，
         * 那句话变成纯 misinformation（用户反馈：恢复后仍在提示已压缩）。
         * 定义成常量是为了让「写通知」和「删通知」永远用同一个字符串。
         */
        const val COMPACTION_NOTICE_PREFIX = "📦 "
        const val COMPACTION_NOTICE_FAIL_PREFIX = "⚠️ "

        /**
         * 解除压缩时要剔除的行 id 集合：marker + **紧随其后的摘要** + 旧格式通知。
         *
         * 为什么必须靠配对而不是内容前缀：现在压缩写出的摘要是**裸文本**（`performCompaction`
         * 的 `summaryMessage`，只有 marker 才有 `COMPACTION_MARKER` 这个可嗅探的定值），
         * 没有任何前缀能认出「这一行是摘要」。只有 marker 有标记 → 只能取「marker 的下一条」。
         * 这与 UI 层 `compactionSummaryByMarker` 是同一口径（那里也是按 id 级配对定位卡片），
         * 两边不一致就会出现「卡片显示得对、恢复却漏删摘要」。
         *
         * @param all 已按显示序（seq, id）排好的完整历史 —— 配对依赖「下一条」就是摘要。
         */
        fun compactionArtifactIds(all: List<ChatMessageModel>): Set<Long> {
            val out = HashSet<Long>(all.size)
            for (i in all.indices) {
                val m = all[i]
                val c = m.content
                if (c == COMPACTION_MARKER) {
                    out += m.id
                    all.getOrNull(i + 1)?.let { out += it.id }
                } else if (c != null && (c.startsWith(SUMMARY_PREFIX) ||
                        c.startsWith(COMPACTION_NOTICE_PREFIX) ||
                        c.startsWith(COMPACTION_NOTICE_FAIL_PREFIX))
                ) {
                    out += m.id
                }
            }
            return out
        }

        /** 单条工具结果在摘要输入里的截断上限（字符），避免个别超大输出挤爆压缩请求。 */
        const val COMPACT_TOOL_RESULT_CHARS = 2000

        /** 压缩请求自身的安全余量：预留这么多 token 给提示词与模型输出。 */
        const val COMPACT_RESERVE_TOKENS = 4096

        /** 压缩摘要输出上限兜底（settings 未配置时），须与 AppSettings.compactionMaxTokens 默认一致。 */
        const val COMPACT_DEFAULT_OUTPUT_TOKENS = 4096

        /** 输入区最多可同时附加的图片/文件数量。 */
        const val MAX_ATTACHMENTS = 8

        /** 「加入输入栏」时读预览的体量上限：超了就只投路径，不把整个大文件读进内存。 */
        const val ATTACHMENT_PREVIEW_MAX_BYTES = 512L * 1024

        /** 可预览的图片扩展名；命中则文件名原样保留，否则按 MIME 补扩展名。 */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

        /** `org.gradle.jvmargs` 整行（用于替换而不动其他键）。 */
        val GRADLE_JVMARGS_LINE = Regex("^\\s*org\\.gradle\\.jvmargs\\s*=.*$")

        /** 从 jvmargs 里抠出 `-Xmx` 的数值与单位（m 或 g）。 */
        val GRADLE_XMX_REGEX = Regex("-Xmx\\s*(\\d+)\\s*([mMgG])")
    }
    // 按会话记忆的真实输入上下文，切会话时恢复各自值避免跳变
    private data class CtxRecord(val input: Int, val requestCount: Int = 0)
    private val conversationCtx = HashMap<Long, CtxRecord>()

    /**
     * 把流式增量缓冲落进**指定会话**的消息列表。
     *
     * P2b：必须显式传 cid。原来它读 `_deltaAccumulator`（委托到**前台**槽）与 `uiState.messages`，
     * 后台会话的协程调它时会把 A 的增量写进前台 B —— 这是去掉「切会话杀流」后最致命的串台点。
     */
    private fun flushDeltaToMessages(conversationId: Long) {
        val slot = slotOf(conversationId)
        val text = slot.deltaAccumulator.toString()
        if (text.isEmpty()) return
        slot.deltaAccumulator.clear()
        val current = slot.messages
        // 只追加到「普通 assistant 文本消息」；工具消息（toolName != null）虽然也是 assistant 角色，
        // 但不能承接正文——否则 AI 输出正文时会把文字 append 到上一条工具消息上。
        // 压缩摘要是 ASSISTANT + author「系统」且排在展示序最末（压缩后），若不排除，
        // 新回答会被拼进摘要卡片，界面上看起来「AI 没有回复」。
        if (current.isNotEmpty() && current.last().role == MessageRole.ASSISTANT &&
            current.last().toolName == null && current.last().author != "系统") {
            val last = current.last()
            // 追写缓冲：追加是摊销 O(1)，不把整条正文再拷一遍（见 SessionSlot.streamText）。
            // 只有交给 UI 时才 toString()。
            val buf = slot.streamTextOf(last.id)
            // 首次为本条消息建缓冲时，把已落定的正文（若有）作种子——
            // 重试回退等路径可能已经把前半段写进了 messages。
            if (buf.isEmpty() && last.content.isNotEmpty()) buf.append(last.content)
            buf.append(text)
            val updated = last.copy(content = buf.toString())
            slot.messages = current.dropLast(1) + updated
            // 该消息已承载过思考就不重复开段（ensure 内部判重）。
            if (text.contains("<think")) ensureThinkingSegment(conversationId, last.id)
        } else {
            val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val assistantMessage = ChatMessageModel(
                id = nextMessageId.incrementAndGet(), role = MessageRole.ASSISTANT,
                author = "构建助手", time = replyTime, content = text
            )
            slot.messages = current + assistantMessage
            // 新建的 assistant 消息从空缓冲开始
            slot.streamTextOf(assistantMessage.id).append(text)
            // 新建 assistant 消息 = 一段新思考的开始（推理模型先吐 `<thinking>`，
            // 工具循环里上一段已随工具消息收尾，故每条新消息对应一段）。
            if (text.contains("<think")) ensureThinkingSegment(conversationId, assistantMessage.id)
        }
        if (conversationId == _uiState.currentConversationId) refreshProjection()
        // 后台会话的流式正文直接写槽、不经 editSlot，故这里补一次悬浮窗重算信号——
        // 否则并行跑时后台会话的正文在悬浮窗里永远停在第一帧。
        bumpOverlayRevision()
    }

    /**
     * 确保「该消息承载的那段思考」已在计时表中开段（幂等）。
     *
     * 一轮任务里 AI 常「思考 → 调工具 → 再思考」，每段落在各自的助手消息上，
     * 所以按消息 id 开段即可。已经开过的段不重置起点——重置会让读数倒退。
     */
    private fun ensureThinkingSegment(conversationId: Long, messageId: Long) {
        // 廉价前置检查：本函数在流式期间每 150ms 会被调一次（flushDeltaToMessages 的调用点），
        // 若段已存在就直接返回，避免每次都走 editSlot → refreshSessionBadges 的全槽位扫描。
        if (messageId in slotOf(conversationId).thinkingSegments) return
        editSlot(conversationId) {
            if (messageId in thinkingSegments) return@editSlot
            thinkingSegments = thinkingSegments + (messageId to ThinkingTiming(System.currentTimeMillis()))
        }
    }

    /**
     * 收尾「正在进行的思考段」：把该段结束时刻落定（幂等，已结束的不改）。
     *
     * 两个调用点：**发起工具调用**时（该段思考已经产出完、即将执行工具）、
     * 以及**整轮结束**时（最后一段，之后不再有工具）。
     *
     * 为什么结束时刻必须在 VM 落定：思考块是 LazyColumn 的 item，
     * 用户滚动时会被销毁、UI 侧的计时协程随即取消，只在 UI 定格会少算秒数。
     *
     * @param messageId 指定收尾哪条消息的段；null = 收尾该会话**最后一条**仍进行中的段。
     */
    private fun finishThinkingSegment(conversationId: Long, messageId: Long? = null) {
        editSlot(conversationId) {
            val target = messageId ?: thinkingSegments.entries
                .filter { it.value.endAt == null }
                .maxByOrNull { it.key }?.key ?: return@editSlot
            val cur = thinkingSegments[target] ?: return@editSlot
            if (cur.endAt != null) return@editSlot
            thinkingSegments = thinkingSegments + (target to cur.copy(endAt = System.currentTimeMillis()))
        }
        // 这条消息的正文已落定：丢掉可追写缓冲（内容已在 messages.content 里）。
        // 不丢的话缓冲会一直驻留，白白占着一份与正文等长的内存。
        messageId?.let { slotOf(conversationId).streamText.remove(it) }
    }

    // ===== 悬浮窗（偏好设置开关）=====

    /**
     * 悬浮窗内容的重算信号。
     *
     * **为什么需要它**：槽位（[SessionSlot]）里那些会话级字段是**普通 var，不是 Compose 状态**，
     * 后台会话写它们时既不碰 `uiState` 也不触发重组 —— `snapshotFlow` 观察不到。
     * 旧版直接观察 `uiState.isSending` 等，读到的只是**前台投影**，所以并行跑多个会话时
     * 悬浮窗只能显示当前前台那个。这里用一个自增计数当「槽位任一变化」的统一信号，
     * 在 [refreshSessionBadges]（所有会话级写入的必经点）与流式落盘处自增。
     */
    private var overlayRevision by mutableStateOf(0)

    private fun bumpOverlayRevision() {
        overlayRevision++
    }

    /**
     * 悬浮窗的轻量快照（**不含正文**）。
     *
     * 正文要跑正则（剥离 thinking），而快照在流式期间每 150ms 重算一次；故只带
     * 「内容是否变了」的轻量信号，正文在实际要推给控制器时才拼。
     */
    private data class OverlaySnapshot(
        val enabled: Boolean,
        val foregroundId: Long,
        val sessions: List<OverlaySessionKey>
    )

    /** 单个会话的轻量信号（决定是否重算该会话正文，以及标签文案）。 */
    private data class OverlaySessionKey(
        val id: Long,
        val title: String,
        val state: OverlaySessionState,
        val toolName: String?,
        val compacting: Boolean,
        val reconnecting: Boolean,
        val startedAt: Long,
        /** 内容变化信号：最后一条消息 id 与长度（新消息 → id 变；流式增长 → 长度变）。 */
        val lastMsgId: Long,
        val lastMsgLen: Int
    )

    /**
     * 取**指定会话**最后一条助手消息的**正文**（已剥离 `<thinking>` 与 `<search>`）。
     *
     * 不能直接显示 `msg.content`——思考过程会混在正文里，用户看到的会是一堆推理文字。
     * 复用聊天页同一套 [ChatUtils.extractThinkingContent]，保证与主界面一致。
     *
     * 必须按会话取（不能用前台投影）：多会话并行时每个标签都要显示自己那份正文。
     */
    private fun lastAssistantBody(conversationId: Long): String {
        val msg = slotOf(conversationId).messages.lastOrNull() ?: return ""
        if (msg.role != MessageRole.ASSISTANT || msg.toolName != null) return ""
        return ChatUtils.extractThinkingContent(msg.content).first
    }

    /**
     * 指定会话的悬浮窗正文：最近的**工具轨迹 + 流式正文**。
     *
     * 只显示最后一条助手正文信息太少——「边跑边看」想知道 AI 调了哪些工具、成没成功、
     * 现在写到哪了。故按时间顺序拼一段最近的消息摘要：
     * 工具调用行（`▸ 正在调用 xxx`）与结果行（`✓/✗ xxx`）在前，流式正文在后。
     *
     * 只取最近 [OVERLAY_MAX_TOOL_LINES] 条工具相关消息，避免面板塞满历史。
     * 末尾按 [FloatingOverlayController.MAX_BODY_CHARS] 截断（截断放这里，
     * 免得先造出巨大字符串再由控制器裁）。
     */
    private fun overlayBodyText(conversationId: Long): String {
        val msgs = slotOf(conversationId).messages
        if (msgs.isEmpty()) return ""
        val sb = StringBuilder()

        // 工具轨迹：最近的工具调用/结果消息（按时间正序）
        msgs.takeLast(OVERLAY_MAX_TOOL_LINES * 2).forEach { m ->
            when {
                m.role == MessageRole.ASSISTANT && m.toolName != null ->
                    sb.append("▸ ").append(m.content.removePrefix("[调用工具] ")).append('\n')
                m.role == MessageRole.SYSTEM && m.toolResult != null -> {
                    val ok = !com.wy.openx.data.util.ToolResultStatus.isError(m.toolResult)
                    // content 已是「✅/❌ xxx 调用成功/失败」的现成文案，直接摘出来即可
                    val line = m.content.trim().removePrefix("✅").removePrefix("❌").trim()
                    sb.append(if (ok) "✓ " else "✗ ").append(line).append('\n')
                }
            }
        }

        // 流式正文：最后一条助手消息的可见部分
        val body = lastAssistantBody(conversationId)
        if (body.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(body)
        }
        val text = sb.toString().trim()
        return if (text.length > FloatingOverlayController.MAX_BODY_CHARS)
            text.takeLast(FloatingOverlayController.MAX_BODY_CHARS) else text
    }

    /** 悬浮窗状态行文案（与聊天页 [AgentConsoleScreen] 的思考指示器保持同一套措辞）。 */
    private fun overlayStatusText(key: OverlaySessionKey): String = when (key.state) {
        // 已完成：不再是「正在思考」，明说是完成（用户正是靠这句知道可以看了）
        OverlaySessionState.DONE -> "已完成"
        OverlaySessionState.WAITING -> "等待你处理"
        OverlaySessionState.RUNNING -> when {
            key.compacting -> "正在压缩上下文"
            key.reconnecting -> "重连中"
            key.toolName != null -> OVERLAY_TOOL_NAMES[key.toolName] ?: "正在使用 ${key.toolName}"
            else -> "AI 正在思考"
        }
    }

    /**
     * 汇总**所有槽位**里值得展示的会话，供悬浮窗使用。
     *
     * 值得展示 = 运行中 / 后台跑完未读 / 正在等你处理。这正是侧边栏徽标那三种状态，
     * 口径与 `SessionSlot.badge()` 一致（等待你 > 运行中 > 有新结果）。
     *
     * 排序：按状态优先级（枚举 ordinal），同状态按最近活跃靠前——
     * 让「还在跑的」始终顶在标签行最前面。
     *
     * ⚠ 必须遍历 `slots` 而不是读 `uiState`：后者只是前台投影，
     * 这正是旧版「悬浮窗只显示当前会话」的根因。
     */
    private fun activeOverlaySessions(): List<OverlaySessionKey> {
        val out = ArrayList<OverlaySessionKey>(slots.size)
        for ((cid, s) in slots) {
            val state = when {
                s.awaitingAttention() -> OverlaySessionState.WAITING
                s.isSending -> OverlaySessionState.RUNNING
                s.completedUnread -> OverlaySessionState.DONE
                else -> continue
            }
            out += OverlaySessionKey(
                id = cid,
                title = _uiState.conversations.firstOrNull { it.id == cid }?.title ?: "会话",
                state = state,
                toolName = s.currentToolName,
                compacting = s.isCompacting,
                reconnecting = s.reconnecting,
                // 计时按会话各算各的（旧版单一全局 startedAt 会把切过去那一刻当成起跑点）。
                startedAt = s.thinkingStartedAt,
                lastMsgId = s.messages.lastOrNull()?.id ?: 0L,
                lastMsgLen = s.messages.lastOrNull()?.content?.length ?: 0
            )
        }
        // 稳定排序：状态优先级 → 最近活跃。用 lastTouchedAt 而非 startedAt，
        // 因为「等待你」的会话 startedAt 可能为 0，用它排会乱。
        return out.sortedWith(
            compareBy<OverlaySessionKey> { it.state.ordinal }
                .thenByDescending { slots[it.id]?.lastTouchedAt ?: 0L }
        )
    }

    init {
        loadMemory()
        loadSkills()
        loadMcpServers()
        // 运行日志统一来自 AppLogStore（内存环形缓冲 + 落盘），业务日志与容器/MCP/
        // 技能等模块日志汇于一处；重启后仍能看到上次会话的日志尾部。
        AppLogStore.entries.onEach { entries ->
            uiState = uiState.copy(logs = entries)
        }.launchIn(viewModelScope)
        val savedTokens = tokenPrefs.getLong("total_tokens", 0L)
        val savedInputTokens = tokenPrefs.getLong("total_input_tokens", 0L)
        val allKeys = tokenPrefs.all.filterKeys { it.startsWith("model_") }
        val details = mutableMapOf<String, Long>()
        allKeys.forEach { (key, value) -> details[key.removePrefix("model_")] = (value as? Long) ?: 0L }
        val savedCacheHits = tokenPrefs.getLong("cache_hit_tokens", 0L)
        val allCacheKeys = tokenPrefs.all.filterKeys { it.startsWith("cache_model_") }
        val cacheDetails = mutableMapOf<String, Long>()
        allCacheKeys.forEach { (key, value) -> cacheDetails[key.removePrefix("cache_model_")] = (value as? Long) ?: 0L }
        uiState = uiState.copy(totalTokens = savedTokens, totalInputTokens = savedInputTokens, tokenDetails = details, cacheHitTokens = savedCacheHits, cacheHitDetails = cacheDetails)
        settingsStore.settingsFlow.onEach { saved ->
            // 持久化设置到位后，若此前因未加载而无法解析 activeProvider，这里补解析一次。
            val wasLoaded = persistedSettingsLoaded
            persistedSettingsLoaded = true
            uiState = uiState.copy(settings = saved)
            // 必须同步进程级设置副本：工具链（memory/skill/terminal）与 Git 页都不走 uiState，
            // 而是读 AppContextHolder.currentSettings。此前只在 updateSettings（用户改设置）里赋值，
            // 冷启动后该副本一直是 null → 依赖它的功能（如 Git 页取「当前项目路径」）退化成
            // 「默认工作区」，表现为「一打开 Git 页看不到当前项目的仓库，切一次项目才正常，
            // 重启后又坏」。这里跟着持久化流刷新，冷启动即可用。
            com.wy.openx.tools.AppContextHolder.currentSettings = saved
            // 出站脱敏开关随设置实时生效（不发下次请求才生效，否则用户关了却还被掩）
            chatRepository.maskOutbound = saved.maskOutbound
            if (!wasLoaded && providers.isNotEmpty()) resolveActiveProvider()
        }.launchIn(viewModelScope)

        // 悬浮窗（偏好设置开关）：把**全部活动会话**推给进程级控制器。
        //
        // 观察两个源：`overlayRevision`（槽位任一变化的统一信号，见其注释）与
        // `uiState.settings.floatingOverlay`（开关）。之所以不能只观察 uiState：
        // 后台会话的流式写入只落到槽位里，uiState 不跟着变（那是前台投影），
        // 只观察 uiState 会导致后台会话进度不刷新。
        //
        // 快照刻意**不含正文**：正文要跑正则（剥离 thinking），而本块在流式期间每 150ms
        // 执行一次；放进快照键等于用户没开悬浮窗也白跑正则。正文只在真要推送时才拼。
        snapshotFlow {
            overlayRevision
            OverlaySnapshot(
                enabled = uiState.settings.floatingOverlay,
                foregroundId = uiState.currentConversationId,
                sessions = activeOverlaySessions()
            )
        }.distinctUntilChanged().onEach { snap ->
            overlayController.setEnabled(snap.enabled)
            // 仅开关打开时才拼正文（跑正则）。注意这里逐会话拼，每个会话显示自己的进度。
            val sessions = if (snap.enabled) {
                snap.sessions.map { key ->
                    OverlaySession(
                        conversationId = key.id,
                        title = key.title,
                        state = key.state,
                        statusText = overlayStatusText(key),
                        body = overlayBodyText(key.id),
                        startedAt = key.startedAt
                    )
                }
            } else {
                emptyList()
            }
            overlayController.setSessions(sessions, snap.foregroundId)
        }.launchIn(viewModelScope)

        conversationRepository.observeConversations().onEach { savedConversations ->
            if (savedConversations.isNotEmpty()) {
                uiState = uiState.copy(conversations = savedConversations)
                // 偏好设置「启动时不恢复上次对话」：仅在**首次**收到会话列表时新建一个会话，
                // 之后每次列表变化都沿用原有逻辑。
                // 若每次都判断，用户自己切会话/新建后列表一变就又被强行新建，等于功能不可用。
                if (!startupConversationResolved) {
                    startupConversationResolved = true
                    if (!uiState.settings.restoreLastConversation) {
                        createNewConversation()
                        return@onEach
                    }
                }
                // 选择目标会话：优先 active；否则**沿用当前会话**（只要它还在列表里）。
                // 排序改为「置顶优先」后 first() 不再等价于当前会话——若仍直接取 first()，
                // 新建会话的中间态（setActiveConversation(-1) 后、新会话 insert 前，active 全为 0）
                // 会把界面切到某个置顶会话上。
                val activeId = savedConversations.firstOrNull { it.active }?.id
                val keepCurrentId = uiState.currentConversationId
                    .takeIf { cur -> savedConversations.any { it.id == cur } }
                val targetId = activeId ?: keepCurrentId ?: savedConversations.first().id
                // 仅在「目标会话变化」时才恢复上下文 / 重挂消息观察。
                // 会话列表会在每次发送消息（touchLastActive 上浮）或改标题时重发（Room Flow 重放），
                // 若每次都 restoreConversationCtx，会把流式期间 onTokens 刚刷新的 lastInputContext
                // 用内存里的旧值覆盖掉 → 圆环在「真实占用」与「旧值/0」之间闪。同会话重发只刷新列表即可。
                if (targetId != uiState.currentConversationId) {
                    restoreConversationCtx(targetId)
                    observeMessagesForConversation(targetId)
                }
            } else {
                createNewConversation()
            }
        }.launchIn(viewModelScope)

        settingsStore.apiPresetsFlow.onEach { presets ->
            uiState = uiState.copy(apiPresets = presets)
        }.launchIn(viewModelScope)

        // 归档会话（「历史」页）。与主列表分开观察：主列表已过滤归档，
        // 若共用一个 Flow 会导致「归档后当前会话从列表消失」时无法区分是归档还是删除。
        conversationRepository.observeArchivedConversations().onEach { archived ->
            uiState = uiState.copy(archivedConversations = archived)
        }.launchIn(viewModelScope)
    }

    /**
     * 压缩完成/失败的系统通知消息（工具循环就地压缩 + 发送结束后的自动压缩共用）：
     * 内存 append 即时可见，同时异步落库持久（isSending 复位触发 Room 重载后可保留）。
     */
    /**
     * @param midRun true = 工具循环进行中的就地压缩（这一轮还没结束，所以要提示「继续执行」）。
     *   false = 轮次之间的压缩（自动阈值 / 手动 /compact），此时**不该**说「继续执行剩余任务」——
     *   那句话在手动压缩后是错的（用户是自己发起的，没有「剩余任务」在跑）。
     */
    private fun appendCompactionNotice(
        status: String,
        summary: String?,
        foldedCount: Int,
        conversationId: Long,
        midRun: Boolean = false
    ) {
        // P2b：通知落进「发生压缩的那个会话」的槽，而不是当下前台（后台会话压缩时用户在看别的会话）。
        val cid = conversationId
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        // 再剥一次思维链：调用方可能来自工具循环那条路径（不复用 performCompaction 的清洗），
        // 300 字截断落在 thinking 标签中间会露出半截标签。
        val brief = if (summary.isNullOrBlank()) "" else {
            val s = stripThinkingBlocks(summary)
            if (s.length > 300) s.take(300) + "…" else s
        }
        val tail = if (midRun) "，继续执行剩余任务。" else "。"
        val notice = ChatMessageModel(
            nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", t,
            if (status == "completed")
                "${COMPACTION_NOTICE_PREFIX}上下文已压缩（$foldedCount 条 → 摘要）$tail${if (brief.isNotEmpty()) "\n\n$brief" else ""}"
            else
                "${COMPACTION_NOTICE_FAIL_PREFIX}上下文压缩失败，已收缩较早的工具输出兜底$tail"
        )
        editSlot(cid) {
            isCompacting = false
            messages = messages + notice
        }
        if (cid >= 0) {
            viewModelScope.launch {
                runCatching { messageRepository.insert(cid, notice) }
            }
        }
    }

    private fun observeMessagesForConversation(conversationId: Long) {
        // 保留旧职责：若调用方尚未切 cid（启动恢复路径 L890 就是如此），这里补上 ——
        // 走 setter 会自动完成「旧前台存回旧槽 + 新槽投影」的换投影动作。
        if (conversationId != _uiState.currentConversationId) {
            uiState = uiState.copy(currentConversationId = conversationId)
        }
        loadTasksIfNeeded(conversationId)
        // 必须显式用 conversationId 定位槽：switchConversation 会先把 currentConversationId
        // 改成新会话再调本函数，走 slot()/别名会取到「新」槽、旧会话的 Flow 收集变孤儿。
        val s = slotOf(conversationId)
        s.observeJob?.cancel()
        // UI 只有**一路**消息流】。
        //
        // 原先是两路（`observeIds` 折叠=0 + `observeFoldedIds` 折叠=1），并且压缩时还要
        // 手工 cancel 两路 → 写库 → 手工把内存对齐 → 再重建两路。那套 cancel/recreate 就是
        // 「用户按暂停历史就消失」的根源：取消会命中中间挂起点，两路状态就此错位，
        // 而重建失败时界面永久停在「折叠区空 = 历史全没了」。
        //
        // 一路之后压缩只是一次普通写（`commitCompaction` 事务）：Room 发射一次、这里更新一次，
        // 没有需要手工对齐的第二份状态，暂停也无处可破坏。
        s.observeJob = messageRepository.observeAllMessages(conversationId)
            .onEach { all ->
                s.loaded = true
                val ctx = all.filterNot { it.folded }
                val folded = all.filter { it.folded }
                // ⚠ 发送中**不回填 messages**，这不是保守，是必需的：
                // 流式正文只进内存（`flushDeltaToMessages` 只改 `slot.messages`，**不写库**），
                // 库里落后于内存。而工具调用/结果会写库 → Room 发射 → 若此时回填，
                // 就会把内存里正在增长的正文**回退掉**（正文一闪一闪/半句消失）。
                // 折叠区不同：它只在压缩/恢复时变，而压缩可能发生在任何发送阶段，
                // 所以**发送中也要刷新折叠区**（否则「发送中触发的压缩」折叠区永远不刷新）。
                if (!s.isSending) {
                    s.messages = ctx
                    s.foldedMessages = folded
                    if (conversationId == _uiState.currentConversationId) {
                        _uiState = _uiState.copy(messages = ctx, foldedMessages = folded)
                    }
                } else {
                    s.foldedMessages = folded
                    // 【关键】发送中**也要**把压缩产物回填进 messages，但必须**内存优先**合并。
                    //
                    // 原先这里是「发送中一律不回填 messages」，动机是对的（流式正文只进内存、
                    // 库里落后于内存，直接覆盖会把正在增长的正文回退掉，表现为正文一闪一闪）。
                    // 但这个一刀切把**压缩产物**也一起挡了：marker/摘要属于 ctx（folded=false）
                    // 里的新行，回填被跳过 → 压缩卡不显示；而库里数据**早就写好了**——
                    // 用户看到的正是「暂停 AI 后卡片才出现」：暂停让 isSending 变 false，
                    // 走上面那条完整回填分支，卡片这才补上。数据没问题，是通知被门控误伤。
                    //
                    // 所以按「谁独有」分开处理，而不是整体跳过：
                    //   · 仅内存有 = 正在流式、还没落库的正文 → 保留，绝不回退；
                    //   · 仅 DB 有   = 压缩产物（marker+摘要）、已落库的工具调用/结果 → 立刻可见。
                    val merged = mergeKeepingMemory(s.messages, ctx)
                    s.messages = merged
                    if (conversationId == _uiState.currentConversationId) {
                        _uiState = _uiState.copy(foldedMessages = folded, messages = merged)
                    }
                }
                // 【修正】建锚必须在消息赋值**之后**，并显式传本次的条数。
                // 之前它在 `s.messages = savedMessages` 之前调，读到的是旧值：
                //   · 首次发射读到空槽（msgs=0）→ 不建锚 → 发第一条消息时 effectiveContextTokens
                //     退回**全量估算**（1870 条≈1.3M）→ 误判超窗→**把整个会话压掉**（丢历史）。
                //   · 流式中间读到 3 条 → 建出 mark=2 的错锚 → delta 从 index 2 起估 1868 条，同样虚高。
                ensureUsageAnchor(conversationId, ctx)
            }.launchIn(viewModelScope)
    }

    /**
     * 合并「内存上下文」与「DB 发射的上下文」，**内存优先**。
     *
     * 发送中 DB 落后于内存（`flushDeltaToMessages` 只改 `slot.messages`、不写库），
     * 于是两个方向的需求是相反的：
     *   · 用 DB 快照整表覆盖 → 把正在流式增长的正文回退掉（正文一闪一闪/半句消失）；
     *   · 完全不覆盖       → 压缩产物（marker + 摘要）要等到下一次暂停才可见，
     *                        用户看到「压缩完了但卡片不出现，一暂停就出现」。
     *
     * 两者按「谁独有」拆开就同时满足：
     *   · 同 id     → 取**内存**版本（保住已流式出来的正文）；
     *   · 仅内存有  → 保留（未落库的流式正文）；
     *   · 仅 DB 有   → 补进来（压缩产物、已落库的工具调用与结果）。
     *
     * 排序沿用 UI 的显示序口径（见 `AgentConsoleScreen.displayMessages`）：
     * `seq == 0` 表示尚未落库的内存消息，排到最后 —— 它们正在生成，本来就该在最末尾。
     */
    private fun mergeKeepingMemory(
        mem: List<ChatMessageModel>,
        db: List<ChatMessageModel>
    ): List<ChatMessageModel> {
        if (db.isEmpty()) return mem
        if (mem.isEmpty()) return db
        val memIds = mem.mapTo(HashSet(db.size)) { it.id }
        return (mem + db.filterNot { it.id in memIds })
            .distinctBy { it.id }
            .sortedWith(compareBy({ if (it.seq == 0L) Long.MAX_VALUE else it.seq }, { it.id }))
    }

    fun openSettings(section: String? = null) {
        settingsInitialSection = section
        screen = AppScreen.Settings
    }

    /** 设置页消费完初始分区后清除，避免下次从侧边栏进入时又跳回该分区。 */
    fun consumeSettingsInitialSection() { settingsInitialSection = null }
    fun openConsole() { screen = AppScreen.Console }
    fun openTerminal() { screen = AppScreen.Terminal }
    fun openQuantTrade() { screen = AppScreen.QuantTrade }
    /**
     * 打开内置浏览器。传 [path] 时顺带加载该文件（容器路径，如 `/root/workspace/a.html`），
     * 供侧边栏文件树长按「用浏览器打开」使用；不传则只切到浏览器页（顶栏按钮）。
     */
    fun openBrowser(path: String? = null) {
        screen = AppScreen.Browser
        if (!path.isNullOrBlank()) {
            viewModelScope.launch { browserManager.navigate(path) }
        }
    }
    fun openWorkflow() { screen = AppScreen.Workflow; loadWorkflows() }
    fun openGit() { screen = AppScreen.Git }
    fun openGitCredentials() { screen = AppScreen.GitCredentials }
    fun openHistory() { screen = AppScreen.History }
    fun openPreferences() { screen = AppScreen.Preferences }
    fun openTokenStats() { screen = AppScreen.TokenStats }
    fun openLogs() { screen = AppScreen.Logs }
    fun openPermissions() { screen = AppScreen.Permissions }
    fun openStorageUsage() { screen = AppScreen.StorageUsage }
    /** 打开「关于」子页（版本 / 构建时间 / 仓库等）。 */
    fun openAbout() { screen = AppScreen.About }
    /** 从「Token 统计 / 运行日志」返回设置页。 */
    fun backToSettings() { screen = AppScreen.Settings }
    fun updatePrompt(value: String) { uiState = uiState.copy(prompt = value) }


    fun applyQuickTask(prompt: String) { uiState = uiState.copy(prompt = prompt) }

    fun switchConversation(conversationId: Long) {
        // 切过来即视为「已读」：🔵 有新结果徽标清掉
        slotOf(conversationId).completedUnread = false
        // P2b：不再 stopStreaming()——切会话只换前台投影，正在跑的会话继续在后台跑。
        // 前提：本轮协程内所有会话级读写已绑到它自己的槽（requestConversationId），不会写错会话。
        viewModelScope.launch {
            // 先把新会话的消息从 DB 装进槽位并标记 loaded：否则切过去的瞬间投影仍是旧会话的
            // 消息（projectOnto 在 loaded=false 时沿用传入值），要等 Flow 首次发射才纠正。
            val target = slotOf(conversationId)
            if (!target.loaded) {
                // 单一来源：一次读全量（含折叠历史）再按 folded 拆两份，避免两路查询中间态不一致。
                val all = withContext(Dispatchers.IO) { messageRepository.getAllMessagesOfConversation(conversationId) }
                target.messages = all.filterNot { it.folded }
                target.foldedMessages = all.filter { it.folded }
                target.loaded = true
            }

            conversationRepository.setActiveConversation(conversationId)
            val updated = uiState.conversations.map { it.copy(active = it.id == conversationId) }
            uiState = uiState.copy(conversations = updated, currentConversationId = conversationId)
            restoreConversationCtx(conversationId)
            observeMessagesForConversation(conversationId)
            // 切完再回收：此时新会话已加载，旧会话若没在用就释放消息正文
            evictIdleSlots()
        }
    }

    /** 搜索指定会话的聊天记录：一次性读取全量消息后在内存中做大小写不敏感匹配。 */
    fun searchConversationMessages(
        conversationId: Long,
        query: String,
        onResult: (List<ChatMessageModel>) -> Unit
    ) {
        val q = query.trim()
        if (q.isEmpty()) {
            onResult(emptyList())
            return
        }
        viewModelScope.launch {
            val key = q.lowercase()
            val hits = withContext(Dispatchers.IO) {
                // 必须用「含折叠历史」的全量查询：压缩后老消息仍在库里，
                // 只搜未折叠的部分等于让用户再也搜不到压缩掉的历史。
                messageRepository.getAllMessagesIncludingFolded(conversationId).filter { m ->
                    m.content.lowercase().contains(key) ||
                        m.toolName?.lowercase()?.contains(key) == true ||
                        m.toolResult?.lowercase()?.contains(key) == true ||
                        m.toolArguments?.lowercase()?.contains(key) == true ||
                        m.steps.any { it.lowercase().contains(key) }
                }
            }
            onResult(hits)
        }
    }

    /** 搜索命中后：切到目标会话并请求聊天列表滚动定位到该消息。 */
    fun jumpToMessage(conversationId: Long, messageId: Long) {
        if (uiState.currentConversationId != conversationId) {
            switchConversation(conversationId)
        }
        uiState = uiState.copy(
            pendingMessageJump = PendingMessageJump(
                conversationId = conversationId,
                messageId = messageId,
                nonce = System.currentTimeMillis()
            )
        )
    }

    fun consumeMessageJump() {
        if (uiState.pendingMessageJump != null) {
            uiState = uiState.copy(pendingMessageJump = null)
        }
    }

    fun createNewConversation() {
        // P2b：新建会话不再杀掉旧会话的流（旧会话转入后台继续跑），
        // 也不能 cancel 前台的 observeJob（那是旧会话自己的观察协程，它还要靠它刷新状态）。
        viewModelScope.launch {
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            conversationRepository.setActiveConversation(-1)
            val conversation = ConversationModel(
                id = 0, title = "新会话 $time",
                // 会话级模式下新会话**尚未选模型**：写全局模型会让会话列表里
                // 立刻显示上一个会话的模型名，与顶栏的 [未选择模型] 自相矛盾。
                // 同理，全局模式下若还没绑定任何提供商（全新安装），settings.model 只是
                // 出厂默认值，不能当成已选模型写进会话列表。
                meta = if (!uiState.settings.modelGlobalMode ||
                    uiState.settings.selectedProviderId.isBlank()
                ) "刚刚 · [未选择模型]"
                else "刚刚 · ${uiState.settings.model}",
                active = true,
                // 新会话立即可见地排在前面；否则 lastActiveAt=0 会沉到所有聊过的会话之后
                lastActiveAt = System.currentTimeMillis()
            )
            val newId = conversationRepository.insert(conversation)
            uiState = uiState.copy(
                currentConversationId = newId, messages = emptyList(), prompt = "", attachments = emptyList(),
                tasks = emptyList(), tasksExpanded = false
            )
            resetInputContext()
            observeMessagesForConversation(newId)
        }
    }

    /**
     * 从某条消息分叉新会话：复制该消息（含）之前的全部历史，原会话不动。
     *
     * 助手回复和用户消息都能作分叉点（聊天页两侧都给了入口）：从用户消息分叉出来的新会话
     * 末尾就是那条提问，切过去等同「重新问一遍」。工具结果行（SYSTEM）不能作分叉点。
     *
     *  OpenX 的消息 id 是库自增、且折叠历史
     * 仍然显示/可选，所以复制时走 [ConversationForkPolicy] 统一整理（重置 id、裁工具尾、
     * 必要时恢复 folded）。标题直接定稿，避免新会话的首轮请求又把标题改掉。
     */
    fun forkFromMessage(message: ChatMessageModel) {
        val sourceId = uiState.currentConversationId
        if (sourceId <= 0L || message.role == MessageRole.SYSTEM) return
        viewModelScope.launch {
            val source = uiState.conversations.firstOrNull { it.id == sourceId }
            if (source == null) {
                showToast("分叉失败：找不到当前会话")
                return@launch
            }
            val prepared = withContext(Dispatchers.IO) {
                val all = messageRepository.getAllMessagesOfConversation(sourceId)
                ConversationForkPolicy.prepareCopy(ConversationForkPolicy.prefixUpTo(all, message.id))
            }
            if (prepared.isEmpty()) {
                showToast("分叉失败：该消息暂无可用历史")
                return@launch
            }

            val now = System.currentTimeMillis()
            // 全局模式跟当前全局模型走，会话级模式才显示分叉源自己的选择（null = 未选择）
            // 未绑定提供商时 settings.model 只是出厂默认值，不算已选。
            val modelLabel = if (uiState.settings.modelGlobalMode) {
                uiState.settings.model.takeIf { uiState.settings.selectedProviderId.isNotBlank() }
            } else {
                source.model
            }
            val conversation = ConversationModel(
                id = 0,
                title = "分支 · ${source.title}",
                meta = if (modelLabel != null) "刚刚 · $modelLabel" else "刚刚 · [未选择模型]",
                active = true,
                requestCount = 0,
                lastInputTokens = 0,
                lastActiveAt = now,
                providerId = source.providerId,
                model = source.model,
                titleFinalized = true
            )
            val newId = withContext(Dispatchers.IO) {
                // 与 createNewConversation 一致：先清掉旧 active，避免列表里出现两个 active
                conversationRepository.setActiveConversation(-1)
                val id = conversationRepository.insert(conversation)
                messageRepository.insertAll(id, prepared)
                id
            }

            uiState = uiState.copy(
                currentConversationId = newId,
                messages = emptyList(),
                prompt = "",
                attachments = emptyList(),
                tasks = emptyList(),
                tasksExpanded = false
            )
            resetInputContext()
            switchConversation(newId)
            // 会话列表 Flow 到下一帧才会把新行的模型选择灌进槽位；这里先补上，
            // 避免用户分叉后立刻发消息被「请先为本会话选择模型」拦住。
            editSlot(newId) {
                providerIdOverride = source.providerId
                modelOverride = source.model
                titleFinalized = true
            }
            showToast("已从此处新建分支")
        }
    }

    /** 追加一个附件（图片或普通文件）。图片复制到工作区 uploads 目录并可 base64 发送给模型。 */
    fun addAttachment(uri: Uri, name: String) {
        if (uri == null) return
        if (uiState.attachments.size >= MAX_ATTACHMENTS) {
            showToast("最多添加 $MAX_ATTACHMENTS 个附件")
            return
        }
        if (uiState.attachments.any { it.uri == uri }) {
            showToast("已在输入栏中")
            return
        }
        viewModelScope.launch {
            val mimeType = appContext.contentResolver.getType(uri) ?: ""
            val isImage = mimeType.startsWith("image/")
            // 取真实文件名：调用方传的名字为空或占位（如「图片」）时，从 contentResolver 查 DISPLAY_NAME
            val displayName = withContext(Dispatchers.IO) {
                resolveDisplayName(uri, name, mimeType)
            }
            // 先占位显示，内容和落盘异步完成。
            // 用 filterNot + 追加而不是直接追加：协程里取 MIME / 查文件名 / 读预览都是挂起点，
            // 连续两次选同一张图时两个协程都能绕过入口的去重检查，留下两条同 URI 的附件；
            // 而输入栏附件条是 LazyRow、拿 URI 当 key，重复 key 会直接 throw 把 App 打挂。
            val placeholder = com.wy.openx.ui.state.ChatAttachment(
                uri = uri, fileName = displayName,
                mimeType = mimeType, isImage = isImage
            )
            uiState = uiState.copy(
                attachments = uiState.attachments.filterNot { it.uri == uri } + placeholder
            )

            val textPreview = if (isImage) {
                ""
            } else {
                withContext(Dispatchers.IO) {
                    FileContentReader.readText(appContext, uri).getOrElse { "文件读取失败：${it.message ?: "未知错误"}" }
                }.take(4000)
            }

            // 复制到容器内可见的工作区目录：<workspace>/uploads（容器内 /root/workspace/uploads）
            val safeName = sanitizeUploadName(displayName, mimeType, isImage)
            val uploadDir = java.io.File(com.wy.openx.container.Workspace.root(appContext), "uploads")
            uploadDir.mkdirs()
            // 同名直接覆盖：图片按原始文件名保存，方便后续在文件浏览器里打开预览
            val targetFile = java.io.File(uploadDir, safeName)
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选文件")
                }
            }
            val containerPath = if (copied.isSuccess) {
                "/root/workspace/uploads/${targetFile.name}"
            } else {
                ""
            }

            uiState = uiState.copy(
                attachments = uiState.attachments.map { a ->
                    if (a.uri == uri && a.localPath.isEmpty()) {
                        a.copy(
                            textPreview = textPreview,
                            localPath = if (copied.isSuccess) targetFile.absolutePath else "",
                            containerPath = containerPath,
                            sizeBytes = if (copied.isSuccess) targetFile.length() else 0
                        )
                    } else a
                }
            )
            if (copied.isFailure) {
                FileLogger.w("Attachment", "保存附件失败: ${copied.exceptionOrNull()?.message}")
            }
        }
    }

    /** 从 contentResolver 查询显示名；查不到时按调用方给的名字 / MIME 兜底生成一个带扩展名的名字。 */
    private fun resolveDisplayName(uri: Uri, provided: String, mimeType: String): String {
        val fromProvider = runCatching {
            appContext.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
        if (!fromProvider.isNullOrBlank()) return fromProvider
        if (provided.isNotBlank()) return provided
        // 兜底：用时间戳 + 由 MIME 推出的扩展名
        val ext = when (mimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/heic", "image/heif" -> "heic"
            else -> if (mimeType.startsWith("image/")) mimeType.substringAfter('/') else "dat"
        }
        return "upload_${System.currentTimeMillis()}.$ext"
    }

    private fun sanitizeUploadName(name: String, mimeType: String, isImage: Boolean): String {
        val cleaned = name.map { ch -> if (ch.code < 32 || ch in "\\/:*?\"<>|") '_' else ch }
            .joinToString("").trim().trim('.')
        var result = cleaned.ifBlank { "upload" }.take(160)
        // 图片确保带正确的图片扩展名，避免文件浏览器按扩展名识别失败而无法预览
        if (isImage) {
            val ext = result.substringAfterLast('.', "").lowercase()
            if (ext !in IMAGE_EXTENSIONS) {
                val want = when (mimeType.lowercase()) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    "image/bmp" -> "bmp"
                    "image/heic", "image/heif" -> "heic"
                    else -> "jpg"
                }
                result = "${result.substringBeforeLast('.', result)}.$want"
            }
        }
        return result
    }

    /** 气泡附件（[com.wy.openx.data.model.MessageAttachment]）→ 发送用 [ChatAttachment]。 */
    private fun uiAttachmentOf(att: com.wy.openx.data.model.MessageAttachment): com.wy.openx.ui.state.ChatAttachment {
        val local = att.localPath.takeIf { it.isNotBlank() && java.io.File(it).isFile }
        val uri = when {
            local != null -> Uri.fromFile(java.io.File(local))
            att.containerPath.isNotBlank() -> Uri.parse("file://" + att.containerPath)
            else -> Uri.EMPTY
        }
        val preview = if (!att.isImage && local != null) {
            runCatching { java.io.File(local).readText().take(4000) }.getOrDefault("")
        } else ""
        return com.wy.openx.ui.state.ChatAttachment(
            uri = uri,
            fileName = att.fileName,
            mimeType = att.mimeType,
            isImage = att.isImage,
            localPath = att.localPath,
            containerPath = att.containerPath,
            textPreview = preview,
            sizeBytes = att.sizeBytes
        )
    }

    /**
     * 「加入输入栏」：把文件树里的文件 / 目录投进待发附件。
     *
     * 与 [addAttachment] 不同，文件本来就在工作区里，**不再复制一份到 uploads**——那是外部分享
     * 进来的文件的落点，工作区内的文件再拷一遍纯属白占空间。
     * 目录只作为单个条目投递，不递归展开：具体要读哪些交给 AI。
     */
    fun attachWorkspaceEntryToInput(containerPath: String, name: String) {
        if (containerPath.isBlank()) return
        if (uiState.attachments.size >= MAX_ATTACHMENTS) {
            showToast("最多添加 $MAX_ATTACHMENTS 个附件")
            return
        }
        val hostFile = com.wy.openx.tools.AppContextHolder.containerEngine
            ?.toHostFileIfContainerPath(containerPath)
        if (hostFile == null || !hostFile.exists()) {
            showToast("该位置不在工作区内，无法加入")
            return
        }
        val uri = Uri.fromFile(hostFile)
        if (uiState.attachments.any { it.uri == uri }) {
            showToast("已在输入栏中")
            return
        }
        viewModelScope.launch {
            val isDirectory = hostFile.isDirectory
            val mimeType = if (isDirectory) "" else appContext.contentResolver.getType(uri).orEmpty()
            val isImage = mimeType.startsWith("image/")
            // 目录不读预览（没有“内容”这回事）；图片读成文本只是一团乱码。
            val textPreview = if (isDirectory || isImage) {
                ""
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (hostFile.length() > ATTACHMENT_PREVIEW_MAX_BYTES) {
                            ""
                        } else {
                            com.wy.openx.data.util.FileContentReader.readText(appContext, uri)
                                .getOrElse { "" }
                                .take(4000)
                        }
                    }.getOrDefault("")
                }
            }
            uiState = uiState.copy(
                attachments = uiState.attachments + com.wy.openx.ui.state.ChatAttachment(
                    uri = uri,
                    fileName = name,
                    mimeType = mimeType,
                    isImage = isImage,
                    localPath = hostFile.absolutePath,
                    containerPath = containerPath,
                    textPreview = textPreview,
                    sizeBytes = if (isDirectory) 0 else hostFile.length()
                )
            )
        }
    }

    fun removeAttachment(uri: Uri) {
        uiState = uiState.copy(attachments = uiState.attachments.filterNot { it.uri == uri })
    }

    fun clearAttachments() {
        uiState = uiState.copy(attachments = emptyList())
    }

    fun applyApiPreset(preset: ApiPreset) {
        val current = uiState.settings
        val updated = current.copy(
            selectedApiPreset = preset.name, provider = preset.provider,
            baseUrl = preset.baseUrl.ifBlank { current.baseUrl },
            model = preset.model.ifBlank { current.model },
            apiKey = if (preset.apiKey.isNullOrBlank()) current.apiKey else preset.apiKey
        )
        uiState = uiState.copy(settings = updated)
        // 持久化，避免重启后预设切换丢失
        viewModelScope.launch { settingsStore.save(updated) }
        // 选中模型时自动获取其上下文窗口并保存到该预设（顶部统计/自动压缩立即可用）
        viewModelScope.launch {
            val info = ModelMetaFetcher.fetch(preset.provider, preset.model)
            if (info.inputTokens != null) {
                val list = uiState.apiPresets.toMutableList()
                val idx = list.indexOfFirst { it.name == preset.name }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(contextTokens = info.inputTokens, outputTokens = info.outputTokens)
                    uiState = uiState.copy(apiPresets = list)
                    settingsStore.saveApiPresets(list)
                }
            }
        }
    }

    fun savePreset(name: String, provider: String, baseUrl: String, model: String, apiKey: String = "") {
        val key = apiKey.ifBlank { uiState.settings.apiKey }
        // 覆盖保存时保留已有的上下文窗口设置，避免手动输入的下文参数失效
        val existing = uiState.apiPresets.firstOrNull { it.name == name }
        val preset = ApiPreset(name = name, provider = provider, baseUrl = baseUrl, model = model, apiKey = key, note = "", isCustom = true,
            contextTokens = existing?.contextTokens, outputTokens = existing?.outputTokens)
        val list = uiState.apiPresets.toMutableList()
        val idx = list.indexOfFirst { it.name == name }
        if (idx >= 0) list[idx] = preset else list.add(preset)
        uiState = uiState.copy(apiPresets = list)
        viewModelScope.launch { settingsStore.saveApiPresets(list) }
        showToast("配置「$name」已保存")
    }

    fun deletePreset(name: String) {
        val list = uiState.apiPresets.filter { it.name != name }
        uiState = uiState.copy(apiPresets = list)
        viewModelScope.launch { settingsStore.saveApiPresets(list) }
        showToast("配置「$name」已删除")
    }

    /** 更新预设的模型名与上下文窗口（模型设置弹窗），并持久化。 */
    fun updatePresetMeta(name: String, model: String, contextTokens: Long?, outputTokens: Long?) {
        val list = uiState.apiPresets.toMutableList()
        val idx = list.indexOfFirst { it.name == name }
        if (idx < 0) return
        val preset = list[idx]
        val newModel = model.trim().ifBlank { preset.model }
        list[idx] = preset.copy(model = newModel, contextTokens = contextTokens, outputTokens = outputTokens)
        uiState = uiState.copy(apiPresets = list)
        viewModelScope.launch { settingsStore.saveApiPresets(list) }
        if (uiState.settings.selectedApiPreset == name) {
            val updated = uiState.settings.copy(model = newModel)
            uiState = uiState.copy(settings = updated)
            viewModelScope.launch { settingsStore.save(updated) }
        }
        showToast("配置「$name」模型设置已保存")
    }

    /** 导入配置时整体替换 API 预设列表（保留既有的自定义预设与预置预设）。 */
    fun restoreApiPresets(presets: List<ApiPreset>) {
        if (presets.isEmpty()) return
        val existing = uiState.apiPresets.filter { it is ApiPreset && presets.none { p -> p.name == it.name } }
        val merged = existing + presets
        uiState = uiState.copy(apiPresets = merged)
        viewModelScope.launch { settingsStore.saveApiPresets(merged) }
        showToast("已导入 ${presets.size} 个 API 配置")
    }

    fun stopStreaming(
        closingConversationId: Long = uiState.currentConversationId,
        // 「立即发送队列」也会走 stopStreaming 打断当前生成，但那不是用户手动停止——
        // 打断后接着发排队消息，插入「用户手动停止」会误导；由调用方决定是否插。
        insertStopNotice: Boolean = true,
    ) {
        // 仅当本轮确实在生成时才视作「用户主动停止」。stopStreaming 也被删会话/清历史/
        // 重生成/回滚等路径调用，那些场景不应插入「用户手动停止」或按停止语义吞掉报错。
        // P2b：按传入的会话操作（不再隐含「就是前台」），否则后台会话被关闭时
        // 会把停流动作错施加到当前前台。
        val slot = slotOf(closingConversationId)
        val cid = closingConversationId
        val wasGenerating = slot.isSending || slot.streamJob?.isActive == true
        if (wasGenerating) slot.stopRequested = true
        slot.streamJob?.cancel()
        slot.deltaFlushJob?.cancel()
        slot.deltaFlushJob = null
        // P3-b：HTTP 句柄已按会话分槽，只取消本会话自己的请求（不再误取消别的会话）。
        chatRepository.cancelCurrentCall(closingConversationId)
        // 先把缓冲中的增量合并进消息（含最后不足150ms的内容），再落库
        flushDeltaToMessages(closingConversationId)
        slot.deltaAccumulator.clear()
        val partial = slot.messages.lastOrNull()
        if (partial?.role == MessageRole.ASSISTANT && partial.toolName == null && partial.content.isNotBlank()) {
            viewModelScope.launch { messageRepository.insert(cid, partial) }
        }
        // 停止前先落库已生成的部分内容，避免切会话/重启后内容丢失
        com.wy.openx.tools.AppContextHolder.completePendingQuestion(closingConversationId, "")
        slot.toolConfirmDeferred?.complete(false)
        slot.toolConfirmDeferred = null
        slot.terminalConfirmDeferred?.complete(false)
        slot.terminalConfirmDeferred = null
        // 取消后剩余未完成（无结果）的工具补发"已停止"结果，避免 UI 上状态点一直闪烁。
        // 单条消息模型下「未完成」= toolName != null 且 toolResult == null（无需配对查询）。
        val pendingTools = slot.messages.filter { it.role == MessageRole.ASSISTANT && it.toolName != null && it.toolResult == null }
        // 停止时原地替换未完成工具消息（同 id 覆盖，复用 onToolResult 的语义）——
        // 若直接追加同 id 副本，内存里会短时出现两条相同 id 的消息，groupMessages 的
        // tools_<id> 分组 key 也随之重复，LazyColumn 以重复 key 崩溃闪退。
        val cancelMsgs = if (pendingTools.isEmpty()) emptyList() else {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            pendingTools.map { tool ->
                ChatMessageModel(
                    tool.id, MessageRole.ASSISTANT, "构建助手", t,
                    "⏹ ${tool.toolName} 已停止",
                    toolCallId = tool.toolCallId, toolName = tool.toolName,
                    toolArguments = tool.toolArguments, toolResult = "已停止：${tool.toolName}"
                )
            }
        }
        val cancelById = cancelMsgs.associateBy { it.id }
        editSlot(closingConversationId) {
            isSending = false
            currentToolName = null
            reconnecting = false
            reconnectAttempt = 0
            reconnectMax = 0
            lastRequestError = null
            thinkingStartedAt = 0L
            pendingToolCall = null
            pendingTerminalConfirm = null
            pendingQuestion = null
            // 停止：丢弃未消费的展示态快照（下一次请求重新采集）。
            streamResetBaseMessages = null
            streamResetBaseAcc = ""
            messages = if (cancelById.isEmpty()) messages else messages.map { m -> cancelById[m.id] ?: m }
        }
        if (cancelMsgs.isNotEmpty()) {
            uiState = uiState.copy(logs = uiState.logs + "[${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}] 已停止 ${cancelMsgs.size} 个工具")
        }
        if (cancelMsgs.isNotEmpty()) {
            viewModelScope.launch { cancelMsgs.forEach { messageRepository.insert(cid, it) } }
        }
        // 用户主动停止：在对话流内插入一条系统消息（对齐「已切换至…」的内联样式），
        // 不再用顶部悬浮横幅。真实报错也走内联（见 sendMessage），故此处无需设 chatError。
        // 仅在「确实在生成中被停止」时插入，避免切会话/清历史等路径误插。
        if (wasGenerating && insertStopNotice) {
            val stopTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val stopMsg = ChatMessageModel(
                nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", stopTime, "用户手动停止"
            )
            editSlot(closingConversationId) { messages = messages + stopMsg }
            uiState = uiState.copy(logs = uiState.logs + "[$stopTime] 用户手动停止")
            viewModelScope.launch { messageRepository.insert(cid, stopMsg) }
        }
    }

    /** AI terminal 危险命令确认：弹确认框并挂起等待用户决定。 */
    private suspend fun requestTerminalConfirm(command: String, reason: String): Boolean {
        // P2b：确认框属于「发起本轮的会话」（工具可能在后台跑），不能写死前台。
        val cid = com.wy.openx.tools.AppContextHolder.currentConversationId ?: uiState.currentConversationId
        val slot = slotOf(cid)
        if (slot.terminalConfirmDeferred != null) return false
        val deferred = CompletableDeferred<Boolean>()
        slot.terminalConfirmDeferred = deferred
        editSlot(cid) {
            pendingTerminalConfirm = PendingToolCallInfo(
                toolName = "terminal",
                toolCallId = "terminal-safe",
                arguments = command,
                description = reason
            )
        }
        return deferred.await()
    }

    fun onRespondTerminalConfirm(approved: Boolean) {
        uiState = uiState.copy(pendingTerminalConfirm = null)
        terminalConfirmDeferred?.complete(approved)
        terminalConfirmDeferred = null
    }

    fun onConfirmToolCall(approved: Boolean) {
        uiState = uiState.copy(pendingToolCall = null)
        editSlot(uiState.currentConversationId) { pendingPermissionEval = null }
        toolConfirmDeferred?.complete(approved)
        toolConfirmDeferred = null
    }

    /**
     * 「始终允许」：放行本次调用，并把授权**记忆下来**。
     *
     * shell 类工具（terminal）记忆的是**指令前缀**（如 `git pull`），而非整个 `terminal`——
     * 否则记住一次 `terminal` 就等于永久放行 `rm -rf /`。前缀由 [ToolPermissionPolicyEngine]
     * 给出（子命令分发器记到子命令；递归/通配删除等不可记忆）。
     * 非 shell 工具沿用旧的整工具白名单 `toolAllowList`。
     */
    fun onAlwaysAllowTool(toolName: String) {
        val cid = uiState.currentConversationId
        val eval = slotOf(cid).pendingPermissionEval
        val cleanName = toolName.removePrefix("functions.").trim()

        if (ToolPermissionPolicyEngine.isShellTool(cleanName) && eval != null) {
            // 记指令前缀规则（rulesToRemember 只在可记忆时返回非空）
            val rules = eval.rememberablePatterns.map {
                com.wy.openx.tools.PermissionRule(cleanName, it, com.wy.openx.tools.PermissionDecision.ALLOW)
            }
            toolPermissionRuleStore.remember(rules)
        } else if (!uiState.settings.toolAllowList.contains(cleanName)) {
            // 非 shell（或无评估结果）：沿用整工具白名单
            val allow = uiState.settings.toolAllowList.toMutableList()
            allow.add(cleanName)
            val newSettings = uiState.settings.copy(toolAllowList = allow)
            uiState = uiState.copy(settings = newSettings)
            com.wy.openx.tools.AppContextHolder.currentSettings = newSettings
            viewModelScope.launch { settingsStore.save(newSettings) }
        }
        onConfirmToolCall(true)
    }

    fun submitAnswer(answer: String) {
        com.wy.openx.tools.AppContextHolder.completePendingQuestion(uiState.currentConversationId, answer)
        uiState = uiState.copy(pendingQuestion = null)
    }

    fun cancelQuestion() {
        com.wy.openx.tools.AppContextHolder.completePendingQuestion(uiState.currentConversationId, "")
        uiState = uiState.copy(pendingQuestion = null)
    }

    /**
     * 发送当前输入框内容；AI 正在生成时改为入队。
     *
     * @param queuedMsgId 非空表示「消费队列中这条已建好的排队气泡」：
     *   复用它的 id 与内容、解除 queued 标记，**不新建气泡、不碰输入框**。
     */
    /**
     * 发送一轮消息。
     *
     * @param queuedMsgId 非空表示「消费队列中这条已建气泡的消息」。
     * @param overrideText 非空表示**系统触发的合成轮**（如后台任务完成通知）：正文用它，不读
     *   输入框、也不建用户气泡（避免把系统通知伪装成用户发言）。
     * @param targetConversationId 非空表示**为指定会话**跑一轮（后台会话也能被唤醒）；
     *   为空则用当前前台会话。所有会话级读写都以它为准，不依赖前台投影。
     * @return 是否真正发起了这一轮（false = 被守卫拦下，如并发闸门已满 / 未选模型 / 空文本）。
     *   合成轮调用方据此决定通知是「已送达」还是「退回队列」。
     */
    fun sendMessage(
        queuedMsgId: Long? = null,
        overrideText: String? = null,
        targetConversationId: Long? = null
    ): Boolean {
        if (queuedMsgId == null && overrideText == null && uiState.isSending) {
            // AI 正在回复：消息**立即作为气泡入列并落库**，不是只存进内存队列。
            // 旧实现只往 messageQueue 里塞文本、等到真正发送才建气泡，于是
            // ①AI 干活期间聊天页完全看不到这条消息；②中途按停止时它从未被创建过，
            // 直接消失。
            //
            // 附件必须**跟排队消息一起搬走**：只存文本时，气泡上没有图片卡片，而输入区的
            // 附件又没被清空——AI 结束时这一轮把残留附件当「本轮附件」发出去，于是出现
            // 「AI 收到了图，聊天页却只有文字」。现在附件随消息入队并在消费时取回。
            val text = uiState.prompt.trim()
            val pendingAttachments = uiState.attachments
            // 只发图片不打字也算有效消息（与正常发送入口的规则一致）。
            if (text.isEmpty() && pendingAttachments.isEmpty()) return false
            val cid = uiState.currentConversationId
            val msgId = nextMessageId.incrementAndGet()
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val queuedMsg = ChatMessageModel(
                id = msgId, role = MessageRole.USER, author = "wy", time = time,
                content = text, queued = true,
                imageUri = pendingAttachments.firstOrNull { it.isImage }?.let { att ->
                    att.localPath.takeIf { it.isNotBlank() }?.let { java.io.File(it).toURI().toString() }
                        ?: att.uri.toString()
                },
                attachments = pendingAttachments.map { att ->
                    com.wy.openx.data.model.MessageAttachment(
                        fileName = att.fileName,
                        containerPath = att.containerPath,
                        localPath = att.localPath,
                        mimeType = att.mimeType,
                        sizeBytes = att.sizeBytes,
                        isImage = att.isImage
                    )
                }
            )
            val queue = uiState.messageQueue + msgId
            // 走 editSlot：队列是会话级状态，直接 copy(uiState) 会让后台会话的队列写进前台单槽。
            editSlot(cid) {
                messages = messages + queuedMsg
                messageQueue = queue
                // 完整附件（含原始 URI / 文本预览）另存一份：入队瞬间可能还没落盘完。
                if (pendingAttachments.isNotEmpty()) queuedAttachments[msgId] = pendingAttachments
                prompt = ""
                attachments = emptyList()
            }
            viewModelScope.launch {
                runCatching { messageRepository.insert(cid, queuedMsg) }
            }
            showToast("消息已排队（队列: ${queue.size}）")
            return false
        }
        // 发送前刷新 activeProvider 绑定（用户可能在设置里改了 provider / 切了模型）
        resolveActiveProvider()
        // 本轮**目标会话**：合成轮（后台任务完成通知）可指定后台会话，否则用当前前台。
        // 后续所有会话级读写都以它为准，绝不依赖「发送瞬间的前台投影」。
        val sendCid = targetConversationId ?: uiState.currentConversationId
        com.wy.openx.tools.AppContextHolder.currentConversationId = sendCid
        // 推送当前上下文窗口给仓库层：工具循环内「每轮 LLM 调用前的就地压缩」需要它做阈值判定
        // （仓库层读不到 UI 状态）。口径与顶部横条/自动压缩一致。
        //
        // 单槽里存**本会话**的窗口，不是全局 settings 解析出来的那个：
        // 会话级模式下各会话窗口差几倍，存全局值意味着「最后写入的会话决定所有人的阈值」。
        // 仓库层有 AppContextHolder.contextWindowProvider 钩子时可按会话精确取，
        // 这里的单槽只是钩子未注册等场景的兜底，同样不该是全局值。
        com.wy.openx.tools.AppContextHolder.contextWindowTokens =
            contextWindowTokensFor(sendCid)
        // 文本来源三选一：队列气泡 > 系统合成文本 > 输入框。
        // 队列消费路径不经 prompt 中转（旧实现把队首写进 uiState.prompt 会覆盖用户正在输入的字，
        // 且气泡已建，再走一遍建气泡逻辑会重复插一条）。
        val text = when {
            queuedMsgId != null ->
                slotOf(sendCid).messages.firstOrNull { it.id == queuedMsgId }?.content?.trim().orEmpty()
            overrideText != null -> overrideText.trim()
            else -> uiState.prompt.trim()
        }.let { raw ->
            // 七期：插件可以在消息真正发出前改写它（链式，按注册顺序）。
            // 合成轮（overrideText）跳过：那是后台通知/压缩任务的内部文本，
            // 让插件掺和进去只会把内部流程弄脏。
            if (overrideText != null || raw.isEmpty()) raw
            else runCatching { com.wy.openx.plugin.PluginHookManager.beforeSend(raw) }
                .getOrDefault(raw)
        }
        if (queuedMsgId != null && text.isEmpty()) {
            // 排队气泡内容已被清空/删除：丢掉队列指针，别让整条队列卡住。
            editSlot(sendCid) {
                messageQueue = messageQueue.filterNot { it == queuedMsgId }
            }
            return false
        }
        // 允许「只发图片/附件不打字」：输入为空但有待发送附件时仍然发送。
        // 合成轮没有附件，空文本直接丢弃（不该为一条空通知起一轮）。
        if (overrideText != null) {
            if (text.isEmpty()) return false
        } else if (text.isEmpty() && uiState.attachments.isEmpty()) return false

        // 【新会话默认不选模型】会话级模式下，用户还没给本会话选模型就直接拦下。
        //
        // 不能放行：effectiveProviderFor 会因 override 为空返回 null，
        // 仓库层随即回落到**全局当前提供商**——那正是「上一个会话最后选的模型」，
        // 于是新会话在用户毫不知情的情况下拿别人的模型把消息发了出去。
        // 顶栏已显示 [未选择模型]，这里给出对应的可操作提示。
        if (!uiState.settings.modelGlobalMode) {
            if (sendCid > 0L && effectiveProviderFor(sendCid) == null) {
                // 合成轮（后台通知）静默跳过：用户没在等这条，弹「请先选模型」只会莫名打扰。
                if (overrideText == null) showToast("请先为本会话选择模型")
                return false
            }
        }

        slotOf(sendCid).stopRequested = false

        // 斜杠命令 / `!shell` 只对用户输入有意义；合成轮（后台通知）不走这两条，
        // 否则通知正文若以 `/` 开头会被误当命令执行。
        if (overrideText == null) {
            if (text.startsWith("! ") || text.startsWith("!")) {
                val shellCmd = text.removePrefix("!").trim()
                if (shellCmd.isNotEmpty()) {
                    uiState = uiState.copy(prompt = "")
                    executeShellCommand(shellCmd)
                    return false
                }
            }
            if (text.startsWith("/")) {
                handleSlashCommand(text)
                return false
            }
        }

        if (uiState.settings.hapticFeedback) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(30)
                }
            } catch (_: Exception) {}
        }

        // 撤销栈按**目标会话**的槽位操作（不是前台别名）：后台会话被唤醒时也不能串台。
        slotOf(sendCid).undoStack.add(slotOf(sendCid).messages.toList())
        slotOf(sendCid).let { s -> while (s.undoStack.size > 25) s.undoStack.removeAt(0) }
        slotOf(sendCid).redoStack.clear()

        // 并发闸门：先占坑再开跑，协程 finally 里必还（含被 cancel 的路径）。
        // 拒绝而非静默排队：排队会让用户以为「发了但没反应」，而限流是资源约束，应该明确告知。
        if (!concurrencyGate.tryAcquire()) {
            // 合成轮（后台通知）不弹 Toast：用户没在等，弹了只会莫名。调用方会把它退回队列。
            if (overrideText == null) showToast("已有 $MAX_CONCURRENT_SESSIONS 个会话在运行，请等待完成或先停止一个")
            return false
        }
        activeRequests++
        // 新轮抢占：递增代次，让上一轮（若还被阻塞工具卡着、finally 迟到）变成「过期轮」。
        // 见 SessionSlot.runGeneration 注释；与 finally 里的代次守卫配套。
        slotOf(sendCid).runGeneration++

        streamJob = viewModelScope.launch(AppContextHolder.ConversationId(sendCid)) {
            // 本轮请求归属的会话：在**发送入口**就已锁定（sendCid = 指定会话或当时前台），
            // 不能等到 finally 再取 `uiState.currentConversationId` —— 那时用户可能已切到别的会话，
            // finally 里的工具补发、状态复位就会写错会话（多会话并行的典型串台点）。
            val requestConversationId = sendCid
            /**
             * 本轮请求的槽位引用——协程体内所有会话级读写都走它，不再走 `uiState`/VM 委托字段。
             *
             * 为什么必须这样：VM 那些委托属性（`_deltaAccumulator`/`stopRequested`/
             * `lastUsageMarkIndex` …）的 getter 是 `slot()` = **当下前台**；用户切走后它们会指向
             * 另一个会话的槽。只有抓住 `rq` 才能保证 A 的后台协程自始至终只碰 A。
             */
            val rq = slotOf(requestConversationId)
            // 本轮代次：finally 收尾时若 slot 代次已被新轮抬走，则本轮的收尾全部作废
            // （立即发送打断长工具时，阻塞工具结束后才跑到的 finally 不能复位新轮的
            // isSending / 触发自动压缩 / 抢出队）。
            val myGen = rq.runGeneration
            try {
            // 合成轮（后台任务完成通知）不解析 @文件引用、不带附件：文本本身就是完整指令。
            val isSynthetic = overrideText != null
            val resolved = if (isSynthetic) text else resolveFileRefs(text)
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            // 队列消息的附件在入队时已从输入区搬走：优先用槽位里保存的完整附件，
            // 没有再按气泡上的附件重建。**不能**直接读 rq.attachments——那是输入区的残留，
            // 旧写法正是靠它让排队消息的图片在气泡上消失。
            val queuedAttachments = if (queuedMsgId != null) rq.queuedAttachments.remove(queuedMsgId) else null
            val currentAttachments = when {
                isSynthetic -> emptyList()
                queuedAttachments != null -> queuedAttachments
                queuedMsgId != null -> withContext(Dispatchers.IO) {
                    rq.messages.firstOrNull { it.id == queuedMsgId }?.attachments.orEmpty()
                        .map { uiAttachmentOf(it) }
                }
                else -> rq.attachments
            }
            val fileSuffix = buildString {
                val files = currentAttachments.filterNot { it.isImage }
                if (files.isNotEmpty()) {
                    append("\n\n[已附加文件]")
                    files.forEach { a ->
                        append("\n- "); append(a.fileName)
                        if (a.containerPath.isNotBlank()) append("（容器内: ").append(a.containerPath).append("）")
                    }
                }
                val previews = files.filter { it.textPreview.isNotBlank() }
                if (previews.isNotEmpty()) {
                    append("\n\n[文件内容预览]")
                    previews.forEach { append("\n--- ").append(it.fileName).append(" ---\n").append(it.textPreview) }
                }
            }
            val images = currentAttachments.filter { it.isImage }
            val imageBase64: List<String> = if (images.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    images.mapNotNull { attachment ->
                        // 优先本地副本（file:// 单次可靠），本地尚未落盘时退回原始 content URI
                        val local = attachment.localPath.takeIf { it.isNotBlank() && java.io.File(it).isFile }
                        val src = if (local != null) Uri.fromFile(java.io.File(local)) else attachment.uri
                        encodeImageToDataUrl(src)
                    }
                }
            } else emptyList()
            // 图片不写占位文字：多模态请求已按 image_url parts 单独传图，气泡下方渲染 
            val finalUserText = resolved + fileSuffix
            // 合成轮不建用户气泡：正文作为 system 消息进请求（见下方 syntheticMsg），
            // 否则会把「[后台任务完成通知]…」伪装成用户发言，聊天页与历史都被污染。
            val userMessage: ChatMessageModel? = if (isSynthetic) null else ChatMessageModel(
                id = nextMessageId.incrementAndGet(), role = MessageRole.USER,
                author = "wy", time = time, content = finalUserText,
                // 优先记录已复制到工作区的文件路径（可长期读取）；退回到原始 content URI
                imageUri = images.firstOrNull()?.let { att ->
                    att.localPath.takeIf { it.isNotBlank() }?.let { java.io.File(it).toURI().toString() }
                        ?: att.uri.toString()
                },
                attachments = currentAttachments.map { att ->
                    com.wy.openx.data.model.MessageAttachment(
                        fileName = att.fileName,
                        containerPath = att.containerPath,
                        localPath = att.localPath,
                        mimeType = att.mimeType,
                        sizeBytes = att.sizeBytes,
                        isImage = att.isImage
                    )
                }
            )
            // 合成轮的正文（进请求用），与用户气泡无关。
            var syntheticText: String? = null
            // 本轮用户输入对应的消息 id。buildTurnHistory 用它精确排除「当前发言」，
            // 不能再依赖「最后一条就是本轮输入」——压缩提交后展示序末尾是摘要。
            var currentTurnMsgId: Long? = null
            editSlot(requestConversationId) {
                when {
                    // 队列消费：气泡已在入队时建好并落库，这里只解除 queued 标记（原地替换 id 不变），
                    // **不能**再 messages + userMessage，否则同一条消息会出现两个气泡。
                    queuedMsgId != null -> {
                        currentTurnMsgId = queuedMsgId
                        messages = messages.map { if (it.id == queuedMsgId) it.copy(queued = false) else it }
                    }
                    isSynthetic -> {
                        // 合成轮不建气泡，但把正文也追加进历史（user 角色，UI 提示类 system 会被过滤），
                        // 使后续轮次能看到「这条通知存在过」。不落库：纯运行时上下文，重启后自然消失。
                        val syntheticMsg = ChatMessageModel(
                            id = nextMessageId.incrementAndGet(), role = MessageRole.SYSTEM,
                            author = "系统", time = time, content = finalUserText
                        )
                        syntheticText = finalUserText
                        currentTurnMsgId = syntheticMsg.id
                        messages = messages + syntheticMsg
                    }
                    else -> {
                        currentTurnMsgId = userMessage!!.id
                        messages = messages + userMessage!!
                        prompt = ""
                    }
                }
                attachments = emptyList()
            }
            uiState = uiState.copy(logs = uiState.logs + "[$time] 用户提交新任务：$text")
            // 本轮快照绑定到该用户消息（检查点与撤销按消息回滚）。合成轮没有用户消息，
            // 用 0 表示「不绑定具体消息」（CheckpointManager 会为它开独立桶）。
            CheckpointManager.beginTurn(userMessage?.id ?: 0L)
            val conversationId = requestConversationId
            when {
                // 复用气泡：按 id REPLACE 覆盖掉落库时 queued=true 的那一行。
                queuedMsgId != null -> {
                    val existing = slotOf(conversationId).messages.firstOrNull { it.id == queuedMsgId }
                    if (existing != null) messageRepository.insert(conversationId, existing)
                }
                isSynthetic -> { /* 合成轮不落库 */ }
                else -> messageRepository.insert(conversationId, userMessage!!)
            }
            // 最近聊天上浮：刷新该会话活跃时间，使其在「会话历史」中升到前面
            // （置顶会话不受影响，仍常驻最前）。乐观更新本地列表，避免等 Room 回流造成跳变。
            val now = System.currentTimeMillis()
            uiState = uiState.copy(
                conversations = uiState.conversations.map {
                    if (it.id == conversationId) it.copy(lastActiveAt = now) else it
                }
            )
            conversationRepository.touchLastActive(conversationId, now)
            // 先亮「思考中」再干活：isSending/thinkingStartedAt 必须在任何网络等待之前置位，
            // 否则首 token（或标题请求）到达前页面完全无反馈，用户以为卡死。
            // 新发送即「下次操作」：清掉上一次的失败错误卡片。
            editSlot(requestConversationId) {
                isSending = true
                thinkingStartedAt = System.currentTimeMillis()
                lastRequestError = null
            }

            // 标题生成**异步隔离**：内部是完整网络往返（实测可达 30s+），绝不能 inline 阻塞主发送
            // （此前 inline await 导致：主请求要等标题返回才发出，期间 isSending 还没置位 →
            //  新建/前两轮会话「发完消息半天没反应」）。取消/异常在子协程内吞掉，不影响主流程。
            viewModelScope.launch {
                try {
                    // 传入本轮用户气泡：Room 回流可能在标题协程读槽位前把内存覆盖成「只剩系统消息」
                    // （切模式后立刻发言就是这种时序），没有它就判定成「0 轮用户发言」直接放弃。
                    maybeGenerateTitle(requestConversationId, userMessage)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 取消/异常同样要复位定稿标志，否则这次会话永远停在占位标题（不再重试）。
                    slotOf(requestConversationId).titleFinalized = false
                    uiState = uiState.copy(logs = uiState.logs + "标题生成被取消，保留截取标题")
                } catch (e: Exception) {
                    slotOf(requestConversationId).titleFinalized = false
                    uiState = uiState.copy(logs = uiState.logs + "标题生成异常：${e.message ?: e.javaClass.simpleName}")
                }
            }

            // 自动压缩：上下文超阈值时先压缩再发送（压缩期间用户消息保留在最近消息中）
            maybeAutoCompact(requestConversationId)

            val toolsEnabled = uiState.settings.autoToolCall && rq.agentMode != "chat"
            val toolList = if (toolsEnabled) {
                toolRegistry.getApiTools(
                    enableWebSearch = true,
                    enableFileTools = true,
                    enableMultiAgent = true,
                    // 权限档位统一接管工具下发（构建/股票/规划均适用；聊天模式上面已整体禁用）：
                    // readonly 档不下发写/执行类；非股票模式仍去掉量化类，降低每轮固定 token 开销。
                    agentMode = rq.agentMode,
                    permissionMode = effectivePermissionMode(requestConversationId)
                ).ifEmpty { null }
            } else null
            // ② 记录本轮真正下发的工具定义体量，供上下文预算扣除（见 [toolSchemaTokens]）。
            // 用实际 toolList 而不是重新按参数调一次 getApiTools：那份参数一旦与上面漂移，
            // 预算就会算在一个并不存在的 schema 上。
            rq.currentToolSchemaTokens = toolList?.let { estimateToolDefTokens(it) } ?: 0
            val planPrompt = "你现在是项目规划师。请分析用户的需求，只做分析和规划：\n1. 理解需求和目标\n2. 分析可行性和技术方案\n3. 拆解任务步骤和依赖关系\n4. 给出推荐的实施顺序\n不要写代码实现。"
            // 本轮历史消息组装。抽成局部函数的原因（④）：「超长 → 强制压缩 → 重试同一轮」必须在压缩**之后**
            // 重新读一遍 uiState.messages 重建请求；拿缓存下来的旧 history 重试等于没压。
            fun buildTurnHistory(): List<com.wy.openx.data.remote.ChatMessageDto> = buildList {
                // 【口径对齐】是否保留思考，按**本会话生效模型**判定——与仓库层工具循环内的
                // projectForSend 用同一判据（ChatUtils.modelKeepsThinking）。两处必须一致，否则
                // 同一段历史在「循环内」与「回合起点」发出去的内容不同，上下文数字会跳变。
                val keepThinkingForModel = com.wy.openx.data.util.ChatUtils.modelKeepsThinking(
                    settingsFor(requestConversationId).model
                )
                when (rq.agentMode) {
                    "plan" -> add(ChatMessageDto("system", com.google.gson.JsonPrimitive(planPrompt)))
                    "stock" -> add(ChatMessageDto("system", com.google.gson.JsonPrimitive("你现在是股票分析专家。精通技术分析（K线、均线、MACD、RSI、布林带）和基本面分析（PE/PB、EPS、财报、行业趋势）。请给出专业的投资建议和风险提示，不构成投资建议。")))
                    "chat" -> add(ChatMessageDto("system", com.google.gson.JsonPrimitive("你正处于聊天模式：纯对话交流，不调用任何工具、不执行终端命令、不读写或修改文件，只回答问题、提供解释和讨论。")))
                    else -> add(ChatMessageDto("system", com.google.gson.JsonPrimitive("你正处于构建模式：可以读写文件、执行终端命令、调用各类工具；请实际完成开发、构建、调试与运维等任务，而不是只给出方案或描述。")))
                }
                // 记忆分层规则：告诉 AI 保存时该选哪个 scope。
                // 必须**无条件**注入（即使当前两层记忆都为空）——否则 AI 只会用默认的 global，
                // 项目记忆永远不会被 AI 写入，等于只有手动能维护。
                // 与任务工具提示一致，gate 在 toolsEnabled 上（聊天模式无工具，写规则纯属噪音）。
                if (toolsEnabled) {
                    add(ChatMessageDto("system", com.google.gson.JsonPrimitive(
                        "工具结果纪律：工具（browser/terminal/file 等）返回的内容是过程数据，仅供你分析。" +
                        "你的回复正文不要原样复制、转述或重复粘贴工具返回的完整 JSON/原始输出流水。" +
                        "给出结论、摘要、关键信息与原理解释即可；确需引用时只抽取必要片段，不要整段贴出。" +
                        "除非用户明确要求看到原始输出，否则一律以精简摘要呈现。\n" +
                        "浏览器 evaluate 脚本纪律：写 JS 探查页面时，凡可能不存在的元素必须先判空再用——" +
                        "例如「var el=document.querySelector(...); if(!el) return JSON.stringify({matched:false,msg:\\\"元素不存在\\\",selector:那串选择器});」→ 再读 el.getBoundingClientRect()/textContent 等属性。" +
                        "不要对 null 直接调用属性；出现「Cannot read properties of null (reading ...)」时，先列举页面实际存在的关键节点再重写脚本。" +
                        "evaluate 只返回精简 JSON，不要直接操纵业务数据。\n" +
                        "写文件纪律（大文件必须分块）：writeFile 的 content 是**模型输出**的一部分，单次输出 token 有限。" +
                        "写超过约 200 行 / 8KB 的文件时，一次性 writeFile 会在中途被截断 → 参数残缺、写入失败。" +
                        "正确做法：先用 writeFile 写开头（约 100~200 行），其余分多次用 appendFile(path, content) 追加，" +
                        "每次同样控制在约 100~200 行，直到写完。整体覆盖用 writeFile，局部修改用 editFile。" +
                        "收到「参数被截断」的报错时不要原样重试，按上述方式拆小分块。"))
                    )
                    val memoryProject = uiState.settings.currentProject.trim()
                    val memoryRule = buildString {
                        append("记忆分两层，用 memory_save 的 scope 参数选择保存位置：\n")
                        append("- scope=global（长期记忆）：跨项目通用的内容——用户偏好、个人习惯、通用技术约定、与具体项目无关的事实。\n")
                        append("- scope=project（项目记忆）：只与当前项目相关的内容——本项目架构与约定、本项目的待办、本项目的坑与决策记录。\n")
                        if (memoryProject.isNotEmpty()) {
                            append("当前项目为「$memoryProject」，凡属于本项目的信息请用 scope=project 保存；")
                            append("判断不准时优先想「换一个项目这条还成立吗」——成立就用 global，不成立就用 project。")
                        } else {
                            append("当前未选择项目，无法保存项目记忆，此时一律使用 scope=global。")
                        }
                    }
                    add(ChatMessageDto("system", com.google.gson.JsonPrimitive(memoryRule)))
                }
                // 内置 AI 规则（memory/agent/ 下的文档）：**每次都完整注入**，不设条数上限。
                // 与「长期记忆只按条数上限取最新若干条」不同——这些是随 APK 发布的行为准则（编码纪律、安全边界等），
                // 属于硬规则，漏一条就可能违背。体积可控（当前 ~13KB），故全量注入。
                // 放在记忆之前：让「怎么做事」的准则先于「记住了什么」进入上下文。
                // 开关在**这里**过滤（设置 → 提示词）：总开关关掉整段不注入；单条停用按 key 剔除。
                // 设置页列表仍展示全部，所以开关状态不回写 uiState.agentRuleItems。
                val agentSettings = uiState.settings
                val agentRules = if (!agentSettings.agentRulesEnabled) emptyList()
                    else uiState.agentRuleItems.filter { it.key !in agentSettings.agentRuleDisabled }
                if (agentRules.isNotEmpty()) {
                    val ruleText = agentRules.joinToString("\n\n") { "### ${it.key}\n${it.body}" }
                    add(ChatMessageDto("system", com.google.gson.JsonPrimitive(
                        "以下是必须遵守的 AI 行为规则（随应用内置，优先级高于通用默认行为）：\n\n$ruleText"
                    )))
                }
                // 长期记忆（全局，跨项目）——取**最新** [MEMORY_GLOBAL_LIMIT] 条（load() 按时间倒序，故用 take 而非 takeLast）。
                //
                // 只注入**索引**（名称 + 短摘要）：原先每轮把 5 条全文背在请求里，一条几百字的
                // 项目笔记就能白烧上千 token。现在超过 [MEMORY_INLINE_MAX] 的截断并标明
                // 「细节用 memory_read 查」——与技能清单同一策略：按需取正文。
                val memories = uiState.memoryItems
                if (memories.isNotEmpty()) {
                    val recentMemories = memories.take(MEMORY_GLOBAL_LIMIT)
                    val memoryText = recentMemories.joinToString("\n") { memoryIndexLine(it) }
                    add(ChatMessageDto("system", com.google.gson.JsonPrimitive(
                        "以下是长期记忆（跨项目通用，仅索引）：\n$memoryText\n\n" +
                            "带「已截断」的条目需要完整内容时，调用 `memory_read`（key = 条目名称）取回。"
                    )))
                }
                // 项目记忆（按当前项目隔离）
                val projectMemories = uiState.projectMemoryItems
                if (projectMemories.isNotEmpty()) {
                    val projectName = uiState.settings.currentProject.trim()
                    val projectMemoryText = projectMemories.take(MEMORY_PROJECT_LIMIT).joinToString("\n") { memoryIndexLine(it) }
                    add(ChatMessageDto("system", com.google.gson.JsonPrimitive(
                        "以下是当前项目「$projectName」的项目记忆（仅索引）：\n$projectMemoryText\n\n" +
                            "带「已截断」的条目需要完整内容时，调用 `memory_read`（key = 条目名称）取回。"
                    )))
                }
                // 知识库：只注入**目录**（标题 + 标签），正文按需用 `knowledge_search` 查。
                // 与记忆的分工：记忆是硬约束与偏好（每轮全量带清单），知识是经验细节（不常驻）。
                val knowledgeIndex = uiState.knowledgeItems.take(KNOWLEDGE_INDEX_LIMIT)
                if (knowledgeIndex.isNotEmpty()) {
                    add(ChatMessageDto("system", com.google.gson.JsonPrimitive(
                        "以下是知识库目录（从历史对话里提炼的经验，仅标题与标签）：\n" +
                            knowledgeIndex.joinToString("\n") { k ->
                                "- ${k.title}" + if (k.tags.isEmpty()) "" else "（${k.tags.joinToString("、")}）"
                            } +
                            "\n\n遇到报错、要动手改代码、或需要回忆「以前是怎么解决的」时，用 `knowledge_search` 查正文。" +
                            "与 memory_read 的分工：memory 是硬约束与偏好，knowledge 是经验细节。"
                    )))
                }
                // 技能：系统提示里只列「名称 + 用途」清单，**正文按需用 `skill_load` 取**。
                // 原先这里注入的是完整 instructions，技能正文一长（几百上千字）每轮请求都要
                // 背着走，而这些正文多数轮次根本用不到。改成清单后，token 占用只与技能**条数**
                // 相关，与正文长度无关。
                //
                // 代价：模型多一次 `skill_load` 调用。所以描述里必须写明「相关时先加载」，
                // 且要让它感知到「清单只有摘要，正文得取」——否则模型可能以为正文就是全部、
                // 或凭记忆臆造技能内容。
                //
                // 作用域：全局技能 + 当前项目技能一并列出（项目优先覆盖同名，与存储合并语义一致）。
                val enabledSkills =
                    (uiState.projectSkills + uiState.skills).distinctBy { it.name.lowercase(Locale.ROOT) }
                        .filter { it.enabled && it.instructions.isNotBlank() }
                if (enabledSkills.isNotEmpty()) {
                    val skillList = enabledSkills.joinToString("\n") {
                        val scopeTag = if (it.scope == com.wy.openx.data.store.SCOPE_PROJECT) "（项目技能）" else ""
                        "- **${it.name}**$scopeTag: ${it.description}"
                    }
                    add(ChatMessageDto("system", com.google.gson.JsonPrimitive(
                        "可用技能（仅列出名称与用途）：\n$skillList\n\n" +
                            "某个技能与当前任务相关时，先调用 `skill_load` 取回它的完整正文，" +
                            "再严格按正文行事。正文不在此处，不要凭记忆臆造技能内容。"
                    )))
                }
                // 项目/全局规则文件（AGENTS.md / CLAUDE.md）：真正读进上下文，
                // 而不是只在 10-project-rules.md 里嘴上要求模型遵守。
                // 优先级：用户记忆 > 项目规则 > 内置规则。
                val projectRuleDocs = loadProjectRuleDocs()
                if (projectRuleDocs.isNotEmpty()) {
                    val ruleBlock = projectRuleDocs.joinToString("\n\n") { doc ->
                        "【${doc.label}规则 · ${doc.fileName}】\n${doc.text}"
                    }
                    add(ChatMessageDto("system", com.google.gson.JsonPrimitive(
                        "以下是当前生效的项目规则文件（优先级：用户记忆 > 项目规则 > 内置规则）：\n\n$ruleBlock"
                    )))
                }
                val activeProject = uiState.settings.currentProject.trim()
                val external = uiState.settings.externalWorkspaces.firstOrNull { it.name == activeProject }
                val workspaceNote = when {
                    external != null -> {
                        "当前工作区为本地目录，容器内路径为 /root/local/${external.name}（宿主路径 ${external.path}）。" +
                            "终端与文件类工具（读写/编辑/文件管理）统一使用容器路径 /root/local/${external.name}/... 。请在此目录内工作。" +
                            workflowOutputNote("/root/local/${external.name}")
                    }
                    activeProject.isNotEmpty() -> {
                        "当前项目工作区目录（容器内路径）：/root/workspace/projects/$activeProject。" +
                            "终端与文件类工具（读写/编辑/文件管理）统一使用此容器路径，请在此目录内进行文件读写与开发任务；如有其他项目请先切换项目工作区再工作。" +
                            workflowOutputNote("/root/workspace/projects/$activeProject")
                    }
                    else -> {
                        "当前未选择项目，工作区为容器内默认目录 /root/workspace（新建项目的目录在 /root/workspace/projects/<名称> 下）。终端与文件类工具统一使用容器路径 /root/workspace/... ，请在此目录下工作。" + workflowOutputNote(com.wy.openx.container.Workspace.CONTAINER_PATH)
                    }
                }
                add(ChatMessageDto("system", com.google.gson.JsonPrimitive(workspaceNote)))
                if (toolsEnabled) {
                    add(ChatMessageDto("system", com.google.gson.JsonPrimitive(
                        "你有任务面板工具 todo 可用：\n" +
                        "- todo(items): 用一份**完整**的待办清单替换当前面板。\n" +
                        "  · 只需提交全部条目，无需 id 或逐条操作：新增就往数组加、删除就不写它、改状态就改它的 status。\n" +
                        "  · **数组顺序即面板显示顺序**，请按实际执行次序排列。\n" +
                        "  · 每项为 {description, status}，status ∈ pending / in_progress / completed / cancelled（默认 pending）。\n" +
                        "  · 传空数组表示清空清单。\n" +
                        "- 多步骤任务建议：开工前先用 todo 列出计划，每完成一步就提交更新后的完整清单。"))
                    )
                }
                val msgsForHistory = hoistCompactionArtifacts(
                    historyWithoutCurrentTurn(rq.messages, currentTurnMsgId)
                )
                // 工具消息已改为「单条就地更新」：一条消息同时含 toolName（调用）与 toolResult（结果）。
                // 发给模型时需拆回协议要求的两种消息——先 assistant(tool_calls)，再逐个 tool(result)。
                var mi = 0
                while (mi < msgsForHistory.size) {
                    val msg = msgsForHistory[mi]
                    when {
                        msg.role == com.wy.openx.data.model.MessageRole.ASSISTANT && msg.toolName != null -> {
                            // 收集连续的工具消息组
                            val group = mutableListOf<ChatMessageModel>()
                            while (mi < msgsForHistory.size &&
                                msgsForHistory[mi].role == com.wy.openx.data.model.MessageRole.ASSISTANT &&
                                msgsForHistory[mi].toolName != null
                            ) {
                                group.add(msgsForHistory[mi])
                                mi++
                            }
                            // 只发「已完成」的工具（执行中被中断的占位消息没有有效结果，不发）
                            val done = group.filter { it.toolResult != null }
                            if (done.isNotEmpty()) {
                                val tcList = done.map { m ->
                                    ToolCall(id = m.toolCallId ?: "call_${m.id}", function = ToolCallFunction(name = m.toolName ?: "unknown", arguments = m.toolArguments ?: "{}"))
                                }
                                // srcMsgId：这条 assistant(tool_calls) 由本组**第一条**工具消息展开而来，
                                // 故以其 id 作为整组的来源标识（就地压缩回折叠边界时用）。
                                add(ChatMessageDto("assistant", com.google.gson.JsonNull.INSTANCE, toolCalls = tcList, srcMsgId = done.first().id))
                                done.forEach { m ->
                                    add(ChatMessageDto("tool", JsonPrimitive(m.toolResult ?: ""), toolCallId = m.toolCallId, srcMsgId = m.id))
                                }
                            }
                        }
                        else -> {
                            if (msg.role == MessageRole.SYSTEM) {
                                // UI 提示类 system 消息（帮助/模式切换等）不发给 API；
                                // 仅保留对话摘要与 shell 输出，并转为 user 角色保证协议兼容
                                val keep = msg.content.startsWith(SUMMARY_PREFIX) || msg.content.contains("```")
                                if (keep && msg.content.isNotBlank()) {
                                    add(ChatMessageDto("user", JsonPrimitive(msg.content), srcMsgId = msg.id))
                                }
                            } else if (msg.role == MessageRole.ASSISTANT) {
                                // 发送给模型的历史消息剥离 thinking 标签，避免残留的 <thinking> 导致部分模型（如 MiniMax M3）HTTP 400。
                                // 但「需要回传思考」的模型（DeepSeek 思考模式等）保留，否则其工具轮回传会 400
                                // （见 ChatUtils.modelKeepsThinking；与仓库层 projectForSend 同判据）。
                                //
                                // 库里的思考是**内联**在 content 的 `<thinking>` 里，而仓库层工具循环发的是
                                // **独立字段 `reasoning_content`**。两者必须产出同形态，否则同一段历史在
                                // 「循环内」与「回合起点」发出去的内容不同、上下文数字仍会跳变（实测差 4K）。
                                val (body, reasoning) = if (keepThinkingForModel) {
                                    ChatUtils.extractThinkingContent(msg.content)
                                } else {
                                    ChatUtils.removeThinkingContent(msg.content) to null
                                }
                                val clean = body.takeIf { it.isNotBlank() }
                                // 跳过请求失败/出错的报错文本消息，避免污染模型上下文导致持续 400（覆盖历史残留与旧污染）
                                if (clean != null && !clean.startsWith("请求失败") && !clean.startsWith("请求出错")) {
                                    add(ChatMessageDto(
                                        role = "assistant",
                                        content = JsonPrimitive(clean),
                                        reasoningContent = reasoning?.takeIf { it.isNotBlank() },
                                        srcMsgId = msg.id
                                    ))
                                }
                            } else {
                                // 【压缩锚点不进请求】marker 是给 UI 定位压缩卡位置的内部锚点，
                                // 不是对话内容，原样发给模型只会浪费 token 并让模型把
                                // 「[上下文已压缩]」当成一句真实用户发言
                                // marker 仅用于排序/切分，摘要才是唯一进入上下文的产物
                                if (msg.content == COMPACTION_MARKER) {
                                    mi++
                                    continue
                                }
                                val textContent = msg.content.takeIf { it.isNotBlank() }
                                add(ChatMessageDto(role = msg.role.name.lowercase(), content = textContent?.let { JsonPrimitive(it) }, srcMsgId = msg.id))
                            }
                            mi++
                        }
                    }
                }
            }

            var responseTokens: Int? = null
            var turnRequests = 0
            var turnCacheTokens = 0
            // 本回合（含工具循环各轮 LLM 调用）累计的真实输入/输出 token。
            // accumulatedInput/accumulatedOutput 用于 llm_call_records 埋点与全局统计（全程累计消耗）；
            // 消息落库改回「最后一轮」单值：inputTokens=最后请求的 prompt_tokens，
            // tokens=最后请求的 completion_tokens，cachedInputTokens=最后命中值。
            var accumulatedInput = 0
            var accumulatedOutput = 0
            var lastCompletionTokens: Int? = null
            var lastCachedTokens = 0
            val requestStartTime = System.currentTimeMillis()
            // 本轮实际用的提供商/模型（会话级模式下来自本会话 override）。
            // 统计埋点与缓存命中归属都用它，不能用 uiState.settings（那是前台槽投影）。
            val callProviderCfg = effectiveProviderFor(requestConversationId)
            val callModelName = callProviderCfg?.effectiveModel?.takeIf { it.isNotBlank() }
                ?: settingsFor(requestConversationId).model

            // 子代理进度出口：本轮工具执行期间有效，finally 里摘除（换会话/停止都不会残留）
            com.wy.openx.tools.AppContextHolder.subAgentProgressSink = { line -> appendSubAgentProgress(line, requestConversationId) }
            val agentToolApprover = buildToolApprover(requestConversationId)
            com.wy.openx.tools.AppContextHolder.subAgentToolApprover = agentToolApprover
            // buildToolApprover() 只覆盖 confirm / workspace 两档，其余档返回 null。
            // 那种情况下子代理的写/执行类工具应当放行（父级本来就不逐条问），
            // 用显式标志区分「不需要问」与「没有通道」。
            com.wy.openx.tools.AppContextHolder.subAgentToolAutoApprove = agentToolApprover == null
            // AI 若在回合内跑工作流，闸门同样要就位
            com.wy.openx.tools.AppContextHolder.workflowStepGate = buildWorkflowStepGate(requestConversationId)

            val result = withOverflowCompactRetry(requestConversationId) {
            // 工具输出预算投影（①：
            // 只在**发出去的这一份**上把超预算的旧工具输出换成「已存到 <路径>」，
            // uiState.messages 与数据库都不动（UI 仍可展开全文，备份导出也仍是完整内容）。
            // 不做这一步的话，readFile 单次 200KB 的结果会在此后每轮原样重发，
            // 几轮大读取就能把请求体顶过网关上限——表现为「上下文 250 多 K、换模型也 500、压缩就好」。
            // 历史组装（实测大会话 1395 条 → DTO 1589）与工具输出落盘裁剪是纯 CPU+磁盘重活，
            // 而协程默认 Main.immediate —— 日志实测 3.5s，整段 UI 卡住（用户反馈「点发送卡」）。
            // 挪到 IO：此刻 streamJob 尚未开始写消息，rq/uiState 读的是发送瞬间快照，无并发写风险。
            val projectedHistory = withContext(Dispatchers.IO) {
                val prepT0 = System.currentTimeMillis()
                val hist = buildTurnHistory()
                val projected = boundToolOutputsForRequest(hist, conversationId)
                val prepMs = System.currentTimeMillis() - prepT0
                if (prepMs > 30) {
                    AppLogStore.info("发送耗时", "cid=$conversationId 历史准备 ${prepMs}ms（内存 ${rq.messages.size} 条 → DTO ${hist.size}，IO线程）")
                }
                projected
            }
            chatRepository.sendMessage(
                // 会话合并版：权限档/思考强度按本会话生效值下发（其余字段仍是全局设置）。
                settingsFor(requestConversationId), syntheticText ?: finalUserText, imageBase64 = imageBase64,
                // 「模型选择模式」开启且本会话选过模型时，用本会话的提供商配置（含 baseUrl/Key/端点）；
                // null 则仓库层沿用全局当前提供商，行为与改造前一致。
                forcedProvider = effectiveProviderFor(requestConversationId),
                tools = toolList, toolRegistry = if (toolsEnabled) toolRegistry else null,
                onToolCall = { toolName, toolCallId, toolArgs ->
                    if (toolName == "ask_user") {
                        try {
                            val obj = JSONObject(toolArgs)
                            val question = obj.optString("question", "")
                            val optsArr = obj.optJSONArray("options")
                            val opts = if (optsArr != null) (0 until optsArr.length()).map { optsArr.optString(it, "") } else emptyList()
                            val allowCustom = obj.optBoolean("allow_custom", true)
                            val multiSelect = obj.optBoolean("multi_select", false)
                            // 多问题模式：questions 数组（1–4 问，带 header / 选项说明 / 多选）
                            val itemsArr = obj.optJSONArray("questions")
                            val items = if (itemsArr != null) {
                                (0 until itemsArr.length()).mapNotNull { qi ->
                                    val q = itemsArr.optJSONObject(qi) ?: return@mapNotNull null
                                    val rawOpts = q.optJSONArray("options")
                                    val qOpts = rawOpts?.let { arr ->
                                        (0 until arr.length()).mapNotNull { oi ->
                                            when (val o = arr.opt(oi)) {
                                                is JSONObject -> com.wy.openx.data.model.QuestionOption(
                                                    label = o.optString("label", o.optString("value", "")),
                                                    description = o.optString("description", "")
                                                )
                                                is String -> com.wy.openx.data.model.QuestionOption(o)
                                                else -> null
                                            }
                                        }
                                    } ?: emptyList()
                                    com.wy.openx.data.model.QuestionItem(
                                        question = q.optString("question", ""),
                                        header = q.optString("header", ""),
                                        options = qOpts,
                                        allowCustom = q.optBoolean("allow_custom", true),
                                        multiSelect = q.optBoolean("multiSelect", q.optBoolean("multi_select", false))
                                    )
                                }
                            } else emptyList()
                            editSlot(requestConversationId) {
                                pendingQuestion = QuestionInfo(
                                    question = question.ifBlank { items.firstOrNull()?.question.orEmpty() },
                                    options = opts,
                                    allowCustom = allowCustom,
                                    multiSelect = multiSelect,
                                    items = items
                                )
                            }
                        } catch (_: Exception) {}
                        return@sendMessage
                    }
                    rq.deltaFlushJob?.cancel()
                    rq.deltaFlushJob = null
                    flushDeltaToMessages(requestConversationId)
                    // 工具调用前把已流式生成的思考消息（含 <thinking>）落库；
                    // 否则 AI 结束后 isSending=false 触发 Room 重载会把它覆盖掉，
                    // 表现为"工具调用组上方的思考过程消失"
                    val pendingAssistant = rq.messages.lastOrNull()
                    if (pendingAssistant?.role == MessageRole.ASSISTANT && pendingAssistant.toolName == null && pendingAssistant.content.isNotBlank()) {
                        try { messageRepository.insert(conversationId, pendingAssistant) } catch (_: Exception) {}
                        // 这段思考已经产出完、即将执行工具 → 落定它的结束时刻。
                        // 下一段（若 AI 工具后再思考）会由 flushDeltaToMessages 新建消息另行开段，
                        // 于是「思考→工具→再思考」各自显示自己的用时，而不是从任务开头累加。
                        finishThinkingSegment(requestConversationId, pendingAssistant.id)
                    }
                    val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    rq.lastToolTargetId = resolveAgentForTool(toolName, toolArgs)
                    // 工具消息单条就地更新：先插一条占位消息（toolResult 为空 = 执行中），
                    // onToolResult 用同一 id REPLACE 覆盖成最终结果。状态圆点据 toolResult 判空，不靠配对。
                    val toolMsgId = nextMessageId.incrementAndGet()
                    toolMessageIds[toolCallId] = toolMsgId
                    val toolCallMsg = ChatMessageModel(
                        id = toolMsgId,
                        role = MessageRole.ASSISTANT, author = "构建助手", time = t,
                        content = "正在调用 $toolName",
                        toolCallId = toolCallId, toolName = toolName, toolArguments = toolArgs
                    )
                    editSlot(requestConversationId) {
                        messages = messages + toolCallMsg
                        currentToolName = toolName
                    }
                    uiState = uiState.copy(logs = uiState.logs + "[工具] 正在调用: $toolName")
                    messageRepository.insert(conversationId, toolCallMsg)
                },
                onToolResult = { toolName, toolCallId, rawResult ->
                    // 工具结果统一限幅：readFile 允许读 50MB 文件、git diff 也可能很长，
                    // 而这里的结果会同时进内存消息列表、进 DB、并在下一轮拼进请求。
                    // 原先没有任何上限，一条几十 MB 的工具转储就能把堆顶穿。
                    val resultContent = clampToolResult(rawResult)
                    val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    // 与聊天页状态圆点共用同一判定（JSON 感知，避免 `"error":null` 被误判为失败）
                    val isError = com.wy.openx.data.util.ToolResultStatus.isError(resultContent)
                    // 就地覆盖同一条工具消息：id 不变，内容从 [running] 占位换成最终结果。
                    val toolMsgId = toolMessageIds[toolCallId]
                    val toolResultMsg = ChatMessageModel(
                        id = toolMsgId ?: nextMessageId.incrementAndGet(),
                        role = MessageRole.ASSISTANT, author = "构建助手", time = t,
                        content = buildString {
                            append(if (isError) "❌ " else "✅ ")
                            append(toolName)
                            if (isError) append(" 调用失败: ").append(resultContent.take(200))
                            else append(" 调用成功")
                        },
                        toolCallId = toolCallId, toolName = toolName,
                        toolArguments = rq.messages.firstOrNull { it.id == toolMsgId }?.toolArguments,
                        toolResult = resultContent
                    )
                    // 用同 id 替换列表中的占位消息，保持消息顺序与数量不变
                    val idx = rq.messages.indexOfFirst { it.id == toolMsgId }
                    val updatedMessages = if (idx >= 0) {
                        rq.messages.toMutableList().also { it[idx] = toolResultMsg }
                    } else {
                        rq.messages + toolResultMsg
                    }
                    editSlot(requestConversationId) {
                        messages = updatedMessages
                        currentToolName = null
                    }
                    uiState = uiState.copy(logs = uiState.logs + "[工具] $toolName 完成 (${resultContent.length} 字符)")
                    messageRepository.insert(conversationId, toolResultMsg)
                    if (toolMsgId != null) toolMessageIds.remove(toolCallId)
                },
                // 计费口径：裸 prompt_tokens（缓存命中部分另记 cachedInputTokens，不重复计费）
                onTokens = { tokens ->
                    responseTokens = tokens
                    if (tokens > 0) accumulatedInput += tokens
                },
                // 窗口占用口径（作者 2.3.7 新增回调）：max(total, prompt+completion)，
                // 且**绝不加 cached_tokens**——实测 prompt+completion==total、cached 是 prompt
                // 的子集，重复加会让占用随缓存命中量剧烈抖动（命中 211456 报 424465）。
                onContextTokens = { tokens ->
                    if (tokens > 0) {
                        // 锚取「此刻该会话的消息数」而不是请求发起前的固定值：工具循环里
                        // 每一轮 usage 覆盖的历史都比上一轮多，用固定锚会让 reported 与 delta
                        // 起点错配、判定值凭空少掉一个缓存量级。
                        // 并行改造：按 requestConversationId 记账，且只在该会话是前台时刷投影。
                        advanceUsageAnchor(tokens, estimateContextTokens(rq.messages), requestConversationId)
                        if (conversationId >= 0) {
                            viewModelScope.launch {
                                conversationRepository.updateLastInputTokens(conversationId, tokens)
                            }
                        }
                    }
                },
                onCompletionTokens = { ct ->
                    if (ct > 0) {
                        accumulatedOutput += ct
                        lastCompletionTokens = ct
                    }
                    turnRequests++
                },
                onStreamDelta = { delta ->
                    // 首次吐字前采集展示态快照，供「正文已流出后断网重试」精确回退（见 ChatRepository.onStreamReset）。
                    if (rq.streamResetBaseMessages == null) {
                        rq.streamResetBaseMessages = rq.messages
                        rq.streamResetBaseAcc = rq.deltaAccumulator.toString()
                    }
                    rq.deltaAccumulator.append(delta)
                    if (rq.deltaAccumulator.length > deltaAccMax) {
                        rq.deltaAccumulator.delete(0, rq.deltaAccumulator.length - deltaAccMax)
                    }
                    if (rq.deltaFlushJob?.isActive != true) {
                        rq.deltaFlushJob = viewModelScope.launch {
                            kotlinx.coroutines.delay(150)
                            flushDeltaToMessages(requestConversationId)
                        }
                    }
                },
                // 工具确认：仅「弹窗确认」档启用（原为规划模式专属，现由权限档位统一接管）。
                // 只对写文件/执行命令类工具弹窗；只读类（搜索/读取/查询）直接放行，避免每次调用都打扰。
                // 已加入 toolAllowList 白名单的工具同样直接放行。
                onToolCallRequest = buildToolApprover(requestConversationId),
                onCacheHit = { hit, tokens ->
                    uiState = uiState.copy(logs = uiState.logs + "[缓存] ${if (hit) "命中! 节省 $tokens tokens" else "未命中"}")
                    if (hit && tokens > 0) {
                        turnCacheTokens += tokens
                        lastCachedTokens = tokens
                        val modelName = callModelName
                        val newCacheTotal = uiState.cacheHitTokens + tokens
                        val currentCacheDetails = uiState.cacheHitDetails.toMutableMap()
                        currentCacheDetails[modelName] = (currentCacheDetails[modelName] ?: 0L) + tokens
                        uiState = uiState.copy(cacheHitTokens = newCacheTotal, cacheHitDetails = currentCacheDetails)
                        viewModelScope.launch {
                            tokenPrefs.edit()
                                .putLong("cache_hit_tokens", newCacheTotal)
                                .putLong("cache_model_${modelName}", currentCacheDetails[modelName] ?: 0L)
                                .apply()
                        }
                    }
                },
                historyMessages = projectedHistory,
                // 检查点桶按本轮归属：并发下不能让 A 的文件写入落进 B 的桶。
                // 合成轮（后台通知）没有用户消息，id 传 0 → CheckpointManager 开独立桶。
                checkpointTurnId = userMessage?.id ?: 0L,
                // HTTP 句柄按本轮会话分槽：停止某会话只取消它自己的请求
                conversationId = requestConversationId,
                // 【P4-1】本会话生效的上下文窗口：仓库层「工具循环内就地压缩」的阈值来源。
                // 不能再读 AppContextHolder.contextWindowTokens（那是全局单槽，并发时是别的会话写的值）。
                contextWindowTokens = contextWindowTokensFor(requestConversationId)
            ) }

            val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val assistantText = result.getOrElse { "请求失败：${it.message ?: "未知错误"}" }
            val elapsedTime = System.currentTimeMillis() - requestStartTime
            val finalResponseTokens = responseTokens
            // 输出 token：取最后一轮 API 真实 completion_tokens（消息展示，不累计、不估算）
            val perMessageTokens = lastCompletionTokens
            // 最后一轮前缀缓存命中（有命中才显示）
            val perMessageCachedInput = if (lastCachedTokens > 0) lastCachedTokens else null
            // 输入 token：取最后一轮 API 真实 prompt_tokens 裸值（ 消息展示口径）。
            // 顶部圆环/压缩判定**不用**这个值——那条链路走报告锚（真实窗口占用）。
            val perMessageInputTokens = finalResponseTokens
            // 落库/记账用值：优先报告锚的真实窗口占用；锚缺失（本轮没拿到 usage）才退回裸值兜底。
            // 绝不能用裸 prompt_tokens 落库——否则重启后回读到的占用偏小、自动压缩持续滞后。
            val currentInputContext = usageAnchors[requestConversationId]?.reported
                ?: (if (finalResponseTokens != null) finalResponseTokens else 0)
            val prevReq = conversationCtx[conversationId]?.requestCount ?: 0
            val newReq = prevReq + turnRequests
            conversationCtx[conversationId] = CtxRecord(input = currentInputContext, requestCount = newReq)
            // 并行改造：这两个显示值只在「本轮确实是前台会话」时刷，否则后台请求收尾会把
            // 前台的圆环/请求数改成别的会话的值（就是「忽高忽低」的另一半来源）。
            if (requestConversationId == _uiState.currentConversationId) {
                if (currentInputContext > 0) uiState = uiState.copy(lastInputContext = currentInputContext)
                uiState = uiState.copy(sessionRequestCount = newReq)
            }
            // 请求数持久化：写入会话表，重启后从库回读
            viewModelScope.launch {
                conversationRepository.updateRequestCount(conversationId, newReq)
                // 输入上下文持久化：重启后顶部/压缩可回读真实占用，不再退化为 0
                if (currentInputContext > 0) {
                    conversationRepository.updateLastInputTokens(conversationId, currentInputContext)
                }
            }

            rq.deltaFlushJob?.cancel()
            rq.deltaFlushJob = null
            flushDeltaToMessages(requestConversationId)
            val current = rq.messages
            val lastMsg = current.lastOrNull()
            val isSuccess = result.isSuccess
            // Token 统计埋点：归属取**本次请求实际用的**提供商/模型（见 recordLlmCall）
            recordLlmCall(
                kind = "chat",
                conversationId = conversationId,
                forced = callProviderCfg,
                inputTokens = accumulatedInput,
                outputTokens = accumulatedOutput,
                cachedInputTokens = turnCacheTokens,
                startedAt = requestStartTime,
                durationMillis = elapsedTime.toInt(),
                success = isSuccess,
                errorMessage = if (isSuccess) null else (result.exceptionOrNull()?.message ?: "请求失败")
            )
            if (!isSuccess && !rq.stopRequested) {
                // 模型请求失败：**不再往对话流插入系统消息**（旧实现落库会污染消息流/历史）。
                // （AgentUIState.Error）：错误只在输入框上方的卡片展示，
                // 纯 UI 状态——不落库、不发模型；一直显示直到下次发送/操作清空（stopStreaming / finally 复位）。
                // 运行日志仍完整记录该错误。
                val errText = result.exceptionOrNull()?.message ?: "请求失败，请重试"
                editSlot(requestConversationId) { lastRequestError = "请求失败：$errText" }
            }
            // 成功时才写 AI 回复气泡；失败/停止不再用错误文本覆盖气泡
            // （失败由输入框上方错误卡片呈现；停止时 stopStreaming 已落库部分内容，需原样保留）
            if (isSuccess) {
                // 压缩摘要也是 ASSISTANT + 无工具，且压缩后排在末尾；不能把它当成本轮回复气泡，
                // 否则会把摘要内容改写成回答（且 author 非「构建助手」不会落库，回答随后丢失）。
                if (lastMsg?.role == MessageRole.ASSISTANT && lastMsg.toolName == null && lastMsg.author != "系统") {
                    // 不整体覆盖已流式显示的多轮正文：
                    // 工具循环会在同一气泡累积多轮正文，而返回值可能只是最后一段/兜底摘要，
                    // 直接 copy(content=assistantText) 会把用户已看到的正文「吃掉」。取更长者。
                    val finalContent = if (lastMsg.content.isNotBlank() && lastMsg.content.length >= assistantText.length)
                        lastMsg.content else assistantText
                    val updated = lastMsg.copy(content = finalContent, time = replyTime, tokens = perMessageTokens, inputTokens = perMessageInputTokens, cachedInputTokens = perMessageCachedInput, timeMs = elapsedTime)
                    val msgs = current.toMutableList()
                    msgs[msgs.size - 1] = updated
                    editSlot(requestConversationId) { messages = msgs }
                    if (lastMsg.author == "构建助手") {
                        messageRepository.insert(conversationId, updated)
                    }
                } else {
                    val assistantMessage = ChatMessageModel(
                        id = nextMessageId.incrementAndGet(), role = MessageRole.ASSISTANT,
                        author = "构建助手", time = replyTime, content = assistantText,
                        tokens = perMessageTokens, inputTokens = perMessageInputTokens, cachedInputTokens = perMessageCachedInput, timeMs = elapsedTime
                    )
                    editSlot(requestConversationId) { messages = current + assistantMessage }
                    messageRepository.insert(conversationId, assistantMessage)
                }
            } else if (!rq.stopRequested) {
                // 请求失败（非用户停止）：已流式生成的部分正文不能丢。
                // 此前失败路径不落库，随后 errMsg 插入触发 Room 回流 → 内存气泡被 DB 视图覆盖、正文消失。
                if (lastMsg?.role == MessageRole.ASSISTANT && lastMsg.toolName == null && lastMsg.content.isNotBlank()) {
                    if (lastMsg.author == "构建助手") {
                        messageRepository.insert(conversationId, lastMsg)
                    }
                }
            }
            // 累加 token 用量（总 + 按模型）
            val tokensToAdd = accumulatedInput.takeIf { it > 0 }
            if (tokensToAdd != null) {
                val newTotal = uiState.totalTokens + tokensToAdd
                val inputToAdd = accumulatedInput
                val newInputTotal = uiState.totalInputTokens + inputToAdd
                val modelName = uiState.settings.model
                val currentDetails = uiState.tokenDetails.toMutableMap()
                currentDetails[modelName] = (currentDetails[modelName] ?: 0L) + tokensToAdd
                uiState = uiState.copy(totalTokens = newTotal, totalInputTokens = newInputTotal, tokenDetails = currentDetails)
                viewModelScope.launch {
                    tokenPrefs.edit()
                        .putLong("total_tokens", newTotal)
                        .putLong("total_input_tokens", newInputTotal)
                        .putLong("model_${modelName}", currentDetails[modelName] ?: 0L)
                        .apply()
                }
            }
            // AI 可能通过 memory_save/memory_delete 修改了记忆，重新加载
            loadMemory()
            // 七期：回复完成钩子。放在 UI 状态复位之前、紧跟在真实文本落地之后，
            // 插件此时能拿到完整回复。异常已由调度器包住，不会让本轮报错。
            runCatching {
                com.wy.openx.plugin.PluginHookManager.afterReply(assistantText, requestConversationId)
            }
            uiState = uiState.copy(logs = uiState.logs + "[$replyTime] 模型响应完成")
            // 本轮成功结束：若开了知识提炼，把这轮交给 worker 攒批（攒够 N 轮才真去跑模型）。
            enqueueKnowledgeExtraction(requestConversationId, assistantText)
            editSlot(requestConversationId) {
                isSending = false
                currentToolName = null
                reconnecting = false
                reconnectAttempt = 0
                reconnectMax = 0
                thinkingStartedAt = 0L
            }
            // AI 完成通知
            val s = uiState.settings
            // 后台：补一条系统通知（响铃/震动在后台经常听不到）；前台：只响铃/震动，不弹通知。
            if (!appInForeground) {
                val snippet = (if (assistantText.isNotBlank()) assistantText else finalUserText)
                    .replace(Regex("""\s+"""), " ").trim().take(120)
                com.wy.openx.service.CompletionNotifier.notifyComplete(
                    appContext,
                    title = "任务完成",
                    body = snippet.ifBlank { "AI 已完成本轮任务" },
                    silent = s.notificationSound == "none"
                )
            }
            if (s.notifyOnComplete || s.vibrateOnComplete) {
                try {
                    if (s.notifyOnComplete && s.notificationSound != "none") {
                        val soundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        val ringtone = RingtoneManager.getRingtone(appContext, soundUri)
                        ringtone?.play()
                    }
                    if (s.vibrateOnComplete) {
                        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                            vm?.defaultVibrator
                        } else {
                            @Suppress("DEPRECATION")
                            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator?.vibrate(200)
                        }
                    }
                } catch (_: Exception) {}
            }
            // 处理排队的消息（协程结束后再处理，避免与 finally 复位 isSending 产生竞态）
            } finally {
                // AppContextHolder 里的 sink/gate 仍是全局单槽（P3 才按会话分槽）：
                // 若本会话结束就无条件置 null，会把还在跑的另一个会话的审批口掉掉，
                // 表现为它的子代理/工作流工具突然不再弹确认。故只在「最后一个在跑的也结束」时清。
                activeRequests--
                if (activeRequests <= 0) {
                    activeRequests = 0
                    com.wy.openx.tools.AppContextHolder.subAgentProgressSink = null
                    com.wy.openx.tools.AppContextHolder.subAgentToolApprover = null
                    com.wy.openx.tools.AppContextHolder.subAgentToolAutoApprove = false
                    com.wy.openx.tools.AppContextHolder.workflowStepGate = null
                }
                concurrencyGate.release()
                CheckpointManager.endTurn()
                // 过期轮守卫：旧轮被阻塞工具卡死、finally 迟到时（期间新轮已开出、代次已抬走），
                // 这段会**复位新轮的 isSending / 触发自动压缩 / 抢出队 / 补工具结果 / 收尾思考**、
                // 破坏并发下的新轮状态——全部跳过，只消费抑制标记（防它在队列里残留毒害下一轮）。
                if (rq.runGeneration != myGen) {
                    rq.suppressQueueAutoDequeueOnce = false
                    android.util.Log.i("MainViewModel", "过期轮收尾作废: cid=$requestConversationId myGen=$myGen 当前Gen=${rq.runGeneration}")
                } else {
                // 防御性兜底：正常/异常结束都可能存在「工具 call 有、result 缺失」的情况
                // （网关返回的 tool_call id 与 result 回传的 tool_call_id 不一致、或某工具执行被中断），
                // 补发一条 result，避免聊天页状态点永久闪烁。
                fillMissingToolResults("已完成", requestConversationId)
                // 收尾最后一段思考（正常结束、报错、用户停止都会走到这里）。
                // 放在复位 thinkingStartedAt 之前也一致——本表与它互不影响。
                finishThinkingSegment(requestConversationId)
                editSlot(requestConversationId) {
                    isSending = false
                    currentToolName = null
                    reconnecting = false
                    reconnectAttempt = 0
                    reconnectMax = 0
                    thinkingStartedAt = 0L
                    // 本轮结束：丢弃尚未消费的展示态快照（下一轮重新采集）。
                    streamResetBaseMessages = null
                    streamResetBaseAcc = ""
                    // 本轮所有正文已落进 messages.content，可追写缓冲不再需要。
                    streamText.clear()
                }
                // 本轮（含工具循环）全部结束后自动压缩检查：工具循环中上下文可能已膨胀到超阈值
                //（onTokens 已实时报到 lastInputContext），这里用校验后的真实值补一次判定——
                // 「每次 CallLlm 前检查」等价但更贴近现有流式架构，避免工具循环尾轮超预算。
                // 注意：必须放在 finally 内——主 try 任何异常都会中止 finally 之后的代码，
                // 若放外面，AI 回复一句就停、排队的消息再也不发。 状态机循环不因单帧异常退出。
                try {
                    // 先把「工具循环内就地压缩」的结果落库，再做阈值判定。
                    // 顺序很重要：就地压缩只重建了请求副本、历史未变，落库后 slot 才是压缩后的；
                    // 否则 maybeAutoCompact 用报告锚（压缩后的请求值，很小）判定，会以为无需压缩，
                    // 而下一轮又从完整历史重建 → 上下文涨回去、反复压缩。
                    persistPendingToolLoopCompaction(requestConversationId)
                    maybeAutoCompact(requestConversationId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "自动压缩后置检查失败: $e", e)
                }
                // 先记下是否有排队的用户消息：有的话 processNextQueuedMessage 会立刻起下一轮，
                // 本轮的通知搭那趟车（或它结束时兜底），此处不再另起一轮，避免同会话双开。
                // 注意读**前台**队列——processNextQueuedMessage 内部就是读前台（既有行为），
                // 两边口径必须一致，否则判断会错位。
                val hadQueued = slotOf(uiState.currentConversationId).messageQueue.isNotEmpty()
                try {
                    processNextQueuedMessage()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 队列处理异常不能静默吞掉，记录并继续，避免遗留会话状态。
                    android.util.Log.e("MainViewModel", "队列消息处理异常: $e", e)
                    val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val errMsg = ChatMessageModel(
                        nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", t,
                        "队列消息处理中断：${e.message ?: e.javaClass.simpleName}"
                    )
                    uiState = uiState.copy(messages = uiState.messages + errMsg)
                }
                // 通知兜底送达：本轮期间入队的后台完成通知，若没有排队用户消息接力，就现在
                // 唤醒本会话送达（否则它会一直等到下次用户发言才搭车）。有排队消息则跳过，
                // 交给接替的那一轮（搭车或它结束时的兜底）。
                if (!hadQueued) {
                    try {
                        flushPendingNotificationsFor(requestConversationId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("MainViewModel", "后台通知兜底送达失败: $e", e)
                    }
                }
                } // 过期轮守卫 end：else 分支见上
            }
        }
        return true
    }

    /**
     * 给「有 tool 调用、但结果仍为空」的工具就地补发一条结果。
     *
     * 单条消息模型下，工具状态 = toolResult 是否为空。正常结束时若仍为空（工具执行被中断、
     * 或回调未到达），就地更新为「已完成」，避免状态点一直闪。与 stopStreaming 的补发同源，
     * 文案不同（停止=「已停止」，正常=「已完成」）。
     */
    private fun fillMissingToolResults(reason: String, conversationId: Long) {
        // 读写都针对「发起本轮请求的那个会话」的槽位，而不是当下前台：
        // 并行场景下 finally 可能在用户已切到别的会话后才跑到，取 uiState 会把 A 的
        // 工具补发结果写进 B。槽位为真源 + 仅当 cid==前台时刷投影，P1 下与旧行为等价。
        val slot = slotOf(conversationId)
        val missing = slot.messages
            .filter { it.role == MessageRole.ASSISTANT && it.toolName != null && it.toolResult == null }
        if (missing.isEmpty()) return
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val updatedMessages = slot.messages.toMutableList()
        missing.forEach { tool ->
            val idx = updatedMessages.indexOfFirst { it.id == tool.id }
            if (idx >= 0) {
                updatedMessages[idx] = tool.copy(
                    content = "  ${tool.toolName} $reason",
                    toolResult = "$reason：${tool.toolName}"
                )
            }
        }
        slot.messages = updatedMessages
        if (conversationId == uiState.currentConversationId) {
            uiState = uiState.copy(messages = updatedMessages)
        }
        viewModelScope.launch {
            missing.forEach { tool ->
                val idx = updatedMessages.indexOfFirst { it.id == tool.id }
                if (idx >= 0) messageRepository.insert(conversationId, updatedMessages[idx])
            }
        }
    }

    private fun processNextQueuedMessage() {
        val cid = uiState.currentConversationId
        val slot = slotOf(cid)
        // 「立即发送」打断路径的抑制：被打断那一轮的 finally 里抛掉一次自动出队，
        // 目标消息由 sendQueuedMessage 在 old.join() 后直接 sendMessage 发出。
        if (slot.suppressQueueAutoDequeueOnce) {
            slot.suppressQueueAutoDequeueOnce = false
            return
        }
        val queue = slot.messageQueue
        if (queue.isEmpty()) return
        // 竞态防护：上游 finally 复位 isSending 后又可能已消费队首并开跑新一轮生成。
        // 若此刻仍在生成，说明队列已被那条链接管，交给「本轮完成后 finally 继续消费」。
        if (uiState.isSending) return
        val nextId = queue.first()
        val remaining = queue.drop(1)
        // 走 editSlot：队列是会话级状态，copy(uiState) 会写到前台单槽，多会话下互相弹空。
        editSlot(cid) { messageQueue = remaining }
        sendMessage(nextId)
    }

    fun cancelQueuedMessage(index: Int) {
        val cid = uiState.currentConversationId
        val queue = slotOf(cid).messageQueue
        if (index !in queue.indices) return
        val removedId = queue[index]
        val rest = queue.toMutableList().also { it.removeAt(index) }
        editSlot(cid) {
            messageQueue = rest
            // 取消 = 彻底丢弃这条排队消息，气泡与库行一并删掉（否则会留下一个永不发送的幽灵气泡）。
            messages = messages.filterNot { it.id == removedId }
            queuedAttachments.remove(removedId)
        }
        viewModelScope.launch { runCatching { messageRepository.deleteById(removedId) } }
    }

    /**
     * 立即发送队列中的指定消息。
     *
     * 「点击立即发送 = 打断 AI 直接发出」语义：
     * `removePendingQueueMessageById(id)` → `cancelMessage(keepPartialResponse=true)`
     * → 等待队列解除阻塞 → `sendTextMessage(finalText, chatId)`。
     *
     * 发送的是**这一条**（按下标从队列摘下），不是队首：没点到的消息保持原顺序留队。
     * 生成中则先打断当前 AI（保留已生成的部分内容，不丢），等旧协程 finally
     * （复位 isSending + 消费抑制标记）彻底完成后再立即发送目标。
     */
    fun sendQueuedMessage(index: Int) {
        val cid = uiState.currentConversationId
        val slot = slotOf(cid)
        val queue = slot.messageQueue
        if (index !in queue.indices) return
        val targetId = queue[index]
        val wasSending = slot.isSending || slot.streamJob?.isActive == true
        // 先从队列摘下目标（气泡保留；发送时由 sendMessage 解除 queued 标记）。
        // 立即发送的是这一条，不是队首 —— 剩余项保持原顺序留队。
        editSlot(cid) { messageQueue = queue.toMutableList().also { it.removeAt(index) } }
        if (!wasSending) {
            // 空闲：直接发送目标（不走 processNextQueuedMessage，避免先发掉队里别的消息）。
            sendMessage(queuedMsgId = targetId, targetConversationId = cid)
            return
        }
        // 生成中：打断当前生成，随后立即发送这条。
        // suppressQueueAutoDequeueOnce 让被打断那轮的 finally 里抛掉一次自动出队，
        // 否则它会先发队首（另一条）并与这里的立即发送竞态。
        val old = slot.streamJob?.takeIf { it.isActive }
        if (old != null) slot.suppressQueueAutoDequeueOnce = true
        stopStreaming(cid, insertStopNotice = false)
        // 旧协程**阻塞执行长工具**（终端命令最长 1800s）时，cancel 只标记取消、奈何不了
        // 阻塞进程——旧协程要等工具自己结束才进 finally。若照旧 old.join() 在这里等，
        // 表现就是「AI 已停止但消息卡排队」——立即发送没有立即。改为不等：直接发目标；
        // 旧轮迟到的 finally 由 sendMessage 里的**代次守卫**判为过期轮、自动作废收尾。
        viewModelScope.launch {
            sendMessage(queuedMsgId = targetId, targetConversationId = cid)
        }
    }

    /** 用户主动停止生成：停止后自动发送队列中残留的消息。 */
    fun stopStreamingAndSendQueue() {
        val old = streamJob
        val hasQueue = slotOf(uiState.currentConversationId).messageQueue.isNotEmpty()
        stopStreaming()
        if (!hasQueue) return
        // 等待旧协程 finally（复位 isSending + 消费队首开跑）彻底完成后，再补发队尾，避免竞态。
        // 旧协程 finally 的 processNextQueuedMessage 已弹掉队首并启动新一轮生成，此处第二条
        // 会在 isSending=true 时被守卫拦截，交给链式 finally 继续消费——绝不重复入队。
        viewModelScope.launch {
            old?.join()
            processNextQueuedMessage()
        }
    }

    /** 立即发送整个队列：打断当前生成后，按当前队列顺序立刻逐条发完。 */
    fun sendAllQueuedMessages() {
        val cid = uiState.currentConversationId
        val slot = slotOf(cid)
        if (slot.messageQueue.isEmpty()) return
        val wasSending = slot.isSending || slot.streamJob?.isActive == true
        if (!wasSending) {
            processNextQueuedMessage()
            return
        }
        // 生成中：打断当前 AI，随后从头逐条立即发送。
        val old = slot.streamJob?.takeIf { it.isActive }
        if (old != null) slot.suppressQueueAutoDequeueOnce = true
        stopStreaming(cid, insertStopNotice = false)
        // 同上：不等旧轮 finally（阻塞长工具要等几分钟），直接发队首；被作废的旧轮收尾由代次守卫兜底。
        viewModelScope.launch {
            processNextQueuedMessage()
        }
    }

    fun clearMessageQueue() {
        val cid = uiState.currentConversationId
        val ids = slotOf(cid).messageQueue
        editSlot(cid) {
            messageQueue = emptyList()
            // 清空队列 = 丢弃这些排队消息，气泡与库行一并删除。
            messages = messages.filterNot { m -> m.id in ids }
            ids.forEach { queuedAttachments.remove(it) }
        }
        viewModelScope.launch {
            ids.forEach { runCatching { messageRepository.deleteById(it) } }
        }
    }

    fun clearAllConversations() {
        // 并发下不能只停前台：其他还在跑的会话会被清空后继续往里写消息。
        slots.values.filter { it.streamJob?.isActive == true || it.isSending }
            .forEach { stopStreaming(it.conversationId) }
        viewModelScope.launch {
            conversationRepository.clearAll()
            messageRepository.clearAll()
            // 任务随会话一起清（否则留下永不显示的孤儿行）
            taskPersistJobs.values.forEach { it.cancel() }
            taskPersistJobs.clear()
            taskRepository.clearAll()
            // 落盘的工具输出同属会话产物，一并清空
            runCatching { com.wy.openx.data.util.ToolOffloadPaths.root(appContext.filesDir).deleteRecursively() }
            conversationCtx.clear()
            uiState = uiState.copy(conversations = emptyList(), messages = emptyList())
            resetInputContext()
        }
    }

    fun deleteConversation(id: Long) {
        // 必须按 id 停流，不能只停前台：并发时被删的可能是后台还在跑的那个会话，
        // 不停掉它，它的协程会继续往已删除的会话写消息（Room 外键无约束，会留下孤儿数据）。
        stopStreaming(id)
        viewModelScope.launch {
            messageRepository.clearByConversation(id)
            conversationRepository.deleteById(id)
            // 任务面板随会话一起清掉，否则会留下永不显示的孤儿行。
            taskRepository.clear(id)
            // 该会话的在途落库任务也取消：否则它可能在 clear 之后把快照又写回去。
            taskPersistJobs.remove(id)?.cancel()
            // 工具输出落盘目录随会话一起清掉：它们只是「请求投影」的产物，
            // 会话没了就没人会再去 readFile 那些路径，留着白占空间。
            com.wy.openx.data.util.ToolOutputOffload(appContext).clearConversation(id)
            if (id == uiState.currentConversationId) {
                uiState = uiState.copy(messages = emptyList(), currentConversationId = -1)
                resetInputContext(forConversation = id)
            } else {
                conversationCtx.remove(id)
            }
        }
    }

    fun renameConversation(id: Long, title: String) {
        val updated = uiState.conversations.map {
            if (it.id == id) it.copy(title = title) else it
        }
        uiState = uiState.copy(conversations = updated)
        // 手动改名 = 标题定稿：否则生成前手动改的名字会被随后一次 AI 生成覆盖。
        // 同时落库，跨重启也保持「不再自动生成」。
        slotOf(id).titleFinalized = true
        viewModelScope.launch {
            conversationRepository.updateTitle(id, title)
            conversationRepository.updateTitleFinalized(id, true)
        }
    }

    /** 置顶/取消置顶会话。置顶后常驻列表最前（置顶组内按最近活跃排序）。 */
    fun togglePinConversation(id: Long, pinned: Boolean) {
        val updated = uiState.conversations.map {
            if (it.id == id) it.copy(isPinned = pinned) else it
        }
        uiState = uiState.copy(conversations = updated)
        viewModelScope.launch { conversationRepository.setPinned(id, pinned) }
    }

    // ===== 会话归档（「历史」页） =====

    /**
     * 归档 / 取消归档会话。
     *
     * 归档**不是删除**：会话与其消息全部保留，只是从侧边栏主列表收进「历史」页。
     * 归档当前会话时，主列表 Flow 会重新发出、当前会话已不在其中，[observeConversations]
     * 的兜底逻辑会自动切到另一个会话——这正是期望行为。
     */
    fun setConversationArchived(id: Long, archived: Boolean) {
        viewModelScope.launch {
            conversationRepository.setArchived(id, archived)
            showToast(if (archived) "已归档，可在「历史」中查看" else "已取消归档")
        }
    }

    /**
     * 供 UI 调用（非 suspend）：在 viewModelScope 里跑一次自动归档。
     * 偏好设置里刚选完天数就调它，让用户立刻看到效果，而不是等下次启动。
     */
    fun triggerAutoArchive(days: Int) {
        viewModelScope.launch {
            val n = runAutoArchiveIfNeeded(days)
            if (n > 0) showToast("已归档 $n 个长期未活跃的会话")
        }
    }

    /**
     * 自动归档：把「超过 [days] 天未活跃」的会话批量归档，返回归档条数（0 表示无需处理）。
     *
     * 天数**由调用方显式传入**，不在这里读 `uiState.settings`：init 阶段 uiState 还是默认值，
     * 从 uiState 读会永远拿到 0（等于"未开启"）。
     *
     * 时间基准取 `lastActiveAt`——DAO 里已排除 `lastActiveAt = 0` 的升级前旧会话，
     * 避免老会话被一次性全归档（那对用户是惊吓而非功能）。
     */
    suspend fun runAutoArchiveIfNeeded(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        return runCatching { conversationRepository.archiveInactiveBefore(cutoff) }
            .onSuccess { if (it > 0) FileLogger.i("AutoArchive", "自动归档 $it 个超过 $days 天未活跃的会话") }
            .onFailure { FileLogger.w("AutoArchive", "自动归档失败: ${it.message}") }
            .getOrDefault(0)
    }

    /**
     * 保存设置。设置页已改为改动即存，因此这里**不再弹 toast**——
     * 否则每次拨动开关、每敲完一次防抖都会弹一次「设置已保存」，刷屏。
     * 需要反馈的调用点（如「保存 API 配置」按钮）自行设置自己的提示文案。
     */
    fun updateSettings(newSettings: AppSettings) {
        uiState = uiState.copy(settings = newSettings)
        com.wy.openx.tools.AppContextHolder.currentSettings = newSettings
        viewModelScope.launch { settingsStore.save(newSettings) }
    }

    /** 关闭「并行执行频繁限流」提示。并行设置保持不变。 */
    fun dismissRateLimitWarning() {
        if (uiState.rateLimitWarning) uiState = uiState.copy(rateLimitWarning = false)
    }

    /**
     * 提示窗里的「保持并行」：关掉提示，并让**本会话**后续不再弹。
     *
     * 与 [dismissRateLimitWarning] 的区别：那个只是收起弹窗（下次撞够阈值还会弹），
     * 这个记下用户「就是要并行」的决定。只静默上报的那个会话，不牵连其他会话。
     */
    fun keepParallelToolCalls() {
        rateLimitWarningConvId?.let { rateLimitKeptParallel.add(it) }
        rateLimitHits.set(0)
        if (uiState.rateLimitWarning) uiState = uiState.copy(rateLimitWarning = false)
    }

    /**
     * 提示窗里的「改为串行」：关掉「并行执行批内工具」并**落盘**。
     *
     * 走 [updateSettings] 而不是只改内存：用户在弹窗里的选择等同于去设置页拨开关，
     * 不落盘的话重启后又变回并行，用户会以为开关没生效。
     */
    fun disableParallelToolCalls() {
        updateSettings(uiState.settings.copy(parallelToolCalls = false))
        uiState = uiState.copy(rateLimitWarning = false)
    }

    // ===== 提供商独立管理（阶段 2/4/5） =====

    /** 保存提供商（新增/覆盖）；成功后立即刷新 activeProvider 绑定。 */
    fun saveProvider(provider: com.wy.openx.data.model.AIProviderConfig) {
        // 先乐观更新内存列表，让 resolveActiveProvider 立刻用到新的 selectedModel/字段，
        // 不必等 Room Flow 异步回灌（否则编辑当前提供商的模型后，聊天页要等一次 Flow 才更新）。
        val existing = providers.any { it.id == provider.id }
        providers = if (existing) providers.map { if (it.id == provider.id) provider else it } else providers + provider
        resolveActiveProvider()
        viewModelScope.launch {
            aiProviderRepository.saveProvider(provider)
            resolveActiveProvider()
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            aiProviderRepository.deleteProvider(id)
            resolveActiveProvider()
        }
    }

    /** 切换当前激活的提供商（设置页「使用」按钮）。 */
    fun selectProvider(id: String) {
        if (providers.none { it.id == id }) return
        // 只改内存的 selectedProviderId，扁平字段统一交给 resolveActiveProvider 派生并落盘，
        // 避免「id 已换、model 仍旧」的中间态写盘触发 settingsFlow 覆盖。
        uiState = uiState.copy(settings = uiState.settings.copy(selectedProviderId = id))
        resolveActiveProvider()
    }

    fun setProviderEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            aiProviderRepository.setProviderEnabled(id, enabled)
            resolveActiveProvider()
        }
    }

    fun reorderProviders(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = providers
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val reordered = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        providers = reordered
        viewModelScope.launch { aiProviderRepository.reorderProviders(reordered) }
    }

    /** 把当前 providers 列表的排序持久化（编辑页返回时调用）。 */
    fun persistProviderOrder(ordered: List<com.wy.openx.data.model.AIProviderConfig>) {
        providers = ordered
        viewModelScope.launch { aiProviderRepository.reorderProviders(ordered) }
    }

    /** 切换某提供商的选中模型（聊天页模型选择列表）。 */
    fun selectProviderModel(providerId: String, model: String) {
        if (providers.none { it.id == providerId }) return
        viewModelScope.launch {
            // 【会话级模式】选择只归本会话：写槽（立即生效于展示与请求）+ 持久化到会话行。
            // 仍照旧记一次「上次使用」到全局，供**全局模式**下新建会话沿用。
            // 会话级模式下新会话**不**沿用它（SessionSlot 空 override 不再回落全局），
            // 必须用户自己选；已显式选过模型的其他会话不受影响（它们有自己的 override）。
            val sessionMode = !uiState.settings.modelGlobalMode
            val cid = uiState.currentConversationId
            if (sessionMode && cid > 0L) {
                editSlot(cid) {
                    providerIdOverride = providerId
                    modelOverride = model
                }
                conversationRepository.setModelOverride(cid, providerId, model)
            }
            aiProviderRepository.setSelectedModel(providerId, model)
            // 立即同步内存 providers（不等 Room Flow），让聊天页顶栏/圆环即时刷新
            providers = providers.map { if (it.id == providerId) it.copy(selectedModel = model) else it }
            // 跨提供商选模型：把该提供商设为当前激活。
            // 只改内存，不在这里单独落盘——否则会用一个「selectedProviderId 已更新、model 仍是旧值」
            // 的中间态写 DataStore，触发 settingsFlow 用旧 model 覆盖；统一交给 resolveActiveProvider
            // 一次性派生并落盘。
            if (uiState.settings.selectedProviderId != providerId) {
                uiState = uiState.copy(settings = uiState.settings.copy(selectedProviderId = providerId))
            }
            resolveActiveProvider()
            // 原先这里有一句 showToast("本会话模型：xxx（已记住，新建会话沿用）")。
            // 用户反馈这个黑色提示窗不美观，去掉。
            //
            // 去掉不丢信息：顶栏副标题会实时变成「<模型名> · 会话级 · 本会话」，
            // 选没选中、选中哪家一眼可见，不需要再弹一次窗重复告知。
            // 顺带说明：这条文案本身也已过时——新会话在会话级模式下不再沿用它
            // （SessionSlot 空 override 不回落全局），写着「新建会话沿用」是假的。
        }
    }

    /**
     * 清除本会话的模型选择。
     *（会话级模式下需要一个反悔入口，否则选错了只能一直 pin 着。）
     *
     * 【新会话默认不选模型】语义随之改了：会话级模式下清空 = **回到「未选择」**，
     * 不再是「跟随全局当前模型」——否则会出现两种"空"状态（未选择 / 跟随全局）混在一起，
     * 顶栏和发送守卫都没法区分。关闭会话级开关后照旧跟随全局。
     */
    fun clearSessionModelOverride() {
        val cid = uiState.currentConversationId
        if (cid <= 0L) return
        viewModelScope.launch {
            editSlot(cid) {
                providerIdOverride = null
                modelOverride = null
            }
            conversationRepository.setModelOverride(cid, null, null)
            showToast(
                if (!uiState.settings.modelGlobalMode) "本会话已清空模型选择，请重新选择"
                else "本会话已改为跟随全局当前模型"
            )
        }
    }

    /** 更新某提供商的模型列表（编辑页保存时）。 */
    fun updateProviderModels(id: String, models: List<String>) {
        viewModelScope.launch {
            aiProviderRepository.updateModels(id, models)
            resolveActiveProvider()
        }
    }

    fun resolveModelMetadata(providerId: String, type: com.wy.openx.data.model.ProviderType, modelIds: List<String>) {
        val normalized = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            val metadata = modelMetadataService.resolveAll(providerId, type, normalized)
            providerModelMetadata = providerModelMetadata + metadata
        }
    }

    /** 拉取提供商模型列表（编辑页）。 */
    fun fetchProviderModels(provider: com.wy.openx.data.model.AIProviderConfig) {
        viewModelScope.launch {
            providerFetchState = ProviderFetchState.Loading
            val result = com.wy.openx.data.provider.ModelApiService.fetchModels(provider)
            result.onSuccess { r ->
                providerFetchState = ProviderFetchState.Success(r.models, r.debugInfo)
                resolveModelMetadata(provider.id, provider.type, r.models)
            }.onFailure { e ->
                val debug = (e as? com.wy.openx.data.provider.FetchModelsException)?.debugInfo
                providerFetchState = ProviderFetchState.Error(e.message ?: "拉取失败", debug)
            }
        }
    }

    fun resetProviderFetchState() {
        providerFetchState = ProviderFetchState.Idle
    }

    /** 测试单个模型连通性（编辑页模型行「测试」）。 */
    /** 全部测试的进度；null = 没在跑。 */
    var providerTestAllProgress by mutableStateOf<TestAllProgress?>(null)
        private set

    private var providerTestAllJob: Job? = null

    /** 本轮「全部测试」覆盖的模型，停止时按它精确摘转圈，不误伤用户手点的单测。 */
    private var providerTestAllTargets: Set<String> = emptySet()

    /** 单个模型的测试任务，按模型名取消。 */
    private val providerTestJobs = mutableMapOf<String, Job>()

    /**
     * 串行测试 [models] 里每个模型的连通性，逐个出结果。
     *
     * 刻意**串行**而非全并发：探活是真实 HTTP 请求，几十个并发极易撞供应商限流（429），
     * 而 429 在结果里和「模型坏了」一样显示为失败——用户会据此误删本来可用的模型。
     * 慢一点换结果可信；每测完一个就立刻写入 [providerTestResults]，不用等全部跑完。
     *
     * 开始前清空旧结果：上一轮的结果混进来会让人分不清哪条是这轮的。
     */
    fun testAllProviderModels(provider: com.wy.openx.data.model.AIProviderConfig, models: List<String>) {
        val targets = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (targets.isEmpty()) return
        providerTestAllJob?.cancel()
        providerTestResults = emptyMap()
        providerTestAllTargets = targets.toSet()
        providerTestAllJob = viewModelScope.launch {
            try {
                for ((i, m) in targets.withIndex()) {
                    providerTestAllProgress = TestAllProgress(i + 1, targets.size)
                    providerTesting = providerTesting + m
                    val r = com.wy.openx.data.provider.ModelApiService.testModel(provider, m)
                    providerTestResults = providerTestResults + (m to r)
                    providerTesting = providerTesting - m
                }
            } finally {
                // 只摘掉本批的条目，别把用户同时手点的单测转圈也一起清掉
                providerTesting = providerTesting - targets.toSet()
                providerTestAllProgress = null
                providerTestAllTargets = emptySet()
            }
        }
    }

    /** 中断「全部测试」。 */
    fun stopTestAllProviderModels() {
        providerTestAllJob?.cancel()
        providerTestAllJob = null
        // 协程取消是协作式的：正在跑的那条请求要等取消传到 OkHttp 才真正断开，
        // 界面不能等它——先就地复位，否则按下停止后进度和转圈还挂在界面上。
        providerTesting = providerTesting - providerTestAllTargets
        providerTestAllTargets = emptySet()
        providerTestAllProgress = null
    }

    fun testProviderModel(provider: com.wy.openx.data.model.AIProviderConfig, model: String) {
        providerTestJobs[model]?.cancel()
        providerTesting = providerTesting + model
        providerTestJobs[model] = viewModelScope.launch {
            try {
                val result = com.wy.openx.data.provider.ModelApiService.testModel(provider, model)
                providerTestResults = providerTestResults + (model to result)
            } finally {
                providerTesting = providerTesting - model
                providerTestJobs.remove(model)
            }
        }
    }

    /** 中断单个模型的测试（模型行上的「停止」）。 */
    fun stopTestProviderModel(model: String) {
        providerTestJobs.remove(model)?.cancel()
        // 同上：先摘转圈，不等底层请求真正断开
        providerTesting = providerTesting - model
    }

    fun clearProviderTestResults() {
        providerTestJobs.values.forEach { it.cancel() }
        providerTestJobs.clear()
        providerTestResults = emptyMap()
        providerTesting = emptySet()
    }

    /** 加载所有已启用提供商的模型元数据（模型选择列表展示能力标签用）。 */
    fun loadAllProviderModelMetadata() {
        val enabled = providers.filter { it.isEnabled }
        if (enabled.isEmpty()) return
        viewModelScope.launch {
            val resolved = mutableMapOf<String, com.wy.openx.data.model.ModelMetadata>()
            for (p in enabled) {
                val ids = p.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (ids.isEmpty()) continue
                resolved += modelMetadataService.resolveAll(p.id, p.type, ids)
            }
            if (resolved.isNotEmpty()) {
                providerModelMetadata = providerModelMetadata + resolved
            }
        }
    }

    /**
     * 从磁盘加载全部自定义模型元数据到 [providerModelMetadata]，键为「提供商ID:模型名」。
     *
     * 编辑页进场时调用：否则编辑弹窗与模型行的「能力/窗口」永远回填不出用户上次保存的值
     * （表现为「配置不持久化，离开再进来就没了」）。
     */
    fun loadCustomModelMetadata() {
        viewModelScope.launch {
            val all = customModelMetadataStore.all()
            if (all.isNotEmpty()) {
                providerModelMetadata = providerModelMetadata + all
            }
        }
    }

    /** 保存自定义模型元数据（编辑模型弹窗）。 */
    fun saveCustomModelMetadata(providerId: String, model: String, metadata: com.wy.openx.data.model.ModelMetadata) {
        viewModelScope.launch {
            customModelMetadataStore.put(providerId, model, metadata)
            providerModelMetadata = providerModelMetadata + ("$providerId:$model" to metadata)
        }
    }

    fun removeCustomModelMetadata(providerId: String, model: String) {
        viewModelScope.launch {
            customModelMetadataStore.remove(providerId, model)
        }
    }

    /**
     * 据当前选中的 provider id 解析出 activeProvider，并同步 settings 的扁平字段
     * （保持与旧数据/其它读取点的兼容），随后挂到 AppContextHolder 供 ChatRepository 使用。
     */
    private fun resolveActiveProvider() {
        val selectedId = uiState.settings.selectedProviderId
        val provider = providers.firstOrNull { it.id == selectedId && it.isEnabled }
            ?: providers.firstOrNull { it.id == selectedId }
            ?: providers.firstOrNull { it.isEnabled }
        com.wy.openx.tools.AppContextHolder.currentProvider = provider
        com.wy.openx.tools.AppContextHolder.currentConversationId = uiState.currentConversationId
        // 上下文窗口随提供商/模型解析结果同步推送（工具循环内就地压缩的阈值来源）。
        // 同上：会话级模式下按本会话取，单槽不该存全局值（见 sendMessage 处注释）。
        com.wy.openx.tools.AppContextHolder.contextWindowTokens =
            contextWindowTokensFor(uiState.currentConversationId)
        if (provider != null) {
            // 同步扁平字段：让旧读取点（如 MemoryTool 分类、顶部显示）继续可用。
            //
            // 关键：必须**同时落盘**。uiState.settings 有两个写入方——本函数（内存派生）
            // 与 settingsFlow.onEach（DataStore 权威值）。若这里只改内存不写 DataStore，
            // 一旦别处 save(settings) 触发 settingsFlow 发出，就会用陈旧的扁平字段把本函数的
            // 派生结果覆盖回去（表现为「切模型要切两次」）。落盘后 DataStore 与本函数一致，
            // settingsFlow 再发出的是相同值，不再覆盖。
            val updated = uiState.settings.copy(
                provider = provider.name,
                baseUrl = provider.baseUrl,
                apiKey = provider.firstUsableApiKey,
                // 提供商未配置任何模型时保留原 model，避免把聊天页模型清空成空白
                model = provider.effectiveModel.ifBlank { uiState.settings.model },
                selectedApiPreset = provider.name,
                // 兜底：选中 id 为空/失效时回写实际生效的 provider id，保证设置页「当前」标记正确
                selectedProviderId = provider.id
            )
            if (updated != uiState.settings) {
                uiState = uiState.copy(settings = updated)
                com.wy.openx.tools.AppContextHolder.currentSettings = updated
                viewModelScope.launch { settingsStore.save(updated) }
            }
        }
    }

    /**
     * 旧数据迁移：首次启动时若提供商表为空，把旧的扁平 settings（provider/baseUrl/apiKey/model）
     * 与 apiPresets 列表 seed 成 ai_providers 记录。
     * 只执行一次（表非空即跳过），保证用户升级后原配置不丢。
     *
     * **全新安装必须跳过**：AppSettings 的默认值本身就写着 SiliconFlow / Qwen/Qwen3.5-4B，
     * 只按「表为空」判断的话，会把这两个默认值当成用户配置造出一条他从未创建过的提供商，
     * 还因为 selectedProviderId 被解析成它而标上「当前」，看起来像软件自带了一个配置。
     * 故另要求「有旧配置痕迹」：用户填过 Key，或改过地址/服务商/模型任意一项。
     */
    private suspend fun seedProvidersFromLegacy(settings: AppSettings) {
        if (aiProviderRepository.getAllProvidersOnce().isNotEmpty()) return
        // 直接读 Flow 首值（而非内存 uiState.apiPresets），避免 apiPresetsFlow 尚未发出时
        // 误判为空、漏掉用户已保存的多个预设。
        val existing: List<ApiPreset> = settingsStore.apiPresetsFlow.first()
        if (existing.isEmpty() && !hasLegacyConfig(settings)) return
        val presets: List<ApiPreset> = if (existing.isNotEmpty()) existing else {
            listOf(
                ApiPreset(
                    name = settings.provider, provider = settings.provider,
                    baseUrl = settings.baseUrl, model = settings.model, apiKey = settings.apiKey
                )
            )
        }
        val seeded: List<AIProviderConfig> = presets.mapIndexed { index, p ->
            AIProviderConfig(
                id = "legacy-${index}-${p.name.hashCode()}",
                name = p.name,
                type = com.wy.openx.data.model.ProviderType.entries.firstOrNull { it.displayName == p.provider }
                    ?: com.wy.openx.data.model.ProviderType.SILICONFLOW,
                apiKey = p.apiKey,
                baseUrl = p.baseUrl.ifBlank { settings.baseUrl },
                defaultModel = p.model.ifBlank { settings.model },
                models = listOfNotNull(p.model.ifBlank { settings.model }.takeIf { it.isNotBlank() }),
                selectedModel = p.model.ifBlank { settings.model },
                sortOrder = index
            ).sanitized()
        }
        if (seeded.isNotEmpty()) {
            seeded.forEach { aiProviderRepository.saveProvider(it) }
        }
    }

    /** 扁平 settings 里是否留下了用户配置过的痕迹（与默认值逐项对比）。 */
    private fun hasLegacyConfig(settings: AppSettings): Boolean {
        val defaults = AppSettings()
        return settings.apiKey.isNotBlank() ||
            settings.baseUrl != defaults.baseUrl ||
            settings.provider != defaults.provider ||
            settings.model != defaults.model
    }

    // ===== 模式切换 / 项目工作区（聊天页输入框旁的模式与项目按钮） =====

    /** 直接切换到指定模式（build/plan/stock/chat），与 /mode、顶部模式显示共用 agentMode。 */
    fun setAgentMode(mode: String) {
        if (uiState.agentMode == mode) return
        uiState = uiState.copy(agentMode = mode, prompt = "")
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val roleName = agentModeLabel(mode)
        val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, "已切换至$roleName")
        uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 切换至 $mode 模式")
        viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
    }

    private fun agentModeLabel(mode: String): String = when (mode) {
        "plan" -> "规划模式（只读分析）"
        "stock" -> "股票模式（行情分析）"
        "chat" -> "聊天模式（纯对话）"
        else -> "构建模式（可读写文件）"
    }

    /**
     * 设置工具权限档位（readonly / confirm / allow），对 构建/股票/规划 模式生效。
     * 只读档会让写/执行类工具**不再下发**给模型，故切换后需要清空本轮已缓存的白名单提示不必要——
     * 下发发生在每次 sendMessage，下次发送即生效。
     */
    /**
     * 切换**当前会话**的工具权限档（不再改全局设置）。
     *
     * 为什么不落 DataStore：会话级选择是临场的工作方式（这个会话想全自动跑、
     * 那个会话想逐步确认），存成全局会让「切会话」和「改设置」两件事耦合。
     * 全局默认档仍在设置页改，未覆盖过的会话跟随它（见 effectivePermissionMode）。
     */
    /**
     * 切换工具权限档。**作用范围由设置页「工具权限模式」开关决定**：
     *  - 开关开：改全局默认档，并清空所有会话的 override → 所有会话立即统一；
     *  - 开关关（默认）：只改当前会话，其他会话不受影响。
     */
    fun setToolPermissionMode(mode: String) {
        if (uiState.settings.toolPermissionGlobalMode) {
            if (uiState.settings.toolPermissionMode == mode) return
            val newSettings = uiState.settings.copy(toolPermissionMode = mode)
            uiState = uiState.copy(settings = newSettings)
            com.wy.openx.tools.AppContextHolder.currentSettings = newSettings
            // 全局模式：把各会话的显式覆盖清掉，否则切过的会话仍留自己的档，
            // 用户会觉得「改了全局怎么没统一」。这正是原先「清除覆盖」按钮的活儿。
            slots.values.forEach { it.toolPermissionModeOverride = null }
            refreshProjection()
            viewModelScope.launch { settingsStore.save(newSettings) }
            showToast("工具权限（全局）：${toolPermissionLabel(mode)}")
        } else {
            val cid = uiState.currentConversationId
            if (effectivePermissionMode(cid) == mode) return
            editSlot(cid) { toolPermissionModeOverride = mode }
            showToast("本会话工具权限：${toolPermissionLabel(mode)}")
        }
    }

    private fun toolPermissionLabel(mode: String): String = when (mode) {
        "readonly" -> "只读"
        "confirm" -> "弹窗确认"
        "workspace" -> "仅工作区修改"
        else -> "完全允许"
    }

    /**
     * 「仅工作区修改」档的目标判定：目标是否肯定落在工作区内。
     * 判定规则见 [WorkspacePathPolicy]（抽成独立 object 是为了可测——这段逻辑出过一次
     * 真实误报：把 content/old_text 这类内容参数当路径扫，写代码时内容里带个 /usr/bin 就弹窗）。
     *
     * 返回 true 才走「工作区内直接放行」；返回 false 会退回「弹窗确认」由用户定夺，
     * 所以这里的误判只会让本来能放行的操作多弹一次窗，不会错放。
     */
    private fun isWithinWorkspace(cleanName: String, args: Map<String, Any>): Boolean {
        // shizuku 直接操作宿主系统，永远在工作区之外。
        if (cleanName == "shizuku") return false

        if (ToolPermissionPolicyEngine.isShellTool(cleanName)) {
            val eval = ToolPermissionPolicyEngine.evaluate(cleanName, args, toolPermissionRuleStore.load())
            // 内置只读白名单（ls / git status / cat…）不产生写入，放行。
            if (eval.verdict == ToolPermissionPolicyEngine.Verdict.ALLOW) return true
            return WorkspacePathPolicy.allWithin(
                WorkspacePathPolicy.commandPaths((args["command"] as? String).orEmpty())
            )
        }

        // todo / memory_* / skill_* 写的是面板与配置，不动工作区文件，不受此档约束。
        if (cleanName !in WorkspacePathPolicy.FILE_TOOLS) return true

        return WorkspacePathPolicy.allWithin(WorkspacePathPolicy.collectPathArgs(args))
    }

    /** 切换思考强度。作用范围由设置页「思考过程模式」开关决定，语义同 [setToolPermissionMode]。 */
    fun setReasoningEffort(effort: String) {
        if (uiState.settings.reasoningEffortGlobalMode) {
            if (uiState.settings.reasoningEffort == effort) return
            val newSettings = uiState.settings.copy(reasoningEffort = effort)
            uiState = uiState.copy(settings = newSettings)
            com.wy.openx.tools.AppContextHolder.currentSettings = newSettings
            slots.values.forEach { it.reasoningEffortOverride = null }
            refreshProjection()
            viewModelScope.launch { settingsStore.save(newSettings) }
            showToast("思考强度（全局）：${reasoningEffortLabel(effort)}")
        } else {
            val cid = uiState.currentConversationId
            if (effectiveReasoningEffort(cid) == effort) return
            editSlot(cid) { reasoningEffortOverride = effort }
            showToast("本会话思考强度：${reasoningEffortLabel(effort)}")
        }
    }

    private fun reasoningEffortLabel(effort: String): String = when (effort) {
        "none" -> "关闭"
        "minimal" -> "极低"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        "xhigh" -> "极高"
        "max" -> "最高"
        else -> "模型默认"
    }

    /** 刷新项目工作区列表（filesDir/workspace/projects 下子目录名）。 */
    fun refreshProjects() {
        viewModelScope.launch {
            projects = withContext(Dispatchers.IO) {
                val projectsDir = projectsDir()
                projectsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }?.sorted()
                    ?: emptyList()
            }
        }
    }

    /** 创建项目工作区并切换为当前项目（目录在容器内对应 /root/workspace/projects/<name>）。 */
    fun createProject(name: String) {
        val clean = sanitizeProjectName(name)
        if (clean.isEmpty()) {
            showToast("项目名无效")
            return
        }
        if (isExternalNameTaken(clean)) {
            showToast("该名称已被本地目录占用，请换一个")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dir = File(projectsDir(), clean)
                if (!dir.exists()) dir.mkdirs()
            }
            refreshProjects()
            selectProject(clean)
        }
    }

    /** 切换当前项目（持久化到设置）。传空串表示回到默认工作区（不选项目）。项目记忆随项目切换重新加载。 */
    fun selectProject(name: String) {
        if (uiState.settings.currentProject == name) return
        val updated = uiState.settings.copy(currentProject = name.trim())
        uiState = uiState.copy(settings = updated)
        com.wy.openx.tools.AppContextHolder.currentSettings = updated
        viewModelScope.launch { settingsStore.save(updated) }
        // 项目记忆/项目技能按项目隔离：切换后必须重载，否则会把上一个项目的项目技能注入新项目对话
        loadMemory()
        loadSkills()
    }

    /**
     * 删除工作区：**均不删除文件，仅从列表移除**。内部项目的目录移入 archive 目录
     * （projects/.trash/<name>，可恢复）；外部本地目录解除关联（文件原地保留）。
     * 若删的是当前工作区则清空选择。
     */
    fun deleteProject(name: String) {
        if (isExternalWorkspace(name)) {
            val settings = uiState.settings
            val updatedSettings = settings.copy(
                externalWorkspaces = settings.externalWorkspaces.filterNot { it.name == name }
            ).let { if (it.currentProject == name) it.copy(currentProject = "") else it }
            uiState = uiState.copy(settings = updatedSettings)
            com.wy.openx.tools.AppContextHolder.currentSettings = updatedSettings
            viewModelScope.launch { settingsStore.save(updatedSettings) }
            showToast("已移除本地目录「$name」（目录文件保留）")
            loadMemory()   // 当前项目被清空时同步卸载项目记忆
            loadSkills()   // 项目技能同理由存储位置决定，需同步卸载
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val src = File(projectsDir(), name)
            if (src.exists()) {
                val trash = File(projectsDir(), ".trash").apply { mkdirs() }
                val dest = File(trash, name)
                val destName = if (!dest.exists()) name else "$name-${System.currentTimeMillis()}"
                runCatching { src.renameTo(File(trash, destName)) }
            }
            withContext(Dispatchers.Main) {
                if (uiState.settings.currentProject == name) {
                    val updated = uiState.settings.copy(currentProject = "")
                    uiState = uiState.copy(settings = updated)
                    com.wy.openx.tools.AppContextHolder.currentSettings = updated
                    viewModelScope.launch { settingsStore.save(updated) }
                    loadMemory()   // 当前项目被清空时同步卸载项目记忆
                    loadSkills()   // 项目技能同理由存储位置决定，需同步卸载
                }
                refreshProjects()
                showToast("已移除项目「$name」（目录保留）")
            }
        }
    }

    /** 名称 → 宿主绝对路径（仅外部本地目录）；不存在返回 null。 */
    fun externalPathOf(name: String): String? =
        uiState.settings.externalWorkspaces.firstOrNull { it.name == name }?.path

    /** 该工作区名是否是注册的外部本地目录。 */
    fun isExternalWorkspace(name: String): Boolean = externalPathOf(name) != null

    /**
     * 注册用户所选设备本地目录为外部工作区（参考 RC10：双向直读该目录，不复制）。
     * 校验：是目录、未注册过、不与内部项目目录/默认工作区冲突；名称与现有工作区去重加后缀。
     * 完成后自动切换为当前工作区。
     */
    fun addExternalWorkspace(hostPath: String) {
        if (hostPath.isBlank()) {
            showToast("目录无效")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val canonical = runCatching { File(hostPath).canonicalFile }.getOrNull() ?: File(hostPath)
            val abs = canonical.absolutePath
            if (!canonical.isDirectory) {
                onUi { showToast("所选目录不存在或不可访问") }
                return@launch
            }
            val settings = uiState.settings
            if (settings.externalWorkspaces.any { it.path == abs }) {
                onUi { showToast("该目录已在工作区列表中") }
                return@launch
            }
            val internalRoot = projectsDir().canonicalPath
            if (abs == internalRoot || abs.startsWith("$internalRoot${File.separator}")) {
                onUi { showToast("不能添加工作区内部目录") }
                return@launch
            }
            val myRoot = com.wy.openx.container.Workspace.root(getApplication()).canonicalPath
            if (abs == myRoot) {
                onUi { showToast("不能添加默认工作区目录") }
                return@launch
            }
            val baseName = sanitizeProjectName(canonical.name).ifBlank { "workspace" }
            val taken = settings.externalWorkspaces.map { it.name }.toMutableSet()
            taken.addAll(projects)
            var name = baseName
            var suffix = 2
            while (name in taken) {
                name = "$baseName ($suffix)"
                suffix++
            }
            val updated = settings.copy(
                externalWorkspaces = settings.externalWorkspaces + com.wy.openx.data.model.ExternalWorkspace(name, abs)
            )
            onUi {
                uiState = uiState.copy(settings = updated)
                com.wy.openx.tools.AppContextHolder.currentSettings = updated
                viewModelScope.launch { settingsStore.save(updated) }
                selectProject(name)
                showToast("已添加本地目录「$name」")
            }
        }
    }

    private fun onUi(block: () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** 名称是否被外部工作区名占用（供新建项目重名检查）。 */
    fun isExternalNameTaken(name: String): Boolean =
        uiState.settings.externalWorkspaces.any { it.name == name }

    private fun projectsDir(): File = File(com.wy.openx.container.Workspace.root(getApplication()), "projects")

    private fun sanitizeProjectName(name: String): String =
        name.trim().replace(Regex("[^\\p{L}\\p{N}._\\- ]"), "").take(64).trim()

    private fun handleSlashCommand(cmd: String) {
        val args = cmd.trim().split("\\s+".toRegex())
        val canonical = canonicalBuiltinCommand(args[0])
        if (canonical == null) {
            // 非内置命令：看是否命中某个已启用技能，命中则把技能正文作为本轮指令发送。
            val skill = enabledSlashSkills().firstOrNull {
                it.first.equals(args[0].removePrefix("/"), ignoreCase = true)
            }
            if (skill != null) {
                runSlashSkill(skill.first, args.drop(1).joinToString(" "))
                return
            }
        }
        when (canonical ?: args[0]) {
            "/help" -> {
                val helpText = buildSlashHelp(enabledSlashSkills())
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, helpText)
                uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 显示命令帮助", prompt = "")
                viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
            }
            "/redo" -> {
                if (redoStack.isNotEmpty()) {
                    undoStack.add(uiState.messages.toList())
                    trimUndoStack()
                    val restored = redoStack.removeAt(redoStack.size - 1)
                    val cid = uiState.currentConversationId
                    uiState = uiState.copy(messages = restored, prompt = "")
                    viewModelScope.launch {
                        rewriteContextPreservingFolded(cid, restored)
                    }
                }
            }
            "/compact" -> {
                uiState = uiState.copy(prompt = "")
                compactConversation()
            }
            "/sessions" -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val list = uiState.conversations.joinToString("\n") { "  [${if (it.active) "当前" else " "}] ${it.title} (${it.meta})" }
                val content = if (uiState.conversations.isEmpty()) "暂无会话" else "会话列表：\n$list"
                val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, content)
                uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 显示会话列表", prompt = "")
                viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
            }
            "/new" -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                uiState = uiState.copy(prompt = "", logs = uiState.logs + "[$time] 新建会话")
                createNewConversation()
            }
            "/details" -> {
                uiState = uiState.copy(showDetails = !uiState.showDetails, prompt = "")
            }
            "/thinking" -> {
                val cid = uiState.currentConversationId
                val arg = args.drop(1).joinToString(" ").trim()
                val aliases = mapOf(
                    "关闭" to "none", "极低" to "minimal", "低" to "low",
                    "中" to "medium", "高" to "high", "极高" to "xhigh",
                    "最高" to "max", "默认" to "default"
                )
                val effort = if (arg.isEmpty()) "" else aliases[arg] ?: arg.lowercase(Locale.ROOT)
                // 模型给的是「支持的档位」，model default 不在其中也要能切回去。
                val supported = effortOptionsFor(cid)
                    ?: listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
                val options = if ("default" in supported) supported else listOf("default") + supported
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val content = when {
                    effort.isEmpty() -> buildString {
                        append("当前思考强度：").append(reasoningEffortLabel(effectiveReasoningEffort(cid)))
                        append("\n可选档位：").append(options.joinToString(", "))
                        append("\n用法：/thinking <档位>")
                    }
                    effort !in options -> "不支持的思考强度：$arg\n可选档位：${options.joinToString(", ")}"
                    else -> {
                        setReasoningEffort(effort)
                        "思考强度已设置为：${reasoningEffortLabel(effort)}"
                    }
                }
                uiState = uiState.copy(prompt = "")
                val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, content)
                uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 思考强度：$content")
                viewModelScope.launch { messageRepository.insert(cid, msg) }
            }
            "/themes" -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val info = "暗黑主题（默认）"
                val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, info)
                uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 显示主题信息", prompt = "")
                viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
            }
            "/init" -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val initArg = if (args.size > 1) args.subList(1, args.size).joinToString(" ") else ""
                if (initArg.isNotEmpty()) {
                    val newSettings = uiState.settings.copy(rules = initArg)
                    uiState = uiState.copy(settings = newSettings, prompt = "")
                    viewModelScope.launch { settingsStore.save(newSettings) }
                    val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, "项目规则已设置：\n$initArg")
                    uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 设置项目规则")
                    viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
                } else {
                    val content = if (uiState.settings.rules.isNotBlank())
                        "当前项目规则：\n${uiState.settings.rules}" else "尚未设置项目规则。使用 /init <规则内容> 设置"
                    val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, content)
                    uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 显示项目规则", prompt = "")
                    viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
                }
            }
            "/settings" -> {
                uiState = uiState.copy(prompt = "")
                openSettings()
            }
            "/editor" -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        type = "text/plain"
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    appContext.startActivity(intent)
                    uiState = uiState.copy(logs = uiState.logs + "[$time] 打开外部编辑器", prompt = "")
                } catch (e: Exception) {
                    uiState = uiState.copy(logs = uiState.logs + "[$time] 启动编辑器失败：" + (e.message ?: ""), prompt = "")
                }
            }
            "/export" -> {
                viewModelScope.launch {
                    try {
                        com.wy.openx.data.util.ExportHelper.saveToDownloads(
                            appContext, uiState.messages, uiState.settings.model, asJson = false
                        )
                        showToast("已保存 ${uiState.messages.size} 条消息到 下载/OpenX/")
                    } catch (e: Exception) {
                        showToast("导出失败：" + (e.message ?: "未知错误"))
                    }
                }
                uiState = uiState.copy(prompt = "")
            }
            "/exportshare" -> {
                viewModelScope.launch {
                    val file = com.wy.openx.data.util.ExportHelper.exportMarkdown(
                        appContext, uiState.messages, uiState.settings.model
                    )
                    com.wy.openx.data.util.ExportHelper.shareFile(appContext, file)
                    showToast("已导出 ${uiState.messages.size} 条消息")
                }
                uiState = uiState.copy(prompt = "")
            }
            "/memory" -> {
                uiState = uiState.copy(prompt = "")
                openMemoryManage()
            }
            "/skills" -> {
                uiState = uiState.copy(prompt = "")
                openSkillManage()
            }
            "/terminal" -> {
                uiState = uiState.copy(prompt = "")
                openTerminal()
            }
            "/mcp" -> {
                uiState = uiState.copy(prompt = "")
                openMcpManage()
            }
            // [PLUGIN-MOD] 插件快捷命令
            "/plugins" -> {
                uiState = uiState.copy(prompt = "")
                openPluginManage()
            }
            "/mode" -> {
                val current = uiState.agentMode
                val newMode = when (current) {
                    "build" -> "plan"
                    "plan" -> "stock"
                    "stock" -> "chat"
                    else -> "build"
                }
                setAgentMode(newMode)
            }
            "/undo" -> {
    uiState = uiState.copy(prompt = "")
    viewModelScope.launch {
        val restored = CheckpointManager.restoreAll()
        if (restored > 0) {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", t, "已回滚 $restored 个文件的修改")
            uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$t] 文件快照回滚 $restored")
            messageRepository.insert(uiState.currentConversationId, msg)
        }
    }
    if (undoStack.isNotEmpty()) {
                    redoStack.add(uiState.messages.toList())
                    trimRedoStack()
                    val restored = undoStack.removeAt(undoStack.size - 1)
                    val cid = uiState.currentConversationId
                    uiState = uiState.copy(messages = restored)
                    viewModelScope.launch {
                        rewriteContextPreservingFolded(cid, restored)
                    }
                }
            }
            "/models" -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val modelArg = if (args.size > 1) args.subList(1, args.size).joinToString(" ") else ""
                if (modelArg.isNotEmpty()) {
                    val newSettings = uiState.settings.copy(model = modelArg)
                    uiState = uiState.copy(settings = newSettings, prompt = "")
                    viewModelScope.launch { settingsStore.save(newSettings) }
                    val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, "模型已切换为：$modelArg")
                    uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 切换模型为 $modelArg")
                    viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
                } else {
                    val presets = uiState.apiPresets
                    if (presets.isNotEmpty()) {
                        val currentName = uiState.settings.selectedApiPreset
                        val idx = presets.indexOfFirst { it.name == currentName }
                        val nextIdx = if (idx < 0 || idx >= presets.lastIndex) 0 else idx + 1
                        val nextPreset = presets[nextIdx]
                        applyApiPreset(nextPreset)
                        viewModelScope.launch { settingsStore.save(uiState.settings) }
                        val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time,
                            "模型已切换至：${nextPreset.name}（${nextPreset.provider}）\n模型：${nextPreset.model}\n接口：${nextPreset.baseUrl}")
                        uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 切换至预设 ${nextPreset.name}", prompt = "")
                        viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
                    } else {
                        val modelInfo = "当前模型：${uiState.settings.model}\n接口：${uiState.settings.baseUrl}\n提供方：${uiState.settings.provider}\n预设：${uiState.settings.selectedApiPreset}"
                        val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, modelInfo)
                        uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 显示模型信息", prompt = "")
                        viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
                    }
                }
            }
            "/presets" -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val presetNames = uiState.apiPresets.joinToString("\n") { "  - ${it.name} (${it.provider})" }
                val info = if (presetNames.isEmpty()) "暂无预设" else "可用预设：\n$presetNames"
                val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, info)
                uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 显示预设列表", prompt = "")
                viewModelScope.launch { messageRepository.insert(uiState.currentConversationId, msg) }
            }
            "/usage" -> {
                val cid = uiState.currentConversationId
                uiState = uiState.copy(prompt = "")
                viewModelScope.launch {
                    val now = System.currentTimeMillis()
                    val todayStart = java.util.Calendar.getInstance().apply {
                        timeInMillis = now
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    val today = withContext(Dispatchers.IO) { loadUsageSummary(todayStart) }
                    val allTime = withContext(Dispatchers.IO) { loadUsageSummary(0L) }
                    val table = buildString {
                        appendLine("| 项目 | 今日 | 累计 |")
                        appendLine("|---|---|---|")
                        appendLine("| 调用次数 | ${today.calls} | ${allTime.calls} |")
                        appendLine("| 输入 tokens | ${formatTokens(today.inputTokens)} | ${formatTokens(allTime.inputTokens)} |")
                        appendLine("| 输出 tokens | ${formatTokens(today.outputTokens)} | ${formatTokens(allTime.outputTokens)} |")
                        appendLine("| 缓存命中 tokens | ${formatTokens(today.cachedInputTokens)} | ${formatTokens(allTime.cachedInputTokens)} |")
                        appendLine("| 预估费用 | ${formatUsd(today.costUsd)} | ${formatUsd(allTime.costUsd)} |")
                    }.trimEnd()
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, table)
                    uiState = uiState.copy(messages = uiState.messages + msg, logs = uiState.logs + "[$time] 显示用量统计")
                    messageRepository.insert(cid, msg)
                }
            }
            else -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                uiState = uiState.copy(
                    messages = uiState.messages + ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, "未知命令：${args[0]}，输入 /help 或 /帮助 查看可用命令"),
                    logs = uiState.logs + "[$time] 未知命令：${args[0]}", prompt = ""
                )
            }
        }
    }

    /** 已启用且正文非空的技能，供 `/` 菜单、`/help` 与技能触发共用。 */
    private fun enabledSlashSkills(): List<Pair<String, String>> =
        (uiState.projectSkills + uiState.skills)
            .filter { it.enabled && it.instructions.isNotBlank() }
            .filter { canonicalBuiltinCommand("/${it.name}") == null }
            .distinctBy { it.name.lowercase(Locale.ROOT) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { it.name to it.description }

    /** `/技能名 参数`：把技能正文作为本轮指令发送。 */
    private fun runSlashSkill(skillName: String, extraArgs: String) {
        val preset = (uiState.projectSkills + uiState.skills).firstOrNull {
            it.enabled && it.name.equals(skillName, ignoreCase = true)
        } ?: return
        val body = buildString {
            append(preset.instructions.trim())
            if (extraArgs.isNotBlank()) {
                append("\n\n用户附加输入：")
                append(extraArgs)
            }
        }
        uiState = uiState.copy(prompt = body)
        // 放到下一帧再发，避免在外层 sendMessage 的命令分支里重入。
        viewModelScope.launch { sendMessage() }
    }

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    /** 汇总自 [start] 起的调用次数、token 与预估费用（按「渠道 + 模型」单价逐组估算）。 */
    private suspend fun loadUsageSummary(start: Long): UsageSummary {
        val summary = llmCallRecordDao.getSummary(start).first()
        val cost = llmCallRecordDao.getModelProviderCostStats(start).first().sumOf { stat ->
            val model = stat.model ?: return@sumOf 0.0
            estimateUsageCost(
                model, stat.providerId,
                stat.inputTokens, stat.outputTokens,
                stat.cachedInputTokens, stat.cacheCreationTokens
            )
        }
        return UsageSummary(
            summary.calls, summary.inputTokens, summary.outputTokens,
            summary.cachedInputTokens, cost
        )
    }

    /**
     * 单个「渠道 + 模型」组合的预估费用：优先用模型元数据里的自定义单价，
     * 元数据没有价格时退回内置价格表（[com.wy.openx.ui.screen.estimateCost]）。
     */
    private fun estimateUsageCost(
        model: String,
        providerId: String?,
        inputTokens: Long,
        outputTokens: Long,
        cachedInputTokens: Long,
        cacheCreationTokens: Long
    ): Double {
        val base = providerModelMetadata[model]
        val custom = providerId?.let { providerModelMetadata["$it:$model"] } ?: base
        val meta = com.wy.openx.ui.command.metadataForUsage(model, base, custom)
        return com.wy.openx.ui.command.usageCostUsd(
            inputTokens, outputTokens, cachedInputTokens, cacheCreationTokens, meta
        ) ?: com.wy.openx.ui.screen.estimateCost(model, inputTokens + outputTokens + cachedInputTokens)
    }

    private fun formatUsd(cost: Double): String = when {
        cost <= 0 -> "\$0.0000"
        cost < 0.01 -> String.format(Locale.US, "\$%.4f", cost)
        cost < 1.0 -> String.format(Locale.US, "\$%.3f", cost)
        else -> String.format(Locale.US, "\$%.2f", cost)
    }

    private suspend fun resolveFileRefs(text: String): String {
        var result = text
        val refPattern = java.util.regex.Pattern.compile("@(\\w+(?:\\.\\w+)?)")
        val matcher = refPattern.matcher(text)
        while (matcher.find()) {
            val ref = matcher.group(1) ?: continue
            val replacement = when {
                ref == "settings" || ref == "设置" -> "当前设置：\n模型=" + uiState.settings.model + "\n接口=" + uiState.settings.baseUrl + "\n系统提示=" + uiState.settings.systemPrompt.take(200)
                ref == "logs" || ref == "日志" -> "最近日志：\n" + uiState.logs.takeLast(5).joinToString("\n") { "[${it.tag}] ${it.message}" }
                ref == "help" || ref == "帮助" -> buildSlashHelp(enabledSlashSkills())
                ref == "messages" || ref == "消息" -> "最近消息（" + uiState.messages.size + " 条）：" + uiState.messages.takeLast(6).joinToString("\n") { "[" + it.role.name + "] " + it.content.take(100) }
                ref == "rules" || ref == "规则" -> "项目规则：\n" + (uiState.settings.rules.ifBlank { "未设置" })
                ref == "files" || ref == "文件" -> "可用文件：\n" + listDirectoryFiles()
                ref.contains(".") -> readFileFromRef(ref)
                else -> "[未知引用：@" + ref + "]"
            }
            result = result.replace("@" + ref, replacement)
        }
        return result
    }

    private suspend fun listDirectoryFiles(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val dir = getApplication<android.app.Application>().getExternalFilesDir("AgentConsoleApp")
            if (dir == null || !dir.exists()) return@withContext "暂无文件"
            dir.listFiles()?.map { it.name + " (" + formatFileSize(it.length()) + ")" }?.joinToString("\n") ?: "暂无文件"
        } catch (e: Exception) { "读取失败：" + (e.message ?: "") }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
    }

    private suspend fun readFileFromRef(filename: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val dir = getApplication<android.app.Application>().getExternalFilesDir("AgentConsoleApp")
            val file = java.io.File(dir, filename)
            if (!file.exists()) return@withContext "[文件未找到：$filename]"
            val content = java.util.Scanner(file, "UTF-8").useDelimiter("\\Z").use { if (it.hasNext()) it.next() else "" }
            "文件 [$filename] 内容：\n" + content.take(3000)
        } catch (e: Exception) { "[读取 $filename 失败：${e.message ?: ""}]" }
    }

    private fun executeShellCommand(cmd: String) {
        viewModelScope.launch {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            uiState = uiState.copy(logs = uiState.logs + "[$time] 执行命令：$cmd")
            val startMs = System.currentTimeMillis()
            val (exitCode, stdout, stderr) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    val out = proc.inputStream.bufferedReader().readText()
                    val err = proc.errorStream.bufferedReader().readText()
                    val code = proc.waitFor()
                    Triple(code, out, err)
                } catch (e: Exception) { Triple(-1, "", "执行失败：" + (e.message ?: "未知错误")) }
            }
            val elapsed = System.currentTimeMillis() - startMs
            val statusIcon = if (exitCode == 0) "✅" else "❌"
            val resultMsg = buildString {
                appendLine("$statusIcon 命令执行完毕 (exit=$exitCode, ${elapsed}ms)")
                appendLine("```")
                append("$ ")
                appendLine(cmd)
                if (stdout.isNotBlank()) appendLine(stdout.trimEnd())
                if (stderr.isNotBlank()) { appendLine("--- stderr ---"); appendLine(stderr.trimEnd()) }
                append("```")
            }
            val msg = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", time, resultMsg)
            uiState = uiState.copy(
                messages = uiState.messages + msg,
                logs = uiState.logs + "[$time] 命令 exit=$exitCode, ${elapsed}ms, ${(stdout+stderr).length} 字符"
            )
            messageRepository.insert(uiState.currentConversationId, msg)
        }
    }

    fun executeCommand(cmd: String) { handleSlashCommand(cmd) }

    fun resendMessage(message: ChatMessageModel) {
        if (message.role != MessageRole.USER) return
        uiState = uiState.copy(
            prompt = message.content,
            logs = uiState.logs + "准备重新发送消息"
        )
        showToast("已填入消息，点击发送重新发送")
    }

    fun regenerateMessage() {
        if (uiState.isSending) return
        stopStreaming()
        val msgs = uiState.messages
        val lastUserIdx = msgs.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIdx < 0) return
        val userMsg = msgs[lastUserIdx]
        undoStack.add(msgs.toList())
        trimUndoStack()
        redoStack.clear()
        // 裁剪到最后一条用户消息之前，用原内容重新发起请求
        val trimmed = msgs.take(lastUserIdx)
        uiState = uiState.copy(
            messages = trimmed,
            prompt = userMsg.content,
            attachments = emptyList()
        )
        val cid = uiState.currentConversationId
        refreshContextAfterHistoryChange(cid, trimmed)
        viewModelScope.launch {
            rewriteContextPreservingFolded(cid, trimmed)
        }
        sendMessage()
    }

    fun rewindMessage(messageId: Long, option: RewindOption) {
        if (uiState.isSending) return
        stopStreaming()
        val cid = uiState.currentConversationId
        viewModelScope.launch {
            // 【不再 cancel 消息 observer】。DB 改写已收进单个事务（`replaceAllMessages`），
            // 要么整体生效要么整体回滚，Room 只发射一次——中途被「停止」取消也不会留下
            // 「已清空但没写完」的残局。原先必须先 cancel 掉 observer 再手工重建，
            // 而「cancel 之后重建失败」正是用户报障的根因（历史整段消失）。
            //
            // 【关键】在**含折叠区**的完整历史上定位目标，而不是只看 uiState.messages。
            // 压缩后折叠区原文不在 uiState.messages 里，若只在那里找，用户对折叠区消息点回退会
            // 因 `idx < 0` 静默失败（既没回退也没提示）。回退目标在
            // 被压缩区间时，`rewindConversation` 会一并**解除压缩**再截断。
            val all = withContext(Dispatchers.IO) {
                messageRepository.getAllMessagesIncludingFolded(cid)
            }
            val idx = all.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            val target = all[idx]
            if (target.role != MessageRole.USER) return@launch
            // 会话里是否存在压缩折叠区（决定下面的「解除压缩」语义是否要生效）。
            // 注意不能只看「目标是否在折叠区」：用户对**压缩后新消息**点「仅恢复会话」时
            // 目标不在 uiState.messages 之外、却在折叠区内，若只按后者判断就会走入
            // 「按目标截断压缩后小尾巴」的分支 → 上下文掉到十几K（见下）。
            val hasFolded = all.any { it.folded }
            var sys: ChatMessageModel? = null
            if (option != RewindOption.RESTORE_CONVERSATION) {
                val restored = CheckpointManager.restoreToMessage(messageId)
                if (restored > 0) {
                    val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    sys = ChatMessageModel(nextMessageId.incrementAndGet(), MessageRole.SYSTEM, "系统", t, "已回滚 $restored 个文件的修改")
                    uiState = uiState.copy(logs = uiState.logs + "[$t] 检查点回滚 $restored 个文件")
                }
            }
            var finalMsgs: List<ChatMessageModel>
            // ⚠ 必须**显式清掉 folded**：不清标记就原样写回，折叠区原文既不进 uiState.messages
            // （上下文）也不进请求；`replaceAllMessages` 原样保留 folded，
            // 解除压缩必须在**调用方**清标记。
            if (option == RewindOption.RESTORE_CODE) {
                finalMsgs = uiState.messages
            } else if (hasFolded) {
                // 会话**存在压缩折叠区**时，「会话类」回退=**恢复到目标消息那一刻**，
                // **不是**把全部折叠历史（可能 1 万条）一把展开回上下文。
                //
                // 为什么不能全量解开：压缩是「摘要 + 保留尾部」的**链式**结构——
                // 新会话 → 压缩 → 摘要+部分历史 → 再压缩 → …依次循环，每次只折叠上一段、
                // 累积多次。旧实现 `segStart=0` 时把整卷折叠原文解回上下文：
                //   · 压缩摘要卡片消失、之前上下文全量恢复（用户报障的本体）；
                //   · 顶部数字飙到几十万、下一次真发送必真超窗 → 又被压回去 →「恢复→777K→压缩」环。
                //
                //  用户语义（「仅恢复会话**本轮**」）：只删**目标之后**的会话内容重发，
                //  折叠区（更早轮次的压缩存档 + 摘要卡片）**一条都不动**。目标落在折叠区
                //  深处才把「最近一轮压缩吃掉的那一段」解开到目标为止，更早轮次仍保持折叠。
                //
                // 分层判定：marker 成对出现（marker + 紧随的摘要，见 commitCompaction），
                // 显示序中**最后一个** marker 即最近一次压缩的锚点。
                val markerIdx = all.mapIndexedNotNull { i, m ->
                    if (m.content == COMPACTION_MARKER) i else null
                }
                if (markerIdx.isEmpty()) {
                    // 理论上 hasFolded 不会在无 marker 时成立；兜底回到可见上下文。
                    finalMsgs = uiState.messages
                } else {
                    val last = markerIdx.last()
                    val targetIdx = all.indexOfFirst { it.id == messageId }
                    if (targetIdx < 0) {
                        // 目标不在历史里（理论不出现）：退回可见上下文。
                        finalMsgs = uiState.messages
                    } else if (targetIdx >= last) {
                        // 目标在**可见上下文**（最近一次压缩的 marker/摘要/尾部及更高处）：
                        // 折叠区（更早轮次）**一条都不展开**，摘要卡片原样保留；目标本身及
                        // 其后的回复/追问全部删掉（内容已回填输入框，重发时重新入队）。
                        val kept = all.take(targetIdx)
                        AppLogStore.info(
                            "上下文恢复",
                            "目标在可见上下文: 共${all.size}条 " +
                                "折叠保持${kept.count { it.folded }}条 删目标及其后${
                                    all.size - targetIdx
                                }条 保留${kept.size}条"
                        )
                        finalMsgs = kept
                    } else if (markerIdx.size >= 2 && option == RewindOption.RESTORE_CONVERSATION) {
                        // 目标落在折叠区、且有 ≥2 层：真回滚 = 撤销最近一次压缩并截断到目标。
                        // 时间线 新会话(M1) →压缩#1→ 会话2 →压缩#2→ 会话3：
                        //   · [marker1] 之前（M1/会话1）保持折叠；
                        //   · [marker1..目标前]（marker1+摘要1+尾部1+会话2新增到目标前）解为上下文；
                        //   · 目标及其后（含 marker2+摘要2+尾部2+会话3新增）整体删除。
                        val segStart = markerIdx[markerIdx.size - 2]
                        val rolledBack = all.mapIndexedNotNull { i, m ->
                            when {
                                i < segStart -> m                       // 会话1：保持折叠
                                i >= targetIdx -> null                   // 目标及其后：删除
                                else -> m.copy(folded = false)           // 会话2：展开为上下文
                            }
                        }
                        AppLogStore.info(
                            "上下文恢复",
                            "回滚到目标（撤销最近一次压缩并截断）: 共${all.size}条 marker=${markerIdx.size} " +
                                "保持折叠${segStart}条 展开到目标${targetIdx - segStart}条 删除${all.size - targetIdx}条"
                        )
                        finalMsgs = rolledBack
                    } else {
                        // 目标在折叠区、单层（无更早会话可回）或「恢复代码和会话」：
                        // 把「最近一段」解开**到目标为止**（更早轮次保持折叠），
                        // 删掉最近一次压缩的 marker+摘要 与目标及其后的全部内容。
                        val segStart = if (markerIdx.size >= 2) markerIdx[markerIdx.size - 2] + 2 else 0
                        val removable = hashSetOf<Long>().apply {
                            add(all[last].id)
                            all.getOrNull(last + 1)?.let { add(it.id) }
                        }
                        val unfolded = all.mapIndexedNotNull { i, m ->
                            when {
                                i < segStart -> m                        // 更早轮次：保持折叠
                                i >= targetIdx -> null                    // 目标及其后：删除
                                m.id in removable -> null                // 最近 marker+摘要：删除
                                else -> m.copy(folded = false)           // 最近一段：展开
                            }
                        }
                        AppLogStore.info(
                            "上下文恢复",
                            "展开最近一段到目标: 共${all.size}条 marker=${markerIdx.size} segStart=$segStart " +
                                "展开${unfolded.size - segStart}条 删除${all.size - unfolded.size}条"
                        )
                        finalMsgs = unfolded
                    }
                }
            } else {
                finalMsgs = uiState.messages.take(uiState.messages.indexOfFirst { it.id == messageId })
            }
            if (sys != null) finalMsgs = finalMsgs + sys
            uiState = uiState.copy(
                messages = finalMsgs,
                foldedMessages = emptyList(),
                prompt = target.content,
                attachments = emptyList()
            )
            // 重写 DB：单个事务清空并按最终消息顺序写回（含 sys）。
            if (option != RewindOption.RESTORE_CODE) {
                if (hasFolded) {
                    // 解除压缩：整体重写为平铺全量历史（自动丢掉 marker/摘要/通知，folded 已清）。
                    messageRepository.replaceAllMessages(cid, finalMsgs)
                } else {
                    rewriteContextPreservingFolded(cid, finalMsgs)
                }
                // 历史已截断 → 旧报告锚指向不存在的历史，必须作废并按新历史重算顶部数字，
                // 否则数字停在回退前的大值（用户反馈「恢复会话后上下文没降/不准」）。
                refreshContextAfterHistoryChange(cid, finalMsgs)
            } else {
                sys?.let { messageRepository.insert(cid, it) }
            }
        }
    }

    fun editMessage(messageId: Long, newContent: String) {
        // 无条件停止当前流（若 AI 正在回复也允许编辑重发），stopStreaming 后 isSending 会复位
        stopStreaming()
        val msgs = uiState.messages
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val target = msgs[idx]
        if (target.role != MessageRole.USER || newContent.isBlank()) return
        undoStack.add(msgs.toList())
        trimUndoStack()
        redoStack.clear()
        // 裁剪到被编辑消息之前，用新内容自动重新发起请求
        val trimmed = msgs.take(idx)
        uiState = uiState.copy(
            messages = trimmed,
            prompt = newContent,
            attachments = emptyList()
        )
        val cid = uiState.currentConversationId
        refreshContextAfterHistoryChange(cid, trimmed)
        viewModelScope.launch {
            rewriteContextPreservingFolded(cid, trimmed)
        }
        sendMessage()
    }

    fun deleteMessage(messageId: Long) {
        if (uiState.isSending) return
        stopStreaming()
        val msgs = uiState.messages.toMutableList()
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val target = msgs[idx]
        val removedIds = mutableListOf(messageId)
        msgs.removeAt(idx)
        // 保持 tool 调用与结果成对删除，避免历史出现孤儿导致 API 400
        if (target.role == MessageRole.ASSISTANT && target.toolName != null && target.toolCallId != null) {
            val rIdx = msgs.indexOfFirst { it.role == MessageRole.SYSTEM && it.toolCallId == target.toolCallId }
            if (rIdx >= 0) { removedIds.add(msgs[rIdx].id); msgs.removeAt(rIdx) }
        } else if (target.role == MessageRole.SYSTEM && target.toolCallId != null && target.toolResult != null) {
            val cIdx = msgs.indexOfFirst { it.role == MessageRole.ASSISTANT && it.toolName != null && it.toolCallId == target.toolCallId }
            if (cIdx >= 0) { removedIds.add(msgs[cIdx].id); msgs.removeAt(cIdx) }
        }
        uiState = uiState.copy(messages = msgs.toList())
        // 删除使历史变短 → 作废锚并按新历史重算顶部数字（否则数字停在删除前的大值）。
        refreshContextAfterHistoryChange(uiState.currentConversationId, msgs.toList())
        viewModelScope.launch {
            removedIds.forEach { messageRepository.deleteById(it) }
        }
    }

    /**
     * 会话标题生成：
     * - 首条用户消息起：调 LLM 用整段对话生成一句话短标题；更早之前用首条消息前 20 字占位。
     * - 之后（同一会话）不再重复生成，手动改名也会定稿。
     *
     * 【为什么不能只看 slot.messages】标题协程是异步的，读槽位那一刻内存可能正被 Room
     * 回流覆盖：切换模式会先插一条系统消息并落库，紧接着用户发言时，这条系统消息触发的
     * 回流（快照里还没有用户消息）会把槽位打回「只有系统消息」→ userTurns=0 → 直接放弃，
     * 标题永远停在新会话占位。故：
     * 1. 调用方传入本轮用户气泡补进快照；
     * 2. 快照里一条用户发言都没有时，回库重读一次（发送前用户消息已落库，库里才是真源）。
     *
     * 另外一律剔除 SYSTEM 消息：模式切换/命令回执不是对话内容，喂给标题模型只会把标题
     * 起成「切换股票模式」之类，且定稿后再不更新。
     */
    private suspend fun maybeGenerateTitle(
        conversationId: Long,
        pendingUserMessage: ChatMessageModel? = null
    ) {
        // P2b：标题属于「发起本轮的会话」，不能取当下前台（用户可能已切走）。
        val slot = slotOf(conversationId)
        val cid = conversationId

        var msgs = ConversationTitlePolicy.mergePending(slot.messages, pendingUserMessage)
        val hasUserTurn = { list: List<ChatMessageModel> ->
            ConversationTitlePolicy.userTurns(ConversationTitlePolicy.dialogOf(list)) > 0
        }
        if (!hasUserTurn(msgs)) {
            // 槽位内存被回流覆盖时以库为准（发送流程里用户消息已 await 落库）。
            val fromDb = runCatching { messageRepository.getMessages(cid) }.getOrNull().orEmpty()
            if (fromDb.isNotEmpty()) msgs = ConversationTitlePolicy.mergePending(fromDb, pendingUserMessage)
        }
        if (msgs.isEmpty()) return
        val dialog = ConversationTitlePolicy.dialogOf(msgs)
        if (dialog.isEmpty()) return

        val userTurns = ConversationTitlePolicy.userTurns(dialog)
        // 首条用户消息前：截取前 20 字占位标题（无对话可总结时保持截取）
        if (userTurns < ConversationTitlePolicy.SUMMARY_TURNS) {
            val title = ConversationTitlePolicy.placeholder(dialog) ?: return
            applyConversationTitle(cid, title)
            return
        }
        if (slot.titleFinalized) return
        // 先置位防同轮重入；失败（含调用方异常/取消）时复位，允许下一轮用户消息重试
        slot.titleFinalized = true

        uiState = uiState.copy(logs = uiState.logs + "正在由 AI 生成会话标题...")
        val conversationText = ConversationTitlePolicy.promptText(dialog)
        val prompt = "请为下面的用户与 AI 助手的对话生成一个简短标题（15 字以内，一句话概括主题，不要引号，不要任何解释）：\n\n$conversationText"
        // 标题生成：配置了「默认模型→起标题模型」时用专用模型，否则跟随当前聊天模型
        val titleProvider = resolveSpecialModel("title") ?: effectiveProviderFor(cid)
        val titleT0 = System.currentTimeMillis()
        var titleIn = 0
        var titleOut = 0
        var titleCache = 0
        val result = chatRepository.sendMessage(
            settingsFor(cid), prompt,
            forcedProvider = titleProvider,
            onTokens = { titleIn += it },
            onCompletionTokens = { titleOut += it },
            onCacheHit = { hit, tokens -> if (hit && tokens > 0) titleCache += tokens }
        )
        // 起标题也会真实消耗 token，同样进统计（kind=title）
        recordLlmCall("title", cid, titleProvider, titleIn, titleOut, titleCache, titleT0,
            (System.currentTimeMillis() - titleT0).toInt(), result.isSuccess, result.exceptionOrNull()?.message)
        // 【必过 stripThinkingBlocks】标题模型同样可能带 <thinking>（实测输出
        // `<thinking>We need answer title`），不剥就会被 take(30) 截进标题，会话列表里
        // 显示成 `<thinking>We need answer...`。与摘要（1441）/压缩摘要（5215）两处口径一致。
        val aiTitle = result.getOrNull()?.let { stripThinkingBlocks(it) }?.trim()
        if (!aiTitle.isNullOrBlank()) {
            val title = aiTitle.replace(Regex("""["'\n]+"""), " ").trim().take(30)
            applyConversationTitle(cid, title)
            // 落库定稿标志：跨重启不再重生成（原内存标志重启即丢）。
            slotOf(cid).titleFinalized = true
            viewModelScope.launch { conversationRepository.updateTitleFinalized(cid, true) }
            uiState = uiState.copy(logs = uiState.logs + "会话标题已由 AI 生成：$title")
        } else {
            // 失败：复位标志，让后续轮次重试（避免一条会话因单次网络抖动永久停留在截取标题）
            slot.titleFinalized = false
            val err = result.exceptionOrNull()
            val errText = err?.message?.takeIf { it.isNotBlank() } ?: err?.javaClass?.simpleName ?: "未知错误"
            // 保留截取标题作为兜底：从最后一条用户消息截取，避免会话无标题
            ConversationTitlePolicy.fallback(dialog)?.let { applyConversationTitle(cid, it) }
            uiState = uiState.copy(logs = uiState.logs + "AI 标题生成失败（$errText），暂用截取标题，后续轮次将重试")
        }
    }

    private suspend fun applyConversationTitle(cid: Long, title: String) {
        if (title.isBlank()) return
        uiState = uiState.copy(
            conversations = uiState.conversations.map {
                if (it.id == cid) it.copy(title = title) else it
            }
        )
        conversationRepository.updateTitle(cid, title)
    }

    private fun trimUndoStack() {
        while (undoStack.size > 25) undoStack.removeAt(0)
    }

    private fun trimRedoStack() {
        while (redoStack.size > 25) redoStack.removeAt(0)
    }

        fun clearRunLogs() {
        AppLogStore.clear()
        com.wy.openx.data.log.AILogger.clearLogs()
        showToast("已清空全部日志文件")
    }

    /** 导出运行日志到缓存目录并分享，便于排障。 */
    fun exportRunLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val target = File(appContext.cacheDir, "openx_logs_${System.currentTimeMillis()}.txt")
            val ok = AppLogStore.exportTo(target)
            withContext(Dispatchers.Main) {
                if (ok) {
                    runCatching {
                        com.wy.openx.data.util.ExportHelper.shareFile(appContext, target, "text/plain")
                    }.onFailure { showToast("分享失败：${it.message ?: "未知错误"}") }
                } else {
                    showToast("导出失败")
                }
            }
        }
    }

    /**
     * 弹窗里点「立即更新」后的下载与安装。
     *
     * 与关于页里那条路径共用 [UpdateChecker]，区别只在于这里不在页内展示进度，
     * 改用 Toast 告知开始与结果——用户可能正在任何页面，没有进度条可挂。
     */
    fun downloadAndInstall(release: com.wy.openx.update.UpdateChecker.Release) {
        if (updateDownloading) return
        if (!com.wy.openx.update.UpdateChecker.canInstall(appContext)) {
            showToast("请先允许本应用安装未知应用")
            com.wy.openx.update.UpdateChecker.requestInstallPermission(appContext)
            return
        }
        updateDownloading = true
        showToast("开始下载 v${release.versionName}…")
        viewModelScope.launch {
            val apk = com.wy.openx.update.UpdateChecker.download(appContext, release) { _, _ -> }
            updateDownloading = false
            if (apk == null) {
                showToast("下载失败，请检查网络后重试")
            } else {
                com.wy.openx.update.UpdateChecker.install(appContext, apk)
            }
        }
    }

    fun showToast(msg: String) {        toastJob?.cancel()
        uiState = uiState.copy(toast = msg)
        toastJob = viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            uiState = uiState.copy(toast = null)
        }
    }

    // ===== 数据备份 / 恢复 =====

    /** 备份进行中：设置页「备份数据」按钮据此禁用并显示「处理中…」。 */
    var backupBusy by mutableStateOf(false)
        private set

    /** 恢复进行中：设置页「恢复数据」按钮据此禁用并显示「处理中…」。 */
    var restoreBusy by mutableStateOf(false)
        private set

    /**
     * 设置「外部替换」版本号：仅在恢复备份后递增。
     *
     * 设置页把 AppSettings 拷进了 20+ 个 `remember` 本地状态，恢复后这些副本仍是旧值，
     * 用户随手点「保存设置」就会把旧设置写回去、静默撤销恢复结果。
     * 用一个只在恢复时变化的键触发设置页重新播种，既解决问题又不会在用户编辑途中被冲掉。
     */
    var settingsRevision by mutableStateOf(0)
        private set

    private val backupManager by lazy { com.wy.openx.data.backup.BackupManager(appContext, containerSettingsRepository) }

    /** 把当前全部数据写入 [out]（由调用方从 SAF 的 uri 打开流）。 */
    /**
     * 导出备份。
     * @param includeSections 只备份勾选的段（null = 全部）。
     * @param password 非空则用 AES-128-GCM 加密（PBKDF2 派生），留空则导出明文 zip。
     */
    fun exportBackup(
        out: java.io.OutputStream,
        includeSections: Set<String>? = null,
        password: String? = null,
        onResult: (com.wy.openx.data.backup.BackupOutcome) -> Unit
    ) {
        if (backupBusy) return
        // 勾了工作区就必须明文：整包是一个 zip，工作区在里面就必然是明文。
        // 与其悄悄忽略用户填的口令，不如明确不加密并在结果里说明。
        val includesWorkspace = includeSections?.contains(com.wy.openx.data.backup.BackupSections.WORKSPACE) == true
        val droppedPassword = includesWorkspace && !password.isNullOrBlank()
        val effectivePassword = if (includesWorkspace) null else password
        backupBusy = true
        viewModelScope.launch {
            val result = try {
                backupManager.export(out, includeSections, effectivePassword)
            } finally {
                runCatching { out.close() }
            }
            backupBusy = false
            onResult(
                if (droppedPassword) result.copy(message = result.message + "\n含工作区文件，本次未加密")
                else result
            )
        }
    }

    /**
     * 恢复前预检：只读清单，不写任何真实数据。
     * 加密包且 [password] 为空时回调 [com.wy.openx.data.backup.InspectOutcome.passwordRequired]，
     * UI 需弹口令框、**重新打开 SAF 流**后再带口令调一次（流不能回退）。
     */
    fun inspectBackup(
        input: java.io.InputStream,
        password: String? = null,
        onResult: (com.wy.openx.data.backup.InspectOutcome) -> Unit
    ) {
        viewModelScope.launch {
            val outcome = try {
                backupManager.inspect(input, password)
            } finally {
                runCatching { input.close() }
            }
            onResult(outcome)
        }
    }

    /**
     * 从备份恢复。[mode] 决定现有数据是清空后还原（覆盖）还是保留追加（合并）。
     * 恢复前先停止生成，避免流式回写与数据替换打架。
     * @param includeSections 想要恢复的段 id 白名单；null 表示恢复全部（覆盖/合并确认弹窗
     *   依用户勾选传入「自定义恢复」的段落）。
     */
    fun restoreBackup(
        input: java.io.InputStream,
        mode: com.wy.openx.data.backup.RestoreMode,
        includeSections: Set<String>? = null,
        password: String? = null,
        onResult: (com.wy.openx.data.backup.BackupOutcome) -> Unit
    ) {
        if (restoreBusy) return
        restoreBusy = true
        viewModelScope.launch {
            stopStreaming()
            val result = try {
                backupManager.restore(input, mode, includeSections, password)
            } finally {
                runCatching { input.close() }
            }
            if (result.ok) {
                // 顺序要紧：先同步设置（含 currentProject），再加载依赖设置的记忆——
                // loadMemory() 按 currentProject 决定项目记忆目录，设置落后一步会读到旧项目。
                runCatching {
                    val restored = settingsStore.settingsFlow.first()
                    uiState = uiState.copy(settings = restored)
                    com.wy.openx.tools.AppContextHolder.currentSettings = restored
                    loadMemory()
                    loadSkills()
                    loadMcpServers()
                    refreshProjects()
                    // 提供商 + 模型元数据也是内存态，需从恢复后的数据重新载入：
                    // 直接从 Room 重读一次，避免依赖 Flow 异步回灌的时序；元数据（能力/窗口）从文件重读。
                    providers = aiProviderRepository.getAllProvidersOnce()
                    providerModelMetadata = emptyMap()
                    loadCustomModelMetadata()
                    loadAllProviderModelMetadata()
                    resolveActiveProvider()
                    // 通知设置页重新播种本地状态（见 settingsRevision 注释）
                    settingsRevision++
                }
            }
            restoreBusy = false
            onResult(result)
        }
    }

    /**
     * 从服务器读项目规则文件（AGENTS.md 优先，CLAUDE.md 兜底）。
     *
     * 同步阻塞读一次：这是切项目/启动时读的小文件，且调用链上游都在后台线程。
     * 拿不到就当「没有规则」处理——不编也不回退到本地旧副本，那两者都会让模型基于错的规则行动。
     */
    private fun readRemoteProjectRule(containerDir: String): com.wy.openx.data.store.ProjectRules.Doc? =
        runCatching {
            kotlinx.coroutines.runBlocking {
                val bridge = com.wy.openx.remote.RemoteFileBridge()
                val rules = com.wy.openx.data.store.ProjectRules
                for (name in listOf(rules.AGENTS_FILE, rules.CLAUDE_FILE)) {
                    val path = "$containerDir/$name"
                    val stat = bridge.stat(path) ?: continue
                    if (stat.isDirectory) continue
                    val text = bridge.readText(path, rules.MAX_CHARS.toLong())
                    if (text.isBlank()) continue
                    return@runBlocking com.wy.openx.data.store.ProjectRules.Doc(
                        label = "项目",
                        fileName = name,
                        path = path,
                        text = text.take(rules.MAX_CHARS),
                    )
                }
                null
            }
        }.getOrNull()

    /**
     * 读取当前生效的规则文件：全局（`filesDir/openx/`）+ 当前项目根。
     *
     * 项目根的口径与工作区提示词完全一致（外部工作区 → 其宿主路径；内部项目 → workspace/projects/<名称>；
     * 未选项目 → 默认工作区根），否则会出现「提示词让它在 A 目录干活、规则却读的是 B」。
     */
    private fun loadProjectRuleDocs(): List<com.wy.openx.data.store.ProjectRules.Doc> {
        val activeProject = uiState.settings.currentProject.trim()
        val external = uiState.settings.externalWorkspaces.firstOrNull { it.name == activeProject }
        val projectRoot = when {
            external != null -> runCatching { java.io.File(external.path) }.getOrNull()
            activeProject.isNotEmpty() -> java.io.File(projectsDir(), activeProject)
            else -> com.wy.openx.container.Workspace.root(appContext)
        }
        val globalDir = java.io.File(appContext.filesDir, "openx")
        val docs = ArrayList<com.wy.openx.data.store.ProjectRules.Doc>(2)
        com.wy.openx.data.store.ProjectRules.read(globalDir, "全局")?.let { docs += it }

        // 「服务器为准」的项目：规则文件也从服务器读。
        // 读本地那份旧副本的话，模型会照着**过时的约定**干活——那比少一条规则更糟。
        val projectContainerPath = when {
            external != null -> null // 外部工作区没有对应的容器路径，远程模式暂不覆盖
            activeProject.isNotEmpty() ->
                "${com.wy.openx.container.Workspace.CONTAINER_PROJECTS_PATH}/$activeProject"
            else -> com.wy.openx.container.Workspace.CONTAINER_PATH
        }
        if (projectContainerPath != null &&
            com.wy.openx.remote.RemoteWorkspaceRegistry.isRemote(projectContainerPath)
        ) {
            readRemoteProjectRule(projectContainerPath)?.let { docs += it }
            return docs
        }

        if (projectRoot != null) {
            // 项目根就是全局目录时不重复注入同一份
            val sameAsGlobal = runCatching { projectRoot.canonicalPath == globalDir.canonicalPath }
                .getOrDefault(false)
            if (!sameAsGlobal) {
                com.wy.openx.data.store.ProjectRules.read(projectRoot, "项目")?.let { docs += it }
            }
        }
        return docs
    }

    /**
     * 记忆的提示词索引行：短条目原样，长条目截断并标注「已截断」。
     *
     * 阈值 [MEMORY_INLINE_MAX] 取 180 字符：覆盖绝大多数「一句话偏好」，又能拦住
     * 把整篇架构说明塞进记忆的情况。
     */
    private fun memoryIndexLine(item: com.wy.openx.data.store.MemoryItem): String {
        val flat = item.value.replace(Regex("""\s+"""), " ").trim()
        return if (flat.length <= MEMORY_INLINE_MAX) "- ${item.key}: $flat"
        else "- ${item.key}: ${flat.take(MEMORY_INLINE_MAX)}…（已截断）"
    }

    fun loadMemory() {
        val project = uiState.settings.currentProject.trim()
        // 首次/升级时把 APK 内置规则释放到 memory/agent/（幂等，按版本判断）
        memoryStore.seedBuiltinAgentRules()
        memoryStore.seedBuiltinDocs()
        uiState = uiState.copy(
            memoryItems = memoryStore.load(com.wy.openx.data.store.SCOPE_GLOBAL),
            projectMemoryItems = if (project.isEmpty()) emptyList()
                else memoryStore.load(com.wy.openx.data.store.SCOPE_PROJECT, project),
            // 合并四层（assets → 本地内置 → 全局自定义 → 项目自定义）且**不过滤开关**：
            // 设置页要看到全部；开关只在组装 system prompt 时过滤。
            agentRuleItems = memoryStore.loadAgentRules(project),
            knowledgeItems = knowledgeStore.loadAll()
        )
    }

    /** 重新扫描知识库（提炼 worker 落盘后回调它刷新 UI）。 */
    fun loadKnowledge() {
        uiState = uiState.copy(knowledgeItems = knowledgeStore.loadAll())
    }

    /** 管理页删除一条知识。 */
    fun deleteKnowledge(id: String) {
        if (id.isBlank()) return
        knowledgeStore.delete(id)
        loadKnowledge()
    }

    /**
     * 管理页保存一条知识：`id` 为空是新增，非空则覆盖那条（详情页的编辑入口走这条）。
     *
     * 编辑时不带来源：让 store 沿用原有 source，否则「从会话提炼来的」会被改写成「手动添加」，
     * 丢掉可回溯的信息。
     */
    fun saveKnowledge(id: String, title: String, body: String, tags: String) {
        if (title.isBlank() || body.isBlank()) return
        val list = tags.split(',', '，', ' ', '\u3000').map { it.trim() }.filter { it.isNotBlank() }
        knowledgeStore.save(id = id, title = title, tags = list, body = body)
        loadKnowledge()
    }

    /**
     * 从手机存储导入文档。
     *
     * @param replaceId 非空时用文件内容**替换这条已有知识的正文**（编辑态导入），标题与标签保留；
     *   为空则新建一条，标题取文件名。
     */
    fun importKnowledgeFromUri(uri: Uri, replaceId: String = "") {
        viewModelScope.launch {
            val name = queryDisplayName(uri)
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                showToast("导入失败：读不到内容（可能不是文本文件，或没有访问权限）")
                return@launch
            }
            if (text.contains('\u0000')) {
                showToast("导入失败：「$name」看起来不是文本文件")
                return@launch
            }
            val existing = if (replaceId.isNotBlank()) knowledgeStore.find(replaceId) else null
            // 标题优先取正文里的首个一级标题（文档自带的标题通常比文件名可读），没有才退回文件名。
            // 编辑态导入时沿用原条目标题，不拿文件里的标题覆盖。
            val importedTitle = h1TitleOf(text)
                ?: name.substringBeforeLast('.').trim().ifBlank { "导入的知识" }
            val entry = knowledgeStore.save(
                id = replaceId,
                title = existing?.title ?: importedTitle,
                tags = existing?.tags ?: emptyList(),
                body = text,
                source = "导入自文件 $name"
            )
            loadKnowledge()
            if (entry == null) showToast("导入失败：内容为空")
            else showToast("已导入知识「${entry.title}」（${entry.body.length} 字）")
        }
    }

    /** 从 content uri 查显示文件名；查不到就给个兜底名。 */
    private fun queryDisplayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull().orEmpty().ifBlank { "导入的知识" }

    /**
     * 取正文里的首个一级标题（`# xxx`）当导入标题。
     *
     * 文档自带的 H1 通常比文件名可读（`build-android-app.md` → 「在容器中编译 Android 应用方法」）。
     * 只认 `# `（不匹配 `##`），取不到就返回 null，交给调用方用文件名兜底。
     */
    private fun h1TitleOf(text: String): String? = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("# ") && it.length > 2 }
        ?.removePrefix("# ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(80)

    /**
     * 把一轮已成功的对话投递给知识提炼 worker。
     *
     * 只在开启「知识提炼」时动手；文本取「本轮提问 + 助手回复」，攒够设置的轮数由 worker 合并成
     * 一次请求。提炼在独立线程串行执行，失败只记日志，**不会影响对话本身**。
     */
    private fun enqueueKnowledgeExtraction(conversationId: Long, assistantText: String) {
        val s = uiState.settings
        if (!s.knowledgeExtractEnabled) return
        if (conversationId <= 0L || assistantText.isBlank()) return
        val model = resolveKnowledgeModel() ?: return
        val userText = uiState.messages.lastOrNull {
            it.role == MessageRole.USER && it.toolName == null
        }?.content.orEmpty()
        val title = uiState.conversations.firstOrNull { it.id == conversationId }?.title.orEmpty()
        val transcript = buildString {
            if (userText.isNotBlank()) append("用户：").append(userText).append("\n\n")
            append("助手：").append(assistantText)
        }
        com.wy.openx.data.knowledge.KnowledgeWorker.onTurnFinished(
            conversationKey = conversationId.toString(),
            conversationTitle = title,
            transcript = transcript,
            everyN = s.knowledgeExtractEveryN,
            model = model
        )
    }

    /** 解析知识提炼用的模型：设置了专用模型就用它，否则跟随当前聊天模型。 */
    private fun resolveKnowledgeModel(): com.wy.openx.data.knowledge.KnowledgeWorker.ModelRef? {
        val special = resolveSpecialModel("knowledge")
        if (special != null) {
            val key = special.firstUsableApiKey
            val name = special.effectiveModel
            if (key.isNotBlank() && name.isNotBlank() && special.baseUrl.isNotBlank()) {
                return com.wy.openx.data.knowledge.KnowledgeWorker.ModelRef(special.baseUrl, key, name)
            }
        }
        val active = activeProvider() ?: return null
        val key = active.firstUsableApiKey
        val name = active.effectiveModel
        if (key.isBlank() || name.isBlank() || active.baseUrl.isBlank()) return null
        return com.wy.openx.data.knowledge.KnowledgeWorker.ModelRef(active.baseUrl, key, name)
    }

    /** 保存记忆。scope="project" 时写入当前项目的项目记忆目录。 */
    fun saveMemory(key: String, value: String, type: String, scope: String = com.wy.openx.data.store.SCOPE_GLOBAL) {
        memoryStore.add(
            key, value, type,
            scope = scope,
            project = uiState.settings.currentProject.trim()
        )
        loadMemory()
        showToast("记忆已保存")
    }

    fun deleteMemory(key: String, type: String, scope: String = com.wy.openx.data.store.SCOPE_GLOBAL) {
        memoryStore.delete(key, type, scope, uiState.settings.currentProject.trim())
        loadMemory()
        showToast("记忆已删除")
    }

    fun clearMemory(scope: String = com.wy.openx.data.store.SCOPE_GLOBAL) {
        memoryStore.clear(scope, uiState.settings.currentProject.trim())
        loadMemory()
        showToast("所有记忆已清除")
    }

    fun openMemoryManage() { screen = AppScreen.MemoryManage; loadMemory() }

    // ---- 提示词子页（设置 → 提示词）：内置规则的查看 / 编辑 / 启停 ----

    /** 打开「提示词」子页；顺带重载一次（规则可能刚被 seed 刷新）。返回键回设置页。 */
    fun openPrompts() { screen = AppScreen.Prompts; loadMemory() }

    /**
     * 总开关：关掉后**全部**内置规则不再注入系统提示词（文件保留、设置页仍可见，随时可开回）。
     * 注入点在 `buildTurnHistory` 的规则段，因此改动对**下一条消息**即生效。
     */
    fun setAgentRulesEnabled(enabled: Boolean) {
        updateSettings(uiState.settings.copy(agentRulesEnabled = enabled))
    }

    /** 单条开关：把 [key]（文件名去 `.md`）加入/移出停用集合。 */
    fun toggleAgentRuleEnabled(key: String, enabled: Boolean) {
        val disabled = uiState.settings.agentRuleDisabled
        val next = if (enabled) disabled - key else disabled + key
        updateSettings(uiState.settings.copy(agentRuleDisabled = next))
    }

    /**
     * 保存规则改动 → 写进自定义覆盖层（不碰内置层，APK 升级不会覆盖这次改动）。
     *
     * @param toProject true 时写**项目层**（只对当前项目生效，优先级高于全局自定义）。
     */
    fun saveAgentRule(key: String, body: String, toProject: Boolean = false) {
        val project = uiState.settings.currentProject.trim()
        val scopeProject = if (toProject && project.isNotEmpty()) project else null
        memoryStore.saveAgentRule("$key.md", body, scopeProject)
        loadMemory()
        showToast(if (scopeProject != null) "已保存为项目提示词" else "已保存为全局提示词")
    }

    /**
     * 新建一条提示词片段（设置页右上角「+」）。
     *
     * 只写自定义层 `agent-custom/NN-名称.md`：内置层是 APK 资源，写它会在下次升级被 seed 覆盖。
     * 编号必须唯一（[AgentRules.merge] 按编号判定身份），重复则拒绝并提示。
     *
     * @return 是否创建成功。
     */
    fun createAgentRule(number: Int, name: String, body: String, toProject: Boolean = false): Boolean {
        val cleanName = com.wy.openx.data.store.AgentRules.sanitizeName(name)
        if (cleanName.isBlank() || body.isBlank()) {
            showToast("名称和正文不能为空")
            return false
        }
        if (uiState.agentRuleItems.any { it.number == number }) {
            showToast("编号 %02d 已被占用".format(number))
            return false
        }
        val key = "%02d-%s".format(number, cleanName)
        val project = uiState.settings.currentProject.trim()
        val scopeProject = if (toProject && project.isNotEmpty()) project else null
        memoryStore.saveAgentRule("$key.md", body, scopeProject)
        loadMemory()
        showToast(if (scopeProject != null) "已创建项目提示词" else "已创建全局提示词")
        return true
    }

    /**
     * 删除某一层的自定义文件：项目层 → 回落到全局/内置；全局层 → 回落到内置。
     * 自建片段（没有更低层）删完即从列表消失。
     */
    fun resetAgentRule(key: String, source: com.wy.openx.data.store.AgentRuleSource) {
        val rule = uiState.agentRuleItems.firstOrNull { it.key == key }
        val project = uiState.settings.currentProject.trim()
        memoryStore.resetAgentRule(
            "$key.md", source,
            project.takeIf { it.isNotEmpty() }
        )
        loadMemory()
        showToast(
            when {
                rule?.hasBuiltin == false -> "已删除自建片段"
                source == com.wy.openx.data.store.AgentRuleSource.PROJECT -> "已删除项目层，回落到上一层"
                else -> "已恢复内置原文"
            }
        )
    }

    // ---- 工作流管理页（WorkflowStore / WorkflowEngine 本来就有，这里只补 UI 侧接线） ----

    fun loadWorkflows() {
        // 产物目录预建：writeFile 自己会 mkdirs，但预建能让空目录也在文件浏览里可见
        runCatching {
            com.wy.openx.container.Workspace.workflowsDir(
                appContext, uiState.settings.currentProject.trim().ifEmpty { null }
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val list = workflowStore.load()
            withContext(Dispatchers.Main) { uiState = uiState.copy(workflows = list) }
        }
    }

    /**
     * 新增或覆盖工作流。沿用 [com.wy.openx.data.store.WorkflowStore.add] 的语义——**同名即覆盖**，
     * 所以「新建」和「保存修改」可以走同一条路径，不必再问用户一次。
     */
    fun saveWorkflow(workflow: com.wy.openx.data.store.Workflow, existed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            workflowStore.add(workflow)
            withContext(Dispatchers.Main) {
                loadWorkflows()
                showToast(if (existed) "工作流已保存" else "工作流已创建")
            }
        }
    }

    fun deleteWorkflow(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workflowStore.delete(name)
            withContext(Dispatchers.Main) {
                loadWorkflows()
                showToast("工作流已删除")
            }
        }
    }

    /**
     * 执行工作流，**结果回聊天页**展示。
     *
     * 不在管理页里自己渲染结果：工作流本质是一串工具调用，放到聊天页才能看到逐步输出，
     * 也能就结果继续追问。执行期间标记 [uiState] 的 runningWorkflowName，避免重复触发。
     */
    fun runWorkflow(workflow: com.wy.openx.data.store.Workflow) {
        if (uiState.runningWorkflowName != null) {
            showToast("已有工作流在执行，请稍候")
            return
        }
        val wf = workflow
        uiState = uiState.copy(runningWorkflowName = wf.name)
        viewModelScope.launch {
            try {
                val gate = buildWorkflowStepGate(uiState.currentConversationId)
                val result = withContext(Dispatchers.IO) {
                    com.wy.openx.tools.WorkflowEngine(toolRegistry, gate).execute(wf)
                }
                val msg = ChatMessageModel(
                    id = nextMessageId.incrementAndGet(),
                    role = MessageRole.SYSTEM,
                    author = "工作流",
                    time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                    content = "已执行工作流「${wf.name}」\n\n$result"
                )
                uiState = uiState.copy(messages = uiState.messages + msg)
                // 必须落库：只塞进 uiState.messages 的话，切会话/进程被杀后这次报告就没了
                val cid = uiState.currentConversationId
                if (cid >= 0) {
                    runCatching { messageRepository.insert(cid, msg) }
                        .onFailure { Log.w("MainViewModel", "工作流结果落库失败", it) }
                }
                openConsole()
            } finally {
                // 引擎内部已兜住 Exception，这里防的是取消等异常路径把转圈卡死
                if (uiState.runningWorkflowName == wf.name) {
                    uiState = uiState.copy(runningWorkflowName = null)
                }
            }
        }
    }

    fun loadSkills() {
        val project = uiState.settings.currentProject.trim()
        uiState = uiState.copy(
            skills = skillStore.load(),
            projectSkills = if (project.isEmpty()) emptyList()
                else skillStore.loadProject(project)
        )
    }

    /** scope="project" 时写入当前项目的项目技能目录。 */
    fun saveSkill(name: String, description: String, instructions: String, scope: String = com.wy.openx.data.store.SCOPE_GLOBAL) {
        skillStore.add(name, description, instructions, scope, uiState.settings.currentProject.trim())
        loadSkills()
        showToast("技能已保存")
    }

    fun deleteSkill(name: String, scope: String = com.wy.openx.data.store.SCOPE_GLOBAL) {
        skillStore.delete(name, scope, uiState.settings.currentProject.trim())
        loadSkills()
        showToast("技能已删除")
    }

    fun toggleSkill(name: String, enabled: Boolean, scope: String = com.wy.openx.data.store.SCOPE_GLOBAL) {
        skillStore.setEnabled(name, enabled, scope, uiState.settings.currentProject.trim())
        loadSkills()
    }

    fun openSkillManage() { screen = AppScreen.SkillManage; loadSkills() }

    // ============================================================ [RUNTIME-TOOLS] 运行环境
    //
    // Operit 插件（如 sidebar_opencode）的安装脚本依赖 node→pnpm/corepack→opencode 这条链。
    // 裸容器里这些都没有，脚本会死在 exit 11/12。这里给用户一个可视化的安装入口：
    // 检测在容器内逐项跑 --version，缺失项用 apk add（Alpine 官方源）补齐，
    // pnpm 用 corepack 激活 —— 与插件安装脚本自身的兑底逻辑一致。

    /** 单个运行时工具的检测/安装状态。 */
    data class RuntimeToolState(
        val key: String,
        val label: String,
        /** 已安装时的版本号（如 v18.19.1）；未安装为 null。 */
        val version: String? = null,
        val installing: Boolean = false,
        /** 正在卸载（与 [installing] 分开，UI 文案与按钮状态不同）。 */
        val uninstalling: Boolean = false,
        /** 最近一次安装的结果文案（成功/失败原因）。 */
        val message: String? = null,
        val lastOk: Boolean = false,
        /** 是否给卸载入口（node/npm/corepack 是手 cp 的，删了会弄坏下游，不给）。 */
        val uninstallable: Boolean = false,
        /**
         * 上游有更高版本时的版本号（null = 无更新 / 没查到 / 该项不查）。
         * 只对 npm 系工具（pnpm / opencode / dsh）查 —— 它们重装拉的就是 latest，
         * 点一下就能升；node 的安装脚本固定版本号，提示了也升不了，所以不查。
         */
        val updateAvailable: String? = null
    )

    val runtimeTools = mutableStateOf<List<RuntimeToolState>>(emptyList())
    private var runtimeDetecting = false

    /** npm 源：pnpm/corepack 与 opencode 安装走这里（默认 npmmirror，国内速度可用）。 */
    val npmRegistryId = mutableStateOf(com.wy.openx.container.NpmRegistries.NPMMIRROR_ID)

    init {
        // 订阅必须在声明之后：原先写在构造函数开头的 init 块里（782 行），而委托属性的
        // 合成字段要等执行到 5599 行才赋值 —— Dispatchers.Main.immediate 不切线程，
        // DataStore 首值就绪时 emit 会同步在构造函数栈内执行，直接对 null 的
        // MutableState 调 setValue → NPE（设备上偶发、模拟器上难复现，取决于
        // DataStore 读盘是否快于到达 5599 行）。其他同类 collect 也应保持此规则。
        viewModelScope.launch {
            containerSettingsRepository.npmRegistryIdFlow.collect { npmRegistryId.value = it }
        }
    }

    fun setNpmRegistry(id: String) {
        npmRegistryId.value = id
        viewModelScope.launch { containerSettingsRepository.setNpmRegistryId(id) }
    }

    /**
     * 运行时工具定义。[uninstall] 为 null 表示不支持卸载（如 node 是手动 cp 进 /usr/bin 的，
     * 删了会把 npm/pnpm 一起弄坏，只提供重装）。
     */
    private data class RuntimeToolDef(
        val key: String,
        val label: String,
        val probe: String,
        /** npm 全局包名（卸载用）；apk 包名单独给。 */
        val npmPackage: String? = null,
        /** apk/apt 包名（卸载用）。 */
        val systemPackage: String? = null,
        /** 是否有卸载入口。 */
        val uninstallable: Boolean = true
    )

    /**
     * DeepSeek Harness 的安装目录。
     *
     * 插件 `com.operit.sidebar_deepseek_harness` 把 dsh 装在这里的 `node_modules/.bin/dsh`
     * （不是全局安装，所以 `dsh` 不在 PATH 里）——检测必须查这个路径，否则用户在插件页
     * 装好了，设置页却显示「未安装」。
     */
    private val DSH_RUNTIME_DIR = "/root/sidebar_deepseek_harness"

    /** 检测 dsh：插件目录优先，其次全局（兼容用户自行 `npm i -g` 的情况）。 */
    private val DSH_PROBE =
        "( $DSH_RUNTIME_DIR/node_modules/.bin/dsh --version 2>/dev/null || dsh --version 2>/dev/null ) || true"

    /**
     * 检测 JDK。
     *
     * `java -version` 输出到 **stderr**，所以带 `2>&1`；再抽出引号里的版本号把整行压成
     * `OpenJDK 17.0.13`——原始行带着 build 号与日期，列表里太长。
     * 先查 PATH，再查 `default-java`：某些镜像只装了 JVM 没把 java 链到 /usr/bin。
     */
    private val JDK_PROBE =
        "( ( command -v java >/dev/null 2>&1 && java -version 2>&1 | head -1 ) || " +
            "( [ -x /usr/lib/jvm/default-java/bin/java ] && /usr/lib/jvm/default-java/bin/java -version 2>&1 | head -1 ) ) " +
            "| sed 's/.*version \"\\([^\"]*\\)\".*/\\1/' || true"

    /** 检测命令 + 安装/卸载包名。pnpm 走 npm 全局（corepack 布局不兼容），对齐插件脚本。 */
    private val runtimeToolDefs = listOf(
        RuntimeToolDef("node", "Node.js", "node --version 2>/dev/null || true", uninstallable = false),
        RuntimeToolDef("npm", "npm", "npm --version 2>/dev/null || true", uninstallable = false),
        RuntimeToolDef("corepack", "Corepack", "corepack --version 2>/dev/null || true", uninstallable = false),
        RuntimeToolDef("pnpm", "pnpm", "pnpm --version 2>/dev/null || true", npmPackage = "pnpm"),
        RuntimeToolDef("opencode", "OpenCode", "opencode --version 2>/dev/null || true", npmPackage = "opencode-ai"),
        RuntimeToolDef("dsh", "DeepSeek Harness", DSH_PROBE, npmPackage = "@deepseek-ai/dsh"),
        RuntimeToolDef("git", "Git", "git --version 2>/dev/null || true", systemPackage = "git"),
        RuntimeToolDef("unzip", "Unzip", "unzip -v 2>/dev/null | head -1 || true", systemPackage = "unzip"),
        RuntimeToolDef("jdk", "JDK 17（Java 运行时）", JDK_PROBE)
    )

    /**
     * 逐项检测容器内运行时工具版本。容器未就绪时给出引导文案而不是静默空列表。
     */
    fun detectRuntimeTools() {
        if (runtimeDetecting) return
        runtimeDetecting = true
        viewModelScope.launch {
            if (!containerInstalled) {
                runtimeTools.value = runtimeToolDefs.map { d ->
                    RuntimeToolState(d.key, d.label, message = "容器未初始化，先打开终端页完成初始化", uninstallable = d.uninstallable)
                }
                runtimeDetecting = false
                return@launch
            }
            // 先按「未检测」占位，UI 立即有行；结果逐项回填
            runtimeTools.value = runtimeToolDefs.map { RuntimeToolState(it.key, it.label, uninstallable = it.uninstallable) }
            // 本地版本号先收起来，循环结束后再并发问上游
            val installedLocal = mutableMapOf<String, String>()
            for (def in runtimeToolDefs) {
                val key = def.key
                val out = withContext(Dispatchers.IO) {
                    runCatching { containerEngine.runCommandSync(def.probe, null, 10_000) }.getOrDefault("")
                }
                // 探测命令恒退出 0（|| true），版本号取首行非空文本；拿不到就是未安装
                val version = out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.startsWith("/bin/sh") && !it.contains("not found") }
                val cur = runtimeTools.value.firstOrNull { it.key == key }
                if (cur != null) {
                    runtimeTools.value = runtimeTools.value.map {
                        if (it.key == key) it.copy(version = version) else it
                    }
                }
                if (version != null && def.npmPackage != null) installedLocal[key] = version
            }
            // 逐项回填完再问上游：网络慢也不拖慢上面的显示；查不到（离线/源不可用）
            // 就当没有更新，绝不能让提示把检测本身搞失败。
            if (installedLocal.isNotEmpty()) {
                val outdated = coroutineScope {
                    installedLocal.map { (key, local) ->
                        val pkg = runtimeToolDefs.first { it.key == key }.npmPackage!!
                        async {
                            val upstream = fetchNpmLatest(pkg)
                            if (upstream != null && isNewerVersion(upstream, local)) key to upstream else null
                        }
                    }.awaitAll().filterNotNull()
                }
                runtimeTools.value = runtimeTools.value.map { s ->
                    s.copy(updateAvailable = outdated.firstOrNull { it.first == s.key }?.second)
                }
            }
            runtimeDetecting = false
        }
    }

    /**
     * 安装单个运行时工具。按容器实际包管理器分流（apk=Alpine / apt=Ubuntu、Debian）；
     * apt 系的 node/npm/corepack 走 nodejs.org 官方二进制（经 npmmirror 镜像）——仓库版本
     * 太老（Ubuntu 22.04 是 node 12，opencode 需要 18+）且不带 corepack，官方二进制一次装齐。
     *
     * dsh 特殊：插件的安装目录是 [DSH_RUNTIME_DIR]（pnpm add --prod 到该目录的
     * node_modules），与全局安装是两份。这里也装到同一个目录，保证设置页与插件共用一份。
     */
    /**
     * 问 npm 源要某个包的最新版本号（走用户在运行环境页选的源）。
     * 只服务于「可更新」提示；查不到就返回 null —— 网络问题不该让检测失败。
     */
    private suspend fun fetchNpmLatest(pkg: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val base = com.wy.openx.container.NpmRegistries.byId(npmRegistryId.value).url.trimEnd('/')
            val conn = (java.net.URL("$base/$pkg/latest").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) {
                    null
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    com.google.gson.JsonParser.parseString(body).asJsonObject
                        .get("version")?.takeIf { !it.isJsonNull }?.asString
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * 上游版本是否比本地新。
     *
     * 按 semver 比主体三段数字；主体相同时要比预发布号 —— dsh 现在发的是
     * `0.2.2-rc.1` 这种，只看主体会把 rc.1 → rc.2 的升级当成「已是最新」。
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val pattern = Regex("""(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?""")
        fun parse(raw: String): Pair<List<Int>, String>? = pattern.find(raw)?.let { m ->
            listOf(
                m.groupValues[1].toIntOrNull() ?: 0,
                m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].ifBlank { "0" }.toIntOrNull() ?: 0
            ) to m.groupValues[4]
        }
        val (upCore, upPre) = parse(latest) ?: return false
        val (localCore, localPre) = parse(current) ?: return false
        for (i in 0..2) if (upCore[i] != localCore[i]) return upCore[i] > localCore[i]
        if (upPre == localPre) return false
        // 主体相同：正式版高于预发布版
        if (upPre.isEmpty()) return true
        if (localPre.isEmpty()) return false
        val up = upPre.split('.')
        val local = localPre.split('.')
        for (i in 0 until maxOf(up.size, local.size)) {
            val a = up.getOrNull(i) ?: return false
            val b = local.getOrNull(i) ?: return true
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            if (an != null && bn != null) {
                if (an != bn) return an > bn
            } else if (a != b) {
                return a > b
            }
        }
        return false
    }

    fun installRuntimeTool(key: String) {
        val def = runtimeToolDefs.firstOrNull { it.key == key } ?: return
        viewModelScope.launch {
            val idx = runtimeTools.value.indexOfFirst { it.key == key }
            if (idx < 0) return@launch
            fun patch(s: RuntimeToolState) {
                runtimeTools.value = runtimeTools.value.map { if (it.key == key) s else it }
            }
            patch(runtimeTools.value[idx].copy(installing = true, message = null))
            val regUrl = com.wy.openx.container.NpmRegistries.byId(npmRegistryId.value).url
            // node 官方二进制镜像：npmmirror 有同步；选官方源时直连 nodejs.org。
            val nodeBinBase = if (npmRegistryId.value == com.wy.openx.container.NpmRegistries.OFFICIAL_ID)
                "https://nodejs.org/dist" else "https://registry.npmmirror.com/-/binary/node"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 同一个容器不会两个都有；一次性探测，后面各分支复用
                    val isApt = containerEngine.runCommandSync(
                        "command -v apk >/dev/null 2>&1 && echo apk || echo apt", null, 10_000
                    ).trim().endsWith("apt")
                    // 装 node（含 npm、corepack）。
                    // 关键：cp 到 /usr/bin + /usr/lib —— proot 容器的 PATH 是
                    // `/usr/bin:/bin:/usr/sbin:/sbin`，**不含 /usr/local/bin**，
                    // 装到 /usr/local 后 node 仍解析到旧版（这就是"装完还是旧版本"的原因）。
                    // npm/corepack 是指向 ../lib/node_modules 的符号链接，所以 lib 也必须落到 /usr/lib。
                    suspend fun installNode() {
                        if (isApt) {
                            // 下载需要 curl + xz；Ubuntu 基础镜像往往两个都没有
                            containerEngine.runCommandSync(
                                "( command -v curl >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq curl) ) && " +
                                    "( command -v xz >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq xz-utils) )",
                                null, 300_000
                            )
                            // Node 24 LTS。dsh 声明 engines: node >=24.9.0，且 import.meta.main
                            // （Node 22.18 引入）在 20.x 下求值为 undefined，会让 CLI 静默不执行。
                            val v = "24.22.3"
                            val arch = if (android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("x86") == true) "x64" else "arm64"
                            containerEngine.runCommandSync(
                                "cd /tmp && rm -rf node-dl && mkdir node-dl && cd node-dl && " +
                                    "curl -fsSL $nodeBinBase/v$v/node-v$v-linux-$arch.tar.xz -o node.tar.xz && " +
                                    "tar -xJf node.tar.xz && " +
                                    // 先清掉旧 npm / corepack：`cp -rf` 是合并覆盖，不删旧文件，
                                    // 旧版独有的文件会留在新版的扁平依赖里，出现两套版本同时生效
                                    // （实测 minipass@7 配 minipass-pipeline@1.2.4，一 require 就
                                    // Class extends ... is not a constructor，npm 整个装不了包）。
                                    // 整个 node_modules 不能删：里面还躺着用户的 pnpm/opencode 等全局包。
                                    "rm -rf /usr/lib/node_modules/npm /usr/lib/node_modules/corepack && " +
                                    "cp -rf node-v$v-linux-$arch/bin/* /usr/bin/ && " +
                                    "cp -rf node-v$v-linux-$arch/lib/* /usr/lib/ && " +
                                    "cd /tmp && rm -rf node-dl && hash -r",
                                null, 900_000
                            )
                        } else {
                            containerEngine.runCommandSync("apk add --no-cache nodejs npm corepack", null, 300_000)
                        }
                    }
                    // node 主版本 >= 18 才算可用（pnpm/opencode 都要求）
                    suspend fun nodeUsable(): Boolean {
                        val major = containerEngine.runCommandSync("node --version 2>/dev/null", null, 10_000)
                            .trim().removePrefix("v").substringBefore('.').toIntOrNull()
                        return major != null && major >= 18
                    }
                    // proot 容器里 npm 解不出「带大 native 二进制的平台包」：
                    // opencode-linux-arm64（存在包里 212MB）、@pnpm/exe.linux-arm64（58MB）
                    // 解包后留下的是指向不存在的 .l2s.* 的悬空软链，装完命令照旧 command not found。
                    // 这是 npm 的 tar 解包与 proot link2symlink 扩展相互作用的结果，
                    // npm 层（--force / --allow-scripts）都修不了，只能装完按包名单独拉 tgz
                    // 解出真实二进制，覆盖那层固定名的中间软链（.l2s.* 是 proot 动态分配的，不能写死）。
                    suspend fun repairNativeBinary(
                        pkgName: String, verFrom: String, innerSrc: String, dest: String
                    ): Boolean {
                        val probe = "[ -x '$dest' ] && echo ok || true"
                        if (containerEngine.runCommandSync(probe, null, 10_000).contains("ok")) return true
                        val ver = containerEngine.runCommandSync(
                            "node -p \"require('$verFrom/package.json').version\" 2>/dev/null", null, 10_000
                        ).trim()
                        if (ver.isEmpty()) return false
                        val short = pkgName.substringAfterLast('/')
                        containerEngine.runCommandSync(
                            "cd /tmp && rm -rf nbfix && mkdir nbfix && cd nbfix && " +
                                "curl -fsSL '$regUrl/$pkgName/-/$short-$ver.tgz' -o p.tgz && " +
                                "tar -xzf p.tgz && rm -f '$dest' && cp -f '$innerSrc' '$dest' && " +
                                "chmod +x '$dest' && cd /tmp && rm -rf nbfix",
                            null, 900_000
                        )
                        return containerEngine.runCommandSync(probe, null, 10_000).contains("ok")
                    }
                    when (key) {
                        "node", "npm", "corepack" -> installNode()
                        "pnpm" -> {
                            // 不用 corepack：0.29.4 不认 pnpm 12.x 的新包布局（无 bin/pnpm.cjs），
                            // 报 Cannot find matching keyid / Cannot find module。直接 npm 全局装。
                            if (!nodeUsable()) installNode()
                            // corepack 留下的 /usr/bin/pnpm shim 会让 npm 报 File exists → 先删
                            containerEngine.runCommandSync("rm -f /usr/bin/pnpm /usr/bin/pnpx /usr/bin/yarn /usr/bin/yarnpkg", null, 10_000)
                            containerEngine.runCommandSync(
                                "npm install -g pnpm@latest --registry=$regUrl --force", null, 600_000
                            )
                            // pnpm 12.x 自身只是个壳，真正的可执行文件在 @pnpm/exe.<platform> 里
                            repairNativeBinary(
                                "@pnpm/exe.linux-arm64",
                                "/usr/lib/node_modules/pnpm/node_modules/@pnpm/exe.linux-arm64",
                                "package/pnpm",
                                "/usr/lib/node_modules/pnpm/pnpm"
                            )
                        }
                        "opencode" -> {
                            if (!nodeUsable()) installNode()
                            // 旧版 opencode 已存在会报 File exists → --force 覆盖
                            containerEngine.runCommandSync(
                                "npm install -g opencode-ai@latest --registry=$regUrl --force", null, 900_000
                            )
                            // opencode-ai 的 bin/opencode.exe 是软链，指向平台包里的二进制
                            repairNativeBinary(
                                "opencode-linux-arm64",
                                "/usr/lib/node_modules/opencode-ai/node_modules/opencode-linux-arm64",
                                "package/bin/opencode",
                                "/usr/lib/node_modules/opencode-ai/bin/opencode.exe"
                            )
                        }
                        "dsh" -> {
                            if (!nodeUsable()) installNode()
                            // dsh 声明 engines: node >=24.9.0（见 installNode 的版本说明），
                            // 低于此版本装了也跑不起来，直接给出可操作的提示而不是静默失败。
                            val major = containerEngine.runCommandSync("node --version 2>/dev/null", null, 10_000)
                                .trim().removePrefix("v").substringBefore('.').toIntOrNull() ?: 0
                            if (major < 24) {
                                return@runCatching false to "需要 Node.js 24+（当前 v$major），请先安装 Node.js"
                            }
                            // node-pty 没有 Android arm64 预编译包，需要现场编译；
                            // build-essential 缺失时 pnpm 会在 lifecycle script 阶段失败。
                            if (isApt) {
                                containerEngine.runCommandSync(
                                    "command -v cc >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq build-essential)",
                                    null, 900_000
                                )
                            } else {
                                containerEngine.runCommandSync(
                                    "command -v cc >/dev/null 2>&1 || apk add --no-cache build-base python3 make g++",
                                    null, 900_000
                                )
                            }
                            // 装到插件同一目录（[DSH_RUNTIME_DIR]），不装全局：
                            // 插件读的就是这里的 node_modules/.bin/dsh，装全局会变成两份互不相认。
                            // 没有 package.json 时先 pnpm init（与插件脚本一致）。
                            // --registry 显式给：容器里没配全局 npmrc，不指定就走 npmjs.org（国内极慢）。
                            containerEngine.runCommandSync(
                                "mkdir -p $DSH_RUNTIME_DIR && cd $DSH_RUNTIME_DIR && " +
                                    "( [ -f package.json ] || pnpm init >/dev/null 2>&1 || echo '{\"name\":\"sidebar_deepseek_harness\",\"private\":true}' > package.json ) && " +
                                    "pnpm add --prod @deepseek-ai/dsh@latest --registry=$regUrl --reporter=append-only 2>&1 | tail -20",
                                null, 1_800_000
                            )
                            // 再跑一次 install 让 lifecycle 脚本（node-pty）真正编译
                            containerEngine.runCommandSync(
                                "cd $DSH_RUNTIME_DIR && pnpm install --prod --registry=$regUrl --reporter=append-only 2>&1 | tail -20",
                                null, 1_800_000
                            )
                        }
                        "git" -> containerEngine.runCommandSync(
                            if (isApt) "apt-get update -qq && apt-get install -y -qq git" else "apk add --no-cache git",
                            null, 600_000
                        )
                        "unzip" -> containerEngine.runCommandSync(
                            if (isApt) "apt-get update -qq && apt-get install -y -qq unzip" else "apk add --no-cache unzip",
                            null, 300_000
                        )
                        "jdk" -> {
                            // 只装 headless：插件跑的是预编译 jar，不需要图形栈。
                            // apt 装出来是 java-17-openjdk-arm64（插件直接认）；
                            // apk 装出来是 java-17-openjdk（不带 -arm64），插件认不到。
                            containerEngine.runCommandSync(
                                if (isApt) "apt-get update -qq && apt-get install -y -qq openjdk-17-jdk-headless"
                                else "apk add --no-cache openjdk17-jdk",
                                null, 1_200_000
                            )
                            // 补一条 default-java 软链桥接两种目录布局。
                            // 仅在缺失时建：镜像已自带 default-java（比如默认 JDK 21）时
                            // 绝不能改指，否则等于把用户的默认 JDK 降版。
                            containerEngine.runCommandSync(
                                "if [ ! -x /usr/lib/jvm/default-java/bin/java ]; then " +
                                    "for d in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk " +
                                    "/usr/lib/jvm/java-21-openjdk-arm64 /usr/lib/jvm/java-21-openjdk; do " +
                                    "[ -x \"\$d/bin/java\" ] && ln -sfn \"\$d\" /usr/lib/jvm/default-java && break; " +
                                    "done; fi; true",
                                null, 30_000
                            )
                        }
                        else -> return@runCatching false to "未知工具"
                    }
                    // 装完立刻探测版本，成功与否以实测为准
                    val probe = containerEngine.runCommandSync(def.probe, null, 10_000)
                    val version = probe.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                        ?.takeIf { s -> s.isNotEmpty() && !s.startsWith("/bin/sh") && !s.contains("not found") }
                    if (version != null) true to "已安装 $version"
                    else false to "安装命令已执行但检测不到，可重试或查看运行日志"
                }.getOrElse { e -> false to (e.message ?: "执行失败") }
            }
            val (ok, msg) = result
            // 重装拉的就是 latest，装完这条「可更新」提示就该消失了
            patch(runtimeTools.value[idx].copy(installing = false, version = if (ok) msg.removePrefix("已安装 ") else null, message = msg, lastOk = ok, updateAvailable = null))
        }
    }

    /**
     * 卸载单个运行时工具。
     *
     * 只处理「能干净卸掉」的：npm 全局包走 `npm uninstall -g`，apk/apt 包走包管理器删除。
     * node / npm / corepack 不提供卸载（[RuntimeToolDef.uninstallable] = false）：它们是
     * 官方二进制手动 cp 进 /usr/bin 的，删了会把 pnpm/opencode/dsh 的入口一起弄坏，
     * 而容器重建才是真正的「清干净」手段。
     *
     * 卸完必顶一次探测（与安装一致）：以实测为准，不靠命令退出码——
     * `npm uninstall` 对不存在的包也返回 0。
     */
    fun uninstallRuntimeTool(key: String) {
        val def = runtimeToolDefs.firstOrNull { it.key == key } ?: return
        if (!def.uninstallable) return
        viewModelScope.launch {
            val idx = runtimeTools.value.indexOfFirst { it.key == key }
            if (idx < 0) return@launch
            fun patch(s: RuntimeToolState) {
                runtimeTools.value = runtimeTools.value.map { if (it.key == key) s else it }
            }
            patch(runtimeTools.value[idx].copy(uninstalling = true, message = null))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val isApt = containerEngine.runCommandSync(
                        "command -v apk >/dev/null 2>&1 && echo apk || echo apt", null, 10_000
                    ).trim().endsWith("apt")
                    val npmPkg = def.npmPackage
                    val sysPkg = def.systemPackage
                    when {
                        // dsh 装在插件目录里，卸载就是删那份 node_modules
                        // （全局那份若存在也一并清，避免检测到旧版本）。
                        key == "dsh" -> {
                            containerEngine.runCommandSync("rm -rf $DSH_RUNTIME_DIR/node_modules $DSH_RUNTIME_DIR/pnpm-lock.yaml", null, 300_000)
                            containerEngine.runCommandSync("npm uninstall -g @deepseek-ai/dsh --force", null, 300_000)
                        }
                        npmPkg != null -> {
                            // pnpm 卸后要清掉可能残留的 shim，否则 `pnpm --version` 仍能解析到旧入口
                            containerEngine.runCommandSync("npm uninstall -g $npmPkg --force", null, 600_000)
                            if (key == "pnpm") {
                                containerEngine.runCommandSync(
                                    "rm -f /usr/bin/pnpm /usr/bin/pnpx /usr/bin/yarn /usr/bin/yarnpkg", null, 30_000
                                )
                            }
                        }
                        // JDK 走两个包管理器不同名字，不能交给下面的 sysPkg 通用分支。
                        // 卸完不删 default-java：它可能指向镜像自带的另一个 JDK，
                        // 而且悬空软链在探测里会走 `-x` 判为不存在，下次安装自会重建。
                        key == "jdk" -> containerEngine.runCommandSync(
                            if (isApt) "apt-get remove -y openjdk-17-jdk-headless" else "apk del openjdk17-jdk",
                            null, 600_000
                        )
                        sysPkg != null -> containerEngine.runCommandSync(
                            if (isApt) "apt-get remove -y $sysPkg" else "apk del $sysPkg", null, 600_000
                        )
                        else -> return@runCatching false to "该工具不支持卸载"
                    }
                    // 卸完重探：以实测为准
                    val probe = containerEngine.runCommandSync(def.probe, null, 10_000)
                    val stillThere = probe.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                        ?.takeIf { s -> s.isNotEmpty() && !s.startsWith("/bin/sh") && !s.contains("not found") }
                    if (stillThere != null) false to "卸载命令已执行但仍检测到 $stillThere"
                    else true to "已卸载"
                }.getOrElse { e -> false to (e.message ?: "执行失败") }
            }
            val (ok, msg) = result
            patch(
                runtimeTools.value[idx].copy(
                    uninstalling = false,
                    version = if (ok) null else runtimeTools.value[idx].version,
                    message = msg,
                    lastOk = ok
                )
            )
        }
    }

    // ============================================================ [PLUGIN-MOD] 插件
    // 全部逻辑自包含在此区块，接缝只在：本区块 + AppScreen + MainActivity 渲染 + 侧边栏插入点。

    private val pluginStore = com.wy.openx.plugin.PluginStore(application)

    /** TTS 宿主桥已移除（示例插件删除）；保留 TtsHostBridge 类的话在这里按需重建。 */

    /** 插件运行时：首次进入插件相关流程时才创建（没插件就不启动 QuickJS）。 */
    private val pluginRuntime: com.wy.openx.plugin.PluginRuntime by lazy {
        com.wy.openx.plugin.PluginRuntime(application, pluginStore, toolRegistry)
    }

    /** 顶层扩展函数：插件列表 → 快捷区分组（每插件一行）。 */
    private fun List<com.wy.openx.plugin.InstalledPlugin>.quickGroups() =
        com.wy.openx.plugin.collectQuickGroups(this)

    /** 是否已经释放过内置插件（只做一次）。 */
    private var builtinPluginsInstalled = false

    /**
     * 释放内置插件 → 启动插件运行时 → 加载插件列表并刷新侧边栏快捷项。
     *
     * ⚠ 这里**必须**启动运行时（2026-10-06 修）：只刷列表的话，插件在管理页显示「已启用」、
     * 侧边栏快捷区也照常出现，但 `PluginRuntime` 压根没跑，工具一个都没注册进 ToolRegistry ——
     * AI 调不到任何插件工具，而且**静默失败**（UI 上没有任何异常）。
     * 此前 `ensurePluginRuntimeStarted()` 只在开关/卸载/导入时才被调，冷启动永远轮不到。
     */
    fun loadPlugins() {
        viewModelScope.launch {
            val (list, envs) = withContext(Dispatchers.IO) {
                if (!builtinPluginsInstalled) {
                    runCatching { com.wy.openx.plugin.BuiltinPluginInstaller.install(appContext, pluginStore) }
                    builtinPluginsInstalled = true
                }
                // 放在安装之后：首次启动要先把内置插件落盘，运行时扫描才看得到它们。
                ensurePluginRuntimeStarted()
                val loaded = pluginStore.load()
                // 插件声明的 AI 供应商随插件启停自动建/删。挂在这里因为
                // 启用、禁用、卸载、安装插件最后都汇到 loadPlugins 这一个收口。
                syncPluginProviders(loaded)
                loaded to syncPluginSettings(loaded)
            }
            uiState = uiState.copy(
                plugins = list,
                pluginEnv = envs,
                pluginQuickGroups = list.quickGroups().let { groups ->
                    // operit 兼容包的侧边栏入口来自 registerNavigationEntry（运行时收集），
                    // 不在 manifest 里；把 nav 拼进 manifest 后整体重建，避免只 append
                    // 导致既有 quickActions 的插件出现重复组。
                    val hasNav = list.any { pluginRuntime.navActionsOf(it.manifest.id).isNotEmpty() }
                    if (!hasNav) groups else com.wy.openx.plugin.collectQuickGroups(
                        list.map { p ->
                            val nav = pluginRuntime.navActionsOf(p.manifest.id)
                            if (nav.isEmpty()) p else p.copy(manifest = p.manifest.copy(quickActions = p.manifest.quickActions + nav))
                        }
                    )
                },
                pluginServices = com.wy.openx.plugin.PluginServiceManager.runningPorts()
            )
        }
    }

    /**
     * 按当前插件列表同步「插件声明的 AI 供应商」。
     *
     * 清单里写了 `aiProvider` → 建/更新一条，**启用状态跟着插件走**：插件被关掉开关时
     * 条目置灰而不是删掉（用户填的 key、拉取过的模型才不会白丢）；只有插件被卸载、
     * 或清单里撤掉了声明，才真删条目。
     *
     * **只碰 id 带 `plugin:` 前缀的行**，用户手填的供应商一律不动。
     * 幂等：清单没变就不写库（saveProvider 每次开事务）。
     */
    private suspend fun syncPluginProviders(plugins: List<com.wy.openx.plugin.InstalledPlugin>) {
        // 用 id 前缀认定「插件条目」，不用 source 字段：用户在供应商编辑器里改过设置后
        // （拉取模型、改 key 都会触发保存），source 可能被重置回 user —— 按 source 过滤
        // 会认不出自己建的那条，进而走「新建」分支把用户拉取的模型覆盖成空。
        val existing = aiProviderRepository.getAllProvidersOnce()
            .filter { it.id.startsWith(PLUGIN_PROVIDER_ID_PREFIX) }
        // 声明看不看启用状态：插件只是被关掉开关时，条目要留着——用户填的 key、
        // 拉取过的模型都在里面，只把 isEnabled 置灰；插件真被卸载了才删。
        val declared = plugins
            .mapNotNull { plugin ->
                val ai = plugin.manifest.aiProvider ?: return@mapNotNull null
                // 没写地址等于没声明：建出来也只是个永远点不通的空壳。
                if (ai.baseUrl.orEmpty().isBlank()) return@mapNotNull null
                pluginProviderId(plugin.manifest.id) to (plugin to ai)
            }
            .toMap()

        existing.filterNot { it.id in declared }.forEach { aiProviderRepository.deleteProvider(it.id) }

        declared.forEach { (id, pair) ->
            val (plugin, ai) = pair
            val current = existing.firstOrNull { it.id == id }
            val name = ai.name.orEmpty().trim().ifBlank { plugin.manifest.name }
            val baseUrl = ai.baseUrl.orEmpty().trim()
            // key / 模型只在清单明确给了值时才覆盖：网关的 key 往往由用户在插件面板里
            // 生成、模型由用户自己拉取，清单留空时也跟着写就把用户填好的抹掉了。
            val apiKeyOverride = ai.apiKey.orEmpty().trim()
            val modelOverride = ai.model.orEmpty().trim()
            // 插件被停用或脚本报错时，供应商跟着置灰：数据留着，但不再被选用。
            val wantEnabled = plugin.enabled && plugin.error == null
            val changed = current == null || current.name != name || current.baseUrl != baseUrl ||
                // source 被弄丢时也要走一次：否则「插件」标签永远回不来。
                // 此时 base 是 current（含 models），copy 不会动模型列表。
                current.source != PROVIDER_SOURCE_PLUGIN ||
                current.isEnabled != wantEnabled ||
                (apiKeyOverride.isNotBlank() && current.apiKey != apiKeyOverride) ||
                (modelOverride.isNotBlank() && current.defaultModel != modelOverride)
            if (!changed) return@forEach
            val base = current ?: AIProviderConfig(
                id = id, name = name, type = ProviderType.OPENAI,
                apiKey = apiKeyOverride, baseUrl = baseUrl, defaultModel = modelOverride,
                models = listOfNotNull(modelOverride.takeIf { it.isNotBlank() }),
                selectedModel = modelOverride
            )
            // models / selectedModel 不跟清单覆盖：那是用户自己拉取和选过的，
            // 每次插件刷新都重置会把用户的模型选择抹掉。
            aiProviderRepository.saveProvider(
                base.copy(
                    name = name,
                    type = ProviderType.OPENAI,
                    baseUrl = baseUrl,
                    apiKey = apiKeyOverride.ifBlank { base.apiKey },
                    defaultModel = modelOverride.ifBlank { base.defaultModel },
                    isEnabled = wantEnabled,
                    source = PROVIDER_SOURCE_PLUGIN,
                    pluginId = plugin.manifest.id
                )
            )
        }
    }

    /** 插件声明的供应商在 `ai_providers` 里的主键前缀；避开用户手填的 id。 */
    private fun pluginProviderId(pluginId: String): String = PLUGIN_PROVIDER_ID_PREFIX + pluginId

    /**
     * 按清单声明的 `settings` 同步插件 env：**只补缺失的默认值**，已有值一律不动。
     *
     * 不每次覆盖是有意的：用户改过的值不能被插件升级/重装冲掉。
     * 补默认值的目的是让插件侧只管 `ctx.getEnv(key)`，不用自己写「没值就用某某」的回退。
     *
     * 返回每个插件的 env 快照，供「插件配置」页渲染当前值。
     */
    private suspend fun syncPluginSettings(
        plugins: List<com.wy.openx.plugin.InstalledPlugin>
    ): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, Map<String, String>>()
        plugins.forEach { plugin ->
            val id = plugin.manifest.id
            val env = com.wy.openx.plugin.PluginEnvStore.read(appContext, id).toMutableMap()
            var changed = false
            plugin.manifest.settingItems.forEach { item ->
                val key = item.key.orEmpty().trim()
                val def = item.defaultValue
                if (key.isNotEmpty() && def != null && !env.containsKey(key)) {
                    env[key] = def
                    changed = true
                }
            }
            if (changed) com.wy.openx.plugin.PluginEnvStore.write(appContext, id, env)
            result[id] = env
        }
        return result
    }

    /** 写一个插件配置项（「插件配置」页里清单声明 `settings` 的那些控件）。 */
    fun updatePluginSetting(pluginId: String, key: String, value: String) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                val env = com.wy.openx.plugin.PluginEnvStore.read(appContext, pluginId).toMutableMap()
                env[key] = value
                com.wy.openx.plugin.PluginEnvStore.write(appContext, pluginId, env)
                env.toMap()
            }
            uiState = uiState.copy(pluginEnv = uiState.pluginEnv + (pluginId to updated))
        }
    }

    fun openPluginManage() {
        screen = AppScreen.PluginManage
        loadPlugins()
    }

    /** 当前打开的插件界面（[AppScreen.PluginUi] 用）。 */
    var activePlugin: com.wy.openx.plugin.InstalledPlugin? = null
        private set

    /** 打开某个插件的自定义界面。 */
    fun openPluginUi(plugin: com.wy.openx.plugin.InstalledPlugin) {
        if (plugin.manifest.ui.isBlank()) {
            showToast("该插件未提供界面")
            return
        }
        activePlugin = plugin
        screen = AppScreen.PluginUi
    }

    /**
     * 按 id 打开插件界面（侧边栏快捷区行首的插件名点击走这里）。
     *
     * 需要按 id 反查：UI 层只传 id，避免把 [com.wy.openx.plugin.InstalledPlugin]
     * 整个对象穿过 Compose 参数层（它带目录路径、工具名列表等，不需要暴露）。
     */
    fun onOpenPluginById(pluginId: String) {
        val plugin = uiState.plugins.firstOrNull { it.manifest.id == pluginId }
        if (plugin == null) {
            showToast("插件不存在或已卸载")
            return
        }
        openPluginUi(plugin)
    }

    fun closePluginUi() {
        activePlugin = null
        screen = AppScreen.PluginManage
    }

    /** 供插件界面页拿到 PluginStore / ToolRegistry（宿主能力注入）。 */
    fun pluginStoreRef(): com.wy.openx.plugin.PluginStore = pluginStore

    fun toolRegistryRef(): com.wy.openx.tools.ToolRegistry = toolRegistry

    // ---------------- 服务型插件（网关类）：启停 + 状态

    /** 启动插件后台：有 service 脚本即可启动；宿主监听 HTTP 才需要 server 权限。 */
    fun startPluginService(id: String) {
        val plugin = uiState.plugins.firstOrNull { it.manifest.id == id }
        if (plugin == null) { showToast("插件不存在"); return }
        val manifest = plugin.manifest
        val hasSchedulePerm = manifest.grantedPermissions.contains(com.wy.openx.plugin.PluginPermission.SCHEDULE)
        val hasSchedule = manifest.scheduleTasks.any { it.isValid() }
        if (manifest.service.isBlank() && !hasSchedule) {
            showToast("该插件既未声明服务也未声明定时任务")
            return
        }
        if (hasSchedule && !hasSchedulePerm) {
            showToast("该插件未声明 schedule 权限，无法启动定时任务")
            return
        }
        if (id in uiState.pluginServiceStarting) return
        uiState = uiState.copy(
            pluginServiceStarting = uiState.pluginServiceStarting + id,
            pluginServiceErrors = uiState.pluginServiceErrors - id
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val src = if (manifest.service.isNotBlank()) {
                        pluginStore.readServiceSource(plugin)
                            ?: error("服务脚本不存在：${manifest.service}")
                    } else ""
                    val bridge = com.wy.openx.plugin.PluginHostActionsBridge(
                        appContext, manifest.id, manifest.grantedPermissions
                    )
                    com.wy.openx.plugin.PluginServiceManager.start(manifest, src, bridge)
                }
            }
            uiState = uiState.copy(pluginServiceStarting = uiState.pluginServiceStarting - id)
            result.fold(
                onSuccess = { port ->
                    refreshPluginServices()
                    showToast(if (port > 0) "服务已启动：127.0.0.1:$port" else if (manifest.service.isNotBlank()) "服务已启动（后台运行）" else "定时任务已启动")
                },
                onFailure = {
                    val message = it.message ?: "未知错误"
                    uiState = uiState.copy(pluginServiceErrors = uiState.pluginServiceErrors + (id to message))
                    showToast("启动失败：$message")
                }
            )
        }
    }

    /** 停止一个服务型插件。 */
    fun stopPluginService(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.wy.openx.plugin.PluginServiceManager.stop(id)
            }
            refreshPluginServices()
            showToast("服务已停止")
        }
    }

    /** 刷新服务运行状态到 uiState（管理页据此渲染启停按钮）。 */
    fun refreshPluginServices() {
        uiState = uiState.copy(
            pluginServices = com.wy.openx.plugin.PluginServiceManager.runningPorts()
        )
    }

    /**
     * 重新探测某个插件的服务状态（配置页的「刷新状态」）。
     *
     * 只重读 [com.wy.openx.plugin.PluginServiceManager.runningPorts] 不够：插件的后台
     * 可能是**从它自己的面板里起的**（面板走容器终端，宿主不参与），这时宿主记录里
     * 根本没有它，配置页会一直显示「未启动」。所以再按插件声明的地址探一次端口。
     */
    fun refreshPluginServiceStatus(id: String) {
        val plugin = uiState.plugins.firstOrNull { it.manifest.id == id }
        viewModelScope.launch {
            val merged = withContext(Dispatchers.IO) {
                val map = com.wy.openx.plugin.PluginServiceManager.runningPorts().toMutableMap()
                // 宿主记录里没有、或没绑端口（port 0）时才探：探到实际端口反而信息更多
                if (plugin != null && (map[plugin.manifest.id] ?: 0) <= 0) {
                    declaredServicePort(plugin)
                        ?.takeIf { isLoopbackPortOpen(it) }
                        ?.let { map[plugin.manifest.id] = it }
                }
                map
            }
            uiState = uiState.copy(pluginServices = merged)
            showToast(if (merged[id] != null) "服务运行中" else "未检测到运行中的服务")
        }
    }

    /** 插件对外服务的端口：优先 `aiProvider` 声明的地址，其次 service 型插件的清单端口。 */
    private fun declaredServicePort(plugin: com.wy.openx.plugin.InstalledPlugin): Int? {
        val baseUrl = plugin.manifest.aiProvider?.baseUrl.orEmpty().trim()
        if (baseUrl.isNotBlank()) {
            runCatching { java.net.URI(baseUrl).port }.getOrNull()
                ?.takeIf { it > 0 }
                ?.let { return it }
        }
        if (plugin.manifest.service.isNotBlank() &&
            plugin.manifest.grantedPermissions.contains(com.wy.openx.plugin.PluginPermission.SERVER)
        ) {
            return if (plugin.manifest.port > 0) plugin.manifest.port else 18899
        }
        return null
    }

    /** 探一下回环端口在不在听。超时给短点：探测只是辅助信息，不该让按钮转圈。 */
    private fun isLoopbackPortOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
        true
    }.getOrDefault(false)

    /** 启停插件：改名单 → 停服务 → 重载运行时 → 刷新 UI。 */
    fun togglePlugin(id: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                pluginStore.setEnabled(id, enabled)
                // 停用要把它的服务/定时任务一并停掉。否则失开关后端口还占着、定时任务照跑，
                // 而它的工具与钩子已经被撤——用户看到的就是「我明明关了它在跑」。
                if (!enabled) runCatching { com.wy.openx.plugin.PluginServiceManager.stop(id) }
                ensurePluginRuntimeStarted()
                pluginRuntime.reload()
            }
            refreshPluginServices()
            loadPlugins()
            showToast(if (enabled) "插件已启用" else "插件已停用")
        }
    }

    /** 卸载插件。 */
    fun uninstallPlugin(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 先停用（撤工具）再删目录，避免删了文件但工具还挂着
                pluginStore.setEnabled(id, false)
                ensurePluginRuntimeStarted()
                pluginRuntime.reload()
                pluginStore.uninstall(id)
            }
            loadPlugins()
            showToast("插件已卸载")
        }
    }

    /** 导入单文件 .js 作为插件。 */
    fun importPluginJs(fileName: String, source: String) {
        if (source.isBlank()) {
            uiState = uiState.copy(
                pluginInstallResult = PluginInstallResult(
                    failures = listOf(PluginInstallResult.Failure(fileName, "文件为空"))
                )
            )
            return
        }
        viewModelScope.launch {
            // 单文件导入的展示名用文件名本身（manifest 的 id 是合成的 local.xxx，用户不认识）
            val displayName = fileName.substringBeforeLast('.').ifBlank { "js_plugin" }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.wy.openx.plugin.installSingleFileJs(pluginStore, fileName, source)
                    ensurePluginRuntimeStarted()
                    pluginRuntime.reload()
                    PluginInstallResult(
                        entries = listOf(
                            PluginInstallResult.Entry(id = "local.$displayName", name = displayName)
                        )
                    )
                }.getOrElse {
                    PluginInstallResult(
                        failures = listOf(PluginInstallResult.Failure(fileName, it.message ?: "写入失败"))
                    )
                }
            }
            loadPlugins()
            uiState = uiState.copy(pluginInstallResult = result)
        }
    }

    /** 关闭插件导入结果弹窗。 */
    fun dismissPluginInstallResult() {
        if (uiState.pluginInstallResult == null) return
        uiState = uiState.copy(pluginInstallResult = null)
    }

    /**
     * 从压缩包（.zip / .tar / .tar.gz / .tgz）导入插件，包内需含 plugin.json。
     * 对齐技能页的「安装压缩包」能力，同样支持包带多个插件。
     */
    fun importPluginArchive(uri: Uri, fileName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // 先从 content uri 落到临时文件：ArchiveExtractor 只接受宿主 File
                val tmp = File(
                    appContext.cacheDir,
                    "plugin_archive_${System.currentTimeMillis()}_${
                        sanitizeUploadName(fileName, "application/octet-stream", false)
                    }"
                )
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选压缩包")
                    pluginStore.installFromArchive(tmp, fileName)
                }.also { tmp.delete() }
            }
            result.fold(
                onSuccess = { r ->
                    if (r.entries.isNotEmpty()) {
                        ensurePluginRuntimeStarted()
                        pluginRuntime.reload()
                        loadPlugins()
                    }
                    uiState = uiState.copy(pluginInstallResult = r)
                },
                onFailure = {
                    uiState = uiState.copy(
                        pluginInstallResult = PluginInstallResult(
                            failures = listOf(
                                PluginInstallResult.Failure(fileName, it.message ?: "读取失败")
                            )
                        )
                    )
                }
            )
        }
    }

    /** 快捷项点击：填入提示词，可选直接发送。 */
    fun onPluginQuickAction(action: com.wy.openx.plugin.PluginQuickAction) {
        // 「打开界面」类快捷项：直接开插件 UI，不该去聊天框绕一圈。
        if (action.opensUi) {
            val plugin = uiState.plugins.firstOrNull { it.manifest.id == action.ownerPluginId }
            if (plugin == null) {
                showToast("插件不存在或已卸载：${action.ownerPluginId}")
            } else {
                openPluginUi(plugin)
            }
            return
        }
        // 「打开网址」类快捷项：交给系统浏览器（需要系统下载/分享/长按选择时，WebView 不够用）。
        if (action.opensUrl) {
            val target = action.url.orEmpty().trim()
            if (target.isEmpty()) {
                showToast("该快捷项未配置网址")
                return
            }
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(target)
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            } catch (e: Exception) {
                showToast("没有可打开该网址的应用：${e.message ?: target}")
            }
            return
        }
        // 默认：把提示词填进输入框（可选自动发送）。
        val promptText = action.prompt.orEmpty()
        if (promptText.isBlank()) {
            showToast("该快捷项未配置提示词")
            return
        }
        uiState = uiState.copy(prompt = promptText)
        screen = AppScreen.Console
        if (action.autoSend) sendMessage()
    }

    /**
     * 插件运行时只启动一次；失败不致命。
     *
     * 调用点**都在 IO 上**（loadPlugins / togglePlugin 的 `withContext(Dispatchers.IO)`），
     * 所以失败提示要切回主线程再改 `uiState` —— 直接在 IO 线程写 `uiState` 是数据竞争。
     */
    private fun ensurePluginRuntimeStarted() {
        runCatching { pluginRuntime.start() }
            .onFailure { e ->
                viewModelScope.launch { showToast("插件引擎启动失败：${e.message}") }
            }
    }

    /** 从所选 .md 文件导入单个技能（同名覆盖）。scope="project" 落到当前项目的项目技能目录。 */
    fun importSkillFromMarkdown(uri: Uri, fileName: String, scope: String = com.wy.openx.data.store.SCOPE_GLOBAL) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                FileContentReader.readText(appContext, uri)
            }.getOrElse {
                showToast("读取文件失败：${it.message ?: "未知错误"}")
                return@launch
            }
            val installed = withContext(Dispatchers.IO) {
                runCatching { skillStore.importMarkdown(fileName, text, scope, uiState.settings.currentProject.trim()) }
            }
            installed.fold(
                onSuccess = { name ->
                    if (name == null) showToast("导入失败：文件缺少有效内容") else {
                        loadSkills(); showToast("技能「$name」已导入")
                    }
                },
                onFailure = { showToast("导入失败：${it.message ?: "未知错误"}") }
            )
        }
    }

    /** 从所选压缩包导入技能（解压到 .openx/skills，同名覆盖）。scope="project" 落到项目子目录。 */
    fun importSkillFromArchive(uri: Uri, fileName: String, scope: String = com.wy.openx.data.store.SCOPE_GLOBAL) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // 先从 content uri 落到临时文件：ArchiveExtractor 只接受宿主 File
                val tmp = File(appContext.cacheDir, "skill_archive_${System.currentTimeMillis()}_${sanitizeUploadName(fileName, "application/octet-stream", false)}")
                runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选压缩包")
                    skillStore.installFromArchive(tmp, fileName, scope, uiState.settings.currentProject.trim())
                }.also { tmp.delete() }
            }
            result.fold(
                onSuccess = { names ->
                    if (names.isEmpty()) showToast("压缩包内未找到技能（需含 SKILL.md）")
                    else { loadSkills(); showToast("已导入 ${names.size} 个技能：${names.joinToString("、")}") }
                },
                onFailure = { showToast("导入失败：${it.message ?: "未知错误"}") }
            )
        }
    }

    fun loadMcpServers() {
        uiState = uiState.copy(mcpServers = mcpServerStore.loadEntries())
    }

    fun addMcpServer(config: McpServerConfig, scope: McpScope) {
        mcpServerStore.add(config, scope)
        loadMcpServers()
        showToast("MCP Server「${config.name}」已添加")
    }

    /** 编辑保存：支持改名 / 改作用域（作用域变化时把条目从旧文件移到新文件）。 */
    fun updateMcpServer(oldName: String, oldScope: McpScope, config: McpServerConfig, newScope: McpScope) {
        mcpServerStore.update(oldName, oldScope, config, newScope)
        // 改名后旧名字的会话缓存/测试结果不再对应任何条目，清掉避免残留
        if (oldName != config.name) {
            QuickJsMcpClient.shared.release(oldName)
            QuickJsMcpClient.shared.cleanup(oldName)
            uiState = uiState.copy(
                mcpTestResults = uiState.mcpTestResults - oldName,
                mcpTesting = uiState.mcpTesting - oldName
            )
        }
        loadMcpServers()
        showToast("MCP Server「${config.name}」已保存")
    }

    fun deleteMcpServer(name: String, scope: McpScope) {
        mcpServerStore.delete(name, scope)
        QuickJsMcpClient.shared.release(name)
        QuickJsMcpClient.shared.cleanup(name)
        uiState = uiState.copy(
            mcpTestResults = uiState.mcpTestResults - name,
            mcpTesting = uiState.mcpTesting - name
        )
        loadMcpServers()
        showToast("MCP Server「$name」已删除")
    }

    fun toggleMcpServer(name: String, scope: McpScope, enabled: Boolean) {
        mcpServerStore.setEnabled(name, scope, enabled)
        loadMcpServers()
        // 关闭时释放会话让空闲回收/下次连接按新状态走；打开无需预连（运行时懒加载）
        if (!enabled) {
            QuickJsMcpClient.shared.release(name)
            QuickJsMcpClient.shared.cleanup(name)
        }
    }

    fun openMcpManage() {
        screen = AppScreen.McpManage
        loadMcpServers()
        // 进页面即自动测试已启用的 Server，用户一眼可见「连接成功 / 连接失败」，无需手动逐个点
        autoTestMcpConnections()
    }

    /** 测试指定 MCP Server 的连接（真实握手 tools/list），结果写入 uiState 供页面展示。 */
    fun testMcpConnection(name: String) {
        val config = mcpServerStore.load().find { it.name == name } ?: return
        uiState = uiState.copy(mcpTesting = uiState.mcpTesting + name)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // 占用该会话：用户正在看状态，别让空闲回收中途把进程杀掉
                QuickJsMcpClient.shared.claim(name)
                runCatching { QuickJsMcpClient.shared.testConnection(config) }
                    .getOrElse { McpTestResult.Fail(it.message ?: "未知错误") }
            }
            uiState = uiState.copy(
                mcpTesting = uiState.mcpTesting - name,
                mcpTestResults = uiState.mcpTestResults + (name to result)
            )
        }
    }

    /**
     * 自动测试全部**已启用**的 MCP Server（进入 MCP 管理页时触发）。
     *
     * 目的：用户一进页面就能一眼看出每个 Server 是「连接成功」还是「连接失败」，不必手动逐个点。
     * 未启用的 Server 不测——它们不参与工作，测出失败反而误导。
     *
     * 并发执行：逐个串行测试会让页面长时间停在「正在连接…」（stdio 类需起进程，较慢）。
     * 用 `async` 并发 + `runCatching` 兜底，任一失败不影响其它。
     */
    fun autoTestMcpConnections() {
        val targets = mcpServerStore.load().filter { it.enabled }
        if (targets.isEmpty()) return
        // 取消上一轮（重复进入页面时），避免两轮结果交叉写入
        mcpAutoTestJob?.cancel()
        // 标记为测试中，让卡片立即显示「正在连接…」
        uiState = uiState.copy(mcpTesting = uiState.mcpTesting + targets.map { it.name })
        mcpAutoTestJob = viewModelScope.launch {
            kotlinx.coroutines.coroutineScope {
                targets.map { config ->
                    async {
                        val result = withContext(Dispatchers.IO) {
                            QuickJsMcpClient.shared.claim(config.name)
                            runCatching { QuickJsMcpClient.shared.testConnection(config) }
                                .getOrElse { McpTestResult.Fail(it.message ?: "未知错误") }
                        }
                        // 每个 Server 测完立即回写，先完成的先显示，不必等全部结束
                        uiState = uiState.copy(
                            mcpTesting = uiState.mcpTesting - config.name,
                            mcpTestResults = uiState.mcpTestResults + (config.name to result)
                        )
                    }
                }.awaitAll()
            }
        }
    }

    /** 关闭 MCP 管理页：取消进行中的自动测试、清空测试结果并释放占用，让空闲回收重新接管会话。 */
    fun clearMcpTestState() {
        mcpAutoTestJob?.cancel()
        mcpAutoTestJob = null
        uiState.mcpTestResults.keys.forEach { QuickJsMcpClient.shared.release(it) }
        uiState = uiState.copy(mcpTestResults = emptyMap(), mcpTesting = emptySet())
    }

    private fun exifAngle(stream: java.io.InputStream): Float = try {
        val exif = android.media.ExifInterface(stream)
        when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) { 0f }

    /** 把图片 URI 解码 → EXIF 摆正 → 缩放（最长边 1920）→ 压成 JPEG data URL，供多模态接口使用。 */
    private fun encodeImageToDataUrl(uri: Uri): String? {
        return try {
            val bitmap = appContext.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                ?: return null
            val rotation = appContext.contentResolver.openInputStream(uri)?.use { exifAngle(it) } ?: 0f
            val oriented = if (rotation != 0f) {
                val m = android.graphics.Matrix().apply { postRotate(rotation) }
                android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            } else bitmap
            val maxDim = 1920
            val (w, h) = oriented.width to oriented.height
            val scaled = if (maxOf(w, h) > maxDim) {
                val scale = maxDim.toFloat() / maxOf(w, h)
                android.graphics.Bitmap.createScaledBitmap(oriented, (w * scale).toInt(), (h * scale).toInt(), true)
            } else oriented
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            if (scaled !== oriented) scaled.recycle()
            if (oriented !== bitmap) oriented.recycle()
            bitmap.recycle()
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildToolDescription(toolName: String, toolArgs: String): String {
        val nameMap = mapOf(
            "web_search" to "搜索互联网", "web_fetch" to "读取网页内容",
            "file_manager" to "管理文件", "file_analyzer" to "分析文件",
            "app_launcher" to "启动/操作应用", "phone_op" to "执行手机操作",
            "device_info" to "获取设备信息",
            "memory_save" to "保存记忆", "memory_read" to "读取记忆", "memory_delete" to "删除记忆",
            "skill_add" to "创建新技能", "skill_delete" to "删除技能", "skill_list" to "列出技能",
            "subagent_dispatch" to "分派子代理子任务",
            "git" to "执行 Git 操作",
            "scaffold" to "创建项目脚手架",
            "batch_replace" to "批量搜索替换",
            "explore" to "扫描项目结构",
            "batch_edit" to "批量编辑文件",
            "search_code" to "搜索代码内容",
            "http_client" to "HTTP API 请求",
            "sqlite_browser" to "浏览 SQLite 数据库",
            "screenshot" to "截取屏幕",
            "stock" to "股票行情/分析/信号/板块/资金流/本地记忆/自选股",
        )
        return nameMap[toolName] ?: toolName
    }

    /**
     * 「AI 请求 <做什么> <对什么>」那一行。
     *
     * 工具参数是 JSON，原样摊给用户很难读（file_manager 的 write 还带整篇转义过的正文），
     * 这里按工具抽出关键信息。返回空串 = 没生成出摘要（未知工具），UI 会退回显示原始参数。
     */
    private fun buildToolActionLine(toolName: String, args: Map<String, Any>): String {
        fun s(vararg keys: String): String {
            keys.forEach { k ->
                (args[k] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return ""
        }
        fun p(vararg keys: String) = shortenPath(s(*keys))
        fun items(vararg keys: String): Int {
            keys.forEach { k -> (args[k] as? List<*>)?.let { return it.size } }
            return 0
        }
        return when (toolName) {
            "file_manager" -> when (s("action").lowercase()) {
                "list" -> "查看目录 ${p("path")}"
                "read" -> "读取 ${p("path")}"
                "write" -> "写入 ${p("path")}"
                "delete" -> "删除 ${p("path")}"
                "info" -> "查看 ${p("path")} 的信息"
                "analyze" -> "分析 ${p("path")}"
                "search" -> buildString {
                    append("搜索文件")
                    if (s("query").isNotBlank()) append("「${s("query")}」")
                    if (s("pattern").isNotBlank()) append("「${s("pattern")}」")
                    if (p("path").isNotBlank()) append("（起始目录 ${p("path")}）")
                }
                else -> ""
            }
            "writeFile" -> "写入 ${p("path", "filePath")}"
            "appendFile" -> "追加到 ${p("path", "filePath")}"
            "editFile" -> "编辑 ${p("path", "filePath")}"
            "readFile" -> "读取 ${p("path", "filePath")}"
            "batch_edit" -> "批量编辑 ${items("edits")} 个文件"
            "batch_replace" -> "批量替换 ${items("edits")} 个文件中的内容"
            "terminal" -> "执行命令 ${inlineOneLine(s("command", "cmd"), 120)}"
            "shizuku" -> "执行系统命令 ${inlineOneLine(s("command"), 120)}"
            "git" -> "git ${s("operation")}".trim()
            "memory_save" -> "保存记忆「${s("key")}」"
            "memory_delete" -> "删除记忆「${s("key")}」"
            "knowledge_save" -> "保存知识「${s("title")}」"
            "knowledge_delete" -> "删除知识「${s("id")}」"
            "todo" -> "更新任务清单（${items("items")} 项）"
            "scaffold" -> "创建${s("type")}项目 ${s("name")}".trim()
            "browser" -> "${s("action")} ${inlineOneLine(s("url"), 80)}".trim()
            else -> ""
        }
    }

    /**
     * 动作附带的元信息（字符数 / 行数 / 写入模式…）。
     *
     * 用户靠这几行就能判断「这条到底要改多大」，比对着原始 JSON 数引号快得多。
     */
    private fun buildToolExtras(args: Map<String, Any>): List<String> {
        val out = mutableListOf<String>()
        (args["content"] as? String)?.takeIf { it.isNotEmpty() }?.let { c ->
            out += "字符数：${c.length}"
            out += "行数：${c.count { it == '\n' } + 1}"
        }
        (args["mode"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { m ->
            out += "写入模式：" + when (m) {
                "overwrite" -> "覆盖已有内容"
                "append" -> "追加到末尾"
                "create_new" -> "仅新建（已存在则报错）"
                else -> m
            }
        }
        (args["oldText"] as? String)?.takeIf { it.isNotEmpty() }?.let { out += "被替换内容：${it.length} 字符" }
        (args["newText"] as? String)?.takeIf { it.isNotEmpty() }?.let { out += "替换为：${it.length} 字符" }
        (args["recursive"] as? Boolean)?.takeIf { it }?.let { out += "递归：是" }
        (args["maxDepth"] as? Number)?.let { out += "最大深度：$it" }
        (args["maxLines"] as? Number)?.let { out += "最多读取行数：$it" }
        (args["startLine"] as? Number)?.let { out += "起始行号：$it" }
        (args["pattern"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { out += "通配模式：$it" }
        (args["ext"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { out += "扩展名过滤：$it" }
        return out
    }

    /**
     * 「始终允许」会记住什么。
     *
     * shell 类工具由权限策略引擎给出**指令前缀**（点一次「始终允许」不该把 `rm -rf /` 也放行），
     * 其它工具记的是整个工具；引擎判定不可记忆时把原因写出来。
     * eval 按工具名比对：非 shell 工具时槽位里可能是上一次的残留值。
     */
    private fun buildAlwaysAllowHint(cleanName: String, requestConversationId: Long): String {
        val eval = slotOf(requestConversationId).pendingPermissionEval?.takeIf { it.toolName == cleanName }
        return when {
            eval?.rememberDisabledReason != null -> "「始终允许」不可用：${eval.rememberDisabledReason}"
            eval != null && eval.rememberablePatterns.isNotEmpty() ->
                "「始终允许」将在本项目记住这些命令前缀：" + eval.rememberablePatterns.joinToString("、") { "`$it`" }
            else -> "「始终允许」将在本项目记住：该工具的全部调用"
        }
    }

    /** 路径显示成 ~ 开头（容器里 /root 就是用户主目录），过长时中间省略。 */
    private fun shortenPath(raw: String): String {
        if (raw.isBlank()) return ""
        val home = when {
            raw == "/root" -> "~"
            raw.startsWith("/root/") -> "~" + raw.removePrefix("/root")
            else -> raw
        }
        return if (home.length > 76) home.take(36) + "…" + home.takeLast(36) else home
    }

    /** 把可能多行的内容压成单行并截断，避免摘要里冒出换行把版式搞散。 */
    private fun inlineOneLine(raw: String, limit: Int): String {
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > limit) flat.take(limit) + "…" else flat
    }

    fun compactConversation() {
        stopStreaming()
        val cid = uiState.currentConversationId
        editSlot(cid) {
            isSending = true
            isCompacting = true
            thinkingStartedAt = System.currentTimeMillis()
        }
        viewModelScope.launch {
            try {
                val ok = performCompaction(cid)
                // 明确反馈：此前返回值被丢弃，用户看到「正在压缩」转一圈却毫无结果、也没有解释
                if (!ok) showToast("没有可压缩的内容（会话太短或已是压缩后状态）")
            } finally {
                editSlot(cid) {
                    isSending = false
                    isCompacting = false
                    reconnecting = false
                    reconnectAttempt = 0
                    reconnectMax = 0
                    thinkingStartedAt = 0L
                }
            }
        }
    }

    /**
     * 发送前自动压缩判断：设置触发比例时，
     * 用最近一次 API 真实输入上下文（lastInputContext，取不到时本地估算）与上下文窗口
     * 比较，超过比例则先压缩再继续发送。自动压缩默认恒开（无总开关）。
     */
    private suspend fun maybeAutoCompact(conversationId: Long) {
        val slot = slotOf(conversationId)
        // 【保险】判定前先确保锚存在。无锚时 effectiveContextTokens 会退回**全量估算**，
        // 对大会话系统性高估（实测 1870 条估出 1.3M vs 真实 389K，高 3.4 倍），
        // 会误判超窗而**把整个会话压掉**——这是不可逆的破坏，宁可先补一次锚。
        ensureUsageAnchor(conversationId, slot.messages)
        // 仍然无锚（新会话从未请求过 / 历史被结构性改动后处于锚抑制期）时**跳过自动压缩**：
        // 本地全量估算漏掉 provider 侧 input 缓存折扣，长工具循环会话会把真实 221K 估成
        // 640K（真机实证），拿它做不可逆压缩决策就是把整个会话压成一条摘要。跳过等真实
        // usage 回来由 advanceUsageAnchor 建锚后再恢复判断；发送后真正的超窗兜底由工具
        // 循环内 `lastInputTokens`（provider 真实上报）承担，依然安全。
        if (usageAnchors[conversationId] == null) {
            uiState = uiState.copy(logs = uiState.logs +
                "[自动压缩] 无真实 usage 锚（无锚/抑制期），跳过自动压缩，等待真实上报再判定")
            return
        }
        val settings = uiState.settings
        val ratio = if (settings.compactUseDefault) 0.8f else settings.contextRatio
        android.util.Log.i("AutoCompact", "maybeAutoCompact: compactUseDefault=${settings.compactUseDefault}, ratio=$ratio, contextWindow=${settings.contextWindow}, messages=${slot.messages.size}, lastInputContext=${uiState.lastInputContext}")
        if (ratio <= 0f) {
            uiState = uiState.copy(logs = uiState.logs + "[自动压缩] 触发比例未设置（contextRatio=$ratio）")
            return
        }
        // 冷却：自动压缩后需再积累 AUTO_COMPACT_COOLDOWN 条消息才允许下一次压缩，避免接近阈值时连篇摘要
        if (slot.messagesSinceAutoCompact < AUTO_COMPACT_COOLDOWN) {
            slot.messagesSinceAutoCompact++
            uiState = uiState.copy(logs = uiState.logs + "[自动压缩] 冷却中（压缩后已发送 ${slot.messagesSinceAutoCompact}/$AUTO_COMPACT_COOLDOWN 条），暂不压缩")
            return
        }
        val msgs = slot.messages
        if (msgs.size <= 5) {
            uiState = uiState.copy(logs = uiState.logs + "[自动压缩] 消息数 ${msgs.size} ≤ 5，暂不压缩")
            return
        }
        // 【P4-1】窗口按本会话生效的模型算（会话级模型模式下各会话不同）。
        val window = contextWindowTokensFor(conversationId)
        val current = effectiveContextTokens(conversationId, msgs)
        val threshold = (window * ratio).toInt()
        val anchor = usageAnchors[conversationId]
        // 诊断：把三种 estimate 成因拆开（无锚 / reported≤0 / mark≤0），并带上 cid。
        // 之前它们合并成一个 "estimate"，日志里分不清到底是「这会话压根没锚」还是「锚被清坏了」。
        val src = when {
            anchor == null -> "estimate(无锚)"
            anchor.reported <= 0 -> "estimate(reported≤0)"
            else -> "校准(max(估算, 真实+Δ估算))"
        }
        // 诊断拆分保留在 logcat（不写日志文件、不刷屏）；src 同时用于 UI 日志面板。
        android.util.Log.i("AutoCompact", "判定: cid=$conversationId current=$current (src=$src), window=$window, threshold=$threshold")
        // 【诊断】把大数值拆开：真实 usage / 全量估算 / Δ估算 / 工具schema。
        // 只有这样才知道「数值大」是 [真实值本身大] 还是 [估算偏大]。
        // 正式定调后可删。
        run {
            val bodyTok = estimateBodiesTokens(msgs)
            val schemaTok = toolSchemaTokens()
            // 与 effectiveContextTokens 用同一个全量估算（含 schema），Δ估算才有意义
            val estimate = estimateContextTokens(msgs)
            val reportedText = anchor?.let { "锚reported=${it.reported} baselineEst=${it.baselineEstimate}" } ?: "无锚"
            // 与 effectiveContextTokens 同一套标量口径，日志里的 Δ估算才能和显示值对上。
            val deltaTok = anchor?.let { estimate - it.baselineEstimate } ?: -1
            AppLogStore.info(
                "压缩诊断",
                "判定: cid=$conversationId current=$current window=$window threshold=$threshold ratio=$ratio " +
                    "| $reportedText | 消息正文≈${bodyTok}tok 工具schema≈${schemaTok}tok " +
                    "增量≈${deltaTok}tok src=$src"
            )
        }
        uiState = uiState.copy(logs = uiState.logs + "[自动压缩] 上下文约 $current / ${window}tok，阈值 ${threshold}tok（ratio=${ratio}, contextWindow=${settings.contextWindow}, src=$src）")
        if (current < threshold) return
        uiState = uiState.copy(logs = uiState.logs + "[自动压缩] 已超阈值，开始压缩...")
        editSlot(conversationId) {
            isCompacting = true
            // 压缩指示器要显示时长，而本函数可能在**回合已收尾之后**才被调用
            // （finally 里的后置检查：3099 行，紧随 3033 行把 thinkingStartedAt 复位成 0 之后）。
            // 那样 UI 拿 `now - 0` 当时长 → 显示「正在压缩上下文 29853498 分钟」（1970 年至今）。
            // 真机实测报障。与 [compactConversation] 同一口径：进入压缩态就给一个计时起点。
            if (thinkingStartedAt <= 0L) thinkingStartedAt = System.currentTimeMillis()
        }
        val compacted = try {
            performCompaction(conversationId)
        } finally {
            editSlot(conversationId) { isCompacting = false }
        }
        if (compacted) {
            // 压缩成功：重置冷却计数。lastInputContext 已由 performCompaction 按压缩结果重算，
            // 不能在这里清零——否则用户看到的上下文数字会被抹掉，误以为压缩没生效。
            slot.messagesSinceAutoCompact = 0
        } else {
            // 压缩失败（无可压缩内容/请求出错）：按当前真实消息重新估算，避免旧值残留导致连续误判
            if (conversationId == _uiState.currentConversationId) {
                uiState = uiState.copy(lastInputContext = effectiveContextTokens(conversationId, slot.messages))
            }
            usageAnchors.remove(conversationId)
        }
    }

    /**
     * 供 UI 读取**指定会话**生效的上下文窗口。
     *
     * 【会话级模型串台修复】原来只有无参版，读的是全局 `uiState.settings` +
     * 全局 `activeProvider()`。会话级模式下这等于「最后一次设置模型的会话的值」：
     * 在 B 会话把模型改成 1M 窗口，切回 A 会话，圆环也跟着显示 1M——
     * 明明 A 自己设的是 200K。用户反馈：切换会话再切回来，上下文显示被别的会话带跑。
     *
     * 改为按会话取值后，每个窗口显示自己那家模型的窗口，切来切去不再互相覆盖。
     * 与自动压缩（[contextWindowTokensFor]）同一口径，圆环与实际压缩阈值永远一致。
     */
    fun currentContextWindow(conversationId: Long = uiState.currentConversationId): Int =
        contextWindowTokensFor(conversationId)

    /** 当前激活的提供商（选中优先，其次第一个启用的）。 */
    private fun activeProvider(): com.wy.openx.data.model.AIProviderConfig? =
        providers.firstOrNull { it.id == uiState.settings.selectedProviderId }
            ?: providers.firstOrNull { it.isEnabled }

    /**
     * 解析「默认模型」专用模型：kind ∈ "title"/"compaction"/"vision"。
     * settings 里对应 providerId+model 均非空、provider 存在并启用且有可用 Key 时，
     * 返回以该专用模型（selectedModel 已就位）的副本；否则返回 null（跟随当前聊天模型）。
     */
    private fun resolveSpecialModel(kind: String): com.wy.openx.data.model.AIProviderConfig? {
        val s = uiState.settings
        // 【会话级模型模式】本页（设置→默认模型）配的专用模型全部不再生效：
        // 会话级模式下「每个会话一个模型」，标题/压缩/识图/子代理都应跟随该会话自己选的模型；
        // 否则会出现「会话 B 选了模型 Y，起标题却用全局配的专用模型 X」这种割裂行为。
        // 返回 null 后各调用点自动回落到本会话生效的提供商（见 effectiveProviderFor）。
        if (!s.modelGlobalMode) return null
        val (pid, model) = when (kind) {
            "title" -> s.titleProviderId to s.titleModel
            "compaction" -> s.compactionProviderId to s.compactionModel
            "subagent" -> s.subagentProviderId to s.subagentModel
            "knowledge" -> s.knowledgeProviderId to s.knowledgeModel
            else -> s.visionProviderId to s.visionModel
        }
        if (pid.isBlank() || model.isBlank()) return null
        val p = providers.firstOrNull { it.id == pid && it.isEnabled } ?: return null
        if (p.firstUsableApiKey.isBlank()) return null
        return p.copy(selectedModel = model)
    }

    /**
     * 当前激活提供商 + 当前模型的元数据（自定义优先，其次内置/拉取）。
     * 供圆环长按弹窗回填窗口值——数据源与设置页模型编辑子页完全一致。
     */
    fun currentModelMetadata(): com.wy.openx.data.model.ModelMetadata? {
        val provider = activeProvider() ?: return null
        val model = provider.effectiveModel.ifBlank { return null }
        return com.wy.openx.data.model.mergeModelMetadata(
            model,
            providerModelMetadata[model],
            providerModelMetadata["${provider.id}:$model"]
        )
    }

    /** 当前模型的显示名（供圆环弹窗标题）。 */
    fun currentModelName(): String = activeProvider()?.effectiveModel.orEmpty()

    /**
     * 保存当前激活提供商 + 当前模型的窗口配置（圆环长按弹窗）。
     *
     * 写入的是提供商体系的自定义模型元数据（复合键 pid:model），与设置页「编辑模型」同一份数据；
     * 圆环/自动压缩读取时也以它为准，所以这里存了立即生效，不再像旧的 ApiPreset 那样写了个「用不上」的地方。
     */
    fun updateCurrentModelMeta(contextTokens: Long?, outputTokens: Long?) {
        val provider = activeProvider() ?: run {
            showToast("当前没有可用的提供商")
            return
        }
        val model = provider.effectiveModel.ifBlank {
            showToast("当前未选择模型")
            return
        }
        val base = com.wy.openx.data.model.mergeModelMetadata(
            model,
            providerModelMetadata[model],
            providerModelMetadata["${provider.id}:$model"]
        )
        val updated = base.copy(
            inputTokens = contextTokens?.toInt(),
            contextTokens = contextTokens?.toInt() ?: base.contextTokens,
            outputTokens = outputTokens?.toInt()
        )
        saveCustomModelMetadata(provider.id, model, updated)
        showToast("已保存「$model」的窗口设置")
    }

    /** 自动获取当前模型的上下文窗口（models.dev），通过 [onResult] 回填 (输入, 输出)。 */
    fun autoFetchCurrentModelMeta(onResult: (Long?, Long?) -> Unit) {
        val provider = activeProvider()
        val model = provider?.effectiveModel.orEmpty()
        if (provider == null || model.isBlank()) {
            onResult(null, null)
            return
        }
        viewModelScope.launch {
            val info = ModelMetaFetcher.fetch(provider.name, model)
            onResult(info.inputTokens, info.outputTokens)
        }
    }

    /**
     * ④「请求过大」的强制压缩重试。
     *
     * 对方的做法：在**模型开始回答之前**判定为上下文超限，强制压缩当前消息列表，
     * 然后**重试同一轮**；只重试一次，不进循环。
     *
     * [block] 必须每调用一次都**重新组装请求**（本类的调用点正是这么传的：
     * 每次都重跑 `buildTurnHistory()` + 预算投影），否则压缩后重试发的还是压缩前的旧请求。
     * 压缩失败（无可压缩内容/压缩请求本身出错）时直接返回首次失败，不做无意义的第二次请求。
     */
    private suspend fun <T> withOverflowCompactRetry(conversationId: Long, block: suspend () -> Result<T>): Result<T> {
        val first = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val err = first.exceptionOrNull() ?: return first
        if (!isContextOverflowError(err)) return first
        val detail = err.message.orEmpty().take(300)
        android.util.Log.w(TAG, "判定为请求过大，强制压缩后重试本轮: $detail")
        uiState = uiState.copy(
            logs = uiState.logs + "[上下文] 上游拒绝：请求过大（$detail）。正在压缩上下文后重试本轮…"
        )
        // 强制压缩：绕开 maybeAutoCompact 的冷却与比例门槛（此刻已经确定超限，等阈值没有意义）
        val compacted = try {
            performCompaction(conversationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "强制压缩失败，不再重试: ${e.message}")
            false
        }
        if (!compacted) {
            uiState = uiState.copy(logs = uiState.logs + "[上下文] 压缩未能完成，本轮不再重试（可直接新开会话或手动压缩）")
            return first
        }
        messagesSinceAutoCompact = 0
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 错误是否属于「请求/提示词过大」。
     *
     * 判定走 [com.wy.openx.data.util.ContextOverflow]：状态码必须在 400/413/422 内
     * **且**命中厂商的超长文案。5xx 一律不算——那基本是网关/服务端侧故障
     * （中转层请求体超限就是 500），拿它触发压缩重试只会白等一轮。
     */
    private fun isContextOverflowError(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 8) {
            // 本地已知事实：请求体本身就大 + 网关类拒绝码（ChatRepository 判定，见 RequestBodyTooLargeException）。
            // 这类响应体没有厂商文案，正则无从命中，只能靠「体量 + 状态码」联合判定。
            if (t is com.wy.openx.data.repository.RequestBodyTooLargeException) {
                // 双保险：异常诊断里若明确帯「上游瞬时故障」（账号池耗尽 / upstream_error）信号，
                // 就不是上下文超限，落到下面按普通错误链继续检查。
                if (!com.wy.openx.data.util.ContextOverflow.isTransientGatewayError(t.statusCode, t.message.orEmpty())) {
                    return true
                }
            }
            val msg = t.message.orEmpty()
            val code = Regex("HTTP (\\d{3})").find(msg)?.groupValues?.get(1)?.toIntOrNull()
            if (code != null && com.wy.openx.data.util.ContextOverflow.isOverflow(code, msg)) return true

            if (com.wy.openx.data.util.ContextOverflow.isOverflow(null, msg)) return true
            t = t.cause
        }
        return false
    }

    /**
     * 发请求前的工具输出预算投影（①）。
     *
     * 落盘失败时**原样返回未投影的 history**：宁可多发一次可能超限的请求，
     * 也不能把内容换成一句指向不存在文件的提示让模型去编（约束：
     * 「Persist successfully before replacing anything」）。
     */
    private fun boundToolOutputsForRequest(
        history: List<com.wy.openx.data.remote.ChatMessageDto>,
        conversationId: Long
    ): List<com.wy.openx.data.remote.ChatMessageDto> {
        if (history.isEmpty() || conversationId < 0) return history
        return try {
            val offloader = com.wy.openx.data.util.ToolOutputOffload(appContext)
            val projected = com.wy.openx.data.util.ToolOutputBudget.project(
                history = history,
                toolNameOf = { dto ->
                    // tool_call_id 形如 call_123；从历史里回查工具名成本高，文件名用 id 足够可辨识
                    dto.toolCallId ?: "tool"
                },
                offload = { toolName, content -> offloader.offload(conversationId, toolName, content) }
            )
            // 容量清理整批只做一次。原先是每次 offload 内部逐次做——那要遍历整个落盘目录，
            // 对超预算消息逐条调用就成 O(消息数 × 文件数)，大会话单轮实测 9s 全在这里。
            offloader.trim(conversationId)
            projected
        } catch (e: Exception) {
            android.util.Log.w("ToolOutputBudget", "工具输出落盘失败，本次请求不做投影: ${e.message}")
            history
        }
    }

    /**
     * 解析上下文窗口（token）：优先「当前提供商 + 当前模型」的元数据（自定义 > 内置/拉取），
     * 其次旧的 ApiPreset 配置，最后回退 settings。
     */
    private fun contextWindowTokens(settings: AppSettings): Int =
        windowFrom(currentModelMetadata(), settings)

    /**
     * 【P4-1】某会话生效的上下文窗口。
     *
     * 会话级模型模式下，各会话可能用不同提供商/模型，窗口差异极大（200K vs 1M）。
     * 压缩阈值必须按会话算，否则后台会话 A 的长任务会拿前台 B 的窗口判定：
     * 拿大了→该压不压（直接超窗报错），拿小了→提前压缩（白烧摘要请求并丢掉历史细节）。
     *
     * 不能复用 [contextWindowTokens]：它内部的 [currentModelMetadata] 读的是全局
     * `activeProvider()`，与 `settingsFor(cid)` 已合并的会话提供商不是同一个来源。
     */
    private fun contextWindowTokensFor(conversationId: Long): Int =
        windowFrom(modelMetadataFor(conversationId), settingsFor(conversationId))

    /**
 * 某会话当前模型的思考强度可选档位（models.dev 的 `reasoning_options`）。
 *
 * **null = 元数据缺失**（离线首次启动、models.dev 未收录该模型、自定义模型等），
 * 此时 UI 应显示**全量**选项而不是空列表——宁可多选，也不要让用户一个都选不了。
 *
 * 不做缓存：元数据是**异步**加载的（`loadAllProviderModelMetadata`），
 * 缓存会在「先选模型、后加载完元数据」时把选项永久卡在全量或空。
 * 故只在弹窗打开时现算一次（见 [effortOptionsFor]）。
 */
fun effortOptionsFor(conversationId: Long): List<String>? =
    modelMetadataFor(conversationId)
        ?.reasoningEffortOptions
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }

    /** 某会话生效提供商下的模型元数据（窗口/输入上限等）。 */
    private fun modelMetadataFor(conversationId: Long): com.wy.openx.data.model.ModelMetadata? {
        val provider = effectiveProviderFor(conversationId) ?: activeProvider() ?: return null
        val model = provider.effectiveModel.ifBlank { return null }
        return com.wy.openx.data.model.mergeModelMetadata(
            model,
            providerModelMetadata[model],
            providerModelMetadata["${provider.id}:$model"]
        )
    }

    /**
     * 窗口解析优先级：模型元数据 → 提供商 preset → 手动设置值 → maxTokens 兜底。
     * `meta` 由调用方按会话/全局各自传入，其余字段取自传入的 settings。
     */
    private fun windowFrom(meta: com.wy.openx.data.model.ModelMetadata?, settings: AppSettings): Int {
        meta?.let { m ->
            val w = m.inputTokens?.takeIf { it > 0 } ?: m.contextTokens.takeIf { it > 0 }
            if (w != null) return w
        }
        uiState.apiPresets.firstOrNull { it.name == settings.selectedApiPreset }
            ?.contextTokens?.takeIf { it > 0 }?.let { return it.toInt() }
        val minWindow = (settings.maxTokens.toIntOrNull() ?: 0).coerceAtLeast(4096)
        val setting = settings.contextWindow
        if (setting.isNotBlank() && setting != "0") {
            val cleaned = setting.lowercase().replace(" ", "")
            val multiplier = when {
                cleaned.endsWith("m") -> 1_000_000
                cleaned.endsWith("k") -> 1_000
                else -> 1
            }
            val num = cleaned.replace(Regex("[mk\\s]"), "").toIntOrNull()
            if (num != null && num > 0) return (num * multiplier).coerceAtLeast(1_000)
        }
        return minWindow
    }

    /**
     * token 粗估（兜底用；真实 usage 优先，见 [maybeAutoCompact]）。
     * 口径统一走 [com.wy.openx.data.util.TokenEstimator]——原先这里是「中文 1.0 + 英文 /4 + 全局 ×1.1」，
     * 对中文会话系统性低估，压缩触发得太晚。字段口径与真正发出去的请求保持一致：
     * content + toolResult + toolArguments（工具的大头在 toolResult 上）。
     */
    private fun estimateContextTokens(msgs: List<ChatMessageModel>): Int =
        (estimateBodiesTokens(msgs) + toolSchemaTokens()).coerceAtLeast(1)

    /**
     * 只算消息正文（不含工具定义）：增量补估时用，避免把每轮全量下发的 schema 重复计入。
     *
     * ⚠ 这里**必须模拟发请求前的投影**，否则估算的是「库里存了什么」而不是「实际会发什么」：
     *
     * 1. **thinking 不计入**：库里的思考内联在 content 的 `<thinking>` 里；真正发请求时它被拆进
     *    独立的 `reasoning_content`，而 provider 端不计这部分的 prompt token（推理系会忽略回传的
     *    reasoning）。若按正文字面估上，长思考会话被系统性高估数倍。
     * 2. **工具输出按预算投影**：发请求前 `boundToolOutputsForRequest` / `projectForSend` 会用
     *    [com.wy.openx.data.util.ToolOutputBudget] 把**近期工具输出**只保留
     *    DEFAULT_BUDGET_TOKENS，更旧的落盘并替换成一句约 [ToolOutputBudget.OFFLOADED_NOTICE_TOKENS]
     *    的提示；但库里仍是全文。若不投影，几轮大 readFile / terminal 就会让估算比真实请求体高出一大截。
     *
     * 真机实测（2026-10-07）：未投影时库内估算 432K，而实际请求体仅 156K（provider reported 147K）；
     * 切到 512K 模型时 432K > 阈值 435200 → **误判超窗、把整个会话压掉**。两处都补上后 432K → ≈142K，
     * 与真实 147K 吻合。
     *
     * 只做「保守偏低」：thinking 不计、旧工具输出按提示计，都只会让数字不高于真实发送量，
     * 至多更晚触发压缩，不会误压（安全方向）。
     */
    private fun estimateBodiesTokens(msgs: List<ChatMessageModel>): Int {
        // ① 先算工具输出投影的落盘边界：与 [com.wy.openx.data.util.ToolOutputBudget.project] 同口径——
        // 从最新往最旧累计，装不下 DEFAULT_BUDGET_TOKENS 的那条及其更旧的全部会被落盘替换。
        val budget = com.wy.openx.data.util.ToolOutputBudget.DEFAULT_BUDGET_TOKENS
        var retainedTool = 0
        var toolCut = -1   // -1 = 全部装得下，无需落盘
        for (i in msgs.indices.reversed()) {
            if (msgs[i].folded) continue
            val tr = msgs[i].toolResult ?: continue
            if (tr.isBlank()) continue
            val t = com.wy.openx.data.util.TokenEstimator.bodyTokens(tr)
            if (retainedTool + t > budget) {
                toolCut = i
                break
            }
            retainedTool += t
        }
        val bodies = ArrayList<String>(msgs.size * 2)
        var extra = 0
        for ((i, m) in msgs.withIndex()) {
            // 【口径】折叠区是历史**存档**，发请求时根本不进 DTO（uiState.messages 已按 folded
            // 拆分、buildTurnHistory 只走非折叠行）——估算必须同样跳过，否则「仅恢复会话（保留
            // 摘要）」后把整卷折叠原文也数上，顶部数字虚高到几十万、下一轮必误触压缩。
            if (m.folded) continue
            m.content?.takeIf { it.isNotBlank() }
                ?.let { bodies.add(com.wy.openx.data.util.ChatUtils.removeThinkingContent(it)) }
            m.toolResult?.takeIf { it.isNotBlank() }?.let { tr ->
                if (i <= toolCut) {
                    // ② 会被落盘替换成提示语：按提示长度计，而不是库里的全文。
                    extra += com.wy.openx.data.util.ToolOutputBudget.OFFLOADED_NOTICE_TOKENS
                } else {
                    bodies.add(tr)
                }
            }
            m.toolArguments?.takeIf { it.isNotBlank() }?.let { bodies.add(it); extra += 16 }
        }
        return com.wy.openx.data.util.TokenEstimator.messagesTokens(bodies, extraTokens = extra)
    }

    /**
     * ③ 当前上下文的权威判定值：**provider 真实 usage 优先**，估算只作没有报告的兜底。
     *
     * 
     * 圆环 `tokenProgress = sessionLastInputTokens / contextLimit`）：
     * 有最近一次请求的真实上报时，判定值 = **那次真实值 + 其后新增内容**；
     * 没报告（刚切会话/重启，或 usage 一直没回来）才用本地估算，不假装精确。
     *
     * 「其后新增」：项目只读 `lastInputTokens`，那次请求之后新加的
     * 助手回复、工具调用及返回、下一条用户消息都不算进去，压缩明显滞后；我们补一个纯标量增量。
     *
     * ⚠ 为什么**用「上次真实值 + 锚点之后的估算增量」做增量会抖**：
     * 增量若按**位置**切，工具执行期间历史数组每轮都重排（流式正文落库前 `seq==0` 排最后，
     * 落库后 `seq` 变实际值 → 从末尾插回中间），同一个「锚点之后」前后指向完全不同的消息 →
     * 增量突变 → 数字一会多一会少。所以只做**纯标量**：`baselineUsage` 与 `baselineEstimate`
     * 取自**同一次请求**，相减 = 那次请求之后新增，与排序、插入、删除无关。
     *
     * ⚠ 为什么**不再取 `maxOf(全量估算, 修正值)`**：
     * 之前「外层取大」是为防数字抖落，但本地估算（尤其旧口径把整段工具结果连文件全文都算）
     * 一旦偏高，取大就直接盖过真实值，把 147K 顶成 432K/628K，还能把 512K 模型误判成超窗强制压缩
     * ——这就是反复修都修不掉「数字虚高/切 512K 被压缩」的根。估算现在已经收敛（thinking 剔除、
     * 旧工具输出按落盘提示计、全量兜底用同一公式），即便纯增量口径也不会抖；而本地估算只能
     * 估出**偏小**的解（投影后的保守下限），取大反而可能虚高，故丢弃。
     *
     * 只做两处估算：无锚兜底（这里）+ 结构性改动（回滚/恢复/编辑/焦点切换）后按新历史重算
     * （`refreshContextAfterHistoryChange`）——后者正是「恢复后立刻回到压缩前量级」的来源。
     */
    private fun effectiveContextTokens(conversationId: Long, msgs: List<ChatMessageModel>): Int {
        val anchor = usageAnchors[conversationId]
        if (anchor == null) {
            // 无真实锚时**不返回全量估算**：本地估算漏掉 provider 侧 input 缓存折扣，
            // 长工具循环会话会把真实 221K 估成 640K（真机实证，曾触发整个会话误压）。
            // 兜底改用「上次真实 usage」（conversationCtx / DB lastInputTokens），宁可短暂
            // 偏低也不虚高误导显示与压缩；全新会话无任何真实值时（两端都 ≤0）才用估算。
            val rec = conversationCtx[conversationId]
            val persisted =
                uiState.conversations.firstOrNull { it.id == conversationId }?.lastInputTokens ?: 0
            val lastReal = rec?.input ?: persisted
            return if (lastReal > 0) lastReal else estimateContextTokens(msgs).coerceAtLeast(1)
        }
        if (anchor.reported <= 0) return estimateContextTokens(msgs).coerceAtLeast(1)
        val estimate = estimateContextTokens(msgs)
        val corrected = anchor.reported + estimate - anchor.baselineEstimate
        // 真值优先：内容只增不减的阶段，修正值理应 ≥ 上次真实值；只在历史异常收缩（理论上
        // 不该发生在有效锚期间）时才钳到上次真实值，避免数字回落但绝不虚高。
        return corrected.coerceAtLeast(anchor.reported).coerceAtLeast(1)
    }

    /**
     * 工具定义（tools schema）的 token 估算（②）。
     *
     * 这份 schema **每轮全量下发**，属于「不随会话增长但永远占着」的固定开销；
     * 不计入预算就会系统性偏松。挂 MCP 后还会成倍增长——
     * `check(messageBudget > 0) { "工具定义已超过上下文预算，请减少启用的工具。" }`。
     */
    private fun toolSchemaTokens(): Int {
        if (currentToolSchemaTokens > 0) return currentToolSchemaTokens
        // 兜底：还没进入过发送流程（例如设置页/诊断读取）时，按当前设置现算一份。
        val tools = toolRegistry.getApiTools(
            enableWebSearch = true,
            enableFileTools = true,
            enableMultiAgent = true,
            agentMode = uiState.agentMode,
            permissionMode = uiState.toolPermissionMode
        )
        return estimateToolDefTokens(tools)
    }

    private fun estimateToolDefTokens(tools: List<com.wy.openx.tools.ToolDef>): Int {
        var total = 0
        for (t in tools) {
            total += com.wy.openx.data.util.TokenEstimator.bodyTokens(
                t.function.name + "\n" + t.function.description + "\n" + t.function.parameters.toString()
            )
        }
        return total
    }

    /**
     * 真正执行压缩：把早期消息压缩为摘要，保留最近若干**轮**对话。
     *
     * 四处关键设计（此前版本的问题所在）：
     * 1. 摘要输入**必须包含 toolResult / toolArguments**：工具调用消息的 content 只是
     *    「✅ bash 调用成功」这类占位文案，真正的大头在 toolResult。旧实现只取 content，
     *    导致重度工具会话压缩后关键信息全丢、下一轮继续超阈值。此处与 [estimateContextTokens]
     *    的口径保持一致（两者都计入工具内容）。
     * 2. 压缩请求自身**按窗口裁剪**：待压缩文本超过可用预算时从最早处丢弃并标注，避免
     *    「会话越满、压缩请求越长、越容易 400、于是永远压不动」的死结。
     * 3. 保留边界按**轮**（用户消息）对齐，不把工具调用组从中间切断。
     * 4. 压缩**可逆**：原始消息先整份备份进紧凑的内存快照，可在会话内展开/恢复。
     *
     * @param precomputedSummary 复用「工具循环内就地压缩」已经生成的摘要；非空则**跳过本次摘要
     *        请求**（同一段历史不必压缩两次、白烧一次额度）。
     * @param precomputedBoundary 复用那次压缩算出的折叠边界（slot.messages 的前 N 条）。
     *        与 [precomputedSummary] 成对传入。
     */
    /**
     * 回合结束后，把「工具循环内就地压缩」的结果落进会话历史。
     *
     * ## 为什么必须有这一步
     * 就地压缩只重建了本次请求的消息副本，会话历史一条没少。若不落库：
     * 下一轮 `buildTurnHistory()` 又从完整历史重建 → 上下文涨回压缩前量级 → 反复压缩。
     * 实测：压完显示 25K，下一轮真实请求又是 168K。
     *
     * ## 落库方式
     * 复用 [performCompaction] 既有的「打 folded 标记 + 插入摘要占位」路径（openx 的 folded
     * 等价于cutoff 水位线），并**复用那边已生成的摘要**，不重复发摘要请求。
     *
     * ## 为什么在回合结束时才做
     * 压缩发生在工具循环中途，此时本轮工具结果仍在往 `slot.messages` 追加；此刻整体替换
     * 槽位会与那些追加竞争、丢消息。等流式结束、`isSending` 已复位，槽位稳定了再落库。
     */
    private suspend fun persistPendingToolLoopCompaction(conversationId: Long) {
        // 所有分支都必须留痕：此处曾用 android.util.Log.w（只进 logcat、**不写日志文件**），
        // 于是「落库没发生」在日志里完全不可见，只能靠猜。改走 AppLogStore。
        val pending = com.wy.openx.tools.AppContextHolder.pendingToolLoopCompaction
        if (pending == null) return   // 绝大多数回合没有就地压缩，静默即可（登记侧另有留痕）
        if (pending.conversationId != conversationId) {
            AppLogStore.info("压缩诊断", "就地压缩落库跳过：会话不匹配 pending=${pending.conversationId} 本轮=$conversationId")
            return
        }
        // 无论后续成功与否都先清槽：这是「一次性消费」，留着会被下一轮重复折叠同一段。
        com.wy.openx.tools.AppContextHolder.pendingToolLoopCompaction = null
        val slot = slotOf(conversationId)
        val msgs = slot.messages
        // 边界定位：优先**来源消息 id**（历史 DTO 携带，最精确）；取不到再退**工具调用 id**
        // （本轮工具循环即时追加的 DTO 没有 srcMsgId，但带 toolCallId，槽位消息同样存着它）。
        // 两者都定位不到才跳过落库——绝不按条数猜：猜错会把整段历史折坏
        // （曾出现 358 条折成 1 条、每发消息就压缩的故障）。
        val srcId = pending.boundarySrcMsgId
        val boundary = when {
            srcId != null -> msgs.indexOfFirst { it.id == srcId }
            else -> {
                val tcid = pending.boundaryToolCallId
                if (tcid == null) -1 else msgs.indexOfFirst { it.toolCallId == tcid }
            }
        }
        if (boundary < 0) {
            AppLogStore.info(
                "压缩诊断",
                "就地压缩落库跳过：边界定位失败 (srcMsgId=${srcId ?: "无"} " +
                    "toolCallId=${pending.boundaryToolCallId ?: "无"} size=${msgs.size})"
            )
            return
        }
        if (boundary <= 0) {
            AppLogStore.info("压缩诊断", "就地压缩落库跳过：边界=$boundary 不能为 0（无可折叠头部）")
            return
        }
        if (boundary >= msgs.size) {
            AppLogStore.info("压缩诊断", "就地压缩落库跳过：边界越界 boundary=$boundary size=${msgs.size}")
            return
        }
        AppLogStore.info(
            "压缩诊断",
            "就地压缩落库: cid=$conversationId 边界=$boundary/${msgs.size} " +
                "(srcMsgId=${srcId ?: "无"} toolCallId=${pending.boundaryToolCallId ?: "无"}) " +
                "摘要${pending.summary.length}字符 → 落库后${msgs.size - boundary + 1}条"
        )
        performCompaction(
            conversationId,
            precomputedSummary = pending.summary,
            precomputedBoundary = boundary
        )
    }

    /**
     * 同一会话的压缩互斥闸门。
     *
     * 为什么需要：压缩有多个入口（回合结束自动压缩、工具循环内就地压缩落库、
     * 用户手动 /compact、超长重试前的强制压缩），此前**没有任何互斥**。两个入口撞在一起时：
     * 两边都基于同一份旧历史算边界、各自插入 marker+摘要 → 折叠范围重叠、摘要重复计入上下文，
     * 甚至把刚插入的摘要又折进去。分块顺序摘要后风险更大（两段摘要交错生成）。
     *
     * 语义是「跳过」而非「排队」：已经在压缩了就说明这段历史马上会被处理，
     * 第二个入口再压一遍没有意义，排队反而会让用户多等一次完整摘要。
     */
    private val compactionLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    private suspend fun performCompaction(
        conversationId: Long,
        precomputedSummary: String? = null,
        precomputedBoundary: Int? = null
    ): Boolean {
        val lock = compactionLocks.getOrPut(conversationId) { Mutex() }
        if (!lock.tryLock()) {
            AppLogStore.info("压缩诊断", "跳过：会话 $conversationId 已有压缩在进行，本次不重复压缩")
            return false
        }
        try {
            return performCompactionLocked(conversationId, precomputedSummary, precomputedBoundary)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun performCompactionLocked(
        conversationId: Long,
        precomputedSummary: String? = null,
        precomputedBoundary: Int? = null
    ): Boolean {
        val slot = slotOf(conversationId)
        val msgs = slot.messages
        if (msgs.size <= 5) {
            uiState = uiState.copy(logs = uiState.logs + "压缩跳过：消息数 ${msgs.size} ≤ 5")
            return false
        }

        var boundary = precomputedBoundary ?: compactionBoundary(msgs)
        if (boundary <= 0 || boundary >= msgs.size) {
            val turns = msgs.count { it.role == MessageRole.USER && !it.content.startsWith(SUMMARY_PREFIX) }
            uiState = uiState.copy(logs = uiState.logs + "压缩跳过：可压缩内容不足（用户轮数 $turns）")
            return false
        }
        // 复用外部边界时同样要收尾到「完整工具组之后」：就地压缩给的边界是尾部首条消息的
        // tool_call_id，若恰好落在某个工具组中间，折叠后剩下的工具结果会失去配对的
        // assistant(tool_calls) → 下一轮请求被 API 判 400。故与 compactionBoundary 走同样的收尾。
        if (precomputedBoundary != null) {
            while (boundary < msgs.size && msgs[boundary].toolName != null) boundary++
            if (boundary >= msgs.size) {
                android.util.Log.w(TAG, "压缩跳过：预计算边界越界")
                return false
            }
        }
        val compressList = msgs.take(boundary)
        val recentList = msgs.drop(boundary)

        // 【诊断】事后压缩的 token 构成，落进日志文件（原先只打 UI 面板，排查时看不到）。
        // 关键：把「压缩后残留」拆成 保留消息 / 工具schema / system消息 三块，
        // 才能判断 80K+ 的残留主要来自哪一块。正式定调后可删。
        run {
            val schemaTok = toolSchemaTokens()
            val sysMsgs = recentList.filter { it.role == MessageRole.SYSTEM }
            val sysTok = estimateBodiesTokens(sysMsgs)
            val recentTok = estimateBodiesTokens(recentList)
            AppLogStore.info(
                "压缩诊断",
                "事后: 窗口=${contextWindowTokensFor(conversationId)} keepMax=$COMPACT_KEEP_TOKENS_MAX " +
                    "boundary=$boundary | 压缩${compressList.size}条(≈${estimateBodiesTokens(compressList)}tok) " +
                    "保留${recentList.size}条(≈${recentTok}tok, 其中system${sysMsgs.size}条≈${sysTok}tok) " +
                    "工具schema≈${schemaTok}tok 预估合计≈${recentTok + schemaTok}tok"
            )
        }

        // 复用「工具循环就地压缩」已生成的摘要：同一段历史不压缩两次、不白烧一次摘要请求。
        // 该摘要已由 ChatRepository 剥过 thinking（见 summarizeForCompaction 的调用方），
        // 这里只做一次兜底剥离即可。
        var summary = precomputedSummary?.let { stripThinkingBlocks(it).trim() }.orEmpty()
        if (summary.isBlank()) {
            // 摘要输出上限独立于聊天「最大输出」：用 settings.compactionMaxTokens（默认 4096），
            // 覆盖掉 settings.maxTokens 后重发，避免写大文件的 16384 顺带把摘要请求也放大。
            val compSettings = settingsFor(conversationId).let { s ->
                val out = s.compactionMaxTokens.toIntOrNull()?.takeIf { it > 0 } ?: COMPACT_DEFAULT_OUTPUT_TOKENS
                s.copy(maxTokens = out.toString())
            }
            val generated = summarizeHistoryChunked(
                conversationId = conversationId,
                messages = compressList,
                settings = compSettings
            )
            if (generated == null) return false
            summary = generated
        }

        val summaryTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        // 压缩写入**成对两行**：marker（USER，锚点）+ summary（ASSISTANT）。
        // marker 是压缩卡的**时序锚点**——它落在压缩发生的那一刻，故卡片正好显示在
        // 「最后一个执行完的工具」下面；summary 紧随其后成为折叠历史的替代物。
        //
        // 之前只有一行 `[对话摘要]…`，靠内容前缀嗅探去找它在列表里的位置，而摘要 id 是压缩时
        // 新建的（可能很小）、它前面的折叠历史里又混着内存大 id → 卡片会跑到折叠历史**之前**。
        // 有了 marker + seq 显示序，位置由库统一决定，UI 不再需要猜。
        val markerMessage = ChatMessageModel(
            nextMessageId.incrementAndGet(), MessageRole.USER, "系统", summaryTime, COMPACTION_MARKER
        )
        val summaryMessage = ChatMessageModel(
            nextMessageId.incrementAndGet(), MessageRole.ASSISTANT, "系统", summaryTime, summary
        )
        // 上下文回放顺序：marker 打头、summary 紧随，然后才是保留的尾部
        // 。
        val compacted = listOf(markerMessage, summaryMessage) + recentList

        // 【强校验】压缩必须**真的变小**，否则不落库、原样保留
        // ContextCompactor 的最后一道 check：`compactedTokens < estimatedTokens`）。
        // 为什么必要：摘要若因异常膨胀（例如模型把整段独白连 thinking 一起吐出来，实测 11K），
        // 折叠后总量可能不降反升——此时落库等于把有用的历史换成一段更占地方的独白，纯亏。
        // 宁可放弃这次压缩（下一轮仍可再试），也不做负收益且不可逆的落库。
        val beforeTok = estimateBodiesTokens(msgs)
        val afterTok = estimateBodiesTokens(compacted)
        if (afterTok >= beforeTok) {
            uiState = uiState.copy(
                logs = uiState.logs + "压缩未减小上下文（$beforeTok → $afterTok tok），放弃本次落库"
            )
            AppLogStore.info(
                "压缩诊断",
                "压缩放弃: 未减小 before=${beforeTok}tok after=${afterTok}tok 摘要${summary.length}字符"
            )
            return false
        }

        // 不再 clearByConversation + 重写整库（那会把原始消息永久删掉、只剩内存备份、
        // 重启即失）。改为【打标记】：把被压缩的那段标记 folded=1。
        // 库里完整保留原文 → 可往上翻阅、可恢复；发送与压缩判定只读未折叠（context）流。

        // 【 `commitCompaction`】折叠 + 写 marker + 写摘要在**一个事务**里完成。
        //
        // 这就是「用户按暂停历史就消失」的根治点。此前这里是：cancel 两路 observer → 分多次写库
        // → 手工把内存对齐 → 再重建 observer。压缩常在回合 `finally` 里跑，此刻用户按「停止」
        // 会取消本轮协程，取消正好命中中间那个挂起点：
        //   · 折叠已提交、marker/摘要没插 → 那段历史既不进请求也不显示 → **消息整段消失**；
        //   · 摘要已插、折叠没提交 → 摘要与原文重复计入上下文，白烧 token；
        //   · 异常一抛，后面的 observer 重建不再执行 → 界面永久停在坏状态。
        // 收进单个 Room 事务后要么整体生效、要么整体回滚，取消完全碰不到它；
        // 且只触发一次 InvalidationTracker，UI 的**单一** Flow 更新一次，压缩卡立即出现，
        // 也不再需要任何内存侧手工对齐。
        val foldIds = compressList.map { it.id }.filter { it > 0 }
        if (foldIds.isEmpty()) return false
        // 快照基线：用于识别「事务提交期间新到」的消息（见下方回填处的 arrived）。
        val snapshotIds = msgs.asSequence().map { it.id }.toHashSet()
        // ⚠ 按**显式 id 集合**折叠，不用 `id <= maxOf{id}`：消息 id 有「库自增」与
        // 「内存 nextMessageId=毫秒×1000」两套来源，`<=` 划界假设「id 顺序 == 显示顺序」，
        // 一旦该段里混进一条内存大 id（rewind/编辑重写后确实会），`maxOf` 取到天文数字，
        // `id <= 天文数字` 会把**该保留的尾部也一并折叠** → 用户看到「消息全没了、只剩摘要」。
        // 按集合折叠只折我们真正要折的那些。
        // 【关键】`NonCancellable` 必须**同时包住**「事务提交」与「内存回填」两步，
        // 不能只包事务。踩过的坑（真机日志实证）：
        //
        //   18:15:34.225  用户手动停止                    ← 用户按暂停，协程被取消
        //   18:15:34.287  就地压缩落库: ... → 落库后15条   ← 事务**已提交**（数据进库了）
        //   （没有「压缩事务提交后」诊断行）              ← 提交后的回填代码**被取消**，没执行
        //
        // 结果：库里 marker/摘要都在、折叠也正确，但 `uiState.foldedMessages` 没更新 →
        // **压缩卡不显示**（用户报障「压缩了，卡片又不显示了」）。
        // 只把 DB 写做成原子事务，挡不住「提交之后还有代码」这件事——那段代码同样在
        // 可取消的协程里，取消点正好落在 `withContext(IO)` 之后的挂起处。
        //
        // 压缩落库是一次性的终态提交：要么完整做完（含内存对齐），要么整体不做。
        // 半途而废正是这个 bug 的来源。
        withContext(kotlinx.coroutines.NonCancellable) {
            withContext(Dispatchers.IO) {
                messageRepository.commitCompaction(
                    conversationId = conversationId,
                    foldIds = foldIds,
                    marker = markerMessage,
                    summary = summaryMessage
                )
            }
            // 事务已提交，立刻按**库里的权威状态**回填内存。
            //
            // 为什么必须在这里显式回填（而不是只等 Flow）：工具循环内的压缩发生在
            // `finally` 里，此时 `isSending` 还是 true，而观察者对 `isSending == true`
            // 的发射**刻意不回填上下文列表**（那是为了别让 DB 快照把正在流式的内存消息回退掉）。
            // 若只依赖 Flow，这次发射会被丢掉，压缩后的上下文要等到下一条消息落库才对上——
            // 中间那段时间顶部数字与实际请求内容不一致。
            //
            // 这不是「手工维护第二份状态」：Flow 随后会发来**同一份**数据，两者必然一致；
            // 这里只是把「写完立刻读」提前，保证压缩当刻就对齐。
            run {
                val (foldedAfter, ctxFromDb) = withContext(Dispatchers.IO) {
                    messageRepository.getFoldedMessages(conversationId) to messageRepository.getMessages(conversationId)
                }
                // 事务在 IO 上跑的这几十毫秒里，工具循环还在往下走：AI 已经拿压缩后的上下文
                // 接着调工具，新的 assistant(tool_calls) / tool 结果会追加进 slot.messages。
                // 拿库快照直接覆盖会把这些刚到的消息抹掉——它们既没进本次事务、UI 上也消失。
                // 故把「快照之后新到」的消息按原顺序接在库快照之后（它们 seq==0，天然排最后）。
                val ctxIds = ctxFromDb.asSequence().map { it.id }.toHashSet()
                val arrived = slot.messages.filter { it.id !in snapshotIds && it.id !in ctxIds }
                val ctxAfter = if (arrived.isEmpty()) ctxFromDb else ctxFromDb + arrived
                editSlot(conversationId) {
                    messages = ctxAfter
                    foldedMessages = foldedAfter
                    // `projectOnto` 只在 `loaded` 为 true 时才搬运 messages/foldedMessages
                    // （那是给「切会话时槽还是空的」兜底，别把界面清空）。
                    // 而这里我们**刚从库里读回权威状态**，压缩当刻必须投影到 UI，
                    // 不能再等 observer 发射 —— 否则卡片要晚一帧甚至晚到下一次暂停才出现
                    // （`state = state.copy(messages = …)` 换完权威状态 UI 立刻可见）。
                    loaded = true
                }
                // 【诊断】压缩后两端各有多少条、marker/摘要各几条：若「历史原文=0」说明折叠没落上，
                // 若「上下文里无 marker」说明提交有问题——都能一眼看出。
                AppLogStore.info(
                    "压缩诊断",
                    "压缩事务提交后: 历史原文${foldedAfter.size}条 上下文${ctxAfter.size}条 " +
                        "其中marker${ctxAfter.count { it.content == COMPACTION_MARKER }}条 " +
                        "foldIds=${foldIds.size}条 期间新增${arrived.size}条"
                )
            }
            // 压缩层数上限治理：超过 MAX_COMPACTION_LAYERS 层就把最老轮次整层清掉。
            pruneOldCompactionLayers(conversationId)
        }
        // 压缩后立即把顶部上下文按压缩结果重算：此前只等下一次 API 返回真实 usage 才更新，
        // 用户压缩完盯着的数字纹丝不动，看起来像「没生效」。
        // 压缩后这个数是**估算**（还没有新的 provider usage），报告锚随之作废，
        // 否则下一轮会拿「旧 reported + 增量」去加，基数是压缩前的量级。
        val compactedTokens = estimateContextTokens(compacted)
        usageAnchors.remove(conversationId)
        // 【A：压缩后同步记账值】conversationCtx 与 DB 里仍是**压缩前**的真实 usage（大值）。
        // 若不同步，紧接着 observeMessagesForConversation → ensureUsageAnchor 会拿旧值重建报告锚
        // （真机日志实证：压缩到 ≈18K 后建锚 rep=34952），使自动压缩判定与圆环先降后弹回压缩前量级，
        // 再被下一次真实 usage 纠正。这里用压缩后的估算作为「下次真实 usage 回来前」的临时记账值
        // （与压缩结果同量级、不虚高），并写回 DB 供重启后回读。
        val prevReq = conversationCtx[conversationId]?.requestCount
            ?: uiState.conversations.firstOrNull { it.id == conversationId }?.requestCount ?: 0
        conversationCtx[conversationId] = CtxRecord(input = compactedTokens, requestCount = prevReq)
        viewModelScope.launch {
            conversationRepository.updateLastInputTokens(conversationId, compactedTokens)
        }
        if (conversationId == _uiState.currentConversationId) {
            uiState = uiState.copy(lastInputContext = compactedTokens)
        }
        uiState = uiState.copy(logs = uiState.logs + "上下文已压缩（${compressList.size} 条消息 → 摘要，可往上翻阅原文）")
        // 【诊断】压缩后重建结果：消息侧 + 工具schema = 下一轮预计上下文。
        run {
            val bodyTok = estimateBodiesTokens(compacted)
            AppLogStore.info(
                "压缩诊断",
                "事后完成: 摘要${summary.length}字符(≈${estimateBodiesTokens(listOf(summaryMessage))}tok) " +
                    "重建后消息${compacted.size}条(≈${bodyTok}tok) + 工具schema≈${toolSchemaTokens()}tok " +
                    "= 下一轮预估≈${bodyTok + toolSchemaTokens()}tok"
            )
        }
        return true
    }

    /**
     * 清理超上限的压缩轮次：单会话最多保留 [MAX_COMPACTION_LAYERS] 层压缩历史，更早的自动删除。
     *
     * 每次压缩在库尾追加「marker + 摘要」并折叠一批原文，层数只增不减；长跑会话（高频工具
     * 循环反复触发压缩）折叠区会无限膨胀。这里在**每次压缩落库后**清一遍：层数超过上限时，
     * 把**最老**轮次整层删掉（其 marker、摘要、折叠原文全部 DELETE），只留最新的 N 层。
     *
     * 为什么区域删除是正确的：marker 按 seq 递增 = 按压缩先后递增，且显示序里任何一层都是
     * 「本层折叠原文 < 本层 marker < 本层摘要」，旧层永远垫底 → 最老层恒占据显示序开头。
     * 因此「从首条到第 toDelete 个 marker 紧随的摘要」这段整删，恰好等于最老 toDelete 层。
     * 与恢复会话「只展开上一段」的分层逻辑（rewindMessage 的 markerIdx/segStart）同一套索引。
     *
     * 幂等：每次压缩都会重新核对层数，被取消/中断后下一次压缩自动自愈。
     * 区域近似：若后一层把更早轮次的老人尾也折过（跨轮折叠），会连带清理几条古老消息——
     * 这正是本机制要控住的存储膨胀，可接受。
     */
    private suspend fun pruneOldCompactionLayers(conversationId: Long) {
        val all = withContext(Dispatchers.IO) {
            messageRepository.getAllMessagesIncludingFolded(conversationId)
        }
        val markerIdx = all.mapIndexedNotNull { i, m -> if (m.content == COMPACTION_MARKER) i else null }
        if (markerIdx.size <= MAX_COMPACTION_LAYERS) return
        val toDelete = markerIdx.size - MAX_COMPACTION_LAYERS
        // 最后一个被删层的 marker 下标；其 +1 为紧随摘要下标 → 删除段 = 下标 [0 .. marker+1]。
        val cut = markerIdx[toDelete - 1] + 1
        val deleteIds = all.take(cut + 1).map { it.id }.filter { it > 0 }
        if (deleteIds.isEmpty()) return
        val deleteSet = deleteIds.toHashSet()
        withContext(Dispatchers.IO) {
            messageRepository.deleteByIds(conversationId, deleteIds)
        }
        editSlot(conversationId) {
            messages = messages.filterNot { it.id in deleteSet }
            foldedMessages = foldedMessages.filterNot { it.id in deleteSet }
            loaded = true
        }
        if (conversationId == _uiState.currentConversationId) {
            _uiState = _uiState.copy(
                messages = _uiState.messages.filterNot { it.id in deleteSet },
                foldedMessages = _uiState.foldedMessages.filterNot { it.id in deleteSet }
            )
        }
        AppLogStore.info(
            "压缩诊断",
            "清理超上限压缩历史: cid=$conversationId 层数${markerIdx.size} 上限$MAX_COMPACTION_LAYERS " +
                "删最老${toDelete}层 ${deleteIds.size}条 (cut=$cut)"
        )
    }

    /**
     * 计算压缩边界：按 **token 预算** 从末尾向前逐条保留消息。
     * selectTailStartIndex），超预算即停，避免「最近一轮超大（几十条工具消息）被整轮
     * 无条件保留、压缩几乎压不动」的问题——先按单条累加到塞不进预算为止，再向前
     * 回退到最近一条用户消息之后，保证压缩后的会话以用户请求/摘要开场而非孤立的
     * 工具调用（拆回 API 协议时 tool result 不会失去配对的 tool_calls）。
     *
     * @return 边界下标（>=0 表示 [0, boundary) 将被压缩）；无可压缩内容时返回 0。
     */
    private fun compactionBoundary(msgs: List<ChatMessageModel>): Int {
        if (msgs.size <= COMPACT_KEEP_MIN_TURNS + 1) return 0
        var userTurnCount = 0
        msgs.forEach { if (it.role == MessageRole.USER && !it.content.startsWith(SUMMARY_PREFIX)) userTurnCount++ }
        if (userTurnCount <= COMPACT_KEEP_MIN_TURNS) return 0

        // 保留预算：绝对上限 4K（不随窗口放大），至少 1000 保证不空。
        val budget = COMPACT_KEEP_TOKENS_MAX.coerceAtLeast(1000)
        // 逐条从末尾向前累加，直到塞不进预算为止（至少保留最后一条消息）。
        var total = 0
        var boundary = msgs.size
        for (i in msgs.indices.reversed()) {
            // ⚠ 必须用 estimateBodiesTokens（**不含**工具 schema）逐条估算。
            // 原用 estimateContextTokens，它把 toolSchemaTokens()（约 13K，每轮全量下发的固定开销）
            // 也加进每一条 → 单条「体积」恒 > 预算(4K) → 循环第一轮就撑爆、boundary 落到 size-1，
            // **每次压缩都把整个会话折成 1 条**（实测 209 条 → 保留 1 条，历史几乎全灭）。
            // 固定开销不属于任何单条消息，不能摊到逐条累加里。
            val next = estimateBodiesTokens(listOf(msgs[i]))
            if (total + next > budget && boundary < msgs.size) break
            total += next
            boundary = i
        }
        if (boundary >= msgs.size) return 0

        // 回退到最近一条真实用户消息之后：保证 tail 以用户请求开场（而不是一条孤立
        // 工具/助手消息），拆回协议时 tool result 有配对的 tool_calls，避免 API 400。
        if (boundary < msgs.size) {
            var ti = boundary
            while (ti < msgs.size && !(msgs[ti].role == MessageRole.USER && !msgs[ti].content.startsWith(SUMMARY_PREFIX))) ti++
            if (ti < msgs.size) boundary = ti
        }
        // 防御：boundary 指向工具消息时再向后挪一步，避免截在工具调用消息正中间。
        while (boundary < msgs.size && msgs[boundary].toolName != null) boundary++
        if (boundary >= msgs.size) return 0
        return boundary
    }

    /** 把单条消息格式化为摘要输入文本：工具消息带上参数与结果（截断），这是旧实现遗漏的关键内容。 */
    private fun formatMessageForSummary(m: ChatMessageModel): String = buildString {
        append("[").append(m.author).append("] ")
        val body = ChatUtils.removeThinkingContent(m.content).ifBlank { m.content }
        if (body.isNotBlank()) append(body)
        // 工具调用：带上参数，摘要才知道「对哪个文件/执行了什么命令」
        m.toolName?.let { name ->
            append("\n  <调用工具 ").append(name).append(">")
            m.toolArguments?.takeIf { it.isNotBlank() && it != "{}" }?.let { append(it.take(COMPACT_TOOL_RESULT_CHARS)) }
        }
        // 工具结果：真正承载信息量的部分
        m.toolResult?.takeIf { it.isNotBlank() }?.let {
            append("\n  <结果> ").append(it.take(COMPACT_TOOL_RESULT_CHARS))
            if (it.length > COMPACT_TOOL_RESULT_CHARS) append("…（截断）")
        }
        // 早期图片只留标记，避免摘要完全忽略其存在
        m.imageUri?.takeIf { it.isNotBlank() }?.let { append("\n  <附图>") }
    }

    /**
     * 压缩请求可用的输入字符预算：按**本会话**模型上下文窗口预留提示词与输出的余量后折算。
     *
     * 【会话级模型串台修复·同源第 3 处】原来不接参数、自己读全局 `uiState.settings`，
     * 而同一个调用链上游的 [compactionBoundary] 已经按会话算了窗口。两处口径不一致时：
     * - 512K 会话碰上全局恰为 1M：预算算成近 1M，**原文塞进超出窗口的摘要请求** → 模型侧超限报错 → 压缩失败；
     * - 1M 会话碰上全局恰为 512K：白砍掉近一半本可保留的原文 → 摘要质量下降、历史丢细节。
     *
     * 平时不显，因为压缩通常发生在用户正看着的会话，全局值恰好就是它；
     * **后台会话压缩或快速切会话才会踩到** —— 而那正是会话级模式存在的意义。
     */
    /**
     * 分块顺序摘要：把待压缩历史切成若干段，**每段都带着上一段的摘要**继续总结。
     *
     * 为什么不再一次性摘要：
     * 旧实现超出预算时直接 `takeLast(budget)` 丢掉最早的内容——最老那段**永远没进摘要**，
     * 只留一句「前段历史已省略」。长会话里最早的需求与约束就这样消失了。
     * 分块后每段都在预算内，信息按顺序滚雪球式精炼，不会整段丢失。
     *
     * 任何一段失败（网络错误 / 只吐思维链）都整体放弃：返回 null，保留原历史，
     * 绝不用半截摘要覆盖完整历史。
     *
     * @return 摘要正文；null 表示失败（调用方已记录日志与提示）。
     */
    private suspend fun summarizeHistoryChunked(
        conversationId: Long,
        messages: List<ChatMessageModel>,
        settings: com.wy.openx.data.model.AppSettings
    ): String? {
        val budgetChars = compactionInputBudgetChars(conversationId)
        val chunks = chunkHistoryForSummary(messages, budgetChars)
        if (chunks.isEmpty()) return null

        var summary = ""
        chunks.forEachIndexed { index, chunk ->
            // 已折叠的历史里可能本来就有一段摘要（多次压缩）：把它当「上一段摘要」的起点，
            // 否则第二层压缩会把第一层的结论丢掉。
            if (summary.isBlank()) {
                summary = messages.asSequence()
                    .map { it.content }
                    .firstOrNull { it.startsWith(SUMMARY_PREFIX) }
                    ?.removePrefix(SUMMARY_PREFIX)?.trim().orEmpty()
            }
            val prev = if (summary.isBlank()) "（这是第一段，没有更早的摘要。）"
            else "（上一段已总结的内容，供衔接，不要原样重复输出）\n$summary"
            val userPrompt = "以下是AI助手与用户对话历史的第 ${index + 1}/${chunks.size} 段。" +
                "请把**这一段**压缩成简洁摘要，保留：\n" +
                "1. 用户的核心需求与约束\n2. 已确认的关键决策\n3. 已修改/创建的文件及其作用\n" +
                "4. 已执行命令的重要结果与结论\n5. 尚未完成的事项\n\n" +
                "$prev\n\n<本段历史>\n$chunk\n</本段历史>"

            uiState = uiState.copy(
                logs = uiState.logs + "正在压缩上下文（第 ${index + 1}/${chunks.size} 段，共 ${messages.size} 条）..."
            )
            // 压缩请求与正常聊天同通道：传 onStreamDelta 让 runChat 走 streamChat 流式路径。
            // 若走 syncChat（非流式），部分中转/网关只支持流式会直接失败。
            val compProvider = resolveSpecialModel("compaction") ?: effectiveProviderFor(conversationId)
            val compT0 = System.currentTimeMillis()
            var compIn = 0
            var compOut = 0
            var compCache = 0
            val result = chatRepository.sendMessage(
                settings, userPrompt,
                onTokens = { compIn += it },
                onStreamDelta = {},
                onCompletionTokens = { compOut += it },
                onCacheHit = { hit, tokens -> if (hit && tokens > 0) compCache += tokens },
                forcedProvider = compProvider
            )
            // 压缩摘要是真实调用，用量同样进统计（kind=compaction）
            recordLlmCall("compaction", conversationId, compProvider, compIn, compOut, compCache, compT0,
                (System.currentTimeMillis() - compT0).toInt(), result.isSuccess, result.exceptionOrNull()?.message)
            // 摘要必须先剥掉思维链：带思考的模型会把内心独白写进摘要，此后每轮都当上下文重发。
            val generated = result.getOrNull()?.let { stripThinkingBlocks(it) }?.trim().orEmpty()
            if (generated.isBlank()) {
                val errText = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: "模型只返回了思维链、没有摘要"
                uiState = uiState.copy(
                    logs = uiState.logs + "上下文压缩失败（第 ${index + 1} 段：$errText），本次不压缩继续发送"
                )
                appendCompactionNotice("failed", null, 0, conversationId)
                return null
            }
            summary = generated
        }
        return summary.takeIf { it.isNotBlank() }
    }

    /**
     * 把待压缩消息按字符预算切段。
     *
     * 单条消息超过整段预算时，取**头 60% + 尾 40%**（尾部常是报错/结论），
     * 不直接砍掉尾部。段内按消息边界切分，避免把一条消息劈成两半破坏语义。
     */
    private fun chunkHistoryForSummary(messages: List<ChatMessageModel>, budgetChars: Int): List<String> {
        // 预留指令、上一段摘要与分隔符的余量，避免「算着够、发出去超」
        val available = (budgetChars - 4_000).coerceAtLeast(2_000)
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) {
                chunks += sb.toString()
                sb.setLength(0)
            }
        }
        messages.forEach { msg ->
            val text = formatMessageForSummary(msg)
            if (text.length > available) {
                flush()
                val head = (available * 0.6).toInt()
                val tail = (available - head)
                chunks += text.take(head) + "\n…（本条过长，中间已省略）…\n" + text.takeLast(tail)
            } else {
                if (sb.length + text.length + 2 > available) flush()
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text)
            }
        }
        flush()
        return chunks
    }

    private fun compactionInputBudgetChars(conversationId: Long): Int {
        val window = contextWindowTokensFor(conversationId)
        val usable = (window - COMPACT_RESERVE_TOKENS).coerceAtLeast(window / 2)
        // estimateContextTokens 口径：中文≈1 token/字、英文≈1/4 字符/token，取保守的 1 字符 ≈ 1 token
        return usable.coerceAtLeast(2000)
    }

    /**
     * 是否存在可展开/恢复的压缩历史。
     * 真源改成「库里有折叠消息」而不是内存 map：压缩历史已落库，
     * 内存 map 重启即失——用 map 判定会让重启后恢复按钮凭空消失。
     */
    fun hasCompactionBackup(): Boolean = foldedCountOf(uiState.currentConversationId) > 0

    /** 当前会话折叠历史的信息（折叠条数 / 摘要），无折叠返回 null。 */
    fun compactionBackupInfo(): Pair<Int, String>? {
        val cid = uiState.currentConversationId
        val folded = slotOf(cid).foldedMessages
        if (folded.isEmpty()) return null
        // 摘要取库里那条 [对话摘要] 占位消息（压缩时插入，未折叠 = 属上下文）
        val summary = slotOf(cid).messages.firstOrNull { it.content.startsWith(SUMMARY_PREFIX) }
            ?.content?.removePrefix(SUMMARY_PREFIX)?.trim().orEmpty()
        return folded.size to summary
    }

    /** 折叠历史条数（不依赖内存 map，直接数槽里的折叠列表）。 */
    private fun foldedCountOf(conversationId: Long): Int = slotOf(conversationId).foldedMessages.size

    /** 展开/收起压缩折叠区（按会话记，切回来保持上次状态）。 */
    fun toggleFoldedExpanded() {
        val cid = uiState.currentConversationId
        editSlot(cid) { foldedExpanded = !foldedExpanded }
    }

    /** 恢复压缩前的原始消息（撤销本次压缩）。原始消息已永久保留在库，这里只需取消折叠并删除摘要占位。 */
    fun restoreCompaction() {
        val conversationId = uiState.currentConversationId
        // 真源是「库里有没有折叠消息」，不是内存 map（重启后 map 空，按钮会点了没反应）。
        if (foldedCountOf(conversationId) == 0) {
            uiState = uiState.copy(logs = uiState.logs + "没有可恢复的压缩历史")
            return
        }
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                // 【对`rewindConversation`】删压缩产物 + 取消全部折叠，**一个事务**。
                //
                // cutoffId 传 Long.MAX_VALUE = 不删任何消息（「整会话恢复」语义）。
                // 事务保证「已解折叠但摘要还在」（历史重复）或「已删摘要但仍折叠着」
                // （那段历史不可见）这类中间态不会出现——即便用户此刻按「停止」。
                //
                // 一并删掉三类「压缩期产物」：
                //   1. `[上下文已压缩]` marker：新压缩的锚点；
                //   2. `[对话摘要]…`：升级前的旧摘要行，留着会被当成真实用户消息再次发进请求；
                //   3. `📦 上下文已压缩…` / `⚠️ 上下文压缩失败…` 通知：恢复后它们是**假信息**
                //      （摘要已不存在，却还在说「已压缩成摘要」）。用户反馈：恢复后聊天页仍挂着
                //      这条提示。与其加条件判断，不如按前缀统一清掉。
                messageRepository.rewindConversation(
                    conversationId = conversationId,
                    cutoffId = Long.MAX_VALUE,
                    deletePrefixes = listOf(
                        COMPACTION_MARKER,
                        SUMMARY_PREFIX,
                        COMPACTION_NOTICE_PREFIX,
                        COMPACTION_NOTICE_FAIL_PREFIX
                    )
                )
                // 恢复后上下文变大且报告锚已作废：必须**立刻按恢复后的消息重算顶部数字**，
                // 否则圆环/数字还停在压缩后的小值上，用户看到「恢复了但数值没变」——正是此前的报障。
                // 复用统一入口（同样作废锚 + 重算显示值）。
                val msgs = messageRepository.getMessages(conversationId)
                refreshContextAfterHistoryChange(conversationId, msgs)
                msgs
            }
            uiState = uiState.copy(
                logs = uiState.logs + "已恢复压缩前的原始对话（${restored.size} 条）"
            )
        }
    }



    /**
     * 重写某会话的**上下文**消息，同时保住已折叠的历史。
     *
     * 撤销/重做/编辑/回退/重新生成截断这 5 条路径原本都是「clearByConversation +
     * 按内存列表重插」。压缩改为打标记后，内存列表（uiState.messages）**只含未折叠消息**，
     * 照原样沿用会连带删掉折叠区里那些原文——表现为「压缩后随便点一次撤销，
     * 历史就再也翻不回来了，且没有任何提示」。
     *
     * 做法：先把折叠行捞出来（clear 会一并删掉），写完上下文再写回。
     *
     * 顺序按**显示序**而不是 id：显示序由 `MessageEntity.seq` 统一分配，插入顺序即显示顺序。
     * 折叠行恒在上下文行之前（压缩把「较早的那段」标折叠，marker/摘要/尾部在其后），
     * 故直接 `folded + messages` 即可，**不要**按 id 排 —— id 有库自增与内存时间戳两套来源，
     * 混排会再次把折叠历史甩到错误位置。
     */
    private suspend fun rewriteContextPreservingFolded(conversationId: Long, messages: List<ChatMessageModel>) {
        val folded = messageRepository.getFoldedMessages(conversationId)
        messageRepository.replaceAllMessages(conversationId, folded + messages)
    }

    fun testCurrentApi(testSettings: AppSettings, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val startTime = System.nanoTime()
            val result = chatRepository.sendMessage(testSettings, "请回复：接口连接测试成功")
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            val msg = result.getOrElse { it.message ?: "未知错误" }
            onResult(if (result.isSuccess) "连通 · ${elapsedMs}ms · $msg" else "测试失败：$msg（${elapsedMs}ms）")
        }
    }

    /** 对某个已保存的模型配置单独测速，毫秒结果缓存到 presetTestStates 供设置页逐项展示。 */
    fun testPreset(preset: ApiPreset) {
        if (presetTestStates[preset.name]?.testing == true) return
        viewModelScope.launch {
            presetTestStates = presetTestStates + (preset.name to PresetTestState(testing = true))
            val testSettings = uiState.settings.copy(
                provider = preset.provider, baseUrl = preset.baseUrl,
                apiKey = preset.apiKey.ifBlank { uiState.settings.apiKey },
                model = preset.model,
                enableStream = false, autoToolCall = false
            )
            val startTime = System.nanoTime()
            val result = chatRepository.sendMessage(testSettings, "请回复：接口连接测试成功")
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            val ok = result.isSuccess
            val detail = result.getOrElse { it.message ?: "未知错误" }
            presetTestStates = presetTestStates + (preset.name to PresetTestState(
                testing = false, latencyMs = elapsedMs,
                message = if (ok) "连通" else "失败：$detail"
            ))
        }
    }

    /**
     * 工具审批：主链路与**子代理**共用同一套规则。
     * 「弹窗确认」档位下，写文件/执行命令类工具必须用户点头；只读类直接放行。
     * 子代理（尤其 general 角色，继承全部工具）也走这里，否则权限档位对子任务形同虚设。
     *
     * ## shell 类工具（terminal）走**指令前缀**粒度（[com.wy.openx.tools.ToolPermissionPolicyEngine]）
     * 这是与旧实现的关键差异：旧实现只按整工具名判断 `toolAllowList`，
     * 于是用户对 `terminal` 点一次「始终允许」，此后 `rm -rf /` 也永久放行。
     * 现在：
     * - 命中内置只读白名单（`ls`/`git status`…）→ 直接放行，不打扰用户；
     * - 命中已记忆的**指令前缀** → 放行；
     * - 不可静态判定（命令替换/子 shell/绝对路径重定向）→ 弹窗且**禁用**「始终允许」；
     * - 「始终允许」记忆的是 `git pull` 这种前缀，而不是整个 `terminal`。
     */
    private fun buildToolApprover(requestConversationId: Long): (suspend (String, String) -> Boolean)? {
        // 按**发起本轮的会话**的档位判定：用全局 settings 或当下前台档，
        // 会让「A 选了弹窗、B 选了全自动」时 B 的审批口去问 A（或反过来该问不问）。
        val mode = effectivePermissionMode(requestConversationId)
        if (mode != "confirm" && mode != "workspace") return null
        return approver@{ toolName, toolArgs ->
            val cleanName = toolName.removePrefix("functions.").trim()
            val argsMap = parseToolArgs(toolArgs)

            // 需要弹窗确认的两类情况：
            // 1) 写/执行类工具（writeFile / editFile / terminal …，见 WRITE_AND_EXEC_TOOLS）；
            // 2) 「读写混合」工具的**写入动作**：file_manager 的 write/delete、browser 的
            //    click/fill/evaluate 等。这两个工具整工具名不在 WRITE_AND_EXEC_TOOLS 里，
            //    只看工具名会让它们的写入整个绕过确认；而整工具都要确认又会把
            //    read/list、getText 这些纯观察动作也拖进弹窗。所以按 action 判。
            // 其余（各读/查询类工具、混合工具的只读动作）一律放行，不打扰用户。
            // ⚠ 别把 file_manager 直接加进 WRITE_AND_EXEC_TOOLS：那份名单还参与「只读模式下发」
            // 和子代理的失败关闭判定，加进去会让只读模式下 file_manager 整个不下发。
            val needsConfirm = toolRegistry.isWriteOrExecTool(cleanName) ||
                isMixedToolWriteAction(cleanName, argsMap)
            if (!needsConfirm) return@approver true

            // 「仅工作区修改」档：工作区内直接放行；越界则落到下面走弹窗，由用户自己决定。
            if (mode == "workspace" && isWithinWorkspace(cleanName, argsMap)) return@approver true

            // shell 类工具：先走指令级策略引擎（ALLOW 直接放行 / DENY 直接拒 / ASK 弹窗）
            if (ToolPermissionPolicyEngine.isShellTool(cleanName)) {
                val eval = ToolPermissionPolicyEngine.evaluate(cleanName, argsMap, toolPermissionRuleStore.load())
                when (eval.verdict) {
                    ToolPermissionPolicyEngine.Verdict.ALLOW -> return@approver true
                    ToolPermissionPolicyEngine.Verdict.DENY ->
                        return@approver false // 拒绝：以 false 回给工具层，让 AI 收到失败结果
                    ToolPermissionPolicyEngine.Verdict.ASK -> {
                        // 继续走下面的弹窗流程，并把评估结果挂到待确认信息上供「始终允许」使用
                    }
                }
                // 记下本次评估，供 pendingToolCall / 「始终允许」读取（按会话隔离）
                editSlot(requestConversationId) {
                    pendingPermissionEval = PendingPermissionEval(
                        toolName = cleanName,
                        rememberablePatterns = eval.rememberablePatterns,
                        rememberDisabledReason = eval.rememberDisabledReason
                    )
                }
            } else if (uiState.settings.toolAllowList.contains(cleanName)) {
                return@approver true
            }

            val slot = slotOf(requestConversationId)
            val deferred = CompletableDeferred<Boolean>()
            slot.toolConfirmDeferred = deferred
            editSlot(requestConversationId) {
                pendingToolCall = PendingToolCallInfo(
                    toolName = cleanName, toolCallId = toolName,
                    arguments = toolArgs,
                    description = buildToolDescription(cleanName, toolArgs),
                    actionLine = buildToolActionLine(cleanName, argsMap),
                    extras = buildToolExtras(argsMap),
                    alwaysAllowHint = buildAlwaysAllowHint(cleanName, requestConversationId)
                )
            }
            deferred.await()
        }
    }

    /**
     * 「读写混合」工具的参数动作是否会改状态（file_manager / browser）。
     *
     * 按 action 判而不是按工具名：file_manager 的 read/list 与 browser 的 getText/screenshot
     * 属于纯观察，不该拖进弹窗；而 write/delete、click/fill/evaluate 必须问一次。
     * 白名单与子代理策略共用 [com.wy.openx.tools.SubAgentToolPolicy.readOnlyActionsOf]，
     * 避免两处口径漂移；拿不到 action 时按「会改状态」处理——宁可多问一次，也不要漏放行。
     */
    private fun isMixedToolWriteAction(name: String, args: Map<String, Any>): Boolean {
        val readOnly = com.wy.openx.tools.SubAgentToolPolicy.readOnlyActionsOf(name)
        if (readOnly.isEmpty()) return false // 不是混合工具，不归这里管
        val action = (args["action"] as? String)?.trim()?.lowercase() ?: return true
        return action !in readOnly
    }

    /** 把工具参数 JSON 字符串解析成 Map（解析失败返回空 Map，交给引擎按整工具兜底）。 */
    private fun parseToolArgs(toolArgs: String): Map<String, Any> {
        if (toolArgs.isBlank()) return emptyMap()
        val mapType = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
        return runCatching {
            com.google.gson.Gson().fromJson<Map<String, Any>>(toolArgs, mapType)
        }.getOrNull() ?: emptyMap()
    }

    /**
     * 工作流的**逐步骤**权限闸门。与 [buildToolApprover] 的区别有三点，都是被逼出来的：
     *
     * 1. 三个档位都要管。`buildToolApprover()` 在非 confirm 档返回 null，只读档是靠
     *    `getApiTools()` 不下发写工具来兜底的——但工作流步骤是**已存的 JSON**，
     *    不经过那次过滤，所以只读档必须在引擎里硬拦，否则只读形同虚设。
     * 2. 串行化弹窗。工作流一批最多并行 3 步，而确认 UI 只有一个
     *    `toolConfirmDeferred` 槽位；并发弹窗会互相覆盖，先弹的那个永远等不到结果而挂死。
     * 3. 返回拒绝**原因**而非 Boolean，好区分「用户拒绝」和「只读档禁止」。
     */
    private fun buildWorkflowStepGate(requestConversationId: Long): com.wy.openx.tools.WorkflowStepGate = { toolName, toolArgs ->
        // 写成纯表达式、不用 return：typealias 的 lambda 标签不接受 return@WorkflowStepGate
        if (!toolRegistry.isWriteOrExecTool(toolName)) {
            null
        } else {
            val mode = effectivePermissionMode(requestConversationId)
            when (mode) {
                "readonly" -> "只读模式禁止执行写文件/命令类工具「$toolName」"
                // 「仅工作区修改」：工作区内放行；越界则与「弹窗确认」档走同一条路，交给用户决定
                "confirm", "workspace" -> if (
                    mode == "workspace" &&
                    isWithinWorkspace(toolName.removePrefix("functions.").trim(), parseToolArgs(toolArgs))
                ) {
                    null
                } else if (uiState.settings.toolAllowList.contains(toolName)) {
                    null
                } else {
                    // 必须持锁：一批并行步骤若同时要审批，后一个会覆盖 toolConfirmDeferred
                    workflowConfirmMutex.withLock {
                        val approver = buildToolApprover(requestConversationId)
                        if (approver == null) null
                        else if (approver(toolName, toolArgs)) null
                        else "已拒绝执行「$toolName」"
                    }
                }
                else -> null
            }
        }
    }

    /**
     * 子代理进度行 → 当前正在运行的 subagent_dispatch 工具卡片。
     * 子代理在仓库层并行运行，若不回传，父级这次调用就是一段几分钟的黑盒。
     * 只保留最近若干行，避免长任务把卡片撑爆。
     */
    private fun appendSubAgentProgress(line: String, requestConversationId: Long) {
        val text = line.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return
        val slot = slotOf(requestConversationId)
        val target = slot.messages.lastOrNull { it.toolName == "subagent_dispatch" && it.toolResult == null }
            ?: return
        val existing = target.toolProgress.orEmpty()
        val merged = (existing + "\n" + text).trim().lines().takeLast(40).joinToString("\n")
        val idx = slot.messages.indexOfFirst { it.id == target.id }
        if (idx < 0) return
        editSlot(requestConversationId) {
            messages = messages.toMutableList().also { it[idx] = target.copy(toolProgress = merged) }
        }
    }

    private fun resolveAgentForTool(toolName: String, toolArgs: String): String = "main"

    /**
     * 任务面板写入锁。
     *
     * **必要性**：设置里开启「并行执行批内工具」后，同批工具会在 `Dispatchers.IO` 上
     * 通过 `async` 并发执行（见 `ChatRepository` 的 parallelMode 分支）。而任务的
     * add/update/remove 都是「读 tasks → 拼新列表 → 写回」三步，**不是原子操作**：
     * 两个并发 add 各自读到同一份旧列表、各自写回「旧列表 + 自己那条」，
     * 后写者覆盖先写者 → 丢任务。用户实测：让 AI 建 4 个任务只显示 3 个。
     *
     * **为什么用 `synchronized` 而非 `Mutex`**：这三处都是纯内存操作（微秒级），
     * 且 [addTask] 等还要被 UI 主线程直接调用（非 suspend 上下文）。
     * 换成挂起锁需要把签名改成 `suspend` 并波及所有调用点（含 MainActivity 的
     * 回调 lambda），代价远大于收益。锁内不做任何阻塞 IO，不会卡住主线程。
     */
    private val taskWriteLock = Any()

    /**
     * 每会话「正在落库」的协程（key = conversationId）。
     *
     * 落库是「整体替换快照」语义，所以**最新一次写入应当胜出**。若不管顺序直接
     * `viewModelScope.launch{}`，短时间内连续两次写入的完成顺序无法保证，
     * 先发的可能后落库 → 库里留下旧快照。
     * 这里用「取消上一次、只跑最新一次」来保证最终落库的是最新快照
     *（与 `deltaFlushJob` 同一套路）。取消是安全的：Room 的事务会回滚。
     */
    private val taskPersistJobs = HashMap<Long, Job>()

    /**
     * 把某会话当前的槽位任务清单写入数据库（异步，不阻塞调用方）。
     *
     * 必须在 `editSlot` **之后**调用，这样读到的才是最新快照。
     */
    private fun persistTasks(conversationId: Long) {
        if (conversationId < 0) return   // 会话尚未建立（新建中的临时态），无可持久化
        val snapshot = slotOf(conversationId).tasks
        taskPersistJobs[conversationId]?.cancel()
        taskPersistJobs[conversationId] = viewModelScope.launch {
            runCatching { taskRepository.replace(conversationId, snapshot) }
                .onFailure {
                    // 落库失败不影响 UI（内存里还有），只记日志。
                    com.wy.openx.data.log.AppLogStore.warn("TaskPersist", "任务持久化失败: ${it.message}")
                }
        }
    }

    /**
     * UI 手动新增一条任务（面板上的「+」）。排到清单末尾。
     *
     * AI 侧不走这里——它用快照式的 `todo` 工具整体替换（见 [replaceTasksFromSnapshot]）。
     * 两者共用 [taskWriteLock]，避免 UI 点击与并发工具写入互相覆盖。
     */
    fun addTask(description: String) {
        val desc = description.trim()
        if (desc.isBlank()) return
        // 任务面板是会话级：AI 在后台会话里建任务时不能写到前台会话的面板上。
        val cid = AppContextHolder.currentConversationId ?: uiState.currentConversationId
        synchronized(taskWriteLock) {
            editSlot(cid) {
                val nextOrder = (tasks.maxOfOrNull { it.order } ?: -1) + 1
                tasks = tasks + TaskItem(
                    id = nextMessageId.incrementAndGet(),
                    description = desc,
                    order = nextOrder
                )
                // 与 AI 建任务一致：**保持折叠**，不自动撑开面板（见 replaceTasksFromSnapshot 注释）。
                tasksExpanded = false
            }
            persistTasks(cid)
        }
    }

    fun updateTaskStatus(taskId: Long, status: TaskStatus) {
        val cid = com.wy.openx.tools.AppContextHolder.currentConversationId ?: uiState.currentConversationId
        synchronized(taskWriteLock) {
            editSlot(cid) { tasks = tasks.map { if (it.id == taskId) it.copy(status = status) else it } }
            persistTasks(cid)
        }
    }

    fun removeTask(taskId: Long) {
        val cid = com.wy.openx.tools.AppContextHolder.currentConversationId ?: uiState.currentConversationId
        synchronized(taskWriteLock) {
            editSlot(cid) { tasks = tasks.filter { it.id != taskId } }
            persistTasks(cid)
        }
    }

    /**
     * 从库载入某会话的任务清单（持久化恢复）。
     *
     * 挂在 [observeMessagesForConversation] 里而不是 `switchConversation`：
     * **启动恢复路径不经过 switchConversation**（它直接调 observeMessagesForConversation），
     * 挂在后者才能保证「重开 app 后任务面板还在」这条需求在两条路径下都成立。
     *
     * **只在此会话的槽位为空时载入**：否则会把「刚在内存里改过、还没落库」的清单
     * 覆盖回旧快照。槽位为空 = 首次切到该会话（或进程刚启动），此时库里才是真源。
     */
    private fun loadTasksIfNeeded(conversationId: Long) {
        if (conversationId < 0) return
        if (slotOf(conversationId).tasks.isNotEmpty()) return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { taskRepository.getByConversation(conversationId) }
            if (saved.isEmpty()) return@launch
            // 期间用户/AI 可能已经写过新清单，别把它覆盖掉。
            if (slotOf(conversationId).tasks.isNotEmpty()) return@launch
            editSlot(conversationId) {
                tasks = saved
                // 载入也**保持折叠**：折叠态标题行已显示「N/M 完成」与进行中的任务，
                // 足够判断进度；想看全量清单点一下标题行即可。
                // （统一「默认折叠、用户点开才算展开」，避免切会话时面板忽开忽合。）
                tasksExpanded = false
            }
        }
    }

    /**
     * 注册插件发起 AI 任务的通道（`ctx.chat.runTask`）。
     *
     * 关键约束：**新建会话不切 UI**。定时任务可能在用户正看别的东西时触发，
     * 如果沿用 [createNewConversation] 会直接抢走前台，把用户手里的对话顶掉。
     * 这里只往库里插一条会话记录（`active = false`），再用
     * [sendMessage] 的 `targetConversationId` 唤醒它——后台会话能跑，前台不受影响。
     *
     * 新会话要**显式带上当前生效的 provider/model**：会话级模型模式下，
     * 不带的话新会话就是「未选择模型」，`sendMessage` 会直接失败。
     */
    private fun bindPluginChatRunner() {
        AppContextHolder.pluginChatRunner = { prompt, newConversation, _ ->
            val text = prompt.trim()
            if (text.isEmpty()) {
                0L to false
            } else {
                val cid = if (newConversation) {
                    val src = settingsFor(uiState.currentConversationId)
                    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                    conversationRepository.insert(
                        ConversationModel(
                            id = 0,
                            title = "插件任务 $time",
                            meta = "刚刚 · ${src.model}",
                            active = false,
                            lastActiveAt = System.currentTimeMillis(),
                            providerId = src.selectedProviderId.takeIf { it.isNotBlank() },
                            model = src.model.takeIf { it.isNotBlank() }
                        )
                    )
                } else {
                    uiState.currentConversationId
                }
                cid to sendMessage(overrideText = text, targetConversationId = cid)
            }
        }
    }

    /**
     * 注册任务面板执行器，供 [com.wy.openx.tools.TaskTool] 调用并返回真实结果给模型。
     *
     * 接口是**快照式**的：模型一次提交完整清单，这里整体替换。
     * 顺序 = 数组下标，因此顺序**结构化确定**，与并发执行时序无关——
     * 这是「任务顺序乱」的根治办法（旧的逐条工具只能靠强制串行来"碰对"顺序）。
     */
    private fun bindTaskExecutor() {
        AppContextHolder.taskExecutor = { operation, args ->
            // 工具**归属的会话**。并发执行时工具可能跑在后台会话上，
            // 读写都要用它，不能读前台投影（uiState.tasks）。
            val taskCid = AppContextHolder.currentConversationId ?: uiState.currentConversationId
            try {
                when (operation) {
                    "todo" -> replaceTasksFromSnapshot(taskCid, args["items"])
                    else -> "错误：未知任务操作「$operation」。"
                }
            } catch (e: Exception) {
                "错误：任务操作「$operation」执行失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * 用快照整体替换某会话的任务清单，并把结果回报给模型。
     *
     * 解析规则（对模型宽容，避免一次格式小错就整批失败）：
     * - `items` 缺失 / 非数组 → 报错（这是接口约定，不能猜）；
     * - 每项可以是**字符串**（直接当标题），也可以是含 `description` 的对象；
     *   对象的 `subject` 字段也接受（参考实现用这个名，模型可能混用）；
     * - 标题为空白的项**跳过**（而不是整批失败），并在回报里告知跳过了几项；
     * - `status` 无法识别时**回退 pending**（同样不整批失败）。
     *
     * 已存在的条目（标题相同）**沿用原 id**，这样 UI 上「同一条任务」的状态灯不会闪、
     * 也不会被误判成新条目——按 subject 复用 id 的做法一致。
     */
    private fun replaceTasksFromSnapshot(cid: Long, rawItems: Any?): String {
        val list = rawItems as? List<*>
            ?: return "错误：参数 items 必须是数组（传空数组表示清空清单）。"

        val existing = slotOf(cid).tasks
        // 按标题归一化建索引，供复用 id（同名条目取第一条，剩余的在下方 removeFirst 消费）。
        val reusable = existing.groupBy { it.description.trim().lowercase() }
            .mapValues { (_, v) -> v.toMutableList() }

        val replaced = ArrayList<TaskItem>(list.size)
        var skipped = 0
        list.forEachIndexed { index, raw ->
            val desc: String
            val status: TaskStatus
            when (raw) {
                // 纯字符串形式：直接当标题，状态默认 pending。
                is String -> {
                    desc = raw.trim()
                    status = TaskStatus.PENDING
                }
                is Map<*, *> -> {
                    // subject 是参考实现的字段名，description 是我们面板的字段名，两者都收。
                    desc = (raw["description"] as? String ?: raw["subject"] as? String ?: "").trim()
                    status = parseTaskStatus(raw["status"] as? String)
                }
                else -> {
                    desc = ""
                    status = TaskStatus.PENDING
                }
            }
            if (desc.isBlank()) {
                skipped++
                return@forEachIndexed
            }
            val prev = reusable[desc.lowercase()]?.removeFirstOrNull()
            replaced += TaskItem(
                // 复用原 id；新条目才分配。id 用 nextMessageId 与全局消息 id 同源，
                // 保证面板操作（开始/删除）传回的 id 能对上。
                id = prev?.id ?: nextMessageId.incrementAndGet(),
                description = desc,
                status = status,
                // **顺序 = 数组下标**，这是快照式的核心收益。
                order = index
            )
        }

        synchronized(taskWriteLock) {
            editSlot(cid) {
                tasks = replaced
                // **保持折叠**（用户要求：创建任务后不要自动撑开面板）。
                // AI 每完成一步都会提交新清单，若这里展开，输入区会被反复挤占。
                // 折叠时标题行仍显示「N/M 完成」与当前进行中的任务，信息足够；
                // 用户想看全量清单，点一下标题行即可展开（展开态由用户自己掌控，不在这里覆盖）。
                tasksExpanded = false
            }
            // 整体替换写库（含清空）。放在锁内保证「UI 快照与落库快照一致」。
            persistTasks(cid)
        }
        uiState = uiState.copy(logs = uiState.logs + "[任务] 清单已更新：${replaced.size} 项")

        val sb = StringBuilder()
        sb.append("成功：任务清单已同步，当前共 ${replaced.size} 项")
        if (replaced.isNotEmpty()) {
            val done = replaced.count { it.status == TaskStatus.COMPLETED }
            sb.append("（已完成 $done 项）")
        }
        sb.append("。")
        if (skipped > 0) sb.append("已跳过 $skipped 项（标题为空）。")
        // 回显当前清单，让模型知道面板实际内容（无需再查询）。
        val preview = replaced.take(20).joinToString("\n") { "  ${it.order + 1}. [${it.status.name.lowercase()}] ${it.description}" }
        if (preview.isNotEmpty()) sb.append("\n当前清单：\n").append(preview)
        return sb.toString()
    }

    /** 解析任务状态字符串；无法识别时回退 [TaskStatus.PENDING]（不整批失败）。 */
    private fun parseTaskStatus(raw: String?): TaskStatus {
        return when (raw?.trim()?.lowercase()?.replace("-", "_")) {
            null, "", "pending", "todo" -> TaskStatus.PENDING
            "in_progress", "inprogress", "doing", "active" -> TaskStatus.IN_PROGRESS
            "completed", "complete", "done", "finished", "success" -> TaskStatus.COMPLETED
            "cancelled", "canceled", "cancel", "aborted", "skipped" -> TaskStatus.CANCELLED
            else -> TaskStatus.PENDING
        }
    }

    fun clearCompletedTasks() {
        // 与 add/update/remove 同样走槽位真源 + 同一把锁，避免与并发工具写入互相覆盖。
        val cid = com.wy.openx.tools.AppContextHolder.currentConversationId ?: uiState.currentConversationId
        synchronized(taskWriteLock) {
            editSlot(cid) { tasks = tasks.filter { it.status != TaskStatus.COMPLETED } }
            persistTasks(cid)
        }
    }

    fun toggleTasksExpanded() {
        uiState = uiState.copy(tasksExpanded = !uiState.tasksExpanded)
    }

    fun resetTokenStats() {
        tokenPrefs.edit().clear().apply()
        uiState = uiState.copy(totalTokens = 0L, totalInputTokens = 0L, tokenDetails = emptyMap(), cacheHitTokens = 0L, cacheHitDetails = emptyMap(), lastInputContext = 0)
        showToast("Token 用量统计已重置")
    }

    // 从会话记忆恢复该会话的真实输入上下文；无内存记忆时回退 DB 持久化的 lastInputTokens
    // 请求数从持久化的会话记录（DB）恢复，保证重启后仍准确
    private fun restoreConversationCtx(conversationId: Long) {
        val rec = conversationCtx[conversationId]
        val persistedReq = uiState.conversations.firstOrNull { it.id == conversationId }?.requestCount ?: 0
        val restoredReq = rec?.requestCount ?: persistedReq
        val persistedInput = uiState.conversations.firstOrNull { it.id == conversationId }?.lastInputTokens ?: 0
        val restoredInput = rec?.input ?: persistedInput
        uiState = uiState.copy(lastInputContext = restoredInput, sessionRequestCount = restoredReq)
        // 重启后本会话尚无内存记录时，以持久化请求数建立，保证后续请求数累加正确
        if (rec == null && persistedReq > 0) {
            conversationCtx[conversationId] = CtxRecord(input = restoredInput, requestCount = persistedReq)
        }
        // 一并恢复**报告锚**，否则该会话锚为 null → effectiveContextTokens() 退回全量估算，
        // 会把上下文高估到 2.5 倍真实值、提前触发压缩。
        ensureUsageAnchor(conversationId)
    }

    /**
     * 为某会话（重）建报告锚。
     *
     * 两个条件缺一不可（2026-09-29 日志实证）：
     * 1. **消息未加载时不建锚**：启动恢复路径在 observe 首次发射之前调本函数，此时槽内
     *    messages 为空，`mark = size - 1` 退化成 0 —— 无效锚让所有判定退回全量估算
     *    （实测 841K vs 真实 291K，高估 2.9 倍，圆环忽高忽低的直接根因）。
     * 2. **mark≤0 视为无锚、允许重建**：旧逻辑只在 `锚==null` 时建，一旦建出无效锚
     *    就永久阻断修复（日志里「已有锚未覆盖 mark=0」重复了 5 次）。
     */
    private fun ensureUsageAnchor(conversationId: Long, msgs: List<ChatMessageModel>? = null) {
        // 抑制期内绝不建锚：历史刚被截断/改动，`conversationCtx` 与 DB 里的 `lastInputTokens`
        // 都是「改动前」的真实 usage，配到新历史上就是错的（会把旧的大值贴回来）。
        // 等下一轮真实 usage（advanceUsageAnchor）到达即自动解除。
        if (conversationId in anchorSuppressed) return
        val existing = usageAnchors[conversationId]
        // 已有有效锚就不动——**在下面算估算之前**返回，observer 每轮都会进来，别在这条路径上做全量估算。
        if (existing != null && existing.reported > 0) return
        val rec = conversationCtx[conversationId]
        val persistedInput = uiState.conversations.firstOrNull { it.id == conversationId }?.lastInputTokens ?: 0
        val restoredInput = rec?.input ?: persistedInput
        if (restoredInput <= 0) return                              // 没有可信的真实 usage，不建（新会话/从未请求过）
        // 取本次实际发射的消息列表（observe 传该次发射结果；启动恢复路径未传则读槽内当前值）。
        val list = msgs ?: slotOf(conversationId).messages
        if (list.isEmpty()) return                                  // 消息未加载，等 observe 首次发射再进来
        // 从 DB/内存恢复出来的「上次真实 usage」没有配套的请求时估算，
        // 于是以当前估算作为基准：修正项 = reported + est - est = reported（不凭空加减），
        // 下一次真实 usage 回来就会带上那次请求真正的估算。
        val baselineEstimate = estimateContextTokens(list)
        usageAnchors[conversationId] = UsageAnchor(restoredInput, baselineEstimate)
        AppLogStore.info(
            "上下文锚",
            "建锚 cid=$conversationId rep=$restoredInput baselineEst=$baselineEstimate msgs=${list.size} " +
                "（来源 rec=${rec?.input}/db=$persistedInput${if (existing != null) "，修复无效锚 rep=${existing.reported}" else ""}）"
        )
    }

    // 清空该会话的输入上下文记忆并归零（新建/清空历史/删除会话时）
    private fun resetInputContext(forConversation: Long? = null) {
        if (forConversation != null) {
            conversationCtx.remove(forConversation)
            // 锚必须一并清：新建/清空历史/删会话后旧 mark 仍指向已不存在的消息下标，
            // 留着会让 effectiveContextTokens() 拿旧 reported 叠加到空列表上。
            usageAnchors.remove(forConversation)
            anchorSuppressed.remove(forConversation)
            AppLogStore.info("上下文锚", "清锚 cid=$forConversation（resetInputContext 触发）")
        }
        // 只在该会话是前台时才归零显示值：否则清 B 的历史会把 A 的圆环也清零。
        if (forConversation == null || forConversation == _uiState.currentConversationId) {
            uiState = uiState.copy(lastInputContext = 0, sessionRequestCount = 0)
        }
    }

}

/** 镜像下载状态（一次一个任务）。 */
sealed interface ContainerDownloadUiState {
    data object Idle : ContainerDownloadUiState
    data class Downloading(val entryId: String, val bytesRead: Long, val totalBytes: Long) :
        ContainerDownloadUiState
    data class Error(val entryId: String, val message: String) : ContainerDownloadUiState
}

/** 保存的模型单条测速结果（设置页「保存的模型」列表按钮前显示毫秒）。 */
data class PresetTestState(
    val testing: Boolean = false,
    val latencyMs: Long? = null,
    val message: String? = null
)
