package com.wy.openx.privacy

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.wy.openx.data.remote.ChatMessageDto

/**
 * 出站脱敏在「消息层」的落地：把将要发出去的历史消息里的文本换成占位符。
 *
 * 只覆盖会进入模型上下文的文本（正文、思考内容、工具调用参数）。图片/附件不动——
 * 脱敏规则是文本正则，碰到 base64 只会白跑一遍，而且图片里本来也读不出这些形态。
 *
 * 返回新列表，**不改原列表**：同一份消息会被重试、工具循环反复使用，就地改会把
 * 已打码的文本当成原文再打一遍（占位符不含敏感形态，第二遍无害，但原列表一旦被污染，
 * 界面上显示的就是占位符了）。
 */
fun List<ChatMessageDto>.maskedForOutbound(masker: OutboundMasker): List<ChatMessageDto> =
    map { dto ->
        dto.copy(
            content = dto.content?.let { maskElement(it, masker::mask) },
            reasoningContent = dto.reasoningContent?.let(masker::mask),
            toolCalls = dto.toolCalls?.map { call ->
                call.copy(function = call.function.copy(arguments = masker.mask(call.function.arguments)))
            },
        )
    }

/** 单个字符串值的回填（工具参数、正文增量都用它）。 */
fun String.restoredFromOutbound(masker: OutboundMasker): String = masker.restore(this)

private fun maskElement(element: JsonElement, f: (String) -> String): JsonElement = when (element) {
    is JsonObject -> JsonObject().apply {
        element.entrySet().forEach { (key, value) -> add(key, maskElement(value, f)) }
    }
    is JsonArray -> JsonArray().apply {
        element.forEach { add(maskElement(it, f)) }
    }
    is JsonPrimitive -> if (element.isString) JsonPrimitive(f(element.asString)) else element
    // JsonNull 等：原样返回
    else -> element
}

/**
 * 非流式响应的回填：正文、思考、工具调用参数里的占位符换回真值。
 *
 * 非流式是一次性拿完整响应，不用像流式那样处理被切断的占位符。
 */
internal fun com.wy.openx.data.remote.ChatResponse.restoredFromOutbound(
    masker: OutboundMasker
): com.wy.openx.data.remote.ChatResponse = copy(
    choices = choices?.map { choice ->
        choice.copy(
            message = choice.message?.let { m ->
                m.copy(
                    content = m.content?.let(masker::restore),
                    reasoning = m.reasoning?.let(masker::restore),
                    reasoningContent = m.reasoningContent?.let(masker::restore),
                    toolCalls = m.toolCalls?.map { call ->
                        call.copy(function = call.function.copy(arguments = masker.restore(call.function.arguments)))
                    },
                )
            },
        )
    }
)
