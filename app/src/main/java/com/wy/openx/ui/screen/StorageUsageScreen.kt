package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.data.storage.CleanupStrategy
import com.wy.openx.data.storage.StorageCategory
import com.wy.openx.data.storage.StorageUsageAnalyzer
import com.wy.openx.data.storage.StorageUsageSummary
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.BgSecondary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.ButtonBg
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 「存储占用」页。
 *
 * 三块内容实现：**存储概览 → 清理建议 → 分类占用**。
 * 分类按 openx 实际目录裁剪，并把 GB 级的「容器系统」单列。
 */
@Composable
fun StorageUsageScreen(
    onBack: () -> Unit,
    onResetContainer: (() -> Unit)? = null,
    /** 某分类被清后的回调（传分类 id）：知识库被清后 VM 需重扫，否则管理页还显示旧条目。 */
    onCategoryCleared: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var summary by remember { mutableStateOf<StorageUsageSummary?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    // 待确认的清理动作：危险操作先弹窗，不直接执行
    var pendingStrategy by remember { mutableStateOf<CleanupStrategy?>(null) }
    var feedback by remember { mutableStateOf("") }
    // 递增即触发重新分析（初值 0 让首帧也跑一次）
    var analyzeTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    // 清理在后台执行，避免大目录删除卡住 UI
    var cleaning by remember { mutableStateOf(false) }

    suspend fun analyze() {
        analyzing = true
        summary = withContext(Dispatchers.IO) { StorageUsageAnalyzer.analyze(context) }
        analyzing = false
    }

    LaunchedEffect(Unit) { analyze() }
    LaunchedEffect(analyzeTick) { if (analyzeTick > 0) analyze() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不铺底色：透出页面底色/背景图，与 Git、量化页一致。
                // （TopAppBar containerColor = Transparent）观感相同。
                // 原来用 BgSecondary（= surface，浅米下纯白）会形成一条白带，选背景图时更是挡图。
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text("存储占用", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(18.dp))

            val s = summary
            if (s == null) {
                // 先显示骨架，后台扫描
                GroupLabel("存储概览")
                PanelCard {
                    MetricRow("总占用", "--", TextPrimary)
                    PanelDivider()
                    MetricRow("可清理", "--", TextSecondary)
                }
                Spacer(Modifier.height(22.dp))
                GroupLabel("清理建议")
                StorageUsageAnalyzer.strategies().forEachIndexed { index, strategy ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    StrategyRow(
                        strategy = strategy,
                        enabled = strategy.id != "reset_container" || onResetContainer != null,
                        onClick = { pendingStrategy = strategy }
                    )
                }
                Spacer(Modifier.height(22.dp))
                GroupLabel("分类占用")
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.height(8.dp))
                        Text("扫描中...", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            } else {

            // ===== 存储概览 =====
            GroupLabel("存储概览")
            PanelCard {
                MetricRow("总占用", formatBytes(s.totalBytes), TextPrimary)
                PanelDivider()
                MetricRow("可清理", formatBytes(s.cleanableBytes), if (s.cleanableBytes > 0) Accent else TextSecondary)
                PanelDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("重新分析", color = TextSecondary, fontSize = 12.sp)
                    Button(
                        onClick = { analyzeTick++ },
                        enabled = !analyzing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonBg,
                            contentColor = TextPrimary,
                            disabledContainerColor = Border,
                            disabledContentColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text(if (analyzing) "分析中…" else "重新分析", fontSize = 12.sp) }
                }
            }

            Spacer(Modifier.height(22.dp))

            // ===== 清理建议 =====
            GroupLabel("清理建议")
            StorageUsageAnalyzer.strategies().forEachIndexed { index, strategy ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                StrategyRow(
                    strategy = strategy,
                    enabled = strategy.id != "reset_container" || onResetContainer != null,
                    onClick = { pendingStrategy = strategy }
                )
            }

            Spacer(Modifier.height(22.dp))

            // ===== 分类占用 =====
            GroupLabel("分类占用")
            val max = s.sortedCategories.firstOrNull()?.bytes?.coerceAtLeast(1L) ?: 1L
            s.sortedCategories.forEachIndexed { index, c ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                CategoryRow(category = c, ratio = c.bytes.toFloat() / max.toFloat(), context = context, onCleaned = { clearedId -> analyzeTick++; onCategoryCleared(clearedId) })
            }

            if (feedback.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(feedback, color = Accent, fontSize = 12.sp)
            }

            Spacer(Modifier.height(32.dp))
        }
        }
    }

    // 清理确认弹窗：危险操作必须二次确认，并显示具体后果
    pendingStrategy?.let { strategy ->
        AlertDialog(
            onDismissRequest = { pendingStrategy = null },
            title = { Text("确认清理", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column {
                    Text(strategy.description, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "将清理：" + strategy.categoryIds.joinToString("、") { id ->
                            summary?.categories?.firstOrNull { it.id == id }?.name ?: id
                        },
                        color = TextSecondary, fontSize = 12.sp
                    )
                    val hints = summary?.categories
                        ?.filter { it.id in strategy.categoryIds && it.cleanupHint != null }
                        ?.map { "· ${it.name}：${it.cleanupHint}" }
                        .orEmpty()
                    if (hints.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        hints.forEach {
                            Text(
                                it,
                                color = if (strategy.risk == "dangerous") androidx.compose.ui.graphics.Color(0xFFFF6B6B) else TextSecondary,
                                fontSize = 11.sp, lineHeight = 16.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = strategy
                    pendingStrategy = null
                    // 重置容器走 ViewModel 的既有逻辑（要停 PTY 会话、清理引擎缓存），
                    // 不能只删目录——否则容器引擎仍认为已安装。
                    if (target.id == "reset_container") {
                        onResetContainer?.invoke()
                        feedback = "已请求重置容器，下次打开终端页会重新安装"
                        analyzeTick++
                    } else if (!cleaning) {
                        cleaning = true
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) {
                                target.categoryIds.sumOf { StorageUsageAnalyzer.clearCategory(context, it) }
                            }
                            cleaning = false
                            feedback = "已释放 ${formatBytes(freed)}"
                            analyzeTick++
                        }
                    }
                }) {
                    Text(
                        if (strategy.risk == "dangerous") "确认清理" else "清理",
                        color = if (strategy.risk == "dangerous") androidx.compose.ui.graphics.Color(0xFFFF6B6B) else Accent
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingStrategy = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = BgSecondary
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f).height(1.dp).background(Border.copy(alpha = 0.6f)))
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun PanelDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Border.copy(alpha = 0.5f)))
}

@Composable
private fun MetricRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StrategyRow(strategy: CleanupStrategy, enabled: Boolean, onClick: () -> Unit) {
    val riskColor = when (strategy.risk) {
        "dangerous" -> androidx.compose.ui.graphics.Color(0xFFFF6B6B)
        "caution" -> androidx.compose.ui.graphics.Color(0xFFF6C760)
        else -> Accent
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strategy.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(Modifier.width(6.dp))
                RiskTag(strategy.risk)
            }
            Spacer(Modifier.height(3.dp))
            Text(strategy.description, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (strategy.risk == "dangerous") riskColor.copy(alpha = 0.15f) else Accent,
                contentColor = if (strategy.risk == "dangerous") riskColor else TextPrimary,
                disabledContainerColor = Border,
                disabledContentColor = TextSecondary
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) { Text("执行", fontSize = 12.sp) }
    }
}

