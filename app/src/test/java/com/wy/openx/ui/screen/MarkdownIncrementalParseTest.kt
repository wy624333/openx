package com.wy.openx.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式增量解析必须与全量解析逐块一致。
 *
 * 长思考链流式下每帧都全量重解析整篇文本（含 `split("\n")` 的整篇行数组分配）是页面卡顿
 * 主因，故 [parseMarkdownState] 会复用上一轮稳定边界之前的块、只重解析尾部。省掉开销的
 * 代价是引入「增量 ≠ 全量」的风险：一旦某类文本的边界判定不成立，表现就是聊天内容错乱
 * 或丢块。本测试用逐字追加模拟流式，对每个中间态同时跑增量与全量两条路径比对。
 */
class MarkdownIncrementalParseTest {

    /** 按 step 个字符递增地喂给增量解析，每个中间态都与全量解析比对。 */
    private fun assertStreamMatchesFull(text: String, step: Int) {
        var state: MarkdownParseState? = null
        var len = 0
        while (len < text.length) {
            len = minOf(len + step, text.length)
            val partial = text.substring(0, len)
            state = parseMarkdownState(partial, state)
            assertEquals(
                "前 $len 字符的增量结果应与全量解析一致：\n${partial.take(120)}",
                parseMarkdown(partial),
                state.blocks
            )
        }
    }

    @Test
    fun `逐字追加与全量解析一致`() {
        val samples = listOf(
            // 段落 + 空行（最常见的思考形态）
            "第一段思考内容。\n\n第二段思考内容。\n\n第三段还在继续写。\n",
            // 列表
            "- 先看看这个文件\n- 再看那个\n\n1. 第一步\n2. 第二步\n",
            // 标题、引用、分隔线
            "# 标题\n\n> 引用一句\n\n---\n\n正文收尾\n",
            // 代码块：未闭合时会一路消费到末尾，闭合后边界才推进
            "开头说明\n\n```kotlin\nfun a() = 1\nval b = 2\n```\n\n结尾说明\n",
            // 表格：靠「下一行含 ---」判定，表头后面暂无行时不能提前定形
            "| 名称 | 值 |\n| --- | --- |\n| a | 1 |\n| b | 2 |\n",
            // 独占一行的图片
            "看图\n\n![image](https://a/b.png)\n\n继续\n",
            // 尾部无换行 + 连续多个空行
            "甲\n\n\n\n乙",
            // 以换行结尾（切分会多出末尾空元素，边界不能算到它之后）
            "甲\n\n乙\n",
            // 超长无空行段落（崩溃场景：LineBreaker OOM）应被切段，且与全量一致
            (1..300).joinToString("\n") { "第${it}步：计算 $it × $it = ${it * it}，中间结论需要记下来。" }
        )
        for (s in samples) {
            assertStreamMatchesFull(s, 1)
            assertStreamMatchesFull(s, 3)
            assertStreamMatchesFull(s, 7)
        }
    }

    @Test
    fun `增量确实复用前缀块而不是每次全量重解`() {
        // 增量若因边界判定失败而退回全量，开销归零却看不出来，这里直接钉住「复用」这个行为
        val head = (1..200).joinToString("\n\n") { "第 $it 段思考内容，足够长以跨过稳定边界。" }
        val first = "$head\n\n收尾段开头"
        val state = parseMarkdownState(first, null)
        assertTrue("应至少定形一个可复用边界", state.cut > 0)
        assertTrue("复用段应含多个块", state.stableBlocks > 1)

        val next = parseMarkdownState(first + "继续写。\n\n新一段", state)
        // 同一实例才说明真的拿走了旧块（数据类 equals 会让全量重解也「通过」）
        for (k in 0 until state.stableBlocks) {
            assertSame(state.blocks[k], next.blocks[k])
        }
        assertEquals(parseMarkdown(first + "继续写。\n\n新一段"), next.blocks)
    }

    @Test
    fun `超长无空行段落被切段且不丢行`() {
        // 崩溃场景：无空行连续多行的长思考整段合并成一个 PARAGRAPH，
        // 渲染时一份 StaticLayout 把 LineBreaker 撑到 OOM（LineBreaker$Builder.build）。
        // 每行 40 字 × 300 行 ≈ 12000 字符，远超 MAX_PARAGRAPH_CHARS(4000)，应切成 ≥3 块。
        val long = (1..300).joinToString("\n") { "第${it}步：计算 $it × $it = ${it * it}，中间结论需要记下来。" }
        val blocks = parseMarkdown(long)
        assertEquals("短段落不受切段影响", 1, parseMarkdown(long.substring(0, 2000)).size)
        assertTrue("长段落应被切成多块，实际 ${blocks.size}", blocks.size >= 3)
        assertTrue(blocks.all { it.content.length <= 4000 })
        // 切段后拼回必须还原原文（不丢行、不重排）
        assertEquals(long, blocks.joinToString("\n") { it.content })
    }

    @Test
    fun `带空行的正常多段文本不受切段影响`() {
        val text = (1..50).joinToString("\n\n") { "第${it}段：正常长度。" }
        val blocks = parseMarkdown(text)
        assertEquals(50, blocks.size)
    }
}
