package com.wy.openx.tools

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import android.os.Environment
import android.util.Log

class StockTool : AgentTool {
    companion object { private const val TAG = "StockTool" }
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class KlineCache(val data: List<Kline>, val time: Long)
    // 多协程并发访问：统一使用并发安全容器
    private val klineCache = java.util.concurrent.ConcurrentHashMap<String, KlineCache>()
    private val cacheTtlMs = 60_000L
    private val requestTimestamps = java.util.Collections.synchronizedList(mutableListOf<Long>())

    // 电路断路器: 源失败计数 (sourceName -> consecutiveFailures)
    private val circuitBreaker = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val circuitBreakerThreshold = 3
    private val circuitBreakerResetMs = 30_000L
    private val circuitBreakerCooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun isSourceAvailable(source: String): Boolean {
        val cooldown = circuitBreakerCooldown[source] ?: return true
        if (System.currentTimeMillis() < cooldown) return false
        circuitBreaker.remove(source); circuitBreakerCooldown.remove(source)
        return true
    }

    private fun recordSourceFailure(source: String) {
        val count = (circuitBreaker[source] ?: 0) + 1
        circuitBreaker[source] = count
        if (count >= circuitBreakerThreshold) {
            circuitBreakerCooldown[source] = System.currentTimeMillis() + circuitBreakerResetMs
        }
    }

    private fun recordSourceSuccess(source: String) {
        circuitBreaker.remove(source)
    }

    private suspend fun rateLimit() {
        val now = System.currentTimeMillis()
        requestTimestamps.removeAll { now - it > 1000 }
        if (requestTimestamps.size >= 8) delay(120)
        requestTimestamps.add(System.currentTimeMillis())
    }

