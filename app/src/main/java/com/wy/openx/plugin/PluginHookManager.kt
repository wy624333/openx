package com.wy.openx.plugin

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wy.openx.container.FileLogger

/**
 * 插件 Hook 的注册表与调度器（七期）。
 *
 * ## 为什么放在 object 单例
 * 触发点分散在主聊天链路里（`MainViewModel.sendMessage`、`ToolRegistry.executeMapped`），
 * 那些地方拿不到 ViewModel 的成员，也不该为了一个钩子去加依赖。所以调度器做成进程级
 * 单例，注册由 [PluginRuntime] 负责，执行侧只看 [PluginHookManager] 有没有要做的事。
 *
 * ## 隔离原则
 * 插件代码是**不可信**的，而钩子全在聊天主路径上。所以：
 * - 任何异常都被吞掉并记日志，绝不让插件把一轮对话搞崩；
 * - 没有注册者时 [hasAny] 直接短路，零开销；
 * - 只做数据变换，不改变流程控制（唯一例外是 `onBeforeTool` 的否决）。
 *
 * ## 没有超时保护（已知取舍）
 * QuickJS 是单线程且无法从外部中断，插件 hook 里写死循环会卡住整轮对话。
 * 加不了超时，只能在文档里写死"hook 内禁止死循环与网络请求"，并靠耗时日志暴露问题。
 */
object PluginHookManager {

    const val ON_APP_START = "onAppStart"
    const val ON_BEFORE_SEND = "onBeforeSend"
    const val ON_AFTER_REPLY = "onAfterReply"
    const val ON_BEFORE_TOOL = "onBeforeTool"
    const val ON_AFTER_TOOL = "onAfterTool"
    /** 向 AI 的系统提示词追加内容（十一期）。 */
    const val ON_PROMPT_INJECT = "onPromptInject"

    val ALL_HOOKS = listOf(
        ON_APP_START, ON_BEFORE_SEND, ON_AFTER_REPLY,
        ON_BEFORE_TOOL, ON_AFTER_TOOL, ON_PROMPT_INJECT
    )

    private const val TAG = "PluginHook"

    private fun isKnown(hook: String) = hook in ALL_HOOKS

    private data class Reg(val pluginId: String, val pkgName: String, val hook: String)

    @Volatile
    private var regs: List<Reg> = emptyList()

    /**
     * 实际调用插件函数的通道，由 [PluginRuntime] 注入（它持有 QuickJS 与包名映射）。
     * 未注入时所有钩子自动退化为"没人实现"。
     */
    @Volatile
    private var invoker: ((pkgName: String, hook: String, argsJson: String) -> String?)? = null

    /**
     * 钩子超时的通知口子。
     *
     * 超时本身已被引擎中断（不会再卡死对话），但用户需要知道“刚才那个插件被跳过了”，
     * 否则只会以为钩子没生效，无从排查。具体提示方式由宿主决定。
     */
    @Volatile
    private var onTimeout: ((pluginId: String, hook: String) -> Unit)? = null

    fun setOnTimeoutHandler(fn: ((String, String) -> Unit)?) {
        onTimeout = fn
    }

    private val gson = Gson()

    fun setInvoker(fn: ((pkgName: String, hook: String, argsJson: String) -> String?)?) {
        invoker = fn
    }

    /** 插件加载成功后登记它实现了哪些钩子。未知名字直接忽略（向前兼容）。 */
    fun register(pluginId: String, pkgName: String, hooks: Collection<String>) {
        val valid = hooks.filter { isKnown(it) }.distinct()
        if (valid.isEmpty()) return
        val kept = regs.filterNot { it.pluginId == pluginId }
        regs = kept + valid.map { Reg(pluginId, pkgName, it) }
        FileLogger.i(TAG, "插件 $pluginId 注册钩子：${valid.joinToString()}")
    }

    fun unregister(pluginId: String) {
        if (regs.none { it.pluginId == pluginId }) return
        regs = regs.filterNot { it.pluginId == pluginId }
    }

    /** 运行时整体重启时调用（卸载全部）。 */
    fun clear() {
        regs = emptyList()
    }

    fun hasAny(hook: String): Boolean = regs.any { it.hook == hook }

    /** 当前注册情况，供插件管理页展示。 */
    fun snapshot(): Map<String, List<String>> =
        regs.groupBy({ it.hook }, { it.pluginId })

    // ------------------------------------------------------------------ 触发

    /**
     * 用户消息发出前的改写。多个插件按注册顺序**链式**处理，每个拿到的是上一个的输出。
     *
     * 返回值协议：
     * - 空串 / 未返回 → 不干预，继续用原文本
     * - JSON `{"text":"..."}` → 用其中的 text（可以是空串，表示真的清空）
     * - 纯字符串 → 直接当新文本（便捷写法）
     */
    fun beforeSend(text: String): String {
        if (!hasAny(ON_BEFORE_SEND)) return text
        var cur = text
        for (reg in regs) {
            if (reg.hook != ON_BEFORE_SEND) continue
            val raw = invoke(reg, mapOf("text" to cur)) ?: continue
            cur = extractText(raw) ?: cur
        }
        return cur
    }

