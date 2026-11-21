package com.wy.openx.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面端 UA 常量的关键性质。
 *
 * 回归背景：WebView 默认 UA 带 `Mobile` 标识，站点据此一律返回手机版。桌面 UA 必须
 * **去掉 Mobile 标识**才会走桌面分支——这是「站点版式」的唯一开关。
 *
 * [BrowserManager] 本体依赖 WebView，其余 UA 逻辑（applyUserAgent / setDesktopMode）
 * 无法在 JVM 单测覆盖，只能真机验证；这里只守住常量本身的约定。
 */
class BrowserUserAgentTest {

    @Test
    fun `桌面UA不带Mobile标识`() {
        // 关键：带 Mobile 站点就给手机版
        assertFalse(BrowserManager.DESKTOP_UA.contains("Mobile", ignoreCase = true))
    }

    @Test
    fun `桌面UA声明为桌面操作系统`() {
        // Windows NT / Macintosh / X11 任一都能让站点走桌面分支
        val ua = BrowserManager.DESKTOP_UA
        assertTrue(
            ua.contains("Windows NT") || ua.contains("Macintosh") || ua.contains("X11")
        )
    }

    @Test
    fun `桌面UA是合法浏览器标识`() {
        val ua = BrowserManager.DESKTOP_UA
        assertTrue(ua.startsWith("Mozilla/5.0"))
        assertTrue(ua.contains("AppleWebKit"))   // 必须含，否则部分站点拒绝
        assertTrue(ua.contains("Chrome/"))       // 现代 UA 都带
        assertTrue(ua.contains("Safari/"))       // WebKit 系完整串需要
    }
}
