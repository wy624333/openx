package com.wy.openx.tools

import com.wy.openx.remote.RemoteFileBridge
import com.wy.openx.remote.RemoteWorkspaceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * readFile。读取指定路径的文件内容，支持 start_line/end_line 分段读取。
 * 逐行流式读取避免 readText() 整篇进内存导致移动端 OOM，单次最多 2000 行 / 200KB，
 * 超限截断并提示用 start_line 续读。返回结构化 JSON（content/total_lines/start_line/end_line/read_lines/truncated/note）。
 */
class ReadFileTool : AgentTool {
    override val name = "readFile"
    override val description = "读取指定路径的文件内容。支持工作区文件或绝对路径的系统文件。单次读取受文件大小限制，超大文件可通过 start_line/end_line 分段读取。"
    override val parameters = buildToolParameters(
        ToolParameter("path", ParameterType.STRING, "文件路径（相对于项目目录或绝对路径）"),
        ToolParameter("start_line", ParameterType.INTEGER, "开始行号（从 1 计）", required = false),
        ToolParameter("end_line", ParameterType.INTEGER, "结束行号；与 start_line 的跨度最多 2000 行，超出按 2000 行截断", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val path = (args["path"] as? String)?.trim() ?: return "错误：未指定文件路径"
        // 「服务器为准」的工作区：先把远端文件取到本地缓存再读。
        // 这样分段/截断逻辑与本地走同一条代码路径，两边不会漂。
        val remote = RemoteWorkspaceRegistry.isRemote(path)
        val file = if (remote) {
            runCatching { RemoteFileBridge().materialize(path) }.getOrElse {
                return "错误：读取远端文件失败「$path」：${it.message ?: "未知错误"}"
            }
        } else {
            resolveFile(path) ?: return "错误：路径不可用或被安全策略拒绝「$path」"
        }
        // 输出路径用调用方传的那一个：缓存路径对它毫无意义，还会误导后续调用
        val displayPath = if (remote) path else file.absolutePath

        return withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) "错误：文件不存在「$displayPath」"
                else if (!file.isFile) "错误：「$displayPath」不是一个文件"
                else {
                    val startLine = ((args["start_line"] as? Number)?.toInt()?.coerceAtLeast(1)) ?: 1
                    val requestedEnd = (args["end_line"] as? Number)?.toInt()
                    // 行窗口上界：显式 end_line 与 start_line+MAX_LINES 取较小值，缺省则按 MAX_LINES 上限。
                    val lineCap = startLine + MAX_LINES - 1
                    val endCap = if (requestedEnd != null) minOf(requestedEnd, lineCap) else lineCap

                    // 逐行流式读取，只保留 [startLine, endCap] 窗口，并在累计字节超过 MAX_BYTES 时提前停止。
                    val sb = StringBuilder()
                    var totalLines = 0
                    var emittedLines = 0
                    var byteCount = 0
                    var truncatedByBytes = false
                    var lineNo = 0
                    file.useLines { allLines ->
                        for (line in allLines) {
                            lineNo++
                            totalLines = lineNo
                            if (lineNo < startLine) continue
                            if (lineNo > endCap) continue
                            if (!truncatedByBytes) {
                                val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                                if (byteCount + lineBytes > MAX_BYTES && emittedLines > 0) {
                                    truncatedByBytes = true
                                } else {
                                    if (emittedLines > 0) sb.append('\n')
                                    sb.append(line)
                                    byteCount += lineBytes
                                    emittedLines++
                                }
                            }
                        }
                    }

                    val lastEmittedLine = startLine + emittedLines - 1
                    val wantedEnd = if (requestedEnd != null) minOf(requestedEnd, totalLines) else totalLines
                    val truncated = emittedLines > 0 && lastEmittedLine < wantedEnd
                    val note = when {
                        !truncated -> null
                        truncatedByBytes -> "已达 ${MAX_BYTES / 1024}KB 上限被截断；从第 ${lastEmittedLine + 1} 行起用 start_line 继续读取。"
                        else -> "已达 $MAX_LINES 行上限被截断；从第 ${lastEmittedLine + 1} 行起用 start_line 继续读取。"
                    }

                    buildString {
                        appendLine("{\"status\":\"read\",\"path\":\"${jsonEscape(displayPath)}\",\"content\":\"${jsonEscape(sb.toString())}\",\"total_lines\":$totalLines,\"start_line\":$startLine,\"end_line\":${maxOf(lastEmittedLine, startLine - 1)},\"read_lines\":$emittedLines,\"truncated\":$truncated${if (note != null) ",\"note\":\"${jsonEscape(note)}\"" else ""}}")
                    }.trimEnd()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { "读取失败: ${e.message ?: "未知错误"}" }
        }
    }

    private fun resolveFile(path: String): File? {
        if (File(path).isAbsolute) return ToolPaths.sanitize(path)
        val ctx = AppContextHolder.context ?: return null
        val base = ctx.getExternalFilesDir(null) ?: return null
        val candidate = File(base, path).takeIf { it.exists() }
            ?: ctx.getExternalFilesDir(null)?.parentFile?.let { File(it, path) }
            ?: return File(base, path)
        return ToolPaths.sanitize(candidate.absolutePath)
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private companion object {
        /** 单次最多返回的行数，超出截断并提示用 start_line 续读。 */
        const val MAX_LINES = 2000
        /** 单次最多返回的字节数（UTF-8，约 200KB），防止超大行撑爆内存/上下文。 */
        const val MAX_BYTES = 200 * 1024
    }
}