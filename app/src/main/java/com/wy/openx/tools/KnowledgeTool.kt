package com.wy.openx.tools

import com.wy.openx.data.store.KnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 知识库工具：查 / 补录 / 删。
 *
 * 与 [MemoryTool] 的分工要在描述里写清楚——两者都能"记住东西"，模型很容易混用：
 * 记忆是少量硬约束（清单已自动注入提示词），知识是大量经验细节（不常驻，需要时来查）。
 */
class KnowledgeTool(private val operation: String) : AgentTool {

    override val definition = when (operation) {
        "knowledge_search" -> ToolDef(
            function = FunctionDef(
                name = "knowledge_search",
                description = "查询本地知识库——从历史对话里沉淀下来的经验、踩过的坑、可复用的做法与项目约定。" +
                    "遇到报错、要动手改代码、或需要回忆「以前是怎么解决的」时，先查这里再动手。" +
                    "与 memory_read 的分工：memory 是少量硬约束/偏好（其清单已自动提供给你），" +
                    "knowledge 是大量经验细节（不自动注入，需要时查）。查不到就如实说没找到，不要编。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "查询词，可用空格分隔多个词（要求每个词都命中）；留空则返回最近更新的条目",
                            "default" to ""
                        ),
                        "limit" to mapOf("type" to "integer", "description" to "最多返回几条（默认 5）", "default" to 5)
                    ),
                    "required" to emptyList<String>()
                )
            )
        )
        "knowledge_save" -> ToolDef(
            function = FunctionDef(
                name = "knowledge_save",
                description = "往知识库补录一条经验（一般不用你手动存：开了「知识提炼」后会自动沉淀）。" +
                    "仅当用户明确说「把这个记进知识库」，或你刚解决了一个值得复用的坑时使用。" +
                    "标题相同会被视为同一条并覆盖更新。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string", "description" to "一句话标题，30 字内，写清「什么情况下会怎样」"),
                        "body" to mapOf("type" to "string", "description" to "正文：现象、根因、做法（可含少量关键代码）"),
                        "tags" to mapOf("type" to "string", "description" to "标签，逗号分隔（可选）", "default" to ""),
                        "id" to mapOf("type" to "string", "description" to "英文短横线 slug；留空则按标题自动生成", "default" to "")
                    ),
                    "required" to listOf("title", "body")
                )
            )
        )
        "knowledge_delete" -> ToolDef(
            function = FunctionDef(
                name = "knowledge_delete",
                description = "从知识库删除一条知识（先 knowledge_search 拿到它的 id）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "id" to mapOf("type" to "string", "description" to "要删除的知识 id")
                    ),
                    "required" to listOf("id")
                )
            )
        )
        else -> ToolDef(
            function = FunctionDef(name = operation, description = "", parameters = emptyMap())
        )
    }

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val ctx = AppContextHolder.context ?: return@withContext "错误：应用上下文未初始化"
        val store = KnowledgeStore(ctx)
        when (operation) {
            "knowledge_search" -> {
                val query = (args["query"] as? String)?.trim().orEmpty()
                val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 20) ?: 5
                val hits = store.search(query, limit)
                if (hits.isEmpty()) {
                    val empty = if (store.loadAll().isEmpty()) {
                        "（知识库目前是空的：可在 设置 → 默认模型 里开启「知识提炼」，或直接让我把某条经验记进去。）"
                    } else ""
                    "知识库里没有匹配「$query」的条目。$empty"
                } else {
                    buildString {
                        append("命中 ${hits.size} 条（正文过长会截断，需要全文可用 file_manager 读下面给的路径）：\n\n")
                        hits.forEach { e ->
                            append("### ").append(e.title).append('\n')
                            append("- id: ").append(e.id)
                            if (e.tags.isNotEmpty()) append("　标签: ").append(e.tags.joinToString("、"))
                            if (e.source.isNotBlank()) append("　来源: ").append(e.source)
                            append('\n')
                            if (e.path.isNotBlank()) append("- 文件: ").append(containerPath(ctx.filesDir.absolutePath, e.path)).append('\n')
                            append('\n')
                            append(if (e.body.length > 900) e.body.take(900) + "\n…（已截断）" else e.body)
                            append("\n\n---\n\n")
                        }
                    }
                }
            }
            "knowledge_save" -> {
                val title = (args["title"] as? String)?.trim().orEmpty()
                val body = (args["body"] as? String)?.trim().orEmpty()
                if (title.isBlank() || body.isBlank()) return@withContext "错误：title 与 body 都不能为空"
                val id = (args["id"] as? String)?.trim().orEmpty()
                val tags = (args["tags"] as? String)?.split(',', '，', ' ')
                    ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                val entry = store.save(id = id, title = title, tags = tags, body = body, source = "AI 手动补录")
                if (entry == null) "错误：保存失败（标题或正文为空）"
                else "✅ 已保存到知识库「${entry.title}」（id: ${entry.id}）\n文件：${containerPath(ctx.filesDir.absolutePath, entry.path)}"
            }
            "knowledge_delete" -> {
                val id = (args["id"] as? String)?.trim().orEmpty()
                if (id.isBlank()) return@withContext "错误：未提供 id"
                if (store.delete(id)) "✅ 已删除知识「$id」" else "未找到知识「$id」"
            }
            else -> "未知操作: $operation"
        }
    }

    /**
     * 把知识文件路径换成**容器视角**。
     *
     * [KnowledgeStore] 在宿主进程里跑，`entry.path` 是宿主 filesDir 下的绝对路径
     * （`/data/user/0/<包名>/files/openx/knowledge/...`）；而调这个工具的 AI 活在容器里，
     * 那里同一个文件是 `/root/.openx/knowledge/...`。不换的话 AI 拿着路径去读会报文件不存在。
     */
    private fun containerPath(filesDir: String, hostPath: String): String =
        if (hostPath.startsWith(filesDir)) {
            "/root" + hostPath.removePrefix(filesDir).replaceFirst("/openx", "/.openx")
        } else hostPath
}
