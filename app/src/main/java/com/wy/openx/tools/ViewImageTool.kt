package com.wy.openx.tools

import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「默认模型→识图模型」的落地消费工具：view_image。
 * 读取图片文件（支持容器路径 /root/workspace/... 与宿主绝对路径，走 ToolPaths 守卫），
 * 编码为 data URL，交给 AppContextHolder.visionAnalyzer（由 MainViewModel 注册，
 * 优先用设置里指定的识图专用 provider+model，未配置则跟随当前聊天模型）识别，
 * 返回模型对图片的文本解读作为工具结果。单图最大 12MB，超限报错提示。
 */
class ViewImageTool : AgentTool {
    override val name = "view_image"
    override val description = "查看一张图片并描述其内容。用于读取截图、示意图、报表、UI 界面等图像信息并转成后续步骤可用的文字结论。"
    override val parameters = buildToolParameters(
        ToolParameter("path", ParameterType.STRING, "图片路径（容器路径如 /root/workspace/a.png，或宿主绝对路径）"),
        ToolParameter("prompt", ParameterType.STRING, "针对图片的具体问题；缺省时描述图片里的关键内容", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val path = (args["path"] as? String)?.trim() ?: return "错误：未指定图片路径"
        val query = (args["prompt"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: "请仔细查看这张图片，描述其内容、布局与可提取的关键信息（文字/数字/结构）。"

        val file = withContext(Dispatchers.IO) {
            runCatching { ToolPaths.sanitize(path) }.getOrNull()
        } ?: return "错误：路径不可用或被安全策略拒绝「$path」"

        val bytes = withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) null
                else if (!file.isFile) null
                else {
                    val len = file.length()
                    if (len > MAX_BYTES || len <= 0) null
                    else file.readBytes()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        if (bytes == null) {
            return if (file.length() > MAX_BYTES)
                "错误：图片「${file.absolutePath}」超过 ${MAX_BYTES / 1024 / 1024}MB 上限，无法识别。请改用更小的图片或先压缩。"
            else "错误：图片「${file.absolutePath}」不存在或不是有效的图片文件"
        }

        val mime = guessMime(file.name)
        val dataUrl = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"

        val analyzer = AppContextHolder.visionAnalyzer
            ?: return "错误：识图模型不可用（未配置识图提供商或应用未初始化），无法完成图像识别。"
        val result = try {
            analyzer(dataUrl, query)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "错误：识图调用失败：${e.message ?: e.javaClass.simpleName}"
        }
        return if (result.isBlank()) "错误：识图模型返回为空，无法解读该图片。" else result.trim()
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext.isNotEmpty())
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.startsWith("image/") } ?: "image/png"
        else "image/png"
    }

    private companion object {
        /** 单图最大字节数：12MB，避免超大截图撑爆识图请求与会话内存。 */
        const val MAX_BYTES = 12 * 1024 * 1024
    }
}