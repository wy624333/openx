package com.wy.openx.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shizuku 工具的参数契约与未就绪兜底（不触碰 Android/Shizuku 运行时）。 */
class ShizukuToolTest {

    private val tool = ShizukuTool()

    @Test
    fun `工具名与参数契约`() {
        assertEquals("shizuku", tool.name)
        val command = tool.parameters["command"]
        assertEquals(ParameterType.STRING, command?.type)
        assertTrue(command?.required == true)
        val timeout = tool.parameters["timeout"]
        assertEquals(ParameterType.INTEGER, timeout?.type)
        assertFalse(timeout?.required == true)
        assertTrue(tool.description.contains("Shizuku"))
    }

    @Test
    fun `缺少command时直接返回错误`() = runBlocking {
        val result = tool.execute(emptyMap())
        assertTrue(result.startsWith("错误"))
        assertTrue(result.contains("command"))
    }
}
