package com.wy.openx.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 插件间消息总线。
 *
 * 重点覆盖「订阅才能拉」这条隔离规则，以及增量游标与容量上限 ——
 * 这三处错了会让插件收到别人的消息、或漏消息、或把内存撑爆。
 */
class PluginBusTest {

    @Before
    fun setUp() = PluginBus.clear()

    @After
    fun tearDown() = PluginBus.clear()

    @Test
    fun `发布返回递增序号`() {
        val a = PluginBus.publish("t", "p1", "one")
        val b = PluginBus.publish("t", "p1", "two")
        assertEquals(1L, a)
        assertEquals(2L, b)
    }

    @Test
    fun `不同 topic 各自独立计数`() {
        assertEquals(1L, PluginBus.publish("a", "p1", "x"))
        assertEquals(1L, PluginBus.publish("b", "p1", "y"))
        assertEquals(2L, PluginBus.publish("a", "p1", "z"))
    }

    @Test
    fun `未订阅的插件拉不到消息`() {
        PluginBus.publish("secret", "p1", "data")
        // p2 没订阅，即使知道 topic 也拉不到
        assertTrue(PluginBus.poll("secret", "p2", 0L).isEmpty())
    }

    @Test
    fun `订阅后能拉到消息`() {
        PluginBus.publish("news", "p1", "hello")
        PluginBus.subscribe("news", "p2")

        val msgs = PluginBus.poll("news", "p2", 0L)
        assertEquals(1, msgs.size)
        assertEquals("hello", msgs[0].payload)
        assertEquals("p1", msgs[0].from)
    }

    @Test
    fun `sinceSeq 只返回增量`() {
        PluginBus.subscribe("t", "p2")
        PluginBus.publish("t", "p1", "a")
        PluginBus.publish("t", "p1", "b")
        PluginBus.publish("t", "p1", "c")

        val msgs = PluginBus.poll("t", "p2", sinceSeq = 1L)
        assertEquals(listOf("b", "c"), msgs.map { it.payload })
    }

    @Test
    fun `退订后拉不到`() {
        PluginBus.subscribe("t", "p2")
        PluginBus.publish("t", "p1", "x")
        PluginBus.unsubscribe("t", "p2")
        assertTrue(PluginBus.poll("t", "p2", 0L).isEmpty())
    }

    @Test
    fun `unsubscribeAll 清掉该插件的全部订阅`() {
        PluginBus.subscribe("a", "p2")
        PluginBus.subscribe("b", "p2")
        PluginBus.publish("a", "p1", "x")

        PluginBus.unsubscribeAll("p2")

        assertTrue(PluginBus.poll("a", "p2", 0L).isEmpty())
        assertTrue(PluginBus.subscribedTopics("p2").isEmpty())
    }

    @Test
    fun `latestSeq 反映当前最大序号`() {
        assertEquals(0L, PluginBus.latestSeq("t"))
        PluginBus.publish("t", "p1", "x")
        PluginBus.publish("t", "p1", "y")
        assertEquals(2L, PluginBus.latestSeq("t"))
    }

    @Test
    fun `limit 生效`() {
        PluginBus.subscribe("t", "p2")
        repeat(10) { PluginBus.publish("t", "p1", "m$it") }
        assertEquals(3, PluginBus.poll("t", "p2", 0L, limit = 3).size)
    }

    @Test
    fun `超出容量时丢最旧的`() {
        PluginBus.subscribe("t", "p2")
        // MAX_PER_TOPIC = 100，发 105 条
        repeat(105) { PluginBus.publish("t", "p1", "m$it") }

        val msgs = PluginBus.poll("t", "p2", 0L, limit = 200)
        assertEquals(100, msgs.size)
        // 最早的 5 条被丢了，第一条应是 m5
        assertEquals("m5", msgs.first().payload)
        assertEquals("m104", msgs.last().payload)
    }

    @Test
    fun `空 topic 被忽略`() {
        assertEquals(0L, PluginBus.publish("", "p1", "x"))
        assertEquals(0L, PluginBus.publish("   ", "p1", "x"))
    }

    @Test
    fun `subscribedTopics 列出该插件订阅的 topic`() {
        PluginBus.subscribe("b", "p1")
        PluginBus.subscribe("a", "p1")
        PluginBus.subscribe("c", "p2")

        assertEquals(listOf("a", "b"), PluginBus.subscribedTopics("p1"))
    }

    @Test
    fun `多个插件可订阅同一 topic 且互不影响`() {
        PluginBus.subscribe("t", "p1")
        PluginBus.subscribe("t", "p2")
        PluginBus.publish("t", "src", "shared")

        assertEquals(1, PluginBus.poll("t", "p1", 0L).size)
        assertEquals(1, PluginBus.poll("t", "p2", 0L).size)
        // p1 拉到不影响 p2 再拉
        assertEquals(1, PluginBus.poll("t", "p2", 0L).size)
    }
}
