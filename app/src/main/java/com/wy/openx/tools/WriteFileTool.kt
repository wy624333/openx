package com.wy.openx.tools

import com.wy.openx.data.util.AtomicFileWriter
import com.wy.openx.remote.RemoteFileBridge
import com.wy.openx.remote.RemoteWorkspaceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * writeFile。写入完整文件内容，自动创建父目录。
 * 结果返回结构化 JSON（path / added_lines / removed_lines / hunks[].diff），
 * UI 据此渲染彩色 diff 预览（与 editFile 同构）。
 */
class WriteFileTool : AgentTool {
    override val name = "writeFile"
    override val description =
        "向指定路径写入完整文件内容（覆盖整个文件，不存在则创建）。局部修改推荐使用 editFile。\n" +
            "⚠ 大文件必须分块：content 是模型输出的一部分，一次写太长会撞上输出 token 上限，" +
            "导致参数被截断成残缺 JSON、写出半截文件。**超过约 200 行 / 8KB 时**请改用：" +
            "首次用 writeFile 写开头（约 100~200 行），其余分多次用 appendFile 追加。"
    override val parameters = buildToolParameters(
        ToolParameter("path", ParameterType.STRING, "文件路径（相对于项目目录或绝对路径）"),
        ToolParameter("content", ParameterType.STRING, "文件内容"),
        ToolParameter("overwrite", ParameterType.BOOLEAN, "目标已存在时是否覆盖。默认 true；设为 false 时若文件已存在则报错。", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val path = (args["path"] as? String)?.trim() ?: return "错误：未指定文件路径"
        val content = args["content"] as? String ?: ""
        val overwrite = (args["overwrite"] as? Boolean) ?: true

        if (RemoteWorkspaceRegistry.isRemote(path)) {
            return withContext(Dispatchers.IO) { writeRemote(path, content, overwrite) }
        }

        val dest = resolveFile(path) ?: return "错误：路径不可用或被安全策略拒绝「$path」"
        val existed = dest.exists()

        return withContext(Dispatchers.IO) {
            try {
                if (existed && !overwrite) {
                    "错误：文件已存在「${dest.absolutePath}」（overwrite=false）"
                } else {
                    // 写前留存旧内容，供生成「旧→新」差异（与 editFile 同构，UI 据此渲染彩色 diff）。
                    val oldContent = if (existed) runCatching { dest.readText() }.getOrDefault("") else ""
                    if (dest.isDirectory) "错误：「${dest.absolutePath}」是一个目录"
                    else {
                        dest.parentFile?.mkdirs()
                        AtomicFileWriter.writeAtomically(dest, content)

                        val diff = buildWriteDiff(existed, oldContent, content)
                        val added = diff.lines().count { it.startsWith("+") }
                        val removed = diff.lines().count { it.startsWith("-") }
                        val hunksJson = listOf(
                            mapOf(
                                "start_line" to 1,
                                "added" to added,
                                "removed" to removed,
                                "diff" to diff
                            )
                        )
                        buildString {
                            appendLine("{\"status\":\"written\",\"path\":\"${jsonEscape(dest.absolutePath)}\",\"created\":${!existed},\"bytes_written\":${content.length},\"lines_written\":${content.lines().size},\"added_lines\":$added,\"removed_lines\":$removed,\"hunks\":[")
                            hunksJson.forEachIndexed { i, h ->
                                append(if (i > 0) "," else "")
                                append("{\"start_line\":${h["start_line"]},\"added\":${h["added"]},\"removed\":${h["removed"]},\"diff\":\"${jsonEscape(h["diff"] as String)}\"}")
                            }
                            appendLine("]}")
                        }.trimEnd()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { "写入失败: ${e.message ?: "未知错误"}" }
        }
    }

    /**
     * 「服务器为准」工作区里的写入：存在性/目录/旧内容/写回全部走 SFTP。
     *
     * 输出字段与本地分支保持一致（同样的 diff 与计数），只把路径换成本地调用方传的那份。
     */
    private suspend fun writeRemote(path: String, content: String, overwrite: Boolean): String {
        val bridge = RemoteFileBridge()
        return try {
            val stat = bridge.stat(path)
            when {
                stat != null && !overwrite -> "错误：文件已存在「$path」（overwrite=false）"
                stat?.isDirectory == true -> "错误：「$path」是一个目录"
                else -> {
                    val existed = stat != null
                    // 旧内容只为生成 diff；超限就不读，差异退化成「整篇新增」（与本地大文件同构）
                    val oldContent = if (existed && stat!!.size <= MAX_DIFF_SOURCE_BYTES) {
                        runCatching { bridge.readText(path, MAX_DIFF_SOURCE_BYTES) }.getOrDefault("")
                    } else ""
                    bridge.writeText(path, content, overwrite = true)

                    val diff = buildWriteDiff(existed, oldContent, content)
                    val added = diff.lines().count { it.startsWith("+") }
                    val removed = diff.lines().count { it.startsWith("-") }
                    buildString {
                        appendLine("{\"status\":\"written\",\"path\":\"${jsonEscape(path)}\",\"created\":${!existed},\"bytes_written\":${content.length},\"lines_written\":${content.lines().size},\"added_lines\":$added,\"removed_lines\":$removed,\"hunks\":[")
                        append("{\"start_line\":1,\"added\":$added,\"removed\":$removed,\"diff\":\"${jsonEscape(diff)}\"}")
                        appendLine("]}")
                    }.trimEnd()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "写入失败: ${e.message ?: "未知错误"}" }
    }

    /**
     * 构造 writeFile 的统一差异文本（每行以 `+`/`-`/` ` 起头）：
     * - 新建文件：旧内容视为空，整篇按「全部新增」呈现，不跑 LCS；
     * - 覆盖写且规模可控：计算旧→新的行级 LCS 差异；
     * - 覆盖写但任一侧行数超过 [MAX_DIFF_LINES]：跳过 O(n·m) 的 LCS，退化为整篇「全部新增」。
     */
    private fun buildWriteDiff(existed: Boolean, oldContent: String, newContent: String): String {
        val newLines = newContent.split("\n")
        if (!existed) return newLines.joinToString("\n") { "+$it" }
        val oldLines = oldContent.split("\n")
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES) {
            return newLines.joinToString("\n") { "+$it" }
        }
        return LineDiff.toUnified(oldContent, newContent)
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
        /** 旧/新任一侧行数超过此值即跳过 LCS：DP 表为 O(n·m)，过大会拖垮移动端内存。 */
        const val MAX_DIFF_LINES = 2000

        /** 读旧内容做 diff 的上限：超过这个量就不读，差异按整篇新增展示。 */
        const val MAX_DIFF_SOURCE_BYTES = 2L * 1024 * 1024
    }
}
