package com.wy.openx.tools

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wy.openx.plugin.PluginHookManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections


class ToolRegistry(private val context: Context) {
    private val gson = Gson()

    /**
     * 工具表。
     *
     * 写方不是只有 init：插件引擎在 Dispatchers.IO 上启停/重载，随之 register/remove；
     * 读方却在主线程（上下文用量估算）与子代理线程上。裸 LinkedHashMap 边写边遍历会撞
     * ConcurrentModificationException（2.9.5 真机崩溃：getApiTools ← toolSchemaTokens）。
     * 故：单次读写交给同步包装，任何**遍历**先取快照（见下面几个快照函数）。
     */
    private val tools: MutableMap<String, AgentTool> = Collections.synchronizedMap(LinkedHashMap())

    /** 遍历用快照：synchronizedMap 只保证单次调用原子，遍历必须自己持锁取一份。 */
    private fun toolSnapshot(): List<AgentTool> = synchronized(tools) { tools.values.toList() }

    private fun toolEntries(): List<Map.Entry<String, AgentTool>> =
        synchronized(tools) { tools.entries.toList() }

    private fun toolNames(): List<String> = synchronized(tools) { tools.keys.toList() }

    private companion object {
        /**
         * 写文件 / 改文件 / 执行命令类工具：规划模式（plan）只做分析与任务拆解，不下发这些。
         * 注意与「只读但可查」的工具区分——file_manager 的 read/list/info 仍是允许的，
         * 故此处只列明确的写/执行入口。
         */
        val WRITE_AND_EXEC_TOOLS = setOf(
            "writeFile", "appendFile", "editFile", "batch_edit", "batch_replace",
            "scaffold", "terminal", "shizuku", "git",
            "todo",
            "memory_save", "memory_delete", "skill_add", "skill_delete",
            "knowledge_save", "knowledge_delete"
        )

        /**
         * 任务面板类工具（快照式：一次提交完整清单）。
         *
         * 单列一份是因为它有一条**独立的**语义要求：面板顺序 = 提交数组的下标顺序，
         * 因此**同一批里出现多个 todo 调用**时必须串行（后一个会整体替换前一个的结果），
         * 否则两次替换的先后由线程抢占决定，最终留下哪份清单是不确定的。
         * 详见 `ChatRepository` 里 `parallelMode` 处的注释。
         */
        val TASK_TOOLS = setOf("todo")

        /** 量化/交易类工具：体量大，仅股票模式下发声。 */
        val STOCK_TOOLS = setOf("stock", "quant", "portfolio", "signal_tracker")
    }

    fun register(tool: AgentTool) {
        val name = tool.definition.function.name
        // 重名判断与写入放同一把锁里：插件重载会并发调用，拆开会漏掉覆盖警告
        synchronized(tools) {
            if (tools.containsKey(name)) {
                Log.w("ToolRegistry", "工具「$name」被重复注册，已覆盖内置实现，请检查来源")
            }
            tools[name] = tool
        }
    }

    /**
     * 按名字移除工具。插件卸载时用：只从 JS 侧删 handler 不够——
     * 工具还留在 map 里，模型仍会看到它、调用后只能拿到 handler-not-found。
     *
     * 只允许移除**非内置**工具（[builtinNames] 快照在 init 时记录），
     * 防止插件用重名把内置工具挤掉后再调这个方法把宿主能力删掉。
     * @return 是否实际移除
     */
    fun remove(name: String): Boolean {
        val clean = name.removePrefix("functions.").trim()
        if (clean in builtinNames) {
            Log.w("ToolRegistry", "拒绝移除内置工具「$clean」")
            return false
        }
        return tools.remove(clean) != null
    }

    /** 内置工具名快照：init() 结束时采集，用于把「宿主的」和「插件的」区分开。 */
    private val builtinNames = mutableSetOf<String>()

    /** 内置工具名（只读，供插件运行时判断冲突）。 */
    fun builtinToolNames(): Set<String> = builtinNames.toSet()

