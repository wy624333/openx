package com.wy.openx.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘目录约定与宿主↔容器路径换算。
 *
 * 这条换算错了的代价很具体：提示语里给错路径，模型用 terminal 去读会「文件不存在」，
 * 然后开始编内容——比直接报错更糟。
 */
class ToolOffloadPathsTest {

    @Test
    fun `容器路径取最后一个 workspace 分段`() {
        val host = "/data/user/0/com.wy.openx/files/workspace/.openx/offloads/12/readFile-ab12cd34.txt"
        assertEquals("/root/workspace/.openx/offloads/12/readFile-ab12cd34.txt", ToolOffloadPaths.containerPathOf(host))
    }

    @Test
    fun `目录名里出现 workspace 也不影响换算`() {
        // filesDir 路径里可能自带 "workspace" 段，必须取**最后一个**
        val host = "/data/user/0/com.wy.openx/workspace/workspace/.openx/offloads/7/t.txt"
        assertEquals("/root/workspace/.openx/offloads/7/t.txt", ToolOffloadPaths.containerPathOf(host))
    }

    @Test
    fun `不在 workspace 下则原样返回`() {
        assertEquals("/sdcard/Download/x.txt", ToolOffloadPaths.containerPathOf("/sdcard/Download/x.txt"))
    }

    @Test
    fun `落盘根目录落在 filesDir 的 workspace 下`() {
        val filesDir = java.io.File("/data/user/0/com.wy.openx/files")
        val root = ToolOffloadPaths.root(filesDir)
        assertEquals(
            listOf("data", "user", "0", "com.wy.openx", "files", "workspace", ".openx", "offloads"),
            root.absolutePath.split("/").filter { it.isNotEmpty() }
        )
        assertEquals(
            listOf("data", "user", "0", "com.wy.openx", "files", "workspace", ".openx", "offloads", "42"),
            ToolOffloadPaths.conversationDir(filesDir, 42L).absolutePath.split("/").filter { it.isNotEmpty() }
        )
    }

    @Test
    fun `工具名清洗掉路径分隔符与奇怪字符`() {
        assertEquals("readFile", ToolOffloadPaths.safeToolName("readFile"))
        assertEquals("a_b-c", ToolOffloadPaths.safeToolName("a_b-c"))
        assertEquals("etcpasswd", ToolOffloadPaths.safeToolName("../../etc/passwd"))
        assertEquals("tool", ToolOffloadPaths.safeToolName("***"))
        assertEquals("tool", ToolOffloadPaths.safeToolName(""))
        // 超长截断
        assertEquals(40, ToolOffloadPaths.safeToolName("n".repeat(200)).length)
    }
}
