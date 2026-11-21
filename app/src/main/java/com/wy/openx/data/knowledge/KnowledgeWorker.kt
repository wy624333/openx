package com.wy.openx.data.knowledge

import android.os.Handler
import android.os.Looper
import com.wy.openx.container.FileLogger
import com.wy.openx.data.store.KnowledgeStore
import com.wy.openx.tools.AppContextHolder
import java.util.concurrent.Executors

/**
 * 知识提炼调度器。
 *
 * 每轮对话结束时调 [onTurnFinished]，由它决定这一轮要不要真去跑模型：
 * - 按「每 N 轮提炼一次」累计（N=1 即每轮），进度记在 store 的游标里，跨会话各记各的；
 * - 没到阈值就把这一轮文本先攒在内存，到阈值时把攒下的几轮**合并成一次请求**——
 *   攒批是为了省调用：否则前 N-1 轮的内容永远读不到；
 * - 所有提炼任务走**单线程**队列：多会话并行时串行执行，既不打爆供应商限流，
 *   也避免两个线程同时重写 index.md。
 *
 * 提炼用哪个模型由调用方解析好（空配置 = 跟随聊天模型），见 [ModelRef]。
 */
object KnowledgeWorker {

    private const val TAG = "KnowledgeWorker"
    private const val MAX_TRANSCRIPT_CHARS = 24_000

    /** 解析好的提炼模型三要素。 */
    data class ModelRef(val baseUrl: String, val apiKey: String, val model: String)

    /** 提炼落盘后的回调（已在主线程）——UI 用它刷新知识列表。 */
    @Volatile
    var onExtracted: (() -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "knowledge-extract") }
    private val lock = Any()

    /** 各会话已攒、尚未提炼的文本（攒批缓冲，进程内有效）。 */
    private val pending = mutableMapOf<String, StringBuilder>()

    /**
     * 一轮对话结束。
     *
     * @param conversationKey 会话标识（会话 id 的字符串形式）
     * @param conversationTitle 会话标题，会写进知识的 source，便于回溯这条知识来自哪次对话
     * @param transcript 这一轮的文本（用户消息 + 助手回复）
     * @param everyN 每几轮提炼一次；<= 1 表示每轮都提炼
     */
    fun onTurnFinished(
        conversationKey: String,
        conversationTitle: String,
        transcript: String,
        everyN: Int,
        model: ModelRef
    ) {
        if (conversationKey.isBlank() || transcript.isBlank()) return
        if (AppContextHolder.context == null) return

        val store = KnowledgeStore(AppContextHolder.context!!)
        val cursor = store.bumpRound(conversationKey)

        val batchText: String? = synchronized(lock) {
            val buffer = pending.getOrPut(conversationKey) { StringBuilder() }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(transcript)
            if (cursor.rounds < everyN.coerceAtLeast(1)) {
                null
            } else {
                val text = buffer.toString()
                pending.remove(conversationKey)
                text
            }
        }
        if (batchText.isNullOrBlank()) return

        val title = conversationTitle.trim().ifBlank { "会话" }
        val source = "$title (#$conversationKey)"
        submit(conversationKey, source, batchText, model)
    }

    /** 丢弃某会话攒着的文本（会话被删时调用，避免它的内容之后被提炼进知识库）。 */
    fun forget(conversationKey: String) {
        synchronized(lock) { pending.remove(conversationKey) }
    }

    private fun submit(conversationKey: String, source: String, transcript: String, model: ModelRef) {
        executor.execute {
            val ctx = AppContextHolder.context ?: return@execute
            val store = KnowledgeStore(ctx)
            runCatching {
                val items = KnowledgeExtractor.extract(
                    transcript = transcript.take(MAX_TRANSCRIPT_CHARS),
                    existingCatalog = store.catalogForExtractor(),
                    baseUrl = model.baseUrl,
                    apiKey = model.apiKey,
                    model = model.model
                )
                if (items == null) {
                    // 请求失败：不动游标，轮数留着，下一轮会带着同一批内容再试一次。
                    FileLogger.w(TAG, "知识提炼未完成（请求失败），保留轮数待重试：$source")
                    return@runCatching
                }
                var saved = 0
                items.forEach { candidate ->
                    if (candidate.title.isBlank() || candidate.body.isBlank()) return@forEach
                    val entry = store.save(
                        id = candidate.id,
                        title = candidate.title,
                        tags = candidate.tags,
                        body = candidate.body,
                        source = source
                    )
                    if (entry != null) saved++
                }
                store.markExtracted(conversationKey)
                FileLogger.i(TAG, "知识提炼完成：$source 候选 ${items.size} 条，落盘 $saved 条")
            }.onFailure { FileLogger.w(TAG, "知识提炼异常：${it.message}", it) }
            onExtracted?.let { callback -> Handler(Looper.getMainLooper()).post { callback() } }
        }
    }
}
