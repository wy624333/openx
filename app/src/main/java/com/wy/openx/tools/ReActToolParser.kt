package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ReActToolParser {
    private val gson = Gson()

    data class ParsedToolCall(
        val name: String,
        val rawArguments: String,
        val startIndex: Int,
        val endIndex: Int
    )

    fun buildToolPrompt(toolDefs: List<ToolDef>): String {
        if (toolDefs.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n你可以使用以下工具来帮助完成任务。")
        sb.append("当需要使用工具时，在回复中按以下格式输出（可以调用多个）：\n")
        sb.append("TOOL_CALL: {\"name\": \"工具名\", \"arguments\": {\"参数名\": \"参数值\"}}\n")
        sb.append("\n可用工具列表：\n")
        for (tool in toolDefs) {
            val func = tool.function
            sb.append("- ${func.name}: ${func.description}\n")
            sb.append("  参数: ${gson.toJson(func.parameters)}\n")
        }
        sb.append("\n注意：请根据任务需求自主选择合适的工具，调用后我会把结果返回给你。")
        return sb.toString()
    }

    fun parseToolCalls(text: String): List<ParsedToolCall> {
        val results = mutableListOf<ParsedToolCall>()
        var searchFrom = 0
        while (true) {
            val idx = text.indexOf("TOOL_CALL:", searchFrom)
            if (idx < 0) break
            val jsonStart = text.indexOf('{', idx)
            if (jsonStart < 0 || jsonStart > idx + 50) { searchFrom = idx + 10; continue }

            var depth = 0
            var jsonEnd = -1
            // 感知字符串与转义：参数值中的 { } " 不再干扰深度计算
            var inStr = false
            var esc = false
            for (i in jsonStart until text.length) {
                val c = text[i]
                if (esc) { esc = false; continue }
                when {
                    c == '\\' && inStr -> esc = true
                    c == '"' -> inStr = !inStr
                    !inStr && c == '{' -> depth++
                    !inStr && c == '}' -> { depth--; if (depth == 0) { jsonEnd = i + 1; break } }
                }
            }
            if (jsonEnd < 0) { searchFrom = jsonStart + 1; continue }

            val jsonStr = text.substring(jsonStart, jsonEnd)
            try {
                val map: Map<String, Any> = gson.fromJson(jsonStr, object : TypeToken<Map<String, Any>>() {}.type)
                val name = map["name"]?.toString()
                if (name == null) { searchFrom = jsonEnd; continue }
                val args = map["arguments"]
                val rawArgs = if (args != null) gson.toJson(args) else "{}"
                results.add(ParsedToolCall(name, rawArgs, idx, jsonEnd))
                searchFrom = jsonEnd
            } catch (_: Exception) {
                searchFrom = jsonEnd
            }
        }
        return results
    }

    fun stripToolCalls(text: String): String {
        val calls = parseToolCalls(text)
        if (calls.isEmpty()) return text
        var result = text
        for (call in calls.reversed()) {
            result = result.substring(0, call.startIndex) + result.substring(call.endIndex)
        }
        return result.trim()
    }
}
