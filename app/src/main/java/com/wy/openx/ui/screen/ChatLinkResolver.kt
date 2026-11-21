package com.wy.openx.ui.screen

/**
 * 聊天区 Markdown 链接的**目标解析**：区分「该交给编辑器的文件路径」与「该交给系统的网址」。
 *
 * ## 约定
 * AI 用 `[显示文本](目标)` 输出可点击路径，目标可以是：
 * - 容器绝对路径：`/root/workspace/src/Main.kt`
 * - `~` 开头：`~/workspace/a.kt`
 * - 相对工作区路径：`src/Main.kt`
 * - `file://` URL（可能带百分号编码）
 * - 以上任一 + `:行号` 后缀：`src/Main.kt:42`（也可写成 `...#L42`）
 *
 * ## 为什么必须区分「无 scheme」的两种形态
 * `scheme` 语法允许 `.`，所以工作区下的 `notes.md:12` 与 `mailto:x` 在语法上**无从区分**
 * （两者都是「scheme 部分不含 `://`」）。若按「有冒号就是网址」判定，文件链接会被丢给浏览器、
 * 最终拼成 `https://notes.md:12` 这种打不开的地址。故不带 `//` 的 scheme 只能**白名单枚举**。
 *
 * 这些函数是纯逻辑（无 Android 依赖），供单测覆盖。
 */

/** 带 `//` 的层级式 scheme：`http://`、`https://`、`file://`、`content://` 等。 */
internal val HIERARCHICAL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

/**
 * 不带 `//` 的网址型 scheme 白名单。语法允许 `.`，不能靠「有冒号即网址」判定，
 * 否则 `notes.md:12` 会被误当网址（见文件头说明）。
 */
internal val OPAQUE_URL_SCHEME = Regex(
    "^(mailto|tel|sms|smsto|geo|data|market|intent|about|javascript|magnet|bitcoin|ws|wss):",
    RegexOption.IGNORE_CASE
)

/** 结尾的 `:行号`。 */
internal val TRAILING_LINE = Regex("^(.*):(\\d+)$")

/** `#L42` 形式的锚点行号（搜索式，出现在路径末尾，如 `a.kt#L42`）。 */
internal val ANCHOR_LINE = Regex("#L(\\d+)", RegexOption.IGNORE_CASE)

/**
 * 链接目标 → 归一化后的文件路径（已去 `file://`、百分号解码）。
 * 判定为网址（http/https/mailto 等）时返回 null，调用方应委托给系统。
 */
fun asChatFilePath(uri: String): String? {
    val trimmed = uri.trim()
    if (trimmed.isEmpty()) return null
    val hierarchical = HIERARCHICAL_SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()
    return when {
        hierarchical == "file" -> decodePath(trimmed.substringAfter("://"))
        hierarchical != null -> null // http / https / content / ftp … 交给系统
        OPAQUE_URL_SCHEME.containsMatchIn(trimmed) -> null // mailto / tel / data …
        else -> decodePath(trimmed) // 容器绝对路径、~ 路径、相对路径（含 notes.md:12）
    }
}

/**
 * 拆出可选的 `:行号` / `#L行号` 后缀；无则行号为 0。
 *
 * 顺序要紧：先记下 `#L42` 的行号并**从右往左**逐层剥离，否则 `a.kt:42#L42` 会把
 * `:42` 留在路径里。两种都出现时以显式的 `:N` 为准（它更接近 AI 的书写习惯）。
 */
fun splitPathAndLine(rawPath: String): Pair<String, Int> {
    var p = rawPath.trim()
    // ① 锚点行号（可能不存在），并把 fragment 整段剥掉
    var anchorLine = 0
    ANCHOR_LINE.find(p)?.let { m ->
        anchorLine = m.groupValues[1].toIntOrNull() ?: 0
    }
    p = p.substringBefore('#').trim()
    // ② 结尾的 `:行号`
    val m = TRAILING_LINE.find(p)
    if (m != null) {
        val path = m.groupValues[1]
        // 路径部分为空（`:42`）时不认这个行号，否则会返回空路径打不开文件。
        if (path.isNotBlank()) return path to (m.groupValues[2].toIntOrNull() ?: anchorLine)
    }
    return p to anchorLine
}

private fun decodePath(s: String): String =
    runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)