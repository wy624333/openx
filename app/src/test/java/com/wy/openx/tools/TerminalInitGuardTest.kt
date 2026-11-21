package com.wy.openx.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端「切容器后进终端一片黑屏」的回归测试。
 *
 * 用户报的现象：切换容器 → 回聊天页 → 进终端 → 卡住，什么都不显示。
 * 根因链：切容器调 stop()，而 stop 不重置在途标记、initialize 开头又是
 * `if (initInFlight.get()) return` 的静默返回 → 会话永远建不起来 →
 * TerminalScreen 的 AndroidView 拿不到 session，只画纯黑。
 */
class TerminalInitGuardTest {

    private val g = TerminalInitGuard()

    @Test
    fun `首次初始化能开始`() {
        assertNotNull(g.tryBegin())
    }

    @Test
    fun `在途时不重复触发`() {
        val first = g.tryBegin()
        assertNotNull(first)
        assertNull("已有在途初始化，不该再开始一次", g.tryBegin())
    }

    /**
     * 这条就是用户报的 bug。旧实现里 stop() 不碰在途标记，
     * 于是在途初始化未完成前，每次进终端都命中静默 return，永久黑屏。
     */
    @Test
    fun `切换容器后必须能重新开始初始化`() {
        g.tryBegin() // 旧容器初始化在途
        g.invalidate() // 切换容器：stop()
        assertNotNull("切容器后必须能重新初始化，否则进终端永久黑屏", g.tryBegin())
    }

    @Test
    fun `切换容器作废旧结果`() {
        val old = g.tryBegin()!!
        g.invalidate()
        assertFalse("切换容器后，旧那次初始化的结果必须作废", g.isCurrent(old))
    }

    @Test
    fun `重新初始化同样作废旧结果`() {
        val old = g.tryBegin()!!
        g.end(old)
        val fresh = g.tryBegin()!!
        assertTrue("最新一次的代号应当有效", g.isCurrent(fresh))
        assertFalse("上一次初始化的结果必须作废", g.isCurrent(old))
    }

    @Test
    fun `结束后可再次开始`() {
        val gen = g.tryBegin()!!
        g.end(gen)
        assertNotNull(g.tryBegin())
    }

    /** end() 之后不应改变代号有效性判定（单纯收尾，不是作废）。 */
    @Test
    fun `end 只清标记不改代号`() {
        val gen = g.tryBegin()!!
        g.end(gen)
        assertTrue(g.isCurrent(gen))
    }

    /**
     * 切容器发生在初始化在途期间时，旧那轮的 finally 不能清掉新一轮的在途标记，
     * 否则一次切容器会开出两个会话。
     */
    @Test
    fun `过期的 end 不会清掉新一轮的在途标记`() {
        val old = g.tryBegin()!!
        g.invalidate()          // 切容器：标记被清、代号自增
        val fresh = g.tryBegin()!!
        g.end(old)              // 旧那轮迟到收尾
        assertNull("旧那轮清了标记，会导致两轮同时在途", g.tryBegin())
    }

    @Test
    fun `连续多次切换容器`() {
        repeat(5) {
            val gen = g.tryBegin()
            assertNotNull("第 ${it + 1} 次切容器后必须能初始化", gen)
            g.invalidate()
            assertFalse(g.isCurrent(gen!!))
        }
    }
}