    /** AI 回复完成后的观察钩子，返回值忽略。 */
    fun afterReply(text: String, conversationId: Long) {
        if (!hasAny(ON_AFTER_REPLY)) return
        for (reg in regs) {
            if (reg.hook != ON_AFTER_REPLY) continue
            invoke(reg, mapOf("text" to text, "conversationId" to conversationId))
        }
    }

    /**
     * 系统提示词注入。返回要**追加**到 base 末尾的文本，null 表示不追加。
     *
     * 多个插件都注册时全部拼接（而不是像 onBeforeSend 那样链式覆盖）：
     * 提示词是累加性质的，每个插件贡献自己那段，谁覆盖谁都会丢信息。
     *
     * 这是**能力最强**的一个钩子——插件能影响 AI 的全部行为，所以它同样受
     * `hook` 危险权限管着，并且有 10 秒超时保护。
     */
    fun systemPromptInject(basePrompt: String, conversationId: Long): String? {
        if (!hasAny(ON_PROMPT_INJECT)) return null
        val parts = ArrayList<String>()
        for (reg in regs) {
            if (reg.hook != ON_PROMPT_INJECT) continue
            // base 只给前 2000 字：插件多半只想看个开头决定加什么，
            // 全量传过去只会把上下文浪费在重复内容上。
            val raw = invoke(
                reg,
                mapOf("base" to basePrompt.take(2000), "conversationId" to conversationId)
            ) ?: continue
            val text = extractText(raw) ?: raw
            if (text.isNotBlank()) parts += text
        }
        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }

    /**
     * 工具执行前。返回非 null 表示**否决**（作为工具结果回给模型），null 表示放行。
     *
     * 否决优先于执行：多个插件里第一个返回 `{"block":"..."}` 的生效。
     */
    fun beforeTool(toolName: String, argsJson: String): String? {
        if (!hasAny(ON_BEFORE_TOOL)) return null
        for (reg in regs) {
            if (reg.hook != ON_BEFORE_TOOL) continue
            val raw = invoke(reg, mapOf("tool" to toolName, "args" to parseArgs(argsJson))) ?: continue
            val block = jsonString(raw, "block")
            if (!block.isNullOrBlank()) return block
        }
        return null
    }

    /**
     * 工具执行后。返回非 null 表示**替换**工具结果。
     *
     * 返回值协议：`{"result":"..."}`，或纯字符串直接当新结果。
     */
    fun afterTool(toolName: String, argsJson: String, result: String): String? {
        if (!hasAny(ON_AFTER_TOOL)) return null
        var cur = result
        for (reg in regs) {
            if (reg.hook != ON_AFTER_TOOL) continue
            val raw = invoke(reg, mapOf("tool" to toolName, "args" to parseArgs(argsJson), "result" to cur))
                ?: continue
            cur = extractResult(raw) ?: cur
        }
        return cur.takeIf { it != result }
    }

    /** 宿主启动、插件加载完成后调一次。 */
    fun appStart() {
        if (!hasAny(ON_APP_START)) return
        for (reg in regs) {
            if (reg.hook != ON_APP_START) continue
            invoke(reg, mapOf("pluginId" to reg.pluginId))
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun invoke(reg: Reg, payload: Map<String, Any?>): String? {
        val fn = invoker ?: return null
        val started = System.currentTimeMillis()
        return try {
            val out = fn(reg.pkgName, reg.hook, gson.toJson(payload))
            if (out == com.wy.openx.tools.JsPackageManager.HOOK_TIMEOUT) {
                // 超时：钩子已被中断，本次直接跳过（不阻断聊天）
                FileLogger.w(TAG, "插件 ${reg.pluginId} 的 ${reg.hook} 执行超时，已中断并跳过")
                runCatching { onTimeout?.invoke(reg.pluginId, reg.hook) }
                return null
            }
            val cost = System.currentTimeMillis() - started
            if (cost > 200) {
                FileLogger.w(TAG, "插件 ${reg.pluginId} 的 ${reg.hook} 耗时 ${cost}ms（钩子在主链路上，应保持轻量）")
            }
            out?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // 插件抛错不能影响这一轮对话
            FileLogger.e(TAG, "插件 ${reg.pluginId} 的 ${reg.hook} 执行失败：${e.message}")
            null
        }
    }

    private fun parseArgs(argsJson: String): Any? =
        runCatching { JsonParser.parseString(argsJson) }.getOrNull()

    private fun extractText(raw: String): String? {
        jsonString(raw, "text")?.let { return it }
        // 不是 JSON 对象就当纯字符串处理（便捷写法）
        return raw.takeIf { !it.trimStart().startsWith("{") }
    }

    private fun extractResult(raw: String): String? =
        jsonString(raw, "result") ?: raw.takeIf { !it.trimStart().startsWith("{") }

    private fun jsonString(raw: String, key: String): String? = runCatching {
        val o = JsonParser.parseString(raw)
        if (!o.isJsonObject) return null
        val v = o.asJsonObject.get(key) ?: return null
        if (v.isJsonNull) return null
        if (v.isJsonPrimitive) v.asString else null
    }.getOrNull()
}