    override val name = "stock"
    override val description = "AI 量化交易全能工具。支持：实时行情、K线数据(日/周/月/分钟/多复权)、技术分析(均线/MACD/RSI/布林/KDJ/形态识别)、买卖信号(综合评分+多空判定)、基本面(PE/PB/EPS/估值分位)、综合分析报告(看涨/看空/观望)、市场扫描(多条件筛选)、市场情绪(涨跌比/连板/异动)、行业板块、资金流向、融资融券、龙虎榜、大宗交易、多股对比、本地记忆(learn/recall)、自选股跟踪(track)、K线图生成。"
    override val parameters = buildToolParameters(
        ToolParameter("action", ParameterType.STRING, "操作类型\n- quote: 实时行情\n- kline: K线数据(默认日线，支持多周期/复权)\n- analysis: 技术分析(均线/MACD/RSI/布林/支撑阻力/形态识别)\n- signal: 买卖信号(综合评分+多空判定)\n- report: 综合分析报告(技术面+资金面+基本面+信号→结论)\n- fundamental: 基本面(PE/PB/EPS/市值/52周)\n- screen: 市场扫描\n- market: 市场情绪(涨跌家数/涨停跌停/连板高度)\n- sector: 行业/概念板块涨跌榜\n- sector_stocks: 板块成分股(需 sector_code)\n- sector_search: 搜索板块代码(需 keyword)\n- flow: 个股资金流向/全市场资金流排行\n- compare: 多股对比(同时比较多只股票的关键指标)\n- margin: 融资融券数据\n- dragon: 龙虎榜数据\n- blocktrade: 大宗交易数据\n- search: 模糊搜索\n- learn: 保存分析笔记/经验到本地记忆\n- recall: 回顾历史分析与笔记\n- track: 自选股管理(add/remove/list)\n- chart: 生成K线图(返回图片路径)", enumValues = listOf("quote", "kline", "analysis", "signal", "report", "fundamental", "screen", "market", "sector", "sector_stocks", "sector_search", "flow", "compare", "margin", "dragon", "blocktrade", "search", "learn", "recall", "track", "chart")),
        ToolParameter("symbol", ParameterType.STRING, "股票代码。单股: sh600000 / sz000001 / aapl。多股(用逗号分隔): sh600000,sz000001,sz002415。", required = false),
        ToolParameter("period", ParameterType.STRING, "K线周期(仅 action=kline/chart): daily(日) / weekly(周) / monthly(月) / 60min / 30min / 15min / 5min / 1min", required = false),
        ToolParameter("count", ParameterType.INTEGER, "返回数量。kline默认30最大200, screen默认10最大50", required = false),
        ToolParameter("adjust", ParameterType.STRING, "复权方式(仅 action=kline/chart): forward(前复权) / backward(后复权) / none(不复权)", required = false),
        ToolParameter("criteria", ParameterType.STRING, "screen 模式的筛选条件: top_gainer(涨幅榜) / top_loser(跌幅榜) / top_volume(量比) / top_amount(成交额) / turnover(换手率) / oversold(超卖RSI<30) / breakout(52周新高) / limit_up(涨停股) / limit_down(跌停股) / consecutive_up(连板股) / macd_golden(MACD金叉) / volume_spike(放量>2倍)", required = false),
        ToolParameter("filters", ParameterType.STRING, "screen 高级复合筛选，格式: 条件1,条件2,... 如: '涨幅>3%,RSI<30,放量>1.5'", required = false),
        ToolParameter("sector_type", ParameterType.STRING, "sector 模式的板块类型: industry(行业板块) / concept(概念板块)", required = false),
        ToolParameter("flow_mode", ParameterType.STRING, "flow 模式: stock(个股资金流向,需symbol) / board(全市场资金流排行)", required = false),
        ToolParameter("symbols", ParameterType.STRING, "compare 模式: 用逗号分隔的多只股票代码，如 sh600000,sz000001,sz002415", required = false),
        ToolParameter("note", ParameterType.STRING, "learn 模式: AI 要保存的分析笔记/经验总结", required = false),
        ToolParameter("tags", ParameterType.STRING, "learn/recall 模式: 标签筛选（如 \"支撑位,趋势\"）", required = false),
        ToolParameter("track_action", ParameterType.STRING, "track 模式: add(添加自选) / remove(删除) / list(查看) / refresh(刷新价格)", required = false),
        ToolParameter("chart_width", ParameterType.INTEGER, "chart 模式: 图片宽度(px)", required = false),
        ToolParameter("chart_height", ParameterType.INTEGER, "chart 模式: 图片高度(px)", required = false),
        ToolParameter("sector_code", ParameterType.STRING, "sector_stocks 模式: 板块代码(如 917 半导体)", required = false),
        ToolParameter("keyword", ParameterType.STRING, "sector_search 模式: 搜索关键词(如 半导体)", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = (args["action"] as? String)?.trim()?.lowercase() ?: "quote"
        val rawSymbol = (args["symbol"] as? String)?.trim()
        return try {
            when (action) {
                "search" -> { val s = rawSymbol ?: return "错误：未指定搜索关键词"; searchStocks(s) }
                "kline" -> {
                    val s = rawSymbol ?: return "错误：未指定股票代码"
                    val p = (args["period"] as? String)?.trim()?.lowercase() ?: "daily"
                    val c = ((args["count"] as? Double)?.toInt()) ?: 30
                    val a = (args["adjust"] as? String)?.trim()?.lowercase() ?: "forward"
                    doKline(s, p, c.coerceIn(1, 200), a)
                }
                "analysis" -> { val s = rawSymbol ?: return "错误：未指定股票代码"; doAnalysis(s) }
                "fundamental" -> { val s = rawSymbol ?: return "错误：未指定股票代码"; doFundamental(s) }
                "signal" -> { val s = rawSymbol ?: return "错误：未指定股票代码"; doSignal(s) }
                "report" -> { val s = rawSymbol ?: return "错误：未指定股票代码"; doReport(s) }
                "screen" -> {
                    val cr = (args["criteria"] as? String)?.trim()?.lowercase() ?: "top_gainer"
                    val cn = ((args["count"] as? Double)?.toInt()) ?: 10
                    val fl = (args["filters"] as? String)?.trim()
                    if (fl != null) doScreenAdvanced(fl, cn.coerceIn(1, 50))
                    else doScreen(cr, cn.coerceIn(1, 50))
                }
                "market" -> doMarket()
                "sector" -> {
                    val st = (args["sector_type"] as? String)?.trim()?.lowercase() ?: "industry"
                    doSector(st, ((args["count"] as? Double)?.toInt())?.coerceIn(1, 30) ?: 20)
                }
                "sector_stocks" -> {
                    val sc = (args["sector_code"] as? String)?.trim() ?: ""
                    if (sc.isBlank()) return "错误：未指定板块代码(sector_code)。先用 action=sector_search&keyword=xxx 查板块代码"
                    doSectorStocks(sc)
                }
                "sector_search" -> {
                    val kw = (args["keyword"] as? String)?.trim() ?: rawSymbol
                    if (kw.isNullOrBlank()) return "错误：未指定搜索关键词"
                    doSectorSearch(kw)
                }
                "flow" -> {
                    val md = (args["flow_mode"] as? String)?.trim()?.lowercase() ?: "stock"
                    val ct = ((args["count"] as? Double)?.toInt())?.coerceIn(1, 30) ?: 15
                    if (md == "board") doFlowBoard(ct)
                    else { val s = rawSymbol ?: return "错误：未指定股票代码"; doFlow(normalizeSymbol(s)) }
                }
                "compare" -> {
                    val syms = (args["symbols"] as? String)?.trim() ?: rawSymbol
                    if (syms == null) return "错误：未指定股票代码(symbols)"
                    doCompare(syms)
                }
                "margin" -> { val s = rawSymbol ?: return "错误：未指定股票代码"; doMargin(s) }
                "dragon" -> { val s = rawSymbol ?: return "错误：未指定股票代码"; doDragonTiger(s) }
                "blocktrade" -> { val s = rawSymbol ?: return "错误：未指定股票代码"; doBlockTrade(s) }
                "chart" -> {
                    val s = rawSymbol ?: return "错误：未指定股票代码"
                    val p = (args["period"] as? String)?.trim()?.lowercase() ?: "daily"
                    val c = ((args["count"] as? Double)?.toInt()) ?: 60
                    val a = (args["adjust"] as? String)?.trim()?.lowercase() ?: "forward"
                    val w = ((args["chart_width"] as? Double)?.toInt()) ?: 800
                    val h = ((args["chart_height"] as? Double)?.toInt()) ?: 500
                    doChart(s, p, c.coerceIn(10, 200), a, w, h)
                }
                "learn" -> {
                    val s = rawSymbol ?: return "错误：未指定股票代码"
                    val n = (args["note"] as? String)?.trim() ?: return "错误：未指定笔记内容"
                    doLearn(s, n, (args["tags"] as? String)?.trim())
                }
                "recall" -> {
                    val s = rawSymbol ?: return "错误：未指定股票代码"
                    doRecall(s, ((args["count"] as? Double)?.toInt()) ?: 10, (args["tags"] as? String)?.trim())
                }
                "track" -> {
                    doTrack((args["track_action"] as? String)?.trim()?.lowercase() ?: "list", rawSymbol)
                }
                else -> { val s = rawSymbol ?: return "错误：未指定股票代码"; getQuotes(s) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "查询失败: ${e.message ?: "未知错误"}" }
    }

    // ═══════════════════════════════════════════════════════
    //  数据类
    // ═══════════════════════════════════════════════════════

    data class Kline(val date: String, val open: Double, val close: Double,
                     val high: Double, val low: Double, val volume: Double, val amount: Double?)
    private data class MACDResult(val dif: Double, val dea: Double, val bar: Double, val signal: String)
    private data class Bollinger(val upper: Double, val mid: Double, val lower: Double)
    private data class KDJResult(val k: Double?, val d: Double?, val j: Double?)
    private data class SignalInfo(val name: String, val direction: String, val description: String, val weight: Int)
    private data class NormalizedSymbol(val sina: String, val tencent: String, val eastMoney: String, val display: String)
    private data class StockQuote(val name: String, val symbol: String, val current: Double?,
                                  val prevClose: Double?, val open: Double?, val high: Double?,
                                  val low: Double?, val volume: Double?, val amount: Double?, val time: String?)
    data class TradePoint(val date: String, val direction: String, val price: Double)
    private data class ScreenItem(val name: String, val code: String, val price: Double?, val change: Double?,
                                   val vol: Double?, val amount: Double?, val high: Double?, val low: Double?,
                                   val open: Double?, val prevClose: Double?, val mcap: Double?,
                                   val turnoverRate: Double?, val yHigh: Double?, val yLow: Double?)
    private data class SectorItem(val name: String, val code: String, val change: Double?,
                                  val upCount: Int?, val downCount: Int?,
                                  val leaderName: String?, val leaderChange: Double?, val leaderCode: String?)

    // ═══════════════════════════════════════════════════════
    //  HTTP 异步 + Gson JSON
    // ═══════════════════════════════════════════════════════

    private suspend fun httpGet(url: String, referer: String? = null): String? = withContext(Dispatchers.IO) {
        val maxRetries = 1
        for (attempt in 0..maxRetries) {
            try {
                rateLimit()
                val rb = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                if (referer != null) rb.header("Referer", referer)
                val resp = client.newCall(rb.build()).execute()
                val body = resp.body?.string()
                resp.close()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "httpGet $url → HTTP ${resp.code}")
                    if (attempt < maxRetries) { delay(500L * (1 shl attempt)); continue } else return@withContext null
                }
                return@withContext body
            } catch (e: IOException) {
                if (attempt < maxRetries) delay(500L * (1 shl attempt))
            }
        }
        null
    }

    private fun parseJsonObj(json: String): JsonObject? =
        try { JsonParser.parseString(json)?.asJsonObject } catch (_: Exception) { null }

    private fun jsonObj(obj: JsonObject?, key: String): JsonObject? = obj?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun jsonArr(obj: JsonObject?, key: String) = obj?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun jsonStr(obj: JsonObject?, key: String): String? = obj?.get(key)?.takeIf { !it.isJsonNull }?.asString
    private fun jsonNum(obj: JsonObject?, key: String): Double? = obj?.get(key)?.takeIf { !it.isJsonNull }?.asDouble

    // ═══════════════════════════════════════════════════════
    //  搜索
    // ═══════════════════════════════════════════════════════

    private suspend fun searchStocks(keyword: String): String {
        val url = "https://searchadapter.eastmoney.com/api/suggest/get?input=${
            java.net.URLEncoder.encode(keyword, "UTF-8")
        }&count=10&type=14"
        val body = httpGet(url) ?: return "搜索无返回"
        val root = parseJsonObj(body) ?: return "搜索无返回"
        val results = mutableListOf<String>()
        val data = jsonObj(root, "QuotationCodeTable")
        val arr = data?.getAsJsonArray("Data") ?: jsonArr(root, "QuotationCodeTable") ?: return "未找到匹配「$keyword」"
        for (i in 0 until arr.size()) {
            val item = arr[i]?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val code = jsonStr(item, "Code") ?: continue; val name = jsonStr(item, "Name") ?: continue
            val typ = when (jsonStr(item, "CodeType")) { "1" -> "沪深"; "2" -> "指数"; "3" -> "港股"; "4" -> "美股"; "5" -> "板块"; else -> "" }
            results.add("  ${name.padEnd(8)} $code  $typ")
        }
        if (results.isEmpty()) return "未找到匹配「$keyword」"
        return buildString { appendLine("🔍 搜索「$keyword」:"); results.take(10).forEach { appendLine(it) } }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  行情
    // ═══════════════════════════════════════════════════════

    private suspend fun getQuotes(input: String): String {
        val symbols = input.split(",", "，", "、", " ").map { it.trim() }.filter { it.isNotBlank() }.take(10)
        if (symbols.isEmpty()) return "错误：未指定股票代码"
        val results = mutableListOf<String>()
        for (sym in symbols) results.add(getSingleQuote(sym))
        return results.joinToString("\n\n")
    }

    private suspend fun getSingleQuote(raw: String): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym)
        if (quote == null) return if (raw.any { it.isLetter() } && raw.none { it.isDigit() })
            "未找到「$raw」。试试 action=search 搜索代码。" else "未找到「$raw」，格式: sh600000 / sz000001 / aapl"
        return formatQuote(quote)
    }

    private suspend fun fetchQuote(sym: NormalizedSymbol): StockQuote? {
        suspend fun tryOne(name: String, fetcher: suspend () -> StockQuote?): StockQuote? {
            if (!isSourceAvailable(name)) return null
            val q = fetcher() ?: run { recordSourceFailure(name); return null }
            val check = sanityCheckQuote(q)
            if (check != null) { recordSourceFailure(name); return null }
            recordSourceSuccess(name); return q
        }
        return tryOne("sina") { fetchSina(sym) }
            ?: tryOne("tencent") { fetchTencent(sym) }
            ?: tryOne("eastmoney") { fetchEastMoney(sym) }
    }

    // ═══════════════════════════════════════════════════════
    //  K 线 (支持多周期/复权/多市场/故障切换)
    // ═══════════════════════════════════════════════════════

    private fun kltForPeriod(period: String): String = when (period) {
        "weekly", "week" -> "102"; "monthly", "month" -> "103"
        "60min" -> "60"; "30min" -> "30"; "15min" -> "15"; "5min" -> "5"; "1min" -> "1"
        else -> "101"
    }

    private fun fqtForAdjust(adjust: String): String = when (adjust) {
        "backward" -> "2"; "none" -> "0"; else -> "1"
    }

    private suspend fun fetchKline(sym: NormalizedSymbol, period: String = "daily", count: Int = 120, adjust: String = "forward"): List<Kline>? {
        val cacheKey = "${sym.eastMoney}_${kltForPeriod(period)}_${fqtForAdjust(adjust)}"
        val now = System.currentTimeMillis()
        klineCache[cacheKey]?.let { if (now - it.time < cacheTtlMs) return it.data }
        val data = fetchKlineEastMoney(sym, period, count, adjust) ?: fetchKlineSina(sym, period, count)
        if (data != null) klineCache[cacheKey] = KlineCache(data, now)
        return data
    }

    private suspend fun fetchKlineEastMoney(sym: NormalizedSymbol, period: String, count: Int, adjust: String): List<Kline>? {
        val klt = kltForPeriod(period); val fqt = fqtForAdjust(adjust)
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=${sym.eastMoney}" +
                "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57&klt=$klt&fqt=$fqt&end=20500101&lmt=$count"
        val body = httpGet(url, "https://quote.eastmoney.com") ?: return null
        val root = parseJsonObj(body) ?: return null
        val data = jsonObj(root, "data") ?: return null
        val klinesJson = jsonArr(data, "klines") ?: return null
        val result = mutableListOf<Kline>()
        for (i in 0 until klinesJson.size()) {
            val parts = klinesJson[i]?.asString?.split(",") ?: continue
            if (parts.size >= 6) {
                val open = parts[1].toDoubleOrNull() ?: 0.0; val close = parts[2].toDoubleOrNull() ?: 0.0
                val high = parts[3].toDoubleOrNull() ?: 0.0; val low = parts[4].toDoubleOrNull() ?: 0.0
                val vol = parts[5].toDoubleOrNull() ?: 0.0
                // 字段顺序校验: 东方财富K线是 [日期,开盘,收盘,最高,最低,成交量]
                val k = Kline(parts[0], open, close, high, low, vol, parts.getOrNull(6)?.toDoubleOrNull())
                if (high < low || close <= 0) continue
                result.add(k)
            }
        }
        return if (result.isEmpty()) null else result
    }

    private suspend fun fetchKlineSina(sym: NormalizedSymbol, period: String, count: Int): List<Kline>? {
        if (period != "daily") return null
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?symbol=${sym.sina}&scale=60&ma=no&datalen=$count"
        val body = httpGet(url) ?: return null
        val arr = try { JsonParser.parseString(body)?.asJsonArray } catch (_: Exception) { null } ?: return null
        val result = mutableListOf<Kline>()
        for (i in 0 until arr.size()) {
            val item = arr[i]?.asJsonObject ?: continue
            result.add(Kline(jsonStr(item, "day") ?: "", jsonNum(item, "open") ?: 0.0,
                jsonNum(item, "close") ?: 0.0, jsonNum(item, "high") ?: 0.0,
                jsonNum(item, "low") ?: 0.0, jsonNum(item, "volume") ?: 0.0, jsonNum(item, "m_amount")))
        }
        return if (result.isEmpty()) null else result
    }

    private suspend fun doKline(raw: String, period: String, count: Int, adjust: String): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym)
        val name = quote?.name ?: sym.display.uppercase()
        val kline = fetchKline(sym, period, count, adjust) ?: return "「${raw}」K线数据获取失败"
        val periodLabel = when (period) { "daily" -> "日"; "weekly" -> "周"; "monthly" -> "月"
            "60min" -> "60分"; "30min" -> "30分"; "15min" -> "15分"; "5min" -> "5分"; "1min" -> "1分"; else -> period }
        val adjustLabel = when (adjust) { "forward" -> "前复权"; "backward" -> "后复权"; else -> "不复权" }
        val data = kline.takeLast(count)
        return buildString {
            appendLine("📊 $name (${periodLabel}线 / $adjustLabel) 共${kline.size}条，显示后${data.size}条")
            appendLine()
            appendLine("日期        开盘     收盘     最高     最低     涨跌幅   成交量")
            for (k in data) {
                val changePct = if (k.close > 0) {
                    val prev = data.getOrNull(data.indexOf(k) - 1)?.close ?: k.open
                    if (prev > 0) ((k.close - prev) / prev * 100) else 0.0
                } else 0.0
                val arrow = if (changePct >= 0) "↑" else "↓"
                val date = if (k.date.length >= 10) k.date.take(10) else k.date
                appendLine("$date  ${fmtPrice(k.open)}  ${fmtPrice(k.close)}  ${fmtPrice(k.high)}  ${fmtPrice(k.low)}  $arrow${fmtPct(changePct)}  ${fmtVol(k.volume)}")
            }
            if (period == "daily") appendLine("\n💡 用 action=chart 生成K线图, action=analysis 做技术分析")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  技术分析 (含 K 线形态识别 + 量价分析 + 涨跌停检测)
    // ═══════════════════════════════════════════════════════

    private suspend fun doAnalysis(raw: String): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym) ?: return "未找到「$raw」"
        if (sym.sina.startsWith("gb_")) return "${quote.name} 基础分析（美股K线有限）:\n\n${formatQuote(quote)}"
        val kline = fetchKline(sym) ?: return "K线数据不足\n\n${formatQuote(quote)}"
        if (kline.size < 20) return "K线数据不足（至少20个交易日）\n\n${formatQuote(quote)}"

        val closes = kline.map { it.close }; val highs = kline.map { it.high }
        val lows = kline.map { it.low }; val volumes = kline.map { it.volume }
        val opens = kline.map { it.open }; val dates = kline.map { it.date }
        val last = closes.last()

        val ma5v = ma(closes, 5); val ma10v = ma(closes, 10); val ma20v = ma(closes, 20); val ma60v = ma(closes, 60)
        val rsiVal = rsi(closes, 14); val macdVal = macd(closes); val boll = bollinger(closes)
        val (sup, res) = supportResistance(highs, lows, closes); val kdjVal = kdj(highs, lows, closes)
        val patterns = detectCandlestickPatterns(opens, closes, highs, lows, volumes)
        val limitInfo = detectLimitUpDown(quote, kline); val volAnalysis = volumePriceAnalysis(closes, volumes)

        val maVals = listOf(ma5v.last(), ma10v.last(), ma20v.last())
        val maAlign = if (maVals == maVals.sortedDescending()) "多头排列 ↑"
        else if (maVals == maVals.sorted()) "空头排列 ↓" else "交叉缠绕"
        val bandPos = when { last >= boll.upper -> "触及上轨 ⚠"; last <= boll.lower -> "触及下轨 ⚠"
            last >= boll.mid -> "中轨上方"; else -> "中轨下方" }

        val result = buildString {
            appendLine("📊 ${quote.name} (${raw.uppercase()}) 技术分析  当前: ${fmtPrice(last)} (${fmtDate(dates.last())})\n")
            appendLine("【均线系统】MA5(${fmtPrice(ma5v.last())}) MA10(${fmtPrice(ma10v.last())}) MA20(${fmtPrice(ma20v.last())})${if (ma60v.isNotEmpty()) " MA60(${fmtPrice(ma60v.last())})" else ""}")
            appendLine("  形态: $maAlign")
            for ((n, v) in listOf("MA5" to ma5v.last(), "MA10" to ma10v.last(), "MA20" to ma20v.last())) appendLine("  价格在$n ${if (last >= v) "↑上方" else "↓下方"}")
            appendLine()
            appendLine("【MACD】DIF:${fmtPrice(macdVal.dif)} DEA:${fmtPrice(macdVal.dea)} BAR:${fmtPrice(macdVal.bar)} ${macdVal.signal}")
            appendLine()
            appendLine("【RSI(14)】${fmtPrice(rsiVal)} ${when { rsiVal > 70 -> "⚠超买"; rsiVal < 30 -> "⚠超卖"; rsiVal > 50 -> "偏强"; else -> "偏弱" }}")
            appendLine()
            appendLine("【KDJ】K:${fmtPrice(kdjVal.k)} D:${fmtPrice(kdjVal.d)} J:${fmtPrice(kdjVal.j)} ${if (kdjVal.k != null && kdjVal.d != null && kdjVal.k > kdjVal.d) "K上穿D偏多" else "K下穿D偏空"}")
            appendLine()
            appendLine("【布林带】上轨${fmtPrice(boll.upper)} 中轨${fmtPrice(boll.mid)} 下轨${fmtPrice(boll.lower)} 带宽${fmtPrice(boll.upper - boll.lower)} 价格$bandPos")
            appendLine()
            appendLine("【支撑/阻力】阻力:${res.joinToString("/"){fmtPrice(it)}} 支撑:${sup.joinToString("/"){fmtPrice(it)}}")
            appendLine()
            val avgVol20 = volumes.takeLast(20).average(); val latestVol = volumes.lastOrNull() ?: 0.0
            appendLine("【成交量】5日均量${fmtVol(volumes.takeLast(5).average())} 量比${String.format("%.2f", if (avgVol20 > 0) latestVol / avgVol20 else 0.0)}")
            if (volAnalysis != null) appendLine("  量价分析: $volAnalysis")
            if (limitInfo != null) appendLine("【涨跌停】$limitInfo")
            if (patterns.isNotEmpty()) { appendLine("\n【K线形态】"); patterns.forEach { appendLine("  $it") } }
            appendLine("\n【走势（近${min(closes.size,20)}日）】\n  ${sparkline(closes.takeLast(20))}")
            appendLine("  ${fmtPrice(closes.takeLast(20).minOrNull() ?: 0.0)} ${" ".repeat(15)} ${fmtPrice(closes.takeLast(20).maxOrNull() ?: 0.0)}")
            // 涨跌量不对称比
            val asym = calcAsymmetryRatio(kline)
            appendLine("\n【涨跌量不对称比(60日)】${String.format("%.2f", asym.ratio)} ${asym.verdict}")
            // 期望值
            val ev = calcExpectedValue(kline)
            appendLine("【期望值】${String.format("%+.2f", ev.ev)} (涨概率${String.format("%.0f", ev.upProb*100)}% 均涨${String.format("%+.2f", ev.avgUp)}% 均跌${String.format("%.2f", ev.avgDn)}%) ${ev.verdict}")
            // OBV
            val obv = calcOBV(kline)
            appendLine("【OBV】${obv.direction} ${obv.verdict}")
            // 数据可靠性校验
            val kw = sanityCheckKline(kline)
            if (kw.isNotEmpty()) appendLine("【数据校验】⚠ ${kw.joinToString("; ")}")
        }.trimEnd()
        saveMem(sym.display, MemEntry(nowString(), "analysis",
            "${quote.name} 价:${fmtPrice(last)} RSI:${fmtPrice(rsiVal)} $maAlign MACD:${if(macdVal.bar>0)"多头" else "空头"}", "技术分析"))
        return result
    }

    private data class PatternInfo(val name: String, val direction: String, val strength: Int)

    private fun detectCandlestickPatterns(opens: List<Double>, closes: List<Double>, highs: List<Double>, lows: List<Double>, volumes: List<Double>): List<String> {
        if (closes.size < 5) return emptyList()
        val patterns = mutableListOf<PatternInfo>(); val n = closes.size
        val body = abs(closes[n-1] - opens[n-1]); val us = highs[n-1] - max(closes[n-1], opens[n-1])
        val ls = min(closes[n-1], opens[n-1]) - lows[n-1]; val tr = highs[n-1] - lows[n-1]
        // 十字星
        if (tr > 0 && body < tr * 0.15 && us > body * 2 && ls > body * 2) patterns.add(PatternInfo("十字星", "转折", 2))
        // 锤子线/吊颈
        if (tr > 0 && ls >= body * 2 && us <= body * 0.5) patterns.add(PatternInfo(if (closes[n-1] > opens[n-1]) "锤子线" else "吊颈线", if (closes[n-1] > opens[n-1]) "看涨" else "看跌", 2))
        // 倒锤/流星
        if (tr > 0 && us >= body * 2 && ls <= body * 0.5) patterns.add(PatternInfo(if (closes[n-1] < opens[n-1]) "流星线" else "倒锤子", if (closes[n-1] < opens[n-1]) "看跌" else "看涨", 2))
        // 光头光脚
        if (tr > 0 && body > tr * 0.8) patterns.add(PatternInfo(if (closes[n-1] > opens[n-1]) "光头光脚大阳线" else "光头光脚大阴线", if (closes[n-1] > opens[n-1]) "看涨" else "看跌", 3))
        // 双根形态
        if (n >= 2) {
            val pB = closes[n-2] > opens[n-2]; val cB = closes[n-1] > opens[n-1]
            if (!pB && cB && closes[n-1] > opens[n-2] && opens[n-1] < closes[n-2]) patterns.add(PatternInfo("看涨吞没", "看涨", 3))
            if (pB && !cB && opens[n-1] > closes[n-2] && closes[n-1] < opens[n-2]) patterns.add(PatternInfo("看跌吞没", "看跌", 3))
            if (pB && !cB && opens[n-1] > highs[n-2] && closes[n-1] < closes[n-2]) patterns.add(PatternInfo("乌云压顶", "看跌", 2))
            if (!pB && cB && opens[n-1] < lows[n-2] && closes[n-1] > closes[n-2]) patterns.add(PatternInfo("曙光初现", "看涨", 2))
        }
        // 三根形态
        if (n >= 3) {
            val b1 = closes[n-3] > opens[n-3]; val b2 = closes[n-2] > opens[n-2]; val b3 = closes[n-1] > opens[n-1]
            if (b1 && b2 && b3) patterns.add(PatternInfo("红三兵", "看涨", 2))
            if (!b1 && !b2 && !b3) patterns.add(PatternInfo("三只乌鸦", "看跌", 2))
            if (!b1 && abs(closes[n-3]-opens[n-3]) > abs(closes[n-2]-opens[n-2])*2 && b3 && closes[n-1] > (closes[n-3]+opens[n-3])/2) patterns.add(PatternInfo("早晨之星", "看涨", 3))
            if (b1 && abs(closes[n-3]-opens[n-3]) > abs(closes[n-2]-opens[n-2])*2 && !b3 && closes[n-1] < (closes[n-3]+opens[n-3])/2) patterns.add(PatternInfo("黄昏之星", "看跌", 3))
        }
        patterns.sortByDescending { it.strength }
        return patterns.take(5).map { "${if (it.direction.contains("看涨")) "🟢" else "🔴"} ${it.name} (强度:${"★".repeat(it.strength)})" }
    }

    private fun detectLimitUpDown(quote: StockQuote, kline: List<Kline>): String? {
        if (kline.size < 2) return null
        val prevClose = kline[kline.size - 2].close; if (prevClose <= 0) return null
        val changePct = (kline.last().close - prevClose) / prevClose * 100
        val info = mutableListOf<String>()
        if (changePct >= 9.5) info.add("📈涨停(+${String.format("%.1f", changePct)}%)")
        if (changePct <= -9.5) info.add("📉跌停(${String.format("%.1f", changePct)}%)")
        val consecutive = kline.takeLast(5).zipWithNext { a, b -> if (a.close > 0) (b.close - a.close) / a.close * 100 else 0.0 }
        val upDays = consecutive.count { it >= 9.5 }; if (upDays >= 2) info.add("🔥${upDays + 1}连板")
        val downDays = consecutive.count { it <= -9.5 }; if (downDays >= 2) info.add("❄${downDays + 1}连板跌停")
        return if (info.isEmpty()) null else info.joinToString(" | ")
    }

    private fun volumePriceAnalysis(closes: List<Double>, volumes: List<Double>): String? {
        if (closes.size < 10) return null
        val avg20 = volumes.takeLast(20).average(); if (avg20 <= 0) return null
        val vr = volumes.last() / avg20; val pu = closes.last() > closes.getOrElse(closes.size - 2) { closes.last() }
        return when {
            vr >= 2.0 && pu -> "放量上涨(量比${String.format("%.1f", vr)})，多头强势"
            vr >= 2.0 && !pu -> "放量下跌(量比${String.format("%.1f", vr)})，抛压沉重"
            vr <= 0.5 && pu -> "缩量上涨，买盘不足，可能回调"
            vr <= 0.5 && !pu -> "缩量下跌，卖盘枯竭，可能企稳"
            vr >= 1.5 && pu -> "温和放量上涨，量价配合"
            vr >= 1.5 && !pu -> "放量下跌，注意风险"
            else -> null
        }
    }

    // ═══════════════════════════════════════════════════════
    //  基本面
    // ═══════════════════════════════════════════════════════

    private suspend fun doFundamental(raw: String): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym) ?: return "未找到「$raw」"
        if (sym.sina.startsWith("gb_")) return "美股基本面暂不支持\n\n${formatQuote(quote)}"
        val finJson = fetchFundamental(sym) ?: return "基本面数据获取失败\n\n${formatQuote(quote)}"
        val name = jsonStr(finJson, "f58") ?: quote.name
        val pe = jsonNum(finJson, "f100"); val pb = jsonNum(finJson, "f115")
        val turnoverRate = jsonNum(finJson, "f117"); val totalMcap = jsonNum(finJson, "f152")
        val circMcap = jsonNum(finJson, "f167"); val high52 = jsonNum(finJson, "f168"); val low52 = jsonNum(finJson, "f169")
        val eps = jsonNum(finJson, "f162"); val totalShares = jsonNum(finJson, "f163")
        val bvps = jsonNum(finJson, "f164"); val roe = jsonNum(finJson, "f185")

        val result = buildString {
            appendLine("📋 ${name} (${raw.uppercase()}) 基本面\n")
            appendLine("【估值】"); if (pe != null) appendLine("  PE(TTM): ${fmtPrice(pe)}${peEval(pe)}")
            if (pb != null) appendLine("  PB: ${fmtPrice(pb)}${if (pb < 2) " 偏低" else if (pb < 5) " 合理" else " 偏高"}")
            if (eps != null) appendLine("  EPS: ${fmtPrice(eps)}"); if (bvps != null) appendLine("  每股净资产: ${fmtPrice(bvps)}")
            if (roe != null) appendLine("  ROE: ${fmtPrice(roe)}%${if (roe > 15) " ⭐优秀" else if (roe > 8) " 良好" else " 一般"}")
            appendLine("\n【市值】")
            if (totalMcap != null) appendLine("  总市值: ${fmtBigAmt(totalMcap)}")
            if (circMcap != null) appendLine("  流通市值: ${fmtBigAmt(circMcap)}")
            if (totalShares != null) appendLine("  总股本: ${fmtVol(totalShares)}")
            if (turnoverRate != null) appendLine("  换手率: ${fmtPrice(turnoverRate)}%")
            appendLine("\n【区间】")
            if (low52 != null && high52 != null) {
                val pct = if (high52 != low52) ((quote.current ?: 0.0) - low52) / (high52 - low52) * 100 else 0.0
                appendLine("  52周: ${fmtPrice(low52)} ~ ${fmtPrice(high52)} 当前在${String.format("%.1f", pct)}%分位 ${if (pct < 25) "📉低位" else if (pct > 75) "📈高位" else "➖中位"}")
            }
        }.trimEnd()
        saveMem(sym.display, MemEntry(nowString(), "fundamental", "${name} PE:${if(pe!=null) fmtPrice(pe) else "N/A"}", "基本面"))
        return result
    }

    private fun peEval(pe: Double): String = when { pe < 0 -> " (亏损)"; pe < 15 -> " 偏低"; pe < 30 -> " 合理"; pe < 60 -> " 偏高"; else -> " ⚠极高" }

    private suspend fun fetchFundamental(sym: NormalizedSymbol): JsonObject? {
        // Source 1: East Money stock/get
        val fields = listOf("f57","f58","f100","f115","f117","f152","f162","f163","f164","f167","f168","f169","f185")
        for (attemptFields in listOf(fields, fields - "f185", listOf("f57","f58","f100","f115","f162","f152","f117"))) {
            val body = httpGet("https://push2.eastmoney.com/api/qt/stock/get?secid=${sym.eastMoney}&fields=${attemptFields.joinToString(",")}", "https://quote.eastmoney.com") ?: continue
            val d = parseJsonObj(body)?.let { jsonObj(it, "data") } ?: continue
            if (d.get("f57")?.isJsonNull == false) return d
        }
        // Source 2: Tencent qt.gtimg.cn as fallback
        return fetchFundamentalTencent(sym)
    }

    private suspend fun fetchFundamentalTencent(sym: NormalizedSymbol): JsonObject? {
        val body = httpGet("https://qt.gtimg.cn/q=${sym.tencent}") ?: return null
        val parts = extractQuoted(body)?.split("~") ?: return null
        if (parts.size < 47) return null
        try {
            val obj = com.google.gson.JsonObject()
            obj.addProperty("f57", sym.tencent)
            obj.addProperty("f58", parts.getOrElse(1) { "" })
            parts.getOrNull(39)?.toDoubleOrNull()?.let { obj.addProperty("f100", it) }
            parts.getOrNull(44)?.toDoubleOrNull()?.let { obj.addProperty("f152", it * 10000) }
            parts.getOrNull(45)?.toDoubleOrNull()?.let { obj.addProperty("f167", it * 10000) }
            parts.getOrNull(46)?.toDoubleOrNull()?.let { obj.addProperty("f117", it) }
            return obj
        } catch (_: Exception) { return null }
    }

    // ═══════════════════════════════════════════════════════
    //  买卖信号 (增强版)
    // ═══════════════════════════════════════════════════════

    private suspend fun doSignal(raw: String): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym) ?: return "未找到「$raw」"
        if (sym.sina.startsWith("gb_")) return "信号分析仅支持A股\n\n${formatQuote(quote)}"
        val kline = fetchKline(sym) ?: return "数据不足\n\n${formatQuote(quote)}"
        if (kline.size < 30) return "历史数据不足（至少30日）\n\n${formatQuote(quote)}"

        val closes = kline.map { it.close }; val highs = kline.map { it.high }; val lows = kline.map { it.low }
        val volumes = kline.map { it.volume }; val opens = kline.map { it.open }; val dates = kline.map { it.date }
        val last = closes.last(); val signals = mutableListOf<SignalInfo>(); var score = 0

        // MA
        val m5 = ma(closes,5); val m10 = ma(closes,10); val m20 = ma(closes,20)
        score += checkCross(m5, m10, "MA5", "MA10", signals)
        score += checkCross(m10, m20, "MA10", "MA20", signals)
        val c5=m5.last(); val c10=m10.last(); val c20=m20.last()
        if (listOf(c5,c10,c20) == listOf(c5,c10,c20).sortedDescending()) { signals.add(SignalInfo("多头排列","买入","MA5>MA10>MA20",2)); score+=2 }
        else if (listOf(c5,c10,c20) == listOf(c5,c10,c20).sorted()) { signals.add(SignalInfo("空头排列","卖出","MA5<MA10<MA20",-2)); score-=2 }

        // MACD
        val macdVal = macd(closes)
        if (closes.size >= 3) {
            val pd = macdOf(closes, closes.size-2); val pdea = smaOfDifs(closes, closes.size-2)
            if (pdea != null) {
                if (pd < pdea && macdVal.dif > macdVal.dea) { signals.add(SignalInfo("MACD金叉","买入","DIF上穿DEA",2)); score+=2 }
                if (pd > pdea && macdVal.dif < macdVal.dea) { signals.add(SignalInfo("MACD死叉","卖出","DIF下穿DEA",-2)); score-=2 }
            }
        }
        if (macdVal.signal.contains("多头")) score += 1; if (macdVal.signal.contains("空头")) score -= 1
        if (macdVal.bar > 0 && closes.size >= 2 && macdOf(closes, closes.size-2) <= 0) { signals.add(SignalInfo("MACD翻红","买入","绿转红",1)); score+=1 }
        if (macdVal.bar < 0 && closes.size >= 2 && macdOf(closes, closes.size-2) >= 0) { signals.add(SignalInfo("MACD翻绿","卖出","红转绿",-1)); score-=1 }

        // RSI
        val rsiVal = rsi(closes, 14)
        if (rsiVal < 30) { score+=2; signals.add(SignalInfo("RSI超卖","买入","RSI=$rsiVal",2)) }
        if (rsiVal > 70) { score-=2; signals.add(SignalInfo("RSI超买","卖出","RSI=$rsiVal",-2)) }
        if (rsiVal < 30 && closes.size >= 3) { val pr = rsi(closes.dropLast(1),14); val rm = closes.takeLast(5).minOrNull()?:last; if (last < closes[closes.size-2] && last <= rm && pr < rsiVal-5) { signals.add(SignalInfo("RSI底背离","买入","价新低RSI升",3)); score+=3 } }
        if (rsiVal > 70 && closes.size >= 3) { val pr = rsi(closes.dropLast(1),14); val rx = closes.takeLast(5).maxOrNull()?:last; if (last > closes[closes.size-2] && last >= rx && pr > rsiVal+5) { signals.add(SignalInfo("RSI顶背离","卖出","价新高RSI降",-3)); score-=3 } }

        // KDJ
        val kdjVal = kdj(highs, lows, closes)
        if (kdjVal.k != null && kdjVal.d != null) {
            if (closes.size >= 2) { val pk = kdj(highs.dropLast(1), lows.dropLast(1), closes.dropLast(1))
                if (pk.k != null && pk.d != null) { if (pk.k < pk.d && kdjVal.k > kdjVal.d) { signals.add(SignalInfo("KDJ金叉","买入", "K上穿D",2)); score+=2 }; if (pk.k > pk.d && kdjVal.k < kdjVal.d) { signals.add(SignalInfo("KDJ死叉","卖出","K下穿D",-2)); score-=2 } } }
            if (kdjVal.j != null && kdjVal.j < 0) { signals.add(SignalInfo("KDJ J<0超卖","买入","J值极低",1)); score+=1 }
            if (kdjVal.j != null && kdjVal.j > 100) { signals.add(SignalInfo("KDJ J>100超买","卖出","J值极高",-1)); score-=1 }
        }

        // Volume
        val avg20 = volumes.takeLast(20).average(); val avg5 = volumes.takeLast(5).average(); val lv = volumes.lastOrNull()?:0.0
        if (avg20 > 0 && lv > avg20*1.5 && last > closes.getOrElse(closes.size-2){last}) { signals.add(SignalInfo("放量上涨","买入","量>1.5倍+价涨",2)); score+=2 }
        if (avg20 > 0 && lv > avg20*1.5 && last < closes.getOrElse(closes.size-2){last}) { signals.add(SignalInfo("放量下跌","卖出","量>1.5倍+价跌",-2)); score-=2 }
        if (avg20 > 0 && avg5 < avg20*0.6) signals.add(SignalInfo("缩量整理","中性","量能萎缩",0))

        // 涨跌量不对称比
        val asym = calcAsymmetryRatio(kline, 60)
        if (asym.ratio > 1.5) { signals.add(SignalInfo("不对称比>1.5","卖出","强烈出货信号",-3)); score-=3 }
        else if (asym.ratio > 1.2) { signals.add(SignalInfo("不对称比>1.2","卖出","出货特征",-2)); score-=2 }
        else if (asym.ratio < 0.8) { signals.add(SignalInfo("不对称比<0.8","买入","买盘强势",2)); score+=2 }

        // 期望值
        val ev = calcExpectedValue(kline, 60)
        if (ev.ev > 0.5) { signals.add(SignalInfo("期望值>0.5","买入","赔率优秀",2)); score+=2 }
        else if (ev.ev > 0) { signals.add(SignalInfo("期望值>0","买入","赔率合理",1)); score+=1 }
        else if (ev.ev < -0.5) { signals.add(SignalInfo("期望值<-0.5","卖出","赔率不利",-2)); score-=2 }

        // OBV
        val obv = calcOBV(kline)
        if (obv.direction == "上行") { signals.add(SignalInfo("OBV上行","买入","资金净流入",2)); score+=2 }
        else if (obv.verdict.contains("背离")) { signals.add(SignalInfo("OBV背离","卖出","价涨OBV降",-3)); score-=3 }

        // Bollinger
        val boll = bollinger(closes); if (last <= boll.lower) { signals.add(SignalInfo("触布林下轨","买入","下轨支撑",1)); score+=1 }
        if (last >= boll.upper) { signals.add(SignalInfo("触布林上轨","卖出","上轨压力",-1)); score-=1 }

        // Support/Resistance
        val (sup, res) = supportResistance(highs, lows, closes)
        sup.maxOrNull()?.let { val d=(last-it)/last*100; if (d in 0.0..2.0) { signals.add(SignalInfo("接近支撑","买入","距支撑${String.format("%.1f",d)}%",1)); score+=1 } }
        res.minOrNull()?.let { val d=(it-last)/last*100; if (d in 0.0..2.0) { signals.add(SignalInfo("接近阻力","卖出","距阻力${String.format("%.1f",d)}%",-1)); score-=1 } }

        // Patterns
        detectCandlestickPatterns(opens, closes, highs, lows, volumes).forEach { p ->
            if (p.contains("看涨")) { score+=2; signals.add(SignalInfo("形态:"+p.substringAfter(") ").substringBefore(" (强度"),"买入","K线形态信号",2)) }
            if (p.contains("看跌")) { score-=2; signals.add(SignalInfo("形态:"+p.substringAfter(") ").substringBefore(" (强度"),"卖出","K线形态信号",-2)) }
        }
        // Limit
        detectLimitUpDown(quote, kline)?.let { l ->
            if (l.contains("涨停")||l.contains("连板")) { signals.add(SignalInfo("涨停/连板","买入","强势",2)); score+=2 }
            if (l.contains("跌停")) { signals.add(SignalInfo("跌停","卖出","风险",-2)); score-=2 }
        }

        val overall = when { score >= 7 -> "⭐⭐⭐强烈看涨"; score >= 4 -> "⭐⭐偏多"; score >= 1 -> "⭐略偏多"
            score <= -7 -> "⭐⭐⭐强烈看跌"; score <= -4 -> "⭐⭐偏空"; score <= -1 -> "⭐略偏空"; else -> "⚖中性" }
        signals.sortByDescending { abs(it.weight) }

        val result = buildString {
            appendLine("🎯 ${quote.name} (${raw.uppercase()}) 买卖信号")
            appendLine("  当前:${fmtPrice(last)} (${fmtDate(dates.last())}) 综合评分:${if(score>=0)"+" else ""}$score → $overall\n")
            if (signals.isNotEmpty()) {
                appendLine("【检测到${signals.size}个信号】")
                signals.take(12).forEach { s ->
                    val icon = when (s.direction) { "买入" -> "🟢"; "卖出" -> "🔴"; else -> "⚪" }
                    appendLine("  $icon ${s.name} (${s.direction} ${if(s.weight>=0)"+" else ""}${s.weight})")
                }
            }
        }.trimEnd()
        val sn = signals.take(3).joinToString(", "){it.name}
        saveMem(sym.display, MemEntry(nowString(),"signal","${quote.name} 评分:${score}→${overall} ${if(sn.isNotEmpty())"信号:$sn" else "无信号"}", "买卖信号"))
        return result
    }

    private fun checkCross(cur: List<Double>, prev: List<Double>, n1: String, n2: String, signals: MutableList<SignalInfo>): Int {
        if (cur.size < 2 || prev.size < 2) return 0
        val pc = cur[cur.size-2]; val cc = cur.last(); val pp = prev[prev.size-2]; val cp = prev.last()
        if (pc < pp && cc > cp) { signals.add(SignalInfo("${n1}金叉${n2}","买入","上穿",3)); return 3 }
        if (pc > pp && cc < cp) { signals.add(SignalInfo("${n1}死叉${n2}","卖出","下穿",-3)); return -3 }
        return 0
    }

    // ═══════════════════════════════════════════════════════
    //  综合分析报告 (report) — 一键看涨/看空/观望
    // ═══════════════════════════════════════════════════════

    private suspend fun doReport(raw: String): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym) ?: return "未找到「$raw」"
        if (sym.sina.startsWith("gb_")) return "综合分析报告仅支持A股\n\n${formatQuote(quote)}"
        val kline = fetchKline(sym) ?: return "数据不足\n\n${formatQuote(quote)}"
        if (kline.size < 20) return "数据不足（至少20日）\n\n${formatQuote(quote)}"
        val finJson = fetchFundamental(sym); val flowInfo = doFlowInternal(sym)

        val closes = kline.map { it.close }; val highs = kline.map { it.high }
        val lows = kline.map { it.low }; val volumes = kline.map { it.volume }; val last = closes.last()
        val m5 = ma(closes,5).last(); val m10 = ma(closes,10).last(); val m20 = ma(closes,20).last()
        val rsiVal = rsi(closes,14); val macdVal = macd(closes); val boll = bollinger(closes)
        val kdjVal = kdj(highs, lows, closes)

        var tScore = 50.0
        if (last > m5) tScore += 5; if (last > m10) tScore += 5; if (last > m20) tScore += 5
        if (listOf(m5,m10,m20).sortedDescending() == listOf(m5,m10,m20)) tScore += 10
        else if (listOf(m5,m10,m20).sorted() == listOf(m5,m10,m20)) tScore -= 10
        if (rsiVal in 40.0..60.0) tScore += 5; if (rsiVal in 30.0..40.0) tScore -= 5; if (rsiVal in 60.0..70.0) tScore += 3
        if (rsiVal < 30) tScore -= 5; if (rsiVal > 70) tScore -= 5
        if (macdVal.bar > 0) tScore += 8 else tScore -= 8
        if (kdjVal.k != null && kdjVal.d != null && kdjVal.k > kdjVal.d) tScore += 3 else tScore -= 3
        if (last > boll.mid) tScore += 4 else tScore -= 4
        val avg20 = volumes.takeLast(20).average(); val lv = volumes.lastOrNull() ?: 0.0
        if (avg20 > 0 && lv > avg20*1.5 && last > closes.getOrElse(closes.size-2){last}) tScore += 5
        if (avg20 > 0 && lv > avg20*1.5 && last <= closes.getOrElse(closes.size-2){last}) tScore -= 5
        tScore = tScore.coerceIn(0.0, 100.0)

        val pe = finJson?.let { jsonNum(it, "f100") }; val pb = finJson?.let { jsonNum(it, "f115") }
        var fScore = 50.0
        if (pe != null) { if (pe in 10.0..30.0) fScore += 15; if (pe > 60 || pe < 0) fScore -= 10 }
        if (pb != null) { if (pb < 3) fScore += 10; if (pb > 10) fScore -= 5 }

        var flScore = 50.0
        if (flowInfo != null) { if (flowInfo.contains("净流入")) flScore += 15; if (flowInfo.contains("净流出")) flScore -= 15 }

        val total = tScore * 0.45 + fScore * 0.25 + flScore * 0.3
        val verdict = when { total >= 70 -> "⭐⭐⭐ 看涨"; total >= 55 -> "⭐⭐ 偏多"; total <= 30 -> "⭐⭐⭐ 看跌"; total <= 45 -> "⭐⭐ 偏空"; else -> "⚖ 观望" }
        val suggestions = mutableListOf<String>()
        if (total >= 65) suggestions.add("可考虑逢低建仓或加仓")
        else if (total >= 55) suggestions.add("持仓观察，不追高")
        else if (total <= 35) suggestions.add("建议减仓或止损，等待企稳")
        else if (total <= 45) suggestions.add("观望为主，不轻易抄底")
        else suggestions.add("多看少动，等待明确信号")
        if (macdVal.bar > 0 && rsiVal < 70) suggestions.add("MACD多头+RSI未超买，多头趋势中")
        if (macdVal.bar < 0 && rsiVal > 30) suggestions.add("MACD空头+RSI未超卖，空头趋势中")
        if (pe != null && pe < 15 && total >= 55) suggestions.add("PE偏低+技术面偏多，具备安全边际")
        if (pe != null && pe > 50 && total < 45) suggestions.add("PE偏高+技术面偏空，注意回调风险")
        val (sup, res) = supportResistance(highs, lows, closes)
        if (sup.isNotEmpty()) suggestions.add("关键支撑:${fmtPrice(sup.first())}，跌破则止损")
        if (res.isNotEmpty()) suggestions.add("关键阻力:${fmtPrice(res.first())}，突破可加仓")

        return buildString {
            appendLine("📋 ${quote.name} (${raw.uppercase()}) 综合分析报告  当前:${fmtPrice(last)}\n")
            appendLine("╔════════════════════════════════╗")
            appendLine("║  综合评分:${String.format("%.1f", total)} → $verdict")
            appendLine("╚════════════════════════════════╝\n")
            appendLine("【维度评分】技术面${String.format("%.0f", tScore)}/100 ${techEval(tScore)}  基本面${String.format("%.0f", fScore)}/100 ${techEval(fScore)}  资金面${String.format("%.0f", flScore)}/100 ${techEval(flScore)}")
            appendLine("\n【关键指标】MA5:${fmtPrice(m5)} MA10:${fmtPrice(m10)} MA20:${fmtPrice(m20)}")
            appendLine("RSI:${fmtPrice(rsiVal)} MACD:${if(macdVal.bar>0)"多头" else "空头"} PE:${if(pe!=null) fmtPrice(pe) else "N/A"} PB:${if(pb!=null) fmtPrice(pb) else "N/A"}")
            if (flowInfo != null) appendLine("  $flowInfo")
            appendLine("\n【操作建议】"); suggestions.take(5).forEach { appendLine("  💡 $it") }
            appendLine("\n💡 深入: action=analysis / signal / fundamental / kline")
        }.trimEnd()
    }

    private fun techEval(s: Double): String = when { s >= 70 -> "✅良好"; s >= 55 -> "📊一般"; s <= 35 -> "⚠较差"; else -> "➖中性" }

    private suspend fun doFlowInternal(sym: NormalizedSymbol): String? {
        val body = httpGet("https://push2.eastmoney.com/api/qt/stock/get?secid=${sym.eastMoney}&fields=f62,f64,f84", "https://quote.eastmoney.com") ?: return null
        val d = parseJsonObj(body)?.let { jsonObj(it, "data") } ?: return null
        val mf = jsonNum(d, "f62") ?: return null
        return if (mf >= 0) "主力资金:净流入${fmtBigAmt(mf)}" else "主力资金:净流出${fmtBigAmt(mf)}"
    }

    private suspend fun doFlowTencent(sym: NormalizedSymbol): String? {
        val q = extractQuoted(httpGet("https://qt.gtimg.cn/q=${sym.tencent}") ?: return null)?.split("~") ?: return null
        if (q.size < 47) return null
        val name = q.getOrElse(1) { "" }
        val price = q.getOrElse(3) { "" }
        val volume = q.getOrElse(6) { "" }
        val amount = q.getOrElse(37) { "" }
        val turnover = q.getOrElse(38) { "" }
        return buildString {
            appendLine("💰 $name (${sym.display}) 资金流向\n")
            appendLine("【腾讯行情(备用)】")
            appendLine("  当前价: $price  成交量: ${if (volume.isNotBlank()) fmtVol(volume.toDoubleOrNull() ?: 0.0) else "N/A"}")
            if (amount.isNotBlank()) appendLine("  成交额: ${fmtAmt(amount.toDoubleOrNull() ?: 0.0)}")
            if (turnover.isNotBlank()) appendLine("  换手率: ${turnover}%")
            appendLine("\n💡 主力资金数据暂不可用，请稍后重试")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  市场扫描
    // ═══════════════════════════════════════════════════════

    private val screenHeaders = mapOf("涨幅榜" to "涨幅榜(↑)", "跌幅榜" to "跌幅榜(↓)", "量比榜" to "量比榜",
        "成交额榜" to "成交额榜", "换手率榜" to "换手率榜", "52周新高" to "52周新高", "涨停股" to "涨停股🔥",
        "跌停股" to "跌停股❄", "连板股" to "连板股🔥", "MACD金叉候选" to "MACD金叉候选", "放量股" to "放量股⚡")

    private suspend fun doScreen(criteria: String, count: Int): String {
        val (fid, po, title) = when (criteria) {
            "top_gainer" -> Triple("f3",1,"涨幅榜"); "top_loser" -> Triple("f3",0,"跌幅榜"); "top_volume" -> Triple("f5",1,"量比榜")
            "top_amount" -> Triple("f6",1,"成交额榜"); "turnover" -> Triple("f12",1,"换手率榜"); "breakout" -> Triple("f15",1,"52周新高")
            "oversold" -> Triple("f3",0,"跌幅榜(超卖)"); "limit_up" -> Triple("f3",1,"涨停股"); "limit_down" -> Triple("f3",0,"跌停股")
            "consecutive_up" -> Triple("f3",1,"连板股"); "volume_spike" -> Triple("f5",1,"放量股")
            else -> Triple("f3",1,"涨幅榜")
        }
        return screenFetch(fid, po, title, count)
    }

    private suspend fun screenFetch(fid: String, po: Int, title: String, count: Int): String {
        val url = "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=$count&po=$po&np=1&fltt=2&invt=2&fid=$fid&fs=m:0+t:6,m:1+t:2&fields=f2,f3,f4,f12,f14,f15,f16,f17,f18,f20,f37,f5,f6,f168,f169"
        val body = httpGet(url, "https://quote.eastmoney.com") ?: return "扫描无返回"
        val root = parseJsonObj(body) ?: return "数据格式异常"
        val diff = jsonArr(jsonObj(root, "data"), "diff") ?: return "扫描结果为空"
        val items = mutableListOf<ScreenItem>()
        for (i in 0 until diff.size()) {
            val item = diff[i]?.asJsonObject ?: continue
            items.add(ScreenItem(jsonStr(item,"f14")?:"" ,jsonStr(item,"f12")?:"" ,jsonNum(item,"f2"),jsonNum(item,"f3"),
                jsonNum(item,"f5"),jsonNum(item,"f6"),jsonNum(item,"f15"),jsonNum(item,"f16"),jsonNum(item,"f17"),
                jsonNum(item,"f18"),jsonNum(item,"f20"),jsonNum(item,"f37"),jsonNum(item,"f168"),jsonNum(item,"f169")))
        }
        return buildString {
            appendLine("📋 市场扫描 — ${screenHeaders[title] ?: title} (前${items.size})\n")
            appendLine("名称${" ".repeat(6)} 代码    当前    涨跌幅   成交额")
            items.forEach { it ->
                val a = if (it.change != null && it.change >= 0) "↑" else "↓"
                appendLine("${it.name.padEnd(8)} ${it.code.padEnd(8)} ${fmtPrice(it.price).padEnd(7)} $a${fmtPct(it.change).padEnd(8)} ${fmtAmt(it.amount)}")
            }
            appendLine("\n💡 用 filters 参数做复合筛选, 如 filters=涨幅>3%,RSI<30")
        }.trimEnd()
    }

    private suspend fun doScreenAdvanced(filters: String, count: Int): String {
        val conditions = filters.split(",", "，").map{it.trim()}.filter{it.isNotBlank()}
        val url = "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:1+t:2&fields=f2,f3,f4,f12,f14,f20,f37,f5,f6"
        val body = httpGet(url, "https://quote.eastmoney.com") ?: return "扫描无返回"
        val diff = jsonArr(jsonObj(parseJsonObj(body), "data"), "diff") ?: return "扫描结果为空"
        val matched = mutableListOf<String>()
        for (i in 0 until diff.size()) {
            val item = diff[i]?.asJsonObject ?: continue
            val name = jsonStr(item,"f14")?:""; val code = jsonStr(item,"f12")?:""; val price = jsonNum(item,"f2")?:0.0
            val change = jsonNum(item,"f3")?:0.0; val mcap = jsonNum(item,"f20")?:0.0; val vol = jsonNum(item,"f5")?:0.0
            val amount = jsonNum(item,"f6")?:0.0; val turn = jsonNum(item,"f37")?:0.0
            var pass = true
            for (c in conditions) {
                when {
                    c.startsWith("涨幅>") -> { val v = c.removePrefix("涨幅>").toDoubleOrNull()?:continue; if (change <= v) pass = false }
                    c.startsWith("涨幅<") -> { val v = c.removePrefix("涨幅<").toDoubleOrNull()?:continue; if (change >= v) pass = false }
                    c.startsWith("涨幅>=") -> { val v = c.removePrefix("涨幅>=").toDoubleOrNull()?:continue; if (change < v) pass = false }
                    c.startsWith("放量>") -> { val v = c.removePrefix("放量>").toDoubleOrNull()?:continue; if (vol <= v) pass = false }
                    c.startsWith("股价<") -> { val v = c.removePrefix("股价<").toDoubleOrNull()?:continue; if (price >= v) pass = false }
                    c.startsWith("股价>") -> { val v = c.removePrefix("股价>").toDoubleOrNull()?:continue; if (price <= v) pass = false }
                    c == "涨停" -> { if (change < 9.5) pass = false }; c == "跌停" -> { if (change > -9.5) pass = false }
                }
            }
            if (pass) matched.add("${name.padEnd(8)} $code  ${fmtPrice(price).padEnd(7)} ${if(change>=0)"↑" else "↓"}${fmtPct(change).padEnd(8)} 量${String.format("%.1f",vol)} 换手${fmtPrice(turn)}%")
            if (matched.size >= count) break
        }
        if (matched.isEmpty()) return "未找到符合条件的股票（条件: $filters）"
        return buildString {
            appendLine("📋 复合筛选结果（条件: $filters 共${matched.size}只）\n")
            appendLine("名称${" ".repeat(6)} 代码    当前    涨跌幅   其他")
            matched.forEach { appendLine(it) }
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  市场情绪 (market)
    // ═══════════════════════════════════════════════════════

    private suspend fun doMarket(): String {
        var body = httpGet("https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=2000&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:1+t:2&fields=f2,f3,f12,f14", "https://quote.eastmoney.com")
        if (body == null) body = httpGet("https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=500&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:1+t:2&fields=f2,f3,f12,f14", "https://quote.eastmoney.com")
        if (body == null) body = httpGet("https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=200&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:1+t:2&fields=f2,f3,f12,f14", "https://quote.eastmoney.com")
        if (body != null) {
            val diff = jsonArr(jsonObj(parseJsonObj(body), "data"), "diff") ?: return "市场数据为空"
            var up=0; var down=0; var flat=0; var limitUp=0; var limitDown=0
            for (i in 0 until diff.size()) {
                val item = diff[i]?.asJsonObject ?: continue; val chg = jsonNum(item,"f3")?:continue
                when { chg > 0.5 -> { up++; if (chg >= 9.5) limitUp++ }; chg < -0.5 -> { down++; if (chg <= -9.5) limitDown++ }; else -> flat++ }
            }
            val total = max(up+down+flat, 1)
            return buildString {
                appendLine("📊 市场情绪全景\n")
                appendLine("【涨跌家数】🟢上涨$up(${String.format("%.1f", up.toDouble()/total*100)}%)  🔴下跌$down(${String.format("%.1f", down.toDouble()/total*100)}%)  ⚪平盘$flat  涨跌比${String.format("%.2f", if(down>0) up.toDouble()/down else up.toDouble())}")
                appendLine("\n【极端行情】🔥涨停${limitUp}只  ❄跌停${limitDown}只")
                val sentiment = when { up.toDouble()/total*100 > 70 -> "市场情绪高涨 ⚠可能过热"; up.toDouble()/total*100 > 55 -> "市场偏多"
                    up.toDouble()/total*100 < 30 -> "市场低迷 ⚠可能超卖"; up.toDouble()/total*100 < 45 -> "市场偏空"; else -> "市场中性" }
                appendLine("\n【市场情绪】$sentiment")
                appendLine("\n💡 用 action=screen&criteria=consecutive_up 看连板股")
            }.trimEnd()
        }
        val tBody = httpGet("https://qt.gtimg.cn/q=sh000001,sz399001,sz399006,sh000016,sh000688") ?: return "市场数据获取失败(EM+TX均不可达)"
        val lines = tBody.split("\n").map { it.trim() }.filter { it.startsWith("v_") }
        var idxUp = 0; var idxDown = 0; var maxChg = 0.0; var minChg = 0.0
        for (line in lines) {
            val parts = line.split("~")
            if (parts.size < 5) continue
            val close = parts.getOrNull(3)?.toDoubleOrNull() ?: continue
            val preClose = parts.getOrNull(4)?.toDoubleOrNull() ?: continue
            if (preClose <= 0) continue
            val chg = (close - preClose) / preClose * 100
            if (chg > 0) idxUp++ else if (chg < 0) idxDown++
            if (chg > maxChg) maxChg = chg
            if (chg < minChg) minChg = chg
        }
        val sentiment = when { idxUp > idxDown * 2 -> "市场情绪高涨 ⚠可能过热"; idxUp > idxDown -> "市场偏多"
            idxDown > idxUp * 2 -> "市场低迷 ⚠可能超卖"; idxDown > idxUp -> "市场偏空"; else -> "市场中性" }
        return buildString {
            appendLine("📊 市场情绪全景(腾讯备用)\n")
            appendLine("【核心指数】${lines.size}只指数参与统计")
            appendLine("🟢上涨${idxUp}只  🔴下跌${idxDown}只  最大涨幅${String.format("%.2f", maxChg)}%  最大跌幅${String.format("%.2f", minChg)}%")
            appendLine("\n【市场情绪】$sentiment")
            appendLine("\n⚠ 数据来自腾讯备用源，仅供参考")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  行业板块
    // ═══════════════════════════════════════════════════════

    private suspend fun doSector(type: String, count: Int): String {
        val fs = if (type == "concept") "m:90+t:3" else "m:90+t:2"
        val title = if (type == "concept") "概念板块" else "行业板块"
        val body = httpGet("https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=$count&po=1&np=1&fltt=2&invt=2&fid=f3&fs=$fs&fields=f2,f3,f4,f12,f14,f104,f105,f128,f136,f140", "https://quote.eastmoney.com") ?: return "板块查询无返回"
        val rawItems = jsonArr(jsonObj(parseJsonObj(body), "data"), "diff") ?: return "板块数据为空"
        val items = mutableListOf<SectorItem>()
        for (i in 0 until rawItems.size()) {
            val item = rawItems[i]?.asJsonObject ?: continue
            items.add(SectorItem(jsonStr(item,"f14")?:"" ,jsonStr(item,"f12")?:"" ,jsonNum(item,"f3"),
                jsonNum(item,"f104")?.toInt(), jsonNum(item,"f105")?.toInt(),
                jsonStr(item,"f128"), jsonNum(item,"f136"), jsonStr(item,"f140")))
        }
        return buildString {
            appendLine("📋 板块扫描 — $title (涨跌幅排序 前${items.size})\n")
            appendLine("名称${" ".repeat(4)} 涨跌幅   涨跌比      领涨股")
            items.forEach { s ->
                val ar = if (s.change != null && s.change >= 0) "🟢" else "🔴"
                val ratio = if (s.upCount != null && s.downCount != null && s.upCount+s.downCount > 0) "${s.upCount}/${s.upCount+s.downCount}" else "N/A"
                val leader = if (s.leaderName != null && s.leaderChange != null) "${s.leaderName}(${fmtPct(s.leaderChange)})" else "-"
                appendLine("$ar ${s.name.padEnd(8)} ${fmtPct(s.change).padEnd(8)} $ratio  $leader")
            }
            appendLine("\n💡 用 action=sector&sector_type=concept 看概念板块")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  板块成分股 (datacenter.eastmoney.com)
    // ═══════════════════════════════════════════════════════

    private suspend fun doSectorSearch(keyword: String): String {
        val boards = fetchEastAllBoards()
        val matched = boards.filter { it["BOARD_NAME"]?.contains(keyword, ignoreCase = true) == true }
        if (matched.isEmpty()) return "未找到匹配「$keyword」的板块"
        return buildString {
            appendLine("🔍 板块搜索「$keyword」: 找到 ${matched.size} 个\n")
            appendLine("代码   名称")
            matched.take(30).forEach { b ->
                appendLine("  ${b["BOARD_CODE"]?.padEnd(5)}  ${b["BOARD_NAME"]}")
            }
            appendLine("\n💡 用 action=sector_stocks&sector_code=xxx 查看板块成分股")
        }.trimEnd()
    }

    private suspend fun doSectorStocks(boardCode: String): String {
        val stocks = fetchEastBoardStocks(boardCode)
        if (stocks.isEmpty()) return "板块「$boardCode」无数据或代码错误"

        // 尝试获取板块名称
        val boards = fetchEastAllBoards()
        val boardName = boards.firstOrNull { it["BOARD_CODE"] == boardCode }?.get("BOARD_NAME") ?: boardCode

        return buildString {
            appendLine("📦 $boardName ($boardCode) — ${stocks.size} 只成分股\n")
            appendLine("代码    名称")
            stocks.take(50).forEach { s ->
                appendLine("  ${s["SECURITY_CODE"]?.padEnd(6)}  ${s["SECUCODE"]}")
            }
            if (stocks.size > 50) appendLine("  ... 还有 ${stocks.size - 50} 只")
            appendLine("\n💡 用 action=sector_search&keyword=xxx 搜索其他板块")
        }.trimEnd()
    }

    private suspend fun fetchEastAllBoards(): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        for (page in 1..4) {
            val body = httpGet("https://datacenter.eastmoney.com/api/data/v1/get?" +
                    "reportName=RPT_BOARD_CONSTITUENT&columns=BOARD_CODE,BOARD_NAME" +
                    "&pageSize=500&pageNumber=$page&source=WEB&client=WEB",
                "https://data.eastmoney.com") ?: break
            val root = parseJsonObj(body)
            val data = root?.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: break
            var count = 0
            for (i in 0 until data.size()) {
                val item = data[i]?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val code = jsonStr(item, "BOARD_CODE") ?: continue
                val name = jsonStr(item, "BOARD_NAME") ?: continue
                if (result.none { it["BOARD_CODE"] == code }) {
                    result.add(mapOf("BOARD_CODE" to code, "BOARD_NAME" to name))
                }
                count++
            }
            if (count < 500) break
        }
        return result
    }

    private suspend fun fetchEastBoardStocks(boardCode: String): List<Map<String, String>> {
        val enc = java.net.URLEncoder.encode(boardCode, "UTF-8")
        val body = httpGet("https://datacenter.eastmoney.com/api/data/v1/get?" +
                "reportName=RPT_BOARD_CONSTITUENT&columns=BOARD_CODE,BOARD_NAME,SECURITY_CODE,SECUCODE" +
                "&pageSize=500&pageNumber=1&source=WEB&client=WEB&filter=(BOARD_CODE%3D%22$enc%22)",
            "https://data.eastmoney.com") ?: return emptyList()
        val root = parseJsonObj(body)
        val data = root?.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return (0 until data.size()).mapNotNull { i ->
            val item = data[i]?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            mapOf(
                "SECURITY_CODE" to (jsonStr(item, "SECURITY_CODE") ?: return@mapNotNull null),
                "SECUCODE" to (jsonStr(item, "SECUCODE") ?: ""),
                "BOARD_NAME" to (jsonStr(item, "BOARD_NAME") ?: "")
            )
        }
    }

    // ═══════════════════════════════════════════════════════
    //  资金流向
    // ═══════════════════════════════════════════════════════

    private suspend fun doFlow(sym: NormalizedSymbol): String {
        val quote = fetchQuote(sym) ?: return "未找到该股票"
        val fieldAttempts = listOf(
            listOf("f62","f64","f66","f69","f70","f71","f84","f85","f86"),
            listOf("f62","f64","f66","f69","f70","f71","f84"),
            listOf("f62","f64","f84")
        )
        var d: JsonObject? = null
        for (fields in fieldAttempts) {
            val body = httpGet("https://push2.eastmoney.com/api/qt/stock/get?secid=${sym.eastMoney}&fields=${fields.joinToString(",")}", "https://quote.eastmoney.com") ?: continue
            d = jsonObj(parseJsonObj(body), "data")
            if (d != null && d.get("f62")?.isJsonNull == false) break
        }
        if (d == null) {
            val tencentFlow = doFlowTencent(sym)
            if (tencentFlow != null) return tencentFlow
            return "资金流向获取失败"
        }
        val mf = jsonNum(d,"f62"); val rt = jsonNum(d,"f64"); val sl = jsonNum(d,"f66"); val lg = jsonNum(d,"f69")
        val md = jsonNum(d,"f70"); val sm = jsonNum(d,"f71"); val mr = jsonNum(d,"f84")
        return buildString {
            appendLine("💰 ${quote.name} (${sym.display}) 资金流向\n")
            if (mf != null) { val dir = if (mf >= 0) "🟢净流入" else "🔴净流出"; appendLine("【主力资金】$dir ${fmtBigAmt(mf)}${if(mr!=null)" (${fmtPrice(mr)}%)" else ""}") }
            if (rt != null) appendLine("【散户资金】${if(rt>=0)"净流入" else "净流出"} ${fmtBigAmt(rt)}")
            appendLine("\n【细分订单】")
            if (sl != null) appendLine("  超大单: ${fmtBigAmt(sl)} ${if(sl>=0)"↑" else "↓"}")
            if (lg != null) appendLine("  大单:   ${fmtBigAmt(lg)} ${if(lg>=0)"↑" else "↓"}")
            if (md != null) appendLine("  中单:   ${fmtBigAmt(md)} ${if(md>=0)"↑" else "↓"}")
            if (sm != null) appendLine("  小单:   ${fmtBigAmt(sm)} ${if(sm>=0)"↑" else "↓"}")
            if (mf != null && sl != null && lg != null) {
                val verdict = when { mf > 0 && sl > 0 && lg > 0 -> "主力全面买入，做多意愿强"; mf > 0 && sl > 0 -> "超大单主导买入，机构看多"
                    mf > 0 && lg > 0 -> "大单主导买入，游资看多"; (rt?:0.0) < 0 && mf < 0 -> "散户和主力均卖出，悲观"
                    (rt?:0.0) > 0 && mf < 0 -> "主力卖出散户接盘，警惕!"; (rt?:0.0) < 0 && mf > 0 -> "主力买入散户卖出，筹码集中"; else -> "资金博弈，观望" }
                appendLine("\n💡 $verdict")
            }
        }.trimEnd()
    }

    private suspend fun doFlowBoard(count: Int): String {
        val body = httpGet("https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=$count&po=1&np=1&fltt=2&invt=2&fid=f62&fs=m:0+t:6,m:1+t:2&fields=f2,f3,f4,f12,f14,f62,f64,f66,f69,f70,f71,f84", "https://quote.eastmoney.com") ?: return "资金排行获取失败"
        val rawItems = jsonArr(jsonObj(parseJsonObj(body), "data"), "diff") ?: return "结果为空"
        val lines = mutableListOf<String>()
        for (i in 0 until rawItems.size()) {
            val item = rawItems[i]?.asJsonObject ?: continue; val name = jsonStr(item,"f14")?:""; val code = jsonStr(item,"f12")?:""
            val chg = jsonNum(item,"f3"); val mf = jsonNum(item,"f62"); val mr = jsonNum(item,"f84")
            if (mf == null || mf == 0.0) continue; val a = if (chg != null && chg >= 0) "↑" else "↓"
            lines.add("${name.padEnd(8)} $a${fmtPct(chg).padEnd(8)} ${fmtBigAmt(mf).padEnd(10)} ${if(mr!=null)"${fmtPrice(mr)}%" else "-"}")
            if (lines.size >= count) break
        }
        return buildString {
            appendLine("💰 全市场主力资金净流入排行 (前${lines.size})\n")
            appendLine("名称${" ".repeat(4)} 涨跌幅   主力净流入    占比")
            lines.forEachIndexed { i, l -> appendLine("${i+1}. $l") }
            if (lines.isEmpty()) appendLine("无数据")
            appendLine("\n💡 主力净流入大=机构看好；净流出大=机构减仓")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  多股对比 (compare)
    // ═══════════════════════════════════════════════════════

    private suspend fun doCompare(input: String): String {
        val symbols = input.split(",","，","、"," ").map{it.trim()}.filter{it.isNotBlank()}.take(6)
        if (symbols.size < 2) return "请至少输入两只股票"
        val results = symbols.mapNotNull { s -> val n = normalizeSymbol(s); fetchQuote(n)?.let { Pair(s.uppercase(),it) } }
        if (results.size < 2) return "至少需要两只有效股票"
        return buildString {
            appendLine("📊 多股对比 (${results.size}只)\n")
            appendLine("指标${" ".repeat(8)} ${results.joinToString("  "){it.first.padEnd(10)}}")
            appendLine("─".repeat(20+results.size*14))
            appendLine("当前价     ${results.joinToString("  "){fmtPrice(it.second.current).padEnd(10)}}")
            val changes = results.map { (_,q) -> if(q.current!=null&&q.prevClose!=null&&q.prevClose!=0.0)(q.current-q.prevClose)/q.prevClose*100 else null }
            appendLine("涨跌幅     ${changes.joinToString("  "){if(it!=null)"${if(it>=0)"↑" else "↓"}${fmtPct(it).padEnd(10)}" else "N/A".padEnd(10)}}")
            appendLine("最高       ${results.joinToString("  "){fmtPrice(it.second.high).padEnd(10)}}")
            appendLine("最低       ${results.joinToString("  "){fmtPrice(it.second.low).padEnd(10)}}")
            appendLine("今开       ${results.joinToString("  "){fmtPrice(it.second.open).padEnd(10)}}")
            appendLine("昨收       ${results.joinToString("  "){fmtPrice(it.second.prevClose).padEnd(10)}}")
            appendLine("成交量     ${results.joinToString("  "){fmtVol(it.second.volume).padEnd(10)}}")
            appendLine("成交额     ${results.joinToString("  "){fmtAmt(it.second.amount).padEnd(10)}}")
            appendLine("\n💡 对个股深入分析: action=analysis&symbol=代码")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  融资融券 (margin) + 龙虎榜 (dragon) + 大宗交易 (blocktrade)
    // ═══════════════════════════════════════════════════════

    private suspend fun doMargin(raw: String): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym) ?: return "未找到「$raw」"
        val body = httpGet("https://push2.eastmoney.com/api/qt/stock/get?secid=${sym.eastMoney}&fields=f57,f58,f160,f161,f164,f165,f166", "https://quote.eastmoney.com") ?: return "融资融券数据获取失败"
        val d = jsonObj(parseJsonObj(body), "data") ?: return "数据异常"
        val mb = jsonNum(d,"f164"); val mBuy = jsonNum(d,"f165"); val sb = jsonNum(d,"f166"); val sv = jsonNum(d,"f160"); val sa = jsonNum(d,"f161")
        if (mb == null && mBuy == null && sb == null) return "${quote.name} 暂无融资融券数据（可能非两融标的）"
        return buildString {
            appendLine("💰 ${quote.name} (${raw.uppercase()}) 融资融券\n")
            if (mb != null) appendLine("【融资余额】${fmtBigAmt(mb)}"); if (mBuy != null) appendLine("【融资买入】${fmtBigAmt(mBuy)}")
            if (sb != null) appendLine("【融券余额】${fmtBigAmt(sb)}"); if (sv != null) appendLine("【融券卖出量】${fmtVol(sv)}")
            if (sa != null) appendLine("【融券卖出额】${fmtBigAmt(sa)}")
            if (mb != null && mBuy != null && mb > 0) { val r = mBuy / mb * 100; appendLine("\n融资买入占比:${String.format("%.2f",r)}% ${if(r>10)"融资活跃，杠杆看多" else "融资平稳"}") }
        }.trimEnd()
    }

    private suspend fun doDragonTiger(raw: String): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym) ?: return "未找到「$raw」"
        val url = "https://datacenter.eastmoney.com/securities/api/data/v1/get?reportName=RPT_DC_HISTORY_LHB&columns=SECUCODE,SECURITY_NAME_ABBR,TRADE_DATE,CHANGE_RATE,CLOSE_PRICE,NET_BUY_AMT_TOTAL&filter=(SECUCODE=%22${sym.display.uppercase()}%22)&pageSize=5&sortTypes=-1&sortColumns=TRADE_DATE"
        val body = httpGet(url) ?: return "${quote.name} 龙虎榜数据获取失败"
        val root = parseJsonObj(body); val result = root?.let { jsonObj(it, "result") }
        val arr = result?.let { jsonArr(it, "data") } ?: root?.let { jsonArr(it, "data") }
        return buildString {
            appendLine("🐉 ${quote.name} (${raw.uppercase()}) 龙虎榜\n")
            if (arr != null && arr.size() > 0) {
                for (i in 0 until arr.size()) { val it = arr[i]?.asJsonObject ?: continue
                    val date = jsonStr(it,"TRADE_DATE")?.take(10)?:""; val chg = jsonNum(it,"CHANGE_RATE"); val net = jsonNum(it,"NET_BUY_AMT_TOTAL")
                    appendLine("  $date 涨跌幅:${if(chg!=null) fmtPct(chg) else "N/A"}  净买入:${if(net!=null) fmtBigAmt(net) else "N/A"}") }
            } else appendLine("近5个交易日无龙虎榜数据")
            appendLine("\n💡 具体买卖席位请查看东方财富官网")
        }.trimEnd()
    }

    private suspend fun doBlockTrade(raw: String): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym) ?: return "未找到「$raw」"
        val body = httpGet("https://push2.eastmoney.com/api/qt/stock/blocktrade/get?secid=${sym.eastMoney}", "https://quote.eastmoney.com") ?: ""
        val data = parseJsonObj(body)?.let { jsonObj(it, "data") }
        return buildString {
            appendLine("📦 ${quote.name} (${raw.uppercase()}) 大宗交易\n")
            if (data != null) {
                val tv = jsonNum(data,"f188"); val ta = jsonNum(data,"f187"); val cnt = jsonNum(data,"f189")?.toInt()
                val ap = jsonNum(data,"f190"); val prem = jsonNum(data,"f191")
                if (tv != null && tv > 0) {
                    if (tv != null) appendLine("  总成交量:${fmtVol(tv)}"); if (ta != null) appendLine("  总成交额:${fmtBigAmt(ta)}")
                    if (cnt != null) appendLine("  成交笔数:$cnt"); if (ap != null) appendLine("  均价:${fmtPrice(ap)}")
                    if (prem != null) appendLine("  折溢价率:${fmtPct(prem)} ${if(prem>=0)"溢价，买方意愿强" else "折价，买方获安全垫"}")
                } else appendLine("近5个交易日无大宗交易")
            } else appendLine("大宗交易数据获取失败（或该股近期无大宗交易）")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  K 线图生成 (chart)
    // ═══════════════════════════════════════════════════════

    private suspend fun doChart(raw: String, period: String, count: Int, adjust: String, w: Int, h: Int): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym) ?: return "未找到「$raw」"
        val kline = fetchKline(sym, period, count, adjust) ?: return "K线数据不足"
        val path = renderKlineChart(quote.name, sym.display, kline.takeLast(count), w.coerceIn(300,2000), h.coerceIn(200,1500))
        return if (path != null) "✅ K线图已生成: $path\n💡 Android端可直接打开查看" else "K线图生成失败"
    }

    private fun renderKlineChart(name: String, symbol: String, data: List<Kline>, width: Int, height: Int, trades: List<TradePoint> = emptyList()): String? {
        val pl = 60f; val pr = 20f; val pt = 50f; val pb = 70f; val cw = width - pl - pr; val ch = height - pt - pb
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint().apply { color = Color.rgb(18,18,28); isAntiAlias = true }; canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),bg)
        val hi = data.maxOf{it.high}; val lo = data.minOf{it.low}; val rng = (hi - lo).coerceAtLeast(0.01); val n = data.size
        val cw2 = (cw.toFloat()/n).coerceAtMost(20f); val gap = if (cw2>6) 2f else 1f
        val upP = Paint().apply { color = Color.rgb(255,77,77); strokeWidth=1.5f; isAntiAlias=true }
        val dnP = Paint().apply { color = Color.rgb(0,200,83); strokeWidth=1.5f; isAntiAlias=true }
        val txP = Paint().apply { color = Color.rgb(200,200,200); textSize=24f; isAntiAlias=true }
        val axP = Paint().apply { color = Color.rgb(80,80,100); strokeWidth=1f }
        val m5P = Paint().apply { color = Color.rgb(255,193,7); strokeWidth=2f; isAntiAlias=true }
        val m10P = Paint().apply { color = Color.rgb(33,150,243); strokeWidth=2f; isAntiAlias=true }
        val m20P = Paint().apply { color = Color.rgb(156,39,176); strokeWidth=2f; isAntiAlias=true }
        val vUp = Paint().apply { color = Color.argb(120,255,77,77) }; val vDn = Paint().apply { color = Color.argb(120,0,200,83) }
        fun y(p: Double) = pt + ((hi-p)/rng*ch).toFloat()
        fun x(i: Int) = pl + (i.toFloat()/(n-1).coerceAtLeast(1))*cw
        // Grid
        for (i in 0..4) { val yy = pt+(ch/4)*i; canvas.drawLine(pl,yy,(width-pr),yy,axP); canvas.drawText(fmtPrice(hi-(rng/4)*i),4f,yy+8f,txP) }
        // Volume area
        val vTop = pt + ch*0.8f; val vH = ch*0.2f; val maxV = data.maxOf{it.volume}.coerceAtLeast(1.0)
        val m5 = ma(data.map{it.close},5); val m10 = ma(data.map{it.close},10); val m20 = ma(data.map{it.close},20)
        for (i in data.indices) {
            val k = data[i]; val xx = x(i); val w2 = (cw2-gap).coerceAtLeast(1f); val isUp = k.close >= k.open
            val p = if (isUp) upP else dnP; val vp = if (isUp) vUp else vDn
            val vh = (k.volume/maxV*vH).toFloat(); canvas.drawRect(xx-cw2/2,vTop+vH-vh,xx-cw2/2+w2,vTop+vH,vp)
            val t = minOf(y(k.open), y(k.close)); val b = maxOf(y(k.open), y(k.close)); val bh = (b-t).coerceAtLeast(1f)
            canvas.drawRect(xx-w2/2,t,xx+w2/2,t+bh,p); canvas.drawLine(xx,y(k.high),xx,y(k.low),p)
        }
        // Trade markers
        if (trades.isNotEmpty()) {
            val tradeByDate = trades.groupBy { it.date }
            val buyP = Paint().apply { color = Color.rgb(0,200,83); strokeWidth=2f; isAntiAlias=true }
            val sellP = Paint().apply { color = Color.rgb(255,77,77); strokeWidth=2f; isAntiAlias=true }
            val buyF = Paint().apply { color = Color.argb(180,0,200,83); style=Paint.Style.FILL; isAntiAlias=true }
            val sellF = Paint().apply { color = Color.argb(180,255,77,77); style=Paint.Style.FILL; isAntiAlias=true }
            for (i in data.indices) {
                val tp = tradeByDate[data[i].date.take(10)] ?: continue
                val xx = x(i); val halfW = (cw2/2).coerceAtLeast(12f)
                for (t in tp) {
                    val yy = y(t.price.coerceIn(data[i].low, data[i].high))
                    if (t.direction == "buy") {
                        val path = android.graphics.Path().apply {
                            moveTo(xx, yy - halfW); lineTo(xx - halfW, yy); lineTo(xx + halfW, yy); close()
                        }
                        canvas.drawPath(path, buyF); canvas.drawPath(path, buyP)
                    } else {
                        val path = android.graphics.Path().apply {
                            moveTo(xx, yy + halfW); lineTo(xx - halfW, yy); lineTo(xx + halfW, yy); close()
                        }
                        canvas.drawPath(path, sellF); canvas.drawPath(path, sellP)
                    }
                }
            }
        }
        fun drawMA(vals: List<Double>, p: Paint) { if (vals.size<2) return
            val off = n - vals.size; val pts = mutableListOf<Float>()
            for (i in vals.indices) { pts.add(x(i+off)); pts.add(y(vals[i])) }
            for (i in 0 until pts.size-2 step 2) canvas.drawLine(pts[i],pts[i+1],pts[i+2],pts[i+3],p) }
        drawMA(m5, m5P); drawMA(m10, m10P); drawMA(m20, m20P)
        val ti = Paint().apply { color=Color.WHITE; textSize=28f; isAntiAlias=true }; canvas.drawText("$name ($symbol)", pl, 32f, ti)
        val lm = Paint().apply { textSize=20f; isAntiAlias=true }; lm.color=Color.rgb(255,193,7); canvas.drawText("MA5", width-200f, 32f, lm)
        lm.color=Color.rgb(33,150,243); canvas.drawText("MA10", width-140f, 32f, lm); lm.color=Color.rgb(156,39,176); canvas.drawText("MA20", width-80f, 32f, lm)
        val step = (n/5).coerceAtLeast(1); for (i in 0 until n step step) { val d = if (data[i].date.length>=10) data[i].date.take(10).substring(5) else data[i].date; canvas.drawText(d, x(i)-16f, height-20f, txP) }
        return try { val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "stock_charts"); dir.mkdirs(); val f = File(dir, "${symbol}_${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }; f.absolutePath } catch (_: Exception) { null } finally { bmp.recycle() }
    }

    // ═══════════════════════════════════════════════════════
    //  本地记忆 + 自选股 (learn / recall / track)
    // ═══════════════════════════════════════════════════════

    private val memDir: File? get() = AppContextHolder.context?.filesDir?.let { File(it, "stock_memory").also { f -> f.mkdirs() } }
    private data class MemEntry(val date: String, val type: String, val content: String, val tags: String? = null)

    private fun loadMem(sym: String): MutableList<MemEntry> {
        val f = File(memDir ?: return mutableListOf(), "${sym.lowercase()}.json"); if (!f.exists()) return mutableListOf()
        return try { val arr = JsonParser.parseString(f.readText())?.asJsonArray ?: return mutableListOf()
            (0 until arr.size()).mapNotNull { i -> val o = arr[i]?.asJsonObject?:return@mapNotNull null
                MemEntry(jsonStr(o,"date")?:"" ,jsonStr(o,"type")?:"" ,jsonStr(o,"content")?:"" ,jsonStr(o,"tags")) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveMem(sym: String, entry: MemEntry) {
        val f = File(memDir ?: return, "${sym.lowercase()}.json")
        try { val entries = loadMem(sym).also{it.add(entry)}; if (entries.size>500) entries.drop(entries.size-500).toMutableList().also{entries.clear();entries.addAll(it)}
            val sb = StringBuilder("[\n"); entries.forEachIndexed { i, e -> sb.append("  {\n    \"date\":\"${e.date}\",\n    \"type\":\"${escapeJson(e.type)}\",\n    \"content\":\"${escapeJson(e.content)}\"${if(e.tags!=null)",\n    \"tags\":\"${escapeJson(e.tags)}\"" else ""}\n  }${if(i<entries.size-1) "," else ""}\n") }; sb.append("]\n"); com.wy.openx.data.util.AtomicFileWriter.writeAtomically(f, sb.toString())
        } catch (_: Exception) {}
    }

    private fun escapeJson(s: String) = s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")
    private fun nowString() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

    private suspend fun doLearn(raw: String, note: String, tags: String?): String {
        val sym = normalizeSymbol(raw); val quote = fetchQuote(sym); val name = quote?.name ?: sym.display
        saveMem(sym.display, MemEntry(nowString(),"note",note,tags))
        val pi = if (quote?.current != null) " (当前价:${fmtPrice(quote.current)})" else ""
        return "🧠 ${name}(${sym.display}) 学习笔记已保存$pi\n\n📝 $note${if(tags!=null) "\n\n🏷 $tags" else ""}\n\n💡 用 action=recall&symbol=${sym.display} 回顾"
    }

    private suspend fun doRecall(raw: String, limit: Int, tags: String?): String {
        val sym = normalizeSymbol(raw); val entries = loadMem(sym.display).reversed().take(limit.coerceIn(1,50))
        if (entries.isEmpty()) return "「${sym.display}」暂无本地记忆"
        val filtered = if (tags != null) { val ts = tags.split(",","，","、").map{it.trim().lowercase()}.toSet()
            entries.filter{it.tags?.lowercase()?.let{t->ts.any{kw->t.contains(kw)}}?:false} } else entries
        if (filtered.isEmpty()) return "未找到标签「$tags」相关记忆"
        return buildString {
            appendLine("🧠 ${sym.display} 本地记忆 (共${filtered.size}条)\n")
            filtered.forEachIndexed { idx, e ->
                val icon = when(e.type){"analysis"->"📊";"signal"->"🎯";"note"->"📝";else->"📌"}
                val d = if (e.content.length>200) e.content.take(200)+"…" else e.content
                appendLine("${idx+1}. $icon [${e.type}] ${e.date}\n   $d${if(e.tags!=null)"\n   🏷 ${e.tags}" else ""}\n") }
            appendLine("💡 用 action=learn 继续添加新笔记")
        }.trimEnd()
    }

    private data class TrackedStock(val symbol: String, val name: String, val added: String, val lastPrice: String? = null)

    private fun loadWatchlist(): MutableList<TrackedStock> {
        val f = File(memDir ?: return mutableListOf(), "watchlist.json"); if (!f.exists()) return mutableListOf()
        return try { val arr = JsonParser.parseString(f.readText())?.asJsonArray ?: return mutableListOf()
            (0 until arr.size()).mapNotNull { i -> val o = arr[i]?.asJsonObject?:return@mapNotNull null
                TrackedStock(jsonStr(o,"symbol")?:"" ,jsonStr(o,"name")?:"" ,jsonStr(o,"added")?:"" ,jsonStr(o,"lastPrice")) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveWatchlist(items: List<TrackedStock>) {
        val f = File(memDir ?: return, "watchlist.json")
        try { val sb = StringBuilder("[\n"); items.forEachIndexed { i, it -> sb.append("  {\n    \"symbol\":\"${it.symbol}\",\n    \"name\":\"${escapeJson(it.name)}\",\n    \"added\":\"${it.added}\",\n    \"lastPrice\":\"${it.lastPrice?:""}\"\n  }${if(i<items.size-1) "," else ""}\n") }; sb.append("]\n"); com.wy.openx.data.util.AtomicFileWriter.writeAtomically(f, sb.toString()) }
        catch (_: Exception) {}
    }

    private suspend fun doTrack(action: String, raw: String?): String {
        val list = loadWatchlist()
        when (action) {
            "add" -> { val sym = raw ?: return "错误：未指定股票代码"; val ns = normalizeSymbol(sym); val q = fetchQuote(ns); val n = q?.name?:ns.display
                if (list.any{it.symbol==ns.display}) return "「$n」已在自选股中"
                val p = if (q?.current!=null) fmtPrice(q.current) else ""; list.add(TrackedStock(ns.display,n,nowString().take(10),p))
                saveWatchlist(list); saveMem(ns.display, MemEntry(nowString(),"track","加入自选,价:$p","watchlist"))
                return "✅ ${n}(${ns.display}) 已加入自选股${if(p.isNotEmpty())" ($p)" else ""}" }
            "remove" -> { val sym = raw?:return "错误：未指定股票代码"; val ns = normalizeSymbol(sym); val r = list.filter{it.symbol!=ns.display}
                if (r.size==list.size) return "「${ns.display}」不在自选股中"; saveWatchlist(r); return "✅ ${ns.display} 已移除" }
            "refresh" -> {
                if (list.isEmpty()) return "自选股为空"
                val updated = list.map { t -> val ns = normalizeSymbol(t.symbol); val q = fetchQuote(ns); t.copy(name = q?.name ?: t.name, lastPrice = if (q?.current != null) fmtPrice(q.current) else t.lastPrice) }
                saveWatchlist(updated)
                return buildString { appendLine("📋 自选股 (${updated.size}只) — 价格已刷新\n"); appendLine("名称${" ".repeat(6)} 代码      当前价    添加日期")
                    updated.forEach { appendLine("  ${(it.name.ifEmpty { it.symbol }).padEnd(8)} ${it.symbol.padEnd(10)} ${it.lastPrice?.padEnd(8) ?: "N/A".padEnd(8)} ${it.added}") }
                    appendLine("\n💡 用 action=track&track_action=refresh 再次刷新") }.trimEnd() }
            else -> { if (list.isEmpty()) return "自选股为空\n用 action=track&track_action=add&symbol=sh600000 添加"
                val ref = list.map{t->val ns=normalizeSymbol(t.symbol);val q=fetchQuote(ns);t.copy(name=q?.name?:t.name,lastPrice=if(q?.current!=null) fmtPrice(q.current) else t.lastPrice)}
                return buildString { appendLine("📋 自选股 (${ref.size}只)\n"); appendLine("名称${" ".repeat(6)} 代码      当前价    添加日期")
                    ref.forEach{appendLine("  ${(it.name.ifEmpty{it.symbol}).padEnd(8)} ${it.symbol.padEnd(10)} ${it.lastPrice?.padEnd(8)?:"N/A".padEnd(8)} ${it.added}")}
                    appendLine("\n💡 用 action=track&track_action=refresh 刷新价格，action=analysis 分析") }.trimEnd() }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  技术指标计算
    // ═══════════════════════════════════════════════════════

    private fun ma(d: List<Double>, p: Int): List<Double> { if (d.size<p) return emptyList(); val r=mutableListOf<Double>(); for (i in p-1 until d.size) r.add(d.subList(i-p+1,i+1).average()); return r }
    private fun macdOf(d: List<Double>, ei: Int) = ema(d.subList(0,ei+1),12) - ema(d.subList(0,ei+1),26)
    private fun smaOfDifs(d: List<Double>, ei: Int): Double? { if (ei<0||ei>=d.size) return null; return (maxOf(0,ei-8)..ei).map{macdOf(d,it)}.average() }

    private fun macd(d: List<Double>): MACDResult {
        val e12 = ema(d,12); val e26 = ema(d,26); val dif = e12-e26; val difs = mutableListOf<Double>()
        for (i in d.indices) difs.add(ema(d.subList(0,i+1),12)-ema(d.subList(0,i+1),26))
        val dea = if (difs.size>=9) difs.takeLast(9).average() else difs.average(); val bar = dif-dea
        val sig = when { bar>0 && difs.size>=2 && (difs.last()-difs.getOrElse(difs.size-2){difs.last()})>0 -> "多头占优，红柱扩张↗"
            bar>0 -> "多头占优，红柱"; bar<0 && difs.size>=2 && (difs.last()-difs.getOrElse(difs.size-2){difs.last()})<0 -> "空头占优，绿柱扩张↘"
            bar<0 -> "空头占优，绿柱"; dif>dea -> "DIF在DEA上方(偏多)"; dif<dea -> "DIF在DEA下方(偏空)"; else -> "DIF与DEA粘合" }
        return MACDResult(dif,dea,bar,sig)
    }
    private fun ema(d: List<Double>, p: Int): Double { if (d.isEmpty()) return 0.0; val m = 2.0/(p+1); var r=d.first(); for (i in 1 until d.size) r=(d[i]-r)*m+r; return r }
    private fun rsi(d: List<Double>, p: Int): Double { if (d.size<=p) return 50.0; var g=0.0; var l=0.0; for (i in d.size-p until d.size) { val df=d[i]-d[i-1]; if (df>0) g+=df else l-=df }; val ag=g/p; val al=l/p; if (al==0.0) return 100.0; return 100.0-100.0/(1.0+ag/al) }
    private fun bollinger(d: List<Double>, p: Int=20): Bollinger { val r=d.takeLast(p); val m=r.average(); val v=r.map{(it-m)*(it-m)}.average(); return Bollinger(m+2*sqrt(v),m,m-2*sqrt(v)) }
    private fun kdj(h: List<Double>, l: List<Double>, c: List<Double>, n: Int=9, m1: Int=3, m2: Int=3): KDJResult {
        if (h.size<n) return KDJResult(null,null,null); val rsv=mutableListOf<Double>()
        for (i in n-1 until c.size) { val hh=h.subList(i-n+1,i+1).maxOrNull()?:0.0; val ll=l.subList(i-n+1,i+1).minOrNull()?:0.0; rsv.add(if(hh!=ll)(c[i]-ll)/(hh-ll)*100 else 50.0) }
        if (rsv.size<maxOf(m1,m2)) return KDJResult(null,null,null); val k=sma(rsv,m1)
        val d = if (rsv.size>=m2) { val kv=mutableListOf(50.0); for (i in 1 until rsv.size) kv.add((2.0/3)*kv.last()+(1.0/3)*rsv[i]); sma(kv,m2).coerceIn(0.0,100.0) } else 50.0
        return KDJResult(k.coerceIn(0.0,100.0),d.coerceIn(0.0,100.0),3*k-2*d)
    }
    private fun sma(d: List<Double>, p: Int): Double { if (d.isEmpty()) return 50.0; if (d.size<p) return d.average(); return d.takeLast(p).average() }
    private fun supportResistance(h: List<Double>, l: List<Double>, c: List<Double>): Pair<List<Double>,List<Double>> {
        val rh=h.takeLast(60).distinct().sorted(); val rl=l.takeLast(60).distinct().sorted(); val cur=c.last()
        return Pair(rl.filter{it<cur&&it>cur*0.85}.distinct().sortedDescending().take(3), rh.filter{it>cur&&it<cur*1.15}.distinct().take(3)) }

    // ═══════════════════════════════════════════════════════
    //  数据源 Sina / Tencent / EastMoney
    // ═══════════════════════════════════════════════════════

    private suspend fun fetchSina(sym: NormalizedSymbol): StockQuote? {
        val body = httpGet("https://hq.sinajs.cn/list=${sym.sina}","https://finance.sina.com.cn")?:return null; if (!body.contains("hq_str_")) return null
        val q = extractQuoted(body)?.split(",")?:return null; if (q.size<20) return null
        return if (sym.sina.startsWith("gb_")) StockQuote(q[0],sym.display,q[1].toDoubleOrNull(),q[26].toDoubleOrNull(),q[5].toDoubleOrNull(),q[6].toDoubleOrNull(),q[7].toDoubleOrNull(),q[12].toDoubleOrNull(),null,q[3])
        else StockQuote(q[0],sym.display,q[3].toDoubleOrNull(),q[2].toDoubleOrNull(),q[1].toDoubleOrNull(),q[4].toDoubleOrNull(),q[5].toDoubleOrNull(),q[8].toDoubleOrNull(),q[9].toDoubleOrNull(),"${q[30]} ${q[31]}")
    }
    private suspend fun fetchTencent(sym: NormalizedSymbol): StockQuote? {
        val q = extractQuoted(httpGet("https://qt.gtimg.cn/q=${sym.tencent}")?:return null)?.split("~")?:return null; if (q.size<40) return null
        return StockQuote(q.getOrElse(1){""},sym.display,q.getOrElse(3){""}.toDoubleOrNull(),q.getOrElse(4){""}.toDoubleOrNull(),q.getOrElse(5){""}.toDoubleOrNull(),q.getOrElse(33){""}.toDoubleOrNull(),q.getOrElse(34){""}.toDoubleOrNull(),q.getOrElse(6){""}.toDoubleOrNull(),q.getOrElse(7){""}.toDoubleOrNull(),"${q.getOrElse(35){""}} ${q.getOrElse(36){""}}")
    }
    private suspend fun fetchEastMoney(sym: NormalizedSymbol): StockQuote? {
        val d = jsonObj(parseJsonObj(httpGet("https://push2.eastmoney.com/api/qt/stock/get?secid=${sym.eastMoney}&fields=f43,f44,f45,f46,f47,f48,f50,f57,f58,f170,f171","https://quote.eastmoney.com")?:return null), "data")?:return null
        if (d.get("f57")?.isJsonNull!=false) return null; return StockQuote(jsonStr(d,"f58")?:"" ,sym.display,jsonNum(d,"f43"),jsonNum(d,"f47"),jsonNum(d,"f46"),jsonNum(d,"f44"),jsonNum(d,"f45"),jsonNum(d,"f48"),jsonNum(d,"f50"),null)
    }
    private suspend fun basicAnalysis(quote: StockQuote): String = formatQuote(quote)

    // ═══════════════════════════════════════════════════════
    //  格式化 + 工具函数
    // ═══════════════════════════════════════════════════════

    private fun normalizeSymbol(raw: String): NormalizedSymbol {
        val s = raw.lowercase().trim()
        if (s.startsWith("sh")||s.startsWith("sz")) { val c=s.substring(2); return NormalizedSymbol(s,s,if(s.startsWith("sh"))"1.$c" else "0.$c",s) }
        if (s.startsWith("gb_")) return NormalizedSymbol(s,"us${s.removePrefix("gb_").uppercase()}","105.${s.removePrefix("gb_").uppercase()}",s)
        val p = if (s.all{it.isDigit()}) { if (s.startsWith("6")||s.startsWith("9")) "sh$s" else "sz$s" } else "gb_$s"
        return normalizeSymbol(p)
    }

    private fun extractQuoted(body: String): String? { val s=body.indexOf("\""); val e=body.lastIndexOf("\""); return if (s<0||e<=s) null else body.substring(s+1,e) }
    private fun formatQuote(q: StockQuote): String { val ch = if (q.current!=null&&q.prevClose!=null) q.current-q.prevClose else null; val cp = if (ch!=null&&q.prevClose!=null&&q.prevClose!=0.0) ch/q.prevClose*100 else null
        val pre = if (q.symbol.let{it.startsWith("sh000")||it.startsWith("sz399")||it.startsWith("sh688")}) "📊" else "📈"
        return buildString { appendLine("$pre ${q.name} (${q.symbol.uppercase()})"); appendLine("  当前:${fmtPrice(q.current)}"); if (ch!=null) appendLine("  涨跌:${if(ch>=0)"↑" else "↓"}${fmtPrice(ch)} (${fmtPct(cp)})"); appendLine("  今开:${fmtPrice(q.open)} | 昨收:${fmtPrice(q.prevClose)}"); appendLine("  最高:${fmtPrice(q.high)} | 最低:${fmtPrice(q.low)}"); if (q.volume!=null) appendLine("  成交量:${fmtVol(q.volume)}"); if (q.amount!=null) appendLine("  成交额:${fmtAmt(q.amount)}"); if (q.time!=null) appendLine("  时间:${q.time}") }.trimEnd() }
    private fun fmtPrice(v:Double?):String = v?.let{String.format("%.2f",it)} ?: "N/A"
    private fun fmtPct(v:Double?):String = v?.let{String.format("%+.2f%%",it)} ?: "N/A"
    private fun fmtDate(d:String):String = if (d.length>=10) d.take(10) else d
    private fun fmtVol(v:Double?):String { if (v==null) return "N/A"; return when { v>=100_000_000 -> String.format("%.2f亿",v/100_000_000); v>=10_000 -> String.format("%.2f万",v/10_000); else -> v.toLong().toString() } }
    private fun fmtAmt(v:Double?):String { if (v==null) return "N/A"; return when { v>=100_000_000 -> String.format("%.2f亿",v/100_000_000); v>=10_000 -> String.format("%.2f万",v/10_000); else -> String.format("%.2f",v) } }
    private fun fmtBigAmt(v:Double?):String { if (v==null) return "N/A"; return when { v>=10_000_000_000L -> String.format("%.2f亿",v/100_000_000); v>=10_000_000 -> String.format("%.2f千万",v/10_000_000); else -> String.format("%.2f万",v/10_000) } }
    private fun sparkline(d: List<Double>): String { if (d.isEmpty()) return ""; val mn=d.min(); val mx=d.max(); val rg=mx-mn; val ch=listOf('▁','▂','▃','▄','▅','▆','▇','█')
        if (rg==0.0) return ch[3].toString().repeat(d.size); return d.joinToString(""){ch[((it-mn)/rg*(ch.size-1)).toInt().coerceIn(0,ch.size-1)].toString()} }

    // ═══════════════════════════════════════════════════════
    //  数据可靠性: Sanity Check
    // ═══════════════════════════════════════════════════════

    /** 验证行情数据合理性，返回 null=正常，非null=异常描述 */
    private fun sanityCheckQuote(q: StockQuote): String? {
        val price = q.current
        if (price != null && (price < 0.001 || price > 50000)) return "股价异常: $price"
        if (q.open != null && q.open < 0) return "开盘价异常"
        if (q.high != null && q.low != null && q.high < q.low) return "最高<最低"
        if (q.high != null && price != null && q.high < price) return "最高<当前价"
        if (q.low != null && price != null && q.low > price) return "最低>当前价"
        if (q.volume != null && q.volume < 0) return "成交量为负"
        if (q.amount != null && q.amount < 0) return "成交额为负"
        return null
    }

    /** 验证K线数据合理性 */
    private fun sanityCheckKline(kline: List<Kline>): List<String> {
        val warnings = mutableListOf<String>()
        if (kline.size < 10) warnings.add("K线数据不足${kline.size}条")
        for (k in kline) {
            if (k.close <= 0) { warnings.add("存在收盘价为0(停牌或数据异常)"); break }
            if (k.high < k.low) { warnings.add("存在最高<最低的K线"); break }
            if (k.high < k.close) { warnings.add("存在最高<收盘的K线"); break }
            if (k.low > k.close) { warnings.add("存在最低>收盘的K线"); break }
        }
        return warnings
    }

    /** K线字段顺序验证：东方财富顺序是 [日期,开盘,收盘,最高,最低,成交量] */
    private fun verifyKlineFieldOrder(kline: Kline): Boolean {
        return kline.open > 0 && kline.close > 0 && kline.high >= kline.low &&
               kline.high >= kline.close && kline.high >= kline.open &&
               kline.low <= kline.close && kline.low <= kline.open
    }

    // ═══════════════════════════════════════════════════════
    //  涨跌量不对称比 (来自 astock-analyst)
    // ═══════════════════════════════════════════════════════

    /**
     * 计算涨跌量不对称比 = 跌日总成交量 / 涨日总成交量
     * > 1.2 出货特征  > 1.5 强烈出货
     */
    private data class AsymmetryResult(val ratio: Double, val verdict: String, val upVol: Double, val dnVol: Double)

    private fun calcAsymmetryRatio(kline: List<Kline>, period: Int = 60): AsymmetryResult {
        val recent = kline.takeLast(period)
        var upVol = 0.0; var dnVol = 0.0
        for (k in recent) {
            if (k.close > k.open) upVol += k.volume
            else if (k.close < k.open) dnVol += k.volume
        }
        val ratio = if (upVol > 0) dnVol / upVol else 999.0
        val verdict = when {
            ratio > 1.5 -> "强烈出货信号 ❌"
            ratio > 1.2 -> "出货特征 ⚠"
            ratio > 1.0 -> "偏空"
            ratio < 0.8 -> "买盘强势 ✅"
            else -> "正常范围"
        }
        return AsymmetryResult(ratio, verdict, upVol, dnVol)
    }

    // ═══════════════════════════════════════════════════════
    //  期望值公式 (来自 astock-analyst)
    // ═══════════════════════════════════════════════════════

    private data class ExpectedValueResult(val ev: Double, val upProb: Double, val avgUp: Double, val avgDn: Double, val verdict: String)

    private fun calcExpectedValue(kline: List<Kline>, period: Int = 60): ExpectedValueResult {
        val recent = kline.takeLast(period)
        val upReturns = mutableListOf<Double>()
        val dnReturns = mutableListOf<Double>()
        for (k in recent) {
            if (k.open <= 0) continue
            val ret = (k.close - k.open) / k.open * 100
            if (ret > 0) upReturns.add(ret)
            else dnReturns.add(ret)
        }
        val total = upReturns.size + dnReturns.size
        val upProb = if (total > 0) upReturns.size.toDouble() / total else 0.5
        val avgUp = if (upReturns.isNotEmpty()) upReturns.average() else 0.0
        val avgDn = if (dnReturns.isNotEmpty()) dnReturns.average() else 0.0
        val ev = upProb * avgUp + (1 - upProb) * avgDn
        val verdict = when {
            ev > 0.5 -> "赔率优秀 ✅"
            ev > 0 -> "赔率合理"
            ev > -0.5 -> "赔率偏低 ⚠"
            else -> "赔率不利 ❌"
        }
        return ExpectedValueResult(ev, upProb, avgUp, avgDn, verdict)
    }

    // ═══════════════════════════════════════════════════════
    //  OBV (能量潮) 趋势判断
    // ═══════════════════════════════════════════════════════

    private data class OBVResult(val direction: String, val verdict: String)

    private fun calcOBV(kline: List<Kline>): OBVResult {
        if (kline.size < 11) return OBVResult("未知", "数据不足")
        val obvList = mutableListOf<Double>()
        var obv = 0.0; var prevClose = kline.first().close
        for (i in 1 until kline.size) {
            val k = kline[i]
            if (k.close > prevClose) obv += k.volume
            else if (k.close < prevClose) obv -= k.volume
            obvList.add(obv); prevClose = k.close
        }
        if (obvList.isEmpty()) return OBVResult("未知", "数据不足")
        val currentObv = obvList.last()
        val pastObv = if (obvList.size >= 10) obvList[obvList.size - 10] else obvList.first()
        val direction = if (currentObv > pastObv) "上行" else "下行"
        val verdict = when {
            direction == "上行" -> "资金净流入 ✅"
            direction == "下行" && kline.last().close > kline[kline.size - 11].close -> "价涨OBV降 ⚠背离"
            else -> "资金流出"
        }
        return OBVResult(direction, verdict)
    }

    suspend fun renderKlinePanel(symbol: String, trades: List<TradePoint>, width: Int = 600, height: Int = 400): String? {
        val sym = normalizeSymbol(symbol)
        val quote = fetchQuote(sym) ?: return null
        val kline = fetchKline(sym) ?: return null
        val name = quote.name.ifEmpty { sym.display.uppercase() }
        return renderKlineChart(name, sym.display, kline.takeLast(120), width, height, trades)
    }

    suspend fun getFundamentalText(symbol: String): String? {
        val sym = normalizeSymbol(symbol)
        val quote = fetchQuote(sym) ?: return null
        if (sym.sina.startsWith("gb_")) return null
        val finJson = fetchFundamental(sym) ?: return null
        val name = jsonStr(finJson, "f58") ?: quote.name
        val pe = jsonNum(finJson, "f100"); val pb = jsonNum(finJson, "f115")
        val eps = jsonNum(finJson, "f162"); val bvps = jsonNum(finJson, "f164"); val roe = jsonNum(finJson, "f185")
        val totalMcap = jsonNum(finJson, "f152"); val circMcap = jsonNum(finJson, "f167")
        val turnoverRate = jsonNum(finJson, "f117"); val totalShares = jsonNum(finJson, "f163")
        val high52 = jsonNum(finJson, "f168"); val low52 = jsonNum(finJson, "f169")
        return buildString {
            appendLine("📋 $name ($symbol)")
            appendLine("【估值】${if(pe!=null)"PE:"+fmtPrice(pe)+peEval(pe) else ""}  ${if(pb!=null)"PB:"+fmtPrice(pb) else ""}")
            if (eps != null) appendLine("  EPS:${fmtPrice(eps)}  每股净资产:${if(bvps!=null)fmtPrice(bvps) else "N/A"}  ROE:${if(roe!=null)fmtPrice(roe)+"%" else "N/A"}")
            appendLine("【市值】总市值:${if(totalMcap!=null)fmtBigAmt(totalMcap) else "N/A"}  流通:${if(circMcap!=null)fmtBigAmt(circMcap) else "N/A"}")
            appendLine("  总股本:${if(totalShares!=null)fmtVol(totalShares) else "N/A"}  换手率:${if(turnoverRate!=null)fmtPrice(turnoverRate)+"%" else "N/A"}")
            if (low52 != null && high52 != null) {
                val pct = if (high52 != low52) ((quote.current ?: 0.0) - low52) / (high52 - low52) * 100 else 0.0
                appendLine("【区间】52周:${fmtPrice(low52)} ~ ${fmtPrice(high52)}  当前在${String.format("%.1f", pct)}%分位")
            }
        }.trimEnd()
    }
}
