package com.wy.openx.tools

import com.wy.openx.data.store.SCOPE_PROJECT
import com.wy.openx.data.store.SkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SkillTool(private val operation: String) : AgentTool {

    override val definition = when (operation) {
        "skill_add" -> ToolDef(
            function = FunctionDef(
                name = "skill_add",
                description = "创建一个新技能（skill），包含名称、描述和详细指令。技能创建后会立即生效，AI将在后续对话中遵循技能的指令。如果已存在同名技能会自动覆盖更新。技能会保存为 <openx>/skills/<name>/SKILL.md 文件。用 scope 区分「全局通用」还是「当前项目专属」：判断不准时想「换一个项目这条还适用吗」，适用用 global，不适用用 project。未选项目时只能用 global。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "技能名称，简短明确（2-50字符），如：代码审查规则、回答格式要求"),
                        "description" to mapOf("type" to "string", "description" to "技能描述，说明这个技能的用途"),
                        "instructions" to mapOf("type" to "string", "description" to "技能的详细指令，AI需要遵循的规则或行为指南"),
                        "scope" to mapOf("type" to "string", "description" to "技能作用域。global=全局技能（跨项目：通用工作规范、个人偏好、通用工具链）；project=项目技能（仅当前项目：本项目架构/约定/流程）。判断不准时想「换一个项目这条还适用吗」——适用用 global，不适用用 project。未选项目时只能 project 会报错", "default" to "global")
                    ),
                    "required" to listOf("name", "description", "instructions")
                )
            )
        )
        "skill_delete" -> ToolDef(
            function = FunctionDef(
                name = "skill_delete",
                description = "删除指定的技能。同名时优先删除项目技能（如无项目技能则删全局技能）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "要删除的技能名称")
                    ),
                    "required" to listOf("name")
                )
            )
        )
        "skill_list" -> ToolDef(
            function = FunctionDef(
                name = "skill_list",
                description = "列出所有已安装的技能及其名称、描述、作用域（全局/项目）和启用状态。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>(),
                    "required" to emptyList<String>()
                )
            )
        )
        // 按需加载技能正文。系统提示里只列「已启用技能的 name + description」以省 token，
        // 正文（可能很长）要靠这个工具取——所以描述里必须讲清「什么时候该调」。
        "skill_load" -> ToolDef(
            function = FunctionDef(
                name = "skill_load",
                description = "读取某个技能的完整指令正文。系统提示中只列出了技能的「名称 + 用途」，" +
                    "当某个技能与当前任务相关时，先调用本工具取回它的完整正文，再严格按正文行事。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "技能名称，须与系统提示「可用技能」清单中的名称一致")
                    ),
                    "required" to listOf("name")
                )
            )
        )
        else -> ToolDef(
            function = FunctionDef(name = operation, description = "", parameters = emptyMap<String, Any>())
        )
    }

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val ctx = AppContextHolder.context ?: return@withContext "错误：应用上下文未初始化"
        val store = SkillStore(ctx)
        val project = AppContextHolder.currentSettings?.currentProject?.trim().orEmpty()
        when (operation) {
            "skill_add" -> {
                val name = (args["name"] as? String)?.trim() ?: return@withContext "错误：未提供技能名称"
                if (name.length < 2) return@withContext "错误：技能名称至少2个字符"
                if (name.length > 100) return@withContext "错误：技能名称不能超过100个字符"
                val description = (args["description"] as? String)?.trim() ?: ""
                val instructions = (args["instructions"] as? String)?.trim() ?: ""
                if (instructions.isEmpty()) return@withContext "错误：技能指令不能为空"
                // 作用域：global=全局技能（默认），project=当前项目的项目技能（未选项目报错）
                val scope = (args["scope"] as? String)?.trim()?.lowercase()
                    ?.takeIf { it == SCOPE_PROJECT } ?: com.wy.openx.data.store.SCOPE_GLOBAL
                if (scope == SCOPE_PROJECT && project.isBlank()) {
                    return@withContext "错误：当前未选择项目，无法创建项目技能。请先在「工作区」中选择或创建项目，或改用 scope=global 创建全局技能。"
                }
                val existing = store.loadMerged(project).find { it.name.equals(name, ignoreCase = true) }
                store.add(name, description, instructions, scope, project)
                val where = if (scope == SCOPE_PROJECT) "项目技能「$project」" else "全局技能"
                if (existing != null) "✅ 技能「$name」已更新（$where，原指令已覆盖）"
                else "✅ 技能「$name」已保存（$where）"
            }
            "skill_delete" -> {
                val name = (args["name"] as? String)?.trim() ?: return@withContext "错误：未提供技能名称"
                // 同名优先删项目技能；无项目技能则删全局技能
                val deleted = if (project.isNotBlank() && store.delete(name, SCOPE_PROJECT, project)) SCOPE_PROJECT
                    else if (store.delete(name)) com.wy.openx.data.store.SCOPE_GLOBAL else null
                if (deleted == null) "未找到技能「$name」"
                else "✅ 技能「$name」已删除"
            }
            "skill_list" -> {
                val all = store.loadMerged(project)
                if (all.isEmpty()) "暂无技能"
                else all.joinToString("\n") {
                    val scopeTag = if (it.scope == SCOPE_PROJECT) "项目" else "全局"
                    "- ${it.name}（$scopeTag · ${if (it.enabled) "已启用" else "已禁用"}）：${it.description}"
                }
            }
            "skill_load" -> {
                val name = (args["name"] as? String)?.trim() ?: return@withContext "错误：未提供技能名称"
                val skill = store.loadMerged(project).firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: return@withContext "错误：未找到技能「$name」。请先确认名称与系统提示「可用技能」清单一致。"
                if (!skill.enabled) return@withContext "错误：技能「${skill.name}」已被禁用，无法加载。"
                if (skill.instructions.isBlank()) return@withContext "错误：技能「${skill.name}」没有正文指令。"
                // 回显作用域：同名时项目技能整条覆盖全局，模型不看到范围就可能把
                // 仅适用于本项目的约定当成跨项目通则（例如沿用本项目的目录/工具链前提）。
                val scope = if (skill.scope == SCOPE_PROJECT)
                    "项目技能（仅在当前项目「$project」内有效）"
                else
                    "全局技能（所有项目通用）"
                "技能「${skill.name}」（$scope）的完整指令如下（请严格遵循）：\n\n${skill.instructions}"
            }
            else -> "错误：不支持的操作"
        }
    }
}