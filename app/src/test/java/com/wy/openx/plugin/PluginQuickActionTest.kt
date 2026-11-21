package com.wy.openx.plugin

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷入口清单解析的健壮性。
 *
 * 起因是一次线上崩溃：
 * ```
 * java.lang.NullPointerException: Parameter specified as non-null is null:
 *   method com.wy.openx.plugin.PluginQuickAction.copy, parameter url
 *   at PluginQuickBarKt.collectQuickGroups(PluginQuickBar.kt:301)
 * ```
 *
 * 两点教训固定在这里：
 * 1. **Gson 反序列化 Kotlin data class 时不应用字段默认值** —— 清单里缺哪个字段，
 *    解出来就是 Java 默认值（对象类型为 null），而不是声明处的 `= ""`。
 * 2. 因此只要有一个可选字段被写成非空类型，`copy()` 的 Kotlin 非空校验就会抛 NPE。
 *    而 `collectQuickGroups` 用 `it.copy()` 回填 ownerPluginId，于是**任何一个**
 *    插件缺这个字段，整个插件列表加载都会挂掉（不是那一个插件不显示的问题）。
 *
 * 所以 PluginQuickAction 的字段一律声明为可空，使用处统一走 `orEmpty()` 兜底。
 */
class PluginQuickActionTest {

    private val gson = Gson()

    @Test
    fun `清单缺字段时解出 null 而不是默认值`() {
        // 真实插件清单里最常见的最小形态：只有 label / action / icon
        val action = gson.fromJson(
            """{"label":"打开面板","action":"openUi","icon":"router"}""",
            PluginQuickAction::class.java
        )

        assertEquals("打开面板", action.label)
        assertEquals("openUi", action.action)
        // 这两个字段清单里没有 —— Gson 不套用声明处的默认值，就是 null
        assertNull(action.url)
        assertNull(action.prompt)
    }

    @Test
    fun `copy 回填来源插件 id 不再抛 NPE`() {
        val action = gson.fromJson(
            """{"label":"打开面板","action":"openUi","icon":"router"}""",
            PluginQuickAction::class.java
        )

        // 修复前这一步就是崩溃现场：copy() 会校验每个非空参数，url 为 null 直接抛
        val copied = action.copy().also { it.ownerPluginId = "openx.ai-gateway" }

        assertEquals("openx.ai-gateway", copied.ownerPluginId)
        assertEquals(action.label, copied.label)
        assertNull(copied.url)
    }

    @Test
    fun `动作类型判断在字段缺失时回落到 prompt`() {
        val minimal = gson.fromJson(
            """{"label":"查余额"}""",
            PluginQuickAction::class.java
        )
        // 没写 action 时既不是 openUi 也不是 openUrl，走默认的填提示词分支
        assertTrue(!minimal.opensUi)
        assertTrue(!minimal.opensUrl)
        assertEquals("prompt", minimal.actionOrPrompt)

        // 显式空串同样要回落，不能当成未知动作
        val blank = gson.fromJson(
            """{"label":"查余额","action":"   "}""",
            PluginQuickAction::class.java
        )
        assertEquals("prompt", blank.actionOrPrompt)
    }

    @Test
    fun `openUrl 动作解析出地址`() {
        val action = gson.fromJson(
            """{"label":"浏览器打开面板","action":"openUrl","url":"http://127.0.0.1:18790"}""",
            PluginQuickAction::class.java
        )
        assertTrue(action.opensUrl)
        assertTrue(!action.opensUi)
        assertEquals("http://127.0.0.1:18790", action.url)
    }

    @Test
    fun `可选字段全部缺失时 orEmpty 给出稳定空值`() {
        val action = gson.fromJson(
            """{"label":"打开面板","action":"openUi"}""",
            PluginQuickAction::class.java
        )
        // 使用处统一走 orEmpty()，避免把 null 漏给 UI 或输入框
        assertEquals("打开面板", action.label.orEmpty())
        assertEquals("", action.prompt.orEmpty())
        assertEquals("", action.url.orEmpty())
        assertEquals("", action.icon.orEmpty())
    }
}
