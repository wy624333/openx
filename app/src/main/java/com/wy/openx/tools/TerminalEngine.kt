package com.wy.openx.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.wy.openx.container.FileLogger
import com.wy.openx.container.LinuxContainerEngine
import com.wy.openx.container.Workspace
import com.wy.openx.termux.TermuxConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** workdir 白名单：只允许容器工作区内的普通路径字符，杜绝引号/分号/反引号等注入字符。 */
private val SAFE_WORKDIR = Regex("^/[A-Za-z0-9_./-]+$")

/**
 * 校验 AI 传入的 workdir，返回可安全拼进 shell 的路径，非法返回 null。
 *
 * workdir 会被原样拼进 `cd "$dir" 2>/dev/null; $command`，只做 trim 等于没校验：
 * 传 `/tmp"; rm -rf /; echo "` 就能闭合引号、在危险命令守卫之外执行任意命令。
 * 规则：必须是 /root/workspace 或 /root/local 下的绝对路径，且不含 .. 与 shell 元字符。
 */
internal fun sanitizeWorkdir(raw: String): String? {
    if (!raw.startsWith("/")) return null
    if (raw.contains("..")) return null
    if (!SAFE_WORKDIR.matches(raw)) return null
    val okRoots = listOf("/root/workspace", "/root/local")
    return if (okRoots.any { raw == it || raw.startsWith("$it/") }) raw else null
}

class TerminalEngine {
    /**
     * 当前会话的**可观察**副本。
     *
     * 之前 [session] 是普通 `var`，而 TerminalScreen 在 `AndroidView.update` 里调
     * `engine.getSession()` —— 组合期读一个非 state 的字段**建立不了重组订阅**，
     * 于是「会话已创建」未必能让 update 重跑，TerminalView 一直不 attachSession，
     * 表现为纯黑屏（TerminalView.onDraw: mEmulator==null → drawColor(0xFF000000)）。
     * 现场症状完全吻合：第一次进终端黑屏、会话其实活着、返回再进就正常
     *（那时是新组合，update 必跑）。
     *
     * 故把 [session] 改为带 setter 同步的字段，并由此 StateFlow 驱动渲染面重组。
     */
    private val _sessionFlow = MutableStateFlow<TerminalSession?>(null)
    val sessionFlow: StateFlow<TerminalSession?> = _sessionFlow.asStateFlow()

    /** 活动标签的会话：键盘输入 / TerminalScreen 挂载均读此指针 */
    private var session: TerminalSession? = null
        set(v) {
            field = v
            _sessionFlow.value = v
        }

    var sessionClient: TerminalSessionClientAdapter? = null
        private set
    var viewClient: TerminalViewClientAdapter? = null
        private set

    /** 容器首装/切换的异步初始化协程（不随页面取消；ensureInstalled 内部为引擎级 job，天然可复用） */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 初始化在途标记 + 代号守卫。切换容器由 [stop] 调 [TerminalInitGuard.invalidate]，
     * 作废在途结果并释放标记——否则切容器后每次进终端都命中 initialize 开头的
     * 「在途就 return」，会话永远建不起来，表现为纯黑屏（详见该类注释）。
     */
    private val initGuard = TerminalInitGuard()

    /** 终端初始化失败的可见原因（供终端页显示「重试」而不是黑屏）；null = 无错误 */
    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    /** 是否正在准备会话（供终端页显示「准备中」而不是黑屏） */
    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing.asStateFlow()

    /** 全部标签（进程级常驻）：会话所有权在此，离开终端页不杀会话，切回复用同一 session */
    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val idCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    /** 活动标签（当前聚焦的会话） */
    private val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    /** 后台命令退出兜底监控的协程作用域（IO 轮询 transcript，不随页面取消） */
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 后台任务（notify=true）完成事件流，供 AI 订阅 */
    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    /** 终端页设置（字号/主题/光标），与设置面板共享同一 SharedPreferences 文件 */
    private val settingsStore: TerminalSettingsStore? by lazy {
        AppContextHolder.context?.let { TerminalSettingsStore(it) }
    }

    /** 当前光标样式（0=块 / 1=下划线 / 2=竖线），供 TerminalSessionClient 读取 */
    var cursorStyle: Int = TerminalSettingsStore.CURSOR_BLOCK
        private set

    /** 标签页背景色（跟随主题），供 TerminalScreen 同步 panel 底色 */
    private var themeBackground: Int = TerminalThemePreset.TERMIUS_DARK.background
    val currentThemeBackground: Int get() = themeBackground

