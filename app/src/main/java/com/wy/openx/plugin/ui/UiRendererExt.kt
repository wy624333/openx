package com.wy.openx.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.plugin.PluginPermission
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
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * 插件 UI 的**扩展组件渲染器**（二期新增）。
 *
 * ## 为什么单独一个文件
 * [RenderUiNode] 的 `when` 已经很长，而二期要加十来个组件。拆成扩展后：
 *  · 主渲染器只多一行分派调用，不动既有分支
 *  · 新增组件集中在本文件，作者更新主渲染器时冲突面最小
 *
 * 返回 `true` 表示已处理；`false` 交给主渲染器走"未支持组件"占位。
 */
@Composable
fun RenderUiNodeExt(node: UiNode, ctx: UiRenderContext, m: Modifier): Boolean {
    when (node.type.lowercase()) {
        "image", "img" -> RenderImage(node, ctx, m)
        "slider" -> RenderSlider(node, ctx, m)
        "radiobutton", "radio_button", "radio" -> RenderRadio(node, ctx, m)
        "dropdown", "select", "dropdownmenu" -> RenderDropdown(node, ctx, m)
        "tabs", "tabrow", "tab" -> RenderTabs(node, ctx, m)
        "listitem", "list_item" -> RenderListItem(node, ctx, m)
        "chip", "filterchip", "assistchip", "tag" -> RenderChip(node, ctx, m)
        else -> return false
    }
    return true
}

// ------------------------------------------------------------------ Image

/**
 * 图片。
 *
 * props：`src`（base64 data URI / http(s) / 本地路径）、`width` `height` `size`
 *        `contentScale`（fit/crop/fill/none）、`cornerRadius`、`alt`（加载失败时的文字）
 *
 * 网络图需要 `http` 权限：没有权限时**不发起请求**，直接渲染"缺权限"占位。
 * 这条检查放在渲染层而不是加载器里，是因为加载器拿不到权限上下文。
 */
