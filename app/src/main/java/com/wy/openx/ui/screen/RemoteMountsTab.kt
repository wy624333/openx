package com.wy.openx.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.remote.RemoteConnection
import com.wy.openx.remote.RemoteEngines
import com.wy.openx.remote.RemoteMount
import com.wy.openx.remote.SyncState
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 「挂载目录」tab：把远端目录绑到本地工作区并同步。
 *
 * 排序用上移/下移按钮而不是拖拽：拖拽排序要自己写手势、占位动画与越界处理，
 * 而这里列表通常只有几条，两个按钮能达成同样效果且不易误触。
 */
@Composable
internal fun RemoteMountsTab(
    mounts: List<RemoteMount>,
    connections: List<RemoteConnection>,
    onEdit: (RemoteMount) -> Unit,
    onDelete: (RemoteMount) -> Unit,
    onToggle: (RemoteMount, Boolean) -> Unit,
    onReorder: (List<String>) -> Unit,
    onFeedback: (String) -> Unit,
) {
    if (mounts.isEmpty()) {
        RemoteEmptyState(
            icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp)) },
            title = "暂无挂载",
            hint = "添加一条挂载，把远端目录和本地工作区绑起来，改了本地文件自动传上去",
        )
        return
    }

    val ordered = mounts.sortedBy { it.sortOrder }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        ordered.forEachIndexed { index, mount ->
            val connection = connections.firstOrNull { it.id == mount.connectionId }
            RemoteMountCard(
                mount = mount,
                connection = connection,
                canMoveUp = index > 0,
                canMoveDown = index < ordered.size - 1,
                onMoveUp = {
                    val ids = ordered.map { it.id }.toMutableList()
                    ids.removeAt(index)
                    ids.add(index - 1, mount.id)
                    onReorder(ids)
                },
                onMoveDown = {
                    val ids = ordered.map { it.id }.toMutableList()
                    ids.removeAt(index)
                    ids.add(index + 1, mount.id)
                    onReorder(ids)
                },
                onEdit = { onEdit(mount) },
                onDelete = { onDelete(mount) },
                onToggle = { start -> onToggle(mount, start) },
                onFeedback = onFeedback,
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(BOTTOM_BAR_CLEARANCE))
    }
}

/**
 * 一条挂载的卡片：连接状态、路径、同步摘要、启停、强制上传/下载、清空索引。
 *
 * 同步引擎从 [RemoteEngines.manager] 取（同一挂载复用同一个引擎），
 * 状态直接订阅引擎的 StateFlow——卡片是同步状态的唯一展示处。
 */
@Composable
private fun RemoteMountCard(
    mount: RemoteMount,
    connection: RemoteConnection?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onFeedback: (String) -> Unit,
) {
    val manager = RemoteEngines.manager
    val scope = rememberCoroutineScope()

    if (connection == null) {
        // 连接被删掉了：显示成不可用，而不是让用户点进去才报错
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Panel,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(mount.remotePath, fontSize = 13.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("所属连接已删除，请删除这条挂载", fontSize = 11.sp, color = Color(0xFFFF6B6B))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFFF6B6B), modifier = Modifier.size(17.dp))
                }
            }
        }
        return
    }

    val engine = remember(mount.id, connection.id) { manager.syncEngineFor(mount, connection) }
    val state by engine.state.collectAsState()
    val outcome by engine.lastOutcome.collectAsState()
    val error by engine.lastError.collectAsState()

    val running = state == SyncState.CONNECTED || state == SyncState.SYNCING
    var busy by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(connection.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Spacer(Modifier.width(6.dp))
                        ProtocolBadge(connection.protocol.name)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (state) {
                                SyncState.CONNECTED -> if (mount.autoConnect) "已自动连接" else "已连接"
                                SyncState.CONNECTING -> "连接中…"
                                SyncState.SYNCING -> "同步中…"
                                SyncState.FAILED -> "已断开"
                                SyncState.IDLE -> "未启动"
                            },
                            fontSize = 10.sp,
                            color = when (state) {
                                SyncState.CONNECTED, SyncState.SYNCING -> Accent
                                SyncState.FAILED -> Color(0xFFFF6B6B)
                                else -> TextSecondary
                            }
                        )
                    }
                    Text(
                        mount.remotePath,
                        fontSize = 11.sp, color = TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        (if (mount.sourceOnServer) "[服务器为准] " else "") + "→ " + mount.localMountPath,
                        fontSize = 11.sp, color = TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp, contentDescription = "上移",
                        tint = if (canMoveUp) TextSecondary else TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown, contentDescription = "下移",
                        tint = if (canMoveDown) TextSecondary else TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Description, contentDescription = "编辑", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFFF6B6B).copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                }
            }

            // 操作行：启动/停止、强制上传、强制下载、清空索引
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RemoteMiniAction(
                    label = if (running) "停止" else "启动",
                    icon = if (running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    enabled = !busy,
                ) { onToggle(!running) }
                RemoteMiniAction("强制上传", Icons.Outlined.Refresh, enabled = running && !busy) {
                    busy = true
                    scope.launch {
                        runCatching { engine.uploadWorkspace(force = true) }
                            .onSuccess { onFeedback("上传完成：${it.summary}") }
                            .onFailure { onFeedback(it.message ?: "上传失败") }
                        busy = false
                    }
                }
                RemoteMiniAction("强制下载", Icons.Outlined.Refresh, enabled = running && !busy) {
                    busy = true
                    scope.launch {
                        runCatching { engine.downloadWorkspace() }
                            .onSuccess { onFeedback("下载完成：${it.summary}") }
                            .onFailure { onFeedback(it.message ?: "下载失败") }
                        busy = false
                    }
                }
                RemoteMiniAction("清空索引", Icons.Default.Delete, enabled = !busy) {
                    engine.clearIndex()
                    onFeedback("已清空同步索引，下次上传将全量重传")
                }
            }

            if (outcome.summary != "无变化") {
                Text(
                    "最近同步：" + outcome.summary,
                    fontSize = 10.sp, color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            error?.let {
                Text(it, fontSize = 10.sp, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** 卡片里的迷你操作按钮：小图标 + 文案，横排塞得下四个。 */
@Composable
private fun RemoteMiniAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = Accent.copy(alpha = if (enabled) 0.12f else 0.05f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (enabled) Accent else TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label, fontSize = 10.sp,
                color = if (enabled) Accent else TextSecondary.copy(alpha = 0.5f)
            )
        }
    }
}
