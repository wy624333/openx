package com.wy.openx.plugin.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 插件文件/图片选择用到的 Uri 读写工具。
 *
 * 单独放一个文件的原因：这些是纯 Android API 调用（不涉及 Compose），
 * 放在界面文件里会淹没真正的 UI 逻辑，也不好单独测试。
 */
internal object PluginUriUtils {

    /** 单次读取上限：防止用户选了个几百 MB 的文件把内存吃掉。 */
    private const val MAX_TEXT_BYTES = 4L * 1024 * 1024

    /** 图片上限：data URI 会膨胀 4/3，8MB 原图 → 约 10.7MB 字符串，够用了。 */
    private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

    /** 从 content Uri 取显示名（失败时退回路径末段）。 */
    fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "未命名"

    /** 读取文本内容（UTF-8）。超限时截断而不是失败。 */
    fun readText(context: Context, uri: Uri): PluginHostActions.PickedFile? = runCatching {
        val name = displayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readAtMost(MAX_TEXT_BYTES)
        } ?: return@runCatching null
        PluginHostActions.PickedFile(
            name = name,
            content = String(bytes, Charsets.UTF_8),
            size = bytes.size.toLong()
        )
    }.getOrNull()

    /**
     * 写入文本到 content Uri（SAF 保存目标）。
     * @return 成功返回显示名，失败返回 null
     */
    fun writeText(context: Context, uri: Uri, content: String): String? = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: return@runCatching null
        displayName(context, uri).ifBlank { "已保存" }
    }.getOrNull()

    /**
     * 读成 `data:image/png;base64,...`。
     *
     * 之所以转 data URI：`ctx.UI.Image({src})` 直接就能用，
     * 插件不需要再关心"这是 content:// 还是 file://"——而 content:// 权限
     * 一旦离开这次会话就失效，存路径根本没用。
     */
    fun toDataUri(context: Context, uri: Uri): String? = runCatching {
        val mime = context.contentResolver.getType(uri) ?: "image/png"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readAtMost(MAX_IMAGE_BYTES)
        } ?: return@runCatching null
        if (bytes.isEmpty()) return@runCatching null
        "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    /** 最多读 [limit] 字节。 */
    private fun java.io.InputStream.readAtMost(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) {
                out.write(buf, 0, (n - (total - limit)).toInt().coerceAtLeast(0))
                break
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
