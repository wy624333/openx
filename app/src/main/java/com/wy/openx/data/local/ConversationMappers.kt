package com.wy.openx.data.local

import com.wy.openx.data.model.ConversationModel

fun ConversationEntity.toModel(): ConversationModel {
    return ConversationModel(
        id = id,
        title = title,
        meta = meta,
        active = active,
        requestCount = requestCount,
        lastInputTokens = lastInputTokens,
        isPinned = isPinned,
        lastActiveAt = lastActiveAt,
        isArchived = isArchived,
        // 会话级模型选择（未选过时为 null → 跟随全局）
        providerId = providerId,
        model = model,
        titleFinalized = titleFinalized
    )
}

fun ConversationModel.toEntity(): ConversationEntity {
    return ConversationEntity(
        id = if (id <= 0) 0 else id,
        title = title,
        meta = meta,
        active = active,
        requestCount = requestCount,
        lastInputTokens = lastInputTokens,
        isPinned = isPinned,
        lastActiveAt = lastActiveAt,
        isArchived = isArchived,
        providerId = providerId,
        model = model,
        titleFinalized = titleFinalized
    )
}
