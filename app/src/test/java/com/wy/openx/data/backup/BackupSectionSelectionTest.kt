package com.wy.openx.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「升级新增的段默认勾上、且只自动勾一次」的回归测试。
 *
 * 背景：默认值原本只在「从没保存过选择」时整体生效。用户保存过一次之后，
 * 后续版本新增的段（容器配置、记忆、内置 MCP、终端外观…）在已保存列表里不存在，
 * 于是**静默保持未勾**——用户以为备份覆盖完整，实际漏掉了从没见过的段。
 */
class BackupSectionSelectionTest {

    private val known = setOf("a", "b", "c")
    private val defaults = setOf("a", "b", "c") // 全部默认勾
    private fun json(vararg ids: String) = ids.joinToString(",", "[", "]")

    @Test
    fun `从没保存过选择时用默认集`() {
        assertEquals(defaults, resolveBackupSectionSelection(null, null, known, defaults))
        assertEquals(defaults, resolveBackupSectionSelection("", "", known, defaults))
    }

    @Test
    fun `升级新增的段按默认值自动并入一次`() {
        // 用户此前保存了 a、b；c 是这版新增的，用户还没见过 → 应自动勾上
        val r = resolveBackupSectionSelection(json("a", "b"), json("a", "b"), known, defaults)
        assertEquals(setOf("a", "b", "c"), r)
        assertTrue("新增段 c 没被自动勾上", "c" in r)
    }

    @Test
    fun `用户主动取消过的段不会被默认值勾回来`() {
        // ack 里已含 c = 用户见过并取消了 → 必须保持未勾，这正是不能每次读都并入 defaults 的原因
        val r = resolveBackupSectionSelection(json("a", "b"), json("a", "b", "c"), known, defaults)
        assertEquals(setOf("a", "b"), r)
        assertFalse("用户取消过的 c 被默认值勾回来了", "c" in r)
    }

    @Test
    fun `默认不勾的新段不会被自动勾上`() {
        // workspace 那类 defaultOn=false 的段，升级新增时也不该自动勾（内容可有 GB 级）
        val known2 = setOf("a", "b", "c")
        val defaults2 = setOf("a", "b")
        val r = resolveBackupSectionSelection(json("a", "b"), json("a", "b"), known2, defaults2)
        assertEquals(setOf("a", "b"), r)
        assertFalse("defaultOn=false 的新段被自动勾上了", "c" in r)
    }

    @Test
    fun `旧版本遗留的未知段被剔除`() {
        // 「勾了但打不进包」的幽灵勾选
        val r = resolveBackupSectionSelection(json("a", "ghost", "b"), json("a", "b"), known, defaults)
        assertFalse("未知段 ghost 没被剔除", "ghost" in r)
        assertEquals(setOf("a", "b", "c"), r)
    }

    @Test
    fun `非法 JSON 退回默认集而不是崩`() {
        assertEquals(defaults, resolveBackupSectionSelection("{不是 json", null, known, defaults))
        assertEquals(defaults, resolveBackupSectionSelection(null, "}}}坏", known, defaults))
    }

    @Test
    fun `容器配置在真实目录里属于会被自动并入的那一类`() {
        // 防止以后有人把容器配置改成 defaultOn=false 却没同步这里的行为说明
        assertTrue(
            "容器配置应当默认勾选（体积仅数百字节，不勾会静默漏备份）",
            BackupSections.CONTAINER in BackupSectionCatalog.defaultSelection
        )
    }
}
