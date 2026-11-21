package com.wy.openx.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ④「请求过大」识别的回归测试。
 *
 * 误判的代价不对称：
 *  - 漏判：多等一轮/多压缩一次，用户觉得慢；
 *  - 误判：把限流当超长，触发无谓的强制压缩 + 重试，还可能掩盖真正的故障。
 * 所以边界必须钉死在「状态码 ∈ {400,413,422} 且命中厂商文案」，5xx 一律不算。
 */
class ContextOverflowTest {

    @Test
    fun `各厂商典型超长文案都应命中`() {
        val samples = listOf(
            400 to "This model's maximum context length is 8192 tokens, however you requested 9000 tokens.",
            400 to "{\"error\":{\"message\":\"prompt is too long: 210000 tokens > 200000 maximum\",\"type\":\"invalid_request_error\"}}",
            400 to "input is too long for requested model",
            413 to "{\"error\":{\"message\":\"request_too_large\",\"type\":\"request_too_large\"}}",
            422 to "reduce the length of the messages",
            400 to "This model's maximum context length is 128000 tokens. However, your messages resulted in 250000 tokens.",
            400 to "input token count exceeds the maximum allowed",
            422 to "prompt exceeds max length",
            400 to "prompt has 120000 tokens, but the configured context size is 100000 tokens",
            400 to "context_length_exceeded"
        )
        for ((code, msg) in samples) {
            assertTrue("应判定为超长: $msg", ContextOverflow.isOverflow(code, msg))
        }
    }

    @Test
    fun `5xx 一律不算超长`() {
        // 中转网关请求体超限普遍回 500，但 500 也可能是服务端故障：
        // 拿它去触发「压缩后重试」只会白等一轮。
        listOf(500, 502, 503, 504).forEach { code ->
            assertFalse("HTTP $code 不应算超长", ContextOverflow.isOverflow(code, "HTTP $code: internal error"))
        }
    }

    @Test
    fun `限流与服务不可用不得判成超长`() {
        assertFalse(ContextOverflow.isOverflow(400, "Throttling error: rate limit exceeded for tokens"))
        assertFalse(ContextOverflow.isOverflow(400, "429 Too Many Requests: too many requests"))
        assertFalse(ContextOverflow.isOverflow(400, "Service unavailable: upstream is down"))
    }

    @Test
    fun `状态码之外一律不判超长`() {
        assertFalse(ContextOverflow.isOverflow(404, "model not found: prompt is too long"))
        assertFalse(ContextOverflow.isOverflow(401, "invalid api key, prompt is too long"))
        assertFalse(ContextOverflow.isOverflow(null, "prompt is too long"))
    }

    @Test
    fun `400 但无关文案不判超长`() {
        assertFalse(ContextOverflow.isOverflow(400, "{\"error\":{\"message\":\"invalid json body\"}}"))
        assertFalse(ContextOverflow.isOverflow(400, ""))
    }

    @Test
    fun `HTTP 状态与正文跨行也能命中`() {
        val diagnostic = "HTTP 400\n{\"error\":{\"message\":\"This model's maximum context length is 32768 tokens.\"}}"
        assertTrue(ContextOverflow.isOverflow(400, diagnostic))
    }
}
