package com.wy.openx.data.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wy.openx.data.model.AIProviderConfig
import com.wy.openx.data.model.ProviderType
import com.wy.openx.data.model.defaultProviderApiPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 拉取模型列表的结果。 */
data class FetchModelsResult(
    val models: List<String>,
    val debugInfo: ModelTestResult
)

/** 拉取模型异常，附带调试信息。 */
class FetchModelsException(
    override val message: String,
    val debugInfo: ModelTestResult?
) : RuntimeException(message)

/** 测试模型连通性的结果，包含完整的请求/响应调试信息。 */
data class ModelTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val requestUrl: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val responseCode: Int = 0,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String = "",
    val errorDetail: String = ""
)

/**
 * 直接通过 OkHttp 调用提供商的 REST 接口来拉取模型列表与测试连通性。
 * openx 请求层只实现 OpenAI 兼容协议；Anthropic/Gemini 的列表 URL 与请求头会按类型适配，
 * 但连通性测试与聊天仍走 OpenAI 兼容路径（聊天请求层统一按 chat/completions）。
 */
object ModelApiService {
    private val JSON = "application/json".toMediaType()

    suspend fun fetchModels(config: AIProviderConfig): Result<FetchModelsResult> = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        runCatching {
            val apiKey = config.firstUsableApiKey
            if (apiKey.isBlank()) error("请先填写 API Key")

            val url = ProviderHttp.resolveModelsUrl(config)
            val requestBuilder = ProviderHttp.applyAuth(
                ProviderHttp.applyUserAgent(Request.Builder(), config.userAgent),
                apiKey,
                config.type
            ).url(url).get()

            val request = requestBuilder.build()
            val reqHeadersMap = maskHeaders(request)

            val client = ProviderHttp.clientFor(config)
            client.newCall(request).execute().use { response ->
                val latency = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                val respHeadersMap = response.headers.names().associateWith { response.header(it).orEmpty() }

                if (!response.isSuccessful) {
                    val debug = ModelTestResult(
                        success = false, latencyMs = latency,
                        message = "HTTP ${response.code}: ${body.take(160)}",
                        requestUrl = url, requestHeaders = reqHeadersMap, requestBody = "",
                        responseCode = response.code, responseHeaders = respHeadersMap,
                        responseBody = body, errorDetail = body
                    )
                    throw FetchModelsException("HTTP ${response.code}: ${body.take(200)}", debug)
                }

                val modelList = parseModels(body, config.type)
                val debug = ModelTestResult(
                    success = true, latencyMs = latency,
                    message = "成功拉取到 ${modelList.size} 个模型 · ${latency}ms",
                    requestUrl = url, requestHeaders = reqHeadersMap, requestBody = "",
                    responseCode = response.code, responseHeaders = respHeadersMap, responseBody = body
                )
                FetchModelsResult(modelList, debug)
            }
        }.recoverCatching { e ->
            if (e is FetchModelsException) throw e
            val latency = (System.nanoTime() - start) / 1_000_000
            val debug = ModelTestResult(
                success = false, latencyMs = latency,
                message = e.message ?: "拉取失败", errorDetail = e.stackTraceToString()
            )
            throw FetchModelsException(e.message ?: "拉取失败", debug)
        }
    }

    /** 发送一条极短请求验证 Key + 模型 + 端点是否可用，返回耗时。 */
    suspend fun testModel(config: AIProviderConfig, model: String): ModelTestResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val apiKey = config.firstUsableApiKey
            if (apiKey.isBlank()) return@withContext ModelTestResult(
                success = false, latencyMs = 0, message = "请先填写 API Key"
            )

            val useResponseApi = config.useResponseApi
            // 路径必须与聊天请求层一致：智谱 GLM 等走 v4（defaultProviderApiPath 决定），
            // 此前这里写死 v1/chat/completions，对 v4 类提供商会 404/400。
            val path = if (useResponseApi) "v1/responses" else defaultProviderApiPath(config.type)
            val url = if (config.useFullUrl) config.baseUrl else joinUrl(config.baseUrl, path)
            // 探活只发一条极短 user 消息。**不传 max_tokens**：不少网关/推理模型对 max_tokens=1
            // 会直接 400（低于其最小输出约束），这并非 Key 或端点问题，却会被误报成「测试失败」。
            // 省略该字段即落到服务端默认值，与真实聊天请求的宽容度一致。
            // 用 Gson 构造请求体，避免手拼 JSON 的转义错误。
            // 此前用 org.json.JSONObject.quote(model) 拼串：quote() 返回的是**已带引号**的字符串
            // （"gpt-4o"），模板又在外层加了一对引号 → "model":""gpt-4o"" 非法 JSON，
            // 导致所有模型的测试请求都 400 invalid_request。
            val payloadObj = com.google.gson.JsonObject().apply {
                addProperty("model", model)
                if (useResponseApi) {
                    val arr = com.google.gson.JsonArray()
                    arr.add(com.google.gson.JsonObject().apply {
                        addProperty("role", "user"); addProperty("content", "hi")
                    })
                    add("input", arr)
                } else {
                    val arr = com.google.gson.JsonArray()
                    arr.add(com.google.gson.JsonObject().apply {
                        addProperty("role", "user"); addProperty("content", "hi")
                    })
                    add("messages", arr)
                }
            }
            val payload = payloadObj.toString()

            val request = ProviderHttp.applyAuth(
                ProviderHttp.applyUserAgent(Request.Builder(), config.userAgent),
                apiKey,
                config.type
            ).url(url).post(payload.toRequestBody(JSON)).build()
            val reqHeadersMap = maskHeaders(request)

            ProviderHttp.clientFor(config).newCall(request).await().use { response ->
                val latency = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                val respHeadersMap = response.headers.names().associateWith { response.header(it).orEmpty() }

                if (response.isSuccessful) {
                    ModelTestResult(
                        success = true, latencyMs = latency, message = "连通 · ${latency}ms",
                        requestUrl = url, requestHeaders = reqHeadersMap, requestBody = payload,
                        responseCode = response.code, responseHeaders = respHeadersMap, responseBody = body
                    )
                } else {
                    ModelTestResult(
                        success = false, latencyMs = latency,
                        message = "HTTP ${response.code}: ${body.take(160)}",
                        requestUrl = url, requestHeaders = reqHeadersMap, requestBody = payload,
                        responseCode = response.code, responseHeaders = respHeadersMap,
                        responseBody = body, errorDetail = body
                    )
                }
            }
        } catch (e: CancellationException) {
            // 用户按了「停止」：取消不是测试失败，不能写进结果表
            throw e
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000
            ModelTestResult(
                success = false, latencyMs = latency,
                message = e.message ?: "请求失败", errorDetail = e.stackTraceToString()
            )
        }
    }

    private fun parseModels(body: String, type: ProviderType): List<String> {
        val root = try {
            JsonParser.parseString(body) as? JsonObject ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val arr: JsonArray? = if (type == ProviderType.GEMINI) {
            root.getAsJsonArray("models")
        } else {
            root.getAsJsonArray("data")
        }
        if (arr == null) return emptyList()

        val field = if (type == ProviderType.GEMINI) "name" else "id"
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val raw = o.get(field)?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            if (type == ProviderType.GEMINI) raw.removePrefix("models/") else raw
        }.filter { it.isNotBlank() }.distinct().sorted()
    }

    private fun maskHeaders(request: Request): Map<String, String> =
        request.headers.names().associateWith { name ->
            val raw = request.header(name).orEmpty()
            if (name.equals("Authorization", ignoreCase = true) || name.equals("x-api-key", ignoreCase = true) ||
                name.equals("x-goog-api-key", ignoreCase = true)
            ) {
                if (raw.length > 12) raw.take(7) + "..." + raw.takeLast(4) else "***"
            } else {
                raw
        }
    }
}

/**
 * 异步发请求，并把「协程取消」接到 OkHttp 的 Call.cancel() 上。
 *
 * 模型列表的「停止」要立刻生效：协程取消是协作式的，`execute()` 这种阻塞调用得等
 * socket 自己超时才返回（本项目客户端 readTimeout 是 300s），在跑的请求根本停不下来。
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
                cont.resume(response) { response.close() }
            } else {
                response.close()
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
}
