package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.git.model.GitBranch
import com.wy.openx.git.model.GitFileChange
import com.wy.openx.git.model.GitTab
import com.wy.openx.tools.LineDiff
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Danger
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.Success
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import com.wy.openx.ui.viewmodel.GitViewModel

/**
 * Git 管理页（状态 / 分支 / 日志三 tab）。
 *
 * 
 * - 三 tab 用 Material3 TabRow（原 FloatingTabBar）；
 * - diff 用 openx 行级着色（原 DiffViewer 含语法高亮）；
 * - 主题色用 openx 语义 token；字符串硬编码中文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    viewModel: GitViewModel,
    onBack: () -> Unit,
    /** 打开「凭据与署名」页。 */
    onOpenCredentials: () -> Unit = {},
    /**
     * 当前项目/外部工作区名（来自设置）。
     *
     * Git 仓库路径由它推导（见 `GitRepository.currentPath`），而设置是异步加载的：
     * 若首帧刷新早于设置到位，页面会停在「不是仓库」。用它做 key，设置一到就自动重刷；
     * 用户在页内切换项目时同样能跟上。
     */
    currentProject: String = ""
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(currentProject) { viewModel.refresh() }

    var showClone by remember { mutableStateOf(false) }

    // toast 用轻量 Snackbar 替代：直接顶部浮层 3 秒
    var toastMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            toastMsg = it
            viewModel.consumeToast()
        }
    }
    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            kotlinx.coroutines.delay(3000)
            toastMsg = null
        }
    }

    Scaffold(
        // PageBg：选背景图时透明透出根背景层（原 BgPrimary 会整屏挡住）
        containerColor = PageBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Git", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        val s = state.status
                        val repoLabel = state.currentRepo?.let { repoDisplayPath(it, state.workspaceRoot) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(enabled = !state.busy) { viewModel.showRepoPicker() }
                        ) {
                            Text(
                                repoLabel ?: (state.status?.branch ?: "选择仓库"),
                                fontSize = 12.sp, color = TextSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(" \u25BE", fontSize = 12.sp, color = TextSecondary)
                            if (s != null && (s.ahead > 0 || s.behind > 0)) {
                                Text(
                                    "  ↑${s.ahead} ↓${s.behind}",
                                    fontSize = 12.sp, color = TextSecondary
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextSecondary)
                    }
                },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = onOpenCredentials) {
                        Icon(Icons.Default.Settings, contentDescription = "凭据与署名", tint = TextSecondary)
                    }
                    IconButton(onClick = { viewModel.pull() }, enabled = state.hasRemote && !state.busy) {
                        Icon(Icons.Default.Download, contentDescription = "拉取", tint = TextSecondary)
                    }
                    IconButton(onClick = { viewModel.push() }, enabled = state.hasRemote && !state.busy) {
                        Icon(Icons.Default.Upload, contentDescription = "推送", tint = TextSecondary)
                    }
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = TextPrimary)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.notReadyHint != null -> GitMessage(state.notReadyHint!!)
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
                state.notARepo -> NotARepo(
                    onInit = { viewModel.initRepo() },
                    onClone = { showClone = true },
                    onPickRepo = { viewModel.showRepoPicker() }
                )
                else -> Column(Modifier.fillMaxSize()) {
                    TabRow(
                        selectedTabIndex = state.tab.ordinal,
                        containerColor = Panel,
                        contentColor = Accent
                    ) {
                        GitTab.entries.forEach { tab ->
                            Tab(
                                selected = state.tab == tab,
                                onClick = { viewModel.selectTab(tab) },
                                text = {
                                    Text(
                                        when (tab) {
                                            GitTab.STATUS -> "改动"
                                            GitTab.BRANCHES -> "分支"
                                            GitTab.LOG -> "日志"
                                        }
                                    )
                                }
                            )
                        }
                    }
                    when (state.tab) {
                        GitTab.STATUS -> GitStatusTab(
                            state = state,
                            onStage = { viewModel.stage(it) },
                            onUnstage = { viewModel.unstage(it) },
                            onStageAll = { viewModel.stageAll() },
                            onUnstageAll = { viewModel.unstageAll() },
                            onRevert = { viewModel.revertWorktree(it) },
                            onRevertAll = { viewModel.revertAllWorktree() },
                            onDeleteUntracked = { viewModel.deleteUntracked(it) },
                            onOpenDiff = { path, staged -> viewModel.showWorkingDiff(path, staged) },
                            onCommit = { viewModel.commit(it) },
                            onSaveIdentityAndCommit = { n, e, m -> viewModel.saveIdentityAndCommit(n, e, m) }
                        )
                        GitTab.BRANCHES -> GitBranchesTab(
                            branches = state.branches,
                            tags = state.tags,
                            busy = state.busy,
                            onCheckout = { r, remote -> viewModel.checkout(r, remote) },
                            onCreateBranch = { name, start, co -> viewModel.createBranch(name, start, co) },
                            onDeleteBranch = { viewModel.deleteBranch(it) },
                            onRenameBranch = { o, n -> viewModel.renameBranch(o, n) },
                            onCreateTag = { viewModel.createTag(it) },
                            onDeleteTag = { viewModel.deleteTag(it) },
                            onDeleteRemote = { viewModel.deleteRemoteBranch(it) }
                        )
                        GitTab.LOG -> GitLogTab(
                            state = state,
                            onOpenCommit = { viewModel.openCommitDetail(it) },
                            onLoadMore = { viewModel.loadMoreCommits() }
                        )
                    }
                }
            }

            if (toastMsg != null) {
                Surface(
                    color = Panel, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                ) {
                    Text(toastMsg!!, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    // diff 全屏
    state.diff?.let { diff ->
        GitDiffSheet(diff = diff, onClose = { viewModel.closeDiff() })
    }

    // 提交详情
    val hash = state.commitDetailHash
    if (hash != null) {
        GitCommitDetailDialog(
            hash = hash,
            files = state.commitDetailFiles,
            loading = state.commitDetailLoading,
            onOpenFile = { path -> viewModel.showCommitFileDiff(hash, path) },
            onClose = { viewModel.closeCommitDetail() }
        )
    }

    // 仓库选择
    if (state.repoPickerVisible) {
        RepoPickerSheet(
            workspaceRoot = state.workspaceRoot,
            subdirs = state.subdirs,
            repos = state.repos,
            currentRepo = state.currentRepo,
            onSelect = { viewModel.switchRepo(it) },
            onDismiss = { viewModel.hideRepoPicker() }
        )
    }

    // 克隆远程仓库
    if (showClone) {
        CloneRepoDialog(
            onDismiss = { showClone = false },
            onConfirm = { url, name ->
                showClone = false
                viewModel.cloneRepo(url, name)
            }
        )
    }
}

@Composable
private fun GitMessage(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(text, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun NotARepo(onInit: () -> Unit, onClone: () -> Unit, onPickRepo: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("当前工作区不是 Git 仓库", color = TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = onInit, shape = RoundedCornerShape(10.dp), color = Accent
            ) {
                Text("初始化仓库", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                onClick = onClone, shape = RoundedCornerShape(10.dp), color = Panel
            ) {
                Text("克隆远程仓库", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onPickRepo) {
                Text("选择已有仓库", color = Accent, fontSize = 13.sp)
            }
        }
    }
}

/**
 * 仓库路径 → 显示名：工作区名/子目录名（如 xingde/git1）；工作区根本身只显示工作区名。
 *
 * 路径不在当前工作区下时（切换项目的瞬间可能发生）回退为末段，避免拼出 `xingde/tmp/other`
 * 这种误导性名字。
 */
internal fun repoDisplayPath(path: String, workspaceRoot: String): String {
    val root = workspaceRoot.trimEnd('/')
    val rootName = root.substringAfterLast('/')
    if (root.isEmpty()) return path.trimEnd('/').substringAfterLast('/')
    if (path != root && !path.startsWith("$root/")) return path.trimEnd('/').substringAfterLast('/')
    val relative = path.removePrefix(root).trimStart('/')
    return if (relative.isBlank()) rootName else "$rootName/$relative"
}

/** 仓库选择弹窗：列出工作区根与其一层直接子目录，Git 仓库可点选，非 Git 目录灰显并标注。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoPickerSheet(
    workspaceRoot: String,
    subdirs: List<String>,
    repos: List<String>,
    currentRepo: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val entries = remember(workspaceRoot, subdirs) {
        (if (workspaceRoot.isNotBlank()) listOf(workspaceRoot) else emptyList()) + subdirs
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "选择仓库",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
            if (entries.isEmpty()) {
                Text("工作区里没有可用目录", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(entries, key = { it }) { path ->
                        RepoPickerRow(
                            path = path,
                            workspaceRoot = workspaceRoot,
                            isRepo = path in repos,
                            isSelected = path == currentRepo,
                            onClick = { onSelect(path) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoPickerRow(
    path: String,
    workspaceRoot: String,
    isRepo: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isRepo) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isRepo) "\uD83D\uDCC1" else "\uD83D\uDCC2",
            fontSize = 16.sp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            repoDisplayPath(path, workspaceRoot),
            fontSize = 14.sp,
            color = if (isRepo) (if (isSelected) Accent else TextPrimary) else TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!isRepo) {
            Text("非仓库", fontSize = 12.sp, color = TextSecondary)
        } else if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** 克隆远程仓库弹窗：填 URL（可选目录名），确认后后台克隆并自动切换。 */
@Composable
private fun CloneRepoDialog(onDismiss: () -> Unit, onConfirm: (String, String?) -> Unit) {
    var url by remember { mutableStateOf("") }
    var dirName by remember { mutableStateOf("") }
    val canClone = url.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("克隆远程仓库", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "仓库会克隆到当前工作区下。私有仓库会弹窗要求填凭据。",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    placeholder = { Text("如 https://github.com/user/repo.git", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dirName, onValueChange = { dirName = it },
                    placeholder = { Text("目录名（留空取仓库名）", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim(), dirName.trim().takeIf { it.isNotEmpty() }) },
                enabled = canClone
            ) { Text("克隆", color = if (canClone) Accent else TextSecondary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } },
        containerColor = Panel
    )
}

// ===== 状态 tab =====

@Composable
private fun GitStatusTab(
    state: GitViewModel.GitUiState,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onRevert: (String) -> Unit,
    onRevertAll: () -> Unit,
    onDeleteUntracked: (String) -> Unit,
    onOpenDiff: (String, Boolean) -> Unit,
    onCommit: (String) -> Unit,
    onSaveIdentityAndCommit: (String, String, String) -> Unit
) {
    val status = state.status
    var commitDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // 顶部：暂存/取消暂存全部 + 提交
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallAction("全部暂存", enabled = !state.busy && status?.unstaged?.isNotEmpty() == true) { onStageAll() }
            SmallAction("全部取消", enabled = !state.busy && status?.staged?.isNotEmpty() == true) { onUnstageAll() }
            SmallAction("还原改动", enabled = !state.busy && status?.unstaged?.isNotEmpty() == true, danger = true) { onRevertAll() }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = { if (!state.busy && status?.staged?.isNotEmpty() == true) commitDialog = true },
                shape = RoundedCornerShape(8.dp),
                color = if (status?.staged?.isNotEmpty() == true) Accent else Border
            ) {
                Text("提交", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
        HorizontalDivider(color = Border.copy(alpha = 0.5f))

        if (status == null || !status.hasChanges) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("工作区干净，无未提交改动", color = TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                if (status.staged.isNotEmpty()) {
                    item { SectionHeader("已暂存 (${status.staged.size})") }
                    items(status.staged, key = { "s_${it.path}" }) { f ->
                        GitFileRow(f, staged = true, busy = state.busy,
                            onToggle = { onUnstage(f.path) },
                            onRevert = { onRevert(f.path) },
                            onOpenDiff = { onOpenDiff(f.path, true) },
                            onDelete = null)
                    }
                }
                if (status.unstaged.isNotEmpty()) {
                    item { SectionHeader("未暂存 (${status.unstaged.size})") }
                    items(status.unstaged, key = { "u_${it.path}" }) { f ->
                        GitFileRow(f, staged = false, busy = state.busy,
                            onToggle = { onStage(f.path) },
                            onRevert = { onRevert(f.path) },
                            onOpenDiff = { onOpenDiff(f.path, false) },
                            onDelete = null)
                    }
                }
                if (status.untracked.isNotEmpty()) {
                    item { SectionHeader("未跟踪 (${status.untracked.size})") }
                    items(status.untracked, key = { "t_$it" }) { p ->
                        GitUntrackedRow(p, busy = state.busy,
                            onStage = { onStage(p) },
                            onOpenDiff = { onOpenDiff(p, false) },
                            onDelete = { onDeleteUntracked(p) })
                    }
                }
            }
        }
    }

    if (commitDialog) {
        CommitDialog(
            hasIdentity = state.hasIdentity,
            initialName = state.userName,
            initialEmail = state.userEmail,
            onDismiss = { commitDialog = false },
            onConfirm = { name, email, msg ->
                commitDialog = false
                if (state.hasIdentity) onCommit(msg) else onSaveIdentityAndCommit(name, email, msg)
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = RoundedCornerShape(8.dp),
        color = Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Text(
            label,
            color = when {
                !enabled -> TextSecondary.copy(alpha = 0.4f)
                danger -> Danger
                else -> TextPrimary
            },
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun statusColor(code: String): Color = when (code.firstOrNull()) {
    'A' -> Success
    'D' -> Danger
    'M', 'R', 'C' -> Color(0xFFF59E0B)
    '?' -> TextSecondary
    else -> TextSecondary
}

@Composable
private fun GitFileRow(
    f: GitFileChange,
    staged: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRevert: () -> Unit,
    onOpenDiff: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onOpenDiff() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(f.statusCode, color = statusColor(f.statusCode), fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(f.path, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onRevert, enabled = !busy, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Undo, contentDescription = "还原", tint = TextSecondary, modifier = Modifier.size(17.dp))
        }
        IconButton(onClick = onToggle, enabled = !busy, modifier = Modifier.size(30.dp)) {
            Icon(
                if (staged) Icons.Default.Close else Icons.Default.Add,
                contentDescription = if (staged) "取消暂存" else "暂存",
                tint = if (staged) TextSecondary else Accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun GitUntrackedRow(path: String, busy: Boolean, onStage: () -> Unit, onOpenDiff: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onOpenDiff() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("??", color = TextSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(path, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Close, contentDescription = "删除", tint = Danger, modifier = Modifier.size(17.dp))
        }
        IconButton(onClick = onStage, enabled = !busy, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Add, contentDescription = "暂存", tint = Accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CommitDialog(
    hasIdentity: Boolean,
    initialName: String,
    initialEmail: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, email: String, msg: String) -> Unit
) {
    var msg by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialName) }
    var email by remember { mutableStateOf(initialEmail) }
    // 缺署名时才要求填写：git commit 没有 user.name/email 会直接失败。
    val identityOk = hasIdentity || (name.isNotBlank() && email.isNotBlank())
    val canCommit = msg.isNotBlank() && identityOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提交改动", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column {
                if (!hasIdentity) {
                    Text(
                        "首次提交需设置 git 署名（将写入容器全局配置，后续无需再填）",
                        color = TextSecondary, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        placeholder = { Text("用户名（user.name）", color = TextSecondary) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        placeholder = { Text("邮箱（user.email）", color = TextSecondary) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = msg, onValueChange = { msg = it },
                    placeholder = { Text("提交信息", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canCommit) onConfirm(name.trim(), email.trim(), msg.trim()) },
                enabled = canCommit
            ) {
                Text("提交", color = if (canCommit) Accent else TextSecondary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } },
        containerColor = Panel
    )
}

// ===== 分支 tab =====

@Composable
private fun GitBranchesTab(
    branches: List<GitBranch>,
    tags: List<com.wy.openx.git.model.GitTag>,
    busy: Boolean,
    onCheckout: (String, Boolean) -> Unit,
    onCreateBranch: (String, String?, Boolean) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onRenameBranch: (String, String) -> Unit,
    onCreateTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onDeleteRemote: (String) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("分支", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            SmallAction("新建分支", enabled = !busy) { showCreate = true }
        }
        HorizontalDivider(color = Border.copy(alpha = 0.5f))
        LazyColumn(Modifier.fillMaxSize()) {
            items(branches, key = { "${it.remote}_${it.name}" }) { b ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy && !b.current) {
                        onCheckout(b.name, b.remote)
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CallMerge,
                        contentDescription = null,
                        tint = if (b.current) Accent else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            b.name, color = if (b.current) Accent else TextPrimary,
                            fontSize = 14.sp, fontWeight = if (b.current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (b.remote || (b.ahead > 0 || b.behind > 0)) {
                            Text(
                                buildString {
                                    if (b.remote) append("远程  ")
                                    if (b.ahead > 0) append("↑${b.ahead}  ")
                                    if (b.behind > 0) append("↓${b.behind}")
                                }.trim(),
                                color = TextSecondary, fontSize = 11.sp
                            )
                        }
                    }
                    if (b.current) {
                        Text("当前", color = Accent, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
                    } else if (!b.remote) {
                        IconButton(onClick = { onDeleteBranch(b.name) }, enabled = !busy, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "删除分支", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        IconButton(onClick = { onDeleteRemote(b.name) }, enabled = !busy, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "删除远程分支", tint = Danger, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (tags.isNotEmpty()) {
                item { SectionHeader("标签 (${tags.size})") }
                items(tags, key = { "tag_${it.name}" }) { t ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t.shortHash, color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(10.dp))
                        Text(t.name, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onDeleteTag(t.name) }, enabled = !busy, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "删除标签", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        BranchCreateDialog(onDismiss = { showCreate = false }, onConfirm = { name, start, co ->
            showCreate = false
            onCreateBranch(name, start, co)
        })
    }
}

@Composable
private fun BranchCreateDialog(onDismiss: () -> Unit, onConfirm: (String, String?, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var checkout by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分支", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    placeholder = { Text("分支名", color = TextSecondary) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = start, onValueChange = { start = it },
                    placeholder = { Text("起点（可选，默认当前 HEAD）", color = TextSecondary) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { checkout = !checkout }) {
                    Icon(
                        if (checkout) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null, tint = if (checkout) Accent else TextSecondary, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("创建后立即切换", color = TextPrimary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), start.trim().ifBlank { null }, checkout) },
                enabled = name.isNotBlank()) {
                Text("创建", color = if (name.isNotBlank()) Accent else TextSecondary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } },
        containerColor = Panel
    )
}

// ===== 日志 tab =====

@Composable
private fun GitLogTab(
    state: GitViewModel.GitUiState,
    onOpenCommit: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    val graph = state.graph
    val commits = graph.commits
    if (commits.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("暂无提交记录", color = TextSecondary, fontSize = 14.sp)
        }
        return
    }
    val laneColors = remember(graph.maxLane) { rememberLaneColors(graph.maxLane + 1) }
    val laneWidth = if (graph.maxLane == 0) 10.dp else 16.dp
    val canvasWidth = laneWidth * (graph.maxLane + 1) + 8.dp
    val edgesByCommit = remember(graph) { groupEdgesByCommit(graph) }

    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(commits, key = { _, c -> c.hash }) { idx, c ->
            val lane = graph.lanes[c.hash] ?: 0
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onOpenCommit(c.hash) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：拓扑泳道 Canvas（撑满行高，保证跨行连线连续）
                GraphCanvas(
                    edges = edgesByCommit[idx].orEmpty(),
                    activeTopLanes = graph.activeTopLanes[c.hash].orEmpty(),
                    activeBottomLanes = graph.activeBottomLanes[c.hash].orEmpty(),
                    lane = lane,
                    isMerge = c.isMerge,
                    laneColors = laneColors,
                    laneWidth = laneWidth,
                    suppressTopLane = idx == 0,
                    modifier = Modifier.width(canvasWidth).fillMaxHeight()
                )
                Column(Modifier.weight(1f).padding(vertical = 8.dp).padding(end = 12.dp)) {
                    val refs = graph.refs[c.hash].orEmpty()
                    if (refs.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp)
                        ) {
                            refs.forEach { r ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when {
                                        r.isCurrent -> Accent
                                        r.isRemote -> Panel
                                        r.isBranch -> Success.copy(alpha = 0.25f)
                                        else -> Panel
                                    }
                                ) {
                                    Text(
                                        r.name,
                                        color = if (r.isCurrent) Color.White else TextPrimary,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    Text(c.message, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${c.shortHash}  ${c.author}  ${c.date}",
                        color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(color = Border.copy(alpha = 0.3f), modifier = Modifier.padding(start = canvasWidth))
        }
        if (graph.hasMore) {
            item {
                Box(
                    Modifier.fillMaxWidth().clickable { onLoadMore() }.padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("加载更多", color = Accent, fontSize = 13.sp)
                }
            }
        }
    }
}

/** 泳道配色循环。 */
private fun rememberLaneColors(count: Int): List<Color> {
    val palette = com.wy.openx.ui.theme.GitLanePalette.colors
    return (0 until count.coerceAtLeast(1)).map { palette[it % palette.size] }
}

/**
 * 把 [GitGraph.edges] 按来源提交索引分组：算法按提交顺序为每个提交生成 parents.size 条边
 * （根提交 0 条），故按父数累积分组即可重建对应。
 */
private fun groupEdgesByCommit(graph: com.wy.openx.git.model.GitGraph): Map<Int, List<com.wy.openx.git.model.GraphEdge>> {
    val result = mutableMapOf<Int, List<com.wy.openx.git.model.GraphEdge>>()
    var edgeIdx = 0
    graph.commits.forEachIndexed { commitIdx, commit ->
        val n = if (commit.parents.isEmpty()) 0 else commit.parents.size
        val list = mutableListOf<com.wy.openx.git.model.GraphEdge>()
        repeat(n) {
            if (edgeIdx < graph.edges.size) list.add(graph.edges[edgeIdx++])
        }
        result[commitIdx] = list
    }
    return result
}

/**
 * 拓扑图 Canvas：绘制当前提交节点 + 分支连线。
 * 绘制顺序：分段竖线 → 跨列贝塞尔连线 → 节点圆点（节点压线）。
 */
@Composable
private fun GraphCanvas(
    edges: List<com.wy.openx.git.model.GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    lane: Int,
    isMerge: Boolean,
    laneColors: List<Color>,
    laneWidth: androidx.compose.ui.unit.Dp,
    suppressTopLane: Boolean = false,
    modifier: Modifier = Modifier
) {
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val lanePx = laneWidth.toPx()
        val padPx = 4.dp.toPx()
        val centerX = lane * lanePx + lanePx / 2f + padPx
        val centerY = size.height / 2f
        val stroke = 2.5.dp.toPx()

        val allLanes = (activeTopLanes + activeBottomLanes + lane).toSet()
        val crossEdgeToLanes = edges.filter { it.fromLane != it.toLane }.map { it.toLane }.toSet()
        val curveBotLanes = crossEdgeToLanes.filterNot { it in activeTopLanes }.toSet()

        // 1. 分段竖线
        for (l in allLanes) {
            val inTop = l in activeTopLanes
            val inBot = l in activeBottomLanes && l !in curveBotLanes
            val color = laneColors.getOrElse(l) { Color.Gray }
            val x = l * lanePx + lanePx / 2f + padPx
            when {
                inTop && inBot -> {
                    val startY = if (suppressTopLane) centerY else 0f
                    drawLine(
                        color = color,
                        start = androidx.compose.ui.geometry.Offset(x, startY),
                        end = androidx.compose.ui.geometry.Offset(x, size.height),
                        strokeWidth = stroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
                inTop -> if (!suppressTopLane) {
                    drawLine(
                        color = color,
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x, centerY),
                        strokeWidth = stroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
                inBot -> drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(x, centerY),
                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }

        // 2. 跨列连线（下半段贝塞尔）
        for (edge in edges) {
            if (edge.fromLane == edge.toLane) continue
            val color = laneColors.getOrElse(edge.lane) { Color.Gray }
            val fromX = edge.fromLane * lanePx + lanePx / 2f + padPx
            val toX = edge.toLane * lanePx + lanePx / 2f + padPx
            val midY = centerY + (size.height - centerY) * 0.5f
            val path = androidx.compose.ui.graphics.Path().apply {
                if (edge.isMergeIn) {
                    moveTo(toX, size.height)
                    cubicTo(toX, midY, fromX, midY, fromX, centerY)
                } else {
                    moveTo(fromX, centerY)
                    cubicTo(fromX, midY, toX, midY, toX, size.height)
                }
            }
            drawPath(
                path = path,
                color = color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )
        }

        // 3. 节点圆点：合并画环形双圈，普通画实心圆
        val nodeRadius = if (isMerge) 7.dp.toPx() else 5.dp.toPx()
        if (isMerge) {
            drawCircle(color = nodeColor, radius = nodeRadius,
                center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx()))
            drawCircle(color = nodeColor, radius = nodeRadius / 2f,
                center = androidx.compose.ui.geometry.Offset(centerX, centerY))
        } else {
            drawCircle(color = nodeColor, radius = nodeRadius,
                center = androidx.compose.ui.geometry.Offset(centerX, centerY))
        }
    }
}

// ===== Diff 全屏 =====

@Composable
private fun GitDiffSheet(diff: GitViewModel.DiffData, onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = PageBg) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextSecondary)
                }
                Text(diff.fileName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = Border.copy(alpha = 0.5f))
            if (diff.message != null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(diff.message, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                }
            } else {
                val family = remember(diff.fileName) { syntaxFamilyOf(diff.fileName) }
                val darkMode = com.wy.openx.ui.theme.currentThemeColors.background.luminance() < 0.5f
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    diff.lines.forEach { line ->
                        val color = when (line.type) {
                            LineDiff.LineType.ADD -> Color(0xFF4CAF50)
                            LineDiff.LineType.REMOVE -> Color(0xFFE53935)
                            LineDiff.LineType.CONTEXT -> TextPrimary
                        }
                        val bg = when (line.type) {
                            LineDiff.LineType.ADD -> Color(0xFF4CAF50).copy(alpha = 0.08f)
                            LineDiff.LineType.REMOVE -> Color(0xFFE53935).copy(alpha = 0.08f)
                            LineDiff.LineType.CONTEXT -> Color.Transparent
                        }
                        val prefix = when (line.type) {
                            LineDiff.LineType.ADD -> "+ "
                            LineDiff.LineType.REMOVE -> "- "
                            LineDiff.LineType.CONTEXT -> "  "
                        }
                        // 语法高亮：仅 CONTEXT 行用 token 颜色（增删行保留红绿语义，高亮会盖掉）。失败降级纯文本。
                        val highlighted = remember(line.text, family, darkMode) {
                            if (line.type == LineDiff.LineType.CONTEXT) highlightCode(line.text, family, darkMode) else null
                        }
                        Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            if (highlighted != null) {
                                Text(
                                    highlighted, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace, lineHeight = 17.sp
                                )
                            } else {
                                Text(
                                    prefix + line.text, color = color, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace, lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitCommitDetailDialog(
    hash: String,
    files: List<GitFileChange>,
    loading: Boolean,
    onOpenFile: (String) -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(hash.take(12), color = TextPrimary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth().height(80.dp), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            } else if (files.isEmpty()) {
                Text("该提交无文件改动", color = TextSecondary, fontSize = 13.sp)
            } else {
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(files, key = { it.path }) { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenFile(f.path) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(f.statusCode, color = statusColor(f.statusCode), fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text(f.path, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭", color = Accent) } },
        containerColor = Panel
    )
}

// ===== 轻量语法高亮（零依赖，基于正则 token）=====
//
// 参考实现的语法高亮依赖 dev.snipme:highlights，但该库用 Kotlin 2.x 编译，
// 与本项目 Kotlin 1.9 元数据不兼容，故改用自研正则 token 高亮：覆盖注释/字符串/关键字/
// 数字/常见类型，足以在 diff 里提升可读性，且不引入依赖。

/** 支持的语法族（按扩展名映射）。 */
private enum class SyntaxFamily { C_LIKE, PYTHON, SHELL, PLAIN }

private val SYNTAX_KEYWORDS = mapOf(
    SyntaxFamily.C_LIKE to setOf(
        "class", "fun", "val", "var", "if", "else", "when", "for", "while", "return", "break", "continue",
        "object", "interface", "enum", "import", "package", "public", "private", "protected", "internal",
        "override", "open", "abstract", "sealed", "data", "suspend", "inline", "const", "static", "final",
        "new", "this", "super", "null", "true", "false", "void", "int", "long", "double", "float", "boolean",
        "char", "byte", "short", "extends", "implements", "throws", "try", "catch", "finally", "throw",
        "function", "let", "const", "async", "await", "export", "default", "struct", "type", "def"
    ),
    SyntaxFamily.PYTHON to setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "with",
        "try", "except", "finally", "raise", "lambda", "None", "True", "False", "and", "or", "not", "in",
        "is", "pass", "break", "continue", "global", "nonlocal", "yield", "async", "await", "self"
    ),
    SyntaxFamily.SHELL to setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
        "return", "export", "local", "echo", "cd", "exit", "in", "set", "unset", "read"
    )
)

private val EXT_FAMILY = mapOf(
    "kt" to SyntaxFamily.C_LIKE, "kts" to SyntaxFamily.C_LIKE,
    "java" to SyntaxFamily.C_LIKE, "js" to SyntaxFamily.C_LIKE, "mjs" to SyntaxFamily.C_LIKE,
    "ts" to SyntaxFamily.C_LIKE, "go" to SyntaxFamily.C_LIKE, "rs" to SyntaxFamily.C_LIKE,
    "c" to SyntaxFamily.C_LIKE, "h" to SyntaxFamily.C_LIKE, "cpp" to SyntaxFamily.C_LIKE,
    "cc" to SyntaxFamily.C_LIKE, "cxx" to SyntaxFamily.C_LIKE, "hpp" to SyntaxFamily.C_LIKE,
    "cs" to SyntaxFamily.C_LIKE, "dart" to SyntaxFamily.C_LIKE, "swift" to SyntaxFamily.C_LIKE,
    "php" to SyntaxFamily.C_LIKE, "json" to SyntaxFamily.C_LIKE,
    "py" to SyntaxFamily.PYTHON,
    "sh" to SyntaxFamily.SHELL, "bash" to SyntaxFamily.SHELL
)

/** 按扩展名推断语法族；未识别返回 PLAIN（不高亮）。 */
private fun syntaxFamilyOf(filePath: String): SyntaxFamily {
    val ext = filePath.substringAfterLast('.', "").lowercase()
    return EXT_FAMILY[ext] ?: SyntaxFamily.PLAIN
}

/** 高亮配色（深浅主题各一套）。 */
private data class SyntaxColors(
    val keyword: Color, val string: Color, val comment: Color, val number: Color
)

private fun syntaxColors(dark: Boolean) = if (dark) {
    SyntaxColors(
        keyword = Color(0xFFC792EA),
        string = Color(0xFFC3E88D),
        comment = Color(0xFF697098),
        number = Color(0xFFF78C6C)
    )
} else {
    SyntaxColors(
        keyword = Color(0xFF7C4DFF),
        string = Color(0xFF2E7D32),
        comment = Color(0xFF8E8E8E),
        number = Color(0xFFC2185B)
    )
}

/**
 * 对单行代码做轻量语法高亮，返回带 SpanStyle 的 AnnotatedString；语言未识别或解析失败返回 null
 * （调用方降级为纯文本）。token 扫描顺序：注释 > 字符串 > 关键字 > 数字（后写覆盖先写，但用区间
 * 去重避免嵌套覆盖）。
 */
private fun highlightCode(
    code: String,
    family: SyntaxFamily,
    darkMode: Boolean
): androidx.compose.ui.text.AnnotatedString? {
    if (family == SyntaxFamily.PLAIN || code.isBlank()) return null
    return try {
        val colors = syntaxColors(darkMode)
        val keywords = SYNTAX_KEYWORDS[family].orEmpty()
        val spans = mutableListOf<Triple<Int, Int, Color>>()

        // 注释：C 系 // 与 /* */ 行内；Python/Shell 用 #
        val commentIdx = when (family) {
            SyntaxFamily.PYTHON, SyntaxFamily.SHELL -> code.indexOf('#')
            else -> {
                val slash = code.indexOf("//")
                val block = code.indexOf("/*")
                listOf(slash, block).filter { it >= 0 }.minOrNull() ?: -1
            }
        }
        if (commentIdx >= 0) {
            spans.add(Triple(commentIdx, code.length, colors.comment))
        }

        // 字符串：单/双引号成对（简单扫描，忽略转义细节）
        var i = 0
        while (i < code.length) {
            val c = code[i]
            if (c == '"' || c == '\'') {
                val end = code.indexOf(c, i + 1)
                if (end < 0) break
                // 不覆盖已识别为注释的区域
                if (commentIdx < 0 || i < commentIdx) spans.add(Triple(i, end + 1, colors.string))
                i = end + 1
            } else i++
        }

        // 关键字：单词边界匹配
        val wordRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
        for (m in wordRegex.findAll(code)) {
            val w = m.value
            if (w in keywords) spans.add(Triple(m.range.first, m.range.last + 1, colors.keyword))
        }

        // 数字字面量
        for (m in Regex("\\b\\d+(\\.\\d+)?\\b").findAll(code)) {
            spans.add(Triple(m.range.first, m.range.last + 1, colors.number))
        }

        if (spans.isEmpty()) return null
        androidx.compose.ui.text.buildAnnotatedString {
            append(code)
            // 后加的 span 优先：先加注释/字符串，再加关键字/数字会被其覆盖——故按「优先级低→高」排序
            spans.sortedBy { it.third == colors.comment || it.third == colors.string }
                .forEach { (s, e, col) -> addStyle(androidx.compose.ui.text.SpanStyle(color = col), s, e) }
        }
    } catch (e: Exception) {
        null
    }
}
