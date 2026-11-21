package com.wy.openx.tools

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import android.util.Log

class QuantTool : AgentTool {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override val name = "quant"
    override val description = "高级量化分析系统。支持：市场状态识别、多周期共振分析、策略预设、风险评估(止盈止损)、策略回测"
    override val parameters = buildToolParameters(
        ToolParameter("action", ParameterType.STRING, "操作类型\n- regime: 市场状态识别(趋势/震荡/高波动)\n- multiframe: 多周期共振分析(日+周+月线)\n- strategy: 策略预设(突破/低吸/强势/反弹/价值)\n- risk: 风险评估(止盈止损位/RR比)\n- fivegate: 五关论证(结构化荐股决策，从硬门槛→评分→期望值→趋势→风险逐层判断)\n- backtest: 策略回测(历史数据验证胜率，支持滑点/手续费)\n- optimize: 策略参数优化(自动搜索最佳参数组合)\n- rotation: 行业轮动追踪(板块资金流向变化)", enumValues = listOf("regime", "multiframe", "strategy", "risk", "fivegate", "backtest", "optimize", "rotation")),
        ToolParameter("symbol", ParameterType.STRING, "股票代码，如 sh600000 / sz000001", required = false),
        ToolParameter("strategy_name", ParameterType.STRING, "strategy/backtest 模式的策略名: breakout(突破) / dip(低吸) / momentum(强势) / reversal(超跌反弹) / value(价值)", required = false),
        ToolParameter("count", ParameterType.INTEGER, "K线数量(默认120)", required = false),
        ToolParameter("slippage", ParameterType.NUMBER, "回测滑点(单边%，默认0.1)", required = false),
        ToolParameter("fee_rate", ParameterType.NUMBER, "回测手续费率(双边%，默认0.03)", required = false),
        ToolParameter("param_name", ParameterType.STRING, "optimize 要优化的参数: rsi_period / ma_period / hold_days", required = false),
        ToolParameter("param_start", ParameterType.INTEGER, "optimize 参数起始值", required = false),
        ToolParameter("param_end", ParameterType.INTEGER, "optimize 参数结束值", required = false),
        ToolParameter("param_step", ParameterType.INTEGER, "optimize 参数步长", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = (args["action"] as? String)?.trim()?.lowercase() ?: "regime"
        val rawSymbol = (args["symbol"] as? String)?.trim()
        return try {
            when (action) {
                "regime" -> {
                    val sym = rawSymbol ?: return "错误：未指定股票代码"
                    doRegime(sym)
                }
                "multiframe" -> {
                    val sym = rawSymbol ?: return "错误：未指定股票代码"
                    doMultiframe(sym)
                }
                "strategy" -> {
                    val sym = rawSymbol ?: return "错误：未指定股票代码"
                    val sn = (args["strategy_name"] as? String)?.trim()?.lowercase() ?: "breakout"
                    doStrategy(sym, sn)
                }
                "risk" -> {
                    val sym = rawSymbol ?: return "错误：未指定股票代码"
                    doRisk(sym)
                }
                "backtest" -> {
                    val sym = rawSymbol ?: return "错误：未指定股票代码"
                    val sn = (args["strategy_name"] as? String)?.trim()?.lowercase() ?: "breakout"
                    val cnt = ((args["count"] as? Double)?.toInt()) ?: 120
                    val slp = (args["slippage"] as? Double) ?: 0.1
                    val fee = (args["fee_rate"] as? Double) ?: 0.03
                    doBacktest(sym, sn, cnt, slp, fee)
                }
                "optimize" -> {
                    val sym = rawSymbol ?: return "错误：未指定股票代码"
                    val sn = (args["strategy_name"] as? String)?.trim()?.lowercase() ?: "breakout"
                    val cnt = ((args["count"] as? Double)?.toInt()) ?: 120
                    val pn = (args["param_name"] as? String)?.trim()?.lowercase() ?: "hold_days"
                    val ps = ((args["param_start"] as? Double)?.toInt()) ?: 5
                    val pe = ((args["param_end"] as? Double)?.toInt()) ?: 40
                    val pst = ((args["param_step"] as? Double)?.toInt()) ?: 5
                    doOptimize(sym, sn, cnt, pn, ps, pe, pst)
                }
                "fivegate" -> {
                    val sym = rawSymbol ?: return "错误：未指定股票代码"
                    doFivegate(sym)
                }
                "rotation" -> doRotation()
                else -> "未知操作: $action，支持: regime/multiframe/strategy/risk/fivegate/backtest/rotation"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "量化分析失败: ${e.message ?: "未知错误"}" }
    }

    // ═══════════════════════════════════════════════════════
    //  市场状态识别
    // ═══════════════════════════════════════════════════════

    private data class RegimeResult(val regime: String, val adx: Double, val atr: Double, val atrPct: Double, val maSlope: Double, val volatility: String, val description: String)

    private suspend fun doRegime(raw: String): String {
        val sym = normalizeSymbol(raw)
        val kline = fetchDailyKline(sym, 60) ?: return "K线数据获取失败"
        val closes = kline.map { it.close }; val highs = kline.map { it.high }; val lows = kline.map { it.low }
        if (closes.size < 20) return "数据不足(需20+交易日)"

        val adxVal = calcADX(highs, lows, closes, 14)
        val atrVal = calcATR(highs, lows, closes, 14)
        val avgPrice = closes.average()
        val atrPct = if (avgPrice > 0) atrVal / avgPrice * 100 else 0.0
        val ma20Slope = calcMaSlope(closes, 20)
        val ma60Slope = if (closes.size >= 60) calcMaSlope(closes, 60) else ma20Slope

        val volatility = when { atrPct > 4 -> "高波动"; atrPct > 2 -> "中波动"; else -> "低波动" }

        val regime = when {
            adxVal > 25 && ma20Slope > 0.5 -> "强势上涨趋势"
            adxVal > 25 && ma20Slope > 0.1 -> "温和上涨趋势"
            adxVal > 25 && ma20Slope < -0.5 -> "强势下跌趋势"
            adxVal > 25 && ma20Slope < -0.1 -> "温和下跌趋势"
            adxVal > 25 -> "强趋势(方向待确认)"
            adxVal < 20 && atrPct < 2 -> "横盘震荡"
            adxVal < 20 -> "弱势震荡"
            else -> "趋势酝酿期"
        }

        val desc = when {
            regime.contains("上涨") && volatility == "高波动" -> "上升趋势中但波动较大，注意回调风险，建议控制仓位"
            regime.contains("上涨") -> "上升趋势良好，可顺势做多，均线附近低吸"
            regime.contains("下跌") && volatility == "高波动" -> "下跌趋势+高波动，风险较大，建议观望"
            regime.contains("下跌") -> "下跌趋势，不建议抄底，等待企稳信号"
            regime == "横盘震荡" -> "横盘整理，突破方向不明，可高抛低吸或等待突破"
            else -> "市场方向不明确，建议观望或轻仓操作"
        }

        val result = buildString {
            appendLine("📊 ${getQuoteName(sym)} 市场状态识别")
            appendLine()
            appendLine("【市场状态】$regime")
            appendLine("  ADX(14): ${String.format("%.1f", adxVal)} ${if (adxVal > 25) "✓ 趋势行情" else if (adxVal < 20) "○ 震荡行情" else "▷ 趋势酝酿"}")
            appendLine("  ATR(14): ${String.format("%.2f", atrVal)} (${String.format("%.2f", atrPct)}%) → $volatility")
            appendLine("  MA20斜率: ${String.format("%+.4f", ma20Slope)} ${if (ma20Slope > 0) "↗" else "↘"}")
            if (closes.size >= 60) appendLine("  MA60斜率: ${String.format("%+.4f", ma60Slope)}")
            appendLine()
            appendLine("【操作建议】$desc")
        }.trimEnd()
        return result
    }

    // ═══════════════════════════════════════════════════════
    //  多周期共振
    // ═══════════════════════════════════════════════════════

    private suspend fun doMultiframe(raw: String): String {
        val sym = normalizeSymbol(raw)
        val daily = fetchDailyKline(sym, 60) ?: return "日线数据获取失败"
        val weekly = fetchKlineRaw(sym, "weekly", 52) ?: return "周线数据获取失败"
        val monthly = fetchKlineRaw(sym, "monthly", 24) ?: return "月线数据获取失败"

        fun analyze(data: List<Kline>, label: String): Triple<String, String, String> {
            val c = data.map { it.close }; val h = data.map { it.high }; val l = data.map { it.low }
            if (c.size < 10) return Triple("数据不足", "中性", "等待")
            val ma5 = ma(c, 5).lastOrNull() ?: 0.0; val ma10 = ma(c, 10).lastOrNull() ?: 0.0; val ma20 = ma(c, 20).lastOrNull() ?: 0.0
            val last = c.last(); val rsiVal = rsi(c, 14)
            val trend = when {
                last > ma5 && last > ma10 && last > ma20 && ma5 > ma10 && ma10 > ma20 -> "↑ 多头排列"
                last < ma5 && last < ma10 && last < ma20 && ma5 < ma10 && ma10 < ma20 -> "↓ 空头排列"
                last > ma20 && ma5 > ma20 -> "↗ 偏多"
                last < ma20 && ma5 < ma20 -> "↘ 偏空"
                else -> "→ 震荡"
            }
            val signal = when {
                rsiVal > 70 -> "超买⚠"; rsiVal < 30 -> "超卖⚠"; rsiVal >= 50 -> "偏强"; else -> "偏弱"
            }
            val action = when {
                trend.contains("多头") && rsiVal < 60 -> "看涨✓"
                trend.contains("多头") && rsiVal < 70 -> "谨慎看涨"
                trend.contains("空头") && rsiVal > 35 -> "看跌✓"
                trend.contains("空头") -> "谨慎看跌"
                trend.contains("偏多") && rsiVal < 60 -> "偏多"
                trend.contains("偏空") && rsiVal > 35 -> "偏空"
                else -> "观望"
            }
            return Triple(trend, signal, action)
        }

        val (dTrend, dSig, dAct) = analyze(daily, "日线")
        val (wTrend, wSig, wAct) = analyze(weekly, "周线")
        val (mTrend, mSig, mAct) = analyze(monthly, "月线")

        val name = getQuoteName(sym)

        val consensus = buildString {
            val actions = listOf(dAct, wAct, mAct)
            val bullish = actions.count { it.contains("看涨") || it == "偏多" }
            val bearish = actions.count { it.contains("看跌") || it == "偏空" }
            appendLine("📊 $name ($raw) 多周期共振分析")
            appendLine()
            appendLine("周期    趋势          RSI信号    操作建议")
            appendLine("─────  ────────     ────────    ────────")
            appendLine("日线    ${dTrend.padEnd(12)} ${dSig.padEnd(10)} $dAct")
            appendLine("周线    ${wTrend.padEnd(12)} ${wSig.padEnd(10)} $wAct")
            appendLine("月线    ${mTrend.padEnd(12)} ${mSig.padEnd(10)} $mAct")
            appendLine()
            when {
                bullish >= 2 && bearish == 0 -> appendLine("【共振结论】✅ 多周期共振看涨，可积极做多")
                bullish == 3 -> appendLine("【共振结论】🔥 全周期一致看涨，强烈做多信号")
                bearish >= 2 && bullish == 0 -> appendLine("【共振结论】❌ 多周期共振看跌，建议空仓观望")
                bearish == 3 -> appendLine("【共振结论】⚠️ 全周期一致看跌，强烈规避")
                dAct.contains("看涨") && !wAct.contains("看跌") -> appendLine("【共振结论】📈 短多中稳，可做多但注意周线级别阻力")
                dAct.contains("看跌") && !wAct.contains("看涨") -> appendLine("【共振结论】📉 短线调整，中线不明，暂观望")
                else -> appendLine("【共振结论】⚖ 周期信号不一致，建议缩小仓位等待共振")
            }
        }.trimEnd()
        return consensus
    }

    // ═══════════════════════════════════════════════════════
    //  策略预设
    // ═══════════════════════════════════════════════════════

    private suspend fun doStrategy(raw: String, sn: String): String {
        val sym = normalizeSymbol(raw)
        val kline = fetchDailyKline(sym, 120) ?: return "K线数据获取失败"
        val c = kline.map { it.close }; val h = kline.map { it.high }; val l = kline.map { it.low }; val v = kline.map { it.volume }
        val last = c.last(); val name = getQuoteName(sym)
        val quote = fetchQuote(sym)

        val result = when (sn) {
            "breakout" -> breakoutStrategy(name, raw, c, h, l, v, kline, quote)
            "dip" -> dipStrategy(name, raw, c, h, l, v, kline, quote)
            "momentum" -> momentumStrategy(name, raw, c, h, l, v, kline, quote)
            "reversal" -> reversalStrategy(name, raw, c, h, l, v, kline, quote)
            "value" -> valueStrategy(name, raw, quote)
            else -> "未知策略: $sn，支持: breakout/dip/momentum/reversal/value"
        }
        return result
    }

    private fun breakoutStrategy(name: String, raw: String, c: List<Double>, h: List<Double>, l: List<Double>, v: List<Double>, kline: List<Kline>, quote: StockQuote?): String {
        val last = c.last(); val ma20 = ma(c, 20).lastOrNull() ?: 0.0; val ma5 = ma(c, 5).lastOrNull() ?: 0.0; val ma10 = ma(c, 10).lastOrNull() ?: 0.0
        val avgVol = v.takeLast(20).average(); val lastVol = v.lastOrNull() ?: 0.0
        val volRatio = if (avgVol > 0) lastVol / avgVol else 1.0
        val boll = bollinger(c); val rsiVal = rsi(c, 14)
        val supRes = supportResistance(h, l, c)
        val score = mutableListOf<String>()

        val signals = mutableListOf<String>()
        if (last > ma20 && ma5 > ma20) signals.add("价格突破MA20")
        if (last > boll.mid && last < boll.upper) signals.add("布林中轨上方运行")
        if (volRatio > 1.5) signals.add("放量${String.format("%.1f", volRatio)}倍")
        if (last > ma5 && ma5 > ma10) signals.add("均线多头排列")
        if (rsiVal in 50.0..70.0) signals.add("RSI偏强(${String.format("%.0f", rsiVal)})未超买")
        if (lastVol > 0 && last > c.getOrElse(c.size - 2) { last }) signals.add("价格收涨")

        val validCount = signals.size
        val verdict = when { validCount >= 4 -> "强烈看涨⭐"; validCount >= 3 -> "看涨"; validCount >= 2 -> "谨慎看涨"; else -> "不满足突破条件" }
        val sl = supRes.first.maxOrNull()?.let { String.format("%.2f", it) } ?: String.format("%.2f", last * 0.95)
        val tp = supRes.second.minOrNull()?.let { String.format("%.2f", it) } ?: String.format("%.2f", last * 1.05)

        return buildString {
            appendLine("📈 $name 突破策略分析")
            appendLine("  策略: 放量突破均线/平台 → 顺势做多")
            appendLine("  当前: ${fmtPrice(last)} MA20:${fmtPrice(ma20)}")
            appendLine()
            appendLine("【信号检查 ${validCount}/6】")
            signals.forEach { appendLine("  ✅ $it") }
            if (validCount < 6) appendLine("  ${"⬜".repeat(6 - validCount)} 待确认信号")
            appendLine()
            appendLine("【判定】$verdict")
            if (verdict.contains("看涨")) {
                appendLine("  🎯 目标位: $tp")
                appendLine("  🛑 止损位: $sl")
                val rr = if (last > 0 && sl.toDoubleOrNull() != null && tp.toDoubleOrNull() != null)
                    String.format("%.2f", (tp.toDouble() - last) / (last - sl.toDouble())) else "N/A"
                appendLine("  📊 盈亏比(R/R): $rr")
            }
            appendLine()
            appendLine("💡 建议: ${if (verdict.contains("看涨")) "可考虑入场，分批建仓" else "等待更明确信号，当前不宜追高"}")
        }.trimEnd()
    }

    private fun dipStrategy(name: String, raw: String, c: List<Double>, h: List<Double>, l: List<Double>, v: List<Double>, kline: List<Kline>, quote: StockQuote?): String {
        val last = c.last(); val ma20 = ma(c, 20).lastOrNull() ?: 0.0; val ma60 = if (c.size >= 60) ma(c, 60).lastOrNull() else null
        val rsiVal = rsi(c, 14); val boll = bollinger(c); val atrVal = calcATR(h, l, c, 14)
        val avgVol = v.takeLast(20).average(); val lastVol = v.lastOrNull() ?: 0.0; val volRatio = if (avgVol > 0) lastVol / avgVol else 1.0
        val supRes = supportResistance(h, l, c)
        val score = mutableListOf<String>()

        val signals = mutableListOf<String>()
        if (last < ma20 * 1.03) signals.add("价格在MA20附近")
        if (last <= boll.lower * 1.02) signals.add("接近布林下轨")
        if (rsiVal < 35) signals.add("RSI超卖(${String.format("%.0f", rsiVal)})")
        if (ma60 != null && last < ma60 * 1.03) signals.add("接近MA60支撑")
        if (volRatio < 0.8) signals.add("缩量${String.format("%.0f", volRatio * 100)}%")
        val nearestSup = supRes.first.maxOrNull()
        if (nearestSup != null && last > 0) { val dist = (last - nearestSup) / last * 100; if (dist < 3) signals.add("距支撑仅${String.format("%.1f", dist)}%") }

        val validCount = signals.size
        val verdict = when { validCount >= 4 -> "强烈看涨⭐"; validCount >= 3 -> "看涨(低吸机会)"; validCount >= 2 -> "关注"; else -> "不满足低吸条件" }
        val sl = if (nearestSup != null) String.format("%.2f", nearestSup * 0.98) else String.format("%.2f", last * 0.95)
        val tp = if (ma60 != null) String.format("%.2f", ma60 * (1 + atrVal / ma60)) else String.format("%.2f", last * 1.05)

        return buildString {
            appendLine("📉 $name 低吸策略分析")
            appendLine("  策略: 回调至支撑位企稳 → 低吸布局")
            appendLine("  当前: ${fmtPrice(last)} RSI:${String.format("%.0f", rsiVal)}")
            appendLine()
            appendLine("【信号检查 ${validCount}/6】")
            signals.forEach { appendLine("  ✅ $it") }
            appendLine()
            appendLine("【判定】$verdict")
            if (verdict.contains("看涨") || verdict == "关注") {
                appendLine("  🎯 目标位: $tp")
                appendLine("  🛑 止损位: $sl")
                val rr = if (last > 0 && sl.toDoubleOrNull() != null && tp.toDoubleOrNull() != null)
                    String.format("%.2f", (tp.toDouble() - last) / (last - sl.toDouble())) else "N/A"
                appendLine("  📊 盈亏比(R/R): $rr")
            }
            appendLine()
            appendLine("💡 建议: ${if (verdict.contains("看涨")) "可分批低吸，仓位不宜过重" else "等待企稳信号，不急于抄底"}")
        }.trimEnd()
    }

    private fun momentumStrategy(name: String, raw: String, c: List<Double>, h: List<Double>, l: List<Double>, v: List<Double>, kline: List<Kline>, quote: StockQuote?): String {
        val last = c.last(); val ma5 = ma(c, 5).lastOrNull() ?: 0.0; val ma10 = ma(c, 10).lastOrNull() ?: 0.0; val ma20 = ma(c, 20).lastOrNull() ?: 0.0
        val rsiVal = rsi(c, 14); val macdVal = macd(c); val kdjVal = kdj(h, l, c)
        val avgVol = v.takeLast(20).average(); val lastVol = v.lastOrNull() ?: 0.0; val volRatio = if (avgVol > 0) lastVol / avgVol else 1.0

        val signals = mutableListOf<String>()
        if (last > ma5 && ma5 > ma10 && ma10 > ma20) signals.add("均线多头排列(强势)")
        if (macdVal.bar > 0 && macdVal.dif > macdVal.dea) signals.add("MACD多头")
        if (rsiVal in 50.0..75.0) signals.add("RSI偏强(${String.format("%.0f", rsiVal)})")
        if (kdjVal.k != null && kdjVal.d != null && kdjVal.k > kdjVal.d && kdjVal.k > 50) signals.add("KDJ多头")
        if (volRatio > 1.2) signals.add("放量${String.format("%.0f", volRatio * 100)}%")
        val last5chg = if (c.size >= 6) (c.last() - c[c.size - 6]) / c[c.size - 6] * 100 else 0.0
        if (last5chg > 3) signals.add("近5日涨幅${String.format("%.1f", last5chg)}%")

        val validCount = signals.size
        val verdict = when { validCount >= 5 -> "强烈看涨⭐"; validCount >= 3 -> "看涨(强势)"; validCount >= 2 -> "偏强"; else -> "不够强势" }
        val sl = String.format("%.2f", ma20 * 0.98)

        return buildString {
            appendLine("⚡ $name 强势策略分析")
            appendLine("  策略: 均线多头+MACD金叉+放量 → 强势股追击")
            appendLine("  当前: ${fmtPrice(last)} MA5:${fmtPrice(ma5)}")
            appendLine()
            appendLine("【信号检查 ${validCount}/6】")
            signals.forEach { appendLine("  ✅ $it") }
            appendLine()
            appendLine("【判定】$verdict")
            if (verdict.contains("看涨")) {
                val tp = String.format("%.2f", last * 1.08)
                appendLine("  🎯 目标位: $tp (+8%)")
                appendLine("  🛑 止损位: $sl (破MA20)")
                appendLine("  📊 盈亏比(R/R): ${String.format("%.2f", (last*1.08-last)/(last-sl.toDouble()))}")
            }
            appendLine()
            appendLine("💡 建议: ${if (verdict.contains("看涨")) "强势股可追，但仓位不宜超过20%，严格止损" else "不够强势，等待更强信号"}")
        }.trimEnd()
    }

    private fun reversalStrategy(name: String, raw: String, c: List<Double>, h: List<Double>, l: List<Double>, v: List<Double>, kline: List<Kline>, quote: StockQuote?): String {
        val last = c.last(); val rsiVal = rsi(c, 14); val boll = bollinger(c)
        val macdVal = macd(c); val kdjVal = kdj(h, l, c)
        val avgVol = v.takeLast(20).average(); val lastVol = v.lastOrNull() ?: 0.0; val volRatio = if (avgVol > 0) lastVol / avgVol else 1.0
        val supRes = supportResistance(h, l, c)

        val signals = mutableListOf<String>()
        if (rsiVal < 30) signals.add("RSI超卖(${String.format("%.0f", rsiVal)})")
        if (last <= boll.lower) signals.add("触及布林下轨")
        if (macdVal.bar > 0 && c.size >= 2) {
            val prevMacd = macd(c.dropLast(1))
            if (prevMacd.bar < 0) signals.add("MACD绿转红")
        }
        if (kdjVal.j != null && kdjVal.j < 0) signals.add("KDJ J<0 超卖")
        if (volRatio < 0.7) signals.add("缩量${String.format("%.0f", volRatio * 100)}%(抛压减弱)")
        if (c.size >= 5) { val chg = (last - c[c.size - 5]) / c[c.size - 5] * 100; if (chg < -5) signals.add("近5日跌幅${String.format("%.1f", chg)}%") }

        val validCount = signals.size
        val verdict = when { validCount >= 4 -> "强烈看涨⭐(超跌反弹)"; validCount >= 3 -> "看涨(反弹机会)"; validCount >= 2 -> "关注"; else -> "不满足反弹条件" }
        val nearestSup = supRes.first.maxOrNull()
        val sl = if (nearestSup != null) String.format("%.2f", nearestSup * 0.97) else String.format("%.2f", last * 0.95)
        val tp = if (boll.mid > 0) String.format("%.2f", boll.mid) else String.format("%.2f", last * 1.05)

        return buildString {
            appendLine("🔄 $name 超跌反弹策略分析")
            appendLine("  策略: 超卖+底背离+缩量 → 反弹机会")
            appendLine("  当前: ${fmtPrice(last)} RSI:${String.format("%.0f", rsiVal)}")
            appendLine()
            appendLine("【信号检查 ${validCount}/6】")
            signals.forEach { appendLine("  ✅ $it") }
            appendLine()
            appendLine("【判定】$verdict")
            if (verdict.contains("看涨")) {
                appendLine("  🎯 目标位(布林中轨): $tp")
                appendLine("  🛑 止损位: $sl")
                val rr = if (last > 0 && sl.toDoubleOrNull() != null && tp.toDoubleOrNull() != null)
                    String.format("%.2f", (tp.toDouble() - last) / (last - sl.toDouble())) else "N/A"
                appendLine("  📊 盈亏比(R/R): $rr")
            }
            appendLine()
            appendLine("💡 建议: ${if (verdict.contains("看涨")) "超跌反弹机会，轻仓试探，反弹至目标位分批出" else "下跌未企稳，不急于抄底"}")
        }.trimEnd()
    }

    private suspend fun valueStrategy(name: String, raw: String, quote: StockQuote?): String {
        val sym = normalizeSymbol(raw)
        val finJson = fetchFundamental(sym); val finObj = if (finJson != null) parseJsonObj(finJson) else null
        val pe = jsonNum(finObj, "f100"); val pb = jsonNum(finObj, "f115"); val eps = jsonNum(finObj, "f162")
        val totalMcap = jsonNum(finObj, "f152"); val turnoverRate = jsonNum(finObj, "f117")

        val signals = mutableListOf<String>()
        if (pe != null && pe < 15) signals.add("PE偏低(${String.format("%.1f", pe)})")
        if (pb != null && pb < 1.5) signals.add("PB偏低(${String.format("%.1f", pb)})")
        if (turnoverRate != null && turnoverRate < 2) signals.add("换手率低(${String.format("%.1f", turnoverRate)}%)")
        if (eps != null && eps > 0.5) signals.add("EPS良好(${String.format("%.2f", eps)})")
        if (totalMcap != null && totalMcap > 50_000_000_000L) signals.add("大市值(蓝筹)")

        val validCount = signals.size
        val verdict = when { validCount >= 4 -> "优质价值股"; validCount >= 3 -> "价值合理"; validCount >= 2 -> "关注"; else -> "一般" }

        return buildString {
            appendLine("💎 $name 价值策略分析")
            appendLine("  策略: 低PE/PB+稳定基本面 → 价值投资")
            appendLine()
            appendLine("【信号检查 ${validCount}/5】")
            signals.forEach { appendLine("  ✅ $it") }
            appendLine()
            appendLine("【判定】$verdict")
            appendLine()
            appendLine("💡 建议: ${if (validCount >= 3) "基本面良好，适合中长期持有，配合技术面找入场点" else "估值一般，建议结合其他策略"}")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  风险评估 (止盈止损)
    // ═══════════════════════════════════════════════════════

    private suspend fun doRisk(raw: String): String {
        val sym = normalizeSymbol(raw)
        val kline = fetchDailyKline(sym, 120) ?: return "K线数据获取失败"
        val c = kline.map { it.close }; val h = kline.map { it.high }; val l = kline.map { it.low }; val v = kline.map { it.volume }
        val last = c.last(); val name = getQuoteName(sym)
        val quote = fetchQuote(sym)
        val prevClose = quote?.prevClose ?: last
        val changePct = if (prevClose > 0) (last - prevClose) / prevClose * 100 else 0.0

        val atrVal = calcATR(h, l, c, 14)
        val atrPct = if (last > 0) atrVal / last * 100 else 0.0
        val boll = bollinger(c)
        val supRes = supportResistance(h, l, c)
        val ma20 = ma(c, 20).lastOrNull() ?: 0.0
        val rsiVal = rsi(c, 14)
        val ma5 = ma(c, 5).lastOrNull() ?: 0.0
        val ma10 = ma(c, 10).lastOrNull() ?: 0.0

        val nearestSup = supRes.first.maxOrNull()
        val nearestRes = supRes.second.minOrNull()

        val sl1 = last - 1 * atrVal  // 保守止损: 1ATR
        val sl2 = last - 2 * atrVal  // 标准止损: 2ATR
        val sl3 = if (nearestSup != null) nearestSup * 0.99 else sl2  // 支撑下方止损

        val tp1 = last + 1 * atrVal
        val tp2 = last + 2 * atrVal
        val tp3 = if (nearestRes != null) nearestRes * 1.01 else last + 3 * atrVal

        val rr1 = if (last > sl1) (tp1 - last) / (last - sl1) else 0.0
        val rr2 = if (last > sl2) (tp2 - last) / (last - sl2) else 0.0
        val rr3 = if (nearestSup != null && last > nearestSup && nearestRes != null) (nearestRes - last) / (last - nearestSup) else 0.0

        val recommendedSl = when {
            sl3 > 0 && (last - sl3) / last * 100 < 5 -> sl3
            atrPct < 3 -> sl2
            else -> sl1
        }
        val recommendedTp = when {
            nearestRes != null && (nearestRes - last) / last * 100 < 20 -> nearestRes
            else -> tp2
        }
        val recommendedRr = if (last > recommendedSl) (recommendedTp - last) / (last - recommendedSl) else 0.0

        val riskLevel = when {
            atrPct > 4 -> "高风险"; atrPct > 2.5 -> "中风险"; else -> "低风险"
        }
        val positionSize = when {
            atrPct > 4 -> "≤10%"; atrPct > 2.5 -> "≤20%"; else -> "≤30%"
        }

        return buildString {
            appendLine("⚠️ $name 风险评估与止盈止损")
            appendLine("  当前: ${fmtPrice(last)} (${String.format("%+.2f", changePct)}%)")
            appendLine()
            appendLine("【波动率分析】")
            appendLine("  ATR(14): ${String.format("%.2f", atrVal)} → 日波动${String.format("%.1f", atrPct)}%")
            appendLine("  风险等级: $riskLevel")
            appendLine("  建议仓位: 总资金$positionSize")
            appendLine()
            appendLine("【技术位】")
            appendLine("  支撑: ${supRes.first.joinToString(", ") { fmtPrice(it) }}")
            appendLine("  阻力: ${supRes.second.joinToString(", ") { fmtPrice(it) }}")
            appendLine("  MA5:${fmtPrice(ma5)} MA10:${fmtPrice(ma10)} MA20:${fmtPrice(ma20)}")
            appendLine()
            appendLine("【止损方案】")
            appendLine("  保守(1ATR): ${fmtPrice(sl1)} (亏损${String.format("%.1f", (last-sl1)/last*100)}%)")
            appendLine("  标准(2ATR): ${fmtPrice(sl2)} (亏损${String.format("%.1f", (last-sl2)/last*100)}%)")
            appendLine("  技术位: ${fmtPrice(sl3)} (支撑下方)")
            appendLine()
            appendLine("【止盈方案】")
            appendLine("  保守(1ATR): ${fmtPrice(tp1)} (+${String.format("%.1f", (tp1-last)/last*100)}%)")
            appendLine("  标准(2ATR): ${fmtPrice(tp2)} (+${String.format("%.1f", (tp2-last)/last*100)}%)")
            appendLine("  技术位: ${fmtPrice(tp3)} (阻力附近)")
            appendLine()
            appendLine("【盈亏比(R/R)】")
            appendLine("  1ATR止损/1ATR止盈: ${String.format("%.2f", rr1)}")
            appendLine("  2ATR止损/2ATR止盈: ${String.format("%.2f", rr2)}")
            appendLine("  技术位止损/阻力止盈: ${String.format("%.2f", rr3)}")
            appendLine()
            appendLine("【推荐方案】🛑止损${fmtPrice(recommendedSl)} 🎯止盈${fmtPrice(recommendedTp)} R/R=${String.format("%.2f", recommendedRr)} ${if (recommendedRr >= 2) "⭐ 优质交易" else if (recommendedRr >= 1) "✓ 可接受" else "⚠ R/R过低"}")
            appendLine()
            appendLine("💡 盈亏比>2才是优质交易，<1不建议入场")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  五关论证 (来自 astock-analyst)
    // ═══════════════════════════════════════════════════════

    private data class AsymmetryResult(val ratio: Double, val verdict: String)

    private fun calcAsymmetryRatio(kline: List<Kline>, period: Int = 60): AsymmetryResult {
        val recent = kline.takeLast(period)
        var upVol = 0.0; var dnVol = 0.0
        for (k in recent) {
            if (k.close > k.open) upVol += k.volume
            else if (k.close < k.open) dnVol += k.volume
        }
        val ratio = if (upVol > 0) dnVol / upVol else 999.0
        val verdict = when { ratio > 1.5 -> "强烈出货"; ratio > 1.2 -> "出货特征"; ratio > 1.0 -> "偏空"; ratio < 0.8 -> "买盘强势"; else -> "正常" }
        return AsymmetryResult(ratio, verdict)
    }

    private data class EVResult(val ev: Double, val upProb: Double, val avgUp: Double, val avgDn: Double, val verdict: String)

    private fun calcExpectedValue(kline: List<Kline>, period: Int = 60): EVResult {
        val recent = kline.takeLast(period); val upR = mutableListOf<Double>(); val dnR = mutableListOf<Double>()
        for (k in recent) { if (k.open <= 0) continue; val r = (k.close - k.open) / k.open * 100; if (r > 0) upR.add(r) else dnR.add(r) }
        val t = upR.size + dnR.size; val upP = if (t > 0) upR.size.toDouble() / t else 0.5
        val avgU = if (upR.isNotEmpty()) upR.average() else 0.0; val avgD = if (dnR.isNotEmpty()) dnR.average() else 0.0
        val ev = upP * avgU + (1 - upP) * avgD
        val verdict = when { ev > 0.5 -> "优秀"; ev > 0 -> "合理"; ev > -0.5 -> "偏低"; else -> "不利" }
        return EVResult(ev, upP, avgU, avgD, verdict)
    }

    private data class OBVResult(val direction: String, val verdict: String)

    private fun calcOBV(kline: List<Kline>): OBVResult {
        if (kline.size < 11) return OBVResult("未知", "数据不足")
        var obv = 0.0; var prev = kline.first().close; val obvList = mutableListOf<Double>()
        for (i in 1 until kline.size) { val k = kline[i]; if (k.close > prev) obv += k.volume else if (k.close < prev) obv -= k.volume; obvList.add(obv); prev = k.close }
        if (obvList.isEmpty()) return OBVResult("未知", "数据不足")
        val cur = obvList.last(); val past = if (obvList.size >= 10) obvList[obvList.size - 10] else obvList.first()
        val dir = if (cur > past) "上行" else "下行"
        val v = when { dir == "上行" -> "资金净流入"; dir == "下行" && kline.last().close > kline[kline.size - 11].close -> "价涨OBV降 ⚠背离"; else -> "资金流出" }
        return OBVResult(dir, v)
    }

    private suspend fun doFivegate(raw: String): String {
        val sym = normalizeSymbol(raw)
        val kline = fetchDailyKline(sym, 120) ?: return "K线数据获取失败"
        if (kline.size < 40) return "数据不足(需至少40个交易日)"
        val name = getQuoteName(sym)
        val c = kline.map { it.close }; val h = kline.map { it.high }; val l = kline.map { it.low }; val v = kline.map { it.volume }
        val last = c.last(); val opens = kline.map { it.open }

        val ma5v = ma(c, 5).lastOrNull() ?: 0.0; val ma10v = ma(c, 10).lastOrNull() ?: 0.0
        val ma20v = ma(c, 20).lastOrNull() ?: 0.0; val ma60v = if (c.size >= 60) ma(c, 60).lastOrNull() else null

        val macdVal = macd(c); val rsiVal = rsi(c, 14); val boll = bollinger(c)
        val kdjVal = kdj(h, l, c); val supRes = supportResistance(h, l, c)
        val asym = calcAsymmetryRatio(kline); val ev = calcExpectedValue(kline); val obv = calcOBV(kline)

        // 近3月涨幅 (~60个交易日)
        val last3m = if (c.size >= 60) (last - c[c.size - 60]) / c[c.size - 60] * 100 else null
        // 20日最高价
        val high20 = c.takeLast(20).maxOrNull() ?: 0.0
        // 日均成交量
        val avgVol20 = v.takeLast(20).average(); val lastVol = v.lastOrNull() ?: 0.0
        val volRatio = if (avgVol20 > 0) lastVol / avgVol20 else 1.0
        // 最大回撤(60日)
        val maxDd = run { var peak = c.first(); var dd = 0.0; for (p in c) { if (p > peak) peak = p; dd = maxOf(dd, (peak - p) / peak * 100) }; dd }

        val gates = mutableListOf<String>()
        var passed = 0; var totalScore = 0

        // ════════════ 第一关: 硬门槛 ════════════
        gates.add("第一关: 硬门槛")
        val gate1checks = mutableListOf<Pair<String, Boolean>>()

        val chk3m = last3m != null && last3m > 0
        gate1checks.add("近3月涨幅${if(last3m!=null) String.format("%+.1f",last3m)+"%" else "N/A"}" to chk3m)

        val chkMa60 = ma60v != null && last > ma60v
        gate1checks.add("股价vs MA60: ${if(ma60v!=null) fmtPrice(last)+" / "+fmtPrice(ma60v)+" → "+(if(chkMa60)"在上方✅" else "在下方❌") else "MA60数据不足"} " to chkMa60)

        val chkAsym = asym.ratio < 1.2
        gate1checks.add("涨跌量不对称比 ${String.format("%.2f", asym.ratio)} → ${asym.verdict}" to chkAsym)

        val chkOBV = obv.direction == "上行"
        gate1checks.add("OBV ${obv.direction} → ${obv.verdict}" to chkOBV)

        for ((desc, ok) in gate1checks) {
            gates.add("  ${if(ok)"✅" else "❌"} $desc")
            if (ok) passed++
        }
        val gate1Pass = gate1checks.all { it.second }

        // ════════════ 第二关: 加分评分 ════════════
        val gate2checks = mutableListOf<Pair<String, Boolean>>()
        val chkMacd = macdVal.bar > 0 && macdVal.dif > macdVal.dea
        gate2checks.add("MACD金叉" to chkMacd)

        val chkRsi = rsiVal in 40.0..70.0
        gate2checks.add("RSI ${String.format("%.0f", rsiVal)} → ${if(rsiVal in 40.0..70.0)"健康区间" else if(rsiVal<40)"偏弱" else "超买"}" to chkRsi)

        val chkBreak = last >= high20
        gate2checks.add("突破近20日高价" to chkBreak)

        val chkVol = volRatio > 1.5
        gate2checks.add("放量>1.5x(量比${String.format("%.1f", volRatio)})" to chkVol)

        gates.add("\n第二关: 加分评分 (通过${gate2checks.count{it.second}}/4)")
        for ((desc, ok) in gate2checks) {
            gates.add("  ${if(ok)"✅" else "⬜"} $desc ${if(ok)"+5" else ""}")
            if (ok) totalScore += 5
        }

        // ════════════ 第三关: 期望值 ════════════
        val gate3Pass = ev.ev > 0
        gates.add("\n第三关: 期望值计算")
        gates.add("  期望值 EV = ${String.format("%+.4f", ev.ev)} → ${ev.verdict} ${if(gate3Pass)"✅通过" else "❌不通过"}")
        gates.add("  涨概率${String.format("%.0f", ev.upProb * 100)}% 均涨${String.format("%+.2f", ev.avgUp)}% 均跌${String.format("%.2f", ev.avgDn)}%")

        // ════════════ 第四关: 趋势深度 ════════════
        gates.add("\n第四关: 趋势深度分析")
        val trendSeg = when {
            listOf(ma5v, ma10v, ma20v).sortedDescending() == listOf(ma5v, ma10v, ma20v) -> "多头排列(MA5>MA10>MA20)"
            listOf(ma5v, ma10v, ma20v).sorted() == listOf(ma5v, ma10v, ma20v) -> "空头排列(MA5<MA10<MA20)"
            else -> "均线交叉缠绕"
        }
        gates.add("  走势分段: $trendSeg")
        val bandPos = when { last >= boll.upper -> "上轨上方(超买)"; last >= boll.mid -> "中轨上方"; else -> "中轨下方" }
        gates.add("  布林位置: $bandPos")
        if (ma60v != null) gates.add("  各均线: MA5=${fmtPrice(ma5v)} MA10=${fmtPrice(ma10v)} MA20=${fmtPrice(ma20v)} MA60=${fmtPrice(ma60v)}")
        gates.add("  60日最大回撤: ${String.format("%.1f", maxDd)}%")
        val sup = supRes.first
        val res = supRes.second
        if (sup.isNotEmpty()) gates.add("  支撑位: ${sup.joinToString(", ") { fmtPrice(it) }}")
        if (res.isNotEmpty()) gates.add("  阻力位: ${res.joinToString(", ") { fmtPrice(it) }}")

        // ════════════ 第五关: 风险点 ════════════
        gates.add("\n第五关: 风险点 (至少3条)")
        val risks = mutableListOf<String>()
        if (ma60v != null && last > ma60v * 1.3) risks.add("股价远高于MA60(${fmtPrice(ma60v)})，回调风险大")
        if (volRatio < 0.5) risks.add("量能萎缩(量比${String.format("%.1f", volRatio)})，流动性差")
        if (rsiVal > 70) risks.add("RSI超买，短期回调概率高")
        if (rsiVal < 30) risks.add("RSI超卖，但熊市不言底")
        if (maxDd > 30) risks.add("60日最大回撤${String.format("%.1f", maxDd)}%，波动大")
        if (asym.ratio > 1.2) risks.add("不对称比${String.format("%.2f", asym.ratio)}，有出货迹象")
        if (macdVal.bar < 0) risks.add("MACD绿柱，空头占优")
        if (last > boll.upper) risks.add("价格触及布林上轨，注意回调")
        if (kdjVal.j != null && kdjVal.j > 100) risks.add("KDJ的J值>100，短线超买")
        if (ma60v != null && last < ma60v * 0.9) risks.add("深跌需确认底部，左侧交易风险大")
        if (obv.direction == "下行") risks.add("OBV下行，资金流出")
        if (risks.size < 3) {
            risks.add("市场系统性风险(大盘走势不明)")
            risks.add("政策面/消息面不确定性")
        }
        risks.take(6).forEachIndexed { i, r -> gates.add("  ${i+1}. ⚠ $r") }

        // ════════════ 综合判定 ════════════
        val recommendation = when {
            gate1Pass && gate3Pass && totalScore >= 10 -> "⭐⭐⭐ 强烈推荐买入"
            gate1Pass && gate3Pass && totalScore >= 5 -> "⭐⭐ 推荐买入"
            gate1Pass && !gate3Pass -> "⚖ 硬门槛通过但期望值为负，谨慎观望"
            !gate1Pass && passed >= 2 -> "⚖ 部分硬门槛未通过，等待条件改善"
            else -> "❌ 不推荐，大多数条件不满足"
        }

        return buildString {
            appendLine("🏛 $name (${raw.uppercase()}) 五关论证")
            appendLine("  当前:${fmtPrice(last)} 数据:${kline.size}个交易日")
            appendLine()
            appendLine("╔════════════════════════════════════╗")
            appendLine("║  $recommendation")
            appendLine("╚════════════════════════════════════╝")
            appendLine()
            gates.forEach { appendLine(it) }
            appendLine()
            appendLine("【总结】硬门槛${if(gate1Pass)"✅全过" else "❌未全过($passed/4)"} | 评分+${totalScore} | 期望值${if(gate3Pass)"✅" else "❌"}${if(gate1Pass&&gate3Pass)" | ⭐可考虑入场" else " | 继续等待"}")
            appendLine("💡 配合 action=strategy 看具体策略, action=risk 看止损止盈")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  回测验证
    // ═══════════════════════════════════════════════════════

    private fun tradeNetPnl(entry: Double, exit: Double, slp: Double, fee: Double): Double {
        val buyPrice = entry * (1.0 + slp / 100)
        val sellPrice = exit * (1.0 - slp / 100)
        val buyFee = buyPrice * fee / 100
        val sellFee = sellPrice * fee / 100
        return (sellPrice - buyPrice - buyFee - sellFee) / buyPrice * 100
    }

    private data class BacktestTrade(val entryDate: String, val entryPrice: Double, val exitDate: String, val exitPrice: Double, val pnlPct: Double, val win: Boolean)

    private suspend fun doBacktest(raw: String, sn: String, count: Int, slp: Double = 0.1, fee: Double = 0.03): String {
        val sym = normalizeSymbol(raw)
        val kline = fetchDailyKline(sym, count.coerceIn(60, 500)) ?: return "K线数据获取失败"
        if (kline.size < 40) return "数据不足，至少需40个交易日"
        val name = getQuoteName(sym)

        val trades = mutableListOf<BacktestTrade>()
        val c = kline.map { it.close }; val h = kline.map { it.high }; val l = kline.map { it.low }; val v = kline.map { it.volume }
        val dates = kline.map { it.date }

        when (sn) {
            "breakout" -> {
                var i = 20
                while (i < c.size - 5) {
                    val sliceC = c.subList(0, i + 1); val sliceV = v.subList(0, i + 1)
                    val ma20v = ma(sliceC, 20).lastOrNull() ?: 0.0; val ma5v = ma(sliceC, 5).lastOrNull() ?: 0.0
                    if (c[i] > ma20v * 1.04 && c[i] > c[i - 1] && v[i] > (ma(sliceV, 5).lastOrNull() ?: 0.0) * 1.5) {
                        val entry = c[i]; val entryDate = dates[i]
                        val exitIdx = min(i + 20, c.size - 1)
                        val exit = c[exitIdx]; val exitDate = dates[exitIdx]
                        val pnl = tradeNetPnl(entry, exit, slp, fee)
                        trades.add(BacktestTrade(entryDate, entry, exitDate, exit, pnl, pnl >= 0))
                        i += 5
                    } else { i += 1 }
                }
            }
            "dip" -> {
                var i = 20
                while (i < c.size - 5) {
                    val sliceC = c.subList(0, i + 1); val sliceH = h.subList(0, i + 1); val sliceL = l.subList(0, i + 1)
                    val rsiVal = rsi(sliceC, 14); val boll = bollinger(sliceC)
                    if (rsiVal < 35 && c[i] <= boll.lower * 1.02) {
                        val entry = c[i]; val entryDate = dates[i]
                        val exitIdx = min(i + 20, c.size - 1)
                        val exit = c[exitIdx]; val exitDate = dates[exitIdx]
                        val pnl = tradeNetPnl(entry, exit, slp, fee)
                        trades.add(BacktestTrade(entryDate, entry, exitDate, exit, pnl, pnl >= 0))
                        i += 5
                    } else { i += 1 }
                }
            }
            "momentum" -> {
                var i = 30
                while (i < c.size - 5) {
                    val sliceC = c.subList(0, i + 1); val sliceH = h.subList(0, i + 1); val sliceL = l.subList(0, i + 1)
                    val ma5v = ma(sliceC, 5).lastOrNull() ?: 0.0; val ma10v = ma(sliceC, 10).lastOrNull() ?: 0.0; val ma20v = ma(sliceC, 20).lastOrNull() ?: 0.0
                    val macdVal = macd(sliceC); val rsiVal = rsi(sliceC, 14)
                    if (c[i] > ma5v && ma5v > ma10v && ma10v > ma20v && macdVal.bar > 0 && rsiVal in 50.0..75.0) {
                        val entry = c[i]; val entryDate = dates[i]
                        val exitIdx = min(i + 20, c.size - 1)
                        val exit = c[exitIdx]; val exitDate = dates[exitIdx]
                        val pnl = tradeNetPnl(entry, exit, slp, fee)
                        trades.add(BacktestTrade(entryDate, entry, exitDate, exit, pnl, pnl >= 0))
                        i += 5
                    } else { i += 1 }
                }
            }
            "reversal" -> {
                var i = 30
                while (i < c.size - 5) {
                    val sliceC = c.subList(0, i + 1); val sliceH = h.subList(0, i + 1); val sliceL = l.subList(0, i + 1)
                    val rsiVal = rsi(sliceC, 14); val macdVal = macd(sliceC); val boll = bollinger(sliceC)
                    if (rsiVal < 30 && c[i] <= boll.lower && macdVal.bar > -0.5) {
                        val entry = c[i]; val entryDate = dates[i]
                        val exitIdx = min(i + 20, c.size - 1)
                        val exit = c[exitIdx]; val exitDate = dates[exitIdx]
                        val pnl = tradeNetPnl(entry, exit, slp, fee)
                        trades.add(BacktestTrade(entryDate, entry, exitDate, exit, pnl, pnl >= 0))
                        i += 5
                    } else { i += 1 }
                }
            }
        }

        val totalTrades = trades.size
        val wins = trades.count { it.win }
        val losses = totalTrades - wins
        val winRate = if (totalTrades > 0) wins.toDouble() / totalTrades * 100 else 0.0
        val avgWin = if (wins > 0) trades.filter { it.win }.map { it.pnlPct }.average() else 0.0
        val avgLoss = if (losses > 0) trades.filter { !it.win }.map { it.pnlPct }.average() else 0.0
        val totalReturn = if (totalTrades > 0) trades.sumOf { it.pnlPct } / totalTrades else 0.0
        val profitFactor = if (avgLoss != 0.0) abs(avgWin / avgLoss) else if (avgWin > 0) 999.0 else 0.0
        val maxDrawdown = calcMaxDrawdown(trades.map { it.pnlPct })

        val strLabel = when (sn) { "breakout" -> "突破策略"; "dip" -> "低吸策略"; "momentum" -> "强势策略"; "reversal" -> "超跌反弹"; else -> sn }

        return buildString {
            appendLine("📊 $name $strLabel 回测报告")
            appendLine("  回测周期: ${dates.firstOrNull() ?: ""} ~ ${dates.lastOrNull() ?: ""} (${kline.size}个交易日)")
            appendLine()
            appendLine("【交易统计】")
            appendLine("  总交易次数: $totalTrades")
            appendLine("  盈利次数: $wins | 亏损次数: $losses")
            appendLine("  胜率: ${String.format("%.1f", winRate)}%")
            appendLine()
            appendLine("【盈亏统计】")
            appendLine("  平均盈利: ${String.format("%+.2f", avgWin)}%")
            appendLine("  平均亏损: ${String.format("%.2f", avgLoss)}%")
            appendLine("  平均单笔: ${String.format("%+.2f", totalReturn)}%")
            appendLine("  盈亏比(PF): ${String.format("%.2f", profitFactor)} ${if (profitFactor >= 2) "⭐" else if (profitFactor >= 1) "✓" else "⚠"}")
            appendLine("  最大回撤: ${String.format("%.1f", maxDrawdown)}%")
            val sharpe = if (trades.size > 1) { 
                val avgRet = trades.map { it.pnlPct }.average()
                val stdRet = sqrt(trades.map { maxOf(0.0, (it.pnlPct - avgRet) * (it.pnlPct - avgRet)) }.average())
                if (stdRet > 0) avgRet / stdRet * sqrt(252.0) else 0.0
            } else 0.0
            appendLine("  夏普比率: ${String.format("%.2f", sharpe)} ${if (sharpe >= 1) "⭐" else if (sharpe >= 0.5) "✓" else ""}")
            appendLine("  成本设定: 滑点${slp}%+手续费${fee}%")
            appendLine()
            val grade = when {
                winRate >= 60 && profitFactor >= 2 -> "🔥 优秀策略，历史表现强劲"
                winRate >= 50 && profitFactor >= 1.5 -> "✅ 良好策略，可实盘参考"
                winRate >= 40 && profitFactor >= 1 -> "⚠️ 一般策略，需结合其他条件"
                else -> "❌ 该策略在此股票上历史表现不佳"
            }
            appendLine("【综合评价】$grade")
            if (totalTrades > 0) {
                appendLine()
                appendLine("【最近3笔交易】")
                trades.takeLast(3).forEach { t ->
                    appendLine("  ${if (t.win) "🟢" else "🔴"} ${t.entryDate}→${t.exitDate}  ${String.format("%+.2f", t.pnlPct)}%")
                }
            }
            appendLine()
            appendLine("💡 回测基于简化模型，未考虑滑点/手续费，实盘可能略有差异")
        }.trimEnd()
    }

    private fun calcMaxDrawdown(pnlList: List<Double>): Double {
        if (pnlList.isEmpty()) return 0.0
        var peak = 1.0; var maxDd = 0.0; var equity = 1.0
        for (pnl in pnlList) { equity *= (1.0 + pnl / 100); peak = max(peak, equity); maxDd = max(maxDd, (peak - equity) / peak * 100) }
        return maxDd
    }

    // ═══════════════════════════════════════════════════════
    //  技术指标
    // ═══════════════════════════════════════════════════════

    private data class Kline(val date: String, val open: Double, val close: Double, val high: Double, val low: Double, val volume: Double, val amount: Double?)
    private data class StockQuote(val name: String, val symbol: String, val current: Double?, val prevClose: Double?, val open: Double?, val high: Double?, val low: Double?, val volume: Double?, val amount: Double?, val time: String?)
    private data class NormalizedSymbol(val sina: String, val eastMoney: String, val display: String)
    private data class MACDResult(val dif: Double, val dea: Double, val bar: Double, val signal: String)
    private data class Bollinger(val upper: Double, val mid: Double, val lower: Double)
    private data class KDJResult(val k: Double?, val d: Double?, val j: Double?)

    private fun ma(data: List<Double>, period: Int): List<Double> {
        if (data.size < period) return emptyList()
        return (period - 1 until data.size).map { i -> data.subList(i - period + 1, i + 1).average() }
    }

    private fun calcATR(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int): Double {
        if (closes.size < period + 1) return 0.0
        val tr = mutableListOf<Double>()
        for (i in 1 until closes.size) {
            val hl = highs[i] - lows[i]
            val hc = abs(highs[i] - closes[i - 1])
            val lc = abs(lows[i] - closes[i - 1])
            tr.add(maxOf(hl, hc, lc))
        }
        val smoothed = wilderRma(tr, period)
        return smoothed.lastOrNull() ?: 0.0
    }

    private fun calcADX(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int): Double {
        if (closes.size < period * 2) return 50.0
        val trList = mutableListOf<Double>()
        val plusDMList = mutableListOf<Double>()
        val minusDMList = mutableListOf<Double>()
        for (i in 1 until closes.size) {
            val upMove = highs[i] - highs[i - 1]; val downMove = lows[i - 1] - lows[i]
            val plusDM = if (upMove > downMove && upMove > 0) upMove else 0.0
            val minusDM = if (downMove > upMove && downMove > 0) downMove else 0.0
            plusDMList.add(plusDM); minusDMList.add(minusDM)
            val tr = maxOf(highs[i] - lows[i], abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1]))
            trList.add(tr)
        }
        val smoothTR = wilderRma(trList, period)
        val smoothPDM = wilderRma(plusDMList, period)
        val smoothMDM = wilderRma(minusDMList, period)
        val dx = mutableListOf<Double>()
        for (i in period - 1 until smoothTR.size) {
            val diPlus = if (smoothTR[i] > 0) smoothPDM[i] / smoothTR[i] * 100 else 0.0
            val diMinus = if (smoothTR[i] > 0) smoothMDM[i] / smoothTR[i] * 100 else 0.0
            val diSum = diPlus + diMinus
            if (diSum > 0) dx.add(abs(diPlus - diMinus) / diSum * 100)
        }
        val smoothDx = wilderRma(dx, period)
        return smoothDx.lastOrNull() ?: 50.0
    }

    private fun calcMaSlope(data: List<Double>, period: Int): Double {
        if (data.size < period + 5) return 0.0
        val maVals = ma(data, period); if (maVals.size < 5) return 0.0
        val recent = maVals.takeLast(5); val half = recent.size / 2
        val firstHalf = recent.take(half).average(); val secondHalf = recent.drop(half).average()
        return if (firstHalf > 0) (secondHalf - firstHalf) / firstHalf else 0.0
    }

    private fun rsi(data: List<Double>, period: Int): Double {
        if (data.size <= period) return 50.0
        var gain = 0.0; var loss = 0.0
        for (i in data.size - period until data.size) {
            val diff = data[i] - data[i - 1]
            if (diff > 0) gain += diff else loss -= diff
        }
        val avgGain = gain / period; val avgLoss = loss / period
        if (avgLoss == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    private fun wilderRma(data: List<Double>, period: Int): List<Double> {
        if (data.isEmpty() || period < 1) return emptyList()
        val result = mutableListOf<Double>()
        result.add(data.first())
        for (i in 1 until data.size) {
            result.add((result[i - 1] * (period - 1) + data[i]) / period)
        }
        return result
    }

    private fun macd(data: List<Double>): MACDResult {
        if (data.isEmpty()) return MACDResult(0.0, 0.0, 0.0, "空头")
        var ema12 = data[0]; var ema26 = data[0]
        val difValues = mutableListOf<Double>()
        difValues.add(0.0)
        for (i in 1 until data.size) {
            ema12 = (data[i] - ema12) * 2.0 / 13 + ema12
            ema26 = (data[i] - ema26) * 2.0 / 27 + ema26
            difValues.add(ema12 - ema26)
        }
        val dif = difValues.last()
        val dea = ema(difValues, 9)
        val bar = dif - dea
        val signal = when { bar > 0 -> "多头"; else -> "空头" }
        return MACDResult(dif, dea, bar, signal)
    }

    private fun ema(data: List<Double>, period: Int): Double {
        if (data.isEmpty()) return 0.0
        val multiplier = 2.0 / (period + 1); var result = data.first()
        for (i in 1 until data.size) result = (data[i] - result) * multiplier + result
        return result
    }

    private fun bollinger(data: List<Double>, period: Int = 20): Bollinger {
        val recent = data.takeLast(period); val mid = recent.average()
        val variance = if (recent.size > 1) recent.map { (it - mid) * (it - mid) }.sum() / (recent.size - 1) else 0.0
        val std = sqrt(variance)
        return Bollinger(mid + 2 * std, mid, mid - 2 * std)
    }

    private fun kdj(highs: List<Double>, lows: List<Double>, closes: List<Double>): KDJResult {
        if (highs.size < 9) return KDJResult(null, null, null)
        var k = 50.0; var d = 50.0
        for (i in 0 until closes.size) {
            val start = maxOf(0, i - 8)
            val hh = highs.subList(start, i + 1).maxOrNull() ?: continue
            val ll = lows.subList(start, i + 1).minOrNull() ?: continue
            val rsv = if (hh != ll) (closes[i] - ll) / (hh - ll) * 100 else 50.0
            k = 2.0 / 3 * k + 1.0 / 3 * rsv
            d = 2.0 / 3 * d + 1.0 / 3 * k
        }
        val j = 3 * k - 2 * d
        return KDJResult(k.coerceIn(0.0, 100.0), d.coerceIn(0.0, 100.0), j)
    }

    private fun supportResistance(highs: List<Double>, lows: List<Double>, closes: List<Double>): Pair<List<Double>, List<Double>> {
        val recentHighs = highs.takeLast(60).distinct().sorted(); val recentLows = lows.takeLast(60).distinct().sorted()
        val current = closes.last()
        val supports = recentLows.filter { it < current && it > current * 0.85 }.distinct().sortedDescending().take(3)
        val resistances = recentHighs.filter { it > current && it < current * 1.15 }.distinct().take(3)
        return Pair(supports, resistances)
    }

    // ═══════════════════════════════════════════════════════
    //  策略参数优化
    // ═══════════════════════════════════════════════════════

    private suspend fun doOptimize(raw: String, sn: String, count: Int, paramName: String, start: Int, end: Int, step: Int): String {
        val sym = normalizeSymbol(raw)
        val name = getQuoteName(sym)
        val strLabel = when (sn) { "breakout" -> "突破策略"; "dip" -> "低吸策略"; "momentum" -> "强势策略"; "reversal" -> "超跌反弹"; else -> sn }
        val results = mutableListOf<Triple<Int, Double, Double>>()
        var current = start
        while (current <= end) {
            val bt = doBacktestInner(raw, sn, count, 0.1, 0.03) { trades, c, _, _, v, dates ->
                when (sn) {
                    "breakout" -> {
                        var i = 20
                        while (i < c.size - 5) {
                            val sliceC = c.subList(0, i + 1)
                            val mov = ma(sliceC, current)
                            if (c[i] > (mov.lastOrNull() ?: 0.0) * 1.04 && c[i] > c[i - 1]) {
                                val exitIdx = min(i + current / 5 * 5, c.size - 1)
                                trades.add(BacktestTrade(dates[i], c[i], dates[exitIdx], c[exitIdx], 0.0, false))
                                i += 5
                            } else i += 1
                        }
                    }
                    "dip" -> {
                        var i = 20
                        while (i < c.size - 5) {
                            val r = rsi(c.subList(0, i + 1), current)
                            if (r < 35) {
                                val exitIdx = min(i + current / 5 * 5, c.size - 1)
                                trades.add(BacktestTrade(dates[i], c[i], dates[exitIdx], c[exitIdx], 0.0, false))
                                i += 5
                            } else i += 1
                        }
                    }
                    "momentum" -> {
                        var i = 30
                        while (i < c.size - 5) {
                            val m = ma(c.subList(0, i + 1), current).lastOrNull() ?: 0.0
                            if (c[i] > m) {
                                val exitIdx = min(i + current / 5 * 5, c.size - 1)
                                trades.add(BacktestTrade(dates[i], c[i], dates[exitIdx], c[exitIdx], 0.0, false))
                                i += 5
                            } else i += 1
                        }
                    }
                    "reversal" -> {
                        var i = 30
                        while (i < c.size - 5) {
                            val r = rsi(c.subList(0, i + 1), 14)
                            if (r < current.toDouble()) {
                                val exitIdx = min(i + 20, c.size - 1)
                                trades.add(BacktestTrade(dates[i], c[i], dates[exitIdx], c[exitIdx], 0.0, false))
                                i += 5
                            } else i += 1
                        }
                    }
                }
            }
            val winRate = if (bt.isNotEmpty()) bt.count { it.pnlPct >= 0 }.toDouble() / bt.size * 100 else 0.0
            val avgRet = if (bt.isNotEmpty()) bt.map { it.pnlPct }.average() else 0.0
            results.add(Triple(current, winRate, avgRet))
            current += step
        }

        val bestWinRate = results.maxByOrNull { it.second }
        val bestAvgRet = results.maxByOrNull { it.third }
        val paramLabel = when (paramName) { "rsi_period" -> "RSI周期"; "ma_period" -> "MA周期"; "hold_days" -> "持有天数"; else -> paramName }

        return buildString {
            appendLine("🔧 $name $strLabel 参数优化 ($paramLabel)")
            appendLine()
            appendLine("【测试范围】${start}~${end} 步长${step} 共${results.size}组")
            appendLine()
            appendLine("    参数  胜率    平均收益")
            results.forEach { (p, wr, ar) ->
                val mark = if (p == bestWinRate?.first) "← 最佳胜率" else if (p == bestAvgRet?.first) "← 最佳收益" else ""
                appendLine("    $p    ${String.format("%5.1f", wr)}%  ${String.format("%+.2f", ar)}%  $mark")
            }
            appendLine()
            if (bestWinRate != null) appendLine("🏆 最佳胜率: ${paramLabel}=${bestWinRate.first}  ${String.format("%.1f", bestWinRate.second)}%")
            if (bestAvgRet != null) appendLine("🏆 最佳收益: ${paramLabel}=${bestAvgRet.first}  ${String.format("%+.2f", bestAvgRet.third)}%")
            appendLine()
            appendLine("💡 用最佳参数配合 action=backtest 做完整回测")
        }.trimEnd()
    }

    private suspend fun doBacktestInner(raw: String, sn: String, count: Int, slp: Double, fee: Double,
                                         strategy: (MutableList<BacktestTrade>, List<Double>, List<Double>, List<Double>, List<Double>, List<String>) -> Unit): List<BacktestTrade> {
        val sym = normalizeSymbol(raw)
        val kline = fetchDailyKline(sym, count.coerceIn(60, 500)) ?: return emptyList()
        if (kline.size < 40) return emptyList()
        val trades = mutableListOf<BacktestTrade>()
        val c = kline.map { it.close }; val h = kline.map { it.high }; val l = kline.map { it.low }; val v = kline.map { it.volume }
        val dates = kline.map { it.date }
        strategy(trades, c, h, l, v, dates)
        return trades.map { it.copy(pnlPct = tradeNetPnl(it.entryPrice, it.exitPrice, slp, fee)) }
    }

    // ═══════════════════════════════════════════════════════
    //  行业轮动追踪
    // ═══════════════════════════════════════════════════════

    private suspend fun doRotation(): String {
        val types = mapOf("行业板块" to "2", "概念板块" to "3", "地域板块" to "4")
        val allSectors = mutableListOf<Pair<String, SectorBrief>>()

        val hosts = listOf("push2.eastmoney.com", "push.eastmoney.com")
        val probeHost = hosts.firstOrNull { host ->
            val url = "https://$host/api/qt/clist/get?pn=1&pz=3&po=1&np=1" +
                    "&fltt=2&invt=2&fs=m:90+t:2&fid=f3&fields=f14,f3"
            val body = httpGet(url, "https://quote.eastmoney.com")
            body != null && jsonArr(jsonObj(parseJsonObj(body), "data"), "diff") != null
        } ?: return "板块数据获取失败(所有数据源均不可达)"

        for ((typeName, typeId) in types) {
            val url = "https://$probeHost/api/qt/clist/get?pn=1&pz=50&po=1&np=1" +
                    "&fltt=2&invt=2&fs=m:90+t:$typeId&fid=f3&fields=f12,f14,f3,f62"
            val body = httpGet(url, "https://quote.eastmoney.com") ?: continue
            val data = jsonObj(parseJsonObj(body), "data") ?: continue
            val arr = jsonArr(data, "diff") ?: continue
            for (i in 0 until arr.size()) {
                val item = arr[i]?.takeIf { !it.isJsonNull }?.asJsonObject ?: continue
                val name = jsonStr(item, "f14") ?: continue
                val chg = jsonNum(item, "f3") ?: 0.0
                val inflow = jsonNum(item, "f62") ?: 0.0
                allSectors.add(typeName to SectorBrief(name, chg, inflow))
            }
        }

        if (allSectors.isEmpty()) return "板块数据获取失败，请稍后重试"

        val top5 = allSectors.sortedByDescending { it.second.changePct }.take(5)
        val bottom5 = allSectors.sortedBy { it.second.changePct }.take(5)
        val topInflow = allSectors.sortedByDescending { it.second.inflow }.take(5)
        val topOutflow = allSectors.sortedBy { it.second.inflow }.take(5)

        return buildString {
            appendLine("🔄 行业轮动追踪")
            if (top5.isNotEmpty()) {
                appendLine()
                appendLine("【涨幅领跑 TOP5】")
                for (s in top5) {
                    val dir = if (s.second.changePct > 3) "🔥" else if (s.second.changePct > 1) "📈" else "➡"
                    appendLine("  $dir ${s.first} ${s.second.name} ${fmtPct(s.second.changePct)} 主力${fmtFlow(s.second.inflow)}")
                }
            }
            if (bottom5.isNotEmpty()) {
                appendLine()
                appendLine("【跌幅垫底 TOP5】")
                for (s in bottom5) {
                    val dir = if (s.second.changePct < -3) "❄" else if (s.second.changePct < -1) "📉" else "➡"
                    appendLine("  $dir ${s.first} ${s.second.name} ${fmtPct(s.second.changePct)} 主力${fmtFlow(s.second.inflow)}")
                }
            }
            if (topInflow.isNotEmpty()) {
                appendLine()
                appendLine("【主力资金流入 TOP5】")
                for (s in topInflow) {
                    appendLine("  💰 ${s.first} ${s.second.name} 主力${fmtFlow(s.second.inflow)} ${fmtPct(s.second.changePct)}")
                }
            }
            if (topOutflow.isNotEmpty()) {
                appendLine()
                appendLine("【主力资金流出 TOP5】")
                for (s in topOutflow) {
                    appendLine("  💸 ${s.first} ${s.second.name} 主力${fmtFlow(s.second.inflow)} ${fmtPct(s.second.changePct)}")
                }
            }
            appendLine()
            appendLine("【轮动研判】")
            if (allSectors.isNotEmpty()) {
                val gainers = top5.map { it.second.name to it.second.changePct }
                val losers = bottom5.map { it.second.name to it.second.changePct }
                val topGainer = gainers.maxByOrNull { it.second }!!
                val topLoser = losers.minByOrNull { it.second }!!
                appendLine("  📌 领涨板块: ${topGainer.first} (${fmtPct(topGainer.second)})")
                appendLine("  📌 领跌板块: ${topLoser.first} (${fmtPct(topLoser.second)})")
                val spread = topGainer.second - topLoser.second
                appendLine("  📌 板块分化度: ${fmtPct(spread)} ${if (spread > 8) "⚠ 分化剧烈" else if (spread > 4) "✓ 适度分化" else "➡ 分化不明显"}")
                val topIn = topInflow.first()
                val topOut = topOutflow.first()
                appendLine("  📌 资金流向: ${topIn.first}${topIn.second.name} +${fmtFlow(topIn.second.inflow)} ← ${topOut.first}${topOut.second.name} ${fmtFlow(topOut.second.inflow)}")
            }
            appendLine()
            appendLine("💡 配合 action=sector 查看板块成分股，action=flow 查看个股资金流")
        }.trimEnd()
    }

    private data class SectorBrief(val name: String, val changePct: Double, val inflow: Double)
    private fun fmtPct(v: Double): String = String.format("%+.2f%%", v)
    private fun fmtFlow(v: Double): String = if (abs(v) >= 1e8) String.format("%+.2f亿", v / 1e8) else if (abs(v) >= 1e4) String.format("%+.2f万", v / 1e4) else String.format("%+.0f", v)

    // ═══════════════════════════════════════════════════════
    //  数据获取
    // ═══════════════════════════════════════════════════════

    private val klineCache = mutableMapOf<String, Pair<List<Kline>, Long>>()

    private suspend fun fetchDailyKline(sym: NormalizedSymbol, count: Int): List<Kline>? {
        val key = "${sym.display}_daily_$count"
        klineCache[key]?.let { if (System.currentTimeMillis() - it.second < 60000) return it.first }
        val data = fetchKlineEastMoney(sym, "daily", count, "forward")
            ?: fetchKlineSina(sym, count)
        if (data != null) klineCache[key] = Pair(data, System.currentTimeMillis())
        return data
    }

    private suspend fun fetchKlineRaw(sym: NormalizedSymbol, period: String, count: Int): List<Kline>? {
        val key = "${sym.display}_${period}_$count"
        klineCache[key]?.let { if (System.currentTimeMillis() - it.second < 60000) return it.first }
        val data = fetchKlineEastMoney(sym, period, count, "forward")
        if (data != null) klineCache[key] = Pair(data, System.currentTimeMillis())
        return data
    }

    private suspend fun fetchKlineSina(sym: NormalizedSymbol, count: Int): List<Kline>? {
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?symbol=${sym.sina}&scale=240&ma=no&datalen=$count"
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

    private suspend fun fetchKlineEastMoney(sym: NormalizedSymbol, period: String, count: Int, adjust: String): List<Kline>? {
        val klt = when (period) { "weekly" -> "102"; "monthly" -> "103"; "60min" -> "60"; "30min" -> "30"; "15min" -> "15"; "5min" -> "5"; "1min" -> "1"; else -> "101" }
        val fqt = when (adjust) { "backward" -> "2"; "none" -> "0"; else -> "1" }
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=${sym.eastMoney}" +
                "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57&klt=$klt&fqt=$fqt&end=20500101&lmt=$count"
        val body = httpGet(url, "https://quote.eastmoney.com") ?: return null
        val root = parseJsonObj(body) ?: return null; val data = jsonObj(root, "data") ?: return null
        val klinesJson = jsonArr(data, "klines") ?: return null; val result = mutableListOf<Kline>()
        for (i in 0 until klinesJson.size()) {
            val parts = klinesJson[i]?.asString?.split(",") ?: continue
            if (parts.size >= 6) {
                val open = parts[1].toDoubleOrNull() ?: 0.0; val close = parts[2].toDoubleOrNull() ?: 0.0
                val high = parts[3].toDoubleOrNull() ?: 0.0; val low = parts[4].toDoubleOrNull() ?: 0.0
                val vol = parts[5].toDoubleOrNull() ?: 0.0
                if (high < low || close <= 0) continue
                result.add(Kline(parts[0], open, close, high, low, vol, parts.getOrNull(6)?.toDoubleOrNull()))
            }
        }
        return if (result.isEmpty()) null else result
    }

    private suspend fun fetchFundamental(sym: NormalizedSymbol): String? {
        val fields = listOf("f57","f58","f100","f115","f117","f152","f162","f163","f164","f167","f168","f169")
        for (attemptFields in listOf(fields, listOf("f57","f58","f100","f115","f162","f152","f117"))) {
            val url = "https://push2.eastmoney.com/api/qt/stock/get?secid=${sym.eastMoney}&fields=${attemptFields.joinToString(",")}"
            val body = httpGet(url, "https://quote.eastmoney.com") ?: continue
            val data = jsonObj(parseJsonObj(body), "data") ?: continue
            if (data.get("f57")?.isJsonNull == false) return body
        }
        // Fallback: Tencent qt.gtimg.cn
        val tBody = httpGet("https://qt.gtimg.cn/q=${sym.sina}") ?: return null
        val s = tBody.indexOf('"'); val e = tBody.lastIndexOf('"'); val q = if (s < 0 || e <= s) null else tBody.substring(s + 1, e)
        val parts = q?.split("~") ?: return null; if (parts.size < 47) return null
        return try {
            val obj = com.google.gson.JsonObject()
            obj.addProperty("f57", sym.sina); obj.addProperty("f58", parts.getOrElse(1) { "" })
            parts.getOrNull(39)?.toDoubleOrNull()?.let { obj.addProperty("f100", it) }
            parts.getOrNull(44)?.toDoubleOrNull()?.let { obj.addProperty("f152", it * 10000) }
            parts.getOrNull(45)?.toDoubleOrNull()?.let { obj.addProperty("f167", it * 10000) }
            parts.getOrNull(46)?.toDoubleOrNull()?.let { obj.addProperty("f117", it) }
            obj.toString()
        } catch (_: Exception) { null }
    }

    private suspend fun fetchQuote(sym: NormalizedSymbol): StockQuote? {
        val url = "https://push2.eastmoney.com/api/qt/stock/get?secid=${sym.eastMoney}&fields=f43,f44,f45,f46,f47,f48,f50,f57,f58,f170,f171"
        val body = httpGet(url, "https://quote.eastmoney.com") ?: return null
        val data = jsonObj(parseJsonObj(body), "data") ?: return null
        if (data.get("f57")?.isJsonNull != false) return null
        return StockQuote(jsonStr(data, "f58") ?: "", sym.display, jsonNum(data, "f43"), jsonNum(data, "f47"),
            jsonNum(data, "f46"), jsonNum(data, "f44"), jsonNum(data, "f45"), jsonNum(data, "f48"), jsonNum(data, "f50"), null)
    }

    private suspend fun getQuoteName(sym: NormalizedSymbol): String = fetchQuote(sym)?.name ?: sym.display.uppercase()

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

    // ═══════════════════════════════════════════════════════
    //  HTTP + JSON 工具
    // ═══════════════════════════════════════════════════════

    private var lastReqTime = 0L

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
                val resp = client.newCall(rb.build()).execute(); val body = resp.body?.string(); resp.close()
                if (!resp.isSuccessful) {
                    Log.w("QuantTool", "httpGet ${url.take(100)} → HTTP ${resp.code}")
                    if (attempt < maxRetries) { delay(500L * (1 shl attempt)); continue } else return@withContext null
                }
                return@withContext body
            } catch (e: IOException) {
                if (attempt < maxRetries) delay(500L * (1 shl attempt))
            }
        }
        null
    }

    private suspend fun rateLimit() {
        val now = System.currentTimeMillis(); val elapsed = now - lastReqTime
        if (elapsed < 150) kotlinx.coroutines.delay(150 - elapsed); lastReqTime = System.currentTimeMillis()
    }

    private fun parseJsonObj(json: String): JsonObject? = try { JsonParser.parseString(json)?.asJsonObject } catch (_: Exception) { null }
    private fun jsonObj(obj: JsonObject?, key: String): JsonObject? = obj?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun jsonArr(obj: JsonObject?, key: String) = obj?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun jsonStr(obj: JsonObject?, key: String): String? = obj?.get(key)?.takeIf { !it.isJsonNull }?.asString
    private fun jsonNum(obj: JsonObject?, key: String): Double? = obj?.get(key)?.takeIf { !it.isJsonNull }?.asDouble

    private fun fmtPrice(v: Double?): String = v?.let { String.format("%.2f", it) } ?: "N/A"
}
