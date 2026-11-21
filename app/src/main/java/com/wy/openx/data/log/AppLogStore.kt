package com.wy.openx.data.log

import android.content.Context
import android.os.Environment
import com.wy.openx.container.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志级别。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** 一条结构化日志。 */
data class LogEntry(
    val time: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    /** 线程不安全的 SimpleDateFormat 每次新建；日志展示频率不高，开销可接受。 */
    fun formatTime(): String = FORMAT.get().format(Date(time))

    companion object {
        private val FORMAT = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()) }

        private val TIME_PREFIX = Regex("^\\[\\d{1,2}:\\d{2}(:\\d{2})?]\\s*")
        private val BRACKET_TAG = Regex("^\\[([^\\]]+)]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

        /**
         * 把历史遗留的「拼接字符串日志」转成结构化条目。
         *
         * 旧调用形如 `"[工具] 正在调用: x"`、`"[HH:mm] 提交任务"`、`"准备重新发送消息"`。
         * 解析规则：先剥掉 `[HH:mm]` 时间前缀（时间由 [time] 字段承载，不需要重复），
         * 再把剩下的 `[词]` 当作 tag，无前缀则归入 tag 默认值。
         * 级别按关键词粗判——这只是迁移期的兼容层，新代码应直接用 AppLogStore.log。
         */
        fun fromLegacy(raw: String, tag: String = "App"): LogEntry {
            val stripped = TIME_PREFIX.replace(raw, "")
            val m = BRACKET_TAG.find(stripped)
            val (t, msg) = if (m != null) m.groupValues[1] to m.groupValues[2] else tag to stripped
            val level = when {
                msg.contains("失败") || msg.contains("错误") || msg.contains("异常") -> LogLevel.ERROR
                msg.contains("警告") || msg.contains("超时") || msg.contains("降级") -> LogLevel.WARN
                else -> LogLevel.INFO
            }
            return LogEntry(System.currentTimeMillis(), level, t, msg)
        }
    }
}

/**
 * 全 App 统一日志仓库：内存环形缓冲 + 落盘，供「设置 → 运行日志」展示与排障。
 *
 * 由 [com.wy.openx.OpenXApp] 在进程启动时 [init]。
 *
 * 容量与落盘策略：
 * - 内存保留最近 [MAX_MEMORY]（1000）条，供 UI 实时展示；
 * - 落盘到 **外置存储** `/sdcard/openx/logs/`（便于用户用文件管理器直接取日志排障）；
 *   外置不可写（未授权/无卡）时自动降级到应用私有目录 `filesDir/logs`；
 * - **每满 [ENTRIES_PER_FILE]（1000）条轮转一个新文件**，文件名 `openx_<时间戳>.log`，
 *   历史文件保留、不覆盖，便于回溯；
 * - [clear] 删除日志目录下**全部** `openx_*.log` 文件并重开新文件。
 */
object AppLogStore {

    private const val MAX_MEMORY = 1000
    private const val ENTRIES_PER_FILE = 1000
    /** 外置日志目录（相对外部存储根）：/sdcard/openx/logs */
    private const val EXTERNAL_DIR_SUBPATH = "openx/logs"
    private const val FILE_PREFIX = "openx_"
    private const val FILE_SUFFIX = ".log"

