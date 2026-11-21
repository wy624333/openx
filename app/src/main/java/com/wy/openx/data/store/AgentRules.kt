package com.wy.openx.data.store

/**
 * 一条「内置 AI 行为规则」（提示词片段）的**合并后视图**。
 *
 * 两层结构：
 * - **内置层** `memory/agent/NN-*.md`：随 APK 打包、每次 versionCode 升级全量覆盖；
 * - **自定义层** `memory/agent-custom/NN-*.md`：用户在设置页改动后写入，**升级不覆盖**。
 *
 * 合并按**文件名前两位数字**判定身份：同编号时自定义层胜出。
 * @see AgentRules.merge
 */
data class AgentRule(
    /** 文件名去掉 `.md`，如 `30-comments`。注入时用作小标题。 */
    val key: String,
    /** 文件名前两位数字（`30-comments.md` → 30）；文件名无该前缀时为 -1。 */
    val number: Int,
    /** 展示标题：取首行 `<!-- 标题：摘要 -->` 的标题，取不到则取 `# 标题` 或 key。 */
    val title: String,
    /** 展示摘要：取首行注释中「：」后面的部分；没有则空。 */
    val summary: String,
    /** 正文原文（含首行注释），注入系统提示词与详情预览用。 */
    val body: String,
    /** 本条最终来自哪一层。 */
    val source: AgentRuleSource,
    /** 实际生效的文件名（自定义层胜出时是自定义层的文件名）。 */
    val fileName: String,
    /**
     * 该编号在**内置层**有没有对应原文。
     *
     * 决定自定义条目的处置文案：有原文 = 用户改过内置规则 →「恢复内置默认」；
     * 没有 = 用户自己新建的片段（设置页「+」）→「删除此片段」（删了就没有了）。
     */
    val hasBuiltin: Boolean = true
)

/** 规则来源层。 */
enum class AgentRuleSource {
    /** 内置层（APK 释放，随版本刷新）。 */
    BUILTIN,
    /** 全局自定义层（`memory/agent-custom/`，所有项目共用，升级保留）。 */
    CUSTOM,
    /** 项目自定义层（`memory/projects/<项目>/agent-custom/`，只对该项目生效）。 */
    PROJECT
}

/**
 * 内置规则两层合并的**纯逻辑**（不碰 Context / 文件系统，便于 JVM 单测）。
 *
 * 规则：
 * 1. 身份 = 文件名前两位数字；无该前缀时身份 = 完整 key（只跟同名的另一层撞）。
 * 2. 同身份时自定义层胜出；自定义层里身份重复的文件只取按文件名排序后的第一个（确定性）。
 * 3. 内置层里没有对应身份的自定义文件（用户新增/改到空编号）照样列出，来源 CUSTOM。
 * 4. 结果按 key 升序 —— 与改造前 `load(SCOPE_AGENT)` 的 `sortedBy { key }` 完全一致，
 *    保证注入顺序、前缀缓存命中的文件顺序都不变。
 */
object AgentRules {

    /** `30-comments.md` → 30；无前缀 → -1。 */
    fun numberOf(fileName: String): Int =
        Regex("""^(\d{2})-""").find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    /**
     * 把用户输入的片段名清洗成可落盘的文件名片段（`NN-<这里>.md` 的后半段）。
     *
     * 路径分隔符/空白/引号等一律换成 `-` 并压缩连续项、去首尾横线与点
     * （点开头会变隐藏文件）。中文保留——文件系统与注入标题都支持。
     */
    fun sanitizeName(raw: String): String =
        raw.trim()
            .replace(Regex("""[\\/:*?"<>|\s.]+"""), "-")
            .trim('-')

    /** 合并身份键：有编号用编号，无编号退化为 key（只与同名文件对撞）。 */
    private fun identity(fileName: String): String {
        val n = numberOf(fileName)
        return if (n >= 0) "n:$n" else "k:${fileName.removeSuffix(".md")}"
    }

    /** 一条「文件名 → 正文」的原始输入。 */
    data class RuleFile(val fileName: String, val body: String)

    /**
     * 合并内置层与自定义层，得到最终生效的规则列表。
     *
     * @param builtin 内置层文件（`memory/agent/`）
     * @param custom  自定义层文件（`memory/agent-custom/`）
     */
    fun merge(
        builtin: List<RuleFile>,
        custom: List<RuleFile>,
        project: List<RuleFile> = emptyList()
    ): List<AgentRule> {
        // 层内先按身份去重（同层同编号取文件名排序后的第一个，保证确定性）
        fun dedupe(files: List<RuleFile>): Map<String, RuleFile> {
            val m = LinkedHashMap<String, RuleFile>()
            files.sortedBy { it.fileName }.forEach { file ->
                val id = identity(file.fileName)
                if (id !in m) m[id] = file
            }
            return m
        }

        val local = dedupe(builtin)
        val global = dedupe(custom)
        val proj = dedupe(project)
        val identities = LinkedHashSet<String>(local.size + global.size + proj.size)
        identities += local.keys
        identities += global.keys
        identities += proj.keys

        return identities.map { id ->
            // 优先级：项目 > 全局自定义 > 内置（同编号只生效一层）
            val (file, source) = when {
                id in proj -> proj.getValue(id) to AgentRuleSource.PROJECT
                id in global -> global.getValue(id) to AgentRuleSource.CUSTOM
                else -> local.getValue(id) to AgentRuleSource.BUILTIN
            }
            // 「有没有可回落的原文」：项目层看全局/内置，全局层看内置
            val hasBuiltin = when (source) {
                AgentRuleSource.BUILTIN -> true
                AgentRuleSource.CUSTOM -> id in local
                AgentRuleSource.PROJECT -> id in local || id in global
            }
            toRule(file, source, hasBuiltin)
        }.sortedBy { it.key }
    }

    private fun toRule(file: RuleFile, source: AgentRuleSource, hasBuiltin: Boolean): AgentRule {
        val key = file.fileName.removeSuffix(".md")
        val (title, summary) = parseHeadline(key, file.body)
        return AgentRule(
            key = key,
            number = numberOf(file.fileName),
            title = title,
            summary = summary,
            body = file.body,
            source = source,
            fileName = file.fileName,
            hasBuiltin = hasBuiltin
        )
    }

    /**
     * 解析首行的标题与摘要，供列表页展示「编号 · 标题 / 摘要」两行。
     *
     * 三档回退：
     * 1. `<!-- 标题：摘要 -->`（内置规则的统一格式）；
     * 2. `# 标题`（没有注释头的规则，如 `80-plugin-docs-index.md`）；
     * 3. key 去掉编号前缀（正文首行什么都不是时的兜底，保证列表不空标题）。
     */
    fun parseHeadline(key: String, body: String): Pair<String, String> {
        val first = body.lineSequence().firstOrNull()?.trim().orEmpty()
        if (first.startsWith("<!--") && first.endsWith("-->")) {
            val inner = first.removePrefix("<!--").removeSuffix("-->").trim()
            val sep = inner.indexOf('：').takeIf { it in 1 until inner.length }
                ?: inner.indexOf(':').takeIf { it in 1 until inner.length }
            return if (sep != null) {
                inner.substring(0, sep).trim().ifBlank { key } to inner.substring(sep + 1).trim()
            } else {
                inner.ifBlank { key } to ""
            }
        }
        if (first.startsWith("# ")) return first.removePrefix("# ").trim() to ""
        return key.replaceFirst(Regex("""^\d{2}-"""), "") to ""
    }
}
