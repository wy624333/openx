package com.wy.openx.data.local

import com.wy.openx.data.log.AppLogStore
import kotlin.math.max
import kotlin.math.min

/**
 * CursorWindow 安全的分块读取器。
 *
 * ## 背景
 *
 * Android 的 `CursorWindow` 只有一个 **2MB** 窗口，且**整个 cursor 的所有行共用它**。
 * 长会话（工具输出、大段代码、文件内容）累计轻易超过 2MB，超了以后读任意一行都会抛：
 *
 * ```
 * IllegalStateException: Couldn't read row 359, col 0 from CursorWindow.
 * ```
 *
 * 这与可用内存无关，也与消息条数无直接关系，只与**单次查询返回的字节数**有关，
 * 所以清内存、删消息都无效——只有把一次读取拆成多次才有用。
 *
 * ## 做法
 *
 * 不按 `LIMIT/OFFSET` 切分，而是**按 id 区间**切分：
 * 先用只取 id 的轻查询拿到全部 id（8B/行，几千行仅几十 KB，永不溢出），
 * 再把 id 列表按块分组，每组取 `[ids[i], ids[j]]` 这个闭区间。
 *
 * 用 id 区间而不是 `LIMIT` 有两个理由：
 * 1. `OFFSET` 在大表上要扫描并跳过，且分块期间若有插入/删除会整体错位、漏读或重读；
 * 2. **区间已知两端**，所以某一块读炸了可以精确折半重试；单行都读不出来时
 *    也能精确跳过那一条并继续，而不是卡在原地。
 *
 * ## 为什么要自适应块大小
 *
 * 固定块大小两头不讨好：按条数定，遇上超长 `content` 照样炸；按字节定，
 * SQLite 查询拿不到字节数。折中做法是**按成功情况调整**：
 * 连续成功就放大块（长会话首次加载更快），一失败就折半。
 * 这样绝大多数会话走最快的路径，个别超大消息自动退化成逐条读。
 */
internal object ChunkedReader {

    private const val TAG = "ChunkedReader"

    /** 首次尝试的块大小。保守起步，靠下面的「连续成功就放大」提速。 */
    const val INITIAL_CHUNK = 200

    /** 块大小上限。再大收益递减，反而更容易触碰 2MB 窗口。 */
    const val MAX_CHUNK = 1000

    /**
     * 判断异常是否为 CursorWindow 溢出。
     *
     * 覆盖三种表现：
     * - `Couldn't read row N, col M from CursorWindow`（最常见）
     * - `Row too big to fit into CursorWindow`（单行就超 2MB）
     * - `android.database.sqlite.SQLiteBlobTooBigException`
     *
     * 沿 cause 链往上找，因为 Room 可能包一层再抛出。
     */
    fun isCursorWindowOverflow(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            val msg = cur.message ?: ""
            if (msg.contains("CursorWindow", ignoreCase = true) ||
                msg.contains("too big", ignoreCase = true) ||
                cur is android.database.sqlite.SQLiteBlobTooBigException
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    /**
     * 按 id 区间分块读取，**每块读完立即交给 [consume]，随即丢弃**，不在内存里累积。
     *
     * 内存占用与总量无关（峰值 = 单块，最多 [MAX_CHUNK] 条）。「读出来就是为了写出去」的
     * 场景（如备份导出大会话段）必须用它——收集版会把全表结果留在堆上，叠加序列化缓冲极易 OOM。
     *
     * @param ids    该范围内全部 id（升序，来自只取 id 的轻查询）
     * @param fetch  按 `(afterId 独占, beforeId 包含)` 区间取数据
     * @param label  日志标签
     * @param consume 处理本块（挂起，便于直接写入输出流）
     */
    suspend fun <E> forEachChunk(
        ids: List<Long>,
        label: String,
        fetch: suspend (afterId: Long, beforeId: Long) -> List<E>,
        consume: suspend (List<E>) -> Unit,
    ) {
        if (ids.isEmpty()) return

        var i = 0
        var chunk = INITIAL_CHUNK
        var failedOnce = false

        while (i < ids.size) {
            val end = min(i + chunk, ids.size)
            val afterId = exclusiveFloor(ids[i])
            val beforeId = ids[end - 1]

            val rows = try {
                fetch(afterId, beforeId)
            } catch (t: Throwable) {
                if (!isCursorWindowOverflow(t)) throw t

                failedOnce = true
                if (end - i > 1) {
                    // 折半重试同一区间：两端已知，不会漏读重读。
                    chunk = max(1, (end - i) / 2)
                    AppLogStore.warn(
                        TAG,
                        "$label 读取块溢出(id ${ids[i]}..$beforeId, ${end - i} 条)，降块到 $chunk 重试"
                    )
                    continue
                }
                // 单行仍读不出：跳过这一条，继续后面的。
                // 宁可少一条消息，也不能让整个会话打不开。
                AppLogStore.error(
                    TAG,
                    "$label 单条消息(id=${ids[i]})读取失败，已跳过。原因：${t.message}"
                )
                i = end
                chunk = INITIAL_CHUNK
                continue
            }

            consume(rows)
            i = end

            // 连续成功就放大块，长会话首次加载不必切太多刀。
            if (chunk < MAX_CHUNK && !failedOnce) chunk = min(MAX_CHUNK, chunk * 2)
        }
    }

    /**
     * [forEachChunk] 的收集版：把全部结果攒进内存返回，保持 id 升序。
     * 数据量可能很大时不要用（备份导出请用 [forEachChunk] 边读边写）。
     */
    suspend fun <E> readAll(
        ids: List<Long>,
        label: String,
        fetch: suspend (afterId: Long, beforeId: Long) -> List<E>,
    ): List<E> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<E>(ids.size)
        forEachChunk(ids, label, fetch) { out.addAll(it) }
        return out
    }

    /**
     * 把 id 区间下界转成「开区间上界」。
     *
     * id 理论上从 1 起（SQLite rowid），`id - 1` 作为开区间上界即可；
     * 防御性地处理非正 id，避免把该行漏在区间外。
     */
    private fun exclusiveFloor(id: Long): Long = if (id > 0L) id - 1L else Long.MIN_VALUE
}
