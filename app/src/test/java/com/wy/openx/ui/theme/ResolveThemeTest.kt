package com.wy.openx.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「主题配色 × 主题模式」解析矩阵的回归测试。
 *
 * 修/新增的东西：原来 themeName 一个维度就定了明暗（同一个 when 直接选 scheme），
 * 主题模式（浅色/深色/跟随系统）引入后变成两个正交维度，边界容易改错：
 * - 哪些配色在 system 下是**固有深色**（科技蓝）；
 * - themeMode 的显式覆盖是否真的覆盖了固有明暗；
 * - 已删除的旧主题名（浅米/亮色/极简亮/极简暗）不能崩、应落到浅米。
 * - 2.5.1 起配色档「跟随系统」改名「系统默认」，**旧名仍须能解析**：
 *   用户库里存的就是旧名，若只认新名会让老用户的配色解析落空。
 *
 * 这些都难用肉眼在所有组合下验证，用测试锁死。
 */
class ResolveThemeTest {

    @Test
    fun `系统默认在system模式跟随系统深浅`() {
        assertFalse(resolveTheme("系统默认", "system", systemDark = false).isDark)
        assertTrue(resolveTheme("系统默认", "system", systemDark = true).isDark)
        assertEquals("claude", resolveTheme("系统默认", "system", false).family)
    }

    @Test
    fun `旧配色名跟随系统仍解析为浅米配色`() {
        // 2.5.1 改名的向后兼容：老用户 DataStore 里存的是「跟随系统」。
        // SettingsStore 读取时会归一化，但 resolveTheme 也必须自己兜住这个值——
        // 两层都兜才安全（CrashActivity 等绕过 SettingsStore 的调用路径直接传原值）。
        val legacy = resolveTheme("跟随系统", "system", systemDark = false)
        assertEquals("claude", legacy.family)
        assertEquals(resolveTheme("系统默认", "system", false), legacy)
    }

    @Test
    fun `所有配色在system模式一律跟随系统`() {
        // 关键：当前配色名里都不含明暗语义，system 下都应跟随系统深浅，
        // 不能有"某个配色无视系统设置"的特例（用户反馈科技蓝曾恒深）。
        listOf("系统默认", "科技蓝", "暖白", "玻璃色", "简约白").forEach { name ->
            assertTrue("$name 在系统深色下应为深", resolveTheme(name, "system", systemDark = true).isDark)
            assertFalse("$name 在系统浅色下应为浅", resolveTheme(name, "system", systemDark = false).isDark)
        }
    }

    @Test
    fun `科技蓝配色族映射正确`() {
        assertEquals("techblue", resolveTheme("科技蓝", "system", false).family)
    }

    @Test
    fun `暖白与玻璃色配色族映射正确`() {
        assertEquals("warmwhite", resolveTheme("暖白", "system", false).family)
        assertEquals("glass", resolveTheme("玻璃色", "system", false).family)
    }

    @Test
    fun `简约白配色族映射正确且浅深两套都存在`() {
        assertEquals("minimalwhite", resolveTheme("简约白", "system", false).family)
        assertFalse(resolveTheme("简约白", "light", systemDark = true).isDark)
        assertTrue(resolveTheme("简约白", "dark", systemDark = false).isDark)
    }

    @Test
    fun `简约白的旧主题名不占用`() {
        // 「简约白」是新增配色，不与已删除的「极简亮」等旧名混淆；
        // 旧名仍必须落到浅米，避免老用户库里残留值被误认成新主题。
        assertEquals("claude", resolveTheme("极简亮", "system", false).family)
        assertEquals("minimalwhite", resolveTheme("简约白", "system", false).family)
    }

    @Test
    fun `themeMode显式覆盖系统明暗`() {
        // light 在系统深色下也强制浅
        assertFalse(resolveTheme("科技蓝", "light", systemDark = true).isDark)
        assertFalse(resolveTheme("系统默认", "light", systemDark = true).isDark)
        // dark 在系统浅色下也强制深
        assertTrue(resolveTheme("暖白", "dark", systemDark = false).isDark)
        assertTrue(resolveTheme("系统默认", "dark", systemDark = false).isDark)
    }

    @Test
    fun `未知themeMode等同于system`() {
        assertEquals(
            resolveTheme("暖白", "system", false).isDark,
            resolveTheme("暖白", "", false).isDark
        )
        assertEquals(
            resolveTheme("暖白", "system", false).isDark,
            resolveTheme("暖白", "garbage", false).isDark
        )
    }

    @Test
    fun `已删除的主题名落到浅米不崩`() {
        listOf("浅米", "亮色", "极简亮", "极简暗").forEach { name ->
            val r = resolveTheme(name, "system", systemDark = false)
            assertEquals("旧主题 $name 应落到 claude 族", "claude", r.family)
            assertFalse("旧主题 $name 不该是深色", r.isDark)
        }
    }

    @Test
    fun `暗色恒深（崩溃页用）`() {
        assertTrue(resolveTheme("暗色", "system", systemDark = false).isDark)
        assertTrue(resolveTheme("暗色", "light", systemDark = false).isDark)
        assertEquals("dark", resolveTheme("暗色", "system", false).family)
        // 崩溃页不能出现浅色：白屏在崩溃场景既刺眼又掩盖信息
    }
}
