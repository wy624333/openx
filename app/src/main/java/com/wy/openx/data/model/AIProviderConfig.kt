package com.wy.openx.data.model

/** 供应商来源：用户手动添加（可在设置里改地址、删除）。 */
const val PROVIDER_SOURCE_USER = "user"

/**
 * 供应商来源：插件在清单里声明（见 `PluginManifest.aiProvider`）。
 *
 * 宿主按插件启停自动建/删，所以这类条目不允许用户改地址——改了下次
 * 插件一启动就被声明的值覆盖，用户会以为"改了没生效"。
 */
const val PROVIDER_SOURCE_PLUGIN = "plugin"

/**
 * 提供商协议类型。openx 请求层目前只实现 OpenAI 兼容（chat/completions 或 Responses），
 * 其余类型保留完整枚举与默认端点，UI 可配置，请求层对非 OpenAI 系按 OpenAI 兼容路径兜底。
 */
enum class ProviderType {
    OPENAI,           // OpenAI 官方 / 兼容网关
    ANTHROPIC,        // Anthropic Claude（Messages API 格式）
    GEMINI,           // Google Gemini（原生 generateContent 格式）
    DEEPSEEK,         // DeepSeek
    MOONSHOT,         // Moonshot AI / Kimi
    ZHIPU,            // 智谱 GLM
    QWEN,             // 阿里通义千问
    DOUBAO,           // 字节豆包
    HUNYUAN,          // 腾讯混元
    MINIMAX,          // MiniMax
    SILICONFLOW,      // 硅基流动 SiliconFlow
    OPENROUTER,       // OpenRouter 聚合
    GROQ,             // Groq
    OLLAMA,           // Ollama 本地
    PERPLEXITY,       // Perplexity
    MISTRAL,          // Mistral AI
    META,             // Meta Llama
    GROK,             // xAI Grok
    OTHER;            // 其他（走 OpenAI 兼容）

    val displayName: String
        get() = when (this) {
            OPENAI -> "OpenAI"
            ANTHROPIC -> "Anthropic"
            GEMINI -> "Gemini"
            DEEPSEEK -> "DeepSeek"
            MOONSHOT -> "Moonshot / Kimi"
            ZHIPU -> "智谱 GLM"
            QWEN -> "通义千问"
            DOUBAO -> "豆包"
            HUNYUAN -> "腾讯混元"
            MINIMAX -> "MiniMax"
            SILICONFLOW -> "SiliconFlow"
            OPENROUTER -> "OpenRouter"
            GROQ -> "Groq"
            OLLAMA -> "Ollama"
            PERPLEXITY -> "Perplexity"
            MISTRAL -> "Mistral"
            META -> "Meta"
            GROK -> "xAI Grok"
            OTHER -> "其他"
        }

    companion object {
        fun fromTypeId(typeId: String): ProviderType {
            val normalized = typeId.trim()
            if (normalized.isEmpty()) return OTHER
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: OTHER
        }
    }
}

enum class KeyRotationStrategy {
    /** 顺序：始终用第一个未冷却的 Key，只有连续失败达阈值才前移。 */
    SEQUENTIAL,

    /** 轮询：新会话轮流分配起始 Key；同一会话内仍粘住同一个 Key 以保住服务端 prompt 缓存。 */
    ROUND_ROBIN
}

data class AIProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val apiKey: String,
    /** 多 Key 模式开关：开启后从 [apiKeys] 轮换取用，关闭时只用 [apiKey]。 */
    val multiKeyEnabled: Boolean = false,
    /** 多 Key 模式下的候选 Key（按列表顺序优先）。 */
    val apiKeys: List<String> = emptyList(),
    /** 多 Key 取用策略：顺序（失败才切）或轮询（新会话轮流起步）。 */
    val keyRotationStrategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
    /** 同一个 Key 连续失败多少次后切到下一个。 */
    val keyFailoverThreshold: Int = 2,
    /** 被切走的 Key 冷却多少分钟后重新纳入候选；0 表示不冷却。 */
    val keyCooldownMinutes: Int = 5,
    val baseUrl: String,
    val defaultModel: String,
    /** 该提供商已添加的可用模型列表（拉取或手动添加）。 */
    val models: List<String> = emptyList(),
    /** 当前选中使用的模型；为空时回退到 defaultModel。 */
    val selectedModel: String = defaultModel,
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Chat Completion 路径发送 prompt_cache_key（shard 路由）。仅 OPENAI 类型生效，默认关闭。 */
    val openaiChatCacheKey: Boolean = false,
    /** 套餐余量查询脚本路径（位于 ~/.aicode/scripts/，或绝对路径/自定义命令）。 */
    val balanceScriptPath: String = "",
    /** 套餐余量自动刷新间隔（分钟），0 表示仅进入时/手动刷新。 */
    val balanceRefreshInterval: Int = 5,
    /** 自定义请求头 User-Agent；留空使用默认。 */
    val userAgent: String = "",
    /** 提供商列表排序序号，越小越靠前；-1 表示未分配（保存时取 max+1 排到末尾）。 */
    val sortOrder: Int = -1,
    /** 单独为该提供商配置代理（关闭时直连）。 */
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /** 来源：见 [PROVIDER_SOURCE_USER] / [PROVIDER_SOURCE_PLUGIN]。 */
    val source: String = PROVIDER_SOURCE_USER,
    /** `source = PROVIDER_SOURCE_PLUGIN` 时为提供该供应商的插件 id。 */
    val pluginId: String = ""
) {
    /** 实际生效的模型：优先 selectedModel，其次 defaultModel。 */
    val effectiveModel: String
        get() = selectedModel.ifBlank { defaultModel }

    /**
     * 实际可用的 Key 列表。多 Key 模式开启且列表非空时用 [apiKeys]，否则退回单 [apiKey]，
     * 使「开关关闭」与「开着但没填」都能落到既有单 Key 行为上。
     */
    val effectiveApiKeys: List<String>
        get() = if (multiKeyEnabled && apiKeys.any { it.isNotBlank() }) {
            apiKeys.filter { it.isNotBlank() }
        } else {
            listOf(apiKey).filter { it.isNotBlank() }
        }

    /** 是否已配置至少一个可用 Key。 */
    val hasUsableApiKey: Boolean get() = effectiveApiKeys.isNotEmpty()

    /** 拉取模型列表 / 连通性测试等无会话上下文的请求用第一个可用 Key。 */
    val firstUsableApiKey: String get() = effectiveApiKeys.firstOrNull() ?: ""
}

