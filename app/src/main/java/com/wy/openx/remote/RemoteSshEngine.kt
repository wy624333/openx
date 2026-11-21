package com.wy.openx.remote

import android.content.Context
import com.wy.openx.container.BoundedOutput
import com.wy.openx.container.CommandEngine
import com.wy.openx.container.CommandEvent
import com.wy.openx.container.CommandResult
import com.wy.openx.container.ContainerInitState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * 把「在哪执行命令」切到远端 SSH 服务器。
 *
 * ## 为什么复用 [CommandEngine]
 * 它的四个执行方法都只吃一个命令字符串，没有任何本地文件系统假设，
 * 本来就是「命令后端」的抽象。实现它之后，超时、限幅、退出码这些语义自动与本地一致，
 * 将来若要加「执行位置」切换，也只是换一个实例的事。
 *
 * ## 它**不**顶替本地引擎
 * 本地 PRoot 是沙盒，远端是真机器——同一个 `rm -rf /` 后果差着量级。
 * 所以这里只作为一个独立后端供**显式**调用（见 RemoteSshTool），
 * 不做「按执行模式静默改道」：那会让模型以为自己在沙盒里，实际在动用户的服务器。
 *
 * ## 关于 projectPath
 * 收到的 projectPath 是**容器内路径**（`/root/workspace/...`），在远端毫无意义，故忽略；
 * 命令跑在远端登录用户的默认目录下。不替调用方注入 `cd`：远端那个目录不存在时，
 * 注入会让每条命令都失败，而报出来的原因与真实意图看起来毫不相关。
 */
class RemoteSshEngine(
    context: Context,
    keyStore: RemoteKeyStore,
    val connection: RemoteConnection,
) : CommandEngine {

    /**
     * 自持一条连接，不借 [RemoteManager] 那条。
     *
     * 借它就得把用户的浏览会话切到这台机器上（[RemoteManager.connect] 是「切换」语义），
     * 且与正在跑的同步引擎争用同一条通道。
     */
    private val client = RemoteSshClient(context, keyStore)

    /**
     * 远端没有 rootfs / proot 要装，也就不存在初始化过程，恒为 [ContainerInitState.Idle]。
     * 这与 [isContainerInstalled] 返回 true 并不矛盾：那个回答的是「这个后端需不需要装」。
     */
    override val initProgress: StateFlow<ContainerInitState> =
        MutableStateFlow(ContainerInitState.Idle)

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
    ): Flow<CommandEvent> = flow {
        val reason = connectOrReason()
        if (reason != null) {
            emit(CommandEvent.Line("无法连接「${connection.name}」：$reason"))
            emit(CommandEvent.Exit(null))
            return@flow
        }
        emitAll(client.execStream(command, timeoutMs))
    }

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
    ): String = execCaptured(command, timeoutMs).output

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
    ): CommandResult = execCaptured(command, timeoutMs)

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
    ): CommandResult = execCaptured(command, timeoutMs, unbounded = true)

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
    ): CommandResult? {
        // 与本地实现同义：只回答「现在能跑吗」，不主动去建立连接。
        // 没连着就返回 null 让调用方走 fallback，避免为一次可选增强付一次建连代价。
        if (!client.isConnected) return null
        return execCaptured(command, timeoutMs)
    }

    /** 远端后端无需安装，恒可用。 */
    override fun isContainerInstalled(): Boolean = true

    override fun isProvisioned(): Boolean = true

    /** 远端登录 shell 未知，按 POSIX 取 `sh`；写死 `bash` 在精简镜像上会直接失败。 */
    override fun defaultShell(): String = "sh"

    override suspend fun ensureInstalled() = Unit

    /** 挂载或连接被删除时收尾，别把 SSH 通道留着占资源。 */
    suspend fun disconnect() {
        runCatching { client.disconnect() }
    }

    private suspend fun execCaptured(
        command: String,
        timeoutMs: Long,
        unbounded: Boolean = false,
    ): CommandResult = withContext(Dispatchers.IO) {
        val reason = connectOrReason()
        if (reason != null) return@withContext CommandResult("无法连接「${connection.name}」：$reason", null)

        // 与本地引擎同一口径：默认只留开头+结尾各两万字符，避免一次 `cat` 大文件撑爆内存与模型上下文；
        // 需要完整输出的调用方（如 git diff）显式走 unbounded 分支，由它自己兜底。
        val output = if (unbounded) BoundedOutput(Int.MAX_VALUE, Int.MAX_VALUE) else BoundedOutput()
        var exitCode: Int? = null
        client.execStream(command, timeoutMs).collect { event ->
            when (event) {
                is CommandEvent.Line -> {
                    output.append(event.text)
                    output.append("\n")
                }
                is CommandEvent.Exit -> exitCode = event.code
            }
        }
        CommandResult(output.build(), exitCode)
    }

    /**
     * 确保已连上目标服务器，返回 null 表示可用；否则返回给用户看的原因。
     *
     * 不抛异常：连不上是这个功能**预期内**会发生的状态（服务器关机、网断、指纹待确认），
     * 让调用方每次都套一层 runCatching 只会把错误处理散到各处。
     */
    private suspend fun connectOrReason(): String? =
        runCatching {
            if (!client.isConnected) client.connect(connection)
        }.exceptionOrNull()?.let { it.message ?: "连接失败" }
}
