package com.wy.openx.data.util

import java.io.File

/**
 * 原子文件写入：先写临时文件再 rename 替换，
 * 避免进程被杀时产生截断的 JSON/数据文件导致整库静默清零。
 */
object AtomicFileWriter {

    fun writeAtomically(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent ?: File("."), file.name + ".tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // 某些文件系统 rename 到已存在目标会失败，退化为删后重命名
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    file.writeText(content, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            throw e
        }
    }
}
