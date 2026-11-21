package com.wy.openx.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图片保存状态的提示文案。
 *
 * 这组测试是**复现一个已发生的 UI bug** 时加的：原先状态用 `Any?`（保存中存 `true`），
 * UI 侧用 `it is String` 判成败，把「正在保存」渲染成了「保存失败」。
 * 最关键的两条是 Saving 不得出现任何文案、以及文案里不含「失败」。
 */
class ImageSaveStateTest {

    @Test
    fun `保存中不显示任何提示文案`() {
        // 防回归核心：点保存后必须先干净地空着，绝不能闪「保存失败」
        assertNull(imageSaveFeedback(ImageSaveState.Saving))
    }

    @Test
    fun `空闲也不显示提示文案`() {
        assertNull(imageSaveFeedback(ImageSaveState.Idle))
    }

    @Test
    fun `保存中绝不含失败字样`() {
        listOf(ImageSaveState.Idle, ImageSaveState.Saving).forEach { st ->
            val t = imageSaveFeedback(st)
            assertNull("状态 $st 不该有文案，实际=$t", t?.contains("失败"))
        }
    }

    @Test
    fun `成功时显示落盘文件名`() {
        assertEquals("已保存 uploads/pic.png", imageSaveFeedback(ImageSaveState.Done("pic.png")))
    }

    @Test
    fun `失败时才显示保存失败`() {
        assertEquals("保存失败", imageSaveFeedback(ImageSaveState.Failed))
    }

    @Test
    fun `只有失败态的字面量是失败`() {
        // 把「出现『保存失败』」与「处于 Failed 态」绑成一一对应，防止别处再写出同样的误导文案
        val all = listOf(
            ImageSaveState.Idle,
            ImageSaveState.Saving,
            ImageSaveState.Done("a.png"),
            ImageSaveState.Done("b.jpg"),
            ImageSaveState.Failed
        )
        val failedTexts = all.filter { imageSaveFeedback(it) == "保存失败" }
        assertEquals(listOf(ImageSaveState.Failed), failedTexts)
    }
}
