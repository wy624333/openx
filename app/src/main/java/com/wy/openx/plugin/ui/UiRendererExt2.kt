package com.wy.openx.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ElevatedSuggestionChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wy.openx.plugin.ui.UiNodeParser.actionId
import com.wy.openx.plugin.ui.UiNodeParser.bool
import com.wy.openx.plugin.ui.UiNodeParser.color
import com.wy.openx.plugin.ui.UiNodeParser.int
import com.wy.openx.plugin.ui.UiNodeParser.numOrNull
import com.wy.openx.plugin.ui.UiNodeParser.str
import com.wy.openx.plugin.ui.UiNodeParser.strOrNull
import com.wy.openx.ui.screen.MarkdownBlockItem
import com.wy.openx.ui.screen.parseMarkdown
import com.wy.openx.ui.theme.TextPrimary

/**
 * 三期扩展组件（对照 operit-ai 的 compose DSL 补齐）。
 *
 * 分文件而不是塞进 [UiRendererExt]：那边已经是二期的一批交互组件，
 * 再堆 26 个进去会让分派表长到没法扫。
 *
 * 命名与 Material3 保持一致（小驼峰），小写别名也收，方便插件作者写
 * `UI.TextButton` 或 `UI.text_button`。
 */
@Composable
fun RenderUiNodeExt2(node: UiNode, ctx: UiRenderContext, m: Modifier): Boolean {
    when (node.type.lowercase()) {
        // ---- 按钮家族 ----
        "textbutton" -> RenderTextButton(node, ctx, m)
        "elevatedbutton" -> ElevatedButton(
            modifier = m,
            enabled = node.boolOrState(ctx, "enabled", def = true),
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } }
        ) { Text(node.stateStringOrProp(ctx, "text")) }

        "filledtonalbutton" -> FilledTonalButton(
            modifier = m,
            enabled = node.boolOrState(ctx, "enabled", def = true),
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } }
        ) { Text(node.stateStringOrProp(ctx, "text")) }

        "iconbutton" -> IconButton(
            modifier = m,
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } }
        ) { Icon(UiIcons.of(node.str("icon").ifBlank { node.str("iconName") }), node.stateStringOrProp(ctx, "contentDescription")) }

        "outlinediconbutton" -> OutlinedIconButton(
            modifier = m,
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } }
        ) { Icon(UiIcons.of(node.str("icon").ifBlank { node.str("iconName") }), node.stateStringOrProp(ctx, "contentDescription")) }

        "fillediconbutton" -> FilledIconButton(
            modifier = m,
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } }
        ) { Icon(UiIcons.of(node.str("icon").ifBlank { node.str("iconName") }), node.stateStringOrProp(ctx, "contentDescription")) }

        // ---- 图标开关 ----
        // ⚠ Material3 的 IconToggleButton 内部只响应「切换指示器」的点击；
        // 传 children（Icon）时，点击落在 Icon 上不会触发 onCheckedChange。
        // 所以自己用 Box + toggleable 复刻，保证整个图标区域都可点。
        "icontogglebutton", "filledicontogglebutton" -> {
            val on = node.boolOrState(ctx, "checked")
            val cb = node.actionId("onCheckedChange")
            val tint = if (on) {
                androidx.compose.material3.MaterialTheme.colorScheme.primary
            } else {
                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            }
            androidx.compose.foundation.layout.Box(
                modifier = m.toggleable(
                    value = on,
                    role = androidx.compose.ui.semantics.Role.Checkbox,
                    onValueChange = { v ->
                        cb?.let { ctx.onAction(it, mapOf("value" to v), true) }
                    }
                ),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Icon(
                    UiIcons.of(node.str("icon").ifBlank { node.str("iconName") }),
                    contentDescription = node.stateStringOrProp(ctx, "contentDescription"),
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        "outlinedicontogglebutton" -> {
            val on = node.boolOrState(ctx, "checked")
            val cb = node.actionId("onCheckedChange")
            val tint = if (on) {
                androidx.compose.material3.MaterialTheme.colorScheme.primary
            } else {
                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            }
            androidx.compose.foundation.layout.Box(
                modifier = m.toggleable(
                    value = on,
                    role = androidx.compose.ui.semantics.Role.Checkbox,
                    onValueChange = { v ->
                        cb?.let { ctx.onAction(it, mapOf("value" to v), true) }
                    }
                ),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Icon(
                    UiIcons.of(node.str("icon").ifBlank { node.str("iconName") }),
                    contentDescription = node.stateStringOrProp(ctx, "contentDescription"),
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // ---- 芯片变体 ----
        "suggestionchip" -> SuggestionChip(
            modifier = m,
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } },
            label = { Text(node.stateStringOrProp(ctx, "text")) }
        )

        "elevatedsuggestionchip" -> ElevatedSuggestionChip(
            modifier = m,
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } },
            label = { Text(node.stateStringOrProp(ctx, "text")) }
        )

        "elevatedfilterchip" -> ElevatedFilterChip(
            modifier = m,
            selected = node.boolOrState(ctx, "selected"),
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } },
            label = { Text(node.stateStringOrProp(ctx, "text")) }
        )

        "elevatedassistchip" -> ElevatedAssistChip(
            modifier = m,
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } },
            label = { Text(node.stateStringOrProp(ctx, "text")) }
        )

        "inputchip" -> InputChip(
            modifier = m,
            selected = node.boolOrState(ctx, "selected"),
            onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } },
            label = { Text(node.stateStringOrProp(ctx, "text")) },
            trailingIcon = if (node.bool("trailingIcon")) {
                { Icon(Icons.Default.Close, "移除", Modifier.size(18.dp)) }
            } else null
        )

        "badgedbox" -> BadgedBox(
            modifier = m,
            badge = {
                Badge(containerColor = UiTheme.colorOr(node.color("badgeColor"), MaterialTheme.colorScheme.error)) {
                    Text(node.stateStringOrProp(ctx, "badgeText"))
                }
            }
        ) {
            node.children.forEach { RenderUiNode(it, ctx) }
        }

        // ---- 懒加载列表 ----
        "lazycolumn" -> RenderLazyColumn(node, ctx, m)
        "lazyrow" -> RenderLazyRow(node, ctx, m)

        // ---- 分隔线 ----
        "horizontaldivider", "divider" -> HorizontalDivider(modifier = m)
        "verticaldivider" -> VerticalDivider(modifier = m)

        // ---- 容器 ----
        "scaffold" -> RenderScaffold(node, ctx, m)
        // surface 不在本文件处理：UiRenderer.kt 的主 when 里有实现（读 containerColor、
        // 带 padding 内衬、支持 cornerRadius）。这里曾重复实现一份，因渲染顺序更靠前而
        // 实际生效，导致主实现成为死代码 —— 表现为插件的按钮底色丢失 + 内边距失效
        // （小恐龙的「开始/暂停/重置/分数」挤成一行且看不见底色）。

        "boxwithconstraints" -> RenderBoxWithConstraints(node, ctx, m)

        // ---- 反馈 ----
        "snackbar", "snackbarhost" -> RenderSnackbar(node, ctx, m)
        "pulltorefreshbox" -> RenderPullToRefresh(node, ctx, m)

        // ---- TabRow 家族 ----
        "primaryscrollabletabrow", "primarytabrow", "secondaryscrollabletabrow", "secondarytabrow" ->
            RenderTabRow(node, ctx, m)

        // ---- 时间选择 ----
        "timepickerdialog" -> RenderTimePickerDialog(node, ctx)

        // ---- 富文本 ----
        "markdown", "md" -> RenderMarkdown(node, ctx, m)

        else -> return false
    }
    return true
}

