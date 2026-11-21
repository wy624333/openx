package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.data.log.LogEntry
import com.wy.openx.data.log.LogLevel
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private val LOG_ERROR_COLOR = Color(0xFFFF6B6B)
private val LOG_WARN_COLOR = Color(0xFFE8B64C)
private val LOG_DEBUG_COLOR = Color(0xFF8A93A6)

/**
 * 运行日志弹窗：级别筛选、关键词搜索、复制单条/全部、导出文件、清空、实时跟随。
 * 日志源为 [com.wy.openx.data.log.AppLogStore]（内存环形缓冲 + 落盘），
 * 因此容器/MCP/技能等模块的错误同样可见，且重启后保留上次会话的日志尾部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSheet(
    logs: List<LogEntry>,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    logDirPath: String? = null
) {
    var query by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var autoFollow by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    var tip by remember { mutableStateOf("") }

    val filtered = remember(logs, query, levelFilter) {
        logs.filter { e ->
            (levelFilter == null || e.level == levelFilter) &&
                (query.isBlank() || e.message.contains(query, ignoreCase = true) ||
                    e.tag.contains(query, ignoreCase = true))
        }
    }

    // 仅在用户开启跟随时贴底，避免翻看历史时被新日志拽回底部
    LaunchedEffect(filtered.size, autoFollow) {
        if (autoFollow && filtered.isNotEmpty()) scrollState.scrollTo(scrollState.maxValue)
    }
    LaunchedEffect(tip) {
        if (tip.isNotEmpty()) {
            delay(1200)
            tip = ""
        }
    }

    val counts = remember(logs) { logs.groupingBy { it.level }.eachCount() }

    // 全屏子页面（原为底部 sheet）。内容自带「返回 + 标题 + 导出/复制/清空」工具条，
    // 故这里只需换成全屏容器，并把顶部让开状态栏。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("运行日志", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("${filtered.size}/${logs.size} 条", color = TextSecondary, fontSize = 11.sp)
                }
                TextButton(
                    onClick = { onExport(); tip = "已导出" },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text(if (tip == "已导出") "已导出" else "导出", color = Accent, fontSize = 12.sp) }
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(filtered.joinToString("\n") { formatForCopy(it) })); tip = "已复制" },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text(if (tip == "已复制") "已复制" else "复制", color = Accent, fontSize = 12.sp) }
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("清空", color = LOG_ERROR_COLOR, fontSize = 12.sp) }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                val tfColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent,
                    focusedContainerColor = Panel,
                    unfocusedContainerColor = Panel
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索消息或来源…", color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp) },
                    shape = RoundedCornerShape(10.dp),
                    colors = tfColors,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = TextSecondary, modifier = Modifier.size(16.dp)) }
                )

                Spacer(modifier = Modifier.height(8.dp))
                // 级别筛选：全部 / 错误 / 警告 / 信息 / 调试，带计数
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChipMini("全部", counts.values.sum(), levelFilter == null) { levelFilter = null }
                    FilterChipMini("错误", counts[LogLevel.ERROR] ?: 0, levelFilter == LogLevel.ERROR, LOG_ERROR_COLOR) { levelFilter = LogLevel.ERROR }
                    FilterChipMini("警告", counts[LogLevel.WARN] ?: 0, levelFilter == LogLevel.WARN, LOG_WARN_COLOR) { levelFilter = LogLevel.WARN }
                    FilterChipMini("信息", counts[LogLevel.INFO] ?: 0, levelFilter == LogLevel.INFO, Accent) { levelFilter = LogLevel.INFO }
                    FilterChipMini("调试", counts[LogLevel.DEBUG] ?: 0, levelFilter == LogLevel.DEBUG, LOG_DEBUG_COLOR) { levelFilter = LogLevel.DEBUG }
                }

                // 落盘目录提示：日志自动保存到 /sdcard/openx/logs（外置不可用时降级私有目录）
                Text(
                    "自动保存至：${logDirPath ?: "（初始化中…）"}",
                    color = TextSecondary.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (logs.isEmpty()) "暂无日志" else "无匹配日志", color = TextSecondary, fontSize = 13.sp)
                    }
                } else {
                    // ⚠ 不能拿 SelectionContainer 包 LazyColumn：
                    //   Lazy 列表会回收离屏 item，selectable 随之从 selection registry 注销，
                    //   而 SelectionManager 仍持有那些 id——拖动选择跨很多行时
                    //   MultiWidgetSelectionDelegate.getDirectionById 对已注销 id 调 LongIntMap.get
                    //   抛 NoSuchElementException（Cannot find value for key N），main 线程直接崩溃。
                    //   这是 Compose foundation 的已知缺陷（本 App 的 FileTreeDrawer 已用
                    //   「合并成单个 Text」绕开同一问题，注释标注 foundation 1.12.0 才修）。
                    // 这里沿用同一思路：整屏日志合成**一个** AnnotatedString（保留级别配色），
                    // 只含 1 个 selectable → 走 SingleSelectionLayout，既保留跨行拖选，又不崩。
                    // 代价：失去「点击单行复制」（顶部「复制」按钮可复制全部筛选结果）。
                    val timeColor = TextSecondary.copy(alpha = 0.55f)
                    val tagColor = Accent.copy(alpha = 0.75f)
                    val errorColor = LOG_ERROR_COLOR
                    val warnColor = LOG_WARN_COLOR
                    val debugColor = LOG_DEBUG_COLOR
                    val infoColor = TextSecondary
                    val logText = remember(filtered, timeColor, tagColor, errorColor, warnColor, debugColor, infoColor) {
                        buildAnnotatedString {
                            filtered.forEachIndexed { i, e ->
                                withStyle(SpanStyle(color = timeColor)) { append(e.formatTime().takeLast(12)) }
                                append("  ")
                                withStyle(SpanStyle(color = tagColor)) { append("[${e.tag}] ") }
                                withStyle(
                                    SpanStyle(
                                        color = when (e.level) {
                                            LogLevel.ERROR -> errorColor
                                            LogLevel.WARN -> warnColor
                                            LogLevel.DEBUG -> debugColor
                                            LogLevel.INFO -> infoColor
                                        }
                                    )
                                ) { append(e.message) }
                                if (i != filtered.lastIndex) append('\n')
                            }
                        }
                    }
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = logText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (autoFollow) "● 自动跟随最新" else "○ 已暂停跟随",
                        color = if (autoFollow) Accent else TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { autoFollow = !autoFollow }.padding(4.dp)
                    )
                    Text(
                        "上下滑动可暂停跟随",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipMini(
    label: String,
    count: Int,
    selected: Boolean,
    color: Color = TextSecondary,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) color.copy(alpha = 0.2f) else PanelAlt,
        modifier = Modifier
    ) {
        Text(
            "$label $count",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = if (selected) color else TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private fun formatForCopy(e: LogEntry): String =
    "${e.formatTime()}  ${e.level.name.padEnd(5)}  [${e.tag}] ${e.message}"

@Composable
private fun LogLine(entry: LogEntry, onClick: () -> Unit) {
    val color = when (entry.level) {
        LogLevel.ERROR -> LOG_ERROR_COLOR
        LogLevel.WARN -> LOG_WARN_COLOR
        LogLevel.DEBUG -> LOG_DEBUG_COLOR
        LogLevel.INFO -> TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            entry.formatTime().takeLast(12),
            color = TextSecondary.copy(alpha = 0.55f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.size(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "[${entry.tag}]",
                color = Accent.copy(alpha = 0.75f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                entry.message,
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp
            )
        }
    }
}
