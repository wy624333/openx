package com.wy.openx.data.store

import android.content.Context
import com.google.gson.JsonParser
import com.wy.openx.container.FileLogger
import java.io.File
import java.util.Locale

data class MemoryItem(
    val key: String,
    val value: String,
    val type: String,
    val category: String = "general",
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 作用域：`"global"` = 长期记忆（全局，跨项目）；`"project"` = 项目记忆（按当前项目隔离）。
     * 该字段由**存储位置**决定（global 在记忆根目录，project 在 projects/<项目>/ 子目录）。
     */
    val scope: String = SCOPE_GLOBAL
)

/** 记忆作用域常量（放在顶层，供 data / ui / tools 各层共用）。 */
const val SCOPE_GLOBAL = "global"
const val SCOPE_PROJECT = "project"

/**
 * 内置 AI 规则的作用域。
 *
 * 存放在记忆根目录的 `agent/` 子目录，与普通记忆同源但**独立成层**：
 * - 不参与长期记忆的注入条数名额（规则文档体积大，会把真记忆挤出去）；
 * - 由专门的逻辑**每次完整注入**（等效于项目级 AGENTS.md 那类硬规则）；
 * - `memory_read` 不限作用域，所以 AI 仍能按关键词搜到这些规则。
 */
const val SCOPE_AGENT = "agent"

/**
 * 内置参考文档（如插件开发指南）：随 APK 打包，与 [SCOPE_AGENT] 一样**不占提示词**，
 * 但注入策略不同——
 *
 * - [SCOPE_AGENT] 是「必须遵守的硬规则」，每次全量注入（约 13KB）；
 * - 本作用域是**查阅型资料**，体积大（插件开发指南约 24KB）且只在写插件时才需要，
 *   每次注入会挤占对话上下文，故**一律不注入**，仅在 [SCOPE_AGENT] 的规则区留一条
 *   「按需用 memory_read 取」的索引提示。
 *
 * 存放于 `memory/docs/`，[com.wy.openx.tools.MemoryTool] 的 memory_read 跨作用域搜索时
 * 同样能命中，AI 知道有这份资料后按关键词取回即可。
 */
const val SCOPE_DOCS = "docs"

/**
 * 记忆仓库：每条记忆保存为 `<filesDir>/openx/memory/<key>[.<type>].md` 文件，
 * 容器内即 `/root/.openx/memory/<key>.md`，与 skill（/root/.openx/skills）同级，
 * 可在文件浏览中直接查看/手动编辑。文件头（frontmatter）含 key/type/category/timestamp/scope，
 * 正文为记忆内容。旧 SharedPreferences（agent_memory）数据在首次使用时自动迁移为文件。
 *
 * **两层记忆**：
 * - 长期记忆（[SCOPE_GLOBAL]）：存于记忆根目录，跨项目共享；
 * - 项目记忆（[SCOPE_PROJECT]）：存于 `memory/projects/<项目名>/` 子目录，按项目物理隔离——
 *   切到项目 A 只读写 A 的记忆，不会污染项目 B。项目名由调用方传入（通常是 `settings.currentProject`）。
 */
class MemoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()

    companion object {
        private const val TAG = "MemoryStore"
        private const val DIR_NAME = "memory"
        private const val DIR_PROJECTS = "projects"
        private const val LEGACY_SP_NAME = "agent_memory"
        /** 内置 AI 规则目录名（记忆根下的子目录）：`memory/agent/`。 */
        private const val DIR_AGENT = "agent"

        /** 内置参考文档目录名（记忆根下的子目录）：`memory/docs/`。 */
        private const val DIR_DOCS = "docs"

        /**
         * 用户改动的覆盖层目录（记忆根下的子目录）：`memory/agent-custom/`。
         *
         * 与 [DIR_AGENT] 同为编号片段，但**不随版本覆盖**：设置页里改过的规则写这里，
         * 同编号时压过内置层（见 [AgentRules.merge]），于是 APK 升级刷新内置原文时
         * 用户的改动不会丢。
         */
        private const val DIR_AGENT_CUSTOM = "agent-custom"

        /** APK 内置参考文档的 assets 路径前缀。 */
        private const val ASSET_DOCS = "docs"
        /** APK 内置规则文档的 assets 路径前缀。 */
        private const val ASSET_AGENT_RULES = "agent-rules"
        /** 记录「内置规则已释放到哪个版本」的标记文件。不以 .md 结尾，避免被 load() 当成记忆。 */
        private const val MARKER_FILE = ".builtin-version"

        /** 内置参考文档的版本标记（与规则分开，便于两边独立升级）。 */
        private const val DOCS_MARKER_FILE = ".builtin-docs-version"
    }

    /** 宿主记忆根目录：filesDir/openx/memory（容器内 /root/.openx/memory） */
    private val memoryRoot: File
        get() = File(File(appContext.filesDir, "openx"), DIR_NAME)

    /** 项目记忆目录：filesDir/openx/memory/projects/<项目名> */
    private fun projectRoot(project: String): File =
        File(File(memoryRoot, DIR_PROJECTS), sanitize(project))

    /**
     * AI 规则目录：filesDir/openx/memory/agent（容器内 /root/.openx/memory/agent）。
     *
     * 放的是**内置的行为准则文档**（编码纪律、安全边界等），随 APK 打包、首次启动释放。
     * 与普通记忆同根目录，于是 memory_read 能搜到；但用独立子目录，
     * 不混进长期记忆的注入条数名额（否则规则一多会把用户真正的记忆挤出提示词）。
     */
    private val agentRoot: File
        get() = File(memoryRoot, DIR_AGENT)

    /**
     * 内置规则的**自定义覆盖层**：`memory/agent-custom/`。
     *
     * 只由设置页「提示词」子页写入；升级不覆盖（[seedBuiltinAgentRules] 只碰 [agentRoot]）。
     * 不在 [SCOPE_AGENT] 目录里，因此 `memory` 工具的 memory_read 读不到——规则本来就是全量
     * 注入提示词的，重复读一份只会噪音，不影响使用。
     */
    private val agentCustomRoot: File
        get() = File(memoryRoot, DIR_AGENT_CUSTOM)

    /** 项目级规则覆盖层：`memory/projects/<项目>/agent-custom/`，只对该项目生效（最高优先级）。 */
    private fun projectAgentCustomRoot(project: String): File =
        File(projectRoot(project), DIR_AGENT_CUSTOM)

    /**
     * 内置参考文档目录：filesDir/openx/memory/docs（容器内 /root/.openx/memory/docs）。
     *
     * 与 [agentRoot] 同理独立成目录，只是不进提示词——AI 用 memory_read 按需查。
     */
    private val docsRoot: File
        get() = File(memoryRoot, DIR_DOCS)

    /** 按作用域解析实际目录；项目名为空的项目记忆回退到根目录（避免写到 projects/ 根下）。 */
    private fun dirFor(scope: String, project: String): File = when {
        scope == SCOPE_AGENT -> agentRoot
        scope == SCOPE_DOCS -> docsRoot
        scope == SCOPE_PROJECT && project.isNotBlank() -> projectRoot(project)
        else -> memoryRoot
    }

    /** 加载指定作用域的记忆（按时间倒序），先迁移旧数据再扫描目录。 */
    fun load(scope: String = SCOPE_GLOBAL, project: String = ""): List<MemoryItem> {
        migrateLegacyIfNeeded()
        val dir = dirFor(scope, project)
        if (!dir.isDirectory) return emptyList()
        return synchronized(lock) {
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }
                ?.mapNotNull { parseItem(it) }
                // 次级键取 key（内置规则无 frontmatter timestamp，恒为 0；若无次级键，
                // 并列顺序会落到 File.listFiles() 的目录顺序上——不稳定，会打乱注入 prompt
                // 的顺序、使前缀缓存整段失效）。按 key 排序后内置规则恰好是 10/20/30/50/70/80。
                ?.sortedWith(compareByDescending<MemoryItem> { it.timestamp }.thenBy { it.key })
                ?: emptyList()
        }
    }

    /**
     * 首次运行（及版本升级）时，把 APK 内置的 AI 规则文档释放到 `memory/agent/`。
     *
     * 用版本号做标记：只有当内置规则的**版本变了**才重新释放，避免每次启动都覆盖。
     * 依据是运行时读到的 `versionCode`（而非 `BuildConfig`，避免为非 application 模块
     * 生成 BuildConfig 的构建配置差异），规则随 APK 更新，标记与 APK 版本同源，
     * 不需要额外维护一个「规则版本」常量（那种手工常量迟早忘了改）。
     *
     * **升级会刷新这 7 个内置文件**（用户对它们的本地修改会被覆盖）——它们是随 app
     * 发布的行为准则，语义上就该跟版本走。用户自己往 `memory/agent/` 加的**其他**文件
     * 不受影响（只覆盖内置的同名文件）。
     *
     * ⚠ 覆盖规则只针对内置层。用户在设置页「提示词」子页改过的内容写在
     * [DIR_AGENT_CUSTOM] 覆盖层，**不经过这里**，升级不会丢（见 [loadAgentRules]）。
     *
     * @return 本次是否实际释放/刷新了文件。
     */
    fun seedBuiltinAgentRules(): Boolean {
        val dir = agentRoot
        val marker = File(dir, MARKER_FILE)
        val currentVersion = runCatching {
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        }.getOrDefault(0L)
        // 版本未变且已有文件 → 不重复释放
        if (marker.isFile && marker.readText().trim() == currentVersion.toString()) return false

        val assets = runCatching { appContext.assets.list(ASSET_AGENT_RULES) }.getOrNull()
        if (assets.isNullOrEmpty()) return false

        synchronized(lock) {
            dir.mkdirs()
            assets.filter { it.endsWith(".md") }.forEach { name ->
                runCatching {
                    appContext.assets.open("$ASSET_AGENT_RULES/$name").use { input ->
                        File(dir, name).outputStream().use { input.copyTo(it) }
                    }
                }.onFailure { FileLogger.w(TAG, "释放内置规则失败 $name: ${it.message}", it) }
            }
            marker.writeText(currentVersion.toString())
        }
        FileLogger.i(TAG, "已释放内置 AI 规则到 ${dir.absolutePath}（版本 $currentVersion）")
        return true
    }

    /**
     * 读取**合并后**的内置规则（内置层 + 自定义覆盖层），设置页「提示词」子页与提示词注入用。
     *
     * 不复用 [load]（SCOPE_AGENT）：那条路只读 [agentRoot] 单层，会漏掉自定义层、也看不出
     * 哪条被覆盖了。规则不需要时间戳/作用域这些记忆属性，故返回 [AgentRule] 而非 [MemoryItem]。
     *
     * **不按总开关/单条开关过滤**——那是提示词注入侧（MainViewModel）的事，
     * 设置页要展示全部（含已停用的），否则开关一关列表就少一条，用户没法再打开。
     */
    fun loadAgentRules(project: String? = null): List<AgentRule> {
        val local = readRuleFiles(agentRoot)
        // APK assets 兜底：本地副本被删/损坏时不至于整条规则消失（同编号时本地优先）
        val builtin = local + readAssetRuleFiles()
        val custom = readRuleFiles(agentCustomRoot)
        val proj = project?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { readRuleFiles(projectAgentCustomRoot(it)) }
            ?: emptyList()
        if (builtin.isEmpty() && custom.isEmpty() && proj.isEmpty()) return emptyList()
        return AgentRules.merge(builtin, custom, proj)
    }

    /** 读 APK 内的内置规则（[ASSET_AGENT_RULES]），只读不落盘。 */
    private fun readAssetRuleFiles(): List<AgentRules.RuleFile> = runCatching {
        appContext.assets.list(ASSET_AGENT_RULES)
            ?.filter { it.endsWith(".md") }
            ?.mapNotNull { name ->
                runCatching {
                    AgentRules.RuleFile(name, appContext.assets.open("$ASSET_AGENT_RULES/$name").use { it.readBytes().toString(Charsets.UTF_8) })
                }.getOrNull()
            }
            ?.sortedBy { it.fileName }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 把一条规则写进自定义覆盖层（[saveAgentRule] 的落盘动作）。
     *
     * 同编号的旧自定义文件会先删掉再写，保证一个编号只有一份，
     * 否则合并结果取决于文件名排序，列表会出现「幽灵条目」。
     *
     * @param fileName 目标文件名（含 `.md`）；通常与内置层同名。
     */
    fun saveAgentRule(fileName: String, body: String, project: String? = null) {
        val name = fileName.removeSuffix(".md") + ".md"
        val number = AgentRules.numberOf(name)
        val target = project?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { projectAgentCustomRoot(it) } ?: agentCustomRoot
        synchronized(lock) {
            target.mkdirs()
            if (number >= 0) {
                target.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".md") && AgentRules.numberOf(it.name) == number }
                    ?.forEach { runCatching { it.delete() } }
            }
            File(target, name).writeText(body)
        }
        FileLogger.i(TAG, "规则自定义已保存: $name（${if (target == agentCustomRoot) "全局" else "项目"}层）")
    }

    /**
     * 删除某一层的自定义文件 → 回落到下一层。
     *
     * [source] 决定删哪一层：项目层删 `projects/<项目>/agent-custom/`，
     * 全局自定义层删 `memory/agent-custom/`。内置层没有可删的文件（UI 也不给这个按钮）。
     */
    fun resetAgentRule(fileName: String, source: AgentRuleSource, project: String? = null) {
        val name = fileName.removeSuffix(".md") + ".md"
        val number = AgentRules.numberOf(name)
        val dir = if (source == AgentRuleSource.PROJECT && !project.isNullOrBlank())
            projectAgentCustomRoot(project) else agentCustomRoot
        synchronized(lock) {
            val targets = dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") && (AgentRules.numberOf(it.name) == number || it.name == name) }
                ?: emptyList()
            targets.forEach { runCatching { it.delete() } }
            if (targets.isNotEmpty()) FileLogger.i(TAG, "已删除规则自定义层: ${targets.joinToString { it.name }}")
        }
    }

    /** 读目录下所有 `.md` 为「文件名 → 正文」；目录不存在返回空。 */
    private fun readRuleFiles(dir: File): List<AgentRules.RuleFile> {
        if (!dir.isDirectory) return emptyList()
        return synchronized(lock) {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.mapNotNull { f -> runCatching { AgentRules.RuleFile(f.name, f.readText()) }.getOrNull() }
                ?.sortedBy { it.fileName }
                ?: emptyList()
        }
    }

    /**
     * 把 APK 内置的参考文档释放到 `memory/docs/`（幂等，按版本判断）。
     *
     * 释放后**不进入提示词**，仅供 memory_read 按需查阅；所以文档体积可以放大
     * （插件开发指南这类参考资料）。
     */
    fun seedBuiltinDocs(): Boolean {
        val dir = docsRoot
        val marker = File(dir, DOCS_MARKER_FILE)
        val currentVersion = runCatching {
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        }.getOrDefault(0L)

        val assets = runCatching { appContext.assets.list(ASSET_DOCS) }.getOrNull()
        if (assets.isNullOrEmpty()) return false

        // 指纹里带内容摘要，而不是只记版本号：文档的改动多数不伴随升版本号
        // （补一段说明、改错别字），只比版本号会让设备上的旧文档永远刷不新。
        val mdNames = assets.filter { it.endsWith(".md") }
        val stamp = "$currentVersion:${docsFingerprint(mdNames)}"
        if (marker.isFile && marker.readText().trim() == stamp) return false

        synchronized(lock) {
            dir.mkdirs()
            mdNames.forEach { name ->
                runCatching {
                    appContext.assets.open("$ASSET_DOCS/$name").use { input ->
                        File(dir, name).outputStream().use { input.copyTo(it) }
                    }
                }.onFailure { FileLogger.w(TAG, "释放内置文档失败 $name: ${it.message}", it) }
            }
            marker.writeText(stamp)
        }
        FileLogger.i(TAG, "已释放内置参考文档到 ${dir.absolutePath}（版本 $currentVersion）")
        return true
    }

    /** assets 内置文档的内容摘要（文件名 + 正文一起算），用于判断是否要重新释放。 */
    private fun docsFingerprint(names: List<String>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        names.sorted().forEach { name ->
            digest.update(name.toByteArray())
            runCatching { appContext.assets.open("$ASSET_DOCS/$name") }.getOrNull()?.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 新增/覆盖记忆：按 (key,type) 幂等，写为可见的记忆文件。 */
    fun add(
        key: String,
        value: String,
        type: String,
        category: String = "general",
        scope: String = SCOPE_GLOBAL,
        project: String = ""
    ) {
        if (key.isBlank() && value.isBlank()) return
        val dir = dirFor(scope, project)
        synchronized(lock) {
            // 先删除同 (key,type) 的旧文件再写新文件，保证唯一性
            deleteUnlocked(key, type, dir)
            val file = fileFor(key, type, dir)
            file.parentFile?.mkdirs()
            val frontmatter = buildString {
                append("---\n")
                append("key: \"").append(esc(key)).append("\"\n")
                append("type: \"").append(esc(type)).append("\"\n")
                append("category: \"").append(esc(category)).append("\"\n")
                append("scope: \"").append(esc(scope)).append("\"\n")
                append("timestamp: ").append(System.currentTimeMillis()).append("\n")
                append("---\n\n")
            }
            file.writeText(frontmatter + value)
            FileLogger.i(TAG, "记忆已保存: ($key, $type, $scope) -> ${file.absolutePath}")
        }
    }

    /** 删除匹配 (key,type) 的记忆文件（限定作用域目录）。 */
    fun delete(key: String, type: String, scope: String = SCOPE_GLOBAL, project: String = "") {
        synchronized(lock) { deleteUnlocked(key, type, dirFor(scope, project)) }
    }

    /** 清空指定作用域的全部记忆文件。 */
    fun clear(scope: String = SCOPE_GLOBAL, project: String = "") {
        synchronized(lock) {
            val dir = dirFor(scope, project)
            if (!dir.isDirectory) return
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.forEach { it.delete() }
            FileLogger.i(TAG, "记忆已清空: ${dir.absolutePath}")
        }
    }

    // ── 文件读写 ──

    private fun deleteUnlocked(key: String, type: String, dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.forEach { f ->
            val item = parseItem(f)
            if (item != null && item.key == key && item.type == type) f.delete()
        }
    }

    /** 计算 (key,type) 对应的文件名；与既有文件冲突时追加稳定短哈希避免相互覆盖。 */
    private fun fileFor(key: String, type: String, dir: File): File {
        val base = sanitize(key).ifBlank { "memory" } +
            (if (type.isBlank() || type == "general") "" else "." + sanitize(type).ifBlank { "type" }) + ".md"
        var candidate = File(dir, base)
        val existing = candidate.takeIf { it.isFile }?.let { parseItem(it) }
        if (existing != null && (existing.key != key || existing.type != type)) {
            val suffix = String.format(Locale.ROOT, "%08x", (key.hashCode() xor type.hashCode()))
            candidate = File(dir, base.removeSuffix(".md") + "." + suffix + ".md")
        }
        return candidate
    }

    /** 解析记忆文件。解析失败返回 null（保留文件，避免误删用户手工编辑的内容）。 */
    private fun parseItem(file: File): MemoryItem? {
        return runCatching {
            val text = file.readText()
            var key = ""
            var type = "general"
            var category = "general"
            var scope = SCOPE_GLOBAL
            var timestamp = 0L
            var value: String
            if (text.startsWith("---\n")) {
                val headerEnd = text.indexOf("\n---", 4)
                if (headerEnd > 0) {
                    val header = text.substring(4, headerEnd)
                    for (line in header.lineSequence()) {
                        val idx = line.indexOf(':')
                        if (idx <= 0) continue
                        val k = line.substring(0, idx).trim()
                        val v = unesc(line.substring(idx + 1))
                        when (k) {
                            "key" -> key = v
                            "type" -> type = v
                            "category" -> category = v
                            "scope" -> scope = v.ifBlank { SCOPE_GLOBAL }
                            "timestamp" -> timestamp = v.toLongOrNull() ?: 0L
                        }
                    }
                    value = text.substring(headerEnd + 4)
                } else {
                    value = text.substring(4)
                }
            } else {
                value = text
            }
            if (key.isBlank()) key = file.name.removeSuffix(".md")
            if (value.isBlank() && key.isBlank()) return null
            MemoryItem(key, value.trim(), type, category, timestamp, scope)
        }.getOrNull()
    }

    /** 字符串转义：反斜杠、双引号、换行。 */
    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    /** 反转义 [esc] 的结果：去掉包围引号并还原。 */
    private fun unesc(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("\"")) s = s.substring(1)
        if (s.endsWith("\"")) s = s.dropLast(1)
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    /** 目录名安全化（与 SkillStore 一致）：非法字符替换为 -。 */
    private fun sanitize(s: String): String =
        s.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").takeIf { it.isNotEmpty() } ?: "memory"

    // ── 旧版 SharedPreferences 迁移 ──

    /** 首次使用把旧 agent_memory 数据迁移为记忆文件，成功后清掉旧表（幂等）。 */
    private fun migrateLegacyIfNeeded() {
        val sp = appContext.getSharedPreferences(LEGACY_SP_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString("items", null) ?: return
        if (raw == "[]") {
            sp.edit().remove("items").apply()
            return
        }
        runCatching {
            parseLegacyItems(raw).forEach { item ->
                if (item.value.isNotBlank() || item.key.isNotBlank()) {
                    add(item.key, item.value, item.type, item.category)
                }
            }
            // 必须无条件清除旧表：否则只要有一处解析/迁移抛错，就会在每次启动重跑并反复告警
            sp.edit().remove("items").apply()
            FileLogger.i(TAG, "已迁移旧 SharedPreferences 记忆数据到 ${memoryRoot.absolutePath}")
        }.onFailure {
            FileLogger.w(TAG, "迁移旧记忆数据失败: ${it.message}", it)
        }
    }

    /**
     * 解析旧 SharedPreferences 里的记忆 JSON。
     *
     * 不能直接 `gson.fromJson(..., List<MemoryItem>)`：Gson 通过反射/Unsafe 构造对象，
     * 不受 Kotlin 空安全约束，历史数据中缺失或显式为 null 的字段会变成 null 灌进非空属性，
     * 直到被当作非空值使用（如 `add(..., category)`）才抛
     * "Parameter specified as non-null is null" 使整批迁移失败。故逐字段做空值兜底。
     */
    private fun parseLegacyItems(raw: String): List<MemoryItem> {
        val array = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val key = obj.strField("key")
            val value = obj.strField("value")
            if (key.isBlank() && value.isBlank()) return@mapNotNull null
            MemoryItem(
                key = key,
                value = value,
                type = obj.strField("type").ifBlank { "general" },
                category = obj.strField("category").ifBlank { "general" },
                timestamp = obj.get("timestamp")?.takeIf { it.isJsonPrimitive }?.asLong ?: System.currentTimeMillis()
            )
        }
    }

    /** 读取字符串字段，缺失/为 null/非字符串时返回空串。 */
    private fun com.google.gson.JsonObject.strField(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
}