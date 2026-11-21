package com.wy.openx.tools

import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * `subagent_dispatch` —— 把若干相互独立的小任务交给**具有隔离上下文**的子代理并行执行，
 * 等全部结束后把聚合结果交回父级。
 *
 * 与老 `call_sub_agent` 的本质差别：
 *  
 *    自建 OkHttpClient 绕过全部提供商配置、10 轮硬截断、无进度、无并发、无角色权限。
 *  - 现在：多任务并行、每个子任务独立上下文 + 独立系统提示、复用主流水线（含流式/缓存/可取消）、
 *    角色级工具白名单 + 执行前二次校验、实时进度回传、可配并发度与轮次/时长预算。
 *
 * 参数（兼容老的字符串数组写法）：
 *   tasks: ["做 A", "做 B"]                                   ← 旧写法
 *   tasks: [{profileId: "explore", instruction: "..."}, ...]  ← 新写法（推荐）
 *   defaultProfileId  任务未指定 profileId 时使用，默认 general
 *   concurrency       可选并发度（上限 3）
 *   mergeInstruction  可选的聚合要求
 */
class SubAgentTool : AgentTool {

    override val definition = ToolDef(
        function = FunctionDef(
            name = "subagent_dispatch",
            description = "把多个**相互独立**的小任务分派给子代理并行执行，等它们全部完成后返回聚合结果。" +
                "子代理拥有独立的系统提示与隔离上下文：不会看到主会话历史，也不会互相干扰。" +
                "只有当任务确实可以拆开并行、或需要大量只读搜索把主上下文撑爆时才用它；单一连续任务不要分派。" +
                "可用角色：" + SubAgentProfiles.all().joinToString("，") { "${it.id}=${it.displayName}（${it.description}）" },
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "tasks" to mapOf(
                        "type" to "array",
                        "description" to "要分派的子任务列表。每项 instruction 必须**自足**：子代理看不到主会话上下文，" +
                            "不能写「继续刚才那个」「看看上面提到的文件」这类依赖上下文的说法。",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "instruction" to mapOf("type" to "string", "description" to "子任务的完整、自足指令"),
                                "profileId" to mapOf(
                                    "type" to "string",
                                    "enum" to SubAgentProfiles.ids(),
                                    "description" to "角色：general 继承父级全部能力；explore 只读探索（找文件/搜代码/读网页，不改任何东西）；" +
                                        "memory-curator 整理与沉淀记忆；planner 不调用任何工具、只产出计划。默认 general。"
                                )
                            ),
                            "required" to listOf("instruction")
                        )
                    ),
                    "defaultProfileId" to mapOf(
                        "type" to "string", "enum" to SubAgentProfiles.ids(),
                        "description" to "子任务省略 profileId 时使用的角色，默认 general。"
                    ),
                    "concurrency" to mapOf("type" to "integer", "description" to "可选并发度（1-3），默认按任务数但封顶 3。"),
                    "mergeInstruction" to mapOf("type" to "string", "description" to "对聚合结果的额外要求，可选。")
                ),
                "required" to listOf("tasks")
            )
        )
    )

    override suspend fun execute(args: Map<String, Any>): String {
        try {
            val defaultProfileId = (args["defaultProfileId"] as? String)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "general"
            val specs = parseTasks(args["tasks"], defaultProfileId)
            if (specs.isEmpty()) {
                return "错误：tasks 为空或格式不正确。tasks 必须是字符串数组，或 {instruction, profileId} 对象数组。"
            }
            val requested = when (val c = args["concurrency"]) {
                is Double -> c.toInt()
                is Int -> c
                is String -> c.trim().toIntOrNull()
                else -> null
            }
            val concurrency = resolveSubAgentConcurrency(requested, specs.size)
            val mergeInstruction = (args["mergeInstruction"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

            val results = SubAgentDispatcher.dispatch(
                tasks = specs,
                concurrency = concurrency,
                runnerProvider = { AppContextHolder.subAgentRunner },
                onEvent = { _, _, _, _, summary, _, _ ->
                    // 进度以「工具执行中」的形式回传给 UI 卡片（父级的 subagent_dispatch 调用）
                    AppContextHolder.subAgentProgressSink?.invoke(summary)
                }
            )

            return buildResultPayload(results, concurrency, mergeInstruction)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "子代理分派失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun parseTasks(raw: Any?, defaultProfileId: String): List<SubAgentTaskSpec> {
        val array: JsonArray = when (raw) {
            is JsonArray -> raw
            is List<*> -> runCatching { gson.toJsonTree(raw).asJsonArray }.getOrElse { JsonArray() }
            is String -> runCatching { JsonParser.parseString(raw).asJsonArray }.getOrElse { JsonArray() }
            else -> JsonArray()
        }
        val out = mutableListOf<SubAgentTaskSpec>()
        for (el in array) {
            when {
                el.isJsonPrimitive -> {
                    val text = el.asString.trim()
                    if (text.isNotEmpty()) out.add(SubAgentTaskSpec(defaultProfileId, text))
                }
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    val profile = obj.get("profileId")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: defaultProfileId
                    val instruction = (obj.get("instruction") ?: obj.get("task"))
                        ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    if (instruction.isNotEmpty()) out.add(SubAgentTaskSpec(profile, instruction))
                }
            }
        }
        return out
    }

    /**
     * 聚合结果：**纯 JSON**，不要在 JSON 前后拼接任何自然语言。
     *
     * 原因：UI 状态圆点与占位文案的唯一判定来源是 [com.wy.openx.data.util.ToolResultStatus.isError]，
     * 它先看结构化 `ok` 字段，只有取不到才回退「error/failed/失败/错误」等**关键词子串匹配**。
     * 纯文本聚合会误判——子代理的结论里只要出现「这个函数会失败」「修复错误」这类正常措辞，
     * 圆点就变红，而模型那边却说调用成功（此前版本末尾那句「若有子代理失败…」就会把成功的调用判成失败）。
     * 走 JSON + `ok` 后判定精确，模型侧也拿到结构化结果。
     */
    private fun buildResultPayload(
        results: List<SubAgentRunResult>,
        concurrency: Int,
        mergeInstruction: String?
    ): String {
        val ok = results.count { it.status == "completed" }
        val allOk = ok == results.size
        val root = com.google.gson.JsonObject()
        root.addProperty("ok", allOk)
        root.addProperty("summary", "子代理执行完毕：成功 $ok/${results.size}（并发上限 $concurrency）")
        root.addProperty("count", results.size)
        root.addProperty("succeeded", ok)
        root.addProperty("concurrency", concurrency)
        if (!mergeInstruction.isNullOrBlank()) root.addProperty("mergeInstruction", mergeInstruction)
        val arr = com.google.gson.JsonArray()
        results.forEachIndexed { i, r ->
            val o = com.google.gson.JsonObject()
            o.addProperty("taskIndex", i)
            o.addProperty("subagentId", r.subagentId)
            o.addProperty("profileId", r.profileId)
            o.addProperty("displayName", SubAgentProfiles.get(r.profileId).displayName)
            o.addProperty("status", r.status)
            o.addProperty("result", r.finalContent)
            val tools = com.google.gson.JsonArray()
            r.toolCallSummaries.distinct().forEach { tools.add(it) }
            o.add("toolCalls", tools)
            o.addProperty("error", r.errorMessage)
            arr.add(o)
        }
        root.add("results", arr)
        root.addProperty(
            "instruction",
            "请基于 results 数组里的 result 字段向用户汇总每个子任务的结论（含关键文件路径）。" +
                "对 status 不是 completed 的子任务，如实说明其原因，不要假装已完成。"
        )
        return root.toString()
    }

    private companion object {
        val gson = com.google.gson.Gson()
    }
}

/** 兼容旧提示词：若模型仍按老名字调用，映射到新工具。 */
internal fun legacySubAgentToolAlias(raw: String): String? = when (raw.removePrefix("functions.").trim()) {
    "call_sub_agent", "talk_to_agent" -> "subagent_dispatch"
    else -> null
}
