package com.wy.openx.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思维链剥离。
 *
 * 背景：上下文压缩是「把整段历史发给模型写摘要」。带思考的模型会连内心独白一起返回，
 * 那段独白若原样写进 `[对话摘要]` 占位消息就**进了模型上下文**——此后每轮都重发给模型，
 * 白烧 token，且模型会把「我上一步在思考什么」当成对话内容；也会直接显示在聊天页
 * （用户实见：恢复后提示里挂着半截 `<thinking>…`）。
 */
class StripThinkingBlocksTest {

    @Test
    fun `完整闭合的 thinking 整段剥掉`() {
        val raw = "<thinking>用户要求压缩这段对话，我按五类信息梳理。</thinking>\n\n## 摘要\n- 核心需求：多会话并行"
        assertEquals("## 摘要\n- 核心需求：多会话并行", stripThinkingBlocks(raw))
    }

    @Test
    fun `未闭合的 thinking 连同其后内容一起丢弃`() {
        // 流被截断 / 网关吞掉收尾标签时就是这个形态。残缺的标签绝不能留在正文里。
        val raw = "摘要开头\n<thinking>我还没写完摘要就被打断了"
        assertEquals("摘要开头", stripThinkingBlocks(raw))
    }

    @Test
    fun `多个 thinking 块全部剥掉`() {
        val raw = "<thinking>a</thinking>正文1<thinking>b</thinking>正文2"
        assertEquals("正文1正文2", stripThinkingBlocks(raw))
    }

    @Test
    fun `think 与 reasoning 变体同样识别`() {
        assertEquals("结论", stripThinkingBlocks("<think>思考</think>结论"))
        assertEquals("结论", stripThinkingBlocks("<reasoning>思考</reasoning>结论"))
    }

    @Test
    fun `标签可含空白与大小写差异`() {
        assertEquals("结论", stripThinkingBlocks("< THINKING >思考</ Thinking >结论"))
        assertEquals("结论", stripThinkingBlocks("<Think>\n思考\n</Think>结论"))
    }

    @Test
    fun `跨行的 thinking 也能整段剥掉`() {
        val raw = "<thinking>\n第一行\n第二行\n</thinking>\n摘要正文"
        assertEquals("摘要正文", stripThinkingBlocks(raw))
    }

    @Test
    fun `没有 thinking 时原样返回`() {
        val raw = "## 摘要\n1. 需求\n2. 决策"
        assertEquals(raw, stripThinkingBlocks(raw))
    }

    @Test
    fun `只有思维链时剥完为空`() {
        // 关键：调用方据此判定「模型没给摘要」→ 按压缩失败处理，
        // 而不是把一段空/残缺内容写进上下文。
        assertTrue(stripThinkingBlocks("<thinking>只有思考没有摘要</thinking>").isEmpty())
        assertTrue(stripThinkingBlocks("   <thinking>  </thinking>  ").isEmpty())
    }

    @Test
    fun `剥离后不残留任何 thinking 标签`() {
        val raw = "<thinking>a</thinking>X<thinking>b"
        val out = stripThinkingBlocks(raw)
        assertFalse("仍有未闭合标签：$out", out.contains("<thinking", ignoreCase = true))
        assertFalse("仍有闭合标签：$out", out.contains("</thinking", ignoreCase = true))
    }
}
