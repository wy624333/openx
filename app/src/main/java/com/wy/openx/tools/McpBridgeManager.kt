package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.wy.openx.container.FileLogger
import com.wy.openx.data.store.McpServerConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpBridgeManager {

    companion object {
        private const val TAG = "McpBridge"
    }

    private data class McpSession(
        val config: McpServerConfig,
        val process: Process,
        val writer: BufferedWriter,
        val reader: BufferedReader,
        /** 实际启动位置（容器内 / 宿主），供状态展示与排障。 */
        val runtime: String,
        val requestId: AtomicInteger = AtomicInteger(1),
        var lastUsed: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val gson = Gson()
    private val idleTimeoutMs = TimeUnit.MINUTES.toMillis(5)
    /** 每个 server 最近一次启动失败的诊断信息，供 UI/工具展示真实原因。 */
    private val lastStartHints = ConcurrentHashMap<String, String>()
    /** 正被界面占用（展示连接状态）的 server 名，空闲回收时跳过。 */
    private val claimed = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun listTools(config: McpServerConfig): String {
        val id = nextId(config)
        val request = mapOf(
            "jsonrpc" to "2.0", "id" to id,
            "method" to "tools/list", "params" to emptyMap<String, Any>()
        )
        return sendRequest(config, request, 30)
    }

    fun callTool(config: McpServerConfig, tool: String, toolArgs: Map<String, Any>, timeout: Int): String {
        val id = nextId(config)
        val request = mapOf(
            "jsonrpc" to "2.0", "id" to id,
            "method" to "tools/call",
            "params" to mapOf("name" to tool, "arguments" to toolArgs)
        )
        return sendRequest(config, request, timeout)
    }

    private fun nextId(config: McpServerConfig): Int {
        val session = getOrCreateSession(config)
        return session.requestId.getAndIncrement()
    }

    private fun sendRequest(config: McpServerConfig, request: Map<String, Any>, timeoutSec: Int): String {
        try {
            val session = getOrCreateSession(config)
            val requestJson = gson.toJson(request)

            synchronized(session) {
                session.writer.write(requestJson)
                session.writer.newLine()
                session.writer.flush()
                session.lastUsed = System.currentTimeMillis()

                val startAt = System.currentTimeMillis()
                val deadline = startAt + timeoutSec * 1000L
                // 看门狗：即使 readLine 因半行数据阻塞，超时后 destroy 进程使流 EOF 解除阻塞
                var done = false
                val fired = java.util.concurrent.atomic.AtomicBoolean(false)
                val watchdog = Thread {
                    val wait = deadline - System.currentTimeMillis()
                    if (wait > 0) {
                        try { Thread.sleep(wait) } catch (_: InterruptedException) {}
                    }
                    if (!done && fired.compareAndSet(false, true)) { try { session.process.destroy() } catch (_: Exception) {} }
                }.apply { isDaemon = true; name = "mcp-watchdog-${config.name}" }
                watchdog.start()

                try {
                    val responseLines = mutableListOf<String>()
                    var braceDepth = 0
                    var inString = false
                    var escaped = false
                    var foundStart = false

                    while (System.currentTimeMillis() < deadline) {
                        if (!session.reader.ready()) {
                            Thread.sleep(50)
                            continue
                        }
                        val line = session.reader.readLine() ?: break
                        responseLines.add(line)

                        for (ch in line) {
                            if (escaped) { escaped = false; continue }
                            if (ch == '\\') { escaped = true; continue }
                            if (ch == '"') { inString = !inString; continue }
                            if (inString) continue
                            if (ch == '{') { braceDepth++; foundStart = true }
                            else if (ch == '}') { braceDepth-- }
                        }

                        if (foundStart && braceDepth <= 0) {
                            break
                        }
                    }

                    val raw = responseLines.joinToString("\n")
                    if (!foundStart) {
                        return "{\"error\":{\"message\":\"No JSON response from MCP server\"}}"
                    }
                    return raw
                } finally {
                    done = true
                }
            }
        } catch (e: IOException) {
            cleanup(config.name)
            return gson.toJson(mapOf(
                "error" to mapOf("message" to "MCP communication error: ${e.message}")
            ))
        }
    }

    private fun getOrCreateSession(config: McpServerConfig): McpSession {
        // 管理级锁：防止并发对同一 server 重复拉起进程并互相覆盖（旧进程泄漏）
        synchronized(this) {
            reapIdleSessionsLocked(except = config.name)
            sessions[config.name]?.let { session ->
                if (session.process.isAlive) {
                    return session
                }
                cleanup(config.name)
            }

            val (proc, runtime) = startProcess(config)

            Thread {
                try { proc.errorStream.bufferedReader().forEachLine { /* 排空，防止管道塞死 */ } }
                catch (_: Exception) {}
            }.apply { isDaemon = true; name = "mcp-stderr-${config.name}" }.start()

            val writer = proc.outputStream.bufferedWriter()
            val reader = proc.inputStream.bufferedReader()

            val session = McpSession(config, proc, writer, reader, runtime)
            sessions[config.name] = session
            lastStartHints.remove(config.name)
            return session
        }
    }

    /**
     * 启动 stdio MCP server 进程：容器已就绪时**在容器内执行**（npx / uvx / python3 等
     * 只有容器里才有），否则回退到宿主域 ProcessBuilder。两条路径都注入 [McpServerConfig.env]。
     *
     * @return (进程, 运行位置描述)。全部失败时抛 [IOException] 并附带两边的失败原因，
     *         避免只报宿主域的错误而误导用户（Android 宿主通常没有 python3/npx）。
     */
    private fun startProcess(config: McpServerConfig): Pair<Process, String> {
        val command = config.command ?: throw IOException("MCP server '${config.name}' 未配置启动命令")
        val args = config.args
        val container = AppContextHolder.containerEngine
        val containerReady = container?.isReadyForCommands() == true

        if (containerReady && container != null) {
            val failure = try {
                // 必须传工作区目录：MCP 配置里的路径常写成容器视角（如 codegraph 的
                // --path /root/workspace/...），而 proot 只有在有 projectPath 时才绑定
                // 工作区到 /root/workspace，传 null 会让 server 根本看不到这个目录。
                return container.startStdioProcess(command, args, container.hostWorkspaceDir, config.env) to "容器"
            } catch (e: Exception) {
                FileLogger.w(TAG, "容器内启动 MCP server '${config.name}' 失败，回退宿主: ${e.message}")
                e.message ?: "未知错误"
            }
            // 已装容器仍失败（如容器内无该命令）：宿主域基本也无望，直接报容器错误，
            // 不再做一次注定失败的宿主尝试。
            lastStartHints[config.name] = "容器内启动失败：$failure"
            throw IOException("Failed to start MCP server '${config.name}' in container: $failure")
        }

        val hostFailure = try {
            val pb = ProcessBuilder(listOf(command) + args)
            if (config.env.isNotEmpty()) pb.environment().putAll(config.env)
            return pb.start() to "宿主"
        } catch (e: IOException) {
            e.message ?: "未知错误"
        }
        lastStartHints[config.name] =
            "android 宿主域无该命令（$hostFailure）；请先在「终端」初始化容器，再启用此 Server"
        throw IOException("Failed to start MCP server '${config.name}': $hostFailure")
    }

    /** 回收空闲超过阈值的会话进程 */
    private fun reapIdleSessionsLocked(except: String) {
        val now = System.currentTimeMillis()
        for ((name, s) in sessions) {
            if (name == except) continue
            // 界面正在展示其状态的 server 不回收，否则用户看着的状态会被后台悄悄清掉
            if (name in claimed) continue
            if (now - s.lastUsed > idleTimeoutMs) {
                cleanup(name)
            }
        }
    }

    /** 标记某 server 正被界面占用，空闲回收跳过它。 */
    fun claim(name: String) { claimed.add(name) }

    /** 解除界面占用。 */
    fun release(name: String) { claimed.remove(name) }

    fun cleanup(name: String) {
        sessions.remove(name)?.let { session ->
            try {
                session.process.destroy()
                session.process.waitFor(3, TimeUnit.SECONDS)
                if (session.process.isAlive) {
                    session.process.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
    }

    fun cleanupAll() {
        sessions.keys.toList().forEach { cleanup(it) }
    }

    fun isAlive(name: String): Boolean {
        return sessions[name]?.process?.isAlive == true
    }

    /** 该 server 的会话实际运行位置（"容器"/"宿主"）；未连接时为 null。 */
    fun runtimeOf(name: String): String? = sessions[name]?.runtime

    /** 该 server 最近一次启动失败的诊断信息；无失败则为 null。 */
    fun lastStartHint(name: String): String? = lastStartHints[name]

    fun parseMcpResponse(raw: String): McpResult {
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            val err = obj.get("error")
            if (err != null && !err.isJsonNull) {
                // error 字段可能是对象，也可能是字符串/原始类型
                val message = when {
                    err.isJsonObject -> err.asJsonObject.get("message")?.takeIf { !it.isJsonNull }?.asString ?: raw.take(300)
                    err.isJsonPrimitive -> err.asString
                    else -> raw.take(300)
                }
                McpResult.Error(message)
            } else {
                McpResult.Success(obj)
            }
        } catch (e: JsonSyntaxException) {
            McpResult.Error("Failed to parse MCP response: ${raw.take(500)}")
        } catch (e: Exception) {
            McpResult.Error("Failed to parse MCP response: ${e.message ?: "unknown"} | ${raw.take(300)}")
        }
    }

    sealed class McpResult {
        data class Success(val json: com.google.gson.JsonObject) : McpResult()
        data class Error(val message: String) : McpResult()
    }
}
