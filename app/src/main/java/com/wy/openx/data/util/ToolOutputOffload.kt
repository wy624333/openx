package com.wy.openx.data.util

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 工具输出的落盘写入方（原子写 + 容量上限）。
 *
 * 要求：
 * 1. **原子写**：先写同目录临时文件再 `renameTo`，`check(...)` 保证失败会被看见。
 *   
 *    
 * 2. **投影不改存储**：本类只负责写文件，替换只发生在 [ToolOutputBudget] 生成的请求副本上。
 *
 * 容量上限：
 * 超过 [MAX_TOTAL_BYTES] 时按会话目录的最后修改时间，从最旧的整目录删。
 */
class ToolOutputOffload(
    private val context: Context,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES
) {

    /** 本实例是否真的写出过新文件（内容未变时命中已有文件，不算）。见 [trim]。 */
    private var wroteNewFile = false

    /**
     * 落盘一份工具输出，返回宿主绝对路径。
     * 抛 [java.io.IOException] 表示写盘失败——调用方必须让它冒泡。
     */
    fun offload(conversationId: Long, toolName: String, content: String): String {
        val dir = ToolOffloadPaths.conversationDir(context.filesDir, conversationId)
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw java.io.IOException("无法创建工具输出落盘目录: ${dir.absolutePath}")
        }
        val name = "${ToolOffloadPaths.safeToolName(toolName)}-${digestOf(content)}.txt"
        val target = File(dir, name)
        if (!target.exists()) {
            val tmp = File.createTempFile("offload-", ".tmp", dir)
            try {
                tmp.writeText(content, Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    // 少数文件系统上 renameTo 会失败，退化成「复制后校验长度」，仍不静默吞掉
                    target.writeText(content, Charsets.UTF_8)
                    check(target.length() == tmp.length() || target.length() > 0) {
                        "工具输出落盘校验失败: ${target.absolutePath}"
                    }
                }
            } finally {
                tmp.delete()
            }
            wroteNewFile = true
        }
        // ⚠ 这里**不做**容量清理：原先每次 offload 都调 enforceCapacity（全树遍历求和），
        // 而 project() 对每条超预算工具消息都调一次 offload → O(消息数 × 落盘文件数)，
        // 大会话实测单轮 9s 全耗在这里（多次重发时文件多已存在，白遍历）。
        // 改为由 [trim] 在**整批落盘结束后**统一执行一次（见 MainViewModel.boundToolOutputsForRequest）。
        return target.absolutePath
    }

    /**
     * 落盘批次结束后统一执行一次容量清理。**未写出新文件时直接返回**（重复重发场景零开销）。
     *
     * 与 [offload] 拆开的原因见其注释：逐次执行会退化成 O(N×M) 的全树遍历。
     * 调用方应在一次请求的投影结束后调用一次，并复用同一个实例。
     *
     * @param keepConversationId 当前会话 id：其目录不会被删（其余会话目录按最旧优先清理）。
     */
    fun trim(keepConversationId: Long) {
        if (!wroteNewFile) return
        wroteNewFile = false
        // 再限流：即使真写了新文件，整树求和也不便宜（数千文件实测单次数百 ms~1s+）。
        // 限流值必须跨实例——调用方每次请求都 new 一个 ToolOutputOffload，实例字段不生效。
        val now = System.currentTimeMillis()
        if (now - lastTrimAt < TRIM_MIN_INTERVAL_MS) return
        lastTrimAt = now
        runCatching {
            enforceCapacity(keep = ToolOffloadPaths.conversationDir(context.filesDir, keepConversationId))
        }
    }

    /** 删掉某个会话的全部落盘文件（会话删除时调用）。 */
    fun clearConversation(conversationId: Long) {
        runCatching { ToolOffloadPaths.conversationDir(context.filesDir, conversationId).deleteRecursively() }
    }

    /** 落盘总量（字节），供设置页/诊断展示。 */
    fun totalBytes(): Long = root().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun root(): File = ToolOffloadPaths.root(context.filesDir)

    /** 超上限就按会话目录的最后修改时间从最旧开始整目录删。 */
    private fun enforceCapacity(keep: File) {
        val root = root()
        if (!root.isDirectory) return
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        if (total <= maxTotalBytes) return
        dirs.filter { it != keep }
            .sortedBy { it.lastModified() }
            .forEach { dir ->
                if (total <= maxTotalBytes) return@forEach
                val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) total -= size
            }
    }

    private fun digestOf(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)

    companion object {
        /** 落盘总量上限 200MB。 */
        const val MAX_TOTAL_BYTES: Long = 200L * 1024 * 1024

        /** [trim] 的最短间隔：容量检查是整树遍历，限流避免每轮请求都扫一遍。 */
        private const val TRIM_MIN_INTERVAL_MS = 30_000L

        /** 跨实例共享的最近一次整树检查时间（调用方每请求 new 一个实例，实例字段会失效）。 */
        @Volatile
        private var lastTrimAt = 0L
    }
}
