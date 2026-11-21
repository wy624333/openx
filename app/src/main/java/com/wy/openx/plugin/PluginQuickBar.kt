package com.wy.openx.plugin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 侧边栏「插件快捷区」：把已启用插件按**每插件一行**渲染，插在底部快捷条上方。
 *
 * ## 布局
 * ```
 * ⚡ 插件
 * ┌────────────────────────────────────────────┐  ← 限高 3 行
 * │ [📦 插件 A]    [入口1] [入口2] ……           │  ← 行内横向滚动
 * │ [📦 插件 B]    [入口]                       │
 * └────────────────────────────────────────────┘  ← 超出则纵向滚动
 * ```
 *
 * ## 设计取舍
 * - **行首插件名**：点击直接打开该插件界面（`manifest.ui` 为空时置灰不可点）。
 *   一行的语义是「这个插件 + 它的入口」，比原来把各插件的胶囊混在一起清晰。
 * - **行内横向滚动**（不做等分）：快捷项数量由插件决定、不可预测；等分在条目多时会挤成一条缝。
 * - **限高 3 行 + 纵向滚动**：插件开多了不会把侧边栏撑爆、把下方会话列表压没。
 *   高度按行高精确算（见 [listMaxHeight]），正好露出 3 行。
 * - **零插件时整个区域消失**（return），不占空间、不留空条。
 * - **点击行为**：`openUi` / `prompt` 由调用方（ViewModel）决定，这里不持有 ViewModel。
 */
@Composable
fun PluginQuickBar(
    groups: List<PluginQuickGroup>,
    /** 是否展开。状态由调用方持有（持久化在设置的 `pluginQuickBarExpanded`，默认折叠）。 */
    expanded: Boolean,
    /** 就地折叠/展开（标题行整行或右侧箭头）。调用方应写回设置，保证下次启动一致。 */
    onExpandedChange: (Boolean) -> Unit,
    onAction: (PluginQuickAction) -> Unit,
    onOpenPlugin: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        // 标题行（整行可点：折叠 / 展开）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "插件",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "折叠插件快捷区" else "展开插件快捷区",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
            // 分组列表：限高 3 行，超出纵向滚动
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(max = listMaxHeight())
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP)
            ) {
                groups.forEach { group ->
                    QuickRow(group = group, onAction = onAction, onOpenPlugin = onOpenPlugin)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 尺寸

/** 单行胶囊高度。 */
private val ROW_HEIGHT = 34.dp

/** 行间距。 */
private val ROW_GAP = 6.dp

/** 可见行数：正好 3 行，再多就滚动。 */
private const val VISIBLE_ROWS = 3

/**
 * 列表最大高度 = 3 行 + 2 个行间距。
 *
 * 用**精确值**而非 `heightIn(max = 200.dp)` 之类：行高一旦调整，这里要跟着算，
 * 否则会出现「第 3 行被截掉一半」的尴尬高度。
 */
private fun listMaxHeight() = ROW_HEIGHT * VISIBLE_ROWS + ROW_GAP * (VISIBLE_ROWS - 1)

// ---------------------------------------------------------------- 单行

@Composable
private fun QuickRow(
    group: PluginQuickGroup,
    onAction: (PluginQuickAction) -> Unit,
    onOpenPlugin: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PluginNameChip(group = group, onOpenPlugin = onOpenPlugin)
        Spacer(Modifier.width(6.dp))
        // 行内横向滚动：快捷项再多也不会撑破侧边栏，滑动查看
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            group.actions.forEach { action ->
                QuickChip(action = action, onClick = { onAction(action) })
            }
        }
    }
}

/**
 * 行首的插件名胶囊。点击打开界面。
 *
 * 没声明 `ui` 的插件（纯工具插件）置灰、不可点 —— 因为点了也没界面可开。
 * 给它保留位置而不是隐藏，是为了让"这一行属于哪个插件"始终可见。
 */
