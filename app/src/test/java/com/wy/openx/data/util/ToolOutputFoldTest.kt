package com.wy.openx.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具卡「指令/结果」折叠规则的回归测试。
 *
 * 背景：改工具卡前，展开后是把工具输出**全量**渲染的。terminal 跑一次 build
 * 几百行日志就能把整屏顶掉，用户想看自己的下一条消息得划很久。
 * 现在按参考项目口径：超 20 行折叠、留尾部。
 */
class ToolOutputFoldTest {

    private fun lines(n: Int) = (1..n).joinToString("\n") { "line$it" }

    @Test
    fun `空文本不折叠`() {
        val r = ToolOutputFold.fold("", expanded = false)
        assertEquals("", r.visible)
        assertEquals(0, r.hiddenCount)
        assertFalse(r.collapsible)
    }

    @Test
    fun `不超过上限原样显示`() {
        val r = ToolOutputFold.fold(lines(10), expanded = false)
        assertEquals(lines(10), r.visible)
        assertEquals(0, r.hiddenCount)
        assertFalse("10 行整好不触发折叠", r.collapsible)
    }

    @Test
    fun `阈值就是 10 行`() {
        assertEquals(10, ToolOutputFold.LINE_LIMIT)
        assertFalse(ToolOutputFold.fold(lines(9), expanded = false).collapsible)
        assertFalse(ToolOutputFold.fold(lines(10), expanded = false).collapsible)
        assertTrue(ToolOutputFold.fold(lines(11), expanded = false).collapsible)
    }

    @Test
    fun `超上限折叠时留尾部`() {
        val r = ToolOutputFold.fold(lines(25), expanded = false)
        assertTrue(r.collapsible)
        assertEquals(15, r.hiddenCount)
        // 留尾：最后一行必须是 line25，最早被折掉的是 line1..line15
        assertTrue(r.visible.endsWith("line25"))
        assertFalse(r.visible.contains("line1\n"))
        assertEquals(10, r.visible.split("\n").size)
    }

    @Test
    fun `展开后给全文且收起切换条仍在`() {
        val r = ToolOutputFold.fold(lines(25), expanded = true)
        assertEquals(lines(25), r.visible)
        assertEquals(15, r.hiddenCount)
        assertTrue("展开后仍需显示「收起」", r.collapsible)
    }

    @Test
    fun `大刷屏也只留 10 行`() {
        // terminal 一次 build 的典型量级
        val r = ToolOutputFold.fold(lines(2000), expanded = false)
        assertEquals(10, r.visible.split("\n").size)
        assertEquals(1990, r.hiddenCount)
        assertTrue(r.visible.endsWith("line2000"))
    }

    @Test
    fun `单行超长不触发折叠`() {
        // 无换行的长 JSON 串：只有 1 行，提示「展开剩余 N 行」是骗人的
        val r = ToolOutputFold.fold("x".repeat(50_000), expanded = false)
        assertEquals(50_000, r.visible.length)
        assertFalse(r.collapsible)
    }

    @Test
    fun `折叠前后切换是幂等的`() {
        val text = lines(37)
        assertEquals(text, ToolOutputFold.fold(text, expanded = true).visible)
        val collapsed = ToolOutputFold.fold(text, expanded = false).visible
        assertEquals(ToolOutputFold.fold(collapsed, expanded = false).visible, collapsed)
    }

    @Test
    fun `末尾有空行时留尾仍保真`() {
        val text = lines(30) + "\n\n"
        val r = ToolOutputFold.fold(text, expanded = false)
        assertEquals(10, r.visible.split("\n").size)
        assertEquals(22, r.hiddenCount)
    }
    @Test
    fun `字符截断留尾部`() {
        // TerminalTool 的 maxOutput 截断：旧实现 take() 留头，把结论丢了
        val out = (1..5000).joinToString("\n") { "log$it" }
        val tailed = ToolOutputFold.keepTail(out, 2000)
        assertTrue(tailed != null)
        assertEquals(2000, tailed!!.length)
        assertTrue("必须保住最后一行", tailed.endsWith("log5000"))
        assertFalse("不能再保住最早的日志", tailed.contains("log1\n"))
    }

    @Test
    fun `未超限不截断`() {
        assertNull(ToolOutputFold.keepTail("short", 2000))
        assertNull(ToolOutputFold.keepTail("exact", 5))
        assertNull(ToolOutputFold.keepTail("anything", 0))
    }
}
