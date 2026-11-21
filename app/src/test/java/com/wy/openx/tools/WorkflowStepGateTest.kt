package com.wy.openx.tools

import com.wy.openx.data.store.Workflow
import com.wy.openx.data.store.WorkflowStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流的权限闸门。
 *
 * 背景：引擎直接调 `ToolRegistry.executeToolCall`，**不经过**主链路的 `buildToolApprover()`，
 * 所以闸门是工作流里 terminal / writeFile / git 唯一的拦截点。拦截失效等于把
 * 「只读档」和「弹窗确认」两道闸门对工作流整体作废。
 */
class WorkflowStepGateTest {

    private fun wf(vararg steps: WorkflowStep) =
        Workflow(name = "测试流", steps = steps.toList())

    /** 记录真正被执行过的工具，用来断言「被拒的步骤有没有漏跑」。 */
    private class Recorder {
        val executed = mutableListOf<String>()
        fun exec(name: String, args: String): String {
            executed.add(name)
            return "ok:$name"
        }
    }

    @Test
    fun `闸门放行时工作流正常跑完`() = runBlocking {
        val rec = Recorder()
        val engine = WorkflowEngine({ n, a -> rec.exec(n, a) }, gate = { _, _ -> null })
        val out = engine.execute(
            wf(WorkflowStep(id = "a", tool = "web_search"))
        )
        assertTrue(out, out.contains("执行完成"))
        assertEquals(listOf("web_search"), rec.executed)
    }

    @Test
    fun `闸门拒绝时 该工具一步都不许跑`() = runBlocking {
        val rec = Recorder()
        val engine = WorkflowEngine({ n, a -> rec.exec(n, a) }) { tool, _ ->
            "只读模式禁止执行写文件/命令类工具「$tool」"
        }
        val out = engine.execute(wf(WorkflowStep(id = "a", tool = "writeFile")))
        assertTrue(out, out.contains("已被中止"))
        assertTrue(out, out.contains("只读模式禁止"))
        assertTrue("被拒的工具不能被执行过，实际=${rec.executed}", rec.executed.isEmpty())
    }

    @Test
    fun `并行批次里有一个被拒 整流中止 且被拒那个没跑`() = runBlocking {
        val rec = Recorder()
        val engine = WorkflowEngine({ n, a -> rec.exec(n, a) }) { tool, _ ->
            if (tool == "git") "已拒绝执行「git」" else null
        }
        // a、b 无依赖会同批并行，b 是 git 应被拒
        val out = engine.execute(
            wf(
                WorkflowStep(id = "a", tool = "web_search"),
                WorkflowStep(id = "b", tool = "git")
            )
        )
        assertTrue(out, out.contains("已被中止"))
        assertTrue("被拒的 git 不能被执行过，实际=${rec.executed}", "git" !in rec.executed)
    }

    @Test
    fun `闸门拿到的是插值后的参数 不是原始 JSON`() = runBlocking {
        var seen = ""
        val rec = Recorder()
        val engine = WorkflowEngine({ n, a -> rec.exec(n, a) }) { _, args ->
            seen = args
            null
        }
        engine.execute(
            wf(
                WorkflowStep(id = "a", tool = "web_search", args = mapOf("query" to "kotlin")),
                WorkflowStep(
                    id = "b",
                    tool = "writeFile",
                    args = mapOf("content" to "前一步说：\${a}"),
                    dependsOn = listOf("a")
                )
            )
        )
        assertTrue("闸门应看到插值后的内容，实际=$seen", seen.contains("ok:web_search"))
    }

    @Test
    fun `拒绝原因会带上是哪个步骤哪个工具`() = runBlocking {
        val rec = Recorder()
        // 只放行只读类、拒绝 terminal，这样中止点落在第二步 x2 上
        val engine = WorkflowEngine({ n, a -> rec.exec(n, a) }) { tool, _ ->
            if (tool == "terminal") "只读模式禁止「$tool」" else null
        }
        val out = engine.execute(
            wf(
                WorkflowStep(id = "x1", tool = "readFile"),
                WorkflowStep(id = "x2", tool = "terminal", dependsOn = listOf("x1"))
            )
        )
        assertTrue(out, out.contains("x2"))
        assertTrue(out, out.contains("terminal"))
        // 前一步是只读类，已放行并执行过；不该被牵连
        assertEquals(listOf("readFile"), rec.executed)
    }

    /** 没有闸门（gate=null）时只读类照跑，写/执行类的兜底由 WorkflowTool 那侧传入的闸门负责。 */
    @Test
    fun `闸门为 null 不影响执行`() = runBlocking {
        val rec = Recorder()
        val engine = WorkflowEngine({ n, a -> rec.exec(n, a) })
        val out = engine.execute(wf(WorkflowStep(id = "a", tool = "terminal")))
        assertTrue(out, out.contains("执行完成"))
        assertEquals(listOf("terminal"), rec.executed)
    }

    @Test
    fun `死锁仍在闸门之前判死锁 不弹无谓的审批`() = runBlocking {
        val rec = Recorder()
        var gateCalls = 0
        val engine = WorkflowEngine({ n, a -> rec.exec(n, a) }) { _, _ ->
            gateCalls++
            null
        }
        val out = engine.execute(
            wf(
                WorkflowStep(id = "a", tool = "t", dependsOn = listOf("b")),
                WorkflowStep(id = "b", tool = "t", dependsOn = listOf("a"))
            )
        )
        assertTrue(out, out.contains("死锁"))
        assertEquals("没有任何步骤可跑，不该问过闸门", 0, gateCalls)
    }
}
