package com.wy.openx.tools

import com.wy.openx.data.util.AtomicFileWriter
import com.wy.openx.remote.RemoteFileBridge
import com.wy.openx.remote.RemoteWorkspaceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「精确字符串匹配」的文件编辑工具。
 * 一次调用可对同一个文件进行多处编辑：传入 edits 数组，按数组顺序依次应用，
 * 每个编辑都在前一个编辑的结果之上匹配。整批编辑是「全有或全无」的——只要有任何
 * 一个编辑匹配失败，整次调用都不写盘并报错，文件不会处于改了一半的状态。
 *
 * 返回结构化 JSON（path / added_lines / removed_lines / hunks[].diff），UI 据此渲染彩色 diff 预览。
 */
class EditFileTool : AgentTool {
    override val name = "editFile"
    override val description = "通过精确的字符串匹配替换修改已存在的文件内容。作为局部修改文件的首选工具。支持通过 edits 数组一次性应用多处修改，整批编辑是原子的——任一处匹配失败将整批回滚。整文件重写请用 writeFile。"

    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "要被替换的原文，需与文件当前内容精确匹配（含缩进和换行）。带足够上下文以保证唯一。"
            ),
            "new_string" to mapOf(
                "type" to "string",
                "description" to "替换后的新内容。传空字符串表示删除匹配到的内容。"
            ),
            "replace_all" to mapOf(
                "type" to "boolean",
                "description" to "是否替换该 old_string 的全部匹配项。默认 false（要求唯一匹配）。"
            )
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters = buildToolParameters(
        ToolParameter("path", ParameterType.STRING, "文件路径（相对于项目目录或绝对路径）"),
        ToolParameter("edits", ParameterType.ARRAY, "要应用的编辑列表，按顺序依次生效。每个元素：{old_string, new_string, replace_all?}。", itemsSchema = editItemSchema)
    )

    private data class Edit(val oldString: String, val newString: String, val replaceAll: Boolean)
    private data class Hunk(val startLine: Int, val added: Int, val removed: Int, val diff: String)

    override suspend fun execute(args: Map<String, Any>): String {
        val path = (args["path"] as? String)?.trim() ?: return "错误：未指定文件路径"
        val edits = parseEdits(args) ?: return "错误：edits 参数缺失或为空：请在 edits 数组里给出至少一个 {old_string,new_string} 编辑"

        val remote = RemoteWorkspaceRegistry.isRemote(path)
        val file = if (remote) {
            // 远端文件先取到本地缓存。这样整段替换/校验/diff 都在缓存副本上跑，
            // 与本地走同一条代码路径；改完再把结果写回服务器。
            runCatching { RemoteFileBridge().materialize(path) }.getOrElse {
                return "错误：读取远端文件失败「$path」：${it.message ?: "未知错误"}"
            }
        } else {
            resolveFile(path) ?: return "错误：路径不可用或被安全策略拒绝「$path」"
        }
        // 输出路径用调用方传的那一个：缓存路径对它没意义
        val displayPath = if (remote) path else file.absolutePath
        if (!file.exists()) return "错误：文件不存在「$displayPath」"
        if (!file.isFile) return "错误：「$displayPath」不是一个文件"

        // 逐个校验编辑本身的合法性（空 old_string / 无变化），避免无意义改动。
        edits.forEachIndexed { i, e ->
            if (e.oldString.isEmpty()) return "错误：第 ${i + 1} 个编辑的 old_string 不能为空；创建文件请用 writeFile。"
            if (e.oldString == e.newString) return "错误：第 ${i + 1} 个编辑的 old_string 与 new_string 相同，无需修改"
        }

        return withContext(Dispatchers.IO) {
            try {
                var content = file.readText()
                val hunks = ArrayList<Hunk>(edits.size)
                var totalReplacements = 0

                edits.forEachIndexed { i, e ->
                    val occurrences = content.split(e.oldString).size - 1
                    if (occurrences == 0) {
                        return@withContext "错误：第 ${i + 1} 个编辑未在文件中找到 old_string，请确认内容（含缩进/换行）与当前文件完全一致"
                    }
                    if (occurrences > 1 && !e.replaceAll) {
                        return@withContext "错误：第 ${i + 1} 个编辑的 old_string 在文件中匹配到 $occurrences 处，请提供更长的唯一上下文，或对该编辑设置 replace_all=true"
                    }

                    val matchIndex = content.indexOf(e.oldString)
                    val startLine = if (matchIndex >= 0) content.substring(0, matchIndex).count { it == '\n' } + 1 else 1

                    val diff = LineDiff.toUnified(e.oldString, e.newString)
                    val added = diff.lines().count { it.startsWith("+") }
                    val removed = diff.lines().count { it.startsWith("-") }
                    hunks.add(Hunk(startLine = startLine, added = added, removed = removed, diff = diff))

                    content = if (e.replaceAll) content.replace(e.oldString, e.newString)
                    else content.replaceFirst(e.oldString, e.newString)
                    totalReplacements += if (e.replaceAll) occurrences else 1
                }

                AtomicFileWriter.writeAtomically(file, content)

                // 上面写的是本地缓存副本，必须把结果同步回服务器才算真的改完；
                // 写回失败要报错，不能只当本地成功而静默弄丢改动。
                if (remote) {
                    runCatching { RemoteFileBridge().writeText(path, content, overwrite = true) }
                        .onFailure {
                            return@withContext "错误：写回远端失败「$path」：${it.message ?: "未知错误"}"
                        }
                }

                val addedTotal = hunks.sumOf { it.added }
                val removedTotal = hunks.sumOf { it.removed }

                buildString {
                    appendLine("{\"status\":\"edited\",\"path\":\"${jsonEscape(displayPath)}\",\"edits_count\":${edits.size},\"replacements\":$totalReplacements,\"total_lines\":${content.lines().size},\"added_lines\":$addedTotal,\"removed_lines\":$removedTotal,\"hunks\":[")
                    hunks.forEachIndexed { i, h ->
                        append(if (i > 0) "," else "")
                        append("{\"start_line\":${h.startLine},\"added\":${h.added},\"removed\":${h.removed},\"diff\":\"${jsonEscape(h.diff)}\"}")
                    }
                    appendLine("]}")
                }.trimEnd()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { "编辑失败: ${e.message ?: "未知错误"}" }
        }
    }

    private fun parseEdits(args: Map<String, Any>): List<Edit>? {
        val arr = args["edits"] as? List<*> ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? Map<*, *> ?: return@mapNotNull null
            val old = obj["old_string"] as? String ?: return@mapNotNull null
            val new = obj["new_string"] as? String ?: ""
            val all = obj["replace_all"] as? Boolean ?: false
            Edit(old, new, all)
        }.takeIf { it.isNotEmpty() }
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
}