    private var ctrlHeld = false
    private var altHeld = false
    private var shiftHeld = false
    private var fnHeld = false

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun getSession(): TerminalSession? = session
    fun readControlKey(): Boolean = ctrlHeld
    fun readAltKey(): Boolean = altHeld
    fun readShiftKey(): Boolean = shiftHeld
    fun readFnKey(): Boolean = fnHeld
    fun setCtrlHeld(value: Boolean) { ctrlHeld = value }

    @Synchronized
    fun initialize() {
        // 把持久化的主题/光标样式写入静态配色与字段：新会话（emulator 构造时 reset 拷贝
        // COLOR_SCHEME）与渲染自动继承，实现「下次进入生效」。
        applyStoredAppearance()
        // 已有标签（之前进入过终端页或未关闭）：激活第一个即可，不重建会话。
        if (_tabs.value.isNotEmpty()) {
            if (_activeTabId.value == null) activateExistingLocked(_tabs.value.first().id)
            return
        }
        _initError.value = null
        val gen = initGuard.tryBegin()
        if (gen == null) {
            // 之前这里是静默 return，导致「切容器后终端黑屏且无法自愈」。
            // 现在至少留痕，且 _isPreparing 让终端页显示「准备中」而不是黑屏。
            FileLogger.i(TAG, "终端初始化在途，跳过重复触发")
            return
        }
        ensureClients()

        val engine = AppContextHolder.containerEngine
        if (engine == null) {
            // 容器引擎未装配（极端情况）：回退到原 Termux bash 会话
            initGuard.end(gen)
            openNativeTab()
            return
        }

        // 容器路径：后台 ensureInstalled（幂等，复用引擎级 job），就绪后用完整 proot argv 建 PTY 会话。
        // 初始化失败则回退原生 shell，不让终端页卡死。
        // TerminalSession 内部会创建 Handler，必须回到主线程（有 Looper）构造，否则抛
        // 「Can't create handler inside thread」——故 ensureInstalled 的耗时工作在后台，
        // 会话创建（openContainerTab/openNativeTab）切到 Dispatchers.Main。
        _isPreparing.value = true
        engineScope.launch {
            try {
                engine.ensureInstalled()
                withContext(Dispatchers.Main) {
                    // 期间用户可能切了容器（stop 会自增代号）或又初始化了一次。
                    // 此时本次结果作废，绝不采用——否则会把「上一个容器」的会话挂上来。
                    if (!initGuard.isCurrent(gen)) {
                        FileLogger.i(TAG, "终端初始化结果已作废（容器已切换或已重新初始化）")
                        return@withContext
                    }
                    runCatching { openContainerTab(engine) }
                        .onFailure { onSessionCreateFailed(it) }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "容器初始化失败，回退原生 shell: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (!initGuard.isCurrent(gen)) return@withContext
                    // 原来 openNativeTab() 直接写在 catch 里：它自己若再抛（如 fork 失败），
                    // 异常会冲出 catch，落到 engineScope 那个没有异常处理器的
                    // SupervisorJob 上被静默吞掉 —— 结果是既没有会话也没有任何提示。
                    runCatching { openNativeTab() }
                        .onFailure { onSessionCreateFailed(it) }
                }
            } finally {
                initGuard.end(gen)
                if (initGuard.isCurrent(gen)) _isPreparing.value = false
            }
        }
    }

    /** 会话创建彻底失败：记录可见原因，让终端页显示错误与「重试」，而不是黑屏。 */
    private fun onSessionCreateFailed(e: Throwable) {
        val msg = e.message ?: e.javaClass.simpleName
        FileLogger.w(TAG, "终端会话创建失败: $msg", if (e is Exception) e else RuntimeException(e))
        _initError.value = "会话创建失败：$msg"
        _isPreparing.value = false
    }

    /**
     * 「新建标签」入口（终端页 TabBar 的 + 号等）。容器未装配时直接建原生 shell 标签；
     * 已装配则后台 ensureInstalled（幂等）后在主线程建容器标签。失败仅记日志不抛出。
     */
    fun requestNewTab() {
        ensureClients()
        val engine = AppContextHolder.containerEngine
        if (engine == null) {
            openNativeTab()
            return
        }
        engineScope.launch {
            try {
                engine.ensureInstalled()
                withContext(Dispatchers.Main) { openContainerTab(engine) }
            } catch (e: Exception) {
                FileLogger.w(TAG, "新建容器标签失败: ${e.message}", e)
            }
        }
    }

