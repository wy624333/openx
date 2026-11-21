package com.wy.openx.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChatUtils.removeThinkingContent` 与 `modelKeepsThinking` 的行为锁定。
 *
 * ## 为什么需要这两条
 * 发给模型的历史里，assistant 正文可能内联 `<thinking>`。发请求前必须按**模型**决定
 * 剥不剥：DeepSeek 思考模式要求 assistant(tool_calls) 回传 reasoning，剥了会 400；
 * 其余模型剥掉以避免污染与多余 token（对齐参考项目 AiCode 1.12 的 OpenAIAdapter）。
 *
 * 这是「工具循环内投影」与「回合起点投影」共用的判据，判错会导致：
 *  - 该剥没剥 → 上下文虚高、部分模型 400；
 *  - 该留没留 → DeepSeek 工具轮回传报 400。
 */
class ChatUtilsThinkingTest {

    @Test
    fun `剥掉闭合的 thinking 块`() {
        val out = ChatUtils.removeThinkingContent("<thinking>先想一下</thinking>你好")
        assertEquals("你好", out)
    }

    @Test
    fun `剥掉未闭合的 thinking 块（流式残留）`() {
        val out = ChatUtils.removeThinkingContent("正文<thinking>还没想完")
        assertEquals("正文", out)
    }

    @Test
    fun `剥掉带随机后缀的 think 标签（混元风格）`() {
        val out = ChatUtils.removeThinkingContent("<think:6124c78e>思考</think:6124c78e>结果")
        assertEquals("结果", out)
    }

    @Test
    fun `剥掉孤立的闭合标签（噪声）`() {
        val out = ChatUtils.removeThinkingContent("结果</thinking>")
        assertEquals("结果", out)
    }

    @Test
    fun `剥掉 search 块`() {
        val out = ChatUtils.removeThinkingContent("<search>查询</search>答案")
        assertEquals("答案", out)
    }

    @Test
    fun `无标签时原样返回`() {
        assertEquals("普通回复", ChatUtils.removeThinkingContent("普通回复"))
    }

    @Test
    fun `DeepSeek 系模型保留思考（不剥）`() {
        assertTrue(ChatUtils.modelKeepsThinking("deepseek-chat"))
        assertTrue(ChatUtils.modelKeepsThinking("deepseek-reasoner"))
        assertTrue(ChatUtils.modelKeepsThinking("DeepSeek-V3"))
        assertTrue(ChatUtils.modelKeepsThinking("deepseek-ai/DeepSeek-R1"))
    }

    @Test
    fun `推理模型名含 reasoner 或 thinking 时保留`() {
        assertTrue(ChatUtils.modelKeepsThinking("some-reasoner-model"))
        assertTrue(ChatUtils.modelKeepsThinking("xxx-thinking-preview"))
    }

    @Test
    fun `OpenAI o 系推理模型保留`() {
        assertTrue(ChatUtils.modelKeepsThinking("o1-mini"))
        assertTrue(ChatUtils.modelKeepsThinking("o3-mini"))
        assertTrue(ChatUtils.modelKeepsThinking("o4-mini"))
    }

    @Test
    fun `普通对话模型不保留（走剥离）`() {
        assertFalse(ChatUtils.modelKeepsThinking("gpt-4o"))
        assertFalse(ChatUtils.modelKeepsThinking("qwen3-27b"))
        assertFalse(ChatUtils.modelKeepsThinking("claude-3-5-sonnet"))
        assertFalse(ChatUtils.modelKeepsThinking("stealth/space-bunny-alpha"))
    }
}

/**
 * 正则**预编译**的回归（性能）。
 *
 * ## 为什么单独锁
 *
 * 这些 pattern 原先写在 `removeThinkingContent` / `extractThinkingContent` 的**函数体内**，
 * 每次调用都 `toRegex()` 重新编译。而 `groupMessages` 对**每条** assistant 消息要调两次
 * `extractThinkingContent`，且在每次重组（流式每帧）都会重跑整个列表 ——
 * 一个几百条消息、含大段工具输出的会话，每帧要编译上千个正则再全文扫描。
 * 真机表现为「发消息后聊天页显示很慢，等一会才在消息里显示」。
 *
 * 这里用**耗时**做粗判：同一段内容重复调用 2 万次，若仍是每次编译正则，
 * 耗时会显著高于复用预编译常量的实现。阈值取得很宽松（只挡「每帧编译上千次」这个量级），
 * 避免 CI 抖动误报。
 */
class ChatUtilsRegexPerfTest {

    @Test
    fun `重复剥 thinking 不随调用次数线性放大（预编译正则）`() {
        val sample = buildString {
            append("正文开始 ")
            repeat(20) { append("<thinking>思考片段$it</thinking>内容$it ") }
            append("结尾")
        }
        // 预热（JIT）
        repeat(200) { ChatUtils.removeThinkingContent(sample) }
        val t0 = System.nanoTime()
        repeat(20_000) { ChatUtils.removeThinkingContent(sample) }
        val perCallMicros = (System.nanoTime() - t0) / 20_000 / 1000.0
        assertTrue(
            "每次剥 thinking 平均耗时 ${perCallMicros}us，疑似又变回每次编译正则（应在几十微秒内）",
            perCallMicros < 200.0
        )
    }

    @Test
    fun `extractThinkingContent 重复调用同样不放大`() {
        val sample = buildString {
            repeat(20) { append("<thinking>思考$it</thinking>正文$it ") }
        }
        repeat(200) { ChatUtils.extractThinkingContent(sample) }
        val t0 = System.nanoTime()
        repeat(20_000) { ChatUtils.extractThinkingContent(sample) }
        val perCallMicros = (System.nanoTime() - t0) / 20_000 / 1000.0
        assertTrue(
            "每次提取 thinking 平均耗时 ${perCallMicros}us，疑似又变回每次编译正则",
            perCallMicros < 200.0
        )
    }
}
