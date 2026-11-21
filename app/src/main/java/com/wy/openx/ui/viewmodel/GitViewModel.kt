package com.wy.openx.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wy.openx.container.FileLogger
import com.wy.openx.git.GitCommandFailureException
import com.wy.openx.git.GitErrorMessage
import com.wy.openx.git.GitOutputTooLargeException
import com.wy.openx.git.GitRepository
import com.wy.openx.git.model.GitBranch
import com.wy.openx.git.model.GitFileChange
import com.wy.openx.git.model.GitGraph
import com.wy.openx.git.model.GitStatus
import com.wy.openx.git.model.GitTab
import com.wy.openx.git.model.GitTag
import com.wy.openx.tools.LineDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Git 页的 UI 状态与操作。
 *
 * 进入页面即 [refresh] 并发拉取 status/graph/remote，随后异步拉取全量分支/标签；每个写操作
 * 执行后自动刷新并通过 [GitUiState.toast] 反馈。[GitUiState.busy] 守卫防止并发写操作相互踩踏。
 *
 * 适配点：
 * - 去 Hilt → AndroidViewModel；主题/ctx 依赖去除；
 * - diff 改用 openx [LineDiff]（不依赖语法高亮/主题仓库）；
 * - 只保留核心操作（挪移/提交/分支/标签/远程），提交拓扑图泳道数据仍完整提供。
 */
class GitViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "GitViewModel"
        const val MAX_DIFF_LINES = 2000
        const val MAX_DIFF_LINE_LENGTH = 2000
    }

    /** diff 渲染数据。 */
    data class DiffData(
        val title: String,
        val fileName: String,
        val lines: List<LineDiff.DiffLine>,
        val oldRef: String = "",
        val newRef: String = "",
        val message: String? = null
    )

    data class GitUiState(
        val loading: Boolean = true,
        val notARepo: Boolean = false,
        val status: GitStatus? = null,
        val branches: List<GitBranch> = emptyList(),
        val graph: GitGraph = GitGraph.EMPTY,
        val tags: List<GitTag> = emptyList(),
        val tab: GitTab = GitTab.STATUS,
        val busy: Boolean = false,
        val toast: String? = null,
        val hasRemote: Boolean = false,
        val hasIdentity: Boolean = false,
        val userName: String = "",
        val userEmail: String = "",
        val repoUrl: String = "",
        val notReadyHint: String? = null,
        /** 工作区里发现的 git 仓库路径列表（根 + 一层子目录）。 */
        val repos: List<String> = emptyList(),
        /** 当前选中的仓库路径；null 表示工作区根。 */
        val currentRepo: String? = null,
        /** 工作区根的一层直接子目录（仓库选择弹窗展示扫描范围）。 */
        val subdirs: List<String> = emptyList(),
        /** 当前工作区根路径（仓库选择弹窗的基准目录）。 */
        val workspaceRoot: String = "",
        /** 是否显示仓库选择弹窗。 */
        val repoPickerVisible: Boolean = false,
        /** 当前打开的 diff；null 表示未打开。 */
        val diff: DiffData? = null,
        /** 正在查看的提交 hash（详情弹层）；null 表示未打开。 */
        val commitDetailHash: String? = null,
        val commitDetailFiles: List<GitFileChange> = emptyList(),
        val commitDetailLoading: Boolean = false
    )

    private val repository = GitRepository(application.applicationContext)

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    fun consumeToast() { _state.update { it.copy(toast = null) } }
    fun selectTab(tab: GitTab) { _state.update { it.copy(tab = tab) } }
    fun closeDiff() { _state.update { it.copy(diff = null) } }
    fun closeCommitDetail() { _state.update { it.copy(commitDetailHash = null, commitDetailFiles = emptyList()) } }

    /** 首次进入或有写操作后刷新：并发拉 status/graph/remote，再异步拉全量分支/标签。 */
    fun refresh() {
        viewModelScope.launch {
            val hint = repository.notReadyHint()
            if (hint != null) {
                _state.update { it.copy(loading = false, notReadyHint = hint, notARepo = false) }
                return@launch
            }
            _state.update { it.copy(loading = true, notReadyHint = null) }
            val workspaceRoot = repository.currentPath()
            // 工作区根变了（切项目 / 切外部目录）→ 丢弃旧选中，否则命令会打到上一个项目的路径
            val previous = repository.currentRepoPath
            if (previous != null && !previous.startsWith(workspaceRoot)) repository.setRepoPath(null)
            val repos = repository.discoverRepos()
            val subdirs = repository.listSubdirectories()
            // 选中仓库已不再是仓库（被删 / 被移走）→ 回退到工作区根
            if (repository.currentRepoPath?.let { it !in repos } == true) repository.setRepoPath(null)
            val selectedRepo = repository.currentRepoPath ?: workspaceRoot.takeIf { it in repos }
            if (selectedRepo == null) {
                _state.update {
                    it.copy(
                        loading = false, notARepo = true,
                        status = null, graph = GitGraph.EMPTY, branches = emptyList(), tags = emptyList(),
                        repos = repos, currentRepo = null, subdirs = subdirs, workspaceRoot = workspaceRoot
                    )
                }
                return@launch
            }
            runCatching {
                coroutineScope {
                    val statusD = async { repository.status() }
                    val remoteD = async { repository.hasRemote() }
                    val nameD = async { repository.getUserName() }
                    val emailD = async { repository.getUserEmail() }
                    val urlD = async { repository.getRepoUrl() }
                    val refs = repository.loadAllRefs()
                    // 首屏 graph 用全量 refs 重算一次，标注分支/标签
                    Data(
                        status = statusD.await(),
                        graph = repository.graph(refs = refs.refsByCommit),
                        hasRemote = remoteD.await(),
                        branches = refs.branches,
                        tags = refs.tags,
                        userName = nameD.await(),
                        userEmail = emailD.await(),
                        repoUrl = urlD.await()
                    )
                }
            }.onSuccess { d ->
                _state.update {
                    it.copy(
                        loading = false, notARepo = false,
                        status = d.status, graph = d.graph, hasRemote = d.hasRemote,
                        branches = d.branches, tags = d.tags,
                        userName = d.userName, userEmail = d.userEmail, repoUrl = d.repoUrl,
                        hasIdentity = d.userName.isNotBlank(),
                        repos = repos, currentRepo = selectedRepo,
                        subdirs = subdirs, workspaceRoot = workspaceRoot
                    )
                }
            }.onFailure { e ->
                FileLogger.w(TAG, "git refresh 失败: ${e.message}")
                _state.update {
                    it.copy(
                        loading = false, toast = friendly(e),
                        repos = repos, currentRepo = selectedRepo,
                        subdirs = subdirs, workspaceRoot = workspaceRoot
                    )
                }
            }
        }
    }

    private data class Data(
        val status: GitStatus,
        val graph: GitGraph,
        val hasRemote: Boolean,
        val branches: List<GitBranch>,
        val tags: List<GitTag>,
        val userName: String,
        val userEmail: String,
        val repoUrl: String
    )

    /** 加载下一页提交（滚到底触发）。 */
    fun loadMoreCommits() {
        val current = _state.value.graph
        if (!current.hasMore || _state.value.busy) return
        viewModelScope.launch {
            val refs = repository.loadAllRefs().refsByCommit
            val next = repository.graphAppend(current.commits, refs)
            _state.update { it.copy(graph = next) }
        }
    }

    // ===== 写操作（统一 busy 守卫 + 完成后刷新 + toast）=====

    private fun runAction(successToast: String? = null, block: suspend GitRepository.() -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                repository.block()
                if (successToast != null) _state.update { it.copy(toast = successToast) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(toast = friendly(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun initRepo() = runAction("已初始化仓库") { initRepo() }
    fun stage(path: String) = runAction { stage(path) }
    fun unstage(path: String) = runAction { unstage(path) }
    fun stageAll() = runAction { stageAll() }
    fun unstageAll() = runAction { unstageAll() }
    fun commit(message: String) = runAction("已提交") { commit(message) }

    /** 首次提交：先写入 git 署名再提交（避免两个 runAction 的 busy 守卫互相跳过）。 */
    fun saveIdentityAndCommit(name: String, email: String, message: String) = runAction("已提交") {
        if (name.isNotBlank()) setUserIdentity(name, email)
        commit(message)
    }
    fun revertWorktree(path: String) = runAction("已还原改动") { revertWorktree(path) }
    fun revertToHead(path: String) = runAction("已还原到上次提交") { revertToHead(path) }
    fun revertAllWorktree() = runAction("已还原全部未暂存改动") { revertAllWorktree() }
    fun deleteUntracked(path: String) = runAction("已删除未跟踪文件") { deleteUntracked(path) }
    fun pull() = runAction("已拉取") { pull() }
    fun push() = runAction("已推送") { push() }
    fun createBranch(name: String, startPoint: String?, checkout: Boolean) =
        runAction(if (checkout) "已创建并切换分支" else "已创建分支") { createBranch(name, startPoint, checkout) }
    fun deleteBranch(name: String) = runAction("已删除分支") { deleteBranch(name) }
    fun renameBranch(oldName: String, newName: String) = runAction("已重命名分支") { renameBranch(oldName, newName) }
    fun checkout(branch: String, isRemote: Boolean) = runAction("已切换分支") { checkout(branch, isRemote) }
    fun createTag(name: String) = runAction("已创建标签") { createTag(name) }
    fun deleteTag(name: String) = runAction("已删除标签") { deleteTag(name) }
    fun deleteRemoteBranch(ref: String) = runAction("已删除远程分支") { deleteRemoteBranch(ref) }

    // ===== 仓库切换 =====

    fun showRepoPicker() { _state.update { it.copy(repoPickerVisible = true) } }

    fun hideRepoPicker() { _state.update { it.copy(repoPickerVisible = false) } }

    /**
     * 切换到指定仓库路径（工作区根传 null 以外的值均可）。
     *
     * 切完直接 [refresh]：status/graph/分支全是新仓库的，旧数据不能留着。
     */
    fun switchRepo(path: String) {
        val target = if (path == _state.value.workspaceRoot) null else path
        repository.setRepoPath(target)
        _state.update { it.copy(repoPickerVisible = false, loading = true, status = null, graph = GitGraph.EMPTY, branches = emptyList(), tags = emptyList()) }
        refresh()
    }

    /** 克隆远程仓库；成功后自动切到新仓库。 */
    fun cloneRepo(url: String, dirName: String? = null) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                repository.clone(url, dirName)
                val repos = repository.discoverRepos()
                val name = dirName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
                val created = repos.firstOrNull { it.endsWith("/$name") }
                repository.setRepoPath(created)
                _state.update { it.copy(toast = "已克隆到 $name") }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(toast = friendly(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ===== Diff =====

    /** 查看某个工作区文件相对 HEAD 的 diff。 */
    fun showWorkingDiff(path: String, staged: Boolean) {
        viewModelScope.launch {
            val d = withContext(Dispatchers.Default) {
                runCatching {
                    // 与 HEAD 对比：staged 看 index（已暂存），未暂存看 worktree 当前内容。
                    val old = repository.showFileContent("HEAD", path)
                    val new = if (staged) repository.indexFileContent(path) else repository.worktreeFileContent(path)
                    buildDiff("工作区 · $path", path, old, new, "HEAD", if (staged) "index" else "worktree")
                }.getOrElse { e -> errorDiff("工作区 · $path", path, friendly(e)) }
            }
            _state.update { it.copy(diff = d) }
        }
    }

    /** 查看某次提交里某文件的 diff。 */
    fun showCommitFileDiff(hash: String, path: String) {
        viewModelScope.launch {
            val d = withContext(Dispatchers.Default) {
                runCatching {
                    val old = repository.showFileContent("$hash^", path)
                    val new = repository.showFileContent(hash, path)
                    buildDiff("$hash · $path", path, old, new, "$hash^", hash)
                }.getOrElse { e -> errorDiff("$hash · $path", path, friendly(e)) }
            }
            _state.update { it.copy(diff = d) }
        }
    }

    /** 打开某次提交的文件清单（详情弹层）。 */
    fun openCommitDetail(hash: String) {
        _state.update { it.copy(commitDetailHash = hash, commitDetailFiles = emptyList(), commitDetailLoading = true) }
        viewModelScope.launch {
            val files = runCatching { repository.commitFiles(hash) }.getOrElse { emptyList() }
            _state.update { it.copy(commitDetailFiles = files, commitDetailLoading = false) }
        }
    }

    private fun buildDiff(title: String, fileName: String, old: String, new: String, oldRef: String, newRef: String): DiffData {
        val oldLines = old.split('\n')
        val newLines = new.split('\n')
        if (oldLines.size > MAX_DIFF_LINES || newLines.size > MAX_DIFF_LINES) {
            return DiffData(title, fileName, emptyList(), oldRef, newRef, "文件行数过多（> $MAX_DIFF_LINES 行），已跳过 diff 计算")
        }
        if (oldLines.any { it.length > MAX_DIFF_LINE_LENGTH } || newLines.any { it.length > MAX_DIFF_LINE_LENGTH }) {
            return DiffData(title, fileName, emptyList(), oldRef, newRef, "存在超长单行（> $MAX_DIFF_LINE_LENGTH 字符），已跳过 diff 渲染")
        }
        return DiffData(title, fileName, LineDiff.diff(old, new), oldRef, newRef, null)
    }

    private fun errorDiff(title: String, fileName: String, message: String): DiffData =
        DiffData(title, fileName, emptyList(), message = message)

    private fun friendly(e: Throwable): String = when (e) {
        is GitOutputTooLargeException -> e.output
        is GitCommandFailureException -> GitErrorMessage.friendly(e.output)
        else -> e.message ?: "操作失败"
    }
}
