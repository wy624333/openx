package com.wy.openx.container

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 PRoot 容器命令执行后端，实现 [CommandEngine]。
 *
 * 
 * [CommandEvent] 与 [CommandResult] 已提升为顶层类型（见 [CommandEngine.kt]）。
 * PRoot 专属方法（[startStdioProcess]/[buildProotInvocation]/[buildInteractiveShellInvocation]）不属于接口。
 */
/**
 * 一次 PRoot 调用的完整描述：可执行文件 + 参数列表 + 环境变量。
 *
 * [argv] 的第 0 个元素即 proot 二进制路径，其余为参数。
 *
 * 两种消费方都要拿到「完整 argv」：
 *  - [ProcessBuilder] 直接接收 argv 列表；
 *  - Termux TerminalSession 的 cmd 仅用于 execvp 查找可执行文件，真正的 argv 由它的
 *    args 参数原样构成（native execvp(cmd, argv)，不会自动补 argv[0]），故 args 必须
 *    也是「含 argv[0]=proot 二进制」的完整 argv，否则选项整体错位一位、proot 会把
 *    rootfs 路径误当客户机程序（"is not a regular file"）。[executable] 只用于前者的 cmd 槽位。
 */
data class ProotInvocation(
    val argv: List<String>,
    val env: Map<String, String>
) {
    val executable: String get() = argv.first()

    /**
     * 供 Termux [com.termux.terminal.TerminalSession] 使用的完整环境数组（"KEY=VALUE"）。
     *
     * [ProcessBuilder] 的 environment() 初始即为父进程（App）环境的副本，再叠加 [env]，
     * 因此 proot 能拿到 ANDROID_ROOT/ANDROID_DATA/LD_LIBRARY_PATH 等系统变量；但
     * TerminalSession 接收的是「完整、不继承父进程」的环境数组，只喂 [env] 会让 proot
     * 这个动态链接的 Android 可执行文件因缺系统环境而 exec 失败、瞬间退出（终端表现为
     * 「会话已结束」而日志无其他报错）。故在此显式合并父进程环境，复刻 ProcessBuilder 语义。
     */
    val ptyEnvArray: Array<String>
        get() = (System.getenv() + env).map { "${it.key}=${it.value}" }.toTypedArray()
}

/**
 * 手动 DI 单例（openx 无 Hilt）。构造参数与 AiCode 的 @Inject 构造一致，只是不标注解。
 */
