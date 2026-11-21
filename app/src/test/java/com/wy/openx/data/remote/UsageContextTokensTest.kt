package com.wy.openx.data.remote

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死「窗口占用 = prompt_tokens，且不得重复加 cached_tokens」。
 *
 * 背景：曾把 onContextTokens 实现成 `prompt_tokens + cached_tokens`，理由是「网关只把未命中
 * 部分计入 prompt_tokens」。实际抓包（ai-logs/session-338.log）证明该假设不成立：
 *
 *   {"prompt_tokens":219821,"completion_tokens":526,"total_tokens":220347,
 *    "prompt_tokens_details":{"cached_tokens":10240}}
 *
 * 219821 + 526 == 220347 == total_tokens，且 cached_tokens(10240) 是 prompt_tokens 的子集。
 * 也就是说 prompt_tokens 已经是「含缓存的完整窗口占用」，再加一次就是重复计。
 *
 * 后果不是小偏差：同一约 21 万 token 的会话，仅因缓存命中量不同就报出
 *   缓存命中 211456 → 424465
 *   缓存命中  10240 → 230061
 * 数字随缓存命中量抖动而非随真实上下文变化，自动压缩判定被彻底带偏
 * （09:54 判 450808 触发不了压缩，10:23 判 230061 又远低于真实值）。
 */
class UsageContextTokensTest {

    private val gson = Gson()

    /** 窗口占用口径 = max(total_tokens, prompt+completion)，对齐 OmniBot。 */
    private fun contextTokens(usage: Usage): Int = usage.reportedContextTokens()

    private fun parse(json: String): Usage = gson.fromJson(json, Usage::class.java)

    @Test
    fun `cached_tokens 是 prompt_tokens 的子集 不得重复相加`() {
        val raw = """{"prompt_tokens":219821,"completion_tokens":526,"total_tokens":220347,
            "prompt_tokens_details":{"cached_tokens":10240}}"""
        val u = parse(raw)

        // 先确认前提：prompt + completion == total，cached 是子集
        assertEquals(u.totalTokens, u.promptTokens + u.completionTokens)
        assertTrue("cached 应为 prompt 的子集", u.effectiveCacheHitTokens < u.promptTokens)

        // 窗口占用 = max(total, prompt+completion) = 220347；误加缓存会得到 230061
        assertEquals(220347, contextTokens(u))
        assertEquals(219821, u.promptTokens)
    }

    @Test
    fun `缓存大量命中时窗口占用不随命中量漂移`() {
        // 同一上下文（prompt 固定 213009），仅缓存命中量不同
        val highHit = parse("""{"prompt_tokens":213009,"completion_tokens":351,
            "total_tokens":213360,"prompt_tokens_details":{"cached_tokens":211456}}""")
        val lowHit = parse("""{"prompt_tokens":213009,"completion_tokens":351,
            "total_tokens":213360,"prompt_tokens_details":{"cached_tokens":10240}}""")

        assertEquals(211456, highHit.effectiveCacheHitTokens)
        assertEquals(10240, lowHit.effectiveCacheHitTokens)

        // 关键：窗口占用必须完全一致，不受缓存命中量影响
        assertEquals(contextTokens(highHit), contextTokens(lowHit))
        // 同一上下文（prompt 同为 213009）→ 窗口占用同为 213360，不受缓存命中量影响
        assertEquals(213360, contextTokens(highHit))
    }

    @Test
    fun `Anthropic 风格 prompt_cache_hit_tokens 同样不参与窗口占用`() {
        val u = parse("""{"prompt_tokens":180000,"completion_tokens":200,
            "total_tokens":180200,"prompt_cache_hit_tokens":120000}""")
        assertEquals(120000, u.effectiveCacheHitTokens)
        // max(180200, 180000+200) = 180200
        assertEquals(180200, contextTokens(u))
    }

    @Test
    fun `prompt_tokens 缺失时回退 total 减 completion`() {
        val u = parse("""{"completion_tokens":300,"total_tokens":50300}""")
        assertEquals(0, u.promptTokens)
        // prompt 缺失时 max(total, 0+300) = total = 50300
        assertEquals(50300, contextTokens(u))
    }

    @Test
    fun `无缓存字段时 effectiveCacheHitTokens 为零且不影响窗口占用`() {
        val u = parse("""{"prompt_tokens":1702,"completion_tokens":243,"total_tokens":1945}""")
        assertEquals(0, u.effectiveCacheHitTokens)
        // max(1945, 1702+243) = 1945
        assertEquals(1945, contextTokens(u))
    }

    // ---- 对齐 OmniBot resolveReportedContextTokens: max(total, prompt+completion) ----

    @Test
    fun `上下文占用取 total 与 prompt加completion 的较大者`() {
        // session-338 实测：total(220347) > prompt+completion(220347) 相等，取 total
        val u = parse("""{"prompt_tokens":219821,"completion_tokens":526,
            "total_tokens":220347,"prompt_tokens_details":{"cached_tokens":10240}}""")
        assertEquals(220347, u.reportedContextTokens())
    }

    @Test
    fun `total 偏小时取 prompt 加 completion`() {
        // 有的 provider 不把本轮输出算进 total，prompt+completion 才是真实窗口
        val u = parse("""{"prompt_tokens":10000,"completion_tokens":2000,"total_tokens":10000}""")
        assertEquals(12000, u.reportedContextTokens())
    }

    @Test
    fun `两个字段都缺失时返回零由调用方回退估算`() {
        assertEquals(0, parse("""{"completion_tokens":0}""").reportedContextTokens())
    }

    @Test
    fun `contextTokens 全部反映真实占用 不受缓存命中量影响`() {
        // 同一约 21 万的会话，缓存命中 211456 与 10240，窗口占用必须一致
        val a = parse("""{"prompt_tokens":213009,"completion_tokens":351,
            "total_tokens":213360,"prompt_tokens_details":{"cached_tokens":211456}}""")
        val b = parse("""{"prompt_tokens":219821,"completion_tokens":526,
            "total_tokens":220347,"prompt_tokens_details":{"cached_tokens":10240}}""")
        assertEquals(213360, a.reportedContextTokens())
        assertEquals(220347, b.reportedContextTokens())
        // 两者都应落在真实占用区间，且不含 cached 的重复计
        assertTrue(a.reportedContextTokens() < 250000)
        assertTrue(b.reportedContextTokens() < 250000)
    }
}
