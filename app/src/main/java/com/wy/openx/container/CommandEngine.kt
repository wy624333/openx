package com.wy.openx.container

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 流式执行命令时产生的事件，供终端逐行实时渲染。 */
sealed interface CommandEvent {
    /** 一行标准输出/错误（stderr 已合并到 stdout）。 */
    data class Line(val text: String) : CommandEvent
    /** 命令结束，附退出码（兜底 shell 无法取到时为 null）。 */
    data class Exit(val code: Int?) : CommandEvent
}

/** 一次命令执行的结果：限幅后的完整输出 + 退出码（超时/异常时为 null）。 */
data class CommandResult(val output: String, val exitCode: Int?)

/**
 * 命令执行后端抽象：把"在哪执行命令"从硬编码的本地 PRoot 解耦。
 *
 * 本地实现：[LinuxContainerEngine]（PRoot Alpine 容器）。
 * 工具层与终端统一依赖本接口而非具体实现，后续可按执行模式扩展（如远程 SSH）。
 *
 * PRoot 专属方法（[LinuxContainerEngine.startStdioProcess]、
 * [LinuxContainerEngine.buildProotInvocation]、[LinuxContainerEngine.incPromptInFlight] 等）
 * 不属于本接口——它们仅供本地 MCP stdio / 凭据 helper / 终端 PTY 使用。
 */
interface CommandEngine {

    /** 执行后端初始化进度，供 UI 实时展示。 */
    val initProgress: StateFlow<ContainerInitState>

    /**
     * 流式执行命令：每读到一行就 emit 一个 [CommandEvent.Line]，命令结束 emit [CommandEvent.Exit]。
     * 首次调用可能触发后端初始化（本地会解压 rootfs）。
     *
     * [timeoutMs] 为命令最长执行时间（毫秒），超时后强制终止并追加超时提示。
     */
    fun runCommandStream(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Flow<CommandEvent>

    /**
     * 同步执行命令并返回输出。首次调用可能触发后端初始化。
     * [timeoutMs] 为命令最长执行时间（毫秒），超时后强制终止，返回已收集的部分输出。
     */
    suspend fun runCommandSync(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String

    /**
     * 同 [runCommandSync]，但一并返回退出码（超时/异常时为 null）。
     * 供需要据退出码判成败的调用方使用（如 git 写操作）。
     */
    suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult

    /**
     * 同 [runCommandSyncWithExit]，但输出**不做限幅截断**（返回完整输出）。
     * 供需要完整文本的调用方使用（如 git diff 页读取 diff/文件内容）。
     */
    suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult

    /**
     * 仅在后端已就绪时执行命令；不会触发初始化（不解压 rootfs）。
     * 未就绪时返回 null，让调用方走 fallback。供只读工具做性能增强使用（如 search 优先用 rg）。
     */
    suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult?

    /** 后端是否已安装就绪。本地按 rootfs/proot 判断。 */
    fun isContainerInstalled(): Boolean

    /** 基础包是否已配置完成。本地按 provision 标记判断。 */
    fun isProvisioned(): Boolean

    /** 默认命令 shell 路径。本地按 provision 状态选 bash/sh。 */
    fun defaultShell(): String

    /**
     * 后端是否已就绪可执行命令。null 表示就绪；非 null 为未就绪的原因提示，
     * 由调用方展示给用户并引导其完成初始化。
     */
    fun notReadyHint(): String? = null

    /**
     * 幂等地确保后端可用：本地会解压 rootfs/proot 并配置基础包（首次耗时）。
     * 仅由终端页（唯一初始化入口）调用，命令执行入口不再自动触发。
     */
    suspend fun ensureInstalled()

    companion object {
        /** 命令默认超时（毫秒）。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** 命令超时上限（毫秒）。 */
        const val MAX_TIMEOUT_MS = 1_800_000L
    }
}

/**
 * 命令超时后的统一提示。
 *
 * 本地与远程两个执行后端共用：对调用方（模型/用户）来说「命令被超时终止」是同一件事，
 * 提示文案不该因为跑在哪儿而变——否则模型会针对不同措辞各学一套应对。
 * 后半句的提示语是给人看的：它解释的是**为何**被超时终止，而不仅仅是结果。
 */
internal fun commandTimeoutNotice(timeoutMs: Long): String =
    "[命令执行超时：超过 ${timeoutMs}ms 已被强制终止]\n" +
        "提示：若你在启动长驻服务（如 http.server/npm run dev/后台进程），禁止用 nohup ... & 或 & 挂后台——" +
        "它会占住命令输出管道导致本次调用被当作未退出而超时终止。请改用 terminal 工具的 start_background(notify=false) 动作。"