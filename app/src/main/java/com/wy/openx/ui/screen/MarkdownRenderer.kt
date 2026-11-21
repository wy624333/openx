package com.wy.openx.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import com.wy.openx.container.FileLogger
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.text.style.TextOverflow

private val BlockquoteBar = Color(0xFF6EA8FE)
private val LinkColor = Color(0xFF6EA8FE)

private const val URL_TAG = "URL"

data class MarkdownBlock(
    val type: BlockType,
    val content: String,
    val level: Int = 0,
    val rows: List<List<String>>? = null,
    val items: List<String>? = null,
    val lang: String = "",
    /** [BlockType.IMAGE] 的图片地址（http/https/content/file）。 */
    val imageUrl: String = "",
    /** [BlockType.IMAGE] 的 alt 文本，用作无障碍描述与全屏查看器标题。 */
    val imageAlt: String = ""
)

enum class BlockType { PARAGRAPH, HEADING, CODE, UL, OL, QUOTE, TABLE, HR, IMAGE }

/**
 * 整行就是一个 markdown 图片的形态，例如生图模型返回的 `![image](https://.../x.png)`。
 *
 * 单独提成 [BlockType.IMAGE] 而不是塞进段落里，是因为 [renderInline] 返回 AnnotatedString——
 * 里面放不了任何可组合的图像节点；而「图片独占一行」正是生图响应的常见形态。
 * 混在文字里的图片（`看图 ![](x)`）仍走原路径，退化成 alt 文本。
 */
private val standaloneImagePattern = Regex("^\\s*!\\[([^\\]]*)\\]\\(\\s*([^)\\s]+)\\s*\\)\\s*$")

// 单遍扫描的内联标记正则（图片/链接/行内代码/加粗/删除线/斜体）
private val inlinePattern = Regex(
    "(!\\[[^\\]]*\\]\\([^)\n]+\\))" +
        "|(\\[[^\\]\n]+\\]\\([^)\n]+\\))" +
        "|(`[^`\n]+`)" +
        "|(\\*\\*[^*\n]+\\*\\*)" +
        "|(~~[^~\n]+~~)" +
        "|((?<![*\\w])\\*[^*\n]+\\*(?!\\*))"
)

/**
 * 「打开聊天里的文件链接」回调：由聊天页在合适的层级 provide，[openUrl] 消费。
 *
 * 用 CompositionLocal 而非逐层传参：[MarkdownContent] / [InlineText] 有几十处调用点
 * （正文、步骤、diff 之外的各处内联），逐个加参数既啰嗦又易漏。
 *
 * @param path 容器内路径（如 `/root/workspace/a.kt`）
 * @param line 1-based 行号；0 表示无行号
 */
typealias ChatFileOpener = (path: String, line: Int) -> Unit

/** 默认无提供方：链接点击退回系统浏览器（原行为）。 */
val LocalChatFileOpener = compositionLocalOf<ChatFileOpener?> { null }

private fun openUrl(context: android.content.Context, url: String, fileOpener: ChatFileOpener?) {
    // 先问宿主：这是不是「该交给编辑器的工作区文件」（含 `文件:行号` 形式）。
    // 判为文件时交由宿主打开——本函数无权访问容器文件系统与编辑器状态。
    if (fileOpener != null) {
        val filePath = asChatFilePath(url)
        if (filePath != null) {
            val (path, line) = splitPathAndLine(filePath)
            runCatching { fileOpener(path, line) }
            return
        }
    }
    // 网址：http/https 直接打开；其余（裸域名）补 https://。
    // ⚠ 旧实现在此无条件给非 http(s) 拼 `https://`，于是 `notes.md:12` 这类**本地文件链接**
    // 会被拼成 `https://notes.md:12` 这种打不开的地址——上面文件判定已把它截走。
    try {
        val uri = if (url.startsWith("http://") || url.startsWith("https://")) Uri.parse(url) else Uri.parse("https://$url")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { }
}

/** 渲染内联标记为 AnnotatedString（单遍，O(n)；链接带 URL 注解） */
fun renderInline(text: String, baseFontSize: TextUnit = 15.sp, inlineFg: Color = Color(0xFFE2E8F0), inlineBg: Color = Color(0x332A3550)): AnnotatedString {
    return buildAnnotatedString {
        var last = 0
        for (m in inlinePattern.findAll(text)) {
            append(text.substring(last, m.range.first))
            val token = m.value
            when {
                token.startsWith("![") -> {
                    val alt = token.removePrefix("![").substringBefore("](")
                    append("[图片: $alt]")
                }
                token.startsWith("[") -> {
                    val label = token.removePrefix("[").substringBefore("](")
                    val url = token.substringAfter("](").removeSuffix(")")
                    pushStringAnnotation(URL_TAG, url)
                    withStyle(SpanStyle(color = LinkColor, textDecoration = TextDecoration.Underline)) {
                        append(label)
                    }
                    pop()
                }
                token.startsWith("`") -> {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = inlineBg,
                        color = inlineFg,
                        fontSize = (baseFontSize.value - 1).sp
                    )) { append(token.trim('`')) }
                }
                token.startsWith("**") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.drop(2).dropLast(2)) }
                }
                token.startsWith("~~") -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.drop(2).dropLast(2)) }
                }
                else -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.drop(1).dropLast(1)) }
                }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

