package com.wy.openx.git

/**
 * 把 git 命令的原始失败输出匹配成用户友好的中文提示。
 *
 * git 的 stderr 是面向命令行的英文技术文案，直接 toast 不友好。这里对常见失败场景做模式匹配，
 * 命中则返回友好提示，未命中则退回原始输出（保留排查信息）。
 *
 * 
 */
object GitErrorMessage {

    /**
     * @param raw git 原始 stdout+stderr 文本（即 [GitCommandFailureException.output]）。
     * @return 友好提示；无匹配时返回 [raw] 原文。
     */
    fun friendly(raw: String): String {
        if (raw.isBlank()) return raw
        if (raw.contains("Cannot delete branch") && raw.contains("checked out"))
            return "无法删除当前所在分支，请先切换到其他分支"
        if (raw.contains("is not fully merged"))
            return "该分支有未合并的提交，无法安全删除"
        if (raw.contains("invalid reference"))
            return "分支或引用不存在"
        if (raw.contains("local changes") && raw.contains("would be overwritten"))
            return "本地有未提交的改动会被覆盖，请先提交或暂存后再切换"
        if (raw.contains("does not appear to be a git repository") ||
            raw.contains("Could not read from remote repository"))
            return "远程仓库不可用或无访问权限"
        if (raw.contains("Authentication failed") ||
            raw.contains("Invalid username or token") ||
            raw.contains("Permission denied") ||
            raw.contains("Password authentication is not supported"))
            return "远程鉴权失败，请检查凭据配置（用户名/密码/Token）"
        if (raw.contains("Repository not found") || raw.contains("repository") && raw.contains("not found"))
            return "远程仓库不存在或无访问权限"
        if (raw.contains("no tracking information"))
            return "当前分支未关联远程分支，无法拉取"
        if (raw.contains("No configured push destination"))
            return "未配置远程仓库，无法推送"
        if (raw.contains("non-fast-forward") || raw.contains("rejected") && raw.contains("fetch first") ||
            raw.contains("Updates were rejected because the remote contains"))
            return "推送被拒绝，远程有更新的提交，请先拉取"
        if (raw.contains("Please tell me who you are") || raw.contains("Author identity unknown"))
            return "尚未配置提交署名，请填写用户名和邮箱"
        return raw
    }
}
