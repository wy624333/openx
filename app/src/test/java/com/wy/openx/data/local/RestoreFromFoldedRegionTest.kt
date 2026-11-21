package com.wy.openx.data.local

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠区上点「仅恢复会话」后上下文数字暴跌的回归。
 *
 * ## 事故经过（第二次踩同一类坑，但根因不同）
 *
 * 用户对一个历史会话手动压缩（上下文 58K）→ 往上翻折叠区 → 对其中一条消息弹
 * 「仅恢复会话」→ 消息看着全都在界面上，顶部数字却只剩 **18K**。
 *
 * 第一版修法（见 [RestoreUnfoldsTest]）只解决了 `folded` 标记没清的问题，
 * 但**语义本身就错了**：`rewindMessage` 对折叠区一律走 `all.take(idx)`，
 * 也就是「回退到点的那条之前」——而 idx 之前只有折叠区那几条原文。
 *
 * 于是：
 * - 点压缩前**最后**那条 → take(idx) ≈ 全量 → 数字看着是对的（用户第一次恢复成功）；
 * - 点**更靠前**的消息（用户第二次往上翻，随手点了第二早的那条）→
 *   take(idx) 只剩两三帧 → 上下文 58K → 18K。
 *
 * 「同一操作、结果不同」，所以第一次测完全正常，根本怀疑不到这里。
 *
 * ## 正确语义
 *
 * 「仅恢复会话」= **解除压缩**：折叠的原文**全部**回到上下文，尾部一条不砍。
 * 它不是「回退到某一刻」——那是 `RESTORE_CODE_AND_CONVERSATION`（顺带回滚文件）的职责。
 * 因此恢复结果**与点在折叠区哪一条无关**。
 *
 * 同时必须剔除压缩产物（marker / 摘要 / 压缩通知）：原文全回来之后摘要若留着，
 * 同一段内容会被计两次 token，数字不降反升。
 */
class RestoreFromFoldedRegionTest {

    private fun msg(id: Long, seq: Long, content: String, folded: Boolean) = ChatMessageModel(
        id = id, role = MessageRole.USER, author = "我", time = "10:00",
        content = content, folded = folded, seq = seq
    )

    /**
     * 与 `MainViewModel.compactionArtifactIds` 保持一致（测试不引用 VM：那是 Android 类，
     * 复制表达式比依赖 mock android.jar 更稳；改那边必须同步改这里）。
     *
     * 注意新摘要是**裸文本**：压缩只给 marker 打了 [上下文已压缩] 这个定值，摘要行没有任何前缀，
     * 所以只能靠「marker 的下一条」配对（`all` 必须已按 seq,id 排好）。
     */
    private fun artifactIds(all: List<ChatMessageModel>): Set<Long> {
        val out = HashSet<Long>(all.size)
        for (i in all.indices) {
            val m = all[i]
            val c = m.content
            if (c == "[上下文已压缩]") {          // COMPACTION_MARKER
                out += m.id
                all.getOrNull(i + 1)?.let { out += it.id }   // 紧随其后的裸文本摘要
            } else if (c != null && (c.startsWith("[对话摘要]") ||   // 旧格式 SUMMARY_PREFIX
                        c.startsWith("📦 ") ||               // COMPACTION_NOTICE_PREFIX
                        c.startsWith("⚠️ ")                  // COMPACTION_NOTICE_FAIL_PREFIX
                    )
            ) {
                out += m.id
            }
        }
        return out
    }

    /**
     * 压缩后的完整历史（按 seq 排序，与 `loadAllMessagesOfConversation` 的
     * `sortedWith(compareBy({ seq }, { id }))` 一致）：
     * 折叠原文 seq 1..6 → 压缩保留尾 seq 7 → marker seq 8 → **裸文本**摘要 seq 9。
     */
    private fun allHistory() = listOf(
        msg(1L, 1L, "老问题1", folded = true),
        msg(2L, 2L, "老回答1", folded = true),
        msg(3L, 3L, "老问题2", folded = true),
        msg(4L, 4L, "老回答2", folded = true),
        msg(5L, 5L, "老问题3", folded = true),
        msg(6L, 6L, "老回答3", folded = true),
        msg(7L, 7L, "压缩保留尾的问题", folded = false),
        msg(8L, 8L, "[上下文已压缩]", folded = false),
        msg(9L, 9L, "这是一段裸文本摘要，没有可嗅探的前缀", folded = false),
        msg(10L, 10L, "在吗", folded = false)
    )

