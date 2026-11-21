package com.wy.openx.git

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 凭据文件的文本格式：每行 `https://user:token@host`，与 git-credential-store 一致。
 *
 * 独立于 [GitCredentialStore]：这里只管文本 ↔ 数据，不碰文件 IO 与 base64 编码。
 * 用户名/令牌里可能含 `:`、`@`、`/` 等分隔符，必须 URL 编码后再拼，否则解析会切错位置。
 */
object GitCredentialFormat {

    private const val SCHEME = "https://"

    /** 序列化为凭据文件正文；空列表得到空串。 */
    fun serialize(credentials: List<GitCredential>): String = buildString {
        credentials.forEach { c ->
            append(SCHEME)
                .append(enc(c.username)).append(':').append(enc(c.token))
                .append('@').append(c.host)
                .append('\n')
        }
    }

    /**
     * 解析凭据文件正文；格式异常的行跳过，同主机后者覆盖前者。
     *
     * 用 `lastIndexOf('@')` 定位主机：令牌里可能出现未编码的 `@`，只有最后一个是分隔符。
     */
    fun parse(text: String): List<GitCredential> {
        val result = LinkedHashMap<String, GitCredential>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("://") }
            .forEach { line ->
                val credential = parseLine(line) ?: return@forEach
                result[credential.host] = credential
            }
        return result.values.toList()
    }

    private fun parseLine(line: String): GitCredential? = runCatching {
        val withoutScheme = line.substringAfter("://")
        val at = withoutScheme.lastIndexOf('@')
        if (at <= 0) return@runCatching null
        val host = withoutScheme.substring(at + 1).trim().lowercase()
        if (host.isEmpty()) return@runCatching null
        val userInfo = withoutScheme.substring(0, at)
        GitCredential(
            host = host,
            username = URLDecoder.decode(userInfo.substringBefore(':'), "UTF-8"),
            token = URLDecoder.decode(userInfo.substringAfter(':', ""), "UTF-8")
        )
    }.getOrNull()

    private fun enc(part: String): String = URLEncoder.encode(part, "UTF-8")
}
