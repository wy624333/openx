package com.wy.openx.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChunkedReader] 回归测试。
 *
 * 背景：Android `CursorWindow` 只有 2MB 且整个 cursor 共用，长会话一次
 * `SELECT *` 会抛 `Couldn't read row N from CursorWindow`（真机在
 * 「恢复上下文」时炸的就是这个）。分块读取是唯一的根治办法，
 * 所以这里的每个边界都必须钉死。
 */
class ChunkedReaderTest {

    private fun ids(n: Int, start: Long = 1L) = (0 until n).map { start + it }

    /** 区间查询的假实现：返回落在 (after, before] 内的 id（数据从 1 开始连续）。 */
    private val rangeFetch: suspend (Long, Long) -> List<Long> = { after, before ->
        generateSequence(after + 1) { it + 1 }.takeWhile { it <= before }.toList()
    }

    @Test
    fun `空 id 列表直接返回空`() = runBlocking {
        val out = ChunkedReader.readAll(emptyList<Long>(), "t") { _: Long, _: Long -> error("不应发起查询") as List<Long> }
        assertTrue(out.isEmpty())
    }

    @Test
    fun `小于一块的消息一次读完且顺序正确`() = runBlocking {
        val ids = ids(50)
        val out = ChunkedReader.readAll(ids, "t", rangeFetch)
        assertEquals(ids, out)
    }

    @Test
    fun `超过一块时自动分块且不重不漏`() = runBlocking {
        val ids = ids(1000)
        var calls = 0
        val out = ChunkedReader.readAll(ids, "t") { a: Long, b: Long -> calls++; rangeFetch(a, b) }
        assertEquals(ids, out)
        assertTrue("应分多块读取，实际 $calls 次", calls > 1)
    }

    @Test
    fun `整块超窗口时折半重试并最终读全`() = runBlocking {
        val ids = ids(900)
        // 模拟：单次区间内字节数 > 2MB 就抛 CursorWindow 异常
        val budget = 300
        val out = ChunkedReader.readAll(ids, "t") { a: Long, b: Long ->
            val n = generateSequence(a + 1) { it + 1 }.takeWhile { it <= b }.count()
            if (n > budget) {
                throw IllegalStateException("Couldn't read row ${a + 1}, col 0 from CursorWindow.")
            }
            rangeFetch(a, b)
        }
        assertEquals(ids, out)
    }

    @Test
    fun `单行也读不出时跳过该行而不是整体失败`() = runBlocking {
        val ids = ids(10)
        val out = ChunkedReader.readAll(ids, "t") { a: Long, b: Long ->
            val rows = rangeFetch(a, b)
            // 第 5 行是「畸形超大消息」：无论区间多小都读不出
            if (rows.contains(5L)) throw IllegalStateException("Couldn't read row 5, col 0 from CursorWindow.")
            rows
        }
        // 其余 9 条必须完好无损
        assertEquals(ids.filter { it != 5L }, out)
    }

    @Test
    fun `连续成功会放大块以加速长会话`() = runBlocking {
        val ids = ids(5000)
        var calls = 0
        val out = ChunkedReader.readAll(ids, "t") { a: Long, b: Long -> calls++; rangeFetch(a, b) }
        assertEquals(ids, out)
        // 初始 200 起步并翻倍，5000 条应在 10 次以内读完
        assertTrue("放大策略失效，读取次数过多：$calls", calls <= 10)
    }

    @Test
    fun `非 CursorWindow 异常必须原样抛出`() = runBlocking {
        // 数据量必须 > INITIAL_CHUNK，否则一次就读完、根本走不到 fetch
        val ids = ids(ChunkedReader.INITIAL_CHUNK + 50)
        val boom = IllegalStateException("database is locked")
        var thrown: Throwable? = null
        try {
            // 显式指定类型参数：lambda 抛异常时类型为 Nothing，
            // 否则 E 推不出来（也别用 `as List<Long>` 强转，那会干扰控制流）
            ChunkedReader.readAll<Long>(ids, "t") { _, _ -> throw boom }
        } catch (t: Throwable) {
            thrown = t
        }
        // 数据库锁、死锁等真实错误不能被吞掉伪装成「读到了」
        assertTrue("应原样抛出，实际 $thrown", thrown === boom)
    }

    @Test
    fun `识别 CursorWindow 溢出异常`() {
        assertTrue(ChunkedReader.isCursorWindowOverflow(
            IllegalStateException("Couldn't read row 359, col 0 from CursorWindow.")
        ))
        assertTrue(ChunkedReader.isCursorWindowOverflow(
            RuntimeException("wrap", IllegalStateException("Row too big to fit into CursorWindow"))
        ))
        assertFalse(ChunkedReader.isCursorWindowOverflow(IllegalStateException("no such table: messages")))
        assertFalse(ChunkedReader.isCursorWindowOverflow(java.io.IOException("network down")))
    }

    @Test
    fun `id 从非 1 开始时也能读全`() = runBlocking {
        // 会话切换/导入恢复后 id 可能不从 1 起
        val ids = ids(300, start = 1000L)
        val out = ChunkedReader.readAll(ids, "t") { a: Long, b: Long ->
            ids.filter { it > a && it <= b }
        }
        assertEquals(ids, out)
    }
}
