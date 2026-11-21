package com.wy.openx.data.skill

import java.io.File

/**
 * 目录型技能源的共享扫描逻辑：递归查找 SKILL.md / CLAUDE.md，
 * 每个含指令文件的目录解析为一个技能。
 */
object SkillDirectoryScanner {
    /**
     * @param excludedRelativeParent 需要整体忽略的根下相对目录名（如全局作用域下的 `projects`，
     *   那里放着按项目隔离的项目技能），命中即跳过，避免全局扫描误收项目技能。
     */
    fun scan(root: File, excludedRelativeParent: String? = null): List<Skill> {
        if (!root.exists()) return emptyList()
        val skillFiles = root.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true)) }
            .filter { f ->
                if (excludedRelativeParent == null) return@filter true
                val rel = f.toRelativeString(root)
                !rel.startsWith(excludedRelativeParent + File.separatorChar) && rel != excludedRelativeParent
            }
            .toList()

        return skillFiles.mapNotNull { it.parentFile }
            .distinct()
            .mapNotNull { SkillParser.parse(it) }
            .sortedBy { it.name.lowercase() }
    }
}