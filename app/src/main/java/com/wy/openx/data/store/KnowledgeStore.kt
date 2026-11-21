package com.wy.openx.data.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wy.openx.container.FileLogger
import java.io.File
import java.util.Locale

/**
 * 一条从对话里提炼出来的知识。
 *
 * 与 [MemoryItem] 是两套东西，刻意不合并：
 * - 记忆由当前聊天的 AI 主动写，每轮把**清单**注入提示词，所以必须少而精；
 * - 知识由独立的提炼模型被动沉淀，提示词里只注入**目录**，正文靠 `knowledge_search` 按需取。
 */
data class KnowledgeEntry(
    val id: String,
    val title: String,
    val tags: List<String> = emptyList(),
    /** 来源标记，形如「会话标题 (#12)」——用于回溯这条知识是哪次对话沉淀的。 */
    val source: String = "",
    val created: Long = 0L,
    val updated: Long = 0L,
    val body: String = "",
    /** 落盘路径（管理页展示与定位用）。 */
    val path: String = ""
)

/** 每个会话的提炼进度（`knowledge/.state/cursor.json` 里的一条）。 */
data class KnowledgeCursor(
    /** 已累计的「对话轮数」，达到设置的阈值才触发一次提炼。 */
    val rounds: Int = 0,
    /** 最近一次提炼完成的时间戳（0 = 从未提炼过）。 */
    val lastExtractedAt: Long = 0L
)

/**
 * 知识库存储：`filesDir/openx/knowledge/`（容器内 `/root/.openx/knowledge/`）。
 *
 * ```
 * knowledge/
 * ├── index.md            总目录（自动重建，给人看的）
 * ├── entries/<id>.md     一条知识一个文件，带 frontmatter
 * └── .state/cursor.json  各会话的提炼进度
 * ```
 * 一条知识一个文件是刻意的：便于 diff 与人工编辑，也让 AI 能只读需要的那一条。
 * 所有读写都走 [lock]——提炼 worker 与设置页/管理页可能同时碰它。
 */
