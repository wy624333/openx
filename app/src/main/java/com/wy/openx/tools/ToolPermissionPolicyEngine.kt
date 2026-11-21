package com.wy.openx.tools

/**
 * 一条工具授权规则。
 *
 * @param toolName 归属工具（如 `terminal`、`writeFile`）。
 * @param pattern 匹配模式：
 *   - [WHOLE_TOOL]（`*`）= 整个工具，不做更细匹配；
 *   - shell 类工具填**指令前缀**（`git status`、`ls`、`rm build/a.o`），按 token 前缀语义匹配；
 *   - 非 shell 工具只支持 [WHOLE_TOOL]。
 * @param decision [PermissionDecision.ALLOW] 放行 / [PermissionDecision.DENY] 拒绝。
 */
data class PermissionRule(
    val toolName: String,
    val pattern: String,
    val decision: PermissionDecision
)

enum class PermissionDecision { ALLOW, DENY }

object PermissionRuleConst {
    /** 整个工具（不做更细匹配）。 */
    const val WHOLE_TOOL = "*"
}

/**
 * 工具授权策略引擎：在弹窗之前评估一次工具调用应「自动放行 / 自动拒绝 / 询问」，
 * 并在用户选择「始终允许」后把规则记忆下来。
 *
 * ## shell 工具的评估顺序
 * 1. 任一段命中 **DENY** 规则 → `DENY`（即便命令不可静态判定，DENY 也对已解析的段生效；可覆盖内置白名单）；
 * 2. 命令**不可静态判定**（命令替换 / 分组 / 绝对路径重定向等）→ `ASK`，且**不可记忆**；
 * 3. 所有段都命中**内置安全白名单**（`ls`/`git status` 等只读命令，见 [BuiltInSafeCommands]）→ `ALLOW`；
 * 4. 所有段都命中**已记忆的 ALLOW 规则** → `ALLOW`；
 * 5. 否则 → `ASK`，可记忆前缀为各段的「程序名」或「程序名+子命令」
 *    （对 `git`/`npm` 等子命令分发器，记 `git pull` 而非 `git`，使不同子命令各自独立授权）。
 *
 * 非 shell 的 ASK 工具按整工具（`pattern="*"`）匹配。
 *
 * Shizuku 工具与 terminal
 * 同属「按指令前缀授权」的 shell 类工具，故共用本引擎；AUTO 档在本项目体现为
 * `toolPermissionMode="allow"`，由调用方直接放行、不经本引擎。
 */
object ToolPermissionPolicyEngine {

    private const val TERMINAL_TOOL = "terminal"
    private const val SHIZUKU_TOOL = "shizuku"

