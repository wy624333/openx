package com.wy.openx.data.provider

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wy.openx.data.model.ModelMetadata
import com.wy.openx.data.model.ProviderType
import com.wy.openx.data.model.mergeModelMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 模型元数据解析（models.dev）：能力标签（Image/Tools/Reasoning）、上下文窗口、单价。
 *
 * 只读链路：内存 → 磁盘缓存(24h) → 网络拉取 → 兜底默认。网络不可达时快速失败，
 * 不阻塞 UI。openx 没有内置 assets 快照，兜底统一按「文本模型 128k/64k、支持工具」。
 */
class ModelMetadataService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cached: Cache? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    suspend fun resolveAll(providerId: String, type: ProviderType, modelIds: List<String>): Map<String, ModelMetadata> =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            modelIds.associateWith { modelId ->
                findMetadata(catalog, type, modelId) ?: default(type, modelId)
            }
        }

    suspend fun resolve(providerId: String, type: ProviderType, modelId: String): ModelMetadata =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            findMetadata(catalog, type, modelId) ?: default(type, modelId)
        }

    /** 启动时异步刷新一次（缓存过期才真正发网络请求）。 */
    suspend fun refreshFromNetworkIfStale() {
        if (refreshAttemptedThisProcess) return
        refreshAttemptedThisProcess = true
        withContext(Dispatchers.IO) {
            val disk = loadCatalogFromDisk()
            if (disk != null && isFresh(disk)) return@withContext
            fetchCatalog()
        }
    }

    private fun isFresh(cache: Cache): Boolean =
        System.currentTimeMillis() - cache.loadedAtMs < CACHE_MAX_AGE_MS

    private fun loadCatalog(): Map<String, Map<String, ModelMetadata>> {
        cached?.let { return it.catalog }
        loadCatalogFromDisk()?.takeIf { isFresh(it) }?.let {
            cached = it
            return it.catalog
        }
        // 网络拉取（同步、单次进程内只做一次）
        fetchCatalog()
        return cached?.catalog ?: emptyMap()
    }

    private fun fetchCatalog() {
        for (url in CANDIDATE_SOURCES) {
            val result = runCatching {
                val request = Request.Builder().url(url).header("User-Agent", "openx").get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    writeCatalogCache(body)
                    parseCatalog(JsonParser.parseString(body))
                }
            }
            if (result.isSuccess) {
                cached = Cache(System.currentTimeMillis(), result.getOrThrow())
                return
            }
        }
        // 全部失败，降级磁盘缓存（即使已过期）
        loadCatalogFromDisk()?.let { cached = it }
    }

    private fun loadCatalogFromDisk(): Cache? {
        val file = cacheFile()
        if (!file.isFile) return null
        return runCatching {
            val body = file.readText(Charsets.UTF_8)
            val loadedAtMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            Cache(loadedAtMs, parseCatalog(JsonParser.parseString(body)))
        }.getOrNull()
    }

    private fun writeCatalogCache(body: String) {
        runCatching { cacheFile().writeText(body, Charsets.UTF_8) }
    }

    private fun cacheFile(): File = File(context.cacheDir, CACHE_FILE_NAME)

    private fun default(type: ProviderType, modelId: String): ModelMetadata = ModelMetadata(
        id = modelId,
        providerId = type.name.lowercase(),
        displayName = modelId,
        contextTokens = DEFAULT_CONTEXT_TOKENS,
        inputTokens = DEFAULT_CONTEXT_TOKENS,
        outputTokens = DEFAULT_OUTPUT_TOKENS,
        supportsTools = true,
        supportsVision = false,
        supportsReasoning = false,
        source = ModelMetadata.Source.INFERRED
    )

    private fun findMetadata(
        catalog: Map<String, Map<String, ModelMetadata>>,
        type: ProviderType,
        modelId: String
    ): ModelMetadata? {
        val normalized = modelId.removePrefix("models/")
        val preferredProviders = preferredProvidersFor(type)
        for (candidate in strippedCandidates(normalized)) {
            for (provider in preferredProviders) {
                catalog[provider]?.get(candidate)?.let { return it }
            }
            catalog.values.firstNotNullOfOrNull { models -> models[candidate] }?.let { return it }
        }
        return null
    }

    private fun preferredProvidersFor(type: ProviderType): List<String> = when (type) {
        ProviderType.ANTHROPIC -> listOf("anthropic", "google-vertex-anthropic")
        ProviderType.GEMINI -> listOf("google", "google-vertex")
        else -> listOf(
            "openai", "openrouter", "deepseek", "groq", "xai", "mistral",
            "togetherai", "alibaba", "moonshot", "zhipu", "qwen", "hunyuan",
            "doubao", "minimax", "siliconflow", "meta", "llama", "perplexity",
            "github-copilot"
        )
    }

    private fun strippedCandidates(modelId: String): List<String> {
        val candidates = mutableListOf(modelId)
        var current = modelId
        var changed: Boolean
        do {
            changed = false
            for (suffix in MODEL_SUFFIXES) {
                if (current.length > suffix.length && current.endsWith(suffix)) {
                    current = current.dropLast(suffix.length)
                    candidates.add(current)
                    changed = true
                    break
                }
            }
        } while (changed)
        return candidates
    }

    private fun parseCatalog(root: JsonElement): Map<String, Map<String, ModelMetadata>> {
        val rootObj = root as? JsonObject ?: return emptyMap()
        return rootObj.entrySet().associate { (providerId, providerEl) ->
            val providerObj = providerEl as? JsonObject ?: return@associate providerId to emptyMap()
            val models = providerObj.getAsJsonObject("models") ?: return@associate providerId to emptyMap()
            val parsed = models.entrySet().mapNotNull { (modelId, modelEl) ->
                val model = modelEl as? JsonObject ?: return@mapNotNull null
                val limit = model.getAsJsonObject("limit")
                val modalities = model.getAsJsonObject("modalities")
                val inputModalities = modalities?.getAsJsonArray("input")
                    ?.mapNotNull { (it as? JsonObject)?.takeIf { _ -> it.isJsonPrimitive } ?: if (it is com.google.gson.JsonPrimitive) it else null }
                    ?.mapNotNull { it?.asString }
                    .orEmpty()
                val cost = model.getAsJsonObject("cost")
                ModelMetadata(
                    id = model.str("id") ?: modelId,
                    providerId = providerId,
                    displayName = model.str("name") ?: model.str("id") ?: modelId,
                    contextTokens = limit?.int("context") ?: 0,
                    inputTokens = limit?.int("input"),
                    outputTokens = limit?.int("output"),
                    supportsTools = model.bool("tool_call"),
                    supportsVision = "image" in inputModalities || "video" in inputModalities || "pdf" in inputModalities,
                    supportsReasoning = model.bool("reasoning"),
                    supportsCustomTemperature = model.bool("temperature"),
                    reasoningEffortOptions = parseReasoningOptions(model.get("reasoning_options")),
                    inputCostUsdPerM = cost?.double("input"),
                    outputCostUsdPerM = cost?.double("output"),
                    cacheReadCostUsdPerM = cost?.double("cache_read"),
                    cacheWriteCostUsdPerM = cost?.double("cache_write"),
                    source = ModelMetadata.Source.MODELS_DEV
                )
            }
            providerId to parsed.associateBy { it.id }
        }
    }

    private fun parseReasoningOptions(el: JsonElement?): List<String>? {
        if (el == null || el is JsonNull) return null
        val arr = el as? JsonArray ?: return null
        val values = arr.mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("type") == "effort" }
            ?.getAsJsonArray("values")
            ?.mapNotNull { (it as? com.google.gson.JsonPrimitive)?.asString }
            .orEmpty()
        return values.takeIf { it.isNotEmpty() }
    }

    private data class Cache(
        val loadedAtMs: Long,
        val catalog: Map<String, Map<String, ModelMetadata>>
    )

    companion object {
        const val CACHE_FILE_NAME = "models-dev-api.json"
        private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val DEFAULT_CONTEXT_TOKENS = 128_000
        private const val DEFAULT_OUTPUT_TOKENS = 64_000
        private val MODEL_SUFFIXES = listOf(
            "-thinking", "-preview", "-high", "-low",
            "(thinking)", "(xhigh)", "(high)", "(low)"
        )
        private val CANDIDATE_SOURCES = listOf(
            "https://models.dev/api.json",
            "https://gcore.jsdelivr.net/gh/symfony/models-dev@main/models-dev.json",
            "https://cdn.jsdelivr.net/gh/symfony/models-dev@main/models-dev.json"
        )
    }
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it !is JsonNull }?.asString

private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it !is JsonNull }?.asInt

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { it !is JsonNull }?.asBoolean ?: false

private fun JsonObject.double(key: String): Double? =
    get(key)?.takeIf { it !is JsonNull }?.asDouble