class KnowledgeStore(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val gson = Gson()

    companion object {
        private const val TAG = "KnowledgeStore"
        private const val DIR_NAME = "knowledge"
        private const val DIR_ENTRIES = "entries"
        private const val DIR_STATE = ".state"
        private const val FILE_INDEX = "index.md"
        private const val FILE_CURSOR = "cursor.json"

        /**
         * 一条知识正文的长度上限。
         *
         * 提炼侧靠 prompt 把每条约束在 800 字内；这里放宽到 20000 是为了容纳**从手机文件导入的文档**
         * （一份笔记常常几千字）。上限存在的意义是防止单条撑爆渲染与检索。
         */
        private const val MAX_BODY_CHARS = 20_000
        private const val MAX_TITLE_CHARS = 80
        private const val MAX_TAGS = 8
    }

    private val root: File get() = File(File(appContext.filesDir, "openx"), DIR_NAME)
    private val entriesDir: File get() = File(root, DIR_ENTRIES)
    private val stateDir: File get() = File(root, DIR_STATE)
    private val indexFile: File get() = File(root, FILE_INDEX)
    private val cursorFile: File get() = File(stateDir, FILE_CURSOR)

    // ── 条目读写 ──

    /** 全部知识，按最近更新倒序。 */
    fun loadAll(): List<KnowledgeEntry> = synchronized(lock) { readAllUnlocked() }

    fun find(id: String): KnowledgeEntry? = synchronized(lock) {
        readAllUnlocked().firstOrNull { it.id == id }
    }

    /**
     * 新增或覆盖一条知识。
     *
     * `id` 给出且已有同 id 文件时视为更新（保留 created、沿用 id）；否则按标题生成新 id。
     * `source` 留空时沿用原条目已有的来源（更新时不把来源冲掉）。
     *
     * @return 落盘后的条目；标题或正文为空时返回 null（不写空条目）。
     */
    fun save(
        id: String,
        title: String,
        tags: List<String>,
        body: String,
        /** 来源标记；空串 = 沿用原条目已有的来源（新增且不传时为无来源）。 */
        source: String = "",
        now: Long = System.currentTimeMillis()
    ): KnowledgeEntry? {
        val cleanTitle = title.trim().take(MAX_TITLE_CHARS)
        val cleanBody = body.trim().take(MAX_BODY_CHARS)
        if (cleanTitle.isBlank() || cleanBody.isBlank()) return null
        return synchronized(lock) {
            val all = readAllUnlocked()
            // 只认「显式给 id 且命中已有条目」为更新；同标题即使 id 不同也当作同一条（提炼模型
            // 两次给出的 slug 不一致时，靠标题兜住，否则会攒出一堆重复条目）。
            val existing = all.firstOrNull { it.id == id && id.isNotBlank() }
                ?: all.firstOrNull { it.title.equals(cleanTitle, ignoreCase = true) }
            val finalId = existing?.id ?: uniqueIdUnlocked(id, cleanTitle, all)
            val entry = KnowledgeEntry(
                id = finalId,
                title = cleanTitle,
                tags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_TAGS),
                source = source.trim().ifBlank { existing?.source.orEmpty() },
                created = existing?.created?.takeIf { it > 0L } ?: now,
                updated = now,
                body = cleanBody
            )
            entriesDir.mkdirs()
            val file = File(entriesDir, "$finalId.md")
            file.writeText(render(entry))
            rebuildIndexUnlocked()
            FileLogger.i(TAG, "知识已保存: ${entry.id}（${entry.title}）-> ${file.absolutePath}")
            entry.copy(path = file.absolutePath)
        }
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val file = File(entriesDir, "${sanitizeId(id)}.md")
        val ok = file.isFile && file.delete()
        if (ok) rebuildIndexUnlocked()
        FileLogger.i(TAG, "知识已删除: $id（$ok）")
        ok
    }

    // ── 查询 ──

    /**
     * 给提炼模型看的「已有知识目录」：只给 id / 标题 / 标签。
     *
     * 目的只有一个——让它判断该 ADD 还是 UPDATE，避免每轮都攒重复条目。
     */
    fun catalogForExtractor(limit: Int = 200): String = synchronized(lock) {
        readAllUnlocked().take(limit).joinToString("\n") { e ->
            "- [${e.id}] ${e.title}" + if (e.tags.isEmpty()) "" else "（${e.tags.joinToString("、")}）"
        }
    }

    /** 注入提示词的目录：让 AI 知道「存在这些知识」，需要细节时再调 knowledge_search。 */
    fun indexForPrompt(limit: Int = 40): String = synchronized(lock) {
        readAllUnlocked().take(limit).joinToString("\n") { e ->
            "- ${e.title}" + if (e.tags.isEmpty()) "" else "（${e.tags.joinToString("、")}）"
        }
    }

    /** 关键词检索（标题 / 标签 / 正文，大小写不敏感）。 */
    fun search(query: String, limit: Int = 8): List<KnowledgeEntry> = synchronized(lock) {
        val q = query.trim().lowercase(Locale.ROOT)
        val all = readAllUnlocked()
        if (q.isBlank()) return@synchronized all.take(limit)
        // 空格分词后要求**每词都命中**：否则「compose 键盘」会被大量只沾一个词的条目淹掉。
        val terms = q.split(' ', '\u3000').filter { it.isNotBlank() }
        all.asSequence()
            .map { e -> e to scoreOf(e, terms) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
            .toList()
    }

    /** 命中打分：标题 > 标签 > 正文——标题命中通常就是要找的那条。 */
    private fun scoreOf(e: KnowledgeEntry, terms: List<String>): Int {
        val title = e.title.lowercase(Locale.ROOT)
        val tags = e.tags.joinToString(" ").lowercase(Locale.ROOT)
        val body = e.body.lowercase(Locale.ROOT)
        var score = 0
        terms.forEach { t ->
            val inTitle = title.contains(t)
            val inTags = tags.contains(t)
            val inBody = body.contains(t)
            if (!inTitle && !inTags && !inBody) return 0
            if (inTitle) score += 10
            if (inTags) score += 6
            if (inBody) score += 1
        }
        return score
    }

    // ── 提炼进度（跨会话各记各的，互不干扰）──

    fun cursorFor(conversationKey: String): KnowledgeCursor = synchronized(lock) {
        readCursorUnlocked()[conversationKey] ?: KnowledgeCursor()
    }

    /** 轮数 +1 并返回累计值；worker 用返回值与阈值比较，决定这一轮要不要提炼。 */
    fun bumpRound(conversationKey: String): KnowledgeCursor = synchronized(lock) {
        val map = readCursorUnlocked().toMutableMap()
        val cur = map[conversationKey] ?: KnowledgeCursor()
        val next = cur.copy(rounds = cur.rounds + 1)
        map[conversationKey] = next
        writeCursorUnlocked(map)
        next
    }

    /** 提炼完成后轮数清零并记时间，下一次重新累计。 */
    fun markExtracted(conversationKey: String, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        val map = readCursorUnlocked().toMutableMap()
        map[conversationKey] = KnowledgeCursor(rounds = 0, lastExtractedAt = at)
        writeCursorUnlocked(map)
    }

    // ── 序列化 ──

    private fun render(e: KnowledgeEntry): String = buildString {
        append("---\n")
        append("id: \"").append(esc(e.id)).append("\"\n")
        append("title: \"").append(esc(e.title)).append("\"\n")
        append("tags: ").append(gson.toJson(e.tags)).append("\n")
        append("source: \"").append(esc(e.source)).append("\"\n")
        append("created: ").append(e.created).append("\n")
        append("updated: ").append(e.updated).append("\n")
        append("---\n\n")
        append(e.body)
        if (!e.body.endsWith("\n")) append("\n")
    }

    /** 解析一条知识文件；读不动就返回 null（保留文件，不因为没有 frontmatter 就删用户的东西）。 */
    private fun parseFile(file: File): KnowledgeEntry? = runCatching {
        val text = file.readText()
        val id = file.name.removeSuffix(".md")
        if (!text.startsWith("---\n")) {
            // 人工丢进 entries/ 的裸文件：文件名当标题，全文当正文。
            return@runCatching KnowledgeEntry(
                id = id, title = id, body = text.trim(),
                created = file.lastModified(), updated = file.lastModified(), path = file.absolutePath
            )
        }
        val headerEnd = text.indexOf("\n---", 4)
        if (headerEnd < 0) return@runCatching null
        val header = text.substring(4, headerEnd)
        val bodyStart = text.indexOf('\n', headerEnd + 1)
        val body = if (bodyStart >= 0) text.substring(bodyStart + 1).trim() else ""
        var title = id
        var tags: List<String> = emptyList()
        var source = ""
        var created = 0L
        var updated = 0L
        header.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().removeSurrounding("\"")
            when (key) {
                "title" -> title = value.ifBlank { id }
                "tags" -> tags = runCatching {
                    JsonParser.parseString(value).asJsonArray.map { it.asString }
                }.getOrDefault(emptyList())
                "source" -> source = value
                "created" -> created = value.toLongOrNull() ?: 0L
                "updated" -> updated = value.toLongOrNull() ?: 0L
            }
        }
        KnowledgeEntry(
            id = id, title = title, tags = tags, source = source,
            created = if (created > 0L) created else file.lastModified(),
            updated = if (updated > 0L) updated else file.lastModified(),
            body = body, path = file.absolutePath
        )
    }.getOrNull()

    private fun readAllUnlocked(): List<KnowledgeEntry> {
        if (!entriesDir.isDirectory) return emptyList()
        return entriesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") && !it.name.startsWith(".") }
            ?.mapNotNull { parseFile(it) }
            ?.sortedByDescending { it.updated }
            ?: emptyList()
    }

    /** 重建给人看的 `index.md`；模型用的是 [catalogForExtractor] / [indexForPrompt]。 */
    private fun rebuildIndexUnlocked() {
        runCatching {
            val all = readAllUnlocked()
            root.mkdirs()
            indexFile.writeText(buildString {
                append("# 知识库目录\n\n")
                append("> 自动生成，共 ").append(all.size).append(" 条；正文在 entries/ 下。\n\n")
                all.forEach { e ->
                    append("- **").append(e.title).append("**")
                    if (e.tags.isNotEmpty()) append("  ").append(e.tags.joinToString(" ") { "`$it`" })
                    append("  · ").append(e.id)
                    if (e.source.isNotBlank()) append("  · 来源：").append(e.source)
                    append("\n")
                }
            })
        }.onFailure { FileLogger.w(TAG, "重建 index.md 失败: ${it.message}", it) }
    }

    /** id 只留 ASCII 字母数字与短横线：文件名要稳、要能人工敲出；中文会被洗成空串。 */
    private fun sanitizeId(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .map { if ((it.isLetterOrDigit() && it.code < 128) || it == '-') it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(60)

    private fun uniqueIdUnlocked(rawId: String, title: String, all: List<KnowledgeEntry>): String {
        val base = sanitizeId(rawId).ifBlank { sanitizeId(title) }
            .ifBlank { "k" + System.currentTimeMillis().toString(36) }
        if (all.none { it.id == base }) return base
        var i = 2
        while (i <= 50) {
            val candidate = "$base-$i"
            if (all.none { it.id == candidate }) return candidate
            i++
        }
        return "$base-" + System.currentTimeMillis().toString(36)
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")

    private fun readCursorUnlocked(): Map<String, KnowledgeCursor> = runCatching {
        if (!cursorFile.isFile) return emptyMap()
        val type = object : TypeToken<Map<String, KnowledgeCursor>>() {}.type
        gson.fromJson<Map<String, KnowledgeCursor>>(cursorFile.readText(), type) ?: emptyMap()
    }.getOrDefault(emptyMap())

    private fun writeCursorUnlocked(map: Map<String, KnowledgeCursor>) {
        runCatching {
            stateDir.mkdirs()
            cursorFile.writeText(gson.toJson(map))
        }.onFailure { FileLogger.w(TAG, "写 cursor.json 失败: ${it.message}", it) }
    }
}