class LinuxContainerEngine(
    private val context: android.content.Context,
    private val containerInstaller: ContainerInstaller,
    private val containerOsDetector: ContainerOsDetector,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val pathHomeResolver: PathHomeResolver
) : CommandEngine {
    /** 容器初始化的实时进度，供所有共享入口（终端页/AI）持有同一份状态。 */
    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    /** 串行化容器初始化（含后台 initScope 内的任务创建与执行），避免多入口并发重复解压/配置。 */
    private val initMutex = Mutex()

    /**
     * 容器初始化的独立协程作用域：不随任何页面/调用方取消而中断，
     * 保证退出终端页后初始化仍在后台继续，下次进入可复用或等待其完成。
     */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 各 profile 在途的初始化 job（受 [initMutex] 保护），完成后移除。
     *
     * 之前是**单个** Job 槽：切容器后新 profile 的 [ensureInstalled] 会复用「旧 profile
     * 还在跑的 doInit」，join 完再判 isInstalledFor(新 profile) 必然为 false →
     * 直接抛「容器未安装」→ 终端回退原生 shell。切容器时这个复用是错的，必须按 profile 分槽。
     */
    private val initJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * 当前选中的 profile（缓存自 [containerSettingsRepository.activeProfileIdFlow]）。
     * 启动首帧为内置 Alpine；profile 切换后由 flow collector 更新。fallback 到内置保证安全。
     */
    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    /**
     * 已完成「一次性就绪准备」的 profile id 集合：门控兼容性巡检与镜像落地（二者无自带守卫、
     * 按 profile 幂等），避免每条命令重复执行。HOME / OS 探测不在此集合内（各自带守卫）。
     * 重置容器 / 删除 profile / rootfs 重建时移除对应 id，使下次重新准备。并发安全。
     */
    private val preparedProfiles: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 上次成功解析容器 $HOME 的 profile id，避免切回旧 profile 时残留他容器值。 */
    @Volatile
    private var homeResolvedProfileId: String? = null

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    /**
     * 等 [currentProfile] 真正追上 [id]，让「切换容器」能返回**已生效**的结论。
     *
     * [currentProfile] 由 init 里的 flow collector 异步更新（Dispatchers.IO），
     * 而 [setActiveProfile] 改的是 flow 的 value —— 两者之间存在窗口。
     * 原先 applyContainerProfile 改完就回调成功，此刻引擎里可能还是**旧 profile**，
     * 用户紧接着开终端就会拿到旧容器的会话。等待上限 3s，超时只记日志不失败
     * （resolveProfile 兜底内置 Alpine，不会真的卡死）。
     */
    suspend fun awaitProfileSwitched(id: String, timeoutMs: Long = 3000): Boolean {
        val ok = withTimeoutOrNull(timeoutMs) {
            while (currentProfile.id != id) delay(10)
            true
        } == true
        if (!ok) FileLogger.w(TAG, "等待容器切换生效超时：期望 $id，当前 ${currentProfile.id}")
        return ok
    }

    /** 按 id 解析 profile：从自定义列表找，兜底内置 Alpine（保证引擎始终可用）。 */
    private suspend fun resolveProfile(id: String): ContainerProfile {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    companion object {
        private const val TAG = "LinuxContainerEngine"

        /** 命令默认超时（毫秒）：未显式指定时套用，避免命令卡死时永久占用会话。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** 命令超时上限（毫秒）：再大的请求也会被钳到此值，防止事实上的“无限等待”。 */
        const val MAX_TIMEOUT_MS = 1_800_000L

        /** 超时后给进程的优雅退出宽限（毫秒），过后强杀。 */
        private const val TIMEOUT_KILL_GRACE_MS = 200L

        /**
         * 基础包配置版本。对应 assets/openx/provision.sh：改脚本（包清单/安装逻辑/镜像源）时同步 +1，
         * 触发在设备上重新执行该脚本（apk add 幂等，已装包跳过）。
         * 独立于 [ContainerInstaller] 的 rootfs INSTALL_VERSION：rootfs 版本升级会删 rootfs
         * （连带清掉本标记），故新 rootfs 必然重跑配置；同 rootfs 下改脚本则靠本版本号触发。
         * 标记文件由脚本自身写入（内容为下方版本号，或用户选择手动安装时的跳过标记
         * provision-script-skipped），App 端仅读它判断是否完成。
         */
        private const val PROVISION_VERSION = "provision-script-v9"
    }

    /** 标记基础包已按 [PROVISION_VERSION] 配置完成（内容为版本号，或用户手动安装的跳过标记），按当前 profile 的 rootfs 目录存放（内置/自定义各自独立）。 */
    private fun provisionMarker(profile: ContainerProfile): java.io.File =
        java.io.File(containerInstaller.rootfsDirFor(profile), ".provisioned")

    /**
     * 在容器内流式执行命令：每读到一行就 emit 一个 [CommandEvent.Line]，命令结束 emit
     * [CommandEvent.Exit]。首次调用会触发 rootfs 安装（幂等）。
     *
     * 与 [runCommandSync] 共用同一进程构建逻辑，区别仅在于输出按行实时下发，
     * 让终端能看到执行「过程」而非只有最终结果。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），默认 [DEFAULT_TIMEOUT_MS]，上限 [MAX_TIMEOUT_MS]。
     * 超时后强制终止子进程并在末尾追加一行超时提示，[CommandEvent.Exit] 退出码记为 null。
     * 由于 readLine 是阻塞读，单靠协程超时无法打断，这里用独立看门狗 destroy 进程来解除阻塞。
     */
    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        // 未就绪（rootfs 未解压或基础包未配置）时不自动初始化，直接提示用户去终端页完成初始化。
        notReadyHint()?.let {
            emit(CommandEvent.Line(it))
            emit(CommandEvent.Exit(null))
            return@flow
        }
        emitAll(streamExecNoInstall(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    /**
     * 在容器内流式执行命令的「裸」实现：每读到一行就 emit [CommandEvent.Line]，命令结束 emit
     * [CommandEvent.Exit]。**不触发懒安装**（不调 [ensureInstalled]），假定 rootfs 已就绪。
     *
     * 抽出此方法供 [runCommandStream]（先 [ensureInstalled]）与初始化脚本执行（先
     * [ContainerInstaller.installRootfsIfNeed]，不能再触发 ensureInstalled 否则递归）共用，
     * 让初始化也能逐行拿到输出以更新进度。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），默认 [DEFAULT_TIMEOUT_MS]，上限 [MAX_TIMEOUT_MS]。
     * 超时后强制终止子进程并在末尾追加一行超时提示，[CommandEvent.Exit] 退出码记为 null。
     * 由于 readLine 是阻塞读，单靠协程超时无法打断，这里用独立看门狗 destroy 进程来解除阻塞。
     */
    private fun streamExecNoInstall(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(流式) cwd=$projectPath timeout=${effectiveTimeout}ms: $command")
        val process = startContainerProcess(command, projectPath)
        val timedOut = AtomicBoolean(false)
        // 看门狗跑在独立 scope（独立 Job）上：若放进包裹 emit 的 coroutineScope 里，emit 的
        // Job 与 flow 收集者不一致会触发「Flow invariant is violated」。这里仅用它在超时时杀进程。
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = launchKillWatchdog(watchScope, process, effectiveTimeout, timedOut, command)
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && process.isAlive) {
                FileLogger.i(TAG, "命令被取消，终止进程: $command")
                runCatching { process.destroy() }
                runCatching { process.destroyForcibly() }
            }
        }
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                emit(CommandEvent.Line(line!!))
            }
            val exitCode = process.waitFor()
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: $command")
                emit(CommandEvent.Line(commandTimeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "命令退出码=$exitCode: $command")
                else FileLogger.v(TAG, "命令完成(退出码 0): $command")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 看门狗超时 destroy 进程会关闭 stdout 管道，使阻塞中的 readLine 抛 IOException
            //（而非返回 null）。若不在此吸收，异常会让 flow 异常终止、CommandEvent.Exit 不再 emit，
            // 上层 executeStream 的 collect 随即中断，已逐行展示给用户的输出在最终 ToolResult 里丢失。
            // 故此处按是否超时分流：超时则 emit 超时提示 + Exit(null)，保留已 emit 的各行；
            // 非 IO 异常也转成一行提示 + Exit，避免 flow 异常终止丢掉已输出内容。
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止(readLine 异常): $command", e)
                emit(CommandEvent.Line(commandTimeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "命令读输出异常(已保留此前输出): $command", e)
                emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            // 协程取消（用户离开页面等）时确保子进程被回收，避免泄漏
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 在容器内同步执行命令并返回输出。首次调用会触发 rootfs 安装（幂等）。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），默认 [DEFAULT_TIMEOUT_MS]，上限 [MAX_TIMEOUT_MS]。
     * 超时后强制终止子进程，返回已收集到的部分输出并在末尾追加超时提示。
     */
    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        // 未就绪时不自动初始化，直接返回引导文案，由用户去终端页完成初始化。
        notReadyHint()?.let { return@withContext it }
        execCaptured(command, projectPath, timeoutMs).output
    }

    /**
     * 同 [runCommandSync]，但一并返回退出码（超时/异常时为 null）。供需要据退出码判成败的调用方
     * 使用——如 git 写操作：git 非零退出码并非进程崩溃，[runCommandSync] 仅返回文本会让上层误报成功。
     */
    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        // 未就绪时不自动初始化，直接返回引导文案，由用户去终端页完成初始化。
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs)
        CommandResult(r.output, r.exitCode)
    }

    /** 一次容器内执行的内部结果：限幅后的完整输出 + 退出码（超时/异常时为 null）。 */
    private data class ExecResult(val output: String, val exitCode: Int?)

    /**
     * 仅在容器已就绪（rootfs 已安装）时执行命令；不会触发 rootfs 解压。
     *
     * 供只读工具做性能增强使用：例如 search 可优先用 rg，但不能因为一次自动批准的搜索
     * 隐式解压容器或联网安装环境。未安装时返回 null。
     */
    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!containerInstaller.isInstalledFor(currentProfile)) return null
        val result = execCaptured(command, projectPath, timeoutMs)
        return CommandResult(result.output, result.exitCode)
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs, unbounded = true)
        CommandResult(r.output, r.exitCode)
    }

    /**
     * 在容器内同步执行命令并捕获输出。**假定 rootfs 已安装**（不做懒安装/配置），
     * 供 [runCommandSync]（先 [ensureInstalled]）与初始化脚本执行（先 [installRootfsIfNeed]）复用，
     * 避免配置流程反向触发 [ensureInstalled] 形成递归。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），钳到 [MAX_TIMEOUT_MS]。超时则强杀进程，
     * 返回已收集的部分输出并在末尾追加超时提示，[ExecResult.exitCode] 记为 null。
     */
    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): ExecResult = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        val timedOut = AtomicBoolean(false)
        val output = BoundedOutput()
        try {
            FileLogger.d(TAG, "执行命令(同步) cwd=$projectPath timeout=${effectiveTimeout}ms: $command")
            val process = startContainerProcess(command, projectPath)
            val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException && process.isAlive) {
                    FileLogger.i(TAG, "命令被取消，终止进程: $command")
                    runCatching { process.destroy() }
                    runCatching { process.destroyForcibly() }
                }
            }

            // 限幅累积：超大输出只保留开头+结尾，避免撑爆内存与模型上下文。
            // 不限幅模式（unbounded）供需要完整输出的调用方使用（如 git diff），由上层自行兜底。
            val boundedOutput = if (unbounded) BoundedOutput(Int.MAX_VALUE, Int.MAX_VALUE) else output
            // 看门狗与读循环并发：超时则 destroy 进程，使阻塞的 readLine 立即返回 null 退出循环。
            var exitCode: Int? = null
            try {
                coroutineScope {
                    val watchdog = launchKillWatchdog(this, process, effectiveTimeout, timedOut, command)
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    try {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            boundedOutput.append(line!!)
                            boundedOutput.append("\n")
                        }
                        exitCode = process.waitFor()
                    } finally {
                        watchdog.cancel()
                        runCatching { reader.close() }
                    }
                }
            } finally {
                cancellationHook?.dispose()
                runCatching { process.destroy() }
            }

            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: $command")
                boundedOutput.append(commandTimeoutNotice(effectiveTimeout))
                boundedOutput.append("\n")
                ExecResult(boundedOutput.build(), null)
            } else {
                FileLogger.v(TAG, "命令完成(退出码 $exitCode，输出 ${boundedOutput.totalChars} 字符): $command")
                ExecResult(boundedOutput.build(), exitCode)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 看门狗超时 destroy 进程会关闭 stdout 管道，使阻塞中的 readLine 抛 IOException
            //（而非返回 null）；逃逸到这里时若已判定超时，应保留此前已读到的部分输出并追加
            // 超时提示，而非当作「执行命令异常」把输出全部丢掉（对齐流式路径 streamExecNoInstall 行为）。
            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止(readLine 异常): $command", e)
                output.append(commandTimeoutNotice(effectiveTimeout))
                output.append("\n")
                ExecResult(output.build(), null)
            } else {
                FileLogger.e(TAG, "执行命令异常: $command", e)
                ExecResult("Error: ${e.message}", null)
            }
        }
    }

    /**
     * 启动看门狗：等待 [timeoutMs] 后若进程仍存活，则标记超时并优雅→强制终止，借此解除
     * 调用方阻塞中的 readLine。返回的 [Job] 由调用方在正常结束时 cancel 掉。
     *
     * 与 AiCode 的区别：暂无可触发在途计数的调用方（credential 桥接在后续子项目接入），
     * [credentialPromptInFlight] 恒为 0，走正常超时分支；字段与 [incPromptInFlight]/
     * [decPromptInFlight] 保留供后续扩展。
     */
    private fun launchKillWatchdog(
        scope: CoroutineScope,
        process: Process,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
        command: String
    ): Job = scope.launch {
        var remaining = timeoutMs
        var totalWaited = 0L
        while (true) {
            delay(remaining)
            if (!process.isAlive) return@launch
            totalWaited += remaining
            val inflight = credentialPromptInFlight.get()
            if (inflight > 0 && totalWaited < MAX_TIMEOUT_MS) {
                FileLogger.i(TAG, "凭据弹窗在途(${inflight})，watchdog 暂缓，再等 60000ms: $command")
                remaining = minOf(60_000L, MAX_TIMEOUT_MS - totalWaited)
                continue
            }
            timedOut.set(true)
            FileLogger.w(TAG, "命令执行超过 ${timeoutMs}ms（累计等待 ${totalWaited}ms，inflight=${inflight}），终止进程: $command")
            runCatching { process.destroy() }
            delay(TIMEOUT_KILL_GRACE_MS)
            if (process.isAlive) runCatching { process.destroyForcibly() }
            return@launch
        }
    }

    /** 当前在途的凭据请求计数（保留给后续 credential helper 接入；openx 首期恒为 0）。 */
    private val credentialPromptInFlight = AtomicInteger(0)

    /** 凭据请求进入途（bridge 收到 helper 的 cred-req 时调）。 */
    fun incPromptInFlight() { credentialPromptInFlight.incrementAndGet() }

    /** 凭据请求结束途（bridge 写回 cred-resp 时调）。 */
    fun decPromptInFlight() { credentialPromptInFlight.decrementAndGet() }

    /** 超时提示行（拼进输出，喂回模型/展示给用户）。 */
    /**
     * 启动进程。rootfs/proot 安装就绪则用 PRoot 进入容器；
     * 否则回退到 Android 原生 shell（rootfs 缺失时的兜底）。
     */
    private fun startContainerProcess(command: String, projectPath: String?): Process {
        val profile = currentProfile
        val useProot = containerInstaller.isInstalledFor(profile)

        val processBuilder = if (useProot) {
            buildProcessBuilder(buildProotInvocation(command, projectPath))
        } else {
            FileLogger.w(TAG, "PRoot 未安装，回退到原生 shell")
            buildNativeProcess(command, projectPath)
        }

        // Redirect stderr to stdout so we capture everything in one stream
        processBuilder.redirectErrorStream(true)
        return processBuilder.start()
    }

    /** 容器是否已安装就绪（按当前 profile）。 */
    override fun isContainerInstalled(): Boolean = containerInstaller.isInstalledFor(currentProfile)

    /**
     * 基础包是否已配置完成（按当前 profile 的标记）。首次进入终端时由初始化菜单
     * （assets/openx/provision.sh）引导安装，完成后脚本写标记；未完成时不影响进入 shell。
     */
    override fun isProvisioned(): Boolean = isProvisionedFor(currentProfile)

    /** 按指定 profile 判断基础包配置是否完成（[defaultShell] 据此选 bash/sh）。 */
    fun isProvisionedFor(profile: ContainerProfile): Boolean {
        val marker = provisionMarker(profile)
        return marker.exists() && marker.readText().trim() == PROVISION_VERSION
    }

    /**
     * 切换 apk 镜像源：持久化选择（下次重新解压/重置后保留），rootfs 已安装则立即覆写
     * /etc/apk/repositories，对下一次 apk 操作生效。
     */
    suspend fun applyApkMirror(mirrorId: String) {
        containerSettingsRepository.setApkMirrorId(mirrorId)
        val profile = currentProfile
        if (containerInstaller.isInstalledFor(profile)) {
            applyStoredApkMirror(profile)
        }
    }

    /** 把仓库里持久化的镜像源选择写入当前 profile 的 rootfs。 */
    private suspend fun applyStoredApkMirror(profile: ContainerProfile) {
        val id = containerSettingsRepository.apkMirrorIdFlow.first()
        containerInstaller.applyApkMirrorToRootfs(
            containerInstaller.rootfsDirFor(profile),
            ApkMirrors.find(id)
        )
    }

    /**
     * 重置容器：删除当前 profile 的 rootfs/proot/标记，下次 [ensureInstalled] 重新解压安装。
     * 调用前须先停止全部正在运行的 PTY 会话（结束 proot 进程），否则文件占用可能导致删除不彻底。
     */
    suspend fun resetContainer() {
        preparedProfiles.remove(currentProfile.id)
        if (homeResolvedProfileId == currentProfile.id) homeResolvedProfileId = null
        containerInstaller.resetContainer(currentProfile)
    }

    /** 删除自定义 profile 后同步清理其快路径标记，避免 id 复用（如同名重建）时误判已准备。 */
    fun forgetProfile(profileId: String) {
        preparedProfiles.remove(profileId)
        if (homeResolvedProfileId == profileId) homeResolvedProfileId = null
    }

    /**
     * 容器未就绪（rootfs 未解压）时返回引导文案，就绪返回 null。
     * 命令执行入口据此不自动初始化、直接失败并引导用户去终端页完成初始化。
     */
    override fun notReadyHint(): String? = notReadyHintFor(currentProfile)

    /** 按指定 profile 判断容器是否就绪时要提示的文案。 */
    fun notReadyHintFor(profile: ContainerProfile): String? {
        if (containerInstaller.isInstalledFor(profile)) return null
        return "容器尚未初始化，请先打开「终端」页完成首次安装"
    }

    /**
     * 容器默认命令 shell：优先用 profile 指定的 [ContainerProfile.shellPath]；未指定时
     * 按 rootfs 里是否实际存在 bash 选 `/bin/bash` 或 `/bin/sh`。
     *
     * `/bin/sh` 是历史默认值：「/bin/sh」视为未指定，继续探测 bash（busybox ash 无 readline，
     * 方向键等转义序列被原样回显成 `^[[A`）；仅自定义的非 sh 路径才按用户显式选择走。
     *
     * 不依赖 provision 标记：用户可能跳过安装菜单（标记为 skipped）或标记版本旧，但 bash
     * 已手动装好；此时若仍回退 /bin/sh，交互 shell 没有 readline，方向键等转义序列会被
     * 原样回显成 `^[[A`。
     */
    override fun defaultShell(): String {
        val profile = currentProfile
        val explicit = profile.shellPath?.trim()
        if (!explicit.isNullOrBlank() && explicit != "/bin/sh") return explicit
        val rootfs = containerInstaller.rootfsDirFor(profile)
        val bashExists = java.io.File(rootfs, "bin/bash").isFile ||
            java.io.File(rootfs, "usr/bin/bash").isFile
        return if (bashExists) "/bin/bash" else "/bin/sh"
    }

    /**
     * 幂等地确保容器可用：解压 rootfs/proot（首次耗时）。所有容器（内置/自定义）一致——
     * 基础工具安装不在此阶段自动执行，由进入终端时的初始化菜单（assets/openx/provision.sh）
     * 引导用户选择，装包失败也不阻塞进入 shell。
     * 仅由终端页（[TerminalSessionManager] 的对应 openx 终端页）作为唯一初始化入口调用；
     * 命令执行入口不再自动触发。
     *
     * 耗时初始化（解压）在引擎级 [initScope] 中执行，不随调用方（终端页 viewModelScope）
     * 取消而中断——退出终端页后初始化仍在后台继续，下次进入可复用同一 job 或等待其完成。
     * 全程通过 [initProgress] 上报阶段进度。rootfs 解压失败时置 [ContainerInitState.Failed]
     * 并抛异常（不静默置 Ready），让终端页进入失败态、可重试。
     */
    override suspend fun ensureInstalled() {
        val profile = currentProfile
        if (containerInstaller.isInstalledFor(profile)) {
            prepareInstalledProfile(profile)
            _initProgress.value = ContainerInitState.Ready
            return
        }
        // 启动或复用后台初始化 job；initMutex 只保护 job 的创建/复用，真正的耗时工作在 initScope 里跑。
        val job = initMutex.withLock {
            val existing = initJobs[profile.id]
            if (existing == null || !existing.isActive) {
                initJobs[profile.id] = initScope.launch {
                    try { doInit(profile) } finally { initJobs.remove(profile.id) }
                }
            }
            initJobs[profile.id]!!
        }
        // 等待完成；若调用方（终端页）被取消，join 抛 CancellationException，但后台 job 继续执行。
        job.join()
        if (containerInstaller.isInstalledFor(profile)) {
            prepareInstalledProfile(profile)
            _initProgress.value = ContainerInitState.Ready
        } else {
            val reason = "容器未安装（缺少 rootfs/proot）"
            _initProgress.value = ContainerInitState.Failed(reason)
            throw IllegalStateException(reason)
        }
    }

    /**
     * 已安装容器的「就绪准备」：兼容性巡检 / 镜像落地 / HOME&OS 探测。
     *
     * 性能：本方法在每次命令执行前都会被 [ensureInstalled] 调用，而其中 HOME / OS 探测各需
     * spawn 一个 proot 进程（`echo $HOME` 尤甚），是 AI 终端工具明显的固定延迟来源。因此：
     * - [preparedProfiles] 门控「无自带守卫且按 profile 一次性」的两项（巡检、镜像落地）；
     * - HOME / OS 探测改为调用点自带守卫（见 [refreshContainerHome] / [detectAndCacheOsIfNeeded]），
     *   以便 profile 之间来回切换时仍能各自刷新、不残留他容器值。
     * 用户主动改镜像不走本缓存——[applyApkMirror] 会立即覆写。
     */
    private suspend fun prepareInstalledProfile(profile: ContainerProfile) {
        if (preparedProfiles.add(profile.id)) {
            // 旧 rootfs（如已导入的 Ubuntu 26.04）不会重新解压，这里兜底巡检修复已知兼容性问题
            containerInstaller.repairRootfsCompatibility(containerInstaller.rootfsDirFor(profile))
            applyStoredApkMirror(profile)
        }
        refreshContainerHome(profile)
        detectAndCacheOsIfNeeded(profile)
    }

    /** 在 [initScope] 中真正执行一次性初始化：解压 rootfs（基础工具由进入终端时的初始化菜单引导安装）。 */
    private suspend fun doInit(profile: ContainerProfile) {
        // installRootfsIfNeed 在真正解压/部署时回调更新进度（已安装则快路径不回调）
        containerInstaller.installRootfsIfNeed(profile) { _initProgress.value = it }
        // 走到这里说明 rootfs 曾被重建（缺失或版本不符）：作废旧的就绪准备与 HOME 解析，
        // 使随后的 prepareInstalledProfile 重新落地镜像源 / 重新探测 HOME（OS 缓存 installRootfsIfNeed 已清）。
        preparedProfiles.remove(profile.id)
        if (homeResolvedProfileId == profile.id) homeResolvedProfileId = null
    }

    /**
     * 查容器内 $HOME 并缓存到 [PathHomeResolver]，供各工具展开 ~。
     * [PathHomeResolver] 为跨 profile 共享单例，故按 profile id 记忆：仅当上次解析的不是当前
     * profile 时才重新查询，既避免每条命令重复 spawn proot，又避免切回旧 profile 时残留他容器的值。
     */
    private suspend fun refreshContainerHome(profile: ContainerProfile) {
        if (homeResolvedProfileId == profile.id) return
        runCatching {
            val result = execCaptured("echo \$HOME", projectPath = null, timeoutMs = 3000)
            val home = result.output.trim().ifEmpty { null }
            if (home != null) {
                pathHomeResolver.containerHome = home
                homeResolvedProfileId = profile.id
            }
        }.onFailure { FileLogger.w(TAG, "查容器 \$HOME 失败", it) }
    }

    /**
     * 容器首次运行后识别系统类型（读 /etc/os-release 的 ID 字段）并写入缓存；
     * 已有缓存或识别失败跳过，下次运行再试。
     */
    private suspend fun detectAndCacheOsIfNeeded(profile: ContainerProfile) {
        if (containerOsDetector.cachedOs(profile.id) != null) return
        runCatching {
            val result = execCaptured(
                "if [ -f /etc/os-release ]; then . /etc/os-release 2>/dev/null; echo \"\$ID\"; fi",
                projectPath = null,
                timeoutMs = 3000
            )
            val osId = result.output.trim().lowercase().ifBlank { null }
            if (osId != null && result.exitCode == 0) {
                containerOsDetector.cacheOs(profile.id, osId)
                FileLogger.i(TAG, "容器系统识别: ${profile.id} -> $osId")
            }
        }.onFailure { FileLogger.w(TAG, "容器系统识别失败: ${profile.id}", it) }
    }

    /**
     * 以长驻进程方式在容器内执行命令（如 `sshd -D`）。调用前需保证容器已安装。
     * 与 [runCommandStream] 不同，这里不读取/消费输出流，由调用方决定如何处理（通常丢弃）。
     */
    fun startProotProcess(command: String, projectPath: String?): Process {
        val pb = buildProcessBuilder(buildProotInvocation(command, projectPath))
        pb.redirectErrorStream(true)
        return pb.start()
    }

    /**
     * 以长驻进程方式在容器内启动一个程序并保留**分离的** stdin/stdout/stderr，供调用方
     * 双向流式通信（如 MCP stdio server：往 stdin 写 JSON-RPC、从 stdout 读 JSON-RPC）。
     *
     * 与 [startProotProcess] 的关键区别：**不** redirectErrorStream，stderr 独立保留，
     * 保证 stdout 是干净的协议流（server 的日志走 stderr，不会污染 JSON-RPC）。
     *
     * [program] 为容器内可执行文件名/路径，[programArgs] 为其参数，二者经
     * `/bin/sh -c 'exec "$0" "$@"'` 逐项透传，避免 shell 引号/转义问题。[extraEnv] 叠加到
     * 容器默认环境（覆盖同名项）。调用前需保证容器已安装（[ensureInstalled]）。
     */
    fun startStdioProcess(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String> = emptyMap(),
        profile: ContainerProfile = currentProfile
    ): Process {
        // proot 二进制非可执行（进程 exec 会抛 EACCES，且被包装成 IOException）时直接失败，
        // 让调用方回退到宿主 ProcessBuilder，而不是留一个含糊的启动错误。
        if (!isContainerInstalled()) {
            throw java.io.IOException("容器尚未安装，无法在容器内启动程序：$program")
        }
        val invocation = buildStdioInvocation(program, programArgs, projectPath, extraEnv, profile)
        val pb = buildProcessBuilder(invocation)
        // 刻意不 redirectErrorStream：stdout 留给 JSON-RPC，stderr 由调用方单独消费。
        return pb.start()
    }

    /** 容器是否已安装就绪。供 MCP stdio 等调用方决定是否走容器执行。 */
    fun isReadyForCommands(): Boolean = isContainerInstalled()

    /**
     * 构造「在容器内直接 exec 某程序（保留分离流）」的 PRoot 调用。
     * 用 `sh -c 'exec "$0" "$@"' program arg1 arg2 …` 把参数原样交给 execvp，规避引号问题。
     */
    private fun buildStdioInvocation(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String>,
        profile: ContainerProfile
    ): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath, profile)
        argv.add("/bin/sh")
        argv.add("-c")
        argv.add("exec \"\$0\" \"\$@\"")
        argv.add(program)
        argv.addAll(programArgs)
        return ProotInvocation(argv, buildContainerEnv() + profile.env + extraEnv)
    }

    /**
     * 构造进入容器执行 [command] 的完整 PRoot 调用（argv + env）。
     * 暴露给终端会话：Termux TerminalSession 需要把可执行文件与参数分开传入。
     */
    fun buildProotInvocation(command: String, projectPath: String?): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath, currentProfile)
        // 用 [defaultShell]：bash 装好后 AI 命令与终端会话都走 bash；装机期间/失败回退 /bin/sh。
        argv.add(defaultShell())
        argv.add("-c")
        argv.add(command)
        return ProotInvocation(argv, buildContainerEnv() + currentProfile.env)
    }

    /**
     * 构造**交互式**容器 shell 调用（供终端页 [com.termux.terminal.TerminalSession] 使用）：
     * 无 `-c` 命令，以 [defaultShell] 直接起交互 shell；工作目录绑定并落到 openx 工作区
     * （宿主 filesDir/workspace ↔ 容器内 /root/workspace），容器默认环境并额外保证
     * parent 环境合并（见 [ProotInvocation.ptyEnvArray]）。
     */
    fun buildInteractiveShellInvocation(): ProotInvocation {
        val workspace = Workspace.root(context)
        val argv = buildBaseProotArgv(workspace.absolutePath, currentProfile)
        argv.add(defaultShell())
        return ProotInvocation(argv, buildContainerEnv() + currentProfile.env)
    }

    /**
     * 宿主侧 openx 工作区目录（与容器内 /root/workspace 对应），保证已存在。
     * 终端会话的宿主 cwd 用此路径：Termux JNI 会在 exec proot 前对宿主 chdir(cwd)，
     * 传容器内路径 `/root/workspace` 会因宿主无此目录而失败（仅打 stderr 噪声，容器内
     * 工作目录由 proot `-w` 决定，无害但难看）。
     */
    val hostWorkspaceDir: String by lazy {
        Workspace.root(context).absolutePath
    }

    /**
     * 把「容器内路径」翻译为宿主真实 [File]，供 AI 文件类工具（file_manager / readFile / writeFile /
     * editFile / explore / search_code …）直接读写「终端里看到的同一批文件」。
     *
     * 返回 null 表示该路径**不是**容器路径（相对路径、或 /sdcard 等宿主路径），交由各工具原有宿主解析处理。
     *
     * 覆盖范围（AI 视角等价于容器 `/`）：
     * - `~`、`/root/` 下各级：家目录；其中三个绑定单独映射——
     *   `/root/workspace`（宿主 filesDir/workspace）、`/root/.openx`（宿主 filesDir/openx）、
     *   `/root/local/<name>`（宿主 externalWorkspaces 中同名目录），其余落在 profile 的 rootfs 内；
     * - 顶层别名 `/workspace`、`/.openx`（文件树侧栏快捷入口）；
     * - 当前 profile 的 extraBindings（格式 `<宿主源>:<容器目标>`）。
     *
     * 不处理非 /root 的绝对路径（如 `/etc`、`/sdcard`、`/data/...`）——避免与宿主语义冲突。
     */
    fun toHostFileIfContainerPath(path: String): File? {
        val raw = path.trim()
        if (raw.isEmpty()) return null
        // 仅处理容器语义：`~` 与 /root/**。相对路径与其它绝对路径交回原解析。
        val expanded = when {
            raw == "~" -> "/root"
            raw.startsWith("~/") -> "/root/" + raw.removePrefix("~/")
            else -> raw
        }
        // 顶层别名 → /root 下真实绑定目标
        val p = when {
            expanded == "/workspace" || expanded.startsWith("/workspace/") ->
                "/root/workspace" + expanded.removePrefix("/workspace")
            expanded == "/.openx" || expanded.startsWith("/.openx/") ->
                "/root/.openx" + expanded.removePrefix("/.openx")
            else -> expanded
        }
        if (p != "/root" && !p.startsWith("/root/")) return null

        val workspaceRoot = Workspace.root(context)
        val openxDir = containerInstaller.openxDir

        // 三个内置绑定
        if (p == "/root/workspace") return workspaceRoot
        if (p.startsWith("/root/workspace/")) {
            return File(workspaceRoot, p.removePrefix("/root/workspace/"))
        }
        if (p == "/root/.openx") return openxDir
        if (p.startsWith("/root/.openx/")) {
            return File(openxDir, p.removePrefix("/root/.openx/"))
        }
        // 外部本地工作区：/root/local/<name>[/...]
        val localPrefix = "/root/local/"
        if (p.startsWith(localPrefix)) {
            val rest = p.removePrefix(localPrefix)
            val name = rest.substringBefore('/')
            val tail = rest.substringAfter('/', "")
            val ws = com.wy.openx.tools.AppContextHolder.currentSettings
                ?.externalWorkspaces?.firstOrNull { it.name == name }
            if (ws != null) return if (tail.isEmpty()) File(ws.path) else File(ws.path, tail)
        }
        // profile 额外绑定（先于 rootfs 兜底匹配，与 proot -b 后者优先一致）
        for (binding in currentProfile.extraBindings) {
            val idx = binding.indexOf(':')
            if (idx <= 0) continue
            val src = binding.substring(0, idx).trim()
            val dst = binding.substring(idx + 1).trim().removeSuffix("/")
            if (dst.isEmpty()) continue
            if (p == dst) return File(src)
            if (p.startsWith("$dst/")) return File(src, p.removePrefix("$dst/"))
        }
        // 其余 /root/**（含 /root 本身）：落在当前 profile 的 rootfs 内（容器 `/` == rootfs 目录）
        return File(containerInstaller.rootfsDirFor(currentProfile), p.removePrefix("/"))
    }

    /**
     * 构造 PRoot 调用的公共前缀 argv：proot 二进制 + rootfs + 标准绑定 + 伪 root + 工作区绑定，
     * 但**不含**最终的客户机命令（由各调用方自行追加 `/bin/sh -c …` 或 `exec` 形式）。
     */
    private fun buildBaseProotArgv(projectPath: String?, profile: ContainerProfile): MutableList<String> {
        val rootfs = containerInstaller.rootfsDirFor(profile).absolutePath
        val prootBin = containerInstaller.prootBin.absolutePath

        val argv = mutableListOf(
            prootBin,
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",  // 绑定 /system 让宿主动态库可用
            "-0"              // 伪 root，apk 等需要
        )

        // 把当前工作区目录绑定到容器内 ~/workspace（即 /root/workspace），使命令与文件工具作用于同一目录
        if (projectPath != null) {
            argv.add("-b")
            argv.add("$projectPath:/root/workspace")
            argv.add("-w")
            argv.add("/root/workspace")
        }

        // 用户通过「添加本地目录」注册的设备目录：绑定到容器内 /root/local/<name>。
        // 外部本地工作区的终端 cwd 即 /root/local/<name>（与宿主路径一一对应）。
        runCatching { com.wy.openx.tools.AppContextHolder.currentSettings?.externalWorkspaces }.getOrNull()
            ?.forEach { ws ->
                if (java.io.File(ws.path).isDirectory) {
                    argv.add("-b")
                    argv.add("${ws.path}:/root/local/${ws.name}")
                }
            }

        // 把 App 私有配置目录绑定到容器内 /root/.openx（读写）：内含 provision.sh 与后续
        // 配置数据。宿主物理目录独立于 rootfs，容器升级重装不丢用户数据。
        // 基础解释器 python3(3.12) 与 git 由进入终端时的初始化菜单（provision.sh）安装。
        // proot 的 -b 要求源路径存在，故先确保目录已建。
        val openxDir = containerInstaller.openxDir.apply { mkdirs() }
        argv.add("-b")
        argv.add("${openxDir.absolutePath}:/root/.openx")

        // 自定义 profile 的额外绑定与参数（内置 profile 这俩为空，此段无操作）
        for (b in profile.extraBindings) {
            argv.add("-b")
            argv.add(b)
        }
        argv.addAll(profile.extraArgs)

        return argv
    }

    /** 容器内进程的标准环境变量（proot loader / 动态库 / PATH / HOME 等）。 */
    private fun buildContainerEnv(): Map<String, String> {
        return mapOf(
            // Android proot 必需的环境变量
            "PROOT_TMP_DIR" to containerInstaller.prootTmpDir.absolutePath, // Android 没有 /tmp
            // Termux proot 的 loader 分离，必须用 PROOT_LOADER/_32 指向，否则无法注入子进程而起不来。
            "PROOT_LOADER" to containerInstaller.prootLoader.absolutePath,
            "PROOT_LOADER_32" to containerInstaller.prootLoader32.absolutePath,
            // Termux proot 动态链接 libtalloc.so.2 / libandroid-shmem.so，需让 linker64 能找到它们；
            // libc.so/liblog.so 走系统默认路径(/system/lib64)。
            "LD_LIBRARY_PATH" to "${containerInstaller.prootLibDir.absolutePath}:/system/lib64:/system/lib",
            // 说明（statx / seccomp）：旧 proot 5.1.0 的 seccomp 过滤表没有 statx，Node 用 statx 解析
            // 模块路径会拿到未翻译的 ~/workspace/xxx → ENOENT「Cannot find module」。Termux proot
            // (5.1.107.x) 的 seccomp 过滤表已包含 statx，默认 seccomp 模式即可正确翻译，故此处
            // **刻意不设 PROOT_NO_SECCOMP**——这正是 Termux 自己用 proot 的方式；强制全量 ptrace
            // (PROOT_NO_SECCOMP=1) 反而在本设备触发过 ptrace(PEEKDATA) I/O error。
            "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME" to "/root",
            // 宿主进程环境的 TMPDIR 指向 App 缓存目录（/data/user/0/<pkg>/cache），容器内 /data 未挂载、
            // 该路径不存在——mktemp/dpkg 等会因找不到临时目录失败，故显式覆盖为容器内 /tmp。
            "TMPDIR" to "/tmp",
            // git 全局配置指向持久挂载里的 .gitconfig（/root/.openx 绑定到宿主 filesDir/openx，
            // 跨 rootfs 升级不丢）。git-credentials 同放该目录，credential.helper=store 经此读。
            "GIT_CONFIG_GLOBAL" to "/root/.openx/.gitconfig",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8"
        )
    }

    private fun buildProcessBuilder(invocation: ProotInvocation): ProcessBuilder {
        val processBuilder = ProcessBuilder(invocation.argv)
        processBuilder.environment().putAll(invocation.env)
        return processBuilder
    }

    private fun buildNativeProcess(command: String, projectPath: String?): ProcessBuilder {
        // Fallback to Android's native shell
        val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command)
        if (projectPath != null) {
            processBuilder.directory(java.io.File(projectPath))
        }
        return processBuilder
    }
}