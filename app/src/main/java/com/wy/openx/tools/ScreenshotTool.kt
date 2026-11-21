package com.wy.openx.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ScreenshotTool : AgentTool {
    companion object {
        @Volatile var mediaProjection: MediaProjection? = null
        private var launchScreenCapture: ((Intent) -> Unit)? = null

        fun setLauncher(launcher: (Intent) -> Unit) {
            launchScreenCapture = launcher
        }

        /** Activity 销毁时调用，避免静态字段持有 Activity 泄漏 */
        fun clearLauncher() {
            launchScreenCapture = null
        }

        fun onPermissionResult(resultCode: Int, data: Intent?) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val ctx = AppContextHolder.context ?: return
                val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                // 旧投屏会话先释放，避免累积系统常驻通知与底层资源
                try { mediaProjection?.stop() } catch (_: Exception) {}
                mediaProjection = mgr.getMediaProjection(resultCode, data)
            } else {
                mediaProjection = null
            }
        }
    }

    override val name = "screenshot"
    override val description = "截取当前屏幕内容并保存为 JPEG 文件。首次使用需要授予屏幕录制权限（会弹出系统对话框），授权后即可正常截图。"
    override val parameters = buildToolParameters(
        ToolParameter("save_to", ParameterType.STRING, "截图保存路径（不传则保存到 Downloads/screenshots/）", required = false),
        ToolParameter("quality", ParameterType.NUMBER, "JPEG 质量 0-100（默认 90）", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val ctx = AppContextHolder.context ?: return "错误：应用上下文未初始化"
        val savePath = (args["save_to"] as? String)?.trim()
        val quality = ((args["quality"] as? Double)?.toInt()) ?: 90

        return withContext(Dispatchers.IO) {
            if (mediaProjection == null) {
                try {
                    val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    launchScreenCapture?.invoke(mgr.createScreenCaptureIntent())
                } catch (_: Exception) {}
                return@withContext "需要屏幕录制权限才能截图。请在弹出窗口中点击「立即开始」，然后重新调用 screenshot 工具。"
            }

            try {
                doCapture(ctx, savePath, quality)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { "截图失败: ${e.message ?: "未知错误"}" }
        }
    }

    private fun doCapture(ctx: Context, savePath: String?, quality: Int): String {
        val proj = mediaProjection ?: return "截图失败：未初始化 MediaProjection"

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels; val height = metrics.heightPixels; val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val virtualDisplay = proj.createVirtualDisplay(
            "screenshot", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        ) ?: run { reader.close(); return "截图失败：无法创建虚拟显示" }

        var image: android.media.Image? = null
        repeat(10) {
            image = reader.acquireLatestImage()
            if (image != null) return@repeat
            Thread.sleep(200)
        }
        if (image == null) { reader.close(); virtualDisplay.release(); return "截图失败：无法获取图像数据（尝试 10 次均超时）" }

        try {
            val plane = image!!.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // 按 rowStride 逐行读取，兼容带行填充(padding)的设备，避免花屏错位
            val pixels = IntArray(width * height)
            if (pixelStride == 4 && rowStride == width * 4) {
                buffer.asIntBuffer().get(pixels)
            } else {
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    for (col in 0 until width) {
                        val p = buffer.int
                        pixels[row * width + col] = p
                    }
                }
            }

            // PixelFormat.RGBA_8888 -> Bitmap.Config.ARGB_8888
            // RGBA int (little-endian): A@24 | B@16 | G@8 | R@0
            // ARGB int:                 A@24 | R@16 | G@8 | B@0
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] = (p and 0xFF00FF00.toInt()) or
                        ((p shl 16) and 0x00FF0000) or
                        ((p ushr 16) and 0x000000FF)
            }

            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

            // 安全策略：保存目录固定为应用外部文件下的 screenshots/，
            // save_to 仅允许指定文件名（拒绝路径分隔符），杜绝越权写入任意目录
            val baseDir = File(ctx.getExternalFilesDir(null), "screenshots")
            baseDir.mkdirs()
            val rawName = if (!savePath.isNullOrBlank()) File(savePath).name else
                "screenshot_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.jpg"
            val fileName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifBlank { "screenshot_${System.currentTimeMillis()}.jpg" }
            val outFile = File(baseDir, fileName)
            FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), it) }
            bitmap.recycle()

            return "✅ 截图已保存: ${outFile.absolutePath} (${width}x${height}, ${outFile.length() / 1024}KB)\n" +
                    "可用 file_manager 工具查看此文件。"
        } finally {
            image?.close()
            reader.close()
            virtualDisplay.release()
        }
    }
}