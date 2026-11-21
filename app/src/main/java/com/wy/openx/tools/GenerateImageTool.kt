package com.wy.openx.tools

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wy.openx.data.provider.ProviderHttp
import com.wy.openx.data.provider.joinUrl
import com.wy.openx.data.model.ProviderType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 「默认模型→生图模型」的消费工具：generate_image。
 * 读取设置里指定的生图专用 provider+model，调用 OpenAI 兼容的 images/generations 接口
 * （response_format=b64_json 优先，网关只返回 url 时下载），把生成的图片保存到
 * `/root/workspace/generated_images/`（容器视角）并在结果里返回容器/宿主路径与大小。
 *
 * 与识图/压缩/起标题不同：生图**不跟随当前聊天模型**（聊天模型通常是纯文本模型，没有
 * 图像输出能力）。未配置生图模型时直接返回错误并引导去「设置→默认模型→生图模型」配置。
 */
class GenerateImageTool : AgentTool {
    override val name = "generate_image"
    override val description = "按文字描述生成一张图片并保存到工作区。需要先在 设置→默认模型→生图模型 里配置支持图像输出的专用模型（如 gpt-image / dall-e）；未配置将报错。"
    override val parameters = buildToolParameters(
        ToolParameter("prompt", ParameterType.STRING, "图片内容描述（越具体越好：主体、风格、构图、光线、画幅）"),
        ToolParameter("size", ParameterType.STRING, "输出尺寸，如 1024x1024 / 512x512 / 256x256（部分模型自定义），默认 1024x1024", required = false),
        ToolParameter("quality", ParameterType.STRING, "画质：auto / low / medium / high（部分模型识别），默认 auto", required = false)
    )

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    override suspend fun execute(args: Map<String, Any>): String {
        val prompt = (args["prompt"] as? String)?.trim() ?: ""
        if (prompt.isBlank()) return "错误：未指定图片描述（prompt）"
        val size = (args["size"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: "1024x1024"
        val quality = (args["quality"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: "auto"

        // ===== 解析「生图模型」专用配置：未配置 = 报错（不跟随聊天模型） =====
        val settings = AppContextHolder.currentSettings
        val pid = settings?.imageGenProviderId?.trim().orEmpty()
        val model = settings?.imageGenModel?.trim().orEmpty()
        if (pid.isBlank() || model.isBlank()) {
            return "错误：生图模型未配置。\n\n生图不跟随聊天模型（聊天模型通常无图像输出能力），请先到「设置 → 默认模型 → 生图模型」里选择一个支持图像输出的模型（如 gpt-image-1、dall-e-3），配置后再调用 generate_image。"
        }
        val provider = AppContextHolder.allProviders.firstOrNull { it.id == pid }
        if (provider == null) {
            return "错误：生图模型对应的提供商（id=$pid）已不存在，可能已被删除。请到「设置 → 默认模型 → 生图模型」重新选择。"
        }
        if (!provider.isEnabled) {
            return "错误：生图模型提供商「${provider.name}」已停用，请在提供商列表启用后再试。"
        }
        val key = provider.firstUsableApiKey
        if (key.isBlank()) {
            return "错误：生图模型提供商「${provider.name}」没有可用的 API Key，请在提供商列表补充后再试。"
        }
        if (provider.type == ProviderType.ANTHROPIC || provider.type == ProviderType.GEMINI) {
            return "错误：当前只支持 OpenAI 兼容的生图接口，提供商「${provider.name}」类型暂不支持生图。请换用 OpenAI 兼容网关提供商并配置支持图像输出的模型。"
        }

        val client = ProviderHttp.clientFor(provider)
        val urlStr = if (provider.useFullUrl) provider.baseUrl else joinUrl(provider.baseUrl, "images/generations")

        // ===== 发请求 =====
        val bodyObj = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", prompt)
            addProperty("n", 1)
            addProperty("size", size)
            if (quality != "auto") addProperty("quality", quality)
            addProperty("response_format", "b64_json")
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                requestImage(client, urlStr, provider.type, key, provider.userAgent, gson.toJson(bodyObj))
            }
        }.getOrElse { throw it }

        if (result == null) return "错误：生图接口未返回图片数据。"

        // ===== 落盘到工作区 =====
        val fileName = "gen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" +
            (0..9999).random().toString().padStart(4, '0') + ".png"
        val containerPath = "/root/workspace/generated_images/$fileName"
        val hostFile = withContext(Dispatchers.IO) {
            (AppContextHolder.containerEngine?.toHostFileIfContainerPath(containerPath))
                ?: ToolPaths.sanitize(containerPath)
        }
        if (hostFile == null) return "错误：无法解析工作区路径「$containerPath」。"

        val bytes = try {
            withContext(Dispatchers.IO) {
                val parent = hostFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) return@withContext null
                hostFile.writeBytes(result); result
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return "错误：图片写入失败（${e.message?.take(120)}）"
        }
        if (bytes == null) return "错误：无法创建图片目录，保存失败。"

        val kb = bytes.size / 1024
        return buildString {
            append("生图成功。图片已保存：\n")
            append("- 容器路径：").append(containerPath).append('\n')
            append("- 宿主路径：").append(hostFile.absolutePath).append('\n')
            append("- 大小：").append(kb).append(" KB，尺寸：").append(size).append('\n')
            append("- 模型：").append(provider.name).append(" / ").append(model).append('\n')
            append("AI 可用 FileManager/readFile 等工具继续处理该图片（先用 view_image 查看确认效果）。\n")
            // 聊天页解析用的图片标记行（不参与 AI 语义，仅 UI 渲染缩略图）
            append("@image:").append(containerPath)
        }
    }

    /** 请求生图接口：解码 b64_json，或按 url 下载；返回图片字节或 null。 */
    private fun requestImage(
        client: okhttp3.OkHttpClient,
        urlStr: String,
        type: ProviderType,
        apiKey: String,
        userAgent: String,
        body: String
    ): ByteArray? {
        val builder = Request.Builder().url(urlStr).post(body.toRequestBody(JSON))
        ProviderHttp.applyAuth(builder, apiKey, type)
        ProviderHttp.applyUserAgent(builder, userAgent)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("生图接口 HTTP ${resp.code}：${resp.body?.string()?.take(300) ?: resp.message}")
            }
            val text = resp.body?.string() ?: throw IllegalStateException("生图接口响应为空")
            val json = try {
                gson.fromJson(text, JsonObject::class.java)
            } catch (e: Exception) {
                throw IllegalStateException("生图接口响应不是合法 JSON：${text.take(300)}")
            }
            val data = json.getAsJsonArray("data")
            if (data == null || data.size() == 0) {
                val err = json.getAsJsonObject("error")
                val detail = err?.get("message")?.asString
                throw IllegalStateException("生图接口未返回图片：${detail ?: text.take(200)}")
            }
            val first = data[0].asJsonObject
            if (first.has("b64_json")) {
                return Base64.decode(first.get("b64_json").asString, Base64.NO_WRAP)
            }
            if (first.has("url")) {
                val u = first.get("url").asString
                val download = Request.Builder().url(u).get().build()
                return client.newCall(download).execute().use { img ->
                    if (!img.isSuccessful) throw IllegalStateException("下载生成图片失败：HTTP ${img.code}")
                    img.body?.bytes() ?: throw IllegalStateException("下载生成图片结果为空")
                }
            }
            throw IllegalStateException("生图接口响应中既无 b64_json 也无 url：${text.take(300)}")
        }
    }
}