package com.wy.openx.data.model

/**
 * 模型元数据：上下文窗口、能力、单价。持久化到自定义模型元数据存储时用 Gson 序列化。
 */
data class ModelMetadata(
    val id: String,
    val providerId: String? = null,
    val displayName: String = id,
    val contextTokens: Int,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    /**
     * models.dev `temperature`：该模型是否接受自定义 temperature。
     * false（含元数据缺失）表示服务端把采样温度固定住了，请求里必须不带该字段。
     */
    val supportsCustomTemperature: Boolean = false,
    val modelType: ModelType = ModelType.CHAT,
    val supportsImageOutput: Boolean = false,
    val source: Source = Source.INFERRED,
    /** models.dev 思考强度档位（reasoning_options 中 effort 类型的 values）。 */
    val reasoningEffortOptions: List<String>? = null,
    /** models.dev cost：输入单价（USD/1M tokens）。 */
    val inputCostUsdPerM: Double? = null,
    /** models.dev cost：输出单价（USD/1M tokens）。 */
    val outputCostUsdPerM: Double? = null,
    /** models.dev cost：缓存读取单价（USD/1M tokens）。 */
    val cacheReadCostUsdPerM: Double? = null,
    /** models.dev cost：缓存写入单价（USD/1M tokens）。 */
    val cacheWriteCostUsdPerM: Double? = null
) {
    enum class ModelType { CHAT, EMBEDDING }

    enum class Source {
        MODELS_DEV,
        INFERRED
    }
}

/**
 * 合并自定义元数据与自动解析（拉取/内置）元数据，自定义优先；窗口未填时保留自动值。
 * [base] 为空时构造兜底元数据（窗口 0，能力全 false）。
 */
fun mergeModelMetadata(
    model: String,
    base: ModelMetadata?,
    custom: ModelMetadata?
): ModelMetadata {
    val a = base ?: ModelMetadata(
        id = model,
        displayName = model,
        contextTokens = 0,
        inputTokens = null,
        outputTokens = null
    )
    val c = custom ?: return a
    return a.copy(
        modelType = c.modelType,
        supportsVision = c.supportsVision,
        supportsImageOutput = c.supportsImageOutput,
        supportsTools = c.supportsTools,
        supportsReasoning = c.supportsReasoning,
        supportsCustomTemperature = c.supportsCustomTemperature,
        // 思考强度档位同样要合并：自定义元数据可能比 models.dev 更细（如只开放 low/high），
        // 原先漏传导致上层永远拿不到该字段、按模型能力过滤选项无从谈起。
        reasoningEffortOptions = c.reasoningEffortOptions ?: a.reasoningEffortOptions,
        contextTokens = c.contextTokens.takeIf { it > 0 } ?: a.contextTokens,
        inputTokens = c.inputTokens ?: a.inputTokens,
        outputTokens = c.outputTokens ?: a.outputTokens,
        inputCostUsdPerM = c.inputCostUsdPerM ?: a.inputCostUsdPerM,
        outputCostUsdPerM = c.outputCostUsdPerM ?: a.outputCostUsdPerM,
        cacheReadCostUsdPerM = c.cacheReadCostUsdPerM ?: a.cacheReadCostUsdPerM,
        cacheWriteCostUsdPerM = c.cacheWriteCostUsdPerM ?: a.cacheWriteCostUsdPerM
    )
}
