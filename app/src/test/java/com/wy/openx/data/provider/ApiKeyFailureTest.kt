package com.wy.openx.data.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护 [isApiKeyFailure]：429 是速率限流，**不是** Key 失效。
 *
 * 回归背景：旧实现把 "http 429" 列进 KEY_FAILURE_MESSAGES，于是限流时
 * 换 Key 无效（同一把 Key 限流不代表别的 Key 也能用），还会把所有 Key 打进冷却，
 * 把「等几秒就好」升级成「整轮对话彻底卡住」。
 */
class ApiKeyFailureTest {

    @Test
    fun `429 属于限流，不算 Key 故障`() {
        assertFalse(IllegalStateException("HTTP 429: rate limited").isApiKeyFailure())
    }

    @Test
    fun `rate limit 与 too many requests 同样不算 Key 故障`() {
        assertFalse(RuntimeException("Rate Limit Exceeded").isApiKeyFailure())
        assertFalse(RuntimeException("Too Many Requests").isApiKeyFailure())
    }

    @Test
    fun `401 与403 仍是 Key 故障`() {
        assertTrue(IllegalStateException("HTTP 401: unauthorized").isApiKeyFailure())
        assertTrue(IllegalStateException("HTTP 403: forbidden").isApiKeyFailure())
    }

    @Test
    fun `余额不足与 key 无效仍算 Key 故障`() {
        assertTrue(IllegalStateException("HTTP 402: insufficient balance").isApiKeyFailure())
        assertTrue(IllegalStateException("invalid api key").isApiKeyFailure())
    }

    @Test
    fun `普通网络错误不算 Key 故障`() {
        assertFalse(IllegalStateException("HTTP 500: server error").isApiKeyFailure())
        assertFalse(java.io.IOException("timeout").isApiKeyFailure())
    }
}
