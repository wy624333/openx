package com.wy.openx.data.store

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * 回归：内置 AI 规则（无 frontmatter timestamp）在 [MemoryStore.load] 中必须有稳定顺序。
 * 曾按 timestamp 并列后落到 File.listFiles() 的目录顺序（不稳定），打乱注入 prompt 的顺序，
 * 使前缀缓存整段失效。
 */
class MemoryStoreOrderTest {
    private fun fakeContext(root: File): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getCacheDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "getString" -> null
                    "getBoolean" -> false
                    "getInt" -> 0
                    "getLong" -> 0L
                    "getFloat" -> 0f
                    "getStringSet" -> emptySet<String>()
                    "edit" -> Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)
                    ) { _, m, _ ->
                        when (m.name) {
                            "putString", "putBoolean", "putInt", "putLong", "putFloat",
                            "putStringSet", "remove", "clear" -> null
                            "commit" -> true
                            else -> null
                        }
                    }
                    else -> null
                }
            } as SharedPreferences
    }

    @Test
    fun agentRulesOrderIsDeterministicByName() {
        val tmp = File.createTempFile("memstore", "").let { it.delete(); it.mkdirs(); it }
        try {
            val agentDir = File(File(File(tmp, "openx"), "memory"), "agent")
            agentDir.mkdirs()
            // 故意按非字典序创建，验证排序不依赖目录列举顺序
            listOf("20-coding", "50-safety", "10-project", "30-comments").forEach {
                File(agentDir, "$it.md").writeText("正文 $it")
            }

            val store = MemoryStore(fakeContext(tmp))
            val keys = store.load(SCOPE_AGENT).map { it.key }
            assertEquals(listOf("10-project", "20-coding", "30-comments", "50-safety"), keys)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
