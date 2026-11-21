package com.wy.openx.plugin.ui

import com.wy.openx.plugin.ui.UiNodeParser.bool
import com.wy.openx.plugin.ui.UiNodeParser.numOrNull
import com.wy.openx.plugin.ui.UiNodeParser.strOrNull

/**
 * 组件的「状态绑定」取值助手。
 *
 * ## 为什么单独一个文件
 * 一期时这些是 `UiRenderer.kt` 里的 private 函数，二期新增的组件
 * （Slider / Dropdown / Tabs / ListItem / Chip / Dialog …）全都要用到同一套逻辑。
 * 与其在每个渲染文件里复制一遍，不如提成包内共享。
 *
 * ## 绑定规则（对插件作者的统一约定）
 * 任何"当前值"都可以用两种方式给：
 *  1. 字面量：`{ value: 42 }` / `{ checked: true }` —— 静态展示
 *  2. 绑定 state：`{ stateKey: 'myKey' }`（别名 `bind`）—— 由 `ctx.useState('myKey', …)` 驱动
 *
 * 绑定时**以 JS 侧 state 为准**：state 是唯一真相源，宿主只做镜像。
 * 这样"点了按钮 → setState → 重渲染"这条链路上不会出现两边各存一份导致的错位。
 */
private fun UiNode.boundKey(): String? =
    strOrNull("stateKey") ?: strOrNull("bind")

/** 读绑定 state 的原始值；没绑定返回 null。 */
private fun UiNode.boundValue(ctx: UiRenderContext): Any? =
    boundKey()?.let { ctx.stateOf(it) }

/**
 * 文本值：优先绑定 state，其次取字面量 props。
 * TextField 用这个，所以输入框在未绑定时也能显示静态内容。
 */
fun UiNode.stateStringOrProp(ctx: UiRenderContext, propKey: String): String {
    boundKey()?.let { return ctx.stateOf(it)?.toString().orEmpty() }
    return props[propKey]?.toString().orEmpty()
}

/** 布尔值：优先绑定 state，其次取字面量 props（字符串 "true"/"1" 也认）。 */
fun UiNode.boolOrState(ctx: UiRenderContext, propKey: String, def: Boolean = false): Boolean {
    boundKey()?.let { key ->
        return when (val v = ctx.stateOf(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", true) || v == "1"
            else -> false
        }
    }
    return bool(propKey, def)
}

/** 浮点值（Slider 用）：绑定优先，其次字面量 `value`；都没有返回 null。 */
fun UiRenderContext.stateFloat(node: UiNode): Float? {
    node.boundKey()?.let { key ->
        return when (val v = stateOf(key)) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull()
            else -> null
        }
    }
    return node.numOrNull("value")
}

/**
 * 整数索引（Dropdown / Tabs 用）。
 *
 * 绑定 state 时读 state；**没绑定时也读字面量**——否则像 `Tabs({ selectedIndex: tab })`
 * 这种"宿主每帧把 state 当 props 传下去"的写法会永远取不到值，表现为
 * 「点标签不切换、选中态永远停在第一项」。
 */
fun UiRenderContext.stateInt(node: UiNode): Int? {
    node.boundKey()?.let { key ->
        return when (val v = stateOf(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }
    // 无 stateKey：回退到字面量 props
    node.numOrNull("selectedIndex")?.let { return it.toInt() }
    node.numOrNull("selected")?.let { return it.toInt() }
    return null
}