    /** 以 `command` 参数承载 shell 命令、按指令前缀做匹配的工具。 */
    private val SHELL_TOOLS = setOf(TERMINAL_TOOL, SHIZUKU_TOOL)

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict 评估结论。
     * @param rememberablePatterns 当 [verdict] 为 ASK 时，「始终允许」会记忆的模式；
     *   为空表示**不可记忆**（命令不可静态判定 / rm 递归通配，只能单次放行）。
     * @param denyReason [verdict] 为 DENY 时的原因（展示给用户与 AI）。
     * @param rememberDisabledReason 不可记忆时的原因（用于在弹窗里禁用「始终允许」按钮）。
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null
    )

    /** 该工具是否为「按指令前缀授权」的 shell 类工具。 */
    fun isShellTool(toolName: String): Boolean = toolName.removePrefix("functions.") in SHELL_TOOLS

    /**
     * 评估一次工具调用。
     *
     * @param toolName 工具名（可带 `functions.` 前缀，会被剥掉）。
     * @param toolArgs 工具参数（从中取 `command`）。
     * @param rules 已记忆的规则（一般来自持久化存储）。
     */
    fun evaluate(toolName: String, toolArgs: Map<String, Any>, rules: List<PermissionRule>): EvalResult {
        val tool = toolName.removePrefix("functions.").trim()
        return if (isShellTool(tool)) evaluateShell(tool, toolArgs, rules) else evaluateGeneric(tool, rules)
    }

    // ---------- shell 类工具 ----------

    private fun evaluateShell(
        tool: String,
        toolArgs: Map<String, Any>,
        rules: List<PermissionRule>
    ): EvalResult {
        val command = (toolArgs["command"] as? String)?.trim()
        // 解析不出命令（异常调用）→ 交给上层按整工具处理，不在此放行。
        if (command.isNullOrEmpty()) return evaluateGeneric(tool, rules)

        val analysis = ShellCommandParser.analyze(command)
        val toolRules = rules.filter { it.toolName == tool }

        // ① 任一段命中 DENY → DENY（DENY 优先于一切 ALLOW，含内置白名单）
        val denyHit = toolRules.firstOrNull { r ->
            r.decision == PermissionDecision.DENY && anySegmentMatches(r.pattern, analysis)
        }
        if (denyHit != null) {
            return EvalResult(
                Verdict.DENY, emptyList(),
                denyReason = "该命令被拒绝授权规则「${denyHit.pattern}」禁止执行"
            )
        }

        // ② 命令不可静态判定 → ASK 且不可记忆
        if (!analysis.analyzable) {
            return EvalResult(
                Verdict.ASK, emptyList(),
                rememberDisabledReason = "命令含命令替换、子 shell 或绝对路径重定向等无法静态判定的构造，" +
                    "无法安全地记忆授权规则，仅支持单次放行"
            )
        }
        if (analysis.segments.isEmpty()) {
            return EvalResult(
                Verdict.ASK, emptyList(),
                rememberDisabledReason = "命令无法解析出可授权的指令前缀，仅支持单次放行"
            )
        }

        // ③ 所有段都命中内置安全白名单 → ALLOW
        if (analysis.segments.all { BuiltInSafeCommands.isSafe(it) }) return EvalResult(Verdict.ALLOW, emptyList())

        // ④ **每一段**都被已记忆的 ALLOW 规则覆盖 → ALLOW。
        // ⚠ 必须是 all 而不是 any：否则记住 `git pull` 后，`git pull && rm -rf /` 会因
        // 第一段命中而被整体放行——这正是本次改造要消除的越权。
        val allowPatterns = toolRules.filter { it.decision == PermissionDecision.ALLOW }.map { it.pattern }
        if (allowPatterns.isNotEmpty() && analysis.segments.all { seg ->
                allowPatterns.any { p -> ShellCommandParser.matches(p, seg) }
            }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // ⑤ 否则 ASK，可记忆前缀
        val rememberable = analysis.segments.mapNotNull { ShellCommandParser.rememberablePrefix(it) }
        val unrememberable = analysis.segments.any { ShellCommandParser.rememberablePrefix(it) == null }
        return EvalResult(
            Verdict.ASK, rememberable,
            rememberDisabledReason = if (unrememberable) {
                "命令中含无法安全记忆的部分（如递归删除 / 通配删除），仅支持单次放行"
            } else null
        )
    }

    /** 规则模式是否命中该命令的**任一段**（用于 DENY：只要有一段命中就拒绝）。 */
    private fun anySegmentMatches(pattern: String, analysis: ShellCommandParser.Analysis): Boolean {
        if (pattern == PermissionRuleConst.WHOLE_TOOL) return true
        return analysis.segments.any { ShellCommandParser.matches(pattern, it) }
    }

    // ---------- 非 shell 工具 ----------

    private fun evaluateGeneric(tool: String, rules: List<PermissionRule>): EvalResult {
        val toolRules = rules.filter { it.toolName == tool }
        if (toolRules.any { it.decision == PermissionDecision.DENY && it.pattern == PermissionRuleConst.WHOLE_TOOL }) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = "该工具已被拒绝授权规则禁止使用")
        }
        if (toolRules.any { it.decision == PermissionDecision.ALLOW && it.pattern == PermissionRuleConst.WHOLE_TOOL }) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        // 非 shell 工具只支持整工具粒度记忆
        return EvalResult(Verdict.ASK, listOf(PermissionRuleConst.WHOLE_TOOL))
    }

    /**
     * 用户点「始终允许」后应写入的规则（可记忆时）。
     * shell 工具按记忆前缀逐条建规则；非 shell 工具建整工具规则。
     */
    fun rulesToRemember(toolName: String, result: EvalResult): List<PermissionRule> {
        if (result.verdict != Verdict.ASK || result.rememberablePatterns.isEmpty()) return emptyList()
        val tool = toolName.removePrefix("functions.").trim()
        return result.rememberablePatterns.map {
            PermissionRule(tool, it, PermissionDecision.ALLOW)
        }
    }
}