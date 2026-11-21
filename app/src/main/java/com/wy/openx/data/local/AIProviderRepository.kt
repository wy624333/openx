package com.wy.openx.data.local

import androidx.room.withTransaction
import com.wy.openx.data.model.AIProviderConfig
import com.wy.openx.data.model.KeyRotationStrategy
import com.wy.openx.data.model.ProviderType
import com.wy.openx.data.model.ProxyType
import com.wy.openx.data.model.sanitized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AIProviderRepository(
    private val dao: AIProviderDao,
    private val db: AppDatabase
) {
    fun getAllProviders(): Flow<List<AIProviderConfig>> {
        return dao.getAllProviders().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    suspend fun getAllProvidersOnce(): List<AIProviderConfig> =
        dao.getAllProvidersOnce().map { it.toDomainModel() }

    suspend fun getProviderById(id: String): AIProviderConfig? =
        dao.getProviderById(id)?.toDomainModel()

    /**
     * 保存提供商。排序值以数据库当前值为准（重排可能异步持久化，UI 传入的
     * sortOrder 可能陈旧，直接使用会撤销刚完成的排序）；新提供商取最大排序 +1。
     */
    suspend fun saveProvider(provider: AIProviderConfig) {
        db.withTransaction {
            val current = dao.getProviderById(provider.id)
            val sortOrder = current?.sortOrder ?: (dao.getMaxSortOrder() + 1)
            dao.insertProvider(provider.copy(sortOrder = sortOrder).sanitized().toEntity())
        }
    }

    /** 按传入顺序重排提供商（重分配 sortOrder 并整体保存）。 */
    suspend fun reorderProviders(providers: List<AIProviderConfig>) {
        db.withTransaction {
            providers.forEachIndexed { index, p -> dao.updateSortOrder(p.id, index) }
        }
    }

    suspend fun deleteProvider(id: String) {
        dao.deleteProvider(id)
    }

    suspend fun setSelectedModel(id: String, model: String) {
        dao.setSelectedModel(id, model)
    }

    suspend fun updateModels(id: String, models: List<String>) {
        dao.setModels(id, models.joinToString("\n"))
    }

    suspend fun setProviderEnabled(id: String, isEnabled: Boolean) {
        dao.setProviderEnabled(id, isEnabled)
    }

    private fun AIProviderEntity.toDomainModel(): AIProviderConfig {
        val modelList = models.split("\n").map { it.substringBefore('|').trim() }.filter { it.isNotEmpty() }
        return AIProviderConfig(
            id = id,
            name = name,
            type = runCatching { ProviderType.valueOf(type) }.getOrDefault(ProviderType.OPENAI),
            apiKey = apiKey,
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = apiKeys.split("\n").map { it.trim() }.filter { it.isNotEmpty() },
            keyRotationStrategy = runCatching { KeyRotationStrategy.valueOf(keyRotationStrategy) }
                .getOrDefault(KeyRotationStrategy.SEQUENTIAL),
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            models = modelList,
            selectedModel = selectedModel.ifBlank { defaultModel },
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi,
            openaiChatCacheKey = openaiChatCacheKey,
            balanceScriptPath = balanceScriptPath,
            balanceRefreshInterval = balanceRefreshInterval,
            userAgent = userAgent,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = runCatching { ProxyType.valueOf(proxyType) }.getOrDefault(ProxyType.HTTP),
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = proxyPassword,
            source = source,
            pluginId = pluginId
        ).sanitized()
    }

    private fun AIProviderConfig.toEntity(): AIProviderEntity {
        return AIProviderEntity(
            id = id,
            name = name,
            type = type.name,
            apiKey = apiKey,
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = apiKeys.joinToString("\n"),
            keyRotationStrategy = keyRotationStrategy.name,
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            models = models.joinToString("\n"),
            selectedModel = selectedModel,
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi,
            openaiChatCacheKey = openaiChatCacheKey,
            balanceScriptPath = balanceScriptPath,
            balanceRefreshInterval = balanceRefreshInterval,
            userAgent = userAgent,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = proxyType.name,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = proxyPassword,
            source = source,
            pluginId = pluginId
        )
    }
}
