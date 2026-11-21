package com.wy.openx.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import com.wy.openx.container.FileLogger
import com.wy.openx.container.ArchiveCompressor
import com.wy.openx.container.ArchiveEntryInfo
import com.wy.openx.container.ArchiveExtractor
import com.wy.openx.container.ArchiveScanner
import com.wy.openx.container.BrowseClipboard
import com.wy.openx.container.BrowseFileSystem
import com.wy.openx.container.FileBrowseState
import com.wy.openx.container.FileEntry
import com.wy.openx.container.isArchiveFileName
import com.wy.openx.data.store.FileTreeUiState
import com.wy.openx.data.store.SettingsStore
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.ButtonBg
import com.wy.openx.ui.theme.ButtonFg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import com.wy.openx.ui.theme.Warning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater

/**
 * 侧边栏文件树：容器根「/」就地展开。目录长按弹功能菜单：
 * 新建文件 / 新建文件夹 / 复制 / 剪切 / 粘贴到此处 / 压缩 / 解压到 / 重命名 / 删除 / 属性。
 * 每行共享同一横向滚动，路径过深时左右滑动查看完整名称。
 */
@Composable
internal fun FileTreeBrowserTab(
    browseFileSystem: BrowseFileSystem?,
    /** 已连上的远程服务器；非空时在容器根上方显示可展开的「远程根」。 */
    remoteConnection: com.wy.openx.remote.RemoteConnection? = null,
    clipboard: BrowseClipboard? = null,
    onClipboardChange: (BrowseClipboard?) -> Unit = {},
    /**
     * 长按菜单「用浏览器打开」：传入**容器路径**，由上层打开内置浏览器并加载它。
     * 单击仍是编辑器（保留看/改源码），只有菜单项走这条渲染预览路径。
     */
    onOpenInBrowser: (String) -> Unit = {},
    /**
     * 长按菜单「加入输入栏」：传入该节点的**容器路径**与显示名，由上层投进聊天输入区当附件。
     * 为 null 时菜单里不出现这一项。
     */
    onAddToInput: ((path: String, name: String) -> Unit)? = null
) {
    var dirCache by remember { mutableStateOf<Map<String, FileBrowseState>>(emptyMap()) }
    var showRemoteRoot by remember { mutableStateOf(false) }
    var expandedPaths by remember { mutableStateOf(setOf(ROOT_PATH)) }
    var loadedFileTree by remember { mutableStateOf(false) }
    var pendingRestoreScroll by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val fileTreeContext = LocalContext.current
    val fileTreeStore = remember { SettingsStore(fileTreeContext.applicationContext) }
    val persistScope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    var creating by remember { mutableStateOf<TreeCreateTarget?>(null) }
    var menuNode by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingRename by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingDelete by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingProperties by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingCompress by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingExtract by remember { mutableStateOf<FileTreeNode?>(null) }
    var archiveBrowse by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingOpen by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingImageView by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingOverwrite by remember { mutableStateOf<PasteJob?>(null) }
    var briefMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hScroll = rememberScrollState()
    // 滚动位置提升到组件顶层，refresh（复制/粘贴/新建等）进入 Loading 分支时不销毁；
    // 展开目录与滚动位置均由 DataStore 磁盘持久化，切 Tab / 关抽屉再开也能恢复。
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(Unit) {
        try {
            val st = fileTreeStore.loadFileTreeState()
            if (st.expandedPaths.isNotEmpty()) expandedPaths = st.expandedPaths
            loadedFileTree = true
            if (st.firstVisibleItemIndex > 0 || st.firstVisibleItemScrollOffset > 0) {
                pendingRestoreScroll = st.firstVisibleItemIndex to st.firstVisibleItemScrollOffset
            }
        } catch (_: Exception) {
            loadedFileTree = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            persistScope.launch {
                try {
                    fileTreeStore.saveFileTreeState(
                        FileTreeUiState(
                            expandedPaths = expandedPaths,
                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    LaunchedEffect(loadedFileTree) {
        if (!loadedFileTree) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .debounce(300)
            .collect { (index, offset) ->
                scope.launch {
                    try {
                        fileTreeStore.saveFileTreeState(
                            FileTreeUiState(
                                expandedPaths = expandedPaths,
                                firstVisibleItemIndex = index,
                                firstVisibleItemScrollOffset = offset
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
    }

    LaunchedEffect(briefMessage) {
        if (briefMessage != null) {
            delay(2500L)
            briefMessage = null
        }
    }

    fun loadDir(dir: String) {
        if (dirCache.containsKey(dir)) return
        val f = browseFileSystem
        dirCache = dirCache + (dir to FileBrowseState.Loading)
        scope.launch {
            val res = if (f != null) {
                withContext(Dispatchers.IO) { runCatching { f.listFiles(dir) } }
            } else {
                Result.failure(IOException("文件系统未就绪"))
            }
            dirCache = dirCache + (dir to res.fold(
                onSuccess = { FileBrowseState.Success(it) },
                onFailure = { FileBrowseState.Error(it.message) }
            ))
        }
    }

    /**
     * 重新拉取一个目录，**保留旧数据直到新数据到手**（stale-while-revalidate）。
     *
     * 原来是 `dirCache = emptyMap()` 整树清空重载，后果很重：
     * nodes 瞬间塌成 1 行 → 挂在同一个 LazyColumn 上的 LazyListState 的
     * firstVisibleItemIndex 被夹回 0 → 整棵树异步重建，视口位置必然丢失。
     * 用户表现就是「复制粘贴一下，树自己跑到别处去了」。
     *
     * 这里**刻意不把已加载的目录置回 Loading**：dirCache 里那份旧数据一直留着，
     * nodes 就不会塌，listState 的位置天然保留，也不需要任何「记锚点再滚回去」
     * 的补偿逻辑——那种逻辑依赖「锚点是否已经出现在 nodes 里」的时序判断，
     * 目录是分层异步加载的，极易在文件出现前就把锚点判掉（第一版就是这么坏的）。
     */
    fun reloadDir(dir: String) {
        val f = browseFileSystem
        scope.launch {
            val res = if (f != null) {
                withContext(Dispatchers.IO) { runCatching { f.listFiles(dir) } }
            } else {
                Result.failure(IOException("文件系统未就绪"))
            }
            dirCache = dirCache + (dir to res.fold(
                onSuccess = { FileBrowseState.Success(it) },
                onFailure = { FileBrowseState.Error(it.message) }
            ))
        }
    }


    /**
     * 手动刷新：重拉所有已加载的目录。同样不拆树，所以既不闪 Loading，
     * 也不会把视口甩出去——比整树清空刷新得还全。
     */
    fun reloadAll() {
        val dirs = dirCache.keys.toList()
        if (dirs.isEmpty()) loadDir(ROOT_PATH) else dirs.forEach { reloadDir(it) }
    }

    /** 重命名目录后同步展开状态，见 [remapExpandedPaths]。 */
    fun remapExpanded(old: String, new: String) {
        expandedPaths = remapExpandedPaths(expandedPaths, old, new)
    }

    /** 剪掉已不存在的展开路径，见 [pruneMissingExpandedPaths]。 */
    fun pruneMissingExpanded() {
        val before = expandedPaths
        val after = pruneMissingExpandedPaths(before, dirCache)
        if (after == before) return
        expandedPaths = after
        // 顺带丢掉这些目录自己残留的缓存条目（目录没了，缓存也该走）
        val gone = before - after
        if (gone.isNotEmpty()) dirCache = dirCache.filterKeys { it !in gone }
    }

    /**
     * 粘贴前把目标目录展开。
     *
     * 只做这一件事，**不再强滚到新文件**：reloadDir 不拆树，用户本来就停在
     * 粘贴的那个目录里，新文件按排序出现在该目录内。主动 scrollToItem 反而
     * 会把视口拽走，正是用户抱怨的「目录乱跑」。
     * 目标目录若是折叠的（长按折叠目录直接粘贴），不展开就根本看不到新文件。
     */
    fun expandAfterPaste(parent: String) {
        if (parent !in expandedPaths) expandedPaths = expandedPaths + parent
    }

    /**
     * 粘贴完成后刷新应刷的目录。
     *
     * ⚠ 剪切**必须同时刷源目录和目标目录**（2026-10-05 修的用户报障：
     * 「剪切怎么和复制一样，怎么还留下文件」）。
     *
     * 根因：`dirCache` 每个目录一份缓存，粘贴时只 `reloadDir(dstParent)`，
     * 源目录那份还是旧的 → 文件已经 `fs.move` 走了（磁盘上源确实不存在了），
     * 但 UI 仍按旧缓存把那个条目画出来，用户看到的就是「复制而不是剪切」。
     *
     * 对照同文件里删除/重命名：它们刷的是 `parentOf(源)`；复制只需刷目标（源没变），
     * 剪切则是源与目标**都**变了 —— 原来漏掉的正是源这一半。
     *
     * 同级移动时源父目录 == 目标父目录，去重避免重复 IO。
     */
    fun reloadAfterPaste(clip: BrowseClipboard, dstParent: String) {
        expandAfterPaste(dstParent)
        dirsToReloadAfterPaste(clip, dstParent).forEach { reloadDir(it) }
    }

    // 每次目录加载完成都复查一遍。加载中/出错的父目录会被下面保守跳过，
    // 下一轮加载完成自然补上，所以这个时机是安全的。
    LaunchedEffect(dirCache) { pruneMissingExpanded() }

    val needLoad = remember(expandedPaths, dirCache) {
        buildSet {
            add(ROOT_PATH)
            expandedPaths.forEach { if (it !in dirCache) add(it) }
        }
    }
    LaunchedEffect(needLoad) {
        needLoad.forEach { loadDir(it) }
    }

    val nodes = remember(expandedPaths, dirCache) {
        val out = ArrayList<FileTreeNode>()
        out.add(
            FileTreeNode(
                entry = ROOT_ENTRY,
                path = ROOT_PATH,
                depth = 0,
                isRoot = true,
                isExpanded = ROOT_PATH in expandedPaths,
                hasError = dirCache[ROOT_PATH] is FileBrowseState.Error
            )
        )
        fun walk(dir: String, depth: Int) {
            val st = dirCache[dir] as? FileBrowseState.Success ?: return
            for (entry in st.entries) {
                val childPath = entry.overridePath
                    ?: if (dir == ROOT_PATH) "/${entry.name}" else "$dir/${entry.name}"
                val expanded = entry.isDirectory && childPath in expandedPaths
                out.add(
                    FileTreeNode(
                        entry = entry,
                        path = childPath,
                        depth = depth,
                        isRoot = false,
                        isExpanded = expanded,
                        hasError = expanded && dirCache[childPath] is FileBrowseState.Error
                    )
                )
                if (expanded) walk(childPath, depth + 1)
            }
        }
        if (ROOT_PATH in expandedPaths) walk(ROOT_PATH, 1)
        out
    }

    fun toggleExpand(path: String) {
        expandedPaths = if (path in expandedPaths) expandedPaths - path else expandedPaths + path
        scope.launch {
            try {
                fileTreeStore.saveFileTreeState(
                    FileTreeUiState(
                        expandedPaths = expandedPaths,
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            clipboard?.let { clip ->
                ClipboardBar(
                    clipboard = clip,
                    onClear = { onClipboardChange(null) },
                    onRefresh = { reloadAll() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (clipboard == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    RefreshButton(onClick = { reloadAll() })
                }
            }
            if (remoteConnection != null) {
                val remote = remoteConnection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showRemoteRoot = !showRemoteRoot }
                        .padding(vertical = 6.dp)
                        .padding(start = 8.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (showRemoteRoot) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        contentDescription = if (showRemoteRoot) "收起远程根" else "展开远程根",
                        modifier = Modifier.size(16.dp),
                        tint = TextSecondary
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "远程根 · ${remote.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (showRemoteRoot) {
                    RemoteConnectionBrowser(config = remote)
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val rootState = dirCache[ROOT_PATH]
                when {
                    browseFileSystem == null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("文件系统未就绪", color = TextSecondary, fontSize = 13.sp)
                    }
                    (rootState == null || rootState is FileBrowseState.Loading) -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    rootState is FileBrowseState.Error -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = rootState.detail ?: "加载失败",
                            color = Color(0xFFFF6B6B),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    else -> {
                        val textMeasurer = rememberTextMeasurer()
                        val textStyle = MaterialTheme.typography.bodyMedium
                        val density = LocalDensity.current
                        // 预算最宽一行内容宽度（固定开销 + 缩进 + 文本），整树统一行宽实现横向整体平移。
                        val maxContentPx = remember(nodes, textStyle) {
                            with(density) {
                                val widest = nodes.maxByOrNull { node ->
                                    val label = nodeLabel(node)
                                    node.depth * 4 + estimateLabelWidth(label)
                                } ?: return@with 0
                                val textW = textMeasurer.measure(nodeLabel(widest), textStyle).size.width
                                (FILE_TREE_ROW_OVERHEAD.toPx() + (ROW_INDENT * widest.depth).toPx() + textW).toInt()
                            }
                        }
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val rowWidthPx = maxOf(constraints.maxWidth, maxContentPx)
                            val rowWidth = with(density) { rowWidthPx.toDp() }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .horizontalScroll(hScroll)
                            ) {
                                items(nodes, key = { it.path }) { node ->
                                    FileTreeRow(
                                        node = node,
                                        rowWidth = rowWidth,
                                        onClick = {
                                            when {
                                                node.entry.isDirectory -> toggleExpand(node.path)
                                                isArchiveFileName(node.entry.name) -> archiveBrowse = node
                                                isViewableImageName(node.entry.name) -> pendingImageView = node
                                                // APK/APKS/XAPK：内置编辑器对它没意义（会显示乱码），
                                                // 交给系统安装器。见 FileOpenHelper。
                                                FileOpenHelper.isInstallablePackage(node.entry.name) -> {
                                                    val fs = browseFileSystem
                                                    if (fs == null) {
                                                        briefMessage = "文件系统未就绪"
                                                    } else {
                                                        FileOpenHelper.openFile(
                                                            fileTreeContext,
                                                            fs.hostFile(node.path),
                                                            "application/vnd.android.package-archive"
                                                        )
                                                    }
                                                }
                                                else -> pendingOpen = node
                                            }
                                        },
                                        onLongClick = { menuNode = node }
                                    )
                                }
                            }
                            LaunchedEffect(nodes, loadedFileTree) {
                                val pending = pendingRestoreScroll ?: return@LaunchedEffect
                                if (!loadedFileTree) return@LaunchedEffect
                                if (pending.first >= listState.layoutInfo.totalItemsCount) return@LaunchedEffect
                                listState.scrollToItem(pending.first, pending.second)
                                pendingRestoreScroll = null
                            }
                        }
                    }
                }
            }
        }
    }

    creating?.let { target ->
        FileNameInputDialog(
            title = if (target.kind == TreeCreateKind.FILE) "新建文件" else "新建文件夹",
            confirmLabel = "创建",
            initialName = "",
            onConfirm = { name ->
                val fs = browseFileSystem
                if (fs != null) {
                    val targetPath = if (target.parent == ROOT_PATH) "/$name" else "${target.parent}/$name"
                    runCatching {
                        if (target.kind == TreeCreateKind.FILE) fs.writeFile(targetPath, "", false)
                        else fs.mkdirs(targetPath)
                    }.onFailure { briefMessage = it.message ?: "创建失败" }
                }
                creating = null
                reloadDir(target.parent)
            },
            onDismiss = { creating = null }
        )
    }

    menuNode?.let { node ->
        FileTreeActionSheet(
            node = node,
            clipboard = clipboard,
            onNewFile = {
                menuNode = null
                if (!node.isRoot && node.path !in expandedPaths) toggleExpand(node.path)
                creating = TreeCreateTarget(node.path, TreeCreateKind.FILE)
            },
            onNewFolder = {
                menuNode = null
                if (!node.isRoot && node.path !in expandedPaths) toggleExpand(node.path)
                creating = TreeCreateTarget(node.path, TreeCreateKind.FOLDER)
            },
            onRename = {
                menuNode = null
                pendingRename = node
            },
            onDelete = {
                menuNode = null
                pendingDelete = node
            },
            onProperties = {
                menuNode = null
                pendingProperties = node
            },
            onExtract = {
                menuNode = null
                pendingExtract = node
            },
            onOpenInBrowser = {
                menuNode = null
                onOpenInBrowser(node.path)
            },
            onAddToInput = if (onAddToInput != null) {
                {
                    menuNode = null
                    onAddToInput.invoke(node.path, node.entry.name)
                }
            } else null,
            onCompress = {
                menuNode = null
                pendingCompress = node
            },
            onCopy = {
                menuNode = null
                onClipboardChange(BrowseClipboard(sourcePath = node.path, sourceName = node.entry.name, isCut = false))
            },
            onCut = {
                menuNode = null
                onClipboardChange(BrowseClipboard(sourcePath = node.path, sourceName = node.entry.name, isCut = true))
            },
            onPasteHere = {
                menuNode = null
                val clip = clipboard
                val fs = browseFileSystem
                if (clip != null && fs != null) {
                    // "/a".substringBeforeLast('/') 是 ""，顶层目录要归一化成 ROOT_PATH，
                    // 否则下面 focusAfterPaste 会往 expandedPaths 里塞一个空串。
                    val dstParent = if (node.entry.isDirectory) node.path
                    else if (node.isRoot) ROOT_PATH
                    else node.path.substringBeforeLast('/').ifEmpty { ROOT_PATH }
                    val target = if (dstParent == ROOT_PATH) "/${clip.sourceName}" else "$dstParent/${clip.sourceName}"
                    when {
                        target == clip.sourcePath -> briefMessage = "源与目标相同，无需粘贴"
                        fs.exists(target) -> pendingOverwrite = PasteJob(clip = clip, target = target)
                        else -> {
                            runCatching {
                                if (clip.isCut) fs.move(clip.sourcePath, target)
                                else fs.copy(clip.sourcePath, target)
                            }.onFailure { briefMessage = it.message ?: "粘贴失败" }
                            onClipboardChange(null)
                            reloadAfterPaste(clip, dstParent)
                        }
                    }
                }
            },
            onDismiss = { menuNode = null }
        )
    }

    pendingOverwrite?.let { job ->
        OverwriteDialog(
            clipboardName = job.clip.sourceName,
            targetName = job.target.substringAfterLast('/', ""),
            isCut = job.clip.isCut,
            onConfirm = {
                val fs = browseFileSystem
                if (fs != null) {
                    runCatching {
                        if (job.clip.isCut) fs.move(job.clip.sourcePath, job.target)
                        else fs.copy(job.clip.sourcePath, job.target)
                    }.onFailure { briefMessage = it.message ?: "粘贴失败" }
                    onClipboardChange(null)
                    reloadAfterPaste(job.clip, parentOf(job.target))
                }
                pendingOverwrite = null
            },
            onDismiss = { pendingOverwrite = null }
        )
    }

    pendingRename?.let { node ->
        FileNameInputDialog(
            title = "重命名",
            confirmLabel = "重命名",
            initialName = node.entry.name,
            onConfirm = { name ->
                val fs = browseFileSystem
                if (fs != null) {
                    val parent = if (node.isRoot) ROOT_PATH else parentOf(node.path)
                    val dst = if (parent == ROOT_PATH) "/$name" else "$parent/$name"
                    runCatching { fs.rename(node.path, dst) }
                        .onSuccess {
                            // 展开状态跟着新路径走，否则 expandedPaths 里留着旧路径：
                            // 新目录不会自动展开，旧路径还会被 loadDir / needLoad 白试一次，
                            // 并且被持久化进 DataStore 越攒越多。
                            // 失败时不能改写——此时旧路径仍然是对的。
                            if (!node.isRoot && node.entry.isDirectory) remapExpanded(node.path, dst)
                        }
                        .onFailure { briefMessage = it.message ?: "重命名失败" }
                }
                pendingRename = null
                reloadDir(parentOf(node.path))
            },
            onDismiss = { pendingRename = null }
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除") },
            text = {
                Text(
                    if (node.entry.isDirectory) "删除文件夹「${node.entry.name}」及其全部内容？"
                    else "删除文件「${node.entry.name}」？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val fs = browseFileSystem
                    if (fs != null) {
                        runCatching { fs.deleteRecursively(node.path) }
                            .onFailure { briefMessage = it.message ?: "删除失败" }
                    }
                    pendingDelete = null
                    reloadDir(parentOf(node.path))
                }) { Text("删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", color = TextSecondary) }
            }
        )
    }

    pendingProperties?.let { node ->
        FilePropertiesDialog(
            node = node,
            browseFileSystem = browseFileSystem,
            onDismiss = { pendingProperties = null }
        )
    }

    pendingCompress?.let { node ->
        CompressDialog(
            node = node,
            browseFileSystem = browseFileSystem,
            onDone = {
                pendingCompress = null
                reloadAll()
            },
            onDismiss = { pendingCompress = null }
        )
    }

    pendingExtract?.let { node ->
        ExtractToDialog(
            node = node,
            browseFileSystem = browseFileSystem,
            onDone = {
                pendingExtract = null
                reloadAll()
            },
            onDismiss = { pendingExtract = null }
        )
    }

    archiveBrowse?.let { node ->
        ArchiveBrowserDialog(
            node = node,
            browseFileSystem = browseFileSystem,
            onExtract = {
                archiveBrowse = null
                pendingExtract = node
            },
            onDismiss = { archiveBrowse = null }
        )
    }

    pendingOpen?.let { node ->
        FileViewerSheet(
            path = node.path,
            name = node.entry.name,
            browseFileSystem = browseFileSystem,
            onDismiss = { pendingOpen = null },
            onSaved = {
                reloadDir(parentOf(node.path))
            }
        )
    }

    pendingImageView?.let { node ->
        ImageViewerSheet(
            name = node.entry.name,
            subtitle = node.path,
            onDismiss = { pendingImageView = null },
            decode = {
                val fs = browseFileSystem
                if (fs == null) {
                    0L to null
                } else {
                    val file = fs.hostFile(node.path)
                    file.length() to decodeImageFile(file, IMAGE_MAX_DIM)
                }
            }
        )
    }

    briefMessage?.let { msg ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = PanelAlt,
                contentColor = Accent,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 新建对象类型，决定确认后调创建文件还是创建文件夹。 */
private enum class TreeCreateKind { FILE, FOLDER }

/** 新建目标：在哪个目录（[parent]）下新建，以及新建文件还是文件夹（[kind]）。 */
private data class TreeCreateTarget(val parent: String, val kind: TreeCreateKind)

/** 文件树的一个可见节点（已按展开状态扁平化，携带 [depth] 供 UI 缩进）。 */
/**
 * 取父目录。注意 "/a".substringBeforeLast('/') 返回的是 **"" 而不是 "/"**，
 * 顶层目录必须归一化成 [ROOT_PATH]，否则查 dirCache[""] 恒为 null。
 * 粘贴、删除、重命名、编辑保存都要用它，算错会让顶层条目永远对不上。
 */
private fun parentOf(path: String): String =
    path.substringBeforeLast('/').ifEmpty { ROOT_PATH }

/**
 * 粘贴完成后**需要重拉缓存的目录集合**（[reloadAfterPaste] 的纯逻辑，抽出来便于单测）。
 *
 * 复制：源目录内容没变，只需重拉目标。
 * 剪切：源目录少了一条，**源与目标都要重拉**，否则 UI 继续按旧缓存渲染那个已不存在的条目。
 *
 * 同级移动时两者相同 → 用 Set 去重，避免发两次 reloadDir。
 */
internal fun dirsToReloadAfterPaste(clip: BrowseClipboard, dstParent: String): Set<String> {
    val dirs = linkedSetOf(dstParent)
    if (clip.isCut) dirs += parentOf(clip.sourcePath)
    return dirs
}

/**
 * 重命名目录后，把展开路径里整棵子树的路径前缀改写掉。
 *
 * 必须按**前缀**改而不是精确匹配：展开过 /a/b 时，集合里同时有
 * /a/b、/a/b/c、/a/b/c/d 三条，只改第一条的话子目录照样对不上。
 * 用 `startsWith("$old/")` 而不是 `startsWith(old)`，否则重命名 /a/b
 * 会把同级的 /a/bc 一起改掉。
 */
internal fun remapExpandedPaths(paths: Set<String>, old: String, new: String): Set<String> {
    if (old == new) return paths
    val remapped = HashSet<String>(paths.size)
    for (p in paths) {
        remapped += when {
            p == old -> new
            p.startsWith("$old/") -> new + p.removePrefix(old)
            else -> p
        }
    }
    return remapped
}

/**
 * 剪掉展开路径里已经不存在的目录：抽屉里删目录、或外部（AI 工具 / 终端）改动后
 * 手动刷新，都会留下永久残留——旧路径既不会自己消失，刷新时还会被
 * `loadDir` 白白尝试加载一次。
 *
 * ## 为什么按「父目录是否已列出来」逐条判定，而不是沿树走一遍
 * 目录是**分层异步加载**的（loadDir 会先把父目录置 Loading，展开更深层级时也一样）。
 * 若在父目录还是 Loading 时就去剪它的子目录，会把「还没加载完」误判成
 * 「已删除」而误剪。所以每条路径只看**自己父目录的列表**：
 * 父目录还没 Success 就一律保留，等它加载完的那一轮自然会重判。
 * 这样调用时机任意都安全，且多轮之后自动收敛到正确结果。
 *
 * 代价是父目录列不出来的（Error）路径会保守留下——宁可漏剪不可误剪。
 */
internal fun pruneMissingExpandedPaths(
    paths: Set<String>,
    dirCache: Map<String, FileBrowseState>
): Set<String> {
    if (paths.isEmpty()) return paths
    val drop = paths.filter { p ->
        if (p == ROOT_PATH) return@filter false
        // 注意 "/a".substringBeforeLast('/') 是 "" 而不是 "/"：顶层目录的父目录
        // 必须归一化成 ROOT_PATH，否则查 dirCache[""] 恒为 null，顶层永远剪不掉。
        val parent = p.substringBeforeLast('/').ifEmpty { ROOT_PATH }
        val st = dirCache[parent] as? FileBrowseState.Success ?: return@filter false
        st.entries.none { e ->
            val cp = e.overridePath
                ?: if (parent == ROOT_PATH) "/${e.name}" else "$parent/${e.name}"
            cp == p
        }
    }
    // 子节点不可能比父节点活得久：/a 被确认删了，/a/b、/a/b/c 必然也没了。
    // 不补这一刀的话，递归删除父目录后它的整条后代链会全部残留——因为那些后代的
    // 父目录已经不在 dirCache 里，逐条判定查不到，只能靠这条逻辑推出来。
    if (drop.isEmpty()) return paths
    val gone = drop.toSet()
    return paths.filter { p -> p !in gone && gone.none { p.startsWith("$it/") } }.toSet()
}

internal data class FileTreeNode(
    val entry: FileEntry,
    /** 容器绝对路径；[isRoot] 时为容器根 `/`。 */
    val path: String,
    /** 缩进层级：根为 0，其直接子项为 1，依此类推。 */
    val depth: Int,
    /** 容器根节点：不可重命名/删除，只能在其下新建。 */
    val isRoot: Boolean,
    /** 目录是否已展开；非目录恒为 false。 */
    val isExpanded: Boolean,
    /** 展开该目录时读取子项失败（如权限不足）。 */
    val hasError: Boolean = false
)

/** 容器根节点显示名。 */
private const val ROOT_LABEL = "容器根 /"

/** 容器根路径。 */
private const val ROOT_PATH = "/"

/** 根节点合成条目。 */
private val ROOT_ENTRY = FileEntry(
    name = ROOT_LABEL,
    isDirectory = true,
    size = 0,
    lastModified = 0
)

/** 文件树一行除缩进与文本外的固定宽度开销（start 8 + 箭头 16 + 4 + 图标 20 + 8 + end 12）。 */
private val FILE_TREE_ROW_OVERHEAD = 68.dp

/** 每级缩进宽度。 */
private val ROW_INDENT = 12.dp

private fun nodeLabel(node: FileTreeNode): String =
    if (node.isRoot) ROOT_LABEL else node.entry.name

/** 粗估名称显示宽度：CJK 记 2 个单位，其余 1 个单位。 */
private fun estimateLabelWidth(label: String): Int =
    label.fold(0) { acc, ch -> acc + (if (ch.code > 0x2E80) 2 else 1) }

/** 文件行副标题：目录只显修改时间，文件显「修改时间 · 大小」。 */
private fun fileRowSubtitle(entry: FileEntry): String? {
    val time = if (entry.lastModified > 0) formatModifiedTime(entry.lastModified) else null
    if (entry.isDirectory) return time
    val size = formatBytes(entry.size)
    return if (time != null) "$time  · $size" else size
}

private fun formatModifiedTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.1f GB", mb / 1024.0)
}

/** 剪切板状态条：显示已复制/已剪切的条目，可取消清空。 */
@Composable
private fun ClipboardBar(
    clipboard: BrowseClipboard,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Panel,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${if (clipboard.isCut) "✂ 剪切" else "📋 复制"}：${clipboard.sourceName}",
                color = Accent,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "取消", tint = TextSecondary, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = TextSecondary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** 面板顶部的刷新按钮（无剪切板条时单独显示，避免与剪切板叉号重叠）。 */
@Composable
private fun RefreshButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = "刷新",
            modifier = Modifier.size(16.dp),
            tint = TextSecondary
        )
    }
}

/** 覆盖确认前的待粘贴任务。 */
private data class PasteJob(val clip: BrowseClipboard, val target: String)

/** 覆盖确认弹窗：粘贴目标已存在时询问是否覆盖。 */
@Composable
private fun OverwriteDialog(
    clipboardName: String,
    targetName: String,
    isCut: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目标已存在") },
        text = {
            Text(
                if (isCut) "剪切的文件「$clipboardName」将移动到已有文件「$targetName」，是否覆盖？"
                else "复制的文件「$clipboardName」将覆盖已有文件「$targetName」，是否继续？"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("覆盖", color = Color(0xFFFF6B6B)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/** 文件树节点长按弹出的功能菜单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileTreeActionSheet(
    node: FileTreeNode,
    clipboard: BrowseClipboard?,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onExtract: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onCompress: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPasteHere: () -> Unit,
    /** 「加入输入栏」；为 null 时不显示这一项。 */
    onAddToInput: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = nodeLabel(node),
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            )
            if (clipboard != null && node.entry.isDirectory) {
                SheetActionRow(
                    icon = Icons.Default.ContentPaste,
                    label = "粘贴到此处",
                    tint = Accent,
                    onClick = onPasteHere
                )
            }
            if (node.entry.isDirectory) {
                SheetActionRow(
                    icon = Icons.Default.NoteAdd,
                    label = "新建文件",
                    tint = TextPrimary,
                    onClick = onNewFile
                )
                SheetActionRow(
                    icon = Icons.Default.CreateNewFolder,
                    label = "新建文件夹",
                    tint = TextPrimary,
                    onClick = onNewFolder
                )
            }
            if (isArchiveFileName(node.entry.name) && !node.entry.isDirectory) {
                SheetActionRow(
                    icon = Icons.Filled.FileDownload,
                    label = "解压到",
                    tint = Accent,
                    onClick = onExtract
                )
            }
            // 浏览器可渲染的文件（html/svg/xml/txt）：提供渲染预览入口。
            // 单击仍是编辑器，这条只在长按菜单里，两条路径互不干扰。
            if (!node.entry.isDirectory && isBrowserPreviewName(node.entry.name)) {
                SheetActionRow(
                    icon = Icons.Default.Public,
                    label = "用浏览器打开",
                    tint = Accent,
                    onClick = onOpenInBrowser
                )
            }
            // 加入输入栏：把该文件 / 目录投给聊天输入区当附件（目录只投一个条目，不递归展开）
            if (onAddToInput != null) {
                SheetActionRow(
                    icon = Icons.Default.Input,
                    label = "加入输入栏",
                    tint = Accent,
                    onClick = onAddToInput
                )
            }
            if (!node.isRoot) {
                SheetActionRow(
                    icon = Icons.Default.ContentCopy,
                    label = "复制",
                    tint = TextPrimary,
                    onClick = onCopy
                )
                SheetActionRow(
                    icon = Icons.Default.ContentCut,
                    label = "剪切",
                    tint = TextPrimary,
                    onClick = onCut
                )
                if (!isArchiveFileName(node.entry.name)) {
                    SheetActionRow(
                        icon = Icons.Default.Archive,
                        label = "压缩",
                        tint = TextPrimary,
                        onClick = onCompress
                    )
                }
                SheetActionRow(
                    icon = Icons.Default.Edit,
                    label = "重命名",
                    tint = TextPrimary,
                    onClick = onRename
                )
            }
            SheetActionRow(
                icon = Icons.Default.Info,
                label = "属性",
                tint = TextPrimary,
                onClick = onProperties
            )
            if (!node.isRoot) {
                SheetActionRow(
                    icon = Icons.Default.Close,
                    label = "删除",
                    tint = Color(0xFFFF6B6B),
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun SheetActionRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = tint, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** 新建 / 重命名共用的名称输入弹窗：名称非法或与原名相同时禁用确认。 */
@Composable
private fun FileNameInputDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = isValidFileEntryName(name) && name.trim() != initialName,
                colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = ButtonFg)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/** 文件夹递归统计结果：总字节、文件数、文件夹数（不含统计起点自身）。 */
private data class BrowseStats(val bytes: Long, val files: Long, val folders: Long)

/** 递归扫描目录统计总大小与文件/文件夹数（容器路径）。 */
private fun computeBrowseStats(fs: BrowseFileSystem, root: String): BrowseStats {
    val bytes = AtomicLong(0)
    var files = 0L
    var folders = 0L
    val stack = ArrayDeque<String>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        val entries = runCatching { fs.listFiles(dir) }.getOrDefault(emptyList())
        for (e in entries) {
            if (e.isDirectory) {
                folders++
                stack.addLast(if (dir == ROOT_PATH) "/${e.name}" else "$dir/${e.name}")
            } else {
                files++
                bytes.addAndGet(e.size)
            }
        }
    }
    return BrowseStats(bytes.get(), files, folders)
}

/** 大小人类可读格式：如 1689202905 → "1.57G"；<1KB 显示 "xxB"。 */
private fun formatHumanSize(bytes: Long): String {
    val b = bytes.toDouble()
    return when {
        bytes >= 1L.shl(30) -> String.format(Locale.US, "%.2fG", b / (1L.shl(30)))
        bytes >= 1L.shl(20) -> String.format(Locale.US, "%.2fM", b / (1L.shl(20)))
        bytes >= 1L.shl(10) -> String.format(Locale.US, "%.2fK", b / (1L.shl(10)))
        else -> String.format(Locale.US, "%dB", bytes)
    }
}

/** 长按「属性」弹出的文件/文件夹详情窗口。 */
@Composable
private fun FilePropertiesDialog(
    node: FileTreeNode,
    browseFileSystem: BrowseFileSystem?,
    onDismiss: () -> Unit
) {
    var stats by remember(node.path) { mutableStateOf<BrowseStats?>(null) }
    LaunchedEffect(node.path) {
        val fs = browseFileSystem ?: return@LaunchedEffect
        if (node.entry.isDirectory) {
            stats = withContext(Dispatchers.IO) { computeBrowseStats(fs, node.path) }
        }
    }
    val isDirectory = node.entry.isDirectory
    val sizeBytes = if (isDirectory) (stats?.bytes ?: 0L) else node.entry.size
    val sizeValue = if (isDirectory && stats == null) "计算中…"
    else formatHumanSize(sizeBytes) + "（$sizeBytes 字节）"
    val modified = remember(node.entry.lastModified) {
        if (node.entry.lastModified <= 0) "-" else
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(node.entry.lastModified))
    }
    val s = stats
    // 关键：SelectionContainer 包「多个 Text」会走 MultiSelectionLayout，拖动选择手柄时命中
    // Compose foundation 的 "unexpectedly miss-crossed selection" 崩溃（属性弹窗选中文字即闪退）。
    // 改为**单个 Text**（内容用 \n 拼接）：只含一个 selectable，走 SingleSelectionLayout，既保留
    // 长按选择/复制，又绕开该崩溃路径（该 bug 在 foundation 1.12.0 才修，见 notes）。
    val propText = buildString {
        append("名称："); append(nodeLabel(node)); append('\n')
        append("路径："); append(node.path); append('\n')
        append("类型："); append(if (isDirectory) "文件夹" else "文件"); append('\n')
        append("大小："); append(sizeValue); append('\n')
        append("修改时间："); append(modified)
        if (isDirectory && s != null) {
            append('\n'); append("文件数："); append(s.files)
            append('\n'); append("文件夹数："); append(s.folders)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("属性") },
        text = {
            SelectionContainer {
                Text(
                    text = propText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = TextSecondary) }
        }
    )
}

/** 压缩设置弹窗：可改压缩包名称、选压缩级别；确认后异步执行。 */
@Composable
private fun CompressDialog(
    node: FileTreeNode,
    browseFileSystem: BrowseFileSystem?,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val source = browseFileSystem?.hostFile(node.path)
    val sourceName = node.entry.name
    val defaultName = sourceName.substringBeforeLast('.', "").ifBlank { "archive" } + ".zip"
    var name by remember(sourceName) { mutableStateOf(defaultName) }
    var level by remember { mutableStateOf(Deflater.DEFAULT_COMPRESSION) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val levelOptions = listOf(
        Triple(Deflater.BEST_SPEED, "最快", "压缩体积大，速度快"),
        Triple(Deflater.DEFAULT_COMPRESSION, "标准", "体积与速度均衡"),
        Triple(Deflater.BEST_COMPRESSION, "最大", "压缩体积小，速度慢")
    )
    AlertDialog(
        onDismissRequest = { if (!busy && result == null) onDismiss() },
        title = { Text("压缩") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("压缩包名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "压缩级别",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                levelOptions.forEach { (lv, llabel, hint) ->
                    val selected = level == lv
                    Surface(
                        color = if (selected) PanelAlt else Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = !busy) { level = lv }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Spacer(Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(llabel, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
                result?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("✓")) Accent else Color(0xFFFF6B6B),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            when {
                result != null -> TextButton(onClick = onDone) { Text("完成", color = TextSecondary) }
                busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp))
                else -> TextButton(onClick = {
                    val targetName = if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
                    if (targetName.isBlank() || targetName == ".zip" || targetName.contains('/') || targetName.contains('\\')) {
                        result = "✗ 名称不合法"
                        return@TextButton
                    }
                    if (source == null) {
                        result = "✗ 无法定位源文件"
                        return@TextButton
                    }
                    val destDir = source.parentFile ?: File(source.absolutePath).parentFile
                        ?: return@TextButton
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                val dest = File(destDir, uniqueZipName(destDir, targetName))
                                ArchiveCompressor.compress(source, dest, level)
                                targetName
                            }
                        }
                        outcome.fold(
                            onSuccess = { n -> result = "✓ 已压缩为 $n" },
                            onFailure = { e -> result = "✗ " + (e.message ?: e.javaClass.simpleName) }
                        )
                        busy = false
                    }
                }) { Text("压缩", color = Accent) }
            }
        },
        dismissButton = {
            if (!busy && result == null) {
                TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
            }
        }
    )
}

/** 解压目标选择弹窗：输入当前目录下的子文件夹名（留空解压到当前目录）。 */
@Composable
private fun ExtractToDialog(
    node: FileTreeNode,
    browseFileSystem: BrowseFileSystem?,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val archiveHost = browseFileSystem?.hostFile(node.path)
    var target by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy && result == null) onDismiss() },
        title = { Text("解压到") },
        text = {
            Column {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("子文件夹名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "留空直接解压到当前目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                result?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("✓")) Accent else Color(0xFFFF6B6B),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            when {
                result != null -> TextButton(onClick = onDone) { Text("完成", color = TextSecondary) }
                busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp))
                else -> TextButton(onClick = {
                    if (archiveHost == null) {
                        result = "✗ 无法定位压缩包"
                        return@TextButton
                    }
                    val sub = sanitizeExtractSubpath(target)
                    if (sub == null) {
                        result = "✗ 子文件夹名不合法"
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                val parent = archiveHost.parentFile ?: File(archiveHost.absolutePath).parentFile
                                val destDir: File? = when {
                                    parent == null -> null
                                    sub.isEmpty() -> parent
                                    else -> File(parent, sub)
                                }
                                destDir?.let { d ->
                                    val count = ArchiveExtractor.extract(archiveHost, node.entry.name, d)
                                    count to d.absolutePath
                                }
                            }
                        }
                        outcome.fold(
                            onSuccess = { pair ->
                                if (pair != null) result = "✓ 已解压 ${pair.first} 个条目到 ${pair.second}"
                            },
                            onFailure = { e -> result = "✗ " + (e.message ?: e.javaClass.simpleName) }
                        )
                        busy = false
                    }
                }) { Text("解压", color = Accent) }
            }
        },
        dismissButton = {
            if (!busy && result == null) {
                TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
            }
        }
    )
}

/** 归档浏览器：把压缩包内条目按文件树展示；目录可展开，头部可「解压到」。 */
@Composable
private fun ArchiveBrowserDialog(
    node: FileTreeNode,
    browseFileSystem: BrowseFileSystem?,
    onExtract: () -> Unit,
    onDismiss: () -> Unit
) {
    val archiveHost = browseFileSystem?.hostFile(node.path)
    var entries by remember(node.path) { mutableStateOf<List<ArchiveEntryInfo>?>(null) }
    var scanError by remember(node.path) { mutableStateOf<String?>(null) }
    var expanded by remember(node.path) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(node.path) {
        val host = archiveHost ?: return@LaunchedEffect
        val list = withContext(Dispatchers.IO) { ArchiveScanner.listEntries(host, node.entry.name) }
        if (list.isEmpty()) {
            scanError = "无法读取压缩包内容"
            entries = emptyList()
        } else {
            entries = list
        }
    }
    val tree = remember(entries) { buildArchiveTree(entries.orEmpty()) }
    val rows = remember(tree, expanded) { flattenArchiveRows(tree, expanded) }
    if (scanError != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("压缩包") },
            text = { Text(scanError.orEmpty()) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("完成", color = TextSecondary) }
            }
        )
        return
    }
    if (entries == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("压缩包") },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("关闭", color = TextSecondary) }
            }
        )
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Archive,
                        contentDescription = null,
                        tint = Accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = node.entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onExtract) {
                        Text("解压到", color = Accent)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(
                    color = Border,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                if (rows.isEmpty()) {
                    Text(
                        text = "压缩包为空",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(rows, key = { it.fullPath }) { row ->
                            ArchiveRow(
                                node = row,
                                isExpanded = row.isDirectory && row.fullPath in expanded,
                                onClick = if (row.isDirectory) {
                                    {
                                        val dirPath = row.fullPath
                                        expanded = if (dirPath in expanded) expanded - dirPath else expanded + dirPath
                                    }
                                } else {
                                    {}
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 归档树单个节点：供 [ArchiveBrowserDialog] 文件树视图复用缩进逻辑。 */
private data class ArchiveUiNode(
    val name: String,
    val fullPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val children: List<ArchiveUiNode> = emptyList(),
    val depth: Int = 0
)

/** 把压缩包扁平条目构造成目录树；目录在前、名称为序。 */
private fun buildArchiveTree(entries: List<ArchiveEntryInfo>): List<ArchiveUiNode> {
    val dirs = LinkedHashMap<String, MutableList<ArchiveEntryInfo>>()
    for (e in entries) {
        val path = e.path.trimEnd('/')
        val parent = if (path.contains('/')) path.substringBeforeLast('/') else ""
        dirs.getOrPut(parent) { mutableListOf() }.add(e)
    }
    fun build(parent: String, depth: Int): List<ArchiveUiNode> {
        val kids = dirs[parent].orEmpty().sortedWith(compareBy<ArchiveEntryInfo> { !it.isDirectory }.thenBy { it.path })
        return kids.map { e ->
            val path = e.path.trimEnd('/')
            ArchiveUiNode(
                name = path.substringAfterLast('/'),
                fullPath = path,
                isDirectory = e.isDirectory,
                sizeBytes = if (e.isDirectory) 0 else e.size,
                children = if (e.isDirectory) build(path, depth + 1) else emptyList(),
                depth = depth
            )
        }
    }
    return build("", 0)
}

/** 按折叠状态把树展平成可见行（深度优先）。 */
private fun flattenArchiveRows(
    nodes: List<ArchiveUiNode>,
    expanded: Set<String>
): List<ArchiveUiNode> {
    val out = ArrayList<ArchiveUiNode>()
    fun walk(list: List<ArchiveUiNode>) {
        for (n in list) {
            out.add(n)
            if (n.isDirectory && n.fullPath in expanded) walk(n.children)
        }
    }
    walk(nodes)
    return out
}

/** 归档浏览器的一行：缩进 + 目录箭头 + 名称（文件带大小）。 */
@Composable
private fun ArchiveRow(
    node: ArchiveUiNode,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .padding(start = 12.dp + ROW_INDENT * node.depth, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDirectory) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Spacer(Modifier.size(16.dp))
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = if (node.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (node.isDirectory) Accent else TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!node.isDirectory) {
            Text(
                text = formatHumanSize(node.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

/** 目标目录已存在同名 zip 时自动追加 (n) 序号，避免覆盖。 */
private fun uniqueZipName(dir: File, want: String): String {
    val base = want.substringBeforeLast('.')
    val ext = want.substringAfterLast('.', "")
    var candidate = want
    var n = 1
    while (File(dir, candidate).exists()) {
        candidate = "$base($n).$ext"
        n++
    }
    return candidate
}

/** 清洗解压子目录输入：拒绝绝对路径、`..`、空字节；尾随斜杠去除；空串表示当前目录。 */
private fun sanitizeExtractSubpath(raw: String): String? {
    val cleaned = raw.trim().replace('\\', '/').trim('/')
    if (cleaned.isEmpty()) return ""
    if (cleaned.contains('\u0000')) return null
    if (cleaned.startsWith("/")) return null
    val segments = cleaned.split("/")
    if (segments.any { it.isEmpty() || it == ".." || it == "." }) return null
    return cleaned
}

/** 文件树的一行：按 [FileTreeNode.depth] 缩进，目录带展开箭头。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: FileTreeNode,
    rowWidth: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isDotfile = !node.isRoot && node.entry.name.startsWith(".")
    val decorationColor: Color? = when {
        node.hasError -> Color(0xFFFF6B6B)
        isDotfile -> TextSecondary.copy(alpha = 0.55f)
        else -> null
    }
    Row(
        modifier = Modifier
            .width(rowWidth)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 6.dp)
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(ROW_INDENT * node.depth))
        if (node.entry.isDirectory) {
            Icon(
                imageVector = if (node.isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = TextSecondary
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.width(4.dp))
        IdentIcon(node = node, decorationColor = decorationColor)
        Spacer(Modifier.width(8.dp))
        Text(
            text = nodeLabel(node),
            style = MaterialTheme.typography.bodyMedium,
            color = decorationColor ?: TextPrimary,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** 文件树行的类型图标：目录用文件夹，文件按扩展名。 */
@Composable
private fun IdentIcon(node: FileTreeNode, decorationColor: Color?) {
    val isDir = node.entry.isDirectory
    Icon(
        imageVector = if (isDir) Icons.Default.Folder else fileTypeIconFor(node.entry.name),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = decorationColor
            ?: if (isDir) Accent else iconTintFor(node.entry.name)
    )
}

/** 常见代码扩展名（高亮 Accent）。 */
private val CODE_EXTS = setOf(
    "kt", "kts", "java", "py", "js", "ts", "go", "rs", "c", "cpp", "cc", "h", "hpp",
    "php", "rb", "sh", "bash", "sql", "html", "htm", "css", "scss", "less", "xml",
    "yaml", "yml", "json", "toml", "md", "gradle", "conf", "ini", "properties",
    "env", "bat", "ps1", "lua", "pl", "r", "dart", "swift", "m", "vue", "svelte"
)

/** 常见图片扩展名（用于文件树图标，含 SVG）。 */
private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico")

/**
 * 可交给 [android.graphics.BitmapFactory] 解码的图片扩展名——**不含 SVG**。
 * SVG 是矢量格式，BitmapFactory 解不了；若命中会走进解码失败分支给出提示，
 * 因此这里直接排除，让它落到文本查看器（SVG 本质是 XML 文本，可读源码）。
 */
private val VIEWABLE_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

/** 是否为可解码显示的位图图片。 */
private fun isViewableImageName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in VIEWABLE_IMAGE_EXTS

/**
 * 该文件是否适合用内置浏览器预览。
 *
 * 清单在 [com.wy.openx.browser.BrowserPreviewFormats]（与 BrowserManager 共用）：
 * HTML/XML/文本 + 图片 + 音频 + 视频——即 WebView 真能渲染的那些。
 * 音视频由 BrowserManager 自动包一层 `<audio>`/`<video>` 播放页，
 * 所以长按 mp3/mp4 也能直接预览。
 *
 * 只在长按菜单里给入口，单击仍进编辑器/查看器（保留「打开看/改源码」这条常用路径）。
 */
private fun isBrowserPreviewName(name: String): Boolean =
    com.wy.openx.browser.BrowserPreviewFormats.isPreviewable(name)

/** 图片查看器解码时的最长边上限（像素）：降采样以控制内存（2560² × 4B ≈ 26MB）。 */
private const val IMAGE_MAX_DIM = 2560

/** 按扩展名选择文件图标。 */
private fun fileTypeIconFor(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXTS -> Icons.Default.Image
        in CODE_EXTS -> Icons.Default.Code
        else -> if (isArchiveFileName(name)) Icons.Default.Archive else Icons.Default.Description
    }
}

/** 文件图标颜色：代码高亮，压缩包橙色，其余弱化。 */
private fun iconTintFor(name: String): Color {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in CODE_EXTS -> Accent
        isArchiveFileName(name) -> Warning
        else -> TextSecondary
    }
}

/**
 * 图片查看器：全屏浏览图片，支持双指缩放 / 拖动平移 / 双击放大 / 复位。
 *
 * 与具体来源无关——[decode] 由调用方提供（文件树走宿主 [File] + [decodeImageFile]；
 * 聊天图片走 content URI + `decodeSampledBitmap`）。解码在 IO 线程执行。
 *
 * 零新依赖——直接用 [android.graphics.BitmapFactory] 解码，并按 EXIF 方向摆正。
 *
 * @param decode 返回 `(字节数, 位图)`；位图为 null 表示解码失败（会显示错误提示）。
 */
@Composable
internal fun ImageViewerSheet(
    name: String,
    subtitle: String,
    onDismiss: () -> Unit,
    decode: suspend () -> Pair<Long, android.graphics.Bitmap?>
) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var totalBytes by remember { mutableStateOf(0L) }

    // 手势状态：缩放倍数 + 平移偏移（屏幕像素）。
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    fun resetTransform() {
        scale = 1f; offsetX = 0f; offsetY = 0f
    }

    // 是否处于初始视图（未缩放/未平移），用于「复位」按钮的可用态。
    val atDefault = scale == 1f && offsetX == 0f && offsetY == 0f

    LaunchedEffect(subtitle) {
        bitmap = null; error = null; loading = true; totalBytes = 0L; resetTransform()
        // 解码在 IO 线程完成，结果回到主线程统一写状态，避免跨线程写 Compose state。
        val result = withContext(Dispatchers.IO) { runCatching { decode() } }
        result.onSuccess { (bytes, bmp) ->
            totalBytes = bytes
            if (bmp == null) error = "无法解码该图片（格式不受支持或文件已损坏）" else bitmap = bmp
            loading = false
        }.onFailure {
            FileLogger.w("ImageViewer", "打开图片失败($subtitle): ${it.message}")
            error = it.message ?: "读取失败"
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = BgPrimary
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部栏：文件名 + 路径 + 尺寸信息 + 复位/关闭
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            name,
                            color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val info = buildString {
                            append(subtitle)
                            bitmap?.let { append("  ·  ${it.width}×${it.height}") }
                            if (totalBytes > 0) append("  ·  ${formatHumanSize(totalBytes)}")
                            if (scale > 1.01f) append("  ·  ${(scale * 100).toInt()}%")
                        }
                        Text(
                            info, color = TextSecondary, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { resetTransform() }, enabled = !atDefault) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "复位缩放",
                            tint = if (atDefault) TextSecondary.copy(alpha = 0.4f) else TextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = TextSecondary)
                    }
                }

                HorizontalDivider(color = Border.copy(alpha = 0.5f))

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { containerSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    when {
                        loading -> CircularProgressIndicator(color = Accent)
                        error != null -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(error!!, color = Color(0xFFFF6B6B), fontSize = 14.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "仅支持 PNG / JPG / GIF / WebP / BMP / HEIC 等位图格式。",
                                color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center
                            )
                        }
                        bmp != null -> Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                // 手势检测必须在 graphicsLayer **之前**（外层）：这样 pan/zoom
                                // 拿到的是未经缩放变换的屏幕坐标，钳制逻辑才成立。
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, 8f)
                                        // 放大后允许平移；缩回 1x 时归零偏移，避免图片跑出屏幕。
                                        if (newScale <= 1f) {
                                            scale = 1f; offsetX = 0f; offsetY = 0f
                                        } else {
                                            val maxX = (containerSize.width * (newScale - 1f)) / 2f
                                            val maxY = (containerSize.height * (newScale - 1f)) / 2f
                                            scale = newScale
                                            // graphicsLayer 的 translation 在父（屏幕）坐标系、不随 scale 缩放，
                                            // 故 pan 直接用屏幕像素增量，拖动即与手指 1:1；边界钳制同理在屏幕空间。
                                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                        }
                                    }
                                }
                                .pointerInput(Unit) {
                                    // 双击：在「适应屏幕」与「2x 放大」之间切换。
                                    detectTapGestures(
                                        onDoubleTap = {
                                            // 直接读 scale 状态（delegated property 读的是实时值），
                                            // 不能用组合期算出的 atDefault——会被闭包捕获成旧值。
                                            if (scale == 1f && offsetX == 0f && offsetY == 0f) {
                                                scale = 2f; offsetX = 0f; offsetY = 0f
                                            } else {
                                                resetTransform()
                                            }
                                        }
                                    )
                                }
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * 从宿主文件解码图片为 [android.graphics.Bitmap]，最长边不超过 [maxDim]（IO 线程调用）。
 *
 * 两遍解码：先 [android.graphics.BitmapFactory.Options.inJustDecodeBounds] 读尺寸算 `inSampleSize`，
 * 再按采样率解码，避免大图整解 OOM；最后按 EXIF 方向旋转摆正（手机拍摄图片常见）。
 * 解码失败返回 null（格式不受支持/损坏），不抛异常。
 */
private fun decodeImageFile(file: File, maxDim: Int): android.graphics.Bitmap? {
    if (!file.isFile) return null
    return try {
        // 1. 读边界
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2. 计算 inSampleSize（2 的幂，与 BitmapFactory 预期一致）
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDim) sampleSize *= 2

        // 3. 采样解码
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = file.inputStream().use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // 4. EXIF 摆正
        val rotation = file.inputStream().use { exifRotationDegrees(it) }
        if (rotation != 0f) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
            val rotated = android.graphics.Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true
            )
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }
    } catch (e: OutOfMemoryError) {
        FileLogger.w("ImageViewer", "解码图片 OOM: ${file.absolutePath}")
        null
    } catch (e: Exception) {
        FileLogger.w("ImageViewer", "解码图片失败: ${file.absolutePath} -> ${e.message}")
        null
    }
}