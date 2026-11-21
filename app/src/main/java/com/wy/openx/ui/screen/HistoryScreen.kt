package com.wy.openx.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.ui.component.SlidableRow
import com.wy.openx.ui.component.SlideAction
import com.wy.openx.ui.component.rememberSlidableGroupState
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.BgSecondary
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary

/**
 * 「历史」页：已归档会话列表。
 *
 * 归档进来的会话在这里查看；左滑会话可「恢复」（回到侧边栏主列表）或「删除」。
 * 与聊天页共用同一批会话数据（`uiState.archivedConversations`），不额外查库。
 */
@Composable
fun HistoryScreen(
    conversations: List<ConversationItem>,
    onBack: () -> Unit,
    onSelect: (Long) -> Unit,
    onUnarchive: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    val slidableGroup = rememberSlidableGroupState()
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .statusBarsPadding()
    ) {
        // 顶栏：左返回、中标题。与设置页顶栏同构，保持一致观感
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶栏不铺底色：透出页面底色/背景图，与 Git、量化页一致。
                // （TopAppBar containerColor = Transparent）观感相同。
                // 原来用 BgSecondary（= surface，浅米下纯白）会形成一条白带，选背景图时更是挡图。
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text("历史", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            if (conversations.isNotEmpty()) {
                Text(
                    "${conversations.size} 个已归档",
                    color = TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
        }

        if (conversations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无已归档会话", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在侧边栏左滑会话即可归档",
                        color = TextSecondary.copy(alpha = 0.6f), fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(conversations, key = { it.id }) { item ->
                    SlidableRow(
                        actions = listOf(
                            SlideAction(
                                icon = Icons.Outlined.Unarchive,
                                tint = Accent,
                                onClick = { onUnarchive(item.id) }
                            ),
                            SlideAction(
                                icon = Icons.Outlined.Delete,
                                tint = androidx.compose.ui.graphics.Color(0xFFFF6B6B),
                                onClick = { pendingDeleteId = item.id }
                            )
                        ),
                        itemId = item.id,
                        groupState = slidableGroup,
                        modifier = Modifier
                    ) {
                        Surface(
                            onClick = { onSelect(item.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = Panel,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.title.ifBlank { "未命名会话" },
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (item.meta.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            item.meta,
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认弹窗（破坏性操作，需确认）
    pendingDeleteId?.let { id ->
        val title = conversations.firstOrNull { it.id == id }?.title?.ifBlank { "未命名会话" } ?: "此会话"
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("确认删除", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("删除「$title」？该会话及其全部消息将被永久删除，不可恢复。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(id)
                    pendingDeleteId = null
                }) { Text("删除", color = androidx.compose.ui.graphics.Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }
}
