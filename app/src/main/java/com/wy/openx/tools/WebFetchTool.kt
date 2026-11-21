package com.wy.openx.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class WebFetchTool(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()
) : AgentTool {

    override val name = "web_fetch"
    override val description = "抓取指定网页的内容并提取可读文本，适合查看文章、新闻、文档等。返回纯文本内容供 AI 分析总结。注意：对 JavaScript 动态渲染的页面（如 SPA 应用）可能只能拿到空壳内容。"
    override val parameters = buildToolParameters(
        ToolParameter("url", ParameterType.STRING, "要抓取的完整网页 URL（必须包含 http:// 或 https://）"),
        ToolParameter("maxChars", ParameterType.INTEGER, "最大返回字符数（默认 10000）", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        try {
            val url = args["url"] as? String ?: return@withContext "错误：未提供 URL"
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext "错误：URL 必须以 http:// 或 https:// 开头"
            }
            val maxChars = ((args["maxChars"] as? Double)?.toInt()) ?: 10000
            fetchPage(url, maxChars)
        } catch (e: Exception) {
            "抓取出错: ${e.message}"
        }
    }

    private fun fetchPage(url: String, maxChars: Int): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        val response = client.newCall(request).execute()

        val contentType = response.header("Content-Type", "")
        val contentLength = response.body?.contentLength() ?: -1L

        if (contentLength > 10 * 1024 * 1024) {
            response.close()
            return "页面过大（${formatSize(contentLength)}），拒绝下载。可尝试减少 maxChars 参数。"
        }

        // 限量流式读取（chunked 响应无 Content-Length，也受此保护）
        val html = response.use { resp ->
            resp.body?.let { b ->
                val cap = (maxChars * 6L).coerceAtLeast(512_000L).coerceAtMost(4_000_000L).toInt()
                val buf = ByteArray(cap)
                var off = 0
                b.byteStream().use { s ->
                    while (off < cap) {
                        val r = s.read(buf, off, cap - off)
                        if (r < 0) break
                        off += r
                    }
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
        } ?: return "无法获取页面内容"

        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim() ?: ""

        val text = html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</div>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</tr>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\n\\s*\\n"), "\n")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&nbsp;", " ").replace("&#39;", "'")

        val clean = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        val result = if (title.isNotBlank()) "标题: $title\n\n$clean" else clean
        return if (result.length > maxChars) {
            result.take(maxChars) + "\n\n...（内容过长已截断，仅显示前 $maxChars 字符）"
        } else result
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
