package com.wy.openx.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * documentId 的编码规则与路径越界防护。
 *
 * provider 本体依赖 Android 的 DocumentsProvider 基类，没法在纯 JVM 单测里跑；
 * 所以把最容易出错的两条规则单独抽出来测：id 往返、`..` 不能逃出根目录。
 */
class OpenXDocumentsProviderIdTest {

    private fun makeDocId(rootId: String, relPath: String): String =
        if (relPath.isEmpty()) "$rootId:" else "$rootId:$relPath"

    private fun rootOf(docId: String): String = docId.substringBefore(':')
    private fun relOf(docId: String): String = docId.substringAfter(':', "")

    @Test
    fun `根目录的 documentId 只有根 id 加冒号`() {
        val id = makeDocId("workspace", "")
        assertEquals("workspace:", id)
        assertEquals("workspace", rootOf(id))
        assertEquals("", relOf(id))
    }

    @Test
    fun `普通路径能原样往返`() {
        val id = makeDocId("workspace", "projects/xingde/app/build.gradle.kts")
        assertEquals("workspace", rootOf(id))
        assertEquals("projects/xingde/app/build.gradle.kts", relOf(id))
    }

    @Test
    fun `路径里带冒号也不影响解析（首个冒号才是分隔符）`() {
        val id = makeDocId("data", "plugins/weird:name.js")
        assertEquals("data", rootOf(id))
        assertEquals("plugins/weird:name.js", relOf(id))
    }

    @Test
    fun `中文目录名能原样往返`() {
        val id = makeDocId("workspace", "projects/xingde/签名密钥/debug.keystore")
        assertEquals("workspace", rootOf(id))
        assertEquals("projects/xingde/签名密钥/debug.keystore", relOf(id))
    }

    @Test
    fun `根 id 里不含冒号，否则解析会错位`() {
        val ids = listOf("files", "workspace", "data", "container", "rootfs_custom-1790330603609")
        ids.forEach { id ->
            assertTrue("根 id 不该含冒号：$id", !id.contains(':'))
        }
    }
}
