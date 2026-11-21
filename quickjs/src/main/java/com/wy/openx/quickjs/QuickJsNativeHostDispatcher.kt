package com.wy.openx.quickjs

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import org.json.JSONArray

class QuickJsNativeHostDispatcher(
    private val dispatchTimer: (Int) -> Unit,
    private val forwardCall: (String, String?) -> String?,
    /**
     * console.* 的输出。之前这里是直接丢弃的（`-> null`），插件作者的日志
     * 既进不了 logcat 也进不了任何界面，排错只能靠猜。现在转给调用方处理。
     */
    private val onConsole: ((level: String, message: String) -> Unit)? = null
) : QuickJsNativeRuntime.HostBridge, Closeable {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "QuickJsTimer").apply { isDaemon = true }
    }
    private val timerTasks = ConcurrentHashMap<Int, ScheduledFuture<*>>()

    override fun onCall(method: String, argsJson: String?): String? {
        return when {
            method.startsWith("console.") -> {
                // argsJson 是 JS 侧 map 出来的字符串数组（见 QuickJsNativeCompatScriptBuilder）
                val level = method.removePrefix("console.")
                val text = runCatching {
                    val arr = JSONArray(argsJson ?: "[]")
                    (0 until arr.length()).joinToString(" ") { arr.optString(it) }
                }.getOrDefault("")
                onConsole?.invoke(level, text)
                null
            }
            method == "scheduleTimer" -> { schedule(argsJson); null }
            method == "cancelTimer" -> { cancel(argsJson); null }
            else -> forwardCall(method, argsJson)
        }
    }

    override fun close() {
        timerTasks.values.forEach { it.cancel(false) }
        timerTasks.clear()
        scheduler.shutdownNow()
    }

    private fun schedule(argsJson: String?) {
        val args = parseArgs(argsJson)
        val timerId = args.getOrNull(0)?.toIntOrNull() ?: return
        val delayMs = max(0L, args.getOrNull(1)?.toLongOrNull() ?: 0L)
        val repeat = args.getOrNull(2)?.let(::parseBoolean) ?: false
        timerTasks.remove(timerId)?.cancel(false)
        val task = if (repeat) {
            scheduler.scheduleAtFixedRate({ dispatchTimer(timerId) }, max(1L, delayMs), max(1L, delayMs), TimeUnit.MILLISECONDS)
        } else {
            scheduler.schedule({ timerTasks.remove(timerId); dispatchTimer(timerId) }, delayMs, TimeUnit.MILLISECONDS)
        }
        timerTasks[timerId] = task
    }

    private fun cancel(argsJson: String?) {
        val timerId = parseArgs(argsJson).firstOrNull()?.toIntOrNull() ?: return
        timerTasks.remove(timerId)?.cancel(false)
    }

    private fun parseArgs(argsJson: String?): List<String> {
        if (argsJson.isNullOrBlank()) return emptyList()
        val array = JSONArray(argsJson)
        return buildList(array.length()) { for (i in 0 until array.length()) add(array.optString(i)) }
    }

    private fun parseBoolean(value: String): Boolean = value.toBooleanStrictOrNull() ?: (value.toIntOrNull() ?: 0) != 0
}
