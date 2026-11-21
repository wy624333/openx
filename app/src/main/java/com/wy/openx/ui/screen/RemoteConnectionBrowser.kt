package com.wy.openx.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.remote.RemoteConnection
import com.wy.openx.remote.RemoteEngines
import com.wy.openx.remote.RemoteEntry
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun RemoteConnectionBrowser(config: RemoteConnection) {
    val manager = RemoteEngines.manager
    val scope = rememberCoroutineScope()
    var currentPath by remember(config.id) { mutableStateOf(initialRemotePath(config)) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var preview by remember { mutableStateOf<RemoteEntry?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var brief by remember { mutableStateOf<String?>(null) }
    var clipboard by remember { mutableStateOf<RemoteClipboard?>(null) }
    var actionEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var creating by remember { mutableStateOf<RemoteCreateTarget?>(null) }
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<RemoteEntry?>(null) }
    var properties by remember { mutableStateOf<RemoteEntry?>(null) }

    suspend fun reload() {
        loading = true
        runCatching { manager.list(config, currentPath) }
            .onSuccess { entries = it; error = null }
            .onFailure { error = it.message ?: "读取目录失败" }
        loading = false
    }

    LaunchedEffect(config.id) {
        if (config.remotePath.isBlank()) {
            runCatching { manager.browserStartPath(config) }
                .onSuccess { path -> currentPath = if (path.startsWith("/")) path else "/$path" }
        }
    }

    LaunchedEffect(config.id, currentPath) { reload() }

    LaunchedEffect(brief) {
        if (brief != null) {
            delay(2200)
            brief = null
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                currentPath.ifBlank { "/" },
                fontSize = 11.sp,
                color = Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (currentPath != "/") {
                Text(
                    "上一级",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 6.dp).clickable {
                        currentPath = remoteParent(currentPath)
                    }
                )
            }
            IconButton(onClick = { scope.launch { reload() } }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = TextSecondary, modifier = Modifier.size(15.dp))
            }
        }
        val clip = clipboard
        if (clip != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 2.dp)
            ) {
                Text(
                    "${if (clip.isCut) "✂ 剪切" else "📋 复制"}：${clip.sourceName}",
                    fontSize = 11.sp,
                    color = Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { clipboard = null }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "取消", tint = TextSecondary, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (brief != null) {
            Text(brief!!, fontSize = 11.sp, color = Accent, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        }
        when {
            error != null -> Text(error!!, fontSize = 12.sp, color = Color(0xFFFF6B6B), modifier = Modifier.padding(vertical = 8.dp))
            loading -> Text("读取中…", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(vertical = 8.dp))
            entries.isEmpty() -> Text("空目录", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(vertical = 8.dp))
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().height(BROWSER_HEIGHT)) {
                items(entries, key = { it.path }) { entry ->
                    RemoteFileTreeRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) currentPath = entry.path else preview = entry
                        },
                        onLongClick = { actionEntry = entry }
                    )
                }
            }
        }
    }

    preview?.let { entry ->
        RemoteFilePreviewDialog(
            entry = entry,
            loader = { manager.readText(config, entry.path) },
            onDismiss = { preview = null },
        )
    }

    actionEntry?.let { entry ->
        RemoteActionSheet(
            entry = entry,
            clipboard = clipboard,
            onNewFile = {
                actionEntry = null
                creating = RemoteCreateTarget(if (entry.isDirectory) entry.path else currentPath, false)
            },
            onNewFolder = {
                actionEntry = null
                creating = RemoteCreateTarget(if (entry.isDirectory) entry.path else currentPath, true)
            },
            onCopy = {
                actionEntry = null
                clipboard = RemoteClipboard(entry.path, entry.name, entry.isDirectory, false)
            },
            onCut = {
                actionEntry = null
                clipboard = RemoteClipboard(entry.path, entry.name, entry.isDirectory, true)
            },
            onPasteHere = {
                actionEntry = null
                clipboard?.let { clip ->
                    val targetDir = if (entry.isDirectory) entry.path else currentPath
                    val target = joinRemotePath(targetDir, clip.sourceName)
                    scope.launch {
                        runCatching {
                            if (target == clip.sourcePath) error("源与目标相同")
                            if (manager.exists(config, target)) error("目标已存在：$target")
                            if (clip.isCut) manager.moveOnServer(config, clip.sourcePath, target)
                            else manager.copyOnServer(config, clip.sourcePath, target, clip.isDirectory)
                        }.onSuccess {
                            clipboard = null
                            brief = if (clip.isCut) "已移动" else "已复制"
                            reload()
                        }.onFailure { brief = it.message ?: "粘贴失败" }
                    }
                }
            },
            onRename = { actionEntry = null; renaming = entry },
            onDelete = { actionEntry = null; deleting = entry },
            onProperties = { actionEntry = null; properties = entry },
            onDismiss = { actionEntry = null }
        )
    }

    creating?.let { target ->
        RemoteNameDialog(
            title = if (target.isDirectory) "新建文件夹" else "新建文件",
            initialName = "",
            onConfirm = { name ->
                scope.launch {
                    val path = joinRemotePath(target.parent, name)
                    runCatching {
                        if (target.isDirectory) manager.mkdir(config, path) else manager.writeText(config, path, "")
                    }.onFailure { brief = it.message ?: "创建失败" }
                    reload()
                }
                creating = null
            },
            onDismiss = { creating = null }
        )
    }

    renaming?.let { entry ->
        RemoteNameDialog(
            title = "重命名",
            initialName = entry.name,
            onConfirm = { name ->
                scope.launch {
                    val target = joinRemotePath(remoteParent(entry.path), name)
                    runCatching { manager.rename(config, entry.path, target) }
                        .onFailure { brief = it.message ?: "重命名失败" }
                    reload()
                }
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除") },
            text = {
                Text(
                    if (entry.isDirectory) "删除文件夹「${entry.name}」及其全部内容？"
                    else "删除文件「${entry.name}」？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { manager.deleteRecursively(config, entry.path, entry.isDirectory) }
                            .onFailure { brief = it.message ?: "删除失败" }
                        reload()
                    }
                    deleting = null
                }) { Text("删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消", color = TextSecondary) } },
            containerColor = Panel
        )
    }

    properties?.let { entry ->
        AlertDialog(
            onDismissRequest = { properties = null },
            title = { Text("属性") },
            text = {
                val propText = buildString {
                    append("名称：").append(entry.name).append('\n')
                    append("路径：").append(entry.path).append('\n')
                    append("类型：").append(if (entry.isDirectory) "目录" else "文件").append('\n')
                    if (!entry.isDirectory) append("大小：").append(formatRemoteSize(entry.size)).append('\n')
                    if (entry.permissions.isNotBlank()) append("权限：").append(entry.permissions)
                }.trimEnd('\n')
                SelectionContainer {
                    Text(text = propText, fontSize = 12.sp, color = TextPrimary)
                }
            },
            confirmButton = { TextButton(onClick = { properties = null }) { Text("关闭") } },
            containerColor = Panel
        )
    }
}

