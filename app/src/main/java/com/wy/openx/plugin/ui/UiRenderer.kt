package com.wy.openx.plugin.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.plugin.ui.UiNodeParser.actionId
import com.wy.openx.plugin.ui.UiNodeParser.bool
import com.wy.openx.plugin.ui.UiNodeParser.color
import com.wy.openx.plugin.ui.UiNodeParser.int
import com.wy.openx.plugin.ui.UiNodeParser.num
import com.wy.openx.plugin.ui.UiNodeParser.numOrNull
import com.wy.openx.plugin.ui.UiNodeParser.str
import com.wy.openx.plugin.ui.UiNodeParser.strOrNull
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.ButtonBg
import com.wy.openx.ui.theme.ButtonFg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/** 自行掌管内衬的容器：外层 padding 交给内层容器施加，避免叠加。 */
private val PADDED_CONTAINERS = setOf("card", "surface")

/** 渲染上下文：把"动作回调"和"状态读写"传给每个节点。 */
class UiRenderContext(
    /**
     * 触发动作（按钮点击 / 输入变化等），交给 JS 侧处理。
     *
     * [rawValue] 为 true 时，宿主传的是 {value: v} 包装且 JS 处理器要的是**裸值**
     * （Slider 的 onValueChange、Switch/Checkbox 的 onCheckedChange 就是直接绑定
     * useState setter 的）。其余动作一律传整个 payload —— Tabs/Dropdown 的
     * onSelect 也带 value 字段，拆包会丢掉 index。
     */
    val onAction: (actionId: String, payload: Map<String, Any?>, rawValue: Boolean) -> Unit,
    /** 读状态（TextField 的 value 由 JS 的 useState 驱动）。 */
    val stateOf: (key: String) -> Any?,
    /** 未知组件的占位提示是否显示（调试用）。 */
    val showUnknown: Boolean = true,
    /**
     * 插件已获得的权限集。
     *
     * 渲染器需要它来做**能力相关的降级**：最典型的是 `Image` —— 网络图没有
     * `http` 权限时不应发起请求，直接渲染"缺权限"占位，否则会变成一个
     * 绕过权限的取图通道。
     */
    val permissions: Set<com.wy.openx.plugin.PluginPermission> = emptySet(),
    /**
     * **同步**调用插件 JS 函数（传 actionId + payload JSON，返回结果 JSON 串）。
     *
     * 只给 WebView 的同步回调（shouldInterceptRequest / shouldOverrideUrlLoading）用：
     * Android 要求它们当场返回结果，而 [onAction] 是异步派发，等不了。
     */
    val invokeSync: ((actionId: String, payloadJson: String) -> String?)? = null,
    /**
     * 渲染世代号。插件重载时递增，用来重置 [RenderUiTree] 里「onLoad 只派发一次」的标记。
     *
     * 不重置的后果：热重载后 `dispatched` 仍是 true，`onLoad` 再也不派发 ——
     * 界面画得出来，但插件的初始化（检查环境、拉数据）从未启动，
     * 表现成「状态停在初始值、日志一条没有」，极难定位。
     */
    val renderEpoch: Int = 0,
    /**
     * 插件安装目录的绝对路径。`Image` 的 `asset://` 包内素材靠它解析。
     *
     * 为空时只是不支持 `asset://`，其它源（data URI / http / 绝对路径）不受影响。
     */
    val pluginDir: String? = null,
    /**
     * 图片占位是否静默（不画灰块/错误文案）。
     *
     * 桌面浮窗（无外壳 + 透明底）里应该用它：加载一帧图、或某张图挂了，
     * 宠物身上冒出一块灰色圆角方块很突兵（用户反馈过）。面板里保留占位便于排查。
     */
    val hidePlaceholder: Boolean = false
)

/**
 * 插件 UI 渲染器：把 [UiNode] 树变成真 Compose。
 *
 * 组件支持策略：**只实现真用得上的**。JS 侧用 Proxy 造节点，任何名字都能传进来，
 * 所以这里不需要登记白名单 —— 不认识的 `type` 渲染成一个占位提示，
 * 插件作者立刻知道"这个组件宿主不支持"，比静默忽略更容易排查。
 *
 * 已支持的组件见 [RenderUiNode] 的 when 分支；二期新增的交互类组件在
 * [RenderUiNodeExt]，浮层类在 [RenderUiNodeOverlay]。
 */
