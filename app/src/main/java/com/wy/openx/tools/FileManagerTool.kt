package com.wy.openx.tools

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileManagerTool(private val context: Context) : AgentTool {

    override val name = "file_manager"
    override val description = """管理文件系统（容器工作区 / 容器系统文件 / 手机存储均可）。提供以下操作：
- list: 列出目录内容（不递归，仅当前目录下的文件和子目录）
- read: 读取文件内容，支持指定行数范围(maxLines)
- write: 写入文件，支持覆盖(overwrite)/追加(append)/安全新建(create_new)模式
- delete: 删除文件或目录（含路径安全检查）
- info: 查看文件或目录详细信息
- search: 按名称/通配符搜索文件（递归遍历所有子目录查找）
- analyze: 深度分析文件内容（代码语言/结构/词法/统计）
路径写法：容器工作区与系统文件用容器路径（如 /root/workspace/projects/<项目>/src/main.kt、~/.openx/mcp.json），
与 terminal 工具看到的是同一批文件；手机存储用 /sdcard/... 。"""
    override val parameters = buildToolParameters(
        ToolParameter("action", ParameterType.STRING, """操作类型说明:
list=查看目录下有什么（只显示当前目录，不递归）
read=读取文件内容
write=写入文件
delete=删除文件或目录
info=查看文件/目录详细信息
search=搜索文件（按名称/通配符递归查找）
analyze=深度分析文件""", enumValues = listOf("list", "read", "write", "delete", "info", "search", "analyze")),
        ToolParameter("path", ParameterType.STRING, "文件或目录的绝对路径（如 /sdcard/Download）。search 时作为搜索起始目录"),
        ToolParameter("query", ParameterType.STRING, "search 操作时的搜索关键词（文件名包含，不区分大小写。留空搜索全部）", required = false),
        ToolParameter("pattern", ParameterType.STRING, "search 操作时的文件名通配模式（如 *.kt, test*, *Service*）", required = false),
        ToolParameter("ext", ParameterType.STRING, "按扩展名过滤（如 kt, txt, json，不包含点）", required = false),
        ToolParameter("filterType", ParameterType.STRING, "list/search 时的类型过滤", required = false, enumValues = listOf("all", "file", "dir")),
        ToolParameter("sortBy", ParameterType.STRING, "list 时的排序字段", required = false, enumValues = listOf("name", "size", "date", "type")),
        ToolParameter("sortOrder", ParameterType.STRING, "list 时的排序方向", required = false, enumValues = listOf("asc", "desc")),
        ToolParameter("recursive", ParameterType.BOOLEAN, "list 时是否递归列出子目录", required = false),
        ToolParameter("maxDepth", ParameterType.INTEGER, "递归/搜索最大深度（默认 3）", required = false),
        ToolParameter("content", ParameterType.STRING, "write 时的文件内容", required = false),
        ToolParameter("mode", ParameterType.STRING, "write 模式：overwrite=覆盖已有, append=追加, create_new=仅新建（文件已存在则报错）", required = false, enumValues = listOf("overwrite", "append", "create_new")),
        ToolParameter("maxLines", ParameterType.INTEGER, "read 时最多读取行数（默认 200）", required = false),
        ToolParameter("startLine", ParameterType.INTEGER, "read 时的起始行号（从 1 开始）", required = false),
        ToolParameter("maxResults", ParameterType.INTEGER, "search 最大返回结果数", required = false),
        ToolParameter("minSize", ParameterType.STRING, "search 时文件大小下限（如 1KB, 1MB）", required = false),
        ToolParameter("maxSize", ParameterType.STRING, "search 时文件大小上限（如 100KB, 10MB）", required = false),
        ToolParameter("modifiedAfter", ParameterType.STRING, "search 时筛选在此日期后修改的文件（格式 yyyy-MM-dd）", required = false),
        ToolParameter("modifiedBefore", ParameterType.STRING, "search 时筛选在此日期前修改的文件（格式 yyyy-MM-dd）", required = false)
    )

    private val baseDir: File by lazy { context.filesDir }
    private val forbiddenPaths = listOf("/", "/system", "/data", "/proc", "/sys", "/etc", "/root", "/dev")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        try {
            val path = args["path"] as? String
            // 文件管理包含删除/重命名等不可逆操作，落在旧副本上比报错危险得多
            if (path != null && com.wy.openx.remote.RemoteWorkspaceRegistry.isRemote(path)) {
                return@withContext "错误：file_manager 不支持「服务器为准」的工作区（代码在服务器上）。" +
                    "请改用 remote_ssh 在服务器上执行对应命令。"
            }
            // action 兜底：模型有 60% 概率漏传（上游不校验 required），能猜就猜，
            // 猜不了就把可用值和它给的参数一起回显，让它能自我纠正而不是原地重试。
            val rawAction = args["action"] as? String
            val preResolved = path?.let { resolvePath(it) }
            val decision = FileManagerActionResolver.resolve(
                rawAction = rawAction,
                path = path,
                hasContent = args["content"] != null,
                pathExists = preResolved?.exists() == true,
                pathIsDirectory = preResolved?.isDirectory == true
            )
            val action = when (decision) {
                is FileManagerActionResolver.Result.Reject -> return@withContext error(decision.message)
                is FileManagerActionResolver.Result.Use -> decision.action
            }
            val actionNotice = FileManagerActionResolver.notice(action, decision.inferred, rawAction)
            val result = when (action) {
                "search" -> searchFiles(args)
                else -> {
                    if (path == null) return@withContext error("操作 '$action' 需要指定 path 参数")
                    val target = resolvePath(path)
                    if (target == null) return@withContext "错误：路径不存在: $path（请检查路径是否正确，或使用 /sdcard/ 前缀）"

                    val permissionError = checkStoragePermission(target)
                    if (permissionError != null) return@withContext permissionError

                    when (action) {
                        "list" -> listFiles(target, args)
                        "read" -> readFile(target, args)
                        "write" -> writeFile(target, args)
                        "delete" -> deleteFile(target)
                        "info" -> fileInfo(target)
                        "analyze" -> analyzeFile(target)
                        else -> error("不支持的操作 '$action'")
                    }
                }
            }
            if (actionNotice != null) "$actionNotice\n$result" else result
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            "文件操作出错: ${e.message ?: "未知错误"}"
        }
    }

    private fun resolvePath(path: String): File? {
        // 容器路径优先：AI 常用 terminal 视角的 `/root/workspace/...`、`~/...` 调本工具，
        // 需翻译成宿主真实文件（与终端作用于同一批文件）；非容器路径返回 null 再按宿主解析。
        val mapped = AppContextHolder.containerEngine?.toHostFileIfContainerPath(path)
        if (mapped != null) return mapped
        val file = File(path)
        if (file.exists()) return file
        if (path.startsWith("/storage/emulated/0/")) {
            val altPath = path.replace("/storage/emulated/0/", "/sdcard/")
            val alt = File(altPath)
            if (alt.exists()) return alt
        }
        if (path.startsWith("/sdcard/")) {
            val altPath = path.replace("/sdcard/", "/storage/emulated/0/")
            val alt = File(altPath)
            if (alt.exists()) return alt
        }
        if (!file.isAbsolute) {
            val rel = File(baseDir, path)
            if (rel.exists()) return rel
        }
        return if (!file.isAbsolute) File(baseDir, path) else file
    }

    private fun checkStoragePermission(file: File): String? {
        if (!file.exists()) return null
        if (file.canRead()) return null
        val storageDir = File(Environment.getExternalStorageDirectory().absolutePath)
        if (file.absolutePath.startsWith(storageDir.absolutePath)) {
            return "无法访问 ${file.absolutePath}，可能缺少存储权限。\n请确保已授予「文件和媒体」权限（MANAGE_EXTERNAL_STORAGE）。"
        }
        return null
    }

    private fun isPathSafe(file: File): String? {
        // 先 canonicalize：杜绝 ".." 与 symlink 绕过前缀白名单
        val canonical = try { file.canonicalFile } catch (_: Exception) { return "路径无法解析: ${file.absolutePath}" }
        val abs = canonical.absolutePath
        val base = try { baseDir.canonicalPath } catch (_: Exception) { baseDir.absolutePath }

        for (forbidden in forbiddenPaths) {
            if (abs == forbidden || abs.startsWith("$forbidden/")) {
                // 例外：应用自身私有目录位于 /data 下，必须放行（否则本工具对自身文件全部失效）
                if (forbidden == "/data" && abs.startsWith(base)) continue
                return "禁止操作系统关键路径: $forbidden"
            }
        }
        val storagePath = Environment.getExternalStorageDirectory().absolutePath
        if (!abs.startsWith(base) && !abs.startsWith(storagePath) && !abs.startsWith("/sdcard") && !abs.startsWith("/storage")) {
            return "路径不在可访问范围内: $abs"
        }
        return null
    }

    private fun listFiles(dir: File, args: Map<String, Any>): String {
        val resolved = resolvePath(dir.absolutePath) ?: return "错误：路径不存在: ${dir.absolutePath}"
        if (!resolved.exists()) return "错误：路径不存在: ${resolved.absolutePath}"
        if (!resolved.isDirectory) return "错误：不是目录: ${resolved.absolutePath}"
        isPathSafe(resolved)?.let { return "🛑 $it" }

        val recursive = (args["recursive"] as? Boolean) ?: false
        val maxDepth = ((args["maxDepth"] as? Double)?.toInt()) ?: 3
        val sortBy = (args["sortBy"] as? String) ?: "name"
        val sortOrder = (args["sortOrder"] as? String) ?: "asc"
        val filterType = (args["filterType"] as? String) ?: "all"
        val filterExt = args["ext"] as? String

        val children = resolved.listFiles() ?: return "无法访问此目录，可能缺少权限。"
        if (children.isEmpty()) return "📁 ${resolved.absolutePath}: 空目录"

        val sorted = sortFiles(children.toList(), sortBy, sortOrder)

        val sb = StringBuilder()
        var totalFiles = 0
        var totalDirs = 0
        var displayedCount = 0
        val MAX_DISPLAY = 120

        if (recursive && maxDepth > 0) {
            sb.appendLine("📁 ${resolved.absolutePath} (递归)")
            val result = walkDir(resolved, 0, maxDepth, sb, sortBy, sortOrder, filterType, filterExt, MAX_DISPLAY)
            totalFiles = result.first
            totalDirs = result.second
            displayedCount = result.third
        } else {
            sb.appendLine("📁 ${resolved.absolutePath}:")
            for (f in sorted) {
                val isDir = f.isDirectory
                if (filterType == "file" && isDir) continue
                if (filterType == "dir" && !isDir) continue
                if (filterExt != null && !isDir && !f.name.endsWith(".$filterExt", ignoreCase = true)) continue

                if (displayedCount >= MAX_DISPLAY) {
                    if (isDir) totalDirs++ else totalFiles++
                    continue
                }
                val icon = if (isDir) "📁" else "📄"
                val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                val date = dateFormat.format(Date(f.lastModified()))
                sb.appendLine("$icon ${f.name}$size  $date")
                if (isDir) totalDirs++ else totalFiles++
                displayedCount++
            }
        }
        sb.append("\n共 $totalDirs 个目录, $totalFiles 个文件")
        if (displayedCount < totalDirs + totalFiles) {
            sb.append("（仅显示前 $displayedCount 项，共 ${totalDirs + totalFiles} 项。可使用 search 或 filterType/ext 精确查找）")
        }
        sb.appendLine()
        if (recursive && filterExt != null) sb.appendLine("（仅显示 .$filterExt 文件）")
        return sb.toString()
    }

    private fun walkDir(
        dir: File, depth: Int, maxDepth: Int, sb: StringBuilder,
        sortBy: String, sortOrder: String, filterType: String, filterExt: String?,
        maxDisplay: Int = 120, displayed: Int = 0
    ): Triple<Int, Int, Int> {
        var files = 0; var dirs = 0; var shown = displayed
        val children = dir.listFiles() ?: return Triple(files, dirs, shown)
        val sorted = sortFiles(children.toList(), sortBy, sortOrder)
        val indent = "  ".repeat(depth + 1)
        for (f in sorted) {
            if (f.isDirectory) {
                if (filterType != "file") {
                    if (shown < maxDisplay) {
                        sb.appendLine("$indent📁 ${f.name}/")
                    }
                    dirs++
                    shown++
                }
                if (depth + 1 < maxDepth) {
                    val sub = walkDir(f, depth + 1, maxDepth, sb, sortBy, sortOrder, filterType, filterExt, maxDisplay, shown)
                    files += sub.first; dirs += sub.second; shown = sub.third
                }
            } else {
                if (filterType == "dir") continue
                if (filterExt != null && !f.name.endsWith(".$filterExt", ignoreCase = true)) continue
                if (shown < maxDisplay) {
                    sb.appendLine("$indent📄 ${f.name} (${formatSize(f.length())})")
                }
                files++
                shown++
            }
        }
        return Triple(files, dirs, shown)
    }

    private fun sortFiles(files: List<File>, sortBy: String, sortOrder: String): List<File> {
        val sorted = when (sortBy) {
            "size" -> files.sortedBy { if (it.isDirectory) 0L else it.length() }
            "date" -> files.sortedBy { it.lastModified() }
            "type" -> files.sortedBy { it.extension.lowercase() }
            else -> files.sortedBy { it.name.lowercase() }
        }
        return if (sortOrder == "desc") sorted.reversed() else sorted
    }

    private fun readFile(file: File, args: Map<String, Any>): String {
        val resolved = resolvePath(file.absolutePath) ?: return "错误：文件不存在: ${file.absolutePath}"
        if (!resolved.exists()) return "错误：文件不存在: ${resolved.absolutePath}"
        if (!resolved.isFile) return "错误：不是文件: ${resolved.absolutePath}"
        isPathSafe(resolved)?.let { return "🛑 $it" }
        if (!resolved.canRead()) {
            val permMsg = checkStoragePermission(resolved)
            if (permMsg != null) return permMsg
            return "错误：无读取权限: ${resolved.absolutePath}"
        }

        val maxSize = 50 * 1024 * 1024
        if (resolved.length() > maxSize) {
            return "错误：文件过大 (${formatSize(resolved.length())})，仅支持读取 50MB 以内的文件"
        }

        val maxLines = ((args["maxLines"] as? Double)?.toInt()) ?: 200
        val startLine = ((args["startLine"] as? Double)?.toInt()) ?: 1

        if (isBinaryFile(resolved)) {
            return "📄 ${resolved.name} (${formatSize(resolved.length())})\n---\n二进制文件，无法以文本方式读取。请使用 analyze 查看文件信息。"
        }

        try {
            var lineCount = 0
            var skippedLines = 0
            var totalLines = 0

            val sb = StringBuilder()
            // 流式读取：跳到 startLine 后取满 maxLines 即停，不再全量 toList() 入内存
            resolved.useLines { allLines ->
                for (line in allLines) {
                    totalLines++
                    if (totalLines > startLine - 1 && lineCount < maxLines) {
                        if (lineCount == 0) {
                            skippedLines = totalLines - 1
                            sb.appendLine("📄 ${resolved.name} (${formatSize(resolved.length())}):")
                            if (skippedLines > 0) sb.appendLine("（从第 $startLine 行开始，跳过了前 $skippedLines 行）")
                            sb.appendLine("---")
                        }
                        sb.appendLine(line)
                        lineCount++
                    }
                }
                if (lineCount == 0 && startLine > 1) {
                    return "错误：起始行 $startLine 超出文件总行数 $totalLines"
                }
                if (lineCount < maxLines && totalLines > skippedLines + lineCount) {
                    sb.appendLine("\n---\n（已到达文件末尾）")
                } else if (lineCount == maxLines && skippedLines + lineCount < totalLines) {
                    sb.appendLine("\n---\n（已截断，仅显示第 $startLine-${startLine + lineCount - 1} 行，剩余 ${totalLines - (skippedLines + lineCount)} 行未显示）")
                } else if (skippedLines > 0 || lineCount < totalLines) {
                    sb.appendLine("\n---\n（共 $totalLines 行）")
                } else { }
            }
            return sb.toString()
        } catch (e: Exception) {
            return "读取失败: ${e.message}"
        }
    }

    private fun writeFile(file: File, args: Map<String, Any>): String {
        val content = args["content"] as? String
        if (content == null) return "错误：写入操作需要提供 content 参数"

        val mode = (args["mode"] as? String) ?: "overwrite"
        val resolved = resolvePath(file.absolutePath) ?: file

        if (mode == "create_new" && resolved.exists()) {
            return "错误：文件已存在，无法新建: ${resolved.absolutePath}（如需覆盖请使用 mode=overwrite）"
        }
        if (mode == "overwrite" && resolved.exists()) {
            // 不再先删后写：直接原子替换，避免删除成功但写入失败导致原文件永久丢失
        }

        val safetyError = isPathSafe(resolved)
        if (safetyError != null) return safetyError

        return try {
            resolved.parentFile?.mkdirs()
            if (mode == "append" && resolved.exists()) {
                resolved.appendText(content, Charsets.UTF_8)
            } else {
                com.wy.openx.data.util.AtomicFileWriter.writeAtomically(resolved, content)
            }
            val modeLabel = when (mode) { "append" -> "追加"; "create_new" -> "新建"; else -> "写入" }
            "✅ 已$modeLabel ${resolved.absolutePath} (${formatSize(resolved.length())})"
        } catch (e: Exception) {
            "写入失败: ${e.message}"
        }
    }

    private suspend fun deleteFile(file: File): String {
        val resolved = resolvePath(file.absolutePath) ?: return "错误：文件不存在: ${file.absolutePath}"
        if (!resolved.exists()) return "错误：文件不存在: ${resolved.absolutePath}"

        val safetyError = isPathSafe(resolved)
        if (safetyError != null) return safetyError

        // 外部存储（/storage/emulated/*、/sdcard）删除：需弹窗人工确认后才执行。
        // 用户曾把备份/源码放在 /storage/emulated/0/.openx，符号链接跟随误删过整目录；
        // 现 APP 一律不自动删用户外部存储内容，由用户点「仍要执行」放行或「拒绝」拦下。
        if (ExternalStorageGuard.isExternalStorageHost(resolved)) {
            val requester = AppContextHolder.terminalConfirmRequester
            if (requester == null) {
                return ExternalStorageGuard.externalDeleteGuardError(resolved) ?: "删除被拦截"
            }
            val kind = if (resolved.isDirectory) "目录" else "文件"
            val approved = requester(
                "file_manager delete ${resolved.absolutePath}",
                "AI 请求删除外部存储（/storage/emulated 或 /sdcard）中的$kind：\n${resolved.absolutePath}"
            )
            if (!approved) return "🛑 外部存储删除已被用户拒绝，未执行。\n${resolved.absolutePath}"
        }

        val abs = try { resolved.canonicalPath.lowercase() } catch (_: Exception) { resolved.absolutePath.lowercase() }
        // 用规范化路径判断，/storage/emulated/0 与 /sdcard 双前缀都覆盖
        val onSdcard = abs.startsWith("/sdcard") || abs.startsWith("/storage/emulated")

        // 递归删非空目录必须人工确认。容器工作区在 /data/user/0/.../files/workspace/ 下，
        // 属于应用私有目录——删了用户在文件管理器里看不见、也找不回来，而上面那条
        // 外部存储守卫只覆盖 /sdcard，压根管不到这里。日志里就是这么静默清掉了一批目录。
        // 外部存储已在上一步单独确认过，这里用 !onSdcard 避免同一次删除弹两遍。
        if (resolved.isDirectory && !onSdcard) {
            val kids = resolved.listFiles()
            if (kids != null && kids.isNotEmpty()) {
                val preview = kids.take(8).joinToString("、") { it.name }
                val more = if (kids.size > 8) " …共 ${kids.size} 项" else ""
                val requester = AppContextHolder.terminalConfirmRequester
                if (requester == null) {
                    return "⚠️ 已阻止自动递归删除非空目录（${kids.size} 项：$preview$more）。" +
                        "请让用户确认后再删，或改用 terminal 逐项处理。\n${resolved.absolutePath}"
                }
                val approved = requester(
                    "file_manager delete ${resolved.absolutePath}",
                    "AI 请求递归删除非空目录（${kids.size} 项），不可恢复：\n${resolved.absolutePath}\n包含：$preview$more"
                )
                if (!approved) {
                    return "🛑 目录删除已被用户拒绝，未执行。\n${resolved.absolutePath}"
                }
            }
        }

        if (resolved.isDirectory && onSdcard) {
            val children = resolved.listFiles()
            if (children != null && children.size > 50) {
                return "⚠️ 目录过大（${children.size} 个项目），为防误删请确认后在应用内操作。建议逐个删除子文件。"
            }
        }

        return try {
            if (resolved.deleteRecursively()) "✅ 已删除: ${resolved.absolutePath}" else "错误：删除失败"
        } catch (e: Exception) {
            "删除失败: ${e.message}"
        }
    }

    private fun fileInfo(file: File): String {
        val resolved = resolvePath(file.absolutePath) ?: return "错误：路径不存在: ${file.absolutePath}"
        if (!resolved.exists()) return "错误：路径不存在: ${resolved.absolutePath}"
        isPathSafe(resolved)?.let { return "🛑 $it" }

        return buildString {
            val isDir = resolved.isDirectory
            appendLine("📋 ${resolved.absolutePath}")
            appendLine("  名称: ${resolved.name}")
            appendLine("  类型: ${if (isDir) "目录" else "文件"}")
            appendLine("  权限: ${permString(resolved)}")
            if (!isDir) {
                appendLine("  大小: ${formatSize(resolved.length())}")
                appendLine("  扩展名: ${resolved.extension.ifEmpty { "(无)" }}")
            }
            if (isDir) {
                val children = resolved.listFiles()
                val fileCount = children?.count { it.isFile } ?: 0
                val dirCount = children?.count { it.isDirectory } ?: 0
                appendLine("  内容: ${children?.size ?: 0} 个项目（$dirCount 目录, $fileCount 文件）")
            }
            appendLine("  最后修改: ${dateFormat.format(Date(resolved.lastModified()))}")
            val hidden = resolved.isHidden
            appendLine("  隐藏: $hidden")
            val canonical = try { resolved.canonicalPath } catch (e: Exception) { resolved.absolutePath }
            if (canonical != resolved.absolutePath) {
                appendLine("  真实路径: $canonical")
            }
        }
    }

    private fun searchFiles(args: Map<String, Any>): String {
        val path = args["path"] as? String ?: Environment.getExternalStorageDirectory().absolutePath
        val query = (args["query"] as? String)?.trim() ?: ""
        val pattern = (args["pattern"] as? String)?.trim() ?: ""
        val ext = (args["ext"] as? String)?.trim()?.lowercase() ?: ""
        val filterType = (args["filterType"] as? String) ?: "all"
        val maxResults = ((args["maxResults"] as? Double)?.toInt()) ?: 50
        val depth = ((args["depth"] as? Double)?.toInt()) ?: ((args["maxDepth"] as? Double)?.toInt()) ?: 5
        val minSizeStr = args["minSize"] as? String
        val maxSizeStr = args["maxSize"] as? String
        val modifiedAfterStr = args["modifiedAfter"] as? String
        val modifiedBeforeStr = args["modifiedBefore"] as? String

        val startDir = resolvePath(path) ?: return "错误：搜索起始目录不存在: $path"
        if (!startDir.exists() || !startDir.isDirectory) {
            return "错误：搜索起始目录不存在或不是目录: $path"
        }
        isPathSafe(startDir)?.let { return "🛑 $it" }

        val minSizeBytes = parseSize(minSizeStr)
        val maxSizeBytes = parseSize(maxSizeStr)
        val modifiedAfter = modifiedAfterStr?.let { parseDate(it) }
        val modifiedBefore = modifiedBeforeStr?.let { parseDate(it) }

        val results = mutableListOf<File>()
        val startTime = System.currentTimeMillis()

        searchRecursive(startDir, query, pattern, ext, filterType, depth, 0, results, maxResults)

        val elapsed = System.currentTimeMillis() - startTime

        if (results.isEmpty()) {
            val filters = mutableListOf<String>()
            if (query.isNotEmpty()) filters.add("名称包含「$query」")
            if (pattern.isNotEmpty()) filters.add("匹配模式「$pattern」")
            if (ext.isNotEmpty()) filters.add("扩展名「.$ext」")
            return "🔍 在 ${startDir.absolutePath} 中未找到${if (filters.isNotEmpty()) " ${filters.joinToString(", ")} 的" else ""}文件（耗时 ${elapsed}ms）"
        }

        val filteredResults = results.filter { file ->
            var ok = true
            if (minSizeBytes != null && file.length() < minSizeBytes) ok = false
            if (maxSizeBytes != null && file.length() > maxSizeBytes) ok = false
            if (modifiedAfter != null && file.lastModified() < modifiedAfter) ok = false
            if (modifiedBefore != null && file.lastModified() > modifiedBefore) ok = false
            ok
        }

        val sb = StringBuilder()
        sb.appendLine("🔍 搜索完成 (在 ${startDir.absolutePath} 中)")
        val filterParts = mutableListOf<String>()
        if (query.isNotEmpty()) filterParts.add("名称包含「$query」")
        if (pattern.isNotEmpty()) filterParts.add("模式「$pattern」")
        if (ext.isNotEmpty()) filterParts.add(".$ext")
        if (filterType == "dir") filterParts.add("仅目录")
        if (filterType == "file") filterParts.add("仅文件")
        if (filterParts.isNotEmpty()) sb.appendLine("   条件: ${filterParts.joinToString(", ")}")
        sb.appendLine("   耗时: ${elapsed}ms, 共 ${filteredResults.size} 个结果:")

        val displayResults = filteredResults.take(maxResults)
        for ((index, file) in displayResults.withIndex()) {
            val icon = if (file.isDirectory) "📁" else "📄"
            val relPath = try {
                file.absolutePath.removePrefix(startDir.absolutePath).trimStart('/')
            } catch (e: Exception) { file.name }
            val size = if (file.isFile) " (${formatSize(file.length())})" else ""
            val modDate = dateFormat.format(Date(file.lastModified()))
            sb.appendLine("${index + 1}. $icon $relPath$size  $modDate")
        }
        if (filteredResults.size > maxResults) {
            sb.appendLine("\n（仅显示前 $maxResults 个结果，共 ${filteredResults.size} 个。可缩小搜索范围以获取更精确结果）")
        }
        return sb.toString()
    }

    private fun searchRecursive(
        dir: File, query: String, pattern: String, ext: String,
        filterType: String, maxDepth: Int, depth: Int,
        results: MutableList<File>, maxResults: Int
    ) {
        if (depth > maxDepth || results.size >= maxResults) return
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (results.size >= maxResults) return
            val name = file.name
            var matches = true

            if (query.isNotEmpty() && !name.contains(query, ignoreCase = true)) matches = false
            if (pattern.isNotEmpty() && !name.matches(Glob.toRegex(pattern))) matches = false
            if (ext.isNotEmpty() && !file.isDirectory && !name.endsWith(".$ext", ignoreCase = true)) matches = false
            if (filterType == "file" && file.isDirectory) matches = false
            if (filterType == "dir" && !file.isDirectory) matches = false

            if (matches) results.add(file)
            if (file.isDirectory && depth < maxDepth) {
                searchRecursive(file, query, pattern, ext, filterType, maxDepth, depth + 1, results, maxResults)
            }
        }
    }

    private fun analyzeFile(file: File): String {
        val resolved = resolvePath(file.absolutePath) ?: return "错误：文件不存在: ${file.absolutePath}"
        if (!resolved.exists()) return "错误：文件不存在: ${resolved.absolutePath}"
        if (!resolved.isFile) return "错误：不是文件，请对目录使用 info 操作"

        return buildString {
            appendLine("📊 文件分析: ${resolved.name}")
            appendLine("  路径: ${resolved.absolutePath}")
            appendLine("  大小: ${formatSize(resolved.length())}")
            appendLine("  最后修改: ${dateFormat.format(Date(resolved.lastModified()))}")

            val ext = resolved.extension.lowercase()
            appendLine("  扩展名: .$ext")

            val isBin = isBinaryFile(resolved)
            if (isBin) {
                appendLine("  类型: 二进制文件")
                appendLine("  MIME 推测: ${guessMimeByExt(ext)}")
                return@buildString
            }

            appendLine("  类型: 文本文件")
            val content = try { resolved.readText(Charsets.UTF_8) } catch (e: Exception) { return@buildString }

            val lineCount = content.lines().size
            val charCount = content.length
            val wordCount = content.split(Regex("\\s+")).size
            val byteCount = content.toByteArray(Charsets.UTF_8).size
            appendLine("  统计: ${lineCount}行 / ${wordCount}词 / ${charCount}字符 / ${byteCount}字节")

            val nonEmptyLines = content.lines().count { it.isNotBlank() }
            val blankLines = lineCount - nonEmptyLines
            appendLine("  空行: $blankLines / 非空行: $nonEmptyLines")

            val lang = detectLanguage(resolved.name, content)
            appendLine("  识别语言: ${lang.first}")
            if (lang.second.isNotEmpty()) {
                appendLine("  语言特征: ${lang.second}")
            }

            when (ext) {
                "json" -> analyzeJson(content, this)
                "xml", "html", "htm", "xhtml" -> analyzeMarkup(content, ext, this)
                "csv", "tsv" -> analyzeCsv(content, ext, this)
                else -> {
                    if (lang.first != "未知") {
                        analyzeCode(content, this)
                    }
                }
            }

            val firstLines = content.lines().take(10).joinToString("\n")
            appendLine("\n  开头 10 行预览:\n${firstLines.take(500)}")
        }
    }

    private fun analyzeJson(content: String, sb: StringBuilder) {
        val trimmed = content.trim()
        val valid = try {
            com.google.gson.JsonParser.parseString(trimmed)
            true
        } catch (e: Exception) { false }
        sb.appendLine("  JSON 合法: $valid")
        if (valid && trimmed.length < 10000) {
            val depth = maxJsonDepth(trimmed, 0)
            sb.appendLine("  JSON 嵌套深度: $depth")
            val isArray = trimmed.startsWith("[")
            sb.appendLine("  根结构: ${if (isArray) "数组" else "对象"}")
        }
    }

    private fun maxJsonDepth(json: String, depth: Int): Int {
        if (depth > 50) return depth
        val trimmed = json.trim()
        if (trimmed.startsWith("{")) {
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.isEmpty()) return depth + 1
            val objDepth = inner.split(",").maxOfOrNull {
                maxJsonDepth(it.trim(), depth + 1)
            } ?: (depth + 1)
            return objDepth
        }
        if (trimmed.startsWith("[")) {
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.isEmpty()) return depth + 1
            val arrDepth = inner.split(",").maxOfOrNull {
                maxJsonDepth(it.trim(), depth + 1)
            } ?: (depth + 1)
            return arrDepth
        }
        return depth
    }

    private fun analyzeMarkup(content: String, ext: String, sb: StringBuilder) {
        val tagCount = Regex("<\\w+[\\s>]").findAll(content).count()
        val selfClosing = Regex("<\\w+[^>]*/>").findAll(content).count()
        val hasDocType = content.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)
        sb.appendLine("  标签数: $tagCount (自闭合: $selfClosing)")
        sb.appendLine("  含 DOCTYPE: $hasDocType")
    }

    private fun analyzeCsv(content: String, ext: String, sb: StringBuilder) {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        val delimiter = if (ext == "tsv") '\t' else ','
        val headerCount = lines.first().split(delimiter).size
        sb.appendLine("  数据行数: ${lines.size - 1}")
        sb.appendLine("  列数: $headerCount")
        sb.appendLine("  表头: ${lines.first().take(200)}")
    }

    private fun analyzeCode(content: String, sb: StringBuilder) {
        val lines = content.lines()
        val commentLines = lines.count { it.trimStart().startsWith("//") || it.trimStart().startsWith("#") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("*") }
        val codeLines = lines.count {
            val t = it.trim()
            t.isNotEmpty() && !t.startsWith("//") && !t.startsWith("#") && !t.startsWith("/*") && !t.startsWith("*")
        }
        sb.appendLine("  代码行: $codeLines / 注释行: $commentLines")

        val importLines = lines.count { it.trimStart().startsWith("import ") || it.trimStart().startsWith("using ") || it.trimStart().startsWith("#include") || it.trimStart().startsWith("package ") }
        if (importLines > 0) sb.appendLine("  导入/引用语句: $importLines")

        val classCount = Regex("""\b(class|interface|object|struct|enum|trait)\s+\w+""").findAll(content).count()
        val funcCount = Regex("""\b(fun|def|function|func|public|private|protected|export)\s+\w+\s*\(""").findAll(content).count()
        if (classCount > 0) sb.appendLine("  类/接口/结构体: $classCount")
        if (funcCount > 0) sb.appendLine("  函数/方法: $funcCount")
    }

    private fun detectLanguage(fileName: String, content: String): Pair<String, String> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val firstLines = content.lines().take(5).joinToString("\n")

        val langMap = mapOf(
            "kt" to ("Kotlin" to "Android 开发语言"),
            "java" to ("Java" to "面向对象编程语言"),
            "py" to ("Python" to "脚本语言"),
            "js" to ("JavaScript" to "Web 脚本语言"),
            "ts" to ("TypeScript" to "JavaScript 超集"),
            "go" to ("Go" to "编译型语言"),
            "rs" to ("Rust" to "系统编程语言"),
            "cpp" to ("C++" to "系统编程语言"),
            "c" to ("C" to "系统编程语言"),
            "h" to ("C/C++ 头文件" to ""),
            "swift" to ("Swift" to "Apple 开发语言"),
            "rb" to ("Ruby" to "脚本语言"),
            "php" to ("PHP" to "Web 后端语言"),
            "sh" to ("Shell" to "Shell 脚本"),
            "bash" to ("Bash" to "Bash 脚本"),
            "sql" to ("SQL" to "数据库查询语言"),
            "xml" to ("XML" to "标记语言"),
            "html" to ("HTML" to "网页标记语言"),
            "css" to ("CSS" to "样式表"),
            "json" to ("JSON" to "数据交换格式"),
            "yaml" to ("YAML" to "配置文件格式"),
            "yml" to ("YAML" to "配置文件格式"),
            "toml" to ("TOML" to "配置文件格式"),
            "md" to ("Markdown" to "文档标记语言"),
            "gradle" to ("Gradle" to "构建脚本"),
            "kts" to ("Kotlin Script" to "Gradle Kotlin DSL"),
            "properties" to ("Properties" to "Java 属性文件"),
            "txt" to ("纯文本" to "")
        )

        val match = langMap[ext]
        if (match != null) return match

        return when {
            firstLines.contains("package ") || firstLines.contains("import ") && firstLines.contains("fun ") -> "Kotlin" to ""
            firstLines.contains("public class") || firstLines.contains("import java.") -> "Java" to ""
            firstLines.contains("def ") || firstLines.contains("import ") && firstLines.contains(":") && !firstLines.contains("{") -> "Python" to ""
            firstLines.contains("function ") || firstLines.contains("const ") || firstLines.contains("let ") || firstLines.contains("var ") && firstLines.contains("=>") -> "JavaScript" to ""
            firstLines.contains("<!DOCTYPE html") || firstLines.contains("<html") -> "HTML" to ""
            firstLines.startsWith("#!") -> "Shell 脚本" to firstLines.substringAfter("#!").substringBefore("\n").trim()
            else -> "未知" to "无法自动识别编程语言"
        }
    }

    private fun isBinaryFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return true
        if (file.length() > 10 * 1024 * 1024) return true
        try {
            val stream = FileInputStream(file)
            stream.use { fis ->
                val header = ByteArray(1024)
                val bytesRead = fis.read(header, 0, minOf(1024, file.length().toInt()))
                if (bytesRead < 0) return true
                for (i in 0 until bytesRead) {
                    val b = header[i].toInt() and 0xFF
                    if (b == 0) return true
                }
            }
            return false
        } catch (e: Exception) { return true }
    }

    private fun guessMimeByExt(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "gz" -> "application/gzip"
        "tar" -> "application/x-tar"
        "apk" -> "application/vnd.android.package-archive"
        "dex" -> "application/octet-stream"
        "so" -> "application/x-sharedlib"
        "db" -> "application/octet-stream (SQLite)"
        else -> "application/octet-stream"
    }

    private fun parseSize(sizeStr: String?): Long? {
        if (sizeStr == null) return null
        return try {
            val s = sizeStr.trim().uppercase()
            when {
                s.endsWith("KB") -> (s.removeSuffix("KB").trim().toDouble() * 1024).toLong()
                s.endsWith("MB") -> (s.removeSuffix("MB").trim().toDouble() * 1024 * 1024).toLong()
                s.endsWith("GB") -> (s.removeSuffix("GB").trim().toDouble() * 1024 * 1024 * 1024).toLong()
                s.endsWith("B") -> s.removeSuffix("B").trim().toLong()
                else -> s.toLong()
            }
        } catch (e: Exception) { null }
    }

    private fun parseDate(dateStr: String): Long? {
        return try {
            dateOnlyFormat.parse(dateStr)?.time
        } catch (e: Exception) { null }
    }

    private fun permString(file: File): String {
        val r = if (file.canRead()) "r" else "-"
        val w = if (file.canWrite()) "w" else "-"
        return "$r$w"
    }

    private fun error(msg: String): String = "错误：$msg"

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private object Glob {
        fun toRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            for (c in pattern) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append(".")
                    '.', '(', ')', '+', '^', '$', '{', '}', '|', '\\' -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
