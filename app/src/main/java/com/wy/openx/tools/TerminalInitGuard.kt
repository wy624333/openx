package com.wy.openx.tools

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 终端会话初始化的「在途 + 代号」守卫。
 *
 * ## 它防的是什么
 * 切容器会调 [TerminalEngine.stop]。此前 stop **不碰在途标记**，而 initialize 开头是
 * `if (initInFlight.get()) return`（静默 return）。于是只要在初始化未完成时切换容器，
 * 之后每次进终端都命中这个 return：不建会话、不打日志、不改状态 →
 * TerminalScreen 拿不到 session，只画黑屏（`TerminalView.onDraw` 里
 * `mEmulator == null` → `drawColor(0xFF000000)`），表现就是「终端卡住，什么都不显示」。
 *
 * 两个设计点：
 * - [invalidate] 同时清在途标记并自增代号。清标记是为了让下次 [tryBegin] 不被旧的在途
 *   状态挡住；自增代号是为了让**旧那次的结果作废**——它算的是切换前的 profile，
 *   若照用会把上一个容器的会话挂上来。
 * - [isCurrent] 供异步结果回来后自检：代号对不上就丢弃，而不是采用。
 */
internal class TerminalInitGuard {

    private val inFlight = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    fun currentGeneration(): Int = generation.get()

    /**
     * 尝试开始一次初始化，**每次尝试都拿一个全新代号**。
     *
     * 自增放在这里而不是只在 [invalidate]，是为了让「上一轮的结果」永远能靠代号识破：
     * 前一轮已 [end] 收尾、新一轮紧接着开始时，两者代号不同，旧结果不会蒙混过关。
     * @return 本次初始化的代号；已在途时返回 null（调用方应跳过触发，但 UI 仍要显示「准备中」）。
     */
    fun tryBegin(): Int? {
        if (!inFlight.compareAndSet(false, true)) return null
        return generation.incrementAndGet()
    }

    /**
     * 初始化结束（成功/失败/作废都调）。
     *
     * 必须带代号：若期间发生过 [invalidate]（切容器），标记已被新一轮占用，
     * 此时旧的 finally 若无条件清标记，会让两轮初始化同时在途 → 一次切容器开出两个会话。
     */
    fun end(gen: Int) {
        if (generation.get() == gen) inFlight.set(false)
    }

    /**
     * 作废在途初始化并释放标记，返回新的代号。切换容器 / 重新初始化时调用。
     * 注意必须**同时**清标记：只自增代号而不清，会留下一个永远「在途」的幽灵状态。
     */
    fun invalidate(): Int {
        inFlight.set(false)
        return generation.incrementAndGet()
    }

    /** 异步结果是否仍然有效。 */
    fun isCurrent(gen: Int): Boolean = generation.get() == gen
}
