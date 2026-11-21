package com.wy.openx.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 估算口径（③）。
 *
 * 改造前的中文系数偏乐观（中文 1.0 + 英文 /4 + 全局 ×1.1），对中文会话系统性低估，
 * 压缩触发得太晚。现在统一走 OmniBot / Gemini CLI 的口径：ASCII 4 字符 ≈ 1 token，
 * 非 ASCII 1.5 token/字。
 */
class TokenEstimatorTest {

    @Test
    fun `ASCII 按四字符一 token`() {
        assertEquals(0, TokenEstimator.textTokens(""))
        assertEquals(1, TokenEstimator.textTokens("a"))
        assertEquals(1, TokenEstimator.textTokens("abcd"))
        // 向上取整，不做「截断式」低估
        assertEquals(2, TokenEstimator.textTokens("abcde"))
    }

    @Test
    fun `中文按一点五 token 每字`() {
        // 100 字中文 ≈ 150 token
        val t = TokenEstimator.textTokens("中".repeat(100))
        assertEquals(150, t)
    }

    @Test
    fun `中文不得比同长度 ASCII 估得更少`() {
        val cjk = TokenEstimator.textTokens("测".repeat(200))
        val ascii = TokenEstimator.textTokens("a".repeat(200))
        assertTrue("中文($cjk) 必须高于同长度 ASCII($ascii)", cjk > ascii)
    }

    @Test
    fun `每条消息都计固定协议开销`() {
        val one = TokenEstimator.messagesTokens(listOf("abcd"))
        assertEquals("4 字符 = 1 token + 4 开销", 5, one)
        assertEquals(10, TokenEstimator.messagesTokens(listOf("abcd", "abcd")))
        assertEquals("空串不计开销", 0, TokenEstimator.messagesTokens(listOf("")))
    }

    @Test
    fun `工具定义计入固定额度`() {
        val bodies = listOf("abcd")
        val base = TokenEstimator.messagesTokens(bodies)
        val withTools = TokenEstimator.messagesTokens(bodies, toolTokens = 30_000)
        assertEquals(base + 30_000, withTools)
    }

    @Test
    fun `图片不再按 base64 文本计`() {
        // base64 若按 ASCII 算，一张图能虚增出好几万 token；OmniBot 固定按 1200 计
        assertEquals(1200, TokenEstimator.IMAGE_TOKENS)
        assertTrue(TokenEstimator.IMAGE_TOKENS < TokenEstimator.textTokens("A".repeat(50_000)))
    }

    @Test
    fun `负额外开销不会得到负值`() {
        assertEquals(0, TokenEstimator.messagesTokens(listOf("abcd"), extraTokens = -100))
    }
}
