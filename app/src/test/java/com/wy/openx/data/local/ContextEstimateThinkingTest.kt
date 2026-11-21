package com.wy.openx.data.local

import com.wy.openx.data.util.ChatUtils
import com.wy.openx.data.util.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文估算**必须剔除 thinking** 的回归。
 *
 * ## 事故经过（2026-10-07）
 *
 * 用户在长思考会话里把模型从 1M 窗口切成 512K。圆环数字从 ~148K **突然跳到 641K**，
 * 直接越过 512K 的 85% 阈值，触发自动压缩。日志：
 * `current=641881 window=512000 | 消息正文≈628874tok | 锚reported=147785`。
 *
 * ## 根因
 *
 * 库里的思考是**内联**在 assistant `content` 的 `<thinking>…</thinking>` 里的。估算函数
 * `estimateBodiesTokens` / `estimateMsgTokens` 把整段 content（含 thinking）按字面估算。
 * 而真正发请求时 thinking 被拆进独立的 `reasoning_content` 字段，provider 端**不计 prompt
 * token**（该会话 388K 字符 reasoning 整段未计入，reported 仅 148K）。
 * 于是长思考会话被系统性高估约 4.3 倍 —— 换成小窗口模型后立刻误判超窗。
 *
 * ## 修法
 *
 * 估算前用 `ChatUtils.removeThinkingContent` 剥掉 thinking，与 provider 实际计入的输入对齐。
 * 本测试锁定「含 thinking 的正文，估算结果等于剥掉 thinking 之后的正文」。
 */
class ContextEstimateThinkingTest {

    /** 与 MainViewModel.estimateBodiesTokens 对单条 content 的口径一致（含每消息固定开销）。 */
    private fun bodyTokensAsEstimated(content: String): Int =
        if (content.isBlank()) 0
        else TokenEstimator.bodyTokens(ChatUtils.removeThinkingContent(content))

    @Test
    fun `大段 thinking 不应把正文估算撑大`() {
        val thinking = "推理".repeat(30_000) // ≈ 6 万汉字
        val body = "这是最终回答。"
        val content = "<thinking>$thinking</thinking>$body"

        val naive = TokenEstimator.bodyTokens(content)
        val fixed = bodyTokensAsEstimated(content)

        // 未剥 thinking 时被按正文估算，数值巨大
        assertTrue("未剥 thinking 会虚高（naive=$naive）", naive > 80_000)
        // 剥掉后只计正文
        assertEquals(TokenEstimator.bodyTokens(body), fixed)
        assertTrue("修复后必须远小于 naive（fixed=$fixed）", fixed < naive / 10)
    }

    @Test
    fun `未闭合的流式 thinking 同样不计`() {
        // 流式过程中 content 尾部可能是 `<thinking>还没闭合`（无 </thinking>）
        val content = "正文内容<thinking>" + "边想边写".repeat(10_000)
        val fixed = bodyTokensAsEstimated(content)
        assertEquals(TokenEstimator.bodyTokens("正文内容"), fixed)
    }

    @Test
    fun `纯思考（正文为空）估算为零`() {
        val content = "<thinking>" + "思考".repeat(50_000) + "</thinking>"
        assertEquals(0, bodyTokensAsEstimated(content))
    }

    @Test
    fun `不带标签的正常正文原样估算`() {
        val body = "普通的回复内容，没有任何思考标签。"
        assertEquals(TokenEstimator.bodyTokens(body), bodyTokensAsEstimated(body))
    }
}
