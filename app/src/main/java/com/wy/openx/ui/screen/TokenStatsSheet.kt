package com.wy.openx.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.max
import kotlin.math.roundToInt
import com.wy.openx.data.local.CallSummary
import com.wy.openx.data.local.DayCallStats
import com.wy.openx.data.local.LlmCallRecordDao
import com.wy.openx.data.local.LlmCallRecordEntity
import com.wy.openx.data.local.ModelCallStats
import com.wy.openx.data.local.ProviderCallStats
import com.wy.openx.data.local.RecentCallRecord
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.BgSecondary
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

private val INPUT_COLOR = Color(0xFF4C9FE8)
private val OUTPUT_COLOR = Color(0xFF4CAF6D)
private val CACHE_COLOR = Color(0xFFE8B64C)
private val ERROR_COLOR = Color(0xFFFF6B6B)

private const val CALLS_PAGE_SIZE = 10

private val COL_TIME_W = 120.dp
private val COL_MODEL_W = 150.dp
private val COL_STATUS_W = 70.dp
private val COL_TOKENS_W = 60.dp
private val COL_DURATION_W = 62.dp
private val COL_COST_W = 80.dp

/** Token 统计周期：决定统计起始时间与趋势粒度（今天=小时粒度，其余=天粒度）。 */
enum class TokenStatsPeriod(val label: String, val startMillis: (Long) -> Long) {
    TODAY("今天", { now ->
        java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }),
    LAST_7_DAYS("近7天", { now -> now - 7 * 86_400_000L }),
    LAST_30_DAYS("近30天", { now -> now - 30 * 86_400_000L }),
    ALL("全部", { 0L })
}

/** Token 统计弹窗的完整 UI 状态，由周期 + 5 个聚合 Flow 组合而成。 */
data class TokenStatsUiState(
    val period: TokenStatsPeriod = TokenStatsPeriod.LAST_7_DAYS,
    val summary: CallSummary? = null,
    val trend: List<DayCallStats> = emptyList(),
    val providers: List<ProviderCallStats> = emptyList(),
    val models: List<ModelCallStats> = emptyList(),
    val recentCalls: List<RecentCallRecord> = emptyList(),
    val callsPage: Int = 0,
    val callsTotal: Int = 0,
    val totalCostUsd: Double = 0.0,
    val recentCallCosts: Map<Long, Double?> = emptyMap(),
    /** 筛选：指定供应商 ID（null 为不限）。 */
    val filterProviderId: String? = null,
    /** 筛选：指定模型名（null 为不限）。 */
    val filterModel: String? = null,
    /** 可供筛选的供应商（id to 名称）。 */
    val availableProviders: List<Pair<String, String>> = emptyList(),
    /** 可供筛选的模型列表。 */
    val availableModels: List<String> = emptyList()
)

private data class RecentPage(val calls: List<RecentCallRecord>, val total: Int, val page: Int)

/**
 * 把趋势聚合补全为周期内的完整时间轴：无记录的天/小时补 0。「全部」周期从最早有记录那天补到当天。
 */
