package com.wy.openx.tools

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `$unloadByIds` 实参序列化的回归测试。
 *
 * 背景（2026-10-20 修）：`unload` 原来传 `gson.toJson(entry.handlerNames)`，
 * 而 `callFunction` 的 argsJson 是**实参列表**，于是 `["wy_calc","wy_time_get"]`
 * 被拆成两个位置实参，`$unloadByIds(idsJson)` 里 `JSON.parse("wy_calc")` 抛
 * `unexpected token: 'wy_calc'`，JS 侧 handler 删不掉（日志报「JS 侧卸载 xx 失败」）。
 *
 * 这里只测实参 JSON 的形状（`unload` 还要起 QuickJS，跑不了 JVM 单测）。
 */
class JsPackageManagerUnloadArgsTest {

    private val gson = Gson()

    /** 把 argsJson 解析成「实参数组」（与 quickjs_jni.cpp 的 CallFunction 同语义）。 */
    private fun parseArgv(argsJson: String): List<Any?> =
        gson.fromJson(argsJson, object : TypeToken<List<Any?>>() {}.type)

    @Test
    fun `实参只有一个，且是 JSON 文本而不是第一个工具名`() {
        val argv = parseArgv(unloadHandlerArgsJson(listOf("wy_calc", "wy_time_get")))

        assertEquals(1, argv.size)
        // 关键回归点：若直接传数组，这里会变成 ["wy_calc", "wy_time_get"]（两个实参）
        assertTrue("实参应为字符串，实际 ${argv[0]?.javaClass}", argv[0] is String)
        assertEquals("[\"wy_calc\",\"wy_time_get\"]", argv[0])
    }

    @Test
    fun `实参内层能被解析回原列表`() {
        val names = listOf("fmg_list_models", "fmg_chat")
        val argv = parseArgv(unloadHandlerArgsJson(names))

        @Suppress("UNCHECKED_CAST")
        val restored = gson.fromJson(argv[0] as String, List::class.java)
        assertEquals(names, restored)
    }

    @Test
    fun `单个工具名也保持数组形状`() {
        val argv = parseArgv(unloadHandlerArgsJson(listOf("wy_calc")))

        assertEquals(1, argv.size)
        assertEquals("[\"wy_calc\"]", argv[0])
    }

    @Test
    fun `空列表不产生零实参`() {
        // 空列表直接传会得到 []（argc=0），函数收到 undefined；仍要包一层保持一个实参
        val argv = parseArgv(unloadHandlerArgsJson(emptyList()))

        assertEquals(1, argv.size)
        assertEquals("[]", argv[0])
    }
}