/**
 * Markdown 渲染。
 *
 * 直接复用聊天页的 [parseMarkdown] + [MarkdownBlockItem]，而不是在插件侧再实现一套：
 * 一是插件作者拿到的语法（标题/列表/代码块/表格/引用）与 AI 回复完全一致；
 * 二是宿主后续改样式时插件自动跟着变。
 *
 * 解析放在 remember 里：内容不变就不重算，避免插件重新渲染时把整段文本重新分块。
 */
@Composable
private fun RenderMarkdown(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val text = node.stateStringOrProp(ctx, "text")
    val blocks = remember(text) { parseMarkdown(text) }
    val fg = UiTheme.colorOr(node.color("color"), TextPrimary)
    val size = (node.numOrNull("fontSize") ?: 14f).sp
    Column(modifier = m) {
        blocks.forEach { block -> MarkdownBlockItem(block, size, fg) }
    }
}

@Composable
private fun RenderTextButton(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    TextButton(
        modifier = m,
        enabled = node.boolOrState(ctx, "enabled", def = true),
        onClick = { node.actionId("onClick")?.let { ctx.onAction(it, emptyMap(), false) } }
    ) { Text(node.stateStringOrProp(ctx, "text")) }
}

@Composable
private fun RenderLazyColumn(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val items = node.children
    // height：嵌在外层滚动容器里时必须有固定高度，否则会撑满父容器
    val h = node.numOrNull("height")?.dp
    LazyColumn(
        modifier = if (h != null) m.height(h) else m,
        verticalArrangement = Arrangement.spacedBy(node.numOrNull("spacing")?.dp ?: 8.dp)
    ) {
        items(items.size) { i -> RenderUiNode(items[i], ctx) }
    }
}

@Composable
private fun RenderLazyRow(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val items = node.children
    val h = node.numOrNull("height")?.dp
    LazyRow(
        modifier = if (h != null) m.height(h) else m,
        horizontalArrangement = Arrangement.spacedBy(node.numOrNull("spacing")?.dp ?: 8.dp)
    ) {
        items(items.size) { i -> RenderUiNode(items[i], ctx) }
    }
}

/**
 * Scaffold：topBar / bottomBar / content 三槽。
 *
 * 只做这三槽——snackbarHost 是瞬态的，由 [RenderSnackbar] 自己管，
 * 放这里会两处 SnackbarHostState 打架。
 */