private fun padTrend(
    period: TokenStatsPeriod,
    trend: List<DayCallStats>,
    tzOffsetMillis: Long
): List<DayCallStats> {
    if (trend.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val byDay = trend.associateBy { it.day }
    val isHourly = period == TokenStatsPeriod.TODAY
    val bucketMillis = if (isHourly) 3_600_000L else 86_400_000L
    val endIndex = (now + tzOffsetMillis) / bucketMillis
    val startIndex = when (period) {
        TokenStatsPeriod.TODAY -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
        TokenStatsPeriod.ALL -> trend.first().day
        else -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
    }
    if (startIndex > endIndex) return trend
    return (startIndex..endIndex).map { index ->
        byDay[index] ?: DayCallStats(index, 0, 0, 0, 0, 0, null, null)
    }
}

/** 单次调用的费用估算（USD），模型无单价时返回 null。 */
private fun recordCost(r: LlmCallRecordEntity): Double? {
    val model = r.model ?: return null
    val total = r.inputTokens.toLong() + r.outputTokens.toLong() + r.cachedInputTokens.toLong()
    val cost = estimateCost(model, total)
    return if (cost > 0) cost else null
}

/** 模型聚合行费用估算（USD），模型无单价时返回 0。 */
private fun modelCost(m: ModelCallStats): Double {
    val model = m.model ?: return 0.0
    return estimateCost(model, m.inputTokens + m.outputTokens + m.cachedInputTokens)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenStatsSheet(
    dao: LlmCallRecordDao?,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    /** 已配置的供应商（id to 名称），用于筛选下拉；调用记录里出现过的渠道也会补进去。 */
    configuredProviders: List<Pair<String, String>> = emptyList()
) {
    var period by remember { mutableStateOf(TokenStatsPeriod.LAST_7_DAYS) }
    var page by remember { mutableStateOf(0) }
    var filterProviderId by remember { mutableStateOf<String?>(null) }
    var filterModel by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf(TokenStatsUiState()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(period, page, dao, filterProviderId, filterModel, configuredProviders) {
        val d = dao ?: return@LaunchedEffect
        loading = true
        val now = System.currentTimeMillis()
        val start = period.startMillis(now)
        val tz = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val trendFlow = if (period == TokenStatsPeriod.TODAY) {
            d.getHourStats(start, tz, filterProviderId, filterModel)
        } else {
            d.getDayStats(start, tz, filterProviderId, filterModel)
        }
        // 可选筛选项来自「不加筛选」的全量查询：筛选列表得包含所有渠道/模型，
        // 否则选了供应商 A 之后就再也没法切到 B 了。
        val allProvidersFlow = d.getProviderStats(start)
        val allModelsFlow = d.getModelStats(start)
        // combine 只有 2~5 个流的重载，这里把「筛选项」两流先合成一个
        val optionsFlow = combine(allProvidersFlow, allModelsFlow) { allP, allM ->
            val fromRecords = allP.mapNotNull { p ->
                val id = p.providerId ?: return@mapNotNull null
                id to (p.providerName ?: id)
            }
            // 记录里出现过的渠道 + 已配置的渠道，去重后作为下拉选项
            (fromRecords + configuredProviders).distinctBy { it.first } to
                allM.mapNotNull { it.model }.distinct()
        }
        combine(
            trendFlow,
            d.getSummary(start, filterProviderId, filterModel),
            d.getProviderStats(start, filterProviderId),
            d.getModelStats(start, filterProviderId, filterModel),
            combine(
                combine(
                    d.getCallsCount(start, filterProviderId, filterModel),
                    d.getRecentCalls(start, CALLS_PAGE_SIZE, page * CALLS_PAGE_SIZE, filterProviderId, filterModel)
                ) { total, calls -> RecentPage(calls, total, page) },
                optionsFlow
            ) { pg, options -> pg to options }
        ) { rawTrend, summary, providers, models, pgWithOptions ->
            val pg = pgWithOptions.first
            val options = pgWithOptions.second
            val trend = padTrend(period, rawTrend, tz)
            val costs = pg.calls.associate { it.record.id to recordCost(it.record) }
            val totalCostUsd = models.sumOf { modelCost(it) }
            TokenStatsUiState(
                period = period,
                summary = summary,
                trend = trend,
                providers = providers,
                models = models,
                recentCalls = pg.calls,
                callsPage = pg.page,
                callsTotal = pg.total,
                totalCostUsd = totalCostUsd,
                recentCallCosts = costs,
                filterProviderId = filterProviderId,
                filterModel = filterModel,
                availableProviders = options.first,
                availableModels = options.second
            )
        }.collect { stats = it; loading = false }
    }

    var showClearConfirm by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text("Token 统计", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showClearConfirm = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "清空统计",
                    tint = ERROR_COLOR,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("清空统计", color = TextPrimary) },
                text = { Text("确定要清空全部 Token 调用统计数据吗？", color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirm = false
                        val d = dao ?: return@TextButton
                        scope.launch {
                            d.deleteAll()
                            onReset()
                            page = 0
                        }
                    }) { Text("确定", color = ERROR_COLOR) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("取消", color = TextSecondary) }
                },
                containerColor = Panel
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TokenStatsPeriod.entries.forEach { p ->
                    FilterChip(
                        selected = stats.period == p,
                        onClick = { period = p; page = 0 },
                        label = { Text(p.label, fontSize = 13.sp) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = Color.White,
                            containerColor = PanelAlt,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = stats.period == p,
                            borderColor = Border, selectedBorderColor = Color.Transparent),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 筛选：供应商 / 模型。两个都选「全部」时与无筛选完全等价
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsFilterDropdown(
                    label = "全部供应商",
                    selected = stats.filterProviderId?.let { id ->
                        stats.availableProviders.firstOrNull { it.first == id }?.second ?: id
                    },
                    options = stats.availableProviders.map { it.second },
                    modifier = Modifier.weight(1f),
                    onSelect = { idx ->
                        filterProviderId = if (idx < 0) null else stats.availableProviders[idx].first
                        page = 0
                    }
                )
                StatsFilterDropdown(
                    label = "全部模型",
                    selected = stats.filterModel,
                    options = stats.availableModels,
                    modifier = Modifier.weight(1f),
                    onSelect = { idx ->
                        filterModel = if (idx < 0) null else stats.availableModels[idx]
                        page = 0
                    }
                )
            }

            if (loading && stats.summary == null) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                val summary = stats.summary
                val hasData = summary != null && summary.calls > 0
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val totalTok = (summary?.inputTokens ?: 0L) + (summary?.outputTokens ?: 0L)
                    SummaryCard("总 Token", if (hasData) formatTokens(totalTok) else "-", modifier = Modifier.weight(1f))
                    SummaryCard("请求数", if (hasData) summary!!.calls.toString() else "-", modifier = Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryCard("费用(美元)", formatSheetCostUsd(stats.totalCostUsd), modifier = Modifier.weight(1f))
                    val rate = cacheHitRate(summary)
                    val cached = summary?.cachedInputTokens ?: 0L
                    SummaryCard("缓存命中率", if (hasData) rate else "-",
                        if (hasData && cached > 0) formatTokens(cached) else null,
                        modifier = Modifier.weight(1f))
                }

                if (stats.trend.size > 1) {
                    SectionHeader("趋势")
                    SettingsCard {
                        TokenTrendChart(trend = stats.trend, isHourly = stats.period == TokenStatsPeriod.TODAY)
                        HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 2.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LegendDot(INPUT_COLOR, "输入")
                            LegendDot(OUTPUT_COLOR, "输出")
                            LegendDot(CACHE_COLOR, "缓存")
                        }
                    }
                }

                SectionHeader("渠道统计")
                SettingsCard {
                    if (stats.providers.isEmpty()) {
                        EmptyStatsPlaceholder()
                    } else {
                        stats.providers.forEachIndexed { index, p ->
                            if (index > 0) HorizontalDivider(color = Border)
                            ProviderStatsRow(p)
                        }
                    }
                }

                SectionHeader("模型统计")
                SettingsCard {
                    if (stats.models.isEmpty()) {
                        EmptyStatsPlaceholder()
                    } else {
                        stats.models.forEachIndexed { index, m ->
                            if (index > 0) HorizontalDivider(color = Border)
                            ModelStatsRow(m)
                        }
                    }
                }

                SectionHeader("调用明细")
                SettingsCard {
                    if (stats.recentCalls.isEmpty()) {
                        EmptyStatsPlaceholder()
                    } else {
                        CallRecordsTable(
                            calls = stats.recentCalls,
                            costs = stats.recentCallCosts,
                            page = stats.callsPage,
                            total = stats.callsTotal,
                            onSelectPage = { page = it }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Panel
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, color = TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
            } else {
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun TokenTrendChart(trend: List<DayCallStats>, isHourly: Boolean) {
    val density = LocalDensity.current
    val labelColor = TextSecondary.toArgb()
    val labelTextSize = with(density) { 10.sp.toPx() }
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = labelTextSize
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    val xLabelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = labelTextSize
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val seriesList = remember(trend) {
        listOf(
            trend.map { it.inputTokens.toDouble() },
            trend.map { it.outputTokens.toDouble() },
            trend.map { it.cachedInputTokens.toDouble() }
        )
    }
    val seriesColors = listOf(INPUT_COLOR, OUTPUT_COLOR, CACHE_COLOR)
    val maxVal = remember(trend) {
        max(trend.maxOfOrNull { (it.inputTokens + it.outputTokens + it.cachedInputTokens).toDouble() } ?: 0.0, 1.0)
    }
    val xLabels = remember(trend, isHourly) {
        val n = trend.size
        val step = if (n <= 6) 1 else (n / 6.0).roundToInt().coerceAtLeast(1)
        (0 until n step step).map { idx ->
            val day = trend[idx].day
            if (isHourly) "${day % 24}时"
            else SimpleDateFormat("M/d", Locale.getDefault()).format(Date(day * 86_400_000L + tzOffsetNow()))
        } to (0 until n step step).toList()
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(190.dp)) {
        val nMini = 4
        val left = 40.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val n = trend.size
        val xStep = if (n > 1) (right - left) / (n - 1).toFloat() else 0f
        fun xAt(idx: Int) = if (n > 1) left + idx * xStep else left

        // y 轴网格与刻度（4 段）
        for (i in 0..nMini) {
            val y = top + (bottom - top) * (nMini - i) / nMini.toFloat()
            drawLine(Border.copy(alpha = 0.5f), Offset(left, y), Offset(right, y), strokeWidth = 1f)
            val v = maxVal * i / nMini
            labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(
                formatTokens(v.toLong()),
                left - 6.dp.toPx(), y + labelTextSize * 0.35f, labelPaint
            )
        }

        // x 轴标签
        xLabels.second.forEachIndexed { i, idx ->
            drawContext.canvas.nativeCanvas.drawText(
                xLabels.first[i],
                xAt(idx), bottom + 18.dp.toPx(), xLabelPaint
            )
        }

        // 三条折线：面积层 + 线段层
        seriesList.zip(seriesColors).forEach { (series, color) ->
            if (n > 0) {
                val points = series.mapIndexed { idx, v ->
                    Offset(xAt(idx), bottom - (bottom - top) * (v / maxVal).toFloat())
                }
                if (n == 1) {
                    drawLine(color, points[0], points[0], strokeWidth = 2.dp.toPx())
                } else {
                    val area = Path().apply {
                        moveTo(points.first().x, bottom)
                        points.forEach { lineTo(it.x, it.y) }
                        lineTo(points.last().x, bottom)
                        close()
                    }
                    drawPath(area, color.copy(alpha = 0.18f))
                    val line = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

private fun tzOffsetNow(): Long =
    java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

@Composable
private fun EmptyStatsPlaceholder() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(14.dp).height(60.dp)
            .background(PanelAlt.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("暂无数据", color = TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun ProviderStatsRow(p: ProviderCallStats) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.providerName ?: p.providerId ?: "未知", color = TextPrimary, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text("${p.calls} 次", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Text("↑${formatTokens(p.inputTokens)} ↓${formatTokens(p.outputTokens)}", color = TextPrimary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight((p.inputTokens + 1f).toFloat()).height(6.dp).background(INPUT_COLOR, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(2.dp))
            Box(modifier = Modifier.weight((p.outputTokens + 1f).toFloat()).height(6.dp).background(OUTPUT_COLOR, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun ModelStatsRow(m: ModelCallStats) {
    val successRate = if (m.calls > 0) {
        String.format(Locale.getDefault(), "%.1f%%", (m.calls - m.errors) * 100.0 / m.calls)
    } else {
        "-"
    }
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.model ?: "未知", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text("↓${formatTokens(m.outputTokens)}", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${m.calls} 次", color = TextSecondary, fontSize = 12.sp)
            Text("成功率 $successRate", color = TextSecondary, fontSize = 12.sp)
            Text("↑${formatTokens(m.inputTokens)}", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CallRecordsTable(
    calls: List<RecentCallRecord>,
    costs: Map<Long, Double?>,
    page: Int,
    total: Int,
    onSelectPage: (Int) -> Unit
) {
    val textColor = TextPrimary
    val mutedColor = TextSecondary
    val pageCount = if (total == 0) 1 else (total - 1) / CALLS_PAGE_SIZE + 1
    Column {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    TableCell("时间", mutedColor, COL_TIME_W, FontWeight.Medium)
                    TableCell("模型", mutedColor, COL_MODEL_W, FontWeight.Medium)
                    TableCell("状态", mutedColor, COL_STATUS_W, FontWeight.Medium)
                    TableCell("输入", mutedColor, COL_TOKENS_W, FontWeight.Medium)
                    TableCell("输出", mutedColor, COL_TOKENS_W, FontWeight.Medium)
                    TableCell("缓存", mutedColor, COL_TOKENS_W, FontWeight.Medium)
                    TableCell("耗时", mutedColor, COL_DURATION_W, FontWeight.Medium)
                    TableCell("费用", mutedColor, COL_COST_W, FontWeight.Medium)
                }
                calls.forEach { call ->
                    CallRecordRow(call, costs[call.record.id], textColor, mutedColor)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onSelectPage(page - 1) }, enabled = page > 0) { Text("上一页", color = TextSecondary, fontSize = 13.sp) }
            Text("${page + 1} / $pageCount", color = mutedColor, fontSize = 12.sp)
            TextButton(onClick = { onSelectPage(page + 1) }, enabled = page < pageCount - 1) { Text("下一页", color = TextSecondary, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun CallRecordRow(call: RecentCallRecord, cost: Double?, textColor: Color, mutedColor: Color) {
    val statusColor = when (call.record.status) {
        "error" -> ERROR_COLOR
        else -> OUTPUT_COLOR
    }
    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        TableCell(formatCallTime(call.record.createdAt), mutedColor, COL_TIME_W)
        TableCell(call.providerName ?: call.record.providerId ?: "未知", textColor, COL_MODEL_W)
        TableCell(call.record.status, statusColor, COL_STATUS_W, FontWeight.Medium)
        TableCell(call.record.inputTokens.toString(), textColor, COL_TOKENS_W)
        TableCell(call.record.outputTokens.toString(), textColor, COL_TOKENS_W)
        TableCell(call.record.cachedInputTokens.toString(), textColor, COL_TOKENS_W)
        TableCell(formatDuration(call.record.durationMillis?.toDouble()), mutedColor, COL_DURATION_W)
        TableCell(if (cost == null) "-" else formatSheetCostUsd(cost), textColor, COL_COST_W)
    }
}

@Composable
private fun TableCell(text: String, color: Color, width: Dp, fontWeight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = fontWeight,
        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.width(width))
}

private fun cacheHitRate(s: CallSummary?): String {
    if (s == null || s.inputTokens <= 0) return "-"
    val rate = min(s.cachedInputTokens * 100.0 / s.inputTokens, 100.0)
    return String.format(Locale.getDefault(), "%.1f%%", rate)
}

private fun formatCallTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun formatSheetCostUsd(cost: Double): String = when {
    cost <= 0.0 -> "$0.00"
    cost < 0.0001 -> "<\$0.0001"
    cost < 0.01 -> String.format(Locale.getDefault(), "$%.4f", cost)
    else -> String.format(Locale.getDefault(), "$%.2f", cost)
}

private fun formatDuration(ms: Double?): String {
    if (ms == null) return "-"
    return if (ms >= 1000) String.format(Locale.getDefault(), "%.1fs", ms / 1000)
    else "${ms.toInt()}ms"
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    // 不再画卡片底板：与设置页/偏好设置页统一，各行直接铺在页面背景上（背景图可透出）。
    // 行自身的横向 padding 与滚动容器已给出的 16dp 共同决定缩进，本容器不再加边距。
    Column { content() }
}

/**
 * 统计页的筛选下拉：一个圆角胶囊，点开列出选项（首项为「全部」，对应 null）。
 *
 * 用 DropdownMenu 自己拼而不引封装：这页只需要「选一项」这一种形态，
 * 加一层通用组件反而要多处理别的场景。
 *
 * [onSelect] 回调下标：-1 表示「全部」（取消筛选），其余为 [options] 下标。
 */
@Composable
private fun StatsFilterDropdown(
    label: String,
    selected: String?,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier = modifier) {
        // 菜单宽度收在本按钮宽度以内：模型名很长时若按内容固有宽度撑开，会越过中线
        // 盖住另一个筛选按钮，左右两个菜单也不等宽。定位仍由 DropdownMenu 自己算。
        val menuMaxWidth = maxWidth
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(10.dp),
            color = PanelAlt,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected ?: label,
                    fontSize = 13.sp,
                    color = if (selected == null) TextSecondary else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Panel,
            // 渠道/模型可能各有几十项，不限高会把菜单撑满整屏。
            // DropdownMenu 内部已自带 verticalScroll，给个高度上限即可滚动查看。
            modifier = Modifier
                .heightIn(max = 360.dp)
                .widthIn(max = menuMaxWidth)
        ) {
            DropdownMenuItem(
                text = { Text(label, fontSize = 13.sp, color = TextSecondary) },
                onClick = { expanded = false; onSelect(-1) }
            )
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            fontSize = 13.sp,
                            color = if (option == selected) Accent else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { expanded = false; onSelect(index) }
                )
            }
        }
    }
}
