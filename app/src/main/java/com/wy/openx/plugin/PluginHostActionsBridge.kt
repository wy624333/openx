package com.wy.openx.plugin

import android.content.Context
import com.google.gson.Gson
import com.wy.openx.container.FileLogger
import com.wy.openx.plugin.ui.PluginOverlayWindow
import com.wy.openx.tools.AppContextHolder
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 服务型插件的宿主桥：实现 service JS 运行时调用的 `__openx*` 原生方法。
 *
 * 与 [com.wy.openx.plugin.ui.PluginUiHostBridge] 的差异：
 *  - 全部**同步**实现（服务 JS 是单线程请求-响应模型，没有 pending 机制）
 *  - HTTP 超时放宽到 120s（上游 LLM 网关常见慢响应）
 *  - 无 toast/rerender 的 UI 依赖（toast 转日志）
 *  - env 落盘与 UI 插件**同目录同文件**（pluginEnv/<pluginId>.json），
 *    这样 UI 面板改的配置，服务重启后能读到同一份
 */
class PluginHostActionsBridge(
    private val context: Context,
    private val pluginId: String,
    private val permissions: Set<PluginPermission>
) {

    companion object {
        private const val TAG = "PluginHostActionsBridge"
        private const val MAX_HTTP_BYTES = 8L * 1024 * 1024
        private const val HTTP_TIMEOUT_SEC = 120L

        /**
         * 后台脚本 exec 的默认 / 最大时长。上限对齐引擎看门狗的单次窗口，不能再大；
         * 需要更久的活请把命令自身放后台（`setsid … &`）后立即返回。
         */
        private const val DEFAULT_EXEC_TIMEOUT_MS = 20_000L
        private const val MAX_EXEC_TIMEOUT_MS = 45_000L
    }

    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun envFile(): File = PluginEnvStore.file(context, pluginId)

    /** JS 侧 `__openxShowToast(msg)` —— 服务无 UI，toast 转日志。 */
    fun openxShowToast(message: String?) {
        FileLogger.i(TAG, "[$pluginId] $message")
    }

    /** JS 侧 `__openxPersistEnv(json)`。 */
    fun openxPersistEnv(json: String?) {
        if (PluginPermission.ENV !in permissions) return
        runCatching {
            envFile().parentFile?.mkdirs()
            envFile().writeText(json.orEmpty())
        }.onFailure { FileLogger.e(TAG, "持久化 env 失败: ${it.message}") }
    }

    /** JS 侧 `NativeInterface.openxLoadEnv()` —— 启动时读回 env 快照。 */
    fun openxLoadEnv(): String {
        if (PluginPermission.ENV !in permissions) return "{}"
        return runCatching { envFile().readText().ifBlank { "{}" } }.getOrDefault("{}")
    }

    /** JS 侧 `NativeInterface.openxPermissions()` —— 权限清单（JSON 数组字符串）。 */
    fun openxPermissions(): String =
        gson.toJson(permissions.map { it.wireName })

    /**
     * JS 侧 `__openxHttpSync(optJson)` —— 同步 HTTP。
     *
     * opt = {url, method, headers:{}, body}
     * 返回 {"ok":true,"status":200,"headers":{},"body":"..."} 或 {"ok":false,"error":"..."}
     */
    fun openxHttpSync(optJson: String?): String {
        if (PluginPermission.HTTP !in permissions) {
            return errorResult("缺少 http 权限")
        }
        val opt = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(optJson ?: "{}", Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()

        val url = opt["url"]?.toString().orEmpty()
        if (url.isBlank()) return errorResult("url 为空")
        val method = (opt["method"]?.toString() ?: "GET").uppercase()

        return try {
            val bodyStr = opt["body"]?.toString()
            val mediaType = (opt["contentType"]?.toString() ?: "application/json").toMediaType()
            val requestBody = if (method == "GET" || method == "HEAD" || bodyStr == null) null
                else bodyStr.toRequestBody(mediaType)

            val builder = Request.Builder().url(url).method(method, requestBody)
            (opt["headers"] as? Map<*, *>)?.forEach { (k, v) ->
                if (k != null && v != null) builder.header(k.toString(), v.toString())
            }

            client.newCall(builder.build()).execute().use { resp ->
                val bodyBytes = resp.body?.bytes()
                if (bodyBytes != null && bodyBytes.size > MAX_HTTP_BYTES) {
                    return errorResult("响应超过 ${MAX_HTTP_BYTES / 1024 / 1024}MB 上限")
                }
                val respHeaders = resp.headers.toMap()
                gson.toJson(
                    mapOf(
                        "ok" to true,
                        "status" to resp.code,
                        "headers" to respHeaders,
                        "body" to (bodyBytes?.toString(Charsets.UTF_8) ?: "")
                    )
                )
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "请求失败")
        }
    }

    private fun errorResult(msg: String): String = gson.toJson(mapOf("ok" to false, "error" to msg))

    // ---------------------------------------------------------------- 插件间总线（需 bus 权限）

    fun openxBusPublish(topic: String?, payload: String?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        val t = topic?.trim().orEmpty()
        if (t.isEmpty()) return errorResult("topic 不能为空")
        val seq = PluginBus.publish(t, pluginId, payload ?: "")
        return gson.toJson(mapOf("ok" to true, "seq" to seq))
    }

    fun openxBusSubscribe(topic: String?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        val t = topic?.trim().orEmpty()
        if (t.isEmpty()) return errorResult("topic 不能为空")
        PluginBus.subscribe(t, pluginId)
        return gson.toJson(mapOf("ok" to true, "latestSeq" to PluginBus.latestSeq(t)))
    }

    fun openxBusUnsubscribe(topic: String?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        PluginBus.unsubscribe(topic?.trim().orEmpty(), pluginId)
        return gson.toJson(mapOf("ok" to true))
    }

    fun openxBusPoll(topic: String?, sinceSeq: Double?, limit: Double?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        val t = topic?.trim().orEmpty()
        if (t.isEmpty()) return errorResult("topic 不能为空")
        val msgs = PluginBus.poll(t, pluginId, sinceSeq?.toLong() ?: 0L, limit?.toInt() ?: 50)
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

    fun openxBusLatestSeq(topic: String?): String {
        if (PluginPermission.BUS !in permissions) return errorResult("缺少 bus 权限")
        return gson.toJson(mapOf("ok" to true, "seq" to PluginBus.latestSeq(topic?.trim().orEmpty())))
    }

    /** 系统通知（需 notify 权限）。定时任务到点提醒用户靠它。 */
    fun openxNotify(optionsJson: String?): String {
        if (PluginPermission.NOTIFY !in permissions) return errorResult("缺少 notify 权限")
        val opt = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(optionsJson ?: "{}", Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()
        val title = opt["title"]?.toString()?.takeIf { it.isNotBlank() } ?: "OpenX 插件"
        val text = opt["text"]?.toString().orEmpty()
        return runCatching {
            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
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
            nm.notify(pluginId.hashCode(), notif)
            gson.toJson(mapOf("ok" to true))
        }.getOrElse { errorResult(it.message ?: "发送通知失败") }
    }

    /**
     * 发起一轮 AI 任务（需 chat_task 权限）。定时任务到点让 AI 干活靠它。
     *
     * 注意这里与 UI 侧的同名方法**不是同一份代码**：服务运行时的 NativeInterface
     * 绑的是本类，漏了就会报 `NativeInterface method not found: openxChatRunTask/3`。
     */
    fun openxChatRunTask(prompt: String?, newConversation: Boolean, workspacePath: String?): String {
        if (PluginPermission.CHAT_TASK !in permissions) {
            return errorResult("缺少 chat_task 权限，请在 plugin.json 的 permissions 里声明 \"chat_task\"")
        }
        val text = prompt?.trim().orEmpty()
        if (text.isEmpty()) return errorResult("prompt 不能为空")
        val runner = com.wy.openx.tools.AppContextHolder.pluginChatRunner
            ?: return errorResult("宿主未就绪（pluginChatRunner 未注册），请重启应用")
        return runCatching {
            val (cid, started) = kotlinx.coroutines.runBlocking {
                runner(text, newConversation, workspacePath)
            }
            if (started) {
                gson.toJson(mapOf("ok" to true, "conversationId" to cid, "started" to true))
            } else {
                gson.toJson(
                    mapOf(
                        "ok" to false,
                        "conversationId" to cid,
                        "error" to "未能启动一轮（可能未选模型、并发已满或当前有请求在跑）"
                    )
                )
            }
        }.getOrElse { errorResult("发起任务失败：${it.message}") }
    }

    /**
     * JS 侧 `__openxExec(command, timeoutMs)` —— 在容器里执行一条命令，同步返回结果。
     *
     * 为什么需要它：有些插件的后台**不是**跑在宿主进程里的 JS 服务，而是容器里的常驻
     * 进程（JVM、Node…）。之前这类插件只能由 UI 面板的终端去拉起来，用户必须先打开面板；
     * 有了 exec，后台脚本就能在 onStart/onStop 里自己把它起停，
     * 「插件配置」页的启动按钮对这类插件也才有意义。
     *
     * 权限与 Operit 兼容层的终端保持一致，要 `filesystem`。
     * 与 UI 侧终端不同，这里**没有 pending 机制**：服务 JS 是单线程同步模型，
     * 一次调用会阻塞到命令结束或超时。所以别把耗时命令直接放进来，
     * 常驻进程请在命令里自己脱离：`setsid xxx </dev/null >/dev/null 2>&1 &`。
     *
     * 返回 `{ok, exitCode, output}`；`exitCode` 为 null 表示超时被杀。
     */
    fun openxExec(command: String?, timeoutMs: Double?): String {
        if (PluginPermission.FILESYSTEM !in permissions) return errorResult("缺少 filesystem 权限")
        val cmd = command.orEmpty()
        if (cmd.isBlank()) return errorResult("命令为空")
        val engine = AppContextHolder.containerEngine ?: return errorResult("容器未装配，无法执行命令")
        val ms = (timeoutMs?.toLong() ?: 0L).takeIf { it > 0 }?.coerceAtMost(MAX_EXEC_TIMEOUT_MS)
            ?: DEFAULT_EXEC_TIMEOUT_MS
        return runCatching {
            val r = runBlocking { engine.runCommandSyncWithExit(cmd, null, ms) }
            FileLogger.i(TAG, "[$pluginId] exec 完成（exit=${r.exitCode}）：${cmd.take(120)}")
            gson.toJson(mapOf("ok" to (r.exitCode == 0), "exitCode" to r.exitCode, "output" to r.output))
        }.getOrElse {
            FileLogger.w(TAG, "[$pluginId] exec 失败：${it.message}")
            errorResult(it.message ?: "命令执行失败")
        }
    }

    /** 电池状态（需 system 权限）。定时任务的「仅充电时」「最低电量」条件靠它。 */
    fun openxBattery(): String {
        if (PluginPermission.SYSTEM !in permissions) return errorResult("缺少 system 权限")
        return runCatching {
            val intent = context.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            ) ?: return errorResult("读取电池状态失败")
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            gson.toJson(mapOf("ok" to true, "level" to pct, "charging" to charging))
        }.getOrElse { errorResult(it.message ?: "读取电池失败") }
    }

    // ---------------------------------------------------------------- 悬浮窗
    //
    // 后台脚本与定时任务也要能拉浮窗。宿主进程被杀会把浮窗一起收走，所以
    // 「一直挂在桌面上」的需求只能靠后台脚本或定时任务定期把它拉回来。
    //
    // 与 UI 轨道（PluginUiHostBridge）共用同一个窗口实例（PluginOverlayWindow.obtain 按
    // 插件 id 共享）——各建各的会导致「UI 已开浮窗、后台任务以为没开，于是又拉一个」。

    private fun overlayErr(msg: String) = "{\"ok\":false,\"error\":\"$msg\"}"

    fun openxOverlayCanShow(): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限，请在 plugin.json 的 permissions 里声明")
        }
        val ok = PluginOverlayWindow.canDrawOverlays(context)
        return "{\"ok\":true,\"canShow\":$ok}"
    }

    fun openxOverlayShow(optionsJson: String?): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限，请在 plugin.json 的 permissions 里声明")
        }
        if (!PluginOverlayWindow.canDrawOverlays(context)) {
            return overlayErr("没有悬浮窗权限，请到系统设置里给 OpenX 开启「显示在其他应用上层」")
        }
        val plugin = runCatching {
            PluginStore(context).load().firstOrNull { it.manifest.id == pluginId }
        }.getOrNull() ?: return overlayErr("未找到插件记录 $pluginId")

        // 必须在另一个线程里建窗：show 要等主线程把 ComposeView 挂好（最多 5 秒），
        // 而服务轨道是同步请求-响应，在这里等会把定时任务/服务请求一起拖死。
        // 代价是 show 变成「异步生效」：调用后立即查 isShowing 可能还是 false。
        Thread({
            runCatching {
                // 实例按插件 id 共享（obtain）：面板那条轨道已经开过浮窗时，这里拿到的是同一个
                val win = PluginOverlayWindow.obtain(
                    context = context,
                    plugin = plugin,
                    store = PluginStore(context),
                    toolRegistry = null,
                    onToast = { msg -> FileLogger.i(TAG, "[$pluginId] $msg") }
                )
                win.show(PluginOverlayWindow.options(optionsJson))
            }.onFailure { FileLogger.e(TAG, "openxOverlayShow 失败：${it.message}") }
        }, "plugin-service-overlay-show").apply { isDaemon = true }.start()

        return "{\"ok\":true,\"accepted\":true}"
    }

    fun openxOverlayHide(): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限，请在 plugin.json 的 permissions 里声明")
        }
        runCatching { PluginOverlayWindow.peek(pluginId)?.hide() }
        return "{\"ok\":true}"
    }

    fun openxOverlayIsShowing(): String {
        if (PluginPermission.OVERLAY !in permissions) {
            return overlayErr("缺少 overlay 权限，请在 plugin.json 的 permissions 里声明")
        }
        val showing = runCatching { PluginOverlayWindow.peek(pluginId)?.isShowing == true }
            .getOrDefault(false)
        return "{\"ok\":true,\"showing\":$showing}"
    }
}
