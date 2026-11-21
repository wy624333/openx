package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.abs

class SignalTracker : AgentTool {
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

    override val name = "signal_tracker"
    override val description = "AI信号历史追踪。记录AI的看涨/看跌信号，追踪后续走势验证准确率，让AI自我优化"
    override val parameters = buildToolParameters(
        ToolParameter("action", ParameterType.STRING, "操作类型\n- record: 记录一个信号\n- review: 查看已到期的信号及实际走势\n- stats: 信号统计(总准确率/各类型准确率)", enumValues = listOf("record", "review", "stats")),
        ToolParameter("symbol", ParameterType.STRING, "股票代码，如 sh600000", required = false),
        ToolParameter("direction", ParameterType.STRING, "信号方向: bullish(看涨) / bearish(看跌) / neutral(中性/观望)", required = false),
        ToolParameter("signal_type", ParameterType.STRING, "信号类型: technical(技术面) / fundamental(基本面) / mixed(综合)", required = false),
        ToolParameter("price", ParameterType.NUMBER, "信号发出时的股价", required = false),
        ToolParameter("reason", ParameterType.STRING, "信号理由简述", required = false),
        ToolParameter("target", ParameterType.NUMBER, "目标价(选填)", required = false),
        ToolParameter("stop", ParameterType.NUMBER, "止损价(选填)", required = false),
        ToolParameter("count", ParameterType.INTEGER, "查看条数(默认20)", required = false)
    )

