package com.wy.openx.tools

/**
 * 任务面板（待办清单）工具——**快照式接口**
 *
 * ## 为什么用快照式，而不是「逐条增删改」三个工具
 * 旧实现是 `add_task` / `update_task_status` / `remove_task` 三个工具逐条操作。它在
 * 「并行执行批内工具」开启时会出两个问题（用户先后都报过）：
 * 1. **丢**：两个 `add_task` 并发读-改-写同一份列表，后写者覆盖先写者 → 建 4 个只显示 3 个；
 * 2. **乱**：并发执行下谁先写入由线程抢占决定 → 面板顺序随机。
 *
 * 快照式的语义是「**用这一份完整清单替换当前清单**」：模型一次调用带齐全部条目，
 * **数组下标即顺序**。于是：
 * - 顺序**结构化确定**，与执行时序无关（并行也不会乱）；
 * - 增 / 删 / 改状态都是同一件事（提交新清单），无需 `todo_id`，
 *   也不需要模型记住「我刚才建了哪几条」；
 * - 天然原子：整体替换，不存在「半个操作」。
 *
 * ## 
 * - 我们的
 *   [com.wy.openx.data.model.TaskItem] 只有一个 `description`，故以它作为条目标题。
 *   为兼容模型可能写 `subject`，解析时**两者都接受**。
 * - 参考实现的状态只有 pending / in_progress / completed；我们多一个 cancelled
 *   （面板上「✕ 关闭」的语义），这里保留支持。
 */
class TaskTool : AgentTool {

    override val definition = ToolDef(
        function = FunctionDef(
            name = "todo",
            description = "用一份**完整**的待办清单替换当前会话的任务面板。只需提交全部条目，" +
                "无需 action / id / 逐条更新：新增就往数组里加、删除就不写它、改状态就改它的 status。" +
                "**顺序即数组下标顺序**（面板按此显示）。传空数组表示清空清单。" +
                "适合在开始多步骤工作前先列出计划，并在每完成一步后提交更新后的清单。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "items" to mapOf(
                        "type" to "array",
                        "description" to "当前完整的待办清单（不是增量）。每项可为字符串" +
                            "（直接作为待办标题），或含 description 的对象。",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "description" to mapOf(
                                    "type" to "string",
                                    "description" to "待办标题，简明说明要做什么（祈使句，如「分析项目结构」）"
                                ),
                                "status" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("pending", "in_progress", "completed", "cancelled"),
                                    "description" to "状态，默认 pending。in_progress=正在做；" +
                                        "completed=已完成；cancelled=已取消。"
                                )
                            ),
                            "required" to listOf("description")
                        )
                    )
                ),
                "required" to listOf("items")
            )
        )
    )

    override suspend fun execute(args: Map<String, Any>): String {
        // 实际执行委托给 MainViewModel 注册的 taskExecutor（操作任务面板并返回真实结果），
        // 使模型能得到真实执行反馈；未注册时给出明确错误，避免静默失败。
        val executor = AppContextHolder.taskExecutor
            ?: return "错误：任务面板控制器未初始化，无法更新任务。请稍后重试。"
        return executor("todo", args)
    }
}