/** 内联文本组件：含链接时可点击打开浏览器；结果按内容缓存避免重复解析 */
/** 正文内嵌图片的显示边长上限（dp）。超出按比例缩，避免超长图把聊天区撑爆。 */
private val MarkdownImageMaxHeight = 420.dp

/** 远程图片字节数上限：超过就按已读部分解码上限截断，防止超大响应打爆内存。 */
private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

/**
 * 正文里的图片节点。
 *
 * 原来 markdown 图片只在 [renderInline] 里被拼成 `[图片: alt]` 纯文字——生图模型返回
 * `![image](https://...)` 时聊天里只能看到「[图片: image]」，图永远不显示。
 *
 * 这里补上渲染：http/https 走 [java.net.URL] 直接下载（不指望 ContentResolver 处理网络
 * 协议），content/file 走 ContentResolver；解码复用 [decodeSampledBitmapBytes]（带 EXIF 摆正），
 * 点一下拉起 [ImageViewerSheet] 看大图。加载中和失败都给明确占位，不留空白。
 */
@Composable
private fun MarkdownImageNode(url: String, alt: String) {
    val context = LocalContext.current
    val label = alt.ifBlank { "图片" }
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var showViewer by remember(url) { mutableStateOf(false) }
    var saveState by remember(url) { mutableStateOf<ImageSaveState>(ImageSaveState.Idle) }

    LaunchedEffect(url) {
        // 注意：加载器给的是原始字节，得过一遍采样解码才是 Bitmap
        bitmap = withContext(Dispatchers.IO) {
            loadMarkdownImageBytes(context, url)?.let { decodeSampledBitmapBytes(it, 1280) }
        }
        loading = false
    }

    // 点了保存才去下载原图字节——没点过就不额外发一次网络请求
    LaunchedEffect(saveState) {
        if (saveState != ImageSaveState.Saving) return@LaunchedEffect
        val bytes = loadMarkdownImageBytes(context, url)
        val saved = if (bytes == null) null
                    else saveMarkdownImageToUploads(context, url, bytes)?.name
        saveState = if (saved != null) ImageSaveState.Done(saved) else ImageSaveState.Failed
    }

    val bmp = bitmap
    when {
        bmp != null -> {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = MarkdownImageMaxHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showViewer = true }
            )
            // 存原图字节而不是重新编码 Bitmap：省一次有损重压，也不用再下一遍
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { saveState = ImageSaveState.Saving },
                    enabled = saveState != ImageSaveState.Saving,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    if (saveState == ImageSaveState.Saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("保存到 uploads", fontSize = 12.sp)
                    }
                }
                // 关键：保存中**不出结果文案**。原先这里用 `saveState?.let { (it is String) }`
                // 判成败，而保存中存的是 `true`——`true is String` 为 false，于是
                // 「正在保存」被渲染成「保存失败」，几百毫秒后才变成「已保存」。
                // 现由 [imageSaveFeedback] 穷尽返回 null（空闲/保存中），UI 不显示结果文字。
                imageSaveFeedback(saveState)?.let { feedback ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = feedback,
                        color = if (saveState is ImageSaveState.Done) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (showViewer) {
                ImageViewerSheet(
                    name = label,
                    subtitle = url,
                    onDismiss = { showViewer = false },
                    decode = {
                        // 全屏按屏幕尺寸重新解码一份更清晰的
                        val bytes = withContext(Dispatchers.IO) { loadMarkdownImageBytes(context, url) }
                        ((bytes?.size ?: 0).toLong() to bytes?.let { decodeSampledBitmapBytes(it, 2560) })
                    }
                )
            }
        }
        loading -> Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("图片加载中…", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        else -> Text(
            text = "[图片加载失败${if (label.isNotBlank()) ": $label" else ""}]",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 13.sp
        )
    }
}