private fun initialRemotePath(config: RemoteConnection): String =
    config.remotePath.ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }

private fun remoteParent(path: String): String =
    path.substringBeforeLast('/', "").ifBlank { "/" }

private fun joinRemotePath(parent: String, name: String): String =
    if (parent == "/") "/$name" else "$parent/$name"

private data class RemoteClipboard(
    val sourcePath: String,
    val sourceName: String,
    val isDirectory: Boolean,
    val isCut: Boolean
)

private data class RemoteCreateTarget(val parent: String, val isDirectory: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteFileTreeRow(entry: RemoteEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    val isDotfile = entry.name.startsWith(".")
    val tint = if (isDotfile) TextSecondary.copy(alpha = 0.55f) else if (entry.isDirectory) Accent else TextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 6.dp)
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.isDirectory) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint
        )
        Spacer(Modifier.width(8.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDotfile) TextSecondary.copy(alpha = 0.55f) else TextPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteActionSheet(
    entry: RemoteEntry,
    clipboard: RemoteClipboard?,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPasteHere: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onProperties: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)
            )
            if (clipboard != null && entry.isDirectory) {
                RemoteSheetRow(Icons.Default.ContentPaste, "粘贴到此处", Accent, onPasteHere)
            }
            if (entry.isDirectory) {
                RemoteSheetRow(Icons.Default.NoteAdd, "新建文件", TextPrimary, onNewFile)
                RemoteSheetRow(Icons.Default.CreateNewFolder, "新建文件夹", TextPrimary, onNewFolder)
            }
            RemoteSheetRow(Icons.Default.ContentCopy, "复制", TextPrimary, onCopy)
            RemoteSheetRow(Icons.Default.ContentCut, "剪切", TextPrimary, onCut)
            RemoteSheetRow(Icons.Default.Edit, "重命名", TextPrimary, onRename)
            RemoteSheetRow(Icons.Default.Info, "属性", TextPrimary, onProperties)
            RemoteSheetRow(Icons.Default.Close, "删除", Color(0xFFFF6B6B), onDelete)
        }
    }
}

@Composable
private fun RemoteSheetRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = tint, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun RemoteNameDialog(title: String, initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("名称") }
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } },
        containerColor = Panel
    )
}

@Composable
private fun RemoteFilePreviewDialog(
    entry: RemoteEntry,
    loader: suspend () -> String,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.path) {
        runCatching { loader() }
            .onSuccess { content = it }
            .onFailure { error = it.message ?: "读取失败" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, fontSize = 14.sp, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            when {
                error != null -> Text(error!!, fontSize = 12.sp, color = Color(0xFFFF6B6B))
                content == null -> Text("读取中…", fontSize = 12.sp, color = TextSecondary)
                content.isNullOrEmpty() -> Text("空文件", fontSize = 12.sp, color = TextSecondary)
                else -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PREVIEW_HEIGHT)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(content!!, fontSize = 11.sp, color = TextPrimary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        containerColor = Panel,
    )
}

internal fun formatRemoteSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
}

private val BROWSER_HEIGHT = 280.dp
private val PREVIEW_HEIGHT = 320.dp
