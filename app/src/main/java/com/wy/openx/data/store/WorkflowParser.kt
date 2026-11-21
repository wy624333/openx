package com.wy.openx.data.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 解析结果：要么拿到工作流，要么给出可读错误。
 */
data class WorkflowParseResult(
    val workflow: Workflow? = null,
    val error: String? = null
)

private val gsonForWorkflows = Gson()

/**
 * 解析并校验工作流定义（纯函数，供 UI 表单与 AI 工具共用）。
 *
 * **为什么要共用**：之前 UI 保存走这套校验、AI 的 `workflow create` 却自己手搓了一遍
 * 宽松解析，结果 AI 能存下带自依赖／悬空依赖的工作流，界面毫无提示，只有运行时报
 * 「工作流死锁」才暴露。同一个入口必须只有一套规则，否则校验等于形同虚设。
 *
 * 校验内容：JSON 语法、非空、步骤 id 重复、缺 tool、缺 id 按位置补 `step_N`、
 * 依赖指向不存在的步骤、依赖自己。
 *
 * **没有拦环形依赖**（a→b→a，各依赖都存在）：如实放行，交给 [com.wy.openx.tools.WorkflowEngine]
 * 执行时判死锁并报出缺哪些依赖。保存期做环检测需要额外的 DFS，两边行为保持一致更重要。
 */
fun parseWorkflowJson(name: String, description: String, stepsJson: String): WorkflowParseResult {
    val steps: List<Map<String, Any>>? = try {
        gsonForWorkflows.fromJson(
            stepsJson.trim(),
            object : TypeToken<List<Map<String, Any>>>() {}.type
        )
    } catch (e: Exception) {
        return WorkflowParseResult(error = "步骤 JSON 解析失败：${e.message?.take(120) ?: "格式无效"}")
    }
    if (steps == null) return WorkflowParseResult(error = "步骤 JSON 解析结果为空，应为 JSON 数组")
    if (steps.isEmpty()) return WorkflowParseResult(error = "至少需要 1 个步骤")

    // 用普通 for 循环而不是 forEachIndexed：校验失败要**带错误信息跳出**，
    // 而 lambda 里的 return 只能返回 lambda 自己（Kotlin 不允许非局部返回）。
    val parsed = mutableListOf<WorkflowStep>()
    val ids = mutableSetOf<String>()
    for (i in steps.indices) {
        val s = steps[i]
        val id = (s["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "step_${i + 1}"
        if (!ids.add(id)) return WorkflowParseResult(error = "步骤 id 重复：$id")
        val tool = (s["tool"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return WorkflowParseResult(error = "第 ${i + 1} 步未指定 tool")
        @Suppress("UNCHECKED_CAST")
        val args = (s["args"] as? Map<String, Any>) ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val dependsOn = (s["dependsOn"] as? List<String>) ?: emptyList()
        parsed.add(
            WorkflowStep(
                id = id,
                tool = tool,
                args = args,
                dependsOn = dependsOn.map { it.trim() },
                description = (s["description"] as? String) ?: ""
            )
        )
    }

    val missing = parsed.flatMap { step -> step.dependsOn.filterNot { it in ids }.map { step.id to it } }
    if (missing.isNotEmpty()) {
        return WorkflowParseResult(
            error = "依赖了不存在的步骤：" + missing.joinToString("、") { (from, to) -> "$from → $to" }
        )
    }
    for (step in parsed) {
        if (step.id in step.dependsOn) return WorkflowParseResult(error = "步骤 ${step.id} 依赖了自己")
    }

    return WorkflowParseResult(
        workflow = Workflow(
            name = name.trim(),
            description = description.trim(),
            steps = parsed
        )
    )
}

fun stringifyWorkflowSteps(steps: List<WorkflowStep>): String =
    gsonForWorkflows.toJson(steps)
