package com.wy.openx.plugin.ui

import com.wy.openx.container.FileLogger
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 插件文件操作的可复用实现。
 *
 * 两个宿主表面共用同一份逻辑：原生 `ctx.*`（沙箱内相对路径）与 Operit 兼容层
 * `Files.*`（真实路径 + 容器路径翻译）。**路径解析与权限校验在各自的桥接层做**，
 * 这里只做纯文件动作，方便单测。
 */
object PluginFileOps {

    private const val TAG = "PluginFileOps"

    /** 单文件读入内存的上限，diff 与内容搜索受它约束。 */
    const val MAX_TEXT_BYTES = 8L * 1024 * 1024

    /** 内容搜索时单个文件的读取上限，避免把大文件全塞进内存。 */
    private const val MAX_GREP_BYTES = 2L * 1024 * 1024

    /** diff 的行数上限：LCS 是 O(n·m)，再大就把内存吃光了。 */
    private const val MAX_DIFF_LINES = 3000

    data class Hit(val path: String, val name: String, val isDirectory: Boolean, val size: Long)

    /** 移动/重命名。跨挂载点时 rename 会失败，退回「复制 + 删源」。 */
    fun move(src: File, dst: File): Boolean {
        if (!src.exists()) return false
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        return try {
            copy(src, dst) && src.deleteRecursively()
        } catch (e: Exception) {
            FileLogger.w(TAG, "move 失败：${e.message}")
            false
        }
    }

    /** 复制文件或目录（目录递归合并）。 */
    fun copy(src: File, dst: File): Boolean {
        if (!src.exists()) return false
        dst.parentFile?.mkdirs()
        return try {
            if (src.isDirectory) src.copyRecursively(dst, overwrite = true)
            else src.copyTo(dst, overwrite = true).isFile
        } catch (e: Exception) {
            FileLogger.w(TAG, "copy 失败：${e.message}")
            false
        }
    }