    @Volatile
    private var appContext: Context? = null

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_MEMORY)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** 供 Compose 观察：日志变化时自动重组。 */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var writer: BufferedWriter? = null
    /** 当前日志文件已写入的条数（达到 [ENTRIES_PER_FILE] 即轮转）。 */
    private var currentCount = 0
    private var currentFile: File? = null
    /** 上次尝试重新解析（等待存储授权后迁移到外置）的时间，用于节流。 */
    private var lastDirRecheck = 0L

    /** 实际生效的日志目录（外置优先，降级私有）。未 init 时为 null。 */
    @Volatile
    var logDir: File? = null
        private set

    /** 进程启动时调用：解析日志目录、恢复内存尾部、打开当前文件追加写。重复调用幂等。 */
    fun init(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            runCatching {
                val dir = resolveLogDir(appContext!!)
                logDir = dir
                dir.mkdirs()
                // 恢复最近会话日志到内存（可能跨多个文件，从新到旧凑满 MAX_MEMORY）
                loadTailInto(dir)
                // 打开/新建当前文件：复用最新文件（若未满 1000 条），否则新建
                openCurrentFile(dir)
            }.onFailure {
                // 日志系统自身失败不能影响 App 启动，降级为仅内存
                FileLogger.rawLogcat("AppLogStore", "日志文件初始化失败，降级为仅内存: ${it.message}")
            }
            publish()
        }
    }

    /** 解析日志目录：优先外置 /sdcard/openx/logs，不可写则降级到私有 filesDir/logs。 */
    private fun resolveLogDir(ctx: Context): File {
        val external = runCatching {
            val root = Environment.getExternalStorageDirectory()
            if (root != null && root.exists()) File(root, EXTERNAL_DIR_SUBPATH) else null
        }.getOrNull()
        if (external != null) {
            val ok = runCatching {
                external.mkdirs()
                external.isDirectory && external.canWrite() && probeWritable(external)
            }.getOrDefault(false)
            if (ok) return external
        }
        return File(ctx.filesDir, "logs")
    }

    /** 实写探测：目录 canWrite 在部分 ROM 上不可靠，实际建删一个探针文件确认。 */
    private fun probeWritable(dir: File): Boolean = runCatching {
        val probe = File(dir, ".write_probe")
        probe.writeText("")
        probe.delete()
        true
    }.getOrDefault(false)

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message.replace('\n', ' ').replace('\r', ' '))
        synchronized(lock) {
            // 若当前落在降级目录（私有 filesDir），周期性地重试外置目录：应用启动时存储权限
            // 常尚未授予，此时只能降级；用户授权后应自动迁到 /sdcard/openx/logs。
            maybeMigrateToExternal()
            buffer.addLast(entry)
            while (buffer.size > MAX_MEMORY) buffer.removeFirst()
            writeLine(entry)
            publish()
        }
    }

    /** 处于降级目录时，每 ≥30s 重试一次外置目录；成功则切换并新建外置文件。 */
    private fun maybeMigrateToExternal() {
        val ctx = appContext ?: return
        val cur = logDir ?: return
        val extRoot = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull() ?: return
        if (cur.absolutePath.startsWith(extRoot)) return
        val now = System.currentTimeMillis()
        if (now - lastDirRecheck < 30_000L) return
        lastDirRecheck = now
        val ext = runCatching { File(Environment.getExternalStorageDirectory(), EXTERNAL_DIR_SUBPATH) }.getOrNull() ?: return
        val ok = runCatching { ext.mkdirs(); ext.isDirectory && ext.canWrite() && probeWritable(ext) }.getOrDefault(false)
        if (!ok) return
        runCatching {
            writer?.close()
            writer = null
            logDir = ext
            currentCount = 0
            openCurrentFile(ext)
        }.onFailure { FileLogger.rawLogcat("AppLogStore", "迁移到外置日志目录失败: ${it.message}") }
    }

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    /** 写入一条历史格式（`[Tag] 消息`）的日志，级别与 tag 自动解析。迁移期兼容用。 */
    fun pushLegacy(raw: String) {
        val e = LogEntry.fromLegacy(raw)
        log(e.level, e.tag, e.message)
    }

    /** 当前快照（最新在末尾）。 */
    fun snapshot(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    /** 清空内存与**日志目录下全部日志文件**。 */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            runCatching {
                writer?.close()
                writer = null
                // 删除目录下全部 openx_*.log（不只当前文件）
                val dir = logDir
                if (dir != null && dir.isDirectory) {
                    dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
                        ?.forEach { runCatching { it.delete() } }
                }
                currentCount = 0
                dir?.let { openCurrentFile(it) }
            }
            publish()
        }
    }

    /** 导出全部日志文本到 [target]（供分享/排障）。 */
    fun exportTo(target: File): Boolean = runCatching {
        val text = snapshot().joinToString("\n") { formatLine(it) }
        target.parentFile?.mkdirs()
        target.writeText(text)
        true
    }.getOrDefault(false)

    // ── 内部 ──

    private fun publish() {
        _entries.value = buffer.toList()
    }

    private fun writeLine(entry: LogEntry) {
        val w = writer ?: return
        runCatching {
            w.write(formatLine(entry))
            w.newLine()
            w.flush()
            currentCount++
            if (currentCount >= ENTRIES_PER_FILE) {
                // 满 1000 条：关闭当前、开新文件（历史保留）
                w.close()
                writer = null
                currentCount = 0
                logDir?.let { openCurrentFile(it) }
            }
        }
    }

    /** 单行格式：`MM-dd HH:mm:ss.SSS  INFO  [Tag] 消息`，便于文本工具解析。 */
    private fun formatLine(entry: LogEntry): String =
        "${entry.formatTime()}  ${entry.level.name.padEnd(5)}  [${entry.tag}] ${entry.message}"

    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private var lastFileStamp: String = ""

    /**
     * 打开「当前」日志文件：目录里最新的 `openx_*.log` 若未满 [ENTRIES_PER_FILE] 则追加，
     * 否则新建 `<时间戳>` 文件。避免频繁重启产生大量小文件。
     */
    private fun openCurrentFile(dir: File) {
        val newest = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
        val (file, count) = if (newest != null) {
            val lines = runCatching { newest.readLines().size }.getOrDefault(0)
            if (lines < ENTRIES_PER_FILE) newest to lines else newLogFile(dir) to 0
        } else {
            newLogFile(dir) to 0
        }
        currentFile = file
        currentCount = count
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true)))
    }

    private fun newLogFile(dir: File): File {
        var stamp = fileNameFormat.format(Date())
        // 同一秒内多次新建（清空后立刻又满）时加后缀，避免覆盖
        if (stamp == lastFileStamp) stamp = "$stamp-${System.nanoTime() % 1000}"
        lastFileStamp = stamp
        return File(dir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
    }

    /** 启动时把历史日志尾部读回内存缓冲，App 重启后仍能看到上次会话的日志。 */
    private fun loadTailInto(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        val acc = mutableListOf<LogEntry>()
        for (f in files) {
            if (acc.size >= MAX_MEMORY) break
            runCatching {
                BufferedReader(f.reader()).use { r -> r.readLines() }
                    .mapNotNull { parseLine(it) }
                    .let { acc.addAll(0, it) }  // 旧文件内容插到前面
            }
        }
        acc.takeLast(MAX_MEMORY).forEach { buffer.addLast(it) }
    }

    /** 解析 [formatLine] 产出的行；格式不符（旧版或外部写入）时返回 null。 */
    private fun parseLine(line: String): LogEntry? {
        // 形如 "09-20 14:30:00.123  INFO  [Tag] msg"
        val firstSpace = line.indexOf("  ")
        if (firstSpace < 0) return null
        val rest = line.substring(firstSpace).trimStart()
        val levelEnd = rest.indexOf("  ")
        if (levelEnd < 0) return null
        val levelName = rest.substring(0, levelEnd).trim()
        val level = LogLevel.entries.firstOrNull { it.name == levelName } ?: return null
        val tail = rest.substring(levelEnd).trimStart()
        if (!tail.startsWith("[")) return null
        val tagEnd = tail.indexOf(']')
        if (tagEnd < 0) return null
        val tag = tail.substring(1, tagEnd)
        val message = tail.substring(tagEnd + 1).trimStart()
        return LogEntry(System.currentTimeMillis(), level, tag, message)
    }
}
