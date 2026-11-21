package com.wy.openx.plugin

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 清单 `aiProvider` 字段（插件对外声明本机 AI 供应商）的解析健壮性。
 *
 * 与 [PluginQuickActionTest] 同一类风险：Gson 反序列化 Kotlin data class 时**不套用**
 * 声明处的默认值，清单里缺哪个字段解出来就是 null。而 `PluginStore.load()` 要遍历
 * 所有已安装插件、`copy()` 时任何非空参数为 null 都会抛 NPE —— 一个旧插件的清单
 * 缺这个字段，就能带倒整个插件列表。
 *
 * 所以 `aiProvider` 及其子字段一律声明为可空，使用处统一 `orEmpty()`。
 */
class PluginAiProviderTest {

    private val gson = Gson()

    /** 本次改动之后新建的插件清单（带 aiProvider）。 */
    private val manifestWithAiProvider = """
        {
          "id": "openx.ai-gateway",
          "name": "AI 网关",
          "version": "1.1.5",
          "apiVersion": "1.0.0",
          "author": "OpenX",
          "compat": "operit",
          "description": "网关",
          "main": "",
          "service": "",
          "ui": "ui/panel.ui.js",
          "permissions": ["filesystem", "http"],
          "quickActions": [],
          "aiProvider": {
            "name": "AI 网关（本机）",
            "baseUrl": "http://127.0.0.1:18791/v1"
          }
        }
    """.trimIndent()

    /** 存量插件清单：本次改动之前装的，都没有 aiProvider 字段。 */
    private val legacyManifest = """
        {
          "id": "openx.ai-gateway",
          "name": "AI 网关",
          "version": "1.1.4",
          "apiVersion": "1.0.0",
          "author": "OpenX",
          "compat": "operit",
          "description": "网关",
          "main": "",
          "service": "",
          "ui": "ui/panel.ui.js",
          "permissions": ["filesystem", "http"],
          "quickActions": []
        }
    """.trimIndent()

    @Test
    fun `声明了 aiProvider 时解析出名字与地址`() {
        val manifest = gson.fromJson(manifestWithAiProvider, PluginManifest::class.java)

        assertEquals("openx.ai-gateway", manifest.id)
        assertEquals("AI 网关（本机）", manifest.aiProvider?.name)
        assertEquals("http://127.0.0.1:18791/v1", manifest.aiProvider?.baseUrl)
    }

    @Test
    fun `存量清单没有 aiProvider 字段时解出 null`() {
        val manifest = gson.fromJson(legacyManifest, PluginManifest::class.java)

        assertNull(manifest.aiProvider)
    }

    @Test
    fun `aiProvider 子字段缺失时也是 null 而不是空串`() {
        // 只想声明地址、不关心 key/模型的常见写法
        val manifest = gson.fromJson(
            """{"id":"x","name":"y","quickActions":[],"aiProvider":{"baseUrl":"http://127.0.0.1:9999/v1"}}""",
            PluginManifest::class.java
        )

        val ai = manifest.aiProvider
        assertEquals("http://127.0.0.1:9999/v1", ai?.baseUrl)
        // 使用处靠 orEmpty() 兜底：留空表示"不覆盖用户自己填的 key / 选的模型"
        assertNull(ai?.name)
        assertNull(ai?.apiKey)
        assertNull(ai?.model)
        assertEquals("", ai?.apiKey.orEmpty())
        assertEquals("", ai?.model.orEmpty())
    }

    @Test
    fun `aiProvider 缺失时 copy 回填不抛 NPE`() {
        // 注意：真实加载路径 `PluginStore.parseManifest` 会先跑 `normalizeManifest`
        // 把 quickActions 等字段补成空数组，所以这里手写清单也把它带上，
        // 以保证本用例只把 aiProvider 当变量。
        val manifest = gson.fromJson(legacyManifest, PluginManifest::class.java)

        val copied = manifest.copy(version = "1.1.5")

        assertEquals("1.1.5", copied.version)
        assertNull(copied.aiProvider)
    }
}
