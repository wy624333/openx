package com.wy.openx.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_providers")
data class AIProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    /** 明文 Room，与 git token 同口径；后续统一加密时一并处理。 */
    val apiKey: String,
    /** 多 Key 模式开关；关闭时只用 [apiKey]。 */
    val multiKeyEnabled: Boolean = false,
    /** 多 Key 候选列表，以换行分隔持久化（同 [models]）。 */
    val apiKeys: String = "",
    /** 多 Key 取用策略：SEQUENTIAL / ROUND_ROBIN。 */
    val keyRotationStrategy: String = "SEQUENTIAL",
    /** 同一个 Key 连续失败多少次后切换。 */
    val keyFailoverThreshold: Int = 2,
    /** 被切走的 Key 冷却分钟数；0 表示不冷却。 */
    val keyCooldownMinutes: Int = 5,
    val baseUrl: String,
    val defaultModel: String,
    /** 可用模型列表，以换行分隔持久化。 */
    val models: String = "",
    /** 当前选中模型；为空时回退到 defaultModel。 */
    val selectedModel: String = "",
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Chat Completion 路径发送 prompt_cache_key（shard 路由）。仅 OPENAI 类型使用，默认关闭。 */
    val openaiChatCacheKey: Boolean = false,
    /** 套餐余量脚本路径。 */
    val balanceScriptPath: String = "",
    /** 套餐余量自动刷新间隔（分钟）。默认 5 分钟。 */
    val balanceRefreshInterval: Int = 5,
    /** 自定义请求头 User-Agent；留空使用默认。 */
    val userAgent: String = "",
    /** 提供商列表排序序号，越小越靠前；新建时分配 max+1。 */
    val sortOrder: Int = 0,
    /** 单独为该提供商配置代理（关闭时直连）。 */
    val proxyEnabled: Boolean = false,
    val proxyType: String = "HTTP",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /** 来源：`user` = 用户手动添加；`plugin` = 插件在清单里声明，由宿主按插件启停代管。 */
    val source: String = "user",
    /** `source = plugin` 时为提供该供应商的插件 id（插件卸载时按它清理）。 */
    val pluginId: String = ""
)