/**
 * 读取 markdown 图片的原始字节；网络协议走 [java.net.URL]，其余交给 ContentResolver。
 *
 * 返回 null 表示读不到，调用方据此显示失败占位——**不抛异常**，因为 markdown 里的
 * 图片地址不可控（模型可能给出失效链接），不能让一条坏图把整条消息的渲染带崩。
 */
private suspend fun loadMarkdownImageBytes(context: android.content.Context, url: String): ByteArray? =
    withContext(Dispatchers.IO) {
        val raw = url.trim()
        if (raw.isEmpty()) return@withContext null
        try {
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                val conn = (java.net.URL(raw).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        FileLogger.w("MarkdownImage", "下载失败 HTTP $code: $raw")
                        return@withContext null
                    }
                    conn.inputStream.use { it.readAtMost(MAX_IMAGE_BYTES) }
                } finally {
                    conn.disconnect()
                }
            } else {
                context.contentResolver.openInputStream(android.net.Uri.parse(raw))
                    ?.use { it.readAtMost(MAX_IMAGE_BYTES) }
            }
        } catch (e: Exception) {
            FileLogger.w("MarkdownImage", "图片读取失败: $raw", e)
            null
        }
    }

/** 读满 [limit] 字节即停，避免超大响应把内存打爆。 */
private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = read(buf)
        if (n <= 0) break
        total += n
        out.write(buf, 0, n)
        if (total >= limit) break
    }
    return out.toByteArray()
}

@Composable
private fun InlineText(
    text: String,
    baseFontSize: TextUnit,
    color: Color,
    lineHeightFactor: Float = 1.5f,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier
) {
    val inlineFg = MaterialTheme.colorScheme.onBackground
    val inlineBg = inlineFg.copy(alpha = 0.1f)
    // 最后一道防线：任何路径漏出的巨型文本（未来新增的块类型、第三方渲染入口）
    // 都不再直达 Text/StaticLayout —— 那会让 LineBreaker 断行数组把堆撑爆
    // （已出现过 OutOfMemoryError at LineBreaker$Builder.build）。
    // 截断尾部并提示；主修复（addParagraphBlocks 切段）保证正常路径到不了这里。
    val safeText = if (text.length > INLINE_TEXT_HARD_LIMIT) {
        text.take(INLINE_TEXT_HARD_LIMIT) + "\n…（内容过长已截断显示）"
    } else text
    val styled = remember(safeText, baseFontSize, inlineFg, inlineBg) { renderInline(safeText, baseFontSize, inlineFg, inlineBg) }
    val context = LocalContext.current
    // CompositionLocal 必须在 @Composable 里读（onClick lambda 不是 composable 上下文），
    // 故在这里取出再以普通参数传给 openUrl。
    val fileOpener = LocalChatFileOpener.current
    val hasLink = text.contains("](")
    if (hasLink) {
        ClickableText(
            text = styled,
            style = TextStyle(
                color = color,
                fontSize = baseFontSize,
                lineHeight = (baseFontSize.value * lineHeightFactor).sp,
                fontStyle = fontStyle,
                fontWeight = fontWeight
            ),
            onClick = { offset ->
                styled.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()?.let { openUrl(context, it.item, fileOpener) }
            },
            modifier = modifier
        )
    } else {
        Text(
            text = styled,
            color = color,
            fontSize = baseFontSize,
            lineHeight = (baseFontSize.value * lineHeightFactor).sp,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            modifier = modifier
        )
    }
}

/**
 * 解析结果的全局 LRU 缓存。
 *
 * LazyColumn 会把滞出视口的 item 回收，滚回来时 `remember` 已丢——没有这份缓存就要
 * 重新解析整篇。更糟的是若首帧先交空列表，高度会从 ≈0 暴涨到实际值，LazyColumn 为此
 * 重新测量并补偿滚动位置；流式期间往上翻历史时连续划过一串长消息，就是「一卡一卡」。
 * 命中缓存能同时避开重复解析与首帧跳高。
 */
private object MarkdownParseCache {

