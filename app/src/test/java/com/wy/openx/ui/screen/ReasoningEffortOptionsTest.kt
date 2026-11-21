package com.wy.openx.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考强度选项的构造：模型能力过滤 + 去除自造值 `"none"`。
 *
 * 回归重点：
 * - 不含 `"none"`（网关不认，发了等于没发；关闭思考走标准 `minimal`）；
 * - 元数据缺失（null）→ 显示全量，不能空列表；
 * - 模型只支持子集时只显示子集；
 * - 一个都不匹配时仍保留「模型默认」。
 */
class ReasoningEffortOptionsTest {

    private fun values(modelSupported: List<String>?) =
        buildEffortOptions(modelSupported).map { it.first }

    // ---------- 全集 ----------

    @Test
    fun `全集不含自造的 none`() {
        assertFalse(ALL_REASONING_EFFORTS.any { it.first == "none" })
    }

    @Test
    fun `全集包含规范档位`() {
        val values = ALL_REASONING_EFFORTS.map { it.first }
        assertTrue(values.containsAll(listOf("minimal", "low", "medium", "high", "xhigh", "max")))
    }

    @Test
    fun `全集第一项是模型默认`() {
        assertEquals("default", values(null).first())
    }

    @Test
    fun `结果里永远没有 none`() {
        assertFalse(values(null).contains("none"))
        assertFalse(values(listOf("minimal", "none")).contains("none"))
    }

    // ---------- 元数据缺失 → 全量 ----------

    @Test
    fun `元数据缺失显示全量`() {
        assertEquals(listOf("default") + ALL_REASONING_EFFORTS.map { it.first }, values(null))
    }

    // ---------- 按能力过滤 ----------

    @Test
    fun `只显示模型支持的档位`() {
        // OpenAI o-series 常见支持集
        assertEquals(listOf("default", "low", "medium", "high"), values(listOf("low", "medium", "high")))
    }

    @Test
    fun `过滤时大小写不敏感`() {
        assertEquals(listOf("default", "high"), values(listOf("HIGH")))
    }

    @Test
    fun `元数据含未知档位时忽略`() {
        // 网关给了个我们不认识的档位，不应出现在选项里
        assertEquals(listOf("default", "low"), values(listOf("low", "ultra")))
    }

    // ---------- 边界 ----------

    @Test
    fun `一个都不匹配时仍保留模型默认`() {
        // 模型元数据存在但没有任何我们收录的档位 → 不能给空列表
        assertEquals(listOf("default"), values(listOf("whatever")))
    }

    @Test
    fun `空列表等同不支持`() {
        assertEquals(listOf("default"), values(emptyList()))
    }

    @Test
    fun `标签与值一一对应且顺序稳定`() {
        val rows = buildEffortOptions(listOf("high", "low", "medium"))
        // 顺序按全集顺序（low 在 medium 前、medium 在 high 前），而不是输入顺序
        assertEquals(listOf("default", "low", "medium", "high"), rows.map { it.first })
        assertTrue(rows.all { it.second.isNotBlank() })
    }
}