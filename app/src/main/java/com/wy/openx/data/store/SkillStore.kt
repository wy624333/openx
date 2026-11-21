package com.wy.openx.data.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wy.openx.container.ArchiveExtractor
import com.wy.openx.container.FileLogger
import com.wy.openx.data.model.SkillPreset
import com.wy.openx.data.skill.SkillDirectoryScanner
import com.wy.openx.data.skill.SkillParser
import java.io.File
import java.util.Locale

/**
 * 技能仓库：技能本体是 `<filesDir>/openx/skills/<name>/SKILL.md` 目录 + 指令文件，
 * 启停名单是 `<filesDir>/openx/skills.json`（仅存禁用名单）。
 *
 * **两层技能**（与记忆作用域语义一致，沿用 [SCOPE_GLOBAL]/[SCOPE_PROJECT]）：
 * - 全局技能（[SCOPE_GLOBAL]）：存于技能根目录，跨项目共享；
 * - 项目技能（[SCOPE_PROJECT]）：存于 `skills/projects/<项目名>/` 子目录，按项目物理隔离，
 *   切到项目 A 只读到 A 的技能。项目名由调用方传入（通常是 `settings.currentProject`）。
 *
 * 兼容 aicode-main 的技能文件夹方案：容器内为 /root/.openx/skills，可由文件
 * 浏览查看/编辑、容器内 AI 直接读写。旧 SharedPreferences（agent_skills）数据
 * 在首次使用时自动迁移为 SKILL.md 文件。
 */