/**
 * 渲染整棵树，并派发根节点的 `onLoad`。
 *
 * `onLoad` 是 Operit 的**组件生命周期回调**，插件普遍用它启动初始化——例如侧边栏插件：
 * ```
 * UI.Box({ onLoad: async () => { await ensureServer(); } }, content)
 * ```
 * 不派发的话界面能画出来，但初始化从未启动：表现为「进度条卡在 0%、不报错、也不写日志」，
 * 因为根本没有东西去调它。
 *
 * 语义照搬 Operit 官方 `ToolPkgComposeDslScreen`：**只认根节点**、只派发一次、payload 为空，
 * 且先 `withFrameNanos` 让首帧先画出来再派发（否则初始化会挡住画面，用户看到的就是白屏）。
 */
@Composable
fun RenderUiTree(root: UiNode, ctx: UiRenderContext, modifier: Modifier = Modifier) {
    // 每帧 render 都会重建 JS 侧 action 表，id 每次都不同——所以这里**不能**把
    // root / actionId 放进 LaunchedEffect 的 key：插件树每帧重建、id 每帧变化，
    // 协程会被不断取消重启，「等一帧再派发」永远等不到，初始化就从未启动
    // （表现为进度条卡住、日志里一条命令都没有）。
    // 改用 SideEffect：它在重组提交后执行，不受取消影响；dispatched 保证只派发一次。
    val onLoadActionId = root.actionId("onLoad")
    // key 里带 renderEpoch：插件重载后要重新派发 onLoad，否则初始化永远不跑。
    var dispatched by remember(ctx.renderEpoch) { mutableStateOf(false) }
    if (!dispatched && !onLoadActionId.isNullOrBlank()) {
        dispatched = true
        SideEffect {
            com.wy.openx.container.FileLogger.i("UiRenderer", "派发根节点 onLoad：$onLoadActionId")
            ctx.onAction(onLoadActionId, emptyMap(), false)
        }
    }
    RenderUiNode(root, ctx, modifier)
}

/**
 * 宿主驱动的循环动画：呼吸 / 浮动 / 倾斜。
 *
 * 为什么不用插件定时器改这些值：那样每帧要重渲染整棵 UI 树（重建 JS 树 + 重组），
 * 顶多几帧每秒，看着就是一跳一跳。交给 Compose 的 infinite transition 是 60fps、
 * 零 JS 开销，且不影响布局（走 graphicsLayer）。
 *
 * ```js
 * UI.Image({ src: 'asset://assets/pet.webp', width: 200, height: 200,
 *   animate: { bob: 2.4, bobMs: 1800, tilt: 1.2, tiltMs: 4200, breathe: 0.015, breatheMs: 2200 } })
 * ```
 *
 * 与静态 `offsetY` / `rotate` / `scale` 是**叠加**关系（两层 graphicsLayer），
 * 插件可以一边给静态姿态，一边让它动。
 */
@Composable
private fun Modifier.nodeAnimate(node: UiNode): Modifier {
    val anim = node.props["animate"] as? Map<*, *> ?: return this

    fun num(key: String, def: Float): Float = when (val v = anim[key]) {
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull() ?: def
        else -> def
    }

    val bob = num("bob", 0f)
    val tilt = num("tilt", 0f)
    val breathe = num("breathe", 0f)
    if (bob == 0f && tilt == 0f && breathe == 0f) return this

    val transition = rememberInfiniteTransition(label = "nodeAnim")
    val twoPi = (2f * Math.PI).toFloat()

    fun loop(ms: Float) = infiniteRepeatable<Float>(
        animation = tween(ms.toInt().coerceIn(200, 60_000), easing = LinearEasing)
    )

    val bobAngle by transition.animateFloat(
        initialValue = 0f, targetValue = twoPi,
        animationSpec = loop(num("bobMs", 1800f)), label = "bob"
    )
    val tiltAngle by transition.animateFloat(
        initialValue = 0f, targetValue = twoPi,
        animationSpec = loop(num("tiltMs", 4200f)), label = "tilt"
    )
    val breatheAngle by transition.animateFloat(
        initialValue = 0f, targetValue = twoPi,
        animationSpec = loop(num("breatheMs", 2200f)), label = "breathe"
    )

    return this.graphicsLayer {
        if (bob != 0f) translationY = (kotlin.math.sin(bobAngle) * bob).dp.toPx()
        if (tilt != 0f) rotationZ = kotlin.math.sin(tiltAngle) * tilt
        if (breathe != 0f) {
            val s = 1f + kotlin.math.sin(breatheAngle) * breathe
            scaleX = s
            scaleY = s
        }
    }
}

