package com.wy.openx.data.remote

import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChatMessageDto.srcMsgId` 必须**只活在进程内**。
 *
 * ## 为什么这条不变量很要紧
 * `srcMsgId` 是压缩折叠边界回传用的内部标记（见字段注释）。它是 Kotlin `@Transient`，
 * Gson 默认 Excluder 会跳过 transient 字段 ⇒ **绝不能**出现在发往任何网关的请求体里。
 * 一旦被序列化出去，严格校验的第三方网关会因未知字段报 400，整条工具链断掉。
 *
 * ## 回归背景
 * 该字段是为修「就地压缩只改请求副本、历史没落库 → 上下文涨回、反复压缩」而加的。
 * 加错类型（漏掉 @Transient）会让所有请求 400，且症状与压缩 bug 无关、极难定位。
 */
class ChatMessageDtoTransientFieldTest {

    private val gson = Gson()

    @Test
    fun `srcMsgId 不参与序列化（不会发给网关）`() {
        val dto = ChatMessageDto(
            role = "user",
            content = JsonPrimitive("你好"),
            srcMsgId = 12345L
        )
        val json = gson.toJson(dto)
        assertFalse("srcMsgId 绝不能出现在请求体里：$json", json.contains("srcMsgId"))
        assertFalse("srcMsgId 的值也不能出现：$json", json.contains("12345"))
    }

    @Test
    fun `srcMsgId 缺省为 null 且不影响其它字段序列化`() {
        val dto = ChatMessageDto(role = "assistant", content = JsonPrimitive("在"))
        assertEquals(null, dto.srcMsgId)
        val json = gson.toJson(dto)
        assertTrue("role 必须照常序列化：$json", json.contains("\"role\":\"assistant\""))
        assertTrue("content 必须照常序列化：$json", json.contains("\"content\":\"在\""))
    }

    @Test
    fun `协议字段照常序列化（tool_call_id 等未被 transient 影响）`() {
        val dto = ChatMessageDto(
            role = "tool",
            content = JsonPrimitive("结果"),
            toolCallId = "call_7",
            srcMsgId = 99L
        )
        val json = gson.toJson(dto)
        assertTrue(json.contains("\"tool_call_id\":\"call_7\""))
        assertTrue(json.contains("\"role\":\"tool\""))
        assertFalse(json.contains("srcMsgId"))
    }

    @Test
    fun `内容为 JsonNull 时也能正常序列化`() {
        val dto = ChatMessageDto(role = "assistant", content = JsonNull.INSTANCE, srcMsgId = 5L)
        val json = gson.toJson(dto)
        assertFalse(json.contains("srcMsgId"))
        assertTrue(json.contains("\"role\":\"assistant\""))
    }
}
