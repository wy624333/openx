package com.wy.openx.data.local

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「压缩完了但卡片不出现，一暂停才出现」的回归。
 *
 * ## 事故经过
 *
 * AI 执行中触发压缩 → 压缩事务已提交（marker + 摘要都在库里，暂停后卡片也确实出现了，
 * 证明数据没丢）→ 但压缩那一刻**卡片不显示**，AI 都开始跑下一个工具了还没出现，
 * 用户按暂停，卡片才立刻出现。
 *
 * ## 根因
 *
 * 单一消息流在**发送中**被 `if (!s.isSending)` 整个跳过 `messages` 回填。
 * 这个门控的动机是对的：流式正文只改内存、不写库，DB 落后于内存，
 * 整表覆盖会把正在增长的正文回退掉（正文一闪一闪）。
 *
 * 但它是**一刀切**，把压缩产物一起挡了：marker 是 ctx（`folded=false`）里的新行，
 * 回填被跳过 → `uiState.messages` 里没有 marker → `displayMessages` 里没有 marker →
 * 卡片无从渲染。而此时**库里数据早已写好**，暂停使 `isSending=false`，
 * 走完整回填分支，卡片这才补上 —— 症状与「数据丢失」几乎一样，但数据是好的。
 *
 * ## 正确做法
 *
 * 不整体跳过，而是**内存优先合并**：同 id 取内存版（不退回流式正文），
 * 仅 DB 有的（marker/摘要、已落库的工具结果）补进来。两条相反的需求同时满足。
 */
class CompactionCardDuringStreamTest {

    private fun msg(
        id: Long, seq: Long, content: String, role: MessageRole = MessageRole.USER
    ) = ChatMessageModel(
        id = id, role = role, author = "我", time = "10:00", content = content, seq = seq
    )

    /** 与 `MainViewModel.mergeKeepingMemory` 保持一致（测试不引用 VM，那是 Android 类）。 */
    private fun merge(mem: List<ChatMessageModel>, db: List<ChatMessageModel>): List<ChatMessageModel> {
        if (db.isEmpty()) return mem
        if (mem.isEmpty()) return db
        val memIds = mem.mapTo(HashSet(db.size)) { it.id }
        return (mem + db.filterNot { it.id in memIds })
            .distinctBy { it.id }
            .sortedWith(compareBy({ if (it.seq == 0L) Long.MAX_VALUE else it.seq }, { it.id }))
    }

    /** 内存里正在流式的那条（seq=0 = 未落库），DB 里还没有它。 */
    private val streaming = msg(100L, 0L, "我正在写这段回答…写了一半", role = MessageRole.ASSISTANT)

    /** DB 发射的上下文：压缩产物（marker + 裸文本摘要）已经写入。 */
    private val dbAfterCompaction = listOf(
        msg(1L, 1L, "老问题", role = MessageRole.ASSISTANT),
        msg(90L, 90L, "[上下文已压缩]"),
        msg(91L, 91L, "这是一段裸文本摘要"),
        msg(92L, 92L, "压缩保留尾")
    )

    @Test
    fun `压缩产物必须在发送中立刻可见否则卡片不出现`() {
        val merged = merge(listOf(msg(1L, 1L, "老问题", role = MessageRole.ASSISTANT), streaming), dbAfterCompaction)
        assertTrue(
            "marker 必须已进内存上下文（否则 UI 渲染不出压缩卡）",
            merged.any { it.content == "[上下文已压缩]" }
        )
        assertTrue("摘要也必须已进内存上下文", merged.any { it.content == "这是一段裸文本摘要" })
    }

    @Test
    fun `不能因为回填而把正在流式的正文回退掉`() {
        val half = msg(100L, 0L, "只写了一半", role = MessageRole.ASSISTANT)
        // 模拟一个更旧的 DB 版本：同一 id、内容更短（DB 落后于内存）
        val stale = msg(100L, 95L, "只写了", role = MessageRole.ASSISTANT)
        val merged = merge(listOf(half), listOf(stale))
        assertEquals(1, merged.size)
        assertEquals(
            "同 id 必须取内存版本，否则正文一闪一闪",
            "只写了一半", merged[0].content
        )
    }

    @Test
    fun `未落库的流式消息排在最后`() {
        val merged = merge(listOf(streaming), dbAfterCompaction)
        assertEquals("流式消息应在末尾（它正在生成）", 100L, merged.last().id)
        assertEquals(0L, merged.last().seq)
    }

    @Test
    fun `旧的门控行为是卡片延迟出现的直接原因`() {
        // 复刻修法前的那条分支：发送中只刷折叠区，messages 完全不动
        val memBefore = listOf(msg(1L, 1L, "老问题", role = MessageRole.ASSISTANT), streaming)
        assertTrue(
            "门控下内存里没有 marker → 卡片不显示（这就是事故现象）",
            memBefore.none { it.content == "[上下文已压缩]" }
        )
        // 暂停 → isSending=false → 整表回填 → marker 进来 → 卡片出现
        val afterPause = dbAfterCompaction
        assertTrue("暂停后 marker 到位，卡片这才出现", afterPause.any { it.content == "[上下文已压缩]" })
    }

    @Test
    fun `DB 为空时不产生无谓的新列表`() {
        val mem = listOf(streaming)
        assertTrue("DB 空时直接返回内存列表（引用相等，省一次拷贝）", merge(mem, emptyList()) === mem)
        val db = dbAfterCompaction
        assertTrue("内存空时直接返回 DB 列表", merge(emptyList(), db) === db)
    }

    @Test
    fun `已落库的工具结果也要合并进来`() {
        val mem = listOf(msg(1L, 1L, "老问题", role = MessageRole.ASSISTANT), streaming)
        val db = listOf(
            msg(1L, 1L, "老问题", role = MessageRole.ASSISTANT),
            msg(50L, 50L, "工具结果已落库")
        )
        val merged = merge(mem, db)
        assertTrue("工具结果只存在于 DB，也必须可见", merged.any { it.content == "工具结果已落库" })
        assertEquals(3, merged.size)
    }
}