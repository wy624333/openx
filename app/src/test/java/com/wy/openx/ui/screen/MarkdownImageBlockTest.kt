package com.wy.openx.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * markdown 图片块识别。
 *
 * 背景：生图模型返回 `![image](https://...)`，原先正文里只会渲染出「[图片: image]」纯文字。
 * 修复靠 [parseMarkdown] 把「独占一行的图片」提成 [BlockType.IMAGE]。
 */
class MarkdownImageBlockTest {

    private fun imageBlocks(md: String) = parseMarkdown(md).filter { it.type == BlockType.IMAGE }

    @Test
    fun `独占一行的远程图片成为图片块`() {
        val blocks = imageBlocks("![image](https://p3-flow-image.cdn/x.png)")
        assertEquals(1, blocks.size)
        assertEquals("https://p3-flow-image.cdn/x.png", blocks[0].imageUrl)
        assertEquals("image", blocks[0].imageAlt)
    }

    @Test
    fun `图片夹在文字中间时也独立成块且不吞掉正文`() {
        val all = parseMarkdown("这是正文\n![image](https://a/b.png)\n还是正文")
        val types = all.map { it.type }
        assertTrue("图片应成块，实际=$types", types.contains(BlockType.IMAGE))
        // 三段都要在：前文、图片、后文
        val paras = all.filter { it.type == BlockType.PARAGRAPH }.map { it.content }
        assertEquals(listOf("这是正文", "还是正文"), paras)
    }

    @Test
    fun `多张图片各自成块`() {
        val blocks = imageBlocks("![a](https://a.png)\n![b](https://b.png)")
        assertEquals(listOf("https://a.png", "https://b.png"), blocks.map { it.imageUrl })
        assertEquals(listOf("a", "b"), blocks.map { it.imageAlt })
    }

    @Test
    fun `无 alt 的图片也能识别且 alt 为空串`() {
        val blocks = imageBlocks("![](https://a.png)")
        assertEquals(1, blocks.size)
        assertEquals("", blocks[0].imageAlt)
    }

    @Test
    fun `图片与文字同行不算独占行 不成块`() {
        // 混排仍退化成 alt 文本（renderInline 路径），这里守住「不成块」这个前提
        assertTrue(imageBlocks("看图 ![image](https://a.png) 好看").isEmpty())
    }

    @Test
    fun `代码块里的图片语法不被当成图片块`() {
        val blocks = parseMarkdown("```\n![image](https://a.png)\n```")
        assertTrue(imageBlocks("```\n![image](https://a.png)\n```").isEmpty())
        assertEquals(1, blocks.size)
        assertEquals(BlockType.CODE, blocks[0].type)
    }

    @Test
    fun `行内代码里的图片语法不被当成图片块`() {
        assertTrue(imageBlocks("`![image](https://a.png)`").isEmpty())
    }
}
