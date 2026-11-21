package com.wy.openx.tools

import com.wy.openx.tools.ToolPermissionPolicyEngine.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权策略引擎的评估顺序：DENY > 不可判定 > 内置白名单 > 已记忆规则 > ASK。
 */
class ToolPermissionPolicyEngineTest {

    private fun eval(cmd: String, rules: List<PermissionRule> = emptyList()) =
        ToolPermissionPolicyEngine.evaluate("terminal", mapOf("command" to cmd), rules)

    private fun allow(pattern: String) =
        PermissionRule("terminal", pattern, PermissionDecision.ALLOW)

    private fun deny(pattern: String) =
        PermissionRule("terminal", pattern, PermissionDecision.DENY)

    // ---------- 内置白名单直接放行 ----------

    @Test
    fun `只读命令自动放行`() {
        assertEquals(Verdict.ALLOW, eval("ls -la").verdict)
        assertEquals(Verdict.ALLOW, eval("cat a.txt").verdict)
        assertEquals(Verdict.ALLOW, eval("git status").verdict)
        assertEquals(Verdict.ALLOW, eval("grep -r foo .").verdict)
    }

    @Test
    fun `git写操作不放行`() {
        assertEquals(Verdict.ASK, eval("git push").verdict)
        assertEquals(Verdict.ASK, eval("git commit -m x").verdict)
    }

    // ---------- 不可静态判定 → ASK 且不可记忆 ----------

    @Test
    fun `命令替换降级为不可记忆的ASK`() {
        val r = eval("echo $(rm -rf /)")
        assertEquals(Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun `未闭合引号同样不可记忆`() {
        assertEquals(Verdict.ASK, eval("ls '").verdict)
    }

    @Test
    fun `绝对路径重定向不可记忆`() {
        val r = eval("echo x > /etc/passwd")
        assertEquals(Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    // ---------- 已记忆规则 ----------

    @Test
    fun `命中已记忆前缀放行`() {
        assertEquals(Verdict.ALLOW, eval("git pull origin main", listOf(allow("git pull"))).verdict)
    }

    @Test
    fun `已记忆前缀不串到其它子命令`() {
        // 记住 git pull 不能顺带放行 git push —— 这是本次改造的核心安全目标
        assertEquals(Verdict.ASK, eval("git push", listOf(allow("git pull"))).verdict)
    }

    @Test
    fun `多段命令每段都要命中`() {
        // 第一段已授权、第二段没授权 → 整体仍 ASK
        assertEquals(Verdict.ASK, eval("git pull && rm -rf x", listOf(allow("git pull"))).verdict)
    }

    @Test
    fun `DENY优先于内置白名单`() {
        val r = eval("ls -la", listOf(deny("ls")))
        assertEquals(Verdict.DENY, r.verdict)
        assertNotNull(r.denyReason)
    }

    @Test
    fun `DENY优先于已记忆ALLOW`() {
        val r = eval("git status", listOf(allow("git status"), deny("git status")))
        assertEquals(Verdict.DENY, r.verdict)
    }

    // ---------- 可记忆前缀 ----------

    @Test
    fun `ASK时给出可记忆前缀`() {
        val r = eval("git pull")
        assertEquals(Verdict.ASK, r.verdict)
        assertEquals(listOf("git pull"), r.rememberablePatterns)
    }

    @Test
    fun `rm递归删除不可记忆`() {
        val r = eval("rm -rf build")
        assertEquals(Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun `rulesToRemember按前缀建规则`() {
        val r = eval("git pull")
        val rules = ToolPermissionPolicyEngine.rulesToRemember("terminal", r)
        assertEquals(1, rules.size)
        assertEquals("git pull", rules[0].pattern)
        assertEquals(PermissionDecision.ALLOW, rules[0].decision)
    }

    @Test
    fun `不可记忆时不产生规则`() {
        val r = eval("rm -rf x")
        assertTrue(ToolPermissionPolicyEngine.rulesToRemember("terminal", r).isEmpty())
    }

    // ---------- 非 shell 工具 ----------

    @Test
    fun `非shell工具按整工具记忆`() {
        val r = ToolPermissionPolicyEngine.evaluate(
            "writeFile", mapOf("path" to "/a.kt"), emptyList()
        )
        assertEquals(Verdict.ASK, r.verdict)
        assertEquals(listOf(PermissionRuleConst.WHOLE_TOOL), r.rememberablePatterns)
    }

    @Test
    fun `非shell工具整工具ALLOW放行`() {
        val r = ToolPermissionPolicyEngine.evaluate(
            "writeFile", emptyMap(),
            listOf(PermissionRule("writeFile", PermissionRuleConst.WHOLE_TOOL, PermissionDecision.ALLOW))
        )
        assertEquals(Verdict.ALLOW, r.verdict)
    }

    @Test
    fun `非shell工具整工具DENY拒绝`() {
        val r = ToolPermissionPolicyEngine.evaluate(
            "writeFile", emptyMap(),
            listOf(PermissionRule("writeFile", PermissionRuleConst.WHOLE_TOOL, PermissionDecision.DENY))
        )
        assertEquals(Verdict.DENY, r.verdict)
    }

    @Test
    fun `functions前缀被剥离`() {
        assertTrue(ToolPermissionPolicyEngine.isShellTool("functions.terminal"))
        assertFalse(ToolPermissionPolicyEngine.isShellTool("functions.writeFile"))
    }
    // ---------- Shizuku 工具（与 terminal 共用前缀授权） ----------

    @Test
    fun `shizuku与terminal同为按前缀授权的shell工具`() {
        assertTrue(ToolPermissionPolicyEngine.isShellTool("shizuku"))
        assertTrue(ToolPermissionPolicyEngine.isShellTool("functions.shizuku"))
        assertFalse(ToolPermissionPolicyEngine.isShellTool("git"))
    }

    @Test
    fun `shizuku走同一套前缀评估与记忆且规则不跨工具`() {
        val r = ToolPermissionPolicyEngine.evaluate(
            "shizuku", mapOf("command" to "pm install /tmp/a.apk"), emptyList()
        )
        assertEquals(Verdict.ASK, r.verdict)
        assertEquals(listOf("pm"), r.rememberablePatterns)

        val rules = ToolPermissionPolicyEngine.rulesToRemember("shizuku", r)
        assertEquals("shizuku", rules.single().toolName)
        assertEquals(
            Verdict.ALLOW,
            ToolPermissionPolicyEngine.evaluate("shizuku", mapOf("command" to "pm list packages"), rules).verdict
        )
        // 记忆的 shizuku 规则不能顺带放行 terminal 中的同名命令
        assertEquals(
            Verdict.ASK,
            ToolPermissionPolicyEngine.evaluate("terminal", mapOf("command" to "pm list packages"), rules).verdict
        )
    }

}