@Composable
private fun RenderScaffold(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    // 槽位认 role prop（UI.Text({role:'topBar'})），不认 node.type —— 没有
    // type 为 topBar 的渲染器，按 type 认会走进"暂不支持组件"分支。
    fun slotOf(n: UiNode) = n.str("role").lowercase()
    val topBarNode = remember(node) { node.children.firstOrNull { slotOf(it) == "topbar" } }
    val bottomBarNode = remember(node) { node.children.firstOrNull { slotOf(it) == "bottombar" } }
    val bodyNodes = remember(node) {
        node.children.filterNot { slotOf(it) == "topbar" || slotOf(it) == "bottombar" }
    }

    Scaffold(
        modifier = m,
        topBar = {
            if (topBarNode != null) Column { RenderUiNode(topBarNode, ctx) }
        },
        bottomBar = {
            if (bottomBarNode != null) Column { RenderUiNode(bottomBarNode, ctx) }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            bodyNodes.forEach { RenderUiNode(it, ctx) }
        }
    }
}

@Composable
private fun RenderBoxWithConstraints(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    BoxWithConstraints(modifier = m) {
        Column {
            node.children.forEach { RenderUiNode(it, ctx) }
        }
    }
}

/**
 * Snackbar：message 变化就弹一次。
 *
 * 浮层类不占布局位置，所以父级给的 padding 要在这里自己吃掉，
 * 否则会被算进流式排版里多出一段空白。
 */
@Composable
private fun RenderSnackbar(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val hostState = remember { SnackbarHostState() }
    val msg = node.stateStringOrProp(ctx, "message")
    val actionText = node.stateStringOrProp(ctx, "actionText")
    val onAction = node.actionId("onAction")

    LaunchedEffect(msg) {
        if (msg.isNotBlank()) hostState.showSnackbar(msg, duration = SnackbarDuration.Short)
    }

    Box(modifier = m) {
        SnackbarHost(hostState) { data ->
            // 不用 Snackbar 的 action 参数（它的类型是 ComposableFunction0，
            // 在 lambda 字面量里构造不出来），改成自己画一个带按钮的行。
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(8.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(data.visuals.message, Modifier.weight(1f))
                    if (actionText.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            data.dismiss()
                            onAction?.let { ctx.onAction(it, emptyMap(), false) }
                        }) { Text(actionText, color = MaterialTheme.colorScheme.inversePrimary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderPullToRefresh(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val refreshing = node.boolOrState(ctx, "refreshing")
    val onRefresh = node.actionId("onRefresh")

    val pullState = rememberPullToRefreshState()
    BoxWithConstraints(
        modifier = m.pullToRefresh(
            state = pullState,
            isRefreshing = refreshing,
            onRefresh = { onRefresh?.let { ctx.onAction(it, emptyMap(), false) } }
        )
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 指示器只在下拉过程中出现；refreshing 由插件自己复位。
            if (refreshing) CircularProgressIndicator(Modifier.size(22.dp))
            Box(Modifier.fillMaxWidth()) { node.children.forEach { RenderUiNode(it, ctx) } }
        }
    }
}

@Composable
private fun RenderTabRow(node: UiNode, ctx: UiRenderContext, m: Modifier) {
    val tabs = node.children.filter { it.type.equals("Tab", true) }
    val selected = (ctx.stateInt(node) ?: node.int("selectedIndex", 0)).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    var current by remember { mutableStateOf(selected) }

    ScrollableTabRow(selectedTabIndex = current, modifier = m) {
        tabs.forEachIndexed { i, tab ->
            Tab(
                selected = current == i,
                onClick = {
                    current = i
                    node.actionId("onTabSelected")?.let {
                        ctx.onAction(it, mapOf("index" to i, "label" to tab.stateStringOrProp(ctx, "text")), false)
                    }
                },
                text = { Text(tab.stateStringOrProp(ctx, "text")) }
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RenderTimePickerDialog(node: UiNode, ctx: UiRenderContext) {
    val open = node.boolOrState(ctx, "open")
    val initialHour = node.int("initialHour", 12)
    val initialMinute = node.int("initialMinute", 0)
    val state = rememberTimePickerState(
        initialHour = initialHour.coerceIn(0, 23),
        initialMinute = initialMinute.coerceIn(0, 59),
        is24Hour = node.boolOrState(ctx, "is24Hour")
    )

    if (open) {
        Dialog(
            onDismissRequest = {
                node.actionId("onDismiss")?.let { ctx.onAction(it, emptyMap(), false) }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = state)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            node.actionId("onDismiss")?.let { ctx.onAction(it, emptyMap(), false) }
                        }) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val payload = mapOf(
                                "hour" to state.hour,
                                "minute" to state.minute,
                                "value" to String.format("%02d:%02d", state.hour, state.minute)
                            )
                            node.actionId("onConfirm")?.let { ctx.onAction(it, payload, false) }
                        }) { Text("确定") }
                    }
                }
            }
        }
    }
}