    private data class SignalRecord(
        val id: Long, val symbol: String, val name: String, val direction: String,
        val signalType: String, val price: Double, val date: String,
        val reason: String, val target: Double? = null, val stop: Double? = null,
        val reviewed: Boolean = false, val outcome: String? = null,
        val outcomePrice: Double? = null, val outcomeDate: String? = null,
        val pnlPct: Double? = null
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = (args["action"] as? String)?.trim()?.lowercase() ?: "stats"
        return try {
            when (action) {
                "record" -> doRecord(args)
                "review" -> doReview(args)
                "stats" -> doStats(args)
                else -> "未知操作: $action"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "信号追踪失败: ${e.message ?: "未知错误"}" }
    }

    private fun doRecord(args: Map<String, Any>): String {
        val raw = (args["symbol"] as? String)?.trim() ?: return "错误：未指定股票代码"
        val direction = (args["direction"] as? String)?.trim()?.lowercase() ?: return "错误：未指定方向(bullish/bearish/neutral)"
        val signalType = (args["signal_type"] as? String)?.trim()?.lowercase() ?: "technical"
        val price = (args["price"] as? Double) ?: return "错误：未指定信号发出时股价"
        val reason = (args["reason"] as? String)?.trim() ?: ""
        val target = args["target"] as? Double
        val stop = args["stop"] as? Double

        if (direction !in listOf("bullish", "bearish", "neutral")) return "方向仅支持: bullish/bearish/neutral"

        val sym = normalizeSymbol(raw)
        val name = fetchStockName(sym)
        val date = nowString().take(19)
        val signals = loadSignals()
        val nextId = (signals.maxOfOrNull { it.id } ?: 0) + 1
        val record = SignalRecord(nextId, sym.display, name, direction, signalType, price, date, reason, target, stop)
        signals.add(record)
        saveSignals(signals)

        val dirIcon = when (direction) { "bullish" -> "🟢 看涨"; "bearish" -> "🔴 看跌"; else -> "⚪ 中性" }
        return buildString {
            appendLine("✅ 信号已记录 #$nextId")
            appendLine("  ${name}(${sym.display}) → $dirIcon")
            appendLine("  价格: ${fmtPrice(price)} | 类型: $signalType")
            if (target != null) appendLine("  目标: ${fmtPrice(target)}")
            if (stop != null) appendLine("  止损: ${fmtPrice(stop)}")
            appendLine("  理由: $reason")
            appendLine("  时间: $date")
            appendLine()
            appendLine("💡 用 action=review 定期检查信号结果，action=stats 查看准确率")
        }.trimEnd()
    }

    private fun doReview(args: Map<String, Any>): String {
        val raw = (args["symbol"] as? String)?.trim()
        val count = ((args["count"] as? Double)?.toInt()) ?: 20
        val signals = loadSignals()

        val pending = signals.filter { !it.reviewed }.sortedBy { it.id }
        val target = if (raw != null) pending.filter { it.symbol == normalizeSymbol(raw).display } else pending
        val toReview = target.take(count.coerceIn(1, 100))

        if (toReview.isEmpty()) return "没有待审核的信号" + if (raw != null) "（${raw}）" else ""

        val results = mutableListOf<String>()
        val signalsList = loadSignals().toMutableList()
        for (sig in toReview) {
            val currentPrice = fetchPrice(sig.symbol)
            if (currentPrice == null) {
                results.add("  ${sig.name}(${sig.symbol}) - 无法获取现价")
                continue
            }
            val daysSince = daysBetween(sig.date, nowString().take(10))
            val pnl = if (sig.price > 0) (currentPrice - sig.price) / sig.price * 100 else 0.0

            val correct = when (sig.direction) {
                "bullish" -> pnl > 2
                "bearish" -> pnl < -2
                "neutral" -> abs(pnl) < 3
                else -> false
            }
            val hitTarget = sig.target != null && ((sig.direction == "bullish" && currentPrice >= sig.target) ||
                    (sig.direction == "bearish" && currentPrice <= sig.target))
            val hitStop = sig.stop != null && ((sig.direction == "bullish" && currentPrice <= sig.stop) ||
                    (sig.direction == "bearish" && currentPrice >= sig.stop))

            val record = sig.copy(reviewed = true, outcome = if (correct) "正确" else "错误",
                outcomePrice = currentPrice, outcomeDate = nowString().take(10), pnlPct = pnl)

            // 复用同一份列表，循环结束后统一保存一次（避免每条信号整文件重写）
            val idx = signalsList.indexOfFirst { it.id == sig.id }
            if (idx >= 0) signalsList[idx] = record

            val icon = if (correct) "✅" else if (hitStop) "❌" else if (hitTarget) "🎯" else "⏳"
            val dirIcon = when (sig.direction) { "bullish" -> "看涨"; "bearish" -> "看跌"; else -> "中性" }
            results.add("  $icon #${sig.id} ${sig.name}(${sig.symbol}) $dirIcon ${sig.date} 信号价${fmtPrice(sig.price)} → 现价${fmtPrice(currentPrice)} (${String.format("%+.2f", pnl)}%) ${daysSince}天")
        }

        val reviewed = results.size
        val remaining = signals.count { !it.reviewed }

        // 统一保存一次
        if (reviewed > 0) saveSignals(signalsList)

        return buildString {
            appendLine("📊 信号审核 (本次${reviewed}条，剩余${remaining}条)")
            appendLine()
            results.forEach { appendLine(it) }
            appendLine()
            appendLine("💡 用 action=stats 查看准确率统计")
        }.trimEnd()
    }

    private fun doStats(args: Map<String, Any>): String {
        val count = ((args["count"] as? Double)?.toInt()) ?: 20
        val signals = loadSignals().sortedByDescending { it.id }.take(count.coerceIn(5, 500))
        if (signals.isEmpty()) return "暂无信号记录\n💡 用 action=record 记录第一个信号"

        val reviewed = signals.filter { it.reviewed }
        val total = reviewed.size
        if (total == 0) return "暂无已审核的信号（共${signals.size}条待审核）\n💡 用 action=review 审核信号"

        val correct = reviewed.count { it.outcome == "正确" }
        val accuracy = correct.toDouble() / total * 100

        val byDirection = reviewed.groupBy { it.direction }
        val dirStats = byDirection.mapValues { (_, sigs) ->
            val c = sigs.count { it.outcome == "正确" }
            "${c}/${sigs.size} (${String.format("%.0f", c.toDouble() / sigs.size * 100)}%)"
        }

        val byType = reviewed.groupBy { it.signalType }
        val typeStats = byType.mapValues { (_, sigs) ->
            val c = sigs.count { it.outcome == "正确" }
            "${c}/${sigs.size} (${String.format("%.0f", c.toDouble() / sigs.size * 100)}%)"
        }

        val recentSignals = signals.takeLast(10).reversed()

        return buildString {
            appendLine("📈 AI信号准确率统计")
            appendLine()
            appendLine("【总览】")
            appendLine("  总信号: ${signals.size}条")
            appendLine("  已审核: ${total}条 | 待审核: ${signals.size - total}条")
            appendLine("  正确: $correct | 错误: ${total - correct}")
            appendLine("  总准确率: ${String.format("%.1f", accuracy)}% ${if (accuracy >= 65) "⭐" else if (accuracy >= 50) "✓" else "⚠"}")
            appendLine()
            appendLine("【分方向准确率】")
            dirStats.forEach { (dir, stat) ->
                val label = when (dir) { "bullish" -> "看涨"; "bearish" -> "看跌"; else -> "中性" }
                appendLine("  $label: $stat")
            }
            appendLine()
            appendLine("【分类型准确率】")
            typeStats.forEach { (type, stat) ->
                val label = when (type) { "technical" -> "技术面"; "fundamental" -> "基本面"; "mixed" -> "综合"; else -> type }
                appendLine("  $label: $stat")
            }
            appendLine()
            appendLine("【最近信号】")
            recentSignals.takeLast(5).forEach { s ->
                val dirIcon = when (s.direction) { "bullish" -> "🟢"; "bearish" -> "🔴"; else -> "⚪" }
                val result = if (s.reviewed) " → ${if (s.outcome == "正确") "✅" else "❌"} ${String.format("%+.1f", s.pnlPct ?: 0.0)}%" else " ⏳待审核"
                appendLine("  $dirIcon #${s.id} ${s.name}(${s.symbol}) ${s.date}$result")
            }
            appendLine()
            appendLine("💡 用 action=review 审核待确认信号，提高AI准确率")
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════
    //  数据持久化
    // ═══════════════════════════════════════════════════════

    private fun dataDir(): File? {
        val ctx = AppContextHolder.context ?: return null
        return File(ctx.filesDir, "signal_tracker").also { it.mkdirs() }
    }

    private fun loadSignals(): MutableList<SignalRecord> {
        val dir = dataDir() ?: return mutableListOf()
        val file = File(dir, "signals.json")
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<SignalRecord>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableListOf()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveSignals(signals: List<SignalRecord>) {
        val dir = dataDir() ?: return
        val file = File(dir, "signals.json")
        try { com.wy.openx.data.util.AtomicFileWriter.writeAtomically(file, gson.toJson(signals)) } catch (_: Exception) { }
    }

    // ═══════════════════════════════════════════════════════
    //  工具函数
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

    private fun fetchPrice(symbol: String): Double? {
        val nsym = normalizeSymbol(symbol)
        return try {
            val url = "https://push2.eastmoney.com/api/qt/stock/get?secid=${nsym.eastMoney}&fields=f43,f57"
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Referer", "https://quote.eastmoney.com").build()
            val resp = httpClient().newCall(req).execute()
            val body = resp.body?.string(); resp.close()
            if (body != null) {
                val data = try { com.google.gson.JsonParser.parseString(body)?.asJsonObject?.getAsJsonObject("data") } catch (_: Exception) { null }
                data?.get("f43")?.takeIf { !it.isJsonNull }?.asDouble
            } else null
        } catch (_: Exception) { null }
    }

    private fun daysBetween(d1: String, d2: String): Int {
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val t1 = fmt.parse(d1.take(10))?.time ?: 0L; val t2 = fmt.parse(d2)?.time ?: 0L
            ((t2 - t1) / 86400000).toInt()
        } catch (_: Exception) { 0 }
    }

    private fun nowString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    private fun fmtPrice(v: Double): String = String.format("%.2f", v)
}
