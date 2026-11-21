package com.wy.openx.git

import org.junit.Assert.assertEquals
import org.junit.Test

class GitCredentialBridgeParseTest {

    @Test
    fun `应取出 host 字段`() {
        assertEquals("github.com", GitCredentialBridge.parseRequestHost("host=github.com\n"))
    }

    @Test
    fun `多行时只认 host 行`() {
        val content = "protocol=https\nhost=gitlab.com\nusername=\n"
        assertEquals("gitlab.com", GitCredentialBridge.parseRequestHost(content))
    }

    @Test
    fun `行尾无换行也能取出`() {
        assertEquals("gitee.com", GitCredentialBridge.parseRequestHost("host=gitee.com"))
    }

    @Test
    fun `空内容得到空串`() {
        assertEquals("", GitCredentialBridge.parseRequestHost(""))
    }

    @Test
    fun `缺少 host 字段得到空串`() {
        // 调用方据此丢弃残文件，避免弹出无法填写的弹窗
        assertEquals("", GitCredentialBridge.parseRequestHost("protocol=https\n\n"))
    }

    @Test
    fun `host 两侧空白应裁掉`() {
        assertEquals("github.com", GitCredentialBridge.parseRequestHost("host=  github.com  \n"))
    }
}