    /**
     * 缓存总字符预算。String 在 JVM 里每字符占 2 字节，而块列表还持有各自副本，
     * 故实际占用约为该值的 2〜4 倍（3M 字 ≈ 12MB）。按条数封顶是不够的：
     * 几条几百 KB 的长回答就能把内存顶爆。
     */
    private const val TOTAL_CHAR_BUDGET = 3_000_000

    /** 单条超过此长度不入缓存（工具输出、整篇长文档这类不应挤掉屏面上的普通消息）。 */
    private const val MAX_CACHED_LENGTH = 300_000

    private val map = object : LinkedHashMap<String, MarkdownParseState>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MarkdownParseState>?): Boolean {
            if (size > 128) return true
            // LinkedHashMap 不记总权重，超预算时在这里从最旧的开始踢。accessOrder=true
            // 保证迭代序就是「最久未用在前」，踢掉的一定是冷数据。
            while (values.sumOf { it.text.length } > TOTAL_CHAR_BUDGET && isNotEmpty()) {
                val it = iterator()
                it.next()
                it.remove()
            }
            return false
        }
    }

    fun get(text: String): MarkdownParseState? = synchronized(map) { map[text] }

    fun put(state: MarkdownParseState) {
        if (state.text.length > MAX_CACHED_LENGTH) return
        synchronized(map) { map[state.text] = state }
    }
}

/**
 * 单个 PARAGRAPH 块的最大字符数，超过则按行切成多个块。
 *
 * 原因：渲染时一个 PARAGRAPH 块就是一个 `InlineText` → 一份 `StaticLayout`；
 * `LineBreaker` 的断行内存开销随文本长度超线性增长，无空行连续多行的长思考
 * 会整段合并成一块，长到某个点直接 `OutOfMemoryError: LineBreaker$Builder.build`。
 * 切段后每段视觉一致（段内本来就该换行），断行规模受控。
 *
 * 4000 ≈ 三屏文字，远超正常段落，只拦截异常形态；也小于增量解析的稳定边界粒度，
 * 不影响 `cut/stableBlocks` 推进（切段发生在块收尾时，边界仍由空行分支更新）。
 */
private const val MAX_PARAGRAPH_CHARS = 4000

/**
 * `InlineText` 的硬上限（字符）：超过则截断尾部并提示。
 *
 * 这是防线而非功能：正常路径到不了这里（PARAGRAPH 已按 [MAX_PARAGRAPH_CHARS] 切段，
 * 其他块类型天然短）。留着它是因为 StaticLayout OOM 直接杀进程，而截断只是显示不全。
 */
private const val INLINE_TEXT_HARD_LIMIT = 20000

/**
 * 把一个超长段落的行列表切成多个 ≤ [MAX_PARAGRAPH_CHARS] 的 PARAGRAPH 块。
 * 视觉与未切分一致：块间无空行时渲染本来就不加间隔（见 MarkdownBlockItem）。
 */
private fun addParagraphBlocks(blocks: MutableList<MarkdownBlock>, paraLines: List<String>) {
    if (paraLines.isEmpty()) return
    val total = paraLines.sumOf { it.length + 1 }
    if (total <= MAX_PARAGRAPH_CHARS) {
        blocks.add(MarkdownBlock(BlockType.PARAGRAPH, paraLines.joinToString("\n")))
        return
    }
    var buf = StringBuilder(paraLines[0])
    for (k in 1 until paraLines.size) {
        if (buf.length + paraLines[k].length + 1 > MAX_PARAGRAPH_CHARS) {
            blocks.add(MarkdownBlock(BlockType.PARAGRAPH, buf.toString()))
            buf = StringBuilder(paraLines[k])
        } else {
            buf.append('\n').append(paraLines[k])
        }
    }
    if (buf.isNotEmpty()) blocks.add(MarkdownBlock(BlockType.PARAGRAPH, buf.toString()))
}

