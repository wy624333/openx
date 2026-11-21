package com.wy.openx.data.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 从 models.dev 拉取模型上下文窗口信息（输入/输出 Token）。 */
data class ModelContextInfo(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null
)

object ModelMetaFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val MODELS_DEV_URL = "https://models.dev/api.json"

    @Volatile
    private var cachedDb: JSONObject? = null

    /** openx 展示名 → models.dev provider id 映射。 */
    private val providerAliases = mapOf(
        "openai" to "openai",
        "deepseek" to "deepseek",
        "siliconflow" to "siliconflow",
        "openrouter" to "openrouter",
        "anthropic" to "anthropic",
        "google gemini" to "google",
        "google" to "google",
        "ollama" to "ollama",
        "智谱 glm" to "zhipuai",
        "zhipu" to "zhipuai",
        "月之暗面 kimi" to "moonshotai",
        "moonshot" to "moonshotai",
        "kimi" to "moonshotai",
        "阿里云百炼" to "aliyun",
        "通义千问" to "qwen",
        "qwen" to "qwen",
        "grok" to "xai",
        "xai" to "xai",
        "mistral" to "mistral",
        "groq" to "groq",
        "together ai" to "together",
        "together" to "together"
    )

    /**
     * 获取模型输入/输出窗口。命中 models.dev 数据时返回对应 Token 数，否则返回 null。
     * 先按 provider 精确查找，再全局按模型名匹配（兼容展示名与官方别名不一致的情况）。
     */
    suspend fun fetch(providerName: String, model: String): ModelContextInfo = withContext(Dispatchers.IO) {
        val db = loadDb()
        val target = model.trim()
        if (db.length() == 0 || target.isBlank()) return@withContext ModelContextInfo()
        val hit = findModel(db, resolveProviderId(providerName), target) ?: return@withContext ModelContextInfo()
        val limit = hit.optJSONObject("limit")
        return@withContext ModelContextInfo(
            inputTokens = limit?.optLong("context")?.takeIf { it > 0 },
            outputTokens = limit?.optLong("output")?.takeIf { it > 0 }
        )
    }

    private fun loadDb(): JSONObject {
        cachedDb?.let { return it }
        return try {
            val request = Request.Builder().url(MODELS_DEV_URL).get().build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank()) {
                    val parsed = JSONObject(body)
                    cachedDb = parsed
                    parsed
                } else {
                    cachedDb ?: JSONObject()
                }
            }
        } catch (_: Exception) {
            cachedDb ?: JSONObject()
        }
    }

    private fun resolveProviderId(name: String): String? {
        val key = name.trim().lowercase()
        providerAliases[key]?.let { return it }
        if (key.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }) {
            val slug = key.replace(Regex("[^a-z0-9]+"), "-").trim('-')
            if (slug.isNotBlank()) return slug
        }
        return null
    }

    private fun findModel(db: JSONObject, providerId: String?, target: String): JSONObject? {
        // 先尝试解析后的 provider 对应的 models 表
        if (providerId != null) {
            val provider = db.optJSONObject(providerId)
            val models = provider?.optJSONObject("models")
            if (models != null) {
                findIn(models, target)?.let { return it }
            }
        }
        // 全局扫描（当前 provider 已排除，避免重复；也兜底 provider 未映射的情况）
        val keys = db.keys()
        while (keys.hasNext()) {
            val pid = keys.next()
            if (pid == providerId) continue
            val provider = db.optJSONObject(pid) ?: continue
            val models = provider.optJSONObject("models") ?: continue
            findIn(models, target)?.let { return it }
        }
        return null
    }

    private fun findIn(models: JSONObject, target: String): JSONObject? {
        val upper = target.uppercase()
        val keys = models.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.equals(target, ignoreCase = true)) return models.getJSONObject(key)
            if (key.uppercase().startsWith("$upper-")) return models.getJSONObject(key)
        }
        return null
    }
}