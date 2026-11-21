package com.wy.openx.git

/**
 * 一条 Git 远程仓库凭据：每主机一条，[host] 小写后即主键。
 *
 * [token] 对 GitHub/Gitee 是 Personal Access Token，对 GitLab 是 PAT 或 deploy token，
 * 自建服务用密码亦可——git 只把它当 password 字段喂给 HTTP Basic 认证。
 */
data class GitCredential(
    val host: String,
    val username: String,
    val token: String
) {
    val id: String get() = host.trim().lowercase()
}
