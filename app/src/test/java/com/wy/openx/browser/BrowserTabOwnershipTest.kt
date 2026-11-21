package com.wy.openx.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多会话标签归属规则（方案 A′：无主标签可用，但一用就认领）。
 *
 * 回归背景：多会话同时用浏览器时，两个会话都选中了同一个（UI 自动建的）无主标签 tab-5，
 * 于是 A 会话的 navigate/evaluate 打到了 B 的页面上。修复靠「优先自己的 + 用无主即认领」。
 */
class BrowserTabOwnershipTest {

    // ---------- 可见性 ----------

    @Test
    fun `无主标签对任何会话可见`() {
        assertTrue(BrowserTabOwnership.isVisibleTo(0L, 7L))
        assertTrue(BrowserTabOwnership.isVisibleTo(0L, 99L))
        assertTrue(BrowserTabOwnership.isVisibleTo(0L, 0L))
    }

    @Test
    fun `本会话标签对自己可见`() {
        assertTrue(BrowserTabOwnership.isVisibleTo(7L, 7L))
    }

    @Test
    fun `别人的标签不可见`() {
        assertFalse(BrowserTabOwnership.isVisibleTo(7L, 8L))
    }

    @Test
    fun `会话隔离是双向的`() {
        assertFalse(BrowserTabOwnership.isVisibleTo(7L, 8L))
        assertFalse(BrowserTabOwnership.isVisibleTo(8L, 7L))
    }

    // ---------- 认领规则（方案 A′ 的核心） ----------

    @Test
    fun `会话使用无主标签时应认领`() {
        assertTrue(BrowserTabOwnership.shouldClaim(0L, 7L))
    }

    @Test
    fun `已属于自己的不需要再认领`() {
        assertFalse(BrowserTabOwnership.shouldClaim(7L, 7L))
    }

    @Test
    fun `别人的标签不该走到认领(会被拒)`() {
        assertFalse(BrowserTabOwnership.shouldClaim(8L, 7L))
    }

    @Test
    fun `用户不认领标签`() {
        // 用户若"拥有"标签，AI 就再也用不了用户打开的页面
        assertFalse(BrowserTabOwnership.shouldClaim(0L, 0L))
    }

    // ---------- 列表过滤 ----------

    @Test
    fun `过滤出本会话与无主标签`() {
        data class Tab(val id: String, val owner: Long)
        val tabs = listOf(
            Tab("tab-1", 0L),   // 用户/UI 建的
            Tab("tab-2", 7L),   // 本会话
            Tab("tab-3", 8L),   // 别的会话
            Tab("tab-4", 7L)
        )
        val visible = BrowserTabOwnership.visibleTo(tabs, { it.owner }, 7L)
        assertEquals(listOf("tab-1", "tab-2", "tab-4"), visible.map { it.id })
    }

    @Test
    fun `过滤时保持顺序`() {
        data class Tab(val id: String, val owner: Long)
        val tabs = listOf(
            Tab("tab-z", 0L),
            Tab("tab-a", 7L),
            Tab("tab-m", 0L)
        )
        val visible = BrowserTabOwnership.visibleTo(tabs, { it.owner }, 7L)
        assertEquals(listOf("tab-z", "tab-a", "tab-m"), visible.map { it.id })
    }

    // ---------- 活动标签选择 ----------

    @Test
    fun `无可见标签返回 null`() {
        assertNull(BrowserTabOwnership.pickActive(emptyList(), null))
    }

    @Test
    fun `记住的活动标签仍可见时优先返回它`() {
        assertEquals("tab-2", BrowserTabOwnership.pickActive(listOf("tab-1", "tab-2"), "tab-2"))
    }

    @Test
    fun `记住的活动标签已消失时兜底取最后一个`() {
        assertEquals("tab-3", BrowserTabOwnership.pickActive(listOf("tab-1", "tab-3"), "tab-2"))
    }

    @Test
    fun `没有记忆时取最后一个`() {
        assertEquals("tab-2", BrowserTabOwnership.pickActive(listOf("tab-1", "tab-2"), null))
    }

    @Test
    fun `单个标签时返回它`() {
        assertEquals("tab-9", BrowserTabOwnership.pickActive(listOf("tab-9"), null))
    }

    // ---------- 显式 tabId 解析 ----------

    @Test
    fun `能用自己的标签`() {
        assertEquals("tab-2", BrowserTabOwnership.resolveExplicit("tab-2", 7L, 7L))
    }

    @Test
    fun `能用无主标签`() {
        assertEquals("tab-1", BrowserTabOwnership.resolveExplicit("tab-1", 0L, 7L))
    }

    @Test
    fun `不能用别人的标签`() {
        assertNull(BrowserTabOwnership.resolveExplicit("tab-3", 8L, 7L))
    }

    // ---------- 关键回归：认领后不再串台 ----------

    @Test
    fun `实测复现场景_认领后第二个会话看不到该标签`() {
        // 日志实测：UI 建了 tab-5（owner=0），会话 401 与 402 都想用它。
        data class Tab(val id: String, var owner: Long)
        val tab5 = Tab("tab-5", 0L)
        val tabs = mutableListOf(tab5)

        // 401 先看到它（无主可见）并使用 → 认领
        assertTrue(BrowserTabOwnership.isVisibleTo(tab5.owner, 401L))
        assertTrue(BrowserTabOwnership.shouldClaim(tab5.owner, 401L))
        tab5.owner = 401L // 认领后的状态

        // 之后 402 就看不见它了 → 必须自己 newTab
        assertFalse(BrowserTabOwnership.isVisibleTo(tab5.owner, 402L))
        assertTrue(BrowserTabOwnership.visibleTo(tabs, { it.owner }, 402L).isEmpty())
    }

    @Test
    fun `两会话各自解析到自己的活动标签`() {
        data class Tab(val id: String, val owner: Long)
        val tabs = listOf(Tab("tab-A", 1L), Tab("tab-B", 2L))

        val idsFor1 = BrowserTabOwnership.visibleTo(tabs, { it.owner }, 1L).map { it.id }
        assertEquals("tab-A", BrowserTabOwnership.pickActive(idsFor1, "tab-A"))

        val idsFor2 = BrowserTabOwnership.visibleTo(tabs, { it.owner }, 2L).map { it.id }
        assertEquals("tab-B", BrowserTabOwnership.pickActive(idsFor2, "tab-B"))
    }
}