@Composable
private fun RenderImage(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val src = node.str("src").ifBlank { node.str("url") }
    // 帧序列：宠物、加载动画、逐帧图这类需要自己动起来。
    // 宿主一次性预解码全部帧，之后只切引用 —— 若让插件用 setInterval 反复改 src，
    // 每帧都会重新读盘 + 解码，且解码是异步的，画面会闪、也会拖慢其他渲染。
    val frames = (node.props["frames"] as? List<*>)
        ?.mapNotNull { raw -> raw?.toString()?.takeIf { it.isNotBlank() } }
        .orEmpty()
    val alt = node.str("alt")
    val radius = node.numOrNull("cornerRadius") ?: 0f
    val scale = contentScaleOf(node.str("contentScale"))

    val remote = PluginImageLoader.isRemote(src)
    val allowed = !remote || PluginPermission.HTTP in ctx.permissions

    var bitmap by remember(src) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(src) { mutableStateOf(false) }

    // 帧序列状态：bitmaps 预解码一次，index 由定时器推动
    var frameBitmaps by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var frameIndex by remember { mutableStateOf(0) }
    var frameFailed by remember { mutableStateOf(false) }

    LaunchedEffect(frames.joinToString("|"), allowed) {
        if (frames.isEmpty() || !allowed) return@LaunchedEffect
        val loaded = frames.mapNotNull { PluginImageLoader.load(it, ctx.pluginDir) }
        frameBitmaps = loaded
        frameFailed = loaded.isEmpty()
        frameIndex = 0
    }

    // 播放：只改 index。interval 单位毫秒，下限 80 以免写个 0 把主线程拖死
    LaunchedEffect(frameBitmaps, node.numOrNull("interval")) {
        if (frameBitmaps.size < 2) return@LaunchedEffect
        val interval = (node.numOrNull("interval") ?: 480f).toLong().coerceAtLeast(80L)
        while (true) {
            delay(interval)
            frameIndex = (frameIndex + 1) % frameBitmaps.size
        }
    }

    LaunchedEffect(src, allowed) {
        if (src.isBlank() || !allowed) return@LaunchedEffect
        failed = false
        val b = PluginImageLoader.load(src, ctx.pluginDir)
        bitmap = b
        failed = b == null
    }

    val w = node.numOrNull("width")
    val h = node.numOrNull("height")
    val ratio = node.numOrNull("aspectRatio")

    Box(
        modifier = m.clip(RoundedCornerShape(radius.dp))
            // 图片点击：容器类组件不会自动把 onClick 接上，而「点图看大图 / 点宠物互动」
            // 是最基本的交互。长按由公共 modifier 链处理，与这里不冲突。
            .then(
                node.actionId("onClick")?.let { id ->
                    Modifier.clickable { ctx.onAction(id, emptyMap(), false) }
                } ?: Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // frameIndex 越界直接返回 null，**不要** coerceIn(0, size-1)：
        // size 为 0 时上限是 -1，空区间会抛 IllegalArgumentException（曾导致所有 Image 崩溃）
        val bmp = frameBitmaps.getOrNull(frameIndex)
            ?: if (frames.isEmpty()) bitmap else null
        // 有源但还没解出来 = 加载中（透明）；没源 / 缺权限 / 解码失败 = 硬失败（显占位）
        val hasSource = src.isNotBlank() || frames.isNotEmpty()
        val hardFail = !allowed || frameFailed || failed || !hasSource
        // 宽高保持占位时也一致，避免加载完成前后布局跳动
        val placeholderModifier = Modifier
            .then(if (w != null) Modifier.width(w.dp) else Modifier.width(120.dp))
            .then(if (h != null) Modifier.height(h.dp) else Modifier.height(80.dp))

        when {
            bmp != null -> androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = node.strOrNull("contentDescription") ?: alt.ifBlank { null },
                contentScale = scale,
                modifier = Modifier
                    .then(if (w != null) Modifier.width(w.dp) else Modifier)
                    .then(if (h != null) Modifier.height(h.dp) else Modifier)
                    .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier)
            )
            // 真失败 / 缺权限 / 没源：面板里给可见占位便于排查；
            // 浮窗（无外壳）里静默 —— 宠物身上不应该出现灰框和错误文字
            hardFail -> if (ctx.hidePlaceholder) {
                Spacer(placeholderModifier)
            } else {
                ImagePlaceholder(
                    text = when {
                        src.isBlank() && frames.isEmpty() -> if (alt.isBlank()) "（无图）" else alt
                        !allowed -> "网络图需要 http 权限"
                        else -> if (alt.isBlank()) "图片加载失败" else alt
                    },
                    warning = !allowed || frameFailed || failed,
                    modifier = placeholderModifier
                )
            }
            // 加载中：**什么都不画**（只占位不填色）。
            // 之前这里画「加载中…」灰底块，换图时会闪一下灰底 —— 透明背景的浮窗里
            // 尤其明显，看着就像立绘四周多了个黑框。
            else -> Spacer(placeholderModifier)
        }
    }
}

@Composable
private fun ImagePlaceholder(text: String, warning: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(Border.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (warning) Color(0xFFFFA726) else TextSecondary,
            fontSize = 11.sp
        )
    }
}

private fun contentScaleOf(token: String): ContentScale = when (token.lowercase()) {
    "crop", "cover" -> ContentScale.Crop
    "fill", "fillbounds", "stretch" -> ContentScale.FillBounds
    "none", "original" -> ContentScale.None
    "inside", "scaleinside" -> ContentScale.Inside
    else -> ContentScale.Fit
}

// ------------------------------------------------------------------ Slider

/**
 * 滑块。props：`value`/`stateKey`、`min` `max` `steps`、`onValueChange`。
 *
 * `steps` 是 Compose 的语义（**中间刻度数**，0 表示连续）。
 * 插件作者容易按"离散档位数"理解，所以同时接受 `divisions` 别名。
 */
@Composable
private fun RenderSlider(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val min = node.num("min", 0f)
    val max = node.num("max", 100f)
    val steps = node.int("steps", node.int("divisions", 0))
    val action = node.actionId("onValueChange") ?: node.actionId("onChange")
    // 插件把 min/max 写反或写成相等时不能崩：Slider 的区间必须非空
    val lo = minOf(min, max)
    val hi = if (maxOf(min, max) > lo) maxOf(min, max) else lo + 1f
    val current = ctx.stateFloat(node) ?: node.numOrNull("value") ?: lo

    Column(modifier = m) {
        Column {
            node.strOrNull("label")?.let {
                Text(it, color = TextSecondary, fontSize = 11.sp)
            }
            Slider(
                value = safeCoerce(current, lo, hi),
                onValueChange = { v -> action?.let { a -> ctx.onAction(a, mapOf("value" to v), true) } },
                valueRange = lo..hi,
                steps = steps.coerceAtLeast(0)
            )
        }
    }
}

