package com.wy.openx.tools

import java.io.File

/**
 * 外部存储删除守卫：识别目标是否落在用户外部存储（共享/手机存储），并给出删除拦截判定。
 *
 * 背景：App 的 AI 工具层（FileManagerTool、MCP fs 等）与备份恢复曾经能通过符号链接或
 * 直接路径删到 `/storage/emulated/0`（或 `/sdcard`）——用户曾把备份/源码放在
 * `/storage/emulated/0/.openx`，一次覆盖恢复被符号链接跟随整个清空。故凡是涉及外部存储的
 * 删除，一律提升为「需二次确认/拦截」级别。
 *
 * [isExternalStorageHost] 同时覆盖 /storage/emulated/&lt;id&gt; 与 /sdcard 两种写法
 * （/sdcard 是 /storage/emulated/0 的常见可访问别名），并做 canonical 归一，杜绝
 * .. 与符号链接绕过。
 */
object ExternalStorageGuard {

    /** 主外部存储根（如 /storage/emulated/0）。 */
    val externalRoot: String by lazy {
        runCatching {
            android.os.Environment.getExternalStorageDirectory().absolutePath
        }.getOrElse { "/storage/emulated/0" }
    }

    /** 是否命中用户外部存储：/storage/emulated/<id>（含 0）或 /sdcard 写法的规范化绝对路径。 */
    fun isExternalStorageHost(file: File): Boolean {
        val abs = try {
            file.canonicalFile.absolutePath
        } catch (_: Exception) {
            file.absolutePath
        }
        return abs.startsWith("/storage/emulated/") ||
            abs.startsWith("/sdcard") ||
            // primary external storage 也可能以主根路径开头
            (abs == externalRoot || abs.startsWith("$externalRoot/"))
    }

    /** 对工具层直接携带宿主路径的删除出口，把危险目标的删除变成受保护异常信息。 */
    fun externalDeleteGuardError(file: File): String? {
        if (!isExternalStorageHost(file)) return null
        val kind = if (file.isDirectory) "目录" else "文件"
        return "🛡️ 外部存储受保护：$kind ${file.absolutePath} 禁止直接删除。\n" +
            "涉及 /storage/emulated/0 或 /sdcard 的删除一律需经 UI 确认（设置→文件浏览器或应用内确认框），" +
            "AI/工具层默认拦截，防止误删备份与源码。"
    }
}