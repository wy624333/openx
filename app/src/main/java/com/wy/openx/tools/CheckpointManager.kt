package com.wy.openx.tools

import com.wy.openx.data.util.AtomicFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedList
import java.util.UUID

/**
 * 轻量文件快照：
 * 每条用户消息（一轮）开启独立快照桶，目录 checkpoints/msg_<userMsgId>/snap_*；
 * 工具批写入前 [snapshotBeforeWrite] 备份原文件；"检查点与撤销"/`/undo` 时
 * [restoreToMessage] 倒序恢复该桶（MODIFY 覆盖，CREATE 删除），并清理该桶。
 */
object CheckpointManager {
    private data class Snapshot(val absPath: String, val backup: File?, val existed: Boolean)

    private val lock = Any()
    private var currentMsgId: Long? = null
    private val buckets = LinkedHashMap<Long, MutableList<Snapshot>>()
    private val byPath = HashMap<Long, HashMap<String, Snapshot>>()

    private val baseDir: File?
        get() = AppContextHolder.context?.filesDir?.let { File(it, "checkpoints") }

    private fun bucketDir(msgId: Long): File? =
        baseDir?.let { b -> b.mkdirs(); File(b, "msg_$msgId") }

    /** 开始一轮：以 user 消息 id 为桶，清空旧残留 */
    fun beginTurn(userMsgId: Long) {
        synchronized(lock) {
            buckets.remove(userMsgId)
            byPath.remove(userMsgId)
            buckets[userMsgId] = LinkedList()
            byPath[userMsgId] = HashMap()
            currentMsgId = userMsgId
        }
        try { bucketDir(userMsgId)?.deleteRecursively() } catch (_: Exception) {}
    }

    /** 消息轮结束：关闭接收快照的门，已存快照保留供撤销恢复 */
    fun endTurn() {
        synchronized(lock) {
            currentMsgId = null
        }
    }

    /** 写工具执行前对将写入的文件一次性快照；预览(diff/dry_run)不产生写入不算 */
    /**
     * @param turnMsgId 本轮 user 消息 id。**并发时必须由调用方显式传入**：
     *   `currentMsgId` 是全局单值，两会话交替 beginTurn 时它只反映最后一次，
     *   于是 A 的写入会被记到 B 的桶里（撤销 B 会回滚 A 的文件）。
     *   传 null 时退回旧的全局指针语义（子代理/压缩等非主链路，保持原行为）。
     */
    suspend fun snapshotBeforeWrite(rawTargets: List<String>, turnMsgId: Long? = null): Unit = withContext(Dispatchers.IO) {
        val msgId = turnMsgId ?: synchronized(lock) { currentMsgId } ?: return@withContext
        for (raw in rawTargets) {
            val abs = absolutize(raw) ?: continue
            val duplicate = synchronized(lock) { byPath[msgId]?.containsKey(abs) ?: true }
            if (duplicate) continue
            val f = File(abs)
            val existed = f.exists() && f.isFile
            val backup = if (existed) {
                val backupDir = File(bucketDir(msgId), "snap_${UUID.randomUUID()}")
                backupDir.mkdirs()
                File(backupDir, f.name).apply { writeBytes(f.readBytes()) }
            } else null
            val snapshot = Snapshot(abs, backup, existed)
            synchronized(lock) {
                val list = byPath[msgId]
                if (list == null) { backup?.delete() }
                else if (list.containsKey(abs)) { backup?.delete() }
                else {
                    list[abs] = snapshot
                    buckets[msgId]?.add(snapshot)
                }
            }
        }
    }

    /** 恢复指定用户消息的快照，返回恢复数量；恢复后清空该桶与磁盘目录 */
    suspend fun restoreToMessage(userMsgId: Long): Int = withContext(Dispatchers.IO) {
        val toRestore = synchronized(lock) { buckets[userMsgId]?.toList() ?: emptyList() }
        var count = 0
        for (s in toRestore.asReversed()) {
            val f = File(s.absPath)
            if (s.existed && s.backup != null && s.backup.exists()) {
                try {
                    AtomicFileWriter.writeAtomically(f, s.backup.readText())
                    count++
                } catch (_: Exception) {}
            } else if (!s.existed && f.exists()) {
                try {
                    f.delete()
                    count++
                } catch (_: Exception) {}
            }
        }
        synchronized(lock) {
            buckets.remove(userMsgId)
            byPath.remove(userMsgId)
            if (currentMsgId == userMsgId) currentMsgId = null
        }
        try { bucketDir(userMsgId)?.deleteRecursively() } catch (_: Exception) {}
        count
    }

    /** /undo：恢复当前最近一轮（最后一条用户消息）的快照 */
    suspend fun restoreAll(): Int {
        val last = synchronized(lock) { buckets.keys.lastOrNull() }
            ?: return 0
        return restoreToMessage(last)
    }

    private fun absolutize(path: String): String? {
        val p = path.trim().removePrefix("file://")
        if (p.isBlank()) return null
        if (File(p).isAbsolute) return File(p).canonicalPath
        val ctx = AppContextHolder.context ?: return null
        val root = ctx.getExternalFilesDir(null) ?: return null
        return File(root, p).canonicalPath
    }
}