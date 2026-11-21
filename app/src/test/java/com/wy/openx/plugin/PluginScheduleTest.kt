package com.wy.openx.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 定时声明解析。
 *
 * 这里最容易出错的是单位换算（把 `2h` 当成 2 分钟之类）和下限保护，
 * 所以每个单位都单独钉一个用例。
 */
class PluginScheduleTest {

    @Test
    fun `every 支持秒`() {
        assertEquals(30_000L, PluginSchedule(id = "t", every = "30s").intervalMs())
    }

    @Test
    fun `every 支持分钟`() {
        assertEquals(600_000L, PluginSchedule(id = "t", every = "10m").intervalMs())
    }

    @Test
    fun `every 支持小时`() {
        assertEquals(7_200_000L, PluginSchedule(id = "t", every = "2h").intervalMs())
    }

    @Test
    fun `every 纯数字按毫秒`() {
        assertEquals(45_000L, PluginSchedule(id = "t", every = "45000").intervalMs())
    }

    @Test
    fun `every 显式 ms 后缀`() {
        assertEquals(20_000L, PluginSchedule(id = "t", every = "20000ms").intervalMs())
    }

    @Test
    fun `every 小于 10 秒被抬到下限`() {
        // 防止插件写 every=1s 把 QuickJS 单线程刷爆
        assertEquals(10_000L, PluginSchedule(id = "t", every = "1s").intervalMs())
        assertEquals(10_000L, PluginSchedule(id = "t", every = "500").intervalMs())
    }

    @Test
    fun `every 非法或为空返回 null`() {
        assertNull(PluginSchedule(id = "t", every = "").intervalMs())
        assertNull(PluginSchedule(id = "t", every = "abc").intervalMs())
        assertNull(PluginSchedule(id = "t", every = "-5m").intervalMs())
        assertNull(PluginSchedule(id = "t", every = "5x").intervalMs())
    }

    @Test
    fun `at 解析为当天第几分钟`() {
        assertEquals(0, PluginSchedule(id = "t", at = "00:00").minuteOfDay())
        assertEquals(8 * 60, PluginSchedule(id = "t", at = "08:00").minuteOfDay())
        assertEquals(23 * 60 + 59, PluginSchedule(id = "t", at = "23:59").minuteOfDay())
        assertEquals(7 * 60 + 5, PluginSchedule(id = "t", at = "7:05").minuteOfDay())
    }

    @Test
    fun `at 非法值返回 null`() {
        assertNull(PluginSchedule(id = "t", at = "").minuteOfDay())
        assertNull(PluginSchedule(id = "t", at = "24:00").minuteOfDay())
        assertNull(PluginSchedule(id = "t", at = "08:60").minuteOfDay())
        assertNull(PluginSchedule(id = "t", at = "8点").minuteOfDay())
    }

    @Test
    fun `isValid 只要有一个合法时间表达式就为真`() {
        assertTrue(PluginSchedule(id = "t", every = "10m").isValid())
        assertTrue(PluginSchedule(id = "t", at = "09:30").isValid())
        assertTrue(PluginSchedule(id = "t", every = "1h", at = "09:30").isValid())
    }

    @Test
    fun `isValid 两个都非法时为假`() {
        assertTrue(!PluginSchedule(id = "t").isValid())
        assertTrue(!PluginSchedule(id = "t", every = "x", at = "y").isValid())
    }
}
