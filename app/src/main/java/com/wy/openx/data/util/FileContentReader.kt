package com.wy.openx.data.util

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

object FileContentReader {
    private const val MAX_READ_CHARS = 2_000_000

    fun readText(context: Context, uri: Uri): Result<String> {
        return runCatching {
            context.contentResolver.openInputStream(uri).use { inputStream ->
                requireNotNull(inputStream) { "无法打开文件流" }
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val buf = CharArray(MAX_READ_CHARS)
                    val read = reader.read(buf)
                    val content = if (read < 0) ""
                    else String(buf, 0, read)
                    // 超大文件截断，防止 OOM
                    if (reader.read() >= 0) {
                        content + "\n\n[文件过大，已截断至前 ${MAX_READ_CHARS / 10000} 万字符]"
                    } else content
                }
            }
        }
    }
}
