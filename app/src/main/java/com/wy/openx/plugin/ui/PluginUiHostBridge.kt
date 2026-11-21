package com.wy.openx.plugin.ui

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wy.openx.audio.AudioPlaybackHub
import com.wy.openx.container.FileLogger
import com.wy.openx.data.provider.ProviderHttp
import com.wy.openx.plugin.PluginBus
import com.wy.openx.plugin.PluginEnvStore
import com.wy.openx.plugin.PluginPermission
import com.wy.openx.plugin.PluginStore
import com.wy.openx.tools.ToolCall
import com.wy.openx.tools.ToolCallFunction
import com.wy.openx.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 插件 UI 的**宿主桥**：实现 JS 侧调用的 `__openx*` 原生方法。
 *
 * ## 为什么这些方法是同步的
 * QuickJS 的 native 调用是同步的，而 `callTool` 是 `suspend`、HTTP 是 IO。
 * 这里用 `runBlocking` 在 **插件自己的 JS 线程**上阻塞等待 —— 不会卡 UI 线程
 * （UI 渲染走另一个协程），代价是插件 JS 在等待期间不能做别的事，可接受。
 *
 * ## 权限强制
 * 每个方法进来先查权限。JS 侧也查了一遍（`requirePermission`），
 * 这里是**第二道防线** —— JS 可以绕过（改 runtime 脚本），宿主不能。
 *
 * ## env 持久化
 * 存在 `pluginEnv/<pluginId>.json`。只允许插件读写**自己的** env，
 * 不能跨插件读取（否则一个插件能偷另一个插件的 API key）。
 */
