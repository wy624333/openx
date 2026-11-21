package com.wy.openx.tools

import com.wy.openx.container.Workspace
import com.wy.openx.remote.RemoteFileBridge
import com.wy.openx.remote.RemoteWorkspaceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SearchCodeTool : AgentTool {
    override val name = "search_code"
    override val description = "在项目文件内容中搜索文本或正则表达式，返回匹配的文件路径、行号及上下文。"
    override val parameters = buildToolParameters(
        ToolParameter("query", ParameterType.STRING, "搜索内容，支持正则表达式（如 `fun\\s+\\w+`）"),
        ToolParameter("dir", ParameterType.STRING, "搜索目录，不传则使用项目根目录", required = false),
        ToolParameter("glob", ParameterType.STRING, "文件过滤 glob，如 `*.kt` 或 `src/**/*.ts`。不传搜索所有文件。", required = false),
        ToolParameter("max_results", ParameterType.INTEGER, "最多返回多少个匹配结果（默认 30）", required = false),
        ToolParameter("context", ParameterType.INTEGER, "匹配行上下各显示几行上下文（默认 2）", required = false),
        ToolParameter("ignore_case", ParameterType.BOOLEAN, "是否忽略大小写（默认 false）", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val query = (args["query"] as? String)?.trim() ?: return "错误：未提供搜索内容"
        val dirStr = (args["dir"] as? String)?.trim()
        val glob = (args["glob"] as? String)?.trim() ?: ""
        val maxResults = ((args["max_results"] as? Double)?.toInt()) ?: 30
        val contextLines = ((args["context"] as? Double)?.toInt()) ?: 2
        val ignoreCase = (args["ignore_case"] as? Boolean) ?: false

        // 容器口径的搜索根：远程分支必须用容器路径，本地分支再转成宿主 File
        val containerRoot = if (!dirStr.isNullOrBlank()) dirStr else Workspace.CONTAINER_PATH
        if (RemoteWorkspaceRegistry.isRemote(containerRoot)) {
            return searchRemote(containerRoot, query, glob, maxResults, contextLines, ignoreCase)
        }

        val rootDir = if (!dirStr.isNullOrBlank()) {
            AppContextHolder.containerEngine?.toHostFileIfContainerPath(dirStr) ?: File(dirStr)
        } else {
            val ctx = AppContextHolder.context ?: return "错误：应用上下文未初始化"
            ctx.getExternalFilesDir(null)?.parentFile ?: return "错误：无法获取项目目录"
        }
        if (!rootDir.exists()) return "错误：目录「${rootDir.absolutePath}」不存在"

        return try {
            val regex = try {
                Regex(query, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            } catch (_: Exception) {
                return "错误：正则表达式无效「$query」"
            }

            val globRegex = if (glob.isNotBlank()) {
                try {
                    val g = glob.replace(".", "\\.").replace("**/", "(.*/)?")
                        .replace("*", "[^/]*").replace("?", "[^/]")
                    Regex("^$g$", RegexOption.IGNORE_CASE)
                } catch (_: Exception) { null }
            } else null

            val results = mutableListOf<SearchMatch>()
            var count = 0

            rootDir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") && it.length() < 5_000_000 }
                .forEach { file ->
                    if (count >= maxResults) return@forEach
                    if (globRegex != null && !globRegex.matches(file.name) && !globRegex.matches(
                            file.relativeTo(rootDir).path
                        )
                    ) return@forEach

                    try {
                        val lines = file.readLines()
                        for ((i, line) in lines.withIndex()) {
                            if (count >= maxResults) break
                            if (regex.containsMatchIn(line)) {
                                val start = maxOf(0, i - contextLines)
                                val end = minOf(lines.size - 1, i + contextLines)
                                val snippet = (start..end).map { idx ->
                                    val prefix = if (idx == i) ">" else " "
                                    "$prefix ${idx + 1}: ${lines[idx]}"
                                }.joinToString("\n")
                                results.add(
                                    SearchMatch(
                                        path = file.relativeTo(rootDir).path,
                                        absPath = file.absolutePath,
                                        line = i + 1,
                                        snippet = snippet
                                    )
                                )
                                count++
                            }
                        }
                    } catch (_: Exception) {}
                }

            if (results.isEmpty()) return "在 ${rootDir.absolutePath} 中未找到匹配「$query」"

            buildString {
                appendLine("🔍 搜索「$query」")
                appendLine("   目录: ${rootDir.absolutePath}")
                if (glob.isNotBlank()) appendLine("   过滤: $glob")
                appendLine("   结果: ${results.size} 个匹配")
                appendLine()

                for ((i, r) in results.withIndex()) {
                    appendLine("--- ${i + 1}. ${r.path}:${r.line} ---")
                    appendLine(r.snippet)
                    appendLine()
                }

                if (results.size >= maxResults) {
                    appendLine("... 仅显示前 $maxResults 个结果（可能还有更多）")
                }
            }.trimEnd()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "搜索失败: ${e.message ?: "未知错误"}" }
    }

    /**
     * 「服务器为准」工作区里的搜索：文件枚举与内容都来自服务器。
     *
     * 匹配/上下文/max_results 的行为与本地分支一致，只是路径统一用容器口径，
     * 以免模型拿到远端绝对路径后再传回那些路径给其它工具（那些路径不在工作区内）。
     */
    private suspend fun searchRemote(
        rootPath: String,
        query: String,
        glob: String,
        maxResults: Int,
        contextLines: Int,
        ignoreCase: Boolean,
    ): String = withContext(Dispatchers.IO) {
        try {
            val regex = try {
                Regex(query, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            } catch (_: Exception) {
                return@withContext "错误：正则表达式无效「$query」"
            }
            val globRegex = if (glob.isNotBlank()) {
                try {
                    val g = glob.replace(".", "\\.").replace("**/", "(.*/)?")
                        .replace("*", "[^/]*").replace("?", "[^/]")
                    Regex("^$g$", RegexOption.IGNORE_CASE)
                } catch (_: Exception) { null }
            } else null

            val bridge = RemoteFileBridge()
            val results = mutableListOf<SearchMatch>()
            var count = 0

            for (path in bridge.walkFiles(rootPath)) {
                if (count >= maxResults) break
                val name = path.substringAfterLast('/')
                val relative = path.removePrefix(rootPath).trimStart('/')
                if (globRegex != null && !globRegex.matches(name) && !globRegex.matches(relative)) continue

                // 单个文件读失败（超大/无权限/二进制）跳过，与本地分支的 per-file catch 同义
                val lines = runCatching { bridge.readText(path, MAX_SEARCH_FILE_BYTES) }
                    .getOrNull()?.lines() ?: continue
                for ((i, line) in lines.withIndex()) {
                    if (count >= maxResults) break
                    if (regex.containsMatchIn(line)) {
                        val start = maxOf(0, i - contextLines)
                        val end = minOf(lines.size - 1, i + contextLines)
                        val snippet = (start..end).map { idx ->
                            val prefix = if (idx == i) ">" else " "
                            "$prefix ${idx + 1}: ${lines[idx]}"
                        }.joinToString("\n")
                        results.add(
                            SearchMatch(path = relative, absPath = path, line = i + 1, snippet = snippet)
                        )
                        count++
                    }
                }
            }

            if (results.isEmpty()) return@withContext "在 $rootPath 中未找到匹配「$query」"

            buildString {
                appendLine("🔍 搜索「$query」")
                appendLine("   目录: $rootPath")
                if (glob.isNotBlank()) appendLine("   过滤: $glob")
                appendLine("   结果: ${results.size} 个匹配")
                appendLine()
                for ((i, r) in results.withIndex()) {
                    appendLine("--- ${i + 1}. ${r.path}:${r.line} ---")
                    appendLine(r.snippet)
                    appendLine()
                }
                if (results.size >= maxResults) {
                    appendLine("... 仅显示前 $maxResults 个结果（可能还有更多）")
                }
            }.trimEnd()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "搜索失败: ${e.message ?: "未知错误"}" }
    }

    private companion object {
        /** 单个文件的读取上限：与本地分支 walkTopDown 的 5MB 阀值保持一致。 */
        const val MAX_SEARCH_FILE_BYTES = 5_000_000L
    }

    private data class SearchMatch(
        val path: String,
        val absPath: String,
        val line: Int,
        val snippet: String
    )
}