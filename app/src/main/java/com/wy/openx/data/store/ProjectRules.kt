package com.wy.openx.data.store

import java.io.File

/**
 * 读取工作区里的 `AGENTS.md` / `CLAUDE.md` 规则文件。
 *
 * 为什么需要：内置规则 `10-project-rules.md` 明确告诉模型「AGENTS.md 是绝对优先级」，
 * 但此前**没有任何代码读它** —— 模型只是被要求遵守一个从未进入上下文的文件。
 *
 * 语义：
 * - 每个目录只取一份，`AGENTS.md` 优先、`CLAUDE.md` 兜底；
 * - 超过 [MAX_CHARS] 截断并标注，避免单个规则文件吃掉整个上下文；
 * - 按 (mtime, length) 缓存，未修改时不重复读盘。
 */
object ProjectRules {

    /** 单个规则文件最多注入的字符数。 32K 上限。 */
    const val MAX_CHARS = 32_000

    const val AGENTS_FILE = "AGENTS.md"
    const val CLAUDE_FILE = "CLAUDE.md"

    /** 一份读到的规则文件。 */
    data class Doc(
        /** 展示用来源标签（「全局」/「项目」）。 */
        val label: String,
        /** 实际文件名（AGENTS.md / CLAUDE.md）。 */
        val fileName: String,
        /** 绝对路径（提示词里给出，便于模型/用户定位）。 */
        val path: String,
        val text: String
    )

    private data class CacheEntry(val mtime: Long, val length: Long, val text: String)

    @Volatile
    private var cache: Map<String, CacheEntry> = emptyMap()

    /** 目录下的规则文件（AGENTS.md 优先），没有/不可读/为空 → null。 */
    fun read(dir: File?, label: String = ""): Doc? {
        if (dir == null) return null
        val file = resolve(dir) ?: return null
        val text = readCached(file) ?: return null
        if (text.isBlank()) return null
        return Doc(label = label, fileName = file.name, path = file.absolutePath, text = text)
    }

    private fun resolve(dir: File): File? {
        val agents = File(dir, AGENTS_FILE)
        if (agents.isFile && agents.canRead()) return agents
        val claude = File(dir, CLAUDE_FILE)
        if (claude.isFile && claude.canRead()) return claude
        return null
    }

    private fun readCached(file: File): String? {
        val mtime = file.lastModified()
        val length = file.length()
        val key = file.absolutePath
        cache[key]?.let { if (it.mtime == mtime && it.length == length) return it.text }
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val text = if (raw.length > MAX_CHARS) {
            raw.take(MAX_CHARS) + "\n…（规则过长，已截断）"
        } else {
            raw.trim()
        }
        cache = cache + (key to CacheEntry(mtime, length, text))
        return text
    }

    /** 测试用：清空缓存。 */
    fun clearCache() {
        cache = emptyMap()
    }
}
