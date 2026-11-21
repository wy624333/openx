package com.wy.openx.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩摘要必须**剥掉思维链**后再使用/入库。
 *
 * ## 回归背景（一次真实的严重故障）
 * 工具循环内的就地压缩原先直接用流式 `fullText` 当摘要，而 `fullText` 把模型的
 * reasoning 包在 `<thinking>` 里 —— 于是摘要从 ~1.6K 膨胀到 **11026 字符**，
 * 整段内心独白被写进 `[对话摘要]` 并此后每轮重发。用户看到的症状是
 * 「前面的消息全没了，只剩一个超大摘要」。
 *
 * 修法：摘要路径统一走 [ChatUtils.removeThinkingContent]；剥完为空视为失败
 * （绝不把带 thinking 的原文当摘要）。
 *
 * 本测试锁定「带 thinking 的摘要 → 剥成纯摘要」这一核心不变量。
 */
class CompactionSummaryThinkingTest {

    @Test
    fun `带 pense thinking 的摘要剥成纯摘要`() {
        val raw = "[对话摘要] <thinking>We need answer in Chinese, only handoff summary. " +
            "Need summarize visible history.</thinking>### 交接摘要\n- 项目与目标：网页游戏 QA。"
        val cleaned = ChatUtils.removeThinkingContent(raw)
        assertEquals("[对话摘要] ### 交接摘要\n- 项目与目标：网页游戏 QA。", cleaned)
        assertFalse("剥后不应残留 thinking 标签", cleaned.contains("thinking"))
    }

    @Test
    fun `未闭合的 thinking（流式截断）也要剥掉`() {
        val raw = "[对话摘要] <thinking>模型还在想，标签没闭合"
        val cleaned = ChatUtils.removeThinkingContent(raw)
        assertEquals("[对话摘要]", cleaned)
    }

    @Test
    fun `thinking 在中间时只剥思考、保留前后正文`() {
        val raw = "前言 <thinking>内心独白</thinking> 后记"
        assertEquals("前言  后记", ChatUtils.removeThinkingContent(raw))
    }

    @Test
    fun `只含 thinking 的摘要剥后为空（调用方应判失败）`() {
        val raw = "<thinking>只有思考，没有摘要</thinking>"
        assertTrue(ChatUtils.removeThinkingContent(raw).isBlank())
    }

    @Test
    fun `正常摘要（无 thinking）原样保留`() {
        val raw = "[对话摘要] ### 交接摘要\n- 已完成：修复浮点血量"
        assertEquals(raw, ChatUtils.removeThinkingContent(raw))
    }
}
