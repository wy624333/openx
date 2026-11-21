package com.wy.openx.ui.command

import java.util.Locale

/**
 * 斜杠命令的唯一定义。
 *
 * 输入框 `/` 菜单、命令面板、`/help` 正文全部由 [BUILTIN_SLASH_COMMANDS] 生成，
 * 避免此前的「菜单 / 帮助 / 实际分支」三份清单互相漂移。
 * 新增命令时在此追加一条，并在 `MainViewModel.handleSlashCommand` 实现同名分支。
 */
data class SlashCommandSpec(
    /** 主命令名，含前导 `/`。 */
    val name: String,
    val description: String,
    /** 其它可用写法（同样含前导 `/`），解析时统一归一到 [name]。 */
    val aliases: List<String> = emptyList(),
    /** 是否出现在会话页「快捷命令」的四项面板。 */
    val quick: Boolean = false,
    /** 是否接受参数（菜单里仅供参考，当前点击行为对内置命令保持一致）。 */
    val acceptsArgs: Boolean = false
)

val BUILTIN_SLASH_COMMANDS: List<SlashCommandSpec> = listOf(
    SlashCommandSpec("/help", "显示此帮助", listOf("/帮助"), quick = true),
    SlashCommandSpec("/compact", "压缩上下文", listOf("/summarize", "/压缩", "/总结"), quick = true),
    SlashCommandSpec("/new", "新建会话", listOf("/clear", "/新建", "/清除"), quick = true),
    SlashCommandSpec("/settings", "打开设置", listOf("/设置")),
    SlashCommandSpec("/export", "导出对话到日志", listOf("/导出")),
    SlashCommandSpec("/exportshare", "导出并分享 Markdown", listOf("/导出分享")),
    SlashCommandSpec("/undo", "撤销最后一条消息", listOf("/撤销")),
    SlashCommandSpec("/redo", "恢复撤销"),
    SlashCommandSpec("/models", "显示/切换模型", listOf("/模型"), acceptsArgs = true),
    SlashCommandSpec("/presets", "列出可用 API 预设", listOf("/预设")),
    SlashCommandSpec("/sessions", "显示会话列表", listOf("/会话")),
    SlashCommandSpec("/details", "切换工具详情", listOf("/详情")),
    SlashCommandSpec("/thinking", "查看/设置推理强度（如 /thinking high）", listOf("/推理"), acceptsArgs = true),
    SlashCommandSpec("/themes", "查看主题信息", listOf("/主题")),
    SlashCommandSpec("/init", "设置项目规则", listOf("/规则"), acceptsArgs = true),
    SlashCommandSpec("/mode", "切换构建/规划/股票/聊天模式", listOf("/模式"), quick = true),
    SlashCommandSpec("/editor", "打开外部编辑器", listOf("/编辑")),
    SlashCommandSpec("/memory", "管理记忆", listOf("/记忆")),
    SlashCommandSpec("/skills", "管理技能", listOf("/技能")),
    SlashCommandSpec("/terminal", "打开内置终端", listOf("/终端")),
    SlashCommandSpec("/mcp", "管理 MCP 服务"),
    SlashCommandSpec("/plugins", "管理插件", listOf("/插件")),
    SlashCommandSpec("/usage", "查看今日与累计用量、预估费用")
)

/** 别名 → 主命令名；未命中返回 null（可能是技能名或未知命令）。 */
fun canonicalBuiltinCommand(token: String): String? {
    val t = token.lowercase(Locale.ROOT)
    return BUILTIN_SLASH_COMMANDS.firstOrNull { spec ->
        spec.name.lowercase(Locale.ROOT) == t ||
            spec.aliases.any { it.lowercase(Locale.ROOT) == t }
    }?.name
}

/** 命令的所有写法，供帮助文本展示：`/help, /帮助`。 */
fun SlashCommandSpec.writtenAs(): String =
    (listOf(name) + aliases).joinToString(", ")

/** `/help` 正文：内置命令 + 已启用技能（[skills] 为 名称 to 描述）。 */
fun buildSlashHelp(skills: List<Pair<String, String>> = emptyList()): String = buildString {
    appendLine("可用命令：")
    BUILTIN_SLASH_COMMANDS.forEach {
        appendLine("  ${it.writtenAs()}  — ${it.description}")
    }
    if (skills.isNotEmpty()) {
        appendLine()
        appendLine("已启用技能（直接输入 /技能名 触发，可带参数）：")
        skills.forEach { (name, description) ->
            // 技能描述可能是一整段话（含换行），帮助里压成一行，避免撑乱列表。
            val desc = description.replace(Regex("\\s+"), " ").trim().ifBlank { "自定义技能" }
            appendLine("  /$name  — $desc")
        }
    }
}.trimEnd()
