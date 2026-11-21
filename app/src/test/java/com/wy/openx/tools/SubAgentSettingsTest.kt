package com.wy.openx.tools

import com.wy.openx.data.model.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理设置的生效回归：并发上限、超时、工具轮次上限、强制只读。
 *
 * 这些设置都是**运行期实时读取** [AppContextHolder.currentSettings]，
 * 所以测试直接改这个字段来模拟用户改设置后立即生效（不需要重启）。
 */
class SubAgentSettingsTest {

    @After
    fun tearDown() {
        AppContextHolder.currentSettings = null
    }

    // ---------- 并发上限 ----------
    @Test
    fun `默认并发上限为 3`() {
        AppContextHolder.currentSettings = AppSettings()
        assertEquals(3, SubAgentLimits.concurrencyCap())
    }

    @Test
    fun `设置里的并发上限生效`() {
        AppContextHolder.currentSettings = AppSettings(subagentConcurrency = 1)
        assertEquals(1, SubAgentLimits.concurrencyCap())
        assertEquals(1, resolveSubAgentConcurrency(null, taskCount = 4))
    }

    @Test
    fun `设置上限高于默认时按设置走`() {
        AppContextHolder.currentSettings = AppSettings(subagentConcurrency = 5)
        assertEquals(5, SubAgentLimits.concurrencyCap())
        assertEquals(5, resolveSubAgentConcurrency(null, taskCount = 8))
    }

    @Test
    fun `越界的设置值被夹回 1 到 5`() {
        AppContextHolder.currentSettings = AppSettings(subagentConcurrency = 99)
        assertEquals(5, SubAgentLimits.concurrencyCap())
        AppContextHolder.currentSettings = AppSettings(subagentConcurrency = 0)
        assertEquals(1, SubAgentLimits.concurrencyCap())
    }

    @Test
    fun `模型传的值只能更低`() {
        AppContextHolder.currentSettings = AppSettings(subagentConcurrency = 2)
        assertEquals(2, resolveSubAgentConcurrency(requested = 4, taskCount = 4))
        assertEquals(1, resolveSubAgentConcurrency(requested = 1, taskCount = 4))
    }

    // ---------- 超时 ----------
    @Test
    fun `默认超时为 30 分钟`() {
        AppContextHolder.currentSettings = AppSettings()
        assertEquals(1_800_000L, SubAgentLimits.timeoutMs())
    }

    @Test
    fun `设置里的超时生效且越界被夹住`() {
        AppContextHolder.currentSettings = AppSettings(subagentTimeoutSec = 60)
        assertEquals(60_000L, SubAgentLimits.timeoutMs())
        // 上限 120 分钟（2026 起放宽）
        AppContextHolder.currentSettings = AppSettings(subagentTimeoutSec = 7200)
        assertEquals(7_200_000L, SubAgentLimits.timeoutMs())
        AppContextHolder.currentSettings = AppSettings(subagentTimeoutSec = 5)
        assertEquals(30_000L, SubAgentLimits.timeoutMs())
        AppContextHolder.currentSettings = AppSettings(subagentTimeoutSec = 99999)
        assertEquals(7_200_000L, SubAgentLimits.timeoutMs())
    }

    // ---------- 工具轮次上限 ----------
    @Test
    fun `默认轮次上限为 50`() {
        AppContextHolder.currentSettings = AppSettings()
        assertEquals(50, SubAgentLimits.maxRounds())
    }

    @Test
    fun `设置里的轮次上限生效且越界被夹住`() {
        AppContextHolder.currentSettings = AppSettings(subagentMaxRounds = 30)
        assertEquals(30, SubAgentLimits.maxRounds())
        AppContextHolder.currentSettings = AppSettings(subagentMaxRounds = 200)
        assertEquals(200, SubAgentLimits.maxRounds())
        AppContextHolder.currentSettings = AppSettings(subagentMaxRounds = 0)
        assertEquals(1, SubAgentLimits.maxRounds())
        AppContextHolder.currentSettings = AppSettings(subagentMaxRounds = 99999)
        assertEquals(200, SubAgentLimits.maxRounds())
    }

    // ---------- 强制只读 ----------
    @Test
    fun `关闭强制只读时 general 保留父级能力`() {
        AppContextHolder.currentSettings = AppSettings(subagentReadOnly = false)
        assertTrue(SubAgentToolPolicy.allows("general", "terminal"))
        assertTrue(SubAgentToolPolicy.allows("general", "writeFile"))
    }

    @Test
    fun `强制只读挡住写文件与执行命令`() {
        AppContextHolder.currentSettings = AppSettings(subagentReadOnly = true)
        // general 也一并收敛：它默认继承父级全部能力，是最需要被拦住的那个
        assertFalse(SubAgentToolPolicy.allows("general", "writeFile"))
        assertFalse(SubAgentToolPolicy.allows("general", "terminal"))
        assertFalse(SubAgentToolPolicy.allows("general", "editFile"))
        // 读类不受影响
        assertTrue(SubAgentToolPolicy.allows("general", "readFile"))
        assertTrue(SubAgentToolPolicy.allows("general", "search_code"))
        assertTrue(SubAgentToolPolicy.allows("general", "web_fetch"))
    }

    @Test
    fun `强制只读下 file_manager 与 browser 的写动作被参数级校验挡住`() {
        AppContextHolder.currentSettings = AppSettings(subagentReadOnly = true)
        // 工具本身仍在下发热图里（只读动作要用），但写动作执行前必须被挡
        assertTrue(SubAgentToolPolicy.allows("general", "file_manager"))
        assertTrue(SubAgentToolPolicy.allows("general", "browser"))
        val write = SubAgentToolPolicy.validate("general", "file_manager", """{"action":"write"}""")
        assertTrue(write != null && write.contains("强制只读"))
        val click = SubAgentToolPolicy.validate("general", "browser", """{"action":"click"}""")
        assertTrue(click != null && click.contains("强制只读"))
        // 只读动作放行
        assertEquals(null, SubAgentToolPolicy.validate("general", "file_manager", """{"action":"read"}"""))
        assertEquals(null, SubAgentToolPolicy.validate("general", "browser", """{"action":"navigate"}"""))
    }

    @Test
    fun `强制只读保留记忆管理员的记忆写入`() {
        AppContextHolder.currentSettings = AppSettings(subagentReadOnly = true)
        assertTrue(SubAgentToolPolicy.allows("memory-curator", "memory_save"))
        assertTrue(SubAgentToolPolicy.allows("memory-curator", "memory_read"))
        assertFalse(SubAgentToolPolicy.allows("memory-curator", "writeFile"))
    }

    @Test
    fun `强制只读不影响 planner 无工具的设定`() {
        AppContextHolder.currentSettings = AppSettings(subagentReadOnly = true)
        assertFalse(SubAgentToolPolicy.allows("planner", "readFile"))
    }
}
