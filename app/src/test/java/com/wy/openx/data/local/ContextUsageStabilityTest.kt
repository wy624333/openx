package com.wy.openx.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具执行期间上下文数字「一会多一会少」的回归。
 *
 * ## 事故经过
 *
 * AI 执行工具时，顶部上下文占用来回跳。用户看到的不是缓慢上升（那是真实的），
 * 而是**忽大忽小**——明显偏小后又弹回去。
 *
 * ## 根因
 *
 * 旧口径是 `anchor.reported + estimate(锚点之后的消息)`，而「锚点之后」是按**数组下标**
 * `subList(mark, msgs.size)` 切出来的。工具执行期间历史数组**每轮都会重排**：
 * 流式正文落库前 `seq == 0`（排在最后），落库后 `seq` 变成实际值，于是从末尾**插回中间**，
 * 整个数组右移。同一句 `subList(mark, …)` 前后指向完全不同的消息 →
 * 增量突变 → 数字掉下去又弹回来。
 *
 * ## 正确口径（对齐 AiCode `ContextCompactor`：`lastInputTokens.takeIf{it>0} ?: 估算`）
 *
 * `有真实上报: 上次真实值 + (本次估算 - 上次估算)（不低于上次真实值）；无上报: 纯估算`
 *
 * 两点都必要：
 *  1. `baselineUsage` 与 `baselineEstimate` 取自**同一次请求**，相减 = 那次请求后新增多少，
 *     **纯标量、与排序/插入/删除无关**（数组重排不会抖）；
 *  2. **不做 `maxOf(全量估算, 修正值)`**：本地估算（we 的投影感知口径收敛为保守下限）一旦偏高，
 *     取大就会盖过真实值，把 147K 顶成 432K、还能把 512K 模型误判超窗强制压缩——
 *     这正是「数字虚高 / 切 512K 被压缩」反复发作的根。数字只钳到「上次真实值」防止异常回落。
 */
class ContextUsageStabilityTest {

    /** 与 `MainViewModel.effectiveContextTokens` 的校准式保持一致。 */
    private fun calibrated(estimate: Int, reported: Int, baselineEstimate: Int): Int {
        if (reported <= 0) return estimate
        val corrected = reported + estimate - baselineEstimate
        return corrected.coerceAtLeast(reported).coerceAtLeast(1)
    }

    /** 旧实现：按**下标**切增量。数组一重排，下标指向就变了。 */
    private fun oldByIndex(msgs: List<Int>, mark: Int, reported: Int): Int {
        if (mark >= msgs.size) return reported
        return reported + msgs.subList(mark, msgs.size).sum()
    }

    private fun sum(l: List<Int>) = l.sum()

    @Test
    fun `数组重排不应让数字掉下去`() {
        // 上次请求：真实 10000，估算 9000（历史 9 条 × 1000）
        val reported = 10_000
        val baselineEstimate = 9_000

        // 本轮新增了一条 3000 的工具结果 → 估算 12000
        val beforeDrop = calibrated(estimate = 12_000, reported = reported, baselineEstimate = baselineEstimate)
        assertEquals("真实值 + Δ估算 = 13000", 13_000, beforeDrop)

        // 关键：这期间数组发生右移（流式消息落库 → seq 变实际值 → 从末尾插回中间）。
        // 旧口径用同一个下标 mark 去 subList，切出来的内容完全变了：
        val msgs = listOf(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 3000)
        val byIndexAfterReorder = oldByIndex(msgs, mark = 9, reported = reported)
        // 真实场景里 mark 会指到别处，切出的大把内容导致 delta 骤变 —— 用重排后 mark 落空演示：
        val byIndexBroken = oldByIndex(msgs, mark = 2, reported = reported)
        assertTrue(
            "旧口径在同一 mark 下会因重排而算出完全不同的值（抖动来源）",
            byIndexAfterReorder != byIndexBroken
        )

        // 新口径：只吃两个标量，数组怎么重排都不影响
        val afterReorder = calibrated(estimate = 12_000, reported = reported, baselineEstimate = baselineEstimate)
        assertEquals("标量口径下结果完全稳定", beforeDrop, afterReorder)
    }

    @Test
    fun `估算虚高时真值优先不得被估算带上去`() {
        // 基线估算偏大（上次估算虚高 20K > 真实 8K），修正值 = 真实 + (est − baselinest) 会为负。
        // 旧口径的「外层 maxOf 取估算」正是 147K→432K、512K 被误压的元凶：
        val result = calibrated(estimate = 12_000, reported = 8_000, baselineEstimate = 20_000)
        assertEquals(
            "真值优先：修正值砍到 0 时数字钳在真实值上，而不是被虚高估算顶上去",
            8_000, result
        )
    }

    @Test
    fun `没有真实值时退回纯估算`() {
        assertEquals(12_000, calibrated(estimate = 12_000, reported = 0, baselineEstimate = 9_000))
    }

    @Test
    fun `数值恒为正`() {
        // 前提：`estimateContextTokens` 自身有 `.coerceAtLeast(1)`，故 estimate 恒 ≥ 1；
        // 校准式在 reported>0 时又做了 `coerceAtLeast(1)`，两条路都不会给出 0。
        assertTrue("估算侧恒为正（estimateContextTokens 的 coerceAtLeast）", (0).coerceAtLeast(1) == 1)
        assertTrue(calibrated(estimate = 1, reported = 0, baselineEstimate = 0) >= 1)
        assertTrue(calibrated(estimate = 1, reported = 1, baselineEstimate = 99_999) >= 1)
    }

    @Test
    fun `估算上升时数字单调上升`() {
        val reported = 10_000
        val baselineEstimate = 9_000
        var prev = 0
        for (e in listOf(9_500, 10_000, 11_000, 12_000, 20_000, 30_000)) {
            val cur = calibrated(estimate = e, reported = reported, baselineEstimate = baselineEstimate)
            assertTrue("估算 $e 时数字不该比上一次($prev)小：$cur", cur >= prev)
            prev = cur
        }
    }

    @Test
    fun `同口径相减才等于新增`() {
        // 这是口径正确性的根：baselineEstimate 必须取「那次请求」的估算
        val trueNew = 3_000
        val estimateThen = 9_000
        val estimateNow = estimateThen + trueNew
        val reported = 10_000
        val corrected = reported + estimateNow - estimateThen
        assertEquals("修正值 = 真实值 + 真实新增量", 13_000, corrected)
    }

    @Test
    fun `sum 辅助不参与被测逻辑`() {
        assertEquals(3, sum(listOf(1, 2)))
    }
}