// ------------------------------------------------------------------ RadioButton

/**
 * 单选钮。props：`checked`/`stateKey`、`onCheckedChange`。
 * 需要配合文案时用 `Row { RadioButton + Text }`，或直接用 `ListItem` 的 `leadingRadio`。
 */
@Composable
private fun RenderRadio(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    RadioButton(
        selected = node.boolOrState(ctx, "checked"),
        onClick = { node.actionId("onCheckedChange")?.let { ctx.onAction(it, emptyMap(), false) } },
        modifier = m
    )
}

// ------------------------------------------------------------------ Dropdown

/**
 * 下拉选择。
 *
 * props：`items`（`["A","B"]` 或 `[{label,value}]`）、`selected`/`selectedIndex`/`stateKey`、
 *        `label`、`placeholder`、`onSelect`
 *
 * 为什么不用 Material 的 `ExposedDropdownMenuBox`：它要求 `TextFields` 与
 * `menuAnchor` 配合，在"节点树渲染"里拿不到稳定的 anchor Modifier。
 * 用 `OutlinedButton + DropdownMenu` 更直接，视觉上也够用。
 *
 * `onSelect` 收到的 payload：`{ index, value, label }` —— 三个都给，插件想用哪个都行。
 */
@Composable
private fun RenderDropdown(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val items = parseItemList(node)
    val action = node.actionId("onSelect") ?: node.actionId("onChange")
    val selectedIdx = ctx.stateInt(node)
        ?: node.int("selectedIndex", node.int("selected", -1))

    var expanded by remember { mutableStateOf(false) }
    val current = items.getOrNull(selectedIdx)
    val display = current?.first
        ?: node.strOrNull("placeholder")
        ?: node.strOrNull("label")
        ?: "请选择"

    Column(modifier = m) {
        node.strOrNull("label")?.let {
            Text(it, color = TextSecondary, fontSize = 11.sp)
        }
        Box {
            androidx.compose.material3.OutlinedButton(onClick = { expanded = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        display,
                        color = if (current != null) TextPrimary else TextSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        UiIcons.of("expand_more"),
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                items.forEachIndexed { index, (label, value) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (index == selectedIdx) Accent else TextPrimary,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            expanded = false
                            action?.let {
                                ctx.onAction(
                                    it,
                                    mapOf("index" to index, "value" to value, "label" to label),
                                    false
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Tabs

/**
 * 标签页。props：`tabs`（`["A","B"]` 或 `[{label}]`）、`selectedIndex`/`stateKey`、`onSelect`。
 *
 * 只渲染**标签条**，不负责切换内容 —— 面板内容由插件用 `selectedIndex` 自己做条件渲染
 * （与 Compose 惯例一致：TabRow 只管选中态）。
 */
@Composable
private fun RenderTabs(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val items = parseItemList(node)
    if (items.isEmpty()) return
    val action = node.actionId("onSelect") ?: node.actionId("onChange")
    val selected = (ctx.stateInt(node) ?: node.int("selectedIndex", 0))
        .coerceIn(0, items.lastIndex)

    androidx.compose.material3.PrimaryTabRow(
        selectedTabIndex = selected,
        modifier = m.fillMaxWidth(),
        containerColor = Panel,
        contentColor = Accent
    ) {
        items.forEachIndexed { index, (label, value) ->
            androidx.compose.material3.Tab(
                selected = index == selected,
                onClick = {
                    action?.let {
                        ctx.onAction(it, mapOf("index" to index, "value" to value, "label" to label), false)
                    }
                },
                text = {
                    Text(
                        label,
                        fontSize = 13.sp,
                        color = if (index == selected) Accent else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

// ------------------------------------------------------------------ ListItem

/**
 * 列表行。props：`title`、`subtitle`、`leadingIcon`、`trailingIcon`、`trailingText`、
 * `onClick`、`leadingRadio`/`leadingCheckbox`（布尔，用于选择列表）。
 *
 * 把"图标 + 标题 + 副标题 + 尾部"这套最常见的组合做成一个组件 ——
 * 否则插件每写一个列表行都要手搓一层 Row/Column，代码又长又容易对不齐。
 */
@Composable
private fun RenderListItem(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val clickable = node.actionId("onClick") != null
    val base = if (clickable) m.clickable {
        node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) }
    } else m

    Row(
        modifier = base.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 前置：单选 / 复选 优先于图标
        if (node.bool("leadingRadio")) {
            RadioButton(
                selected = node.boolOrState(ctx, "checked"),
                onClick = { node.actionId("onCheckedChange")?.let { ctx.onAction(it, emptyMap(), false) } }
            )
        } else if (node.bool("leadingCheckbox")) {
            androidx.compose.material3.Checkbox(
                checked = node.boolOrState(ctx, "checked"),
                onCheckedChange = {
                    node.actionId("onCheckedChange")?.let { a -> ctx.onAction(a, emptyMap(), false) }
                }
            )
        } else {
            node.strOrNull("leadingIcon")?.let {
                Icon(
                    UiIcons.of(it),
                    contentDescription = null,
                    tint = UiTheme.colorOr(node.color("leadingIconColor"), TextSecondary),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                node.str("title"),
                color = UiTheme.colorOr(node.color("titleColor"), TextPrimary),
                fontSize = node.num("fontSize", 14f).sp,
                fontWeight = if (node.bool("bold")) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            node.strOrNull("subtitle")?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        node.strOrNull("trailingText")?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = TextSecondary, fontSize = 12.sp)
        }
        node.strOrNull("trailingIcon")?.let {
            Spacer(Modifier.width(8.dp))
            Icon(
                UiIcons.of(it),
                contentDescription = null,
                tint = UiTheme.colorOr(node.color("trailingIconColor"), TextSecondary),
                modifier = Modifier.size(20.dp)
            )
        } ?: if (clickable && node.bool("showArrow", false)) {
            Spacer(Modifier.width(8.dp))
            Icon(
                UiIcons.of("chevron_right"),
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Spacer(Modifier.width(0.dp))
        }
    }
}

// ------------------------------------------------------------------ Chip

/**
 * 标签片 / 过滤片。
 *
 * props：`text`/`label`、`selected`（给了就当 FilterChip，可显示选中态）、
 *        `onClick`、`icon`、`color`
 *
 * `selected` 支持 `stateKey` 绑定 —— 多选过滤场景插件的写法是：
 * 每个 chip 绑一个 `useState('tag_xx', false)`，点击翻转。
 */
@Composable
private fun RenderChip(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val text = node.str("text").ifBlank { node.str("label") }
    val hasSelected = node.props.containsKey("selected") || node.props.containsKey("stateKey")
    val onClick: () -> Unit = {
        node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) }
    }
    val icon: @Composable (() -> Unit)? = node.strOrNull("icon")?.let {
        {
            Icon(
                UiIcons.of(it),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    }

    if (hasSelected) {
        androidx.compose.material3.FilterChip(
            selected = node.boolOrState(ctx, "selected"),
            onClick = onClick,
            label = { Text(text, fontSize = 12.sp) },
            leadingIcon = icon,
            modifier = m
        )
    } else {
        androidx.compose.material3.AssistChip(
            onClick = onClick,
            label = { Text(text, fontSize = 12.sp) },
            leadingIcon = icon,
            modifier = m
        )
    }
}

// ------------------------------------------------------------------ 工具

/**
 * 解析 `items`/`tabs` 数组为 `(label, value)` 列表。
 *
 * 同时接受两种写法：
 *   `["A", "B"]`                    → label = value = 原值
 *   `[{ label: "A", value: "a" }]`  → 分别取值
 */
private fun parseItemList(node: UiNode): List<Pair<String, String>> {
    val raw = node.props["items"] ?: node.props["tabs"] ?: return emptyList()
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
        when (item) {
            is String -> item to item
            is Map<*, *> -> {
                val label = item["label"]?.toString()
                    ?: item["text"]?.toString()
                    ?: item["title"]?.toString()
                    ?: return@mapNotNull null
                val value = item["value"]?.toString() ?: label
                label to value
            }
            else -> item?.toString()?.let { it to it }
        }
    }
}
