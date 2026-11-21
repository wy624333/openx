package com.wy.openx.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpClientTool : AgentTool {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override val name = "http_client"
    override val description = "发起 HTTP 请求，支持 GET/POST/PUT/DELETE/PATCH/HEAD，可自定义请求头、请求体。用于测试 API、抓取网页等。"
    override val parameters = buildToolParameters(
        ToolParameter("method", ParameterType.STRING, "HTTP 方法", enumValues = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")),
        ToolParameter("url", ParameterType.STRING, "请求 URL（必须包含 http:// 或 https://）"),
        ToolParameter("headers", ParameterType.OBJECT, "请求头键值对，如 {\"Authorization\": \"Bearer xxx\", \"Content-Type\": \"application/json\"}", required = false),
        ToolParameter("body", ParameterType.STRING, "请求体（仅 POST/PUT/PATCH 有效）。如果是 JSON，Content-Type 自动设为 application/json", required = false),
        ToolParameter("params", ParameterType.OBJECT, "URL 查询参数键值对，如 {\"key\": \"value\"}", required = false),
        ToolParameter("max_response_length", ParameterType.INTEGER, "响应体最大截断长度（默认 5000）", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val method = (args["method"] as? String)?.trim()?.uppercase() ?: return "错误：未指定 HTTP 方法"
        val urlStr = (args["url"] as? String)?.trim() ?: return "错误：未指定 URL"
        val rawHeaders = args["headers"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val bodyStr = (args["body"] as? String)?.trim() ?: ""
        val rawParams = args["params"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val maxLen = (((args["max_response_length"] as? Double)?.toInt()) ?: 5000).coerceIn(100, 50_000)

        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            return "错误：URL 必须以 http:// 或 https:// 开头"
        }

        return try {
            withContext(Dispatchers.IO) {
                val url = buildUrl(urlStr, rawParams)

            val headers = okhttp3.Headers.Builder()
            for ((k, v) in rawHeaders) {
                headers.add(k.toString(), v.toString())
            }

            val requestBuilder = Request.Builder().url(url).headers(headers.build())

            if (method in listOf("POST", "PUT", "PATCH") && bodyStr.isNotBlank()) {
                val contentType = if (rawHeaders.any { (k, _) ->
                        k.toString().equals("Content-Type", ignoreCase = true)
                    }) null
                else if (bodyStr.trimStart().startsWith("{") || bodyStr.trimStart().startsWith("["))
                    "application/json; charset=utf-8".toMediaTypeOrNull()
                else
                    "text/plain; charset=utf-8".toMediaTypeOrNull()
                requestBuilder.method(method, bodyStr.toRequestBody(contentType))
            } else {
                requestBuilder.method(method, null)
            }

            val startTime = System.currentTimeMillis()
            val response = client.newCall(requestBuilder.build()).execute()
            val elapsed = System.currentTimeMillis() - startTime

            // 限量读取响应体（上限2MB），避免超大响应撑爆内存
            val responseBody = response.body?.let { b ->
                val cap = 2_000_000
                val buf = ByteArray(cap)
                var off = 0
                b.byteStream().use { s ->
                    while (off < cap) {
                        val r = s.read(buf, off, cap - off)
                        if (r < 0) break
                        off += r
                    }
                }
                String(buf, 0, off, Charsets.UTF_8)
            } ?: ""
            val truncatedBody = if (responseBody.length > maxLen)
                "${responseBody.take(maxLen)}\n\n... (响应体过长，截断至 $maxLen 字符，实际 ${responseBody.length} 字符)"
            else responseBody

            val responseHeaders = response.headers.joinToString("\n") { "${it.first}: ${it.second}" }

            buildString {
                appendLine("🌐 ${response.request.method} ${response.request.url}")
                appendLine("状态: ${response.code} ${response.message}  (${elapsed}ms)")
                appendLine()

                if (responseHeaders.isNotBlank()) {
                    appendLine("响应头:")
                    for (line in responseHeaders.lines().take(30)) appendLine("  $line")
                    appendLine()
                }

                if (truncatedBody.isNotBlank()) {
                    appendLine("响应体:")
                    appendLine("```")
                    appendLine(truncatedBody)
                    appendLine("```")
                }
            }.trimEnd()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: java.net.UnknownHostException) { "错误：无法解析域名「${e.message}」" }
        catch (e: java.net.SocketTimeoutException) { "错误：请求超时「${e.message}」" }
        catch (e: Exception) { "HTTP 请求失败: ${e.message ?: "未知错误"}" }
    }

    private fun buildUrl(base: String, params: Map<*, *>): String {
        if (params.isEmpty()) return base
        val query = params.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k.toString(), "UTF-8")}=${
                java.net.URLEncoder.encode(v.toString(), "UTF-8")
            }"
        }
        return if (base.contains("?")) "$base&$query" else "$base?$query"
    }
}