package com.wy.openx.tools

import com.wy.openx.data.store.MemoryStore
import com.wy.openx.data.store.SCOPE_AGENT
import com.wy.openx.data.store.SCOPE_DOCS
import com.wy.openx.data.store.SCOPE_GLOBAL
import com.wy.openx.data.store.SCOPE_PROJECT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryTool(private val operation: String) : AgentTool {

    // SimpleDateFormat 非线程安全：工具可能被并发调用，改为每次使用时局部创建
    private fun dateFormat(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override val definition = when (operation) {
        "memory_save" -> ToolDef(
            function = FunctionDef(
                name = "memory_save",
                description = "保存一条记忆到本地存储，AI会在后续对话中记住并参考。适合保存用户的偏好、重要信息、待办事项、任务要求等。如果已存在同名的记忆会自动覆盖更新。设置 type=auto 可让 AI 自动判断分类。注意用 scope 区分「跨项目通用」还是「本项目专属」：判断不准时想「换一个项目这条还成立吗」，成立用 global，不成立用 project。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "key" to mapOf("type" to "string", "description" to "记忆的名称/关键词，简短明确，例如：用户昵称、项目名、偏好设置"),
                        "value" to mapOf("type" to "string", "description" to "记忆的具体内容，可以包含重要细节和信息"),
                        "type" to mapOf("type" to "string", "description" to "记忆类型：auto（自动分类）/user（用户信息）/ai（AI记录）/project（项目相关）", "default" to "auto"),
                        "scope" to mapOf("type" to "string", "description" to "记忆作用域。global=长期记忆（跨项目通用：用户偏好、个人习惯、通用约定）；project=项目记忆（仅当前项目：本项目架构/约定/待办/坑）。判断不准时想「换一个项目这条还成立吗」——成立用 global，不成立用 project。未选项目时只能用 global", "default" to "global"),
                        "category" to mapOf("type" to "string", "description" to "手动指定分类：preference(偏好)/fact(事实)/task(任务)/code(代码)/general(通用)，留空则自动检测", "default" to "")
                    ),
                    "required" to listOf("key", "value")
                )
            )
        )
        "memory_read" -> ToolDef(
            function = FunctionDef(
                name = "memory_read",
                description = "按关键词搜索已保存的记忆。不提供关键词则返回所有记忆（最多20条）。支持按类别筛选。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "key" to mapOf("type" to "string", "description" to "搜索关键词（可选），按名称或内容匹配", "default" to ""),
                        "type" to mapOf("type" to "string", "description" to "按类型筛选：user/ai/project（可选）", "default" to ""),
                        "category" to mapOf("type" to "string", "description" to "按类别筛选：preference(偏好)/fact(事实)/task(任务)/code(代码)/general(通用)", "default" to ""),
                        "maxResults" to mapOf("type" to "integer", "description" to "最大返回条数（默认20）", "default" to 20)
                    ),
                    "required" to emptyList<String>()
                )
            )
        )
        "memory_delete" -> ToolDef(
            function = FunctionDef(
                name = "memory_delete",
                description = "删除指定的记忆条目。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "key" to mapOf("type" to "string", "description" to "要删除的记忆名称"),
                        "type" to mapOf("type" to "string", "description" to "记忆类型：user/ai/project", "default" to "ai")
                    ),
                    "required" to listOf("key")
                )
            )
        )
        else -> ToolDef(
            function = FunctionDef(name = operation, description = "", parameters = emptyMap())
        )
    }

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val ctx = AppContextHolder.context ?: return@withContext "错误：应用上下文未初始化"
        val store = MemoryStore(ctx)
        when (operation) {
            "memory_save" -> {
                val key = (args["key"] as? String)?.trim() ?: return@withContext "错误：未提供记忆名称"
                val value = (args["value"] as? String)?.trim() ?: return@withContext "错误：未提供记忆内容"
                if (key.length > 100) return@withContext "错误：记忆名称过长（最长100字符）"
                var type = args["type"] as? String ?: "ai"
                var category = args["category"] as? String ?: ""
                // 作用域：global=长期记忆（默认），project=当前项目的项目记忆
                val scope = (args["scope"] as? String)?.trim()?.lowercase()
                    ?.takeIf { it == SCOPE_PROJECT } ?: SCOPE_GLOBAL
                val project = AppContextHolder.currentSettings?.currentProject?.trim().orEmpty()
                if (scope == SCOPE_PROJECT && project.isBlank()) {
                    return@withContext "错误：当前未选择项目，无法保存项目记忆。请先在「工作区」中选择或创建项目，或改用 scope=global 保存为长期记忆。"
                }
                if (type == "auto") {
                    type = "ai"
                    category = MemoryClassifier.classifyByKeywords(value)
                    val settings = AppContextHolder.currentSettings
                    if (settings != null) {
                        try {
                            val llmCategory = MemoryClassifier.classifyWithLLM(value, settings.baseUrl, settings.apiKey, settings.model)
                            if (llmCategory != null) category = llmCategory
                        } catch (_: Exception) {}
                    }
                }
                if (category.isBlank()) category = MemoryClassifier.classifyByKeywords(value)
                val existing = store.load(scope, project).find { it.key == key && it.type == type }
                store.add(key, value, type, category, scope, project)
                val where = if (scope == SCOPE_PROJECT) "项目记忆「$project」" else "长期记忆"
                if (existing != null) "✅ 已更新$where「$key」（分类: $category，原内容已覆盖）"
                else "✅ 已保存$where「$key」（分类: $category）"
            }
            "memory_read" -> {
                val searchKey = (args["key"] as? String)?.trim()
                val filterType = (args["type"] as? String)?.trim()
                val filterCategory = (args["category"] as? String)?.trim()
                val maxResults = ((args["maxResults"] as? Double)?.toInt()) ?: 20
                // 默认同时读长期 + 项目 + 内置规则（未选项目时只有长期+规则）。
                // 规则也纳入搜索：它们已完整注入提示词，但 AI 想精确回查某条时仍可搜。
                val project = AppContextHolder.currentSettings?.currentProject?.trim().orEmpty()
                val all = store.load(SCOPE_AGENT) + store.load(SCOPE_DOCS) + store.load(SCOPE_GLOBAL) +
                    (if (project.isNotBlank()) store.load(SCOPE_PROJECT, project) else emptyList())
                val filtered = all.filter { item ->
                    val matchKey = searchKey.isNullOrBlank() || item.key.contains(searchKey, ignoreCase = true) || item.value.contains(searchKey, ignoreCase = true)
                    val matchType = filterType.isNullOrBlank() || item.type.equals(filterType, ignoreCase = true)
                    val matchCategory = filterCategory.isNullOrBlank() || item.category.equals(filterCategory, ignoreCase = true)
                    matchKey && matchType && matchCategory
                }.sortedByDescending { it.timestamp }
                if (filtered.isEmpty()) "未找到匹配的记忆"
                else filtered.take(maxResults).joinToString("\n") { item ->
                    val time = if (item.timestamp > 0) dateFormat().format(Date(item.timestamp)) else "未知时间"
                    val catIcon = when (item.category) {
                        "preference" -> "❤️"
                        "fact" -> "📋"
                        "task" -> "📌"
                        "code" -> "💻"
                        else -> "📝"
                    }
                    "$catIcon ${item.key} (${item.type}) [${item.category}] [$time]: ${item.value.take(200)}${if (item.value.length > 200) "…" else ""}"
                } + if (filtered.size > maxResults) "\n...（还有 ${filtered.size - maxResults} 条未显示）" else ""
            }
            "memory_delete" -> {
                val key = (args["key"] as? String)?.trim() ?: return@withContext "错误：未提供记忆名称"
                val type = args["type"] as? String ?: "ai"
                val scope = (args["scope"] as? String)?.trim()?.lowercase()
                    ?.takeIf { it == SCOPE_PROJECT } ?: SCOPE_GLOBAL
                val project = AppContextHolder.currentSettings?.currentProject?.trim().orEmpty()
                if (scope == SCOPE_PROJECT && project.isBlank()) {
                    return@withContext "错误：当前未选择项目，无法删除项目记忆。"
                }
                val existing = store.load(scope, project).find { it.key == key && it.type == type }
                if (existing == null) {
                    val where = if (scope == SCOPE_PROJECT) "项目记忆" else "长期记忆"
                    "未找到$where「$key」（类型: $type）"
                } else {
                    store.delete(key, type, scope, project)
                    "✅ 已删除记忆「$key」"
                }
            }
            else -> "错误：不支持的操作"
        }
    }
}
