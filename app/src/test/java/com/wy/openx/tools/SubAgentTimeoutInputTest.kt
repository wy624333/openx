package com.wy.openx.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「单任务超时」手动输入解析规则。
 *
 * 背景：该项原本只有一条 30..7200 秒的滑块，拖到「7 分钟」不现实，
 * 所以把数值标签改成可点开手输。规则见 [SubAgentTimeoutInput]。
 */
class SubAgentTimeoutInputTest {

    // ---------- 裸数字：按当前显示单位解释 ----------

    @Test
    fun `当前是分钟时裸数字按分钟算`() {
        // 当前 5 分钟 = 300 秒，填 10 → 600 秒
        assertEquals(600, SubAgentTimeoutInput.parse("10", 300))
    }

    @Test
    fun `当前是秒时裸数字按秒算`() {
        // 当前 45 秒（秒档），填 90 → 90 秒；若误按分钟解释会变成 5400 并被夹到 5400（同样超出秒档本意）
        assertEquals(90, SubAgentTimeoutInput.parse("90", 45))
    }

    @Test
    fun `当前是混合显示时按分钟解释`() {
        // 5 分 30 秒 = 330 秒，unitFor → MINUTES，填 7 → 420
        assertEquals(420, SubAgentTimeoutInput.parse("7", 330))
    }

    @Test
    fun `分钟可以填小数`() {
        assertEquals(90, SubAgentTimeoutInput.parse("1.5", 300))
    }

    // ---------- 带单位后缀：后缀说了算 ----------

    @Test
    fun `秒后缀`() {
        assertEquals(90, SubAgentTimeoutInput.parse("90s", 300))
        assertEquals(90, SubAgentTimeoutInput.parse("90秒", 300))
    }

    @Test
    fun `分钟后缀`() {
        assertEquals(120, SubAgentTimeoutInput.parse("2m", 300))
        assertEquals(120, SubAgentTimeoutInput.parse("2min", 300))
        assertEquals(120, SubAgentTimeoutInput.parse("2分钟", 300))
    }

    @Test
    fun `小时后缀`() {
        assertEquals(3600, SubAgentTimeoutInput.parse("1h", 300))
        assertEquals(7200, SubAgentTimeoutInput.parse("2小时", 300))
    }

    @Test
    fun `后缀优先于当前单位`() {
        // 当前是分钟，填 90s 仍应是 90 秒而不是 90 分钟
        assertEquals(90, SubAgentTimeoutInput.parse("90s", 300))
    }

    @Test
    fun `数字与单位之间有空格也能认`() {
        assertEquals(120, SubAgentTimeoutInput.parse("2 m", 300))
        assertEquals(120, SubAgentTimeoutInput.parse("2 分", 300))
    }

    @Test
    fun `全角空格也能认`() {
        assertEquals(120, SubAgentTimeoutInput.parse("2　m", 300))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(120, SubAgentTimeoutInput.parse("2M", 300))
        assertEquals(90, SubAgentTimeoutInput.parse("90SEC", 300))
    }

    // ---------- 越界夹取，不报错 ----------

    @Test
    fun `小于下限夹到下限`() {
        // 秒档填 5 → 5 秒，低于 30 秒下限，夹到 30
        assertEquals(30, SubAgentTimeoutInput.parse("5", 45))
        // 分钟档填 0.1 → 6 秒，同样夹到 30
        assertEquals(30, SubAgentTimeoutInput.parse("0.1", 300))
    }

    @Test
    fun `超过上限夹到上限`() {
        assertEquals(7200, SubAgentTimeoutInput.parse("200", 300))
        assertEquals(7200, SubAgentTimeoutInput.parse("999", 300))
    }

    // ---------- 非法输入：返回 null，不改值 ----------

    @Test
    fun `空串与纯空格非法`() {
        assertNull(SubAgentTimeoutInput.parse("", 300))
        assertNull(SubAgentTimeoutInput.parse("   ", 300))
    }

    @Test
    fun `零非法`() {
        // 超时 0 秒没意义，dispatcher 也会夹回下限，用户会以为没生效
        assertNull(SubAgentTimeoutInput.parse("0", 300))
        assertNull(SubAgentTimeoutInput.parse("0m", 300))
    }

    @Test
    fun `纯符号非法`() {
        assertNull(SubAgentTimeoutInput.parse("abc", 300))
        assertNull(SubAgentTimeoutInput.parse("分钟", 300))
        assertNull(SubAgentTimeoutInput.parse("-5", 300))
    }

    @Test
    fun `多个小数点非法`() {
        assertNull(SubAgentTimeoutInput.parse("1.2.3", 300))
    }

    @Test
    fun `未知单位非法`() {
        assertNull(SubAgentTimeoutInput.parse("5x", 300))
        assertNull(SubAgentTimeoutInput.parse("5天", 300))
    }

    // ---------- 单位与初值 ----------

    @Test
    fun `unitFor 按 60 秒分界`() {
        assertEquals(SubAgentTimeoutInput.Unit.SECONDS, SubAgentTimeoutInput.unitFor(30))
        assertEquals(SubAgentTimeoutInput.Unit.SECONDS, SubAgentTimeoutInput.unitFor(59))
        assertEquals(SubAgentTimeoutInput.Unit.MINUTES, SubAgentTimeoutInput.unitFor(60))
        assertEquals(SubAgentTimeoutInput.Unit.MINUTES, SubAgentTimeoutInput.unitFor(300))
    }

    @Test
    fun `draftFor 整分显示整数`() {
        assertEquals("5", SubAgentTimeoutInput.draftFor(300))
    }

    @Test
    fun `draftFor 非整分显示小数`() {
        assertEquals("1.5", SubAgentTimeoutInput.draftFor(90))
    }

    @Test
    fun `draftFor 秒档原样`() {
        assertEquals("45", SubAgentTimeoutInput.draftFor(45))
    }

    @Test
    fun `draft 出来的初值能被自己解析回去`() {
        for (sec in listOf(30, 45, 60, 90, 300, 330, 1800)) {
            val d = SubAgentTimeoutInput.draftFor(sec)
            assertEquals("初值 $d 应能解析回 $sec", sec, SubAgentTimeoutInput.parse(d, sec))
        }
    }
}
