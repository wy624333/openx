package com.wy.openx.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell 命令静态分析器：分段、引号感知、不可静态判定的降级。
 *
 * 这些判定是授权系统的安全核心——任何误判都可能导致「本该弹窗的命令被自动放行」，
 * 故覆盖正向与反向两类用例。
 */
class ShellCommandParserTest {

    private fun analyze(command: String) = ShellCommandParser.analyze(command)

    // ---------- 分段 ----------

    @Test
    fun `单命令单段`() {
        val a = analyze("ls -la")
        assertEquals(1, a.segments.size)
        assertEquals(listOf("ls", "-la"), a.segments[0])
    }

    @Test
    fun `管道分多段`() {
        val a = analyze("cat a.txt | grep foo | wc -l")
        assertEquals(3, a.segments.size)
        assertEquals(listOf("cat", "a.txt"), a.segments[0])
        assertEquals(listOf("grep", "foo"), a.segments[1])
    }

    @Test
    fun `与或分号分多段`() {
        assertEquals(2, analyze("ls && pwd").segments.size)
        assertEquals(2, analyze("ls ; pwd").segments.size)
        assertEquals(2, analyze("ls & pwd").segments.size)
    }

    @Test
    fun `引号内的分隔符不切段`() {
        val a = analyze("echo 'a && b'")
        assertEquals(1, a.segments.size)
        assertEquals(listOf("echo", "a && b"), a.segments[0])
    }

    @Test
    fun `双引号内分隔符不切段`() {
        val a = analyze("echo \"a | b\"")
        assertEquals(1, a.segments.size)
    }

    // ---------- 可静态判定 ----------

    @Test
    fun `普通命令可静态判定`() {
        assertTrue(analyze("git status").analyzable)
        assertTrue(analyze("ls -la /root").analyzable)
    }

    @Test
    fun `相对路径重定向仍可判定`() {
        assertTrue(analyze("echo hi > out.txt").analyzable)
        assertTrue(analyze("echo hi >> out.txt").analyzable)
    }

    @Test
    fun `输入重定向不降级`() {
        assertTrue(analyze("wc -l < in.txt").analyzable)
    }

    @Test
    fun `环境赋值前缀可静态判定且按程序名处理`() {
        val a = analyze("FOO=bar git status")
        assertTrue(a.analyzable)
        assertEquals("git", ShellCommandParser.programPrefix(a.segments[0]))
    }

    // ---------- 不可静态判定（必须降级为弹窗）----------

    @Test
    fun `命令替换不可判定`() {
        assertFalse(analyze("echo $(whoami)").analyzable)
        assertFalse(analyze("echo `whoami`").analyzable)
    }

    @Test
    fun `双引号内命令替换仍不可判定`() {
        assertFalse(analyze("echo \"$(whoami)\"").analyzable)
        assertFalse(analyze("echo \"`whoami`\"").analyzable)
    }

    @Test
    fun `单引号内命令替换是字面量`() {
        // 单引号内不展开，故仍可判定——这正是引号感知的意义
        assertTrue(analyze("echo '$(whoami)'").analyzable)
    }

    @Test
    fun `子shell与分组括号不可判定`() {
        assertFalse(analyze("(cd /tmp && ls)").analyzable)
        assertFalse(analyze("diff <(ls) <(ls)").analyzable)
    }

    @Test
    fun `绝对路径输出重定向不可判定`() {
        assertFalse(analyze("echo x > /etc/passwd").analyzable)
        assertFalse(analyze("echo x >> /tmp/a").analyzable)
    }

    @Test
    fun `引号包裹的绝对路径重定向同样不可判定`() {
        assertFalse(analyze("echo x > '/etc/passwd'").analyzable)
    }

    @Test
    fun `未闭合引号不可判定`() {
        assertFalse(analyze("echo 'abc").analyzable)
        assertFalse(analyze("echo \"abc").analyzable)
    }

    // ---------- 匹配语义 ----------

    @Test
    fun `token前缀匹配`() {
        assertTrue(ShellCommandParser.matches("git status", listOf("git", "status")))
        assertTrue(ShellCommandParser.matches("git status", listOf("git", "status", "-s")))
        assertFalse(ShellCommandParser.matches("git status", listOf("git", "push")))
    }

    @Test
    fun `绝对路径程序名不匹配裸名规则`() {
        // 宁可多问，不误放
        assertFalse(ShellCommandParser.matches("node", listOf("/usr/bin/node", "x.js")))
    }

    @Test
    fun `环境赋值不参与匹配`() {
        assertTrue(ShellCommandParser.matches("git status", listOf("FOO=bar", "git", "status")))
    }

    // ---------- 可记忆前缀 ----------

    @Test
    fun `普通程序记忆程序名`() {
        assertEquals("cat", ShellCommandParser.rememberablePrefix(listOf("cat", "a.txt")))
    }

    @Test
    fun `子命令分发器记到子命令`() {
        assertEquals("git pull", ShellCommandParser.rememberablePrefix(listOf("git", "pull")))
        assertEquals("npm install", ShellCommandParser.rememberablePrefix(listOf("npm", "install")))
    }

    @Test
    fun `分发器后紧跟flag退化为程序名`() {
        assertEquals("git", ShellCommandParser.rememberablePrefix(listOf("git", "--version")))
    }

    @Test
    fun `rm单文件删除可记忆完整命令`() {
        assertEquals("rm build/a.o", ShellCommandParser.rememberablePrefix(listOf("rm", "build/a.o")))
    }

    @Test
    fun `rm递归或通配不可记忆`() {
        assertNull(ShellCommandParser.rememberablePrefix(listOf("rm", "-rf", "build")))
        assertNull(ShellCommandParser.rememberablePrefix(listOf("rm", "-r", "build")))
        assertNull(ShellCommandParser.rememberablePrefix(listOf("rm", "*.log")))
        assertNull(ShellCommandParser.rememberablePrefix(listOf("rm", "--recursive", "x")))
    }

    @Test
    fun `rm无目标不可记忆`() {
        assertNull(ShellCommandParser.rememberablePrefix(listOf("rm")))
    }

    @Test
    fun `rm识别绝对路径形式的程序名`() {
        val info = ShellCommandParser.parseRmInfo(listOf("/bin/rm", "-rf", "x"))
        assertTrue(info.isRm)
        assertTrue(info.isRecursive)
    }
}