package com.wy.openx.tools

import com.termux.terminal.TerminalSession

/**
 * 终端标签的运行状态。Finished 保留在列表里不移除，供用户/AI 回看输出。
 */
sealed interface RunState {
    data object Running : RunState
    data class Finished(val exitCode: Int) : RunState
}

/** 后台任务完成通知携带的终端输出行数。 */
const val TAIL_LINES = 50

/** 截取终端 transcript 的最后 n 行；null 输入返回 null。 */
fun String?.takeTailLines(n: Int): String? =
    this?.lines()?.takeLast(n)?.joinToString("\n")

/**
 * 后台命令结束时 emit 的事件，供 AI 订阅（notify=true 的后台标签）。
 */
data class TabFinishedEvent(
    val tabId: String,
    val title: String,
    val command: String?,
    val exitCode: Int,
    /** 发起该后台命令的会话 id；回调据此路由回原会话，而非用户当前所在会话。 */
    val sourceSessionId: String?,
    /** 结束时终端 transcript 的最后 [TAIL_LINES] 行快照。 */
    val tailOutput: String? = null,
    /**
     * 拥有该后台任务的 **AI 会话** id（0 = 无主）。MainViewModel 据此把完成通知路由回发起它的会话
     * ——不能只看 `sourceSessionId`（那是终端标签 id）。见 [TerminalTab.ownerConversationId]。
     */
    val ownerConversationId: Long = 0L
)

/**
 * 一个终端标签：会话 + 元数据。
 *
 * [TerminalEngine] 持有全部标签（进程级常驻），`session` 指向活动标签的会话，
 * 键盘输入作用于活动标签。后台标签（[isBackground]）由 AI 的 startBackgroundCommand 创建，
 * 不抢占焦点，按 id 控制；AI 的 execute 命令走一次性进程、不建立标签（见 TerminalEngine.executeAi）。
 * 非活动标签的会话继续在后台保活，切回时复用同一 TerminalSession。
 */
class TerminalTab(
    val id: String,
    title: String,
    val session: TerminalSession,
    val command: String?,
    val isBackground: Boolean = false,
    val notifyOnExit: Boolean = false,
    /** 发起该后台命令的会话 id；交互标签为 null。回调据此路由回原会话。 */
    val sourceSessionId: String? = null,
    /**
     * 【P4-3】拥有该标签的 **AI 会话** id（0 = 无主：用户手动开的交互标签、子代理等）。
     *
     * 与 [sourceSessionId] 不同：那个是「终端标签 id」（用于后台任务完成通知路由回哪个终端），
     * 这个是「哪个 AI 会话创建的」（用于并发隔离）。两者语义不同，不能复用。
     *
     * 多会话并行时，`get_output` 不传 id 若取全局 `activeTabId`，会读到**另一个会话**的终端画面，
     * 并把那些输出当成本任务的结果回传给模型 —— 不崩不报错，只是拿到错误数据、很难察觉。
     */
    val ownerConversationId: Long = 0L
) {
    var title: String = title
        internal set

    var runState: RunState = RunState.Running

    /**
     * 是否已向 AI 发出过完成事件。兜底监控与 onFinished 都可能触发完成，
     * 用此标记保证同一命令只回调一次，避免进程迟退时重复通知。
     */
    @Volatile
    var finishedNotified: Boolean = false
}

/**
 * 终端标签摘要，供 AI 的 terminal 工具列出标签时使用（不携带 session 等运行时对象）。
 */
data class TabInfo(
    val id: String,
    val title: String,
    val isBackground: Boolean,
    val running: Boolean,
    val command: String?,
    /** 【P4-3】该标签属于哪个 AI 会话（0 = 无主）。供工具按会话过滤列表。 */
    val ownerConversationId: Long = 0L
)