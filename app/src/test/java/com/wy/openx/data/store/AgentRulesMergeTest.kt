package com.wy.openx.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置提示词两层合并（内置层 `memory/agent/` + 自定义层 `memory/agent-custom/`）的纯逻辑。
 *
 * 覆盖三件最要命的事：
 * 1. 同编号自定义层胜出 —— 否则用户改的提示词会被 APk 升级的 seed 静默覆盖；
 * 2. 顺序与改造前的 `sortedBy { key }` 完全一致 —— 注入顺序变会整段打掉前缀缓存；
 * 3. 标题/摘要解析 —— 设置页列表那一行标题来自文件首行注释，解析错列表就是一堆 key。
 */
class AgentRulesMergeTest {

    private fun f(name: String, body: String = "<!-- 标题：摘要 -->\n正文") = AgentRules.RuleFile(name, body)

    @Test
    fun `只有内置层时按 key 升序返回，来源全部是内置`() {
        val out = AgentRules.merge(
            builtin = listOf(f("50-safety.md"), f("05-assistant-behavior.md"), f("20-coding-discipline.md")),
            custom = emptyList()
        )
        assertEquals(listOf("05-assistant-behavior", "20-coding-discipline", "50-safety"), out.map { it.key })
        assertTrue(out.all { it.source == AgentRuleSource.BUILTIN })
        assertEquals(5, out[0].number)
    }

    @Test
    fun `同编号时自定义层压过内置层`() {
        val out = AgentRules.merge(
            builtin = listOf(f("30-comments.md", "<!-- 注释：默认不写 -->\n内置原文")),
            custom = listOf(f("30-comments.md", "<!-- 注释：改过了 -->\n自定义原文"))
        )
        assertEquals(1, out.size)
        assertEquals(AgentRuleSource.CUSTOM, out[0].source)
        assertEquals("<!-- 注释：改过了 -->\n自定义原文", out[0].body)
        assertEquals("注释", out[0].title)
        assertEquals("改过了", out[0].summary)
        assertEquals("30-comments.md", out[0].fileName)
    }

    @Test
    fun `自定义层文件名不同但编号相同，仍按编号覆盖`() {
        // 覆盖身份是编号而非文件名：用户/工具把自定义文件改名后照样生效，
        // 否则内置层和自定义层会同时注入，等于一条规则出现两份。
        val out = AgentRules.merge(
            builtin = listOf(f("10-project-rules.md", "<!-- 项目规则：内置 -->\n内置")),
            custom = listOf(f("10-my-edited-rules.md", "<!-- 项目规则：自定义 -->\n自定义"))
        )
        assertEquals(listOf("10-my-edited-rules"), out.map { it.key })
        assertEquals(AgentRuleSource.CUSTOM, out[0].source)
        assertEquals("<!-- 项目规则：自定义 -->\n自定义", out[0].body)
    }

    @Test
    fun `自定义层新增的编号照样列出`() {
        val out = AgentRules.merge(
            builtin = listOf(f("10-project-rules.md")),
            custom = listOf(f("90-extra.md", "<!-- 额外：用户加的 -->\n新规则"))
        )
        assertEquals(listOf("10-project-rules", "90-extra"), out.map { it.key })
        assertEquals(AgentRuleSource.CUSTOM, out[1].source)
    }

    @Test
    fun `无编号的文件只与同名文件对撞，不被别的编号吃掉`() {
        val out = AgentRules.merge(
            builtin = listOf(f("my-notes.md", "内置笔记")),
            custom = listOf(f("my-notes.md", "自定义笔记"), f("70-x.md", "别的编号"))
        )
        assertEquals(listOf("70-x", "my-notes"), out.map { it.key })
        val notes = out.first { it.key == "my-notes" }
        assertEquals("自定义笔记", notes.body)
        assertEquals(AgentRuleSource.CUSTOM, notes.source)
        assertEquals(-1, notes.number)
        assertEquals(AgentRuleSource.CUSTOM, out.first { it.key == "70-x" }.source)
    }

    @Test
    fun `自定义层同编号重复文件只取文件名排序后的第一个，结果确定`() {
        val out = AgentRules.merge(
            builtin = listOf(f("20-coding-discipline.md")),
            custom = listOf(f("20-b.md", "b"), f("20-a.md", "a"))
        )
        assertEquals(listOf("20-a.md"), out.map { it.fileName })
        assertEquals("a", out[0].body)
    }

    @Test
    fun `两层都空返回空表`() {
        assertTrue(AgentRules.merge(emptyList(), emptyList()).isEmpty())
    }

    // ---- 标题 / 摘要解析 ----

    @Test
    fun `首行注释的全角冒号拆标题与摘要`() {
        val (title, summary) = AgentRules.parseHeadline(
            "05-assistant-behavior",
            "<!-- 助手行为与输出规范：职责、意图理解、输出文本、代码引用、结尾总结 -->\n正文"
        )
        assertEquals("助手行为与输出规范", title)
        assertEquals("职责、意图理解、输出文本、代码引用、结尾总结", summary)
    }

