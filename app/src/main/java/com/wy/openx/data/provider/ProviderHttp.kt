package com.wy.openx.data.provider

import com.wy.openx.data.model.AIProviderConfig
import com.wy.openx.data.model.defaultProviderApiPath
import com.wy.openx.data.model.ProviderType
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 为某次请求构建 OkHttpClient：按 provider 级代理（开启时）或直连（关闭时）建立连接。
 *
 * openx 目前没有全局代理设置，提供商代理关闭时直接使用默认
 * 直连 client；开启且配置合法时，按 provider 的 HTTP/SOCKS5 配置构建独立 client。
 */
object ProviderHttp {
    /** 直连 client（openx 全局默认，无代理）。 */
    val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 短超时 client，供模型列表拉取 / 连通性测试等非聊天请求使用。 */
    val shortClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun clientFor(config: AIProviderConfig): OkHttpClient {
        if (!config.proxyEnabled || config.proxyHost.isBlank() || config.proxyPort !in 1..65535) {
            return directClient
        }
        val type = when (config.proxyType) {
            com.wy.openx.data.model.ProxyType.HTTP -> Proxy.Type.HTTP
            com.wy.openx.data.model.ProxyType.SOCKS5 -> Proxy.Type.SOCKS
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .proxy(Proxy(type, InetSocketAddress(config.proxyHost, config.proxyPort)))
        if (config.proxyUsername.isNotBlank()) {
            builder.proxyAuthenticator(proxyAuthenticator(config.proxyUsername, config.proxyPassword))
        }
        return builder.build()
    }

    private fun proxyAuthenticator(username: String, password: String): Authenticator =
        object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (response.code != 407) return null
                return response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(username, password))
                    .build()
            }
        }

    /** 构建目标 URL：useFullUrl 直接用 baseUrl，否则按 provider 类型拼 API 路径。 */
    fun resolveChatUrl(config: AIProviderConfig): String {
        if (config.useFullUrl) return config.baseUrl
        val path = if (config.useResponseApi) "v1/responses" else defaultProviderApiPath(config.type)
        return joinUrl(config.baseUrl, path)
    }

    /** 构建模型列表 URL。 */
    fun resolveModelsUrl(config: AIProviderConfig): String {
        val base = config.baseUrl.trimEnd('/')
        val path = if (config.type == ProviderType.GEMINI) "v1beta/models" else "v1/models"
        return joinUrl(base, path)
    }

    /** 给请求附加认证头（OpenAI 兼容用 Bearer；Anthropic/Gemini 用各自头，openx 未实现其原生协议，仅保留头构造）。 */
    fun applyAuth(builder: Request.Builder, apiKey: String, type: ProviderType): Request.Builder =
        when (type) {
            ProviderType.ANTHROPIC -> builder
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
            ProviderType.GEMINI -> builder.header("x-goog-api-key", apiKey)
            else -> builder.header("Authorization", "Bearer $apiKey")
        }

    fun applyUserAgent(builder: Request.Builder, userAgent: String): Request.Builder =
        if (userAgent.isNotBlank()) builder.header("User-Agent", userAgent) else builder
}

private val VERSION_SEGMENT_REGEX = Regex("""^v\d+.*$""", RegexOption.IGNORE_CASE)

/**
 * 拼接 baseUrl 与 API 路径：容忍尾斜杠、以及 baseUrl 已含版本段（如 /v1、/api/v3、/v1beta）
 * 的情况，避免重复或冲突。
 */
fun joinUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val cleanPath = path.trimStart('/')

    val lastSegment = base.substringAfterLast('/', "")
    if (lastSegment.isNotEmpty() && cleanPath.startsWith("$lastSegment/", ignoreCase = true)) {
        return "$base/${cleanPath.substring(lastSegment.length + 1)}"
    }
    if (lastSegment.matches(VERSION_SEGMENT_REGEX)) {
        val pathFirstSegment = cleanPath.substringBefore('/', "")
        if (pathFirstSegment.matches(VERSION_SEGMENT_REGEX)) {
            val remainingPath = cleanPath.substringAfter('/', "")
            return if (remainingPath.isNotEmpty()) "$base/$remainingPath" else base
        }
    }
    return "$base/$cleanPath"
}
