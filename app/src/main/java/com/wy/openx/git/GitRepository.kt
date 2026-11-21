package com.wy.openx.git

import android.content.Context
import com.wy.openx.container.FileLogger
import com.wy.openx.container.Workspace
import com.wy.openx.git.model.GitBranch
import com.wy.openx.git.model.GitCommit
import com.wy.openx.git.model.GitFileChange
import com.wy.openx.git.model.GitGraph
import com.wy.openx.git.model.GitGraphRef
import com.wy.openx.git.model.GitStatus
import com.wy.openx.git.model.GitTag
import com.wy.openx.git.model.GraphCommit
import com.wy.openx.tools.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "GitRepository"

/**
 * 在容器内执行 git 命令并解析输出。
 *
 * 直接复用 [com.wy.openx.container.LinuxContainerEngine.runCommandSyncUnbounded]（cwd = 当前工作区，
 * 绑定挂载到容器 `~/workspace`），不经 agent 工具链 / 权限引擎——Git 页是用户主动操作。
 * 所有输出解析为纯领域模型。
 *
 * 命令经 [shellQuote] 逐参数转义后拼成单条 `git ...` 字符串交给 `/bin/sh -c`，故格式串里的
 * `|`、`%(...)`、含空格的路径都能安全传递。
 *
 * 所有命令带 `-c core.quotepath=false`：git 默认把路径里的非 ASCII 字节转成八进制转义，
 * 关掉后直接输出 UTF-8 原文，中文目录/文件名不再乱码。
 *
 * 适配点：
 * - `CommandEngine` 由 [AppContextHolder.containerEngine] 直接取（openx 无 DI）；
 * - 工作区路径按 openx 语义计算（当前项目 / 外部工作区 / 默认工作区）；
 * - openx 的 `CommandResult` 无 `outputTruncated`（unbounded 模式实际不限幅），去掉该判定，
 *   仅保留基于 `cat-file -s` 的文件字节上限预检。
 */
class GitRepository(private val context: Context) {

    private companion object {
        /** 提交拓扑图每页加载条数。 */
        const val GRAPH_PAGE_SIZE = 100
        /** diff 允许读取的单侧文件字节上限：超过则不拉内容，直接降级为「文件过大」。 */
        const val MAX_DIFF_FILE_BYTES = 2L * 1024 * 1024
        /**
         * clone 超时：默认 120s 对完整仓库（带历史/大文件）远远不够，
         * 中途被 watchdog 强杀会留下半截目录，比慢慢跑更难受。
         */
        const val CLONE_TIMEOUT_MS = 10 * 60_000L
        /**
         * 当前选中的仓库路径；null = 工作区根。
         *
         * 放伴生对象而非实例字段：Git 页与凭据页各持一个 [GitRepository]，但「当前仓库」是全局 UI
         * 状态——两页必须指向同一仓库，否则在凭据页改的 remote.origin.url 会落到另一个仓库。
         */
        @Volatile
        var selectedRepoPath: String? = null
    }

    private val engine get() = AppContextHolder.containerEngine

    /** 当前选中的仓库路径；null 表示用工作区根。仓库切换器写它，所有 git 命令以它为 cwd。 */
    val currentRepoPath: String?
        get() = selectedRepoPath

    /**
     * 当前工作区在**宿主**上的绝对路径（proot 会把它绑定为容器 `/root/workspace` 并设为 cwd）：
     * - 选中外部本地目录 → 该目录；
     * - 选中项目 → `filesDir/workspace/projects/<name>`；
     * - 未选中 → `filesDir/workspace`。
     */
    fun currentPath(): String {
        val settings = AppContextHolder.currentSettings
        val proj = settings?.currentProject?.trim()
        if (!proj.isNullOrEmpty()) {
            val ext = settings.externalWorkspaces.firstOrNull { it.name == proj }
            if (ext != null) return ext.path
            return File(Workspace.root(context), "projects/$proj").absolutePath
        }
        return Workspace.root(context).absolutePath
    }