/**
 * 节点级长按。
 *
 * 组件清单原本只有 onClick：插件想做「按住不放」的交互（摸摸宠物、长按弹菜单）
 * 无处可写。这里补成通用属性：任何节点写 `onLongClick: handler` 都生效。
 *
 * 用 detectTapGestures 而不是 detectDragGestures —— 它只在长按阈值到达后才触发，
 * 不会抢子节点自己的 onClick。
 */
private fun Modifier.nodeLongPress(node: UiNode, ctx: UiRenderContext): Modifier {
    val longPressId = node.actionId("onLongClick") ?: return this
    return pointerInput(longPressId) {
        detectTapGestures(onLongPress = { ctx.onAction(longPressId, emptyMap(), false) })
    }
}

@Composable
fun RenderUiNode(node: UiNode, ctx: UiRenderContext, modifier: Modifier = Modifier) {
    // Card / Surface 的内衬由内层容器统一施加（见 containerPaddingModifier），
    // 这里跳过外层 padding，避免叠加成两倍。
    val applyPadding = node.type.lowercase() !in PADDED_CONTAINERS
    // 长按是节点通用的（“按住宠物不放”这类交互），挂在公共 modifier 链上，
    // 不必每个组件自己实现一遍。
    val m = modifier
        .applyNodeModifier(node, applyPadding = applyPadding)
        .nodeLongPress(node, ctx)
        .nodeAnimate(node)
    // 自由绘制：canvas 不是「占位型」组件，先于扩展组件处理
    if (RenderUiNodeCanvas(node, ctx, m)) return
    // 扩展组件优先（二期新增的交互类 + 浮层类）。
    // 放最前面而不是 when 的 else 里：浮层（Dialog/BottomSheet）不占父布局位置，
    // 让它先于所有布局分支处理，避免被当成普通子节点参与流式排版。
    if (RenderUiNodeExt(node, ctx, m)) return
    if (RenderUiNodeExt2(node, ctx, m)) return
    // 音频播放条（宿主渲染，读 AudioPlaybackHub 状态）
    if (RenderUiNodeAudio(node, ctx, m)) return
    if (RenderUiNodeOverlay(node, ctx)) return
    // Operit 兼容：侧边栏类插件的界面本体就是一个网页
    if (RenderUiNodeWebView(node, ctx, m)) return
    when (node.type.lowercase()) {
        // ---------------------------------------------------------- 布局
        "column" -> Column(
            modifier = m,
            verticalArrangement = verticalArrangement(node.str("verticalArrangement")),
            horizontalAlignment = horizontalAlignment(node.str("horizontalAlignment"))
        ) {
            // weight/grow 只能在这里展开：Modifier.weight 是 ColumnScope/RowScope
            // 的扩展，放进通用的 applyNodeModifier 根本编译不过。
            node.children.forEach { child ->
                val weight = child.numOrNull("weight") ?: if (child.bool("grow")) 1f else null
                if (weight != null && weight > 0f) RenderUiNode(child, ctx, Modifier.weight(weight))
                else RenderUiNode(child, ctx)
            }
        }

        "row" -> if (node.bool("wrap")) {
            FlowRow(
                modifier = m,
                horizontalArrangement = horizontalArrangement(node.str("horizontalArrangement")),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // FlowRow 的作用域不是 RowScope，没有 weight；换行布局里权重本来也没意义
                node.children.forEach { RenderUiNode(it, ctx) }
            }
        } else {
            Row(
                modifier = m,
                horizontalArrangement = horizontalArrangement(node.str("horizontalArrangement")),
                verticalAlignment = verticalAlignment(node.str("verticalAlignment"))
            ) {
                node.children.forEach { child ->
                    val weight = child.numOrNull("weight") ?: if (child.bool("grow")) 1f else null
                    if (weight != null && weight > 0f) RenderUiNode(child, ctx, Modifier.weight(weight))
                    else RenderUiNode(child, ctx)
                }
            }
        }

        "box" -> Box(modifier = m, contentAlignment = boxAlignment(node.str("contentAlignment"))) {
            node.children.forEach { RenderUiNode(it, ctx) }
        }

        "spacer" -> Spacer(
            Modifier
                .width((node.numOrNull("width") ?: 0f).dp)
                .height((node.numOrNull("height") ?: 0f).dp)
        )

        "lazycolumn", "lazy_column" -> LazyColumn(modifier = m) {
            itemsIndexed(node.children) { _, child -> RenderUiNode(child, ctx) }
        }

        "lazyrow", "lazy_row" -> androidx.compose.foundation.lazy.LazyRow(modifier = m) {
            itemsIndexed(node.children) { _, child -> RenderUiNode(child, ctx) }
        }

        // 网格："columns" 给整数（固定列数）或 "auto"（按最小宽度自适应）
        "lazyverticalgrid", "lazy_vertical_grid", "lazygrid", "lazy_grid", "grid", "gridview" ->
            LazyVerticalGrid(
                columns = if (node.str("columns") == "auto") {
                    GridCells.Adaptive((node.numOrNull("minCellWidth") ?: 120f).dp)
                } else {
                    GridCells.Fixed(node.int("columns", 2).coerceAtLeast(1))
                },
                modifier = m,
                verticalArrangement = Arrangement.spacedBy((node.numOrNull("spacing") ?: 8f).dp),
                horizontalArrangement = Arrangement.spacedBy((node.numOrNull("spacing") ?: 8f).dp)
            ) {
                gridItems(node.children) { child -> RenderUiNode(child, ctx) }
            }

        "card" -> Surface(
            modifier = m,
            color = UiTheme.colorOr(node.color("background"), Panel),
            shape = RoundedCornerShape((node.numOrNull("cornerRadius") ?: 12f).dp)
        ) {
            Column(node.containerPaddingModifier(12f)) {
                node.children.forEach { RenderUiNode(it, ctx) }
            }
        }

        "surface" -> {
            // Operit 用 containerColor / contentColor 表达容器色与内容色（官方
            // ToolPkgComposeDslGeneratedRenderers 读的就是这两个）。只认 background
            // 会让插件的按钮/卡片底色整个丢掉 —— 底色回退成面板色，插件指定的浅色
            // 文字糊在上面就看不清（小恐龙的按钮真机踩过）。
            val containerRaw = node.color("containerColor") ?: node.color("background")
            // 插件用 {type:"pill"} 表示胶囊形，不认它就退成 8dp 圆角
            val shapeObj = node.props["shape"] as? Map<*, *>
            val shape = if (shapeObj?.get("type")?.toString()?.equals("pill", true) == true) {
                RoundedCornerShape(50)
            } else {
                RoundedCornerShape((node.numOrNull("cornerRadius") ?: 8f).dp)
            }
            val onClickId = node.actionId("onClick")
            // 必须用带 onClick 的重载：Surface 是插件常用的按钮载体
            // （小恐龙的「开始/暂停」「重置」），用无 onClick 的那版会渲染成
            // 纯装饰面板 —— 看着有底有字，点了没反应。
            if (onClickId != null) {
                Surface(
                    onClick = { ctx.onAction(onClickId, emptyMap(), false) },
                    modifier = m,
                    shape = shape,
                    color = UiTheme.colorOr(containerRaw, Panel),
                    contentColor = UiTheme.colorOr(node.color("contentColor"), TextPrimary),
                    enabled = !node.bool("disabled")
                ) {
                    Box(node.containerPaddingModifier(0f)) {
                        node.children.forEach { RenderUiNode(it, ctx) }
                    }
                }
            } else {
                Surface(
                    modifier = m,
                    shape = shape,
                    color = UiTheme.colorOr(containerRaw, Panel),
                    contentColor = UiTheme.colorOr(node.color("contentColor"), TextPrimary)
                ) {
                    Box(node.containerPaddingModifier(0f)) {
                        node.children.forEach { RenderUiNode(it, ctx) }
                    }
                }
            }
        }

        // ---------------------------------------------------------- 文本
        "text" -> Text(
            text = node.str("text"),
            modifier = m,
            color = UiTheme.colorOr(node.color("color"), TextPrimary),
            fontSize = node.num("fontSize", 14f).sp,
            fontWeight = if (node.bool("bold")) FontWeight.Bold else FontWeight.Normal,
            textAlign = textAlign(node.str("textAlign")),
            maxLines = node.int("maxLines", Int.MAX_VALUE),
            overflow = TextOverflow.Ellipsis
        )

        // ---------------------------------------------------------- 输入
        "textfield", "text_field", "outlinedtextfield" -> {
            val action = node.actionId("onValueChange") ?: node.actionId("onChange")
            val external = node.stateStringOrProp(ctx, "value")
            // 用 TextFieldValue 维护光标：纯 String 重载在插件状态回环
            // （输入 → onAction → JS setState → 重渲染）时 selection 会被重置，
            // 表现为「光标每次输入都跳到文字最前面」。
            //
            // ⚠ 外部值只在「还没拿到过」时补一次，之后不再跟随。
            //
            // 两个坑都出在这里：
            //  · 若每次都跟随 external：插件回环是异步的，输入瞬间 external 还停在旧值，
            //    覆盖会把刚输入的字符和输入法组合串（composition）一起清掉 ——
            //    表现为「候选词一闪一闪、字母时有时无」。
            //  · 若完全不管 external：初次组合时插件状态可能还没传过来（external 为空），
            //    输入框就永远空着，连已有的值（如已保存的扫描目录）都显示不出来。
            // 所以：只在第一次拿到非空外部值时补齐一次，之后交给 TextField 自己维护。
            //
            // 另外两个要点：
            //  · Compose 的 remember 按「树中位置」复用状态。插件切 Tab 时，若两个 Tab 的
            //    输入框落在同一个位置，状态会被沿用 → 表现成「切了 Tab 输入框内容还是旧的」。
            //    所以支持插件传 `key`（或 `fieldKey`）：同位置不同 key 视为不同输入框，重新接管。
            //  · 未聚焦时跟随外部值：插件切 Tab 改 value、或程序化重置内容都能生效；
            //    聚焦期间仍不跟随，避免打断输入与输入法组合串。
            val fieldKey = node.strOrNull("key") ?: node.strOrNull("fieldKey") ?: ""
            var tv by remember(fieldKey) { mutableStateOf(TextFieldValue(external, TextRange(external.length))) }
            var focused by remember(fieldKey) { mutableStateOf(false) }
            if (!focused && tv.text != external) {
                tv = TextFieldValue(external, TextRange(external.length))
            }
            OutlinedTextField(
                value = tv,
                onValueChange = { new ->
                    tv = new
                    action?.let { a -> ctx.onAction(a, mapOf("value" to new.text), true) }
                },
                modifier = m.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                label = node.strOrNull("label")?.let { { Text(it) } },
                placeholder = node.strOrNull("placeholder")?.let { { Text(it) } },
                singleLine = node.bool("singleLine", true)
            )
        }

        "button" -> androidx.compose.material3.Button(
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } },
            modifier = m,
            enabled = !node.bool("disabled"),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = ButtonBg,
                contentColor = ButtonFg
            )
        ) {
            Text(node.str("text", "按钮"), fontSize = node.num("fontSize", 14f).sp)
        }

        "textbutton", "text_button" -> TextButton(
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } },
            modifier = m
        ) {
            Text(node.str("text", "按钮"), color = UiTheme.colorOr(node.color("color"), Accent))
        }

        "iconbutton", "icon_button" -> IconButton(
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } },
            modifier = m
        ) {
            val iconName = node.str("icon")
            Icon(
                imageVector = UiIcons.of(iconName),
                contentDescription = node.strOrNull("contentDescription"),
                tint = UiTheme.colorOr(node.color("color"), TextPrimary)
            )
        }

        "switch" -> Switch(
            checked = node.boolOrState(ctx, "checked"),
            onCheckedChange = { v -> node.actionId("onCheckedChange")?.let { ctx.onAction(it, mapOf("value" to v), true) } },
            modifier = m
        )

        "checkbox" -> Checkbox(
            checked = node.boolOrState(ctx, "checked"),
            onCheckedChange = { v -> node.actionId("onCheckedChange")?.let { ctx.onAction(it, mapOf("value" to v), true) } },
            modifier = m
        )

        // ---------------------------------------------------------- 其他
        "icon" -> Icon(
            imageVector = UiIcons.of(node.str("icon")),
            contentDescription = node.strOrNull("contentDescription"),
            tint = UiTheme.colorOr(node.color("color"), TextPrimary),
            modifier = m.size((node.numOrNull("size") ?: 24f).dp)
        )

        "divider", "horizontaldivider", "horizontal_divider" -> HorizontalDivider(
            modifier = m,
            color = UiTheme.colorOr(node.color("color"), Border)
        )

        "verticaldivider", "vertical_divider" -> androidx.compose.material3.VerticalDivider(
            modifier = m,
            color = UiTheme.colorOr(node.color("color"), Border)
        )

        "linearprogressindicator" -> if (node.numOrNull("progress") != null) {
            // 给了 progress（0~1）就是确定模式；不给仍然是不确定动画（旧行为）。
            LinearProgressIndicator(
                progress = { (node.numOrNull("progress") ?: 0f).coerceIn(0f, 1f) },
                modifier = m.fillMaxWidth(),
                color = UiTheme.colorOr(node.color("color"), Accent),
                trackColor = UiTheme.colorOr(node.color("trackColor"), Border)
            )
        } else {
            LinearProgressIndicator(
                modifier = m.fillMaxWidth(),
                color = UiTheme.colorOr(node.color("color"), Accent)
            )
        }

        "circularprogressindicator" -> Box(
            modifier = m,
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = UiTheme.colorOr(node.color("color"), Accent),
                modifier = Modifier.size((node.numOrNull("size") ?: 28f).dp)
            )
        }

        // ---------------------------------------------------------- 未支持
        // 二期新增的组件分散在扩展渲染器里；两层都放过才走占位提示。
        else -> if (ctx.showUnknown) UnknownComponent(node)
    }
}

