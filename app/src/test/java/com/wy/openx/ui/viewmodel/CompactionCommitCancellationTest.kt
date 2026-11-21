package com.wy.openx.ui.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「压缩落库被取消 → 卡片不显示」的回归。
 *
 * ## 真机事故
 *
 * ```
 * 18:15:34.225  用户手动停止                    ← 用户按暂停，本轮协程被取消
 * 18:15:34.287  就地压缩落库: ... → 落库后15条   ← 事务**已提交**（数据进库了）
 * （缺少「压缩事务提交后」诊断行）               ← 提交之后的回填代码**没执行**
 * ```
 *
 * 结果：库里 marker/摘要都在、折叠也正确，但 `uiState.foldedMessages` 没被更新 →
 * **压缩卡不显示**。
 *
 * 教训：把 DB 写做成「单个原子事务」**并不能**保证整个提交动作完整——
 * 事务提交之后往往还有代码（内存回填、诊断、后续写），而它们仍在可取消的协程里，
 * 取消点会落在 `withContext(IO)` 之后的下一个挂起点上。
 *
 * 压缩落库是**一次性终态提交**：要么完整做完（含内存对齐），要么整体不做。
 * 本测试用真实协程取消验证：只有 `NonCancellable` 能保证「提交 + 回填」两步都跑完。
 */
class CompactionCommitCancellationTest {

    /** 模拟「事务提交 + 内存回填」两步，返回是否两步都完成。 */
    private fun runCommit(useNonCancellable: Boolean): Pair<Boolean, Boolean> {
        val committed = AtomicBoolean(false)
        val backfilled = AtomicBoolean(false)
        val job = runBlocking {
            val j = launch(Dispatchers.Default) {
                val body: suspend () -> Unit = {
                    withContext(Dispatchers.IO) {
                        // 模拟事务提交（真实实现里是一次 Room 事务）
                        delay(20)
                        committed.set(true)
                    }
                    // 模拟提交后的内存回填（真实实现里是 getFoldedMessages + editSlot）
                    delay(20)
                    backfilled.set(true)
                }
                if (useNonCancellable) {
                    withContext(NonCancellable) { body() }
                } else {
                    body()
                }
            }
            // 让事务提交先发生，再取消 —— 精确复刻真机时序
            delay(35)
            j.cancel(CancellationException("用户手动停止"))
            j
        }
        runBlocking { job.join() }
        return committed.get() to backfilled.get()
    }

    @Test
    fun `不加 NonCancellable 时提交后的回填会被取消——这正是卡片不显示的原因`() {
        val (committed, backfilled) = runCommit(useNonCancellable = false)
        assertTrue("事务应当已提交（真机日志里数据确实进库了）", committed)
        // 锁住这个反例：提交成功、回填丢失 = 库里有 marker 但 UI 不显示卡片
        assertTrue("提交后的回填应被取消（复现真机症状）", !backfilled)
    }

    @Test
    fun `NonCancellable 保证提交与回填都完成`() {
        val (committed, backfilled) = runCommit(useNonCancellable = true)
        assertTrue("事务提交必须完成", committed)
        assertTrue("回填也必须完成（否则压缩卡不显示）", backfilled)
    }

    @Test
    fun `取消仍然会向上传播，不会被 NonCancellable 吞掉`() {
        // NonCancellable 只保证那一段不被打断，不改变协程本身的取消语义：
        // 取消后协程仍应处于 cancelled 状态（否则「停止」按钮会失效）。
        val job = runBlocking {
            val j = launch(Dispatchers.Default) {
                withContext(NonCancellable) { delay(50) }
                delay(10_000)   // 这段可取消
            }
            delay(10)
            j.cancel()
            j
        }
        runBlocking { job.join() }
        assertTrue("协程必须已被取消", job.isCancelled)
    }

    @Test
    fun `NonCancellable 段内的挂起点不会被取消打断`() {
        val steps = AtomicInteger(0)
        val job = runBlocking {
            val j = launch(Dispatchers.Default) {
                withContext(NonCancellable) {
                    delay(20); steps.incrementAndGet()   // 提交
                    delay(20); steps.incrementAndGet()   // 回填
                }
            }
            delay(5)
            j.cancel()
            j
        }
        runBlocking { job.join() }
        assertEquals("NonCancellable 内的两个挂起点都应执行完", 2, steps.get())
    }
}
