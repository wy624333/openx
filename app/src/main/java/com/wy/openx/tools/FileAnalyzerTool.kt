package com.wy.openx.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAnalyzerTool(private val context: Context) : AgentTool {

    override val name = "file_analyzer"
    override val description = "深度分析文件：检测编码、语言类型、代码结构、安全风险等。返回文件的详细结构分析而非原始内容。要查看文件内容请使用 file_manager 的 read 操作。"
    override val parameters = buildToolParameters(
        ToolParameter("path", ParameterType.STRING, "文件的绝对路径（如 /sdcard/Download/xxx.txt）"),
        ToolParameter("type", ParameterType.STRING, "分析类型：auto 自动检测（默认），code 代码分析，text 文本分析，json JSON 校验，xml XML 分析", required = false, enumValues = listOf("auto", "code", "text", "json", "xml"))
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        try {
            val path = args["path"] as? String ?: return@withContext "错误：未提供文件路径"
            if (com.wy.openx.remote.RemoteWorkspaceRegistry.isRemote(path)) {
                return@withContext "错误：file_analyzer 不支持「服务器为准」的工作区（代码在服务器上）。" +
                    "请先用 readFile 读内容，或改用 remote_ssh 在服务器上分析。"
            }
            val analysisType = (args["type"] as? String) ?: "auto"

            val file = resolvePath(path)
            if (file == null || !file.exists()) return@withContext "错误：文件不存在: $path"
            if (!file.isFile) return@withContext "错误：不是文件: $path"

            analyze(file, analysisType)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            "文件分析出错: ${e.message}"
        }
    }

    private fun resolvePath(path: String): File? {
        // 容器路径优先（与 terminal 视角一致）；非容器路径返回 null 再按宿主解析。
        val mapped = AppContextHolder.containerEngine?.toHostFileIfContainerPath(path)
        if (mapped != null) return mapped
        val file = File(path)
        if (file.exists()) return file
        if (path.startsWith("/storage/emulated/0/")) {
            val alt = File(path.replace("/storage/emulated/0/", "/sdcard/"))
            if (alt.exists()) return alt
        }
        if (!file.isAbsolute) {
            val rel = File(context.filesDir, path)
            if (rel.exists()) return rel
        }
        return if (!file.isAbsolute) File(context.filesDir, path) else file
    }

    private fun analyze(file: File, type: String): String {
        return buildString {
            appendLine("📊 文件分析: ${file.name}")
            appendLine("  路径: ${file.absolutePath}")
            appendLine("  大小: ${formatSize(file.length())}")
            appendLine("  修改: ${dateFormat.format(Date(file.lastModified()))}")
            appendLine("  权限: ${if (file.canRead()) "r" else "-"}${if (file.canWrite()) "w" else "-"}")

            if (isBinaryFile(file)) {
                appendLine("  类型: 二进制文件")
                appendLine("  推测 MIME: ${guessMimeByExt(file.extension)}")
                return@buildString
            }

            val content = try { file.readText(Charsets.UTF_8) } catch (e: Exception) {
                try { file.readText(Charsets.ISO_8859_1) } catch (e2: Exception) { null }
            }
            if (content == null) { appendLine("  类型: 二进制或不可读文件"); return@buildString }

            val detectedEncoding = detectEncoding(content)
            appendLine("  编码: $detectedEncoding")
            appendLine("  扩展名: .${file.extension}")

            val lineCount = content.lines().size
            val charCount = content.length
            val byteCount = content.toByteArray(Charsets.UTF_8).size
            val wordCount = content.split(Regex("\\s+")).size
            val nonEmptyLines = content.lines().count { it.isNotBlank() }
            val blankLines = lineCount - nonEmptyLines

            appendLine("  统计: ${lineCount}行 / ${wordCount}词 / ${charCount}字符 / ${byteCount}字节")
            appendLine("  空行: $blankLines / 非空: $nonEmptyLines")

            val analysis = when (type) {
                "json" -> analyzeJson(content)
                "xml" -> analyzeXml(content)
                "code" -> analyzeCode(file.name, content)
                "text" -> analyzeText(content)
                else -> autoAnalyze(file.name, content)
            }
            append(analysis)

            val lines = content.lines()
            val previewLines = lines.take(8)
            if (previewLines.isNotEmpty()) {
                appendLine("\n  ── 开头预览 ──")
                previewLines.forEach { appendLine("  $it") }
            }
            if (lines.size > 8) {
                val tailLines = lines.takeLast(5)
                appendLine("  ── 结尾预览 (5行) ──")
                tailLines.forEach { appendLine("  $it") }
            }
        }
    }

    private fun autoAnalyze(fileName: String, content: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "json" -> analyzeJson(content)
            "xml", "html", "htm", "xhtml", "svg" -> analyzeXml(content)
            "kt", "java", "py", "js", "ts", "go", "rs", "cpp", "c", "swift", "rb", "php", "scala" -> analyzeCode(fileName, content)
            "csv", "tsv" -> analyzeCsv(content, ext)
            "md" -> analyzeMarkdown(content)
            else -> {
                val lang = detectLanguage(fileName, content)
                if (lang.first != "未知" && lang.first != "纯文本") {
                    analyzeCode(fileName, content)
                } else {
                    analyzeText(content)
                }
            }
        }
    }

    private fun analyzeJson(content: String): String {
        val sb = StringBuilder()
        val trimmed = content.trim()
        val valid = try {
            com.google.gson.JsonParser.parseString(trimmed)
            true
        } catch (e: Exception) { false }
        sb.appendLine("  JSON 合法: $valid")

        if (!valid) {
            try {
                com.google.gson.JsonParser.parseString(trimmed)
            } catch (e: com.google.gson.JsonSyntaxException) {
                sb.appendLine("  JSON 错误: ${e.message?.take(200)}")
                val lineErrors = findJsonErrorLine(content, e)
                if (lineErrors > 0) sb.appendLine("  错误大致在第 $lineErrors 行附近")
            }
            return sb.toString()
        }

        try {
            val element = com.google.gson.JsonParser.parseString(trimmed)
            val depth = jsonDepth(element, 0)
            sb.appendLine("  嵌套深度: $depth")
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                sb.appendLine("  根结构: 对象 (${obj.keySet().size} 个键)")
                obj.keySet().forEach { key ->
                    val value = obj.get(key)
                    val valType = when {
                        value.isJsonObject -> "对象"
                        value.isJsonArray -> "数组(${value.asJsonArray.size()}项)"
                        value.isJsonNull -> "null"
                        else -> value.asJsonPrimitive.toString().take(50)
                    }
                    sb.appendLine("    - $key: $valType")
                }
            } else if (element.isJsonArray) {
                val arr = element.asJsonArray
                sb.appendLine("  根结构: 数组 (${arr.size()} 项)")
                if (arr.size() > 0) {
                    val first = arr[0]
                    val firstType = when {
                        first.isJsonObject -> "对象 (${first.asJsonObject.keySet().size} 个键)"
                        first.isJsonArray -> "数组"
                        else -> first.asJsonPrimitive.toString().take(30)
                    }
                    sb.appendLine("  首项类型: $firstType")
                }
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun jsonDepth(element: com.google.gson.JsonElement, depth: Int): Int {
        if (depth > 50) return depth
        return when {
            element.isJsonObject -> {
                element.asJsonObject.entrySet().maxOfOrNull { jsonDepth(it.value, depth + 1) } ?: (depth + 1)
            }
            element.isJsonArray -> {
                element.asJsonArray.maxOfOrNull { jsonDepth(it, depth + 1) } ?: (depth + 1)
            }
            else -> depth
        }
    }

    private fun findJsonErrorLine(content: String, error: com.google.gson.JsonSyntaxException): Int {
        val msg = error.message ?: return 0
        val lineMatch = Regex("""line (\d+)""").find(msg)
        if (lineMatch != null) return lineMatch.groupValues[1].toInt()
        val idxMatch = Regex("""at (\d+)""").find(msg)
        if (idxMatch != null) {
            val idx = idxMatch.groupValues[1].toInt()
            return content.substring(0, minOf(idx, content.length)).count { it == '\n' } + 1
        }
        return 0
    }

    private fun analyzeXml(content: String): String {
        val sb = StringBuilder()
        val tagCount = Regex("<\\w+[\\s>]").findAll(content).count()
        val selfClosing = Regex("<\\w+[^>]*/>").findAll(content).count()
        val hasDocType = content.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)
        val rootTag = Regex("<(\\w+)[\\s>]").find(content.trimStart())?.groupValues?.get(1) ?: "?"
        val attrCount = Regex("""\w+\s*=\s*["']""").findAll(content).count()
        sb.appendLine("  根标签: <$rootTag>")
        sb.appendLine("  标签总数: $tagCount (自闭合: $selfClosing)")
        sb.appendLine("  属性数: $attrCount")
        sb.appendLine("  含 DOCTYPE: $hasDocType")
        return sb.toString()
    }

    private fun analyzeCode(fileName: String, content: String): String {
        val sb = StringBuilder()
        val lines = content.lines()
        val lineCount = lines.size

        val lang = detectLanguage(fileName, content)
        sb.appendLine("  语言: ${lang.first} ${lang.second}")

        val commentLines = lines.count {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("#") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("<!--") || t.startsWith("///") || t.startsWith("//!")
        }
        val blankLines = lines.count { it.isBlank() }
        val codeLines = lineCount - commentLines - blankLines
        sb.appendLine("  代码行: $codeLines / 注释行: $commentLines / 空行: $blankLines")
        if (lineCount > 0) {
            sb.appendLine("  注释占比: ${"%.1f".format(commentLines * 100.0 / lineCount)}%")
        }

        val imports = Regex("""^(import |using |package |#include|require|from\s+\S+\s+import)""", RegexOption.MULTILINE).findAll(content).count()
        if (imports > 0) sb.appendLine("  导入/引用数: $imports")

        val classes = ArrayList<kotlin.text.MatchResult>(64)
        for (m in Regex("""\b(class|interface|object|struct|enum|trait|data class)\s+(\w+)""").findAll(content)) {
            classes.add(m)
            if (classes.size >= 1000) break
        }
        if (classes.isNotEmpty()) {
            sb.appendLine("  类型定义:")
            classes.take(100).forEach { sb.appendLine("    - ${it.groupValues[1]} ${it.groupValues[2]}") }
            if (classes.size > 100) sb.appendLine("    ... 还有 ${classes.size - 100} 个")
        }

        val functions = ArrayList<kotlin.text.MatchResult>(128)
        for (m in Regex("""\bfun\s+(\w+)\s*\(""").findAll(content)) {
            functions.add(m)
            if (functions.size >= 2000) break
        }
        if (functions.isEmpty()) {
            val altFns = ArrayList<kotlin.text.MatchResult>(64)
            for (m in Regex("""\b(def |function |func |public|private|protected|export\s+)?\n?\s*(\w+)\s*\([^)]*\)\s*(\{|:|\=)""").findAll(content)) {
                altFns.add(m)
                if (altFns.size >= 500) break
            }
            if (altFns.isNotEmpty() && altFns.size < 30) {
                sb.appendLine("  函数/方法: ${altFns.size}")
            }
        } else {
            sb.appendLine("  函数/方法:")
            functions.take(15).forEach { sb.appendLine("    - fun ${it.groupValues[1]}()") }
            if (functions.size > 15) sb.appendLine("    ... 还有 ${functions.size - 15} 个")
        }

        return sb.toString()
    }

    private fun analyzeText(content: String): String {
        val sb = StringBuilder()
        val lines = content.lines()
        val lineCount = lines.size
        val charCount = content.length
        val wordCount = content.split(Regex("\\s+")).size
        val avgLineLen = if (lineCount > 0) charCount / lineCount else 0
        val longLines = lines.count { it.length > 200 }

        sb.appendLine("  平均行长度: ${avgLineLen}字符")
        if (longLines > 0) sb.appendLine("  超长行(>200字符): $longLines 行")

        val chineseChars = content.count { it in '\u4e00'..'\u9fff' }
        val englishChars = content.count { it in 'a'..'z' || it in 'A'..'Z' }
        val digitChars = content.count { it in '0'..'9' }
        val punctChars = content.count { it in "，。、；：？！''（）【】《》—…·.,;:!?'\"()[]{}" }
        sb.appendLine("  字符组成: 中文${chineseChars} / 英文${englishChars} / 数字${digitChars} / 标点${punctChars}")

        val paragraphs = content.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }
        sb.appendLine("  段落数: $paragraphs")

        return sb.toString()
    }

    private fun analyzeCsv(content: String, ext: String): String {
        val sb = StringBuilder()
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return "  CSV: 空文件"
        val delimiter = if (ext == "tsv") '\t' else ','
        val headerCount = lines.first().split(delimiter).size
        sb.appendLine("  数据行数: ${lines.size - 1}")
        sb.appendLine("  列数: $headerCount")
        val headers = lines.first().split(delimiter).map { it.trim() }
        sb.appendLine("  表头:")
        headers.forEachIndexed { i, h -> sb.appendLine("    [${i + 1}] $h") }

        val columnTypes = mutableListOf<String>()
        for (col in 0 until headerCount) {
            val values = lines.drop(1).mapNotNull { it.split(delimiter).getOrNull(col)?.trim() }.filter { it.isNotEmpty() }
            val numCount = values.count { it.toDoubleOrNull() != null }
            columnTypes.add(if (numCount > values.size / 2) "数值" else "文本")
        }
        sb.appendLine("  推测类型: ${columnTypes.joinToString(", ")}")
        return sb.toString()
    }

    private fun analyzeMarkdown(content: String): String {
        val sb = StringBuilder()
        val lines = content.lines()
        val h1 = lines.count { it.trimStart().startsWith("# ") }
        val h2 = lines.count { it.trimStart().startsWith("## ") && !it.trimStart().startsWith("### ") }
        val h3plus = lines.count { Regex("^#{3,}\\s").containsMatchIn(it.trimStart()) }
        sb.appendLine("  标题: H1=$h1 / H2=$h2 / H3+=$h3plus")
        val codeBlocks = Regex("```").findAll(content).count() / 2
        if (codeBlocks > 0) sb.appendLine("  代码块: $codeBlocks")
        val links = Regex("""\[([^\]]+)\]\(([^)]+)\)""").findAll(content).count()
        if (links > 0) sb.appendLine("  链接数: $links")
        val listItems = lines.count { it.trimStart().matches(Regex("^[-*+]\\s|^\\d+\\.\\s")) }
        if (listItems > 0) sb.appendLine("  列表项: $listItems")
        return sb.toString()
    }

    private fun detectLanguage(fileName: String, content: String): Pair<String, String> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val firstLines = content.lines().take(5).joinToString("\n")

        val langMap = mapOf(
            "kt" to ("Kotlin" to "Android 开发语言"),
            "kts" to ("Kotlin Script" to "Gradle Kotlin DSL"),
            "java" to ("Java" to "面向对象编程语言"),
            "py" to ("Python" to "脚本语言"),
            "js" to ("JavaScript" to "Web 脚本语言"),
            "ts" to ("TypeScript" to "JavaScript 超集"),
            "jsx" to ("JSX" to "React 组件"),
            "tsx" to ("TSX" to "React TypeScript 组件"),
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
            "scss" to ("SCSS" to "CSS 预处理器"),
            "less" to ("Less" to "CSS 预处理器"),
            "json" to ("JSON" to "数据交换格式"),
            "yaml" to ("YAML" to "配置文件格式"),
            "yml" to ("YAML" to "配置文件格式"),
            "toml" to ("TOML" to "配置文件格式"),
            "md" to ("Markdown" to "文档标记语言"),
            "gradle" to ("Gradle" to "构建脚本"),
            "properties" to ("Properties" to "Java 属性文件"),
            "txt" to ("纯文本" to ""),
            "log" to ("日志文件" to ""),
            "csv" to ("CSV" to "表格数据"),
            "tsv" to ("TSV" to "表格数据"),
            "env" to ("环境变量" to "配置文件"),
            "gitignore" to ("Git 忽略规则" to ""),
            "ini" to ("INI 配置" to ""),
            "cfg" to ("配置文件" to ""),
            "conf" to ("配置文件" to "")
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
            else -> "未知" to "无法自动识别"
        }
    }

    private fun detectEncoding(content: String): String {
        val utf8Bytes = content.toByteArray(Charsets.UTF_8)
        val isoBytes = content.toByteArray(Charsets.ISO_8859_1)
        return if (utf8Bytes.size == isoBytes.size && content.contains(Regex("[\\x80-\\xFF]"))) {
            "UTF-8 / ISO-8859-1 兼容"
        } else if (content.contains("\u0000")) {
            "UTF-16 (含空字符)"
        } else "UTF-8"
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
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
        "gif" -> "image/gif"; "webp" -> "image/webp"
        "bmp" -> "image/bmp"; "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"; "gz" -> "application/gzip"
        "tar" -> "application/x-tar"; "apk" -> "application/vnd.android.package-archive"
        "dex" -> "application/octet-stream"; "so" -> "application/x-sharedlib"
        "db" -> "application/octet-stream (SQLite)"
        else -> "application/octet-stream"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