class SkillStore(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()

    companion object {
        private const val TAG = "SkillStore"
        private const val CONFIG_FILE = "skills.json"
        private const val DIR_NAME = "skills"
        private const val DIR_PROJECTS = "projects"
        private const val LEGACY_SP_NAME = "agent_skills"
    }

    /** 宿主技能根目录：filesDir/openx/skills */
    private val skillsRoot: File
        get() = File(File(appContext.filesDir, "openx"), DIR_NAME)

    /** 项目技能目录：filesDir/openx/skills/projects/<项目名>（与记忆 projects/ 同构）。 */
    private fun projectRoot(project: String): File =
        File(File(skillsRoot, DIR_PROJECTS), sanitizeName(project))

    /** 宿主启停配置文件：filesDir/openx/skills.json */
    private val configFile: File
        get() = File(File(appContext.filesDir, "openx"), CONFIG_FILE)

    private val legacySp = appContext.getSharedPreferences(LEGACY_SP_NAME, Context.MODE_PRIVATE)

    /** 按作用域解析技能目录；项目名为空时项目作用域回退到全局根目录（与记忆 dirFor 同语义）。 */
    private fun dirFor(scope: String, project: String): File = when {
        scope == SCOPE_PROJECT && project.isNotBlank() -> projectRoot(project)
        else -> skillsRoot
    }

    /** 加载全局技能（含目录扫描 + 禁用名单过滤），按名称排序。 */
    fun load(): List<SkillPreset> {
        migrateLegacyIfNeeded()
        val disabled = disabledNamesFor(SCOPE_GLOBAL, "")
        return SkillDirectoryScanner.scan(skillsRoot, excludedRelativeParent = DIR_PROJECTS).map { skill ->
            SkillPreset(
                name = skill.name,
                description = skill.description,
                enabled = skill.name.lowercase(Locale.ROOT) !in disabled,
                instructions = skill.instructions,
                scope = SCOPE_GLOBAL
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /** 加载当前项目的项目技能（无项目时返回空列表）。 */
    fun loadProject(project: String): List<SkillPreset> {
        if (project.isBlank()) return emptyList()
        val disabled = disabledNamesFor(SCOPE_PROJECT, project)
        return SkillDirectoryScanner.scan(projectRoot(project)).map { skill ->
            SkillPreset(
                name = skill.name,
                description = skill.description,
                enabled = skill.name.lowercase(Locale.ROOT) !in disabled,
                instructions = skill.instructions,
                scope = SCOPE_PROJECT
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /**
     * 全局 + 项目合并（项目优先同名覆盖，供运行时按名解析 / 注入）。语义对齐
     * [com.wy.openx.data.store.McpServerStore.loadEntries] 的「项目覆盖全局同名」。
     */
    fun loadMerged(project: String): List<SkillPreset> =
        (loadProject(project) + load()).distinctBy { it.name.lowercase(Locale.ROOT) }

    /** 启用技能列表（供系统提示词注入）。 */
    fun loadEnabled(): List<SkillPreset> = load().filter { it.enabled && it.instructions.isNotBlank() }

    /** 当前项目的启用技能（供注入）。 */
    fun loadEnabledProject(project: String): List<SkillPreset> =
        loadProject(project).filter { it.enabled && it.instructions.isNotBlank() }

    /**
     * 从单个 .md 技能文件导入：解析 frontmatter 后按 [add] 落盘（同名覆盖）。
     * @return 安装后的技能名；解析结果为空名时返回 null。
     */
    fun importMarkdown(fileName: String, text: String, scope: String = SCOPE_GLOBAL, project: String = ""): String? {
        val fallback = fileName.substringBeforeLast('.').ifBlank { "skill" }
        val skill = SkillParser.parseMarkdown(text, fallback)
        val name = skill.name.trim()
        if (name.isEmpty()) return null
        // 保留原始正文；description 为空时用正文首行兜底，便于列表展示
        val description = skill.description.ifBlank {
            skill.instructions.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: ""
        }
        add(name, description, skill.instructions, scope, project)
        return name
    }

    /**
     * 从压缩包导入技能（同名覆盖）：解压到临时目录，找出含 SKILL.md / CLAUDE.md 的目录，
     * 逐个复制进技能目录（全局根或项目子目录）。压缩包根目录本身就是技能（散装 SKILL.md）时，
     * 用压缩包名作目录名。
     * @return 安装成功的技能目录名列表。
     */
    fun installFromArchive(archive: File, archiveName: String, scope: String = SCOPE_GLOBAL, project: String = ""): List<String> {
        val tmp = File(appContext.cacheDir, "skill_import_${System.currentTimeMillis()}")
        try {
            tmp.mkdirs()
            ArchiveExtractor.extract(archive, archiveName, tmp)

            val skillDirs = tmp.walkTopDown()
                .filter { it.isDirectory && hasInstructionFile(it) }
                .toList()

            // 压缩包根目录散装 SKILL.md：整个临时目录算一个技能
            val targets = if (skillDirs.isEmpty()) {
                if (hasInstructionFile(tmp)) listOf(tmp to archiveBaseName(archiveName)) else emptyList()
            } else {
                skillDirs.map { it to it.name }
            }
            if (targets.isEmpty()) return emptyList()

            val destRoot = dirFor(scope, project)
            val installed = mutableListOf<String>()
            for ((srcDir, rawName) in targets) {
                val name = sanitizeName(rawName)
                if (name.isEmpty()) continue
                val dest = File(destRoot, name)
                if (dest.exists()) deleteRecursivelyNoFollow(dest)
                dest.parentFile?.mkdirs()
                if (srcDir.copyRecursively(dest, overwrite = true)) {
                    installed.add(name)
                }
            }
            if (installed.isNotEmpty()) {
                FileLogger.i(TAG, "从压缩包导入技能: $installed -> ${destRoot.absolutePath}（scope=$scope）")
            }
            return installed
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun hasInstructionFile(dir: File): Boolean =
        dir.listFiles()?.any {
            it.isFile && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true))
        } ?: false

    /** 去掉压缩包后缀，作为散装技能的目录名。 */
    private fun archiveBaseName(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        val suffix = listOf(".tar.gz", ".tar.xz", ".tgz", ".zip", ".tar", ".gz", ".xz")
            .firstOrNull { lower.endsWith(it) }
        return if (suffix != null) name.dropLast(suffix.length) else name
    }

    /** 新增/覆盖技能：写 skills/<name>/SKILL.md（frontmatter + 指令正文）。scope=project 落到项目子目录。 */
    fun add(name: String, description: String, instructions: String, scope: String = SCOPE_GLOBAL, project: String = "") {
        val safe = sanitizeName(name)
        if (safe.isEmpty()) return
        val dir = File(dirFor(scope, project), safe)
        dir.mkdirs()
        val frontmatter = buildString {
            append("---\n")
            append("name: \"").append(escapeYaml(name)).append("\"\n")
            append("description: \"").append(escapeYaml(description)).append("\"\n")
            append("---\n\n")
        }
        val file = File(dir, "SKILL.md")
        file.writeText(frontmatter + (instructions.ifBlank { "" }))
        FileLogger.i(TAG, "技能已保存: $name（scope=$scope, project=$project） -> ${file.absolutePath}")
    }

    /** 删除技能目录（含 SKILL.md）。返回是否找到并删除。 */
    fun delete(name: String, scope: String = SCOPE_GLOBAL, project: String = ""): Boolean {
        val dir = File(dirFor(scope, project), sanitizeName(name))
        if (!dir.isDirectory) return false
        val hasInstruction = dir.listFiles()?.any {
            it.isFile && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true))
        } ?: false
        return if (hasInstruction) { deleteRecursivelyNoFollow(dir); true } else false
    }

    /** 启用/禁用：改 skills.json 禁用名单（全局 .disabled，项目 .projects[<项目>]）。 */
    fun setEnabled(name: String, enabled: Boolean, scope: String = SCOPE_GLOBAL, project: String = "") {
        val disabled = disabledNamesFor(scope, project).toMutableSet()
        val key = name.lowercase(Locale.ROOT)
        if (enabled) disabled.remove(key) else disabled.add(key)
        writeDisabled(scope, project, disabled)
    }

    /** 读取指令正文（供运行时按名读取，合并列表先项目后全局）。 */
    fun loadInstructions(name: String, project: String = ""): String? =
        loadMerged(project).firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions

    /**
     * 递归删除目录，不跟随符号链接（否则 `up -> ..` / 指向 /storage/emulated/0 的链接会逃出
     * 目标目录，删掉外部用户的备份/源码）。
     */
    private fun deleteRecursivelyNoFollow(dir: File) {
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        val roots = ArrayList<File>()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            cur.listFiles()?.forEach { f ->
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    f.delete()
                    return@forEach
                }
                if (f.isDirectory) stack.addLast(f)
                else f.delete()
            }
            roots.add(cur)
        }
        roots.asReversed().forEach { if (it.isDirectory) it.delete() }
    }

    // ── 禁用名单读写 ──

    /**
     * 当前禁用技能名集合（归一化为小写）。
     * 配置文件结构：`{"disabled":[...全局...], "projects":{"<项目>":[...项目...]}}`；
     * 旧版单层 `{"disabled":[...]}` 仍可读，一律视为全局。
     */
    fun disabledNamesFor(scope: String, project: String): Set<String> {
        if (!configFile.isFile) return emptySet()
        return runCatching {
            val raw = configFile.readText()
            val root = gson.fromJson(raw, Map::class.java) ?: emptyMap<String, Any>()
            val list: List<*>? = when {
                scope == SCOPE_PROJECT && project.isNotBlank() -> {
                    val key = sanitizeName(project)
                    val nested = root["projects"] as? Map<*, *>
                    // 正常结构 projects.<项目>；兼容早期 bug 版本把项目名误写到**顶层**的数据。
                    (nested?.get(key) as? List<*>) ?: (root[key] as? List<*>)
                }
                else -> root["disabled"] as? List<*>
            }
            list?.mapNotNull { it?.toString() }?.map { it.lowercase(Locale.ROOT) }?.toSet() ?: emptySet()
        }.getOrElse {
            FileLogger.w(TAG, "读取技能配置失败: ${it.message}")
            emptySet()
        }
    }

    /** 写禁用名单：临时文件 + rename 原子落盘（按作用域分别读写，互不影响）。 */
    private fun writeDisabled(scope: String, project: String, names: Set<String>) {
        runCatching {
            configFile.parentFile?.mkdirs()
            val root: MutableMap<String, Any> = if (configFile.isFile) {
                gson.fromJson<Map<String, Any>>(
                    configFile.readText(),
                    object : TypeToken<Map<String, Any>>() {}.type
                )?.toMutableMap() ?: mutableMapOf()
            } else mutableMapOf()
            if (scope == SCOPE_PROJECT && project.isNotBlank()) {
                val key = sanitizeName(project)
                val projects = (root["projects"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
                projects[key] = names.sorted()
                root["projects"] = projects
                // 清理早期 bug 版本误写到顶层的项目名键，避免被下次读取当成项目名单。
                if (key != "projects" && key != "disabled") root.remove(key)
            } else {
                root["disabled"] = names.sorted()
            }
            val json = gson.toJson(root)
            val tmp = File(configFile.parentFile, "${configFile.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(configFile)) configFile.writeText(json)
        }.onFailure {
            FileLogger.w(TAG, "写入技能配置失败: ${it.message}", it)
        }
    }

    // ── 旧版 SharedPreferences 迁移 ──

    /** 首次使用把旧 agent_skills 数据迁移为 SKILL.md 文件，成功后清掉旧表（幂等）。 */
    private fun migrateLegacyIfNeeded() {
        val raw = legacySp.getString("items", null) ?: legacySp.getString("skills", null) ?: return
        if (raw == "[]") {
            legacySp.edit().remove("items").remove("skills").apply()
            return
        }
        runCatching {
            val old: List<SkillPreset>? = gson.fromJson(raw, object : TypeToken<List<SkillPreset>>() {}.type)
            old?.forEach { preset ->
                add(preset.name, preset.description, preset.instructions)
                if (!preset.enabled) setEnabled(preset.name, false)
            }
            legacySp.edit().remove("items").remove("skills").apply()
            FileLogger.i(TAG, "已迁移旧 SharedPreferences 技能数据到 ${skillsRoot.absolutePath}")
        }.onFailure {
            FileLogger.w(TAG, "迁移旧技能数据失败: ${it.message}", it)
        }
    }

    /** 目录名安全化：非法字符替换为 -，规避路径穿越/斜杠。 */
    private fun sanitizeName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").takeIf { it.isNotEmpty() } ?: "skill"

    /** YAML 双引号字符串转义。 */
    private fun escapeYaml(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}