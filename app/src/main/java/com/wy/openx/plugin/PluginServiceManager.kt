package com.wy.openx.plugin

import com.google.gson.Gson
import com.wy.openx.container.FileLogger
import com.wy.openx.quickjs.QuickJsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 服务型插件管理器：给声明了 `service` 入口的插件提供**后台常驻运行**能力。
 *
 * ## 设计
 *  - 每个运行中的服务插件 = 一个独立 [QuickJsEngine]（单线程）+ 一个 ServerSocket 接受线程。
 *    不复用 [PluginRuntime] 的引擎：那个是按「AI 工具调用」生命周期设计的，UI 关闭即销毁；
 *    服务要活到用户点「停止」为止。
 *  - HTTP 层是手写的最小实现（ServerSocket + 按行解析头 + Content-Length 读 body），
 *    刻意不引 Ktor/NanoHTTPD：依赖体积和同步冲突都不值得。
 *  - **流式响应**：插件 JS 的 handler 返回 `{stream:true, upstream:{...}}` 时，宿主直接
 *    把上游 SSE 字节搬运给客户端，JS 只负责协议转换（拼上游请求头/改路径），不搬运字节。
 *    这绕开了 QuickJS 单线程不能并发处理的限制。
 *
 * ## 插件 JS 契约（service 脚本）
 * ```js
 * // 必须导出：onStart(info) → 启动回调（info.port = 实际端口）
 * exports.onStart = function (info) { ... }
 * // 可选导出：onStop() → 停止清理
 * exports.onStop = function () { ... }
 * // 必须导出：handleRequest(req) → 同步返回响应描述
 * //   req = {method, path, query, headers, body}
 * //   返回 {status, headers, body}               → 普通响应
 * //   返回 {status, headers, stream:{url, method, headers, body}} → 流式代理
 * //      宿主对上游发起请求，把响应字节（SSE 逐帧）原样转发给客户端
 * ```
 *
 * ## 线程模型
 *  - accept 线程：每连接一个协程（Dispatchers.IO），解析 HTTP 头。
 *  - JS 调用：全部经 QuickJsEngine 自己的单线程 executor（engine.callFunction 线程安全，
 *    内部 submit 到 runtime 线程并等待）。并发请求会在此自然排队——对网关够用。
 *  - 流式搬运：在连接协程里直接读上游 socket 写下游，不经过 JS。
 */
object PluginServiceManager {