    /** git 命令的实际执行目录：选中了子目录仓库用其路径，否则工作区根。 */
    private fun repoCwd(): String = currentRepoPath ?: currentPath()

    /** 切换当前仓库（null 回到工作区根）。 */
    fun setRepoPath(path: String?) {
        selectedRepoPath = path?.takeIf { it.isNotBlank() }
    }

    /** 指定路径是否处于 git 工作树内。 */
    suspend fun isGitRepo(path: String): Boolean = runCatching {
        val e = engine ?: return@runCatching false
        e.runCommandSyncUnbounded("git rev-parse --is-inside-work-tree", path).output.trim() == "true"
    }.getOrElse { false }

    /**
     * 发现工作区里的 git 仓库：工作区根 + 一层直接子目录。
     *
     * 只看一层：项目下再深就归入该项目自己的仓库，全盘递归扫描既慢又会把 `.git` 内部目录误当候选。
     */
    suspend fun discoverRepos(): List<String> {
        val root = currentPath()
        val candidates = mutableListOf(root)
        runCatching {
            File(root).listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { candidates.add(it.absolutePath) }
        }
        return candidates.filter { isGitRepo(it) }
    }

    /** 工作区根的一层直接子目录（仓库选择弹窗展示扫描范围）。 */
    fun listSubdirectories(): List<String> = runCatching {
        File(currentPath()).listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.map { it.absolutePath }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * 克隆远程仓库到工作区根下的新目录（目录名取仓库名去 `.git` 后缀）。
     *
     * 凭据由 credential.helper 链自动注入：私有仓库会触发弹窗回填后继续。
     */
    suspend fun clone(url: String, dirName: String? = null): String {
        val name = dirName?.trim()?.takeIf { it.isNotEmpty() }
            ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        val e = engine ?: throw GitCommandFailureException("容器未就绪")
        val root = currentPath()
        val target = File(root, name)
        if (target.exists()) throw GitCommandFailureException("目录已存在：$name")
        val cmd = "git clone ${shellQuote(url.trim())} ${shellQuote(name)}"
        val result = e.runCommandSyncUnbounded(cmd, root, CLONE_TIMEOUT_MS)
        if (result.exitCode != 0) {
            // 失败（含超时被杀）会留下半截目录，不清理则用户重试时恒报「目录已存在」。
            // 只删本次 clone 新建的 target，不碰用户原有内容。
            runCatching { target.deleteRecursively() }
            throw GitCommandFailureException(result.output.trim().ifBlank { "克隆失败" })
        }
        return result.output
    }

    /**
     * 执行一条 `git` 子命令，返回合并后的 stdout+stderr 文本。仅用于只读命令（status/branches/log/remote 等）。
     */
    private suspend fun git(vararg args: String): String {
        val e = engine ?: return ""
        return e.runCommandSyncUnbounded(buildGitCommand(args), repoCwd()).output
    }

    /**
     * 执行一条 `git` **写**子命令，据退出码判成败：非零（真实失败）抛 [GitCommandFailureException]。
     * 空退出码（超时/异常）同样按失败抛，避免静默成功。
     */
    private suspend fun gitChecked(vararg args: String): String {
        val e = engine ?: throw GitCommandFailureException("容器未装配，无法执行 git")
        val result = e.runCommandSyncUnbounded(buildGitCommand(args), repoCwd())
        if (result.exitCode == 0) return result.output
        throw GitCommandFailureException(result.output.ifBlank { "git 退出码 ${result.exitCode}" })
    }

    /** 拼成交给 `/bin/sh -c` 的单条命令字符串，逐参数 [shellQuote] 转义。 */
    private fun buildGitCommand(args: Array<out String>): String = buildString {
        append("git -c core.quotepath=false")
        args.forEach { append(' '); append(shellQuote(it)) }
    }

    /** 当前工作区是否处于一个 git 工作树内。异常时返回 false 而非抛出。 */
    suspend fun isRepo(): Boolean {
        return runCatching { git("rev-parse", "--is-inside-work-tree").trim() == "true" }.getOrElse { false }
    }

    /** 容器是否就绪可执行 git 命令；未就绪时返回引导文案。 */
    fun notReadyHint(): String? = engine?.notReadyHint()

    /** 在当前工作区初始化 git 仓库。 */
    suspend fun initRepo(): String = gitChecked("init")

    /** 是否已配置至少一个远程仓库。 */
    suspend fun hasRemote(): Boolean = git("remote").trim().isNotEmpty()

    /** `git status` 聚合视图。 */
    suspend fun status(): GitStatus {
        val raw = git("status", "--porcelain=v1", "-b")
        val lines = raw.split('\n').map { it.removeSuffix("\r") }

        var branch = "(unknown)"
        var upstream: String? = null
        var isDetached = false
        var ahead = 0
        var behind = 0
        val staged = mutableListOf<GitFileChange>()
        val unstaged = mutableListOf<GitFileChange>()
        val untracked = mutableListOf<String>()

        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("## ")) {
                val header = line.removePrefix("## ")
                isDetached = header.startsWith("HEAD (no branch)")
                val tracking = header.substringBefore(" [")
                if (isDetached) {
                    branch = "HEAD"
                } else {
                    branch = tracking.substringBefore("...").ifBlank { tracking }
                    upstream = tracking.substringAfter("...", "").ifBlank { null }
                }
                val bracket = header.substringAfter(" [", "")
                if (bracket.isNotBlank()) {
                    bracket.removeSuffix("]").split(",").forEach { tok ->
                        val t = tok.trim()
                        val n = t.filter { it.isDigit() }.toIntOrNull() ?: 0
                        if (t.startsWith("ahead")) ahead = n
                        else if (t.startsWith("behind")) behind = n
                    }
                }
                continue
            }
            if (line.length < 3) continue
            val x = line[0]
            val y = line[1]
            val rawPath = line.substring(3)
            val path = unquoteGitPath(rawPath.substringAfter(" -> ").trim())

            if (x == '?' && y == '?') {
                untracked.add(path)
                continue
            }
            if (x != ' ' && x != '?') {
                staged.add(GitFileChange(path, x.toString(), staged = true))
            }
            if (y != ' ' && y != '?') {
                unstaged.add(GitFileChange(path, y.toString(), staged = false))
            }
        }
        return GitStatus(branch, ahead, behind, staged, unstaged, untracked, upstream, isDetached)
    }

    /** 本地 + 远程分支列表，当前分支高亮。 */
    suspend fun branches(): List<GitBranch> = loadAllRefs().branches

    /** 最近 [limit] 条提交。 */
    suspend fun log(limit: Int = 50): List<GitCommit> {
        val raw = git("log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s", "-n", limit.toString())
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.removeSuffix("\r").split('\u001f')
            if (parts.size < 5) null
            else GitCommit(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }

    /** 拓扑图视图（含泳道布局）。失败返回 [GitGraph.EMPTY]，不抛。 */
    suspend fun graph(
        limit: Int = GRAPH_PAGE_SIZE,
        refs: Map<String, List<GitGraphRef>> = emptyMap()
    ): GitGraph = graphAppend(emptyList(), refs, limit)

    /**
     * 分页加载下一批提交并整体重算泳道布局（泳道分配依赖全局子父顺序，必须整图重算）。
     */
    suspend fun graphAppend(
        existingCommits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>> = emptyMap(),
        limit: Int = GRAPH_PAGE_SIZE
    ): GitGraph = withContext(Dispatchers.Default) {
        val skip = existingCommits.size
        val logRaw = runCatching {
            git("log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1f%P%x1f%b", "--skip", skip.toString(), "-n", limit.toString())
        }.getOrDefault("")
        if (logRaw.isBlank() || logRaw.startsWith("fatal:")) {
            return@withContext if (existingCommits.isEmpty()) GitGraph.EMPTY
            else GitGraphBuilder.buildGraph(existingCommits, refs, hasMore = false)
        }

        val newCommits = GitGraphBuilder.parseGraphCommits(logRaw)
        if (newCommits.isEmpty() && existingCommits.isEmpty()) return@withContext GitGraph.EMPTY

        val existingHashes = existingCommits.mapTo(HashSet()) { it.hash }
        val merged = existingCommits + newCommits.filter { it.hash !in existingHashes }
        GitGraphBuilder.buildGraph(merged, refs, hasMore = newCommits.size >= limit)
    }

    /** 一次拉取全部分支/标签及其指向的提交哈希。 */
    data class AllRefs(
        val branches: List<GitBranch>,
        val tags: List<GitTag>,
        val refsByCommit: Map<String, List<GitGraphRef>>
    )

    suspend fun loadAllRefs(): AllRefs = withContext(Dispatchers.Default) {
        val raw = runCatching {
            git(
                "for-each-ref",
                "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)\u001f%(upstream:short)\u001f%(upstream:track)",
                "refs/heads", "refs/remotes", "refs/tags"
            )
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext AllRefs(emptyList(), emptyList(), emptyMap())
        val branches = mutableListOf<GitBranch>()
        val tags = mutableListOf<GitTag>()
        val refsByCommit = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val name = parts[0]
            val hash = parts[1]
            val isHead = parts[2].trim() == "*"
            val refname = parts[3]
            val upstream = parts.getOrNull(4)?.ifBlank { null }
            val track = parts.getOrNull(5).orEmpty()
            val ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val isRemote = refname.startsWith("refs/remotes")
            val isBranch = refname.startsWith("refs/heads") || isRemote
            refsByCommit.getOrPut(hash) { mutableListOf() }
                .add(GitGraphRef(name, isBranch, isHead && !isRemote, isRemote))
            if (isBranch) {
                branches.add(GitBranch(name, current = isHead && !isRemote, remote = isRemote, upstream = upstream, ahead = ahead, behind = behind))
            } else {
                tags.add(GitTag(name, hash.take(7)))
            }
        }
        tags.reverse()
        AllRefs(branches, tags, refsByCommit)
    }

    /** 某次提交改动的文件清单。 */
    suspend fun commitFiles(hash: String): List<GitFileChange> {
        val raw = git("diff-tree", "--no-commit-id", "-r", "--root", "--name-status", "--no-renames", hash)
        return withContext(Dispatchers.Default) {
            raw.lineSequence().mapNotNull { line ->
                val l = line.removeSuffix("\r").trim()
                val tab = l.indexOf('\t')
                if (l.isBlank() || tab < 0) null
                else {
                    val status = l.substring(0, tab).trim()
                    val path = unquoteGitPath(l.substring(tab + 1).trim())
                    GitFileChange(path, status, staged = false)
                }
            }.toList()
        }
    }

    suspend fun stage(path: String) = gitChecked("add", "--", path)
    suspend fun unstage(path: String) = gitChecked("reset", "HEAD", "--", path)
    suspend fun stageAll() = gitChecked("add", "-A")
    suspend fun unstageAll() = gitChecked("reset")
    suspend fun commit(message: String) = gitChecked("commit", "-m", message)

    /** 回退单个文件的未暂存改动（工作区还原成暂存区内容）。 */
    suspend fun revertWorktree(path: String): String = gitChecked("checkout", "--", path)

    /** 回退单个文件的全部改动（已暂存 + 未暂存），还原到上次提交。 */
    suspend fun revertToHead(path: String): String {
        if (!hasHead()) return gitChecked("rm", "-q", "--cached", "--", path)
        gitChecked("reset", "-q", "HEAD", "--", path)
        return if (existsInHead(path)) gitChecked("checkout", "--", path) else ""
    }

    /** 回退全部未暂存改动。 */
    suspend fun revertAllWorktree(): String = gitChecked("checkout", "--", ":/")

    /** 删除未跟踪的文件或目录（`git clean -f -d`）。 */
    suspend fun deleteUntracked(path: String): String = gitChecked("clean", "-f", "-d", "--", path)

    /** 某个未跟踪目录下的未跟踪文件清单。 */
    suspend fun untrackedFilesIn(dir: String): List<String> {
        val raw = git("ls-files", "--others", "--exclude-standard", "--", dir)
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { it.removeSuffix("\r").trim().ifBlank { null } }
            .map { unquoteGitPath(it) }
    }

    private suspend fun hasHead(): Boolean =
        runCatching { git("rev-parse", "--verify", "HEAD").trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    private suspend fun existsInHead(path: String): Boolean =
        runCatching { git("ls-tree", "HEAD", "--", path).trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /** 拉取（`git pull`）。凭据由容器 credential.helper 链自动注入。 */
    suspend fun pull(): String = gitChecked("pull")

    /**
     * 推送：有上游则 `git push`；无上游时自动 `git push --set-upstream <remote> <branch>` 首推建关联。
     */
    suspend fun push(): String {
        val hasUpstream = runCatching { git("rev-parse", "--abbrev-ref", "@{upstream}").trim() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "HEAD" && !it.startsWith("fatal") } != null
        if (hasUpstream) return gitChecked("push")
        val remote = git("remote").split('\n').firstOrNull { it.removeSuffix("\r").isNotBlank() }?.removeSuffix("\r")?.trim()
            ?: throw GitCommandFailureException("未配置远程仓库")
        val branch = git("rev-parse", "--abbrev-ref", "HEAD").removeSuffix("\r").trim()
            .takeIf { it.isNotBlank() && it != "HEAD" }
            ?: throw GitCommandFailureException("无法确定当前分支（处于 detached HEAD）")
        return gitChecked("push", "--set-upstream", remote, branch)
    }

    /** 本地标签列表，按创建时间倒序。 */
    suspend fun listTags(): List<GitTag> = loadAllRefs().tags

    /** 创建新分支。checkout=true 则创建并切换。 */
    suspend fun createBranch(name: String, startPoint: String?, checkout: Boolean): String {
        return if (checkout) {
            if (startPoint.isNullOrBlank()) gitChecked("checkout", "-b", name)
            else gitChecked("checkout", "-b", name, startPoint)
        } else {
            if (startPoint.isNullOrBlank()) gitChecked("branch", name)
            else gitChecked("branch", name, startPoint)
        }
    }

    /** 安全删除本地分支（`git branch -d`）。 */
    suspend fun deleteBranch(name: String): String = gitChecked("branch", "-d", name)

    /** 重命名本地分支。 */
    suspend fun renameBranch(oldName: String, newName: String): String =
        gitChecked("branch", "-m", oldName, newName)

    /** 创建轻量标签（指向当前 HEAD）。 */
    suspend fun createTag(name: String): String = gitChecked("tag", name)

    /** 删除本地标签。 */
    suspend fun deleteTag(name: String): String = gitChecked("tag", "-d", name)

    /** 删除远程分支。 */
    suspend fun deleteRemoteBranch(ref: String): String {
        val remote = ref.substringBefore('/', "origin")
        val branch = ref.substringAfter('/', ref)
        return gitChecked("push", remote, "--delete", branch)
    }

    /** 切换到指定分支或标签。远程分支会创建本地跟踪分支。 */
    suspend fun checkout(branch: String, isRemote: Boolean): String {
        return if (isRemote) {
            val localName = branch.substringAfter('/', branch)
            gitChecked("checkout", "-b", localName, "--track", branch)
        } else {
            gitChecked("checkout", branch)
        }
    }

    /** 写入提交署名，优先项目级，否则写全局。 */
    suspend fun setUserIdentity(name: String, email: String) {
        if (name.isNotBlank()) gitChecked("config", if (hasLocalConfig("user.name")) "--local" else "--global", "user.name", name)
        if (email.isNotBlank()) gitChecked("config", if (hasLocalConfig("user.email")) "--local" else "--global", "user.email", email)
    }

    private suspend fun hasLocalConfig(key: String): Boolean =
        runCatching { git("config", "--local", "--get", key).trim() }.getOrDefault("").isNotBlank()

    /** 读取 git 当前实际生效的 user.name。 */
    suspend fun getUserName(): String =
        runCatching { git("config", "--get", "user.name").trim() }.getOrDefault("").removeSuffix("\r")

    /** 读取 git 当前实际生效的 user.email。 */
    suspend fun getUserEmail(): String =
        runCatching { git("config", "--get", "user.email").trim() }.getOrDefault("").removeSuffix("\r")

    /** 写入仓库地址 remote.origin.url（仅项目级）。 */
    suspend fun setRepoUrl(url: String) {
        if (url.isNotBlank()) {
            gitChecked("config", "--local", "remote.origin.url", url)
        } else {
            runCatching { gitChecked("config", "--local", "--unset", "remote.origin.url") }
        }
        runCatching { git("config", "--global", "--unset", "remote.origin.url") }
    }

    /** 读取 git 当前实际生效的 remote.origin.url。 */
    suspend fun getRepoUrl(): String =
        runCatching { git("config", "--get", "remote.origin.url").trim() }.getOrDefault("").removeSuffix("\r")

    /** 读取指定 ref 下某文件的完整内容（`git show <ref>:<path>`）。 */
    suspend fun showFileContent(ref: String, path: String): String {
        if (refBlobSize("$ref:$path") > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git("show", "$ref:$path")
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }

    private suspend fun refBlobSize(spec: String): Long =
        runCatching { git("cat-file", "-s", spec).trim().toLong() }.getOrDefault(0L)

    /** 读取工作区当前文件内容。本地工作区就在 app 私有目录，直接用 File 读。 */
    suspend fun worktreeFileContent(path: String): String {
        val local = withContext(Dispatchers.IO) {
            runCatching {
                File(repoCwd(), path)
                    .takeIf { it.isFile }
                    ?.also { if (it.length() > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException() }
                    ?.readText()
            }.getOrElse { e ->
                if (e is GitOutputTooLargeException) throw e
                null
            }
        }
        if (local != null) return local
        val e = engine ?: return ""
        val result = e.runCommandSyncUnbounded("cat -- ${shellQuote(path)}", repoCwd())
        return if (result.exitCode == 0) result.output else ""
    }

    /** 读取暂存区当前文件内容（index）。 */
    suspend fun indexFileContent(path: String): String {
        if (refBlobSize(":$path") > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git("show", ":$path")
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }

    /**
     * 对单个 shell 参数做单引号转义。含「安全字符」之外的字符时整体包单引号。
     * 注意 `|` 是 shell 管道符，**不可**列入安全集。
     */
    private fun shellQuote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (arg.all { it.isLetterOrDigit() || it in "_.@/:=+,%-" }) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}

/**
 * porcelain v1 / ls-files / diff-tree 对含引号、反斜杠、空格或控制字符的路径会整体加引号并做
 * C 风格转义，这里做反向解析还原真实路径。八进制转义是逐字节的，需先攒字节再整体 UTF-8 解码。
 */
internal fun unquoteGitPath(raw: String): String {
    if (!raw.startsWith("\"")) return raw
    val inner = raw.removeSurrounding("\"")
    val sb = StringBuilder(inner.length)
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c != '\\' || i + 1 >= inner.length) {
            sb.append(c)
            i += 1
            continue
        }
        if (inner[i + 1] in '0'..'7') {
            val bytes = mutableListOf<Byte>()
            while (i + 1 < inner.length && inner[i] == '\\' && inner[i + 1] in '0'..'7') {
                val octal = inner.substring(i + 1, minOf(i + 4, inner.length)).takeWhile { it in '0'..'7' }
                if (octal.length != 3) break
                bytes.add(octal.toInt(8).toByte())
                i += 4
            }
            if (bytes.isEmpty()) {
                sb.append(c)
                i += 1
            } else {
                sb.append(String(bytes.toByteArray(), Charsets.UTF_8))
            }
            continue
        }
        val decoded = when (inner[i + 1]) {
            'a' -> '\u0007'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'v' -> '\u000B'
            '"' -> '"'
            '\\' -> '\\'
            else -> null
        }
        if (decoded == null) {
            sb.append(c)
            i += 1
        } else {
            sb.append(decoded)
            i += 2
        }
    }
    return sb.toString()
}