/**
 * 未支持组件的占位。
 *
 * ## 为什么要给替代建议
 * 插件作者写 `Scaffold` 时最想知道的是"那我该用什么"。只告诉他"不支持"
 * 等于把问题丢回去，他只能去翻宿主源码——而宿主源码不在他手里。
 * 所以这里维护一张"常见误用 → 该用什么"的映射表。
 *
 * 表里**没写**的（通常是插件自造的组件名）给一个通用兜底提示；
 * 明确无替代的（WebView / Canvas 这类）写"无替代"，省得作者反复试错。
 */
private val COMPONENT_FALLBACKS: Map<String, String> = buildMap {
    // 骨架 / 导航
    put("scaffold", "Column")
    put("topappbar", "Row")
    put("bottomappbar", "Row")
    put("toolbar", "Row")
    put("appbar", "Row")
    put("navigationdrawer", "Column，或 ModalBottomSheet（底部抽屉）")
    put("drawer", "ModalBottomSheet（底部抽屉）")
    put("bottomsheet", "ModalBottomSheet") // 已支持，兜底提示
    put("bottomnavigation", "Row + IconButton")
    put("navbar", "Row + IconButton")
    // 按钮类
    put("floatingactionbutton", "Button，或 IconButton")
    put("fab", "Button，或 IconButton")
    put("extendedfab", "Button")
    put("speeddial", "Button")
    put("togglebutton", "Button")
    put("segmentedbutton", "Row + Button")
    // 反馈类
    put("snackbar", "ctx.showToast(\"...\")（需 toast 权限）")
    put("tooltip", "Text")
    put("banner", "Card")
    put("popup", "Dialog")
    put("alertdialog", "Dialog") // 已支持
    put("toast", "ctx.showToast(\"...\")（需 toast 权限）")
    // 展示类
    put("badge", "Text + background")
    put("avatar", "Image")
    put("circularimage", "Image + cornerRadius")
    put("ratingbar", "Row + IconButton")
    put("timeline", "Column + ListItem")
    put("accordion", "Card + Switch（或 Chip）")
    put("expansionpanel", "Card + Switch")
    put("stepper", "Row + Text")
    put("breadcrumb", "Row + Text")
    // 列表 / 网格
    put("grid", "LazyColumn + Row")
    put("lazyverticalgrid", "LazyColumn + Row")
    put("lazyhorizontalgrid", "LazyRow + Column")
    put("carousel", "LazyRow")
    put("pager", "LazyRow")
    put("table", "Column + Row")
    put("datatable", "Column + Row")
    put("listview", "LazyColumn + ListItem")
    // 输入类
    put("searchbar", "TextField + IconButton")
    put("datepicker", "TextField（让用户手输日期，如 2026-01-20）")
    put("timepicker", "TextField（让用户手输时间，如 14:30）")
    put("colorpicker", "TextField（让用户输 #RRGGBB）")
    put("rangeselector", "两个 TextField")
    put("autocomplete", "Dropdown")
    put("textarea", "TextField（暂不支持多行自动增高）")
    // 图表 / 媒体：无替代
    put("qrcode", "Image（插件自己生成二维码的 data URI）")
    put("barcode", "Image（插件自己生成条码图片）")
    put("chart", "Image（在插件里生成图片后显示）")
    put("graph", "Image（在插件里生成图片后显示）")
    put("video", "无替代（暂不支持视频播放）")
    put("audio", "AudioBar（宿主渲染播放条；用 ctx.audio.play/pause/stop 控制）")
    put("camera", "无替代（暂不支持相机预览）")
    put("map", "无替代（暂不支持地图）")
    put("webview", "无替代（暂不支持网页嵌入）")
    put("html", "无替代（暂不支持网页嵌入）")
    put("canvas", "无替代（暂不支持自绘图形）")
}