@Composable
private fun PluginNameChip(group: PluginQuickGroup, onOpenPlugin: (String) -> Unit) {
    Row(
        modifier = Modifier
            .height(ROW_HEIGHT)
            .clip(RoundedCornerShape(17.dp))
            .background(if (group.hasUi) Panel else Panel.copy(alpha = 0.5f))
            .clickable(enabled = group.hasUi) { onOpenPlugin(group.pluginId) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Extension,
            contentDescription = null,
            tint = if (group.hasUi) Accent else TextSecondary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            group.pluginName,
            color = if (group.hasUi) TextPrimary else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 插件名过长时不要挤掉右侧快捷项；超出省略。
            modifier = Modifier.widthIn(max = 108.dp)
        )
    }
}

// ---------------------------------------------------------------- 快捷项胶囊

@Composable
private fun QuickChip(action: PluginQuickAction, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(ROW_HEIGHT)
            .clip(RoundedCornerShape(17.dp))
            .background(Panel)
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = iconOf(action.icon.orEmpty()),
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            action.label.orEmpty(),
            color = TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 图标名 → 材料图标。未知名字一律回退到 MoreHoriz，避免为插件名字做穷举。 */
private fun iconOf(name: String): ImageVector = when (name.lowercase()) {
    "volume", "volume_up", "speak", "read" -> Icons.Outlined.VolumeUp
    "stop", "pause" -> Icons.Outlined.Stop
    "list", "voices" -> Icons.Outlined.List
    "bolt", "action" -> Icons.Outlined.Bolt
    "open_in_new", "openurl", "browser", "link", "external" -> Icons.Outlined.OpenInNew
    else -> Icons.Outlined.MoreHoriz
}

// ---------------------------------------------------------------- 组装

/**
 * operit `registerNavigationEntry` 的定义 → 侧边栏快捷项。
 *
 * title 可能是 `{zh,en,default}` 多语言对象也可能是字符串；icon 是官方图标名
 * （如 `Icons.Code`），取末段映射到 OpenX 的 Material 图标名。
 */
internal fun Map<String, Any>.toQuickAction(): PluginQuickAction? {
    val surface = this["surface"]?.toString().orEmpty()
    if (surface.isNotBlank() && !surface.contains("main_sidebar", ignoreCase = true)) return null
    val title = when (val t = this["title"]) {
        is Map<*, *> -> {
            val preferZh = java.util.Locale.getDefault().language.equals("zh", ignoreCase = true)
            val order = if (preferZh) listOf("zh", "default", "en") else listOf("en", "default", "zh")
            order.firstNotNullOfOrNull { k -> (t[k] as? String)?.takeIf { it.isNotBlank() } }
                ?: t.values.firstOrNull() as? String
        }
        else -> t as? String
    } ?: this["id"]?.toString() ?: return null
    val iconName = this["icon"]?.toString()?.substringAfterLast('.')?.replaceFirstChar { it.lowercase() }.orEmpty()
    return PluginQuickAction(
        label = title,
        icon = iconName,
        action = "openUi"
    )
}

/**
 * 供宿主拼装「已启用插件 → 分组」。顺序 = 插件排序（按名字），稳定可预期。
 *
 * 只保留**有快捷项**的插件：没有快捷项的行只剩下个插件名，点开还是空的，
 * 不如直接不显示（要开界面用户可以走管理页）。
 */
fun collectQuickGroups(plugins: List<InstalledPlugin>): List<PluginQuickGroup> =
    plugins
        .filter { it.enabled && it.error == null }
        .mapNotNull { plugin ->
            val actions = plugin.manifest.quickActions
            if (actions.isEmpty()) return@mapNotNull null
            PluginQuickGroup(
                pluginId = plugin.manifest.id,
                pluginName = plugin.manifest.name.ifBlank { plugin.manifest.id },
                hasUi = plugin.manifest.ui.isNotBlank(),
                // 回填来源插件 id：点击回调只拿到 action，靠它才知道该发到哪个插件。
                actions = actions.map { it.copy().also { a -> a.ownerPluginId = plugin.manifest.id } }
            )
        }
