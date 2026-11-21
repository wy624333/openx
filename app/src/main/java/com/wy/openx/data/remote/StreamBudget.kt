package com.wy.openx.data.remote

import com.wy.openx.data.repository.StreamApiException

/**
 * 单次流式响应允许累积的增量字符上限。
 *
 * 防止思考链（reasoning_content）或异常长回复无限 append StringBuilder 导致 OOM。
 * 超过上限直接中断本次请求（宁可中断也不要静默截断，避免工具入参半截）。
 */
const val MAX_STREAM_CHARS = 4 * 1024 * 1024

class StreamBudget(private val maxChars: Int = MAX_STREAM_CHARS) {
    private var total = 0L

    @Synchronized
    fun add(delta: String) {
        if (delta.isEmpty()) return
        total += delta.length
        if (total > maxChars) {
            throw StreamApiException(
                code = "response_too_large",
                message = "上游返回内容异常（单次响应超过 ${maxChars / 1024}KB），已中断"
            )
        }
    }

    @Synchronized
    fun current(): Long = total
}
