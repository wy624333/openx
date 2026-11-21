package com.wy.openx.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 聊天区 Markdown 链接的目标解析：区分「工作区文件」与「网址」，并拆 `:行号`。
 *
 * 回归重点：旧实现把非 http(s) 的目标一律拼成 `https://`，于是 AI 输出的
 * `src/Main.kt:42` 会被拼成 `https://src/Main.kt:42` 这种打不开的地址。
 */
class ChatLinkResolverTest {

    // ---------- 判为网址（交给系统） ----------

    @Test
    fun `http 与 https 判为网址`() {
        assertNull(asChatFilePath("http://example.com"))
        assertNull(asChatFilePath("https://example.com/a.kt"))
    }

    @Test
    fun `mailto 等无斜杠 scheme 判为网址`() {
        assertNull(asChatFilePath("mailto:a@b.com"))
        assertNull(asChatFilePath("tel:123456"))
    }

    @Test
    fun `file scheme 归一化为裸路径`() {
        assertEquals("/root/workspace/a.kt", asChatFilePath("file:///root/workspace/a.kt"))
    }

    @Test
    fun `file url 百分号解码`() {
        assertEquals("/root/workspace/a b.kt", asChatFilePath("file:///root/workspace/a%20b.kt"))
    }

    // ---------- 判为文件（交给编辑器） ----------

    @Test
    fun `容器绝对路径判为文件`() {
        assertEquals("/root/workspace/src/Main.kt", asChatFilePath("/root/workspace/src/Main.kt"))
    }

    @Test
    fun `波浪号路径判为文件`() {
        assertEquals("~/workspace/a.kt", asChatFilePath("~/workspace/a.kt"))
    }

    @Test
    fun `相对路径判为文件`() {
        assertEquals("src/Main.kt", asChatFilePath("src/Main.kt"))
    }

    @Test
    fun `带行号的文件名判为文件而非网址`() {
        // 关键回归：scheme 语法允许 `.`，`notes.md:12` 与 `mailto:` 语法上难分，
        // 必须靠白名单枚举而不是「有冒号即网址」。
        assertEquals("notes.md:12", asChatFilePath("notes.md:12"))
    }

    @Test
    fun `空串判为网址(返回null不接管)`() {
        assertNull(asChatFilePath("   "))
    }

    // ---------- 拆行号 ----------

    @Test
    fun `无行号时行号为0`() {
        assertEquals("/root/a.kt" to 0, splitPathAndLine("/root/a.kt"))
    }

    @Test
    fun `拆出冒号行号`() {
        assertEquals("/root/a.kt" to 42, splitPathAndLine("/root/a.kt:42"))
    }

    @Test
    fun `拆出锚点行号`() {
        assertEquals("/root/a.kt" to 42, splitPathAndLine("/root/a.kt#L42"))
    }

    @Test
    fun `冒号行号后带锚点也能拆出`() {
        assertEquals("/root/a.kt" to 42, splitPathAndLine("/root/a.kt:42#L42"))
    }

    @Test
    fun `相对路径带行号`() {
        assertEquals("src/Main.kt" to 7, splitPathAndLine("src/Main.kt:7"))
    }

    @Test
    fun `行号非数字时按无行号处理`() {
        // `foo:bar` 不是行号，整串当路径（避免把路径名截断）
        assertEquals("foo:bar" to 0, splitPathAndLine("foo:bar"))
    }

    @Test
    fun `路径为空时忽略行号`() {
        // `:42` 去掉冒号部分路径为空 → 当作完整路径，不返回空路径
        assertEquals(":42" to 0, splitPathAndLine(":42"))
    }

    @Test
    fun `带空格与fragment的路径`() {
        assertEquals("/root/my file.kt" to 3, splitPathAndLine("/root/my file.kt:3#section"))
    }
}