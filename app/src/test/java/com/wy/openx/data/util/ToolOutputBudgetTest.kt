package com.wy.openx.data.util

import com.google.gson.JsonPrimitive
import com.wy.openx.data.remote.ChatMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ①工具输出预算投影的回归测试。
 *
 * 这里盯的是三条不变量（写错了会出真事故的）：
 *  1. 保留的是**最新**的一段工具输出（最旧的先落盘）；
 *  2. 只改 tool 消息的 content，role / tool_call_id / 消息数 / 顺序一律不动；
 *  3. 落盘抛异常必须冒泡——绝不能返回一句指向不存在文件的提示。
 */
class ToolOutputBudgetTest {

    private fun toolMsg(id: String, content: String) =
        ChatMessageDto(role = "tool", content = JsonPrimitive(content), toolCallId = id)

    private fun userMsg(content: String) =
        ChatMessageDto(role = "user", content = JsonPrimitive(content))

    /** 不落盘、只记录被省掉的内容与文件名，断言用。 */
    private class Recorder : (String, String) -> String {
        val offloaded = mutableListOf<Pair<String, String>>()
        override fun invoke(toolName: String, content: String): String {
            offloaded += toolName to content
            return "/data/openx/offloads/$toolName-${offloaded.size}.txt"
        }
    }

    @Test
    fun `预算内不落盘任何输出`() {
        val history = listOf(
            userMsg("hi"),
            toolMsg("call_1", "a".repeat(4000)),
            toolMsg("call_2", "b".repeat(4000))
        )
        val rec = Recorder()
        val out = ToolOutputBudget.project(history, budgetTokens = 50_000, offload = rec)
        assertEquals(history, out)
        assertTrue("预算内不该落盘", rec.offloaded.isEmpty())
    }

    @Test
    fun `超预算时从最旧的一条开始落盘`() {
        // 每条约 10_000 token（40_000 字符），预算 50_000 → 只够留 5 条
        val big = "x".repeat(40_000)
        val history = (1..8).map { toolMsg("call_$it", big) }
        val rec = Recorder()
        // 用 tool_call_id 当工具名，好断言落盘顺序
        val out = ToolOutputBudget.project(
            history, budgetTokens = 50_000,
            toolNameOf = { it.toolCallId ?: "tool" }, offload = rec
        )

        val kept = out.filter { it.content!!.asString.startsWith("x") }
        val replaced = out.filter { it.content!!.asString.contains("已完整保存到") }
        // 每条 10_000 token + 4 开销 = 10_004；预算 50_000 只够放下 4 条（40_016），第 5 条就到 50_020 了
        assertEquals(4, kept.size)
        assertEquals(4, replaced.size)
        assertEquals(replaced.size, rec.offloaded.size)
        // 保留的必须是最新的一段：call_5..call_8，落盘的是最旧的 call_1..call_4
        assertEquals(
            listOf("call_5", "call_6", "call_7", "call_8"),
            kept.map { it.toolCallId }
        )
        assertEquals(
            listOf("call_1", "call_2", "call_3", "call_4"),
            rec.offloaded.map { it.first }
        )
        // 每条提示语里带的都是它自己那份落盘路径（主线上是「<工具名>-<hash>.txt」）
        replaced.forEachIndexed { i, msg ->
            val expected = "/data/openx/offloads/" + rec.offloaded[i].first + "-" + (i + 1) + ".txt"
            assertTrue("第 $i 条提示语未带自己的落盘路径: ${msg.content!!.asString}", msg.content!!.asString.contains(expected))
        }
        // 落盘的是原文，不是提示语
        assertTrue(rec.offloaded.all { it.second.length == 40_000 })
    }