    fun activateTab(id: String) {
        val tab = tabById(id) ?: return
        _activeTabId.value = id
        session = tab.session
        _isRunning.value = tab.runState is RunState.Running
    }

    fun closeTab(id: String): Boolean {
        val tab = tabById(id) ?: return false
        runCatching { tab.session.finishIfRunning() }
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            val next = remaining.lastOrNull()
            if (next != null) {
                session = next.session
                _activeTabId.value = next.id
            } else {
                session = null
                _activeTabId.value = null
            }
        }
        if (remaining.isEmpty()) _isRunning.value = false
        FileLogger.i(TAG, "关闭终端标签 $id")
        return true
    }

    fun renameTab(id: String, title: String) {
        tabById(id)?.let { it.title = title }
    }

    /**
     * 供 AI 预留接口：把一条命令挂后台跑（如 `npm run dev`），返回唯一 tabId。
     *
     * 后台标签不抢占当前会话焦点。命令跑完后：notify=true 时以命令真实退出码结束并触发
     * [tabFinishedEvents]；notify=false 时 `exec shell` 保活，标签可继续输入（dev server
     * 退出后也能复用）。输出全程留在 emulator 缓冲里，AI/用户切过去都能用 [getTabOutput]
     * 看到累计输出。
     */
    suspend fun startBackgroundCommand(
        command: String,
        title: String? = null,
        notify: Boolean = false,
        sourceSessionId: String? = null,
        /** 【P4-3】发起本后台命令的 AI 会话 id（0=无主）。工具层据此隔离默认标签。 */
        conversationId: Long = 0L
    ): String {
        val engine = AppContextHolder.containerEngine
            ?: throw IllegalStateException("容器未装配，无法启动后台命令")
        ensureClients()
        withContext(Dispatchers.IO) { engine.ensureInstalled() }
        val shell = engine.defaultShell()
        // 用 `ec=$?` 捕获命令真实退出码后再 echo，否则 echo 本身恒为 0 会覆盖进程退出码。
        // notify=true：echo 后 `exit $ec` 让 shell 以命令真实退出码结束 → onFinished 拿到正确码；
        // notify=false：echo 后 `exec shell` 保活，标签可复用（dev server 场景）。
        val afterCommand = if (notify) "; exit \$ec" else "; exec $shell"
        val shellCommand = "cd \"${workspaceCwd()}\" 2>/dev/null; export ENV=/etc/profile; " +
            "$command; ec=\$?; echo \"[command exited: \$ec]\"$afterCommand"
        val id = nextId()
        val s = withContext(Dispatchers.Main) {
            buildBackgroundSession(engine, shellCommand)
        }
        _tabs.value = _tabs.value + TerminalTab(
            id = id,
            title = title ?: id,
            session = s,
            command = command,
            isBackground = true,
            notifyOnExit = notify,
            sourceSessionId = sourceSessionId,
            ownerConversationId = conversationId
        )
        // 后台命令不抢占当前标签焦点：仅当没有活动标签时才设为当前。
        if (_activeTabId.value == null) activateTab(id)
        if (notify) monitorBackgroundExit(id)
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    /** 按 id 向标签发送输入并回车执行（AI 持续发命令的入口）。返回是否命中标签且仍活跃。 */
    fun sendInput(id: String, input: String, appendNewline: Boolean = true): Boolean {
        val tab = tabById(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) "$input\n" else input
        writeToSession(tab.session, text)
        return true
    }

    /** 按 id 向标签写入原始文本，不自动追加回车。 */
    fun writeToTab(id: String, text: String): Boolean {
        val tab = tabById(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        writeToSession(tab.session, text)
        return true
    }

    /** 按 id 向标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tabById(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    /** 按 id 读取终端内容（emulator 屏幕缓冲的完整转录），供 AI 拉取。返回 null 表示无此标签。 */
    fun getTabOutput(id: String): String? {
        val tab = tabById(id) ?: return null
        return runCatching {
            tab.session.getEmulator().getScreen().getTranscriptText().trimEnd('\n')
        }.getOrNull() ?: ""
    }

    /** 列出全部标签的摘要（id/标题/是否后台/运行状态/命令），供 AI 选目标。 */
    fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title.ifBlank { "终端" },
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command,
            ownerConversationId = it.ownerConversationId
        )
    }

    /**
     * 【P4-3】某会话最近一个后台标签 id（没有则 null）。
     *
     * 供 `get_output` 在不传 id 时定位「自己那个终端」，而不是拿全局 `activeTabId` ——
     * 后者在多会话并行下属于最后一次激活标签页的那个会话，会读到别的会话的输出。
     */
    fun lastBackgroundTabIdFor(conversationId: Long): String? =
        _tabs.value.lastOrNull { it.isBackground && it.ownerConversationId == conversationId }?.id

    /** 【P4-3】某标签归属于哪个 AI 会话（0 = 无主/不存在）。 */
    fun tabOwner(tabId: String): Long = tabById(tabId)?.ownerConversationId ?: 0L

    private fun writeToSession(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    /** 构造进入容器的 PTY 会话（后台命令用，主线程调用）。 */
    private fun buildBackgroundSession(engine: LinuxContainerEngine, shellCommand: String): TerminalSession {
        val invocation = engine.buildProotInvocation(shellCommand, engine.hostWorkspaceDir)
        val s = TerminalSession(
            invocation.executable,
            engine.hostWorkspaceDir,
            invocation.argv.toTypedArray(),
            invocation.ptyEnvArray,
            TRANSCRIPT_ROWS,
            sessionClient
        )
        s.updateSize(BACKGROUND_COLUMNS, BACKGROUND_ROWS, 560, 336)
        return s
    }

    /** 在输出文本的末尾行中定位退出标记 `[command exited: N]` 并解析退出码；未找到返回 null。 */
    private fun extractExitCode(output: String): Int? {
        val tail = output.takeLast(1000)
        val idx = tail.lastIndexOf(EXIT_MARKER_PREFIX)
        if (idx < 0) return null
        if (idx > 0 && tail[idx - 1] != '\n' && tail[idx - 1] != '\r') return null
        var end = idx + EXIT_MARKER_PREFIX.length
        if (end >= tail.length || !tail[end].isDigit()) return null
        var code = 0
        while (end < tail.length && tail[end].isDigit()) {
            code = code * 10 + (tail[end] - '0')
            end++
        }
        return if (end < tail.length && tail[end] == ']') code else null
    }

    /**
     * 完成兜底监控：termux 的会话结束依赖 PTY 读到 EOF，而 proot 宿主进程在 Android 上可能
     * 概率性不退出（即使其 bash 已执行 exit，proot 仍攥着 /dev/pts 不释放），导致 onFinished
     * 永不回调、notify=true 的任务不触发通知。bash 在真正退出前必打印 `[command exited: N]`，
     * 以此作为命令结束的可靠信号：监控到后短缓冲（给正常回调留时间），仍 Running 则强制收尾。
     */
    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch {
            var seenMarker = false
            var lastOutputLen = -1
            val startedAt = System.currentTimeMillis()
            while (true) {
                val tab = tabById(tabId) ?: return@launch
                if (tab.runState !is RunState.Running) return@launch
                // 监控本身必须有上限：命令永不退出（如 `tail -f`、交互式程序、卡死的网络挂起）时，
                // 这个 while 会每秒轮询到进程结束，协程 + 全量转录拷贝一起泄漏。
                // 到点就收手：此时把标签当作仍在运行交给用户，不再反复扫描。
                if (System.currentTimeMillis() - startedAt > EXIT_MONITOR_MAX_MS) {
                    FileLogger.i(TAG, "兜底监控超时，停止轮询：$tabId")
                    return@launch
                }
                val output = getTabOutput(tabId) ?: return@launch
                if (!seenMarker) {
                    // 输出无增长时跳过扫描：仅比较长度，省去每轮全量定位退出标记。
                    if (output.length != lastOutputLen) {
                        lastOutputLen = output.length
                        if (extractExitCode(output) != null) seenMarker = true
                    }
                } else {
                    delay(EXIT_MARKER_GRACE_MS)
                    // 缓冲后再查：正常 onFinished 回调若已触发，状态不再 Running，此处直接退出。
                    val current = tabById(tabId) ?: return@launch
                    if (current.runState is RunState.Running) {
                        val exitCode = extractExitCode(getTabOutput(tabId) ?: "") ?: 0
                        current.runState = RunState.Finished(exitCode)
                        FileLogger.i(TAG, "兜底：标签 $tabId 检测到退出标记，强制收尾 exit=$exitCode")
                        emitFinishedIfNeeded(current, exitCode)
                    }
                    return@launch
                }
                delay(EXIT_MARKER_POLL_MS)
            }
        }
    }

    /** 触发一次性的 TabFinishedEvent（用于后台 notify 标签）。 */
    private fun emitFinishedIfNeeded(tab: TerminalTab, exitCode: Int) {
        if (tab.notifyOnExit && !tab.finishedNotified) {
            tab.finishedNotified = true
            _tabFinishedEvents.tryEmit(
                TabFinishedEvent(
                    tab.id, tab.title.ifBlank { "终端会话" }, tab.command, exitCode, tab.sourceSessionId,
                    tailOutput = getTabOutput(tab.id)?.takeTailLines(TAIL_LINES),
                    ownerConversationId = tab.ownerConversationId
                )
            )
        }
    }

    /**
     * 按句柄找标签。
     *
     * 先按 id 精确匹配；未命中时**退回按标题匹配**（要求唯一），因为 AI/用户很容易把
     * 界面上看到的标题当作句柄传进来（历史上 `list_tabs` 只显示标题，导致这个混用是必然的）。
     * 重名时不猜（返回 null），避免误操作到另一个标签。
     */
    private fun tabById(id: String): TerminalTab? {
        _tabs.value.firstOrNull { it.id == id }?.let { return it }
        return _tabs.value.filter { it.title == id }.singleOrNull()
    }

    private fun activateExistingLocked(id: String) {
        val tab = tabById(id) ?: return
        _activeTabId.value = id
        session = tab.session
        _isRunning.value = tab.runState is RunState.Running
    }

    /**
     * 交互式容器标签的启动命令：`cd` 兜底、ENV 加载登录环境、首次进入时弹初始化菜单
     * （脚本自行判断已完成/已跳过则秒退），配色提示符与常用 alias，最后 `exec` 让
     * defaultShell 取代外层 sh -c 成为前台交互 shell。
     */
    private fun defaultInteractiveCommand(engine: LinuxContainerEngine): String {
        val shell = engine.defaultShell()
        return "cd \"${workspaceCwd()}\" 2>/dev/null; export ENV=/etc/profile; " +
            "[ -f /root/.openx/provision.sh ] && sh /root/.openx/provision.sh; " +
            "export PS1='\\[\\033[01;32m\\]\\u@\\h\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '; " +
            "alias ls='ls --color=auto' 2>/dev/null; alias grep='grep --color=auto' 2>/dev/null; alias ll='ls -la --color=auto' 2>/dev/null; " +
            "exec $shell"
    }

    /** 终端默认工作目录：选中项目工作区则在项目目录内工作；选中外部本地目录则在 /root/local/<name>；
     *  否则回到工作区根。 */
    private fun workspaceCwd(): String {
        val settings = AppContextHolder.currentSettings
        val proj = settings?.currentProject?.trim()
        if (proj.isNullOrEmpty()) return "/root/workspace"
        if (settings.externalWorkspaces.any { it.name == proj }) return "/root/local/$proj"
        return "/root/workspace/projects/$proj"
    }

    /** 容器就绪后以 proot 交互 shell 创建 PTY 会话（挂在容器内），并入标签池并激活。 */
    private fun openContainerTab(engine: LinuxContainerEngine) {
        val invocation = engine.buildProotInvocation(
            defaultInteractiveCommand(engine),
            engine.hostWorkspaceDir
        )
        val id = nextId()
        val s = TerminalSession(
            invocation.executable,
            engine.hostWorkspaceDir,
            invocation.argv.toTypedArray(),
            invocation.ptyEnvArray,
            null,
            sessionClient
        )
        s.updateSize(80, 24, 560, 336)
        // 标题留空，由界面按位置显示「终端 N」。原先传 id，导致切一次容器标签就变成
        // "term-2"——把内部自增计数器直接漏给用户（还会流进通知与 AI 的会话列表）。
        addAndActivate(TerminalTab(id, "", s, null))
        FileLogger.i(TAG, "容器终端会话已启动（proot）：$id")
    }

    /** 原生的 Termux bash 会话（容器未装配/初始化失败的兜底），并入标签池并激活。 */
    private fun openNativeTab() {
        TermuxConstants.HOME_DIR.mkdirs()
        val shellPath = resolveShellPath()
        val id = nextId()
        val s = TerminalSession(
            shellPath, TermuxConstants.HOME_DIR_PATH,
            resolveShellArgs(shellPath), resolveEnvironment(shellPath),
            null, sessionClient
        )
        s.updateSize(80, 24, 560, 336)
        // 标题留空，由界面按位置显示「终端 N」。原先传 id，导致切一次容器标签就变成
        // "term-2"——把内部自增计数器直接漏给用户（还会流进通知与 AI 的会话列表）。
        addAndActivate(TerminalTab(id, "", s, null))
    }

    private fun addAndActivate(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id
        session = tab.session
        _isRunning.value = true
    }

    private fun ensureClients() {
        if (sessionClient == null) {
            sessionClient = TerminalSessionClientAdapter(this)
        }
        if (viewClient == null) {
            viewClient = TerminalViewClientAdapter(this)
        }
    }

    /** 应用持久化的外观设置（光标样式读入、主题写入静态 COLOR_SCHEME）。幂等。 */
    private fun applyStoredAppearance() {
        val store = settingsStore ?: return
        cursorStyle = store.cursorStyle
        themeBackground = TerminalThemePreset.findById(store.themeId).background
        writeTerminalScheme(TerminalThemePreset.findById(store.themeId))
    }

    /** 设置字号：持久化并立即应用到已挂载的 TerminalView（未挂载的 view 在 factory 时用 store 初始值）。 */
    fun setFontSize(size: Int) {
        val store = settingsStore
        if (store != null) store.fontSize = size
        sessionClient?.terminalView?.setTextSize(size)
    }

    /** 应用光标样式（0=块 / 1=下划线 / 2=竖线）：持久化并刷新所有会话 emulator 与视图。 */
    fun applyCursorStyle(style: Int) {
        cursorStyle = style.coerceIn(0, 2)
        settingsStore?.cursorStyle = cursorStyle
        refreshAllEmulators()
    }

    /** 应用配色主题：持久化并写入静态配色、刷新所有会话的颜色与视图。 */
    fun applyTheme(preset: TerminalThemePreset) {
        themeBackground = preset.background
        writeTerminalScheme(preset)
        settingsStore?.themeId = preset.id
        refreshAllEmulators()
    }

    /** 把主题写入 Termux 静态配色（后续任何 emulator 构造/reset 都会继承）。 */
    private fun writeTerminalScheme(preset: TerminalThemePreset) {
        val scheme = TerminalColors.COLOR_SCHEME
        preset.ansiColors.forEachIndexed { i, c -> scheme.mDefaultColors[i] = c }
        scheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND] = preset.foreground
        scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = preset.background
        scheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR] = preset.cursor
    }

    /** 刷新所有会话的 emulator（配色 reset 自静态 scheme、光标样式重读）并重绘已挂载视图。 */
    private fun refreshAllEmulators() {
        _tabs.value.forEach { tab ->
            runCatching {
                val emulator = tab.session.getEmulator()
                emulator.mColors.reset()
                emulator.setCursorStyle()
            }
        }
        sessionClient?.terminalView?.onScreenUpdated()
    }

    fun onSessionFinished(finished: TerminalSession) {
        val tab = _tabs.value.firstOrNull { it.session === finished } ?: return
        tab.runState = RunState.Finished(finished.exitStatus)
        if (tab.id == _activeTabId.value) _isRunning.value = false
        // 必须留痕：会话进程一退出，界面就只剩一片空白（没有 shell 输出，也没有报错），
        // 而此前这里一行日志都不打 —— 终端「什么都不显示」时无从判断是进程死了还是压根没建起来。
        FileLogger.w(TAG, "终端会话已结束：${tab.id}，退出码 ${finished.exitStatus}（会话数 ${_tabs.value.size}）")
        emitFinishedIfNeeded(tab, finished.exitStatus)
    }

    fun onCopyText(text: String) {
        val ctx = AppContextHolder.context ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    fun onPasteText() {
        val ctx = AppContextHolder.context ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: return
            session?.write(text)
        }
    }

    /**
     * 在容器中执行一条 AI 命令并返回输出 `Bash` 工具：**一次性 proot 进程**
     * 执行（[CommandEngine.runCommandSync]），命令结束即返回，不再走「持久 PTY 会话 + 字符串 marker
     * 轮询」——后者在大输出下（marker 判定失效/输出经终端模拟器逐字节处理）会空等满超时，
     * 表现为「命令早已结束却卡住数十秒」。
     *
     * 工作目录：proot `-w` 已把容器 cwd 设为 /root/workspace；若当前选中了项目/外部工作区，
     * 命令前先 `cd` 过去，使相对路径与文件工具的容器路径一致。
     * 注意：**不再保持跨命令 shell 状态**（cd/export/alias 等只在本条命令内有效），
     * 需要时请把相关操作用 `cd x && ...` / `;` 串在同一条命令里。
     */
    suspend fun executeAi(command: String, timeoutMs: Long = 120_000L, workdir: String? = null): String {
        val engine = AppContextHolder.containerEngine
            ?: return "错误：容器未装配，无法执行命令"
        withContext(Dispatchers.IO) { engine.ensureInstalled() }
        val requested = workdir?.trim()?.takeIf { it.isNotEmpty() }
        val dir = if (requested == null) workspaceCwd()
        else sanitizeWorkdir(requested)
            ?: return "错误：workdir 非法。workdir 会拼进 shell 执行，只接受容器工作区内的绝对路径" +
                "（/root/workspace 或 /root/local 开头，不含 .. 与 shell 元字符）。" +
                "本次目标：$requested"
        val wrapped = "cd \"$dir\" 2>/dev/null; $command"
        return engine.runCommandSync(wrapped, engine.hostWorkspaceDir, timeoutMs)
    }

    fun writeInput(text: String) { session?.write(text) }
    fun writeByte(byte: Int) { session?.write(byteArrayOf(byte.toByte()), 0, 1) }

    fun sendCtrl(key: Char) {
        val code = key.uppercaseChar() - 'A' + 1
        session?.writeCodePoint(false, code)
    }

    fun sendEnter() { writeInput("\n") }
    fun sendEscape() { writeByte(27) }
    fun sendTab() { writeInput("\t") }
    fun sendArrow(direction: ArrowDirection) { writeInput("\u001B${direction.code}") }
    fun sendBackspace() { writeByte(127) }
    fun clear() { session?.write("\u001B[2J\u001B[H") }

    @Synchronized
    fun stop() {
        // 之前无任何日志，导致「切换容器」这个动作在日志里完全不可见
        FileLogger.i(TAG, "终端会话全部停止（原有 ${_tabs.value.size} 个标签）")
        // 自增代号：作废任何在途初始化的结果（切容器时它算的是旧 profile）。
        // 同时释放「在途」标记 —— 否则在途初始化未完成前，每次进终端都命中
        // initialize() 开头的「在途就 return」，会话永远建不起来，表现为黑屏。
        initGuard.invalidate()
        _isPreparing.value = false
        _initError.value = null
        _tabs.value.forEach { runCatching { it.session.finishIfRunning() } }
        _tabs.value = emptyList()
        session = null
        _activeTabId.value = null
        _isRunning.value = false
    }

    @Synchronized
    fun restart() {
        stop()
        initialize()
    }

    /** 界面离开时释放对 TerminalView 的强引用，避免 View/Activity 泄漏 */
    fun releaseViewRefs() {
        sessionClient?.terminalView = null
        viewClient?.terminalView = null
    }

    private fun resolveShellPath(): String {
        val b = "${TermuxConstants.BIN_DIR_PATH}/bash"
        return if (File(b).exists()) b else "/system/bin/sh"
    }

    private fun resolveShellArgs(shellPath: String): Array<String>? =
        if (shellPath.contains(TermuxConstants.BIN_DIR_PATH)) {
            arrayOf("--login")
        } else {
            null
        }

    private fun resolveEnvironment(shellPath: String): Array<String>? {
        val env = mutableMapOf<String, String>()
        val sysEnv = System.getenv()
        if (shellPath.contains(TermuxConstants.BIN_DIR_PATH)) {
            val p = TermuxConstants.PREFIX_DIR_PATH
            val h = TermuxConstants.HOME_DIR_PATH
            val appDataDir = File(TermuxConstants.APP_FILES_DIR).parent
            val sysPath = sysEnv["PATH"] ?: "/system/bin"
            env.putAll(sysEnv)
            env["PREFIX"] = p
            env["HOME"] = h
            env["TERM"] = "xterm-256color"
            env["TMPDIR"] = "$p/tmp"
            env["PATH"] = "$p/bin:$sysPath"
            env["LD_LIBRARY_PATH"] = "$p/lib"
            env["LD_PRELOAD"] = "$p/lib/libexec-wrapper.so"
            env["TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE"] = "force"
            if (appDataDir != null) env["TERMUX_APP__DATA_DIR"] = appDataDir
            env["TERMUX_APP__PACKAGE_MANAGER"] = "apt"
            env["TERMUX_APP__PACKAGE_VARIANT"] = "apt-android-7"
            env["TERMUX_MAIN_PACKAGE_FORMAT"] = "debian"
            env["SHELL"] = "$p/bin/bash"
            env["DPKG_ADMINDIR"] = "$p/var/lib/dpkg"
            env["TERMUX_VERSION"] = "0.118.0"
            env["COLORTERM"] = "truecolor"
            env["LANG"] = "en_US.UTF-8"
            env["PWD"] = h
            env["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
            env["GNUTLS_SYSTEM_CA_CERTS"] = "/system/etc/security/cacerts"
            val cafile = "$p/etc/ssl/certs/ca-certificates.crt"
            if (File(cafile).exists()) {
                env["SSL_CERT_FILE"] = cafile
                env["CURL_CA_BUNDLE"] = cafile
                env["GNUTLS_SYSTEM_CA_CERT"] = cafile
            }
            val aptConfig = "$p/etc/apt/apt.conf.d/99-prefix"
            if (File(aptConfig).exists()) env["APT_CONFIG"] = aptConfig
        } else {
            env.putAll(sysEnv)
            env["TERM"] = "xterm-256color"
            env["PATH"] = "/system/bin:/system/xbin"
        }
        return env.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    enum class ArrowDirection(val code: String) {
        UP("[A"), DOWN("[B"), RIGHT("[C"), LEFT("[D")
    }

    companion object {
        private const val TAG = "TerminalEngine"

        /** 后台命令会话的转录行数（不挂视图，直接用完整转录供 AI 读取）。 */
        private const val TRANSCRIPT_ROWS = 2000
        private const val BACKGROUND_COLUMNS = 80
        private const val BACKGROUND_ROWS = 24

        /** 命令打印 `[command exited: N]` 后等待正常 onFinished 回调的缓冲（毫秒）。 */
        private const val EXIT_MARKER_GRACE_MS = 1_500L

        /**
         * 兜底监控的最长运行时长。
         *
         * 取值：要大于任何正常构建/安装/下载的耗时（AI 工具允许 timeout 最大 1800s），
         * 又不能无限等——命令永不退出时监控协程会每秒轮询一次并全量拷贝转录，永远不停。
         */
        private const val EXIT_MONITOR_MAX_MS = 3_600_000L
        /** 完成兜底监控轮询屏幕缓冲的间隔（毫秒）。 */
        private const val EXIT_MARKER_POLL_MS = 1_000L
        /** 匹配命令退出标记 `[command exited: N]` 的定位前缀。 */
        private const val EXIT_MARKER_PREFIX = "[command exited: "

        @Volatile private var sharedInstance: TerminalEngine? = null

        /**
         * 取全局唯一的引擎实例。
         *
         * 此前有个隐蔽但要命的条件：仅当 `_isRunning == true` 才复用，否则**换一个新对象**。
         * 而 [stop]（切换容器 / 重置容器都会调）恰好把 `_isRunning` 置 false —— 于是
         * stop 之后任何一次 getOrCreate 都会凭空造出第二个引擎：idCounter 从 0 重新开始，
         * 而 AppContextHolder 与界面上那个 TerminalView 仍绑在旧实例上，
         * 真正活着的会话没人渲染 → 「终端一片黑」。
         * 现场日志里 tab id 出现 term-1 → term-2 → **term-1** 的倒序，就是换实例的铁证。
         *
         * 现在只在该实例**根本不存在**时创建。会话没了由 [initialize] 重建，
         * 引擎对象本身必须始终唯一，否则调用方与界面会各持一个。
         */
        fun getOrCreate(): TerminalEngine {
            sharedInstance?.let { return it }
            return synchronized(this) {
                sharedInstance ?: TerminalEngine().also {
                    sharedInstance = it
                    it.initialize()
                }
            }
        }
        fun getShared(): TerminalEngine? = sharedInstance
        fun getPrefixDiskUsage(): String {
            val p = TermuxConstants.PREFIX_DIR
            if (!p.exists()) return "未安装"
            try {
                val total = p.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                return when {
                    total > 100_000_000 -> "${total / 1_000_000}MB"
                    total > 100_000 -> "${total / 1_000}KB"
                    else -> "${total}B"
                }
            } catch (_: Exception) { return "未知" }
        }
    }
}
