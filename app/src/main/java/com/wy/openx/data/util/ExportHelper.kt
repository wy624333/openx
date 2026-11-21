package com.wy.openx.data.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.wy.openx.data.model.ChatMessageModel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportHelper {

    /** 导出前清理历史导出文件，只保留最新 keep 份，防止 cacheDir 无限膨胀 */
    private fun pruneOldExports(context: Context, keep: Int = 5) {
        try {
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("chat_export_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun exportMarkdown(context: Context, messages: List<ChatMessageModel>, modelName: String): File {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val now = Date()

        val md = StringBuilder()
        md.appendLine("# AI 智能体 对话记录")
        md.appendLine()
        md.appendLine("- **模型**：$modelName")
        md.appendLine("- **时间**：${dateFormat.format(now)}")
        md.appendLine("- **总消息数**：${messages.size}")
        md.appendLine()
        md.appendLine("---").appendLine()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (msg in messages) {
            val t = if (msg.time.isNotBlank()) msg.time else timeFmt.format(Date())
            when (msg.role.name.lowercase()) {
                "user" -> md.appendLine("### 👤 用户 ($t)")
                "assistant" -> md.appendLine("### 🤖 AI ($t)")
                "system" -> md.appendLine("### ⚙ 系统 ($t)")
                else -> md.appendLine("### ${msg.role.name} ($t)")
            }
            md.appendLine().appendLine(msg.content).appendLine("---").appendLine()
        }

        val file = File(context.cacheDir, "chat_export_${fileNameFormat.format(now)}.md")
        file.writeText(md.toString(), Charsets.UTF_8)
        pruneOldExports(context)
        return file
    }

    fun exportJson(context: Context, messages: List<ChatMessageModel>, modelName: String): File {
        val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val now = Date()
        val json = buildString {
            appendLine("{")
            appendLine("  \"app\": \"AI 智能体\",")
            appendLine("  \"version\": \"1.0.0\",")
            appendLine("  \"model\": \"$modelName\",")
            appendLine("  \"messageCount\": ${messages.size},")
            appendLine("  \"messages\": [")
            for ((i, msg) in messages.withIndex()) {
                val comma = if (i < messages.size - 1) "," else ""
                appendLine("    {")
                appendLine("      \"role\": \"${msg.role.name.lowercase()}\",")
                appendLine("      \"content\": ${escapeJson(msg.content)},")
                appendLine("      \"author\": \"${msg.author}\"")
                appendLine("    }$comma")
            }
            appendLine("  ]")
            appendLine("}")
        }
        val file = File(context.cacheDir, "chat_export_${fileNameFormat.format(now)}.json")
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    /**
     * 将聊天记录保存到系统的「下载」目录（Download/OpenX/），返回保存的文件路径。
     * Android 10+ 使用 MediaStore，Android 9- 使用传统文件写入并放到公共下载目录。
     */
    fun saveToDownloads(context: Context, messages: List<ChatMessageModel>, modelName: String, asJson: Boolean = false): String {
        val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val now = Date()
        val fileName = "openx_chat_${fileNameFormat.format(now)}.${if (asJson) "json" else "md"}"
        val content = if (asJson) {
            buildExportJson(messages, modelName)
        } else {
            buildExportMarkdown(messages, modelName)
        }
        val folderName = "OpenX"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, fileName, content, folderName)
        } else {
            saveToLegacyDir(fileName, content, folderName)
        }
    }

    private fun buildExportMarkdown(messages: List<ChatMessageModel>, modelName: String): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val now = Date()
        val md = StringBuilder()
        md.appendLine("# AI 智能体 对话记录")
        md.appendLine()
        md.appendLine("- **模型**：$modelName")
        md.appendLine("- **时间**：${dateFormat.format(now)}")
        md.appendLine("- **总消息数**：${messages.size}")
        md.appendLine()
        md.appendLine("---").appendLine()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (msg in messages) {
            val t = if (msg.time.isNotBlank()) msg.time else timeFmt.format(now)
            when (msg.role.name.lowercase()) {
                "user" -> md.appendLine("### 👤 用户 ($t)")
                "assistant" -> md.appendLine("### 🤖 AI ($t)")
                "system" -> md.appendLine("### ⚙ 系统 ($t)")
                else -> md.appendLine("### ${msg.role.name} ($t)")
            }
            md.appendLine().appendLine(msg.content).appendLine("---").appendLine()
        }
        return md.toString()
    }

    private fun buildExportJson(messages: List<ChatMessageModel>, modelName: String): String {
        return buildString {
            appendLine("{")
            appendLine("  \"app\": \"AI 智能体\",")
            appendLine("  \"version\": \"1.0.0\",")
            appendLine("  \"model\": \"$modelName\",")
            appendLine("  \"messageCount\": ${messages.size},")
            appendLine("  \"messages\": [")
            for ((i, msg) in messages.withIndex()) {
                val comma = if (i < messages.size - 1) "," else ""
                appendLine("    {")
                appendLine("      \"role\": \"${msg.role.name.lowercase()}\",")
                appendLine("      \"content\": ${escapeJson(msg.content)},")
                appendLine("      \"author\": \"${msg.author}\"")
                appendLine("    }$comma")
            }
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun saveViaMediaStore(context: Context, fileName: String, content: String, folderName: String): String {
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (fileName.endsWith(".json")) "application/json" else "text/markdown")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folderName")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return saveToLegacyDir(fileName, content, folderName)
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "$folderName/$fileName"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            saveToLegacyDir(fileName, content, folderName)
        }
    }

    private fun saveToLegacyDir(fileName: String, content: String, folderName: String): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloadsDir, folderName)
        if (!folder.exists()) folder.mkdirs()
        val file = File(folder, fileName)
        FileOutputStream(file).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
        return file.absolutePath
    }

    fun shareFile(context: Context, file: File, mimeType: String = "text/markdown") {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 从 Application context 启动 chooser 需要 NEW_TASK；Activity context 下该 flag 无副作用
        val chooser = Intent.createChooser(shareIntent, "导出对话").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            .replace("\b", "\\b").replace("\u000C", "\\f")
            .let { "\"$it\"" }
    }
}
