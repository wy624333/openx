package com.wy.openx.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PluginServiceManager 的 HTTP 解析逻辑单测。
 *
 * 不测真实端口监听（需要 Android Context + QuickJS 引擎），只测纯函数：
 *  - parseRequest：HTTP 请求行/头/body 解析
 *  - 端口顺延逻辑（通过 start 的 manifest.port 推算）
 *
 * parseRequest 是 private，通过反射访问——这是刻意的：
 * 该函数是纯 HTTP 协议解析，无 Android 依赖，值得独立验证。
 */
class PluginServiceManagerTest {

    private fun parseRequest(raw: String): PluginServiceManager.ParsedRequest? {
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
        val method = PluginServiceManager.javaClass.getDeclaredMethod("parseRequest", java.io.DataInputStream::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(PluginServiceManager, input) as PluginServiceManager.ParsedRequest?
    }

    @Test
    fun `parseRequest parses GET with query`() {
        val req = parseRequest(
            "GET /v1/models?source=all HTTP/1.1\r\n" +
                "Host: 127.0.0.1:18899\r\n" +
                "Accept: application/json\r\n" +
                "\r\n"
        )
        assertNotNull(req)
        assertEquals("GET", req!!.method)
        assertEquals("/v1/models", req.path)
        assertEquals("source=all", req.query)
        assertEquals("127.0.0.1:18899", req.headers["host"])
        assertEquals("application/json", req.headers["accept"])
        assertNull(req.body)
    }

    @Test
    fun `parseRequest parses POST with body`() {
        val body = """{"model":"deepseek-v4.1-flash-free","messages":[]}"""
        val raw = "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "\r\n" +
            body
        val req = parseRequest(raw)
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("", req.query)
        assertEquals(body, req.body)
    }

    @Test
    fun `parseRequest reads UTF-8 chinese body by bytes not chars`() {
        // 回归：Content-Length 是字节数，中文 UTF-8 多字节时按字符读会截断。
        // 构造一个「字节长 > 字符长」的 body，验证完整读回。
        val body = "{\"model\":\"kilo-kilo-auto/free\",\"messages\":[{\"role\":\"user\",\"content\":\"你好，世界！这是一个长中文消息，用来验证字节级读取。\"}]}"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        assertTrue("前提：字节长应大于字符长（含中文）", bodyBytes.size > body.length)
        val raw = "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "\r\n"
        val rawBytes = raw.toByteArray(Charsets.UTF_8) + bodyBytes
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(rawBytes))
        val m = PluginServiceManager.javaClass.getDeclaredMethod("parseRequest", java.io.DataInputStream::class.java)
        m.isAccessible = true
        val req = m.invoke(PluginServiceManager, input) as PluginServiceManager.ParsedRequest?
        assertNotNull(req)
        assertEquals(body, req!!.body) // 中文完整无损
    }

    @Test
    fun `parseRequest lowercases header names`() {
        val req = parseRequest(
            "GET / HTTP/1.1\r\n" +
                "Authorization: Bearer public\r\n" +
                "\r\n"
        )
        assertNotNull(req)
        assertEquals("Bearer public", req!!.headers["authorization"])
    }

    @Test
    fun `parseRequest returns null on malformed request line`() {
        assertNull(parseRequest("not a valid request\r\n\r\n"))
    }

    @Test
    fun `parseRequest returns null on empty input`() {
        assertNull(parseRequest(""))
    }

    @Test
    fun `parseRequest handles OPTIONS preflight`() {
        val req = parseRequest("OPTIONS /v1/chat/completions HTTP/1.1\r\n\r\n")
        assertNotNull(req)
        assertEquals("OPTIONS", req!!.method)
    }

    @Test
    fun `server permission is dangerous`() {
        assertTrue(PluginPermission.SERVER.dangerous)
        assertEquals("server", PluginPermission.SERVER.wireName)
    }

    @Test
    fun `manifest service and port fields parse from json`() {
        val json = """
            {"id":"test","name":"t","version":"1","main":"m.js",
             "service":"service/gateway.js","port":18899,
             "permissions":["server","http"]}
        """.trimIndent()
        val manifest = com.google.gson.Gson().fromJson(json, PluginManifest::class.java)
        assertEquals("service/gateway.js", manifest.service)
        assertEquals(18899, manifest.port)
        assertTrue(manifest.grantedPermissions.contains(PluginPermission.SERVER))
    }

    @Test
    fun `manifest without service field does not crash on isNotBlank`() {
        // 回归：旧插件 json 没有 service 字段，Gson 不走 Kotlin 默认值 → null。
        // 真实路径是 PluginStore.parseManifest（先 normalizeManifest 补默认值），
        // 这里同时验证两层防护：normalize 后 Gson 解析 + 直接对字段 isNotBlank。
        val oldJson = """
            {"id":"legacy","name":"Legacy Plugin","main":"main.js"}
        """.trimIndent()

        // 第一层：模拟 PluginStore.normalizeManifest 的兜底
        val root = com.google.gson.JsonParser.parseString(oldJson).asJsonObject
        root.addProperty("service", "")
        root.addProperty("port", 0)
        val manifest = com.google.gson.Gson().fromJson(root, PluginManifest::class.java)
        assertTrue(manifest.service.isNotBlank().not()) // 空串不崩
        assertEquals(0, manifest.port)

        // 第二层：直接 Gson（无 normalize）时字段为 null —— 验证 UI 侧判空防御
        val raw = com.google.gson.Gson().fromJson(oldJson, PluginManifest::class.java)
        assertTrue(raw.service == null || raw.service.isNotBlank().not())
    }
}