@Composable
private fun RiskTag(risk: String) {
    val (label, color) = when (risk) {
        "dangerous" -> "危险" to androidx.compose.ui.graphics.Color(0xFFFF6B6B)
        "caution" -> "注意" to androidx.compose.ui.graphics.Color(0xFFF6C760)
        "safe" -> "安全" to androidx.compose.ui.graphics.Color(0xFF35C58B)
        else -> "信息" to TextSecondary
    }
    Text(
        label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
private fun CategoryRow(category: StorageCategory, ratio: Float, context: android.content.Context, onCleaned: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<com.wy.openx.data.storage.StorageItem>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { if (category.cleanable || category.id == "other" || category.id == "history" || category.id == "memory" || category.id == "knowledge") expanded = !expanded }
        ) {
            Text(category.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.width(6.dp))
            RiskTag(category.risk)
            Spacer(Modifier.weight(1f))
            Text(formatBytes(category.bytes), fontSize = 13.sp, color = TextSecondary)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        // 占比条：以最大项为基准，直观看出「空间去哪了」
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(PanelAlt)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(ratio.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when (category.risk) {
                            "dangerous" -> androidx.compose.ui.graphics.Color(0xFFFF6B6B)
                            "caution" -> androidx.compose.ui.graphics.Color(0xFFF6C760)
                            else -> Accent
                        }
                    )
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(category.description, color = TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
        if (category.cleanupHint != null) {
            Text(category.cleanupHint, color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp, lineHeight = 14.sp)
        }
        if (expanded && category.cleanable) {
            Spacer(Modifier.height(6.dp))
            LaunchedEffect(expanded) {
                if (items.isEmpty()) {
                    items = withContext(Dispatchers.IO) { com.wy.openx.data.storage.StorageUsageAnalyzer.listItems(context, category.id) }
                }
            }
            if (items.isNotEmpty()) {
                items.take(50).forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (selected.contains(item.path)) selected - item.path else selected + item.path
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = selected.contains(item.path),
                            onCheckedChange = {
                                selected = if (selected.contains(item.path)) selected - item.path else selected + item.path
                            }
                        )
                        Text(item.name, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(formatBytes(item.size), color = TextSecondary, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { selected = items.map { it.path }.toSet() }) { Text("全选", color = TextSecondary) }
                    TextButton(onClick = { selected = emptySet() }) { Text("取消", color = TextSecondary) }
                    var showConfirm by remember { mutableStateOf(false) }
                    if (showConfirm) {
                        AlertDialog(
                            onDismissRequest = { showConfirm = false },
                            title = { Text("确认清理", color = TextPrimary) },
                            text = {
                                Column {
                                    Text("确定清理选中的 ${selected.size} 项？", color = TextSecondary)
                                    Spacer(Modifier.height(6.dp))
                                    Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                                        selected.forEach { p ->
                                            Text("· ${java.io.File(p).name}", color = TextSecondary, fontSize = 12.sp)
                                            Text("  $p", color = TextSecondary.copy(alpha = 0.7f), fontSize = 10.sp)
                                            Spacer(Modifier.height(4.dp))
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showConfirm = false
                                    cleaning = true
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            selected.forEach { p -> runCatching { java.io.File(p).deleteRecursively() } }
                                        }
                                        cleaning = false
                                        selected = emptySet()
                                        items = emptyList()
                                        onCleaned(category.id)
                                    }
                                }) { Text("清理", color = Accent) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showConfirm = false }) { Text("取消", color = TextSecondary) }
                            },
                            containerColor = BgSecondary
                        )
                    }
                    TextButton(enabled = selected.isNotEmpty() && !cleaning, onClick = { showConfirm = true }) { Text(if (cleaning) "清理中" else "清理选中", color = Accent) }
                }
            } else {
                Text("暂无可展开项", color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

/** 人类可读的体积：与备份模块 `formatSize` 同口径。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
