package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class PortfolioTool : AgentTool {
    private companion object {
        @Volatile private var sharedClient: okhttp3.OkHttpClient? = null
        private fun httpClient(): okhttp3.OkHttpClient =
            sharedClient ?: synchronized(this) {
                sharedClient ?: okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build().also { sharedClient = it }
            }
    }


    private val gson = Gson()

    override val name = "portfolio"
    override val description = "持仓管理与模拟盘。记录实盘买入/卖出，模拟虚拟交易，跟踪盈亏和整体风险"
    override val parameters = buildToolParameters(
        ToolParameter("action", ParameterType.STRING, "操作类型\n- buy: 买入记录(实盘)\n- sell: 卖出记录(实盘)\n- list: 查看当前持仓\n- pnl: 盈亏汇总\n- paper_buy: 模拟买入\n- paper_sell: 模拟卖出\n- paper_list: 查看模拟持仓\n- paper_pnl: 模拟盘盈亏\n- close: 平仓(实盘/模拟)\n- clean: 清空所有记录", enumValues = listOf("buy", "sell", "list", "pnl", "paper_buy", "paper_sell", "paper_list", "paper_pnl", "close", "clean")),
        ToolParameter("symbol", ParameterType.STRING, "股票代码，如 sh600000", required = false),
        ToolParameter("price", ParameterType.NUMBER, "成交价格", required = false),
        ToolParameter("shares", ParameterType.NUMBER, "成交股数(或份数)", required = false),
        ToolParameter("note", ParameterType.STRING, "备注(可选)", required = false),
        ToolParameter("paper", ParameterType.BOOLEAN, "close操作时: true=平模拟仓, false=平实盘仓(默认)", required = false)
    )

    private data class TradeRecord(
        val id: Long, val symbol: String, val name: String, val direction: String,
        val price: Double, val shares: Int, val amount: Double, val date: String,
        val note: String? = null, val paper: Boolean = false
    )

    private data class Position(
        val symbol: String, val name: String, val totalShares: Int,
        val avgCost: Double, val heldCost: Double
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = (args["action"] as? String)?.trim()?.lowercase() ?: "list"
        return try {
            when (action) {
                "buy" -> doTrade(args, false, "buy")
                "sell" -> doTrade(args, false, "sell")
                "list" -> doList(false)
                "pnl" -> doPnl(false)
                "paper_buy" -> doTrade(args, true, "buy")
                "paper_sell" -> doTrade(args, true, "sell")
                "paper_list" -> doList(true)
                "paper_pnl" -> doPnl(true)
                "close" -> doClose(args)
                "clean" -> doClean()
                else -> "未知操作: $action"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "操作失败: ${e.message ?: "未知错误"}" }
    }

    private fun doTrade(args: Map<String, Any>, paper: Boolean, direction: String): String {
        val raw = (args["symbol"] as? String)?.trim() ?: return "错误：未指定股票代码"
        val price = (args["price"] as? Double) ?: return "错误：未指定价格"
        val shares = ((args["shares"] as? Double)?.toInt()) ?: return "错误：未指定股数"
        val note = (args["note"] as? String)?.trim()
        if (shares <= 0 || price <= 0) return "错误：股数和价格必须为正数"

        val sym = normalizeSymbol(raw)
        val name = fetchStockName(sym)
        val amount = price * shares
        val date = nowString().take(19)
        val trades = loadTrades(paper)
        val nextId = (trades.maxOfOrNull { it.id } ?: 0) + 1
        val record = TradeRecord(nextId, sym.display, name, direction, price, shares, amount, date, note, paper)
        trades.add(record)
        saveTrades(trades, paper)

        val label = if (paper) "模拟" else "实盘"
        return buildString {
            appendLine("✅ ${label}${if (direction == "buy") "买入" else "卖出"}成功")
            appendLine("  ${name}(${sym.display})")
            appendLine("  价格: ${fmtPrice(price)} × $shares 股 = ${fmtAmt(amount)}")
            if (note?.isNotEmpty() == true) appendLine("  备注: $note")
            appendLine("  时间: $date")
            appendLine("  ID: #$nextId")
            if (direction == "sell") {
                val allBuys = trades.filter { it.direction == "buy" && it.symbol == sym.display && it.paper == paper }
                val cost = calcCostBasis(allBuys)
                val profit = (price - cost) * shares
                appendLine("  估算盈亏: ${if (profit >= 0) "🟢" else "🔴"} ${fmtPrice(profit)} (${String.format("%+.2f", if (cost > 0) (price - cost) / cost * 100 else 0.0)}%)")
            }
            appendLine()
            appendLine("💡 用 action=list 查看当前持仓，action=pnl 查看盈亏汇总")
        }.trimEnd()
    }

    private fun doList(paper: Boolean): String {
        val trades = loadTrades(paper)
        val label = if (paper) "模拟" else "实盘"
        val buys = trades.filter { it.direction == "buy" }
        val positions = mutableListOf<Position>()

        for ((sym, group) in buys.groupBy { it.symbol }) {
            val totalSold = trades.filter { it.direction == "sell" && it.symbol == sym }.sumOf { it.shares }
            val totalShares = group.sumOf { it.shares } - totalSold
            if (totalShares <= 0) continue
            val totalCost = group.sumOf { it.amount }
            val avgCost = totalCost / group.sumOf { it.shares }
            val heldCost = avgCost * totalShares
            positions.add(Position(sym, group.first().name, totalShares, avgCost, heldCost))
        }

        if (positions.isEmpty()) return "${label}持仓为空\n💡 用 action=buy 记录买入，或查看最近交易: paper_list"

        val totalInvested = positions.sumOf { it.heldCost }
        val currentPrices = fetchMultiPrices(positions.map { it.symbol })

        return buildString {
            appendLine("📋 ${label}持仓 (${positions.size}只)")
            appendLine()
            appendLine("名称${" ".repeat(6)} 代码      持股    成本价   现价    盈亏率     盈亏额")
            var totalPnl = 0.0
            for (p in positions.sortedByDescending { it.heldCost }) {
                val curPrice = currentPrices[p.symbol] ?: p.avgCost
                val marketValue = curPrice * p.totalShares
                val pnlAmt = marketValue - p.heldCost
                val pnlPct = if (p.heldCost > 0) (marketValue - p.heldCost) / p.heldCost * 100 else 0.0
                totalPnl += pnlAmt
                val arrow = if (pnlPct >= 0) "🟢" else "🔴"
                appendLine("$arrow ${p.name.padEnd(8)} ${p.symbol.padEnd(8)} ${p.totalShares.toString().padEnd(6)} ${fmtPrice(p.avgCost)}  ${fmtPrice(curPrice)}  ${String.format("%+.2f", pnlPct).padEnd(7)}%  ${fmtPrice(pnlAmt)}")
            }
            appendLine()
            appendLine("持仓成本: ${fmtAmt(totalInvested)}")
            appendLine("持仓盈亏: ${if (totalPnl >= 0) "🟢" else "🔴"} ${fmtPrice(totalPnl)}")
            val pnlPct = if (totalInvested > 0) totalPnl / totalInvested * 100 else 0.0
            appendLine("收益率: ${String.format("%+.2f", pnlPct)}%")
            if (!paper) {
                val wins = trades.filter { it.direction == "sell" }.count { sell ->
                    val buyCost = calcCostBasis(trades.filter { it.direction == "buy" && it.symbol == sell.symbol && it.id < sell.id })
                    sell.price > buyCost
                }
                if (trades.size > 0) appendLine("总交易次数: ${trades.size}")
            }
        }.trimEnd()
    }

    private fun doPnl(paper: Boolean): String {
        val allTrades = loadTrades(paper).sortedByDescending { it.id }
        val label = if (paper) "模拟" else "实盘"
        val buys = allTrades.filter { it.direction == "buy" }
        val sells = allTrades.filter { it.direction == "sell" }

        var realizedPnl = 0.0; var totalInvested = 0.0; var totalProceeds = 0.0
        val closedTrades = mutableListOf<Triple<String, String, Double>>()

        for (sell in sells) {
            val buyCost = calcCostBasis(buys.filter { it.symbol == sell.symbol && it.id < sell.id })
            val profit = (sell.price - buyCost) * sell.shares
            realizedPnl += profit
            totalProceeds += sell.amount
            closedTrades.add(Triple(sell.symbol, sell.name, profit))
        }

        totalInvested = buys.sumOf { it.amount }
        val currentPrices = fetchMultiPrices(buys.map { it.symbol }.distinct())

        var unrealizedPnl = 0.0
        val unrealizedDetails = mutableListOf<Triple<String, String, Double>>()
        for ((sym, group) in buys.groupBy { it.symbol }) {
            val symSells = sells.filter { it.symbol == sym }
            val heldShares = group.sumOf { it.shares } - symSells.sumOf { it.shares }
            if (heldShares <= 0) continue
            val avgCost = calcCostBasis(group)
            val curPrice = currentPrices[sym] ?: avgCost
            val pnl = (curPrice - avgCost) * heldShares
            unrealizedPnl += pnl
            if (abs(pnl) > 0.01) unrealizedDetails.add(Triple(sym, group.first().name, pnl))
        }

        return buildString {
            appendLine("💰 ${label}盈亏报告")
            appendLine()
            appendLine("【汇总】")
            appendLine("  总投入: ${fmtAmt(totalInvested)}")
            appendLine("  总回收: ${fmtAmt(totalProceeds)}")
            appendLine("  已实现盈亏: ${if (realizedPnl >= 0) "🟢" else "🔴"} ${fmtPrice(realizedPnl)}")
            appendLine("  浮动盈亏: ${if (unrealizedPnl >= 0) "🟢" else "🔴"} ${fmtPrice(unrealizedPnl)}")
            val totalPnl = realizedPnl + unrealizedPnl
            val totalPct = if (totalInvested > 0) totalPnl / totalInvested * 100 else 0.0
            appendLine("  总盈亏: ${if (totalPnl >= 0) "🟢" else "🔴"} ${fmtPrice(totalPnl)} (${String.format("%+.2f", totalPct)}%)")
            if (closedTrades.size >= 3) {
                val rets = closedTrades.map { it.third / (totalInvested / closedTrades.size) * 100 }
                val avgR = rets.average(); val varR = rets.map { (it - avgR) * (it - avgR) }.average()
                val stdR = sqrt(varR); val downVar = rets.filter { it < 0 }.map { it * it }.average()
                val sharpe = if (stdR > 0) avgR / stdR * sqrt(252.0) else 0.0
                val sortino = if (downVar > 0) avgR / sqrt(downVar) * sqrt(252.0) else 0.0
                appendLine("  夏普比率: ${String.format("%.2f", sharpe)} ${if (sharpe >= 1) "⭐" else if (sharpe >= 0.5) "✓" else ""}")
                appendLine("  Sortino: ${String.format("%.2f", sortino)} ${if (sortino >= 1.5) "⭐" else if (sortino >= 0.75) "✓" else ""}")
            }
            if (unrealizedDetails.isNotEmpty()) {
                appendLine()
                appendLine("【持仓明细】")
                unrealizedDetails.forEach { (sym, name, pnl) ->
                    appendLine("  ${if (pnl >= 0) "🟢" else "🔴"} $name($sym) ${String.format("%+.2f", pnl)}")
                }
            }
            if (closedTrades.isNotEmpty()) {
                appendLine()
                appendLine("【已平仓交易 (${closedTrades.size}笔)】")
                closedTrades.takeLast(10).reversed().forEach { (sym, name, pnl) ->
                    appendLine("  ${if (pnl >= 0) "🟢" else "🔴"} $name($sym) ${String.format("%+.2f", pnl)}")
                }
                if (closedTrades.size > 10) appendLine("  ...还有${closedTrades.size - 10}笔")
            }
            appendLine()
            appendLine("💡 用 action=list 查看当前持仓明细")
        }.trimEnd()
    }

    private fun doClose(args: Map<String, Any>): String {
        val raw = (args["symbol"] as? String)?.trim() ?: return "错误：未指定股票代码"
        val sym = normalizeSymbol(raw)
        val isPaper = (args["paper"] as? Boolean) ?: false
        val trades = loadTrades(isPaper)
        val remaining = trades.filter { it.symbol != sym.display }
        val removed = trades.size - remaining.size
        saveTrades(remaining.toMutableList(), isPaper)
        val label = if (isPaper) "模拟" else "实盘"
        return "✅ ${label}已平仓 ${sym.display}，移除了 $removed 条记录"
    }

    private fun doClean(): String {
        saveTrades(mutableListOf(), false)
        saveTrades(mutableListOf(), true)
        return "✅ 已清空所有实盘和模拟交易记录"
    }

    // ═══════════════════════════════════════════════════════
    //  数据持久化
    // ═══════════════════════════════════════════════════════

    private fun dataDir(): File? {
        val ctx = AppContextHolder.context ?: return null
        val f = File(ctx.filesDir, "portfolio")
        if (!f.exists() && !f.mkdirs()) return null
        return f
    }

    private fun loadTrades(paper: Boolean): MutableList<TradeRecord> {
        val dir = dataDir() ?: return mutableListOf()
        val file = File(dir, if (paper) "paper_trades.json" else "trades.json")
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<TradeRecord>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableListOf()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveTrades(trades: List<TradeRecord>, paper: Boolean) {
        val dir = dataDir() ?: return
        val file = File(dir, if (paper) "paper_trades.json" else "trades.json")
        try {
            com.wy.openx.data.util.AtomicFileWriter.writeAtomically(file, gson.toJson(trades))
        } catch (_: Exception) { }
    }

    private fun calcCostBasis(buys: List<TradeRecord>): Double {
        if (buys.isEmpty()) return 0.0
        val totalCost = buys.sumOf { it.amount }
        val totalShares = buys.sumOf { it.shares }
        return if (totalShares > 0) totalCost / totalShares else 0.0
    }

    // ═══════════════════════════════════════════════════════
    //  数据获取
    // ═══════════════════════════════════════════════════════

    private data class NormalizedSymbol(val sina: String, val eastMoney: String, val display: String)

    private fun normalizeSymbol(raw: String): NormalizedSymbol {
        val s = raw.lowercase().trim()
        if (s.startsWith("sh") || s.startsWith("sz")) {
            val code = s.substring(2); val em = if (s.startsWith("sh")) "1.$code" else "0.$code"
            return NormalizedSymbol(s, em, s)
        }
        if (s.startsWith("gb_")) { val code = s.removePrefix("gb_").uppercase(); return NormalizedSymbol(s, "105.$code", s) }
        val prefixed = if (s.all { it.isDigit() }) { if (s.startsWith("6") || s.startsWith("9")) "sh$s" else "sz$s" } else "gb_$s"
        return normalizeSymbol(prefixed)
    }

    private fun fetchStockName(sym: NormalizedSymbol): String {
        val url = "https://push2.eastmoney.com/api/qt/stock/get?secid=${sym.eastMoney}&fields=f57,f58"
        return try {
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Referer", "https://quote.eastmoney.com").build()
            val resp = httpClient().newCall(req).execute()
            val body = resp.body?.string(); resp.close()
            if (body != null) {
                val obj = try { com.google.gson.JsonParser.parseString(body)?.asJsonObject } catch (_: Exception) { null }
                obj?.getAsJsonObject("data")?.get("f58")?.takeIf { !it.isJsonNull }?.asString ?: sym.display.uppercase()
            } else sym.display.uppercase()
        } catch (_: Exception) { sym.display.uppercase() }
    }

    private fun fetchMultiPrices(symbols: List<String>): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        if (symbols.isEmpty()) return result
        for (sym in symbols.distinct().take(10)) {
            val nsym = normalizeSymbol(sym)
            try {
                val url = "https://push2.eastmoney.com/api/qt/stock/get?secid=${nsym.eastMoney}&fields=f43,f57"
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Referer", "https://quote.eastmoney.com").build()
                val resp = httpClient().newCall(req).execute()
                val body = resp.body?.string(); resp.close()
                if (body != null) {
                    val data = try { com.google.gson.JsonParser.parseString(body)?.asJsonObject?.getAsJsonObject("data") } catch (_: Exception) { null }
                    val price = data?.get("f43")?.takeIf { !it.isJsonNull }?.asDouble
                    if (price != null && price > 0) result[nsym.display] = price
                }
            } catch (_: Exception) { }
        }
        return result
    }

    private fun nowString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    private fun fmtPrice(v: Double): String = String.format("%.2f", v)
    private fun fmtAmt(v: Double): String = if (abs(v) >= 100000000) String.format("%.2f亿", v / 100000000) else if (abs(v) >= 10000) String.format("%.2f万", v / 10000) else String.format("%.2f", v)
}
