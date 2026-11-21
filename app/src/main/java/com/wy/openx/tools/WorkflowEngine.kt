package com.wy.openx.tools

import com.google.gson.Gson
import com.wy.openx.data.store.Workflow
import com.wy.openx.data.store.WorkflowStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * 单步执行前的权限裁决。返回 `null` 放行；返回非 null 的字符串为**拒绝原因**（会写进报告）。
 *
 * 用「原因」而不是 Boolean，是为了区分「用户点了拒绝」和「只读档禁止」这两种完全不同的拒绝。
 */
typealias WorkflowStepGate = suspend (toolName: String, toolArgs: String) -> String?

class WorkflowEngine(
    /**
     * 工具执行出口。抽成 lambda 而不是直接持 [ToolRegistry]：一是让权限闸门可单测
     * （`ToolRegistry` 构造需要 Android `Context`，JVM 单测建不出来），
     * 二是执行与授权解耦，将来换执行后端不影响闸门。
     */
    private val executeTool: suspend (toolName: String, toolArgs: String) -> String,
    private val gate: WorkflowStepGate? = null
) {
    constructor(
        registry: ToolRegistry,
        gate: WorkflowStepGate? = null
    ) : this(
        { name, argsJson ->
            registry.executeToolCall(ToolCall(
                id = "wf_call",
                function = ToolCallFunction(name = name, arguments = argsJson)
            ))
        },
        gate
    )

    private val gson = Gson()

    suspend fun execute(workflow: Workflow, maxParallel: Int = 3): String = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()
        val steps = workflow.steps
        val executed = mutableSetOf<String>()
        val startTime = System.currentTimeMillis()

        try {
            while (executed.size < steps.size) {
                val ready = steps.filter { step ->
                    step.id !in executed && step.dependsOn.all { it in executed }
                }
                if (ready.isEmpty()) {
                    val stuck = steps.filter { it.id !in executed }
                    return@withContext buildString {
                        appendLine("❌ 工作流死锁")
                        appendLine("以下步骤无法执行（依赖不满足）:")
                        stuck.forEach { s ->
                            val missing = s.dependsOn.filterNot { it in executed }
                            appendLine("  - ${s.id} (${s.tool}): 缺少依赖 $missing")
                        }
                    }
                }

                val batch = ready.take(maxParallel)
                // 闸门要在**派发之前**逐个过：不能先 async 再问，否则拒绝也已经把活发出去了。
                val argsByStep = mutableMapOf<String, String>()
                for (step in batch) {
                    val resolvedArgs = gson.toJson(resolveArgs(step.args, results))
                    val denial = gate?.invoke(step.tool, resolvedArgs)
                    if (denial != null) {
                        return@withContext buildString {
                            appendLine("⛔ 工作流「${workflow.name}」已被中止")
                            appendLine("步骤 ${step.id} 需要执行工具「${step.tool}」，但被拒绝：$denial")
                            appendLine("已完成的步骤不会回滚，修改权限设置后可重新运行。")
                        }
                    }
                    argsByStep[step.id] = resolvedArgs
                }

                val deferreds = batch.map { step ->
                    async {
                        val result = executeTool(step.tool, argsByStep.getValue(step.id))
                        step.id to result
                    }
                }

                val batchResults = deferreds.awaitAll()
                batchResults.forEach { (id, result) ->
                    results[id] = result
                    executed.add(id)
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            buildString {
                appendLine("✅ 工作流「${workflow.name}」执行完成 (${elapsed}ms)")
                appendLine("总步骤: ${steps.size}")
                steps.forEach { step ->
                    val r = results[step.id] ?: "(无输出)"
                    val truncated = if (r.length > 1500) r.take(1500) + "\n[截断...]" else r
                    appendLine("\n--- ${step.id} [${step.tool}] ---")
                    appendLine(truncated)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "❌ 工作流执行失败: ${e.message ?: "未知错误"}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveArgs(args: Map<String, Any>, results: Map<String, String>): Map<String, Any> {
        return args.mapValues { (_, v) ->
            when (v) {
                is String -> {
                    val vStr = v as String
                    var result = vStr
                    for ((id, res) in results) {
                        result = result.replace("\${$id}", res.take(8000))
                    }
                    result
                }
                is Map<*, *> -> resolveArgs(v as Map<String, Any>, results)
                is List<*> -> v.map { elem ->
                    if (elem is Map<*, *>) resolveArgs(elem as Map<String, Any>, results) else elem
                }
                else -> v
            }
        }
    }
}