    @Test
    fun `首行注释只有标题没有冒号`() {
        val (title, summary) = AgentRules.parseHeadline("50-safety", "<!-- 安全边界 -->\n正文")
        assertEquals("安全边界", title)
        assertEquals("", summary)
    }

    @Test
    fun `没有注释头就取一级标题`() {
        val (title, summary) = AgentRules.parseHeadline("80-plugin-docs-index", "# 插件开发参考文档（按需查阅）\n正文")
        assertEquals("插件开发参考文档（按需查阅）", title)
        assertEquals("", summary)
    }

    @Test
    fun `两样都没有时兜底成去掉编号的 key，标题不为空`() {
        val (title, _) = AgentRules.parseHeadline("60-notes", "纯正文第一行")
        assertEquals("notes", title)
    }

    @Test
    fun `编号解析`() {
        assertEquals(5, AgentRules.numberOf("05-assistant-behavior.md"))
        assertEquals(80, AgentRules.numberOf("80-plugin-docs-index.md"))
        assertEquals(-1, AgentRules.numberOf("my-notes.md"))
        assertEquals(-1, AgentRules.numberOf("README.md"))
    }

    // ---- 自建片段（设置页右上角「+」新建） ----

    @Test
    fun `自建片段标记为没有内置原文，恢复默认文案改成删除`() {
        val out = AgentRules.merge(
            builtin = listOf(f("30-comments.md")),
            custom = listOf(f("90-extra.md", "<!-- 额外：用户新建 -->\n新规则"))
        )
        assertFalse(out.first { it.key == "90-extra" }.hasBuiltin)
        // 30 号没有自定义层文件 → 仍是内置层，「有内置原文」恒成立
        assertTrue(out.first { it.key == "30-comments" }.hasBuiltin)
    }

    @Test
    fun `覆盖内置的自定义条目仍算有内置原文，恢复默认可用`() {
        val out = AgentRules.merge(
            builtin = listOf(f("30-comments.md", "<!-- 注释：内置 -->\n原文")),
            custom = listOf(f("30-comments.md", "<!-- 注释：改过 -->\n改后"))
        )
        assertTrue(out.single().hasBuiltin)
        assertEquals(AgentRuleSource.CUSTOM, out.single().source)
    }

    @Test
    fun `名称清洗成可落盘的文件名片段`() {
        assertEquals("my-comments", AgentRules.sanitizeName("my comments"))
        assertEquals("a-b-c", AgentRules.sanitizeName("a/b\\c"))
        assertEquals("notes", AgentRules.sanitizeName(" .notes. "))
        assertEquals("中文片段", AgentRules.sanitizeName("中文片段"))
        assertEquals("", AgentRules.sanitizeName("///"))
    }

    // ---- 项目层（memory/projects/<项目>/agent-custom/） ----

    @Test
    fun `项目层优先于全局自定义层与内置层`() {
        val out = AgentRules.merge(
            builtin = listOf(f("30-comments.md", "<!-- 注释：内置 -->\n内置")),
            custom = listOf(f("30-comments.md", "<!-- 注释：全局 -->\n全局")),
            project = listOf(f("30-comments.md", "<!-- 注释：项目 -->\n项目"))
        )
        assertEquals(1, out.size)
        assertEquals(AgentRuleSource.PROJECT, out[0].source)
        assertEquals("<!-- 注释：项目 -->\n项目", out[0].body)
        assertTrue(out[0].hasBuiltin)
    }

    @Test
    fun `项目层独有的新编号标记为自建`() {
        val out = AgentRules.merge(
            builtin = listOf(f("30-comments.md")),
            custom = emptyList(),
            project = listOf(f("95-project-only.md", "<!-- 项目专用：仅本项目 -->\n内容"))
        )
        val rule = out.first { it.key == "95-project-only" }
        assertEquals(AgentRuleSource.PROJECT, rule.source)
        assertFalse(rule.hasBuiltin)
    }

    @Test
    fun `项目层没覆盖时全局自定义仍然生效`() {
        val out = AgentRules.merge(
            builtin = listOf(f("30-comments.md", "内置")),
            custom = listOf(f("30-comments.md", "全局改过")),
            project = emptyList()
        )
        assertEquals(AgentRuleSource.CUSTOM, out.single().source)
        assertEquals("全局改过", out.single().body)
        assertTrue(out.single().hasBuiltin)
    }

    @Test
    fun `四层结果仍按 key 升序，注入顺序不变`() {
        val out = AgentRules.merge(
            builtin = listOf(f("50-safety.md"), f("05-assistant-behavior.md")),
            custom = listOf(f("40-approach.md")),
            project = listOf(f("30-comments.md"))
        )
        assertEquals(
            listOf("05-assistant-behavior", "30-comments", "40-approach", "50-safety"),
            out.map { it.key }
        )
    }
}
