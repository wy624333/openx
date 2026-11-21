package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object MemoryClassifier {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val keywordCategories = mapOf(
        "preference" to listOf(
            "喜欢", "偏好", "习惯", "口味", "爱好", "偏爱",
            "不喜欢", "讨厌", "反感", "prefer", "favorite", "like", "love", "hate"
        ),
        "fact" to listOf(
            "名字", "年龄", "职业", "地址", "电话", "邮箱", "生日",
            "name", "age", "job", "address", "phone", "email", "birthday",
            "学校", "公司", "城市", "国家", "关系"
        ),
        "task" to listOf(
            "任务", "待办", "计划", "项目", "截至", "截止",
            "task", "todo", "plan", "project", "deadline", "schedule",
            "需要做", "要完成", "进度", "进行中"
        ),
        "code" to listOf(
            "代码", "函数", "类", "接口", "API", "bug", "debug",
            "code", "function", "class", "interface", "repository", "commit",
            "变量", "方法", "import", "package", "gradle", "依赖"
        )
    )

    fun classifyByKeywords(text: String): String {
        val lower = text.lowercase()
        val scores = mutableMapOf<String, Int>()
        keywordCategories.forEach { (category, keywords) ->
            scores[category] = keywords.count { kw -> lower.contains(kw.lowercase()) }
        }
        return scores.maxByOrNull { it.value }?.let { (cat, score) ->
            if (score > 0) cat else "general"
        } ?: "general"
    }

    data class ClassifyRequest(
        val model: String,
        val messages: List<ClassifyMessage>,
        val stream: Boolean = false,
        val maxTokens: Int = 50,
        val temperature: Float = 0.0f
    )

    data class ClassifyMessage(val role: String, val content: String)

    fun classifyWithLLM(
        text: String,
        apiUrl: String,
        apiKey: String,
        model: String
    ): String? {
        val prompt = """你是一个记忆分类器。请将以下记忆内容分类到最合适的类别（只返回类别名称）：
类别: preference(偏好/喜好), fact(事实/个人信息), task(任务/待办), code(代码/技术), general(通用/其他)

记忆内容: $text

类别:"""

        try {
            val body = gson.toJson(ClassifyRequest(
                model = model,
                messages = listOf(ClassifyMessage("user", prompt))
            ))
            val request = Request.Builder()
                .url("${apiUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(JSON))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: return null
            val obj = JsonParser.parseString(respBody).asJsonObject
            val content = obj.getAsJsonArray("choices")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?.trim()?.lowercase() ?: return null

            return when {
                content.contains("preference") -> "preference"
                content.contains("fact") -> "fact"
                content.contains("task") -> "task"
                content.contains("code") -> "code"
                else -> "general"
            }
        } catch (_: Exception) {
            return null
        }
    }
}