    @Test
    fun `投影不改角色与顺序_只换 content`() {
        val big = "y".repeat(200_000)
        val history = listOf(
            ChatMessageDto(role = "system", content = JsonPrimitive("sys")),
            toolMsg("call_1", big),
            ChatMessageDto(role = "user", content = JsonPrimitive("q")),
            toolMsg("call_2", "small")
        )
        val out = ToolOutputBudget.project(history, budgetTokens = 10, offload = Recorder())
        assertEquals(history.size, out.size)
        assertEquals(history.map { it.role }, out.map { it.role })
        assertEquals(history.map { it.toolCallId }, out.map { it.toolCallId })
        assertEquals("sys", out[0].content!!.asString)
        assertEquals("q", out[2].content!!.asString)
        assertTrue(out[1].content!!.asString.contains("已完整保存到"))
    }

    @Test
    fun `落盘失败必须抛出而不是静默替换`() {
        val history = listOf(toolMsg("call_1", "z".repeat(100_000)))
        var thrown: Exception? = null
        try {
            ToolOutputBudget.project(history, budgetTokens = 10, offload = { _, _ -> throw java.io.IOException("disk full") })
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue("落盘失败必须冒泡", thrown is java.io.IOException)
    }

    @Test
    fun `assistant 的工具调用参数不计入工具输出预算`() {
        val calls = (1..6).map {
            ChatMessageDto(
                role = "assistant", content = com.google.gson.JsonNull.INSTANCE,
                toolCalls = listOf(
                    com.wy.openx.tools.ToolCall(
                        id = "call_$it",
                        type = "function",
                        function = com.wy.openx.tools.ToolCallFunction(
                            name = "readFile", arguments = """{"path":"/root/workspace/$it"}"""
                        )
                    )
                )
            )
        }
        val out = ToolOutputBudget.project(calls, budgetTokens = 1_000, offload = Recorder())
        assertEquals("assistant 消息不该被投影", calls, out)
    }

    @Test
    fun `提示语同时给出宿主路径与容器路径`() {
        val n = ToolOutputBudget.notice("/data/user/0/com.wy.openx/files/workspace/.openx/offloads/1/readFile-ab12.txt")
        assertTrue(n.contains("readFile-ab12.txt"))
        // 落盘目录在 workspace 下，容器内应是 /root/workspace/.openx/offloads/...
        assertTrue(n.contains("/root/workspace/.openx/offloads/1/readFile-ab12.txt"))
    }

    @Test
    fun `投影后整体 token 远小于未投影（上下文估算须模拟投影）`() {
        // 20 条各约 10K token 的旧工具输出，预算 50K → 只留最新 4 条，其余 16 条落盘成提示。
        // 库里的估算若不做这层投影，就会把早已不发送的旧输出也算进上下文
        // （真机实测 432K vs 实际 156K，切小窗口模型时误压）。
        val big = "x".repeat(40_000)
        val history = (1..20).map { toolMsg("call_$it", big) }
        val raw = history.sumOf { TokenEstimator.bodyTokens(it.content!!.asString) }
        val out = ToolOutputBudget.project(history, budgetTokens = 50_000, offload = Recorder())
        val projected = out.sumOf { TokenEstimator.bodyTokens(it.content!!.asString) }
        assertTrue("投影后($projected) 应远小于原始($raw)", projected < raw / 3)
    }

    @Test
    fun `落盘提示语常量与真实提示量级一致`() {
        val notice = ToolOutputBudget.notice(
            "/data/user/0/com.wy.openx/files/workspace/.openx/offloads/1/readFile-abcd1234.txt"
        )
        val actual = TokenEstimator.bodyTokens(notice)
        assertTrue(
            "常量 ${ToolOutputBudget.OFFLOADED_NOTICE_TOKENS} 与实际提示($actual) 偏差过大",
            kotlin.math.abs(ToolOutputBudget.OFFLOADED_NOTICE_TOKENS - actual) < 80
        )
    }

    @Test
    fun `空历史与零预算原样返回`() {
        val rec = Recorder()
        assertEquals(emptyList<ChatMessageDto>(), ToolOutputBudget.project(emptyList(), offload = rec))
        val one = listOf(toolMsg("call_1", "a".repeat(1000)))
        assertEquals(one, ToolOutputBudget.project(one, budgetTokens = 0, offload = rec))
        assertTrue(rec.offloaded.isEmpty())
    }
}
