package com.wy.openx.tools

import java.io.File

class ExploreTool : AgentTool {
    override val name = "explore"
    override val description = "扫描项目目录结构，返回文件树、文件大小、行数等信息，帮助了解项目全貌。"
    override val parameters = buildToolParameters(
        ToolParameter("dir", ParameterType.STRING, "要扫描的目录路径。不传则使用应用根目录。", required = false),
        ToolParameter("max_depth", ParameterType.INTEGER, "最大扫描深度（默认 4）", required = false),
        ToolParameter("show_files", ParameterType.BOOLEAN, "是否列出文件（默认 true）", required = false),
        ToolParameter("filter", ParameterType.STRING, "文件过滤，如 *.kt 或 *.{kt,java}。不传则显示所有。", required = false),
        ToolParameter("max_results", ParameterType.INTEGER, "最多显示多少项（默认 50）", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val dirStr = (args["dir"] as? String)?.trim()
        val maxDepth = ((args["max_depth"] as? Double)?.toInt()) ?: 4
        val showFiles = (args["show_files"] as? Boolean) ?: true
        val filter = (args["filter"] as? String)?.trim() ?: ""
        val maxResults = ((args["max_results"] as? Double)?.toInt()) ?: 50

        // 代码在服务器上时，本地扫到的是旧副本。宁可拒绝也不要把过时的项目结构交给模型：
        // 它会据此判断「哪些文件存在」，然后去不存在的路径上改代码。
        val containerDir = if (!dirStr.isNullOrBlank()) dirStr
        else com.wy.openx.container.Workspace.CONTAINER_PATH
        if (com.wy.openx.remote.RemoteWorkspaceRegistry.isRemote(containerDir)) {
            return "错误：explore 不支持「服务器为准」的工作区（代码在服务器上）。" +
                "请改用 remote_ssh 跑 `find`/`tree`，或用 search_code 搜内容。"
        }

        val root = if (!dirStr.isNullOrBlank()) {
            AppContextHolder.containerEngine?.toHostFileIfContainerPath(dirStr) ?: File(dirStr)
        } else {
            val ctx = AppContextHolder.context ?: return "错误：应用上下文未初始化"
            ctx.getExternalFilesDir(null) ?: return "错误：无法获取文件目录"
        }
        if (!root.exists()) return "错误：目录「${root.absolutePath}」不存在"
        if (!root.isDirectory) return "错误：「${root.absolutePath}」不是一个目录"

        return try {
            val filterRegex = if (filter.isNotBlank()) {
                val glob = filter.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                try { Regex(glob, setOf(RegexOption.IGNORE_CASE)) } catch (_: Exception) { null }
            } else null

            var totalFiles = 0
            var totalDirs = 0
            var totalSize = 0L
            val lines = mutableListOf<String>()
            var count = 0

            fun walk(dir: File, depth: Int, prefix: String) {
                if (count >= maxResults) return
                if (depth > maxDepth) return

                val entries = dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }) ?: return
                for ((idx, entry) in entries.withIndex()) {
                    if (count >= maxResults) return
                    if (entry.name.startsWith(".")) continue

                    val isLast = idx == entries.lastIndex
                    val connector = if (isLast) "└── " else "├── "
                    val subPrefix = if (isLast) "$prefix    " else "$prefix│   "

                    if (entry.isDirectory) {
                        totalDirs++
                        lines.add("$prefix$connector📁 ${entry.name}/")
                        count++
                        walk(entry, depth + 1, subPrefix)
                    } else if (showFiles) {
                        if (filterRegex != null && !filterRegex.matches(entry.name)) continue
                        totalFiles++
                        totalSize += entry.length()
                        val sizeStr = when {
                            entry.length() < 1024 -> "${entry.length()}B"
                            entry.length() < 1024 * 1024 -> String.format("%.1fKB", entry.length() / 1024.0)
                            else -> String.format("%.1fMB", entry.length() / (1024.0 * 1024.0))
                        }
                        lines.add("$prefix$connector📄 ${entry.name} ($sizeStr)")
                        count++
                    }
                }
            }

            lines.add("📁 ${root.name}/")
            walk(root, 1, "")

            if (!showFiles) {
                val dirsOnly = lines.filter { it.contains("📁") }
                lines.clear()
                lines.addAll(dirsOnly)
            }

            val summary = buildString {
                appendLine("📊 项目概览: ${root.absolutePath}")
                appendLine("  目录: $totalDirs | 文件: $totalFiles | 总大小: ${formatSize(totalSize)}")
                if (filter.isNotBlank()) appendLine("  过滤: $filter")
                appendLine()
            }

            (summary + lines.joinToString("\n")).trimEnd()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "扫描失败: ${e.message ?: "未知错误"}" }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1fKB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}