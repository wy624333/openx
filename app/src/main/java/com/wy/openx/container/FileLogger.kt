package com.wy.openx.container

import android.util.Log
import com.wy.openx.data.log.AppLogStore
import com.wy.openx.data.log.LogLevel

/**
 * 轻量日志门面：包装 [android.util.Log]，让容器引擎代码保持原调用形态。
 * 方法签名的 FileLogger（d/i/w/e/v，支持 tag+msg 与 tag+msg+Throwable）。
 *
 * 除 logcat 外同时转发到 [AppLogStore]，使容器/MCP/技能等模块的错误能在
 * 「设置 → 运行日志」中查看。转发对 [AppLogStore] 未初始化（如单元测试）安全。
 *
 * 带 Throwable 的 w/e 会附带**异常类名 + 消息 + 关键堆栈帧**（而非仅 message），
 * 便于在 App 内直接看到出错位置，无需连电脑抓 logcat。
 */
object FileLogger {
    private const val DEFAULT_TAG = "OpenXContainer"
    /** 写入日志的堆栈帧上限（只取最相关的几帧，避免刷屏）。 */
    private const val MAX_STACK_FRAMES = 6

    /** 把异常格式化为「全限定类名: 消息 → at 帧1 ← at 帧2 …」，供 App 内展示更详细的报错。 */
    private fun describe(t: Throwable): String {
        val head = "${t.javaClass.name}: ${t.message ?: "(no message)"}" +
            (t.cause?.takeIf { it !== t }?.let { " | cause=${it.javaClass.name}: ${it.message ?: ""}" } ?: "")
        val frames = t.stackTrace.take(MAX_STACK_FRAMES).joinToString(" ← ") { "at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        return if (frames.isBlank()) head else "$head | $frames"
    }

    @JvmStatic fun d(tag: String, msg: String) { Log.d(tag, msg); AppLogStore.debug(tag, msg) }
    @JvmStatic fun d(msg: String) = d(DEFAULT_TAG, msg)

    @JvmStatic fun i(tag: String, msg: String) { Log.i(tag, msg); AppLogStore.info(tag, msg) }
    @JvmStatic fun i(msg: String) = i(DEFAULT_TAG, msg)

    @JvmStatic fun w(tag: String, msg: String) { Log.w(tag, msg); AppLogStore.warn(tag, msg) }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable) { Log.w(tag, msg, tr); AppLogStore.warn(tag, "$msg | ${describe(tr)}") }
    @JvmStatic fun w(msg: String) = w(DEFAULT_TAG, msg)
    @JvmStatic fun w(msg: String, tr: Throwable) = w(DEFAULT_TAG, msg, tr)

    @JvmStatic fun e(tag: String, msg: String) { Log.e(tag, msg); AppLogStore.error(tag, msg) }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable) { Log.e(tag, msg, tr); AppLogStore.error(tag, "$msg | ${describe(tr)}") }
    @JvmStatic fun e(msg: String) = e(DEFAULT_TAG, msg)
    @JvmStatic fun e(msg: String, tr: Throwable) = e(DEFAULT_TAG, msg, tr)

    @JvmStatic fun v(tag: String, msg: String) { Log.v(tag, msg); AppLogStore.debug(tag, msg) }
    @JvmStatic fun v(msg: String) = v(DEFAULT_TAG, msg)

    /**
     * 仅写 logcat，不进入 [AppLogStore]。供日志系统自身使用，避免
     * 「写日志失败 → 记录日志失败 → …」的递归。
     */
    internal fun rawLogcat(tag: String, msg: String) = Log.w(tag, msg)
}
