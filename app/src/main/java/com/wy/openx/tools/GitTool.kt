package com.wy.openx.tools

import com.wy.openx.container.CommandResult

class GitTool : AgentTool {

    private companion object {
        const val GIT_TIMEOUT_MS = 60_000L

        /** 容器内工作区根（宿主 filesDir/workspace 的绑定目标）。 */
        const val DEFAULT_WORKDIR = "/root/workspace"
    }

    override val name = "git"
    override val description = "执行 Git 操作来管理代码版本。支持 status/diff/log/add/commit/branch/push/pull/checkout/init。所有操作在指定目录下执行。命令带 60 秒超时保护。"
    override val parameters = buildToolParameters(
        ToolParameter("operation", ParameterType.STRING, "Git 操作类型：status(状态) diff(差异) log(日志) add(暂存) commit(提交) branch(分支) push(推送) pull(拉取) checkout(切换) init(初始化)", enumValues = listOf("status", "diff", "log", "add", "commit", "branch", "push", "pull", "checkout", "init")),
        ToolParameter("path", ParameterType.STRING, "文件或目录路径（add/checkout 时需要）。多个文件用空格分隔。", required = false),
        ToolParameter("message", ParameterType.STRING, "提交信息（commit 时需要）", required = false),
        ToolParameter("branch", ParameterType.STRING, "分支名（checkout 时切换到该分支）", required = false),
        ToolParameter("count", ParameterType.INTEGER, "显示条数（log 时有效，默认 10）", required = false),
        ToolParameter("dir", ParameterType.STRING, "Git 仓库目录（容器内路径，如 /root/workspace/projects/xxx）。不传则用 /root/workspace。", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val operation = (args["operation"] as? String)?.trim() ?: return "错误：未指定操作"
        val dirStr = (args["dir"] as? String)?.trim()
        val dir = if (!dirStr.isNullOrBlank()) {
            sanitizeWorkdir(dirStr)
                ?: return "错误：目录非法「$dirStr」。只接受容器工作区内的绝对路径" +
                    "（/root/workspace 或 /root/local 开头，不含 .. 与 shell 元字符）"
        } else {
            DEFAULT_WORKDIR
        }
        // 代码在服务器上时，本地容器里跑 git 操作的是旧副本（甚至根本没有仓库）。
        // 不拦的话模型会以为已经提交/回滚了，实际改的是本地。
        if (com.wy.openx.remote.RemoteWorkspaceRegistry.isRemote(dir)) {
            return "错误：git 工具不支持「服务器为准」的工作区（代码在服务器上）。" +
                "请改用 remote_ssh 在服务器上执行 git 命令（如 `git status`）。"
        }

        // cd 失败会静默落在容器初始目录执行，故除 init（要在空目录建仓库）外先确认目录存在。
        val engine = AppContextHolder.containerEngine
            ?: return "错误：容器未装配，无法执行 git（请先在终端页完成容器初始化）"
        if (operation != "init" && engine.toHostFileIfContainerPath(dir)?.isDirectory != true) {
            return "错误：目录不存在或不可用「$dir」"
        }

        return try {
            when (operation) {
                "status" -> gitStatus(dir)
                "diff" -> gitDiff(dir, (args["path"] as? String)?.trim() ?: "")
                "log" -> gitLog(dir, ((args["count"] as? Double)?.toInt()) ?: 10)
                "add" -> gitAdd(dir, (args["path"] as? String)?.trim() ?: "")
                "commit" -> gitCommit(dir, (args["message"] as? String)?.trim() ?: "")
                "branch" -> gitBranch(dir)
                "push" -> gitPush(dir)
                "pull" -> gitPull(dir)
                "checkout" -> gitCheckout(dir, (args["branch"] as? String)?.trim() ?: "", (args["path"] as? String)?.trim() ?: "")
                "init" -> gitInit(dir)
                else -> "错误：不支持的操作「$operation」"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            "Git 错误: ${e.message ?: "未知错误"}"
        }
    }

    /**
     * 在容器（PRoot）内执行 git 并捕获输出。宿主进程里没有 git——它只装在容器 rootfs 内，
     * 从宿主直接 exec 只会因找不到程序而失败。故与 terminal 工具一致，统一走容器通道。
     *
     * 参数逐项用单引号包裹：工具参数可能含空格/引号/分号（如 commit message），
     * 拼进 shell 前必须转义，否则就是命令注入。
     */
    private suspend fun runGit(
        dir: String,
        vararg cmd: String,
        unbounded: Boolean = false
    ): CommandResult {
        val engine = AppContextHolder.containerEngine
            ?: return CommandResult("错误：容器未装配，无法执行 git", null)
        val args = cmd.joinToString(" ") { shellQuote(it) }
        // `|| exit 1`：目录进不去就立即失败，避免 cd 失败后 git 落在容器初始目录执行。
        // `-c core.quotepath=false` 与 GitRepository 一致：否则中文路径会输出成八进制转义。
        val command = "cd ${shellQuote(dir)} || exit 1; git -c core.quotepath=false $args"
        return if (unbounded) {
            engine.runCommandSyncUnbounded(command, engine.hostWorkspaceDir, GIT_TIMEOUT_MS)
        } else {
            engine.runCommandSyncWithExit(command, engine.hostWorkspaceDir, GIT_TIMEOUT_MS)
        }
    }

    private suspend fun gitExec(dir: String, vararg cmd: String, unbounded: Boolean = false): String {
        val r = runGit(dir, *cmd, unbounded = unbounded)
        val text = r.output.trimEnd()
        return when {
            r.exitCode == null -> text.ifBlank { "Git 命令超时或执行异常（${GIT_TIMEOUT_MS / 1000}秒）" }
            r.exitCode != 0 -> "Git 命令执行失败 (exit=${r.exitCode}):\n$text"
            else -> text
        }
    }

    /** 单引号转义（' 换成 '\\'' 后整体包裹），可安全承载任意字符，含换行与双引号。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 过滤以 - 开头的参数，防止被解析为 git 选项（选项注入防护）；路径前加 -- 分隔 */
    private fun safePathArgs(raw: String): List<String>? {
        val parts = raw.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.any { it.startsWith("-") }) return null
        return parts
    }

    private suspend fun isGitRepo(dir: String): Boolean =
        runGit(dir, "rev-parse", "--git-dir").exitCode == 0

    /**
     * 取当前分支名。仓库尚无提交（HEAD 未创建）等取值失败时返回 null——分支名会被拼进
     * push/pull 命令，绝不能把 git 的报错文本当成分支名传下去。
     */
    private suspend fun currentBranch(dir: String): String? {
        val r = runGit(dir, "rev-parse", "--abbrev-ref", "HEAD")
        if (r.exitCode != 0) return null
        val name = r.output.trim()
        return name.takeIf { it.isNotBlank() && safeToken(it) }
    }

    private suspend fun ensureGitRepo(dir: String) {
        if (!isGitRepo(dir)) throw IllegalStateException("「$dir」不是一个 Git 仓库")
    }

    private suspend fun gitStatus(dir: String): String {
        ensureGitRepo(dir)
        val status = gitExec(dir, "status", "--short")
        val summary = gitExec(dir, "status", "--porcelain")
        val branch = currentBranch(dir) ?: "(尚无提交)"
        if (status.isBlank()) return "✅ 工作区干净，无变更\n当前分支: $branch"
        val staged = summary.lines().count { it.startsWith("M") || it.startsWith("A") || it.startsWith("D") }
        val modified = summary.lines().count { it.startsWith(" M") || it.startsWith("??") }
        return "当前分支: $branch\n$status\n---\n📊 暂存: $staged  未暂存: $modified"
    }

    private suspend fun gitDiff(dir: String, path: String): String {
        ensureGitRepo(dir)
        val cmd = mutableListOf("diff")
        if (path.isNotBlank()) {
            val paths = safePathArgs(path)
                ?: return "错误：路径参数不能以 - 开头"
            cmd.add("--")
            cmd.addAll(paths)
        }
        // 不限幅：diff 取完整输出，再由下方按 10000 字符截断（限幅模式只留首尾、中段丢失）。
        val diff = gitExec(dir, *cmd.toTypedArray(), unbounded = true)
        if (diff.isBlank()) return "没有差异"
        if (diff.length > 10000) return diff.take(10000) + "\n\n...(差异过长，已截断至 10000 字符)"
        return diff
    }

    private suspend fun gitLog(dir: String, count: Int): String {
        ensureGitRepo(dir)
        val k = count.coerceIn(1, 100)
        return gitExec(dir, "log", "--oneline", "--graph", "-n", k.toString())
    }

    private suspend fun gitAdd(dir: String, path: String): String {
        ensureGitRepo(dir)
        if (path.isBlank()) return "错误：请指定要暂存的文件路径"
        val paths = safePathArgs(path)
            ?: return "错误：路径参数不能以 - 开头"
        val result = gitExec(dir, "add", "--", *paths.toTypedArray())
        return if (result.isBlank()) "✅ 已暂存: $path" else result
    }

    private suspend fun gitCommit(dir: String, message: String): String {
        ensureGitRepo(dir)
        if (message.isBlank()) return "错误：请提供提交信息"
        // -m 后的值不会被当作选项，无需额外过滤
        return gitExec(dir, "commit", "-m", message)
    }

    private suspend fun gitBranch(dir: String): String {
        ensureGitRepo(dir)
        val local = gitExec(dir, "branch")
        return "本地分支:\n$local"
    }

    private suspend fun gitPush(dir: String): String {
        ensureGitRepo(dir)
        val branch = currentBranch(dir) ?: return "错误：无法确定当前分支（仓库可能尚无提交）"
        return gitExec(dir, "push", "origin", branch)
    }

    private suspend fun gitPull(dir: String): String {
        ensureGitRepo(dir)
        val branch = currentBranch(dir) ?: return "错误：无法确定当前分支（仓库可能尚无提交）"
        return gitExec(dir, "pull", "origin", branch)
    }

    private suspend fun gitCheckout(dir: String, branch: String, path: String): String {
        ensureGitRepo(dir)
        return if (branch.isNotBlank()) {
            if (!safeToken(branch)) return "错误：分支名不能以 - 开头"
            gitExec(dir, "checkout", branch)
        } else if (path.isNotBlank()) {
            val paths = safePathArgs(path)
                ?: return "错误：路径参数不能以 - 开头"
            gitExec(dir, "checkout", "--", *paths.toTypedArray())
        } else {
            "错误：请指定分支名或文件路径"
        }
    }

    private suspend fun gitInit(dir: String): String {
        AppContextHolder.containerEngine?.toHostFileIfContainerPath(dir)?.mkdirs()
        val result = gitExec(dir, "init")
        return if (result.contains("Initialized")) "✅ 已初始化 Git 仓库: $dir" else result
    }

    private fun safeToken(t: String): Boolean = !t.startsWith("-")
}
