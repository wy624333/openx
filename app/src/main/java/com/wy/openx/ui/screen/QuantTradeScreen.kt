package com.wy.openx.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonParser
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.ButtonBg
import com.wy.openx.ui.theme.ButtonFg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import com.wy.openx.tools.StockTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class WatchlistStock(
    val symbol: String,
    val name: String,
    val added: String,
    val lastPrice: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuantTradeScreen(
    onBack: () -> Unit,
    onSubmitPrompt: (String) -> Unit
) {
    var stockCode by remember { mutableStateOf("") }
    var watchlist by remember { mutableStateOf<List<WatchlistStock>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val watchlistDir = File(context.filesDir, "stock_memory")
    val watchlistFile = File(watchlistDir, "watchlist.json")

    fun refresh() {
        scope.launch {
            loading = true
            val items = loadWatchlist(watchlistFile)
            if (items.isNotEmpty()) {
                val updated = refreshPrices(watchlistFile, items)
                if (updated != null) watchlist = updated else watchlist = items
            } else {
                watchlist = items
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { watchlist = loadWatchlist(watchlistFile); loading = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("量化交易", fontWeight = FontWeight.Bold, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                        Text("AI 分析决策 · 用户手动执行", color = TextSecondary, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        // PageBg：选背景图时透明透出根背景层（原 BgPrimary 会整屏挡住）
        containerColor = PageBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = stockCode,
                    onValueChange = { stockCode = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入股票代码，如 000001, 600519", color = TextSecondary.copy(alpha = 0.5f)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Accent
                    )
                )
                Button(
                    onClick = {
                        if (stockCode.isBlank()) return@Button
                        adding = true
                        scope.launch {
                            addToWatchlist(watchlistFile, stockCode)
                            watchlist = loadWatchlist(watchlistFile)
                            stockCode = ""
                            adding = false
                        }
                    },
                    enabled = stockCode.isNotBlank() && !adding,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = ButtonFg),
                    modifier = Modifier.height(56.dp)
                ) {
                    if (adding) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("自选", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (!loading && watchlist.isNotEmpty()) {
                WatchlistSection(
                    items = watchlist,
                    onRefresh = { refresh() },
                    onDelete = { symbol ->
                        scope.launch {
                            removeFromWatchlist(watchlistFile, symbol)
                            watchlist = loadWatchlist(watchlistFile)
                        }
                    },
                    onAnalyzeAll = { onSubmitPrompt("请分析我的自选股，逐一给出技术面判断和操作建议") },
                    onAnalyze = { sym -> onSubmitPrompt("请对$sym 进行完整量化分析，包含技术指标、策略信号和风险评估") }
                )
            }

            Text("快速分析", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            ActionGrid(stockCode = stockCode, onSubmitPrompt = onSubmitPrompt)

            Spacer(modifier = Modifier.height(8.dp))
            Text("持仓与交易", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            TradeActions(stockCode = stockCode, onSubmitPrompt = onSubmitPrompt)

            Spacer(modifier = Modifier.height(8.dp))
            PaperTradingPanel(context = context, onSubmitPrompt = onSubmitPrompt)
        }
    }
}

@Composable
private fun WatchlistSection(
    items: List<WatchlistStock>,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onAnalyzeAll: () -> Unit,
    onAnalyze: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (expanded) "📂" else "📁", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("自选股 (${items.size}只)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Row {
                    Text(if (expanded) "▼" else "▶", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                items.forEachIndexed { idx, stock ->
                    if (idx > 0) Spacer(modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Border))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { onAnalyze(stock.symbol) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stock.name.ifEmpty { stock.symbol }, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Row {
                                Text(stock.symbol, color = TextSecondary, fontSize = 10.sp)
                                if (stock.added.isNotEmpty()) {
                                    Text("  ·  ${stock.added}", color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp)
                                }
                            }
                        }
                        Text(stock.lastPrice.ifEmpty { "—" }, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { deleteTarget = stock.symbol }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFEF5350), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onAnalyzeAll,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI 一键分析全部自选股", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = { Text("确认删除「${deleteTarget}」自选股？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget?.let { onDelete(it) }
                    deleteTarget = null
                }) {
                    Text("删除", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private data class ActionItem(val icon: ImageVector, val label: String, val prompt: String)

@Composable
private fun ActionGrid(stockCode: String, onSubmitPrompt: (String) -> Unit) {
    val actions = listOf(
        ActionItem(Icons.Default.TrendingUp, "趋势识别", "请对${stockCode}进行quant multiframe分析，判断当前趋势状态"),
        ActionItem(Icons.Default.ShowChart, "技术指标", "请对${stockCode}进行quant regime分析，评估当前市场状态"),
        ActionItem(Icons.Default.Analytics, "策略信号", "请对${stockCode}进行quant strategy分析，输出买卖信号"),
        ActionItem(Icons.Default.Warning, "风险评估", "请对${stockCode}进行quant risk分析，计算最大回撤、波动率等风控指标"),
        ActionItem(Icons.Default.AccountBalance, "板块轮动", "请执行quant rotation分析当前市场行业板块轮动情况"),
        ActionItem(Icons.Default.SwapHoriz, "策略回测", "请对${stockCode}进行quant backtest分析，回测均线策略表现"),
        ActionItem(Icons.Default.Tune, "参数优化", "请对${stockCode}进行quant optimize分析，搜索最优均线参数"),
        ActionItem(Icons.Default.Assessment, "完整诊断", "请对${stockCode}进行完整的量化分析，包含技术指标、市场状态、策略信号、风险评估"),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    val enabled = stockCode.isNotBlank() || action.label == "板块轮动"
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = enabled) {
                                if (enabled) onSubmitPrompt(action.prompt)
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (enabled) PanelAlt else PanelAlt.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                action.icon,
                                contentDescription = null,
                                tint = if (enabled) Accent else TextSecondary.copy(alpha = 0.3f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                action.label,
                                color = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TradeActions(stockCode: String, onSubmitPrompt: (String) -> Unit) {
    val actions = listOf(
        ActionItem(Icons.Default.ShowChart, "持仓概况", "请执行portfolio doList显示当前持仓概况"),
        ActionItem(Icons.Default.TrendingUp, "盈亏分析", "请执行portfolio doPnL显示当前持仓盈亏"),
        ActionItem(Icons.Default.History, "信号回顾", "请执行signal回顾最近30天的信号记录和准确率"),
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSubmitPrompt(action.prompt) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = PanelAlt)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(action.icon, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(action.label, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun PaperTradingPanel(
    context: android.content.Context,
    onSubmitPrompt: (String) -> Unit
) {
    var paperStockCode by remember { mutableStateOf("") }
    var allTrades by remember { mutableStateOf<List<PaperTrade>>(emptyList()) }
    var selectedSymbol by remember { mutableStateOf<String?>(null) }
    var chartBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var chartLoading by remember { mutableStateOf(false) }
    var panelLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        allTrades = loadPaperTrades(File(context.filesDir, "portfolio/paper_trades.json"))
        panelLoading = false
    }

    val filteredTrades = if (selectedSymbol != null) filterTradesBySymbol(allTrades, selectedSymbol!!) else allTrades
    val metrics = calcPaperMetrics(allTrades)

    /** 丢弃当前 K 线图并释放其 native 内存（Bitmap 不受 GC 管）。 */
    fun releaseChart() {
        val old = chartBitmap
        chartBitmap = null
        if (old != null && !old.isRecycled) old.recycle()
    }

    // 离开页面时回收：K 线位图尺寸不小，不回收则每进出一次就泄一份 native 内存。
    DisposableEffect(Unit) {
        onDispose { chartBitmap?.let { if (!it.isRecycled) it.recycle() } }
    }

    fun loadChart(symbol: String) {
        chartLoading = true
        selectedSymbol = symbol
        val sym = symbol.lowercase().let { if (it.all { c -> c.isDigit() }) (if (it.startsWith("6") || it.startsWith("9")) "sh$it" else "sz$it") else it }
        scope.launch {
            val tradePoints = filterTradesBySymbol(allTrades, symbol).map {
                StockTool.TradePoint(it.date.take(10), it.direction, it.price)
            }
            val path = withContext(Dispatchers.IO) { StockTool().renderKlinePanel(sym, tradePoints) }
            if (path != null) {
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
                // 旧图必须 recycle：Bitmap 走 native 内存、不受 GC 管，反复看不同股票的 K 线
                // 会让原生内存只增不减。asImageBitmap() 只是包装，同一块 native 内存不会被 GC 回收。
                val old = chartBitmap
                chartBitmap = bmp
                if (old != null && old !== bmp && !old.isRecycled) old.recycle()
            }
            chartLoading = false
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🤖", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text("AI 模拟交易", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Stock code input + buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = paperStockCode,
                    onValueChange = { paperStockCode = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("股票代码", color = TextSecondary.copy(alpha = 0.5f), fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Accent
                    )
                )
                Button(
                    onClick = { if (paperStockCode.isNotBlank()) loadChart(paperStockCode.trim()) },
                    enabled = paperStockCode.isNotBlank() && !chartLoading,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = ButtonFg),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.ShowChart, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("K线", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val code = paperStockCode.trim()
                        if (code.isNotBlank()) onSubmitPrompt("请对${code}进行完整的量化分析，并用模拟盘(portfolio paper_buy/paper_sell)验证你的策略。先给出分析结论，然后执行模拟交易测试。")
                    },
                    enabled = paperStockCode.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = ButtonFg),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("策略验证", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metrics row
            if (!panelLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(PanelAlt, RoundedCornerShape(8.dp)).padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricItem("交易", "${metrics.totalTrades}")
                    MetricItem("胜率", "${String.format("%.1f", metrics.winRate)}%", if (metrics.winRate >= 50) Color(0xFF4CAF50) else Color(0xFFEF5350))
                    MetricItem("盈亏", "${if (metrics.totalPnl >= 0) "+" else ""}${String.format("%.2f", metrics.totalPnl)}", if (metrics.totalPnl >= 0) Color(0xFF4CAF50) else Color(0xFFEF5350))
                    MetricItem("收益率", "${if (metrics.totalPnlPct >= 0) "+" else ""}${String.format("%.1f", metrics.totalPnlPct)}%", if (metrics.totalPnlPct >= 0) Color(0xFF4CAF50) else Color(0xFFEF5350))
                    MetricItem("持仓", "${metrics.openPositions}")
                }
            }

            // K-line chart
            if (chartLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(32.dp))
                }
            } else if (chartBitmap != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Image(
                    bitmap = chartBitmap!!.asImageBitmap(),
                    contentDescription = "K线图",
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("🟢 买入  🔴 卖出", color = TextSecondary, fontSize = 11.sp)
                }
            }

            // Trade history
            if (filteredTrades.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📝", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("交易记录 (${filteredTrades.size})", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (selectedSymbol != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("[${selectedSymbol}]", color = Accent, fontSize = 11.sp, modifier = Modifier.clickable {
                            selectedSymbol = null; releaseChart()
                        })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("×", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.clickable {
                            selectedSymbol = null; releaseChart()
                        })
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = PanelAlt)) {
                    Column(modifier = Modifier.padding(8.dp).horizontalScroll(rememberScrollState())) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            filteredTrades.sortedByDescending { it.id }.take(50).forEach { t ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { loadChart(t.symbol) }.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (t.direction == "buy") "🟢" else "🔴",
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("#${t.id}", color = TextSecondary, fontSize = 10.sp)
                                    Text(t.symbol, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(String.format("%.2f", t.price), color = TextPrimary, fontSize = 11.sp, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
                                    Text("${t.shares}股", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                                    Text(t.date.take(10), color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
                                }
                            }
                        }
                    }
                }
            } else if (!panelLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("暂无模拟交易记录\n通过「策略验证」让 AI 进行模拟买卖", color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }

            if (panelLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, valueColor: Color = TextPrimary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 10.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ── 自选股 I/O ────────────────────────────────────────

/**
 * 读自选股文件。
 *
 * 声明为 suspend + withContext(IO)：调用点都是 scope.launch / LaunchedEffect，
 * 但 launch 默认跑主线程 dispatcher，不切线程的话文件读 + JSON 解析会直接卡 UI。
 */
private suspend fun loadWatchlist(file: File): List<WatchlistStock> = withContext(Dispatchers.IO) {
    if (!file.exists()) return@withContext emptyList()
    try {
        val arr = JsonParser.parseString(file.readText())?.asJsonArray ?: return@withContext emptyList()
        (0 until arr.size()).mapNotNull { i ->
            val o = arr[i]?.asJsonObject ?: return@mapNotNull null
            WatchlistStock(
                symbol = o.get("symbol")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null,
                name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                added = o.get("added")?.takeIf { !it.isJsonNull }?.asString ?: "",
                lastPrice = o.get("lastPrice")?.takeIf { !it.isJsonNull }?.asString ?: ""
            )
        }
    } catch (_: Exception) { emptyList() }
}

private suspend fun saveWatchlist(file: File, items: List<WatchlistStock>) = withContext(Dispatchers.IO) {
    try {
        file.parentFile?.mkdirs()
        val sb = StringBuilder("[\n")
        items.forEachIndexed { i, it ->
            sb.append("  {\n    \"symbol\":\"${it.symbol}\",\n    \"name\":\"${it.name.replace("\\", "\\\\").replace("\"", "\\\"")}\",\n    \"added\":\"${it.added}\",\n    \"lastPrice\":\"${it.lastPrice}\"\n  }${if (i < items.size - 1) "," else ""}\n")
        }
        sb.append("]\n")
        file.writeText(sb.toString())
    } catch (_: Exception) {}
}

private suspend fun addToWatchlist(file: File, rawCode: String) = withContext(Dispatchers.IO) {
    try {
        val sym = normalizeSymbol(rawCode.trim())
        val items = loadWatchlist(file)
        if (items.any { it.symbol == sym }) return@withContext

        val secId = toEastMoneySecId(sym)
        var name = sym.uppercase()
        var price = ""
        if (secId != null) {
            try {
                val conn = URL("https://push2.eastmoney.com/api/qt/stock/get?secid=$secId&fields=f58,f43").openConnection() as HttpURLConnection
                conn.apply { connectTimeout = 4000; readTimeout = 4000; setRequestProperty("User-Agent", "Mozilla/5.0") }
                val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
                val root = try { JsonParser.parseString(body)?.asJsonObject } catch (_: Exception) { null }
                val data = root?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                if (data != null) {
                    data.get("f58")?.takeIf { !it.isJsonNull }?.asString?.let { name = it }
                    data.get("f43")?.takeIf { !it.isJsonNull }?.asDouble?.let { price = String.format("%.2f", it) }
                }
            } catch (_: Exception) {}
        }

        val now = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val newItems = items + WatchlistStock(sym, name, now, price)
        saveWatchlist(file, newItems)
    } catch (_: Exception) {}
}

private suspend fun removeFromWatchlist(file: File, symbol: String) = withContext(Dispatchers.IO) {
    try {
        val items = loadWatchlist(file).filter { it.symbol != symbol }
        saveWatchlist(file, items)
    } catch (_: Exception) {}
}

private fun normalizeSymbol(raw: String): String {
    val s = raw.trim().lowercase()
    if (s.startsWith("sh") || s.startsWith("sz")) return s
    if (s.all { it.isDigit() }) {
        return if (s.startsWith("6") || s.startsWith("9")) "sh$s" else "sz$s"
    }
    return s
}

private fun toEastMoneySecId(symbol: String): String? {
    return when {
        symbol.startsWith("sh") -> "1.${symbol.removePrefix("sh")}"
        symbol.startsWith("sz") -> "0.${symbol.removePrefix("sz")}"
        else -> null
    }
}

private data class PaperTrade(
    val id: Long, val symbol: String, val name: String, val direction: String,
    val price: Double, val shares: Int, val amount: Double, val date: String,
    val note: String? = null, val paper: Boolean = false
)

private data class PaperMetrics(
    val totalTrades: Int, val winCount: Int, val lossCount: Int,
    val winRate: Double, val totalPnl: Double, val totalPnlPct: Double,
    val openPositions: Int
)

private suspend fun loadPaperTrades(file: File): List<PaperTrade> = withContext(Dispatchers.IO) {
    if (!file.exists()) return@withContext emptyList()
    try {
        val arr = JsonParser.parseString(file.readText())?.asJsonArray ?: return@withContext emptyList()
        (0 until arr.size()).mapNotNull { i ->
            val o = arr[i]?.asJsonObject ?: return@mapNotNull null
            PaperTrade(
                id = o.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return@mapNotNull null,
                symbol = o.get("symbol")?.takeIf { !it.isJsonNull }?.asString ?: "",
                name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                direction = o.get("direction")?.takeIf { !it.isJsonNull }?.asString ?: "",
                price = o.get("price")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
                shares = o.get("shares")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                amount = o.get("amount")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
                date = o.get("date")?.takeIf { !it.isJsonNull }?.asString ?: "",
                note = o.get("note")?.takeIf { !it.isJsonNull }?.asString,
                paper = o.get("paper")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            )
        }
    } catch (_: Exception) { emptyList() }
}

private fun filterTradesBySymbol(trades: List<PaperTrade>, symbol: String): List<PaperTrade> {
    val s = symbol.trim().lowercase()
    return trades.filter { it.symbol.lowercase() == s }
}

private fun calcPaperMetrics(trades: List<PaperTrade>): PaperMetrics {
    val buys = trades.filter { it.direction == "buy" }
    val sells = trades.filter { it.direction == "sell" }
    val totalCost = buys.sumOf { it.amount }
    val totalRevenue = sells.sumOf { it.amount }
    val totalPnl = totalRevenue - totalCost
    val totalPnlPct = if (totalCost > 0) totalPnl / totalCost * 100 else 0.0
    val totalSellShares = sells.sumOf { it.shares }
    val totalBuyShares = buys.sumOf { it.shares }
    val openPositions = (totalBuyShares - totalSellShares).coerceAtLeast(0)

    var winCount = 0
    var lossCount = 0
    val symbols = (buys.map { it.symbol } + sells.map { it.symbol }).distinct()
    for (sym in symbols) {
        val symSells = sells.filter { it.symbol == sym }
        val symBuys = buys.filter { it.symbol == sym }
        if (symBuys.isEmpty() || symSells.isEmpty()) continue
        val avgBuyPrice = symBuys.sumOf { it.price * it.shares } / symBuys.sumOf { it.shares }
        for (s in symSells) {
            if (s.price >= avgBuyPrice) winCount++ else lossCount++
        }
    }

    val totalClosed = winCount + lossCount
    val winRate = if (totalClosed > 0) winCount.toDouble() / totalClosed * 100 else 0.0
    return PaperMetrics(trades.size, winCount, lossCount, winRate, totalPnl, totalPnlPct, openPositions)
}

private suspend fun refreshPrices(file: File, items: List<WatchlistStock>): List<WatchlistStock>? = withContext(Dispatchers.IO) {
    try {
        val secIds = items.mapNotNull { toEastMoneySecId(it.symbol) }
        if (secIds.isEmpty()) return@withContext null
        val url = "https://push2.eastmoney.com/api/qt/ulist.np/get?fields=f58,f43&secids=${secIds.joinToString(",")}"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply { connectTimeout = 5000; readTimeout = 5000; setRequestProperty("User-Agent", "Mozilla/5.0") }
        val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
        val root = try { JsonParser.parseString(body)?.asJsonObject } catch (_: Exception) { null }
        val dataArr = root?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@withContext null
        val priceMap = mutableMapOf<String, String>()
        for (e in dataArr) {
            val o = e?.asJsonObject ?: continue
            val secId = o.get("secid")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val price = o.get("f43")?.takeIf { !it.isJsonNull }?.asDouble
            if (price != null && price > 0) {
                val sym = when {
                    secId.startsWith("1.") -> "sh${secId.removePrefix("1.")}"
                    secId.startsWith("0.") -> "sz${secId.removePrefix("0.")}"
                    else -> continue
                }
                priceMap[sym] = String.format("%.2f", price)
            }
        }
        if (priceMap.isEmpty()) return@withContext null
        val updated = items.map { it.copy(lastPrice = priceMap[it.symbol] ?: it.lastPrice) }
        file.parentFile?.mkdirs()
        val sb = StringBuilder("[\n")
        updated.forEachIndexed { i, it ->
            sb.append("  {\n    \"symbol\":\"${it.symbol}\",\n    \"name\":\"${it.name.replace("\\", "\\\\").replace("\"", "\\\"")}\",\n    \"added\":\"${it.added}\",\n    \"lastPrice\":\"${it.lastPrice}\"\n  }${if (i < updated.size - 1) "," else ""}\n")
        }
        sb.append("]\n")
        file.writeText(sb.toString())
        updated
    } catch (_: Exception) { null }
}
