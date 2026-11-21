package com.wy.openx.data.model

enum class ApiProviderType {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    DEEPSEEK,
    ALIYUN,
    ZHIPU,
    MOONSHOT,
    SILICONFLOW,
    OPENROUTER,
    OLLAMA,
    OTHER;

    companion object {
        fun fromProviderTypeId(providerTypeId: String): ApiProviderType? {
            val normalized = providerTypeId.trim()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        }
    }
}