    fun init() {
        AppContextHolder.context = context
        register(WebSearchTool())
        register(WebFetchTool())
        register(FileAnalyzerTool(context))
        register(FileManagerTool(context))
        register(AppTool(context))
        register(PhoneOpTool())
        register(DeviceTool())
        register(MemoryTool("memory_save"))
        register(MemoryTool("memory_read"))
        register(MemoryTool("memory_delete"))
        register(KnowledgeTool("knowledge_search"))
        register(KnowledgeTool("knowledge_save"))
        register(KnowledgeTool("knowledge_delete"))
        register(SkillTool("skill_add"))
        register(SkillTool("skill_delete"))
        register(SkillTool("skill_list"))
        register(SkillTool("skill_load"))
        register(SubAgentTool())
        register(UserQuestionTool())
        register(GitTool())
        register(ScaffoldTool())
        register(BatchReplaceTool())
        register(ReadFileTool())
        register(EditFileTool())
        register(WriteFileTool())
        register(AppendFileTool())
        register(ViewImageTool())
        register(GenerateImageTool())
        register(ExploreTool())
        register(BatchEditTool())
        register(SearchCodeTool())
        register(HttpClientTool())
        register(SqliteBrowserTool())
        register(ScreenshotTool())
        register(BrowserTool())
        register(StockTool())
        register(QuantTool())
        register(PortfolioTool())
        register(SignalTracker())
        register(TaskTool())
        // 新增工具
        register(TerminalTool())
        register(RemoteSshTool())
        register(ShizukuTool())
        register(McpTool())
        register(WorkflowTool())

        // init 结束才采集内置快照：插件运行时据此判断重名冲突，
        // 也防止插件卸载时误删宿主能力。必须在所有 register 之后。
        builtinNames.clear()
        builtinNames.addAll(toolNames())
    }

    fun getApiTools(
        enableWebSearch: Boolean = true,
        enableFileTools: Boolean = true,
        enableMultiAgent: Boolean = false,
        agentMode: String = "build",
        permissionMode: String = "allow"
    ): List<ToolDef> = toolSnapshot().filter { tool ->
        val name = tool.definition.function.name
        val allowedByMode = when {
            // 只读档：不下发写/执行类工具，模型无从调用（比「下发后拒绝」更省轮次）。
            // 权限档位统一接管，规划模式不再单独过滤（避免两处规则打架）。
            permissionMode == "readonly" -> name !in WRITE_AND_EXEC_TOOLS
            // 量化工具只在股票模式下发声（StockTool/QuantTool 体量大，其他模式纯占额度）
            agentMode != "stock" -> name !in STOCK_TOOLS
            else -> true
        }
        allowedByMode && when (name) {
            "web_search", "web_fetch" -> enableWebSearch
            "file_analyzer", "file_manager" -> enableFileTools
            "subagent_dispatch" -> enableMultiAgent
            else -> true
        }
    }.map { tool ->
        // 出口统一规范化 schema：剥离 default 等非标准字段、统一 type 小写，
        // 保证严格校验的第三方网关/聚合 API 能识别工具（默认值由工具内部兜底，不影响执行）
        tool.definition.copy(
            function = tool.definition.function.copy(
                parameters = normalizeToolSchema(tool.definition.function.parameters)
            )
        )
    }

    fun hasTools(): Boolean = tools.isNotEmpty()

    /**
     * 该工具是否属于「写文件/执行命令」类——即权限档为「弹窗确认」时需要弹窗询问的类别。
     * 只读类工具（web_search / 读取文件 / 查询等）不需确认，避免每次调用都打扰用户。
     */
    fun isWriteOrExecTool(name: String): Boolean = name.removePrefix("functions.").trim() in WRITE_AND_EXEC_TOOLS

    /**
     * 是否为任务面板类工具（add_task / update_task_status / remove_task）。
     * 供 `ChatRepository` 判定「本批是否必须整批串行」——见 [TASK_TOOLS] 注释。
     */
    fun isTaskTool(name: String): Boolean = name.removePrefix("functions.").trim() in TASK_TOOLS

