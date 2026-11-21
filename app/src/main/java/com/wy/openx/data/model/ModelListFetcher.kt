package com.wy.openx.data.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

data class ModelOption(
    val id: String,
    val name: String,
    val description: String = ""
)

object ModelListFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getModelsList(
        apiKey: String,
        apiEndpoint: String,
        providerType: ApiProviderType
    ): Result<List<ModelOption>> = withContext(Dispatchers.IO) {
        try {
            val modelsUrl = getModelsListUrl(apiEndpoint, providerType)
            if (modelsUrl.isBlank()) {
                return@withContext Result.success(emptyList())
            }

            val requestBuilder = Request.Builder()
                .url(modelsUrl)
                .addHeader("Content-Type", "application/json")
            
            // 根据不同供应商添加不同的认证头
            when (providerType) {
                ApiProviderType.GOOGLE -> {
                    // Google Gemini API 使用 API 密钥作为查询参数
                    val urlWithKey = modelsUrl.toHttpUrl().newBuilder()
                        .setQueryParameter("key", apiKey)
                        .build()
                    requestBuilder.url(urlWithKey)
                }
                ApiProviderType.ANTHROPIC -> {
                    requestBuilder.addHeader("x-api-key", apiKey)
                    requestBuilder.addHeader("anthropic-version", "2023-06-01")
                }
                else -> {
                    if (apiKey.isNotBlank()) {
                        requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }
                }
            }
            
            val request = requestBuilder.get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext Result.success(emptyList())

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val models = parseModelsResponse(body, providerType)
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getModelsListUrl(apiEndpoint: String, providerType: ApiProviderType): String {
        // 如果端点已经包含 /models，直接使用
        if (apiEndpoint.contains("/models")) {
            return apiEndpoint
        }
        
        val baseUrl = extractBaseUrl(apiEndpoint)
        return when (providerType) {
            ApiProviderType.OPENAI,
            ApiProviderType.DEEPSEEK,
            ApiProviderType.ALIYUN,
            ApiProviderType.MOONSHOT,
            ApiProviderType.SILICONFLOW,
            ApiProviderType.OPENROUTER,
            ApiProviderType.ANTHROPIC,
            ApiProviderType.OTHER -> "$baseUrl/v1/models"
            ApiProviderType.ZHIPU -> "$baseUrl/v4/models"
            ApiProviderType.GOOGLE -> {
                if (apiEndpoint.contains("generativelanguage.googleapis.com")) {
                    if (apiEndpoint.endsWith("/models")) apiEndpoint
                    else "https://generativelanguage.googleapis.com/v1beta/models"
                } else {
                    "$baseUrl/v1/models"
                }
            }
            ApiProviderType.OLLAMA -> "$baseUrl/v1/models"
        }
    }

    /** 从完整URL提取基本URL，例如: https://api.openai.com/v1/chat/completions -> https://api.openai.com */
    private fun extractBaseUrl(fullUrl: String): String {
        return try {
            val url = URL(fullUrl)
            val path = url.path
            
            // 查找版本路径，例如 /v1, /v2
            val versionPathRegex = Regex("/v\\d+")
            val match = versionPathRegex.find(path)
            
            if (match != null) {
                // 截取到版本路径之前的部分
                val pathBeforeVersion = path.substring(0, match.range.first)
                "${url.protocol}://${url.authority}$pathBeforeVersion"
            } else {
                // 如果找不到版本路径，则返回原始URL的主机部分
                "${url.protocol}://${url.authority}"
            }
        } catch (e: Exception) {
            // 尝试简单提取
            fullUrl.substringBefore("/v1").substringBefore("/chat/completions")
        }
    }

    private fun parseModelsResponse(body: String, providerType: ApiProviderType): List<ModelOption> {
        return try {
            val json = JSONObject(body)

            when (providerType) {
                ApiProviderType.GOOGLE -> {
                    val modelsArray = json.optJSONArray("models") ?: return emptyList()
                    (0 until modelsArray.length()).map { i ->
                        val model = modelsArray.getJSONObject(i)
                        val name = model.optString("name", "")
                        ModelOption(
                            id = name,
                            name = name.substringAfterLast("/"),
                            description = model.optString("description", "")
                        )
                    }
                }
                ApiProviderType.OLLAMA -> {
                    val modelsArray = json.optJSONArray("models") ?: return emptyList()
                    (0 until modelsArray.length()).map { i ->
                        val model = modelsArray.getJSONObject(i)
                        val name = model.optString("name", "")
                        ModelOption(id = name, name = name)
                    }
                }
                else -> {
                    val data = json.optJSONArray("data") ?: return emptyList()
                    (0 until data.length()).map { i ->
                        val model = data.getJSONObject(i)
                        val id = model.optString("id", "")
                        ModelOption(id = id, name = id)
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