@Composable
private fun UnknownComponent(node: UiNode) {
    // 兼容 snake_case / kebab-case / 驼峰多种写法
    val key = node.type.lowercase().replace("_", "").replace("-", "").replace(" ", "")
    val hint = COMPONENT_FALLBACKS[key]
        ?: COMPONENT_FALLBACKS.entries.firstOrNull { it.key.replace("_", "") == key }?.value

    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFA726).copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Column {
            Text(
                "⚠ 暂不支持组件：${node.type}",
                color = Color(0xFFFFA726),
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (hint != null) "可改用：$hint" else "可改用：Column / Row / Box 自行组合",
                color = TextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

// ------------------------------------------------------------------ 取值助手
// 状态绑定（stateStringOrProp / boolOrState）已提到 UiStateBindings.kt，供扩展组件共用。

// ------------------------------------------------------------------ 枚举映射

private fun verticalArrangement(token: String) = when (token.lowercase()) {
    "spacedby", "spaced_by" -> Arrangement.spacedBy(8.dp)
    "center", "centervertically" -> Arrangement.Center
    "bottom" -> Arrangement.Bottom
    "spacebetween", "space_between" -> Arrangement.SpaceBetween
    "spacearound", "space_around" -> Arrangement.SpaceAround
    "spaceevenly", "space_evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.Top
}

private fun horizontalArrangement(token: String) = when (token.lowercase()) {
    "spacedby", "spaced_by" -> Arrangement.spacedBy(8.dp)
    "center", "centerhorizontally" -> Arrangement.Center
    "end" -> Arrangement.End
    "spacebetween", "space_between" -> Arrangement.SpaceBetween
    "spacearound", "space_around" -> Arrangement.SpaceAround
    "spaceevenly", "space_evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.Start
}

private fun horizontalAlignment(token: String) = when (token.lowercase()) {
    "center", "centerhorizontally" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}

private fun verticalAlignment(token: String) = when (token.lowercase()) {
    "center", "centervertically" -> Alignment.CenterVertically
    "bottom" -> Alignment.Bottom
    "top" -> Alignment.Top
    else -> Alignment.CenterVertically
}

private fun boxAlignment(token: String) = when (token.lowercase()) {
    "center" -> Alignment.Center
    "topstart", "top_start" -> Alignment.TopStart
    "topend", "top_end" -> Alignment.TopEnd
    "bottomstart", "bottom_start" -> Alignment.BottomStart
    "bottomend", "bottom_end" -> Alignment.BottomEnd
    else -> Alignment.TopStart
}

private fun textAlign(token: String) = when (token.lowercase()) {
    "center" -> TextAlign.Center
    "end", "right" -> TextAlign.End
    "justify" -> TextAlign.Justify
    else -> TextAlign.Start
}
