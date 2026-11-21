package com.wy.openx.tools

import com.wy.openx.data.util.AtomicFileWriter
import com.wy.openx.remote.RemoteFileBridge
import com.wy.openx.remote.RemoteWorkspaceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 追加写：把 [content] 追加到文件末尾（不存在则创建）。
 *
 * ## 为什么需要它（大文件写入的唯一可靠姿势）
 * `writeFile` 的内容是**模型输出的一部分**。一次要吐的字节越多，越容易撞上模型的输出 token 上限
 * （本项目默认 `max_tokens` 见 [com.wy.openx.data.model.AppSettings.maxTokens]）而被**截断在 JSON 中途**。
 * 截断后 `arguments` 是残缺 JSON，即便宽容修复也只能得到**半截内容**——静默写出残缺文件，比报错更糟。
 *
 * 所以长文件必须**分多次调用**：首次 `writeFile` 写开头，之后用本工具追加。
 * 单次控制在几十行/几 KB，与模型的单次输出能力匹配。
 *
 * ## 与 writeFile 的分工
 * - `writeFile`：**覆盖**整个文件（或新建）。适合短文件、或明确要整体替换。
 * - `appendFile`：**追加**到末尾。适合长文件分块写、写日志/清单、往文件尾部续内容。
 *
 * 追加不做「旧→新」LCS 差异（那需要读全文件，大文件下纯浪费），直接返回本次追加的行数/字节数
 * 与追加位置的上下文，够 AI 确认即可。
 */
/** 读取旧内容做拼接的上限：超过就直接追加，不把大文件拉进内存。 */
private const val MAX_APPEND_READ_BYTES = 8L * 1024 * 1024

class AppendFileTool : AgentTool {

    override val name = "appendFile"

    override val description =
        "把内容**追加**到文件末尾（文件不存在则自动创建）。用于：长文件分多次写入（首次用 writeFile 写开头，" +
            "之后用本工具逐块追加）、往文件尾部续写日志/清单。若目标是整体覆盖文件，请用 writeFile；" +
            "局部修改请用 editFile。\n" +
            "⚠ 单次 content 不要太大（建议 ≤ 200 行 / 8KB）：模型单次输出 token 有限，" +
            "内容越长越可能被截断成残缺 JSON。长文件请拆成多次 appendFile 调用。"

    override val parameters = buildToolParameters(
        ToolParameter("path", ParameterType.STRING, "文件路径（相对于项目目录或绝对路径）"),
        ToolParameter("content", ParameterType.STRING, "要追加的内容（原样写到文件末尾，不自动加换行）"),
        ToolParameter(
            "ensure_newline", ParameterType.BOOLEAN,
            "追加前若文件末尾不是换行，是否先补一个换行。默认 true（避免与上一块内容粘连）。",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val path = (args["path"] as? String)?.trim() ?: return "错误：未指定文件路径"
        val content = args["content"] as? String ?: ""
        val ensureNewline = (args["ensure_newline"] as? Boolean) ?: true

        if (content.isEmpty()) return "错误：content 为空，无内容可追加"

        if (RemoteWorkspaceRegistry.isRemote(path)) {
            return appendRemote(path, content, ensureNewline)
        }

        val dest = resolveFile(path) ?: return "错误：路径不可用或被安全策略拒绝「$path」"

        return withContext(Dispatchers.IO) {
            try {
                if (dest.isDirectory) return@withContext "错误：「${dest.absolutePath}」是一个目录"

                val existed = dest.exists()
                val oldLength = if (existed) dest.length() else 0L
                // 读旧内容仅用于「是否补换行」判断（不整文件读入做差异，大文件下会爆内存）。
                val needNewline = ensureNewline && existed && oldLength > 0 && !endsWithNewline(dest)

                dest.parentFile?.mkdirs()
                // 追加写：AtomicFileWriter 只支持整体覆盖，故这里直接 append。
                dest.appendText(buildString {
                    if (needNewline) append('\n')
                    append(content)
                })

                val addedLines = content.lines().size
                val newLength = dest.length()
                buildString {
                    appendLine("{\"status\":\"appended\",\"path\":\"${jsonEscape(dest.absolutePath)}\",\"created\":${!existed},")
                    appendLine("\"appended_lines\":$addedLines,\"appended_bytes\":${content.length},")
                    appendLine("\"file_bytes\":$newLength,\"inserted_newline_before\":$needNewline,")
                    append("}")
                }.trimEnd()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "追加失败: ${e.message ?: "未知错误"}"
            }
        }
    }

    /** 文件末尾是否为换行（只读最后 1 字节，避免大文件整读）。 */
    private fun endsWithNewline(file: File): Boolean = runCatching {
        val len = file.length()
        if (len <= 0) return@runCatching false
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - 1)
            val b = raf.read()
            b == '\n'.code || b == '\r'.code
        }
    }.getOrDefault(false)

    /** 与 [WriteFileTool] 同口径的路径解析：绝对路径走安全校验，相对路径挂到应用目录。 */
    /**
     * 「服务器为准」工作区里的追加。
     *
     * SFTP 没有 append 模式，只能「读旧 + 拼接 + 整写回」。因此大文件不读旧内容直接追加——
     * 代价是不再补换行（不读就不知道末尾有没有换行），这比把整个大文件拉进内存好。
     */
    private suspend fun appendRemote(
        path: String,
        content: String,
        ensureNewline: Boolean,
    ): String = withContext(Dispatchers.IO) {
        try {
            val bridge = RemoteFileBridge()
            val stat = bridge.stat(path)
            if (stat?.isDirectory == true) return@withContext "错误：「$path」是一个目录"

            val existed = stat != null
            val readable = existed && stat!!.size <= MAX_APPEND_READ_BYTES
            val old = if (readable) {
                runCatching { bridge.readText(path, MAX_APPEND_READ_BYTES) }.getOrDefault("")
            } else ""
            val needNewline = ensureNewline && readable && old.isNotEmpty() && !old.endsWith("\n")

            val merged = buildString {
                append(old)
                if (needNewline) append('\n')
                append(content)
            }
            bridge.writeText(path, merged, overwrite = true)

            buildString {
                appendLine("{\"status\":\"appended\",\"path\":\"${jsonEscape(path)}\",\"created\":${!existed},")
                appendLine("\"appended_lines\":${content.lines().size},\"appended_bytes\":${content.length},")
                appendLine("\"file_bytes\":${merged.toByteArray(Charsets.UTF_8).size},\"inserted_newline_before\":$needNewline,")
                append("}")
            }.trimEnd()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "追加失败: ${e.message ?: "未知错误"}" }
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