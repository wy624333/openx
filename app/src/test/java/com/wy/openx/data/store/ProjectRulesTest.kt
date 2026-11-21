package com.wy.openx.data.store

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `AGENTS.md` / `CLAUDE.md` 读取：优先级、兜底、截断与缓存失效。
 *
 * 这块是纯文件 IO，不依赖 Android 资源，故直接跑 JVM 单测（没有 Robolectric）。
 */
class ProjectRulesTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("openx-rules-").toFile()
        ProjectRules.clearCache()
    }

    private fun write(name: String, text: String) {
        File(dir, name).writeText(text)
    }

    @Test
    fun `AGENTS 优先于 CLAUDE`() {
        write("AGENTS.md", "agents 内容")
        write("CLAUDE.md", "claude 内容")
        val doc = ProjectRules.read(dir, "项目")
        assertEquals("AGENTS.md", doc?.fileName)
        assertEquals("agents 内容", doc?.text?.trim())
        assertEquals("项目", doc?.label)
    }

    @Test
    fun `没有 AGENTS 时回退 CLAUDE`() {
        write("CLAUDE.md", "claude 内容")
        assertEquals("CLAUDE.md", ProjectRules.read(dir, "全局")?.fileName)
    }

    @Test
    fun `目录不存在或文件为空返回 null`() {
        assertNull(ProjectRules.read(dir, "项目"))
        assertNull(ProjectRules.read(null, "项目"))
        write("AGENTS.md", "   \n  ")
        assertNull(ProjectRules.read(dir, "项目"))
    }

    @Test
    fun `超长规则截断并标注`() {
        write("AGENTS.md", "x".repeat(ProjectRules.MAX_CHARS + 500))
        val text = ProjectRules.read(dir)?.text.orEmpty()
        assertTrue(text.length < ProjectRules.MAX_CHARS + 100)
        assertTrue(text.endsWith("…（规则过长，已截断）"))
    }

    @Test
    fun `文件修改后缓存失效，读到新内容`() {
        write("AGENTS.md", "旧内容")
        assertEquals("旧内容", ProjectRules.read(dir)?.text)
        // 明确改长度 + 时间，避免同毫秒写入导致 (mtime,length) 未变
        File(dir, "AGENTS.md").writeText("新内容更长了")
        File(dir, "AGENTS.md").setLastModified(System.currentTimeMillis() + 1_000)
        assertEquals("新内容更长了", ProjectRules.read(dir)?.text)
    }
}
