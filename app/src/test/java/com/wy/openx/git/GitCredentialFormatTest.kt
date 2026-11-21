package com.wy.openx.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitCredentialFormatTest {

    @Test
    fun `序列化后解析应还原原值`() {
        val source = listOf(
            GitCredential("github.com", "wy", "ghp_abcdefg1234567"),
            GitCredential("gitlab.com", "dev@example.com", "glpat-xyz")
        )
        val text = GitCredentialFormat.serialize(source)
        assertEquals(source, GitCredentialFormat.parse(text))
    }

    @Test
    fun `令牌含冒号与斜杠应正确往返`() {
        // URL 编码负责转义，否则冒号会被当成 username/token 的分隔符
        val source = listOf(GitCredential("git.example.com", "user", "pa:ss/word:with@chars"))
        val restored = GitCredentialFormat.parse(GitCredentialFormat.serialize(source))
        assertEquals(1, restored.size)
        assertEquals("user", restored[0].username)
        assertEquals("pa:ss/word:with@chars", restored[0].token)
    }

    @Test
    fun `用户名含特殊字符应正确往返`() {
        val source = listOf(GitCredential("gitee.com", "a b+c@d.com", "token"))
        val restored = GitCredentialFormat.parse(GitCredentialFormat.serialize(source))
        assertEquals("a b+c@d.com", restored[0].username)
    }

    @Test
    fun `host 应统一小写`() {
        val restored = GitCredentialFormat.parse("https://u:t@GitHub.COM\n")
        assertEquals("github.com", restored.single().host)
    }

    @Test
    fun `同主机重复条目应保留后者`() {
        val text = "https://old:aaa@github.com\nhttps://new:bbb@github.com\n"
        val restored = GitCredentialFormat.parse(text)
        assertEquals(1, restored.size)
        assertEquals("new", restored[0].username)
        assertEquals("bbb", restored[0].token)
    }

    @Test
    fun `空行与无协议行应跳过`() {
        val text = "\n   \nnot-a-credential\ngithub.com:onlyhost\nhttps://u:t@ok.com\n"
        val restored = GitCredentialFormat.parse(text)
        assertEquals(1, restored.size)
        assertEquals("ok.com", restored[0].host)
    }

    @Test
    fun `缺少用户信息分隔符的行应跳过`() {
        // 没有 '@' → 无法区分主机与用户信息，按坏行丢弃
        assertTrue(GitCredentialFormat.parse("https://justhost.com\n").isEmpty())
    }

    @Test
    fun `空输入应得到空列表与空串`() {
        assertTrue(GitCredentialFormat.parse("").isEmpty())
        assertEquals("", GitCredentialFormat.serialize(emptyList()))
    }

    @Test
    fun `主机大小写不同应合并为一条`() {
        val restored = GitCredentialFormat.parse(
            "https://a:1@GitHub.com\nhttps://b:2@github.COM\n"
        )
        assertEquals(1, restored.size)
        assertEquals("b", restored[0].username)
    }

    @Test
    fun `id 应与 host 小写一致`() {
        assertEquals("github.com", GitCredential("GitHub.com", "u", "t").id)
    }
}
