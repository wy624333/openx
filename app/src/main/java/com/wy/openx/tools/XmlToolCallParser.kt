package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 解析「工具调用被模型写成 XML 文本」的情况。
 *
 * 背景：部分模型/网关（Anthropic 风格、部分思考模型）有时不返回原生 `tool_calls` 字段，
 * 而是把调用写成正文文本：
 * ```
 * <tool_calls>
 * <invoke name="terminal">
 * <parameter name="command">ls -la</parameter>
 * <parameter name="timeout">300</parameter>
 * </invoke>
 * </tool_calls>
 * ```
 * 此前这种文本会被当普通回复显示、且**不会触发工具执行**，表现为「AI 突然停在半途」。
 * 本解析器把它识别为结构化工具调用，交给正常的工具执行链路。
 *
 * 兼容点：
 * - 可有可无 `<tool_calls>`/`<tool_calls>` 外壳；
 * - 参数名/值支持双引号或不加引号；
 * - 参数值按类型推断（数字/布尔/JSON 对象或数组/字符串），与原生 tool_calls 的 arguments 语义一致；
 * - **DSML 伪 XML**（deepseek-v4.1-flash 等）：工具调用写成
 *   `<｜｜DSML｜｜ invoke name="terminal">…</｜｜DSML｜｜ invoke>`，且 finish_reason=stop、
 *   不带原生 tool_calls。此前标记全不匹配 → 原文刷进气泡、工具不执行、AI 停在半途；
 *   现由 [normalizeDsml] 先归一成标准标签再走既有解析（流式抑制用的
 *   [firstMarkerIndex] 直接按 `<｜｜DSML｜｜` 前缀定位，索引与原文一致，不做归一以免错位）。
 */
object XmlToolCallParser {
    private val gson = Gson()

    data class ParsedCall(val name: String, val arguments: String, val startIndex: Int, val endIndex: Int)

    /** DSML 标签桥（全角竖线 U+FF5C），如 `<｜｜DSML｜｜ invoke` / `</｜｜DSML｜｜ parameter>`。 */
    private const val DSML_BARS = "｜｜DSML｜｜"

    /** 任一工具调用起始标记（用于「流式展示时抑制 XML 文本」的检测）。 */
    private val MARKERS = listOf("<tool_calls>", "<invoke ", "<invoke>", "<tool_call>", "<$DSML_BARS")

    private val INVOKE_REGEX = Regex(
        """<invoke\b[^>]*\bname\s*=\s*["']?([^"'>\s]+)["']?[^>]*>(.*?)</invoke>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val FUNC_REGEX = Regex(
        """<function\b[^>]*\bname\s*=\s*["']?([^"'>\s]+)["']?[^>]*>(.*?)</function>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val PARAM_REGEX = Regex(
        """<parameter\b[^>]*\bname\s*=\s*["']?([^"'>\s]+)["']?[^>]*>(.*?)</parameter>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    /** 文本中是否出现任一工具调用起始标记。 */
    fun hasMarker(text: String): Boolean = MARKERS.any { text.contains(it, ignoreCase = true) }

    /**
     * DSML 伪 XML → 标准标签归一（幂等，无 DSML 时原样返回）。
     * 顺序敏感：先换外壳（`｜｜DSML｜｜ calls` → `tool_calls`），否则随后的
     * `｜｜DSML｜｜ ` 剥离会把它降级成无人识别的 `<calls>` 残壳。
     */
    private fun normalizeDsml(text: String): String {
        if (!text.contains(DSML_BARS)) return text
        return text
            .replace("$DSML_BARS function_calls", "tool_calls")
            .replace("$DSML_BARS calls", "tool_calls")
            .replace("$DSML_BARS ", "")
            .replace(DSML_BARS, "")
    }

    /** 首个工具调用起始标记的下标（无则 -1）。供流式展示时截断到标记之前。 */
    fun firstMarkerIndex(text: String): Int {
        var best = -1
        for (m in MARKERS) {
            val i = text.indexOf(m, ignoreCase = true)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return best
    }

    /** 解析全部工具调用；无则返回空列表。 */
    fun parse(text: String): List<ParsedCall> {
        // 先归一 DSML 再解析：索引基于归一后的文本。外部调用方只取 name/arguments；
        // strip 内部同样先归一，故与 strip 的删除基准一致。
        val src = normalizeDsml(text)
        if (!hasMarker(src)) return emptyList()
        val results = mutableListOf<ParsedCall>()
        collect(src, INVOKE_REGEX, results)
        if (results.isEmpty()) collect(src, FUNC_REGEX, results)
        return results.sortedBy { it.startIndex }
    }

    private fun collect(text: String, regex: Regex, out: MutableList<ParsedCall>) {
        for (m in regex.findAll(text)) {
            val name = m.groupValues[1].trim().removePrefix("functions.")
            if (name.isBlank()) continue
            val body = m.groupValues[2]
            val args = JsonObject()
            for (p in PARAM_REGEX.findAll(body)) {
                val key = p.groupValues[1].trim()
                if (key.isBlank()) continue
                args.add(key, inferValue(p.groupValues[2]))
            }
            out.add(ParsedCall(name, gson.toJson(args), m.range.first, m.range.last + 1))
        }
    }

    /** 按类型推断参数值：数字/布尔/JSON 容器原样，其余为字符串。 */
    private fun inferValue(raw: String): JsonPrimitive {
        val v = raw.trim()
        if (v.isEmpty()) return JsonPrimitive("")
        v.toLongOrNull()?.let { return JsonPrimitive(it) }
        v.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        if (v.equals("true", true) || v.equals("false", true)) return JsonPrimitive(v.toBoolean())
        if ((v.startsWith("{") && v.endsWith("}")) || (v.startsWith("[") && v.endsWith("]"))) {
            runCatching { com.google.gson.JsonParser.parseString(v) }.getOrNull()?.let {
                if (it.isJsonObject || it.isJsonArray) return JsonPrimitive(v)  // 作为原始 JSON 字符串，ToolRegistry 解析时再转
            }
        }
        return JsonPrimitive(v)
    }

    /** 从文本中剥离工具调用 XML（保留其余正文），供最终展示/落库。 */
    fun strip(text: String): String {
        // 与 parse 同基准：删除索引来自归一后的文本，故必须先归一再删，否则 DSML 存在时
        // 归一引起的长度收缩会让索引错位、删错段。无 DSML 时 normalizeDsml 原样返回。
        val normalized = normalizeDsml(text)
        val calls = parse(normalized)
        if (calls.isEmpty()) return normalized
        val sb = StringBuilder(normalized)
        for (c in calls.sortedByDescending { it.startIndex }) {
            if (c.startIndex in 0..c.endIndex && c.endIndex <= sb.length) sb.delete(c.startIndex, c.endIndex)
        }
        // 顺带删掉空壳标记与多余空行
        var out = sb.toString()
        for (m in MARKERS) out = out.replace(m, "", ignoreCase = true)
        out = out.replace("</tool_calls>", "", ignoreCase = true).replace("</tool_call>", "", ignoreCase = true)
        return out.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
