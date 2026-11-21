package com.wy.openx.data.util

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 工具执行结果的成功/失败判定（**单一事实来源**）。
 *
 * 聊天页状态圆点（[com.wy.openx.ui.screen]）与工具结果占位文案（MainViewModel）都必须走这里，
 * 否则同一结果会出现「✅ 文案 + 红点」这类自相矛盾的 UI。
 *
 * 判定顺序（v2：按「结构 → 显式标记 → 计数」，**结果内容里的词一律不作数**）：
 * 1. 结构化 JSON（结果以 `{` 开头）：优先读 `ok` 字段（`ok=false` 即失败）；无 `ok` 时看 `error`
 *    字段（非 JSON null 即失败）。**必须**先走这条——不少工具的成功响应形如
 *    `{"ok":true,...,"error":null}`，字段名 `error` 本身会让朴素子串匹配误判为失败。
 * 2. 终端原始输出（`[Shell 环境]` 开头）：命令输出属于**内容**，里面出现 error/失败/错误 很正常
 *    （`grep -rn "error" .`、`ls /失败用例` 都会命中），因此只认输出开头两行的显式标记。
 * 3. 首行显式错误标记：本项目工具的错误返回统一写成 `错误：…`，另有 `❌` / `⛔`（拦截）
 *    / `执行失败` / `Error:` / `Traceback` / `已停止` / `用户已拒绝` 等约定。
 * 4. 摘要窗口（首 8 行）里的**失败计数 > 0**：覆盖「成功 3 / 失败 2」「未变更/失败: 1」这类
 *    部分失败的统计头。只看开头 8 行，是为了让「统计头」与「结果正文里的偶然用词」区分开。
 * 5. 其余一律成功。
 *
 * 为什么不再全文关键词匹配（v1 的 bug）：关键词会把**成功的调用**判成失败，用户看到
 * 「AI 说调用成功、UI 红点」的自相矛盾。实测过的误判：
 *   - `subagent_dispatch` 聚合文本末尾的「若有子代理失败…」→ 全成功却红点；
 *   - `batch_edit` 成功头 `✅ 批量编辑完成 (成功 3 / 失败 0)` → 恒红；
 *   - `batch_replace` 的 `未变更/失败: 0` → 恒红；
 *   - `file_analyzer` 成功分析并报告 `JSON 错误: …` / `错误大致在第 N 行附近` → 误红；
 *   - `terminal` 执行 `grep -rn "error" .` 成功返回 → 误红；
 *   - `git diff` / `readFile` 读到含「失败」二字的文件内容 → 误红。
 * 代价：极少数「错误信息只出现在正文中间且无任何标记」的工具不再判红——宁可绿也不可红，
 * 因为红色会被读成「操作失败」，而模型侧往往已经基于成功结果继续往下走了。
 */
object ToolResultStatus {

    /** 首行以此开头即视为失败（与本项目工具的错误书写约定一致）。 */
    private val ERROR_PREFIXES = listOf(
        "❌", "⛔", "错误", "失败", "异常", "中断", "已停止",
        "用户已拒绝", "被拒绝", "已拦截",
        "error", "failed", "exception"
    )

    /**
     * 出现在首行任意位置即视为失败的**无歧义**短语。
     * 单独用 `执行失败` 收编「Git 命令执行失败 (exit=128)」这类主语在前的句式；
     * 词表刻意保守，避免再次出现「内容含词即判红」。
     */
    private val ERROR_PHRASES = listOf(
        "执行失败", "traceback", "not permitted", "access denied", "permission denied",
        "syntaxerror", "command not found", "no such file", "unsupported operation", "不支持的操作"
    )

    /**
     * 失败计数：`(成功 3 / 失败 2)`、`未变更/失败: 1`、`2 failed`、`failed: 1`。
     * 数字必须**结束该短语**（后面只能是行尾/`)`/`,`/空格分隔等），否则「本轮失败 0 项」
     * 「正文里提到 失败 9 处」这类散文会被误当成计数器。
     */
    private val FAIL_COUNT_PATTERNS = listOf(
        Regex("""失败\s*[:：/]?\s*(\d+)\s*(?=$|[)）,，、/|\]·])"""),
        Regex("""(\d+)\s*failed\s*(?=$|[)）,，、/|\]·])""", RegexOption.IGNORE_CASE),
        Regex("""failed\s*[:=]\s*(\d+)\s*(?=$|[)）,，、/|\]·])""", RegexOption.IGNORE_CASE)
    )

    /** 终端原始输出的开头标记：命中后只按「显式标记」判定，内容一律忽略。 */
    private const val SHELL_OUTPUT_HEADER = "[Shell 环境]"

    /** 统计头窗口：只看前 8 行。 */
    private const val SUMMARY_WINDOW_LINES = 8

    /** 结果是否为错误。 */
    fun isError(result: String?): Boolean {
        if (result == null) return false
        val trimmed = result.trim()
        if (trimmed.isEmpty()) return false

        // 1) 结构化 JSON（用 Gson 而非 org.json：后者在 JVM 单测里是 android.jar 的 stub，
        //    走不到真实实现，判定会假绿）
        if (trimmed.startsWith("{")) {
            val verdict = runCatching {
                val o = JsonParser.parseString(trimmed)
                if (!o.isJsonObject) return@runCatching null
                val obj = o.asJsonObject
                when {
                    obj.has("ok") -> !readOkFlag(obj)
                    obj.has("error") -> !obj.get("error").isJsonNull
                    else -> null
                }
            }.getOrNull()
            if (verdict != null) return verdict
        }

        val lines = trimmed.lines()
        val firstLine = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        // 2) 终端原始输出：命令输出是内容，不是状态
        if (trimmed.startsWith(SHELL_OUTPUT_HEADER)) {
            return lines.take(2).any { hasErrorMarker(it.trim()) }
        }

        // 3) 首行显式错误标记（「失败 0」这类零计数不算）
        if (hasErrorMarker(firstLine) && !reportsZeroFailures(firstLine)) return true

        // 4) 摘要窗口内的失败计数 > 0
        return lines.take(SUMMARY_WINDOW_LINES).any { line ->
            reportsPositiveFailures(line)
        }
    }

    /** 读 `ok` 字段；`"ok":"true"` 这类字符串写法也当布尔处理，与原 org.json 行为一致。 */
    private fun readOkFlag(obj: JsonObject): Boolean {
        val v = obj.get("ok")
        if (v == null || v is JsonNull) return true
        return runCatching {
            val p = v.asJsonPrimitive
            if (p.isBoolean) p.asBoolean else p.asString.toBoolean()
        }.getOrDefault(true)
    }

    private fun hasErrorMarker(line: String): Boolean {
        val low = line.lowercase()
        if (ERROR_PREFIXES.any { low.startsWith(it.lowercase()) }) return true
        return ERROR_PHRASES.any { low.contains(it.lowercase()) }
    }

    /** 该行是否只是报告「失败 0」。 */
    private fun reportsZeroFailures(line: String): Boolean {
        val counts = extractFailCounts(line)
        return counts.isNotEmpty() && counts.all { it == 0 }
    }

    /** 该行是否报告了大于 0 的失败数。 */
    private fun reportsPositiveFailures(line: String): Boolean =
        extractFailCounts(line).any { it > 0 }

    private fun extractFailCounts(line: String): List<Int> {
        for (p in FAIL_COUNT_PATTERNS) {
            val m = p.find(line) ?: continue
            val n = m.groupValues.getOrNull(1)?.toIntOrNull()
            if (n != null) return listOf(n)
        }
        return emptyList()
    }
}
