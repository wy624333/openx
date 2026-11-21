package com.wy.openx.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.data.model.AIProviderConfig
import com.wy.openx.data.model.ModelMetadata
import com.wy.openx.ui.component.ModelLogoIcon
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 聊天页模型选择列表：按提供商分组、iOS 风格搜索框、
 * 能力标签（Image/Tools/上下文窗口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionSheet(
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (providerId: String, model: String) -> Unit,
    onDismiss: () -> Unit,
    /** 提供时显示「跟随聊天模型」清除入口（用于识图/压缩/起标题专用模型选择）。 */
    onClear: (() -> Unit)? = null,
    /** 标题行展示的文案，默认「选择模型」。 */
    title: String = "选择模型"
) {
    val sheetState = rememberModalBottomSheetState()
    var searchQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel,
        dragHandle = { HorizontalDivider(color = Border, modifier = Modifier.width(40.dp)) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
                onClear?.let { clear ->
                    Text(
                        "跟随聊天模型", color = Accent, fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { clear() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("筛选模型...", fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary.copy(alpha = 0.5f)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "清除", modifier = Modifier.size(14.dp), tint = TextSecondary)
                        }
                    }
                },
                colors = tfColors(),
                shape = RoundedCornerShape(12.dp)
            )

            val activeProviders = providers.filter { it.isEnabled && it.models.isNotEmpty() }
            if (activeProviders.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                    Text("暂无可用模型，请先在设置 → API 配置中添加提供商与模型", fontSize = 13.sp, color = TextSecondary)
                }
            } else {
                val expandedMap = remember(activeProviders.map { it.id }, currentProviderId, currentModel) {
                    mutableStateMapOf<String, Boolean>().apply {
                        activeProviders.forEach { provider ->
                            val hasCurrent = provider.id == currentProviderId && provider.models.contains(currentModel)
                            put(provider.id, hasCurrent)
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 固定高度下限 360 / 上限 420，
                        // 不做屏幕比例动态限制，也不锁嵌套滚动 —— 默认手势，滑动流畅。
                        .heightIn(min = 360.dp, max = 420.dp)
                ) {
                    activeProviders.forEach { provider ->
                        val filtered = provider.models.filter {
                            searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true)
                        }
                        if (filtered.isNotEmpty()) {
                            val hasCurrent = provider.id == currentProviderId && filtered.contains(currentModel)
                            val expanded = if (searchQuery.isNotBlank()) true else (expandedMap[provider.id] ?: hasCurrent)
                            item(key = "header_${provider.id}") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (searchQuery.isBlank()) {
                                                expandedMap[provider.id] = !(expandedMap[provider.id] ?: hasCurrent)
                                            }
                                        }
                                        .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${provider.name} (${filtered.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextSecondary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (expanded) "收起" else "展开",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            if (expanded) {
                                items(filtered, key = { "${provider.id}_$it" }) { model ->
                                    val selected = provider.id == currentProviderId && model == currentModel
                                    // 内置/拉取元数据以纯模型名为键；自定义元数据以「提供商ID:模型名」为键。
                                    // 两者都要取并合并（自定义优先），否则手动配置的能力/窗口在此不显示。
                                    val meta = com.wy.openx.data.model.mergeModelMetadata(
                                        model,
                                        modelMetadata[model],
                                        modelMetadata["${provider.id}:$model"]
                                    )
                                    ModelSelectionRow(
                                        model = model,
                                        selected = selected,
                                        metadata = meta,
                                        onClick = { onSelect(provider.id, model) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSelectionRow(
    model: String,
    selected: Boolean,
    metadata: ModelMetadata?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModelLogoIcon(modelName = model, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                model,
                fontSize = 14.sp,
                color = if (selected) Accent else TextPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ModelSelectionTags(metadata)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Check, contentDescription = "当前选中", tint = Accent, modifier = Modifier.size(18.dp))
        }
    }
    HorizontalDivider(color = Border.copy(alpha = 0.4f), thickness = 0.5.dp)
}

@Composable
private fun ModelSelectionTags(metadata: ModelMetadata?) {
    metadata ?: return
    val tags = mutableListOf<String>()
    if (metadata.supportsVision) tags.add("Image")
    if (metadata.supportsTools) tags.add("Tools")
    val input = metadata.inputTokens?.takeIf { it > 0 } ?: metadata.contextTokens.takeIf { it > 0 }
    if (input != null) tags.add("↑${formatLimit(input)}")
    metadata.outputTokens?.takeIf { it > 0 }?.let { tags.add("↓${formatLimit(it)}") }
    if (tags.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
        tags.take(4).forEach { t ->
            Surface(color = PanelAlt.copy(alpha = 0.6f), shape = RoundedCornerShape(50)) {
                Text(t, fontSize = 9.sp, color = TextSecondary, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
    }
}

private fun formatLimit(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "${tokens / 1_000_000.0}".trimDecimal() + "M"
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> "${tokens / 1_000.0}".trimDecimal() + "K"
    else -> tokens.toString()
}

private fun String.trimDecimal(): String = replace(Regex("(\\.\\d)\\d+"), "$1").removeSuffix(".0")
