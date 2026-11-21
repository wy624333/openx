package com.wy.openx.data.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流表单解析与校验。
 *
 * 这层校验的价值在于**把死锁挡在保存之前**：用户手写 JSON 很容易依赖一个不存在的 id，
 * 而 [com.wy.openx.tools.WorkflowEngine] 要等到真正执行时才报「工作流死锁」，那时已经跑了别的步骤。
 */
class WorkflowParserTest {

    private fun ok(steps: String, name: String = "wf", desc: String = "") =
        parseWorkflowJson(name, desc, steps)

    @Test
    fun `正常的两步依赖工作流解析通过`() {
        val r = ok(
            """[{"id":"a","tool":"read","args":{"p":"x"}},
                {"id":"b","tool":"write","dependsOn":["a"]}]"""
        )
        assertNull(r.error)
        val wf = r.workflow!!
        assertEquals("wf", wf.name)
        assertEquals(listOf("a", "b"), wf.steps.map { it.id })
        assertEquals(listOf("a"), wf.steps[1].dependsOn)
    }

    @Test
    fun `依赖了不存在的步骤被拦下 并指出是哪一步`() {
        val r = ok("""[{"id":"a","tool":"t","dependsOn":["ghost"]}]""")
        assertNull(r.workflow)
        assertTrue(r.error!!, r.error!!.contains("ghost"))
        assertTrue(r.error!!, r.error!!.contains("a"))
    }

    @Test
    fun `步骤依赖自己被拦下`() {
        val r = ok("""[{"id":"a","tool":"t","dependsOn":["a"]}]""")
        assertNull(r.workflow)
        assertTrue(r.error!!, r.error!!.contains("依赖了自己"))
    }

    @Test
    fun `环形依赖本层不拦 如实放行给执行期死锁检测`() {
        // a→b→a：依赖都存在，但永远等不到 ready。
        // 本层只拦「依赖不存在」与「依赖自己」；**环形如实放行**，
        // 由 WorkflowEngine 执行时判死锁。这里锁定这个分工，免得误以为保存时已挡住环。
        val r = ok("""[{"id":"a","tool":"t","dependsOn":["b"]},{"id":"b","tool":"t","dependsOn":["a"]}]""")
        assertNull(r.error)
        assertNotNull(r.workflow)
        assertEquals(2, r.workflow!!.steps.size)
    }

    @Test
    fun `步骤 id 重复被拦下`() {
        val r = ok("""[{"id":"a","tool":"t"},{"id":"a","tool":"t"}]""")
        assertNull(r.workflow)
        assertTrue(r.error!!, r.error!!.contains("重复"))
    }

    @Test
    fun `缺 tool 被拦下并给出序号`() {
        val r = ok("""[{"id":"a","tool":"t"},{"id":"b"}]""")
        assertNull(r.workflow)
        assertTrue(r.error!!, r.error!!.contains("第 2 步"))
    }

    @Test
    fun `空数组被拦下`() {
        assertTrue(ok("[]").error!!.contains("至少"))
    }

    @Test
    fun `非法 JSON 给出可读错误而不是抛异常`() {
        val r = ok("""[{"id":"a",""")
        assertNull(r.workflow)
        assertNotNull(r.error)
    }

    @Test
    fun `缺 id 时按位置补 step_N 并保持稳定`() {
        val r = ok("""[{"tool":"t"},{"tool":"t2"}]""")
        assertNull(r.error)
        assertEquals(listOf("step_1", "step_2"), r.workflow!!.steps.map { it.id })
    }

    @Test
    fun `名称与描述去首尾空白`() {
        val r = ok("""[{"id":"a","tool":"t"}]""", name = "  我的流程  ", desc = "  说明  ")
        assertEquals("我的流程", r.workflow!!.name)
        assertEquals("说明", r.workflow!!.description)
    }

    @Test
    fun `steps 可被序列化回去且能再次解析（编辑不丢数据）`() {
        val first = ok("""[{"id":"a","tool":"read","args":{"p":"x"},"dependsOn":[]}]""").workflow!!
        val roundTrip = parseWorkflowJson(first.name, first.description, stringifyWorkflowSteps(first.steps))
        assertNull(roundTrip.error)
        assertEquals(first.steps.map { it.id }, roundTrip.workflow!!.steps.map { it.id })
        assertEquals("read", roundTrip.workflow!!.steps[0].tool)
    }

    /**
     * 界面的字符串来自 DEFAULT_STEPS_JSON，AI 生成的 JSON 结构和它一致，
     * 所以只要 DEFAULT 那份能过解析，AI 照着抄的就能过。
     */
    @Test
    fun `默认模板 JSON 能被自己解析回来`() {
        val r = parseWorkflowJson("默认", "", DEFAULT_STEPS_JSON_LIKE)
        assertNull(r.error)
        assertEquals("s1", r.workflow!!.steps[0].id)
    }

    @Test
    fun `AI 常见的无 id 写法也能过（与界面同一套规则）`() {
        val r = parseWorkflowJson("ai", "", """[{"tool":"web_search","args":{"query":"x"}}]""")
        assertNull(r.error)
        assertEquals("step_1", r.workflow!!.steps[0].id)
    }

    /** 悬空依赖：AI 手写依赖时最容易犯，界面和 AI 现在都会在保存时就拦下。 */
    @Test
    fun `悬空依赖被拦下 这正是 AI 路径原先漏掉的`() {
        val r = parseWorkflowJson("ai", "", """[{"id":"a","tool":"t","dependsOn":["nope"]}]""")
        assertNull(r.workflow)
        assertTrue(r.error!!, r.error!!.contains("nope"))
    }

    @Test
    fun `自依赖被拦下 AI 路径原先漏掉`() {
        val r = parseWorkflowJson("ai", "", """[{"id":"a","tool":"t","dependsOn":["a"]}]""")
        assertNull(r.workflow)
        assertTrue(r.error!!, r.error!!.contains("依赖了自己"))
    }

    private val DEFAULT_STEPS_JSON_LIKE =
        """[
  {
    "id": "s1",
    "tool": "list_files",
    "args": { "path": "." },
    "dependsOn": []
  }
]"""
}
