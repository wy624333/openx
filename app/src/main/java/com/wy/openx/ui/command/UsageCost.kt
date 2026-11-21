package com.wy.openx.ui.command

import com.wy.openx.data.model.ModelMetadata
import com.wy.openx.data.model.mergeModelMetadata

/**
 * 按模型元数据单价计算一组 token 的预估费用（USD）。
 *
 * - 输入 / 输出 / 缓存读取 / 缓存写入各自按对应单价计费，单项单价缺失按 0 计；
 * - 四项单价全缺返回 null，由调用方退回内置价格表（避免把「不知道价格」当成「免费」）。
 */
fun usageCostUsd(
    inputTokens: Long,
    outputTokens: Long,
    cachedInputTokens: Long,
    cacheCreationTokens: Long,
    metadata: ModelMetadata?
): Double? {
    val inPrice = metadata?.inputCostUsdPerM
    val outPrice = metadata?.outputCostUsdPerM
    val readPrice = metadata?.cacheReadCostUsdPerM
    val writePrice = metadata?.cacheWriteCostUsdPerM
    if (inPrice == null && outPrice == null && readPrice == null && writePrice == null) return null
    return (inputTokens * (inPrice ?: 0.0) +
        outputTokens * (outPrice ?: 0.0) +
        cachedInputTokens * (readPrice ?: 0.0) +
        cacheCreationTokens * (writePrice ?: 0.0)) / 1_000_000.0
}

/** 合并基础（自动解析）与自定义元数据，自定义非空字段优先；两者都缺返回 null。 */
fun metadataForUsage(
    model: String,
    base: ModelMetadata?,
    custom: ModelMetadata?
): ModelMetadata? =
    if (base == null && custom == null) null else mergeModelMetadata(model, base, custom)
