package com.wy.openx.ui.screen

import android.content.Context
import android.graphics.BitmapFactory
import com.wy.openx.container.FileLogger
import com.wy.openx.container.Workspace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MarkdownImage"

/**
 * markdown 图片「保存到工作区」的状态。
 *
 * 原先用 `Any?` 表示（null=空闲、true=保存中、String=成功、false=失败），结果 UI 侧
 * 靠 `it is String` 判成败，把保存中的 `true` 判成了失败——点击后先闪「保存失败」，
 * 几百毫秒后才变「已保存」。改成 sealed 后 `when` 穷尽，漏掉状态由编译器报出来。
 */
internal sealed interface ImageSaveState {
    data object Idle : ImageSaveState
    data object Saving : ImageSaveState
    data class Done(val fileName: String) : ImageSaveState
    data object Failed : ImageSaveState
}

/**
 * 保存状态对应的提示文案；**空闲与保存中返回 null**（不显示任何结果文字）。
 * 抽成纯函数是为了能单测「保存中不得出现『保存失败』」这条。
 */
internal fun imageSaveFeedback(state: ImageSaveState): String? = when (state) {
    ImageSaveState.Idle, ImageSaveState.Saving -> null
    is ImageSaveState.Done -> "已保存 uploads/${state.fileName}"
    ImageSaveState.Failed -> "保存失败"
}

/** 与 MainViewModel 附件用的扩展名表保持一致。 */
internal val MARKDOWN_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

private fun mimeToExt(mimeType: String): String = when (mimeType.lowercase()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/bmp" -> "bmp"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    else -> "png"
}

/**
 * 从图片 URL 推导出落盘文件名。
 *
 * 模型给的地址形状很杂：带 query 的 CDN 链接、路径里没有扩展名的对象存储 key、
 * 甚至 `data:` 形态，所以规则是「取路径末段 → 清洗 → 有合法图片扩展名就用，没有就按 MIME 补」。
 * 纯函数，不碰 IO，便于单测。
 */
internal fun deriveImageFileName(rawUrl: String, mimeType: String): String {
    // 只取 path，丢掉 ?query#fragment——CDN 链接的签名参数常比文件名本身还长
    var path = rawUrl.trim()
    path = path.substringBefore('?').substringBefore('#')
    if (path.startsWith("data:")) path = ""
    // 注意不能先 trimEnd('/')：那会把 "https://a.b.c/" 变成 "https://a.b.c"，
    // 再取末段就把**主机名**当成文件名了（曾产出 a.b.png）。直接取末段，
    // 无末段时 substringAfterLast 返回空串，落到下面的 image 兜底。
    val last = path.substringAfterLast('/')
    val dot = last.lastIndexOf('.')
    val hasExt = dot > 0 && dot < last.length - 1
    val baseRaw = if (hasExt) last.substring(0, dot) else last
    val extFromUrl = if (hasExt) last.substring(dot + 1).lowercase() else ""
    val base = baseRaw
        .map { ch -> if (ch.code < 32 || ch in "\\/:*?\"<>|") '_' else ch }
        .joinToString("")
        .trim()
        .trim('.')
        .take(120)
        .ifBlank { "image" }
    val ext = if (extFromUrl in MARKDOWN_IMAGE_EXTENSIONS) extFromUrl else mimeToExt(mimeType)
    return "$base.$ext"
}

/**
 * 目标已存在时追加 `_1`/`_2`…，避免「保存」把上一张同名图静默覆盖掉。
 * 上限 1000 次——再极端就说明这个目录被塞满了，不该继续。
 */
internal fun uniqueFileIn(dir: File, name: String): File {
    val direct = File(dir, name)
    if (!direct.exists()) return direct
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    for (i in 1..1000) {
        val candidate = File(dir, if (ext.isBlank()) "${base}_$i" else "${base}_$i.$ext")
        if (!candidate.exists()) return candidate
    }
    return File(dir, "${base}_${System.currentTimeMillis()}.$ext")
}

/** 只读字节头拿 MIME，不解码像素——比整张解码便宜得多。 */
private fun probeMime(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.outMimeType ?: ""
    } catch (_: Exception) {
        ""
    }
}

/**
 * 把 markdown 里的远程图片存到工作区 `uploads` 目录。
 *
 * 目录与既有附件落盘口径一致：宿主 `filesDir/workspace/uploads`，容器内即 `/root/workspace/uploads`，
 * 这样文件浏览器和容器里的 AI 工具都能直接看到。
 *
 * 返回落盘文件；读不到就返回 null 并记日志，**不抛**。
 */
internal suspend fun saveMarkdownImageToUploads(context: Context, url: String, bytes: ByteArray): File? =
    withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext null
        try {
            val dir = File(Workspace.root(context), "uploads")
            if (!dir.isDirectory && !dir.mkdirs()) {
                FileLogger.w(TAG, "无法创建 uploads 目录: ${dir.absolutePath}")
                return@withContext null
            }
            val name = deriveImageFileName(url, probeMime(bytes))
            val target = uniqueFileIn(dir, name)
            target.outputStream().use { it.write(bytes) }
            FileLogger.i(TAG, "图片已保存到工作区: ${target.absolutePath}（${target.length()} 字节）")
            target
        } catch (e: Exception) {
            FileLogger.w(TAG, "保存图片到工作区失败: $url", e)
            null
        }
    }