    /** 把 [src]（文件或目录）打包成 [dst]，返回写入的文件条目数。 */
    fun zip(src: File, dst: File): Int {
        if (!src.exists()) throw IllegalArgumentException("源不存在：${src.name}")
        dst.parentFile?.mkdirs()
        val dstCanonical = dst.canonicalPath
        val base = if (src.isDirectory) src else (src.parentFile ?: src)
        var count = 0
        ZipOutputStream(dst.outputStream().buffered()).use { zos ->
            for (f in src.walkTopDown()) {
                if (f.canonicalPath == dstCanonical) continue   // 别把正在写的包打进去
                val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                if (rel.isEmpty()) continue
                if (f.isDirectory) {
                    zos.putNextEntry(ZipEntry("$rel/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    /** 解压 [zip] 到 [dst]。条目名含 `../` 时抛 [SecurityException]，返回解出的文件数。 */
    fun unzip(zip: File, dst: File): Int {
        if (!zip.isFile) throw IllegalArgumentException("不是文件：${zip.name}")
        dst.mkdirs()
        val root = dst.canonicalFile
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                val inside = out.path == root.path || out.path.startsWith(root.path + File.separator)
                if (!inside) throw SecurityException("zip 条目越界：${e.name}")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                    count++
                }
            }
        }
        return count
    }

    /**
     * 在 [root] 下递归搜索。
     *
     * [nameLike] 按文件名子串匹配（不区分大小写）；[exts] 是扩展名白名单（小写、不带点）；
     * [contentLike] 命中文件内容（只读 ≤2MB 的文本），不区分大小写。
     */
    fun search(
        root: File,
        nameLike: String? = null,
        exts: Set<String> = emptySet(),
        contentLike: String? = null,
        limit: Int = 200,
        skipHidden: Boolean = true
    ): List<Hit> {
        if (!root.isDirectory) throw IllegalArgumentException("不是目录：${root.name}")
        val cap = limit.coerceIn(1, 2000)
        val name = nameLike?.trim()?.lowercase().orEmpty()
        val content = contentLike?.trim().orEmpty()
        val wantDirs = exts.isEmpty() && content.isEmpty()
        val out = ArrayList<Hit>()

        // 目录本身命中（且没做文件类型/内容过滤时）也算一条，跟常见 grep 工具一致
        for (f in root.walkTopDown().onEnter { !skipHidden || it == root || !it.name.startsWith(".") }) {
            if (f == root) continue
            if (out.size >= cap) break
            if (f.isDirectory) {
                if (wantDirs && (name.isEmpty() || f.name.lowercase().contains(name))) {
                    out += Hit(f.absolutePath, f.name, true, 0)
                }
                continue
            }
            if (exts.isNotEmpty() && f.extension.lowercase() !in exts) continue
            if (name.isNotEmpty() && !f.name.lowercase().contains(name)) continue
            if (content.isNotEmpty()) {
                if (f.length() > MAX_GREP_BYTES) continue
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (!text.contains(content, ignoreCase = true)) continue
            }
            out += Hit(f.absolutePath, f.name, false, f.length())
        }
        return out
    }

    /** 两个文本文件的逐行 unified diff；无差异时返回空串。 */
    fun diff(a: File, b: File, context: Int = 3): String {
        if (!a.isFile) throw IllegalArgumentException("不是文件：${a.name}")
        if (!b.isFile) throw IllegalArgumentException("不是文件：${b.name}")
        if (a.length() > MAX_TEXT_BYTES || b.length() > MAX_TEXT_BYTES) {
            throw IllegalArgumentException("文件超过 8MB 上限")
        }
        val la = a.readLines()
        val lb = b.readLines()
        if (la.size > MAX_DIFF_LINES || lb.size > MAX_DIFF_LINES) {
            throw IllegalArgumentException("行数超过 $MAX_DIFF_LINES 上限，无法 diff")
        }
        return unified(la, lb, a.name, b.name, context.coerceIn(0, 20))
    }

    /**
     * 基于 LCS 的 unified diff。
     *
     * 行号约定按 unified diff 规范：新增行的 aNo 记「插入点前 a 已消费的行数」
     * （顶部插入就是 0），删除行的 bNo 同理。这样生成的 hunk 头才能被 patch 正确应用。
     */
    private fun unified(a: List<String>, b: List<String>, nameA: String, nameB: String, ctx: Int): String {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        data class Row(val kind: Char, val aNo: Int, val bNo: Int, val text: String)

        val rows = ArrayList<Row>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { rows += Row(' ', i + 1, j + 1, a[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { rows += Row('-', i + 1, j, a[i]); i++ }
                else -> { rows += Row('+', i, j + 1, b[j]); j++ }
            }
        }
        while (i < n) { rows += Row('-', i + 1, m, a[i]); i++ }
        while (j < m) { rows += Row('+', n, j + 1, b[j]); j++ }

        val changed = rows.indices.filter { rows[it].kind != ' ' }
        if (changed.isEmpty()) return ""

        // 间隔 ≤ 2*ctx+1 的变更合并进同一个 hunk，否则上下文会叠在一起
        val groups = ArrayList<IntRange>()
        var start = changed[0]
        var end = changed[0]
        for (k in changed.drop(1)) {
            if (k - end <= 2 * ctx + 1) {
                end = k
            } else {
                groups += start..end
                start = k
                end = k
            }
        }
        groups += start..end

        val sb = StringBuilder()
        sb.append("--- ").append(nameA).append('\n')
        sb.append("+++ ").append(nameB).append('\n')
        for (g in groups) {
            val from = (g.first - ctx).coerceAtLeast(0)
            val to = (g.last + ctx).coerceAtMost(rows.size - 1)
            val slice = rows.subList(from, to + 1)
            val aStart = slice.firstOrNull { it.kind != '+' }?.aNo ?: slice.first().aNo
            val bStart = slice.firstOrNull { it.kind != '-' }?.bNo ?: slice.first().bNo
            val aCount = slice.count { it.kind != '+' }
            val bCount = slice.count { it.kind != '-' }
            sb.append("@@ -").append(aStart).append(',').append(aCount)
                .append(" +").append(bStart).append(',').append(bCount).append(" @@\n")
            for (r in slice) sb.append(r.kind).append(r.text).append('\n')
        }
        return sb.toString()
    }
}