/** 绝不包含空白的字段（API Key / URL / 代理主机）：连中间空白一并去掉。 */
private fun String.stripAllWhitespace(): String = filterNot { it.isWhitespace() }

/** 允许内部空格的字段（名称 / 模型名 / UA / 路径 / 代理账号）：去换行与制表符，再 trim 首尾。 */
private fun String.stripLineBreaks(): String =
    filterNot { it == '\n' || it == '\r' || it == '\t' }.trim()

/**
 * 清洗手填/粘贴的提供商配置。粘贴 API Key 常带入换行或首尾空格，直接拼进 Authorization
 * 头会被 OkHttp 拒绝（Unexpected char 0x0a in Authorization value）；baseUrl / 代理主机带空白则
 * 拼出非法 URL。写入与读取两侧各清洗一次，已存的脏数据不靠迁移也能恢复可用。
 */
fun AIProviderConfig.sanitized(): AIProviderConfig = copy(
    name = name.stripLineBreaks(),
    apiKey = apiKey.stripAllWhitespace(),
    apiKeys = apiKeys.map { it.stripAllWhitespace() }.filter { it.isNotEmpty() }.distinct(),
    baseUrl = baseUrl.stripAllWhitespace(),
    defaultModel = defaultModel.stripLineBreaks(),
    models = models.map { it.stripLineBreaks() }.filter { it.isNotEmpty() }.distinct(),
    selectedModel = selectedModel.stripLineBreaks(),
    balanceScriptPath = balanceScriptPath.stripLineBreaks(),
    userAgent = userAgent.stripLineBreaks(),
    proxyHost = proxyHost.stripAllWhitespace(),
    proxyUsername = proxyUsername.stripLineBreaks(),
    proxyPassword = proxyPassword.stripLineBreaks()
)

fun defaultProviderBaseUrl(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "https://api.anthropic.com"
    ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
    ProviderType.DEEPSEEK -> "https://api.deepseek.com/v1"
    ProviderType.MOONSHOT -> "https://api.moonshot.cn/v1"
    ProviderType.ZHIPU -> "https://open.bigmodel.cn/api/paas/v4"
    ProviderType.QWEN -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
    ProviderType.DOUBAO -> "https://ark.cn-beijing.volces.com/api/v3"
    ProviderType.HUNYUAN -> "https://api.hunyuan.cloud.tencent.com/v1"
    ProviderType.MINIMAX -> "https://api.minimax.chat/v1"
    ProviderType.SILICONFLOW -> "https://api.siliconflow.cn/v1"
    ProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
    ProviderType.GROQ -> "https://api.groq.com/openai/v1"
    ProviderType.OLLAMA -> "http://localhost:11434/v1"
    ProviderType.PERPLEXITY -> "https://api.perplexity.ai"
    ProviderType.MISTRAL -> "https://api.mistral.ai/v1"
    ProviderType.META -> "https://openai-compatible-gateway.example.com/v1"
    ProviderType.GROK -> "https://api.x.ai/v1"
    else -> "https://api.openai.com/v1"
}

/** OpenAI 兼容类各家的 chat/completions 路径；智谱 GLM 走 v4。 */
fun defaultProviderApiPath(type: ProviderType): String = when (type) {
    ProviderType.ZHIPU -> "v4/chat/completions"
    else -> "v1/chat/completions"
}
