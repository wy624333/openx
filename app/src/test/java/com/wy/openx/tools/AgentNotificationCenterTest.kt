package com.wy.openx.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台任务完成通知的入队 / 取出 / 确认送达，以及渲染文本。
 *
 * 这些是纯内存逻辑（无 Android 依赖），故可在 JVM 单测里直接覆盖。
 */
class AgentNotificationCenterTest {

    @After
    fun tearDown() {
        AgentNotificationCenter.clearAll()
    }

    private fun n(
        cid: Long = 7L,
        tab: String = "term-1",
        exit: Int = 0,
        tail: String? = null,
        cmd: String? = "npm run build"
    ) = AgentNotification(
        conversationId = cid, tabId = tab, title = tab,
        command = cmd, exitCode = exit, tailOutput = tail
    )

    // ---------- 基本入队/取出 ----------

    @Test
    fun `入队后按会话隔离取出`() {
        AgentNotificationCenter.enqueue(n(cid = 1, tab = "a"))
        AgentNotificationCenter.enqueue(n(cid = 2, tab = "b"))
        assertEquals(1, AgentNotificationCenter.pendingCount(1))
        assertEquals(1, AgentNotificationCenter.pendingCount(2))
        assertEquals(1, AgentNotificationCenter.drain(1).size)
        assertEquals(0, AgentNotificationCenter.pendingCount(1))
        assertEquals(1, AgentNotificationCenter.pendingCount(2))
    }

    @Test
    fun `drain 会取空队列`() {
        assertEquals(0, AgentNotificationCenter.drain(99).size)
        assertEquals(0, AgentNotificationCenter.pendingCount(99))
    }

    // ---------- 去重 ----------

    @Test
    fun `同一 tab 重复通知只保留最新一条`() {
        AgentNotificationCenter.enqueue(n(tab = "term-1", exit = 1))
        AgentNotificationCenter.enqueue(n(tab = "term-1", exit = 0))
        val items = AgentNotificationCenter.peek(7)
        assertEquals(1, items.size)
        assertEquals(0, items.first().exitCode)
    }

    @Test
    fun `不同 tab 各保留一条`() {
        AgentNotificationCenter.enqueue(n(tab = "term-1"))
        AgentNotificationCenter.enqueue(n(tab = "term-2"))
        assertEquals(2, AgentNotificationCenter.pendingCount(7))
    }

    // ---------- ack 精确确认 ----------

    @Test
    fun `ack 只移除指定序号的条目`() {
        val s1 = AgentNotificationCenter.enqueue(n(tab = "a"))
        val s2 = AgentNotificationCenter.enqueue(n(tab = "b"))
        assertTrue(s1 > 0 && s2 > s1)
        AgentNotificationCenter.ack(7, listOf(s1))
        val left = AgentNotificationCenter.peek(7)
        assertEquals(1, left.size)
        assertEquals("b", left.first().tabId)
    }

    @Test
    fun `ack 后队列空则移除会话键`() {
        val s = AgentNotificationCenter.enqueue(n(tab = "a"))
        AgentNotificationCenter.ack(7, listOf(s))
        assertEquals(0, AgentNotificationCenter.pendingCount(7))
        // 空队列下再次 ack 不报错
        AgentNotificationCenter.ack(7, listOf(s))
        assertEquals(0, AgentNotificationCenter.pendingCount(7))
    }

    @Test
    fun `序号单调递增`() {
        val s1 = AgentNotificationCenter.enqueue(n(tab = "a"))
        val s2 = AgentNotificationCenter.enqueue(n(tab = "b"))
        val s3 = AgentNotificationCenter.enqueue(n(tab = "c"))
        assertTrue(s1 < s2 && s2 < s3)
    }

    // ---------- clear ----------

    @Test
    fun `clear 只清指定会话`() {
        AgentNotificationCenter.enqueue(n(cid = 1, tab = "a"))
        AgentNotificationCenter.enqueue(n(cid = 2, tab = "b"))
        AgentNotificationCenter.clear(1)
        assertEquals(0, AgentNotificationCenter.pendingCount(1))
        assertEquals(1, AgentNotificationCenter.pendingCount(2))
    }

    @Test
    fun `clearAll 清空全部`() {
        AgentNotificationCenter.enqueue(n(cid = 1, tab = "a"))
        AgentNotificationCenter.enqueue(n(cid = 2, tab = "b"))
        AgentNotificationCenter.clearAll()
        assertEquals(0, AgentNotificationCenter.pendingCount(1))
        assertEquals(0, AgentNotificationCenter.pendingCount(2))
    }

    // ---------- enqueueAll（送达失败退回） ----------

    @Test
    fun `enqueueAll 可把通知退回队列`() {
        val items = AgentNotificationCenter.drain(7).let { listOf(n(tab = "a"), n(tab = "b")) }
        assertEquals(0, AgentNotificationCenter.pendingCount(7))
        AgentNotificationCenter.enqueueAll(7, items)
        assertEquals(2, AgentNotificationCenter.pendingCount(7))
    }

    // ---------- 渲染 ----------

    @Test
    fun `空列表渲染为空串`() {
        assertEquals("", AgentNotificationCenter.render(emptyList()))
    }

    @Test
    fun `渲染包含标签 退出码 与命令`() {
        val text = AgentNotificationCenter.render(listOf(n(tab = "term-9", exit = 3, cmd = "gradle build")))
        assertTrue(text.contains("term-9"))
        assertTrue(text.contains("退出码 3"))
        assertTrue(text.contains("gradle build"))
    }

    @Test
    fun `渲染明确声明不是用户新消息`() {
        val text = AgentNotificationCenter.render(listOf(n()))
        // AI 需要知道这是系统事件，否则会当成用户发言去回应
        assertTrue(text.contains("后台任务完成通知"))
        assertTrue(text.contains("不是用户"))
    }

    @Test
    fun `非零退出码措辞为中性不判为出错`() {
        val ok = AgentNotificationCenter.render(listOf(n(exit = 0)))
        val bad = AgentNotificationCenter.render(listOf(n(exit = 1)))
        assertTrue(ok.contains("正常完成"))
        // 非 0 可能只是用户 Ctrl-C，措辞必须中性
        assertTrue(bad.contains("非 0"))
        assertFalse(bad.contains("出错"))
    }

    @Test
    fun `渲染包含末尾输出`() {
        val text = AgentNotificationCenter.render(listOf(n(tail = "BUILD SUCCESSFUL")))
        assertTrue(text.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `渲染提示可用 terminal 取完整输出`() {
        val text = AgentNotificationCenter.render(listOf(n()))
        assertTrue(text.contains("get_output"))
    }
}