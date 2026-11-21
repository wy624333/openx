package com.wy.openx.plugin

import com.google.gson.Gson

/**
 * AI 会话的**聚合状态**快照（给插件看的那一份）。
 *
 * 刻意只放“状态”不放“内容”：没有对话正文、没有思考正文、没有工具参数。
 * 插件能知道「在忙 / 在跑哪个工具 / 上下文用了多少 / 在等你确认」，
 * 但拿不到任何用户输入或模型输出 —— 这是会话数据能对外开放的边界。
 */
data class HostSessionSnapshot(
    /** 是否正在生成（一轮请求进行中）。 */
    val busy: Boolean = false,
    /** 是否处于“思考中”（已开始但还没出正文/工具）。 */
    val thinking: Boolean = false,
    /** 正在执行的工具名（宿主原始名，如 `web_search`）；没有则为 null。 */
    val tool: String? = null,
    /** 在等你操作的类型：`approval`（工具授权）/ `terminal`（危险命令确认）/ `question`（向你提问）；没有则 null。 */
    val pending: String? = null,
    /** 最近一次请求的输入上下文 token 数（用来判断“上下文吃得多不多”）。 */
    val contextTokens: Int = 0,
    /** 是否处于网络重连（请求失败正在退避重试）。 */
    val reconnecting: Boolean = false
)

/**
 * 会话状态提供者：由 `MainViewModel` 在启动时注入。
 *
 * 为什么用全局单例而不是把 ViewModel 传进插件桥：
 * 插件桥（`PluginUiHostBridge`）活在插件 JS 生命周期里，而 UI 状态在 ViewModel 里；
 * 两者没有父子关系。注册一个只读 provider，比反向持有 ViewModel 干净得多。
 *
 * 插件侧对应 `ctx.host.session()`（需要 `session` 权限）。
 */
object HostSessionState {

    /** ViewModel 注册的快照读取函数；未注册时返回空快照（全 false/0）。 */
    @Volatile
    private var provider: (() -> HostSessionSnapshot)? = null

    fun register(fn: () -> HostSessionSnapshot) {
        provider = fn
    }

    fun snapshot(): HostSessionSnapshot = runCatching { provider?.invoke() }
        .getOrNull() ?: HostSessionSnapshot()

    /** 序列化成插件可读的 JSON（带 `ok` 字段，与其它宿主桥的返回约定一致）。 */
    fun snapshotJson(): String {
        val json = Gson().toJsonTree(snapshot()).asJsonObject
        json.addProperty("ok", true)
        return json.toString()
    }
}