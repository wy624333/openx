package com.wy.openx.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebSearchTool(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            // ConcurrentHashMap：OkHttp 多线程并发读写；过期条目在读取时惰性清理
            private val store = java.util.concurrent.ConcurrentHashMap<String, MutableList<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val list = store.getOrPut(url.host) { java.util.Collections.synchronizedList(mutableListOf()) }
                synchronized(list) {
                    list.removeAll { c -> cookies.any { it.name == c.name && it.path == c.path } }
                    list.addAll(cookies.filter { it.expiresAt > System.currentTimeMillis() })
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val list = store[url.host] ?: return emptyList()
                return synchronized(list) {
                    list.removeAll { it.expiresAt <= System.currentTimeMillis() }
                    list.toList()
                }
            }
        })
        .build()
) : AgentTool {

    private val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private val gson = com.google.gson.Gson()
    private val errors = mutableListOf<String>()

    override val name = "web_search"
    override val description = "搜索互联网获取最新信息。自动尝试 DuckDuckGo、必应中国、搜狗、B站等搜索引擎，结果包含标题、链接和摘要。注意：搜索引擎可能因反爬机制返回空结果，此时可尝试更换关键词或使用 web_fetch 工具直接抓取已知 URL。"
    override val parameters = buildToolParameters(
        ToolParameter("query", ParameterType.STRING, "搜索关键词，尽量使用精确关键词"),
        ToolParameter("count", ParameterType.INTEGER, "返回结果数量（默认5）", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        errors.clear()
        try {
            val query = args["query"] as? String ?: return@withContext "错误：未提供搜索关键词"
            val count = (args["count"] as? Double)?.toInt() ?: 5

            withTimeout(30_000L) {
                searchAll(query, count)
            }
        } catch (e: TimeoutCancellationException) {
            buildErrorResult("搜索超时（30秒）", errors)
        } catch (e: Exception) {
            buildErrorResult("搜索出错: ${e.message}", errors)
        }
    }

    private fun searchAll(query: String, count: Int): String {
        val engines = listOf(
            "Parallel AI" to { searchParallel(query, count) },
            "DuckDuckGo" to { searchDuckDuckGo(query, count) },
            "必应中国" to { searchBingCn(query, count) },
            "搜狗" to { searchSogou(query, count) },
            "B站" to { searchBilibili(query, count) },
            "百度" to { searchBaidu(query, count) },
            "360" to { search360(query, count) }
        )
        for ((name, fn) in engines) {
            try {
                val r = fn()
                if (r.isNotEmpty()) return "${name}搜索结果：\n$r"
            } catch (e: Exception) {
                errors.add("$name: ${e.message?.take(100) ?: "未知错误"}")
            }
        }
        return buildErrorResult("所有搜索引擎均无法返回结果", errors)
    }

    // Parallel AI 公开 MCP 搜索接口（与 opencode 一致），无需 API key，稳定快速
    private fun searchParallel(query: String, count: Int): String {
        val bodyJson = gson.toJson(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to "tools/call",
                "params" to mapOf(
                    "name" to "web_search",
                    "arguments" to mapOf(
                        "objective" to query,
                        "search_queries" to listOf(query),
                        "session_id" to "openx-android"
                    )
                )
            )
        )
        val request = Request.Builder()
            .url("https://search.parallel.ai/mcp")
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "openx/1.0")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = client.newCall(request).execute()
        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
        val body = resp.use { it.body?.string() } ?: throw IllegalStateException("空响应")
        val rawText = parseMcpResponse(body) ?: throw IllegalStateException("解析失败，返回: ${body.take(100)}")
        val formatted = formatParallelResults(rawText, count)
        if (formatted.isBlank()) throw IllegalStateException("无有效结果")
        return formatted
    }

    // 兼容解析 MCP 返回的普通 JSON 或 SSE (Server-Sent Events) 格式
    private fun parseMcpResponse(body: String): String? {
        for (lineRaw in body.lineSequence()) {
            val line = lineRaw.trim()
            if (line.isEmpty()) continue
            val jsonStr = when {
                line.startsWith("data: ") -> line.substring(6)
                line.startsWith("{") -> line
                else -> continue
            }
            try {
                val json = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
                if (json.has("result")) {
                    val contentArr = json.getAsJsonObject("result")?.getAsJsonArray("content") ?: continue
                    for (el in contentArr) {
                        if (el.isJsonObject) {
                            val text = el.asJsonObject.get("text")?.takeIf { !it.isJsonNull }?.asString
                            if (!text.isNullOrBlank()) return text
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略片段/部分行的解析错误
            }
        }
        return null
    }

    // MCP web_search 返回的文本可能是纯文本或 JSON（含 results 数组），统一格式化成标题/链接/摘要
    private fun formatParallelResults(raw: String, count: Int): String {
        return try {
            val el = com.google.gson.JsonParser.parseString(raw.trim())
            if (el.isJsonObject) {
                val obj = el.asJsonObject
                val results = obj.getAsJsonArray("results")
                if (results != null && results.size() > 0) {
                    results.take(count).mapNotNull { item ->
                        if (!item.isJsonObject) return@mapNotNull null
                        val o = item.asJsonObject
                        val title = o.get("title")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                        val link = o.get("url")?.takeIf { !it.isJsonNull }?.asString
                            ?: o.get("link")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val snippet = o.get("snippet")?.takeIf { !it.isJsonNull }?.asString
                            ?: o.get("description")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        buildList {
                            add("标题: $title")
                            if (link.isNotBlank()) add("链接: $link")
                            if (snippet.isNotBlank()) add("摘要: $snippet")
                        }.joinToString("\n")
                    }.joinToString("\n---\n")
                } else {
                    // 无 results 数组时，fallback 用对象里正文文本
                    (obj.get("content") ?: obj.get("text"))?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
                }
            } else {
                raw.trim()
            }
        } catch (e: Exception) {
            raw.trim()
        }
    }

    private fun searchDuckDuckGo(query: String, count: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        val response = client.newCall(Request.Builder().url(url)
            .header("User-Agent", desktopUA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Cache-Control", "no-cache")
            .build()).execute()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        val html = response.use { it.body?.string() } ?: throw IllegalStateException("空响应")
        if (html.contains("captcha", ignoreCase = true) || html.contains("verify", ignoreCase = true)) {
            throw IllegalStateException("触发反爬验证")
        }
        val results = mutableListOf<String>()
        val resultRegex = Regex(
            """<a[^>]*rel\s*=\s*["']nofollow["'][^>]*class\s*=\s*["'][^"']*result__a[^"']*["'][^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val snippetRegex = Regex(
            """<a[^>]*class\s*=\s*["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val links = ArrayList<kotlin.text.MatchResult>(count * 2)
        for (m in resultRegex.findAll(html)) {
            links.add(m)
            if (links.size >= count * 3) break
        }
        val snippets = ArrayList<kotlin.text.MatchResult>(count * 2)
        for (m in snippetRegex.findAll(html)) {
            snippets.add(m)
            if (snippets.size >= count * 3) break
        }
        for (i in 0 until minOf(links.size, count)) {
            val linkMatch = links[i]
            var link = linkMatch.groupValues[1].trim()
            if (link.startsWith("//")) link = "https:$link"
            val title = extractText(linkMatch.groupValues[2])
            val snippet = if (i < snippets.size) extractText(snippets[i].groupValues[1]) else ""
            if (title.isNotBlank() && link.isNotBlank() && link.startsWith("http")) {
                results.add(if (snippet.isNotBlank()) "标题: $title\n链接: $link\n摘要: $snippet" else "标题: $title\n链接: $link")
            }
        }
        if (results.isEmpty()) {
            val altResultRegex = Regex(
                """class\s*=\s*["'][^"']*result__body[^"']*["'][^>]*>(.*?)(?=class\s*=\s*["'][^"']*result__body|</div>\s*<div[^>]*class\s*=\s*["']\s*results\s*["'])""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (block in altResultRegex.findAll(html).take(count)) {
                val b = block.groupValues[1]
                val title = extractText(Regex("""<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1) ?: "")
                val link = Regex("""href\s*=\s*["'](https?://[^"']+)["']""").find(b)?.groupValues?.get(1) ?: ""
                val snippet = extractText(Regex("""class\s*=\s*["'][^"']*snippet[^"']*["'][^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1) ?: "")
                if (title.isNotBlank() && link.isNotBlank()) {
                    results.add(if (snippet.isNotBlank()) "标题: $title\n链接: $link\n摘要: $snippet" else "标题: $title\n链接: $link")
                }
            }
        }
        return results.joinToString("\n---\n")
    }

    private fun searchSogou(query: String, count: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.sogou.com/web?query=$encoded"
        val response = client.newCall(Request.Builder().url(url)
            .header("User-Agent", desktopUA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()).execute()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        val html = response.use { it.body?.string() } ?: throw IllegalStateException("空响应")
        if (html.contains("antispider", ignoreCase = true) || html.contains("captcha", ignoreCase = true)) {
            throw IllegalStateException("触发反爬验证")
        }
        val results = mutableListOf<String>()
        val vrRegex = Regex("""<div[^>]*class\s*=\s*["'][^"']*vrwrap[^"']*["'][^>]*>(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)
        for (vr in vrRegex.findAll(html).take(count)) {
            val block = vr.groupValues[1]
            val title = extractText(Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: "")
            val link = Regex("""<a[^>]*href\s*=\s*["'](https?://[^"']+)["']""").find(block)?.groupValues?.get(1) ?: ""
            val snippet = extractText(Regex("""<p[^>]*class\s*=\s*["'][^"']*str-text[^"']*["'][^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: "")
            if (title.isNotBlank() && link.isNotBlank()) {
                results.add(if (snippet.isNotBlank()) "标题: $title\n链接: $link\n摘要: $snippet" else "标题: $title\n链接: $link")
            }
        }
        if (results.isNotEmpty()) return results.joinToString("\n---\n")
        val h3s = Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (h3 in h3s.take(count * 2)) {
            val t = extractText(h3.groupValues[1])
            if (t.isNotBlank() && t.length > 5) results.add("标题: $t")
        }
        return results.take(count).joinToString("\n---\n")
    }

    private fun searchBingCn(query: String, count: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://cn.bing.com/search?q=$encoded&count=$count"
        val response = client.newCall(Request.Builder().url(url)
            .header("User-Agent", desktopUA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cache-Control", "no-cache")
            .build()).execute()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        val html = response.use { it.body?.string() } ?: throw IllegalStateException("空响应")
        if (html.contains("captcha", ignoreCase = true) || html.contains("unusual traffic", ignoreCase = true)) {
            throw IllegalStateException("触发反爬验证")
        }
        val results = mutableListOf<String>()
        val algoRegex = Regex("""<li[^>]*class\s*=\s*["'][^"']*b_algo[^"']*["'][^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        for (match in algoRegex.findAll(html).take(count)) {
            val block = match.groupValues[1]
            val title = extractText(Regex("""<h2[^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: "")
            val link = Regex("""<a[^>]*href\s*=\s*["'](https?://[^"']+)["']""").find(block)?.groupValues?.get(1) ?: ""
            val snippet = extractText(Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: "")
            if (title.isNotBlank() && link.isNotBlank() && link.startsWith("http")) {
                results.add(if (snippet.isNotBlank()) "标题: $title\n链接: $link\n摘要: $snippet" else "标题: $title\n链接: $link")
            }
        }
        if (results.isNotEmpty()) return results.joinToString("\n---\n")
        val h3s = Regex("""<h2[^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (h3 in h3s.take(count * 2)) {
            val t = extractText(h3.groupValues[1])
            if (t.isNotBlank() && t.length > 5) results.add("标题: $t")
        }
        return results.take(count).joinToString("\n---\n")
    }

    private fun searchBaidu(query: String, count: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.baidu.com/s?wd=$encoded&rn=$count"
        val response = client.newCall(Request.Builder().url(url)
            .header("User-Agent", desktopUA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()).execute()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        val html = response.use { it.body?.string() } ?: throw IllegalStateException("空响应")
        if (html.contains("antispider", ignoreCase = true) || html.length < 500) {
            throw IllegalStateException("触发反爬验证或页面过短(${html.length})")
        }
        val results = mutableListOf<String>()
        val patterns = listOf(
            Regex("""<div[^>]*class\s*=\s*["'][^"']*result[^"']*c-container[^"']*["'][^>]*>(.*?)(?:<div[^>]*class\s*=\s*["'][^"']*result|</div>\s*<!--)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""<div[^>]*class\s*=\s*["'][^"']*c-container[^"']*["'][^>]*>(.*?)(?:<div[^>]*class\s*=\s*["'][^"']*c-container|</div>\s*<div[^>]*id\s*=\s*)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""<h3[^>]*class\s*=\s*["'][^"']*t[^"']*["'][^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
        )
        for (pattern in patterns) {
            val matches = ArrayList<kotlin.text.MatchResult>(count)
            for (m in pattern.findAll(html)) {
                matches.add(m)
                if (matches.size >= count) break
            }
            if (matches.isEmpty()) continue
            for (match in matches) {
                val block = match.groupValues[1]
                val title = extractText(Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: "")
                val link = Regex("""<a[^>]*href\s*=\s*["'](https?://[^"']+)["']""").find(block)?.groupValues?.get(1)
                    ?: Regex("""<a[^>]*href\s*=\s*["']/(http[^"']+)["']""").find(block)?.groupValues?.get(1) ?: ""
                if (title.isNotBlank() && link.isNotBlank() && !title.contains("百度热搜") && !title.contains("相关搜索")) {
                    val snippet = extractText(Regex("""<span[^>]*class\s*=\s*["'][^"']*(?:content-right|c-abstract|abstract)[^"']*["'][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                        ?: Regex("""<div[^>]*class\s*=\s*["'][^"']*c-abstract[^"']*["'][^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: "")
                    results.add(if (snippet.isNotBlank()) "标题: $title\n链接: $link\n摘要: $snippet" else "标题: $title\n链接: $link")
                }
            }
            if (results.isNotEmpty()) break
        }
        if (results.isNotEmpty()) return results.joinToString("\n---\n")
        val h3s = Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (h3 in h3s.take(count * 2)) {
            val t = extractText(h3.groupValues[1])
            if (t.isNotBlank() && t.length > 5) results.add("标题: $t")
        }
        return results.take(count).joinToString("\n---\n")
    }

    private fun search360(query: String, count: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.so.com/s?q=$encoded&pn=1"
        val response = client.newCall(Request.Builder().url(url)
            .header("User-Agent", desktopUA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()).execute()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        val html = response.use { it.body?.string() } ?: throw IllegalStateException("空响应")
        if (html.length < 300) throw IllegalStateException("页面过短(${html.length})，可能被拦截")
        val results = mutableListOf<String>()
        val blockRegex = Regex("""<li[^>]*class\s*=\s*["'][^"']*res-list[^"']*["'][^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        for (block in blockRegex.findAll(html).take(count)) {
            val b = block.groupValues[1]
            val title = extractText(Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1) ?: "")
            val link = Regex("""<a[^>]*href\s*=\s*["'](https?://[^"']+)["']""").find(b)?.groupValues?.get(1) ?: ""
            val snippet = extractText(Regex("""<p[^>]*class\s*=\s*["'][^"']*res-desc[^"']*["'][^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1) ?: "")
            if (title.isNotBlank() && link.isNotBlank()) {
                results.add(if (snippet.isNotBlank()) "标题: $title\n链接: $link\n摘要: $snippet" else "标题: $title\n链接: $link")
            }
        }
        if (results.isNotEmpty()) return results.joinToString("\n---\n")
        val h3s = Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (h3 in h3s.take(count * 2)) {
            val t = extractText(h3.groupValues[1])
            if (t.isNotBlank() && t.length > 5) results.add("标题: $t")
        }
        return results.take(count).joinToString("\n---\n")
    }

    private fun searchBilibili(query: String, count: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&keyword=$encoded"
        val response = client.newCall(Request.Builder().url(url)
            .header("User-Agent", desktopUA)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .build()).execute()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        val text = response.use { it.body?.string() } ?: throw IllegalStateException("空响应")
        val json = gson.fromJson(text, Map::class.java)
        val code = (json["code"] as? Double)?.toInt() ?: (json["code"] as? Int) ?: 0
        if (code != 0) {
            val msg = json["message"] as? String ?: "未知错误"
            throw IllegalStateException("API返回错误 code=$code: $msg")
        }
        val data = json["data"] as? Map<*, *> ?: throw IllegalStateException("data 字段为空")
        val results = data["result"] as? List<*> ?: return ""
        val out = results.take(count).mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val title = (m["title"] as? String ?: "").replace(Regex("<[^>]*>"), "")
            val link = m["arcurl"] as? String ?: ""
            val author = m["author"] as? String ?: ""
            val play = m["play"] as? Double ?: 0
            if (title.isNotBlank() && link.isNotBlank()) "标题: $title\n链接: $link\nUP主: $author | 播放: ${play.toInt()}" else null
        }
        return out.joinToString("\n---\n")
    }

    private fun buildErrorResult(msg: String, errors: List<String>): String {
        val sb = StringBuilder(msg)
        if (errors.isNotEmpty()) {
            sb.append("\n各引擎错误详情：")
            for (e in errors.take(6)) {
                sb.append("\n  - $e")
            }
        }
        sb.append("\n\n建议：更换关键词，或使用 web_fetch 工具直接抓取已知 URL")
        return sb.toString()
    }

    private fun extractText(html: String): String {
        return html.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#34;", "\"").replace("&#x3D;", "=").trim()
    }
}