    private const val TAG = "PluginServiceManager"
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_BODY_BYTES = 8L * 1024 * 1024
    private const val MAX_PORT_ATTEMPTS = 10
    private const val STREAM_IDLE_TIMEOUT_SEC = 900L

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 流式代理专用 client：长超时、不限制整体时长（SSE 可能跑很久）。 */
    private val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(STREAM_IDLE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class RunningService(
        val pluginId: String,
        /** 监听端口；0 表示没绑端口（纯定时任务插件）。 */
        val port: Int,
        val serverSocket: ServerSocket?,
        val engine: QuickJsEngine,
        @Volatile var stopRequested: Boolean = false
    ) {
        @Volatile var acceptJob: Job = Job()
        /** 定时任务：taskId → 协程句柄（stop 时统一取消）。 */
        val timers = ConcurrentHashMap<String, Job>()
    }

    /** pluginId → 运行中的服务。 */
    private val running = ConcurrentHashMap<String, RunningService>()

    /** 请求计数（日志/状态展示用）。 */
    private val requestCounters = ConcurrentHashMap<String, AtomicLong>()

    /** 当前运行中的服务快照：pluginId → port。 */
    fun runningPorts(): Map<String, Int> = running.entries.associate { it.key to it.value.port }

    fun isRunning(pluginId: String): Boolean = running.containsKey(pluginId)

    fun portOf(pluginId: String): Int = running[pluginId]?.port ?: 0

    fun requestCount(pluginId: String): Long = requestCounters[pluginId]?.get() ?: 0L

    // ---------------------------------------------------------------- 生命周期

    /**
     * 启动一个服务型插件。
     *
     * @param manifest 插件清单（需 service 脚本或合法 schedule；宿主监听端口才需 server 权限）
     * @param serviceSource service 脚本源码
     * @param bridge 该插件的宿主桥（env 持久化等；与 UI 桥同构，toast/rerender 为 no-op）
     * @return 实际监听端口
     */
    fun start(
        manifest: PluginManifest,
        serviceSource: String,
        bridge: PluginHostActionsBridge?
    ): Int {
        val pluginId = manifest.id
        // 有 service 脚本 **且** 声明了 server 权限才开端口。
        // 只想要定时回调的插件无需开放端口（否则会被强制要求 server 权限）。
        val wantsHttp = manifest.service.isNotBlank() &&
            manifest.grantedPermissions.contains(PluginPermission.SERVER)
        val schedulable = manifest.scheduleTasks.filter { it.isValid() }
        // 声明了 service 就能启动，哪怕不申请 server 权限：这类脚本的作用可能是
        // 用 ctx.exec 把容器里的常驻进程拉起来/停掉，并不需要宿主自己绑端口。
        check(manifest.service.isNotBlank() || schedulable.isNotEmpty()) {
            "插件 $pluginId 既没有 service 脚本也没有合法的定时任务"
        }
        synchronized(this) {
            check(!running.containsKey(pluginId)) { "服务已在运行（端口 ${running[pluginId]?.port}）" }

            // 1. 找可用端口：从清单 port 起，占用则顺延。
            //    只声明了定时任务、不提供 HTTP 的插件不绑端口（port 保持 0）。
            var port = 0
            var bound: ServerSocket? = null
            if (wantsHttp) {
                port = if (manifest.port in 1024..65535) manifest.port else 18899
                var attempts = 0
                while (bound == null && attempts < MAX_PORT_ATTEMPTS) {
                    attempts++
                    try {
                        val s = ServerSocket()
                        // 只绑回环 IPv4：127.0.0.1。不能用 getLoopbackAddress()（它返回 ::1，
                        // IPv6 回环），curl/OkHttp 连 127.0.0.1 时走 IPv4 会被拒。
                        // 也不暴露给局域网（插件要局域网共享时由用户显式开启——当前版本不做）。
                        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
                        bound = s
                    } catch (e: Exception) {
                        port++
                    }
                }
                if (bound == null) error("端口 ${manifest.port}~$port 均被占用")
            }

            // 2. 起独立 QuickJS 引擎跑 service 脚本
            val engine = QuickJsEngine()
            try {
                engine.bindNativeInterface(bridge ?: NoopBridge)
                engine.installBootstrap(PluginServiceJsRuntime.SCRIPT, "<service-runtime>")
                // 纯定时插件没有 service 脚本，不装（空源码装进去只会多一次无谓 eval）
                if (serviceSource.isNotBlank()) {
                    engine.installBootstrap(serviceSource, "<service>")
                }
                // onStart（若声明）：告诉插件实际端口（args 必须是 JSON 数组；返回 JSON 字符串）
                val onStartArgs = gson.toJson(listOf(mapOf("port" to port)))
                val startResult = engine.callFunction<String>("__openxServiceOnStart", onStartArgs)
                val startStatus = runCatching { com.google.gson.JsonParser.parseString(startResult).asJsonObject }
                    .getOrElse { error("onStart 响应格式错误：${it.message}") }
                if (!startStatus.get("ok").asBoolean) {
                    error("onStart 失败：${startStatus.get("error")?.asString ?: "未知错误"}")
                }
                // 插件被打断后 VM 重建时，新 VM 是空状态，onStart 必须重跑才算恢复。
                engine.registerBootstrap("__openxServiceOnStart($onStartArgs);", "<service-onstart>")
            } catch (e: Exception) {
                runCatching { engine.close() }
                runCatching { bound?.close() }
                throw IllegalStateException("服务脚本加载失败：${e.message}", e)
            }

            // 3. 接受连接循环（只有真提供 HTTP 服务才跑）
            val svc = RunningService(pluginId, port, bound, engine)
            running[pluginId] = svc
            requestCounters[pluginId] = AtomicLong(0)

            if (bound != null) {
                svc.acceptJob = scope.launch {
                    acceptLoop(svc)
                }
            }

            // 4. 注册定时任务
            schedulable.forEach { s -> registerSchedule(svc, s) }

            FileLogger.i(
                TAG,
                if (port > 0) "服务插件 $pluginId 已启动，端口 $port，定时任务 ${schedulable.size} 个"
                else "定时插件 $pluginId 已启动（无端口），定时任务 ${schedulable.size} 个"
            )
            return port
        }
    }

    /**
     * 为一条定时声明注册调度。
     *
     * 固定间隔用 `scheduleAtFixedRate`，每天固定时刻用「算到下一个该时刻的延迟」
     * 再 `schedule`（不用 cron 解析：只用得到 `HH:mm`，自己算更可控）。
     */
    private fun registerSchedule(svc: RunningService, s: PluginSchedule) {
        val taskId = s.id.ifBlank { "task" }
        val interval = s.intervalMs()
        val minute = s.minuteOfDay()
        if (interval == null && minute == null) return

        // 用协程 delay 循环而不是 ScheduledExecutor：和 stop 共用 scope，
        // 取消时一次清干净，不会留孤儿线程。
        val job = scope.launch {
            if (interval != null) {
                while (!svc.stopRequested && isActive) {
                    delay(interval)
                    if (svc.stopRequested) break
                    fireSchedule(svc, taskId)
                }
            } else if (minute != null) {
                while (!svc.stopRequested && isActive) {
                    delay(millisUntilNext(minute))
                    if (svc.stopRequested) break
                    fireSchedule(svc, taskId)
                    // 跨过这一分钟，避免同一分钟内重复触发
                    delay(60_000)
                }
            }
        }
        svc.timers[taskId] = job
        FileLogger.i(
            TAG,
            "插件 ${svc.pluginId} 定时任务 $taskId 已注册（" +
                (if (interval != null) "每 ${interval / 1000}s" else "每天 ${s.at}") + "）"
        )
    }

    /** 距离下一个 `minuteOfDay` 的毫秒数（今天已过就是明天）。 */
    private fun millisUntilNext(minuteOfDay: Int): Long {
        val cal = java.util.Calendar.getInstance()
        val target = cal.clone() as java.util.Calendar
        target.set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        target.set(java.util.Calendar.MINUTE, minuteOfDay % 60)
        target.set(java.util.Calendar.SECOND, 0)
        target.set(java.util.Calendar.MILLISECOND, 0)
        if (!target.after(cal)) target.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return (target.timeInMillis - cal.timeInMillis).coerceAtLeast(1000L)
    }

    private fun fireSchedule(svc: RunningService, taskId: String) {
        scope.launch {
            runCatching {
                // callFunction 要求参数是 JSON **数组**（对应 JS 函数的实参列表），
                // 直接传对象会报 "QuickJS function args must be a JSON array"。
                val info = gson.toJson(
                    listOf(
                        mapOf(
                            "taskId" to taskId,
                            "firedAt" to System.currentTimeMillis(),
                            "pluginId" to svc.pluginId
                        )
                    )
                )
                val out = svc.engine.callFunction<String>("__openxServiceOnSchedule", info)
                FileLogger.i(TAG, "定时任务触发 ${svc.pluginId}/$taskId → $out")
            }.onFailure {
                FileLogger.w(TAG, "定时任务 ${svc.pluginId}/$taskId 执行失败：${it.message}")
            }
        }
    }

    /** 停止一个服务型插件。幂等。 */
    fun stop(pluginId: String) {
        synchronized(this) {
            val svc = running.remove(pluginId) ?: return
            svc.stopRequested = true
            runCatching { svc.acceptJob.cancel() }
            svc.timers.values.forEach { runCatching { it.cancel() } }
            svc.timers.clear()
            runCatching { svc.serverSocket?.close() }
            runCatching {
                svc.engine.callFunction<String>("__openxServiceOnStop", "[]")
            }
            runCatching { svc.engine.close() }
            requestCounters.remove(pluginId)
            FileLogger.i(TAG, "服务插件 $pluginId 已停止")
        }
    }

    /** 停止全部（App 退出时调）。 */
    fun stopAll() {
        running.keys.toList().forEach { stop(it) }
        scope.cancel()
    }

    // ---------------------------------------------------------------- HTTP 循环

    private fun acceptLoop(svc: RunningService) {
        val server = svc.serverSocket ?: return
        while (!svc.stopRequested && scope.isActive) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (svc.stopRequested) break
                FileLogger.w(TAG, "accept 中断：${e.message}")
                continue
            }
            scope.launch { handleConnection(svc, socket) }
        }
    }

    private fun handleConnection(svc: RunningService, socket: Socket) {
        try {
            // 读超时给足：上游 LLM 网关响应慢（推理模型 reasoning 阶段可能静默 1-2 分钟）。
            // 30s 会导致 kilo/beam 等慢模型对话全部 Read timed out。
            socket.soTimeout = 300_000
            val input = java.io.DataInputStream(java.io.BufferedInputStream(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())

            val request = parseRequest(input) ?: run {
                writeSimpleResponse(output, 400, "Bad Request")
                return
            }
            requestCounters[svc.pluginId]?.incrementAndGet()

            // 调插件 JS：handleRequest
            val reqJson = JSONObject().apply {
                put("method", request.method)
                put("path", request.path)
                put("query", request.query)
                put("headers", JSONObject(request.headers))
                put("body", request.body ?: "")
            }
            val resultJson = try {
                svc.engine.callFunction<String>(
                    "__openxServiceHandleRequest", gson.toJson(listOf(reqJson.toString()))
                )
            } catch (e: Exception) {
                FileLogger.e(TAG, "插件 ${svc.pluginId} 处理请求异常：${e.message}")
                writeSimpleResponse(output, 500, "{\"error\":\"plugin handler failed: ${e.message?.replace("\"", "'")}\"}")
                return
            }
            val result = runCatching { JSONObject(resultJson ?: "{}").also { it.toString() } }.getOrNull()
            if (result == null) {
                writeSimpleResponse(output, 502, "{\"error\":\"plugin returned invalid response\"}")
                return
            }

            if (result.optBoolean("stream")) {
                handleStreamProxy(svc, output, result)
            } else {
                val status = result.optInt("status", 200)
                val bodyStr = result.opt("body")?.toString() ?: ""
                val headers = result.optJSONObject("headers") ?: JSONObject()
                writeFullResponse(output, status, headers, bodyStr.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "连接处理失败（${svc.pluginId}）：${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ---------------------------------------------------------------- 流式代理

    /**
     * 流式代理：宿主直接对上游发起请求，把响应字节原样转发给客户端。
     *
     * JS 侧返回：
     * ```
     * {stream:true, status:200, headers:{...}, upstream:{url, method, headers, body}}
     * ```
     * 上游响应头里的 content-type / transfer-encoding 透传，SSE 帧不解析不缓存，
     * 逐块搬运（8KB 缓冲）。上游断开或 idle 超过 [STREAM_IDLE_TIMEOUT_SEC] 即结束。
     */
    private fun handleStreamProxy(svc: RunningService, output: BufferedOutputStream, result: JSONObject) {
        val upstream = result.optJSONObject("upstream")
        if (upstream == null) {
            writeSimpleResponse(output, 502, "{\"error\":\"stream proxy missing upstream\"}")
            return
        }
        val url = upstream.optString("url")
        if (url.isBlank()) {
            writeSimpleResponse(output, 502, "{\"error\":\"stream proxy missing url\"}")
            return
        }
        val method = upstream.optString("method", "POST").ifBlank { "POST" }
        val upstreamHeaders = upstream.optJSONObject("headers") ?: JSONObject()
        val upstreamBody = upstream.optString("body", "")

        val requestBuilder = Request.Builder()
            .url(url)
            .method(method, if (method.equals("GET", true) || method.equals("HEAD", true)) null
                else upstreamBody.toRequestBody("application/json".toMediaType()))
        for (key in upstreamHeaders.keys()) {
            requestBuilder.header(key, upstreamHeaders.optString(key))
        }

        try {
            streamClient.newCall(requestBuilder.build()).execute().use { resp ->
                // 下游响应头：透传 content-type；强制 chunked（不预知长度）
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ").append(resp.code).append(" ")
                    .append(resp.message.ifBlank { "OK" }).append("\r\n")
                val ct = resp.header("content-type") ?: "text/event-stream"
                sb.append("Content-Type: ").append(ct).append("\r\n")
                sb.append("Cache-Control: no-cache\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")
                output.write(sb.toString().toByteArray(Charsets.UTF_8))
                output.flush()

                resp.body?.byteStream()?.use { upstreamIn ->
                    val buf = ByteArray(8 * 1024)
                    while (scope.isActive && !svc.stopRequested) {
                        val n = try {
                            upstreamIn.read(buf)
                        } catch (e: Exception) {
                            // 读超时（idle）或上游断开：结束搬运
                            break
                        }
                        if (n < 0) break
                        if (n > 0) {
                            output.write(buf, 0, n)
                            output.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "流式代理失败（${svc.pluginId} → $url）：${e.message}")
            // 头还没写出去时给个错误响应；已经在流中途则只能断开
            runCatching {
                writeSimpleResponse(output, 502, "{\"error\":\"upstream failed: ${e.message?.replace("\"", "'")}\"}")
            }
        }
    }

    // ---------------------------------------------------------------- HTTP 解析/写出

    data class ParsedRequest(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: String?
    )

    /** 按字节流解析 HTTP 请求。解析失败返回 null。
     *
     * ⚠ 必须按**字节流**解析，不能用 Reader：Content-Length 是字节数，
     * body 含中文（UTF-8 多字节）时字节数 > 字符数，按字符读会读不满、
     * body 尾部被截断 → 上游收到残缺 JSON 挂死（真机踩过：App 带 system
     * prompt 的流式请求永远转圈，短英文消息却正常）。
     */
    private fun parseRequest(input: java.io.DataInputStream): ParsedRequest? {
        // 请求行 + 头部：逐字节读到 \r\n\r\n（纯 ASCII，直接拼字符串）
        val head = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            if (head.length >= 4 && head.endsWith("\r\n\r\n")) break
            if (head.length > MAX_HEADER_BYTES) return null
        }

        val lines = head.toString().trimEnd().split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        // 合法请求行：METHOD SP PATH SP HTTP/1.1（至少 3 段，且最后一段以 HTTP/ 开头）
        if (parts.size < 3 || !parts.last().startsWith("HTTP/", ignoreCase = true)) return null
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_BODY_BYTES) return null
        val body: String? = if (contentLength > 0) {
            // 按字节读满 Content-Length，再统一 UTF-8 解码
            val bytes = ByteArray(contentLength.toInt())
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        } else null

        // path 与 query 拆分
        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = if (qIdx >= 0) target.substring(qIdx + 1) else ""
        return ParsedRequest(method, path, query, headers, body)
    }

    private fun writeSimpleResponse(output: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(" ")
            .append(statusText(status)).append("\r\n")
        sb.append("Content-Type: application/json; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeFullResponse(
        output: BufferedOutputStream,
        status: Int,
        headers: JSONObject,
        body: ByteArray
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(" ")
            .append(statusText(status)).append("\r\n")
        var hasCt = false
        for (key in headers.keys()) {
            val v = headers.optString(key)
            if (key.equals("content-type", true)) hasCt = true
            if (key.equals("content-length", true) || key.equals("transfer-encoding", true)) continue
            sb.append(key).append(": ").append(v).append("\r\n")
        }
        if (!hasCt) sb.append("Content-Type: application/json; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 408 -> "Request Timeout"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
        else -> "Status"
    }

    /** 空桥：插件没提供宿主桥时的占位（所有方法 no-op）。 */
    object NoopBridge {
        private val gson = Gson()

        fun openxShowToast(msg: String?) {}
        fun openxPersistEnv(json: String?) {}
        fun openxRequestRerender() {}

        // 定时插件没提供宿主桥时总线仍要能用。
        fun openxBusPublish(topic: String?, payload: String?): String {
            val t = topic?.trim().orEmpty()
            if (t.isEmpty()) return gson.toJson(mapOf("ok" to false, "error" to "topic 不能为空"))
            val seq = PluginBus.publish(t, "anonymous", payload ?: "")
            return gson.toJson(mapOf("ok" to true, "seq" to seq))
        }

        fun openxBusSubscribe(topic: String?): String {
            val t = topic?.trim().orEmpty()
            if (t.isEmpty()) return gson.toJson(mapOf("ok" to false, "error" to "topic 不能为空"))
            PluginBus.subscribe(t, "anonymous")
            return gson.toJson(mapOf("ok" to true, "latestSeq" to PluginBus.latestSeq(t)))
        }

        fun openxBusUnsubscribe(topic: String?): String {
            PluginBus.unsubscribe(topic?.trim().orEmpty(), "anonymous")
            return gson.toJson(mapOf("ok" to true))
        }

        fun openxBusPoll(topic: String?, sinceSeq: Double?, limit: Double?): String {
            val t = topic?.trim().orEmpty()
            val msgs = PluginBus.poll(t, "anonymous", sinceSeq?.toLong() ?: 0L, limit?.toInt() ?: 50)
            return gson.toJson(
                mapOf(
                    "ok" to true,
                    "messages" to msgs.map {
                        mapOf(
                            "seq" to it.seq,
                            "from" to it.from,
                            "payload" to it.payload,
                            "at" to it.at
                        )
                    }
                )
            )
        }

        fun openxBusLatestSeq(topic: String?): String =
            gson.toJson(mapOf("ok" to true, "seq" to PluginBus.latestSeq(topic?.trim().orEmpty())))

        /**
         * 无桥接时的 AI 任务入口。
         *
         * 不能省：服务运行时的 NativeInterface 无论有没有 bridge 都会调这个方法，
         * 少了会报 `NativeInterface method not found`。权限校验交给调用方（start 时已校验）。
         */
        fun openxChatRunTask(prompt: String?, newConversation: Boolean, workspacePath: String?): String {
            val text = prompt?.trim().orEmpty()
            if (text.isEmpty()) return gson.toJson(mapOf("ok" to false, "error" to "prompt 不能为空"))
            val runner = com.wy.openx.tools.AppContextHolder.pluginChatRunner
                ?: return gson.toJson(mapOf("ok" to false, "error" to "宿主未就绪"))
            return runCatching {
                val (cid, started) = kotlinx.coroutines.runBlocking {
                    runner(text, newConversation, workspacePath)
                }
                gson.toJson(mapOf("ok" to started, "conversationId" to cid))
            }.getOrElse {
                gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "发起失败")))
            }
        }
    }
}
