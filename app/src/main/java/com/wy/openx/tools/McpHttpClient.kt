package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.wy.openx.container.FileLogger
import com.wy.openx.data.store.McpServerConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP Streamable HTTP 传输客户端（aicode-main 同款方案，Gson + okhttp 实现）。
 *
 * 单端点 POST JSON-RPC：server 可返回 `application/json` 直接响应，或
 * `text/event-stream`（SSE，读取首条 `data:` 消息即完整 JSON）。`initialize`
 * 响应返回的 `Mcp-Session-Id` 头会被缓存并用于后续请求。同步调用，需在
 * IO 线程执行（调用方 McpTool 已 withContext(Dispatchers.IO)）。
 */
class McpHttpClient {

    companion object {
        private const val TAG = "McpHttpClient"
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val idCounter = AtomicLong(0)
    private val sessionIds = ConcurrentHashMap<String, String>()

    fun listTools(config: McpServerConfig): String = request(config, "tools/list", emptyMap())

    fun callTool(config: McpServerConfig, tool: String, toolArgs: Map<String, Any>): String =
        request(config, "tools/call", mapOf("name" to tool, "arguments" to toolArgs))

    /** 断开与服务器的会话（清理 session id）。 */
    fun close(name: String) {
        sessionIds.remove(name)
    }

    fun closeAll() {
        sessionIds.clear()
    }

    private fun request(config: McpServerConfig, method: String, params: Map<String, Any>): String {
        val endpoint = config.url ?: return "{\"error\":{\"message\":\"MCP server '${config.name}' 缺少 url\"}}"
        val id = idCounter.incrementAndGet()
        val bodyJson = gson.toJson(mapOf(
            "jsonrpc" to "2.0", "id" to id, "method" to method,
            "params" to (params as Any)
        ))
        FileLogger.d(TAG, "POST [$method] id=$id -> $endpoint")

        try {
            val httpReq = buildRequest(config, endpoint, bodyJson)
            client.newCall(httpReq).execute().use { resp ->
                resp.header("Mcp-Session-Id")?.let {
                    if (it.isNotBlank()) sessionIds[config.name] = it
                }

                if (!resp.isSuccessful) {
                    return "{\"error\":{\"message\":\"HTTP ${resp.code} ${resp.message}（$method 失败）\"}}"
                }

                val contentType = resp.header("Content-Type").orEmpty()
                val rawJson = if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    extractSseJson(resp.body?.charStream()?.buffered())
                        ?: return "{\"error\":{\"message\":\"SSE 响应中未找到 $method 数据\"}}"
                } else {
                    resp.body?.string()
                        ?: return "{\"error\":{\"message\":\"$method 响应为空\"}}"
                }
                return rawJson
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "HTTP 请求失败 [config=${config.name} method=$method]: ${e.message}", e)
            return "{\"error\":{\"message\":\"MCP HTTP communication error: ${e.message ?: "unknown"}\"}}"
        }
    }

    private fun buildRequest(config: McpServerConfig, endpoint: String, bodyJson: String): Request {
        val builder = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json, text/event-stream")
        sessionIds[config.name]?.takeIf { it.isNotBlank() }?.let { builder.add("Mcp-Session-Id", it) }
        config.headers.forEach { (k, v) -> builder.add(k, v) }

        return Request.Builder()
            .url(endpoint)
            .headers(builder.build())
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()
    }

    /** 从 SSE 流中提取首个 `data:` 事件的完整 JSON（可跨多行拼接）。 */
    private fun extractSseJson(reader: BufferedReader?): String? {
        reader ?: return null
        val data = StringBuilder()
        var line = reader.readLine()
        while (line != null) {
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim()).append('\n')
                line.isBlank() && data.isNotEmpty() -> return data.toString().trim('\n')
            }
            line = reader.readLine()
        }
        return data.takeIf { it.isNotEmpty() }?.toString()?.trim('\n')
    }

    /** 把 raw JSON-RPC 响应解析为统一结果（复用还原注释）。 */
    fun parseMcpResponse(raw: String): McpBridgeManager.McpResult {
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            val err = obj.get("error")
            if (err != null && !err.isJsonNull) {
                val message = when {
                    err.isJsonObject -> err.asJsonObject.get("message")?.takeIf { !it.isJsonNull }?.asString ?: raw.take(300)
                    err.isJsonPrimitive -> err.asString
                    else -> raw.take(300)
                }
                McpBridgeManager.McpResult.Error(message)
            } else {
                McpBridgeManager.McpResult.Success(obj)
            }
        } catch (e: JsonSyntaxException) {
            McpBridgeManager.McpResult.Error("Failed to parse MCP response: ${raw.take(500)}")
        } catch (e: Exception) {
            McpBridgeManager.McpResult.Error("Failed to parse MCP response: ${e.message ?: "unknown"} | ${raw.take(300)}")
        }
    }
}