package com.wy.openx.ui.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.wy.openx.ui.state.SessionBadge
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import com.wy.openx.ui.viewmodel.MainViewModel

/**
 * 应用级侧边栏：会话历史 + 文件树 + 插件快捷区 + 底部入口。
 *
 * 聊天页那套侧边栏（[AgentConsoleScreen] 里的 HistoryPane）回调由页面自己注入；
 * 插件页是全屏独立页面，拿不到那套回调，所以这一份直接从 [MainViewModel] 取数。
 * 两者最终渲染同一个 [ConversationDrawerContent]，外观与行为一致。
 *
 * 插件页里点会话 / 新建会话 / 设置 / 各管理页都会顺带切回聊天页：插件页盖在上层，
 * 不切页的话用户看不到操作结果。
 */
@Composable
internal fun AppSidebarContent(vm: MainViewModel, embedded: Boolean = false) {
    val uiState = vm.uiState
    var searchSession by remember { mutableStateOf<ConversationItem?>(null) }
    var renameSession by remember { mutableStateOf<ConversationItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteSessionId by remember { mutableStateOf<Long?>(null) }
    var archiveSessionId by remember { mutableStateOf<Long?>(null) }

    ConversationDrawerContent(
        conversations = uiState.conversations.map { c ->
            c.toLegacy().copy(
                meta = conversationMetaLabel(c, !uiState.settings.modelGlobalMode, uiState.settings.model),
                badge = uiState.sessionStatuses[c.id] ?: SessionBadge.IDLE
            )
        },
        currentId = uiState.currentConversationId,
        onSelect = { id ->
            vm.switchConversation(id)
            vm.openConsole()
        },
        onNewConversation = {
            vm.createNewConversation()
            vm.openConsole()
        },
        onOpenSettings = { vm.openSettings() },
        onDeleteConversation = { id -> deleteSessionId = id },
        onRequestRename = { item ->
            renameSession = item
            renameText = item.title
        },
        onTogglePin = { id, pinned -> vm.togglePinConversation(id, pinned) },
        onToggleArchive = { id, archived ->
            // 归档要确认弹窗；取消归档不需要（与聊天页口径一致）
            if (archived) archiveSessionId = id else vm.setConversationArchived(id, false)
        },
        onOpenHistory = { vm.openHistory() },
        onOpenMemoryManage = { vm.executeCommand("/memory") },
        onOpenMcpManage = { vm.executeCommand("/mcp") },
        onOpenSkillManage = { vm.executeCommand("/skills") },
        onOpenWorkflow = { vm.openWorkflow() },
        onOpenPluginManage = { vm.openPluginManage() },
        pluginQuickGroups = uiState.pluginQuickGroups,
        pluginQuickBarExpanded = uiState.settings.pluginQuickBarExpanded,
        onPluginQuickBarExpandedChange = { expanded ->
            vm.updateSettings(vm.uiState.settings.copy(pluginQuickBarExpanded = expanded))
        },
        onPluginQuickAction = { action -> vm.onPluginQuickAction(action) },
        onOpenPluginById = { id -> vm.onOpenPluginById(id) },
        onSearchMessages = { session -> searchSession = session },
        browseFileSystem = vm.browseFileSystem,
        onOpenFileInBrowser = { path -> vm.openBrowser(path) },
        onAttachToInput = { path, name -> vm.attachWorkspaceEntryToInput(path, name) },
        embedded = embedded
    )

    archiveSessionId?.let { convId ->
        AlertDialog(
            onDismissRequest = { archiveSessionId = null },
            title = { Text("确认归档", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("归档后此会话将从侧边栏移入「历史」，数据保留，可随时取消归档。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setConversationArchived(convId, true)
                    archiveSessionId = null
                }) { Text("确认归档", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { archiveSessionId = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }

    deleteSessionId?.let { convId ->
        AlertDialog(
            onDismissRequest = { deleteSessionId = null },
            title = { Text("确认删除", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("确定要删除此会话吗？此操作不可撤销。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteConversation(convId)
                    deleteSessionId = null
                }) { Text("确认删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSessionId = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }

    renameSession?.let { item ->
        AlertDialog(
            onDismissRequest = { renameSession = null },
            title = { Text("重命名会话", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text("输入新名称", color = TextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Accent,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        vm.renameConversation(item.id, renameText)
                        renameSession = null
                    }
                }) { Text("确认", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameSession = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }

    searchSession?.let { session ->
        MessageSearchDialog(
            sessionTitle = session.title,
            onSearch = { query, callback -> vm.searchConversationMessages(session.id, query, callback) },
            onJump = { messageId ->
                vm.jumpToMessage(session.id, messageId)
                vm.openConsole()
                searchSession = null
            },
            onDismiss = { searchSession = null }
        )
    }
}
