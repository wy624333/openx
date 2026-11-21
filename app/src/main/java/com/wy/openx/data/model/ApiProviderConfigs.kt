package com.wy.openx.data.model

data class ProviderEndpointOption(
    val endpoint: String,
    val label: String
)

data class ProviderApiConfig(
    val providerType: ApiProviderType,
    val defaultModelName: String = "",
    val defaultApiEndpoint: String = "",
    val endpointOptions: List<ProviderEndpointOption> = emptyList(),
    val requiresApiKey: Boolean = true
)

object ApiProviderConfigs {
    private val configs: Map<ApiProviderType, ProviderApiConfig> = listOf(
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI,
            defaultModelName = "gpt-4o",
            defaultApiEndpoint = "https://api.openai.com/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ANTHROPIC,
            defaultModelName = "claude-sonnet-4-20250514",
            defaultApiEndpoint = "https://api.anthropic.com/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.GOOGLE,
            defaultModelName = "gemini-2.5-pro",
            defaultApiEndpoint = "https://generativelanguage.googleapis.com/v1beta"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.DEEPSEEK,
            defaultModelName = "deepseek-chat",
            defaultApiEndpoint = "https://api.deepseek.com/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ALIYUN,
            defaultModelName = "qwen-max",
            defaultApiEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ZHIPU,
            defaultModelName = "glm-4-flash",
            defaultApiEndpoint = "https://open.bigmodel.cn/api/paas/v4",
            endpointOptions = listOf(
                ProviderEndpointOption("https://open.bigmodel.cn/api/paas/v4", "国内标准"),
                ProviderEndpointOption("https://api.z.ai/api/paas/v4", "国际版")
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MOONSHOT,
            defaultModelName = "moonshot-v1-128k",
            defaultApiEndpoint = "https://api.moonshot.cn/v1",
            endpointOptions = listOf(
                ProviderEndpointOption("https://api.moonshot.cn/v1", "国内 (moonshot.cn)"),
                ProviderEndpointOption("https://api.moonshot.ai/v1", "国际 (moonshot.ai)")
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.SILICONFLOW,
            defaultModelName = "Qwen/Qwen3.5-4B",
            defaultApiEndpoint = "https://api.siliconflow.cn/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENROUTER,
            defaultModelName = "openai/gpt-4o",
            defaultApiEndpoint = "https://openrouter.ai/api/v1"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OLLAMA,
            defaultModelName = "llama3",
            defaultApiEndpoint = "http://localhost:11434/v1",
            requiresApiKey = false
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OTHER,
            defaultModelName = "",
            defaultApiEndpoint = ""
        )
    ).associateBy { it.providerType }

    fun getConfig(providerType: ApiProviderType): ProviderApiConfig {
        return configs[providerType] ?: configs[ApiProviderType.OTHER]!!
    }

    fun getDefaultModelName(providerType: ApiProviderType): String {
        return getConfig(providerType).defaultModelName
    }

    fun getDefaultEndpoint(providerType: ApiProviderType): String {
        return getConfig(providerType).defaultApiEndpoint
    }

    fun getEndpointOptions(providerType: ApiProviderType): List<ProviderEndpointOption> {
        return getConfig(providerType).endpointOptions
    }

    fun requiresApiKey(providerType: ApiProviderType): Boolean {
        return getConfig(providerType).requiresApiKey
    }

    fun isDefaultModelName(modelName: String): Boolean {
        return configs.values.any { it.defaultModelName == modelName }
    }

    fun getProviderDisplayName(providerType: ApiProviderType): String {
        return when (providerType) {
            ApiProviderType.OPENAI -> "OpenAI"
            ApiProviderType.ANTHROPIC -> "Anthropic"
            ApiProviderType.GOOGLE -> "Google Gemini"
            ApiProviderType.DEEPSEEK -> "DeepSeek"
            ApiProviderType.ALIYUN -> "阿里云百炼"
            ApiProviderType.ZHIPU -> "智谱 GLM"
            ApiProviderType.MOONSHOT -> "月之暗面 Kimi"
            ApiProviderType.SILICONFLOW -> "SiliconFlow"
            ApiProviderType.OPENROUTER -> "OpenRouter"
            ApiProviderType.OLLAMA -> "Ollama (本地)"
            ApiProviderType.OTHER -> "自定义"
        }
    }
}
