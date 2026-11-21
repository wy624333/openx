package com.wy.openx.plugin

import com.wy.openx.container.FileLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件间消息总线。
 *
 * ## 为什么是「拉」不是「推」
 *
 * 每个插件跑在**独立的 QuickJS 实例**里（UI 面板一个、服务一个、工具执行一个），
 * 实例之间没有共享堆。想做「A 发消息 → 立刻调起 B 的回调」，宿主必须持有所有实例
 * 并在任意时刻跨线程重入别人的引擎 —— QuickJS 是单线程的，重入轻则阻塞、重则崩溃，
 * 而且插件可能正处在渲染中途。
 *
 * 所以这里只做**消息队列**：发布方投递，订阅方在自己的生命周期里（渲染、工具调用、
 * 定时任务）主动拉取。语义上等价于「邮箱」，插件写法简单、宿主也不会被卡住。
 *
 * ## 隔离
 *
 * - 按 topic 分队列，每 topic 一个单调递增序号，拉取时传 `sinceSeq` 只拿增量。
 * - 每 topic 保留最近 [MAX_PER_TOPIC] 条，超出丢最旧的（慢订阅者会丢消息，
 *   这是有意的：总线不该无限增长把内存吃光）。
 * - 订阅关系也要登记：只有声明过订阅的插件才能拉到该 topic 的消息，
 *   否则任何插件都能监听别人的私有 topic。
 */
object PluginBus {

    private const val TAG = "PluginBus"

    /** 每个 topic 保留的消息上限。 */
    private const val MAX_PER_TOPIC = 100

    data class Message(val seq: Long, val topic: String, val from: String, val payload: String, val at: Long)

    private class TopicState {
        val messages = ArrayDeque<Message>()
        var seq: Long = 0
        /** 订阅了本 topic 的插件 id。 */
        val subscribers = mutableSetOf<String>()
    }

    private val topics = ConcurrentHashMap<String, TopicState>()

    /** 发布一条消息，返回它的序号。 */
    fun publish(topic: String, from: String, payload: String): Long = synchronized(this) {
        val t = topic.trim()
        if (t.isEmpty()) return 0
        val st = topics.getOrPut(t) { TopicState() }
        st.seq += 1
        st.messages.addLast(Message(st.seq, t, from, payload, System.currentTimeMillis()))
        while (st.messages.size > MAX_PER_TOPIC) st.messages.removeFirst()
        st.seq
    }

    /** 登记订阅关系（`on` 时调）。 */
    fun subscribe(topic: String, pluginId: String) = synchronized(this) {
        val t = topic.trim()
        if (t.isEmpty()) return
        topics.getOrPut(t) { TopicState() }.subscribers += pluginId
    }

    /** 取消订阅。 */
    fun unsubscribe(topic: String, pluginId: String) = synchronized(this) {
        topics[topic.trim()]?.subscribers?.remove(pluginId)
    }

    /** 插件卸载时清掉它的全部订阅。 */
    fun unsubscribeAll(pluginId: String) = synchronized(this) {
        topics.values.forEach { it.subscribers.remove(pluginId) }
    }

    /**
     * 拉取某 topic 中序号大于 [sinceSeq] 的消息。
     *
     * [pluginId] 未订阅该 topic 时返回空列表 —— 订阅是拉取的前提。
     */
    fun poll(topic: String, pluginId: String, sinceSeq: Long, limit: Int = 50): List<Message> =
        synchronized(this) {
            val st = topics[topic.trim()] ?: return emptyList()
            if (pluginId !in st.subscribers) return emptyList()
            st.messages.asSequence()
                .filter { it.seq > sinceSeq }
                .take(limit.coerceIn(1, 200))
                .toList()
        }

    /** 当前最大序号（插件可用来「从现在开始听」）。 */
    fun latestSeq(topic: String): Long = synchronized(this) {
        topics[topic.trim()]?.seq ?: 0
    }

    /** 已订阅的 topic 列表（调试/诊断用）。 */
    fun subscribedTopics(pluginId: String): List<String> = synchronized(this) {
        topics.entries.filter { pluginId in it.value.subscribers }.map { it.key }.sorted()
    }

    /** 清空（测试与「全部插件重载」时用）。 */
    fun clear() = synchronized(this) {
        topics.clear()
        FileLogger.i(TAG, "总线已清空")
    }
}
