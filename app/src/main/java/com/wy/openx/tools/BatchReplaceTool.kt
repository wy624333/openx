package com.wy.openx.tools

import com.wy.openx.data.util.AtomicFileWriter
import com.wy.openx.remote.RemoteWorkspaceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BatchReplaceTool : AgentTool {
    override val name = "batch_replace"
    override val description = "跨文件搜索和替换文本。支持按 glob 模式匹配文件（支持 ** 与 {a,b}），先预览匹配结果，可选执行替换。replace 模式会处理所有匹配文件，不受 max_results 限制。"
    override val parameters = buildToolParameters(
        ToolParameter("operation", ParameterType.STRING, "操作类型：search(仅搜索预览) replace(搜索并替换)", enumValues = listOf("search", "replace")),
        ToolParameter("search", ParameterType.STRING, "要搜索的文本（支持正则表达式）"),
        ToolParameter("pattern", ParameterType.STRING, "文件匹配模式，如 *.kt、src/**/*.ts、*.{js,ts}。不传则搜索所有文件。", required = false),
        ToolParameter("replace", ParameterType.STRING, "替换为的文本（字面文本，$ 与 \\ 不作为分组引用）", required = false),
        ToolParameter("dir", ParameterType.STRING, "搜索目录路径。不传则使用应用根目录。", required = false),
        ToolParameter("max_results", ParameterType.INTEGER, "最大显示结果数（默认 20）", required = false)
    )

    private companion object {
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
    }

    override suspend fun execute(args: Map<String, Any>): String {
        val operation = (args["operation"] as? String)?.trim() ?: return "错误：未指定操作"
        val search = (args["search"] as? String) ?: return "错误：未提供搜索文本"
        val pattern = (args["pattern"] as? String)?.trim() ?: "*"
        val replace = (args["replace"] as? String) ?: ""
        val maxResults = (((args["max_results"] as? Double)?.toInt()) ?: 20).coerceIn(1, 200)
        val dirStr = (args["dir"] as? String)?.trim()

        val rootDir = if (!dirStr.isNullOrBlank()) {
            ToolPaths.sanitize(dirStr)?.takeIf { it.isDirectory }
                ?: return "错误：目录不可用或被安全策略拒绝「$dirStr」"
        } else {
            val ctx = AppContextHolder.context ?: return "错误：应用上下文未初始化"
            ctx.getExternalFilesDir(null) ?: return "错误：无法获取文件目录"
        }
        if (!dirStr.isNullOrBlank() && RemoteWorkspaceRegistry.isRemote(dirStr)) {
            return "错误：batch_replace 不支持「服务器为准」的工作区（代码在服务器上）。请改用 editFile 逐个文件修改。"
        }

        if (!rootDir.exists()) return "错误：目录「${rootDir.absolutePath}」不存在"

        return try {
            withContext(Dispatchers.IO) {
                val files = collectFiles(rootDir, pattern)
                if (files.isEmpty()) return@withContext "未找到匹配「$pattern」的文件"

                val regex = try { Regex(search) } catch (e: Exception) {
                    return@withContext "错误：正则表达式无效 - ${e.message}"
                }

                // 展示用结果（受 maxResults 限制）
                val previewResults = mutableListOf<Pair<File, List<MatchResult>>>()
                // 替换目标 = 全部命中文件（不受 maxResults 截断）
                val hitFiles = mutableListOf<File>()
                var totalMatches = 0
                for (file in files) {
                    if (file.length() > MAX_FILE_BYTES) continue
                    val text = try { file.readText() } catch (_: Exception) { continue }
                    val matches = ArrayList<MatchResult>(64)
                    for (m in regex.findAll(text)) {
                        matches.add(m)
                        if (matches.size >= 5000) break
                    }
                    if (matches.isNotEmpty()) {
                        totalMatches += matches.size
                        hitFiles.add(file)
                        if (previewResults.size < maxResults) {
                            previewResults.add(file to matches.take(50))
                        }
                    }
                }

                when (operation) {
                    "search" -> buildSearchResult(previewResults, totalMatches, rootDir)
                    "replace" -> buildReplaceResult(hitFiles, search, replace, regex, rootDir)
                    else -> "错误：不支持的操作「$operation」"
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "执行失败: ${e.message ?: "未知错误"}" }
    }

    private fun collectFiles(dir: File, pattern: String): List<File> {
        val globRegex = try {
            Regex(globToRegex(pattern), setOf(RegexOption.IGNORE_CASE))
        } catch (_: Exception) { return emptyList() }
        return dir.walkTopDown()
            .filter { it.isFile }
            .filter { !it.path.contains("/.") }
            .filter { globRegex.matches(it.name) || globRegex.matches(it.relativeTo(dir).path) }
            .toList()
    }

    /** 支持 ** 跨目录、* 单层、? 单字符、{a,b} 展开 */
    private fun globToRegex(glob: String): String {
        return expandBraces(glob).joinToString("|") { g ->
            buildString {
                var i = 0
                while (i < g.length) {
                    val c = g[i]
                    when {
                        c == '*' && i + 1 < g.length && g[i + 1] == '*' -> {
                            append(".*")
                            i += 2
                            if (i < g.length && g[i] == '/') i++
                        }
                        c == '*' -> { append("[^/]*"); i++ }
                        c == '?' -> { append('.'); i++ }
                        else -> {
                            if ("\\.[]{}()+-^$|".indexOf(c) >= 0) append('\\')
                            append(c); i++
                        }
                    }
                }
            }
        }.let { alternatives -> if (alternatives.contains('|')) "($alternatives)" else alternatives }
    }

    /** 展开 {a,b,c}（支持单层嵌套扫描） */
    private fun expandBraces(pattern: String): List<String> {
        val open = pattern.indexOf('{')
        if (open < 0) return listOf(pattern)
        var depth = 0
        var close = -1
        for (i in open until pattern.length) {
            when (pattern[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { close = i; break } }
            }
        }
        if (close < 0) return listOf(pattern)
        val prefix = pattern.substring(0, open)
        val body = pattern.substring(open + 1, close)
        val suffix = pattern.substring(close + 1)
        val options = splitTopLevel(body)
        return options.flatMap { expandBraces(prefix + it + suffix) }
    }

    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (ch in s) {
            when {
                ch == '{' -> { depth++; cur.append(ch) }
                ch == '}' -> { depth--; cur.append(ch) }
                ch == ',' && depth == 0 -> { parts.add(cur.toString()); cur.clear() }
                else -> cur.append(ch)
            }
        }
        parts.add(cur.toString())
        return parts
    }

    private fun buildSearchResult(results: List<Pair<File, List<MatchResult>>>, total: Int, root: File): String {
        val sb = StringBuilder()
        sb.appendLine("🔍 搜索完成，共匹配 $total 处，显示 ${results.size} 个文件：")
        sb.appendLine()
        for ((file, matches) in results) {
            val rel = file.relativeTo(root).path
            sb.appendLine("📄 $rel (${matches.size} 处)")
            for (m in matches.take(5)) {
                val line = getLineNumber(file, m.range.first)
                val ctx = getContext(file, m.range, 40)
                sb.appendLine("  :$line  $ctx")
            }
            if (matches.size > 5) sb.appendLine("  ... 还有 ${matches.size - 5} 处")
        }
        return sb.toString().trimEnd()
    }

    private fun buildReplaceResult(
        hitFiles: List<File>, search: String, replace: String,
        regex: Regex, root: File
    ): String {
        if (replace.isBlank()) return "错误：replace 操作需要提供替换文本"

        var replaced = 0
        var failed = 0
        val modifiedFiles = mutableListOf<File>()
        for (file in hitFiles) {
            try {
                val text = file.readText()
                // 用 lambda 字面替换：replace 中的 $、\ 不再被当作分组引用解析
                val newText = text.replace(regex) { _ -> replace }
                if (newText != text) {
                    AtomicFileWriter.writeAtomically(file, newText)
                    replaced++
                    modifiedFiles.add(file)
                }
            } catch (_: Exception) { failed++ }
        }

        val sb = StringBuilder()
        sb.appendLine("✅ 替换完成")
        sb.appendLine("  搜索: «$search»")
        sb.appendLine("  替换: «$replace»")
        sb.appendLine("  命中文件: ${hitFiles.size}")
        sb.appendLine("  已修改: $replaced")
        sb.appendLine("  未变更/失败: $failed")
        sb.appendLine()
        sb.appendLine("修改的文件：")
        for (file in modifiedFiles.take(20)) {
            sb.appendLine("  📄 ${file.relativeTo(root).path}")
        }
        if (modifiedFiles.size > 20) sb.appendLine("  ... 还有 ${modifiedFiles.size - 20} 个")
        return sb.toString().trimEnd()
    }

    private fun getLineNumber(file: File, offset: Int): Int {
        return try {
            val text = file.readText()
            text.substring(0, (offset + 1).coerceAtMost(text.length)).count { it == '\n' } + 1
        } catch (_: Exception) { 0 }
    }

    private fun getContext(file: File, range: IntRange, radius: Int): String {
        return try {
            val text = file.readText()
            val start = (range.first - radius).coerceAtLeast(0)
            val end = (range.last + radius + 1).coerceAtMost(text.length)
            val ctx = text.substring(start, end).replace("\n", "\\n")
            (if (start > 0) "..." else "") + ctx + (if (end < text.length) "..." else "")
        } catch (_: Exception) { "" }
    }
}
