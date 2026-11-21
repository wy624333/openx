package com.wy.openx.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全应用背景图渲染层。
 *
 * 用 Compose 重写。分层自下而上：
 *   1. 兜底底色（主题背景色，保证图片未加载/失败时不是黑屏）；
 *   2. 背景图（本地文件 / 图片直链），按焦点 + 缩放摆放；
 *   3. 蒙版（模糊 + 白/黑叠加 + 明暗），把图片压淡，让上层文字与卡片清晰。
 *
 * 放在主题包而不是某个页面：它是**全应用**的根背景，由 MainActivity 在最底层渲染一次，
 * 各页面内容区用透明底透出它。否则每页各画一遍，滚动时图片会跟着内容动、且重复解码。
 */

/** 解析后的背景配置（已把 settings 里的原始字段整理成渲染需要的形态）。 */
data class BackgroundSpec(
    val enabled: Boolean,
    val sourceType: String,   // "none" / "local" / "remote"
    val localPath: String,
    val remoteUrl: String,
    val blurSigma: Float,
    val overlayAlpha: Float,
    val brightness: Float,
    val focalX: Float,
    val focalY: Float,
    val imageScale: Float
) {
    /** 是否真的有可渲染的图源。 */
    val hasSource: Boolean
        get() = enabled && when (sourceType) {
            "local" -> localPath.isNotBlank()
            "remote" -> remoteUrl.isNotBlank()
            else -> false
        }

    companion object {
        val Disabled = BackgroundSpec(
            enabled = false, sourceType = "none", localPath = "", remoteUrl = "",
            blurSigma = 8f, overlayAlpha = 0.18f, brightness = 1f,
            focalX = 0f, focalY = 0f, imageScale = 1f
        )
    }
}

/** 从 settings 组装渲染用的 [BackgroundSpec]。 */
fun com.wy.openx.data.model.AppSettings.toBackgroundSpec(): BackgroundSpec = BackgroundSpec(
    enabled = bgEnabled,
    sourceType = bgSourceType,
    localPath = bgLocalPath,
    remoteUrl = bgRemoteUrl,
    blurSigma = bgBlurSigma,
    overlayAlpha = bgOverlayAlpha,
    brightness = bgBrightness,
    focalX = bgFocalX,
    focalY = bgFocalY,
    imageScale = bgImageScale
)

/**
 * 全应用背景层。未启用或无有效图源时**不渲染任何东西**，由调用方决定是否还需要兜底底色。
 *
 * @param fallbackColor 图片未加载/加载失败时的兜底底色（取主题背景色）。
 * @return 是否实际渲染了背景（供调用方判断要不要额外铺底）。
 */
@Composable
fun AppBackgroundLayer(
    spec: BackgroundSpec,
    fallbackColor: Color,
    modifier: Modifier = Modifier
): Boolean {
    if (!spec.hasSource) return false

    var bitmap by remember(spec.sourceType, spec.localPath, spec.remoteUrl) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    // 远程图异步加载；本地图同样放 IO（大图解码不能卡主线程）
    LaunchedEffect(spec.sourceType, spec.localPath, spec.remoteUrl) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = when (spec.sourceType) {
                    "local" -> File(spec.localPath).takeIf { it.isFile }?.readBytes()
                    "remote" -> if (spec.remoteUrl.startsWith("http")) {
                        val conn = (java.net.URL(spec.remoteUrl).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 15_000
                            readTimeout = 20_000
                            instanceFollowRedirects = true
                        }
                        conn.inputStream.use { it.readBytes() }
                    } else null
                    else -> null
                } ?: return@runCatching null
                // 背景图要铺满全屏，按 2560 采样足够，避免 4K 原图占几十 MB 内存
                decodeSampledBitmap(bytes, 2560)
            }.getOrNull()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1) 兜底底色：图片还没 decode 完时也有底，不会闪黑
        Box(Modifier.fillMaxSize().background(fallbackColor))

        val blur = spec.blurSigma.coerceIn(0f, 24f)
        val alpha = spec.overlayAlpha.coerceIn(0f, 0.55f)
        val brightness = spec.brightness.coerceIn(0.5f, 1.5f)

        // 2) 背景图本体：按焦点 + 缩放摆放，并把「背景柔化」作用在**图片**上。
        //
        // 注意模糊必须加在图片层、不能加在下面的蒙版上：蒙版是一块纯色，
        // 对纯色做模糊在视觉上等于没做（Blur 作用于元素自身，不是它后面的内容）。
        // 柔化图片后再叠蒙版。
        //
        // 局限：Modifier.blur 在 Android 12(API 31) 以下不生效（静默降级为无模糊）。
        // 本项目 minSdk 24，旧机型上「背景柔化」滑条会没有视觉效果，其余设置正常。
        val bmp = bitmap
        if (bmp != null) {
            val scale = spec.imageScale.coerceIn(1f, 3f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blur > 0f) Modifier.blur(blur.dp) else Modifier)
                    .graphicsLayer {
                        // 放大：焦点为锚点，避免放大后焦点跑出视野
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(
                            (spec.focalX.coerceIn(-1f, 1f) + 1f) / 2f,
                            (spec.focalY.coerceIn(-1f, 1f) + 1f) / 2f
                        )
                    }
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 3) 蒙版：透明度 + 明暗（颜色跟随主题明暗，把图片压淡以保证上层文字清晰）
        val isDark = currentThemeColors.background.luminance() < 0.5f
        val maskColor = if (isDark) Color.Black else Color.White
        Box(Modifier.fillMaxSize().background(maskColor.copy(alpha = alpha)))
        // 明暗：亮 → 叠白，暗 → 叠黑。用极轻的透明度避免盖过蒙版强度设置。
        if (brightness != 1f) {
            val delta = (brightness - 1f).coerceIn(-0.5f, 0.5f)
            val tint = if (delta > 0f) Color.White else Color.Black
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tint.copy(alpha = kotlin.math.abs(delta)))
            )
        }
    }
    return true
}

/** 从字节数组降采样解码（背景图不需要 EXIF 精细摆正，采样即可）。 */
private fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int): android.graphics.Bitmap? {
    if (bytes.isEmpty()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDim) sampleSize *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Exception) {
        null
    }
}
