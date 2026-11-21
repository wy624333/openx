package com.wy.openx.data.backup

import com.google.gson.Gson
import com.wy.openx.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证「设置与 API 预设」段的**真实合并逻辑**能完整还原 AppSettings 字段——
 * 重点是子代理设置（并发/超时/轮次上限/只读/模型/提供商），它们没有独立分段，全靠这段带出去。
 *
 * 这里跑的是 [parseSettingsSafely] 本体，不是抄一份的复制品。
 */
class BackupSettingsMergeTest {

    private val gson = Gson()

    private fun settingsWithSubagent(
        provider: String = "sub-provider",
        model: String = "sub-model",
        concurrency: Int = 5,
        timeoutSec: Int = 1800,
        readOnly: Boolean = true,
        maxRounds: Int = 30
    ) = AppSettings(
        subagentProviderId = provider,
        subagentModel = model,
        subagentConcurrency = concurrency,
        subagentTimeoutSec = timeoutSec,
        subagentReadOnly = readOnly,
        subagentMaxRounds = maxRounds
    )

    @Test
    fun `子代理六个字段都能备份并还原`() {
        val backed = settingsWithSubagent()
        val json = gson.toJsonTree(backed).toString()

        // 恢复时的「当前设置」是另一套值，确保真的被覆盖而不是恰好相等
        val current = settingsWithSubagent("other", "other-model", 1, 30, false)
        val merged = parseSettingsSafely(gson, json, current)

        assertNotNull(merged)
        assertEquals("sub-provider", merged!!.subagentProviderId)
        assertEquals("sub-model", merged.subagentModel)
        assertEquals(5, merged.subagentConcurrency)
        assertEquals(1800, merged.subagentTimeoutSec)
        assertEquals(true, merged.subagentReadOnly)
        assertEquals(30, merged.subagentMaxRounds)
    }

    @Test
    fun `子代理字段不出现在备份里时保留当前值`() {
        // 模拟旧版本备份：JSON 里没有 subagent* 键
        val legacy = gson.toJsonTree(AppSettings()).asJsonObject
        legacy.entrySet().removeIf { it.key.startsWith("subagent") }
        val current = settingsWithSubagent("keep-me", "keep-me-too", 4, 900, true, maxRounds = 25)

        val merged = parseSettingsSafely(gson, legacy.toString(), current)

        assertNotNull(merged)
        assertEquals("keep-me", merged!!.subagentProviderId)
        assertEquals("keep-me-too", merged.subagentModel)
        assertEquals(4, merged.subagentConcurrency)
        assertEquals(900, merged.subagentTimeoutSec)
        assertEquals(true, merged.subagentReadOnly)
        // 旧版本备份没有 subagent_max_rounds，必须保留当前值而不是被默认值 12 冲掉
        assertEquals(25, merged.subagentMaxRounds)
    }

    @Test
    fun `备份里的 null 不覆盖当前值`() {
        val obj = gson.toJsonTree(settingsWithSubagent()).asJsonObject
        obj.add("subagentModel", com.google.gson.JsonNull.INSTANCE)
        val current = settingsWithSubagent("p", "current-model", 3, 300, false)

        val merged = parseSettingsSafely(gson, obj.toString(), current)

        assertEquals("current-model", merged!!.subagentModel)
    }

    @Test
    fun `未暴露在备份段里的字段不受影响`() {
        // temperature 等字段随 settings 段一起走，合并后应与备份一致
        val backed = AppSettings(temperature = 0.42f, maxTokens = "4096", subagentConcurrency = 2)
        val current = AppSettings(temperature = 1.0f, maxTokens = "1024", subagentConcurrency = 5)

        val merged = parseSettingsSafely(gson, gson.toJsonTree(backed).toString(), current)

        assertEquals(0.42f, merged!!.temperature!!, 1e-6f)
        assertEquals("4096", merged.maxTokens)
        assertEquals(2, merged.subagentConcurrency)
    }

    @Test
    fun `工具设置同样能备份并还原`() {
        // 工具设置没有独立分段，和子代理一样全靠「设置与 API 预设」这段带出去
        val backed = AppSettings(
            autoToolCall = false,
            parallelToolCalls = true,
            toolAllowList = listOf("file_read", "file_write", "terminal"),
            toolPermissionMode = "ask",
            showLogs = false
        )
        val current = AppSettings(
            autoToolCall = true,
            parallelToolCalls = false,
            toolAllowList = emptyList(),
            toolPermissionMode = "allow",
            showLogs = true
        )

        val merged = parseSettingsSafely(gson, gson.toJsonTree(backed).toString(), current)

        assertNotNull(merged)
        assertEquals(false, merged!!.autoToolCall)
        assertEquals(true, merged.parallelToolCalls)
        assertEquals(listOf("file_read", "file_write", "terminal"), merged.toolAllowList)
        assertEquals("ask", merged.toolPermissionMode)
        assertEquals(false, merged.showLogs)
    }

    @Test
    fun `高级设置的系统提示词与项目规则能备份并还原`() {
        // 「高级」分区只有这两项内容，都存在 AppSettings 里，随 settings 段走
        val longPrompt = "你是一个资深全栈工程师。\n1. 代码用 ```lang 包裹\n2. 先分析再动手"
        val rules = "1. Java/Kotlin 优先\n2. 禁止 force unwrap\n3. 单测覆盖变更逻辑"
        val backed = AppSettings(systemPrompt = longPrompt, rules = rules)
        val current = AppSettings(systemPrompt = "旧提示词", rules = "旧规则")

        val merged = parseSettingsSafely(gson, gson.toJsonTree(backed).toString(), current)

        assertNotNull(merged)
        assertEquals(longPrompt, merged!!.systemPrompt)
        assertEquals(rules, merged.rules)
    }

    @Test
    fun `非法 JSON 返回 null 而不是抛异常`() {
        assertNull(parseSettingsSafely(gson, "not json at all {{{", AppSettings()))
    }
}
