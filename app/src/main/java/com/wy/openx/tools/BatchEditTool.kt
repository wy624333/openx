package com.wy.openx.tools

import com.wy.openx.data.util.AtomicFileWriter
import com.wy.openx.remote.RemoteWorkspaceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BatchEditTool : AgentTool {
    override val name = "batch_edit"
    override val description = "批量编辑多个文件。接收一组编辑操作（每项含文件路径、原文、新文本），统一预览差异后全部应用。"
    override val parameters = buildToolParameters(
        ToolParameter("edits", ParameterType.ARRAY, "编辑操作列表，每项包含 path/old_text/new_text",
            itemsSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "文件路径"),
                    "old_text" to mapOf("type" to "string", "description" to "要替换的原文（必须完全匹配，字面匹配而非正则）"),
                    "new_text" to mapOf("type" to "string", "description" to "替换后的新文本")
                ),
                "required" to listOf("path", "old_text", "new_text")
            )
        ),
        ToolParameter("dir", ParameterType.STRING, "基础目录路径。不传则使用应用根目录。", required = false),
        ToolParameter("dry_run", ParameterType.BOOLEAN, "仅预览不写入（默认 true）", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val editsRaw = args["edits"]
        val editList = when (editsRaw) {
            is List<*> -> editsRaw.filterIsInstance<Map<*, *>>()
            else -> return "错误：edits 必须是一个数组"
        }
        if (editList.isEmpty()) return "错误：未提供任何编辑操作"

        val dirStr = (args["dir"] as? String)?.trim()
        val baseDir = if (!dirStr.isNullOrBlank()) {
            ToolPaths.sanitize(dirStr)?.takeIf { it.isDirectory }
                ?: return "错误：目录不可用或被安全策略拒绝「$dirStr」"
        } else {
            val ctx = AppContextHolder.context ?: return "错误：应用上下文未初始化"
            ctx.getExternalFilesDir(null) ?: return "错误：无法获取文件目录"
        }
        val dryRun = (args["dry_run"] as? Boolean) ?: true

        // 本工具是「批量 + 预览两阶段」，逐文件走 SFTP 会让 dry_run 退化成全量下载；
        // 而静默改到本地旧副本更不可接受。所以直接拒结并指引到已接路由的 editFile。
        val remoteTouched = (dirStr != null && RemoteWorkspaceRegistry.isRemote(dirStr)) ||
            editList.any { e ->
                val p = (e["path"] as? String)?.trim()
                p != null && RemoteWorkspaceRegistry.isRemote(p)
            }
        if (remoteTouched) {
            return "错误：batch_edit 不支持「服务器为准」的工作区（代码在服务器上）。请改用 editFile 逐个文件修改。"
        }

        return try {
            withContext(Dispatchers.IO) {
                val parsed = mutableListOf<EditTarget>()
                for ((i, edit) in editList.withIndex()) {
                    val path = (edit["path"] as? String)?.trim() ?: return@withContext "错误：第 ${i + 1} 项缺少 path"
                    val oldText = (edit["old_text"] as? String) ?: return@withContext "错误：第 ${i + 1} 项缺少 old_text"
                    val newText = (edit["new_text"] as? String) ?: return@withContext "错误：第 ${i + 1} 项缺少 new_text"
                    val file = resolveFile(baseDir, path)
                        ?: return@withContext "错误：文件未找到或路径被安全策略拒绝 - $path"
                    parsed.add(EditTarget(file, oldText, newText))
                }

                val results = mutableListOf<EditResult>()
                for (t in parsed) {
                    val content = t.file.readText()
                    val firstIdx = content.indexOf(t.oldText)
                    if (firstIdx < 0) {
                        results.add(EditResult(t.file.path, false, error = "文件中未找到匹配的原文"))
                        continue
                    }
                    // 字面量定位替换（不用正则），newText 中的 $ \ 等字符原样写入
                    val updated = buildString {
                        append(content, 0, firstIdx)
                        append(t.newText)
                        append(content, firstIdx + t.oldText.length, content.length)
                    }
                    val diff = computeDiff(t.oldText, t.newText)
                    if (!dryRun) AtomicFileWriter.writeAtomically(t.file, updated)
                    results.add(EditResult(t.file.path, true, diff = diff))
                }

                buildString {
                    val successCount = results.count { it.success }
                    val failCount = results.count { !it.success }
                    if (dryRun) appendLine("📋 批量编辑预览 (dry_run=true)")
                    else appendLine("✅ 批量编辑完成 (成功 $successCount / 失败 $failCount)")
                    appendLine()
                    for ((i, r) in results.withIndex()) {
                        appendLine("${i + 1}. ${if (r.success) "✅" else "❌"} ${r.path}")
                        if (r.error.isNotBlank()) appendLine("   错误: ${r.error}")
                        if (r.diff.isNotBlank()) {
                            for (line in r.diff.lines().take(10)) appendLine("   $line")
                            if (r.diff.lines().size > 10) appendLine("   ... 还有 ${r.diff.lines().size - 10} 行差异")
                        }
                    }
                    if (!dryRun) {
                        appendLine()
                        appendLine("成功: $successCount / 总计: ${results.size}")
                    }
                }.trimEnd()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "批量编辑失败: ${e.message ?: "未知错误"}" }
    }

    private fun resolveFile(baseDir: File, path: String): File? {
        val candidate = if (File(path).isAbsolute) {
            ToolPaths.sanitize(path) ?: return null
        } else {
            val direct = File(baseDir, path)
            val resolved = direct.takeIf { it.exists() }
                ?: baseDir.parentFile?.let { File(it, path) }?.takeIf { it.exists() }
                ?: direct
            // 相对路径必须落在 baseDir 或其父级（应用目录）之内
            val baseCanon = baseDir.canonicalPath
            val candCanon = resolved.canonicalPath
            if (!candCanon.startsWith(baseCanon) &&
                baseDir.parentFile?.let { candCanon.startsWith(it.canonicalPath) } != true
            ) return null
            resolved
        }
        return candidate.takeIf { it.exists() }
    }

    private fun computeDiff(oldText: String, newText: String): String {
        var oldLines = oldText.split("\n")
        var newLines = newText.split("\n")
        if (oldLines.size > 2000 || newLines.size > 2000) {
            oldLines = oldLines.take(2000)
            newLines = newLines.take(2000)
        }
        val sb = StringBuilder()
        val lcs = longestCommonSubsequence(oldLines, newLines)
        var oi = 0; var ni = 0
        for (common in lcs) {
            while (oi < common.first) { sb.appendLine("- ${oldLines[oi]}"); oi++ }
            while (ni < common.second) { sb.appendLine("+ ${newLines[ni]}"); ni++ }
            sb.appendLine("  ${oldLines[oi]}"); oi++; ni++
        }
        while (oi < oldLines.size) { sb.appendLine("- ${oldLines[oi]}"); oi++ }
        while (ni < newLines.size) { sb.appendLine("+ ${newLines[ni]}"); ni++ }
        return sb.toString().trimEnd()
    }

    private fun longestCommonSubsequence(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val m = a.size; val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n) dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        val result = mutableListOf<Pair<Int, Int>>()
        var i = m; var j = n
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> { result.add(i - 1 to j - 1); i--; j-- }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return result.reversed()
    }

    private data class EditResult(
        val path: String,
        val success: Boolean,
        val diff: String = "",
        val error: String = ""
    )

    private data class EditTarget(
        val file: File,
        val oldText: String,
        val newText: String
    )
}
