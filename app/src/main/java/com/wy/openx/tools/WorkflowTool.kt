package com.wy.openx.tools

import com.wy.openx.data.store.Workflow
import com.wy.openx.container.Workspace
import com.wy.openx.data.store.WorkflowStore
import com.wy.openx.data.store.parseWorkflowJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WorkflowTool : AgentTool {

    override val name = "workflow"
    override val description = """管理工作流（一系列按依赖关系自动执行的工具调用）。
支持的操作：
- list: 列出已有工作流
- create: 创建新工作流（定义步骤和依赖关系）
- run: 执行已有工作流（自动按 DAG 拓扑顺序执行）
- delete: 删除工作流

步骤间可通过 ${'$'}{step_id} 引用前置步骤的输出。

【产物存放约定】工作流生成的文档、报告等文件，请写到**当前工作区下的 workflows/ 子目录**下，
不要写到 /root/ 或其它 rootfs 目录：那里的文件在容器 rootfs 里，
设置页「删除当前容器 rootfs,重新安装」会连同一起抹掉；而工作区是独立挂载，跨重装存活。"""
    override val parameters = buildToolParameters(
        ToolParameter("action", ParameterType.STRING, "操作类型", enumValues = listOf("list", "create", "run", "delete")),
        ToolParameter("name", ParameterType.STRING, "工作流名称", required = false),
        ToolParameter("description", ParameterType.STRING, "工作流描述（create 时可选）", required = false),
        ToolParameter("steps", ParameterType.STRING, """步骤定义的 JSON 数组（create 时需要）。每步格式：
{"id": "step1", "tool": "工具名", "args": {"key": "value"}, "dependsOn": []}
dependsOn 可引用前置步骤的 id。args 中可用 ${'$'}{step_id} 引用前置步骤输出。""", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val ctx = AppContextHolder.context ?: return@withContext "错误：应用上下文未初始化"
        // 产物目录预建：UI 侧在 loadWorkflows() 建，AI 侧必须在这里建，否则用 AI 建/跑工作流时目录并不存在
        runCatching {
            Workspace.workflowsDir(ctx, AppContextHolder.currentSettings?.currentProject?.trim().orEmpty().ifEmpty { null })
        }
        val store = WorkflowStore(ctx)
        val toolRegistry = AppContextHolder.toolRegistry ?: return@withContext "错误：ToolRegistry 未初始化"
        val action = args["action"] as? String ?: return@withContext "错误：未指定操作"

        when (action) {
            "list" -> {
                val workflows = store.load()
                if (workflows.isEmpty()) "暂无工作流。可使用 create 创建。"
                else workflows.joinToString("\n") { w ->
                    "📋 ${w.name}${if (w.description.isNotBlank()) ": ${w.description}" else ""}（${w.steps.size} 步）"
                }
            }
            "create" -> {
                val name = (args["name"] as? String)?.trim() ?: return@withContext "错误：未提供名称"
                val description = (args["description"] as? String) ?: ""
                val stepsJson = (args["steps"] as? String)?.trim() ?: return@withContext "错误：未提供步骤定义"
                // 与界面保存走**同一个**解析器：之前这里自己手搓了一套宽松解析，
                // 导致 AI 能存下带自依赖／悬空依赖的工作流，界面不报错、只有运行时才死锁。
                val parsed = parseWorkflowJson(name, description, stepsJson)
                val wf = parsed.workflow
                    ?: return@withContext "错误：工作流定义无效 —— ${parsed.error}。请按提示修正后重试。"
                store.add(wf)
                "✅ 工作流「${wf.name}」已创建（${wf.steps.size} 步）\n步骤: ${wf.steps.joinToString(" -> ") { "${it.id}(${it.tool})" }}"
            }
            "run" -> {
                val name = (args["name"] as? String)?.trim() ?: return@withContext "错误：未提供名称"
                val workflow = store.load().find { it.name == name }
                    ?: return@withContext "错误：未找到工作流「$name」"
                val engine = WorkflowEngine(toolRegistry, AppContextHolder.workflowStepGate)
                engine.execute(workflow)
            }
            "delete" -> {
                val name = (args["name"] as? String)?.trim() ?: return@withContext "错误：未提供名称"
                store.delete(name)
                "✅ 已删除工作流「$name」"
            }
            else -> "错误：不支持的操作「$action」"
        }
    }
}