    suspend fun executeToolCall(toolCall: ToolCall): String {
        val cleanName = toolCall.function.name.removePrefix("functions.").trim()
        if (cleanName.isBlank()) {
            Log.w("ToolRegistry", "工具调用名为空：模型/网关未返回工具名，本轮调用无法路由执行")
            return "错误：模型返回的工具调用缺少工具名，无法执行。请重试，或检查当前模型/网关是否正确支持并向模型透传了工具列表。"
        }
        // 老提示词/历史消息里可能残留 call_sub_agent / talk_to_agent，统一映射到新工具
        val tool = resolveTool(legacySubAgentToolAlias(cleanName) ?: cleanName)
            ?: return buildUnknownToolError(cleanName)

        val argsType = object : TypeToken<Map<String, Any>>() {}.type
        val args: Map<String, Any>
        try {
            args = gson.fromJson(toolCall.function.arguments, argsType) ?: emptyMap()
        } catch (e: Exception) {
            // 大内容工具参数（尤其 writeFile/editFile 的 content）常被模型输出 token 截断，
            // 导致 arguments 是不完整 JSON。
            val repaired = repairTruncatedJson(toolCall.function.arguments)
                ?.let { runCatching { gson.fromJson<Map<String, Any>>(it, argsType) }.getOrNull() }

            // ⚠ 写类工具**绝不能**用补全结果继续执行。
            // 补全只能补上「收尾引号 + 括号」，补不出被截断掉的内容 —— 执行等于**静默写入半截文件**，
            // 比报错更糟（用户以为写好了，实际文件残缺）。故这里明确拒绝，并告诉 AI 怎么正确地分块。
            if (cleanName in WRITE_AND_EXEC_TOOLS) {
                Log.w("ToolRegistry", "工具「$cleanName」arguments 被截断，拒绝执行（写类工具不补全）")
                return buildTruncatedWriteHint(cleanName)            }

            if (repaired != null) {
                Log.w("ToolRegistry", "工具「$cleanName」arguments 被截断，已宽容修复后执行")
                return executeMapped(cleanName, tool, repaired)
            }
            Log.w("ToolRegistry", "工具「$cleanName」参数解析失败: ${e.message}, 原文: ${toolCall.function.arguments?.take(200)}")
            return "错误：工具「$cleanName」的参数不是合法 JSON，已忽略本次调用。请检查 arguments 格式。"
        }

        // 统一调度到 IO 线程 + 异常兜底，杜绝主线程 IO 与未捕获异常冲垮聊天管线
        return executeMapped(cleanName, tool, args)
    }

    private suspend fun executeMapped(cleanName: String, tool: AgentTool, args: Map<String, Any>): String =
        withContext(Dispatchers.IO) {
            // 七期钩子。这里处在**所有工具执行的唯一汇合点**上（含主链路、并行模式、子代理），
            // 所以 onBeforeTool / onAfterTool 只需改这一处就全覆盖。
            // 没插件影响时完全不序列化参数，保持原有开销。
            val hookActive = PluginHookManager.hasAny(PluginHookManager.ON_BEFORE_TOOL) ||
                PluginHookManager.hasAny(PluginHookManager.ON_AFTER_TOOL)
            val argsJson = if (hookActive) gson.toJson(args) else ""

            if (hookActive) {
                // 插件否决：直接当工具结果交给模型，不执行真实工具
                PluginHookManager.beforeTool(cleanName, argsJson)?.let { blocked ->
                    Log.i("ToolRegistry", "工具「$cleanName」被插件钩子拦下")
                    return@withContext "操作被插件拦截：$blocked"
                }
            }

            val result = try {
                tool.execute(args)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ToolRegistry", "工具「$cleanName」执行异常", e)
                "错误：工具「$cleanName」内部异常：${e.message ?: e.javaClass.simpleName}"
            }

            if (hookActive) {
                PluginHookManager.afterTool(cleanName, argsJson, result) ?: result
            } else {
                result
            }
        }

    /**
     * 宽容修复被截断的工具参数 JSON。
     *
     * 大内容工具调用（writeFile/editFile 的 content 常达数百上千行）被模型输出 token
     * 截断时，arguments 往往以三种形态中断：
     *  1. 内容字符串被截断：`{"path":"a/b","content":"abc...`（缺收尾引号与括号）
     *  2. 结构被截断：`{"path":"a/b","content":"abc"`（缺收尾大括号）
     *  3. 完全无截断则是合法 JSON（此处不会进来）。
     *
     * 修复策略：在截断点补上未闭合的字符串收尾引号，再按结构深度补 `}`/`]`，
     * 每形成一个候选就解析验证，成功即返回。
     */
    private fun repairTruncatedJson(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        var inString = false
        var escaped = false
        var depth = 0
        for (c in s) {
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
        }

        val objType = object : TypeToken<Map<String, Any>>() {}.type
        val valid: (String) -> Boolean = { t ->
            runCatching { gson.fromJson<Map<String, Any>>(t, objType) }.getOrNull() != null
        }

        var candidate = s
        if (inString) candidate += "\""

        // 构造若干候选，顺序尝试：
        val candidates = LinkedHashSet<String>()
        candidates.add(candidate)
        candidates.add(candidate.trimEnd(',') + "}")
        candidates.add(candidate.trimEnd(',') + "]}")
        candidates.add(candidate.trimEnd(',') + "}")
        // 若结构仍嵌套（数组等），按深度逐层补
        candidates.add(candidate.trimEnd(',') + "]".repeat(depth.coerceAtLeast(0)))
        candidates.add(candidate.trimEnd(',') + "}".repeat(depth.coerceAtLeast(0)))
        candidates.add(candidate.trimEnd(',') + "]".repeat(depth.coerceAtLeast(0)) + "}".repeat(depth.coerceAtLeast(0)))

        return candidates.firstOrNull { valid(it) }
    }

