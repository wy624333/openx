package com.wy.openx.data.knowledge

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wy.openx.container.FileLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 调「知识提炼模型」从一段对话里抽取可沉淀的知识。
 *
 * 走的是最小依赖的路子（OkHttp 直连 `/chat/completions`），与 [com.wy.openx.tools.MemoryClassifier]
 * 同类；不接聊天主链路，所以提炼失败绝不会影响正常对话。
 */
object KnowledgeExtractor {

    private const val TAG = "KnowledgeExtractor"

    /** 交给模型的对话文本上限：够它看懂一轮上下文，又不至于把整段历史塞进去。 */
    private const val MAX_TRANSCRIPT_CHARS = 12_000

    /**
     * 提炼模型返回的一条候选。
     *
     * `op = "update"` 表示已有知识列表里有同一件事，要沿用它的 `id` 覆盖，而不是新建重复条目。
     */
    data class Candidate(
        val op: String,
        val id: String,
        val title: String,
        val tags: List<String>,
        val body: String
    )

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val SYSTEM_PROMPT = """
        你是一个知识提炼器。读取一段「AI 编程助手与用户」的对话，抽出值得长期复用的知识，供以后遇到同类问题时查阅。

        只抽这三类：
        1. 踩过的坑：现象、根因、怎么解决；
        2. 可复用的做法：配置、命令、API 用法、排查步骤（要能照着做）；
        3. 项目硬约束：架构约定、路径、构建方式、安全红线。

        不要抽：
        - 寒暄、确认、一次性的闲聊；
        - 已有知识列表里已经存在的内容（若与某条是同一件事，用 op=update 并沿用它的 id，不要新建重复条目）；
        - 大段代码粘贴（要写成「什么情况下怎么做」，代码只保留关键几行）；
        - 密钥、口令、token 等敏感信息。

        输出**严格 JSON**，不要任何解释文字、不要 Markdown 代码块围栏：
        {"items":[{"op":"add","id":"英文短横线-slug","title":"一句话标题（30 字内）","tags":["标签"],"body":"正文，说清现象/原因/做法"}]}

        没有值得沉淀的内容就输出 {"items":[]}。每条 body 控制在 800 字以内，最多输出 5 条。
    """.trimIndent()

    /**
     * 跑一次提炼。
     *
     * @param transcript 待提炼的对话文本（可能是攒了几轮的合并结果）。
     * @param existingCatalog 已有知识目录（id + 标题 + 标签），用于让模型判断该 add 还是 update。
     * @return 候选列表；请求失败或解析不出时返回 null（调用方据此决定要不要保留轮数重试）。
     */
    fun extract(
        transcript: String,
        existingCatalog: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ): List<Candidate>? {
        if (transcript.isBlank() || baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null

        val userPrompt = buildString {
            append("【已有知识】")
            append(if (existingCatalog.isBlank()) "（空）" else "\n" + existingCatalog)
            append("\n\n【本次对话】\n")
            append(transcript.take(MAX_TRANSCRIPT_CHARS))
        }

        val payload = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to 0.2,
            "max_tokens" to 1500,
            "stream" to false
        )

        return runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(payload).toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    FileLogger.w(TAG, "提炼请求失败 HTTP ${response.code}: ${text.take(200)}")
                    return@use null
                }
                parseItems(text)
            }
        }.getOrNull()
    }

    private fun parseItems(responseBody: String): List<Candidate>? {
        val content = runCatching {
            JsonParser.parseString(responseBody).asJsonObject
                .getAsJsonArray("choices").first().asJsonObject
                .getAsJsonObject("message").get("content").asString
        }.getOrNull() ?: return null

        val jsonText = extractJsonObject(content) ?: return emptyList()
        val items = runCatching {
            JsonParser.parseString(jsonText).asJsonObject.getAsJsonArray("items")
        }.getOrNull() ?: return emptyList()

        return items.mapNotNull { element ->
            runCatching {
                val o = element.asJsonObject
                Candidate(
                    op = o.get("op")?.asString?.trim()?.lowercase().orEmpty().ifBlank { "add" },
                    id = o.get("id")?.asString?.trim().orEmpty(),
                    title = o.get("title")?.asString?.trim().orEmpty(),
                    tags = o.getAsJsonArray("tags")?.mapNotNull { it.asString } ?: emptyList(),
                    body = o.get("body")?.asString?.trim().orEmpty()
                )
            }.getOrNull()
        }
    }

    /** 从模型输出里揪出 JSON 对象：它常把结果包在 ``` 围栏里，或前后多带一句解释。 */
    private fun extractJsonObject(text: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.getOrNull(1)?.trim()
        val candidate = fenced ?: text
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return candidate.substring(start, end + 1)
    }
}
