package com.wy.openx.plugin.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件 UI 的节点树。
 *
 * 协议** Compose DSL**（`ToolPkgComposeDslParser`），因为：
 *  1. 那套协议已被验证可行（JS Proxy 造节点 → JSON → 宿主渲染）
 *  
 *
 * 线格式（JS 侧 `__operit_render_compose_dsl` 的返回值）：
 * ```json
 * {
 *   "tree":   { "type": "Column", "props": {...}, "children": [...], "slots": {...} },
 *   "state":  { "url": "http://...", "token": "***" },
 *   "memo":   { "key": value },
 *   "actions": { "__action:xxx": {"type": "handler", "name": "handleConnect"} }
 * }
 * ```
 *
 * `slots` 用于具名插槽（如 `Scaffold` 的 topBar/bottomBar）。
 */
data class UiNode(
    val type: String,
    val props: Map<String, Any?>,
    val children: List<UiNode>,
    val slots: Map<String, List<UiNode>> = emptyMap(),
    /**
     * 插件自定义主题（token → `#hex`）。由宿主在解析后注入，JS 侧不感知。
     *
     * 存在节点上而不是用全局变量，是为了避开"面板和悬浮窗同时渲染时串台"：
     * 两者都在主线程重组，一个进程级主题表必然会互相覆盖。
     */
    val theme: Map<String, String> = emptyMap()
) {
    /** 递归注入主题（子节点与具名插槽一并处理）。 */
    fun withTheme(t: Map<String, String>): UiNode = if (t.isEmpty()) {
        this
    } else {
        copy(
            theme = t,
            children = children.map { it.withTheme(t) },
            slots = slots.mapValues { (_, list) -> list.map { it.withTheme(t) } }
        )
    }

    /**
     * 树里是否有「必须有确定高度」的组件。
     *
     * WebView 没有内在高度、完全依赖外部约束，放进 `verticalScroll`（给子项的是
     * 无限高度约束）会塔成 0 高；PullToRefreshBox 内部已自带滚动，外层再套会冲突。
     *
     * `Lazy*` 分两种：带 `height` 的是插件有意让它嵌在外层滚动里（见
     * `RenderLazyColumn` 里对 height 的注释），外层可以正常套滚动；不带 height 的
     * 放进无限约束里会撑满父容器、退化成全量渲染，才需要外层保持有界。
     * 之前把带 height 的也当「必须外部有界」，导致音频/插件发现器这类主面板滑不动。
     */
    fun needsBoundedHeight(): Boolean {
        val t = type.lowercase()
        if (t == "webview" || t == "pulltorefreshbox") return true
        if (t in LAZY_TYPES && !hasFixedHeight()) return true
        return children.any { it.needsBoundedHeight() } ||
            slots.values.flatten().any { it.needsBoundedHeight() }
    }

    /** `height` 属性是否给了可用数值（与 `UiNodeParser.numOrNull` 同语义）。 */
    private fun hasFixedHeight(): Boolean = when (val v = props["height"]) {
        is Number -> v.toFloat() > 0f
        is String -> v.toFloatOrNull()?.let { it > 0f } ?: false
        else -> false
    }

    private companion object {
        val LAZY_TYPES = setOf(
            "lazycolumn", "lazy_column", "lazyrow", "lazy_row",
            "lazyverticalgrid", "lazy_vertical_grid",
            "lazygrid", "lazy_grid", "grid", "gridview"
        )
    }
}

/** 一次渲染的完整结果。 */
data class UiRenderResult(
    val tree: UiNode,
    val state: Map<String, Any?>,
    val memo: Map<String, Any?>
)

/**
 * 节点解析器。
 *
 * 容错优先级：**尽量渲染出一部分**，而不是整棵树失败。插件写错一个属性，
 * 不应该导致整个界面空白 —— 所以单节点解析失败时记 `error` 节点而非抛出。
 */
object UiNodeParser {

    /** 解析 JS 返回的渲染结果；结构不合法（连 tree 都没有）时返回 null。 */
    /**
     * 解析一次渲染结果。
     *
     * [theme] 可空：manifest 用 Gson 直读，**Gson 不走 Kotlin 默认值**——没声明
     * `theme` 字段的插件拿到的就是 null（不是 emptyMap）。所以这里必须接可空，
     * 不能靠默认参数兜底。
     */
    fun parseRenderResult(raw: Any?, theme: Map<String, String>? = null): UiRenderResult? {
        val root = toJsonObject(raw) ?: return null
        val tree = parseNode(root.opt("tree")) ?: return null
        return UiRenderResult(
            tree = tree.withTheme(theme ?: emptyMap()),
            state = toMap(root.opt("state")),
            memo = toMap(root.opt("memo"))
        )
    }

