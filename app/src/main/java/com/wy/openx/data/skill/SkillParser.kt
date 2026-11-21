package com.wy.openx.data.skill

import com.wy.openx.container.FileLogger
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 解析一个技能目录：读取 SKILL.md（或 CLAUDE.md 回退）的 YAML frontmatter + 正文。
 * 无指令文件或无 name 时视为非法，返回 null。
 */
object SkillParser {
    private const val TAG = "SkillParser"
    private const val MAX_DESC_CHARS = 500

    fun parse(dir: File): Skill? {
        var file = File(dir, "SKILL.md")
        if (!file.exists()) file = File(dir, "CLAUDE.md")
        if (!file.exists()) {
            file = dir.listFiles()?.firstOrNull {
                it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true)
            } ?: return null
        }

        val text = try {
            if (!file.isFile || !file.canRead()) return null
            file.readText()
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取技能文件失败: ${file.absolutePath}", e)
            return null
        }

        val (frontmatter, body) = splitAndParseFrontmatter(text)

        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: dir.name
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)
        val requiredTools = try {
            (frontmatter["required_tools"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 required_tools 失败: ${file.absolutePath}", e)
            emptyList()
        }

        return Skill(
            name = name,
            description = description,
            requiredTools = requiredTools,
            dir = dir,
            instructions = body.trim()
        )
    }

    /**
     * 从 SKILL.md 原文解析技能（不落盘），供「导入 .md 技能」使用。
     * 无 frontmatter 或无 name 时，名称回退为 [fallbackName]（通常是所选文件名）。
     */
    fun parseMarkdown(text: String, fallbackName: String): Skill {
        val (frontmatter, body) = splitAndParseFrontmatter(text)
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: fallbackName
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)
        val requiredTools = try {
            (frontmatter["required_tools"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return Skill(
            name = name,
            description = description,
            requiredTools = requiredTools,
            dir = null,
            instructions = body.trim()
        )
    }

    /** 用 SnakeYAML 切分并解析 YAML frontmatter，返回 (键值对, 正文)。 */
    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            val yaml = Yaml()
            yaml.load<Map<String, Any>>(block) ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "解析 YAML 失败", e)
            emptyMap()
        }

        return map to rest
    }
}