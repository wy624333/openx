package com.wy.openx.git

import com.wy.openx.container.FileLogger
import com.wy.openx.tools.AppContextHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 容器内 credential helper 与 App 之间的文件 IPC 桥。
 *
 * 容器内 git 缺凭据时 helper 写 `cred-req-<id>` 到 `/root/.openx`（经 PRoot -b 绑定即宿主
 * `filesDir/openx` 同一 inode）。本桥轮询该目录捕获请求 → 暴露 [request] 供全局 Compose 弹窗 →
 * 用户回填后 [respond] 写 `cred-resp-<id>` 明文 KV，helper 轮询取走喂回 git，git 自动续跑。
 * 三端（Git 页 / AI Bash / 交互终端）缺凭据都走这条统一链，无需逐命令路径适配。
 *
 * 轮询而非 inotify：inotify 在 PRoot 绑定目录上个别机型会失效，而请求本身就是「用户要填表单」的
 * 场景，1s 延迟无感；单一路径也免了「事件 + 兜底轮询」双触发的去重逻辑。
 *
 * 临时文件清理：请求文件由 helper 拿到响应后自删，响应文件也由 helper 删它读到的；App 只写响应、
 * 不删自己没写的文件，避免与 helper 抢删。
 *
 * 在途计数：收到请求 [LinuxContainerEngine.incPromptInFlight]、写回响应 [LinuxContainerEngine.decPromptInFlight]，
 * 配合 watchdog 在 helper 在途时暂停超时杀进程——用户离开几分钟填凭据，git 命令不会被 120s 强杀。
 */
object GitCredentialBridge {

    private const val TAG = "GitCredentialBridge"
    private const val REQ_PREFIX = "cred-req-"
    private const val RESP_PREFIX = "cred-resp-"
    private const val POLL_INTERVAL_MS = 1000L

    /** 当前待弹凭据请求（host 来自 git credential 协议的 host 字段）。null 表示无待处理。 */
    data class CredentialRequest(val requestId: String, val host: String)

    private val _request = MutableStateFlow<CredentialRequest?>(null)
    val request: StateFlow<CredentialRequest?> = _request.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var started = false

    private val openxDir: File?
        get() = AppContextHolder.context?.let { File(it.filesDir, "openx") }

    /** 启动轮询。幂等，可重复调（App 启动时一次即可）。 */
    fun start() {
        if (started) return
        started = true
        pollJob = scope.launch {
            while (true) {
                runCatching { scanOnce() }
                    .onFailure { FileLogger.w(TAG, "扫描凭据请求失败: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        started = false
    }

    /** 扫一遍待处理请求；已有待弹请求时不再抢占（用户正在填）。 */
    private fun scanOnce() {
        val dir = openxDir ?: return
        if (!dir.isDirectory) return
        if (_request.value != null) {
            // 在途请求要盯着它的文件是否还在：helper 轮询超时（约 25min）后会自删请求文件并退出，
            // 若 App 侧不跟着清，_request 永远非空 → 后续请求全被挡掉、在途计数不归零 → git 命令永不被超时杀。
            val current = _request.value!!
            val stillPending = File(dir, "$REQ_PREFIX${current.requestId}").exists()
            if (!stillPending) {
                FileLogger.i(TAG, "凭据请求已被 helper 放弃 id=${current.requestId}")
                _request.value = null
                AppContextHolder.containerEngine?.decPromptInFlight()
            }
            return
        }
        val pending = dir.listFiles { f ->
            f.isFile && f.name.startsWith(REQ_PREFIX) && !f.name.endsWith(".tmp")
        }?.minByOrNull { it.lastModified() } ?: return
        val requestId = pending.name.removePrefix(REQ_PREFIX)
        val host = parseRequestHost(runCatching { pending.readText() }.getOrDefault(""))
        if (host.isEmpty()) {
            // 读不到 host 的请求（helper 异常写残）直接丢弃，否则弹窗无法填。
            pending.delete()
            return
        }
        FileLogger.i(TAG, "收到凭据请求 host=$host id=$requestId")
        AppContextHolder.containerEngine?.incPromptInFlight()
        _request.value = CredentialRequest(requestId = requestId, host = host)
    }

    /** 用户回填：落凭据文件 + 写响应让 helper 续跑。 */
    suspend fun respond(username: String, password: String) {
        val current = _request.value ?: return
        val dir = openxDir
        if (dir == null) {
            _request.value = null
            return
        }
        runCatching {
            GitCredentialStore.saveFromPrompt(
                host = current.host,
                username = username,
                password = password
            )
            val resp = File(dir, "$RESP_PREFIX${current.requestId}")
            val tmp = File(dir, "${resp.name}.tmp")
            tmp.writeText("username=$username\npassword=$password\n")
            if (!tmp.renameTo(resp)) {
                resp.writeText("username=$username\npassword=$password\n")
                tmp.delete()
            }
        }.onFailure { FileLogger.w(TAG, "写凭据响应失败: ${it.message}") }
        _request.value = null
        AppContextHolder.containerEngine?.decPromptInFlight()
    }

    /** 用户取消：写 cancel 响应让 helper 退出非零，git 报认证失败。 */
    suspend fun cancel() {
        val current = _request.value ?: return
        val dir = openxDir
        if (dir == null) {
            _request.value = null
            return
        }
        runCatching {
            val resp = File(dir, "$RESP_PREFIX${current.requestId}")
            resp.writeText("cancel=1\n")
        }.onFailure { FileLogger.w(TAG, "写取消响应失败: ${it.message}") }
        _request.value = null
        AppContextHolder.containerEngine?.decPromptInFlight()
    }

    /**
     * 从请求文件正文取 host（helper 写入的唯一字段 `host=xxx`）。
     *
     * 取不到 host 时弹窗无法填（不知是哪个站点），调用方会直接丢弃该请求。
     */
    internal fun parseRequestHost(content: String): String = content.lineSequence()
        .firstOrNull { it.startsWith("host=") }
        ?.removePrefix("host=")
        ?.trim()
        .orEmpty()
}
