package com.wy.openx.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截断时回给模型的提示文本。
 *
 * 重点：**必须给出可执行的下一步**（分块方案），否则模型会原样重试同一次超大写入、再次被截断，
 * 陷入「长文件写不进去、反复报错」的死循环。
 */
class TruncatedWriteHintTest {

    @Test
    fun `明确说明没有写入任何内容`() {
        // 用户/AI 必须知道"这次没写成"，否则会以为文件已是半成品
        val hint = buildTruncatedWriteHint("writeFile")
        assertTrue(hint.contains("截断"))
        assertTrue(hint.contains("没有写入任何内容"))
    }

    @Test
    fun `writeFile 提示分块方案与 appendFile`() {
        val hint = buildTruncatedWriteHint("writeFile")
        assertTrue(hint.contains("appendFile"))
        assertTrue(hint.contains("分块写入"))
        // 必须明确"不要再用 writeFile 一次性写"
        assertTrue(hint.contains("不要再用 writeFile"))
    }

    @Test
    fun `appendFile 提示拆小上限`() {
        val hint = buildTruncatedWriteHint("appendFile")
        assertTrue(hint.contains("appendFile"))
        assertTrue(hint.contains("拆小"))
    }

    @Test
    fun `编辑类工具提示拆成多次编辑`() {
        for (t in listOf("editFile", "batch_edit", "batch_replace")) {
            val hint = buildTruncatedWriteHint(t)
            assertTrue(hint.contains("多次") || hint.contains("拆成"))
        }
    }

    @Test
    fun `未知工具给出兜底建议`() {
        val hint = buildTruncatedWriteHint("someTool")
        assertTrue(hint.contains("拆小") || hint.contains("分多次"))
    }

    @Test
    fun `提示里带上了工具名`() {
        assertTrue(buildTruncatedWriteHint("writeFile").contains("writeFile"))
        assertTrue(buildTruncatedWriteHint("appendFile").contains("appendFile"))
    }
}