    /**
     * 宽容的工具名解析：先精确匹配，再做忽略大小写/分隔符匹配，最后做包含匹配。
     * 适配不同模型/网关返回的带前后缀或变体工具名（如 "web_search"、"Web Search"、"functions.mcp"）。
     */
    private fun resolveTool(rawName: String): AgentTool? {
        val name = rawName.removePrefix("functions.").trim()
        tools[name]?.let { return it }
        val norm = normalizeToolName(name)
        val entries = toolEntries()
        for ((key, tool) in entries) if (normalizeToolName(key) == norm) return tool
        for ((key, tool) in entries) {
            val k = normalizeToolName(key)
            if (norm.isNotEmpty() && (norm.contains(k) || k.contains(norm))) return tool
        }
        return null
    }

    private fun normalizeToolName(s: String): String =
        s.lowercase().replace("_", "").replace("-", "").replace(" ", "").trim()

    private fun buildUnknownToolError(name: String): String {
        val available = toolNames().sorted().joinToString(", ")
        Log.w("ToolRegistry", "未找到工具「$name」，已注册工具: $available")
        // 附上可用列表，让模型下次改用真实工具名
        return "错误：未找到工具「$name」。可用工具列表：$available。请从列表中选择真实存在的工具名重新调用。"
    }

    fun getToolNames(): List<String> = toolNames()

    /**
     * 从工具调用参数中提取本批将要实际写入的文件路径（供并行执行前打快照）。
     * 预览类调用（batch_edit.dry_run）不会写文件，返回空列表。
     */
    fun collectWriteTargets(toolCall: ToolCall): List<String> {
        val cleanName = toolCall.function.name.removePrefix("functions.")
        val args: Map<String, Any> = try {
            gson.fromJson(
                toolCall.function.arguments,
                object : TypeToken<Map<String, Any>>() {}.type
            ) ?: emptyMap()
        } catch (_: Exception) {
            return emptyList()
        }
        return when (cleanName) {
            // editFile 与 writeFile 都会实际写盘；editFile 无预览态（其 edits 一次性原子生效）
            "editFile", "writeFile" -> listOfNotNull((args["path"] as? String)?.trim())
            "batch_edit" -> {
                if ((args["dry_run"] as? Boolean) == true) emptyList()
                else {
                    val dir = (args["dir"] as? String)?.trim() ?: ""
                    val edits = args["edits"]
                    val list = when (edits) {
                        is List<*> -> edits.filterIsInstance<Map<*, *>>()
                        else -> emptyList()
                    }
                    list.mapNotNull { e ->
                        val p = (e["path"] as? String)?.trim()
                        if (p.isNullOrBlank()) null
                        else if (dir.isBlank()) p
                        else "$dir/${p.removePrefix("/")}"
                    }
                }
            }
            else -> emptyList()
        }
    }
}


/**
 * 写类工具参数被截断时回给模型的提示。
 *
 * 关键：必须**明确说清发生了什么 + 给出可执行的下一步**，否则模型会原样重试同一次超大写入、
 * 再次被截断，陷入死循环（这正是「长文件写不进去、反复报错」的成因）。
 *
 * 顶层 internal（而非 ToolRegistry 私有方法）：纯字符串逻辑、无需 Context，便于单测覆盖。
 */
internal fun buildTruncatedWriteHint(toolName: String): String = buildString {
    append("错误：本次「$toolName」的参数被截断了，没有写入任何内容（写类工具不会用残缺参数执行）。\n")
    when (toolName) {
        "writeFile" -> append(
            "原因：content 太长，模型单次输出 token 不够。\n" +
                "请改为**分块写入**：\n" +
                "1. 先用 writeFile 写入文件开头（控制在约 100~200 行内）；\n" +
                "2. 其余内容分多次用 appendFile(path, content) 追加，每次同样控制在约 100~200 行；\n" +
                "3. 每块写完后继续调 appendFile，直到写完。\n" +
                "不要再用 writeFile 一次性写整个大文件。"
        )
        "appendFile" -> append(
            "原因：本次 content 太长。请把这一次的上限再拆小（约 100~200 行 / 8KB），" +
                "多调用几次 appendFile 追加即可。"
        )
        "editFile", "batch_edit", "batch_replace" -> append(
            "原因：替换内容/参数太长。请把这次修改拆成多次较小的编辑，" +
                "每次只改一处，分多轮完成。"
        )
        else -> append("请把参数内容拆小后分多次调用。")
    }
}