    private fun parseNode(value: Any?): UiNode? {
        val obj = toJsonObject(value) ?: return null
        val type = obj.optString("type").trim()
        if (type.isBlank()) return null

        val props = toMap(obj.opt("props")).toMutableMap()

        val children = mutableListOf<UiNode>()
        when (val raw = obj.opt("children")) {
            is JSONArray -> for (i in 0 until raw.length()) {
                parseNode(raw.opt(i))?.let { children += it }
            }
            is JSONObject -> parseNode(raw)?.let { children += it }
            null -> Unit
            else -> parseNode(raw)?.let { children += it }
        }

        val slots = mutableMapOf<String, List<UiNode>>()
        val rawSlots = obj.opt("slots")
        if (rawSlots is JSONObject) {
            val keys = rawSlots.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = rawSlots.opt(key)
                val list = mutableListOf<UiNode>()
                when (arr) {
                    is JSONArray -> for (i in 0 until arr.length()) {
                        parseNode(arr.opt(i))?.let { list += it }
                    }
                    is JSONObject -> parseNode(arr)?.let { list += it }
                    else -> parseNode(arr)?.let { list += it }
                }
                if (list.isNotEmpty()) slots[key] = list
            }
        }

        return UiNode(type = type, props = props, children = children, slots = slots)
    }

    // ---------------------------------------------------------------- 值转换

    private fun toJsonObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
            ?: runCatching { JSONObject("$value") }.getOrNull()
        is Map<*, *> -> runCatching { JSONObject(value as Map<*, *>) }.getOrNull()
        else -> null
    }

    /** ANY → Map（props 用）。JSONObject 转成 Kotlin Map 方便取值。 */
    fun toMap(value: Any?): Map<String, Any?> {
        if (value !is JSONObject) return emptyMap()
        val out = mutableMapOf<String, Any?>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = normalize(value.opt(k))
        }
        return out
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> toMap(value)
        is JSONArray -> (0 until value.length()).map { normalize(value.opt(it)) }
        else -> value
    }

    // ---------------------------------------------------------------- 取值助手

    /** 字符串属性。 */
    fun UiNode.str(key: String, def: String = ""): String =
        props[key]?.toString() ?: def

    /** 可空字符串属性（空串视为 null）。 */
    fun UiNode.strOrNull(key: String): String? =
        props[key]?.toString()?.takeIf { it.isNotBlank() }

    /** 布尔属性。JS 的 true/false 经 JSON 过来是 Boolean；字符串 "true" 也认。 */
    fun UiNode.bool(key: String, def: Boolean = false): Boolean = when (val v = props[key]) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.equals("true", true) || v == "1"
        else -> def
    }

    /** 数字属性（dp / sp / 倍数）。 */
    fun UiNode.num(key: String, def: Float): Float = when (val v = props[key]) {
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull() ?: def
        else -> def
    }

    fun UiNode.numOrNull(key: String): Float? = when (val v = props[key]) {
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull()
        else -> null
    }

    fun UiNode.int(key: String, def: Int): Int = num(key, def.toFloat()).toInt()

    /**
     * 从 props 里取"动作标识"。
     *
     * JS 侧 `onClick: handleConnect` 会被 runtime 换成 `{"__actionId":"..."}`
     * 或字符串 `"__action:xxx"`（两种形式 Operit 都支持，这里都认）。
     */
    fun UiNode.actionId(key: String): String? = when (val v = props[key]) {
        is String -> {
            val t = v.trim()
            when {
                t.startsWith("__action:") -> t.removePrefix("__action:").trim().ifBlank { null }
                t.isNotBlank() && t != "__action" -> t
                else -> null
            }
        }
        is Map<*, *> -> v["__actionId"]?.toString()?.trim()?.ifBlank { null }
        else -> null
    }

    /**
     * 颜色属性：支持 `#RRGGBB` / `#AARRGGBB` / 主题 token 名（如 "primary"）。
     *
     * 插件在 plugin.json 声明了 theme 时，token 会被换成它定义的具体色值；
     * **只在插件真的写了这个属性时才查主题**，没写就返回 null、由调用方用默认色。
     *
     * 为什么不"没写 color 也自动跟着主题变"：同一个 key 在不同组件里语义不同。
     * `Text` 的 color 是正文色，`Button` 的 color 却是强调色（默认 Accent），
     * 自动兜底会让按钮文字变成正文色，深底浅字就可能相反而看不清。刷新行为
     * 可以接受，观感错乱不行。
     */
    fun UiNode.color(key: String): String? {
        val raw = props[key]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return theme[raw.trim().lowercase()] ?: raw
    }
}

/**
 * 一次动作派发的结果。
 *
 * @param result 派发后的新节点树（失败时可能仍是上一帧）
 * @param needsRerender JS 侧 setState 是否被调用过 → 宿主需要再渲染一次
 */
data class DispatchOutcome(
    val result: UiRenderResult?,
    val needsRerender: Boolean
)
