package com.wy.openx.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.remote.RemoteActiveState
import com.wy.openx.remote.RemoteConnection
import com.wy.openx.remote.RemoteConnState
import com.wy.openx.remote.RemoteProtocol
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 「连接配置」tab：服务器列表 + 空态。
 *
 * 这一页只负责「哪台服务器、连不连」；浏览远端文件统一在侧边栏文件管理的远程根里。
 *
 * 底部给 [BOTTOM_BAR_CLEARANCE] 留白：悬浮 tab 栏会盖住列表最后一项。
 */
@Composable
internal fun RemoteConnectionsTab(
    connections: List<RemoteConnection>,
    activeState: RemoteActiveState,
    onConnect: (RemoteConnection) -> Unit,
    onDisconnect: () -> Unit,
    onEdit: (RemoteConnection) -> Unit,
    onDelete: (RemoteConnection) -> Unit,
    onOpenTerminal: (RemoteConnection) -> Unit,
) {
    if (connections.isEmpty()) {
        RemoteEmptyState(
            icon = { Icon(Icons.Outlined.Dns, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp)) },
            title = "暂无连接配置",
            hint = "添加一台 SFTP / FTP 服务器，就能在这里读写远端文件",
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        connections.sortedBy { it.sortOrder }.forEach { config ->
            val state = if (activeState.connectionId == config.id) activeState.state else RemoteConnState.IDLE
            val connected = state == RemoteConnState.CONNECTED
            RemoteConnectionCard(
                config = config,
                state = state,
                onConnect = { onConnect(config) },
                onDisconnect = onDisconnect,
                onEdit = { onEdit(config) },
                onDelete = { onDelete(config) },
                onOpenTerminal = { onOpenTerminal(config) },
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(BOTTOM_BAR_CLEARANCE))
    }
}

/** 空态：居中图标 + 标题 + 提示。 */
@Composable
internal fun RemoteEmptyState(
    icon: @Composable () -> Unit,
    title: String,
    hint: String,
) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon()
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary, textAlign = TextAlign.Center)
            Text(hint, fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 一台服务器的卡片：名称、协议徽标、端点、连接状态、显式连接/断开按钮、编辑、删除。
 *
 * 不再整块可点：用户反馈点卡片后看不出连没连上，显式按钮 + 状态文案更清楚。
 */
@Composable
private fun RemoteConnectionCard(
    config: RemoteConnection,
    state: RemoteConnState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val connected = state == RemoteConnState.CONNECTED
    val statusLabel = when (state) {
        RemoteConnState.IDLE -> "已断开"
        RemoteConnState.CONNECTING -> "连接中…"
        RemoteConnState.CONNECTED -> "已连接"
        RemoteConnState.FAILED -> "连接失败"
    }
    val statusColor = when (state) {
        RemoteConnState.CONNECTED -> Accent
        RemoteConnState.FAILED -> Color(0xFFFF6B6B)
        else -> TextSecondary
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Dns,
                contentDescription = null,
                tint = if (connected) Accent else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(config.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Spacer(Modifier.width(6.dp))
                    ProtocolBadge(config.protocol.name)
                }
                Text(
                    "${config.endpoint} · $statusLabel",
                    fontSize = 11.sp,
                    color = statusColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(6.dp))
            val connectLabel = if (connected) "断开连接" else if (state == RemoteConnState.FAILED) "重新连接" else "启动连接"
            val connectIcon = if (connected) Icons.Outlined.Stop else if (state == RemoteConnState.FAILED) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow
            val connectTint = if (connected) Color(0xFFFF6B6B) else Accent
            IconButton(
                onClick = if (connected) onDisconnect else onConnect,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(connectIcon, contentDescription = connectLabel, tint = connectTint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(4.dp))
            // 只有 SSH 能开终端：FTP 是纯文件协议，没有命令通道
            if (config.protocol == RemoteProtocol.SFTP) {
                IconButton(onClick = onOpenTerminal, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Terminal, contentDescription = "终端", tint = TextSecondary, modifier = Modifier.size(17.dp))
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.Description, contentDescription = "编辑", tint = TextSecondary, modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFFF6B6B).copy(alpha = 0.8f), modifier = Modifier.size(17.dp))
            }
        }
    }
}

/** 协议小徽标（SFTP / FTP）。 */
@Composable
internal fun ProtocolBadge(text: String) {
    Surface(shape = RoundedCornerShape(4.dp), color = Accent.copy(alpha = 0.14f)) {
        Text(
            text,
            fontSize = 9.sp,
            color = Accent,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** 底部悬浮 tab 栏的占位高度：列表末尾要留出这么多，否则最后一项被遮住。 */
internal val BOTTOM_BAR_CLEARANCE = 88.dp
