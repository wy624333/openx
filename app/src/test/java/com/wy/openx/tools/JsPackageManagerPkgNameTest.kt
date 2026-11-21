package com.wy.openx.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 插件包名冲突分配的回归测试。
 *
 * 背景（2026-10-06 修）：`JsPackageManager.load` 原来直接 `packages[pkgName] = ...`，
 * 而包名是「第一个工具名第一个下划线前的前缀」推出来的 —— 两个插件首工具同前缀
 * （`get_weather` / `get_time` 都推成 `get`）时，后加载的会把前一个的 PkgInfo 整个覆盖：
 *  · 前一个插件的工具全部注册不上，但管理页仍显示它加载成功（静默失败）；
 *  · 前一个插件卸载时按基名删，删掉的却是**别人**那条。
 *
 * 这里只测分配策略本身（`load` 还要起 QuickJS，跑不了 JVM 单测）。
 */
class JsPackageManagerPkgNameTest {

    @Test
    fun `无冲突时直接用基名`() {
        assertEquals("get", allocatePackageName("get", taken = emptySet()))
    }

    @Test
    fun `冲突时加数字后缀而不是覆盖`() {
        assertEquals("get#2", allocatePackageName("get", taken = setOf("get")))
    }

    @Test
    fun `连续多个冲突时往后顺延`() {
        val taken = setOf("get", "get#2", "get#3")
        assertEquals("get#4", allocatePackageName("get", taken))
    }

    @Test
    fun `只占用了后缀不占用基名时仍返回基名`() {
        // 别人已经用了 get#2，但 get 本身空着 —— 不该被无关条目挡住
        assertEquals("get", allocatePackageName("get", taken = setOf("get#2")))
    }

    @Test
    fun `无下划线的工具名整串就是基名`() {
        // Kotlin substringBefore 找不到分隔符时返回原串（不是空串），这里要跟它一致
        val base = "todo".substringBefore('_')
        assertEquals("todo", allocatePackageName(base, taken = setOf("other")))
        assertEquals("todo#2", allocatePackageName(base, taken = setOf("todo")))
    }

    @Test
    fun `每个插件都拿到互不相同的包名`() {
        val taken = mutableSetOf<String>()
        val a = allocatePackageName("get", taken).also { taken += it }
        val b = allocatePackageName("get", taken).also { taken += it }
        assertEquals("get", a)
        assertEquals("get#2", b)
    }
}
