package com.wy.openx.plugin.compat

import android.content.Context
import com.google.gson.Gson
import com.wy.openx.tools.AppContextHolder
import com.wy.openx.container.FileLogger
import com.wy.openx.plugin.PluginPermission
import com.wy.openx.plugin.ui.PluginFileOps
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Operit 兼容插件的宿主能力桥。
 *
 * 与 [com.wy.openx.plugin.PluginHostActionsBridge] 的差异：
 *  - 面向 `Tools.*`（真文件系统语义），不是 `ctx.*`（插件沙箱语义）
 *  - 全部同步实现：兼容层要求宿主能力同步返回，异步工具才有机会在原调用栈里结算
 *  - 每次调用都带 pluginId：桥是**按引擎全局绑定**的，不这样的话没法按各插件
 *    自己声明的权限做校验
 *
 * ## 路径策略（真 FS 的边界）
 * 容器路径（root 下、workspace 下）交给 `LinuxContainerEngine` 翻译成宿主路径；
 * 其余路径只放行 `/sdcard`、`/storage`、`/tmp`。**应用私有目录不在放行范围**——
 * 那是 OpenX 自己的数据库、记忆和插件配置，不能给第三方插件碰。
 */
class CompatHostBridge(
    private val context: Context,
    /** pluginId → 该插件已授权的权限集。 */
    private val permissionsOf: (String) -> Set<PluginPermission>,
    /**
     * 单条命令的时长上限（毫秒）。
     *
     * **必须小于调用侧引擎的看门狗**，否则命令超时会中断整个引擎并触发 VM 重建。
     * 工具轨道的引擎看门狗是 60s，用默认 45s；UI 轨道的引擎给的时间更长
     * （用户本来就在等进度条），那里传更大的值。
     */
    private val terminalMaxTimeoutMs: Long = COMPAT_TERMINAL_TIMEOUT_MS,
    /**
     * 异步作用域。给了才能把长命令挂后台跑（JS 立即拿到 Promise，界面不卡）。
     * 工具轨道没有它，长命令就还是同步跑完。
     */
    private val hostScope: kotlinx.coroutines.CoroutineScope? = null,
    /**
     * 把后台结果送回 JS 的回调（形如 `__openxResolvePending(id, json)`）。
     *
     * 长命令挂后台后，QuickJS 线程得以释放，`Screen()` 能重新执行，界面才刷得出
     * 「安装中」这类状态。不提供时退回同步执行（行为同旧版）。
     */
    private val pendingSink: ((String, String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "CompatHostBridge"
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_HTTP_BYTES = 8L * 1024 * 1024

        /**
         * 兼容工具里单条命令的上限。必须明显小于 `QuickJsEngine.EXECUTION_TIMEOUT_MS`（60s），
         * 否则命中看门狗会中断整个引擎并触发 VM 重建。
         */
        private const val COMPAT_TERMINAL_TIMEOUT_MS = 45_000L

        /**
         * 长命令续跑的总预算硬上限。
         *
         * 装依赖类命令（拉 build-essential + 编 node-pty + pnpm install）在手机上
         * 可能跑十几分钟，但也不能无限等——用户点了一个按钮就卡住不能接受。
         */
        private const val MAX_TOTAL_MS = 20 * 60 * 1000L

        /** 多段输出累积的上限，超过后保留头尾。 */
        private const val MAX_ACCUMULATED_CHARS = 200_000

        /**
         * 容器内的插件配置根（**容器语义路径**，非宿主路径）。
         *
         * 得经 [resolvePath] 翻译成当前 profile 的 rootfs 内目录；放 rootfs 而非应用内部
         * openx 目录，因为后者已被 [isProtectedPath] 禁止插件访问。
         */
        private const val PKG_CONFIG_ROOT = "/root/.operit_pkg_config"
    }

    private val gson = Gson()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- 插件自身文件
    //
    // 模块加载专用：只读，且限定在这个插件自己的安装目录内。

    fun openxCompatModule(pluginId: String?, op: String?, argsJson: String?): String {
        val id = pluginId.orEmpty()
        val root = pluginRootOf(id) ?: return error("未知插件：$id")
        val args = parseArgs(argsJson)
        val path = args["path"]?.toString().orEmpty()
        val file = File(path)
        val canonical = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        if (!canonical.path.startsWith("$rootPath/")) {
            return error("模块路径越界：$path")
        }
        return when (op) {
            "exists" -> gson.toJson(mapOf("ok" to true, "exists" to file.isFile))
            "read" -> {
                if (!file.isFile) return error("文件不存在：$path")
                if (file.length() > MAX_FILE_BYTES) return error("文件超过 8MB 上限：$path")
                gson.toJson(mapOf("ok" to true, "content" to file.readText()))
            }
            else -> error("未知操作：$op")
        }
    }

    // ---------------------------------------------------------------- 真文件系统

    fun openxCompatFs(pluginId: String?, op: String?, argsJson: String?): String {
        val id = pluginId.orEmpty()
        if (PluginPermission.FILESYSTEM !in permissionsOf(id)) {
            return error("缺少 filesystem 权限")
        }
        val args = parseArgs(argsJson)
        val rawPath = args["path"]?.toString().orEmpty()
        val resolved = resolvePath(rawPath)

        // exists 必须对「路径不可解析」返回 false 而不是抛错。
        // 抛错会让插件的 `if (!exists.exists)` 分支走不到，上层 catch 后
        // 又常常被当成「检查失败」而非「未安装」——DSH 就是这样误判成已装好的。
        if (resolved == null) {
            if (op == "exists") return gson.toJson(mapOf("ok" to true, "exists" to false))
            return error("路径不在放行范围：$rawPath")
        }
        val file = resolved

        return try {
            when (op) {
                "exists" -> gson.toJson(mapOf("ok" to true, "exists" to file.exists()))
                "read" -> {
                    if (!file.isFile) return error("文件不存在：$rawPath")
                    if (file.length() > MAX_FILE_BYTES) return error("文件超过 8MB 上限：$rawPath")
                    gson.toJson(mapOf("ok" to true, "content" to file.readText()))
                }
                "write" -> {
                    val content = args["content"]?.toString().orEmpty()
                    val append = args["append"] == true
                    file.parentFile?.mkdirs()
                    if (append) file.appendText(content) else file.writeText(content)
                    gson.toJson(mapOf("ok" to true, "bytes" to file.length()))
                }
                "mkdir" -> {
                    val recursive = args["recursive"] != false
                    val created = if (recursive) file.mkdirs() else file.mkdir()
                    gson.toJson(mapOf("ok" to true, "created" to created, "exists" to file.isDirectory))
                }
                "list" -> {
                    if (!file.isDirectory) return error("不是目录：$rawPath")
                    val entries = file.listFiles()?.map { child ->
                        mapOf(
                            "name" to child.name,
                            "path" to child.absolutePath,
                            "isDirectory" to child.isDirectory,
                            "size" to child.length()
                        )
                    }.orEmpty()
                    gson.toJson(mapOf("ok" to true, "entries" to entries))
                }
                "remove" -> {
                    val recursive = args["recursive"] == true
                    val removed = if (recursive) file.deleteRecursively() else file.delete()
                    gson.toJson(mapOf("ok" to true, "removed" to removed))
                }
                "stat" -> gson.toJson(
                    mapOf(
                        "ok" to true,
                        "exists" to file.exists(),
                        "isDirectory" to file.isDirectory,
                        "size" to file.length(),
                        "lastModified" to file.lastModified()
                    )
                )
                // operit 的 Files.move/copy/unzip/info/download 官方返回布尔或对象；
                // 这里统一返回 {ok, ...} 由 JS 侧 __openxCompatUnwrap 折算。
                "move" -> {
                    val destRaw = args["destination"]?.toString().orEmpty()
                    val dest = resolvePath(destRaw) ?: return error("路径不在放行范围：$destRaw")
                    if (!file.exists()) return error("源不存在：$rawPath")
                    dest.parentFile?.mkdirs()
                    val ok = if (file.isDirectory) file.copyRecursively(dest, overwrite = true) && file.deleteRecursively()
                    else file.renameTo(dest) || (file.copyTo(dest, overwrite = true).isFile && file.delete())
                    gson.toJson(mapOf("ok" to ok, "moved" to ok))
                }
                "copy" -> {
                    val destRaw = args["destination"]?.toString().orEmpty()
                    val dest = resolvePath(destRaw) ?: return error("路径不在放行范围：$destRaw")
                    if (!file.exists()) return error("源不存在：$rawPath")
                    dest.parentFile?.mkdirs()
                    val ok = if (file.isDirectory) file.copyRecursively(dest, overwrite = true)
                    else file.copyTo(dest, overwrite = true).isFile
                    gson.toJson(mapOf("ok" to ok, "copied" to ok))
                }
                "info" -> gson.toJson(
                    mapOf(
                        "ok" to true,
                        "exists" to file.exists(),
                        "isDirectory" to file.isDirectory,
                        "isFile" to file.isFile,
                        "size" to file.length(),
                        "lastModified" to file.lastModified(),
                        "name" to file.name,
                        "path" to file.absolutePath
                    )
                )
                "unzip" -> {
                    if (!file.isFile) return error("不是文件：$rawPath")
                    val destRaw = args["destination"]?.toString().orEmpty()
                    val dest = resolvePath(destRaw) ?: return error("路径不在放行范围：$destRaw")
                    val count = PluginFileOps.unzip(file, dest)
                    gson.toJson(mapOf("ok" to true, "files" to count))
                }
                "zip" -> {
                    if (!file.exists()) return error("源不存在：$rawPath")
                    val destRaw = args["destination"]?.toString().orEmpty()
                    val dest = resolvePath(destRaw) ?: return error("路径不在放行范围：$destRaw")
                    val count = PluginFileOps.zip(file, dest)
                    gson.toJson(
                        mapOf("ok" to true, "files" to count, "path" to dest.absolutePath, "size" to dest.length())
                    )
                }
                "search" -> {
                    if (!file.isDirectory) return error("不是目录：$rawPath")
                    val exts = when (val e = args["ext"] ?: args["extensions"]) {
                        is List<*> -> e.mapNotNull { it?.toString()?.trim()?.lowercase()?.removePrefix(".") }
                            .filter { it.isNotEmpty() }.toSet()
                        is String -> e.split(',', ' ', ';').map { it.trim().lowercase().removePrefix(".") }
                            .filter { it.isNotEmpty() }.toSet()
                        else -> emptySet()
                    }
                    val hits = PluginFileOps.search(
                        root = file,
                        nameLike = (args["name"] ?: args["query"] ?: args["pattern"])?.toString(),
                        exts = exts,
                        contentLike = args["content"]?.toString(),
                        limit = (args["limit"] as? Number)?.toInt() ?: 200
                    )
                    gson.toJson(
                        mapOf(
                            "ok" to true,
                            "count" to hits.size,
                            "entries" to hits.map {
                                mapOf(
                                    "name" to it.name,
                                    "path" to it.path,
                                    "isDirectory" to it.isDirectory,
                                    "size" to it.size
                                )
                            }
                        )
                    )
                }
                "diff" -> {
                    if (!file.isFile) return error("不是文件：$rawPath")
                    val otherRaw = (args["other"] ?: args["b"] ?: args["target"])?.toString().orEmpty()
                    val other = resolvePath(otherRaw) ?: return error("路径不在放行范围：$otherRaw")
                    if (!other.isFile) return error("文件不存在：$otherRaw")
                    val text = PluginFileOps.diff(file, other, (args["context"] as? Number)?.toInt() ?: 3)
                    gson.toJson(mapOf("ok" to true, "same" to text.isEmpty(), "diff" to text))
                }
                "download" -> {
                    val url = args["url"]?.toString().orEmpty()
                    if (url.isBlank()) return error("url 为空")
                    if (PluginPermission.HTTP !in permissionsOf(id)) return error("缺少 http 权限")
                    file.parentFile?.mkdirs()
                    val req = okhttp3.Request.Builder().url(url).build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return error("下载失败 HTTP ${resp.code}")
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        if (bytes.size > MAX_HTTP_BYTES) return error("下载超过 8MB 上限")
                        file.writeBytes(bytes)
                        gson.toJson(mapOf("ok" to true, "path" to file.absolutePath, "size" to bytes.size))
                    }
                }
                else -> error("未知操作：$op")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "[$id] fs/$op 失败: ${e.message}")
            error(e.message ?: "文件操作失败")
        }
    }

    // ---------------------------------------------------------------- HTTP

    fun openxCompatHttp(pluginId: String?, method: String?, optJson: String?): String {
        val id = pluginId.orEmpty()
        if (PluginPermission.HTTP !in permissionsOf(id)) return error("缺少 http 权限")
        val args = parseArgs(optJson)
        val url = args["url"]?.toString().orEmpty()
        if (url.isBlank()) return error("url 为空")
        val verb = (method ?: "GET").uppercase()

        return try {
            val bodyStr = args["body"]?.let { if (it is String) it else gson.toJson(it) }
            val requestBody = if (verb == "GET" || verb == "HEAD" || bodyStr == null) {
                null
            } else {
                bodyStr.toRequestBody("application/json".toMediaType())
            }
            val builder = Request.Builder().url(url).method(verb, requestBody)
            @Suppress("UNCHECKED_CAST")
            (args["headers"] as? Map<Any?, Any?>)?.forEach { (k, v) ->
                if (k != null && v != null) builder.header(k.toString(), v.toString())
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val bytes = resp.body?.bytes()
                if (bytes != null && bytes.size > MAX_HTTP_BYTES) {
                    return error("响应超过 8MB 上限")
                }
                gson.toJson(
                    mapOf(
                        "ok" to true,
                        "url" to url,
                        "statusCode" to resp.code,
                        "statusMessage" to resp.message,
                        "headers" to resp.headers.names().associateWith { name -> resp.header(name).orEmpty() },
                        "contentType" to (resp.header("Content-Type") ?: ""),
                        "content" to (bytes?.toString(Charsets.UTF_8) ?: ""),
                        "size" to (bytes?.size ?: 0)
                    )
                )
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "[$id] http 失败: ${e.message}")
            error(e.message ?: "请求失败")
        }
    }

    // ---------------------------------------------------------------- 轻量系统能力

    /** JS 侧 `Tools.System.sleep(ms)` —— 同步阻塞，参数就是毫秒数本身。 */
    fun openxCompatSleep(pluginId: String?, msArg: String?, unused: String?): String {
        val ms = msArg?.toLongOrNull()?.coerceIn(0L, 10_000L) ?: 0L
        if (ms > 0) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return error("等待被中断")
            }
        }
        return gson.toJson(mapOf("ok" to true))
    }

    /** JS 侧 `Tools.System.toast(msg)` —— 兼容插件无 UI 上下文，转日志。 */
    fun openxCompatToast(pluginId: String?, message: String?, unused: String?): String {
        FileLogger.i(TAG, "[${pluginId.orEmpty()}] ${message.orEmpty()}")
        return gson.toJson(mapOf("ok" to true))
    }

    // ---------------------------------------------------------------- 终端

    fun openxCompatTerminal(pluginId: String?, op: String?, argsJson: String?): String {
        val id = pluginId.orEmpty()
        if (PluginPermission.FILESYSTEM !in permissionsOf(id)) return error("缺少 filesystem 权限")
        if (op != "exec") return gson.toJson(mapOf("ok" to true, "output" to ""))

        val args = parseArgs(argsJson)
        val command = args["command"]?.toString().orEmpty()
        if (command.isBlank()) return error("命令为空")
        val engine = AppContextHolder.containerEngine ?: return error("容器未装配，无法执行命令")
        val askedMs = (args["timeoutMs"] as? Number)?.toLong() ?: terminalMaxTimeoutMs

        // 单次窗口受引擎看门狗约束（见 terminalMaxTimeoutMs 的说明）。
        val windowMs = askedMs.coerceAtMost(terminalMaxTimeoutMs).coerceAtLeast(1_000L)

        // 请求不超过单次上限 → 插件没在要长任务，按原样跑一次即可，不引入额外重试。
        if (askedMs <= terminalMaxTimeoutMs) {
            return execOnce(id, engine, command, windowMs, 1)
        }

        // ---- 长命令 ----
        //
        // 优先挂后台：立即返回 `pending:<id>`，JS 侧拿到 Promise 后 QuickJS 线程就释放了，
        // Screen() 能重新执行 —— 这是「界面在下载时没反应」的解法。
        // 没有异步作用域时退回同步分段（行为与旧版一致，只是界面会卡）。
        val scope = hostScope
        val sink = pendingSink
        if (scope != null && sink != null) {
            val pid = "t${System.nanoTime()}"
            scope.launch {
                val json = runCatching {
                    runLongCommand(id, engine, command, askedMs, windowMs)
                }.getOrElse {
                    FileLogger.e(TAG, "[$id] 长命令异常: ${it.message}")
                    error("命令执行失败：${it.message}")
                }
                runCatching { sink(pid, json) }
            }
            FileLogger.i(TAG, "[$id] 长命令已挂后台（$pid），请求 ${askedMs / 1000}s")
            return "pending:$pid"
        }

        return runLongCommand(id, engine, command, askedMs, windowMs)
    }

    /**
     * 长命令：分段续跑（可同步也可从后台协程调用）。
     *
     * 不是「断点续传」：超时会 kill 进程，命令从头开始。但 pnpm / apt 都有本地缓存，
     * 已下载的包不会重下，所以每段重跑都能推进进度。
     *
     * 关键规则：**只有超时才续跑**。退出码非 null 说明命令真的跑完了（成功或失败），
     * 再重试只会白白再等一轮——一个语法错误不该耗掉二十分钟。
     */
    private fun runLongCommand(
        id: String,
        engine: com.wy.openx.container.CommandEngine,
        command: String,
        askedMs: Long,
        windowMs: Long
    ): String {
        // ---- 以下为分段续跑主体 ----
        //
        // 这不是「断点续传」：超时会 kill 进程，命令从头开始。但 pnpm / apt 都有本地
        // 缓存，已下载的包不会重下，所以每段重跑都能**推进进度**——这是它能救活
        // 装依赖类命令的原因（DSH 这类包首次安装要拉 build-essential 再编 node-pty）。
        //
        // 关键规则：**只有超时才续跑**。退出码非 null 说明命令真的跑完了（成功或失败），
        // 再重试只会白白再等一轮——一个语法错误不该耗掉二十分钟。
        val totalBudgetMs = (askedMs * 2).coerceAtMost(MAX_TOTAL_MS)
        var elapsed = 0L
        var attempt = 0
        val acc = StringBuilder()

        while (elapsed < totalBudgetMs) {
            attempt++
            val window = (totalBudgetMs - elapsed).coerceAtMost(windowMs).coerceAtLeast(1_000L)
            FileLogger.i(
                TAG,
                "[$id] 第 $attempt 段（窗口 ${window / 1000}s / 总预算 ${totalBudgetMs / 1000}s）：${command.take(100)}"
            )
            val startedAt = System.currentTimeMillis()
            val r = runBlocking { engine.runCommandSyncWithExit(command, null, window) }
            val cost = System.currentTimeMillis() - startedAt
            appendBounded(acc, r.output)

            when {
                r.exitCode == 0 -> {
                    FileLogger.i(TAG, "[$id] 第 $attempt 段成功，耗时 ${cost}ms，共 ${attempt} 段")
                    return gson.toJson(
                        mapOf("ok" to true, "output" to acc.toString(), "exitCode" to 0, "attempts" to attempt)
                    )
                }
                // 非 null = 进程真的结束了（非零退出），不重试
                r.exitCode != null -> {
                    FileLogger.w(TAG, "[$id] 第 $attempt 段失败，退出码 ${r.exitCode}，不重试")
                    return gson.toJson(
                        mapOf(
                            "ok" to false,
                            "output" to acc.toString(),
                            "exitCode" to r.exitCode,
                            "attempts" to attempt,
                            "error" to "命令以退出码 ${r.exitCode} 结束"
                        )
                    )
                }
                else -> {
                    // 退出码 null = 超时被 kill，继续下一段
                    elapsed += cost.coerceAtLeast(window)
                    FileLogger.w(
                        TAG,
                        "[$id] 第 $attempt 段超时（${cost}ms），已用 ${elapsed / 1000}s / ${totalBudgetMs / 1000}s"
                    )
                }
            }
        }

        FileLogger.w(TAG, "[$id] 续跑预算用完（${totalBudgetMs / 1000}s，共 $attempt 段），命令未完成")
        return gson.toJson(
            mapOf(
                "ok" to false,
                "output" to acc.toString(),
                "exitCode" to -1,
                "attempts" to attempt,
                "timedOut" to true,
                "error" to "命令在 ${totalBudgetMs / 1000} 秒内未完成（分 $attempt 段续跑）。" +
                    "若是在装依赖，已下载的部分已缓存，再点一次可继续推进。"
            )
        )
    }

    /** 单段执行（短命令走这里，行为与旧版一致，只是不再谎报 exitCode=0）。 */
    private fun execOnce(
        id: String,
        engine: com.wy.openx.container.CommandEngine,
        command: String,
        windowMs: Long,
        attempt: Int
    ): String {
        FileLogger.i(TAG, "[$id] 执行命令（上限 ${windowMs / 1000}s）：${command.take(120)}")
        val startedAt = System.currentTimeMillis()
        return try {
            val r = runBlocking { engine.runCommandSyncWithExit(command, null, windowMs) }
            FileLogger.i(
                TAG,
                "[$id] 命令完成，耗时 ${System.currentTimeMillis() - startedAt}ms，退出码 ${r.exitCode}，" +
                    "输出 ${r.output.length} 字符：${r.output.take(200).replace("\n", " ⏎ ")}"
            )
            if (r.exitCode == 0 || r.exitCode == null) {
                // null 表示超时：保留 timedOut 标记，让插件能区分「跑完了」和「被砍了」。
                // ok 必须为 true —— 超时说明命令确实跑过并已终止，只是没在窗口内退出；
                // 若给 false，JS 侧 __openxCompatUnwrap 会抛「宿主调用失败」，
                // 插件既拿不到已产出的输出，也看不到 timedOut（后台常驻服务启动必然超时）。
                gson.toJson(
                    mapOf(
                        "ok" to true,
                        "output" to r.output,
                        "exitCode" to (r.exitCode ?: -1),
                        "timedOut" to (r.exitCode == null),
                        "attempts" to attempt
                    )
                )
            } else {
                gson.toJson(
                    mapOf(
                        "ok" to false,
                        "output" to r.output,
                        "exitCode" to r.exitCode,
                        "attempts" to attempt
                    )
                )
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "[$id] terminal 失败: ${e.message}")
            error(e.message ?: "命令执行失败")
        }
    }

    /** 累积多段输出，超过上限时保留头尾（中间进度条之类没价值）。 */
    private fun appendBounded(acc: StringBuilder, chunk: String) {
        acc.append(chunk)
        if (acc.length > MAX_ACCUMULATED_CHARS) {
            val head = acc.substring(0, MAX_ACCUMULATED_CHARS / 4)
            val tail = acc.substring(acc.length - MAX_ACCUMULATED_CHARS / 4)
            acc.setLength(0)
            acc.append(head).append("\n…（中间输出过长已省略）…\n").append(tail)
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun pluginRootOf(pluginId: String): File? {
        if (pluginId.isBlank()) return null
        val safe = pluginId.trim().replace(Regex("[^A-Za-z0-9._\\-]"), "_").trim('.')
        if (safe.isBlank()) return null
        return File(File(File(context.filesDir, "openx"), "plugins"), safe)
    }

    /**
     * 路径 → 宿主 File。容器语义（root 下路径）交给引擎翻译；
     * 其余只放行外部存储与 /tmp，应用私有目录一律拒绝。
     */
    /**
     * 路径 → 宿主 File。
     *
     * 先按**容器语义路径**过黑名单（`~` 展开后再判），再交给引擎翻译：
     * `/root/.openx` 会被映射到应用内部的 openx 目录（插件代码、记忆、技能、
     * plugins.json 全在那），翻译完再拦已经来不及区分语义了。
     */
    private fun resolvePath(raw: String): File? {
        val p = raw.trim()
        if (p.isEmpty()) return null
        val expanded = when {
            p == "~" -> "/root"
            p.startsWith("~/") -> "/root/" + p.removePrefix("~/")
            else -> p
        }
        if (isProtectedPath(expanded)) return null
        val translated = AppContextHolder.containerEngine?.toHostFileIfContainerPath(expanded)
        if (translated != null) return translated
        val allowed = expanded == "/sdcard" || expanded.startsWith("/sdcard/") ||
            expanded.startsWith("/storage/") || expanded.startsWith("/tmp/")
        if (!allowed) return null
        return runCatching { File(expanded).canonicalFile }.getOrElse { File(expanded).absoluteFile }
    }

    /**
     * 宿主自身与插件生态的存放区：兼容插件不得读写。
     *
     * 用容器语义路径判（`/root/.openx`、`/data/data/...`），不用翻译后的宿主路径——
     * rootfs 与应用内部目录同属 `filesDir` 下，按宿主路径判会连带误杀
     * `/root/.config` 这类完全合法的容器内路径。
     */
    private fun isProtectedPath(path: String): Boolean {
        val norm = path.trimEnd('/')
        return norm == "/root/.openx" || norm.startsWith("/root/.openx/") ||
            norm == "/data/data" || norm.startsWith("/data/data/") ||
            norm.startsWith("/data/user/") || norm.startsWith("/data/local/tmp/") ||
            norm.startsWith("/storage/emulated/0/Android/data/")
    }

    // ---------------------------------------------------------------- 随包资源与配置目录

    /**
     * JS 侧 `ToolPkg.getConfigDir()` / `ToolPkg.readResource(key, name, internal)`。
     *
     * 配置目录落在**容器 rootfs 内**的 `/root/.operit_pkg_config/<id>`（不是应用内部的
     * openx 目录，那里已被 [isProtectedPath] 禁止）：插件写自己的配置能跨会话保留，
     * 又碰不到宿主与其他插件的数据。
     */
    fun openxCompatResource(pluginId: String?, op: String?, argsJson: String?): String {
        val id = pluginId.orEmpty()
        if (id.isBlank()) return error("缺少插件身份")
        val safeId = sanitize(id)
        if (safeId.isBlank()) return error("插件 id 非法")
        val args = parseArgs(argsJson)
        return when (op) {
            "configDir" -> {
                val sub = sanitize(args["pluginId"]?.toString().orEmpty())
                // 容器语义路径（回给 JS，后续读写走它翻译）与宿主路径（真正建目录）分开算
                val containerPath = if (sub.isBlank()) "$PKG_CONFIG_ROOT/$safeId" else "$PKG_CONFIG_ROOT/$safeId/$sub"
                val hostDir = resolvePath(containerPath) ?: return error("配置目录路径非法：$containerPath")
                runCatching { hostDir.mkdirs() }
                gson.toJson(mapOf("ok" to true, "path" to containerPath))
            }
            "readResource" -> {
                if (PluginPermission.FILESYSTEM !in permissionsOf(id)) return error("缺少 filesystem 权限")
                val key = args["key"]?.toString().orEmpty()
                val outName = args["outputFileName"]?.toString().orEmpty()
                val pluginDir = File(File(File(context.filesDir, "openx"), "plugins"), safeId)
                val hit = resolveResource(pluginDir, key)
                if (hit == null) {
                    gson.toJson(mapOf("ok" to true, "path" to "", "missing" to true))
                } else if (outName.isBlank()) {
                    gson.toJson(mapOf("ok" to true, "path" to hit.absolutePath))
                } else {
                    val containerPath = "$PKG_CONFIG_ROOT/$safeId/out/${sanitize(outName)}"
                    val dest = resolvePath(containerPath) ?: return error("输出路径非法：$containerPath")
                    runCatching {
                        dest.parentFile?.mkdirs()
                        hit.copyTo(dest, overwrite = true)
                    }.onFailure { return error(it.message ?: "资源释放失败") }
                    gson.toJson(mapOf("ok" to true, "path" to containerPath))
                }
            }
            else -> error("未知操作：$op")
        }
    }

    /**
     * 容器内的插件配置根（**容器语义路径**）。
     *
     * 不能直接当宿主路径用：它得经 [resolvePath] 翻译成当前 profile 的 rootfs 内目录。
     * 放在 rootfs 而非应用内部 openx 目录，因为后者已被 [isProtectedPath] 禁止插件访问。
     */
    /**
     * 把资源 key 解析成真实文件。
     *
     * 优先按 manifest 的 `resources` 声明查表（官方语义：元素是 `{key, path, mime}`，
     * 按 key 不区分大小写匹配）；没声明时退回 `resources/<key>` 约定路径。
     */
    private fun resolveResource(pluginDir: File, key: String): File? {
        if (key.isBlank()) return null
        val declared = runCatching { OperitCompat.readManifest(pluginDir)?.resources }.getOrNull().orEmpty()
        for (r in declared) {
            if (!r.key.equals(key, ignoreCase = true)) continue
            val f = File(pluginDir, r.path)
            // 防上跳：声明路径必须落在插件目录内
            val inside = runCatching { f.canonicalPath.startsWith(pluginDir.canonicalPath) }.getOrDefault(false)
            if (inside && f.isFile) return f
        }
        val legacy = File(pluginDir, "resources/$key")
        return if (legacy.isFile) legacy else null
    }

    private fun sanitize(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._\\-]"), "_").trim('.')

    // ---------------------------------------------------------------- 终端

    @Suppress("UNCHECKED_CAST")
    private fun parseArgs(json: String?): Map<String, Any?> =
        runCatching {
            gson.fromJson(json ?: "{}", Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()

    private fun error(msg: String): String = gson.toJson(mapOf("ok" to false, "error" to msg))
}
