package com.wy.openx.container

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 把镜像 rootfs 下载到 App 私有目录 filesDir/rootfs_images/（与手动导入镜像同一目录）。
 * 成功返回 file:// uri，供 [ContainerInstaller] 解压；取消时同步 cancel 底层 call 并清理半成品文件。
 */
class ContainerImageDownloader(private val context: Context) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 流式下载到 destination，进度经 [onProgress] 回调（bytesRead/totalBytes，total 可能为 -1）。
     * 返回 file:// uri；失败或取消时删除半成品文件并抛出异常。
     */
    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "rootfs_images").apply { mkdirs() }
        val dest = File(dir, fileName)
        val call = client.newCall(Request.Builder().url(url).build())
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    dest.delete()
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("响应为空")
                        val total = body.contentLength()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = 0L
                        body.byteStream().use { input ->
                            dest.outputStream().use { output ->
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    read += n
                                    onProgress(read, total)
                                }
                            }
                        }
                        cont.resume(Uri.fromFile(dest).toString())
                    } catch (e: Exception) {
                        dest.delete()
                        if (cont.isCancelled) return
                        cont.resumeWithException(e)
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}