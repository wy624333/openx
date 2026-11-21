package com.wy.openx.plugin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 区间夹取的边界行为。
 *
 * 起因是一次真实崩溃（Android 16 / vivo，2026-11-17）：`RenderImage` 里写了
 * `frameIndex.coerceIn(0, frameBitmaps.size - 1)`，帧序列为空时上限是 -1，
 * `coerceIn` 收到 0..-1 这个空区间直接抛 IllegalArgumentException，
 * 结果**所有** `Image` 组件（哪怕根本没用 frames）一点就崩。
 *
 * 上下界在插件 UI 里大量来自插件传入的数据（尺寸、min/max、屏幕像素、集合长度），
 * 插件写错一个数不该让宿主崩。所以统一走 [safeCoerce]，这里锁住它的行为。
 */
class UiRangeSafetyTest {

    @Test
    fun `反序区间不抛异常`() {
        assertEquals(5, safeCoerce(5, 10, 0))
        assertEquals(10, safeCoerce(20, 10, 0))
        assertEquals(0, safeCoerce(-3, 10, 0))
    }

    @Test
    fun `空集合的 size-1 上限不再炸`() {
        // 这就是崩溃现场：空序列时 upper = -1。
        // 归一化后区间变成 [-1, 0]，所以任何值都被夹到 0（不会抛异常）；
        // 对应到帧索引就是 getOrNull(0) 在空列表上返回 null，交给静态图兜底。
        val emptySize = 0
        assertEquals(0, safeCoerce(0, 0, emptySize - 1))
        assertEquals(0, safeCoerce(3, 0, emptySize - 1))
    }

    @Test
    fun `相等上下界退化成该值`() {
        assertEquals(7, safeCoerce(7, 7, 7))
        assertEquals(7, safeCoerce(100, 7, 7))
    }

    @Test
    fun `正常区间行为与 coerceIn 一致`() {
        assertEquals(0, safeCoerce(-5, 0, 10))
        assertEquals(10, safeCoerce(99, 0, 10))
        assertEquals(4, safeCoerce(4, 0, 10))
    }

    @Test
    fun `浮点版同样处理反序区间`() {
        assertEquals(0.5f, safeCoerce(0.5f, 1f, 0f))
        assertEquals(1f, safeCoerce(2f, 1f, 0f))
    }
}