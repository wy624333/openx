package com.wy.openx.data.backup

import com.wy.openx.container.ContainerSettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「容器配置」段的回归测试。
 *
 * 这个段的价值全在**边界**上——搬什么、坚决不搬什么。所以测试重点不是「能导出」，
 * 而是「不会把不该搬的东西搬进去」。
 */
class BackupContainerSectionTest {

    @Test
    fun `容器配置在目录里且默认勾选`() {
        val entry = BackupSectionCatalog.entries.firstOrNull { it.id == BackupSections.CONTAINER }
        assertTrue("容器配置段未登记进目录", entry != null)
        assertEquals("容器配置", entry!!.label)
        // 体积只有几百字节，默认勾上；不勾的话换机后镜像源/profile 全丢，用户很难察觉
        assertTrue(
            BackupSections.CONTAINER in BackupSectionCatalog.defaultSelection
        )
    }

    @Test
    fun `容器配置属于可导出段`() {
        // 与 registeredSectionIds 对齐：漏登记的话 UI 能勾但导出时静默不出数据
        assertTrue(BackupSections.CONTAINER in BackupManager.exportableSectionIds)
    }

    @Test
    fun `空快照判定`() {
        assertTrue(ContainerSettingsSnapshot().isEmpty)
        assertFalse(ContainerSettingsSnapshot(activeProfileId = "builtin-alpine").isEmpty)
        assertFalse(ContainerSettingsSnapshot(apkMirrorId = "tuna").isEmpty)
        assertFalse(ContainerSettingsSnapshot(profilesJson = "[]").isEmpty)
    }

    /**
     * 锁死「不备份已下载镜像」这个决定。
     *
     * `downloaded_images_json` 存的是**本机 fileUri**，新手机上那些文件根本不存在，
     * 照搬进来只会留下一堆指向死文件的记录，比不备份更糟——
     * 用户会看到「已下载」却点不开。将来若有人往快照里加镜像字段，本测试会失败。
     */
    @Test
    fun `快照不含已下载镜像与本地路径字段`() {
        // Compose 编译器会往 data class 注入 `$stable` 这类合成字段（未标 isSynthetic），一并滤掉
        val fields = ContainerSettingsSnapshot::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf("activeProfileId", "apkMirrorId", "profilesJson"),
            fields
        )
    }
}
