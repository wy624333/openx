package com.wy.openx.plugin.ui

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PluginFileOps] 的行为测试。
 *
 * 这些动作同时被原生 `ctx.*` 和 Operit 兼容层 `Files.*` 复用，一处写错两边都坏，
 * 所以覆盖得细一些：zip 往返、解压越界防护、搜索过滤、unified diff 的行号。
 */
class PluginFileOpsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("plugin-files-ops").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(rel: String, text: String): File =
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    // ------------------------------------------------------------------ move / copy

    @Test
    fun `move 移动文件并保留内容`() {
        val src = write("a.txt", "hello")
        val dst = File(root, "sub/b.txt")

        assertTrue(PluginFileOps.move(src, dst))
        assertFalse(src.exists())
        assertEquals("hello", dst.readText())
    }

    @Test
    fun `move 源不存在时返回 false 而不抛异常`() {
        assertFalse(PluginFileOps.move(File(root, "nope.txt"), File(root, "x.txt")))
    }

    @Test
    fun `copy 目录时递归复制且不动源`() {
        write("dir/one.txt", "1")
        write("dir/nested/two.txt", "2")
        val dst = File(root, "copied")

        assertTrue(PluginFileOps.copy(File(root, "dir"), dst))
        assertEquals("1", File(dst, "one.txt").readText())
        assertEquals("2", File(dst, "nested/two.txt").readText())
        assertTrue(File(root, "dir/one.txt").exists())
    }

    // ------------------------------------------------------------------ zip / unzip

    @Test
    fun `zip 再 unzip 能还原目录结构`() {
        write("src/a.txt", "AAA")
        write("src/sub/b.txt", "BBB")
        val zipFile = File(root, "pack.zip")

        val zipped = PluginFileOps.zip(File(root, "src"), zipFile)
        assertEquals(2, zipped)
        assertTrue(zipFile.length() > 0)

        val out = File(root, "out")
        val unzipped = PluginFileOps.unzip(zipFile, out)
        assertEquals(2, unzipped)
        assertEquals("AAA", File(out, "a.txt").readText())
        assertEquals("BBB", File(out, "sub/b.txt").readText())
    }

    @Test
    fun `zip 单文件时条目名是文件名本身`() {
        val f = write("solo.txt", "x")
        val zipFile = File(root, "solo.zip")
        PluginFileOps.zip(f, zipFile)

        val out = File(root, "unpacked")
        PluginFileOps.unzip(zipFile, out)
        assertTrue(File(out, "solo.txt").isFile)
    }

    @Test(expected = SecurityException::class)
    fun `unzip 拒绝越界条目`() {
        // 手工造一个条目名为 ../evil.txt 的压缩包
        val evil = File(root, "evil.zip")
        java.util.zip.ZipOutputStream(evil.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../evil.txt"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }
        PluginFileOps.unzip(evil, File(root, "target"))
    }

    // ------------------------------------------------------------------ search

    @Test
    fun `search 按扩展名过滤`() {
        write("a.kt", "kotlin")
        write("b.txt", "text")
        write("sub/c.kt", "kotlin2")

        val hits = PluginFileOps.search(root, exts = setOf("kt"))
        assertEquals(setOf("a.kt", "c.kt"), hits.map { it.name }.toSet())
    }

    @Test
    fun `search 按文件名子串匹配且不区分大小写`() {
        write("Report-Final.md", "x")
        write("other.md", "y")

        val hits = PluginFileOps.search(root, nameLike = "report")
        assertEquals(listOf("Report-Final.md"), hits.map { it.name })
    }

    @Test
    fun `search 按内容匹配`() {
        write("hit.txt", "这里有关键字 needle")
        write("miss.txt", "无关内容")

        val hits = PluginFileOps.search(root, contentLike = "NEEDLE")
        assertEquals(listOf("hit.txt"), hits.map { it.name })
    }

    @Test
    fun `search 遵守 limit`() {
        repeat(10) { write("f$it.txt", "x") }
        val hits = PluginFileOps.search(root, exts = setOf("txt"), limit = 3)
        assertEquals(3, hits.size)
    }

    @Test
    fun `search 默认跳过隐藏目录`() {
        write(".hidden/secret.txt", "x")
        write("visible.txt", "y")

        val hits = PluginFileOps.search(root, exts = setOf("txt"))
        assertEquals(listOf("visible.txt"), hits.map { it.name })
    }

    // ------------------------------------------------------------------ diff

    @Test
    fun `diff 相同内容返回空串`() {
        val a = write("a.txt", "1\n2\n3")
        val b = write("b.txt", "1\n2\n3")
        assertEquals("", PluginFileOps.diff(a, b))
    }

    @Test
    fun `diff 输出 unified 格式的增删行`() {
        val a = write("a.txt", "one\ntwo\nthree")
        val b = write("b.txt", "one\nTWO\nthree")

        val d = PluginFileOps.diff(a, b)
        assertTrue(d.startsWith("--- a.txt\n+++ b.txt\n"))
        assertTrue("应包含删除行：$d", d.contains("-two"))
        assertTrue("应包含新增行：$d", d.contains("+TWO"))
    }

    @Test
    fun `diff 在文件末尾追加行时行号正确`() {
        val a = write("a.txt", "1\n2")
        val b = write("b.txt", "1\n2\n3")

        val d = PluginFileOps.diff(a, b)
        assertTrue("应包含 +3：$d", d.contains("+3"))
        // 追加只在 b 侧新增，a 侧计数保持 2
        assertTrue("hunk 头应反映 a 侧 2 行：$d", d.contains("@@ -1,2 +1,3 @@"))
    }

    @Test
    fun `diff 在文件顶部插入行时默认带上下文`() {
        val a = write("a.txt", "x\ny")
        val b = write("b.txt", "new\nx\ny")

        // 默认 ctx=3：上下文行被包含，hunk 头与 diff -u 一致
        val d = PluginFileOps.diff(a, b)
        assertTrue("hunk 头应带上下文：$d", d.contains("@@ -1,2 +1,3 @@"))
        assertTrue("新增行应在上下文之前：$d", d.contains("+new\n x\n y"))
    }

    @Test
    fun `diff 无上下文时顶部插入的 a 起始行号为 0`() {
        val a = write("a.txt", "x\ny")
        val b = write("b.txt", "new\nx\ny")

        // ctx=0 时不带上下文，插入点的 a 侧计数为 0（unified diff 规范）
        val d = PluginFileOps.diff(a, b, context = 0)
        assertTrue("ctx=0 时 a 起始行应为 0：$d", d.contains("@@ -0,0 +1,1 @@"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `diff 拒绝不存在的文件`() {
        PluginFileOps.diff(File(root, "missing.txt"), write("b.txt", "x"))
    }

    // ------------------------------------------------------------------ 边界

    @Test
    fun `zip 源不存在时抛异常`() {
        val e = runCatching { PluginFileOps.zip(File(root, "nope"), File(root, "o.zip")) }
        assertNotNull(e.exceptionOrNull())
        assertTrue(e.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `search 传入文件而非目录时抛异常`() {
        val f = write("file.txt", "x")
        val e = runCatching { PluginFileOps.search(f) }
        assertTrue(e.exceptionOrNull() is IllegalArgumentException)
    }
}
