package com.wy.openx.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份范围选择目录的回归测试。
 *
 * 这里锁的是「UI 勾选框」和「实际打包逻辑」不会各走各的：
 * 之前 `BackupSections.QUANT` 是个 0 引用的死常量，照它写 UI 会勾到一个根本不存在的段。
 */
class BackupSectionCatalogTest {

    @Test
    fun `目录与实际可导出的段完全一致`() {
        assertEquals(
            BackupManager.exportableSectionIds,
            BackupSectionCatalog.entries.map { it.id }.toSet()
        )
    }

    @Test
    fun `段 id 不重复`() {
        val ids = BackupSectionCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `工作区默认不勾其余都勾`() {
        val sel = BackupSectionCatalog.defaultSelection
        assertFalse(BackupSections.WORKSPACE in sel)
        assertEquals(
            BackupSectionCatalog.entries.filter { it.id != BackupSections.WORKSPACE }.map { it.id }.toSet(),
            sel
        )
    }

    @Test
    fun `不存在 quant 这个段`() {
        // 死常量 QUANT("quant") 已删除：量化三段的真实 id 是 portfolio/stock_memory/signal_tracker
        assertFalse(BackupSectionCatalog.isValid("quant"))
        assertTrue(BackupSectionCatalog.isValid("portfolio"))
        assertTrue(BackupSectionCatalog.isValid("stock_memory"))
        assertTrue(BackupSectionCatalog.isValid("signal_tracker"))
    }

    @Test
    fun `技能目录段不单列 归并到 skills`() {
        val ids = BackupSectionCatalog.entries.map { it.id }
        assertTrue(BackupSections.SKILLS in ids)
        assertFalse("skills_dir" in ids)
    }

    @Test
    fun `工作区上限统一为 2GB`() {
        // 旧值 512MB（用户看到的「500M」）。曾讨论「常规 500M + 单独入口 2GB」的双入口方案，
        // 已定案为统一 2GB 单入口——越限是静默跳过，用户主动勾了工作区却拿不到数据是最差的失败方式。
        assertEquals(2L * 1024 * 1024 * 1024, BackupManager.MAX_WORKSPACE_BYTES)
    }

    @Test
    fun `工作区仍是默认不勾`() {
        // 放宽上限不等于默认带上：日常备份不该因为工作区变肥就变重
        assertFalse(BackupSections.WORKSPACE in BackupSectionCatalog.defaultSelection)
    }

    @Test
    fun `isValid 与 labelOf 对未知 id 的兜底`() {
        assertTrue(BackupSectionCatalog.isValid(BackupSections.SETTINGS))
        assertFalse(BackupSectionCatalog.isValid("nope"))
        assertEquals("设置与 API 预设", BackupSectionCatalog.labelOf(BackupSections.SETTINGS))
        assertEquals("nope", BackupSectionCatalog.labelOf("nope"))
    }
}
