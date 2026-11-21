package com.wy.openx.ui.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {

    @Test
    fun `主命令名唯一且以斜杠开头`() {
        val names = BUILTIN_SLASH_COMMANDS.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(names.all { it.startsWith("/") && it.length > 1 })
    }

    @Test
    fun `别名不与任何主命令或别名冲突`() {
        val all = BUILTIN_SLASH_COMMANDS.flatMap { listOf(it.name) + it.aliases }
        assertEquals(all.size, all.distinct().size)
        BUILTIN_SLASH_COMMANDS.forEach { spec ->
            assertTrue(spec.aliases.all { it.startsWith("/") && it.length > 1 })
        }
    }

    @Test
    fun `canonicalBuiltinCommand 归一化别名与大小写`() {
        assertEquals("/help", canonicalBuiltinCommand("/帮助"))
        assertEquals("/compact", canonicalBuiltinCommand("/总结"))
        assertEquals("/help", canonicalBuiltinCommand("/HELP"))
        assertNull(canonicalBuiltinCommand("/不存在的命令"))
        assertNull(canonicalBuiltinCommand("/my-skill"))
    }

    @Test
    fun `thinking 与 usage 已登记`() {
        val names = BUILTIN_SLASH_COMMANDS.map { it.name }
        assertTrue("/thinking" in names)
        assertTrue("/usage" in names)
        assertEquals("/thinking", canonicalBuiltinCommand("/推理"))
    }

    @Test
    fun `快捷命令面板只取 quick 标记项`() {
        val quick = BUILTIN_SLASH_COMMANDS.filter { it.quick }.map { it.name }
        assertEquals(listOf("/help", "/compact", "/new", "/mode"), quick)
    }

    @Test
    fun `帮助文本包含全部命令且可附技能`() {
        val help = buildSlashHelp(listOf("pdf-report" to "生成 PDF 报告"))
        BUILTIN_SLASH_COMMANDS.forEach { spec ->
            assertTrue("缺少 ${spec.name}", help.contains(spec.name))
            assertTrue("缺少 ${spec.name} 的描述", help.contains(spec.description))
        }
        assertTrue(help.contains("/pdf-report"))
        assertTrue(help.contains("生成 PDF 报告"))
    }

    @Test
    fun `未启用技能时帮助不出现技能段落`() {
        val help = buildSlashHelp()
        assertFalse(help.contains("已启用技能"))
    }

    @Test
    fun `技能描述中的换行在帮助里压成一行`() {
        val help = buildSlashHelp(listOf("long-skill" to "第一段\n第二段\n  第三段"))
        assertTrue(help.contains("/long-skill  — 第一段 第二段 第三段"))
        assertFalse(help.contains("第一段\n"))
    }

    @Test
    fun `技能描述为空时帮助使用占位文案`() {
        val help = buildSlashHelp(listOf("blank-skill" to "  \n "))
        assertTrue(help.contains("/blank-skill  — 自定义技能"))
    }
}
