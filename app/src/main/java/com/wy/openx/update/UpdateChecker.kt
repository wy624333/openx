package com.wy.openx.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.wy.openx.BuildConfig
import com.wy.openx.ui.screen.FileOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用更新检查与下载。
 *
 * 版本信息取自 GitHub Releases API。国内直连 api.github.com 常被拦截，故按
 * [API_HOSTS] 顺序逐个尝试；拿到 asset 下载地址后，下载同样按镜像前缀
 * [MIRROR_PREFIXES] 逐个回退——镜像是把原始 GitHub 地址拼在前缀后面使用。
 *
 * 镜像时效性差：任一环节拿到可用结果即停止，全失败才报错。
 */
object UpdateChecker {

    private const val REPO = "wy624333/openx"

    /** 官方 API 与反代镜像。顺序即优先级。 */
    private val API_HOSTS = listOf(
        "https://api.github.com/repos/$REPO/releases/latest",
        "https://gh-proxy.com/https://api.github.com/repos/$REPO/releases/latest",
        "https://ghfast.top/https://api.github.com/repos/$REPO/releases/latest"
    )

    /**
     * Release 资产的镜像前缀：`前缀 + 原始 github.com 地址`。
     * 实测可用性会变，故保留多条，失败自动换下一条。
     */
    private val MIRROR_PREFIXES = listOf(
        "https://gh.monlor.com/",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://git.yylx.win/"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 远端最新版本。 */
    data class Release(
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val assetUrl: String,
        val assetName: String
    ) {
        /** 与当前安装版本比较。 */
        val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
    }

    /**
     * 查询最新 Release。
     * @return 成功时返回版本信息；网络失败或无 Release 时返回 null。
     */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        for (host in API_HOSTS) {
            val release = runCatching { parseRelease(host) }.getOrNull()
            if (release != null) return@withContext release
        }
        null
    }

    private fun parseRelease(apiUrl: String): Release? {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            // tag 形如 v2.10.0；versionCode 从更新说明里的 "versionCode: 103" 读取，
            // 缺失时退回按语义化版本比较（见 compareVersion）。
            val tag = json.optString("tag_name", "").trimStart('v', 'V')
            if (tag.isBlank()) return null
            val notes = json.optString("body", "")

            val assets = json.optJSONArray("assets")
            var assetUrl = ""
            var assetName = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    assetUrl = asset.optString("browser_download_url", "")
                    assetName = name
                    if (assetUrl.isNotBlank()) break
                }
            }

            return Release(
                versionName = tag,
                versionCode = parseVersionCode(notes) ?: versionCodeOf(tag),
                notes = notes,
                assetUrl = assetUrl,
                assetName = assetName.ifBlank { "OpenX-$tag.apk" }
            )
        }
    }

    /** 更新说明里约定的 `versionCode: 103` 一行。 */
    private fun parseVersionCode(notes: String): Int? {
        val match = Regex("versionCode\\s*[:：]\\s*(\\d+)", RegexOption.IGNORE_CASE).find(notes)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** 无 versionCode 标注时，用 主*10000 + 次*100 + 修订 折算，保证单调可比。 */
    private fun versionCodeOf(name: String): Int {
        val parts = name.split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major * 10000 + minor * 100 + patch
    }

    /**
     * 下载 APK 到 cacheDir/update/。
     * @param onProgress 已下载字节 / 总字节（总字节未知时为 0）。
     * @return 下载完成的文件；全部线路失败返回 null。
     */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        if (release.assetUrl.isBlank()) return@withContext null
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, release.assetName)
        if (target.isFile && target.length() > 0) target.delete()

        val candidates = MIRROR_PREFIXES.map { it + release.assetUrl } + release.assetUrl
        for (url in candidates) {
            val ok = runCatching { downloadFrom(url, target, onProgress) }.getOrDefault(false)
            if (ok) return@withContext target
            if (target.isFile) target.delete()
        }
        null
    }

    private fun downloadFrom(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val total = body.contentLength()
            var downloaded = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            return target.length() > 0
        }
    }

    /** 拉起系统安装器。未授予「安装未知应用」时先引导去授权。 */
    fun install(context: Context, apk: File) {
        if (!FileOpenHelper.isInstallablePackage(apk.name)) {
            Toast.makeText(context, "安装包文件名异常：${apk.name}", Toast.LENGTH_SHORT).show()
            return
        }
        FileOpenHelper.openFile(context, apk, "application/vnd.android.package-archive")
    }

    /** 是否已允许本应用安装未知来源应用（Android 8+ 为按应用授权）。 */
    fun canInstall(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return true
        return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    }

    /** 打开「安装未知应用」授权页。 */
    fun requestInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(context, "无法打开授权页面，请在系统设置中手动开启", Toast.LENGTH_SHORT).show()
        }
    }
}
