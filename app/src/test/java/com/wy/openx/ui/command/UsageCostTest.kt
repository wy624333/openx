package com.wy.openx.ui.command

import com.wy.openx.data.model.ModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UsageCostTest {

    private fun meta(
        id: String = "m",
        input: Double? = null,
        output: Double? = null,
        cacheRead: Double? = null,
        cacheWrite: Double? = null
    ) = ModelMetadata(
        id = id,
        contextTokens = 128_000,
        inputCostUsdPerM = input,
        outputCostUsdPerM = output,
        cacheReadCostUsdPerM = cacheRead,
        cacheWriteCostUsdPerM = cacheWrite
    )

    @Test
    fun `四项单价都缺失时返回 null 以便回退内置价格表`() {
        assertNull(usageCostUsd(1000, 1000, 0, 0, meta()))
        assertNull(usageCostUsd(1000, 1000, 0, 0, null))
    }

    @Test
    fun `按各自单价分别计费`() {
        val m = meta(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75)
        val cost = usageCostUsd(
            inputTokens = 1_000_000,
            outputTokens = 1_000_000,
            cachedInputTokens = 1_000_000,
            cacheCreationTokens = 1_000_000,
            metadata = m
        )
        assertEquals(3.0 + 15.0 + 0.3 + 3.75, cost!!, 1e-9)
    }

    @Test
    fun `缺失的单项按零计而不是整体回退`() {
        val m = meta(input = 2.0)
        assertEquals(0.002, usageCostUsd(1000, 5000, 5000, 5000, m)!!, 1e-12)
    }

    @Test
    fun `零 token 计费为零`() {
        assertEquals(0.0, usageCostUsd(0, 0, 0, 0, meta(input = 10.0))!!, 0.0)
    }

    @Test
    fun `自定义元数据优先于自动解析元数据`() {
        val merged = metadataForUsage(
            "gpt-4o",
            meta(id = "auto", input = 2.5, output = 10.0),
            meta(id = "custom", input = 1.0, output = 4.0)
        )
        assertNotNull(merged)
        assertEquals(1.0, merged!!.inputCostUsdPerM!!, 0.0)
        assertEquals(4.0, merged.outputCostUsdPerM!!, 0.0)
    }

    @Test
    fun `自定义未填的单价沿用自动解析值`() {
        val merged = metadataForUsage(
            "gpt-4o",
            meta(id = "auto", input = 2.5, output = 10.0),
            meta(id = "custom", input = 1.0)
        )!!
        assertEquals(1.0, merged.inputCostUsdPerM!!, 0.0)
        assertEquals(10.0, merged.outputCostUsdPerM!!, 0.0)
    }

    @Test
    fun `两者都缺时元数据为 null`() {
        assertNull(metadataForUsage("m", null, null))
    }
}
