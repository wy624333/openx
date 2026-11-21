package com.wy.openx.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class RepoDisplayPathTest {

    private val root = "/root/workspace/projects/xingde"

    @Test
    fun `工作区根本身只显示工作区名`() {
        assertEquals("xingde", repoDisplayPath(root, root))
    }

    @Test
    fun `子目录仓库显示为工作区名加子目录名`() {
        assertEquals("xingde/git1", repoDisplayPath("$root/git1", root))
    }

    @Test
    fun `多层子路径保留层级`() {
        assertEquals("xingde/a/b", repoDisplayPath("$root/a/b", root))
    }

    @Test
    fun `工作区根带尾斜杠时仍能正确截取`() {
        assertEquals("xingde/git1", repoDisplayPath("$root/git1", "$root/"))
    }

    @Test
    fun `路径不含工作区根时回退为末段`() {
        // 切项目瞬间选中路径可能属于旧项目，不应拼出误导性的 xingde/tmp/other
        assertEquals("other", repoDisplayPath("/tmp/other", root))
    }

    @Test
    fun `前缀相似但不属于本工作区的路径不应误判`() {
        // /root/workspace/projects/xingde2 不是 xingde 的子目录
        assertEquals("xingde2", repoDisplayPath("/root/workspace/projects/xingde2", root))
    }

    @Test
    fun `工作区根为空时回退为末段`() {
        assertEquals("git1", repoDisplayPath("/a/git1", ""))
    }
}
