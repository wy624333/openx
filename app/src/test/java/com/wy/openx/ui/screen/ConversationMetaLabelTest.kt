package com.wy.openx.ui.screen

import com.wy.openx.data.model.ConversationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧边栏/历史页「相对时间 · 模型名」副标题的回归测试。
 *
 * 修的是：副标题原来读 Room 里那份**建会话时写死、之后永不更新**的 meta，
 * 导致会话级模式下选了模型侧边栏仍一直显示「[未选择模型]」。
 * 这里锁住改成实时派生后的语义，防止未来又被改回去读静态字段。
 */
class ConversationMetaLabelTest {

    private fun conv(model: String?, lastActiveAt: Long = System.currentTimeMillis()) =
        ConversationModel(id = 1, title = "t", meta = "刚刚 · [未选择模型]", model = model, lastActiveAt = lastActiveAt)

    @Test
    fun `会话级模式下选了模型应显示该模型而不是未选择`() {
        val label = conversationMetaLabel(conv("deepseek-chat"), sessionMode = true, globalModel = "gpt-4o")
        // 关键回归点：绝不能因为 meta 里存着旧值就显示 [未选择模型]
        assertTrue("应显示本会话模型，实际=$label", label.contains("deepseek-chat"))
        assertTrue(label.contains("刚刚"))
    }

    @Test
    fun `会话级模式下未选模型显示未选择`() {
        val label = conversationMetaLabel(conv(null), sessionMode = true, globalModel = "gpt-4o")
        assertTrue("实际=$label", label.contains("[未选择模型]"))
    }

    @Test
    fun `会话级模式下空串等同未选`() {
        val label = conversationMetaLabel(conv(""), sessionMode = true, globalModel = "gpt-4o")
        assertTrue("实际=$label", label.contains("[未选择模型]"))
    }

    @Test
    fun `全局模式下跟随全局当前模型`() {
        // 全局模式不看会话自己的 model 列，一律用全局当前模型
        val label = conversationMetaLabel(conv("deepseek-chat"), sessionMode = false, globalModel = "gpt-4o")
        assertTrue("实际=$label", label.contains("gpt-4o"))
        assertTrue("实际=$label", !label.contains("deepseek-chat"))
    }

    @Test
    fun `全局模式全局模型为空显示未选择`() {
        val label = conversationMetaLabel(conv("x"), sessionMode = false, globalModel = "")
        assertTrue("实际=$label", label.contains("[未选择模型]"))
    }

    @Test
    fun `旧会话无时间戳时不显示时间前缀`() {
        // lastActiveAt = 0 是升级前的旧数据（该列 Room 9→10 才加），没有可用时间
        val label = conversationMetaLabel(conv("m", lastActiveAt = 0L), sessionMode = true, globalModel = "")
        assertEquals("m", label)
    }

    @Test
    fun `相对时间边界`() {
        val now = System.currentTimeMillis()
        assertTrue(conversationMetaLabel(conv("m", now - 30_000), true, "").startsWith("刚刚"))
        assertTrue(conversationMetaLabel(conv("m", now - 5 * 60_000), true, "").contains("5 分钟前"))
        assertTrue(conversationMetaLabel(conv("m", now - 2 * 3_600_000), true, "").contains("2 小时前"))
        assertTrue(conversationMetaLabel(conv("m", now - 3 * 86_400_000L), true, "").contains("3 天前"))
    }
}
