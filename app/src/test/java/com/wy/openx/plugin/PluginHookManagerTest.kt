package com.wy.openx.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PluginHookManager] 的注册表行为测试。
 *
 * 这里是进程级单例，每个用例前后都要清空，否则用例间会互相污染。
 * 重点覆盖「未知钩子名被丢弃」——它保证插件写错钩子名不会静默进入调度表，
 * 也保证以后新增钩子时旧插件不受影响。
 */
class PluginHookManagerTest {

    @Before
    fun setUp() = PluginHookManager.clear()

    @After
    fun tearDown() = PluginHookManager.clear()

    @Test
    fun `注册已知钩子后 hasAny 为真`() {
        PluginHookManager.register("p1", "pkg1", listOf(PluginHookManager.ON_APP_START))

        assertTrue(PluginHookManager.hasAny(PluginHookManager.ON_APP_START))
        assertFalse(PluginHookManager.hasAny(PluginHookManager.ON_BEFORE_SEND))
    }

    @Test
    fun `未知钩子名被丢弃`() {
        PluginHookManager.register("p1", "pkg1", listOf("onSomethingNotExist"))

        PluginHookManager.ALL_HOOKS.forEach {
            assertFalse("不该注册 $it", PluginHookManager.hasAny(it))
        }
    }

    @Test
    fun `已知与未知混在一起时只保留已知的`() {
        PluginHookManager.register(
            "p1", "pkg1",
            listOf(PluginHookManager.ON_BEFORE_SEND, "bogus", PluginHookManager.ON_AFTER_TOOL)
        )

        assertTrue(PluginHookManager.hasAny(PluginHookManager.ON_BEFORE_SEND))
        assertTrue(PluginHookManager.hasAny(PluginHookManager.ON_AFTER_TOOL))
        assertEquals(2, PluginHookManager.snapshot().values.sumOf { it.size })
    }

    @Test
    fun `重复注册同一插件不会累积`() {
        PluginHookManager.register("p1", "pkg1", listOf(PluginHookManager.ON_APP_START))
        PluginHookManager.register("p1", "pkg1", listOf(PluginHookManager.ON_APP_START))

        assertEquals(1, PluginHookManager.snapshot()[PluginHookManager.ON_APP_START]?.size)
    }

    @Test
    fun `重新注册会替换该插件的钩子集合`() {
        PluginHookManager.register("p1", "pkg1", listOf(PluginHookManager.ON_APP_START))
        PluginHookManager.register("p1", "pkg1", listOf(PluginHookManager.ON_AFTER_REPLY))

        assertFalse(PluginHookManager.hasAny(PluginHookManager.ON_APP_START))
        assertTrue(PluginHookManager.hasAny(PluginHookManager.ON_AFTER_REPLY))
    }

    @Test
    fun `多个插件注册同一钩子都会出现在快照里`() {
        PluginHookManager.register("p1", "pkg1", listOf(PluginHookManager.ON_BEFORE_TOOL))
        PluginHookManager.register("p2", "pkg2", listOf(PluginHookManager.ON_BEFORE_TOOL))

        val owners = PluginHookManager.snapshot()[PluginHookManager.ON_BEFORE_TOOL].orEmpty()
        assertEquals(setOf("p1", "p2"), owners.toSet())
    }

    @Test
    fun `unregister 只移除指定插件`() {
        PluginHookManager.register("p1", "pkg1", listOf(PluginHookManager.ON_APP_START))
        PluginHookManager.register("p2", "pkg2", listOf(PluginHookManager.ON_APP_START))

        PluginHookManager.unregister("p1")

        assertEquals(listOf("p2"), PluginHookManager.snapshot()[PluginHookManager.ON_APP_START])
    }

    @Test
    fun `没有注册者时 beforeSend 原样返回`() {
        assertEquals("原文", PluginHookManager.beforeSend("原文"))
    }

    @Test
    fun `没有注册者时 beforeTool 放行`() {
        assertEquals(null, PluginHookManager.beforeTool("read_file", "{}"))
    }
}
