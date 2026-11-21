package com.wy.openx.tools

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BuiltinMcpHost(private val context: Context) {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("builtin_mcp_store", Context.MODE_PRIVATE)
    private val baseDir: File = File(context.filesDir, "mcp_workspace").also { it.mkdirs() }

    private fun safePath(path: String): File {
        val target = File(baseDir, File(path).normalize().path).normalize()
        // 必须带分隔符比较：baseDir 是 ".../mcp_workspace"，而 ".../mcp_workspace_evil"
        // 只是以它为前缀，用裸 startsWith 会把兄弟目录误判成沙箱内路径。
        val root = baseDir.canonicalPath
        val canonical = target.canonicalPath
        if (canonical != root && !canonical.startsWith(root + File.separator)) {
            throw SecurityException("Path traversal: $path")
        }
        return target
    }

    private fun ok(obj: Any) = gson.toJson(obj)
    private fun err(msg: String) = """{"error":"${msg.replace("\"","\\\"").replace("\n","\\n")}"}"""

    /** 限量读取流（防止大响应/大文件撑爆内存） */
    private fun readLimited(stream: java.io.InputStream, maxBytes: Int): String {
        val buf = ByteArray(maxBytes)
        var off = 0
        while (off < maxBytes) {
            val r = stream.read(buf, off, maxBytes - off)
            if (r < 0) break
            off += r
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    fun mcpReadFile(path: String?): String {
        try {
            val f = safePath(path ?: return err("path required"))
            if (!f.isFile) return err("Not a file: $path")
            f.inputStream().use { return ok(mapOf("content" to readLimited(it, 2_000_000))) }
        } catch (e: Exception) { return err(e.message ?: "read failed") }
    }

    fun mcpWriteFile(path: String?, content: String?): String {
        try {
            val f = safePath(path ?: return err("path required"))
            f.parentFile?.mkdirs()
            f.writeText(content ?: "")
            return ok(mapOf("ok" to true))
        } catch (e: Exception) { return err(e.message ?: "write failed") }
    }

    fun mcpListDir(path: String?): String {
        try {
            val f = if (path.isNullOrBlank()) baseDir else safePath(path)
            if (!f.isDirectory) return err("Not a directory: $path")
            val entries = f.listFiles()?.map { file ->
                mapOf(
                    "name" to file.name, "isDir" to file.isDirectory,
                    "size" to file.length(), "lastModified" to file.lastModified()
                )
            } ?: emptyList()
            return ok(mapOf("entries" to entries))
        } catch (e: Exception) { return err(e.message ?: "list failed") }
    }

    fun mcpFileInfo(path: String?): String {
        try {
            val f = safePath(path ?: return err("path required"))
            if (!f.exists()) return err("Not found: $path")
            return ok(mapOf(
                "name" to f.name, "isDir" to f.isDirectory, "isFile" to f.isFile,
                "size" to f.length(), "lastModified" to f.lastModified()
            ))
        } catch (e: Exception) { return err(e.message ?: "info failed") }
    }

    fun mcpDeleteFile(path: String?): String {
        try {
            val f = safePath(path ?: return err("path required"))
            if (!f.exists()) return err("Not found: $path")
            return ok(mapOf("deleted" to f.deleteRecursively()))
        } catch (e: Exception) { return err(e.message ?: "delete failed") }
    }

    @Suppress("unused")
    fun mcpFetch(url: String?, optionsJson: String?): String {
        try {
            val targetUrl = url ?: return err("url required")
            val opts = if (optionsJson.isNullOrBlank()) emptyMap<String,Any>() else
                try { gson.fromJson(optionsJson, Map::class.java) } catch (_: Exception) { emptyMap() }
            val method = (opts["method"] as? String)?.uppercase() ?: "GET"
            val headers = (opts["headers"] as? Map<*, *>)?.filterKeys { it is String }?.mapKeys { it.key.toString() } ?: emptyMap()
            val body = opts["body"] as? String

            val conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v.toString()) }

            if (body != null && method in listOf("POST", "PUT", "PATCH")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = conn.responseCode
            // 限量读取（1MB），避免大响应阻塞 QuickJS 线程过久
            val respBody = try { conn.inputStream.use { readLimited(it, 1_000_000) } }
                catch (_: Exception) { conn.errorStream?.use { readLimited(it, 1_000_000) } ?: "" }
            conn.disconnect()
            return ok(mapOf("status" to code, "body" to respBody))
        } catch (e: Exception) { return err(e.message ?: "fetch failed") }
    }

    fun mcpGetValue(key: String?): String {
        if (key.isNullOrBlank()) return err("key required")
        val v = prefs.getString(key, null) ?: return err("Key not found: $key")
        return ok(mapOf("value" to v))
    }

    @Suppress("unused")
    fun mcpSetValue(key: String?, value: String?): String {
        if (key.isNullOrBlank()) return err("key required")
        prefs.edit().putString(key, value ?: "").apply()
        return ok(mapOf("ok" to true))
    }

    fun mcpDeleteValue(key: String?): String {
        if (key.isNullOrBlank()) return err("key required")
        prefs.edit().remove(key).apply()
        return ok(mapOf("deleted" to true))
    }

    fun mcpListValues(): String {
        val entries = prefs.all.mapValues { it.value.toString() }
        return ok(mapOf("keys" to entries.keys.toList(), "entries" to entries))
    }
}