    /** 旧实现：按 idx 截断。 */
    private fun finalMsgsBroken(all: List<ChatMessageModel>, idx: Int) =
        all.take(idx).map { it.copy(folded = false) }

    /** 新实现：解除压缩，全部原文回来 + 剔除压缩产物。 */
    private fun finalMsgsFixed(all: List<ChatMessageModel>, idx: Int) =
        all.filterNot { it.id in artifactIds(all) }.map { it.copy(folded = false) }

    @Test
    fun `点第二早的消息不该把上下文截成两三帧`() {
        val all = allHistory()
        // 用户点「老回答1」(id=2, idx=1) —— 折叠区里第二早的那条
        val targetIdx = all.indexOfFirst { it.content == "老回答1" }
        assertEquals(1, targetIdx)

        val broken = finalMsgsBroken(all, targetIdx)
        assertEquals(
            "坏实现下点第二早的消息只剩 1 条上下文（用户看到的 18K）",
            1, broken.count { !it.folded }
        )

        val fixed = finalMsgsFixed(all, targetIdx)
        // 8 条原文/保留尾 + 压缩后的新消息「在吗」全部回到上下文，一条不砍
        assertEquals(
            "解除压缩后折叠的原文必须全部回来",
            8, fixed.count { !it.folded }
        )
        assertTrue("最早的原文也要在", fixed.any { it.content == "老问题1" })
        assertTrue("最新的原文也要在", fixed.any { it.content == "老回答3" })
        assertTrue("压缩保留尾不能被砍掉", fixed.any { it.content == "压缩保留尾的问题" })
    }

    @Test
    fun `恢复结果与点在折叠区哪一条无关`() {
        val all = allHistory()
        val onOldest = finalMsgsFixed(all, all.indexOfFirst { it.content == "老问题1" })
        val onSecondOldest = finalMsgsFixed(all, all.indexOfFirst { it.content == "老回答1" })
        val onNewest = finalMsgsFixed(all, all.indexOfFirst { it.content == "老回答3" })
        assertEquals(
            "点哪条都必须恢复出同一份完整历史（否则用户无从预期）",
            onOldest.map { it.id }, onSecondOldest.map { it.id }
        )
        assertEquals(onOldest.map { it.id }, onNewest.map { it.id })
    }

    @Test
    fun `压缩产物必须剔除否则原文与摘要重复计 token`() {
        val all = allHistory()
        val fixed = finalMsgsFixed(all, 0)
        assertFalse("marker 不能留", fixed.any { it.content == "[上下文已压缩]" })
        assertFalse(
            "裸文本摘要必须靠 marker 配对剔除（否则与原文重复计 token）",
            fixed.any { it.content.startsWith("这是一段裸文本摘要") }
        )
        assertFalse("压缩通知不能留", fixed.any { it.content.startsWith("📦 ") })
        assertFalse("失败通知不能留", fixed.any { it.content.startsWith("⚠️ ") })
    }

    @Test
    fun `解除压缩后不应再有折叠标记`() {
        val fixed = finalMsgsFixed(allHistory(), 0)
        assertTrue("全部消息都要重新计入请求", fixed.none { it.folded })
    }

    @Test
    fun `点压缩后的新消息选仅恢复会话也要整会话恢复而非截断小尾巴`() {
        val all = allHistory()
        // 用户压在压缩之后追加的新消息（如「在吗」）上——它不在折叠区里。
        val onNew = finalMsgsFixed(all, all.indexOfFirst { it.content == "在吗" })
        val onFolded = finalMsgsFixed(all, all.indexOfFirst { it.content == "老回答1" })
        assertEquals("恢复结果与点折叠区还是压缩后的新消息无关", onFolded.map { it.id }, onNew.map { it.id })
        assertTrue("最早的原文也要在", onNew.any { it.content == "老问题1" })
        assertTrue("最新的原文也要在", onNew.any { it.content == "老回答3" })
        assertTrue("压缩后的新消息也不能被截掉", onNew.any { it.content == "在吗" })
    }

    @Test
    fun `旧实现的截断行为正是数字暴跌的原因`() {
        val all = allHistory()
        val targetIdx = all.indexOfFirst { it.content == "老回答1" }
        val broken = finalMsgsBroken(all, targetIdx)
        // 折叠原文都回来了，但 idx 之后的原文和保留尾全被砍掉
        assertTrue(broken.any { it.content == "老问题1" })
        assertFalse("坏实现砍掉了后面的原文", broken.any { it.content == "老回答3" })
        assertFalse("坏实现砍掉了压缩保留尾", broken.any { it.content == "压缩保留尾的问题" })
    }
}