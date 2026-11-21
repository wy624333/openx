package com.wy.openx.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 `file_manager` 漏传 `action` 的兜底推断。
 *
 * 背景见 [FileManagerActionResolver]：线上 47 次调用有 28 次漏 `action`，
 * 全部返回 `错误：未指定操作`，模型无法自我纠正。
 */
class FileManagerActionResolverTest {

    private fun use(rawAction: String?, path: String? = null, content: String? = null) =
        FileManagerActionResolver.resolve(
            rawAction = rawAction,
            path = path,
            hasContent = content != null,
            pathExists = false,
            pathIsDirectory = false
        ) as FileManagerActionResolver.Result.Use

    // ---------- 正常给了 action：原样使用，不改写 ----------

    @Test
    fun `给了合法 action 就原样用`() {
        assertEquals("delete", use("delete", "/root/workspace/a.md").action)
    }

    @Test
    fun `action 大小写不规范也能认`() {
        assertEquals("read", use("READ", "/root/workspace/a.md").action)
    }

    @Test
    fun `action 带空格也能认`() {
        assertEquals("list", use("  list  ", "/root/workspace/").action)
    }

    @Test
    fun `显式给了 action 时不标记为推断`() {
        assertEquals(false, use("read", "/root/workspace/a.md").inferred)
    }

    // ---------- 非法 action：报错要带可用值 ----------

    @Test
    fun `非法 action 被拒绝且列出全部可用值`() {
        val r = FileManagerActionResolver.resolve(
            rawAction = "rm", path = "/root/workspace/a.md",
            hasContent = false, pathExists = true, pathIsDirectory = false
        ) as FileManagerActionResolver.Result.Reject
        assertTrue(r.message.contains("rm"))
        for (a in FileManagerActionResolver.ACTIONS) {
            assertTrue("报错里应列出 $a", r.message.contains(a))
        }
    }

    // ---------- 漏传 action：按参数推断 ----------

    @Test
    fun `带了 content 推断为 write`() {
        val r = use(null, "/root/workspace/a.md", content = "hello")
        assertEquals("write", r.action)
        assertTrue(r.inferred)
    }

    @Test
    fun `路径以斜杠结尾推断为 list`() {
        assertEquals("list", use(null, "/root/workspace/projects/xingde/").action)
    }

    @Test
    fun `已存在的目录推断为 list`() {
        val r = FileManagerActionResolver.resolve(
            rawAction = null, path = "/root/workspace/projects",
            hasContent = false, pathExists = true, pathIsDirectory = true
        ) as FileManagerActionResolver.Result.Use
        assertEquals("list", r.action)
    }

    @Test
    fun `已存在的文件推断为 read`() {
        val r = FileManagerActionResolver.resolve(
            rawAction = null, path = "/root/workspace/a.md",
            hasContent = false, pathExists = true, pathIsDirectory = false
        ) as FileManagerActionResolver.Result.Use
        assertEquals("read", r.action)
    }

    @Test
    fun `不存在的路径推断为 read 以便回显带路径的报错`() {
        assertEquals("read", use(null, "/root/workspace/nope.md").action)
    }

    @Test
    fun `content 优先于路径形态`() {
        // 同时带 content 和以 / 结尾的路径：写文件意图更强
        assertEquals("write", use(null, "/root/workspace/x/", content = "d").action)
    }

    // ---------- 兜底失败 ----------

    @Test
    fun `action 与 path 都没有时拒绝并给出可用值`() {
        val r = FileManagerActionResolver.resolve(
            rawAction = null, path = null,
            hasContent = false, pathExists = false, pathIsDirectory = false
        ) as FileManagerActionResolver.Result.Reject
        assertTrue(r.message.contains("未指定 action"))
        assertTrue(r.message.contains("read"))
    }

    @Test
    fun `空字符串 action 等同于没传`() {
        assertEquals("read", use("", "/root/workspace/a.md").action)
    }

    // ---------- notice 文案 ----------

    @Test
    fun `推断时给出提示 显式时不给`() {
        assertTrue(FileManagerActionResolver.notice("read", true, null)!!.contains("read"))
        assertEquals(null, FileManagerActionResolver.notice("read", false, "read"))
    }
}
