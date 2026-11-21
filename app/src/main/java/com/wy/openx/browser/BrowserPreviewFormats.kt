package com.wy.openx.browser

/**
 * 内置浏览器可直接预览的文件格式（侧边栏长按菜单「用浏览器打开」的准入清单）。
 *
 * 之所以单独抽出来：菜单（`FileTreeDrawer`）要知道「哪些文件给入口」，
 * [BrowserManager] 要知道「哪些文件要包一层播放器页面」——两处必须用同一份清单，
 * 否则会出现「菜单有入口、打开却白屏」或反之。
 *
 * 只收 Android WebView（Chromium）确实能渲染的类型：
 * - 文本：HTML 直接渲染，其余按纯文本显示；
 * - 图片：位图与 SVG（WebView 原生支持）；
 * - 音视频：靠 `media` 标签播放，容器/编码需为 Chromium 支持的组合
 *   （mp4/H.264+AAC、webm/VP8·VP9+Opus、mp3、m4a/AAC、ogg、opus、flac、wav）。
 *
 * **不收**：pdf（WebView 无内置 PDF 渲染器）、mkv/avi（Chromium 不支持这些容器）、
 * 以及各类压缩包/office 文档。
 */
object BrowserPreviewFormats {

    /** 文本类：HTML 渲染，其余纯文本预览。 */
    val TEXT_EXTS: Set<String> = setOf(
        "html", "htm", "xhtml", "svg", "xml", "txt", "text", "log",
        "json", "jsonl", "js", "mjs", "css", "md", "markdown",
        "csv", "tsv", "yaml", "yml", "ini", "conf", "cfg", "toml"
    )

    /** 图片类：Chromium 能直接解码显示的位图 + SVG。 */
    val IMAGE_EXTS: Set<String> = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "avif"
    )

    /** 音频类。 */
    val AUDIO_EXTS: Set<String> = setOf(
        "mp3", "m4a", "m4b", "aac", "wav", "ogg", "oga", "opus", "flac", "weba"
    )

    /** 视频类（容器 + 编码需为 Chromium 支持的组合）。 */
    val VIDEO_EXTS: Set<String> = setOf(
        "mp4", "m4v", "webm", "ogv", "mov", "3gp", "3gpp"
    )

    /** 全部可预览扩展名（小写，不带点）。 */
    val ALL_EXTS: Set<String> = TEXT_EXTS + IMAGE_EXTS + AUDIO_EXTS + VIDEO_EXTS

    /** 媒体种类：决定包装页用 `<audio>` 还是 `<video>`。 */
    enum class MediaKind { AUDIO, VIDEO }

    /** 取小写扩展名（无扩展名返回空串）。 */
    fun extOf(pathOrName: String): String =
        pathOrName.substringAfterLast('.', "").lowercase()

    /** 该文件名/路径是否值得给「用浏览器打开」入口。 */
    fun isPreviewable(pathOrName: String): Boolean = extOf(pathOrName) in ALL_EXTS

    /** 音视频返回对应种类；其余（含未知扩展名）返回 null。 */
    fun mediaKind(pathOrName: String): MediaKind? = when (val ext = extOf(pathOrName)) {
        in AUDIO_EXTS -> MediaKind.AUDIO
        in VIDEO_EXTS -> MediaKind.VIDEO
        else -> null
    }
}