class PluginUiHostBridge(
    private val context: Context,
    private val pluginId: String,
    private val permissions: Set<PluginPermission>,
    private val store: PluginStore,
    private val toolRegistry: ToolRegistry?,
    /** 弹 toast 的实际实现（由 UI 层注入）。 */
    private val toastImpl: (String) -> Unit,
    /** 请求宿主重渲染（由 UI 层注入）。 */
    private val rerenderImpl: () -> Unit,
    /** 服务启停成功后回调（由 UI 层注入，用于同步插件管理页状态）。 */
    private val serviceChangedImpl: () -> Unit = {},
    /** 持久化 env（由 UI 层或 Store 注入）。 */
    private val persistEnvImpl: ((String) -> Unit)? = null,
    /**
     * 需要 Activity 的能力（文件选择/保存、图片选择）。
     * 为 null 时这些 ctx API 会返回"宿主未提供"错误，而不是崩溃——
     * 例如在非交互场景（测试、预览）下打开插件界面时。
     */
    private val hostActions: PluginHostActions? = null,
    /** 跑挂起请求的后台作用域；null 时所有 pending 类 API 返回错误。 */
    private val hostScope: kotlinx.coroutines.CoroutineScope? = null,
    /**
     * 请求结果回来时的回调：(pendingId, resultJson) —— 需切回 JS 线程执行。
     * 由 [PluginUiSession] 注入（它持有 lock，能保证 QuickJS 只在单线程上用）。
     */
    private val onPendingSettled: ((String, String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "PluginUiHostBridge"
        private const val MAX_HTTP_BYTES = 2L * 1024 * 1024   // 2MB 响应上限
        /**
         * 插件 HTTP 超时。
         *
         * 原来 60s 对国内网络下的慢接口（比如电台目录、图片直链）不够用，
         * 容易表现为“插件转圈很久才报错”。放宽到 300s。
         *
         * 注意：ctx.http 是 runBlocking 同步实现，action 派发期间宿主持锁，
         * 所以**插件侧不要在 onClick 里直接 await**（见插件开发指南）。
         */
        private const val MAX_HTTP_TIMEOUT_SEC = 300L
    }

    private val gson = Gson()

    /** JS 侧 `__openxShowToast(msg)` */
    /**
     * WebView 控制器命令（插件 → 宿主）。同步返回 `{result}` / `{error}` JSON。
     *
     * 与其它 openx*Async 不同，这是**同步**的：controller 方法（loadUrl/getState 等）
     * 在插件里就是当同步 API 用的，而且 WebView 的资源拦截回调要求当场返回。
     */
    fun openxWebViewControllerCommand(payloadJson: String?): String =
        WebViewControllers.handleCommand(payloadJson ?: "")

    fun openxShowToast(message: String?) {
        if (PluginPermission.TOAST !in permissions) {
            FileLogger.e(TAG, "插件 $pluginId 未声明 toast 权限，忽略提示：$message")
            return
        }
        // 插件动作派发跑在 Dispatchers.Default，而 Toast 必须在主线程。
        // 不切线程的话这里会静默失败，表现为"插件报错了但用户什么也看不到"。
        val text = message.orEmpty()
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runCatching { toastImpl(text) }
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { toastImpl(text) }
            }
        }
    }

    /** JS 侧 `__openxRequestRerender()` —— useState setter 触发。 */
    fun openxRequestRerender() {
        runCatching { rerenderImpl() }
    }

    // ------------------------------------------------------------ 音频播放
    //
    // 全部是**非阻塞**的：AudioPlaybackHub 把命令 post 到主线程就返回，
    // 这里不需 runBlocking。这一点很关键 —— action 派发期间宿主持有插件 session 锁，
    // 一旦在这里阻塞，整个插件面板会僵住。

    private fun requireAudio(): Boolean {
        if (PluginPermission.AUDIO !in permissions) {
            FileLogger.e(TAG, "插件 $pluginId 未声明 audio 权限，忽略播放请求")
            return false
        }
        return true
    }

    /** JS 侧 `__openxAudioPlay(uri, title, artist)` */
    fun openxAudioPlay(uri: String?, title: String?, artist: String?) {
        if (!requireAudio()) return
        val src = uri.orEmpty()
        FileLogger.i(TAG, "openxAudioPlay 被调用: uri=$src title=${title.orEmpty()}")
        if (src.isBlank()) {
            FileLogger.e(TAG, "插件 $pluginId 播放地址为空")
            return
        }
        runCatching {
            AudioPlaybackHub.play(context, src, title.orEmpty(), artist.orEmpty())
        }.onFailure { FileLogger.e(TAG, "播放失败: ${it.message}") }
    }

    /** JS 侧 `__openxAudioPlayQueue(tracksJson, startIndex)` —— tracksJson 是 [{uri,title,artist}] */
    fun openxAudioPlayQueue(tracksJson: String?, startIndex: Double) {
        if (!requireAudio()) return
        val raw = tracksJson.orEmpty()
        if (raw.isBlank()) return
        runCatching {
            // 用 JsonParser 而不是 fromJson(..., Array<Map<..>>::class.java)：
            // Kotlin 不允许在 class literal 上用泛型，那样编译不过。
            val arr = com.google.gson.JsonParser.parseString(raw).asJsonArray
            val tracks = arr.mapNotNull { el ->
                runCatching {
                    val o = el.asJsonObject
                    AudioPlaybackHub.Track(
                        uri = o.get("uri")?.asString.orEmpty(),
                        title = o.get("title")?.asString.orEmpty(),
                        artist = o.get("artist")?.asString.orEmpty()
                    )
                }.getOrNull()
            }.filter { it.uri.isNotBlank() }
            if (tracks.isEmpty()) return
            FileLogger.i(TAG, "openxAudioPlayQueue: ${tracks.size} 首, 从 ${startIndex.toInt()} 开始")
            AudioPlaybackHub.playQueue(context, tracks, startIndex.toInt())
        }.onFailure { FileLogger.e(TAG, "playQueue 失败: ${it.message}") }
    }

    fun openxAudioNext() {
        if (!requireAudio()) return
        runCatching { AudioPlaybackHub.next() }
    }

    fun openxAudioPrevious() {
        if (!requireAudio()) return
        runCatching { AudioPlaybackHub.previous() }
    }

    fun openxAudioPause() {
        if (!requireAudio()) return
        runCatching { AudioPlaybackHub.pause() }
    }

    fun openxAudioResume() {
        if (!requireAudio()) return
        runCatching { AudioPlaybackHub.resume() }
    }

    fun openxAudioToggle() {
        if (!requireAudio()) return
        runCatching { AudioPlaybackHub.toggle() }
    }

    fun openxAudioStop() {
        if (!requireAudio()) return
        runCatching { AudioPlaybackHub.stop() }
    }

    fun openxAudioSeek(positionMs: Double) {
        if (!requireAudio()) return
        runCatching { AudioPlaybackHub.seekTo(positionMs.toLong()) }
    }

    fun openxAudioSetVolume(volume: Double) {
        if (!requireAudio()) return
        runCatching { AudioPlaybackHub.setVolume(volume.toFloat()) }
    }

    /** JS 侧 `__openxAudioState()` —— 返回状态快照 JSON。 */
    fun openxAudioState(): String {
        if (!requireAudio()) return "{}"
        val s = AudioPlaybackHub.state()
        val map = mutableMapOf<String, Any?>(
            "playing" to s.playing,
            "buffering" to s.buffering,
            "positionMs" to s.positionMs,
            "durationMs" to s.durationMs,
            "volume" to s.volume,
            "error" to s.error
        )
        val np = s.nowPlaying
        map["uri"] = np?.uri
        map["title"] = np?.title
        map["artist"] = np?.artist
        map["isLive"] = np?.isLive ?: false
        return runCatching { gson.toJson(map) }.getOrDefault("{}")
    }

    /** JS 侧 `__openxPersistEnv(json)` */
    fun openxPersistEnv(json: String?) {
        if (PluginPermission.ENV !in permissions) return
        runCatching {
            if (persistEnvImpl != null) {
                persistEnvImpl(json.orEmpty())
            } else {
                envFile().writeText(json.orEmpty())
            }
        }.onFailure { FileLogger.e(TAG, "持久化 env 失败: ${it.message}") }
    }

    /**
     * JS 侧 `__openxHttpAsync(urlJson)` —— 同步阻塞实现。
     *
     * 返回 JSON 字符串：`{"ok":true,"status":200,"body":"..."}`
     * 或 `{"ok":false,"error":"..."}`。
     */
    fun openxHttpAsync(optionsJson: String?): String {
        if (PluginPermission.HTTP !in permissions) {
            return errorResult("缺少 http 权限")
        }
        val opt = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(optionsJson ?: "{}", Map::class.java) as? Map<String, Any?>
        }.getOrNull() ?: return errorResult("参数解析失败")

        val url = opt["url"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return errorResult("缺少 url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return errorResult("只支持 http/https")
        }
        val method = (opt["method"]?.toString() ?: "GET").uppercase()
        val bodyText = opt["body"]?.toString()
        val headers = runCatching {
            @Suppress("UNCHECKED_CAST")
            (opt["headers"] as? Map<String, Any?>).orEmpty()
        }.getOrDefault(emptyMap())

        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v?.toString().orEmpty()) }
            when (method) {
                "GET", "HEAD" -> builder.method(method, null)
                "POST", "PUT", "PATCH", "DELETE" -> {
                    val contentType = headers.entries
                        .firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value?.toString() ?: "application/json"
                    builder.method(method, (bodyText ?: "").toRequestBody(contentType.toMediaType()))
                }
                else -> return errorResult("不支持的 method：$method")
            }

            // 单独建 client：插件请求的超时和宿主业务请求解耦
            val client = ProviderHttp.shortClient.newBuilder()
                .callTimeout(MAX_HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()

            runBlocking {
                client.newCall(builder.build()).execute().use { resp ->
                    val stream = resp.body?.byteStream()
                    val bytes = stream?.let { s ->
                        val buf = ByteArray(8192)
                        val out = java.io.ByteArrayOutputStream()
                        var total = 0L
                        while (true) {
                            val n = s.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_HTTP_BYTES) break
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } ?: ByteArray(0)
                    val text = runCatching { String(bytes, Charsets.UTF_8) }
                        .getOrElse { "" }
                    gson.toJson(
                        mapOf(
                            "ok" to resp.isSuccessful,
                            "status" to resp.code,
                            "body" to text,
                            "truncated" to (bytes.size >= MAX_HTTP_BYTES)
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            FileLogger.e(TAG, "插件 $pluginId HTTP 失败: ${e.message}")
            errorResult(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * JS 侧 `__openxCallToolAsync(toolName, paramsJson)` —— 调用宿主工具。
     *
     * 同步阻塞等待工具执行完（工具本身是 suspend）。返回 `{"ok":true,"result":"..."}`。
     */
    // ---------------------------------------------------------------- 插件间总线（需 bus 权限）

    /** JS 侧 `ctx.bus.publish(topic, payload)` */
    fun openxBusPublish(topic: String?, payload: String?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        val t = topic?.trim().orEmpty()
        if (t.isEmpty()) return errorResult("topic 不能为空")
        val seq = PluginBus.publish(t, pluginId, payload ?: "")
        return gson.toJson(mapOf("ok" to true, "seq" to seq))
    }

    /** JS 侧 `ctx.bus.subscribe(topic)` */
    fun openxBusSubscribe(topic: String?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        val t = topic?.trim().orEmpty()
        if (t.isEmpty()) return errorResult("topic 不能为空")
        PluginBus.subscribe(t, pluginId)
        return gson.toJson(mapOf("ok" to true, "latestSeq" to PluginBus.latestSeq(t)))
    }

    /** JS 侧 `ctx.bus.unsubscribe(topic)` */
    fun openxBusUnsubscribe(topic: String?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        PluginBus.unsubscribe(topic?.trim().orEmpty(), pluginId)
        return gson.toJson(mapOf("ok" to true))
    }

    /** JS 侧 `ctx.bus.poll(topic, sinceSeq, limit)` */
    fun openxBusPoll(topic: String?, sinceSeq: Double?, limit: Double?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        val t = topic?.trim().orEmpty()
        if (t.isEmpty()) return errorResult("topic 不能为空")
        val msgs = PluginBus.poll(
            topic = t,
            pluginId = pluginId,
            sinceSeq = sinceSeq?.toLong() ?: 0L,
            limit = limit?.toInt() ?: 50
        )
        return gson.toJson(
            mapOf(
                "ok" to true,
                "messages" to msgs.map {
                    mapOf(
                        "seq" to it.seq,
                        "topic" to it.topic,
                        "from" to it.from,
                        "payload" to it.payload,
                        "at" to it.at
                    )
                }
            )
        )
    }

    /** JS 侧 `ctx.bus.latestSeq(topic)` */
    fun openxBusLatestSeq(topic: String?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        return gson.toJson(
            mapOf("ok" to true, "seq" to PluginBus.latestSeq(topic?.trim().orEmpty()))
        )
    }

    /** JS 侧 `ctx.callTool(name, params)` —— 调用其他插件或内置工具。 */
    fun openxCallToolAsync(toolName: String?, paramsJson: String?): String {
        if (PluginPermission.CALL_TOOL !in permissions) {
            return errorResult("缺少 call_tool 权限")
        }
        val name = toolName?.trim().orEmpty()
        if (name.isEmpty()) return errorResult("缺少工具名")
        val registry = toolRegistry ?: return errorResult("工具注册表不可用")

        return try {
            val call = ToolCall(
                id = "plugin_${System.currentTimeMillis()}",
                function = ToolCallFunction(
                    name = name,
                    arguments = paramsJson?.takeIf { it.isNotBlank() } ?: "{}"
                )
            )
            val result = runBlocking { registry.executeToolCall(call) }
            gson.toJson(mapOf("ok" to true, "result" to result))
        } catch (e: Throwable) {
            FileLogger.e(TAG, "插件 $pluginId 调用工具 $name 失败: ${e.message}")
            errorResult(e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------- 二期能力：剪贴板 / 通知 / 文件 / 图片

    /**
     * 插件文件沙箱目录：`filesDir/openx/plugin-files/<pluginId>/`。
     *
     * 不放到外部存储：外部目录任何 App 都能读写，而插件内容有可能含用户数据。
     * 沙箱内的路径在 ctx API 里用相对路径表示（如 `note.txt`），写绝对路径会被拒绝。
     */
    private fun sandboxDir(): File =
        File(File(context.filesDir, "openx/plugin-files"), pluginId).apply { mkdirs() }

    /** 解析沙箱相对路径；越出沙箱（`..` / 绝对路径）返回 null。 */
    private fun resolveInSandbox(rel: String): File? {
        if (rel.isBlank()) return null
        val root = sandboxDir().canonicalFile
        val f = File(root, rel).canonicalFile
        return if (f.path == root.path || f.path.startsWith(root.path + File.separator)) f else null
    }

    /**
     * JS 侧 `__openxServiceControlAsync({cmd:'start'|'stop'|'status'})` — 服务启停/查询。
     *
     * 与 [MainViewModel.startPluginService] 同款逻辑，但同步执行（服务启动本身很快，
     * QuickJS 调用返回即代表启动完成/失败，UI 面板无需轮询）。
     * 需要 server 权限（与服务入口同门槛，防止非服务型插件乱启停）。
     */
    fun openxServiceControlAsync(optionsJson: String?): String {
        if (PluginPermission.SERVER !in permissions) {
            return errorResult("缺少 server 权限")
        }
        val opt = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(optionsJson ?: "{}", Map::class.java) as? Map<String, Any?>
        }.getOrNull() ?: return errorResult("参数解析失败")
        val cmd = opt["cmd"]?.toString()?.lowercase().orEmpty()

        return try {
            when (cmd) {
                "status" -> {
                    val running = com.wy.openx.plugin.PluginServiceManager.isRunning(pluginId)
                    val port = com.wy.openx.plugin.PluginServiceManager.portOf(pluginId)
                    val requests = com.wy.openx.plugin.PluginServiceManager.requestCount(pluginId)
                    gson.toJson(mapOf("ok" to true, "running" to running, "port" to port, "requests" to requests))
                }
                "start" -> {
                    val installed = store.load()
                        .firstOrNull { it.manifest.id == pluginId }
                        ?: return errorResult("插件不存在：$pluginId")
                    val manifest = installed.manifest
                    if (manifest.service.isBlank()) return errorResult("该插件未声明 service 入口")
                    if (!manifest.grantedPermissions.contains(PluginPermission.SERVER)) {
                        return errorResult("插件清单未声明 server 权限")
                    }
                    if (com.wy.openx.plugin.PluginServiceManager.isRunning(pluginId)) {
                        return errorResult("服务已在运行（端口 ${com.wy.openx.plugin.PluginServiceManager.portOf(pluginId)}）")
                    }
                    val src = store.readServiceSource(installed)
                        ?: return errorResult("服务脚本不存在：${manifest.service}")
                    val bridge = com.wy.openx.plugin.PluginHostActionsBridge(
                        context, manifest.id, manifest.grantedPermissions
                    )
                    val port = com.wy.openx.plugin.PluginServiceManager.start(manifest, src, bridge)
                    runCatching { serviceChangedImpl() }
                    gson.toJson(mapOf("ok" to true, "port" to port))
                }
                "stop" -> {
                    com.wy.openx.plugin.PluginServiceManager.stop(pluginId)
                    runCatching { serviceChangedImpl() }
                    gson.toJson(mapOf("ok" to true))
                }
                else -> errorResult("未知 cmd：$cmd（支持 start/stop/status）")
            }
        } catch (e: Throwable) {
            FileLogger.e(TAG, "插件 $pluginId 服务控制 $cmd 失败: ${e.message}")
            errorResult(e.message ?: e.javaClass.simpleName)
        }
    }

    /** JS 侧 `__openxClipboardCopy(text)` */
    fun openxClipboardCopy(text: String?): String {
        if (PluginPermission.CLIPBOARD !in permissions) return errorResult("缺少 clipboard 权限")
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("plugin", text.orEmpty()))
            gson.toJson(mapOf("ok" to true))
        }.getOrElse { errorResult(it.message ?: "剪贴板写入失败") }
    }

    /** JS 侧 `__openxClipboardPaste()` */
    fun openxClipboardPaste(): String {
        if (PluginPermission.CLIPBOARD !in permissions) return errorResult("缺少 clipboard 权限")
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            } else ""
            gson.toJson(mapOf("ok" to true, "text" to text))
        }.getOrElse { errorResult(it.message ?: "剪贴板读取失败") }
    }

    /** JS 侧 `__openxNotifyAsync({title,text,id})` */
    fun openxNotifyAsync(optionsJson: String?): String {
        if (PluginPermission.NOTIFY !in permissions) return errorResult("缺少 notify 权限")
        val opt = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(optionsJson ?: "{}", Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()
        val title = opt["title"]?.toString()?.takeIf { it.isNotBlank() } ?: "OpenX 插件"
        val text = opt["text"]?.toString().orEmpty()
        return runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val channelId = "openx_plugin"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(channelId) == null) {
                    nm.createNotificationChannel(
                        android.app.NotificationChannel(
                            channelId, "插件通知",
                            android.app.NotificationManager.IMPORTANCE_DEFAULT
                        )
                    )
                }
            }
            val notif = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            // id 取插件名哈希，避免不同插件互相覆盖通知
            nm.notify(pluginId.hashCode(), notif)
            gson.toJson(mapOf("ok" to true))
        }.getOrElse { errorResult(it.message ?: "发送通知失败") }
    }

    /** JS 侧 `__openxReadFileAsync(relPath)` —— 沙箱内读取 */
    fun openxReadFileAsync(relPath: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val f = resolveInSandbox(relPath.orEmpty()) ?: return errorResult("路径不合法（只能访问插件目录内）")
        if (!f.isFile) return errorResult("文件不存在：$relPath")
        return runCatching {
            gson.toJson(mapOf("ok" to true, "content" to f.readText(), "size" to f.length()))
        }.getOrElse { errorResult(it.message ?: "读取失败") }
    }

    /** JS 侧 `__openxWriteFileAsync({path,content})` —— 沙箱内写入 */
    fun openxWriteFileAsync(optionsJson: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val opt = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(optionsJson ?: "{}", Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()
        val rel = opt["path"]?.toString().orEmpty()
        val content = opt["content"]?.toString().orEmpty()
        val f = resolveInSandbox(rel) ?: return errorResult("路径不合法（只能写插件目录内）")
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content)
            gson.toJson(mapOf("ok" to true, "size" to f.length()))
        }.getOrElse { errorResult(it.message ?: "写入失败") }
    }

    /** JS 侧 `__openxListFilesAsync(dir)` —— 列出沙箱目录 */
    fun openxListFilesAsync(dirRel: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val f = if (dirRel.isNullOrBlank()) sandboxDir() else resolveInSandbox(dirRel)
        if (f == null || !f.isDirectory) return errorResult("目录不存在")
        return runCatching {
            val names = f.listFiles()?.map { it.name }?.sorted().orEmpty()
            gson.toJson(mapOf("ok" to true, "files" to names))
        }.getOrElse { errorResult(it.message ?: "列目录失败") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun optOf(json: String?): Map<String, Any?> =
        runCatching { gson.fromJson(json ?: "{}", Map::class.java) as? Map<String, Any?> }
            .getOrNull().orEmpty()

    /** JS 侧 `__openxMoveFileAsync({from,to})` —— 沙箱内移动/重命名 */
    fun openxMoveFileAsync(optionsJson: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val opt = optOf(optionsJson)
        val from = resolveInSandbox(opt["from"]?.toString().orEmpty())
            ?: return errorResult("源路径不合法（只能访问插件目录内）")
        val to = resolveInSandbox(opt["to"]?.toString().orEmpty())
            ?: return errorResult("目标路径不合法（只能访问插件目录内）")
        if (!from.exists()) return errorResult("源不存在")
        return runCatching {
            if (!PluginFileOps.move(from, to)) errorResult("移动失败")
            else gson.toJson(mapOf("ok" to true, "path" to to.absolutePath))
        }.getOrElse { errorResult(it.message ?: "移动失败") }
    }

    /** JS 侧 `__openxCopyFileAsync({from,to})` —— 沙箱内复制（目录递归） */
    fun openxCopyFileAsync(optionsJson: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val opt = optOf(optionsJson)
        val from = resolveInSandbox(opt["from"]?.toString().orEmpty())
            ?: return errorResult("源路径不合法（只能访问插件目录内）")
        val to = resolveInSandbox(opt["to"]?.toString().orEmpty())
            ?: return errorResult("目标路径不合法（只能访问插件目录内）")
        if (!from.exists()) return errorResult("源不存在")
        return runCatching {
            if (!PluginFileOps.copy(from, to)) errorResult("复制失败")
            else gson.toJson(mapOf("ok" to true, "path" to to.absolutePath))
        }.getOrElse { errorResult(it.message ?: "复制失败") }
    }

    /**
     * JS 侧 `__openxSearchFilesAsync({dir,name,ext,content,limit})` —— 沙箱内搜索。
     *
     * `name`（也可写 `query`）是文件名子串，`ext` 接受数组或逗号串，
     * `content` 是内容关键字（只读 ≤2MB 的文本），`limit` 默认 200、上限 2000。
     */
    fun openxSearchFilesAsync(optionsJson: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val opt = optOf(optionsJson)
        val dirRel = opt["dir"]?.toString().orEmpty()
        val root = if (dirRel.isBlank()) sandboxDir() else resolveInSandbox(dirRel)
        if (root == null || !root.isDirectory) return errorResult("目录不存在")
        val exts = when (val e = opt["ext"]) {
            is List<*> -> e.mapNotNull { it?.toString()?.trim()?.lowercase()?.removePrefix(".") }
                .filter { it.isNotEmpty() }.toSet()
            is String -> e.split(',', ' ', ';').map { it.trim().lowercase().removePrefix(".") }
                .filter { it.isNotEmpty() }.toSet()
            else -> emptySet()
        }
        val limit = (opt["limit"] as? Number)?.toInt() ?: 200
        return runCatching {
            val hits = PluginFileOps.search(
                root = root,
                nameLike = (opt["name"] ?: opt["query"])?.toString(),
                exts = exts,
                contentLike = opt["content"]?.toString(),
                limit = limit
            )
            gson.toJson(
                mapOf(
                    "ok" to true,
                    "count" to hits.size,
                    "hits" to hits.map {
                        mapOf(
                            "path" to it.path,
                            "name" to it.name,
                            "isDirectory" to it.isDirectory,
                            "size" to it.size
                        )
                    }
                )
            )
        }.getOrElse { errorResult(it.message ?: "搜索失败") }
    }

    /** JS 侧 `__openxZipAsync({from,to})` —— 把沙箱内文件/目录打成 zip */
    fun openxZipAsync(optionsJson: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val opt = optOf(optionsJson)
        val from = resolveInSandbox(opt["from"]?.toString().orEmpty())
            ?: return errorResult("源路径不合法（只能访问插件目录内）")
        val to = resolveInSandbox(opt["to"]?.toString().orEmpty())
            ?: return errorResult("输出路径不合法（只能访问插件目录内）")
        if (!from.exists()) return errorResult("源不存在")
        return runCatching {
            val n = PluginFileOps.zip(from, to)
            gson.toJson(
                mapOf("ok" to true, "path" to to.absolutePath, "files" to n, "size" to to.length())
            )
        }.getOrElse { errorResult(it.message ?: "打包失败") }
    }

    /** JS 侧 `__openxUnzipAsync({zip,to})` —— 解压到沙箱目录 */
    fun openxUnzipAsync(optionsJson: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val opt = optOf(optionsJson)
        val z = resolveInSandbox(opt["zip"]?.toString().orEmpty())
            ?: return errorResult("压缩包路径不合法（只能访问插件目录内）")
        val to = resolveInSandbox(opt["to"]?.toString().orEmpty())
            ?: return errorResult("目标路径不合法（只能访问插件目录内）")
        if (!z.isFile) return errorResult("压缩包不存在")
        return runCatching {
            val n = PluginFileOps.unzip(z, to)
            gson.toJson(mapOf("ok" to true, "path" to to.absolutePath, "files" to n))
        }.getOrElse { errorResult(it.message ?: "解压失败") }
    }

    /** JS 侧 `__openxDiffAsync({a,b,context})` —— 两个沙箱内文本文件的 unified diff */
    fun openxDiffAsync(optionsJson: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val opt = optOf(optionsJson)
        val fa = resolveInSandbox(opt["a"]?.toString().orEmpty())
            ?: return errorResult("文件 a 路径不合法（只能访问插件目录内）")
        val fb = resolveInSandbox(opt["b"]?.toString().orEmpty())
            ?: return errorResult("文件 b 路径不合法（只能访问插件目录内）")
        if (!fa.isFile) return errorResult("文件 a 不存在")
        if (!fb.isFile) return errorResult("文件 b 不存在")
        val ctxLines = (opt["context"] as? Number)?.toInt() ?: 3
        return runCatching {
            val text = PluginFileOps.diff(fa, fb, ctxLines)
            gson.toJson(mapOf("ok" to true, "same" to text.isEmpty(), "diff" to text))
        }.getOrElse { errorResult(it.message ?: "比较失败") }
    }

    /**
     * 挂起一个要等用户操作的请求：立即返回 `pending:<id>`，结果回来后再结算。
     *
     * 之前是 runBlocking 同步等，而 QuickJS 单次执行有 60 秒上限——用户挑张图、
     * 选个文件只要超过 60 秒就报“QuickJS 执行超时”，而且选择器还白开了一次。
     * 现在 JS 侧拿到的是 Promise，宿主在后台等，回来后调 [settle] 触发微任务。
     */
    private fun <T> pending(block: suspend () -> T, toJson: (T?) -> String): String {
        val scope = hostScope ?: return errorResult("宿主未提供异步作用域")
        val id = "p${System.nanoTime()}"
        scope.launch {
            val json = runCatching { toJson(block()) }
                .getOrElse { errorResult(it.message ?: "操作失败") }
            settle(id, json)
        }
        return "pending:$id"
    }

    /** 把挂起请求的结果送回 JS（线程安全：可能从 IO 线程回调）。 */
    private fun settle(id: String, resultJson: String) {
        onPendingSettled?.invoke(id, resultJson)
    }

    /** JS 侧 `__openxPickFileAsync({mime:[...]})` —— 挂起等用户选择 */
    fun openxPickFileAsync(optionsJson: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val ha = hostActions ?: return errorResult("宿主未提供文件选择能力")
        val opt = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(optionsJson ?: "{}", Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()
        val mimes = (opt["mime"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        return pending({ ha.pickFile(mimes) }) { picked ->
            if (picked == null) gson.toJson(mapOf("ok" to true, "cancelled" to true))
            else gson.toJson(
                mapOf(
                    "ok" to true,
                    "cancelled" to false,
                    "name" to picked.name,
                    "content" to picked.content,
                    "size" to picked.size
                )
            )
        }
    }

    /** JS 侧 `__openxSaveFileAsync({name,content})` */
    fun openxSaveFileAsync(optionsJson: String?): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val ha = hostActions ?: return errorResult("宿主未提供文件保存能力")
        val opt = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(optionsJson ?: "{}", Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()
        val name = opt["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "plugin_output.txt"
        val content = opt["content"]?.toString().orEmpty()
        return pending({ ha.saveFile(name, content) }) { saved ->
            if (saved == null) gson.toJson(mapOf("ok" to true, "cancelled" to true))
            else gson.toJson(mapOf("ok" to true, "cancelled" to false, "name" to saved))
        }
    }

    /** JS 侧 `__openxPickImageAsync()` —— 返回 data URI，可直接喂给 Image 组件 */
    fun openxPickImageAsync(): String {
        if (PluginPermission.FILE !in permissions) return errorResult("缺少 file 权限")
        val ha = hostActions ?: return errorResult("宿主未提供图片选择能力")
        return pending({ ha.pickImage() }) { dataUri ->
            if (dataUri == null) gson.toJson(mapOf("ok" to true, "cancelled" to true))
            else gson.toJson(mapOf("ok" to true, "cancelled" to false, "data" to dataUri))
        }
    }

    /** 插件沙箱的展示路径（供调试面板/文档用）。 */
    fun sandboxPath(): String = sandboxDir().absolutePath

    // ------------------------------------------------ 五期 / 六期：原生能力桥
    //
    // 无障碍 UI 自动化、设备信息、应用管理、摘要编码。实现在 PluginAutomationBridge，
    // 这里只做转发：QuickJS 的 bindNativeInterface 只能绑一个实例，所以入口必须是本类。
    //
    // 参数个数必须固定：宿主侧按 (方法名 + 参数个数) 反射查找方法，少传一个
    // 就找不到（JS 侧会用默认值补齐，见 PluginUiRuntime 的 ctx.ui 封装）。

    private val automation by lazy { PluginAutomationBridge(context, pluginId, permissions) }

    fun openxUiReady(): String = automation.openxUiReady()

    fun openxUiSnapshot(optionsJson: String?): String = automation.openxUiSnapshot(optionsJson)

    fun openxUiFind(selectorJson: String?, limit: Double): String =
        automation.openxUiFind(selectorJson, limit)

    fun openxUiClick(selectorJson: String?): String = automation.openxUiClick(selectorJson)

    fun openxUiSetText(selectorJson: String?, text: String?): String =
        automation.openxUiSetText(selectorJson, text)

    fun openxUiScroll(selectorJson: String?, direction: String?): String =
        automation.openxUiScroll(selectorJson, direction)

    fun openxUiSwipe(x1: Double, y1: Double, x2: Double, y2: Double): String =
        automation.openxUiSwipe(x1, y1, x2, y2)

    fun openxUiGlobal(action: String?): String = automation.openxUiGlobal(action)

    fun openxUiWaitFor(selectorJson: String?, timeoutMs: Double): String =
        automation.openxUiWaitFor(selectorJson, timeoutMs)

    fun openxUiForeground(): String = automation.openxUiForeground()

    fun openxUiTapAt(x: Double, y: Double): String = automation.openxUiTapAt(x, y)

    fun openxBatteryInfo(): String = automation.openxBatteryInfo()

    fun openxDeviceStats(): String = automation.openxDeviceStats()

    fun openxMemoryList(keyword: String?, scope: String?, limit: Double): String =
        automation.openxMemoryList(keyword, scope, limit)

    fun openxMemoryRead(key: String?, scope: String?): String =
        automation.openxMemoryRead(key, scope)

    fun openxMemorySave(key: String?, value: String?, category: String?, scope: String?): String =
        automation.openxMemorySave(key, value, category, scope)

    fun openxMemoryDelete(key: String?, scope: String?): String =
        automation.openxMemoryDelete(key, scope)

    fun openxChatList(limit: Double): String = automation.openxChatList(limit)

    fun openxChatCurrent(): String = automation.openxChatCurrent()

    fun openxChatMessages(limit: Double): String = automation.openxChatMessages(limit)

    /** JS 侧 `ctx.chat.runTask(prompt, {newConversation, workspace})` */
    fun openxChatRunTask(prompt: String?, newConversation: Boolean, workspacePath: String?): String =
        automation.openxChatRunTask(prompt, newConversation, workspacePath)

    fun openxDeviceInfo(): String = automation.openxDeviceInfo()

    fun openxAppList(max: Double): String = automation.openxAppList(max)

    fun openxAppSearch(keyword: String?, max: Double): String =
        automation.openxAppSearch(keyword, max)

    fun openxAppLaunch(packageName: String?): String = automation.openxAppLaunch(packageName)

    fun openxCryptoHash(algorithm: String?, text: String?): String =
        automation.openxCryptoHash(algorithm, text)

    fun openxCryptoBase64(mode: String?, text: String?): String =
        automation.openxCryptoBase64(mode, text)

    // ------------------------------------------------ 十期：插件悬浮窗
    //
    // 窗口实例缓存在 Bridge 里（一个插件一个），因为 ctx.overlay.show/hide 会被
    // 反复调用，不能每次都重建一个 QuickJS 会话 —— 重建等于把插件状态全丢了。

    // 窗口实例由 PluginOverlayWindow.obtain 按插件 id 统一管理（UI 轨道与后台轨道共享），
    // 这里只缓存插件记录，避免每次动作都回磁盘扫一遍插件列表。
    private var overlayPlugin: com.wy.openx.plugin.InstalledPlugin? = null

    private fun overlayErr(msg: String) = "{\"ok\":false,\"error\":\"$msg\"}"

    private fun resolveSelf(): com.wy.openx.plugin.InstalledPlugin? {
        overlayPlugin?.let { return it }
        val hit = runCatching {
            store.load().firstOrNull { it.manifest.id == pluginId }
        }.getOrNull()
        overlayPlugin = hit
        return hit
    }

    fun openxOverlayCanShow(): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限，请在 plugin.json 的 permissions 里声明")
        }
        val ok = PluginOverlayWindow.canDrawOverlays(context)
        return "{\"ok\":true,\"canShow\":$ok}"
    }

    fun openxOverlayShow(optionsJson: String?): String {
        FileLogger.i(TAG, "openxOverlayShow 被调用 options=$optionsJson")
        if (PluginPermission.OVERLAY !in permissions) {
            FileLogger.e(TAG, "缺 overlay 权限，permissions=$permissions")
            return overlayErr("缺少 overlay 权限，请在 plugin.json 的 permissions 里声明")
        }
        if (!PluginOverlayWindow.canDrawOverlays(context)) {
            FileLogger.e(TAG, "系统未授权悬浮窗")
            return overlayErr("没有悬浮窗权限，请到系统设置里给 OpenX 开启「显示在其他应用上层」")
        }

        val opt = parseOverlayOptions(optionsJson)

        // 必须在**另一个线程**里建悬浮窗。
        //
        // ctx.overlay.show() 是从插件动作派发里调进来的，那一刻本插件面板的
        // QuickJS 引擎正在当前线程上跑。要在悬浮窗里复用同一套渲染就得再建一个
        // PluginUiSession（也就是再建一个 QuickJS 引擎），而同线程重入创建会直接
        // 卡死（实测：日志停在"被调用"，8 秒后仍无返回，面板也跟着失去响应）。
        //
        // 所以这里只投递任务并立即返回。代价是 show 变成"异步生效"：
        // 调用后马上查 isShowing() 可能还是 false，插件应当等一拍或稍后轮询。
        Thread({
            runCatching {
                val self = resolveSelf()
                if (self == null) {
                    FileLogger.e(TAG, "后台 show：找不到插件记录 pluginId=$pluginId")
                    return@runCatching
                }
                // 实例在窗口注册表里共享：后台脚本轨道查 isShowing 看到的是同一个窗口
                val win = PluginOverlayWindow.obtain(
                    context = context,
                    plugin = self,
                    store = store,
                    toolRegistry = toolRegistry,
                    onToast = toastImpl
                )
                val ok = win.show(opt)
                FileLogger.i(TAG, "后台 show 完成 ok=$ok isShowing=${win.isShowing}")
            }.onFailure { FileLogger.e(TAG, "后台 show 异常：${it.message}") }
        }, "plugin-overlay-show").apply { isDaemon = true }.start()

        return "{\"ok\":true,\"accepted\":true}"
    }

    fun openxOverlayHide(): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限")
        }
        runCatching { PluginOverlayWindow.peek(pluginId)?.hide() }
        return "{\"ok\":true}"
    }

    /** 选项解析收口在 [PluginOverlayWindow.options]，UI 与服务两条轨道共用一份。 */
    private fun parseOverlayOptions(optionsJson: String?): OverlayOptions =
        PluginOverlayWindow.options(optionsJson)

    fun openxOverlayIsShowing(): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限")
        }
        val showing = runCatching { PluginOverlayWindow.peek(pluginId)?.isShowing == true }
            .getOrDefault(false)
        return "{\"ok\":true,\"showing\":$showing}"
    }

    /**
     * 把窗口挪到屏幕坐标 (x, y)，px。允许负值（贴边时把窗口移出屏幕一部分）。
     *
     * 同步返回 ok=failed 只说明没窗口/没权限；位移本身是异步生效的（见 moveTo 注释）。
     */
    fun openxOverlayMoveTo(x: Int, y: Int): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限")
        }
        val win = PluginOverlayWindow.peek(pluginId) ?: return overlayErr("浮窗未显示")
        win.moveTo(x, y)
        return "{\"ok\":true}"
    }

    /** 按 dp 修改已显示浮窗的宽高，不重建窗口，也不改变用户拖动过的位置。 */
    fun openxOverlayResize(widthDp: Int, heightDp: Int): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限")
        }
        if (widthDp < 1 || heightDp < 1) return overlayErr("宽高必须大于 0")
        val win = PluginOverlayWindow.peek(pluginId)?.takeIf { it.isShowing }
            ?: return overlayErr("浮窗未显示")
        win.resizeTo(widthDp, heightDp)
        return "{\"ok\":true}"
    }

    /** 当前窗口几何信息（JSON）。插件靠它做边界判断与贴边。 */
    fun openxOverlayGeometry(): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限")
        }
        val win = PluginOverlayWindow.peek(pluginId) ?: return overlayErr("浮窗未显示")
        return runCatching { win.geometryJson() }
            .getOrElse { overlayErr(it.message ?: "读取几何信息失败") }
    }

    /** 切换触摸穿透（开着时窗口完全不吃触摸，底下的 App 正常操作）。 */
    fun openxOverlaySetPassThrough(on: Boolean): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限")
        }
        val win = PluginOverlayWindow.peek(pluginId) ?: return overlayErr("浮窗未显示")
        win.setPassThrough(on)
        return "{\"ok\":true,\"passThrough\":$on}"
    }

    /**
     * 在浮窗头顶说一句话（独立窗口，**不接收触摸**，不挡操作）。
     *
     * 不能用浮窗里的 Text 组件代替：浮窗尺寸紧贴立绘（甚至可以只有几 dp），
     * 摆不下字；把浮窗改大又会多挡一圈触摸。
     */
    fun openxOverlaySay(text: String?, ms: Int): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限")
        }
        val win = PluginOverlayWindow.peek(pluginId) ?: return overlayErr("浮窗未显示")
        win.showBubble(text.orEmpty(), ms.toLong().coerceIn(800, 20_000))
        return "{\"ok\":true}"
    }

    /**
     * AI 会话的聚合状态（需 `session` 权限）。
     *
     * 只给状态不给内容：忙闲、正在跑的工具名、上下文用量、待确认类型。
     */
    fun openxHostSession(): String {
        if (PluginPermission.SESSION !in permissions) {
            return overlayErr("缺少 session 权限，请在 plugin.json 的 permissions 里声明")
        }
        return com.wy.openx.plugin.HostSessionState.snapshotJson()
    }

    // ---------------------------------------------------------------- Operit 兼容转发
    //
    // 兼容层的 UI 脚本调 `Tools.*` 时走同一套 openxCompat* 方法，但 UI session 的
    // NativeInterface 是**本桥**而不是 CompatHostBridge，所以这里逐个转发。
    // 权限用本插件自己的 grantedPermissions（工具轨道那张表在 PluginRuntime 里，
    // UI 拿不到，也不该拿）。

    private val compatBridge: com.wy.openx.plugin.compat.CompatHostBridge by lazy {
        // 290s：插件装依赖（pnpm install / 编译原生模块）耗时可达数分钟，DSH 这类
        // 包真实请求 300s。上限必须**小于** UI 引擎看门狗（UI_EXECUTION_TIMEOUT_MS
        // = 300s），留 10s 余量——命令超时会中断整个引擎，宁可先在边界拒。
        //
        // hostScope + pendingSink：长命令挂后台，JS 侧立即拿到 Promise。
        // 不接这两个的话 runBlocking 会占住 QuickJS 线程，界面在下载期间完全不刷新。
        com.wy.openx.plugin.compat.CompatHostBridge(
            context = context,
            permissionsOf = { permissions },
            terminalMaxTimeoutMs = 290_000L,
            hostScope = hostScope,
            pendingSink = onPendingSettled
        )
    }

    fun openxCompatFs(pluginId: String?, op: String?, argsJson: String?): String =
        compatBridge.openxCompatFs(pluginId ?: this.pluginId, op, argsJson)

    fun openxCompatModule(pluginId: String?, op: String?, argsJson: String?): String =
        compatBridge.openxCompatModule(pluginId ?: this.pluginId, op, argsJson)

    fun openxCompatHttp(pluginId: String?, method: String?, optJson: String?): String =
        compatBridge.openxCompatHttp(pluginId ?: this.pluginId, method, optJson)

    fun openxCompatTerminal(pluginId: String?, op: String?, argsJson: String?): String =
        compatBridge.openxCompatTerminal(pluginId ?: this.pluginId, op, argsJson)

    fun openxCompatSleep(pluginId: String?, ms: String?, unused: String?): String =
        compatBridge.openxCompatSleep(pluginId ?: this.pluginId, ms, unused)

    fun openxCompatToast(pluginId: String?, message: String?, unused: String?): String =
        compatBridge.openxCompatToast(pluginId ?: this.pluginId, message, unused)

    fun openxCompatResource(pluginId: String?, op: String?, argsJson: String?): String =
        compatBridge.openxCompatResource(pluginId ?: this.pluginId, op, argsJson)

    // ---------------------------------------------------------------- 内部

    private fun errorResult(message: String): String =
        gson.toJson(mapOf("ok" to false, "error" to message))

    private fun envFile(): File = PluginEnvStore.file(context, pluginId)

    /** 读取已持久化的 env（供初始化时注入 JS）。 */
    fun loadEnvJson(): String {
        if (PluginPermission.ENV !in permissions) return "{}"
        return runCatching {
            envFile().takeIf { it.isFile }?.readText() ?: "{}"
        }.getOrDefault("{}")
    }
}
