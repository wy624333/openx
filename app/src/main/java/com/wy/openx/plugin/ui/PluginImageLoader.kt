package com.wy.openx.plugin.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wy.openx.container.FileLogger
import com.wy.openx.data.provider.ProviderHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 插件 `Image` 组件的图片加载。
 *
 * ## 为什么不引第三方库
 * 项目里没有 Coil/Glide，而为了一个插件组件去引入图片加载库（连带 KTX、协程扩展、
 * 内存缓存策略）不划算。这里只做**三种源**的解码，覆盖插件实际会用到的场景：
 *
 *  1. `data:image/png;base64,...` —— 插件自己内联的小图（图标、二维码）
 *  2. `http(s)://...`           —— 网络图（需 `http` 权限）
 *  3. `asset://相对路径`        —— 插件**自己包内**的素材（表情图、图标）
 *  4. 本地绝对路径               —— 图片处理类插件读写磁盘（需 `file` 权限，由调用方判断）
 *
 * ## 限制
 * 响应上限 [MAX_BYTES]，防止插件一张巨图把内存吃穿。超限返回 null（渲染占位），
 * 不抛异常 —— 图片加载失败不该让整个插件界面崩掉。
 *
 * 没有实现磁盘缓存：插件图片通常是小图、低频，加了缓存反而要处理失效与清理。
 */
internal object PluginImageLoader {

    private const val TAG = "PluginImageLoader"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val TIMEOUT_SEC = 20L
    private const val ASSET_PREFIX = "asset://"

    /** 是否为网络源（调用方据此决定要不要 http 权限）。 */
    fun isRemote(src: String): Boolean =
        src.startsWith("http://", true) || src.startsWith("https://", true)

    /** 是否为插件包内素材（`asset://相对路径`）。 */
    fun isAsset(src: String): Boolean = src.startsWith(ASSET_PREFIX, ignoreCase = true)

    /**
     * 已解码图片的内存缓存（按字节数计量）。
     *
     * 立绘轮换这类场景会反复换图，没缓存的话每次换图都要重新读盘 + 解码几百 KB，
     * 换图瞬间是空白的（看着像闪一下）。缓存后换回看过的图是即时的。
     * 上限 24MB ≈ 30 张 420×420 ARGB 图；再大就要担心低端机内存了。
     */
    private val cache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(
        24 * 1024 * 1024
    ) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap): Int =
            value.width * value.height * 4
    }

    /**
     * 解码图片。失败返回 null（由 UI 渲染占位）。
     *
     * [assetDir] 是插件自己的安装目录，用来解析 `asset://` 包内素材。
     * 素材随插件一起分发，不是用户文件，所以**不需要 `file` 权限**。
     */
    suspend fun load(src: String, assetDir: String? = null): ImageBitmap? {
        if (src.isBlank()) return null
        // key 带上 assetDir：不同插件的 asset:// 可能同名不同图
        val cacheKey = cacheKeyOf(src, assetDir)
        cache.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = when {
                    src.startsWith("data:", true) -> decodeDataUri(src)
                    isRemote(src) -> download(src)
                    isAsset(src) -> readAsset(src, assetDir)
                    else -> readLocal(src)
                } ?: return@runCatching null
                if (bytes.isEmpty()) return@runCatching null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.onFailure {
                FileLogger.e(TAG, "图片解码失败 (${src.take(60)}): ${it.message}")
            }.getOrNull()?.also { cache.put(cacheKey, it) }
        }
    }

    /**
     * 缓存 key。
     *
     * 对本地文件（含 `asset://`）额外带 mtime：插件重装、素材被替换后路径不变，
     * 但缓存里的旧图还在 —— 用户换了图却仍看到旧的，极易误判成“没生效”。
     */
    private fun cacheKeyOf(src: String, assetDir: String?): String {
        val base = (assetDir ?: "") + "|" + src
        val f = resolveFile(src, assetDir) ?: return base
        return base + "|" + f.lastModified()
    }

    /**
     * 把 `asset://相对路径` 或绝对路径解析成插件目录内的文件；越界/不存在返回 null。
     *
     * 只允许落在**当前插件自己的目录**内：`asset://../其它插件/x.png` 这类越界路径直接拒绕。
     * 不校验的话 `asset://` 就成了绕开文件沙箱读任意文件的口子（即使只能当图片解码，
     * 也足够探测文件是否存在）。
     */
    private fun resolveFile(src: String, assetDir: String?): File? {
        if (!isAsset(src)) {
            val f = File(src)
            return if (f.isFile) f else null
        }
        if (assetDir.isNullOrBlank()) return null
        val rel = src.substring(ASSET_PREFIX.length).trimStart('/')
        if (rel.isEmpty()) return null
        val base = File(assetDir).canonicalFile
        val target = File(base, rel).canonicalFile
        if (!target.path.startsWith(base.path + File.separator)) {
            FileLogger.e(TAG, "asset 路径越界：$src")
            return null
        }
        return target
    }

    /** 读插件包内素材（见 [resolveFile] 的越界说明）。 */
    private fun readAsset(src: String, assetDir: String?): ByteArray? {
        val target = resolveFile(src, assetDir) ?: return null
        if (!target.isFile) return null
        return readLocal(target.path)
    }

    /** `data:[<mime>][;base64],<payload>`。 */
    private fun decodeDataUri(uri: String): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma)
        val payload = uri.substring(comma + 1)
        return runCatching {
            if (meta.contains("base64", ignoreCase = true)) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                // 非 base64 的 data URI 是 URL 编码的
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun readLocal(path: String): ByteArray? {
        val f = File(path)
        if (!f.isFile) return null
        if (f.length() > MAX_BYTES) {
            FileLogger.e(TAG, "本地图片超过上限：${f.length()} bytes")
            return null
        }
        return runCatching { f.readBytes() }.getOrNull()
    }

    private fun download(url: String): ByteArray? {
        // 复用宿主 client，但单独设超时：插件图片不该拖住界面
        val client = ProviderHttp.shortClient.newBuilder()
            .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        return runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                if (body.contentLength() > MAX_BYTES) return@use null
                body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_BYTES) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }
        }.getOrNull()
    }
}
