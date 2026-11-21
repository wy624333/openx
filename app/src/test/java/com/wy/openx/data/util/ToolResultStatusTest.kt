package com.wy.openx.data.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果成功/失败判定回归测试。
 *
 * 重点锁两件事：
 *  1. 真正的失败必须判红（错误：xxx / ❌ / ok=false / 失败计数>0 / 拦截）；
 *  2. 成功的调用**绝不**因为结果内容里出现 error/失败/错误 而判红（v1 的主要 bug）。
 */
class ToolResultStatusTest {

    // ---------- 必须判绿：成功的调用 ----------
    @Test
    fun `batch_edit 全成功不应判红`() {
        assertFalse(ToolResultStatus.isError("✅ 批量编辑完成 (成功 3 / 失败 0)"))
    }

    @Test
    fun `batch_replace 零失败不应判红`() {
        val text = """
            ✅ 替换完成
              搜索: «foo»
              命中文件: 5
              已修改: 5
              未变更/失败: 0
        """.trimIndent()
        assertFalse(ToolResultStatus.isError(text))
    }

    @Test
    fun `file_analyzer 成功分析并报告 JSON 错误不应判红`() {
        val text = """
            📊 文件分析: a.json
              路径: /sdcard/a.json
              JSON 合法: false
              JSON 错误: Expected value at line 1
              错误大致在第 3 行附近
        """.trimIndent()
        assertFalse(ToolResultStatus.isError(text))
    }

    @Test
    fun `terminal 输出内容含 error 不应判红`() {
        val text = """
            [Shell 环境]
            ```
            $ grep -rn "error" .
            ./src/main/Error.kt:10:val error = 1
            ```
        """.trimIndent()
        assertFalse(ToolResultStatus.isError(text))
    }

    @Test
    fun `读取到含失败二字的正常文件内容不应判红`() {
        assertFalse(ToolResultStatus.isError("/sdcard/测试报告.md\n# 失败用例统计\n本轮失败 0 项，通过 12 项"))
    }

    @Test
    fun `结构化 ok true 不判红`() {
        assertFalse(ToolResultStatus.isError("""{"ok":true,"error":null,"result":"done"}"""))
    }

    // ---------- 必须判红：真正的失败 ----------
    @Test
    fun `项目约定的错误前缀判红`() {
        assertTrue(ToolResultStatus.isError("错误：文件不存在: /sdcard/x.kt"))
        assertTrue(ToolResultStatus.isError("错误：删除失败"))
    }

    @Test
    fun `结构化 ok false 判红`() {
        assertTrue(ToolResultStatus.isError("""{"ok":false,"error":"boom"}"""))
    }

    @Test
    fun `结构化仅 error 非空判红`() {
        assertTrue(ToolResultStatus.isError("""{"error":{"message":"HTTP 500"}}"""))
    }

    @Test
    fun `失败计数大于零判红`() {
        assertTrue(ToolResultStatus.isError("✅ 批量编辑完成 (成功 1 / 失败 2)"))
        val replace = """
            ✅ 替换完成
              命中文件: 5
              已修改: 3
              未变更/失败: 2
        """.trimIndent()
        assertTrue(ToolResultStatus.isError(replace))
    }

    @Test
    fun `执行失败与拦截判红`() {
        assertTrue(ToolResultStatus.isError("❌ 执行失败: command not found"))
        assertTrue(ToolResultStatus.isError("⛔ 已拦截危险命令（rm -rf /）。命令未执行。"))
        assertTrue(ToolResultStatus.isError("已停止"))
    }

    @Test
    fun `git 非零退出判红`() {
        assertTrue(ToolResultStatus.isError("Git 命令执行失败 (exit=128):\nfatal: not a git repository"))
    }

    // ---------- 边界 ----------
    @Test
    fun `空与 null 判绿`() {
        assertFalse(ToolResultStatus.isError(null))
        assertFalse(ToolResultStatus.isError(""))
        assertFalse(ToolResultStatus.isError("   \n  "))
    }

    @Test
    fun `正文深处的偶然用词不参与判定`() {
        // 统计头在窗口内且为零 → 绿；窗口外的失败计数不参与判定
        assertFalse(ToolResultStatus.isError("✅ 替换完成\n未变更/失败: 0\n\n正文里提到 失败 9 处"))
    }
}
