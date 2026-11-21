package com.wy.openx.remote

import android.content.Context

/**
 * 同步行为设置。
 *
 * [respectGitignore]：自动读取本地工作区根目录的 `.gitignore` 并忽略其中内容。
 * 这比手填忽略列表准确得多——项目自己的规则本来就写在那里，跟着走不会漏。
 *
 * [batchSize]：单次同步最多同时处理多少个文件，超出部分分批排队。
 * 一次推几百个文件上去会让 SSH 通道长时间占满，中途网络抖动就整批失败；
 * 分批能让失败的影响面小一些，也给其它操作留出空隙。
 */
data class RemoteSyncSettings(
    val respectGitignore: Boolean = true,
    val batchSize: Int = 5,
) {
    companion object {
        /** 队列上限的取值范围：太小传输慢，太大会把通道占满。 */
        val BATCH_MIN = 1
        val BATCH_MAX = 50
    }
}

/**
 * 同步设置的持久化（SharedPreferences）。
 *
 * 不并进 `remote_servers.json`：那是「连接与挂载」的清单，格式变更会牵连读写逻辑；
 * 同步设置是全局偏好，单独一份更稳，也不怕被清单重写覆盖。
 */
class RemoteSyncSettingsStore(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("remote_sync_settings", Context.MODE_PRIVATE)
    }

    fun load(): RemoteSyncSettings = RemoteSyncSettings(
        respectGitignore = prefs.getBoolean(KEY_GITIGNORE, true),
        batchSize = prefs.getInt(KEY_BATCH, 5)
            .coerceIn(RemoteSyncSettings.BATCH_MIN, RemoteSyncSettings.BATCH_MAX),
    )

    fun save(settings: RemoteSyncSettings) {
        prefs.edit()
            .putBoolean(KEY_GITIGNORE, settings.respectGitignore)
            .putInt(
                KEY_BATCH,
                settings.batchSize.coerceIn(RemoteSyncSettings.BATCH_MIN, RemoteSyncSettings.BATCH_MAX)
            )
            .apply()
    }

    private companion object {
        const val KEY_GITIGNORE = "respect_gitignore"
        const val KEY_BATCH = "batch_size"
    }
}

/**
 * 极简 `.gitignore` 匹配器。
 *
 * 只支持实际会用到的子集：空行与 `#` 注释跳过、目录/通配后缀匹配、`!` 取反、
 * 以及结尾 `/` 表示只匹配目录。完整 gitignore 语义（双星号、字符区间、转义）不实现——
 * 目标是「别把构建产物传上去」，不是做 git 的兼容层。
 *
 * 匹配对象是**相对工作区根**的路径（`/` 分隔）。
 */
internal class GitignoreMatcher(patterns: List<String>) {

    private val rules: List<Rule> = patterns.mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val negated = line.startsWith("!")
        val body = if (negated) line.substring(1).trim() else line
        if (body.isEmpty()) return@mapNotNull null
        // 结尾斜杠：只匹配目录（这里统一按「路径前缀」处理，对文件路径同样成立）
        val dirOnly = body.endsWith("/")
        val cleaned = body.removeSuffix("/").removePrefix("/")
        Rule(
            pattern = cleaned,
            negated = negated,
            dirOnly = dirOnly,
            // 无斜杠 = 匹配任意层级；带斜杠 = 从根开始匹配
            matchAnywhere = !cleaned.contains('/'),
        )
    }

    fun isEmpty(): Boolean = rules.isEmpty()

    /** [relativePath] 为 `/` 分隔的相对路径。命中返回 true（忽略）。 */
    fun isIgnored(relativePath: String): Boolean {
        val path = relativePath.removePrefix("/")
        var ignored = false
        for (rule in rules) {
            if (rule.matches(path)) {
                // 取反规则最后生效（git 语义：后面的规则覆盖前面的）
                ignored = !rule.negated
            }
        }
        return ignored
    }

    /** 目录是否被整体忽略（用于剪枝，避免遍历进去再逐个判定）。 */
    fun isDirectoryIgnored(relativePath: String): Boolean {
        val path = relativePath.removePrefix("/")
        var ignored = false
        for (rule in rules) {
            if (rule.matchesDirectory(path)) ignored = !rule.negated
        }
        return ignored
    }

    private data class Rule(
        val pattern: String,
        val negated: Boolean,
        val dirOnly: Boolean,
        val matchAnywhere: Boolean,
    ) {
        fun matches(path: String): Boolean = matchSegments(path)

        fun matchesDirectory(path: String): Boolean =
            dirOnly || matchSegments(path) || matchSegments("$path/x")

        private fun matchSegments(path: String): Boolean {
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return false
            if (matchAnywhere) {
                // 无斜杠：任意层级的名字匹配，支持 * 通配
                return segments.any { wildcardMatch(pattern, it) }
            }
            // 带斜杠：按路径前缀匹配（`src/build` 命中 `src/build/...`）
            val target = segments.joinToString("/")
            return wildcardMatch(pattern, target) ||
                target.startsWith("$pattern/") ||
                wildcardMatch(pattern + "/*", target)
        }
    }
}

/**
 * 通配匹配，只支持 `*`（任意长度）与 `?`（单字符）。
 *
 * 用递归下降而不是正则：gitignore 模式里的 `.`、`[`、`+` 都是普通字符，
 * 转成正则要先逐个转义，容易漏；直接匹配更不容易出错。
 */
internal fun wildcardMatch(pattern: String, text: String): Boolean {
    var p = 0
    var t = 0
    var starAt = -1
    var matchAt = 0
    while (t < text.length) {
        when {
            p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t]) -> {
                p++; t++
            }
            p < pattern.length && pattern[p] == '*' -> {
                starAt = p
                matchAt = t
                p++
            }
            starAt >= 0 -> {
                // 回溯：让上一个 * 多吞一个字符
                p = starAt + 1
                matchAt++
                t = matchAt
            }
            else -> return false
        }
    }
    while (p < pattern.length && pattern[p] == '*') p++
    return p == pattern.length
}