@Composable
private fun rememberMarkdownBlocks(text: String): List<MarkdownBlock> {
    // 解析放到后台线程，主线程只负责组合/测量。
    //
    // 流式输出时 text 每个 token 都变，若在主线程同步 parseMarkdown（O(n)，且逐行做
    // 列表/标题/分隔线判定），长文本下会持续占满主线程 → 掉帧。这里：
    //  · 首帧用同步解析的结果兜底（此时文本通常很短，或是一次性历史文本），避免闪空白；
    //  · 之后所有变化都丢到 Dispatchers.Default 解析；
    //  · parseMarkdown 是纯 CPU 计算、不可挂起，故用 conflate + 提交前校验「是否仍是最新文本」：
    //    解析途中到达的新文本被丢弃，解析完成后立即取最新一份，绝不会因为协程被取消而卡住不更新；
    //  · 解析未完成期间沿用上一次的结果，内容只增不跳（不闪「裸文本」）。
    val latestTextState = rememberUpdatedState(text)
    // 首帧一律同步解析（先查全局缓存）。曾经试过「长文本首帧交空列表、后台填充」，
    // 打字是不卡了，但滞动时每条长消息进入视口都要空→填充→高度暴涨，整页反而更卡。
    // 同步解析的 O(n) 只在首次发生，且有缓存兜着；代价远小于布局跳动。
    var state by remember {
        val cached = MarkdownParseCache.get(text)
        mutableStateOf(cached ?: parseMarkdownState(text, null).also { MarkdownParseCache.put(it) })
    }
    LaunchedEffect(Unit) {
        snapshotFlow { latestTextState.value }
            .conflate()
            .collect { snapshot ->
                val current = state
                // 首帧已同步解析过这份文本，别再重解一遍
                if (current?.text == snapshot) return@collect
                // 前缀未变时只重解析尾部，这是长思考流式不卡的关键
                val parsed = withContext(Dispatchers.Default) {
                    parseMarkdownState(snapshot, current)
                }
                if (latestTextState.value == snapshot) state = parsed
                // 流式中间态不写缓存：每帧一份前缀，会把屏面上的历史消息顶出去
            }
    }
    return state?.blocks ?: emptyList()
}

/**
 * 单个 markdown 块的渲染（正文 Column 与思考 LazyColumn 共用同一份，视觉完全一致）。
 */
