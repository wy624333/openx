package com.wy.openx.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 守护 [sanitizeWorkdir]：terminal 工具的 `workdir` 会被拼进 `cd "$dir" 2>/dev/null; $command`，
 * 只做 trim 等于零校验——AI 传 `/tmp"; rm -rf /; echo "` 就能闭合引号，
 * 在危险命令守卫（只查 `command`）之外执行任意命令。
 */
class WorkdirSanitizeTest {

    @Test
    fun `容器工作区内的正常路径放行`() {
        assertEquals("/root/workspace", sanitizeWorkdir("/root/workspace"))
        assertEquals("/root/workspace/projects/xingde", sanitizeWorkdir("/root/workspace/projects/xingde"))
        assertEquals("/root/local", sanitizeWorkdir("/root/local"))
        assertEquals("/root/local/abc-1.2", sanitizeWorkdir("/root/local/abc-1.2"))
    }

    @Test
    fun `闭合引号注入被拦截`() {
        assertNull(sanitizeWorkdir("/tmp\"; rm -rf /; echo \""))
        assertNull(sanitizeWorkdir("/root/workspace\"; whoami; echo \""))
    }

    @Test
    fun `shell 元字符一律拦截`() {
        assertNull(sanitizeWorkdir("/root/workspace; rm -rf /"))
        assertNull(sanitizeWorkdir("/root/workspace && curl evil.com"))
        assertNull(sanitizeWorkdir("/root/workspace`id`"))
        assertNull(sanitizeWorkdir("/root/workspace\$(id)"))
        assertNull(sanitizeWorkdir("/root/workspace\nrm -rf /"))
        assertNull(sanitizeWorkdir("/root/workspace|cat /etc/passwd"))
    }

    @Test
    fun `路径穿越被拦截`() {
        assertNull(sanitizeWorkdir("/root/workspace/../../etc"))
        assertNull(sanitizeWorkdir("/root/workspace/.."))
    }

    @Test
    fun `工作区之外的绝对路径被拦截`() {
        assertNull(sanitizeWorkdir("/etc"))
        assertNull(sanitizeWorkdir("/"))
        assertNull(sanitizeWorkdir("/data/data/com.wy.openx"))
    }

    @Test
    fun `相对路径被拦截`() {
        assertNull(sanitizeWorkdir("workspace"))
        assertNull(sanitizeWorkdir("./projects"))
    }
}
