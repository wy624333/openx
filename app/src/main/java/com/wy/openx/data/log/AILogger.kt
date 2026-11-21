package com.wy.openx.data.log

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AI 提供商「完整请求 / 响应」日志：**每个会话(sessionId)一个文件**，逐次详细落盘每一次
 * 调用的 URL、请求体(body)、响应体/原始 SSE，便于在没有抓包工具时离线诊断模型交互问题。
 *
 * 与 [AppLogStore]（运行日志，按 1000 条分文件）相互独立：本类按「会话」维度归档，
 * 体量更大、内容更全（含完整对话历史、工具定义、原始 SSE 流），因此单独成文件。
 *
 * 落盘位置：优先外置 `/sdcard/openx/ai-logs/`（便于文件管理器直接取出），
 * 不可写时降级到私有 `filesDir/ai-logs/`。
 * 请求体不含 API Key（密钥在 HTTP 头，本类只记录 URL 与 body），且对图片 base64 做脱敏，可安全留存。
 */
object AILogger {

    private const val TAG = "AILogger"
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024 // 单会话上限 20MB（每轮重发完整历史，增长快）
    private const val EXTERNAL_DIR_SUBPATH = "openx/ai-logs"
    private const val FILE_PREFIX = "session-"
    private const val FILE_SUFFIX = ".log"

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-logger").apply { isDaemon = true }
    }
    private val timestampFormat = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault())
    // 与 Retrofit 的 Gson 行为对齐，额外开启缩进便于阅读，关掉 HTML 转义避免 < > & 被转义影响可读性。
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var logDir: File? = null
        private set

    /** 每会话的调用计数：把同一次交互的 REQUEST / RESPONSE 配上同一序号。 */
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private var lastDirRecheck = 0L

    /** 初始化日志目录。重复调用安全。 */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        logDir = resolveLogDir(context.applicationContext)
        com.wy.openx.container.FileLogger.rawLogcat(TAG, "AILogger 初始化完成，AI 会话日志目录: ${logDir?.absolutePath}")
    }

    private fun resolveLogDir(ctx: Context): File {
        val external = runCatching {
            val root = android.os.Environment.getExternalStorageDirectory()
            if (root != null && root.exists()) File(root, EXTERNAL_DIR_SUBPATH) else null
        }.getOrNull()
        if (external != null) {
            val ok = runCatching {
                external.mkdirs()
                external.isDirectory && external.canWrite() && probeWritable(external)
            }.getOrDefault(false)
            if (ok) return external
        }
        return File(ctx.filesDir, "ai-logs")
    }

    private fun probeWritable(dir: File): Boolean = runCatching {
        val probe = File(dir, ".write_probe")
        probe.writeText("")
        probe.delete()
        true
    }.getOrDefault(false)

    /**
     * 记录一次请求的 URL 与请求体，并把本会话计数 +1（作为本次交互的序号）。
     * @return 序号 `n`，调用方必须把它原样回传给对应的 [logResponse]/[logError]/[logResponseStream]。
     */
    fun logRequest(sessionId: String?, provider: String, model: String, method: String, url: String, body: Any?): Int {
        val n = counter(sessionId).incrementAndGet()
        val text = buildString {
            append('\n').append("=".repeat(78)).append('\n')
            append(now()).append("  REQUEST #").append(n)
            append("   [").append(provider).append(" / ").append(model).append("]\n")
            append(method).append(' ').append(url).append('\n')
            append("--- request body ---\n")
            append(stringify(body)).append('\n')
        }
        write(sessionId, text)
        return n
    }

    /** 记录一次非流式响应对象。 */
    fun logResponse(sessionId: String?, provider: String, body: Any?, seq: Int) {
        val text = buildString {
            append(now()).append("  RESPONSE #").append(seq)
            append("   [").append(provider).append("]\n")
            append("--- response body ---\n")
            append(stringify(body)).append('\n')
        }
        write(sessionId, text)
    }

    /** 记录一次流式响应累积的原始 SSE 文本。 */
    fun logResponseStream(sessionId: String?, provider: String, raw: String, seq: Int) {
        val text = buildString {
            append(now()).append("  RESPONSE #").append(seq)
            append("   [").append(provider).append(" / stream]\n")
            append("--- raw SSE ---\n")
            append(redact(raw).ifBlank { "(空响应)" })
            if (!raw.endsWith("\n")) append('\n')
        }
        write(sessionId, text)
    }

    /** 记录一次请求失败（取消不算失败，不应走到这里）。 */
    fun logError(sessionId: String?, provider: String, throwable: Throwable, seq: Int) {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val text = buildString {
            append(now()).append("  ERROR #").append(seq)
            append("   [").append(provider).append("]\n")
            append(sw.toString().trimEnd()).append('\n')
        }
        write(sessionId, text)
    }

    /** 全部会话日志文件（供占用统计/清理）。 */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.sortedBy { it.name } ?: emptyList()
    }

    /** 删除全部会话日志，返回释放字节数。 */
    fun clearLogs(): Long {
        val dir = logDir ?: return 0L
        val freed = AtomicLong(0)
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            runCatching {
                dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }?.forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed.addAndGet(size)
                }
                counters.clear()
            }.onFailure { Log.e(TAG, "清空 AI 会话日志失败", it) }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return freed.get()
    }

    // ── 内部 ──

    private fun counter(sessionId: String?): AtomicInteger =
        counters.getOrPut(sessionId ?: "unknown") { AtomicInteger(0) }

    private fun now(): String = timestampFormat.format(java.time.Instant.now())

    private fun stringify(body: Any?): String = when (body) {
        null -> "null"
        is String -> body
        else -> runCatching { gson.toJson(body) }.getOrElse { body.toString() }
    }.let { redact(it) }

    private fun write(sessionId: String?, text: String) {
        maybeMigrateToExternal()
        val dir = logDir ?: return
        val safeId = (sessionId ?: "unknown").replace(Regex("[^A-Za-z0-9_-]"), "_")
        ioExecutor.execute {
            runCatching {
                val file = File(dir, "$FILE_PREFIX$safeId$FILE_SUFFIX")
                if (file.length() > MAX_FILE_BYTES) {
                    file.writeText("--- AI 会话日志超过 ${MAX_FILE_BYTES / 1024 / 1024}MB 已重置 ---\n")
                }
                file.appendText(text)
            }.onFailure { Log.e(TAG, "写入 AI 会话日志失败", it) }
        }
    }

    /** 降级到私有目录时，周期性重试外置（启动时存储权限常未授予）。 */
    private fun maybeMigrateToExternal() {
        val ctx = appContext ?: return
        val cur = logDir ?: return
        val extRoot = runCatching { android.os.Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull() ?: return
        if (cur.absolutePath.startsWith(extRoot)) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastDirRecheck < 30_000L) return
        lastDirRecheck = nowMs
        val ext = runCatching { File(android.os.Environment.getExternalStorageDirectory(), EXTERNAL_DIR_SUBPATH) }.getOrNull() ?: return
        val ok = runCatching { ext.mkdirs(); ext.isDirectory && ext.canWrite() && probeWritable(ext) }.getOrDefault(false)
        if (ok) logDir = ext
    }

    /**
     * 对可能很大的二进制内容脱敏/截断，避免日志文件被图片 base64 撑爆：
     * 形如 `"data:image/...;base64,xxxx..."` 或超长 base64 串，替换为占位。
     */
    private fun redact(text: String): String {
        var out = text
        // data URL 图片
        out = Regex(""""data:image/[^;"]+;base64,[A-Za-z0-9+/=\\s]{200,}"""").replace(out) { "\"data:image/…;base64,(已省略 ${'$'}{it.value.length} 字符)\"" }
        // 裸 base64 超长串（>2000 字符）——不常见但兜底
        out = Regex("""[A-Za-z0-9+/]{2000,}={0,2}""").replace(out) { "(base64 已省略 ${it.value.length} 字符)" }
        return out
    }
}