@Composable
internal fun MarkdownBlockItem(
    block: MarkdownBlock,
    baseFontSize: TextUnit,
    bodyFg: Color
) {
    when (block.type) {
        BlockType.HEADING -> {
            val sizes = mapOf(1 to 22.sp, 2 to 19.sp, 3 to 17.sp)
            InlineText(
                text = block.content,
                baseFontSize = sizes[block.level.coerceIn(1, 3)] ?: 17.sp,
                color = bodyFg,
                fontWeight = FontWeight.Bold,
                lineHeightFactor = 1.4f,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
            )
        }
        BlockType.CODE -> {
            val code = block.content.trimEnd()
            val lines = remember(code) { code.lines() }
            val clipboardManager = LocalClipboardManager.current
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (block.lang.isNotBlank()) {
                            Text(block.lang, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(code)) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制代码", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                    Column(modifier = Modifier.padding(start = 12.dp, bottom = 12.dp, end = 12.dp)) {
                        lines.forEachIndexed { _, line ->
                            Text(
                                text = remember(line) { highlightLine(line) },
                                fontFamily = FontFamily.Monospace,
                                fontSize = (baseFontSize.value - 2).sp,
                                color = bodyFg,
                                lineHeight = (baseFontSize.value + 4).sp
                            )
                        }
                    }
                }
            }
        }
        BlockType.IMAGE -> {
            MarkdownImageNode(url = block.imageUrl, alt = block.imageAlt)
        }
        BlockType.UL -> {
            block.items?.forEach { item ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text("  •  ", color = bodyFg, fontSize = baseFontSize)
                    InlineText(
                        text = item,
                        baseFontSize = baseFontSize,
                        color = bodyFg,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        BlockType.OL -> {
            block.items?.forEachIndexed { i, item ->
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text("  ${i + 1}.  ", color = bodyFg, fontSize = baseFontSize)
                    InlineText(
                        text = item,
                        baseFontSize = baseFontSize,
                        color = bodyFg,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        BlockType.QUOTE -> {
            Row(modifier = Modifier.padding(vertical = 2.dp).height(IntrinsicSize.Min)) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(BlockquoteBar)
                )
                Spacer(modifier = Modifier.width(10.dp))
                InlineText(
                    text = block.content,
                    baseFontSize = baseFontSize,
                    color = bodyFg,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        BlockType.TABLE -> {
            block.rows?.let { rows ->
                val tableBorder = MaterialTheme.colorScheme.outline
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().border(1.dp, tableBorder.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                ) {
                    Column(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                        rows.forEachIndexed { ri, row ->
                            Row {
                                row.forEachIndexed { ci, cell ->
                                    val isHeader = ri == 0
                                    Text(
                                        text = cell,
                                        color = if (isHeader) MaterialTheme.colorScheme.onSurface else bodyFg,
                                        fontSize = (baseFontSize.value - 1).sp,
                                        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                            .widthIn(min = 80.dp, max = 220.dp)
                                    )
                                    if (ci < row.size - 1) {
                                        Box(modifier = Modifier.width(1.dp).height(16.dp).background(tableBorder))
                                    }
                                }
                            }
                            if (ri == 0) {
                                HorizontalDivider(color = tableBorder, thickness = 1.dp)
                            }
                        }
                    }
                }
            }
        }
        BlockType.HR -> {
            HorizontalDivider(
                color = Color(0xFF2A3550),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        BlockType.PARAGRAPH -> {
            if (block.content.isNotBlank()) {
                InlineText(
                    text = block.content,
                    baseFontSize = baseFontSize,
                    color = bodyFg,
                    lineHeightFactor = 1.6f
                )
            }
        }
    }
}

/**
 * @param textColor 正文颜色覆盖（外观设置里的自定义聊天文本色）。null = 用主题的
 *   `onBackground`。只影响正文/标题/列表/引用的文字色，代码块与表格仍用各自的
 *   语义色（它们本来就不跟随正文色）。
 */
@Composable
fun MarkdownContent(
    text: String,
    baseFontSize: TextUnit = 15.sp,
    textColor: Color? = null
) {
    val blocks = rememberMarkdownBlocks(text)
    val bodyFg = textColor ?: MaterialTheme.colorScheme.onBackground
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            MarkdownBlockItem(block = block, baseFontSize = baseFontSize, bodyFg = bodyFg)
        }
    }
}

/**
 * markdown 的**块级虚拟化**渲染：每个 markdown 块是 LazyColumn 的一个 item，
 * 只组合/测量可视区块（思考块内部 200dp 滚动用的就是它）。
 *
 * 为什么必须块级：长思考整段是「非懒加载列 + verticalScroll」，布局成本与全文长度
 * 成正比——用户一滚几万字的思考，主线程每帧全量 measure/layout → 卡住甚至 ANR 闪退。
 * 拆成块后单次布局只花在可视的几块上，与思考总长度无关（解析仍在后台线程，草案不变）。
 *
 * @param state 由调用方持有的滚动状态（跟随 / 判定溢出 / 橡皮筋交接都要读它）
 * @param bottomPadding 尾部缓冲（内滚到底时空出渐隐遮罩的高度）
 */
@Composable
fun MarkdownLazyContent(
    text: String,
    state: LazyListState,
    baseFontSize: TextUnit = 15.sp,
    textColor: Color? = null,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp
) {
    val blocks = rememberMarkdownBlocks(text)
    val bodyFg = textColor ?: MaterialTheme.colorScheme.onBackground
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        items(count = blocks.size, key = { it }) { i ->
            MarkdownBlockItem(block = blocks[i], baseFontSize = baseFontSize, bodyFg = bodyFg)
        }
    }
}

// 逐行判定用到的正则全部预编译：parseMarkdown 对流式累积全文每来一次都要跑一遍，
// 若在循环里现构造 Regex（Pattern 编译开销远大于匹配本身），长思考流式下会成为主线程热点。
// 注意 olNumberStripRegex 在列表项剥离（replace）与段落断开判定（matches）两处复用，语义一致。
private val hrLineRegex = Regex("^(-{3,}|\\*{3,}|_{3,})\$")
private val ulItemRegex = Regex("^[-*+]\\s.*")
private val ulBulletStripRegex = Regex("^[-*+]\\s")
private val olItemRegex = Regex("^\\d+\\.\\s.*")
private val olNumberStripRegex = Regex("^\\d+\\.\\s")

/**
 * internal 而非 private：图片块识别（独占行判定 + 段落断开）是可以脱离 UI 验证的纯逻辑，
 * 提成 internal 才能被 [MarkdownImageBlockTest] 直接测。抄一份正则进测试只会抄错、且错得没人发现。
 */
/**
 * 上一轮的解析结果，供 [parseMarkdownState] 做增量复用。
 *
 * [cut] 是「稳定边界」的字符偏移：该位置之前的所有块（前 [stableBlocks] 个）已完全由
 * 文本前缀 [0, cut) 决定，后续无论追加什么内容都不会改变它们——因为边界必定落在一个
 * 空行之后，而空行不属于任何块、也不会被后续行合并。代码围栏未闭合时解析会一路消费到
 * 文本末尾，不会在其后再产生边界，故复用段永远不含「还在长」的围栏。
 */
internal class MarkdownParseState(
    val text: String,
    val blocks: List<MarkdownBlock>,
    val cut: Int,
    val stableBlocks: Int
)

internal fun parseMarkdown(text: String): List<MarkdownBlock> = parseMarkdownState(text, null).blocks

/**
 * 解析 [text]；传入上一轮 [prev] 时只重解析稳定边界之后的尾部。
 *
 * 流式长思考链下全文重解析是卡顿主因：文本每帧都在长，O(n) 全量扫描与整篇行数组的
 * 分配叠加到高频快照上，GC 压力直接把整个聊天页拖卡。增量后成本只与「本轮新增的
 * 那一小段」成正比。复用前校验前缀未变，不满足则退回全量解析。
 */
internal fun parseMarkdownState(text: String, prev: MarkdownParseState?): MarkdownParseState {
    var base = 0
    val blocks = mutableListOf<MarkdownBlock>()
    if (prev != null && prev.cut > 0 && prev.cut <= prev.text.length &&
        prev.cut <= text.length && text.regionMatches(0, prev.text, 0, prev.cut)
    ) {
        base = prev.cut
        for (k in 0 until prev.stableBlocks.coerceAtMost(prev.blocks.size)) blocks.add(prev.blocks[k])
    }
    val lines = (if (base == 0) text else text.substring(base)).split("\n")
    // starts[j] = 第 j 行在【本次截取的尾部】里的起始偏移，用于反推绝对稳定边界。
    val starts = IntArray(lines.size + 1)
    for (j in lines.indices) starts[j + 1] = starts[j] + lines[j].length + 1
    if (lines.isNotEmpty()) starts[lines.size] = starts[lines.lastIndex] + lines.last().length
    var i = 0
    var cut = base
    var stableBlocks = blocks.size

    while (i < lines.size) {
        val line = lines[i]

        when {
            // Code block
            line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~") -> {
                val fence = line.trimStart()
                val isTilde = fence.startsWith("~~~")
                val lang = if (isTilde) fence.removePrefix("~~~").trim() else fence.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(if (isTilde) "~~~" else "```")) {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock(BlockType.CODE, codeLines.joinToString("\n"), lang = lang))
                i++
            }
            // 独占一行的图片（生图模型返回的就是这种形态）
            standaloneImagePattern.matches(line) -> {
                val m = standaloneImagePattern.find(line)!!
                blocks.add(
                    MarkdownBlock(
                        type = BlockType.IMAGE,
                        content = "",
                        imageUrl = m.groupValues[2],
                        imageAlt = m.groupValues[1]
                    )
                )
                i++
            }
            // Heading
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length
                val content = line.drop(level).trim()
                blocks.add(MarkdownBlock(BlockType.HEADING, content, level = level))
                i++
            }
            // Horizontal rule
            line.trim().matches(hrLineRegex) -> {
                blocks.add(MarkdownBlock(BlockType.HR, ""))
                i++
            }
            // Unordered list
            line.trimStart().matches(ulItemRegex) -> {
                val items = mutableListOf(line.trimStart().replaceFirst(ulBulletStripRegex, "").trim())
                i++
                while (i < lines.size && lines[i].trimStart().matches(ulItemRegex)) {
                    items.add(lines[i].trimStart().replaceFirst(ulBulletStripRegex, "").trim())
                    i++
                }
                blocks.add(MarkdownBlock(BlockType.UL, "", items = items))
            }
            // Ordered list
            line.trimStart().matches(olItemRegex) -> {
                val items = mutableListOf(line.trimStart().replace(olNumberStripRegex, ""))
                i++
                while (i < lines.size && lines[i].trimStart().matches(olItemRegex)) {
                    items.add(lines[i].trimStart().replace(olNumberStripRegex, ""))
                    i++
                }
                blocks.add(MarkdownBlock(BlockType.OL, "", items = items))
            }
            // Blockquote
            line.trimStart().startsWith(">") -> {
                val quoteLines = mutableListOf(line.trimStart().removePrefix(">").trim())
                i++
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines.add(lines[i].trimStart().removePrefix(">").trim())
                    i++
                }
                blocks.add(MarkdownBlock(BlockType.QUOTE, quoteLines.joinToString("\n")))
            }
            // Table
            line.contains("|") && i + 1 < lines.size && lines[i + 1].contains("---") -> {
                val rows = mutableListOf(
                    line.split("|").filter { it.isNotBlank() }.map { it.trim() }
                )
                i += 2
                while (i < lines.size && lines[i].contains("|")) {
                    rows.add(lines[i].split("|").filter { it.isNotBlank() }.map { it.trim() })
                    i++
                }
                blocks.add(MarkdownBlock(BlockType.TABLE, "", rows = rows))
            }
            // Empty line
            line.isBlank() -> {
                i++
                // 空行不属于任何块、也不会被后续行合并，它之前的块已全部定形。
                // 但仅当它后面还有已解析的行时才能当可复用边界：
                //  · 末尾那个空元素往往只是文本以「\n」结尾产生的切分假象，并非真空行；
                //  · 表格靠「下一行含 ---」判定，若表头后面暂无行，会被当成段落，
                //    分隔行后到时已无法回溯改判（增量结果与全量解析不一致）。
                // 要定形一个块，需要终止它的那一行已经存在；i < lines.size 保证这点。
                if (i < lines.size) {
                    cut = base + starts[i]
                    stableBlocks = blocks.size
                }
            }
            // Regular paragraph
            else -> {
                val paraLines = mutableListOf(line)
                i++
                var breakAfterHr = false
                while (i < lines.size && lines[i].isNotBlank()
                    && !lines[i].startsWith("#")
                    && !lines[i].trimStart().startsWith("```")
                    && !lines[i].trimStart().startsWith(">")
                    && !lines[i].trimStart().matches(ulItemRegex)
                    && !lines[i].trimStart().matches(olNumberStripRegex)
                    && !lines[i].trimStart().startsWith("|")
                    && !standaloneImagePattern.matches(lines[i])
                ) {
                    if (lines[i].trim().matches(hrLineRegex)) {
                        breakAfterHr = true
                        i++
                        break
                    }
                    paraLines.add(lines[i])
                    i++
                }
                // 超长段落按行切段：一个块一份 StaticLayout，无空行连续行的长思考
                // 整段合并会把 LineBreaker 撑到 OOM（见 addParagraphBlocks 的 KDoc）。
                addParagraphBlocks(blocks, paraLines)
                if (breakAfterHr) blocks.add(MarkdownBlock(BlockType.HR, ""))
            }
        }
    }
    return MarkdownParseState(text, blocks, cut, stableBlocks)
}

