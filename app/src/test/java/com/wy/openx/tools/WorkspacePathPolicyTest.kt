package com.wy.openx.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「仅工作区修改」档的路径判定。
 *
 * 这里守的主要是那条**回归线**：只扫「路径参数」，不碰「内容参数」。
 * 曾经的实现是 `args.values.filterIsInstance<String>()` 全扫，于是 writeFile 的 content、
 * editFile 的 old_text/new_text、batch_replace 的 replace 里只要出现工作区外的绝对路径，
 * 就被判成「工作区外」而弹窗——用户正常写代码时莫名被拦。
 */
class WorkspacePathPolicyTest {

    // ===== 单路径 =====

    @Test
    fun `工作区根及其子路径都算工作区内`() {
        assertTrue(WorkspacePathPolicy.isWithin("/root/workspace"))
        assertTrue(WorkspacePathPolicy.isWithin("/root/workspace/"))
        assertTrue(WorkspacePathPolicy.isWithin("/root/workspace/projects/xingde"))
        assertTrue(WorkspacePathPolicy.isWithin("/root/local/mydir"))
    }

    @Test
    fun `工作区外与形近路径都判为区外`() {
        assertFalse(WorkspacePathPolicy.isWithin("/root"))
        assertFalse(WorkspacePathPolicy.isWithin("/tmp"))
        assertFalse(WorkspacePathPolicy.isWithin("/root/workspace-other"))
        assertFalse(WorkspacePathPolicy.isWithin("/root/workspacex/a"))
        assertFalse(WorkspacePathPolicy.isWithin("/system/bin"))
    }

    // ===== 列表判定 =====

    @Test
    fun `相对路径放行 绝对路径越界拒绝 波浪号拒绝`() {
        assertTrue(WorkspacePathPolicy.allWithin(listOf("src/main.kt", "a/b/c")))
        assertTrue(WorkspacePathPolicy.allWithin(listOf("/root/workspace/a.md", "-rf", "ls")))
        assertFalse(WorkspacePathPolicy.allWithin(listOf("/root/workspace/a.md", "/tmp/b")))
        assertFalse(WorkspacePathPolicy.allWithin(listOf("~/.gitconfig")))
        assertTrue(WorkspacePathPolicy.allWithin(emptyList()))
    }

    // ===== 参数收集：核心回归点 =====

    @Test
    fun `writeFile 只认 path content 里的工作区外路径不参与判定`() {
        val args = mapOf(
            "path" to "/root/workspace/notes.md",
            "content" to "#!/usr/bin/env python3\n# 见 /etc/hosts 与 /api/v1/users\nprint(1)"
        )
        assertEquals(listOf("/root/workspace/notes.md"), WorkspacePathPolicy.collectPathArgs(args))
        assertTrue(WorkspacePathPolicy.allWithin(WorkspacePathPolicy.collectPathArgs(args)))
    }

    @Test
    fun `writeFile 的 path 越界仍会被拦下`() {
        val args = mapOf("path" to "/etc/hosts", "content" to "x")
        assertFalse(WorkspacePathPolicy.allWithin(WorkspacePathPolicy.collectPathArgs(args)))
    }

    @Test
    fun `editFile 的 edits 只取 path 代码片段里的路径不算`() {
        val edits = listOf(
            mapOf(
                "path" to "/root/workspace/A.kt",
                "old_text" to "val p = \"/usr/bin\"",
                "new_text" to "val p = \"/bin\""
            ),
            mapOf("path" to "/root/workspace/B.kt", "old_text" to "a", "new_text" to "b")
        )
        val args = mapOf("path" to "/root/workspace/A.kt", "edits" to edits)
        val paths = WorkspacePathPolicy.collectPathArgs(args)
        assertEquals(
            listOf("/root/workspace/A.kt", "/root/workspace/A.kt", "/root/workspace/B.kt"),
            paths
        )
        assertTrue(WorkspacePathPolicy.allWithin(paths))
    }

    @Test
    fun `batch_replace 只认 dir search 与 replace 里的路径不算`() {
        val args = mapOf(
            "operation" to "replace",
            "search" to "/old/path/here",
            "replace" to "/new/path/there",
            "dir" to "/root/workspace/projects"
        )
        assertEquals(listOf("/root/workspace/projects"), WorkspacePathPolicy.collectPathArgs(args))
        assertTrue(WorkspacePathPolicy.allWithin(WorkspacePathPolicy.collectPathArgs(args)))
    }

    @Test
    fun `没有路径参数的工具一律放行`() {
        // skill_add 的 instructions 是长文本，可能含任意路径
        val args = mapOf("name" to "x", "instructions" to "见 /usr/share/doc 与 /etc")
        assertTrue(WorkspacePathPolicy.collectPathArgs(args).isEmpty())
        assertTrue(WorkspacePathPolicy.allWithin(WorkspacePathPolicy.collectPathArgs(args)))
    }

    @Test
    fun `git 的 dir 参与判定 message 不参与`() {
        val ok = mapOf(
            "operation" to "commit",
            "dir" to "/root/workspace/projects/x",
            "message" to "fix /usr/local/bin 路径"
        )
        assertTrue(WorkspacePathPolicy.allWithin(WorkspacePathPolicy.collectPathArgs(ok)))

        val bad = mapOf("operation" to "status", "dir" to "/etc")
        assertFalse(WorkspacePathPolicy.allWithin(WorkspacePathPolicy.collectPathArgs(bad)))
    }

    // ===== shell 命令 =====

    @Test
    fun `shell 命令按 token 挑出绝对路径`() {
        assertTrue(
            WorkspacePathPolicy.allWithin(
                WorkspacePathPolicy.commandPaths("cd /root/workspace && npm run build")
            )
        )
        assertFalse(
            WorkspacePathPolicy.allWithin(
                WorkspacePathPolicy.commandPaths("cp /root/workspace/a /tmp/b")
            )
        )
        assertFalse(
            WorkspacePathPolicy.allWithin(
                WorkspacePathPolicy.commandPaths("rm -rf /system/bin/x")
            )
        )
        // 全相对路径：目标由 cwd 决定，放行
        assertTrue(
            WorkspacePathPolicy.allWithin(
                WorkspacePathPolicy.commandPaths("npm run build && ls -la")
            )
        )
    }
}
