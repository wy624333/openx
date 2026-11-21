package com.wy.openx.tools

import java.util.concurrent.atomic.AtomicLong

/**
 * 一条待送达 AI 的**异步完成通知**（后台终端命令跑完等）。
 *
 * 为什么需要：AI 用 `terminal(start_background, notify=true)` 挂了个长任务（构建、dev server），
 * 然后本轮回复结束。任务跑完后若没人告诉 AI，它就永远不知道结果——用户得手动再发一句
 * 「跑完了吗」。这里把完成事件攒起来，在**下一个能触达 AI 的时机**送过去。
 */
data class AgentNotification(
    /** 会话归属（0 = 无主）。路由回发起该后台任务的会话，而非用户当前所在会话。 */
    val conversationId: Long,
    /** 终端标签 id，AI 可据此调 `terminal(get_output, id=...)` 取完整输出。 */
    val tabId: String,
    val title: String,
    val command: String?,
    val exitCode: Int,
    val tailOutput: String?,
    /** 单调序号，供 [AgentNotificationCenter.peek] 后精确 [AgentNotificationCenter.ack]。 */
    val seq: Long = 0L
)

/**
 * 按会话攒「待送 AI 的异步完成通知」。
 *
 * ## 送达时机（两条路径，见 MainViewModel）
 * 1. **搭车注入**（首选）：AI 正忙、本轮还在跑工具循环时，把通知作为 `system` 消息追加到
 *    工具结果之后——AI **当轮**就能看到，省掉「等本轮结束再起一轮」的 LLM 往返。
 * 2. **空闲唤醒**（兜底）：AI 已空闲（本轮结束）时，把通知合并成一条消息，**为原会话**触发
 *    新一轮生成，让 AI 主动处理。
 *
 * 两条路径都只在通知真正进入请求后才 [ack]，中途被取消的通知仍留在队列，交给下个时机。
 */
object AgentNotificationCenter {

    private val lock = Any()
    private val pending = HashMap<Long, MutableList<AgentNotification>>()
    private val seqGen = AtomicLong(0)

    /** 入队一条通知，返回分配到的序号（0 表示已被去重丢弃）。 */
    fun enqueue(n: AgentNotification): Long = synchronized(lock) {
        val list = pending.getOrPut(n.conversationId) { mutableListOf() }
        // 去重：同一 tab 只保留最新一条（进程迟退时兜底监控与 onFinished 都可能触发，
        // 即便 TerminalTab.finishedNotified 已挡一层，这里再挡一层更稳）。
        list.removeAll { it.tabId == n.tabId }
        val seq = seqGen.incrementAndGet()
        list.add(n.copy(seq = seq))
        seq
    }

    /**
     * 批量重新入队（送达失败时退回）。与 [enqueue] 同样按 tabId 去重，
     * 保留原条目内容、重新分配序号。
     */
    fun enqueueAll(conversationId: Long, items: List<AgentNotification>) {
        items.forEach { enqueue(it) }
    }

    /** 读取该会话当前全部待送通知（不移除）。 */
    fun peek(conversationId: Long): List<AgentNotification> = synchronized(lock) {
        pending[conversationId]?.toList() ?: emptyList()
    }

    /** 确认已送达：按序号移除。peek 之后新入队的条目不受影响。 */
    fun ack(conversationId: Long, seqs: Collection<Long>) {
        if (seqs.isEmpty()) return
        synchronized(lock) {
            val list = pending[conversationId] ?: return
            list.removeAll { it.seq in seqs }
            if (list.isEmpty()) pending.remove(conversationId)
        }
    }

    /** 原子取走该会话全部待送通知。 */
    fun drain(conversationId: Long): List<AgentNotification> = synchronized(lock) {
        pending.remove(conversationId) ?: emptyList()
    }

    fun pendingCount(conversationId: Long): Int = synchronized(lock) {
        pending[conversationId]?.size ?: 0
    }

    fun clear(conversationId: Long) {
        synchronized(lock) { pending.remove(conversationId) }
    }

    fun clearAll() {
        synchronized(lock) { pending.clear() }
    }

    /**
     * 把通知渲染成注入 AI 的文本。
     *
     * 措辞要点：
     * - 明确「这是后台任务的完成通知，不是用户的新消息」，避免 AI 把它当用户指令回复。
     * - 给出 tabId 与取完整输出的方法，AI 需要细节时自己 `terminal(get_output)`。
     * - 区分「完成」与「失败」（非 0 退出码），但**不**把非 0 一律说成「出错」——用户 Ctrl-C
     *   主动终止也是非 0，措辞留中性（给出退出码让 AI 自己判断）。
     */
    fun render(notifications: List<AgentNotification>): String {
        if (notifications.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("[后台任务完成通知] 你之前用 terminal(start_background) 挂起的后台任务已结束，")
        sb.append("以下是结果。这不是用户发来的新消息，请据此判断是否需要继续处理：\n")
        notifications.forEach { n ->
            sb.append("- 「").append(n.title).append("」(标签 ").append(n.tabId).append(")")
            if (!n.command.isNullOrBlank()) sb.append("：命令 `").append(n.command).append('`')
            sb.append(" 已结束，退出码 ").append(n.exitCode)
            sb.append(if (n.exitCode == 0) "（正常完成）" else "（非 0，可能是失败或被中断）")
            sb.append('\n')
            val tail = n.tailOutput?.trim().orEmpty()
            if (tail.isNotEmpty()) {
                sb.append("  末尾输出（最后 $TAIL_LINES 行）：\n")
                tail.lineSequence().forEach { sb.append("    ").append(it).append('\n') }
            }
        }
        sb.append("如需完整输出可调用 terminal(action=\"get_output\", id=\"<标签 id>\")。")
        return sb.toString()
    }
}