// 预编译：注释匹配排除 URL 中的 "//"（如 https://）
private val codeCommentRegex = Regex("(?<!:)//.*$")
private val codeStringRegex = Regex("\"[^\"]*\"")
private val codeWordSplitRegex = Regex("(\\b|(?<=\\W)(?=\\w)|(?<=\\w)(?=\\W))")
private val codeKeywords = setOf(
    "fun", "val", "var", "if", "else", "when", "for", "while", "return", "class", "object",
    "import", "package", "private", "public", "internal", "protected", "override", "abstract",
    "interface", "enum", "data", "sealed", "companion", "init", "constructor", "super", "this",
    "null", "true", "false", "is", "in", "as", "try", "catch", "finally", "throw", "suspend",
    "let", "apply", "also", "run", "with"
)

private fun highlightLine(line: String): AnnotatedString {
    return buildAnnotatedString {
        val commentMatch = codeCommentRegex.find(line)
        val codePart = if (commentMatch != null) line.substring(0, commentMatch.range.first) else line

        var last = 0
        for (m in codeStringRegex.findAll(codePart)) {
            if (m.range.first > last) appendHighlightedCode(codePart.substring(last, m.range.first))
            withStyle(SpanStyle(color = Color(0xFFCE9178))) { append(m.value) }
            last = m.range.last + 1
        }
        if (last < codePart.length) appendHighlightedCode(codePart.substring(last))

        if (commentMatch != null) {
            withStyle(SpanStyle(color = Color(0xFF6A9955))) { append(commentMatch.value) }
        }
    }
}

private fun AnnotatedString.Builder.appendHighlightedCode(s: String) {
    if (s.isEmpty()) return
    val words: List<String> = s.split(codeWordSplitRegex)
    words.forEach { w ->
        if (w in codeKeywords) {
            withStyle(SpanStyle(color = Color(0xFF569CD6))) { append(w) }
        } else if (w.toIntOrNull() != null || (w.startsWith("0x") && w.length > 2)) {
            withStyle(SpanStyle(color = Color(0xFFB5CEA8))) { append(w) }
        } else {
            append(w)
        }
    }
}
