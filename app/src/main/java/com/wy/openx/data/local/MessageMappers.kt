package com.wy.openx.data.local

import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.MessageAttachment
import com.wy.openx.data.model.MessageRole
import org.json.JSONArray
import org.json.JSONObject

fun MessageEntity.toModel(): ChatMessageModel {
    return ChatMessageModel(
        id = id,
        role = when (role) {
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            else -> MessageRole.SYSTEM
        },
        author = author,
        time = time,
        content = content,
        isCode = isCode,
        isSteps = isSteps,
        steps = if (steps.isBlank()) emptyList() else steps.split("\\|\\|\\|".toRegex()),
        imageUri = imageUri,
        attachments = decodeAttachments(attachmentsJson),
        tokens = tokens,
        inputTokens = inputTokens,
        cachedInputTokens = cachedInputTokens,
        timeMs = timeMs,
        toolCallId = toolCallId,
        toolName = toolName,
        toolArguments = toolArguments,
        toolResult = toolResult,
        folded = folded,
        seq = seq,
        queued = queued
    )
}

fun ChatMessageModel.toEntity(conversationId: Long): MessageEntity {
    return MessageEntity(
        id = if (id <= 0) 0 else id,
        conversationId = conversationId,
        role = when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
        },
        author = author,
        time = time,
        content = content,
        isCode = isCode,
        isSteps = isSteps,
        steps = steps.joinToString("|||"),
        imageUri = imageUri,
        attachmentsJson = encodeAttachments(attachments),
        tokens = tokens,
        inputTokens = inputTokens,
        cachedInputTokens = cachedInputTokens,
        timeMs = timeMs,
        toolCallId = toolCallId,
        toolName = toolName,
        toolArguments = toolArguments,
        toolResult = toolResult,
        folded = folded,
        seq = seq,
        queued = queued
    )
}

/** 附件列表 → JSON（org.json 内建，无需额外序列化依赖）。 */
internal fun encodeAttachments(attachments: List<MessageAttachment>): String? {
    if (attachments.isEmpty()) return null
    val arr = JSONArray()
    attachments.forEach { a ->
        arr.put(JSONObject().apply {
            put("fileName", a.fileName)
            put("containerPath", a.containerPath)
            put("localPath", a.localPath)
            put("mimeType", a.mimeType)
            put("sizeBytes", a.sizeBytes)
            put("isImage", a.isImage)
        })
    }
    return arr.toString()
}

internal fun decodeAttachments(json: String?): List<MessageAttachment> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    MessageAttachment(
                        fileName = o.optString("fileName"),
                        containerPath = o.optString("containerPath"),
                        localPath = o.optString("localPath"),
                        mimeType = o.optString("mimeType"),
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        isImage = o.optBoolean("isImage")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
