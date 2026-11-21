package com.wy.openx.ui.screen

import android.Manifest
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.wy.openx.ui.viewmodel.COMPACTION_MARKER
import com.wy.openx.ui.viewmodel.SUMMARY_PREFIX
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONObject
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Schema
// [PLUGIN-MOD] 插件管理入口图标
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.MutableState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.wy.openx.ui.component.SheetScrollLock
import com.wy.openx.ui.component.SlidableRow
import com.wy.openx.ui.component.SlideAction
import com.wy.openx.ui.component.ModelLogoIcon
import com.wy.openx.container.BrowseClipboard
import com.wy.openx.container.FileLogger
import com.wy.openx.data.util.ChatUtils
import com.wy.openx.data.model.ChatMessageModel
import com.wy.openx.data.model.ApiPreset
import com.wy.openx.data.model.MessageRole
import com.wy.openx.data.model.PendingToolCallInfo
import com.wy.openx.data.model.QuestionInfo
import com.wy.openx.data.model.TaskItem
import com.wy.openx.data.model.TaskStatus
import com.wy.openx.ui.state.ConsoleUiState
import com.wy.openx.ui.command.BUILTIN_SLASH_COMMANDS
import com.wy.openx.ui.command.canonicalBuiltinCommand
import com.wy.openx.ui.state.SessionBadge
import com.wy.openx.ui.state.ThinkingTiming
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.BgSecondary
import com.wy.openx.tools.EditorSettingsStore
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.ButtonBg
import com.wy.openx.ui.theme.ButtonFg
import com.wy.openx.ui.theme.ButtonFgOnFill
import com.wy.openx.ui.theme.CodeBg
import com.wy.openx.ui.theme.Danger
import com.wy.openx.ui.theme.GlassElementBrush
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.Success
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import com.wy.openx.ui.theme.UserBubbleBg
import com.wy.openx.ui.theme.UserBubbleFg
import com.wy.openx.ui.theme.Warning
import com.wy.openx.ui.theme.isGlassTheme
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Token 数字格式化（对齐 RC7）：≥1K 显示 X.Xk，≥1M 显示 X.XM，≥1B 显示 X.XB。 */
private fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> "%.1fB".format(tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

// 用户向下拖动（内容往更早的消息方向走）时第一时间回调，不等滚动状态变化。
// 走 Initial 阶段且不消费事件，只做旁路观察，不影响列表/抽屉自身手势。
private fun Modifier.onReadDragStart(onRead: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var acc = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            when (event.type) {
                PointerEventType.Press -> acc = 0f
                PointerEventType.Move -> {
                    val dy = event.changes.firstOrNull()?.positionChange()?.y ?: 0f
                    if (dy > 0f) {
                        acc += dy
                        if (acc >= 6f) {
                            acc = 0f
                            onRead()
                        }
                    } else {
                        acc = 0f
                    }
                }
                PointerEventType.Release -> acc = 0f
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConsoleScreen(
    uiState: ConsoleUiState,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopStreaming: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
    onPickFileResult: (Uri?, String) -> Unit,
    onApplyQuickTask: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenQuantTrade: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    /** 侧边栏文件树长按「用浏览器打开」：传入容器路径，打开内置浏览器并加载该文件。 */
    onOpenFileInBrowser: (String) -> Unit = {},
    /** 侧边栏文件树长按「加入输入栏」：容器路径 + 文件名，投进聊天输入区当附件。 */
    onAttachToInput: (String, String) -> Unit = { _, _ -> },
    onOpenWorkflow: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    // [PLUGIN-MOD] 插件：侧边栏入口 + 快捷区
    onOpenPluginManage: () -> Unit = {},
    pluginQuickGroups: List<com.wy.openx.plugin.PluginQuickGroup> = emptyList(),
    onPluginQuickAction: (com.wy.openx.plugin.PluginQuickAction) -> Unit = {},
    /** 快捷区行首插件名点击：按 id 打开插件界面。 */
    onOpenPluginById: (String) -> Unit = {},
    /** 侧边栏插件快捷区是否展开（持久化在设置里，默认折叠）。 */
    pluginQuickBarExpanded: Boolean = false,
    /** 就地折叠/展开插件快捷区；调用方写回设置。 */
    onPluginQuickBarExpandedChange: (Boolean) -> Unit = {},
    onSwitchConversation: (Long) -> Unit,
    /** 跨会话「等待你」横幅点「忽略」：只收起提醒，不打断那个会话。 */
    onDismissAwaitingBanner: () -> Unit = {},
    onToggleCommandPalette: () -> Unit = {},
    onExecuteCommand: (String) -> Unit = {},
    onDeleteConversation: (Long) -> Unit = {},
    onResendMessage: (ChatMessageModel) -> Unit = {},
    onRegenerateMessage: (() -> Unit)? = null,
    onDeleteMessage: (Long) -> Unit = {},
    onEditMessage: ((Long, String) -> Unit)? = null,
    /** 「从此处新建分支」：复制该消息及之前的历史到新会话。 */
    onForkMessage: ((ChatMessageModel) -> Unit)? = null,
    onRewindMessage: ((Long, com.wy.openx.ui.viewmodel.RewindOption) -> Unit) = { _, _ -> },
    onClearAttachment: () -> Unit = {},
    onRemoveAttachment: (Uri) -> Unit = {},
    currentToolName: String? = null,
    pendingToolCall: PendingToolCallInfo? = null,
    onConfirmToolCall: ((Boolean) -> Unit)? = null,
    onAlwaysAllowTool: ((String) -> Unit)? = null,
    pendingTerminalConfirm: PendingToolCallInfo? = null,
    onRespondTerminalConfirm: (Boolean) -> Unit = {},
    pendingQuestion: QuestionInfo? = null,
    onSubmitAnswer: (String) -> Unit = {},
    onCancelQuestion: () -> Unit = {},
    tasks: List<TaskItem> = emptyList(),
    tasksExpanded: Boolean = true,
    onAddTask: (String) -> Unit = {},
    onUpdateTaskStatus: (Long, TaskStatus) -> Unit = { _, _ -> },
    onRemoveTask: (Long) -> Unit = {},
    onClearCompletedTasks: () -> Unit = {},
    onToggleTasksExpanded: () -> Unit = {},
    onSwitchPreset: (ApiPreset) -> Unit = {},
    agentMode: String = "build",
    onSetAgentMode: (String) -> Unit = {},
    toolPermissionMode: String = "allow",
    onSetToolPermissionMode: (String) -> Unit = {},
    reasoningEffort: String = "default",
    onSetReasoningEffort: (String) -> Unit = {},
    /**
     * 当前模型支持的思考强度档位（models.dev）。**null = 元数据缺失 → 显示全量**。
     * 做成回调而非值：元数据异步加载，且只在弹窗打开时才需要——不缓存，避免时序问题。
     */
    reasoningEffortOptionsProvider: () -> List<String>? = { null },
    projects: List<String> = emptyList(),
    currentProject: String = "",
    onCreateProject: (String) -> Unit = {},
    onSelectProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
    externalWorkspaces: List<com.wy.openx.data.model.ExternalWorkspace> = emptyList(),
    onAddExternalWorkspace: (String) -> Unit = {},
    onRename: (Long, String) -> Unit = { _, _ -> },
    onTogglePin: (Long, Boolean) -> Unit = { _, _ -> },
    /** 归档 / 取消归档（侧边栏左滑与全滑触发）。 */
    onToggleArchive: (Long, Boolean) -> Unit = { _, _ -> },
    /** 打开「历史」页（已归档会话）。 */
    onOpenHistory: () -> Unit = {},
    onCancelQueuedMessage: (Int) -> Unit = {},
    onClearMessageQueue: () -> Unit = {},
    onSendQueuedMessage: (Int) -> Unit = {},
    onSendAllQueuedMessages: () -> Unit = {},
    browseFileSystem: com.wy.openx.container.BrowseFileSystem? = null,
    scrollState: ChatScrollState = ChatScrollState(),
    onSearchConversationMessages: (Long, String, (List<ChatMessageModel>) -> Unit) -> Unit = { _, _, cb -> cb(emptyList()) },
    onJumpToMessage: (Long, Long) -> Unit = { _, _ -> },
    onConsumeMessageJump: () -> Unit = {},
    /** 上下文用量圆环：当前上下文 token 数与窗口阈值。 */
    contextUsedTokens: Int = 0,
    contextWindowTokens: Int = 0,
    /** 长按圆环 → 模型设置抽屉保存（复用设置页同一套逻辑）。 */
    onUpdatePresetMeta: (String, String, Long?, Long?) -> Unit = { _, _, _, _ -> },
    /** 偏好设置「使用独立的发送按钮」：开启后回车换行、须点发送按钮。 */
    separateSendButton: Boolean = false,
    // ===== 提供商独立管理（聊天页模型选择列表） =====
    providers: List<com.wy.openx.data.model.AIProviderConfig> = emptyList(),
    providerModelMetadata: Map<String, com.wy.openx.data.model.ModelMetadata> = emptyMap(),
    onSelectProviderModel: (String, String) -> Unit = { _, _ -> },
    /** 当前模型元数据 / 名称 / 保存 / 自动获取（圆环长按弹窗走 provider 体系）。 */
    currentModelMeta: com.wy.openx.data.model.ModelMetadata? = null,
    currentModelName: String = "",
    onSaveCurrentModelMeta: (Long?, Long?) -> Unit = { _, _ -> },
    onAutoFetchCurrentModelMeta: ((Long?, Long?) -> Unit) -> Unit = { cb -> cb(null, null) },
    /**
     * 需求3：横屏左栏是否显示「子页面」而非会话历史
     * （设置 / 终端 / Git / 浏览器 / 量化 / 历史 / 记忆 / MCP / 技能 / Token 统计 …）。
     */
    leftPage: Boolean = false,
    /**
     * 子页面的内容槽。由 MainActivity 提供——只有它持有各 Screen 的完整参数，
     * 本组件只负责摆放位置。
     * 注：形参类型已带 @Composable，调用点传普通 lambda 即可被推断为
     * ComposableFunction0；若在调用点显式写 `@Composable { }` 反而要求
     * MainActivity 也 import Composable，故此处不用可空类型。
     */
    /** 关闭「并行执行频繁限流」提示（保持并行设置不变）。 */
    onDismissRateLimitWarning: () -> Unit = {},
    /** 弹窗里点「保持并行」：关掉提示，并让**本会话**后续不再弹。 */
    onKeepParallel: () -> Unit = {},
    /** 同一提示窗里的「改为串行」：关掉「并行执行批内工具」并落盘。 */
    onDisableParallelToolCalls: () -> Unit = {},
    leftPageContent: @Composable () -> Unit = {}
) {
    // 语音回合 / 「自动朗读」开关 → 回复结束后播报（细节见该函数）
    AutoSpeakOnReply(uiState = uiState)

    // 远程连接状态：聊天页顶栏与侧边栏远程根只认「真正连上」的状态，
    // 连接中/失败不显示，避免用户以为已经可用。
    val remoteActiveState by com.wy.openx.remote.RemoteEngines.manager.activeState.collectAsState()
    val remoteTraffic by com.wy.openx.remote.RemoteTrafficMonitor.state.collectAsState()
    val remoteConnections by com.wy.openx.remote.RemoteEngines.manager.connections.collectAsState()
    val connectedRemote by remember(remoteActiveState, remoteConnections) {
        derivedStateOf {
            if (remoteActiveState.state == com.wy.openx.remote.RemoteConnState.CONNECTED) {
                remoteConnections.firstOrNull { it.id == remoteActiveState.connectionId }
            } else null
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = scrollState.listState
    var showDeleteConversationDialog by remember { mutableStateOf<Long?>(null) }
    // 归档确认：与非破坏性的「取消归档」区分——归档会把会话移出主列表，值得一次确认
    var showArchiveConfirmDialog by remember { mutableStateOf<Long?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var pendingRenameSessionId by remember { mutableStateOf<Long?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessageModel?>(null) }
    var rewindTarget by remember { mutableStateOf<ChatMessageModel?>(null) }
    // 聊天记录搜索：当前搜索的目标会话 + 命中后临时高亮的消息 id
    var searchSession by remember { mutableStateOf<ConversationItem?>(null) }
    var highlightMessageId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val photoUri = remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri.value != null) {
            // 名称留空：查询不到 DISPLAY_NAME 时 VM 会用时间戳生成 photo_xxx.jpg
            onPickFileResult(photoUri.value!!, "")
        }
    }
    val launchCamera: () -> Unit = {
        val dir = File(context.cacheDir, "camera_photos")
        dir.mkdirs()
        // 只保留最近 9 张，避免缓存无限膨胀
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        photoUri.value = uri
        cameraLauncher.launch(uri)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }
    val onTakePhoto: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPickFileResult(uri, "") }
    // 图片支持多选，一次可追加多张（名称留空，由 VM 从 contentResolver 取真实文件名）
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> uris.forEach { onPickFileResult(it, "") } }

    // 流式中的助手消息 id：思考块据此显示「正在思考（N秒）」还是「思考过程用时 N秒」，
    // 也是它能否拿到权威计时起点（thinkingStartedAt）的唯一依据。
    //
    // ⚠ **必须每帧按当前 messages 现算，不能用 `remember(uiState.isSending)` 缓存**：
    // isSending 翻成 true 的那一刻，本轮助手气泡**还没建出来**（首个流式增量才 new 一条），
    // 那时算出来的只能是上一轮的 id 或 -1。而 isSending 此后不再变化，remember 不会重算，
    // 于是永远匹配不上本轮消息 → 思考块被当成「非流式」→ 计时器从不启动 →
    // 生成结束后标题连「用时 N秒」都不显示（用户实测报的就是这个）。
    //
    // 取「最后一条消息，且是普通助手文本」而不是 `lastOrNull { assistant }`：
    // 后者在「isSending 已置位、首 token 还没到」的空窗期会命中**上一条**助手消息，
    // 让它误显示「正在思考」并开始计时。用「必须是最后一条」可避开这个空窗期。
    //
    // 现算的代价可忽略（一次 lastOrNull/字段读），且 groups 本来就把 uiState.messages
    // 当 remember key，不会因此多重建。
    val lastMsg = uiState.messages.lastOrNull()
    val streamingMsgId = if (
        uiState.isSending && lastMsg != null &&
        lastMsg.role == MessageRole.ASSISTANT && lastMsg.toolName == null
    ) lastMsg.id else -1L
    // thinkingSegments 参与 key：它每段只变两次（开段、结束），不会在流式期间每 150ms 变化，
    // 不违反上面「不要用 messages 当 key」的初衷。必须参与，否则段结束时刻落定时
    // groups 不重算、标题不会从「正在思考」切成「思考过程用时 N秒」。
    //
    // ── 单一平铺列表────────────────────────────────
    //
    // 折叠历史、压缩卡、保留尾部**在同一条列表里按显示序排**：
    //   …历史原文(folded)… → [压缩锚点 marker] → 压缩卡 → 保留尾部…
    // 压缩卡正好落在「最后一个执行完的工具」下面，往上翻就是压缩前的原文。
    //
    // 为什么不再分三块渲染（foldedList → summaryMsgs → groups）：分块等于自己实现排序，
    //  openx 的消息 id 有「库自增」与「内存 nextMessageId=毫秒×1000」两套来源，
    // 自己按 id 排必然出错（用户报障「卡片跑到最上面、历史消失」）。
    // 现在位置完全由库里的 `MessageEntity.seq`（会话内单调显示序）决定，UI 不再猜。
    //
    // marker 行本身不渲染成气泡——它只是压缩卡的锚点，卡片渲染在它的位置上。
    // 折叠原文与上下文**合并后按 seq 排**，而不是简单「折叠在前、上下文在后」拼接。
    // 拼接只在「折叠区恰好都排在上下文之前」时才成立，是隐含假设；seq 已经携带完整显示序，
    // 直接用它对两者做归并排序才与数据模型一致（压缩卡的位置也就稳定落在压缩发生处）。
    //
    // ⚠ `seq == 0` 表示「**尚未落库**」的内存消息（流式正文、工具调用/结果、刚发的用户消息），
    // 必须排到**最后**，不能按字面值 0 排到最前。
    // 踩过的坑：内存消息 seq 默认 0，而落库消息 seq >= 1，于是新消息被排到列表**最顶端**——
    // 用户停在底部只看到「卡住、看不到 AI 执行」，一按暂停消息落库拿到 seq 才「冒出来」。
    // 用 `Long.MAX_VALUE` 兜底即可让未落库消息稳定排在已落库消息之后，且它们之间仍按 id（=创建序）排。
    val displayMessages = remember(uiState.messages, uiState.foldedMessages) {
        (uiState.foldedMessages + uiState.messages).sortedWith(
            compareBy({ if (it.seq == 0L) Long.MAX_VALUE else it.seq }, { it.id })
        )
    }
    // marker → 紧随其后的摘要正文。marker 只是锚点、不进请求也不渲染成气泡，
    // 卡片要显示的文本是它**后面那条**消息，所以在这里配好对。
    //
    // 在**完整的平铺列表**（折叠原文 + 上下文）上配对，而不是只看上下文：二次压缩时
    // 上一次的 marker/摘要已被折进折叠区，仍应在原来的位置显示它自己那张卡。
    //
    // ⚠ 配对只做 **id 级**操作（`id → 下标 → 下一条的 id/内容`），不做内容比较：
    // 早先这里用 `content.trim()` 去 `firstOrNull` 反查摘要行，而工具结果单条可达十几万字符，
    // 每帧对每条消息 trim + 比较 = 明显的卡顿（「发消息后要等一会才显示」的一半原因）。
    // 摘要必然紧跟在 marker 之后，按下标取即可，无需回扫。
    val compactionSummaryByMarker = remember(displayMessages) {
        val msgs = displayMessages
        buildMap {
            for (i in msgs.indices) {
                if (msgs[i].content != COMPACTION_MARKER) continue
                val summary = msgs.getOrNull(i + 1) ?: continue
                if (summary.content == COMPACTION_MARKER) continue
                put(msgs[i].id, summary.id to summary.content.trim())
            }
        }
    }
    // 已被 marker 卡片「代劳」的摘要消息不再单独渲染成气泡，否则一次压缩会显示
    // 「一张卡 + 一段裸摘要文字」两份。
    val compactionSummaryIds = remember(compactionSummaryByMarker) {
        compactionSummaryByMarker.values.mapTo(HashSet()) { it.first }
    }
    val visibleMessages = remember(displayMessages, compactionSummaryIds) {
        displayMessages.filterNot { it.id in compactionSummaryIds }
    }
    val displayGroups = remember(visibleMessages, streamingMsgId, uiState.thinkingSegments) {
        groupMessages(visibleMessages, streamingMsgId, uiState.thinkingSegments)
    }
    // 检查最后一项底部是否真的靠近视口底部，
    // 避免用户在超长 thinking 区域（最后一项中部）被误判为"在底部"而触发强制跟随。
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf true
            val lastIndex = info.totalItemsCount - 1
            lastVisible.index >= lastIndex &&
                (lastVisible.offset + lastVisible.size) <= info.viewportEndOffset + 80
        }
    }
    // LazyColumn 里的真实最后一项下标。
    fun lastContentIndex(): Int = displayGroups.size - 1
    // 用户向上翻看过历史后将锁定自动跟随（scrollState.userPulledUp），直到用户自己回到底部
    // 或切换会话（防止工具调用追加消息时不断把用户拽回底部）；用首可见项变化 + 滚动进行中
    // 来区分用户手势与程序滚动。
    // 值存在 ChatScrollState 里，原因见该字段的 KDoc —— 放 remember 会被设置页销毁。
    // 聊天里点「文件:行号」链接要打开的目标（路径 + 1-based 行号；行号 0 = 无）。
    // 由 [LocalChatFileOpener] 写入（Markdown 链接点击），底部渲染 [FileViewerSheet] 消费。
    var chatLinkTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // 搜索命中后的跳转：切到目标会话后消息异步加载，待目标消息出现在列表里再滚动定位；
    // 命中后短暂高亮该气泡，3 秒后自动取消。
    val pendingJump = uiState.pendingMessageJump
    LaunchedEffect(pendingJump, displayGroups, uiState.currentConversationId) {
        if (pendingJump == null) return@LaunchedEffect
        if (pendingJump.conversationId != uiState.currentConversationId) return@LaunchedEffect
        val targetId = pendingJump.messageId
        val idx = displayGroups.indexOfFirst { g ->
            when (g) {
                is MsgGroup.Single -> g.msg.id == targetId
                is MsgGroup.ToolCalls -> g.items.any { it.id == targetId }
                is MsgGroup.Thinking -> g.msgId == targetId
            }
        }
        if (idx >= 0) {
            // 目标上方留一个 item 作为上下文
            val scrollTo = (idx - 1).coerceAtLeast(0)
            withFrameNanos { }
            listState.scrollToItem(scrollTo, 0)
            scrollState.userPulledUp = true
            highlightMessageId = targetId
            onConsumeMessageJump()
        }
    }
    LaunchedEffect(highlightMessageId) {
        if (highlightMessageId != null) {
            delay(3000)
            highlightMessageId = null
        }
    }
    // 兜底：若目标消息迟迟未出现（例如已被删除），10 秒后放弃跳转，避免长期禁掉自动贴底
    LaunchedEffect(pendingJump) {
        if (pendingJump != null) {
            delay(10_000)
            onConsumeMessageJump()
        }
    }
    // 程序性滚动标记：scrollToItem 也会改变 firstVisibleItemIndex/Offset，
    // 若不加排除，一次程序性贴底就会被下方 snapshotFlow 判成「用户上翻」而永久禁用自动跟随。
    // 只在真正的拖动中才置 true。
    var programmaticScroll by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var prevFirst = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, programmaticScroll)
        }
            .collect { (first, offset, programmatic) ->
                val movedUp = first < prevFirst || (first == prevFirst && offset < prevOffset)
                // isScrollInProgress 不足以区分「用户手指拖动」与「代码调scrollToItem」：
                // 两者都会置起该标志。所以再叠一个 programmaticScroll 排除。
                if (movedUp && listState.isScrollInProgress && !programmatic) {
                    scrollState.userPulledUp = true
                } else if (!movedUp && !listState.canScrollForward &&
                    listState.isScrollInProgress && !programmatic) {
                    // 只有「用户自己滚动到严格底部」才解除锁定。
                    // 旧判据用 isAtBottom（含 80px 容差），流式期间布局/内容增长产生的
                    // 轻微位移也会命中，把刚锁上的 userPulledUp 清掉 → 外层又贴底 → 抖动。
                    scrollState.userPulledUp = false
                }
                prevFirst = first
                prevOffset = offset
            }
    }
    // 进入页面/切换会话时强制贴底（此时消息可能尚未异步加载完，用 seenSessionLoaded
    // 记住「本会话是否已贴过底」，避免空消息阶段提前消费掉新会话状态导致最终停在顶部）；
    // 同一会话内新增消息仅在用户已在底部（且未上翻历史）时跟随，避免工具调用追加消息
    // 把正在翻看历史的用户拽回底部。scrollState.seen* 记忆跨 tab 切换存活。
    LaunchedEffect(uiState.currentConversationId, uiState.messages.size) {
        val isNewSession = scrollState.seenSessionLoaded != uiState.currentConversationId
        val contentChanged = isNewSession || scrollState.seenCount != uiState.messages.size
        // 消息被裁剪（Rewind「恢复对话/仅恢复会话」会把列表截断到目标消息之前）时，
        // 列表整体变短，原滚动位置大概率指向已消失的 item，会停在顶部。此时应强制贴
        // 底到裁剪后的最后一条，并解除可能残留的上翻锁定。
        val shrunk = scrollState.seenConversation == uiState.currentConversationId &&
            scrollState.seenCount > uiState.messages.size && scrollState.seenCount > 0
        if (isNewSession) scrollState.userPulledUp = false
        if (shrunk) scrollState.userPulledUp = false
        // 有「搜索跳转」待处理时不要贴底，否则会和定位滚动互相打架
        val jumpPendingHere = uiState.pendingMessageJump?.conversationId == uiState.currentConversationId
        if (!jumpPendingHere && uiState.messages.isNotEmpty() && uiState.settings.autoScroll && contentChanged) {
            // 用 scrollState.userPulledUp 作为「用户是否停在底部」的判据，不能在这里重新求值 isAtBottom：
            // 本 effect 在新消息追加之后才运行，视口下方已经多了 item，isAtBottom 必然重算为
            // false（canScrollForward 变真），于是「本来就在底部」被误判为「不在底部」而不跟随
            // ——这正是「开关打开、有新消息也不到底」的根因。scrollState.userPulledUp 由滚动监听维护：
            // 只有用户真上翻才置真，回到底部自动复位；关闭开关时则完全不滚动。
            val shouldFollow = isNewSession || shrunk || !scrollState.userPulledUp
            if (shouldFollow) {
                // 等待一帧让 LazyColumn 完成新会话/新增消息的布局后再贴底（参考 rc6 snapToBottom）
                withFrameNanos { }
                programmaticScroll = true
                listState.scrollToItem(lastContentIndex(), Int.MAX_VALUE)
                withFrameNanos { }
                programmaticScroll = false
            }
        }
        if (uiState.messages.isNotEmpty()) scrollState.seenSessionLoaded = uiState.currentConversationId
        scrollState.seenConversation = uiState.currentConversationId
        scrollState.seenCount = uiState.messages.size
    }
    // 流式输出期间每帧检查视口是否被推离底部
    // （thinking 增长导致 item 变高、或 markdown 异步解析导致高度跳变），若偏离则滚回
    // 锚点。比旧的事件驱动 tailContentLen 触发更可靠：thinking 增长会持续改变 item 高度，
    // 事件驱动的 scrollToItem 在 offset clamp 时会停在 thinking 区域而非正文末尾。
    //
    // 停止后还要再跑几帧：isSending 由 true 变 false 时，思考条收起 + 输入框变矮 +
    // 末条气泡重新测量，视口高度随之变化，而本effect 会在 false 时立即退出——
    // 于是最后这次视口变化没人校准，最后几行就停在输入框上面（用户实测：回复完差两三行）。
    // 因此改成：结束后再跑几帧贴底，确认贴上了就收工。
    LaunchedEffect(uiState.isSending) {
        if (!uiState.settings.autoScroll) return@LaunchedEffect
        var settleFrames = 0
        // [B] 降噪/降频：内容刷新被 VM 节流到 ~150ms 一次，逐帧全量快照是白烧 CPU。
        // 布局快照采样到每 4 帧一次（90/120Hz 下 ≈ 30~40ms），修复动作再压到 ≥80ms 一次。
        var pollFrames = 0L
        var lastFixAt = 0L
        while (true) {
            withFrameNanos { }
            if (scrollState.userPulledUp) {
                if (!uiState.isSending) return@LaunchedEffect   // 用户主动上翻，不再跟随
                continue
            }
            if (!uiState.isSending) {
                // 停止后收尾：等布局稳定（最多 ~20 帧）再贴一次底
                if (++settleFrames > 20) return@LaunchedEffect
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex < 0) return@LaunchedEffect
                programmaticScroll = true
                listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                withFrameNanos { }
                programmaticScroll = false
                if (settleFrames > 3 && isAtBottom) return@LaunchedEffect
                continue
            }
            settleFrames = 0
            // [B] 降噪：用户手指正拖着/惯性滚着时不抢位——原来每次内容一长就立刻
            // scrollToItem，等于程序在跟用户手势抢方向（视觉抖动 + 掉帧）。松手后再校准。
            if (listState.isScrollInProgress) continue
            // [B] 降频：layoutInfo 快照（强制位置重算）从每帧一次降到每 4 帧一次。
            if (++pollFrames % 4L != 0L) continue
            val layout = listState.layoutInfo
            val lastIndex = layout.totalItemsCount - 1
            if (lastIndex < 0) return@LaunchedEffect
            // canScrollForward=false 时不退出循环（用户可能在底部等新消息到来），
            // 改为 continue 跳过本轮检查，等新 item 出现后自然恢复校准。
            if (!listState.canScrollForward) continue
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            val lost = lastVisible == null || lastVisible.index < lastIndex
            val pushedDown = lastVisible != null &&
                (lastVisible.offset + lastVisible.size) > layout.viewportEndOffset + 80
            if ((lost || pushedDown) && System.currentTimeMillis() - lastFixAt > 80L) {
                lastFixAt = System.currentTimeMillis()
                programmaticScroll = true
                listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                withFrameNanos { }
                programmaticScroll = false
            }
        }
    }

    // ── 横屏两栏（方案A，详见 横屏两栏设计方案.md）────────────────────────────
    // 横屏：左栏聊天历史 weight(1f) + 右栏聊天页 weight(2f)，严格 1/3。
    // 竖屏：走下面 ModalNavigationDrawer 的抽屉路径，与改造前完全一致（零回归）。
    // 仅按方向判定，不用旧方案的 screenWidthDp>=720 阈值——那会让小屏手机横屏落回竖屏布局。
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 需求5：左栏折叠态（不点不折叠）。局部 mutableStateOf 即可，
    // 顶栏回调与下面 LaunchedEffect 都能读写；旋转重建后自然回到展开态。
    var landscapePaneCollapsed by remember { mutableStateOf(false) }
    // K1：横屏可视高度本就只有一百多 dp，键盘弹出时临时收起左栏，让聊天区独占全宽。
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // 需求5：横屏下顶栏 ≡ 按钮改为手动折叠左栏的开关（不点不折叠，点了聊天页全屏）。
    val paneCollapsed = landscapePaneCollapsed
    // 需求3：左栏被子页面占用时即使键盘弹出也保留——子页面不该随键盘消失，
    // 且此时用户多半不在输入框里打字。
    val showHistoryPane =
        isLandscape && !paneCollapsed && (!imeVisible || leftPage)

    // 需求3+5 交互修正：左栏处于折叠态时若从别处打开子页面（如顶栏的终端/Git 按钮），
    // 子页面会渲染进不可见的左栏 → 表现为「点了没反应」。故打开子页面时自动展开。
    LaunchedEffect(leftPage) {
        if (leftPage) landscapePaneCollapsed = false
    }

    // 键盘弹出/收起会改变消息列表的视口高度：滚动偏移不变，底部那几行就被输入框盖住。
    // 流式期间由下面 isSending 的校准循环兜住了（所以「AI 回复中弹键盘」是正常的），
    // 但回复结束后那个循环已经退出，之后视口再变就没人校准——这正是「回复完的消息被遮挡、
    // 而刚发出的用户消息不会」的原因。
    //
    // 触发源用「视口高度」而不是 imeVisible：视口变化的成因不止键盘（输入框自身高度、
    // 附件条出现/消失都会），监听高度才不漏。滚动不改视口高度，所以不会误触发。
    // 另：不能只贴一次——键盘是渐变动画，这几百毫秒内视口持续收缩，贴一帧之后又被压缩了，
    // 所以要在变化期间反复校准，直到真的贴住（或超时退出）。
    LaunchedEffect(Unit) {
        var lastViewport = -1
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { h ->
            if (h <= 0 || h == lastViewport) return@collect
            lastViewport = h
            if (!uiState.settings.autoScroll) return@collect
            if (uiState.isSending) return@collect              // 流式期间交给下面的校准循环
            if (scrollState.userPulledUp) return@collect       // 用户在看历史，别拽回去
            var frames = 0
            while (frames < 48) {
                withFrameNanos { }
                frames++
                // 期间用户开始上翻 / 突然开始流式，立刻让位（后者交给原有校准循环）
                if (scrollState.userPulledUp || uiState.isSending) return@collect
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex < 0) return@collect
                programmaticScroll = true
                listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                withFrameNanos { }
                programmaticScroll = false
                // 真贴住了就收工；否则说明视口还在变，继续下一帧
                if (isAtBottom && frames > 4) return@collect
            }
        }
    }

    // 聊天历史：竖屏当抽屉内容、横屏当常驻左栏，两处共用这一份实现。
    // 注：局部 @Composable fun 不能加 private（Kotlin 限制）。
    @Composable
    fun HistoryPane(embedded: Boolean) {
        ConversationDrawerContent(
            conversations = uiState.conversations.map { c ->
                c.toLegacy()
                    // 副标题实时派生（相对时间 + 本会话模型名），不读 Room 里那份永不更新的 meta。
                    // 原来显示 c.meta，导致选了模型也一直停在建会话时的「[未选择模型]」。
                    .copy(
                        meta = conversationMetaLabel(
                            c,
                            // sessionMode 参数 = 会话级模式（全局模式的取反）
                            !uiState.settings.modelGlobalMode,
                            uiState.settings.model
                        ),
                        badge = uiState.sessionStatuses[c.id] ?: SessionBadge.IDLE
                    )
            },
            currentId = uiState.currentConversationId,
            onSelect = { id ->
                onSwitchConversation(id)
                scope.launch { drawerState.close() }
            },
            onNewConversation = {
                onNewConversation()
                scope.launch { drawerState.close() }
            },
            onOpenSettings = {
                onOpenSettings()
                scope.launch { drawerState.close() }
            },
            onDeleteConversation = { id ->
                showDeleteConversationDialog = id
            },
            onRequestRename = { item ->
                pendingRenameSessionId = item.id
                renameText = item.title
                showRenameDialog = true
            },
            onTogglePin = { id, pinned -> onTogglePin(id, pinned) },
            onToggleArchive = { id, archived ->
                // 归档走确认弹窗；取消归档（archived=false）不需要确认
                if (archived) showArchiveConfirmDialog = id else onToggleArchive(id, false)
            },
            onOpenHistory = {
                onOpenHistory()
                scope.launch { drawerState.close() }
            },
            onOpenMemoryManage = {
                onExecuteCommand("/memory")
                scope.launch { drawerState.close() }
            },
            onOpenMcpManage = {
                onExecuteCommand("/mcp")
                scope.launch { drawerState.close() }
            },
            onOpenSkillManage = {
                onExecuteCommand("/skills")
                scope.launch { drawerState.close() }
            },
            onOpenWorkflow = {
                onOpenWorkflow()
                scope.launch { drawerState.close() }
            },
            // [PLUGIN-MOD]
            onOpenPluginManage = {
                onExecuteCommand("/plugins")
                scope.launch { drawerState.close() }
            },
            pluginQuickGroups = pluginQuickGroups,
            pluginQuickBarExpanded = pluginQuickBarExpanded,
            onPluginQuickBarExpandedChange = onPluginQuickBarExpandedChange,
            onPluginQuickAction = { action ->
                scope.launch { drawerState.close() }
                onPluginQuickAction(action)
            },
            onOpenPluginById = { id ->
                scope.launch { drawerState.close() }
                onOpenPluginById(id)
            },
            onSearchMessages = { session ->
                searchSession = session
                scope.launch { drawerState.close() }
            },
            browseFileSystem = browseFileSystem,
            remoteConnection = connectedRemote,
            onOpenFileInBrowser = { path ->
                onOpenFileInBrowser(path)
                scope.launch { drawerState.close() }
            },
            onAttachToInput = { path, name ->
                onAttachToInput(path, name)
                scope.launch { drawerState.close() }
            },
            embedded = embedded
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 横屏左栏常驻且顶栏已隐藏 ≡ 按钮，禁掉抽屉滑动手势避免两套入口并存。
        gesturesEnabled = !isLandscape,
        drawerContent = {
            if (!isLandscape) {
                ModalDrawerSheet(
                    drawerContainerColor = BgPrimary,
                    modifier = Modifier.width(300.dp)
                ) { HistoryPane(embedded = false) }
            }
        }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (showHistoryPane) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(BgSecondary)
                        .navigationBarsPadding()
                ) {
                    // 需求3：左栏既可显示会话历史，也可被子页面占用。
                    if (leftPage) leftPageContent() else HistoryPane(embedded = true)
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Border)
                )
            }
        // Column 本体保持原缩进不动（其内有 360 行），仅把 fillMaxSize 换成 weight/fillMaxHeight。
        Column(
            modifier = Modifier
                .weight(if (showHistoryPane) 2f else 1f)
                .fillMaxHeight()
                // 横屏：顶栏的 windowInsets 已归零，状态栏（电池/信号那一行）改在这里统一避让，
                // 避免与 TopAppBar 内部 inset 双重偏移；竖屏维持原样由 TopAppBar 自己处理。
                .then(if (isLandscape) Modifier.statusBarsPadding() else Modifier)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // ── 需求6：顶栏/输入框各包成局部函数——竖屏按原顺序流式摆放，横屏叠进消息区悬浮。
            //    barModifier 由调用点传入：align 需要 BoxScope，只有调用处知道自己身处哪个容器。
            @Composable
            fun TopBarArea(barModifier: Modifier) {
            val modeLabel = when (uiState.agentMode) {
                "plan" -> "规划模式"
                "stock" -> "股票模式"
                "chat" -> "聊天模式"
                else -> "构建模式"
            }
            val sshConnected = remoteActiveState.state == com.wy.openx.remote.RemoteConnState.CONNECTED
            // 顶部标题 = 当前对话标题（参考 rc6：显示当前会话标题而非固定应用名）
            val currentConvTitle = remember(uiState.currentConversationId, uiState.conversations) {
                uiState.conversations.firstOrNull { it.id == uiState.currentConversationId }?.title.orEmpty()
            }
            ClaudeTopBar(
                compact = isLandscape,
                // 需求6：宽度与对齐改由调用点决定（竖屏整行、横屏悬浮右半）。
                modifier = barModifier,
                title = currentConvTitle.ifBlank { "OpenX" },
                // 【新会话默认不选模型】会话级模式下 effectiveModel 为空 = 本会话还没选模型
                // （SessionSlot 不再把空 override 回落成全局模型）。这里如实显示占位文案，
                // 而不是拿全局那家来填——那正是「串台」的另一种表现。
                subtitle = "${uiState.effectiveModel.ifBlank { "[未选择模型]" }} · $modeLabel" +
                    // 会话级模式下本会话已选定模型时标一下，否则用户会以为看到的是全局值。
                    (if (uiState.effectiveModel.isNotBlank() &&
                            (uiState.effectiveProviderId != uiState.settings.selectedProviderId ||
                            uiState.effectiveModel != uiState.settings.model)) " · 本会话" else ""),
                onMenuClick = { scope.launch { drawerState.open() } },
                // 需求5：横屏 ≡ 按钮语义 = 折叠/展开左栏（竖屏仍为打开抽屉）。
                paneVisible = showHistoryPane,
                onTogglePane = { landscapePaneCollapsed = !landscapePaneCollapsed },
                onNewChat = onNewConversation,
                onOpenTerminal = onOpenTerminal,
                onOpenQuantTrade = onOpenQuantTrade,
                onOpenBrowser = onOpenBrowser,
                onOpenGit = onOpenGit,
                sshStatus = if (sshConnected) "SSH：已连接" else null,
                sshTraffic = if (sshConnected) {
                    "↓${com.wy.openx.remote.RemoteTrafficMonitor.format(remoteTraffic.downloaded)} " +
                        "↑${com.wy.openx.remote.RemoteTrafficMonitor.format(remoteTraffic.uploaded)}"
                } else null
            )
            }

            // 竖屏：顶栏仍留在流式布局最上方（与改造前完全一致）。
            if (!isLandscape) TopBarArea(Modifier)

            // 上下文占用改由输入栏的圆形指示器承载（短按看用量、长按改阈值），
            // 故此处不再渲染顶部横条与「Token 统计」会话菜单——避免同一个信息两处出现。
            // maxCtx 仍要算：下面 ClaudeBottomBar 的 maxTokens 参数要用。
            val ctxTokens = uiState.lastInputContext
            // 【会话级模型串台修复】上限要按**本会话生效的提供商/模型**算，且必须用与
            // 自动压缩、圆环完全相同的算法（MainViewModel.contextWindowTokensFor）。
            //
            // 这里原来自己查一遍：uiState.effectiveProviderId / effectiveModel / settings
            // 全是**全局当前值**——「最后一次设置模型的会话」的值，不是本会话的。
            // 后果：在 B 会话把模型改成 1M 窗口，切回 A 会话，A 的圆环和 maxTokens
            // 都跟着变成 1M（明明 A 自己设的是 200K）。用户反馈：切会话再切回来，
            // 上下文显示被别的会话带跑。
            //
            // 单一数据源 = 同一个函数，圆环百分比、maxTokens、自动压缩阈值三者永远一致。
            val maxCtx = contextWindowTokens


            if (uiState.isSending) {
                IndeterminateLoadingBar()
            }

            // 输入框块上移成函数定义：横屏要在消息 Box 内部调用它，
            // Kotlin 局部函数不允许前向引用，故定义必须早于调用点。
            @Composable
            fun InputArea(barModifier: Modifier) {
            ClaudeBottomBar(
                modifier = barModifier,
                compact = isLandscape,
                paneNarrow = showHistoryPane,
                // 只对**用户手势**滚动淡出：程序化贴底（滚动跟随/isScrollInProgress 也会短暂置起，
                // 若不排除，每条新消息到达都会让输入框一闪一闪地淡出）。
                isScrolling = listState.isScrollInProgress && !programmaticScroll,
                prompt = uiState.prompt,
                onPromptChange = onPromptChange,
                onSend = onSend,
                onStopGeneration = onStopStreaming,
                onAttachClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                onPickImage = { imagePickerLauncher.launch("image/*") },
                onTakePhoto = onTakePhoto,
                attachments = uiState.attachments,
                onRemoveAttachment = onRemoveAttachment,
                isSending = uiState.isSending,
                onSlashCommand = onApplyQuickTask,
                onExecuteCommand = onExecuteCommand,
                slashSkills = (uiState.projectSkills + uiState.skills)
                    .filter { it.enabled && it.instructions.isNotBlank() }
                    .filter { canonicalBuiltinCommand("/${it.name}") == null }
                    .distinctBy { it.name.lowercase() }
                    .sortedBy { it.name.lowercase() }
                    .map { it.name to it.description },
                apiPresets = uiState.apiPresets,
                selectedApiPreset = uiState.settings.selectedApiPreset,
                onSwitchPreset = onSwitchPreset,
                agentMode = agentMode,
                onSetAgentMode = onSetAgentMode,
                toolPermissionMode = toolPermissionMode,
                onSetToolPermissionMode = onSetToolPermissionMode,
                reasoningEffort = reasoningEffort,
                onSetReasoningEffort = onSetReasoningEffort,
                reasoningEffortOptionsProvider = reasoningEffortOptionsProvider,
                projects = projects,
                currentProject = currentProject,
                onCreateProject = onCreateProject,
                onSelectProject = onSelectProject,
                onDeleteProject = onDeleteProject,
                externalWorkspaces = externalWorkspaces,
                onAddExternalWorkspace = onAddExternalWorkspace,
                maxTokens = maxCtx,
                messageQueue = uiState.messageQueue,
                queuedTexts = uiState.messageQueue.mapNotNull { qid ->
                    uiState.messages.firstOrNull { it.id == qid }?.content
                },
                onCancelQueuedMessage = onCancelQueuedMessage,
                onClearMessageQueue = onClearMessageQueue,                onSendQueuedMessage = onSendQueuedMessage,
                tasks = uiState.tasks,
                tasksExpanded = uiState.tasksExpanded,
                onToggleTasksExpanded = onToggleTasksExpanded,
                onUpdateTaskStatus = onUpdateTaskStatus,
                onRemoveTask = onRemoveTask,
                contextUsedTokens = contextUsedTokens,
                contextWindowTokens = contextWindowTokens,
                onUpdatePresetMeta = onUpdatePresetMeta,
                separateSendButton = separateSendButton,
                providers = providers,
                settings = uiState.settings,
                effectiveProviderId = uiState.effectiveProviderId,
                effectiveModel = uiState.effectiveModel,
                providerModelMetadata = providerModelMetadata,
                onSelectProviderModel = onSelectProviderModel,
                currentModelMeta = currentModelMeta,
                currentModelName = currentModelName,
                onSaveCurrentModelMeta = onSaveCurrentModelMeta,
                onAutoFetchCurrentModelMeta = onAutoFetchCurrentModelMeta
            )
            }

            // 需求6：横屏悬浮输入框的实测高度（px）。它会随附件行/任务面板/队列面板长高，
            // 不能写死，量出来后换算成 contentPadding 给消息区让位。
            var floatingInputHeightPx by remember { mutableIntStateOf(0) }
            // 需求9b：悬浮顶栏的实测高度（px）。顶栏高度改动后消息区让位自动跟随，
            // 不必再同步修改写死的 contentPadding 数字。
            var topBarHeightPx by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            // 顶栏/输入框悬浮宽度（**相对右栏**，不是相对全屏）：左栏可见时右侧留出一段
            // 空隙透出下方滚动的消息，左栏折叠后聊天页全屏则占满。
            //
            // 0.6 会挤坏底部操作行：那行有 10 个 28dp 图标（含语音键）约需 324dp，
            // 而 0.6 × 右栏(2/3 屏宽) 实际只有全屏的 40%，窄屏横屏下直接溢出——
            // 发送键被顶到圆角外，看起来「变小了」。0.8 留出余量。
            val barOverlayWidth = if (showHistoryPane) 0.8f else 1f
            val focusManager = LocalFocusManager.current
            val isSendingNow = rememberUpdatedState(uiState.isSending)
            Box(modifier = Modifier.fillMaxSize().weight(1f).onReadDragStart {
                // 流式期间用户只要往下拖（回看上文）就立刻停掉外层贴底，
                // 不等 firstVisibleItem 变化，避免手指刚按下那一两帧被自动跟随拽回底部。
                if (isSendingNow.value) scrollState.userPulledUp = true
            }) {
                // 请求失败改为对话流内联系统消息（见 MainViewModel），不再有顶部悬浮横幅
                if (displayMessages.isEmpty()) {
                    ClaudeEmptyState(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { focusManager.clearFocus() },
                        // 需求6：横屏时顶栏/输入框悬浮在消息区之上，消息区本身铺满整个右栏，
                        // 用 contentPadding 让出两者占位高度，避免首末条消息被永久遮挡。
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            // 需求9b：横屏顶栏悬浮在消息区上方，用实测高度让位（含 8dp 呼吸）。
                            top = if (isLandscape)
                                with(density) { topBarHeightPx.toDp() } + 8.dp
                            else 8.dp,
                            bottom = if (isLandscape)
                                with(density) { floatingInputHeightPx.toDp() } + 8.dp
                            else 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // ── 单一平铺列表 折叠原文 / 压缩卡 / 保留尾部 ──
                        // 位置全部由库里的 `seq`（会话内单调显示序）决定，UI 不再自己排序。
                        items(
                            displayGroups,
                            key = { it.key },
                            contentType = {
                                when (it) {
                                    is MsgGroup.Single -> "message"
                                    is MsgGroup.Thinking -> "thinking"
                                    else -> "toolGroup"
                                }
                            }
                        ) { group ->
                            val ctx = LocalContext.current
                            when (group) {
                                is MsgGroup.Single -> {
                                    val msg = group.msg
                                    // 压缩锚点行本身不渲染成气泡——它的位置就是压缩卡的位置。
                                    // 卡片上方那条分割线由 [CompactionSummaryBlock] 自带。
                                    // 注意读的是 **visibleMessages 侧的全量配对表**：二次压缩后
                                    // 上一次的 marker 已折进折叠区，卡片仍要显示在原处。
                                    compactionSummaryByMarker[msg.id]?.let { (_, text) ->
                                        CompactionSummaryBlock(summary = text)
                                        return@items
                                    }
                                    // marker **无条件**不渲染
                                    // 它的作用只是给紧随其后的摘要当位置锚点。若配对失败（摘要进了折叠区、
                                    // 或二次压缩后只剩 marker），落到下面就会被当成普通气泡，
                                    // 用户看到一条内容为「[上下文已压缩]」的怪消息。
                                    if (msg.content == COMPACTION_MARKER) return@items
                                    // 升级前的旧摘要行（`[对话摘要]…`）同样渲染成卡片，
                                    // 免得老会话里出现「一张卡 + 一行裸文字」两种样式。
                                    if (msg.content.startsWith(SUMMARY_PREFIX)) {
                                        CompactionSummaryBlock(
                                            summary = msg.content.removePrefix(SUMMARY_PREFIX).trim()
                                        )
                                        return@items
                                    }
                                    // 压缩前的历史原文：**用普通消息气泡渲染**，与压缩前完全一致
                                    // 展示层根本不区分 isCompacted）。
                                    // 压缩的唯一可见产物是那条「上下文已压缩」卡片，**消息结构不变**：
                                    // 上面是压缩前的老消息、中间一条横线 + 卡片、下面是压缩后的新对话。
                                    //
                                    // 刻意不再有「已折叠」专用样式，也不给消息挂「从这轮重新开始」按钮：
                                    // 那会让被压缩的消息变成另一种东西，等于压缩一次就把历史改了样。
                                    // （它们只是 folded=1、不参与模型请求，渲染路径与普通消息完全相同。）
                                    ClaudeMessageBubble(
                                        msg = msg.toLegacy(),
                                        msgId = msg.id,
                                        highlighted = msg.id == highlightMessageId,
                                        streaming = msg.id == streamingMsgId,
                                        // 横屏右栏只有 2/3 屏宽、可视高度又小，字号整体降一档，
                                        // 避免少量文字就把消息区撑满（用户设置仍生效，只是封顶下调）。
                                        fontSize = when (uiState.settings.fontSize) {
                                            "small" -> if (isLandscape) 12.sp else 13.sp
                                            "large" -> if (isLandscape) 15.sp else 17.sp
                                            // 自定义档是用户精确调过的值，不再替他降档
                                            "custom" -> uiState.settings.fontSizeCustomSp.sp
                                            else -> if (isLandscape) 13.sp else 14.sp
                                        },
                                        // 外观设置里的自定义聊天文本色（auto 时为 null = 跟随主题）
                                        customTextColor = chatCustomTextColor(uiState.settings),
                                        showTimestamp = uiState.settings.showTimestamp,
                                        modelName = uiState.effectiveModel,
                                        scrollState = scrollState,
                                        onResend = if (msg.role == MessageRole.USER) {{ onResendMessage(msg) }} else null,
                                        onCopy = {
                                            val copyText = if (msg.role == MessageRole.USER) {
                                                msg.content
                                            } else {
                                                ChatUtils.removeThinkingContent(msg.content)
                                            }
                                            val clip = ClipData.newPlainText("msg", copyText)
                                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(clip)
                                            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onEdit = if (msg.role == MessageRole.USER && onEditMessage != null) {{ editingMessage = msg }} else null,
                                        onRegenerate = if (msg.role == MessageRole.ASSISTANT && onRegenerateMessage != null) {{ onRegenerateMessage() }} else null,
                                        onDelete = if (msg.role != MessageRole.SYSTEM) {{ onDeleteMessage(msg.id) }} else null,
                                        // 从此处新建分支：仅对已落库的非排队消息显示
                                        onFork = onForkMessage?.takeIf {
                                            (msg.role == MessageRole.ASSISTANT || msg.role == MessageRole.USER) && msg.id != streamingMsgId && !msg.queued
                                        }?.let { cb -> { cb(msg) } },
                                        onRewind = if (msg.role == MessageRole.USER) {{ rewindTarget = msg }} else null,
                                        // 「文件:行号」链接 → 打开内置编辑器并定位该行
                                        onOpenFileLink = { path, line ->
                                            chatLinkTarget = path to line
                                        }
                                    )
                                    // 排队中的消息：AI 还在跑，本条尚未发出去。
                                    // 必须明确标出来，否则用户以为消息丢了（历史行为：入队不建气泡，
                                    // 直到消费才出现；按停止则彻底消失）。
                                    if (msg.queued) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 2.dp),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "排队中，等待发送",
                                                color = TextSecondary,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                                is MsgGroup.Thinking -> {
ThinkingBlock(
                                        content = group.content,
                                        msgId = group.msgId,
                                        streaming = group.streaming,
                                        timing = group.timing,
                                        done = group.done,
                                        alwaysExpanded = uiState.settings.thinkingAlwaysExpanded,
                                        fontSize = when (uiState.settings.fontSize) {
                                            "small" -> 11.sp
                                            "large" -> 15.sp
                                            "custom" -> uiState.settings.fontSizeCustomSp.sp
                                            else -> 13.sp
                                        },
                                        scrollState = scrollState,
                                        parentListState = listState
                                    )
                                }
                                is MsgGroup.ToolCalls -> {
                                    ToolCallGroup(
                                        items = group.items,
                                        // 横屏右栏只有 2/3 屏宽、可视高度又小，字号整体降一档，
                                        // 避免少量文字就把消息区撑满（用户设置仍生效，只是封顶下调）。
                                        fontSize = when (uiState.settings.fontSize) {
                                            "small" -> if (isLandscape) 12.sp else 13.sp
                                            "large" -> if (isLandscape) 15.sp else 17.sp
                                            "custom" -> uiState.settings.fontSizeCustomSp.sp
                                            else -> if (isLandscape) 13.sp else 14.sp
                                        },
                                        showDetails = uiState.showDetails,
                                        onDeleteGroup = onDeleteMessage.takeIf { group.items.isNotEmpty() }?.let { { onDeleteMessage(group.items.first().id) } },
                                        onDeleteItem = onDeleteMessage.takeIf { group.items.isNotEmpty() }?.let { { item -> onDeleteMessage(item.id) } },
                                        isStillSending = uiState.isSending,
                                        scrollState = scrollState
                                    )
                                }
                        }
                    }
                }
                }
                // 快速到底按钮防抖：isAtBottom 在流式贴底校准期间会在 true/false 间逐帧抖动
                //（内容每 ~150ms 长一截，校准每 ~80ms 才修正一次，两下交替 = 按钮每次校准都
                // 闪一下，120Hz 平滑后尤其刺眼）。这里做「稳定 150ms 才翻转」：只有真离开底部
                //（翻历史）按钮才出现；流式贴底造成的瞬态抖动被滤掉，按钮全程不闪。
                var showJumpToLatest by remember { mutableStateOf(false) }
                LaunchedEffect(isAtBottom) {
                    if (isAtBottom) {
                        showJumpToLatest = false
                    } else {
                        delay(150)
                        if (!isAtBottom) showJumpToLatest = true
                    }
                }
                if (showJumpToLatest && uiState.messages.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            // 需求8：抬到悬浮输入框（zIndex 2f）之上，保证任何情况下都可见可点。
                            .zIndex(3f)
                            // 横屏输入框悬浮在右下角，按钮上移到它正上方——高度取实测值
                            //（floatingInputHeightPx 会随附件行/任务面板/队列面板长高，不能写死）；
                            // 竖屏输入框是流式末项、消息 Box 底边已贴着它，维持原 4dp。
                            .padding(
                                end = 8.dp,
                                bottom = if (isLandscape)
                                    with(density) { floatingInputHeightPx.toDp() } + 4.dp
                                else 4.dp
                            )
                            .size(36.dp)
                            .clip(RoundedCornerShape(18.dp)).background(Accent.copy(alpha = 0.85f))
                            .clickable {
                                // 用 scrollToItem 瞬移，不 animateScrollToItem。
                                // 动画滚动是**逐帧**从当前位置挪到目标项，中间经过的每一项都要
                                // 组合 + 解析 markdown + 测量高度，成本正比于**跨越的 item 数**
                                // 而非目标距离。长会话（实测恢复后约 1945 组）里从最前面点到最
                                // 后面，一帧内要过掉约 1940 个 item，主线程直接被占满——用户
                                // 反馈的「点回到底部卡一下，过一会才滚过去」就是这个。
                                // 瞬移没有中间过程，瞬时到底、零卡顿。
                                scrollState.userPulledUp = false
                                scope.launch { listState.scrollToItem(lastContentIndex(), Int.MAX_VALUE) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("↓", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                // Toast 悬浮在消息区底部，不参与 Column 布局。
                // 需求9c：横屏输入框悬浮在右下角（zIndex 2f），Toast 水平居中会横跨到输入框
                // 占据的右半区、被它压住 → 横屏时 bottom 让出输入框实测高度，并抬到 zIndex 3f。
                // 竖屏输入框是流式末项、消息 Box 底边贴着它，维持原 12dp 即可。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(3f)
                        .padding(
                            bottom = if (isLandscape)
                                with(density) { floatingInputHeightPx.toDp() } + 12.dp
                            else 12.dp
                        )
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = uiState.toast != null,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 }
                    ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF333333),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            uiState.toast ?: "",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
                }
                // ── P2c：跨会话「等待你」横幅 ──
                // 某个**非前台**会话卡在「需要确认 / 需要回答」时浮在聊天页顶部。
                // 「忽略」只收起提醒（会话继续等、侧边栏 🟡 不消失）；
                // 「前往处理」切过去由用户亲自决定——绝不在横幅上替用户批准/拒绝。
                // 用 contentAlignment（同需求6 的作用域原因）；zIndex 4f 压在输入框(2f)/顶栏之上。
                Box(
                    modifier = Modifier.fillMaxSize().zIndex(4f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    // 不用 AnimatedVisibility：本处同时存在外层 ColumnScope 与内层 BoxScope
                    // 两个隐式接收者，它两边都有扩展函数，会和之前 `align` 一样解析错重载。
                    // 横幅本身带 fadeIn 需求不强，直接条件渲染。
                    val b = uiState.awaitingBanner
                    if (b != null) {
                            Column(
                                modifier = Modifier
                                    // 横屏顶栏也是悬浮在消息区上方（占右栏右半、宽 0.6），
                                    // 居中的横幅会压到它的左半。按**实测高度**下推，
                                    // 与 Toast 按 floatingInputHeightPx 上推同一套路。
                                    .padding(
                                        start = 10.dp,
                                        top = if (isLandscape)
                                            with(density) { topBarHeightPx.toDp() } + 6.dp
                                        else 6.dp,
                                        end = 10.dp
                                    )
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF2A2A2E))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier.size(8.dp).clip(CircleShape)
                                            .background(Warning)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "会话「${b.title}」需要处理",
                                        color = Color.White, fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (b.summary.isNotEmpty()) {
                                    Text(
                                        b.summary, color = Color(0xFFBFBFBF), fontSize = 11.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        "忽略",
                                        color = Color(0xFF9A9A9A), fontSize = 12.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onDismissAwaitingBanner() }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "前往处理 →",
                                        color = Warning, fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onSwitchConversation(b.sessionId) }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                    }
                }

                // ── 需求6：横屏把顶栏与输入框叠在消息区上方 ──
                // 两者只占右栏右半（左栏被折叠时占满），空白侧因此透出下方滚动的聊天消息。
                // zIndex 保证画在消息之上；TopBar 的 containerColor 本就是 Transparent。
                if (isLandscape) {
                    // 用 contentAlignment 而非 Modifier.align：此处同时存在外层 ColumnScope
                    // 与消息 Box 的 BoxScope 两个隐式接收者，align 会被解析到错误的重载。
                    // 定位层不设 clickable/pointerInput，Compose 下不消费触摸，消息区手势正常。
                    Box(
                        modifier = Modifier.fillMaxSize().zIndex(2f),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        TopBarArea(
                            Modifier
                                .fillMaxWidth(barOverlayWidth)
                                .onSizeChanged { topBarHeightPx = it.height }
                        )
                    }
                    Box(
                        modifier = Modifier.fillMaxSize().zIndex(2f),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(barOverlayWidth)
                                // 思考条并入被测高的这一列：floatingInputHeightPx 随之含思考条高度，
                                // ↓按钮 / Toast / 消息 contentPadding 会自动整体让到思考条之上。
                                .onSizeChanged { floatingInputHeightPx = it.height }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // 请求失败错误卡片：输入框上方，折叠+复制，直到下次发送清空。
                                if (uiState.lastRequestError != null) {
                                    RequestErrorCard(
                                        message = uiState.lastRequestError.orEmpty(),
                                        compact = true,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                }
                                if (uiState.isSending || uiState.isCompacting) {
                                    ClaudeThinkingIndicator(
                                        toolName = currentToolName,
                                        reconnecting = uiState.reconnecting,
                                        reconnectAttempt = uiState.reconnectAttempt,
                                        reconnectMax = uiState.reconnectMax,
                                        startedAt = uiState.thinkingStartedAt,
                                        compacting = uiState.isCompacting,
                                        compact = true
                                    )
                                }
                                InputArea(Modifier)
                            }
                        }
                    }
                }
            }

            // 任务面板：全部完成/取消后**自动折叠**。
            //
            // ⚠ 这里**刻意不再「有未完成任务就自动展开」**。
            // 用户要求「创建任务后自动折叠」：AI 一建任务面板就展开会挤占输入区，
            // 而且每完成一步清单更新都会再撑开一次，很打扰。
            // 实现（ TodoDashboardBar）默认就是**折叠**的
            //（`mutableStateOf(false)`），且没有自动展开逻辑，本次与之对齐。
            //
            // 只保留「全做完 → 收起」这一半：那是用户明确不需要再看的时候，
            // 自动收起不会打断任何人（反过来自动展开会打断）。
            LaunchedEffect(uiState.tasks) {
                val allFinished = uiState.tasks.isNotEmpty() && uiState.tasks.all {
                    it.status == TaskStatus.COMPLETED || it.status == TaskStatus.CANCELLED
                }
                if (allFinished && uiState.tasksExpanded) onToggleTasksExpanded()
            }

            // 思考指示器固定在输入框上方（不移入消息滚动列表）。
            // isCompacting 单独放行：发送结束后的自动压缩在 finally 里运行，此时 isSending 已复位为
            // false，若只看 isSending 用户就看不到「正在压缩上下文」的提示。
            // 竖屏：思考条留在流式布局（输入框上方）。横屏：改由下方悬浮层承载（否则落在消息 Box 之下、
            // 显示到聊天页左下角），故这里加 !isLandscape 限定。
            if (!isLandscape && uiState.lastRequestError != null) {
                RequestErrorCard(
                    message = uiState.lastRequestError.orEmpty(),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            if (!isLandscape && (uiState.isSending || uiState.isCompacting)) {
                ClaudeThinkingIndicator(
                    toolName = currentToolName,
                    reconnecting = uiState.reconnecting,
                    reconnectAttempt = uiState.reconnectAttempt,
                    reconnectMax = uiState.reconnectMax,
                    startedAt = uiState.thinkingStartedAt,
                    compacting = uiState.isCompacting
                )
            }

            // 聊天里点「文件:行号」链接打开的文件：定位到目标行。
            chatLinkTarget?.let { (target, line) ->
                FileViewerSheet(
                    path = target,
                    name = target.substringAfterLast('/'),
                    browseFileSystem = browseFileSystem,
                    onDismiss = { chatLinkTarget = null },
                    onSaved = { },
                    initialLine = line
                )
            }

            // AI 提问面板：内联在输入框上方
            pendingQuestion?.let { question ->
                if (question.items.isNotEmpty()) {
                    MultiQuestionPanel(
                        info = question,
                        onSubmit = onSubmitAnswer,
                        onCancel = onCancelQuestion
                    )
                } else {
                var customAnswer by remember { mutableStateOf("") }
                var usedSet by remember(question) { mutableStateOf(setOf<Int>()) }
                var questionExpanded by remember { mutableStateOf(true) }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Panel,
                    border = BorderStroke(1.dp, Border),
                    shadowElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // 标题：点击折叠/展开
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { questionExpanded = !questionExpanded },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("AI 向你提问", fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
                            Icon(
                                if (questionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (questionExpanded) "收起" else "展开",
                                tint = TextSecondary
                            )
                        }
                        if (questionExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 480.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(question.question, color = TextPrimary, fontSize = 15.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                if (question.options.isNotEmpty()) {
                                    if (question.multiSelect) {
                                        Text("可多选：", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        question.options.forEachIndexed { index, option ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().selectable(
                                                    selected = index in usedSet,
                                                    onClick = {
                                                        usedSet = if (index in usedSet)
                                                            usedSet - index else usedSet + index
                                                    },
                                                    role = Role.Checkbox
                                                ).padding(vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = index in usedSet,
                                                    onCheckedChange = { checked ->
                                                        usedSet = if (checked)
                                                            usedSet + index else usedSet - index
                                                    },
                                                    colors = CheckboxDefaults.colors(checkedColor = Accent)
                                                )
                                                Text("${index + 1}. $option", fontSize = 14.sp, color = TextPrimary)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                    } else {
                                        Text("选择一项：", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        question.options.forEachIndexed { index, option ->
                                            OutlinedButton(
                                                onClick = { onSubmitAnswer(option) },
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                                                border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                            ) {
                                                Text("${index + 1}. $option", fontSize = 14.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                    }
                                }
                                if (question.allowCustom) {
                                    OutlinedTextField(
                                        value = customAnswer,
                                        onValueChange = { customAnswer = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text("输入你的答案...", color = TextSecondary.copy(alpha = 0.5f)) },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Accent,
                                            unfocusedBorderColor = Border,
                                            cursorColor = Accent,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        maxLines = 4,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                        keyboardActions = KeyboardActions(onSend = { if (customAnswer.isNotBlank()) onSubmitAnswer(customAnswer) })
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = onCancelQuestion) { Text("跳过", color = TextSecondary) }
                                    if (question.multiSelect || question.allowCustom) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                val selected = usedSet.sorted().map { question.options[it] }
                                                val answer = buildString {
                                                    if (selected.isNotEmpty()) append(selected.joinToString("、"))
                                                    if (customAnswer.isNotBlank()) {
                                                        if (isNotEmpty()) append("；")
                                                        append("自定义:$customAnswer")
                                                    }
                                                }
                                                if (answer.isNotBlank()) onSubmitAnswer(answer)
                                            },
                                            enabled = (question.multiSelect && usedSet.isNotEmpty()) || customAnswer.isNotBlank(),
                                            colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = TextPrimary),
                                            shape = RoundedCornerShape(10.dp)
                                        ) { Text("提交") }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
            // 竖屏：输入框回到流式布局末尾（与改造前一致）。
            if (!isLandscape) InputArea(Modifier)
        }
        }
    }


    // 并行执行批内工具时频繁撞限流（429）：这不是「出错」，而是「当前并发度超过了
    // 供应商容忍度」。给一键切回串行的入口，免得用户为关一个开关跑去设置页翻三级。
    if (uiState.rateLimitWarning) {
        AlertDialog(
            onDismissRequest = { onDismissRateLimitWarning() },
            title = { Text("频繁触发限流", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text(
                    "并行执行批内工具时多次返回 429（请求过于频繁）。改为串行可降低瞬时并发、" +
                        "减少被限流，代价是同一批工具的耗时变长。\n\n" +
                        "选「保持并行」后，本会话不再提示。",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { onDisableParallelToolCalls() }) {
                    Text("改为串行", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { onKeepParallel() }) {
                    Text("保持并行", color = TextSecondary)
                }
            },
            containerColor = Panel
        )
    }

    showArchiveConfirmDialog?.let { convId ->
        AlertDialog(
            onDismissRequest = { showArchiveConfirmDialog = null },
            title = { Text("确认归档", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("归档后此会话将从侧边栏移入「历史」，数据保留，可随时取消归档。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onToggleArchive(convId, true)
                    showArchiveConfirmDialog = null
                }) { Text("确认归档", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveConfirmDialog = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }

    showDeleteConversationDialog?.let { convId ->
        AlertDialog(
            onDismissRequest = { showDeleteConversationDialog = null },
            title = { Text("确认删除", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("确定要删除此会话吗？此操作不可撤销。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConversation(convId)
                    showDeleteConversationDialog = null
                }) { Text("确认删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConversationDialog = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
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
                        onRename(pendingRenameSessionId ?: uiState.currentConversationId, renameText)
                        renameText = ""
                        showRenameDialog = false
                    }
                }) { Text("确认", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false; renameText = "" }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }

    searchSession?.let { session ->
        MessageSearchDialog(
            sessionTitle = session.title,
            onSearch = { query, callback -> onSearchConversationMessages(session.id, query, callback) },
            onJump = { messageId ->
                onJumpToMessage(session.id, messageId)
                searchSession = null
            },
            onDismiss = { searchSession = null }
        )
    }

    editingMessage?.let { target ->
        MessageEditDialog(
            initialText = target.content,
            onConfirm = { newContent ->
                editingMessage = null
                onEditMessage?.invoke(target.id, newContent)
            },
            onDismiss = { editingMessage = null }
        )
    }

    rewindTarget?.let { target ->
        RewindSheet(
            promptSnippet = target.content.take(20),
            onOption = { opt ->
                onRewindMessage(target.id, opt)
                rewindTarget = null
            },
            onDismiss = { rewindTarget = null }
        )
    }

    pendingToolCall?.let { call ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("工具调用确认", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                // 工具参数可能很长（多文件批量编辑的 JSON 动辄几十行），不限高的话
                // AlertDialog 会被内容撑满整屏。这里封顶半屏，超出部分内部滚动。
                Column(
                    modifier = Modifier
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp / 2).dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Surface(
                        color = PanelAlt,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(call.description, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (call.actionLine.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("AI 请求${call.actionLine}", color = TextPrimary, fontSize = 13.sp)
                            }
                            call.extras.forEach { line ->
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(line, color = TextSecondary, fontSize = 12.sp)
                            }
                            // 没生成摘要的工具（未知工具 / 参数结构特殊）仍然把参数摊开：宁可难看也别丢信息
                            if (call.actionLine.isBlank() && call.arguments.isNotBlank() && call.arguments != "{}") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("参数：", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(call.arguments, color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                    if (call.alwaysAllowHint.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(call.alwaysAllowHint, color = TextSecondary, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onConfirmToolCall?.invoke(false) }
                    ) { Text("拒绝", color = TextSecondary) }
                    TextButton(
                        onClick = { onAlwaysAllowTool?.invoke(call.toolName) }
                    ) { Text("始终允许", color = TextSecondary) }
                    Button(
                        onClick = { onConfirmToolCall?.invoke(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = TextPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("允许") }
                }
            },
            containerColor = Panel
        )
    }

    pendingTerminalConfirm?.let { ts ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("危险命令确认", fontWeight = FontWeight.Bold, color = Color(0xFFEF5350)) },
            text = {
                // 同上：命令或路径可能很长，封顶半屏并在内部滚动
                Column(
                    modifier = Modifier
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp / 2).dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(ts.description, color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = PanelAlt,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("AI 请求执行的命令：", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(ts.arguments, color = Color(0xFFEF5350), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onRespondTerminalConfirm(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350), contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("仍要执行") }
            },
            dismissButton = {
                TextButton(
                    onClick = { onRespondTerminalConfirm(false) }
                ) { Text("拒绝", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudeTopBar(
    title: String,
    subtitle: String,
    onMenuClick: () -> Unit,
    onNewChat: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenQuantTrade: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    /** 横屏两栏压缩模式：压低标题栏高度、≡ 改为左栏折叠开关（方案 T1 + 需求5）。 */
    compact: Boolean = false,
    /**
     * 宽度与对齐由调用方决定——调用点位于右栏 Column 作用域内，才能用 ColumnScope.align。
     * 横屏且左栏可见时传 `Modifier.fillMaxWidth(0.5f).align(Alignment.End)`：顶栏只占右栏右半，
     * 左半腾出来（与左栏聊天历史在视觉上对齐），标题和按钮挤在右半显示。
     */
    modifier: Modifier = Modifier,
    /** 需求5：左栏当前是否可见（决定折叠按钮的图标方向）。 */
    paneVisible: Boolean = false,
    /** 需求5：横屏点击 ≡ / ◀ 时切换左栏折叠态。 */
    onTogglePane: () -> Unit = {},
    /** SSH 已连接时在主标题下追加绿色状态提示。 */
    sshStatus: String? = null,
    sshTraffic: String? = null,
) {
    // 标题/模型名过长时不再只能省略号截断，改为可左右拖动查看全文。
    // 切换会话后必须回到开头，否则新标题一进来就停在上一轮的偏移位置。
    val titleScroll = rememberScrollState()
    val subtitleScroll = rememberScrollState()
    LaunchedEffect(title) { titleScroll.scrollTo(0) }
    LaunchedEffect(subtitle) { subtitleScroll.scrollTo(0) }
    TopAppBar(
        // 横屏可视高度极有限：内容区给 56dp（Material 默认 64dp）。
        // 不能再压到 44dp——标题 13sp + 副标题 10sp 两行文字加行高约需 34dp，
        // 与 36dp 的按钮触摸尺寸并存时 44dp 会把两行文字上下各裁掉一半。
        modifier = modifier.then(
            if (compact) Modifier.height(if (sshStatus != null) 88.dp else 56.dp)
            else if (sshStatus != null) Modifier.height(96.dp)
            else Modifier
        ),
        // 关键修复：把状态栏 inset 归零。TopAppBar 默认 windowInsets 含 statusBars(~24dp)，
        // 会把上面的 height(44dp) 吃掉 24dp、只剩 20dp，48dp 的 IconButton 直接被裁没——
        // 这正是「终端/Git 按钮看不到」的根因。状态栏避让改由右栏 Column 统一 statusBarsPadding。
        windowInsets = if (compact) WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
                       else TopAppBarDefaults.windowInsets,
        title = {
            Column {
                Text(
                    text = title,
                    modifier = Modifier.horizontalScroll(titleScroll),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = if (compact) 13.sp else 16.sp,
                    lineHeight = if (compact) 16.sp else 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        modifier = Modifier.horizontalScroll(subtitleScroll),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = if (compact) 10.sp else 12.sp,
                        lineHeight = if (compact) 12.sp else 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (sshStatus != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (compact) 4.dp else 5.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = sshStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = if (compact) 8.sp else 9.sp,
                                lineHeight = if (compact) 10.sp else 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (sshTraffic != null) {
                            Text(
                                text = sshTraffic,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = if (compact) 8.sp else 9.sp,
                                lineHeight = if (compact) 10.sp else 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            // 需求5：横屏左栏常驻，≡ 不再是「打开抽屉」而是「折叠/展开左栏」的开关——
            //   展开态显示 ◀（点击收起左栏，聊天页全屏）；折叠态显示 ≡（点击恢复两栏）。
            // 竖屏维持原样：≡ 打开抽屉。
            if (compact) {
                IconButton(onClick = onTogglePane) {
                    Icon(
                        if (paneVisible) Icons.Default.KeyboardArrowLeft else Icons.Default.Menu,
                        contentDescription = if (paneVisible) "折叠聊天历史" else "展开聊天历史",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "菜单", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            // 操作按钮排：固定只显示 3 个按钮宽（每个 IconButton 48dp），其余靠横向滑动查看。
            // 顺序：终端 → Git → 新建会话 → 浏览器 → 量化。
            Row(
                modifier = Modifier
                    // 横屏按钮触摸尺寸压到 36dp，整排 3 个宽 = 108dp（竖屏维持 3 × 48 = 144dp）
                    .width(if (compact) 108.dp else 144.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    modifier = if (compact) Modifier.size(36.dp) else Modifier,
                    onClick = onOpenTerminal
                ) {
                    Text(">_", fontSize = if (compact) 13.sp else 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
                IconButton(
                    modifier = if (compact) Modifier.size(36.dp) else Modifier,
                    onClick = onOpenGit
                ) {
                    Icon(Icons.Default.AccountTree, contentDescription = "Git", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    modifier = if (compact) Modifier.size(36.dp) else Modifier,
                    onClick = onNewChat
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新建会话", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    modifier = if (compact) Modifier.size(36.dp) else Modifier,
                    onClick = onOpenBrowser
                ) {
                    Icon(Icons.Default.Public, contentDescription = "浏览器", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    modifier = if (compact) Modifier.size(36.dp) else Modifier,
                    onClick = onOpenQuantTrade
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = "量化交易",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/** `/` 菜单项：内置命令与技能共用同一套渲染。 */
private data class SlashMenuEntry(val cmd: String, val desc: String, val isSkill: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudeBottomBar(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit = {},
    onAttachClick: () -> Unit,
    onTakePhoto: () -> Unit = {},
    attachments: List<com.wy.openx.ui.state.ChatAttachment> = emptyList(),
    onRemoveAttachment: (Uri) -> Unit = {},
    isSending: Boolean = false,
    onSlashCommand: (String) -> Unit = {},
    onExecuteCommand: (String) -> Unit = {},
    /** 已启用技能：名称 to 描述，并入 `/` 菜单作为可触发命令。 */
    slashSkills: List<Pair<String, String>> = emptyList(),
    onPickImage: () -> Unit = {},
    apiPresets: List<ApiPreset> = emptyList(),
    selectedApiPreset: String = "",
    onSwitchPreset: (ApiPreset) -> Unit = {},
    agentMode: String = "build",
    onSetAgentMode: (String) -> Unit = {},
    toolPermissionMode: String = "allow",
    onSetToolPermissionMode: (String) -> Unit = {},
    reasoningEffort: String = "default",
    onSetReasoningEffort: (String) -> Unit = {},
    /** 见 [AgentConsoleScreen] 同名参数：当前模型支持的思考强度档位，null = 全量。 */
    reasoningEffortOptionsProvider: () -> List<String>? = { null },
    projects: List<String> = emptyList(),
    currentProject: String = "",
    onCreateProject: (String) -> Unit = {},
    onSelectProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
    externalWorkspaces: List<com.wy.openx.data.model.ExternalWorkspace> = emptyList(),
    onAddExternalWorkspace: (String) -> Unit = {},
    maxTokens: Int = 4096,
    messageQueue: List<Long> = emptyList(),
    /** 与 [messageQueue] 一一对应的排队消息正文（由调用方从消息列表解析出）。 */
    queuedTexts: List<String> = emptyList(),
    onCancelQueuedMessage: (Int) -> Unit = {},
    onClearMessageQueue: () -> Unit = {},
    onSendQueuedMessage: (Int) -> Unit = {},
    onSendAllQueuedMessages: () -> Unit = {},
    tasks: List<TaskItem> = emptyList(),
    tasksExpanded: Boolean = true,
    onToggleTasksExpanded: () -> Unit = {},
    onUpdateTaskStatus: (Long, TaskStatus) -> Unit = { _, _ -> },
    onRemoveTask: (Long) -> Unit = {},
    /** 上下文用量圆环：当前上下文 token 数与窗口阈值。 */
    contextUsedTokens: Int = 0,
    contextWindowTokens: Int = 0,
    /** 长按圆环 → 模型设置抽屉保存（模型名 / 输入窗口 / 输出窗口），复用设置页同一套逻辑。 */
    onUpdatePresetMeta: (String, String, Long?, Long?) -> Unit = { _, _, _, _ -> },
    /** 偏好设置「使用独立的发送按钮」：开启后回车换行、须点发送按钮。 */
    separateSendButton: Boolean = false,
    // ===== 提供商独立管理（聊天页模型选择列表） =====
    providers: List<com.wy.openx.data.model.AIProviderConfig> = emptyList(),
    settings: com.wy.openx.data.model.AppSettings = com.wy.openx.data.model.AppSettings(),
    /**
     * 【会话级模型】本会话生效的提供商 id / 模型名。
     * 空串 = 未传，退回 `settings` 里的全局值（保证其他调用方行为不变）。
     */
    effectiveProviderId: String = "",
    effectiveModel: String = "",
    providerModelMetadata: Map<String, com.wy.openx.data.model.ModelMetadata> = emptyMap(),
    onSelectProviderModel: (String, String) -> Unit = { _, _ -> },
    /** 当前模型元数据（圆环长按弹窗回填用）。 */
    currentModelMeta: com.wy.openx.data.model.ModelMetadata? = null,
    /** 当前模型显示名（圆环长按弹窗标题）。 */
    currentModelName: String = "",
    /** 保存当前模型窗口配置（写入 provider 体系，与设置页同一份数据）。 */
    onSaveCurrentModelMeta: (Long?, Long?) -> Unit = { _, _ -> },
    /** 自动获取当前模型窗口（models.dev）。 */
    onAutoFetchCurrentModelMeta: ((Long?, Long?) -> Unit) -> Unit = { cb -> cb(null, null) },
    /** 横屏两栏压缩模式：压低输入框高度（详见 横屏两栏设计方案.md §4.3）。 */
    compact: Boolean = false,
    /** 需求5：横屏且左栏可见时才做「缩进 + 靠右」；左栏折叠后恢复全宽。 */
    paneNarrow: Boolean = false,
    /** 需求6：横屏悬浮时由调用点决定宽度与对齐；竖屏传 Modifier 走原流式布局。 */
    modifier: Modifier = Modifier,
    /** 消息列表正在滚动时输入框内容整体淡出到 40%，停止滚动恢复 */
    isScrolling: Boolean = false,
) {
    var showFullscreen by remember { mutableStateOf(false) }
    // 长按上下文圆环 → 阈值编辑弹窗
    var showContextThresholdDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var showModelMenu by remember { mutableStateOf(false) }
    var showToolsMenu by remember { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
    var showPermMenu by remember { mutableStateOf(false) }
    var showEffortMenu by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<String?>(null) }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onAddExternalWorkspace(treeUriToHostPath(uri).orEmpty())
    }
    val canSend = prompt.isNotBlank() || attachments.isNotEmpty()
    val isProcessing = isSending && !canSend

    // 消息列表滚动时输入框淡出（同 AiCode ChatInputBar：无真模糊，纯 alpha 渐变，
    // 长列表阅读时降低底部干扰；横屏悬浮时叠在消息上方，淡出还能透出滚动内容）。
    val contentAlpha by animateFloatAsState(
        targetValue = if (isScrolling) 0.4f else 1f,
        animationSpec = tween(200),
        label = "claude-bottom-bar-alpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 需求6：横屏悬浮时根容器背景透明，否则整行不透明会把空白侧透出的消息重新盖住；
            // 输入框自身的 Surface（Panel 色）仍不透明，文字照样清晰可读。
            //
            // 透明条件：横屏（compact）、玻璃色主题、**以及背景图激活时**都要透明。
            // 原来这里判断 `isGlassTheme || compact`，漏了背景图 —— 于是选了背景图后
            // 竖屏下这一行仍铺不透明底色，输入框两侧出现两块空白把背景挡住（用户反馈）。
            // compact 必须保留：PageBg 只涵盖玻璃色/背景图，不涵盖横屏。
            .background(if (compact) Color.Transparent else PageBg)
            .graphicsLayer { alpha = contentAlpha }
    ) {
        if (attachments.isNotEmpty()) {
            // 附件条：图片显示缩略图，文件显示图标+文件名，可横向滚动，右上角 × 移除
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(attachments, key = { it.uri.toString() }) { attachment ->
                    AttachmentThumb(
                        attachment = attachment,
                        onRemove = { onRemoveAttachment(attachment.uri) }
                    )
                }
            }
        }

        val showCommands = prompt.startsWith("/") && prompt.length < 20
        if (showCommands) {
            val query = prompt.lowercase()
            val builtins = BUILTIN_SLASH_COMMANDS.map {
                SlashMenuEntry(cmd = it.name, desc = it.description, isSkill = false)
            }
            val skills = slashSkills.map {
                val desc = it.second.replace(Regex("\\s+"), " ").trim().ifBlank { "自定义技能" }
                SlashMenuEntry(cmd = "/${it.first}", desc = "技能 · $desc", isSkill = true)
            }
            val filtered = (builtins + skills).filter {
                it.cmd.startsWith(query) || it.desc.contains(prompt.removePrefix("/"), ignoreCase = true)
            }
            if (filtered.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .heightIn(max = 160.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Panel
                ) {
                    LazyColumn(modifier = Modifier.padding(4.dp)) {
                        items(filtered) { entry ->
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp)).clickable {
                                    if (!entry.isSkill && prompt.trim() == entry.cmd) {
                                        onExecuteCommand(entry.cmd)
                                    } else {
                                        // 已输入参数时仅补全命令，保留用户参数；技能总是补空格等参数。
                                        onPromptChange("${entry.cmd} ")
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(entry.cmd, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        entry.desc,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val estimatedTokens = estimateTokens(prompt)
        if (estimatedTokens > maxTokens * 0.9) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFF6B6B).copy(alpha = 0.1f)
            ) {
                Text(
                    "⚠ Token ($estimatedTokens) 超限 ($maxTokens)",
                    color = Color(0xFFFF6B6B),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        if (messageQueue.isNotEmpty()) {
            QueuedRequestPanel(
                // 队列存的是消息 id（见 ConsoleUiState.messageQueue），面板要显示文本；
                // 正文由调用方解析好传进来（取不到的气泡已在调用侧跳过）。
                queued = queuedTexts,
                onSend = onSendQueuedMessage,
                onSendAll = onSendAllQueuedMessages,
                onRemove = onCancelQueuedMessage,
                onClearAll = onClearMessageQueue
            )
        }

Surface(
            modifier = Modifier
                // 需求1：横屏输入框不铺满右栏、也不居中，而是左侧缩进约 1/3、右边缘贴住右栏右边，
                // 把腾出的左半留给消息区（与顶栏右半对齐，视觉上成一列）。
                // align 需要 ColumnScope 隐式接收者（Surface 正是本 Column 的直接子项）。
                // 竖屏 compact=false → 维持原 fillMaxWidth()，零影响。
                // 需求6：宽度改由外层悬浮 Box 统一控制（barOverlayWidth：左栏可见 0.5、
                // 折叠后 1.0），此处只填满外层；若内外各乘一次系数会变成 0.25。
                .then(Modifier.fillMaxWidth())
                // 输入框整体上提：**加大**底部外边距（底部留白越多，输入框离屏幕底边越远）。
                // 上一轮把它从 6dp 收到 2dp 是把方向搞反了——间距变小只会更贴底。
                .padding(
                    start = 12.dp, end = 12.dp,
                    top = if (compact) 4.dp else 6.dp,
                    bottom = if (compact) 6.dp else 14.dp
                )
                .then(
                    if (isGlassTheme) Modifier.clip(RoundedCornerShape(24.dp)).background(GlassElementBrush)
                    else Modifier
                ),
            shape = RoundedCornerShape(if (compact) 18.dp else 24.dp),
            color = if (isGlassTheme) Color.Transparent else Panel,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(
                    start = if (compact) 2.dp else 4.dp, end = if (compact) 2.dp else 4.dp,
                    top = if (compact) 0.dp else 2.dp, bottom = if (compact) 0.dp else 2.dp
                )
            ) {
                if (tasks.isNotEmpty()) {
                    // 任务面板：嵌入输入框容器，TextField 上方；背景/圆角由输入框容器提供
                    TaskPanel(
                        tasks = tasks,
                        tasksExpanded = tasksExpanded,
                        onToggleExpanded = onToggleTasksExpanded,
                        onUpdateStatus = onUpdateTaskStatus,
                        onRemove = onRemoveTask
                    )
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when {
                                isSending -> "AI 处理中，发送将自动排队..."
                                attachments.isNotEmpty() -> "可补充说明，或直接发送图片（可留空）"
                                else -> "输入消息..."
                            },
                            color = if (isSending) Accent.copy(alpha = 0.6f) else TextSecondary.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Accent
                    ),
                    maxLines = if (compact) 2 else 4,
                    minLines = 1,
                    // 「使用独立的发送按钮」开启时：回车=换行（ImeAction.Default），只能点发送按钮发出。
                    // 关闭时（默认）：回车直接发送，与既有行为一致。
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (separateSendButton) ImeAction.Default else ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (!separateSendButton && canSend) onSend() }
                    )
                )

                // 底部操作行：模型/工具/发送/全屏按钮并入输入框内部
                // 图标尺寸对齐常用输入框样式：按钮 28dp、图标 20dp、间距 4dp。
                // 关键：Material3 会把可点击组件（Surface/IconButton）强制撑到 minimumInteractiveComponentSize
                // （默认 48dp）以保证触控目标——这会让我们设的 28dp 被居中、每侧多出 10dp 死区，
                // 观感就是「图标间距过大」。故在本行作用域内把该尺寸置 0，让 28dp 真正生效。
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides 0.dp,
                    LocalMinimumInteractiveComponentEnforcement provides false
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 工具权限档位（只读/弹窗确认/完全允许）——置于模型选项之前
                    Surface(
                        onClick = { showPermMenu = true },
                        shape = CircleShape,
                        color = Color.Transparent
                    ) {
                        Icon(
                            when (toolPermissionMode) {
                                "readonly" -> Icons.Outlined.Visibility
                                "confirm" -> Icons.Outlined.Security
                                "workspace" -> Icons.Outlined.Folder
                                else -> Icons.Outlined.LockOpen
                            },
                            contentDescription = "工具权限",
                            tint = if (toolPermissionMode == "allow") TextPrimary else Accent,
                            modifier = Modifier
                                .size(28.dp)
                                .padding(4.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    Surface(
                        onClick = { showModelMenu = true },
                        shape = CircleShape,
                        color = Color.Transparent
                    ) {
                        val currentModel = effectiveModel.ifBlank { settings.model }
                        if (currentModel.isNotBlank()) {
                            ModelLogoIcon(
                                modelName = currentModel,
                                size = 20.dp,
                                modifier = Modifier
                                    .size(28.dp)
                                    .padding(4.dp)
                            )
                        } else {
                            ModelLogoIcon(
                                modelName = "other",
                                size = 20.dp,
                                modifier = Modifier
                                    .size(28.dp)
                                    .padding(4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    // 模式切换按钮（构建/规划/股票/聊天，与 /mode、顶部模式同源 agentMode）
                    Surface(
                        onClick = { showModeMenu = true },
                        shape = CircleShape,
                        color = Color.Transparent
                    ) {
                        Icon(
                            when (agentMode) {
                                "plan" -> Icons.Outlined.Lightbulb
                                "stock" -> Icons.Outlined.ShowChart
                                "chat" -> Icons.Outlined.ChatBubble
                                else -> Icons.Outlined.Build
                            },
                            contentDescription = "切换模式",
                            tint = if (agentMode == "build") TextPrimary else Accent,
                            modifier = Modifier
                                .size(28.dp)
                                .padding(4.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // 项目工作区（创建/切换）按钮
                    Surface(
                        onClick = { showProjectMenu = true },
                        shape = CircleShape,
                        color = Color.Transparent
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = "项目工作区",
                            tint = TextPrimary,
                            modifier = Modifier
                                .size(28.dp)
                                .padding(4.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // 思考强度（推理强度）——置于工作区右侧
                    Surface(
                        onClick = { showEffortMenu = true },
                        shape = CircleShape,
                        color = Color.Transparent
                    ) {
                        Icon(
                            com.wy.openx.ui.theme.AppIcons.Brain,
                            contentDescription = "思考强度",
                            tint = if (reasoningEffort == "default") TextPrimary else Accent,
                            modifier = Modifier
                                .size(28.dp)
                                .padding(4.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    Spacer(Modifier.weight(1f))

                    // 上下文用量圆环（短按看用量、长按改阈值）——置于全屏按钮左侧
                    if (contextWindowTokens > 0) {
                        val ctxRatio = if (contextUsedTokens > 0)
                            contextUsedTokens.toFloat() / contextWindowTokens else 0f
                        val ctxTooltip = buildString {
                            if (contextUsedTokens > 0) {
                                append(formatTokenCount(contextUsedTokens.toLong()))
                                append(" / ")
                                append(formatTokenCount(contextWindowTokens.toLong()))
                                append(" tokens")
                            } else {
                                append("0 / ")
                                append(formatTokenCount(contextWindowTokens.toLong()))
                                append(" tokens")
                            }
                            append("\n长按可调整阈值")
                        }
                        com.wy.openx.ui.component.ContextUsageRingButton(
                            ratio = ctxRatio,
                            tooltipMessage = ctxTooltip,
                            onLongClick = { showContextThresholdDialog = true }
                        )
                        Spacer(Modifier.width(4.dp))
                    }

                    IconButton(
                        onClick = { showFullscreen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Fullscreen,
                            contentDescription = "全屏编辑",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box {
                        Surface(
                            onClick = { showToolsMenu = true },
                            shape = CircleShape,
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier.size(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = "工具",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    val sendIcon: @Composable () -> Unit = when {
                        isProcessing -> {
                            // 生成中且输入框为空：红方块 = 停止生成
                            { Box(modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFF6B6B))) }
                        }
                        isSending && canSend -> {
                            // 生成中但输入了新内容：队列图标，点击将消息加入队列
                            { Icon(Icons.Outlined.Add, contentDescription = "加入队列", tint = Accent, modifier = Modifier.size(20.dp)) }
                        }
                        isSending -> {
                            // 生成中且输入框为空：停止按钮
                            { Box(modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFFF6B6B))) }
                        }
                        else -> {
                            { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送", tint = if (canSend) Accent else TextSecondary.copy(alpha = 0.4f), modifier = Modifier.size(20.dp)) }
                        }
                    }
                    // 录音键：常驻在发送键左边（仅生成中隐藏）
                    VoiceInputButton(
                        isSending = isSending,
                        onPromptChange = onPromptChange,
                    )
                    IconButton(
                        onClick = {
                            when {
                                isProcessing -> onStopGeneration()
                                isSending && canSend -> onSend()
                                !isSending && canSend -> onSend()
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        sendIcon()
                    }
                }
                }
            }
        }

    }

    // 工具菜单：从底部滑出的弹窗（样式与模型选择一致）
    if (showToolsMenu) {
        val toolsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val toolsConfig = LocalConfiguration.current
        val toolsMaxHeight = (toolsConfig.screenHeightDp / 2).dp
        ModalBottomSheet(
            onDismissRequest = { showToolsMenu = false },
            sheetState = toolsSheetState,
            containerColor = Panel,
            dragHandle = { HorizontalDivider(color = Border) }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = toolsMaxHeight)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                item {
                    Text(
                        "工具",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                item {
                    ToolsMenuItem(
                        icon = { Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp)) },
                        label = "附件",
                        onClick = { onAttachClick(); showToolsMenu = false }
                    )
                }
                item {
                    ToolsMenuItem(
                        icon = { Icon(Icons.Outlined.Image, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp)) },
                        label = "图片（可多选）",
                        onClick = { onPickImage(); showToolsMenu = false }
                    )
                }
                item {
                    ToolsMenuItem(
                        icon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp)) },
                        label = "拍照",
                        onClick = { onTakePhoto(); showToolsMenu = false }
                    )
                }
            }
        }
    }

    // 模型选择：按提供商分组的底部弹窗（含搜索与能力标签）
    if (showModelMenu) {
        ModelSelectionSheet(
            providers = providers,
            currentProviderId = effectiveProviderId.ifBlank { settings.selectedProviderId },
            currentModel = effectiveModel.ifBlank { settings.model },
            modelMetadata = providerModelMetadata,
            onSelect = { pid, model -> onSelectProviderModel(pid, model); showModelMenu = false },
            onDismiss = { showModelMenu = false }
        )
    }

    // 模式选择：构建/规划/股票/聊天，与 /mode、顶部模式显示共用 agentMode；聊天模式禁用工具调用
    if (showModeMenu) {
        val modeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showModeMenu = false },
            sheetState = modeSheetState,
            containerColor = Panel,
            dragHandle = { HorizontalDivider(color = Border) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    "选择模式",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ModeSheetRow(
                    active = agentMode == "build",
                    icon = { Icon(Icons.Outlined.Build, contentDescription = null, tint = if (agentMode == "build") Accent else TextPrimary, modifier = Modifier.size(18.dp)) },
                    title = "构建模式",
                    subtitle = "可读写文件，适合开发、构建任务",
                    onClick = { onSetAgentMode("build"); showModeMenu = false }
                )
                HorizontalDivider(color = Border)
                ModeSheetRow(
                    active = agentMode == "plan",
                    icon = { Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = if (agentMode == "plan") Accent else TextPrimary, modifier = Modifier.size(18.dp)) },
                    title = "规划模式",
                    subtitle = "只做方案与任务拆解，不直接写代码",
                    onClick = { onSetAgentMode("plan"); showModeMenu = false }
                )
                HorizontalDivider(color = Border)
                ModeSheetRow(
                    active = agentMode == "stock",
                    icon = { Icon(Icons.Outlined.ShowChart, contentDescription = null, tint = if (agentMode == "stock") Accent else TextPrimary, modifier = Modifier.size(18.dp)) },
                    title = "股票模式",
                    subtitle = "技术分析与基本面，附风险提示",
                    onClick = { onSetAgentMode("stock"); showModeMenu = false }
                )
                HorizontalDivider(color = Border)
                ModeSheetRow(
                    active = agentMode == "chat",
                    icon = { Icon(Icons.Outlined.ChatBubble, contentDescription = null, tint = if (agentMode == "chat") Accent else TextPrimary, modifier = Modifier.size(18.dp)) },
                    title = "聊天模式",
                    subtitle = "禁用工具调用，纯对话",
                    onClick = { onSetAgentMode("chat"); showModeMenu = false }
                )
            }
        }
    }

    // 工具权限档位：只读 / 弹窗确认 / 仅工作区修改 / 完全允许（对 构建/股票/规划 生效；聊天模式恒只读不参与）
    if (showPermMenu) {
        val permSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showPermMenu = false },
            sheetState = permSheetState,
            containerColor = Panel,
            dragHandle = { HorizontalDivider(color = Border) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    "工具权限",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ModeSheetRow(
                    active = toolPermissionMode == "readonly",
                    icon = { Icon(Icons.Outlined.Visibility, contentDescription = null, tint = if (toolPermissionMode == "readonly") Accent else TextPrimary, modifier = Modifier.size(18.dp)) },
                    title = "只读",
                    subtitle = "仅查询分析，不下发写文件/执行命令类工具",
                    onClick = { onSetToolPermissionMode("readonly"); showPermMenu = false }
                )
                HorizontalDivider(color = Border)
                ModeSheetRow(
                    active = toolPermissionMode == "confirm",
                    icon = { Icon(Icons.Outlined.Security, contentDescription = null, tint = if (toolPermissionMode == "confirm") Accent else TextPrimary, modifier = Modifier.size(18.dp)) },
                    title = "弹窗确认",
                    subtitle = "每次写文件/执行命令前弹窗由你确认",
                    onClick = { onSetToolPermissionMode("confirm"); showPermMenu = false }
                )
                HorizontalDivider(color = Border)
                ModeSheetRow(
                    active = toolPermissionMode == "workspace",
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = null, tint = if (toolPermissionMode == "workspace") Accent else TextPrimary, modifier = Modifier.size(18.dp)) },
                    title = "仅工作区修改",
                    subtitle = "工作区内直接放行，越界时弹窗确认",
                    onClick = { onSetToolPermissionMode("workspace"); showPermMenu = false }
                )
                HorizontalDivider(color = Border)
                ModeSheetRow(
                    active = toolPermissionMode == "allow",
                    icon = { Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = if (toolPermissionMode == "allow") Accent else TextPrimary, modifier = Modifier.size(18.dp)) },
                    title = "完全允许",
                    subtitle = "工具直接执行，不弹窗（默认）",
                    onClick = { onSetToolPermissionMode("allow"); showPermMenu = false }
                )
            }
        }
    }

    // 思考强度（推理强度）：对应 OpenAI 兼容接口的 reasoning_effort。
    // 「模型默认」档不发送该参数，避免不支持的网关因未知字段报错。
    if (showEffortMenu) {
        val effortSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val effortCfg = LocalConfiguration.current
        // 限高为半屏（与「选择模型」弹窗一致），选项过多时在列表内滚动
        val maxEffortSheetHeight = (effortCfg.screenHeightDp / 2).dp
        ModalBottomSheet(
            onDismissRequest = { showEffortMenu = false },
            sheetState = effortSheetState,
            containerColor = Panel,
            dragHandle = { HorizontalDivider(color = Border) }
        ) {
            // 标题行刻意放在 LazyColumn **外面**：它在滚动容器之外、不产生嵌套滚动，
            // 因此仍由面板自身的 draggable 接管 —— 按住这一排下滑即可关闭弹窗。
            // 若把它留在列表里（原来就是第一个 item），锁住列表后它将无法再拖动关闭。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    "思考强度",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxEffortSheetHeight)
                        // 锁住列表的「溢出滑动」：滑列表不再拖动弹窗、甩一下也不关闭。
                        .nestedScroll(SheetScrollLock)
                ) {
                    itemsIndexed(buildEffortOptions(reasoningEffortOptionsProvider())) { index, (value, label) ->
                        if (index > 0) HorizontalDivider(color = Border)
                        ModeSheetRow(
                            active = reasoningEffort == value,
                            icon = { Icon(com.wy.openx.ui.theme.AppIcons.Brain, contentDescription = null, tint = if (reasoningEffort == value) Accent else TextPrimary, modifier = Modifier.size(18.dp)) },
                            title = label,
                            onClick = { onSetReasoningEffort(value); showEffortMenu = false }
                        )
                    }
                }
            }
        }
    }

    // 项目工作区（标题栏 + 右侧「新建」按钮，新建走独立对话框，列表行显示名称与路径）
    if (showProjectMenu) {
        val projectSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val projectCfg = LocalConfiguration.current
        val maxProjectSheetHeight = (projectCfg.screenHeightDp / 2).dp
        ModalBottomSheet(
            onDismissRequest = { showProjectMenu = false },
            sheetState = projectSheetState,
            containerColor = Panel,
            dragHandle = { HorizontalDivider(color = Border) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "工作区",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { folderPickerLauncher.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加本地目录", color = Accent)
                    }
                    TextButton(onClick = { showCreateProjectDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建", color = Accent)
                    }
                }
                // 默认工作区：容器内 /root/workspace（不选任何项目时使用），始终置顶展示。
                val isDefaultCurrent = currentProject.isBlank()
                Surface(
                    onClick = { onSelectProject(""); showProjectMenu = false },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDefaultCurrent) Accent.copy(alpha = 0.15f) else Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = null,
                            tint = if (isDefaultCurrent) Accent else TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "默认工作区",
                                color = if (isDefaultCurrent) Accent else TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (isDefaultCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "/root/workspace",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isDefaultCurrent) {
                            Text("当前", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                if (projects.isEmpty() && externalWorkspaces.isEmpty()) {
                    Text("暂无其他工作区，可点击「新建」创建项目，或「添加本地目录」选择设备文件夹", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = maxProjectSheetHeight),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(projects, key = { "p:$it" }) { p ->
                            val isCurrent = p == currentProject
                            Surface(
                                onClick = { onSelectProject(p); showProjectMenu = false },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isCurrent) Accent.copy(alpha = 0.15f) else Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (isCurrent) Accent else TextPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            p,
                                            color = if (isCurrent) Accent else TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "projects/$p",
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isCurrent) {
                                        Text("当前", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { projectToDelete = p },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "删除项目",
                                            tint = Color(0xFFFF6B6B),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                        items(externalWorkspaces, key = { "e:${it.name}" }) { ws ->
                            val isCurrent = ws.name == currentProject
                            Surface(
                                onClick = { onSelectProject(ws.name); showProjectMenu = false },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isCurrent) Accent.copy(alpha = 0.15f) else Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        tint = if (isCurrent) Accent else TextPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            ws.name,
                                            color = if (isCurrent) Accent else TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            ws.path,
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isCurrent) {
                                        Text("当前", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 4.dp))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { projectToDelete = ws.name },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "移除本地目录",
                                            tint = Color(0xFFFF6B6B),
                                            modifier = Modifier.size(18.dp)
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

    if (showCreateProjectDialog) {
        CreateProjectDialog(
            existingNames = projects + externalWorkspaces.map { it.name },
            onConfirm = { name ->
                onCreateProject(name)
                showCreateProjectDialog = false
            },
            onDismiss = { showCreateProjectDialog = false }
        )
    }

    projectToDelete?.let { p ->
        val isExternal = externalWorkspaces.any { it.name == p }
        val title = if (isExternal) "移除本地目录" else "移除项目工作区"
        val message = if (isExternal) {
            "确定要移除本地目录「$p」吗？仅解除工作区关联，不会删除该目录中的任何文件。"
        } else {
            "确定要将项目「$p」从工作区移除吗？不会删除任何文件（目录保留在 workspace 的 archive 区域，可随时恢复）。"
        }
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text(title, fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text(message, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteProject(p)
                    projectToDelete = null
                }) { Text("确认移除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }

    if (showContextThresholdDialog) {
        // 圆环长按 → 编辑「当前提供商 + 当前模型」的窗口。写入 provider 体系的自定义模型元数据
        // （与设置页「编辑模型」同一份数据），圆环/自动压缩读取时以此为最高优先级，故立即生效。
        // 旧实现走 ApiPreset 预设，配置写了却没人读，导致「设置了也没用、设置页也不变」。
        if (currentModelName.isBlank()) {
            showContextThresholdDialog = false
            android.widget.Toast.makeText(context, "当前没有可用的模型", android.widget.Toast.LENGTH_LONG).show()
        } else {
            com.wy.openx.ui.screen.CurrentModelMetaSheet(
                modelName = currentModelName,
                initial = currentModelMeta,
                onSave = { ctx, out ->
                    showContextThresholdDialog = false
                    onSaveCurrentModelMeta(ctx, out)
                },
                onAutoFetch = onAutoFetchCurrentModelMeta,
                onDismiss = { showContextThresholdDialog = false }
            )
        }
    }

    if (showFullscreen) {
        FullscreenInputDialog(
            initialText = prompt,
            onConfirm = { text ->
                onPromptChange(text)
                showFullscreen = false
                if (text.isNotBlank()) onSend()
            },
            onSaveOnly = { text ->
                onPromptChange(text)
                showFullscreen = false
            },
            onDismiss = { showFullscreen = false }
        )
    }
}

@Composable
private fun ToolsMenuItem(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ModeSheetRow(
    active: Boolean,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String = "",
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = if (active) Accent else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                // subtitle 为空则不渲染，避免留出空白行
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = TextSecondary, fontSize = 11.sp)
                }
            }
            if (active) {
                Icon(Icons.Default.Check, contentDescription = "当前选中", tint = Accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    existingNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val canConfirm = trimmed.isNotEmpty() && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目工作区", fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称", color = TextSecondary) },
                    placeholder = { Text("如 my-app", color = TextSecondary.copy(alpha = 0.5f)) },
                    isError = duplicate,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        cursorColor = Accent
                    )
                )
                if (duplicate) {
                    Spacer(Modifier.height(2.dp))
                    Text("已存在同名项目", color = Color(0xFFFF6B6B), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = { onConfirm(trimmed) }) {
                Text("创建", color = if (canConfirm) Accent else TextSecondary.copy(alpha = 0.4f))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        },
        containerColor = Panel
    )
}

@Composable
private fun FullscreenInputDialog(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onSaveOnly: ((String) -> Unit)? = null
) {
    var value by remember {
        // 光标落到末尾：进来就是要接着往下写，也是键盘弹出后最容易被遮挡的位置。
        mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
    }
    val text = value.text
    // 键盘弹出后 CoreTextField 不会把光标重新滚进可视区：TextFieldScrollerPosition.update()
    // 只在「光标矩形变化」时才滚动，视口变矮只更新滚动上限。表现就是文字停在原处、要手动往上滑。
    // 这里在键盘弹出的瞬间把光标挪一格再跨帧放回，借「光标变了」那条分支逼它按新视口重算。
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (!imeVisible) return@LaunchedEffect
        val selection = value.selection
        if (!selection.collapsed) return@LaunchedEffect
        val back = (selection.start - 1).coerceAtLeast(0)
        if (back == selection.start) return@LaunchedEffect
        val perturbed = value.copy(selection = TextRange(back))
        value = perturbed
        withFrameNanos { }
        // 这一帧里若有真输入进来，value 已不是上面那份，不能再按旧 selection 覆盖回去。
        if (value === perturbed) value = value.copy(selection = selection)
    }
    // Dialog 走独立 Window：默认 decorFitsSystemWindows=true 时 insets 由系统代劳，
    // Compose 侧 WindowInsets.ime 恒为 0、imePadding() 加不出高度 → 内容被输入法盖住。
    // 置 false 交给 Compose，下面 Surface 的 systemBarsPadding()/imePadding() 才真正生效。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
            color = Panel
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
                    Spacer(Modifier.weight(1f))
                    if (onSaveOnly != null) {
                        TextButton(
                            onClick = { onSaveOnly(text) },
                            enabled = text.isNotBlank()
                        ) { Text("保存", color = Accent) }
                    }
                    Text("编辑消息", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onConfirm(text) },
                        enabled = text.isNotBlank()
                    ) { Text("发送", color = if (text.isNotBlank()) Accent else TextSecondary.copy(alpha = 0.4f)) }
                }
                HorizontalDivider(color = Border)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Accent
                    ),
                    placeholder = { Text("输入消息...", color = TextSecondary.copy(alpha = 0.5f)) },
                    maxLines = Int.MAX_VALUE,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                )
            }
        }
    }
}

@Composable
private fun MessageEditDialog(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    val clipboard = LocalClipboardManager.current
    val dialogContext = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .systemBarsPadding()
                .imePadding(),
            shape = RoundedCornerShape(16.dp),
            color = Panel
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
                    // 复制当前编辑框内容（而非原消息）：改到一半想存草稿、或清空输入框后仍要原文时用得上。
                    IconButton(onClick = {
                        scope.launch { clipboard.setText(AnnotatedString(text)) }
                        android.widget.Toast.makeText(
                            dialogContext, "已复制", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "复制内容",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("编辑消息", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onConfirm(text.trim()) },
                        enabled = text.isNotBlank()
                    ) { Text("发送", color = Accent) }
                }
                Text(
                    "保存后，此消息之后的回复将被删除并按新内容重新生成。",
                    color = TextSecondary.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                HorizontalDivider(color = Border, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Accent
                    ),
                    placeholder = { Text("输入消息...", color = TextSecondary.copy(alpha = 0.5f)) },
                    maxLines = Int.MAX_VALUE,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                )
            }
        }
    }
}

@Composable
private fun ClaudeMessageBubble(
    msg: ChatMessage,
    msgId: Long = -1L,
    highlighted: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    /** 聊天正文自定义颜色（外观设置里选的颜色）。null = 跟随主题（默认行为）。 */
    customTextColor: Color? = null,
    showTimestamp: Boolean = false,
    modelName: String = "OpenX",
    scrollState: ChatScrollState = ChatScrollState(),
    /** 该消息是否正在流式接收（正文攒块渲染用；折叠/状态判定不受影响）。 */
    streaming: Boolean = false,
    onResend: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRewind: (() -> Unit)? = null,
    /** 「从此处新建分支」：用户消息与助手回复都提供。 */
    onFork: (() -> Unit)? = null,
    /** 聊天里点「文件:行号」链接 → (容器路径, 1-based 行号；0=无行号)。null=不接管，走系统浏览器。 */
    onOpenFileLink: ((String, Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val isToolCall = msg.toolName != null && msg.role == "assistant"
    val isToolResult = msg.toolName != null && msg.role == "system"

    val toolIcon = when {
        msg.toolName?.startsWith("web_") == true -> "🌐"
        msg.toolName?.startsWith("file_") == true -> "📁"
        msg.toolName?.startsWith("memory") == true -> "🧠"
        msg.toolName?.startsWith("skill") == true -> "🔧"
        msg.toolName == "ask_user" -> "💬"
        msg.toolName == "subagent_dispatch" || msg.toolName == "call_sub_agent" || msg.toolName == "talk_to_agent" -> "🤖"
        msg.toolName == "git_operation" || msg.toolName == "git" -> "🔀"
        msg.toolName == "scaffold" -> "🏗️"
        msg.toolName?.contains("batch") == true -> "📦"
        msg.toolName?.contains("edit") == true -> "📝"
        msg.toolName == "explorer" || msg.toolName?.contains("search") == true -> "🔍"
        msg.toolName == "http_client" -> "📡"
        msg.toolName == "sqlite_browser" -> "🗄️"
        msg.toolName == "screenshot" -> "📸"
        msg.toolName == "stock_query" || msg.toolName == "stock" -> "📈"
        msg.toolName?.startsWith("add_") == true || msg.toolName?.startsWith("update_") == true || msg.toolName?.startsWith("remove_") == true -> "📋"
        else -> "⚡"
    }

    if (isToolCall) {
        var expanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PanelAlt.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp)
            ) {
                Column {
                    Surface(
                        onClick = { expanded = !expanded },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(toolIcon, fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(msg.toolName ?: "", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Text(if (expanded) "▼" else "▶", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                    AnimatedVisibility(visible = expanded) {
                        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                            Text(msg.content, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        return
    }

    if (isToolResult) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(toolIcon, fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Text(msg.content.take(80), color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRegenerateDialog by remember { mutableStateOf(false) }
    var showForkDialog by remember { mutableStateOf(false) }

    val isUser = msg.role == "user"
    val isSystem = msg.role == "system"
    val (bodyContentRaw, thinkingContent) = if (msg.isSteps || isUser || isSystem) {
        Pair(msg.content, "")
    } else {
        // 流式中的气泡 content 每帧都在变：不缓存（否则会把历史消息的缓存全挤出去）
        ChatUtils.extractThinkingContent(msg.content, cacheable = !streaming)
    }
    // 历史消息正文里遗留的「[已附加图片 N 张]」占位行：展示时剔除（图片已由气泡下方附件行呈现）
    val bodyContent = if (isUser) stripImageAttachmentPlaceholder(bodyContentRaw) else bodyContentRaw
    // 正文流式也走打字机限速揭示（与思考同一套 rememberStreamingRenderText）：模型快速吐字
    // 时逐 token 全量重跑正文 markdown 解析/测量同样会卡。**刻意不按 streaming 切渲染路径**：
    // 完成那一帧仍在打字机路径里当帧补全全文，避免旧「攒 400 字符」节流差的一段在结束时
    // 整体蹦出（尾部在输入框上方闪一下）。非流式（历史消息 / 刚结束）经同一渲染器也立即
    // 返回原文，开销可忽略。
    val renderBody = if (isUser || isSystem || msg.isSteps) bodyContent
    else rememberStreamingRenderText(bodyContent, streaming, msgId)

    val bubbleColor = when {
        isUser -> UserBubbleBg
        isSystem -> PanelAlt.copy(alpha = 0.4f)
        else -> Panel.copy(alpha = 0.5f)
    }
    val textColor = if (isUser) UserBubbleFg else MaterialTheme.colorScheme.onBackground
    // 外观设置里的「聊天文本颜色」：用户显式指定时覆盖正文颜色，
    // 但**用户气泡**保留其专属前景色（否则自定义色会与柠檬色气泡糊在一起、不可读）。
    val bodyTextColor = if (customTextColor != null && !isUser) customTextColor else textColor
    val labelColor = TextSecondary
    // 横屏两栏时右栏只占约 2/3 屏宽，若仍按整屏 0.7 计算会宽于右栏本身、等于不设限，
    // 故横屏改用 0.55（≈ 右栏的 82%），竖屏维持原 0.7 不变。
    val cfg = LocalConfiguration.current
    val bubbleFraction = if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) 0.55f else 0.7f
    val userMaxWidth = with(androidx.compose.ui.platform.LocalDensity.current) { (cfg.screenWidthDp * bubbleFraction).dp }
    val roleLabel = when {
        isUser -> "你"
        isSystem -> "系统"
        // 模型名往往很长（如 loomy/DeepSeek-V4.1-Flash），不截断会把同一行的时间与
        // 操作图标挤到换行，故超过 18 个字符就省略。
        else -> if (modelName.length > 18) modelName.take(18) + "…" else modelName
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // 不再整块 clickable 打开编辑（会与文本选择/复制手势冲突），仅保留下方"编辑"入口
        // 纯图片消息没有正文：跳过空气泡，只在下方渲染附件行。
        val pureImageMessage = isUser && msg.content.isBlank() &&
            (msg.attachments.isNotEmpty() || msg.imageUri != null) &&
            msg.steps.isEmpty()
        if (!pureImageMessage) {
        Surface(
            shape = if (isUser) RoundedCornerShape(12.dp) else RoundedCornerShape(20.dp),
            color = bubbleColor,
            border = if (highlighted) BorderStroke(2.dp, Accent) else null,
            modifier = Modifier.widthIn(max = if (isUser) userMaxWidth else androidx.compose.ui.unit.Dp.Unspecified)
        ) {
            Column(modifier = Modifier.padding(horizontal = if (isUser) 14.dp else 16.dp, vertical = 10.dp)) {
                if (msg.isSteps) {
                    msg.steps.forEachIndexed { index, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("${index + 1}. ", color = textColor, fontWeight = FontWeight.Bold, fontSize = fontSize)
                            Text(step, color = textColor, fontSize = fontSize)
                        }
                    }
                } else if (isDiffContent(bodyContent)) {
                    SelectionContainer { DiffView(diffText = bodyContent, fontSize = fontSize) }
                } else {
                    // 把「打开文件链接」回调经 CompositionLocal 下传给 Markdown 渲染器：
                    // 点击 `文件:行号` 形式的链接时交给宿主打开编辑器，而非拼成 https:// 网址。
                    // 这里不直接改 MarkdownContent 签名——它有几十处调用点，
                    // 逐个加参数既啰嗦又易漏（见 ChatLinkResolver.kt 文件头说明）。
                    CompositionLocalProvider(
                        LocalChatFileOpener provides onOpenFileLink
                    ) {
                        SelectionContainer {
                            MarkdownContent(text = renderBody, baseFontSize = fontSize, textColor = if (!isUser) customTextColor else null)
                        }
                    }
                }

                // Artifact preview for HTML content
                val htmlContent = remember(bodyContent) { extractHtmlContent(bodyContent) }
                if (htmlContent != null) {
                    var showPreview by remember { mutableStateOf(false) }
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = { showPreview = true },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("▶ 预览页面", fontSize = 12.sp, color = Accent)
                    }

                    if (showPreview) {
                        val webViewRef = remember { mutableStateOf<WebView?>(null) }
                        Dialog(
                            onDismissRequest = { showPreview = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth(0.95f)
                                    .fillMaxHeight(0.9f),
                                shape = RoundedCornerShape(16.dp),
                                color = Panel
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("预览", fontWeight = FontWeight.Bold, color = TextPrimary)
                                        TextButton(onClick = { showPreview = false }) {
                                            Text("关闭", color = Accent)
                                        }
                                    }
                                    DisposableEffect(Unit) {
                                        onDispose { webViewRef.value?.destroy() }
                                    }
                                    AndroidView(
                                        factory = { ctx ->
                                            WebView(ctx).apply {
                                                webViewClient = WebViewClient()
                                                settings.javaScriptEnabled = true
                                                settings.allowFileAccess = false
                                                settings.loadWithOverviewMode = true
                                                settings.useWideViewPort = true
                                                settings.builtInZoomControls = true
                                                settings.displayZoomControls = false
                                                loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                                            }.also { webViewRef.value = it }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }

                }
            }
        }

        // 图片/文件附件行：图片缩略+名字+大小·容器路径，整行点击看大图
        if (isUser && (msg.attachments.isNotEmpty() || msg.imageUri != null)) {
            SentAttachmentList(attachments = messageDisplayAttachments(msg), context = context)
        }

        // Token & time display for assistant messages（只回显 API 真实 usage，无则显示 ?）
        if (!isUser && !isSystem && (msg.tokens != null || msg.inputTokens != null || (msg.cachedInputTokens ?: 0) > 0 || msg.timeMs != null)) {
            Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
                if (msg.timeMs != null) {
                    Text(formatTime(msg.timeMs), color = labelColor.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                if (msg.tokens != null || msg.inputTokens != null || (msg.cachedInputTokens ?: 0) > 0) {
                    if (msg.timeMs != null) Text(" · ", color = labelColor.copy(alpha = 0.6f), fontSize = 12.sp)
                    val inVal = msg.inputTokens
                    val outVal = msg.tokens
                    val cacheVal = msg.cachedInputTokens ?: 0
                    val inStr = if (inVal != null) formatTokenCount(inVal.toLong()) else "?"
                    val outStr = if (outVal != null) formatTokenCount(outVal.toLong()) else "?"
                    val totalStr = if (inVal != null || outVal != null) formatTokenCount(((inVal ?: 0) + (outVal ?: 0)).toLong()) else "?"
                    val cacheStr = if ((inVal ?: 0) > 0 && cacheVal > 0) " 缓存${(cacheVal * 100.0 / inVal!!).toInt().coerceAtMost(100)}%" else ""
                    Text("Token:${totalStr}${cacheStr}  ↑${inStr}  ↓${outStr}", color = labelColor.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isUser) {
                Spacer(modifier = Modifier.weight(1f))
                if (showTimestamp && msg.time.isNotBlank()) {
                    Text(
                        msg.time,
                        color = labelColor.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                if (onEdit != null) {
                    IconButton(onClick = { onEdit() }, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑", tint = labelColor.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                    }
                }
                // 复制
                IconButton(onClick = {
                    val clip = ClipData.newPlainText("msg", msg.content)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.size(18.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", tint = labelColor.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                }
                if (onFork != null) {
                    IconButton(onClick = { showForkDialog = true }, modifier = Modifier.size(18.dp)) {
                        Icon(
                            Icons.Default.CallSplit,
                            contentDescription = "从此处新建分支",
                            tint = labelColor.copy(alpha = 0.4f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                if (onResend != null) {
                    IconButton(onClick = { onRewind?.invoke() }, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "检查点与撤销", tint = labelColor.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "删除", tint = labelColor.copy(alpha = 0.35f), modifier = Modifier.size(14.dp))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (roleLabel.isNotEmpty()) {
                        Text(roleLabel, color = labelColor.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (showTimestamp && msg.time.isNotBlank()) Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (showTimestamp && msg.time.isNotBlank()) {
                        Text(msg.time, color = labelColor.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onRegenerate != null) {
                        IconButton(onClick = { showRegenerateDialog = true }, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "重新生成", tint = labelColor.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    IconButton(
                        onClick = {
                            val clip = ClipData.newPlainText("msg", bodyContentRaw)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", tint = labelColor.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                    }
                    if (onFork != null) {
                        Spacer(modifier = Modifier.width(2.dp))
                        IconButton(onClick = { showForkDialog = true }, modifier = Modifier.size(18.dp)) {
                            Icon(
                                Icons.Default.CallSplit,
                                contentDescription = "从此处新建分支",
                                tint = labelColor.copy(alpha = 0.4f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    // 朗读本条回复（系统 TTS）；朗读中再点一次即停
                    MessageSpeakButton(
                        messageId = msgId,
                        content = msg.content,
                        idleTint = labelColor.copy(alpha = 0.4f),
                    )
                    if (onDelete != null) {
                        Spacer(modifier = Modifier.width(2.dp))
                        IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "删除", tint = labelColor.copy(alpha = 0.35f), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("确定要删除此消息吗？此操作不可撤销。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete?.invoke()
                }) { Text("确认删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }
    if (showRegenerateDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateDialog = false },
            title = { Text("确认重新生成", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("确定要重新生成此回复吗？当前内容将被覆盖。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateDialog = false
                    onRegenerate?.invoke()
                }) { Text("确认重新生成", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateDialog = false }) { Text("取消", color = TextSecondary) }
            },
containerColor = Panel
        )
    }
    if (showForkDialog) {
        AlertDialog(
            onDismissRequest = { showForkDialog = false },
            title = { Text("新建分支会话", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Text(
                    "将复制此消息及之前的全部历史到新会话并自动切换，原会话保持不变。",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForkDialog = false
                    onFork?.invoke()
                }) { Text("新建分支", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showForkDialog = false }) { Text("取消", color = TextSecondary) }
            },
            containerColor = Panel
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RewindSheet(
    promptSnippet: String,
    onOption: (com.wy.openx.ui.viewmodel.RewindOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text("检查点与撤销", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
            if (promptSnippet.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text("目标消息：$promptSnippet", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            RewindRow(
                title = "恢复代码和会话",
                desc = "删除此消息及之后的对话，回滚文件到保存点，并按原消息填入输入框",
                onClick = { onOption(com.wy.openx.ui.viewmodel.RewindOption.RESTORE_CODE_AND_CONVERSATION) }
            )
            RewindRow(
                title = "仅恢复会话",
                desc = "回到该消息处；若已压缩上下文，会先解除压缩、恢复压缩前的全部原文；按原消息填入输入框（不动文件）",
                onClick = { onOption(com.wy.openx.ui.viewmodel.RewindOption.RESTORE_CONVERSATION) }
            )
            RewindRow(
                title = "仅恢复代码",
                desc = "仅把文件回滚到该消息的保存点（不动对话）",
                onClick = { onOption(com.wy.openx.ui.viewmodel.RewindOption.RESTORE_CODE) }
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun RewindRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary)
            Spacer(Modifier.height(1.dp))
            Text(desc, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal const val REASONING_COLLAPSE_LINE_LIMIT = 8

/**
 * 耗时文案：<60s 直接报秒，超过则进位为「X 分 Y 秒」。
 * 一直报「用时 1234秒」不好读。
 */
private fun formatElapsed(sec: Int): String =
    if (sec < 60) "${sec}秒" else "${sec / 60}分${sec % 60}秒"


/**
 * 流式文本渲染（消息正文与思考共用）
 * `rememberTypewriterStreamingText`（StatusBubbles.kt）。
 *
 * 旧实现 `rememberThrottledRenderText` 是「50ms 轮询 + 攒够 400 字符或满 250ms 才提交」：
 * 结尾至多差 400 字符没提交，而 `streaming` 一变 false 立即切到原文 → 最后几段在单帧里
 * 整体蹦出来 = 「尾部在输入框上方闪一下」。
 *
 * 打字机按「上游速率 × 系数」跟随揭示、滞后越多追得越快（追赶项 = 滞后 ÷
 * [TYPEWRITER_SETTLE_SECONDS]），上游停顿时以 [TYPEWRITER_MIN_RATE] 匀速追平；
 * `active=false` 不再一帧补全，而是按 [typewriterDrainRate] 匀速打完剩余（见收尾阶段）。
 * 每 [TYPEWRITER_RENDER_INTERVAL_MS] 快照一次渲染文本。
 * 结论：结尾只有「匀速打完」，没有整块蹦出。
 *
 * @param text 完整累积文本（每 delta 追加后的快照）
 * @param active 是否仍在流式输出；false = 结束，当帧补全全文
 * @param sessionKey 消息稳定标识（换轮/换会话即补全当前全文，不归零重打）
 */
@Composable
private fun rememberStreamingRenderText(text: String, active: Boolean, sessionKey: Any): String {
    // 已见文本的前缀指纹与其长度一起构成「同一轮延续」的判据
    var shownChars by remember(sessionKey) { mutableStateOf(0) }
    var shownHead by remember(sessionKey) { mutableStateOf(0) }
    val restored = remember {
        if (isStreamContinuation(text, shownChars, shownHead)) text.substring(0, shownChars) else text
    }
    var shownCodePoints by remember(sessionKey) {
        mutableStateOf(restored.codePointCount(0, restored.length).toFloat())
    }
    var renderText by remember(sessionKey) { mutableStateOf(restored) }
    // 上游到达事件窗口：(帧时间戳, 累计码点数)，用于估算吐字速率
    val arrivals = remember(sessionKey) { ArrayDeque<Pair<Long, Int>>() }
    var lastArrivalNanos by remember(sessionKey) { mutableStateOf(0L) }
    var lastText by remember(sessionKey) { mutableStateOf(restored) }
    var lastSessionKey by remember { mutableStateOf<Any?>(null) }
    // 渲染文本与其指纹必须同步更新，否则恢复时会拿指纹校验另一段文本
    val commitRender: (String) -> Unit = { snapshot ->
        renderText = snapshot
        shownChars = snapshot.length
        shownHead = streamHeadFingerprint(snapshot)
    }

    LaunchedEffect(text, active, sessionKey) {
        // 换会话 / 新一轮 / 重试：补全到当前全文，再跟着后续 delta 打字（不归零重打）。
        // 换会话必须单独判：sessionKey 先到的那一帧，文本还是旧会话的，看不出突变。
        val sessionChanged = sessionKey != lastSessionKey
        lastSessionKey = sessionKey
        if (sessionChanged || (lastText.isNotEmpty() && !text.startsWith(lastText))) {
            shownCodePoints = text.codePointCount(0, text.length).toFloat()
            commitRender(text)
            arrivals.clear()
            lastArrivalNanos = 0L
        }
        lastText = text

        // 上游结束进入收尾阶段：不再一帧补全（那正是「结束瞬间抽搐一下」的来源），
        // 剩余那点字按恒定速率匀速打完。此时 text 不会再变，收尾期间本协程不会被打断。
        val draining = !active
        val now = System.nanoTime()
        val codePoints = text.codePointCount(0, text.length)
        // 收尾速率按「进入收尾时的剩余量」定，保证收尾时长恒定、且能精确打完
        val drainRate = if (draining) {
            typewriterDrainRate((codePoints - shownCodePoints).coerceAtLeast(0f))
        } else {
            null
        }
        val drainDeadlineNanos = now + TYPEWRITER_DRAIN_HARD_MS * 1_000_000L
        if (!draining) {
            // 记录本次到达事件，裁剪速率窗口（保留最近 WINDOW 内至少 2 条）
            if (lastArrivalNanos != 0L) {
                arrivals.addLast(now to codePoints)
                val windowNanos = TYPEWRITER_RATE_WINDOW_MS * 1_000_000L
                while (arrivals.size > 2 && now - arrivals.first().first > windowNanos) {
                    arrivals.removeFirst()
                }
            }
            lastArrivalNanos = now
        }

        // 帧驱动推进显示进度，追平本次文本即退出（text 再变化时本协程被取消重启）
        var lastRenderNanos = 0L
        var lastFrameNanos = 0L
        while (shownCodePoints < codePoints) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val dtSec = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    // 播出阶段才需要估算上游速率：收尾阶段上游已经不动了
                    var arrivalRate = 0f
                    if (!draining) {
                        val oldest = arrivals.firstOrNull()
                        if (arrivals.size >= 2 && oldest != null) {
                            val spanSec = (frameNanos - oldest.first) / 1_000_000_000f
                            if (spanSec > 0f) {
                                arrivalRate = (arrivals.last().second - oldest.second) / spanSec
                            }
                        }
                    }
                    val rate = typewriterRate(
                        lag = codePoints - shownCodePoints,
                        arrivalRate = arrivalRate,
                        drainRate = drainRate
                    )
                    shownCodePoints = (shownCodePoints + rate * dtSec)
                        .coerceAtMost(codePoints.toFloat())
                }
                lastFrameNanos = frameNanos

                // 收尾硬上限：极端情况（文本被整体替换、渲染跟不上）也必须在这之内追平，
                // 否则尾巴会一直挂在未追平状态。
                if (draining && frameNanos >= drainDeadlineNanos) {
                    shownCodePoints = codePoints.toFloat()
                }

                // 渲染节流：到间隔就快照当前显示进度；追平瞬间强制渲染完整文本
                val intervalNanos = TYPEWRITER_RENDER_INTERVAL_MS * 1_000_000L
                if (frameNanos - lastRenderNanos >= intervalNanos || shownCodePoints >= codePoints) {
                    val snapshot = truncateToCodePoints(text, shownCodePoints.toInt(), codePoints)
                    if (snapshot != renderText) {
                        lastRenderNanos = frameNanos
                        commitRender(snapshot)
                    }
                }
            }
        }
        // 追平后确保渲染完整文本（while 退出时 shownCodePoints 已到 available）
        if (renderText != text) commitRender(text)
    }
    return renderText
}

/** 打字机最低显示速率（字符/秒）：上游停顿时仍匀速追赶，保证能看到结尾。 */
private const val TYPEWRITER_MIN_RATE = 30f

/** 常规显示速率上限（字符/秒）：滞后在 [TYPEWRITER_LAG_TARGET] 以内时的天花板。 */
private const val TYPEWRITER_MAX_RATE = 220f

/** 滞后超标后的应急速率上限（字符/秒）：允许突破常规上限，把积压迅速吃掉。 */
private const val TYPEWRITER_BURST_RATE = 1600f

/** 显示速率 = 上游速率 × 该系数：略小于 1，保留「始终慢半拍」的滞后感。 */
private const val TYPEWRITER_FOLLOW_RATIO = 0.95f

/**
 * 允许的滞后（字符）：显示最多落在上游这么多字之后，超过它就不再受 [TYPEWRITER_MAX_RATE] 约束。
 *
 * 这是「结束瞬间别抽搐」的关键：滞后被压在小范围内，上游结束时要补的永远是最后那一小段。
 *
 * 注：官方取 12（配合其 60ms 高频到达）。我方未做独立流式通道、flush 间隔较长，
 * 取更宽的阈值让「跟随项」主导显示速率（显示≈上游速率×系数，从而逐帧连续流动、
 * 不出现「一批瞬间打完再静止等下一批」的顿挫），burst 只在严重积压时兜底。
 */
private const val TYPEWRITER_LAG_TARGET = 40f

/**
 * 追赶时间常数（秒）：滞后按该节奏收敛，越小追得越紧。
 *
 * 官方取 0.06（激进，靠高频到达维持连续）。我方低频下取较大值，避免追平即静止。
 */
private const val TYPEWRITER_SETTLE_SECONDS = 0.25f

/**
 * 上游结束后把剩余文字打完的目标时长（秒）。
 *
 * 结束后不再一帧补全（那正是「突然跳到结尾」的来源），而是按「剩余 ÷ 该时长」匀速打完。
 */
private const val TYPEWRITER_DRAIN_SECONDS = 0.2f

/** 收尾阶段的显示速率上限（字符/秒）：剩余很多时也不至于一闪而过。 */
private const val TYPEWRITER_DRAIN_MAX_RATE = 400f

/** 收尾硬上限（ms）：极端情况也必须在这之内追平，否则尾巴永远挂在未追平状态。 */
private const val TYPEWRITER_DRAIN_HARD_MS = 600L

/**
 * 打字机单帧显示速率（码点/秒）。
 *
 * [lag] = 上游已到字数 − 已显示字数；[arrivalRate] 为滑窗估算的上游吐字速率；
 * [drainRate] 非空表示上游已结束的收尾阶段（按该恒定速率匀速打完剩余）。
 *
 * 播出阶段取跟随项与追赶项的较大值。常规上限 [TYPEWRITER_MAX_RATE] 只在滞后未超标时
 * 生效：滞后一旦超过 [TYPEWRITER_LAG_TARGET] 就放宽到 [TYPEWRITER_BURST_RATE]。这条规则是
 * 「结束时别抽搐」的关键——旧实现把速率死封在上限，模型吐得快时滞后只增不减（稳态约
 * 1.8×上游速率，动辄上百字），上游一结束整段一次跳出，看起来就是突然抽搐一下。
 */
private fun typewriterRate(lag: Float, arrivalRate: Float, drainRate: Float? = null): Float {
    if (lag <= 0f) return 0f
    if (drainRate != null) return drainRate.coerceIn(TYPEWRITER_MIN_RATE, TYPEWRITER_BURST_RATE)
    val followRate = arrivalRate * TYPEWRITER_FOLLOW_RATIO
    val catchUpRate = lag / TYPEWRITER_SETTLE_SECONDS
    val ceiling = if (lag > TYPEWRITER_LAG_TARGET) TYPEWRITER_BURST_RATE else TYPEWRITER_MAX_RATE
    return maxOf(followRate, catchUpRate).coerceIn(TYPEWRITER_MIN_RATE, ceiling)
}

/**
 * 收尾速率（码点/秒）：把 [lag] 个剩余码点在 [TYPEWRITER_DRAIN_SECONDS] 内匀速打完。
 *
 * 取「按剩余量摊到目标时长」而不是「固定倍数追赶」，保证收尾时长恒定：
 * 剩余十几字看得清是打字，剩余几百字也不会一格一格磨到用户以为卡住。
 */
private fun typewriterDrainRate(lag: Float): Float =
    (lag / TYPEWRITER_DRAIN_SECONDS).coerceIn(TYPEWRITER_MIN_RATE, TYPEWRITER_DRAIN_MAX_RATE)

/** 上游吐字速率估算的滑动窗口时长（ms）。 */
private const val TYPEWRITER_RATE_WINDOW_MS = 500L

/** 打字机渲染快照间隔（ms）：~40fps 高频平滑快照，消除低速模型的顿挫感。 */
private const val TYPEWRITER_RENDER_INTERVAL_MS = 24L

/** 延续判据的前缀采样上限（字符）：只存指纹不存全文。 */
private const val CONTINUITY_HEAD_CHARS = 64

/** 已见文本的前缀指纹。 */
private fun streamHeadFingerprint(text: String): Int =
    text.take(CONTINUITY_HEAD_CHARS).hashCode()

/** [text] 是否是「长度 [seenChars]、前缀指纹 [seenHead]」那段已见文本的延续。 */
private fun isStreamContinuation(text: String, seenChars: Int, seenHead: Int): Boolean {
    if (seenChars <= 0 || text.length < seenChars) return false
    return text.take(minOf(seenChars, CONTINUITY_HEAD_CHARS)).hashCode() == seenHead
}

/** 按码点数量截断字符串，避免把 emoji 等代理对截成孤立的半个字符。 */
private fun truncateToCodePoints(text: String, codePoints: Int, totalCodePoints: Int): String {
    if (codePoints <= 0) return ""
    // 全文码点数由调用方给出：它在本次 effect 里已经算过一次，自己再算就是每帧多一
    // 轮 O(n) 扫描（长思考链下每 24ms 一次，是纯粹的浪费）。
    if (codePoints >= totalCodePoints) return text
    var index = 0
    var count = 0
    while (index < text.length && count < codePoints) {
        index += Character.charCount(text.codePointAt(index))
        count++
    }
    return text.substring(0, index)
}

@Composable
private fun ThinkingBlock(
    content: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    msgId: Long = -1L,
    streaming: Boolean = false,
    /** 该段思考的权威计时（起止），由 [groupMessages] 从 VM 带下来；null = 无计时。 */
    timing: ThinkingTiming? = null,
    /** 该段思考是否已收尾（正文已开始输出，或 VM 已落定 endAt）。 */
    done: Boolean = false,
    /** 设置项：思考块是否始终展开。false（默认）= 思考中展开、结束后自动折叠。 */
    alwaysExpanded: Boolean = false,
    scrollState: ChatScrollState = ChatScrollState(),
    /** 外层聊天列表状态：顶部越界时直接把剩余手势驱动到它（等价参考实现的 jumpTo 转发）。 */
    parentListState: LazyListState? = null
) {
    // 自动开合的目标态：开关打开则恒展开；否则思考中展开、这段思考一结束就折叠。
    val autoExpanded = alwaysExpanded || !done
    var expanded by remember(msgId) {
        mutableStateOf(
            loadThinkingFoldState(msgId)?.first
                ?: scrollState.thinkingState[msgId]?.expanded
                ?: autoExpanded
        )
    }
    var userToggled by remember(msgId) {
        mutableStateOf(
            loadThinkingFoldState(msgId)?.second
                ?: scrollState.thinkingState[msgId]?.userToggled
                ?: false
        )
    }
    var fullExpanded by remember(msgId) { mutableStateOf(false) }
    LaunchedEffect(msgId, expanded, userToggled) {
        scrollState.thinkingState[msgId] = ToolGroupState(expanded, userToggled)
        saveThinkingFoldState(msgId, expanded, userToggled)
    }
    // 思考中自动展开、一结束自动折叠；开关打开则始终展开。
    // 用户手动动过的块（userToggled）不再被自动改 —— 与工具组 ToolCallGroup 同一口径。
    LaunchedEffect(msgId, autoExpanded, userToggled) {
        if (userToggled) return@LaunchedEffect
        expanded = autoExpanded
    }

    // 计时：**完全由 VM 记录的该段起止决定**，这里只读不算、不自记、不定格。
    //
    // 为什么起止都在 VM：思考块是 LazyColumn 的 item，滚出屏幕会被销毁、本协程随之取消。
    // 若结束时刻只在 UI 侧定格，用户滚走时定格值会停在「滚走前的最后一秒」，
    // 少算真实用时。VM 在「发起工具调用」和「整轮结束」两处落定 endAt（见 thinkingSegments），
    // 所以无论 item 是否在屏，用时都准确。
    //
    // 多段思考（思考→工具→再思考）各查各的 timing，因此各显示各自的用时，
    // 而不是从任务开头累加（那需要整轮的 thinkingStartedAt，语义不同）。
    //
    // 每秒重算：仅在该段**未结束**时跳秒；已结束则显示定格值，不再变化。
    val elapsedSec = remember(msgId) { mutableIntStateOf(timing?.elapsedSec() ?: 0) }
    LaunchedEffect(msgId, timing) {
        if (timing == null) {
            elapsedSec.intValue = 0
            return@LaunchedEffect
        }
        elapsedSec.intValue = timing.elapsedSec()
        if (timing.finished) return@LaunchedEffect   // 已结束：定格，不跳秒
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsedSec.intValue = timing.elapsedSec()
        }
    }

    // 折叠策略沿用原有口径：<8 行全量显示；>8 行折叠为尾部 8 行预览 + 「展开剩余 X 行」。
    val lineCount = remember(content) { content.count { it == '\n' } + 1 }
    val overThreshold = lineCount > REASONING_COLLAPSE_LINE_LIMIT
    // 渲染用文本：流式时打字机限速揭示（见 rememberStreamingRenderText），结束当帧补全。
    // 行数/折叠/计时仍用原始 content，保证实时判据不被打字机拖慢。
    val renderText = rememberStreamingRenderText(content, streaming, msgId)
    // 内容区固定最大高度 + 内部独立滚动：
    // 不再「尾部 8 行预览 + 展开剩余 N 行」。那种做法有两个问题：
    // ①思考内容被截断，看不到开头（推理链的开头往往最关键）；
    // ② 预览/全文两种高度来回切换，item 高度跳变会把滚动锚点带偏。
    // 改成固定高 200dp 内滚：item 高度稳定，内容又能完整读到（滑动即可）。
    // 用heightIn 而非固定 height：短思考不会被拉高留白。
    val maxContentHeight = 200.dp
    // 原先这里按「行数 > 4」判 scrollable —— 行数 ≠ 高度：长段落/代码块换行少却很高，
    // 会被判成不滚，内容漏出 200dp 之外压到下一项气泡上；换行多但很矮又会白挂渐隐遮罩。
    // 现在始终挂 verticalScroll，用 `innerScroll.maxValue > 0` 判真实溢出。

    // 竖线用 drawBehind 画在 Row 左缘：Row 高度天然等于（限高后的）内容高度，
    // 省掉 height(IntrinsicSize.Min) 那一轮 intrinsic 测量（流式内容每 token 都测一次，是卡顿来源之一）。
    // 左右边距对齐工具卡（ToolCallGroup）：工具卡根 Surface 是 fillMaxWidth 无水平 padding，
    // 只在汇总行加 padding(end = 8.dp)，左侧靠列表 contentPadding 定位。
    // 思考块与之保持一致，左边不再额外缩进。
    Row(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            // 折叠箭头 + 图标 + 标题。
            // 标题行**不带竖线**（竖线只标记思考内容区，见下），箭头左对齐到内容起点。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        expanded = !expanded
                        userToggled = true
                    }
                    .padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 与工具卡（ToolCallGroup）用同一个图标，保持全App 折叠控件一致。
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
                Icon(
                    Icons.Default.Stars,
                    contentDescription = null,
                    tint = TextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                // 有计时才报时长：
                // - 未结束（该段还在跑）→「正在思考（12秒）」
                // - 已结束 →「思考过程用时 23秒」（定格值，不跳秒）
                // - 无计时（历史消息）→ 只显示「思考过程」，不硬报「0秒」
                //   （那是假数据，也让人误以为思考是瞬时的）。
                val hasTiming = timing != null
                Text(
                    text = when {
                        timing == null -> "思考过程"
                        !timing.finished -> "正在思考（${formatElapsed(elapsedSec.intValue)}）"
                        else -> "思考过程用时 ${formatElapsed(elapsedSec.intValue)}"
                    },
                    color = TextSecondary,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium
                )
            }
            if (expanded) {
        // 内容区：左侧一根竖线做视觉分隔（标题行不画）。
        // 竖线由 drawBehind 画在 Row 左缘，Row 高度天然等于内容高度，
        // 不再需要 height(IntrinsicSize.Min)（省掉一轮 intrinsic 测量）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = TextSecondary.copy(alpha = 0.75f),
                        size = Size(2.dp.toPx(), size.height)
                    )
                }
        ) {
                    Spacer(Modifier.width(2.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            // heightIn 必须保留：没有它思考内容不限高，整块会无限铺开、
                            // 把下方气泡顶出屏幕。
                            .heightIn(max = maxContentHeight)
                            // ⚠ 兜底裁剪：heightIn 只限制 Box 自身尺寸、**不裁孩子**。
                            // 思考与正文气泡是平级 LazyColumn item，内容一旦画出 200dp
                            // 就会压到下一项气泡上（旧行数判据漏判时真会发生）。
                            .clipToBounds()
                            .padding(start = 10.dp, top = 4.dp)
                    ) {
                    // 内部滚动状态（lazy）。流式增长时自动跟随到底部；用户上滑后停止跟随
                    //（可回看上文），回到底部附近再恢复跟随。
                    // 2026-10-10 由 verticalScroll 的**全量 Column** 改为**块级 LazyColumn**：
                    // verticalScroll 每帧把整段思考一次性 measure/layout，挂几万字思考一滑就把
                    // 主线程打满（卡住 → ANR/闪退）；拆成块只布局可视几块，成本与思考总长度无关。
                    val innerState = rememberLazyListState()
                    // 真实溢出：以内滚还有没有余量为准，**不看块数**（理由见 maxContentHeight 处注释）。
                    // 不溢出时 canScroll 双 false，内滚一分不占、手势自然透传给外层列表。
                    val innerOverflow by remember {
                        derivedStateOf { innerState.canScrollForward || innerState.canScrollBackward }
                    }
                    // 渐隐遮罩：内容超出可视区时，底部边缘淡出，暗示「还能继续下滑」。
                    if (innerOverflow) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(18.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                                    )
                                )
                        )
                    }
                    // 跟随开关。⚠ 只由「用户滑动」翻转，**不能**挂在 content 上判断：
                    // 内容增长不代表用户动过，拿它当判据就会回到无条件拽底。
                    //
                    // 为什么改（2026-10-06，用户报「AI 执行工具时往上翻，页面不停抖动」）：
                    // 原来这里是**无条件** `scrollTo(maxValue)`，content 每变一次就拽一次；
                    // 思考是流式输出，等于每帧把正在上翻的用户拽回底部 —— 肉眼就是整页在抖。
                    // 上面那句注释本来就写着「上滑停止跟随、回底恢复」，是代码没照做，这次补齐。
                    var innerFollow by remember(msgId) { mutableStateOf(true) }
                    // 渲染文本直接用全文：内层 LazyColumn 本身是块级虚拟化（MarkdownLazyContent），
                    // 单帧布局成本恒定、与思考总长度无关 —— 不再需要原「尾部 4000 字窗口
                    //（THINKING_TAIL_CHARS）」那种文案级截断，也就没了「上滑/思考结束 → 切全文」
                    // 的跳跃式切换。
                    LaunchedEffect(renderText) {
                        // 等一帧让新内容完成测量，否则滚不到位。key 用实际参与布局的 renderText：
                        // 布局跟着它变，用固定 key 会在布局更新前抢跑。
                        withFrameNanos { }
                        if (innerFollow) {
                            val last = innerState.layoutInfo.totalItemsCount - 1
                            if (last >= 0) innerState.scrollToItem(last, Int.MAX_VALUE)
                        }
                    }
                    // 判据只看 firstVisibleItem 走向：
                    //   · 首位不变 + 偏移变小 = 有人往上看（程序性跟随只往大偏移）→ 停跟随
                    //   · 无后向余量且末项整体露出视口 = 回到底部 → 恢复跟随
                    //   · 内容增长（末项序号涨、view 不动）两条都不满足 → 开关保持原样
                    LaunchedEffect(innerState) {
                        var lastFirst = innerState.firstVisibleItemIndex
                        var lastOffset = innerState.firstVisibleItemScrollOffset
                        snapshotFlow {
                            innerState.firstVisibleItemIndex to innerState.firstVisibleItemScrollOffset
                        }.collect { (first, off) ->
                            val info = innerState.layoutInfo
                            val lastVisible = info.visibleItemsInfo.lastOrNull()
                            val nearBottom = !innerState.canScrollForward &&
                                lastVisible != null &&
                                lastVisible.index >= info.totalItemsCount - 1 &&
                                (lastVisible.offset + lastVisible.size) <= info.viewportEndOffset + 8
                            if (innerState.isScrollInProgress && first == lastFirst && off < lastOffset) {
                                innerFollow = false
                            } else if (nearBottom) {
                                innerFollow = true
                                // 内层回到底部且外层也确实在底部时，解除外层上翻锁，
                                // 恢复正常的流式贴底；外层不在底部则保持锁定。
                                if (parentListState != null && !parentListState.canScrollForward) {
                                    scrollState.userPulledUp = false
                                }
                            }
                            lastFirst = first
                            lastOffset = off
                        }
                    }
                    // 底部橡皮筋空白：滑到思考内容底还想继续往上时，手势在卡内吃掉、
                    // 内容上移露出空白，松手弹回。⚠ 仅在**思考进行中**这样做 ——
                    // 流式输出时漏给外层会跟外层「贴底校准」循环互相抢，整页不停抖动。
                    // 思考结束后不再吃手势（atBottomGetter 为 false），剩余 delta 透传给外层。
                    // atBottomGetter：只有此刻（思考进行中、溢出且真的贴底）才允许橡皮筋吃手势。
                    val blankPx = remember { mutableStateOf(0f) }
                    val blankScope = rememberCoroutineScope()
                    val maxBlankPx = with(LocalDensity.current) { 140.dp.toPx() }
                    // 只在「思考进行中」才允许橡皮筋吃手势：思考结束后（streaming=false）
                    // 内层到底不再吞 delta，剩余手势透传给外层聊天列表，用户能继续往上翻。
                    val atBottomGetter = rememberUpdatedState {
                        streaming && innerOverflow && !innerState.canScrollForward
                    }
                    // 顶部越界判据：思考进行中且内层已无法向后滚（在顶部）。
                    // 与底部对称 —— 流式时顶部 delta 也不漏给外层，避免和外层「贴底校准」互抢。
                    val atTopGetter = rememberUpdatedState {
                        streaming && innerOverflow && !innerState.canScrollBackward
                    }
                    val isStreamingGetter = rememberUpdatedState { streaming }
                    // 用户向顶部方向滚动 = 明确想回看上文：内层停止自动跟随，
                    // 同时锁住外层「贴底校准」（userPulledUp），否则外层会把刚翻上去的
                    // 内容又拽回底部 → 顶部/快速上滑时整页抖动。
                    val onUserScrollUpState = rememberUpdatedState {
                        innerFollow = false
                        scrollState.userPulledUp = true
                    }
                    // 顶部越界时直接把剩余手势驱动到外层列表（等价参考实现的
                    // parentPosition.jumpTo）：不再依赖 nestedScroll 再转一手，
                    // 避免与父级贴底校准互相拉扯造成抖动。
                    val parentScrollState = rememberUpdatedState<(Float) -> Float> { delta ->
                        parentListState?.dispatchRawDelta(delta) ?: 0f
                    }
                    val handoff = remember(blankPx, blankScope, maxBlankPx) {
                        ThinkingScrollHandoff(
                            atBottom = { atBottomGetter.value() },
                            atTop = { atTopGetter.value() },
                            isStreaming = { isStreamingGetter.value() },
                            onUserScrollUp = { onUserScrollUpState.value() },
                            parentScroll = { delta -> parentScrollState.value(delta) },
                            blankPx = blankPx,
                            scope = blankScope,
                            maxBlankPx = maxBlankPx
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 思考内容上用户往下拖时立即停内层跟随并锁外层，不等滚动位移。
                            .onReadDragStart {
                                innerFollow = false
                                scrollState.userPulledUp = true
                            }
                            // 始终挂内滚：不溢出时 canScroll 双 false，等于没滚、手势自然透传。
                            // 底交接触发条件见 ThinkingScrollHandoff。
                            .nestedScroll(handoff)
                    ) {
                        SelectionContainer {
                            // 关掉内层拉伸回弹（等价参考实现的 ClampingScrollPhysics）：
                            // 边界处手势只做交接，不产生「拉一下再弹回」的抖动观感。
                            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                                MarkdownLazyContent(
                                    text = renderText,
                                    state = innerState,
                                    baseFontSize = (fontSize.value - 1).sp,
                                    // 内滚到底时给 6dp 缓冲，避免最后一行紧贴渐隐遮罩。
                                    bottomPadding = if (innerOverflow) 6.dp else 0.dp
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

/**
 * 压缩摘要**折叠卡**。
 *
 * 默认收起为一行「上下文已压缩」+ 展开箭头，点击展开才显示摘要正文。
 * 这样压缩后聊天页不会突然多出一大段文字：历史消息仍平铺可见、可往上翻，
 * 摘要只是一张可展开的卡片。
 *
 * 上方一条**纯细横线**把「压缩前」与「压缩后」分开。
 * 恢复压缩**不在这里给按钮**：走上方的「仅恢复会话 / 仅恢复代码和会话」弹窗
 * 
 */
@Composable
private fun CompactionSummaryBlock(summary: String) {
    var expanded by remember { mutableStateOf(false) }
    // 用 `hasVisibleContent()` 判空：摘要可能只剩空白/换行，展开也不该多出一块空白。
    val hasBody = remember(summary) { summary.isNotBlank() }
    Column(modifier = Modifier.fillMaxWidth()) {
        CompactionDivider()
        Spacer(Modifier.height(6.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PanelAlt,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Accent)
                    )
                    Spacer(Modifier.width(8.dp))
                    // 文字用中性色：状态由圆点表达就够，
                    // 标题再用强调色会显得像按钮。
                    Text(
                        "上下文已压缩",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    // 图标而非文字：
                    // 「展开 ▼ / 收起 ▲」两个词在卡片右侧比一个箭头占掉两倍宽度。
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                // 摘要正文走 **Markdown**：
                // 压缩摘要天然是结构化的（分点、`文件:行号` 引用、代码片段），
                // 用纯 Text 打印会把换行和列表压成一坨，可读性差很多。
                if (expanded && hasBody) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        MarkdownContent(text = summary, baseFontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/**
 * 压缩分割线：一条**纯细横线**。
 *
 * 之前是 `─●─`（两侧横线 + 中间圆点）。改成纯线是因为圆点与卡片标题里的状态圆点重复，
 * 一条安静的细线更能表达「这里是同一段对话被压缩切开的位置」，且不与气泡视觉竞争。
 */
@Composable
private fun CompactionDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = Border.copy(alpha = 0.7f),
        thickness = 0.5.dp
    )
}

@Composable
private fun ClaudeEmptyState(
    modifier: Modifier = Modifier
) {    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Panel),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "你好！有什么我可以帮你的？",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "输入消息开始对话",
            color = TextSecondary,
            fontSize = 14.sp
        )
    }
}

/**
 * 「AI 正在思考… / 重连中…」后面三个点的配色：一个点一个颜色。
 * 色相等距取 0°/120°/240°，饱和度/明度沿用工具卡转圈的 `Color.hsv(hue, 0.68f, 0.95f)`，
 * 两处动效因此是同一套色，工具卡在转、这里在闪，一眼能看出是同一种"进行中"。
 */
private val THINKING_DOT_COLORS = listOf(
    Color.hsv(hue = 0f, saturation = 0.68f, value = 0.95f),
    Color.hsv(hue = 120f, saturation = 0.68f, value = 0.95f),
    Color.hsv(hue = 240f, saturation = 0.68f, value = 0.95f)
)

/**
 * 请求失败错误卡片。
 * 折叠一行：⚠ 请求失败 + 复制 + 展开箭头；点开显示完整错误文本（最高 160dp 可滚动）。
 * 纯 UI 状态，不落库、不发模型；显示在输入框上方，直到下一次发送清空。
 */
@Composable
private fun RequestErrorCard(
    message: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val titleSize = if (compact) 12.sp else 13.sp
    val subSize = if (compact) 11.sp else 12.sp

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Danger.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Danger.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("⚠", fontSize = titleSize, color = Danger)
                Spacer(Modifier.width(6.dp))
                Text(
                    "请求失败",
                    fontSize = titleSize,
                    fontWeight = FontWeight.SemiBold,
                    color = Danger,
                    modifier = Modifier.weight(1f)
                )
                // 复制按钮
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            scope.launch { clipboardManager.setText(AnnotatedString(message)) }
                            copied = true
                            scope.launch {
                                kotlinx.coroutines.delay(1200)
                                copied = false
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (copied) "已复制" else "复制",
                        fontSize = subSize,
                        color = if (copied) Accent else TextSecondary
                    )
                }
                // 展开/折叠箭头
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起详情" else "展开详情",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                // 完整错误文本：最高 160dp 滚动区，长报错可滚动看完。
                Text(
                    text = message,
                    fontSize = subSize,
                    lineHeight = subSize * 1.4f,
                    color = TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun ClaudeThinkingIndicator(
    toolName: String? = null,
    reconnecting: Boolean = false,
    reconnectAttempt: Int = 0,
    reconnectMax: Int = 0,
    startedAt: Long = 0L,
    compacting: Boolean = false,
    compact: Boolean = false
) {
    // 横屏悬浮态（compact=true）与竖屏统一收紧胶囊上下内边距（用户反馈上下偏宽）；字体仍分档。
    val padH = if (compact) 12.dp else 16.dp
    val padV = if (compact) 4.dp else 6.dp
    val mainSize = if (compact) 11.sp else 13.sp
    val elapsedSize = if (compact) 10.sp else 11.sp
    val statusDotSize = if (compact) 6.dp else 8.dp
    val ellipsisDotSize = if (compact) 4.dp else 5.dp
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
        label = "dot"
    )
    // 计时基于绝对开始时间戳（来自 ViewModel），离开/返回聊天页不重置，仅按当前时刻连续计算。
    //
    // ⚠ `startedAt` 为 0 表示「本段不可计时」（回合已收尾后才进入的压缩等），**绝不能**拿
    // `now - 0` 当时长——那是 1970 年至今 ≈ 29853498 分，用户真机见过这个数。
    // 与 [FloatingOverlayController.elapsedSuffix] 同一口径：不可计时就不显示时长。
    val timeable = startedAt > 0L
    var elapsed by remember(startedAt) {
        mutableIntStateOf(
            if (startedAt > 0L) ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(0) else 0
        )
    }
    LaunchedEffect(toolName, startedAt) {
        if (startedAt <= 0L) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceAtLeast(0)
        }
    }
    val toolNames = remember(toolName) {
        mapOf(
            "web_search" to "正在搜索互联网",
            "web_fetch" to "正在读取网页",
            "file_manager" to "正在操作文件",
            "file_analyzer" to "正在分析文件",
            "app_launcher" to "正在操作应用",
            "phone_operator" to "正在操作手机",
            "device_info" to "正在获取设备信息"
        )
    }

    Column(
        // compact（横屏悬浮态）：思考条与输入框同属一列，左边缘需对齐输入框卡片——
        // 输入框 Surface 自身有 padding(start = 12.dp)，故这里补同样的 12dp 缩进。
        // 竖屏 compact=false：维持原样（贴流式列左边缘），零回归。
        modifier = Modifier.fillMaxWidth().then(if (compact) Modifier.padding(start = 12.dp) else Modifier),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Panel.copy(alpha = 0.5f)
        ) {
            if (compacting) {
                Row(
                    modifier = Modifier.padding(horizontal = padH, vertical = padV),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(statusDotSize).clip(CircleShape).background(Accent.copy(alpha = dotAlpha)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在压缩上下文", color = Accent, fontSize = mainSize)
                    if (timeable) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AnimatedContent(targetState = elapsed, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "elapsed") { e ->
                            val txt = if (e < 60) "${e}秒" else "${e / 60}分${e % 60}秒"
                            Text(txt, color = TextSecondary.copy(alpha = 0.4f), fontSize = elapsedSize)
                        }
                    }
                }
            } else if (toolName != null && !reconnecting) {
                Row(
                    modifier = Modifier.padding(horizontal = padH, vertical = padV),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(statusDotSize).clip(CircleShape).background(Accent.copy(alpha = dotAlpha)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(toolNames[toolName] ?: "正在使用 $toolName", color = Accent, fontSize = mainSize)
                    if (timeable) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AnimatedContent(targetState = elapsed, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "elapsed") { e ->
                            val txt = if (e < 60) "${e}秒" else "${e / 60}分${e % 60}秒"
                            Text(txt, color = TextSecondary.copy(alpha = 0.4f), fontSize = elapsedSize)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = padH, vertical = padV),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        // 重连提示带次数：AI 在等网络重试，明示「第 N/M 次」让用户知道还在努力。
                        if (reconnecting) "重连中 (第 $reconnectAttempt/$reconnectMax 次)" else "AI 正在思考",
                        color = if (reconnecting) Accent else TextSecondary.copy(alpha = 0.6f),
                        fontSize = mainSize
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Row {
                        repeat(3) { i ->
                            Box(
                                modifier = Modifier
                                    .size(ellipsisDotSize)
                                    .padding(start = if (i > 0) 3.dp else 0.dp)
                                    .clip(CircleShape)
                                    // 三个点三色：原先三点同色（重连用 Accent、思考用灰），
                                    // 看着就是"一个颜色的省略号"，没有生气。改成三点各一个颜色，
                                    // 色相取工具卡转圈同一套 HSV 参数（0.68/0.95），
                                    // 等距取 0°/120°/240° 三原色位——同族配色，与转圈一眼看得出是一套。
                                    .background(
                                        THINKING_DOT_COLORS[i].copy(
                                            alpha = when {
                                                i == 0 -> dotAlpha
                                                i == 1 -> 1f - (dotAlpha - 0.2f)
                                                else -> dotAlpha.coerceIn(0.2f, 0.6f)
                                            }
                                        )
                                    )
                            )
                        }
                    }
                    if (timeable) {
                        Spacer(modifier = Modifier.width(10.dp))
                        AnimatedContent(targetState = elapsed, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "elapsed") { e ->
                            val txt = if (e < 60) "${e}秒" else "${e / 60}分${e % 60}秒"
                            Text(txt, color = TextSecondary.copy(alpha = 0.4f), fontSize = elapsedSize)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndeterminateLoadingBar() {
    val infiniteTransition = rememberInfiniteTransition(label = "loadingBar")
    val progress by infiniteTransition.animateFloat(
        initialValue = -0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Panel)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.25f)
                .offset(x = maxWidth * progress)
                .background(Accent)
        )
    }
}

/** 按最近活跃时间把会话分成「置顶/今天/昨天/本周X/30天内/更早」几组，组内按时间倒序。 */
private fun groupConversationsByTime(items: List<ConversationItem>): List<Pair<String, List<ConversationItem>>> {
    if (items.isEmpty()) return emptyList()

    val pinned = items.filter { it.pinned }.sortedByDescending { it.lastActiveAt }
    val rest = items.filter { !it.pinned }.sortedByDescending { it.lastActiveAt }

    if (items.all { it.pinned }) {
        return if (pinned.isEmpty()) emptyList() else listOf("置顶" to pinned)
    }

    val now = java.util.Calendar.getInstance()
    val startOfToday = now.clone() as java.util.Calendar
    startOfToday.set(java.util.Calendar.HOUR_OF_DAY, 0)
    startOfToday.set(java.util.Calendar.MINUTE, 0)
    startOfToday.set(java.util.Calendar.SECOND, 0)
    startOfToday.set(java.util.Calendar.MILLISECOND, 0)
    val todayStart = startOfToday.timeInMillis

    val startOfThisWeek = startOfToday.clone() as java.util.Calendar
    // 周一为一周起点：DAY_OF_WEEK 周日=1，周一=2…将周一映射为 0
    val dowOffset = (startOfToday.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    startOfThisWeek.add(java.util.Calendar.DAY_OF_YEAR, -dowOffset)
    val weekStart = startOfThisWeek.timeInMillis

    val startOf30d = todayStart - 30L * 24L * 3600L * 1000L

    val groups = LinkedHashMap<String, MutableList<ConversationItem>>()
    val dayNames = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    for (item in rest) {
        val t = item.lastActiveAt
        val label = when {
            t >= todayStart -> "今天"
            t >= todayStart - 24L * 3600L * 1000L -> "昨天"
            t >= weekStart -> {
                val c = java.util.Calendar.getInstance()
                c.timeInMillis = t
                dayNames[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
            }
            t >= startOf30d -> "30 天内"
            else -> "更早"
        }
        groups.getOrPut(label) { mutableListOf() }.add(item)
    }

    val ordered = LinkedHashMap<String, List<ConversationItem>>()
    if (pinned.isNotEmpty()) ordered["置顶"] = pinned
    listOf("今天", "昨天").forEach { if (groups.containsKey(it)) ordered[it] = groups[it]!! }
    // 本周某天：周日…周六 按自然周序排列（周一在前）
    (1..7).forEach { i ->
        val label = dayNames[i - 1]
        if (groups.containsKey(label)) ordered[label] = groups[label]!!
    }
    if (groups.containsKey("30 天内")) ordered["30 天内"] = groups["30 天内"]!!
    if (groups.containsKey("更早")) ordered["更早"] = groups["更早"]!!

    return ordered.map { (k, v) -> k to v }.filter { it.second.isNotEmpty() }
}

@Composable
internal fun ConversationDrawerContent(
    conversations: List<ConversationItem>,
    currentId: Long,
    onSelect: (Long) -> Unit,
    onNewConversation: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onRequestRename: (ConversationItem) -> Unit = {},
    onTogglePin: (Long, Boolean) -> Unit = { _, _ -> },
    /** 归档 / 取消归档（侧边栏左滑与全滑触发）。 */
    onToggleArchive: (Long, Boolean) -> Unit = { _, _ -> },
    /** 打开「历史」页（已归档会话）。 */
    onOpenHistory: () -> Unit = {},
    onOpenMemoryManage: () -> Unit = {},
    onOpenMcpManage: () -> Unit = {},
    onOpenSkillManage: () -> Unit = {},
    onOpenWorkflow: () -> Unit = {},
    // [PLUGIN-MOD] 插件入口与侧边栏快捷区（作者更新后需重新确认本行仍存在）
    onOpenPluginManage: () -> Unit = {},
    pluginQuickGroups: List<com.wy.openx.plugin.PluginQuickGroup> = emptyList(),
    onPluginQuickAction: (com.wy.openx.plugin.PluginQuickAction) -> Unit = {},
    /** 快捷区行首插件名点击：按 id 打开插件界面。 */
    onOpenPluginById: (String) -> Unit = {},
    /** 插件快捷区是否展开（持久化在设置里，默认折叠）。 */
    pluginQuickBarExpanded: Boolean = false,
    onPluginQuickBarExpandedChange: (Boolean) -> Unit = {},
    onSearchMessages: (ConversationItem) -> Unit = {},
    browseFileSystem: com.wy.openx.container.BrowseFileSystem? = null,
    /** 已连上的远程服务器；非空时侧边栏文件树顶部显示「远程根」。 */
    remoteConnection: com.wy.openx.remote.RemoteConnection? = null,
    /** 文件树长按「用浏览器打开」：容器路径 → 打开内置浏览器加载。 */
    onOpenFileInBrowser: (String) -> Unit = {},
    /** 文件树长按「加入输入栏」：容器路径 + 文件名 → 投成待发附件。 */
    onAttachToInput: (String, String) -> Unit = { _, _ -> },
    /** true=横屏常驻左栏（收紧 padding、修正 inset 顺序）；false=竖屏抽屉浮层。 */
    embedded: Boolean = false
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var browseClipboard by remember { mutableStateOf<BrowseClipboard?>(null) }
    var menuSession by remember { mutableStateOf<ConversationItem?>(null) }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            // 内嵌为常驻左栏时先吃 status bar 再补紧 padding：原抽屉版是浮层，
            // 顺序反了会双重偏移；且 16dp 在 1/3 窄栏里太占地方。
            .then(
                if (embedded) Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)
                else Modifier.padding(16.dp).statusBarsPadding()
            )
    ) {
        DrawerTopTabs(selected = selectedTab, onSelect = { selectedTab = it })
        Spacer(modifier = Modifier.height(if (embedded) 4.dp else 12.dp))

        when (selectedTab) {
            0 -> {
                // 标题行：左边「会话历史」文字，右边归档入口。
                // 归档进来的会话在「历史」页里，不再出现在下面这个主列表。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "会话历史",
                        color = TextSecondary,
                        fontSize = if (embedded) 11.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        onClick = onOpenHistory,
                        shape = CircleShape,
                        color = Color.Transparent
                    ) {
                        Icon(
                            Icons.Outlined.Archive,
                            contentDescription = "历史（已归档会话）",
                            tint = TextSecondary,
                            modifier = Modifier.size(26.dp).padding(4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(if (embedded) 3.dp else 8.dp))
                // 列表级滑动协调：同一时刻只允许一行展开，滑开新行时旧行自动收起
                val slideGroup = com.wy.openx.ui.component.rememberSlidableGroupState()
                // 切会话后收起所有已展开行。
                // 不做这一步，滑开一行 → 进聊天页 → 再开抽屉，那行仍停在展开位
                // （抽屉内容一直留在组合里，expanded 状态不会自己重置）。
                LaunchedEffect(currentId) {
                    slideGroup.requestOpen(null)
                }
                // 会话历史按最近活跃时间分组：置顶 / 今天 / 昨天 / 本周某天 / 30 天内 / 更早。
                val grouped = remember(conversations) { groupConversationsByTime(conversations) }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    grouped.forEach { (label, groupItems) ->
                        item(key = "group-$label") {
                            Text(
                                label,
                                color = TextSecondary,
                                fontSize = if (embedded) 10.sp else 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(
                                    start = 6.dp,
                                    top = if (embedded) 5.dp else 10.dp,
                                    bottom = if (embedded) 2.dp else 4.dp
                                )
                            )
                        }
                        items(groupItems, key = { it.id }) { item ->
                            val isActive = item.id == currentId || item.active
                            SlidableRow(
                                modifier = Modifier.padding(vertical = if (embedded) 1.dp else 2.dp),
                                itemId = item.id,
                                groupState = slideGroup,
                                actions = listOf(
                                    SlideAction(icon = Icons.Outlined.Archive) { onToggleArchive(item.id, true) },
                                    SlideAction(
                                        icon = if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin
                                    ) { onTogglePin(item.id, !item.pinned) },
                                    SlideAction(
                                        icon = Icons.Outlined.Delete,
                                        tint = Color(0xFFFF6B6B)
                                    ) { onDeleteConversation(item.id) }
                                ),
                                // 全滑 = 归档
                                onFullSwipe = { onToggleArchive(item.id, true) }
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = androidx.compose.foundation.LocalIndication.current,
                                            onClick = { onSelect(item.id) },
                                            onLongClick = { menuSession = item }
                                        ),
                                    shape = RoundedCornerShape(10.dp),
                                    // 必须给**不透明**底色：滑动操作画在这一层背后，
                                    // 若用 Color.Transparent（第一版就是），抽屉底色直接透上来，
                                    // 三个操作图标在未滑动时就全部可见、与文字重叠。
                                    color = if (isActive) PanelAlt else BgPrimary
                                ) {
                                    Column(modifier = Modifier.padding(if (embedded) 6.dp else 10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // 多会话并行徽标：四态必须**颜色+动效双重可区分**。
                                            // WAITING 用琥珀黄(静止)、DONE 用蓝(静止)：两者原先同为暖色系，
                                            // 在 primary 偏黄的主题下几乎看不出差别，会把「在等你」误读成
                                            // 「已跑完」——而漏看等待态会让 AI 无限期卡住，比看错颜色严重得多。
                                            when (item.badge) {
                                                SessionBadge.WAITING -> Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(Warning)
                                                )
                                                SessionBadge.RUNNING -> {
                                                    // 呼吸圆点：透明度往复渐变，表示「这个会话正在后台跑」
                                                    val pulse = rememberInfiniteTransition(label = "runBadge")
                                                    val a = pulse.animateFloat(
                                                        initialValue = 0.3f, targetValue = 1f,
                                                        animationSpec = infiniteRepeatable(
                                                            animation = tween(600),
                                                            repeatMode = RepeatMode.Reverse
                                                        ),
                                                        label = "runBadgeAlpha"
                                                    )
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(CircleShape)
                                                            .background(Success.copy(alpha = a.value))
                                                    )
                                                }
                                                SessionBadge.DONE -> Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        // 不用 Accent：它跟随主题 primary，在暖色主题下与
                                                        // WAITING 的 Warning 撞色。未读完成固定用蓝。
                                                        .background(Color(0xFF3B82F6))
                                                )
                                                SessionBadge.IDLE -> {}
                                            }
                                            if (item.badge != SessionBadge.IDLE) Spacer(Modifier.width(5.dp))
                                            if (item.pinned) {
                                                Icon(
                                                    Icons.Filled.PushPin,
                                                    contentDescription = "已置顶",
                                                    tint = Accent,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                item.title,
                                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isActive) Accent else TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = if (embedded) 12.sp else 14.sp
                                            )
                                        }
                                        if (item.meta.isNotEmpty()) {
                                            Text(
                                                item.meta,
                                                color = TextSecondary,
                                                fontSize = if (embedded) 10.sp else 11.sp,
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
            1 -> {
                // 用 weight(1f) 约束高度：FileTreeBrowserTab 内部根容器是 fillMaxSize()，
                // 若直接放在 Column 里会吃光剩余高度，把下方 Spacer/Divider/DrawerShortcutBar
                // 压成 0 高导致底部快捷条「消失」。加权重后它只占底部条之外的空间。
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    FileTreeBrowserTab(
                        browseFileSystem = browseFileSystem,
                        remoteConnection = remoteConnection,
                        clipboard = browseClipboard,
                        onClipboardChange = { browseClipboard = it },
                        onOpenInBrowser = onOpenFileInBrowser,
                        onAddToInput = onAttachToInput
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (embedded) 4.dp else 8.dp))
        HorizontalDivider(color = Border)
        Spacer(modifier = Modifier.height(if (embedded) 6.dp else 12.dp))

        // [PLUGIN-MOD] 插件快捷区：插在分隔线与快捷条之间。
        // 无插件时 PluginQuickBar 自身 return，不留空隙。
        if (pluginQuickGroups.isNotEmpty()) {
            com.wy.openx.plugin.PluginQuickBar(
                groups = pluginQuickGroups,
                expanded = pluginQuickBarExpanded,
                onExpandedChange = onPluginQuickBarExpandedChange,
                onAction = onPluginQuickAction,
                onOpenPlugin = onOpenPluginById
            )
            Spacer(modifier = Modifier.height(if (embedded) 6.dp else 10.dp))
        }

        // 底部快捷条：横向等分纯图标胶囊（借鉴 OmniBot 的 HomeDrawer footer：
        // 44 高 / 22 圆角 / 图标 17 / 等分 / 纯图标 + Tooltip / 无选中态）。
        // 颜色改用本项目主题 token（Panel 比 BgPrimary 浅一层，天然形成「浮起」的胶囊），
        // 不硬编码色值以保持多主题一致。
        DrawerShortcutBar(
            items = listOf(
                DrawerShortcut(Icons.Outlined.Settings, "设置", onOpenSettings),
                DrawerShortcut(Icons.Outlined.Psychology, "记忆管理", onOpenMemoryManage),
                DrawerShortcut(Icons.Outlined.Hub, "MCP 管理", onOpenMcpManage),
                DrawerShortcut(Icons.Outlined.Stars, "技能管理", onOpenSkillManage),
                DrawerShortcut(Icons.Outlined.Schema, "工作流管理", onOpenWorkflow),
                // [PLUGIN-MOD] 插件管理入口（按用户定案：先加到这排，不引入横滑）
                DrawerShortcut(Icons.Outlined.Extension, "插件管理", onOpenPluginManage)
            )
        )
    }

    menuSession?.let { session ->
        SessionActionSheet(
            session = session,
            onRename = {
                menuSession = null
                onRequestRename(session)
            },
            onSearch = {
                menuSession = null
                onSearchMessages(session)
            },
            onDismiss = { menuSession = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionSheet(
    session: ConversationItem,
    onRename: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            Text(
                session.title,
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
            )
            // 置顶与删除都已移到侧边栏「左滑出操作」里（用户要求从长按弹窗去掉），
            // 此处只留不适合放左滑的两项：搜索、重命名。
            SessionActionRow(Icons.Default.Search, "搜索聊天记录", Accent, onClick = { onDismiss(); onSearch() })
            SessionActionRow(Icons.Filled.Edit, "重命名", TextPrimary, onClick = { onDismiss(); onRename() })
        }
    }
}

@Composable
private fun SessionActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
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

/**
 * 会话聊天记录搜索：输入关键词 → 列出命中消息 → 点击跳转到该会话对应位置。
 * 匹配在 ViewModel 侧对全量消息做大小写不敏感检索（content / 工具名 / 工具参数 / 工具结果 / 步骤）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageSearchDialog(
    sessionTitle: String,
    onSearch: (String, (List<ChatMessageModel>) -> Unit) -> Unit,
    onJump: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChatMessageModel>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }

    fun runSearch(text: String) {
        val q = text.trim()
        searching = true
        searched = false
        onSearch(q) { hits ->
            results = hits
            searching = false
            searched = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(16.dp),
            color = Panel
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("搜索聊天记录", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    sessionTitle,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("输入关键词", color = TextSecondary) },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; results = emptyList(); searched = false }) {
                                Icon(Icons.Default.Close, contentDescription = "清空", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Accent,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border
                    )
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { runSearch(query) },
                        enabled = query.isNotBlank() && !searching,
                        shape = RoundedCornerShape(9.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = ButtonFgOnFill)
                    ) {
                        Text("搜索", color = ButtonFgOnFill, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (searched && !searching) {
                        Text("${results.size} 条结果", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Border)
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        searching -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Accent)
                        }
                        !searched -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("输入关键词后点「搜索」", color = TextSecondary, fontSize = 13.sp)
                        }
                        results.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("没有找到匹配的消息", color = TextSecondary, fontSize = 13.sp)
                        }
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(results, key = { it.id }) { msg ->
                                MessageSearchResultRow(
                                    msg = msg,
                                    keyword = query.trim(),
                                    onClick = { onJump(msg.id) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("关闭", color = TextSecondary)
                }
            }
        }
    }
}

/** 单条搜索结果：角色标签 + 时间 + 命中片段（关键词高亮）。 */
@Composable
private fun MessageSearchResultRow(
    msg: ChatMessageModel,
    keyword: String,
    onClick: () -> Unit
) {
    val roleLabel = when (msg.role) {
        MessageRole.USER -> "你"
        MessageRole.SYSTEM -> if (msg.toolName != null) "工具" else "系统"
        MessageRole.ASSISTANT -> if (msg.toolName != null) "工具" else "AI"
    }
    val body = buildString {
        if (msg.toolName != null) append("[${msg.toolName}] ")
        when {
            msg.content.isNotBlank() -> append(msg.content)
            msg.toolResult != null -> append(msg.toolResult)
            msg.toolArguments != null -> append(msg.toolArguments)
            msg.steps.isNotEmpty() -> append(msg.steps.joinToString("\n"))
        }
    }.replace("\n", " ").trim()

    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(roleLabel, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(msg.time, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text("定位 ›", color = Accent, fontSize = 11.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = highlightKeyword(body, keyword),
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 在预览文本中把关键词包成 Accent 加粗高亮（大小写不敏感）。 */
private fun highlightKeyword(text: String, keyword: String): AnnotatedString {
    if (keyword.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val idx = text.indexOf(keyword, start, ignoreCase = true)
            if (idx < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, idx))
            withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.Bold)) {
                append(text.substring(idx, idx + keyword.length))
            }
            start = idx + keyword.length
        }
    }
}

/**
 * 侧边栏顶部 Tab 切换条（会话 / 文件）：玻璃态分段控件。
 *
 * 视觉与「提供商编辑页」的 FloatingTabBar 对齐（用户要求统一）：
 * 玻璃渐变底 + Accent 渐变指示器 + 弹簧动画；**尺寸保持侧边栏原样**
 *（全宽 × 34dp），不套用底部栏的 68dp 悬浮形态。
 *
 * 选中态前景色必须用 `onPrimary` 而非 `Accent` —— 指示器底色就是 Accent，
 * 同色叠同色对比度 1:1，字会完全看不见（提供商页刚修过同一问题）。
 */
@Composable
private fun DrawerTopTabs(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    // 图标 + 文案；图标用描边款（与界面其余地方一致，用户要求）
    val items = listOf(
        Icons.Outlined.ChatBubble to "会话",
        Icons.Outlined.Folder to "文件"
    )

    val isLight = BgPrimary.luminance() > 0.5f

    // 玻璃底：与 FloatingTabBar 同配方（浅色主题用 surface，深色用 surfaceVariant）
    val glassBackgroundBrush = remember(isLight) {
        if (isLight) {
            Brush.verticalGradient(listOf(BgSecondary.copy(alpha = 0.94f), BgSecondary.copy(alpha = 0.90f)))
        } else {
            Brush.verticalGradient(listOf(Panel.copy(alpha = 0.92f), BgSecondary.copy(alpha = 0.88f)))
        }
    }
    val glassBorderBrush = remember(isLight) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.14f))
            )
        } else {
            Brush.verticalGradient(
                listOf(Border.copy(alpha = 0.55f), Border.copy(alpha = 0.20f), BgPrimary.copy(alpha = 0.60f))
            )
        }
    }
    val indicatorBrush = remember(isLight) {
        if (isLight) {
            Brush.verticalGradient(listOf(Accent.copy(alpha = 0.95f), Accent.copy(alpha = 0.85f)))
        } else {
            Brush.verticalGradient(listOf(Accent.copy(alpha = 0.90f), Accent.copy(alpha = 0.72f)))
        }
    }
    val indicatorBorderBrush = remember(isLight) {
        Brush.verticalGradient(
            listOf(
                if (isLight) Color.Black.copy(alpha = 0.08f) else Accent.copy(alpha = 0.35f),
                if (isLight) Color.Black.copy(alpha = 0.02f) else Accent.copy(alpha = 0.08f)
            )
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(glassBackgroundBrush)
            .border(1.dp, glassBorderBrush, RoundedCornerShape(9.dp))
            .padding(3.dp)
    ) {
        val thumbWidth = maxWidth / items.size
        val thumbOffset by animateDpAsState(
            targetValue = thumbWidth * selected,
            animationSpec = spring(dampingRatio = 0.76f, stiffness = 380f),
            label = "drawer-tab-thumb"
        )
        // 指示器：Accent 玻璃渐变
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(7.dp))
                .background(indicatorBrush)
                .border(1.dp, indicatorBorderBrush, RoundedCornerShape(7.dp))
        )
        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { index, (icon, label) ->
                val isSelected = selected == index
                // 选中：onPrimary（叠在 Accent 上的语义对比色）；未选中：次级文字色
                val fgColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else TextSecondary,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "drawer-tab-fg"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = fgColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = fgColor
                        )
                    }
                }
            }
        }
    }
}

/** 文件 Tab：浏览容器根目录（rootfs + 绑定合并视图）。完整操作：导航 / 新建文件 / 新建文件夹 / 刷新 / 复制 / 剪切 / 粘贴 / 重命名 / 删除。 */
@Composable
private fun FileBrowserTab(
    browseFileSystem: com.wy.openx.container.BrowseFileSystem?,
    clipboard: BrowseClipboard? = null,
    onClipboardChange: (BrowseClipboard?) -> Unit = {}
) {
    var path by remember { mutableStateOf("/") }
    var state by remember { mutableStateOf<com.wy.openx.container.FileBrowseState>(com.wy.openx.container.FileBrowseState.Loading) }
    var loadVersion by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf<CreateKind?>(null) }
    var menuEntry by remember { mutableStateOf<com.wy.openx.container.FileEntry?>(null) }
    var pendingRename by remember { mutableStateOf<com.wy.openx.container.FileEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<com.wy.openx.container.FileEntry?>(null) }
    var pendingOpen by remember { mutableStateOf<com.wy.openx.container.FileEntry?>(null) }

    LaunchedEffect(path, loadVersion) {
        val fs = browseFileSystem ?: return@LaunchedEffect
        state = com.wy.openx.container.FileBrowseState.Loading
        state = withContext(Dispatchers.IO) {
            try {
                com.wy.openx.container.FileBrowseState.Success(fs.listFiles(path))
            } catch (e: Exception) {
                FileLogger.w("FileBrowser", "listFiles($path) 失败: ${e.message}")
                com.wy.openx.container.FileBrowseState.Error(e.message)
            }
        }
    }

    val pasteToCurrentDir: () -> Unit = {
        val fs = browseFileSystem
        val clip = clipboard
        if (fs != null && clip != null) {
            val target = "$path/${clip.sourceName}"
            runCatching {
                if (clip.isCut) fs.move(clip.sourcePath, target)
                else fs.copy(clip.sourcePath, target)
            }.onSuccess {
                onClipboardChange(null)
            }.onFailure { FileLogger.w("FileBrowser", "粘贴失败: ${it.message}") }
        }
        loadVersion++
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileBreadcrumb(path = path, onNavigate = { target -> path = target }, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { creating = CreateKind.FILE },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = "新建文件", modifier = Modifier.size(16.dp), tint = TextSecondary)
            }
            IconButton(
                onClick = { creating = CreateKind.FOLDER },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹", modifier = Modifier.size(16.dp), tint = TextSecondary)
            }
            if (clipboard != null) {
                IconButton(
                    onClick = { pasteToCurrentDir() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "粘贴到当前目录", modifier = Modifier.size(16.dp), tint = Accent)
                }
            }
            IconButton(
                onClick = { loadVersion++ },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新", modifier = Modifier.size(16.dp), tint = TextSecondary)
            }
        }
        if (clipboard != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Accent.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
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
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "点击粘贴 ▶",
                        color = Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { pasteToCurrentDir() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { onClipboardChange(null) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "取消", tint = TextSecondary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        if (path.trim().trimEnd('/') !in setOf("", "/")) {
            FileBrowserRow(
                icon = Icons.Default.ArrowUpward,
                label = "上一级",
                emphasized = true,
                onClick = { parent@ path = path.substringBeforeLast('/', "/").ifBlank { "/" } }
            )
        }
        when (val s = state) {
            is com.wy.openx.container.FileBrowseState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            is com.wy.openx.container.FileBrowseState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = s.detail ?: "加载失败",
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            is com.wy.openx.container.FileBrowseState.Success -> if (s.entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空目录", color = TextSecondary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(s.entries, key = { it.name }) { entry ->
                        FileBrowserRow(
                            icon = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            label = entry.name,
                            emphasized = entry.isDirectory,
                            subtitle = fileRowSubtitle(entry),
                            onClick = {
                                val child = entry.overridePath ?: "$path/${entry.name}"
                                if (entry.isDirectory) path = child else pendingOpen = entry
                            },
                            onLongClick = { menuEntry = entry }
                        )
                    }
                }
            }
        }
    }

    creating?.let { kind ->
        FileNameInputDialog(
            title = if (kind == CreateKind.FILE) "新建文件" else "新建文件夹",
            confirmLabel = "创建",
            initialName = "",
            onConfirm = { name ->
                runCatching {
                    val target = "$path/$name"
                    if (kind == CreateKind.FILE) browseFileSystem?.writeFile(target, "", false)
                    else browseFileSystem?.mkdirs(target)
                }.onFailure { FileLogger.w("FileBrowser", "创建失败: ${it.message}") }
                creating = null
                loadVersion++
            },
            onDismiss = { creating = null }
        )
    }

    menuEntry?.let { entry ->
        FileEntryActionSheet(
            name = entry.name,
            onCopy = {
                val src = entry.overridePath ?: "$path/${entry.name}"
                onClipboardChange(BrowseClipboard(sourcePath = src, sourceName = entry.name, isCut = false))
                menuEntry = null
            },
            onCut = {
                val src = entry.overridePath ?: "$path/${entry.name}"
                onClipboardChange(BrowseClipboard(sourcePath = src, sourceName = entry.name, isCut = true))
                menuEntry = null
            },
            onPasteHere = if (clipboard != null) {
                val action: () -> Unit = {
                    val dstParent = entry.overridePath ?: "$path/${entry.name}"
                    val target = if (entry.isDirectory) {
                        "$dstParent/${clipboard!!.sourceName}"
                    } else {
                        "$path/${clipboard!!.sourceName}"
                    }
                    browseFileSystem?.let { fs ->
                        runCatching {
                            val c = clipboard!!
                            if (c.isCut) fs.move(c.sourcePath, target)
                            else fs.copy(c.sourcePath, target)
                        }.onSuccess {
                            onClipboardChange(null)
                        }.onFailure { FileLogger.w("FileBrowser", "粘贴失败: ${it.message}") }
                        menuEntry = null
                        loadVersion++
                    }
                }
                action
            } else null,
            onRename = {
                menuEntry = null
                pendingRename = entry
            },
            onDelete = {
                menuEntry = null
                pendingDelete = entry
            },
            onDismiss = { menuEntry = null }
        )
    }

    pendingRename?.let { entry ->
        FileNameInputDialog(
            title = "重命名",
            confirmLabel = "重命名",
            initialName = entry.name,
            onConfirm = { name ->
                runCatching {
                    val src = entry.overridePath ?: "$path/${entry.name}"
                    val dst = "$path/$name"
                    browseFileSystem?.rename(src, dst)
                }.onFailure { FileLogger.w("FileBrowser", "重命名失败: ${it.message}") }
                pendingRename = null
                loadVersion++
            },
            onDismiss = { pendingRename = null }
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除") },
            text = {
                Text(if (entry.isDirectory) "删除文件夹「${entry.name}」及其全部内容？" else "删除文件「${entry.name}」？")
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val target = entry.overridePath ?: "$path/${entry.name}"
                        browseFileSystem?.deleteRecursively(target)
                    }.onFailure { FileLogger.w("FileBrowser", "删除失败: ${it.message}") }
                    pendingDelete = null
                    loadVersion++
                }) { Text("删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", color = TextSecondary) }
            }
        )
    }

    pendingOpen?.let { entry ->
        val openPath = entry.overridePath ?: "$path/${entry.name}"
        FileViewerSheet(
            path = openPath,
            name = entry.name,
            browseFileSystem = browseFileSystem,
            onDismiss = { pendingOpen = null },
            onSaved = { loadVersion++ }
        )
    }
}

/**
 * 文件查看/编辑弹窗：读取容器内文本文件。
 * - 顶部单一工具条：Markdown 渲染/源码切换、自动换行、编辑/预览、撤销/重做、复制、保存、关闭；
 * - Markdown 默认渲染预览；源码视图行号列固定、长行整树共享横向滚动；
 * - 大文件：读取上限 16MB，超出部分截断并给出提示（截断时禁用编辑），
 *   源码预览用 LazyColumn 按行虚拟化，避免一次性组合上万行卡顿；
 * - 二进制文件由 [com.wy.openx.container.BrowseFileSystem.readFile] 拦截并给出提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileViewerSheet(
    path: String,
    name: String,
    browseFileSystem: com.wy.openx.container.BrowseFileSystem?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    /**
     * 打开后定位到的行（1-based，来自聊天里的 `文件:行号` 链接）。
     * 0 = 不定位。Markdown 默认进渲染预览，此时不跳（行号只对源码视图有意义），
     * 用户切到源码后仍会停在顶部——这是刻意的：预览与行号不同源，跳了也对不上。
     */
    initialLine: Int = 0
) {
    val isMarkdown = remember(name) {
        name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
    }
    var isEditing by remember { mutableStateOf(false) }
    var showRendered by remember { mutableStateOf(isMarkdown) }
    var wordWrap by remember { mutableStateOf(true) }
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var originalText by remember { mutableStateOf<String?>(null) }
    var showUnsaved by remember { mutableStateOf(false) }
    var copyHint by remember { mutableStateOf(false) }
    // 实际字节数（用于状态栏）；truncated 表示只读取了前 N 字节，编辑会丢内容故禁用。
    var fileBytes by remember { mutableStateOf(0L) }
    var truncated by remember { mutableStateOf(false) }
    var editHint by remember { mutableStateOf<String?>(null) }
    // 编辑器设置（字号 / 换行箭头 / 空白符号）：持久化，下次打开文件沿用。
    val editorContext = LocalContext.current.applicationContext
    val editorSettings = remember { EditorSettingsStore(editorContext) }
    var fontSizeSp by remember { mutableStateOf(editorSettings.fontSizeSp) }
    var showWrapArrow by remember { mutableStateOf(editorSettings.showWrapArrow) }
    var showWhitespace by remember { mutableStateOf(editorSettings.showWhitespace) }
    var showEditorPosition by remember { mutableStateOf(editorSettings.showEditorPosition) }
    var showEditorStatusBar by remember { mutableStateOf(editorSettings.showEditorStatusBar) }
    var showEditorSettings by remember { mutableStateOf(false) }
    // Sora 编辑器句柄：撤销/重做走原生 UndoManager，不再手写撤销栈。
    val editorHandle = remember { SoraEditorHandle() }
    // 撤销/重做按钮的可用状态（由编辑器事件驱动，仅编辑态展示）。
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var cursorLine by remember { mutableStateOf(1) }
    var cursorColumn by remember { mutableStateOf(1) }
    var selectedCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current

    val loadLimit = remember { browseFileSystem?.maxViewableFileSize() ?: (16L * 1024 * 1024) }
    val dirty = content != null && content != originalText
    val fileSize = fileBytes


    LaunchedEffect(path) {
        content = null; error = null; isEditing = false
        showRendered = isMarkdown
        wordWrap = true
        canUndo = false; canRedo = false
        cursorLine = 1; cursorColumn = 1; selectedCount = 0
        showUnsaved = false; copyHint = false; truncated = false; editHint = null
        val fs = browseFileSystem ?: run { error = "文件系统未就绪"; return@LaunchedEffect }
        val totalBytes = withContext(Dispatchers.IO) {
            runCatching { fs.hostFile(path).length() }.getOrDefault(0L)
        }
        fileBytes = totalBytes
        val result = withContext(Dispatchers.IO) {
            runCatching { fs.readFile(path, loadLimit) }
        }
        result.onSuccess { text ->
            truncated = totalBytes > loadLimit
            content = text
            originalText = text
        }.onFailure {
            FileLogger.w("FileBrowser", "openFile($path) 失败: ${it.message}")
            error = it.message ?: "读取失败"
        }
    }

    // 「聊天里点 `文件:行号`」的定位：等内容与编辑器都就绪后把光标送到目标行。
    // 依赖 [content] 而非 [path]——编辑器实例随内容重建，只等 path 会早于 setText 而跳空。
    // Markdown 默认渲染预览、源码视图此时并不存在，故仅在源码/编辑态定位（见 initialLine 注释）。
    //
    // 跳过一次就锁死（[lineLocated]）：content 会在每次输入时变化 → 本效果会不断重启，
    // 不锁的话每敲一个字光标都被拽回目标行，看起来就是「打字时光标乱跳」。
    var lineLocated by remember(path) { mutableStateOf(false) }
    LaunchedEffect(content, showRendered, isEditing, isMarkdown) {
        if (lineLocated || initialLine <= 0) return@LaunchedEffect
        if (content.isNullOrEmpty()) return@LaunchedEffect
        if (isMarkdown && showRendered && !isEditing) return@LaunchedEffect // 预览态无行号
        // 让 AndroidView 的 factory 与 update 先跑完，再定位。
        withFrameNanos { }
        editorHandle.gotoLine(initialLine)
        lineLocated = true
    }

    fun enterEdit() {
        if (truncated) {
            editHint = "文件超过 ${formatBytes(loadLimit)}，仅预览前部分内容，无法编辑"
            scope.launch { delay(2600); editHint = null }
            return
        }
        isEditing = true
    }

    /**
     * Sora 文本变化：更新 content（脏标记/保存/统计共用），并同步撤销/重做按钮的可用态。
     *
     * 撤销栈由原生 UndoManager 维护，这里必须以文本变化为驱动把 canUndo/canRedo 带上来；
     * 少了这两行，按钮初值为 false 且永不刷新，会一直置灰、点不到 [undo]/[redo]。
     */
    fun onEdit(newText: String) {
        if (newText == content) return
        content = newText
        canUndo = editorHandle.canUndo()
        canRedo = editorHandle.canRedo()
    }

    fun undo() {
        editorHandle.undo()
        canUndo = editorHandle.canUndo()
        canRedo = editorHandle.canRedo()
    }

    fun redo() {
        editorHandle.redo()
        canUndo = editorHandle.canUndo()
        canRedo = editorHandle.canRedo()
    }

    fun copyAll() {
        clipboardManager.setText(AnnotatedString(content.orEmpty()))
        copyHint = true
        scope.launch { delay(1600); copyHint = false }
    }

    fun saveContent(exitAfter: Boolean = false) {
        val fs = browseFileSystem ?: run { error = "文件系统未就绪"; return }
        val text = content.orEmpty()
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { fs.writeFile(path, text, true) }
            }
            saving = false
            result.onSuccess {
                originalText = text
                onSaved()
                if (exitAfter) {
                    onDismiss()
                } else {
                    isEditing = false
                    showRendered = isMarkdown
                }
            }.onFailure {
                FileLogger.w("FileBrowser", "saveFile($path) 失败: ${it.message}")
                error = it.message ?: "保存失败"
            }
        }
    }

    fun requestClose() {
        if (dirty) showUnsaved = true else onDismiss()
    }

    Dialog(
        onDismissRequest = {
            // 设置子页开着时，返回键先退子页，别直接关掉整个文件查看器。
            if (showEditorSettings) showEditorSettings = false else requestClose()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // 背景已铺到状态栏（见下方 Surface），而 Dialog 走独立 Window、不继承 MainActivity 的
        // edge-to-edge 设置，状态栏图标颜色需按**实际背景亮度**显式指定：深色背景配浅色图标，
        // 浅色背景配深色图标。不指定时系统默认给白色图标，浅色主题下会看不清。
        StatusBarIconTintEffect()
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // 只避让底部系统栏：状态栏区域让背景铺到屏幕顶，
                // 顶部工具条改为自身 statusBarsPadding，避免按钮/标题钻进刘海。
                .navigationBarsPadding(),
            color = BgPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 背景已铺到屏幕顶，这里单独避让状态栏，图标与标题不会被刘海/时间挡住。
                        .statusBarsPadding()
                        .padding(top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (dirty) {
                                Text("*", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                name,
                                color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = if (dirty) 2.dp else 0.dp)
                            )
                        }
                        // 长路径不截断，改为可横向滑动：截断后看不出「哪个工作区 / 哪层目录」，
                        // 而路径往往正是用户唯一能确认当前文件位置的线索。
                        Text(path, color = TextSecondary, fontSize = 11.sp,
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.horizontalScroll(rememberScrollState()))
                    }
                    // 视图/编辑动作集中在标题右侧：Markdown 渲染切换 → 换行 → 编辑/预览
                    // → 撤销/重做 → 复制 → 保存 → 关闭
                    if (content != null && error == null) {
                        if (isMarkdown && !isEditing) {
                            EditorToolIcon(
                                icon = if (showRendered) Icons.Default.Code else Icons.Default.Visibility,
                                hint = if (showRendered) "查看源码" else "渲染预览",
                                active = showRendered,
                                onClick = { showRendered = !showRendered }
                            )
                        }
                        if (!isEditing && !(showRendered && isMarkdown)) {
                            EditorToolIcon(
                                icon = Icons.AutoMirrored.Filled.WrapText,
                                hint = if (wordWrap) "自动换行：已开" else "自动换行：已关",
                                active = wordWrap,
                                onClick = { wordWrap = !wordWrap }
                            )
                        }
                        EditorToolIcon(
                            icon = if (isEditing) Icons.Default.Visibility else Icons.Default.Edit,
                            hint = if (isEditing) "退出编辑，回到预览" else "编辑",
                            active = isEditing,
                            onClick = { if (isEditing) isEditing = false else enterEdit() }
                        )
                        if (isEditing) {
                            EditorToolIcon(
                                icon = Icons.AutoMirrored.Filled.Undo,
                                hint = "撤销",
                                enabled = canUndo,
                                onClick = { undo() }
                            )
                            EditorToolIcon(
                                icon = Icons.AutoMirrored.Filled.Redo,
                                hint = "重做",
                                enabled = canRedo,
                                onClick = { redo() }
                            )
                        }
                    }
                    EditorToolIcon(
                        icon = Icons.Outlined.ContentCopy,
                        hint = "复制全文",
                        onClick = { copyAll() }
                    )
                    if (isEditing) {
                    EditorToolIcon(
                        icon = Icons.Default.Save,
                        hint = if (saving) "保存中…" else "保存",
                        active = dirty,
                        enabled = (!saving),
                        onClick = { saveContent(exitAfter = false) }
                    )
                    }
                    EditorToolIcon(
                        icon = Icons.Outlined.Settings,
                        hint = "编辑器设置",
                        onClick = { showEditorSettings = true }
                    )
                    EditorToolIcon(
                        icon = Icons.Default.Close,
                        hint = "关闭",
                        onClick = { requestClose() }
                    )
                }
                if (copyHint || editHint != null || truncated) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        val msg = when {
                            copyHint -> "已复制全文"
                            editHint != null -> editHint!!
                            else -> "文件过大，仅预览前 ${formatBytes(loadLimit)}（原 ${formatBytes(fileSize)}），已禁用编辑"
                        }
                        Text(msg, color = if (editHint != null || truncated) Warning else Accent, fontSize = 11.sp)
                    }
                }

                HorizontalDivider(color = Border.copy(alpha = 0.5f))

                when {
                    content == null && error == null -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    error != null -> Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            error ?: "未知错误",
                            color = Color(0xFFFF6B6B),
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    else -> {
                        val body = content.orEmpty()
                        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                when {
                                    showRendered && isMarkdown && !isEditing -> {
                                        SelectionContainer(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(scrollState)
                                                .padding(vertical = 8.dp)
                                        ) {
                                            MarkdownContent(text = body, baseFontSize = 14.sp)
                                        }
                                    }
                                    else -> SoraEditorSurface(
                                        text = body,
                                        editable = isEditing,
                                        wordWrap = wordWrap,
                                        fontSizeSp = fontSizeSp,
                                        showWrapArrow = showWrapArrow,
                                        showWhitespace = showWhitespace,
                                        filePath = path,
                                        modifier = Modifier.fillMaxSize(),
                                        handle = editorHandle,
                                        onTextChange = { onEdit(it) },
                                        onCursorChange = { l, c, sel ->
                                            cursorLine = l; cursorColumn = c; selectedCount = sel
                                        }
                                    )
                                }
                            // 光标位置 / 选中字数（选中时显示在右上角浮层；编辑时不再显示浮层，统一走底部状态栏）
                            if (showEditorPosition && selectedCount > 0) {
                                Text(
                                    "已选 $selectedCount 字符 · 行 $cursorLine : 列 $cursorColumn",
                                    color = Accent,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(end = 6.dp, top = 4.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(BgPrimary.copy(alpha = 0.85f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            }
                            if (isEditing) {
                                val eds = editorHandle
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = { eds.moveCursorLeft() }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("←", color = TextPrimary) }
                                    TextButton(onClick = { eds.moveCursorRight() }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("→", color = TextPrimary) }
                                    TextButton(onClick = { eds.commitText("\t") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Tab", color = TextPrimary) }
                                    listOf("{", "}", "(", ")", "[", "]", "\"", "'", "`", ";", ":", ",", ".", "<", ">", "=", "+", "-", "*", "/", "|", "&", "!", "?", "_", "#", "@", "%", "\\").forEach { sym ->
                                        TextButton(onClick = { eds.commitText(sym) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(sym, color = TextPrimary, fontFamily = FontFamily.Monospace) }
                                    }
                                }
                            }
                        }
                        if (showEditorStatusBar) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).padding(bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val lineCount = body.count { it == '\n' } + if (body.isEmpty()) 0 else 1
                                Text(
                                    "$lineCount 行 · ${body.length} 字符 · ${formatBytes(fileSize)} · UTF-8",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (showEditorPosition) {
                                    Text(
                                        "行 $cursorLine : 列 $cursorColumn",
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
        if (showEditorSettings) {
            EditorSettingsPage(
                fontSizeSp = fontSizeSp,
                showWrapArrow = showWrapArrow,
                showWhitespace = showWhitespace,
                showEditorPosition = showEditorPosition,
                showEditorStatusBar = showEditorStatusBar,
                onFontSizeChange = { v ->
                    fontSizeSp = v
                    editorSettings.fontSizeSp = v
                },
                onShowWrapArrowChange = { v ->
                    showWrapArrow = v
                    editorSettings.showWrapArrow = v
                },
                onShowWhitespaceChange = { v ->
                    showWhitespace = v
                    editorSettings.showWhitespace = v
                },
                onShowEditorPositionChange = { v ->
                    showEditorPosition = v
                    editorSettings.showEditorPosition = v
                },
                onShowEditorStatusBarChange = { v ->
                    showEditorStatusBar = v
                    editorSettings.showEditorStatusBar = v
                },
                onBack = { showEditorSettings = false }
            )
        }
    }

    if (showUnsaved) {
        AlertDialog(
            onDismissRequest = { showUnsaved = false },
            title = { Text("未保存的更改") },
            text = { Text("内容已修改，尚未保存。退出前要保存吗？") },
            confirmButton = {
                TextButton(onClick = { showUnsaved = false; saveContent(exitAfter = true) }) {
                    Text("保存并退出", color = Accent)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showUnsaved = false }) { Text("取消", color = TextSecondary) }
                    TextButton(onClick = { showUnsaved = false; onDismiss() }) { Text("不保存", color = TextSecondary) }
                }
            }
        )
    }

}

/**
 * 让当前 [Dialog] 窗口的状态栏图标颜色跟随背景亮度。
 *
 * 背景铺到状态栏之下后，深色背景需要浅色图标、浅色背景需要深色图标，否则看不清时间与电量。
 * 直接按 [BgPrimary] 的相对亮度判定，不依赖主题名或 themeMode，任何配色都成立。
 * `isAppearanceLightStatusBars = true` 表示「用深色图标」，命名与直觉相反，注意别弄反。
 */
@Composable
private fun StatusBarIconTintEffect() {
    val view = LocalView.current
    // 背景色随主题变化，主题切换时要重新判定。
    val background = BgPrimary
    DisposableEffect(background) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let {
            WindowCompat.getInsetsController(it, view)
        }
        val darkIcons = ColorUtils.calculateLuminance(background.toArgb()) > 0.5f
        controller?.isAppearanceLightStatusBars = darkIcons
        onDispose {
            // 窗口即将销毁，仅复位避免复用到同一 Window 时残留浅色图标。
            controller?.isAppearanceLightStatusBars = false
        }
    }
}

/**
 * 文件查看器的编辑器设置**子页**：全屏覆盖在查看器之上，不是弹窗抽屉。
 *
 * 为什么不做 ModalBottomSheet：切「显示自动换行符号」会让 Sora 的
 * FLAG_DRAW_SOFT_WRAP 变化 → createLayout() 销毁并重建整篇布局；抽屉在重组时
 * 底层内容会被重新测量，重建过程直接落在可见区域，表现为整页闪烁。
 * 改成叠在上面的全屏子页后，重建发生在被完全遮挡期间，看不到。
 *
 * 改动仍即时作用于当前编辑器并写入 [EditorSettingsStore]，下次打开文件沿用。
 * 只影响本查看器的 Sora 编辑器，不动顶栏的自动换行开关，也不影响其它页面。
 */
@Composable
private fun EditorSettingsPage(
    fontSizeSp: Int,
    showWrapArrow: Boolean,
    showWhitespace: Boolean,
    showEditorPosition: Boolean,
    showEditorStatusBar: Boolean,
    onFontSizeChange: (Int) -> Unit,
    onShowWrapArrowChange: (Boolean) -> Unit,
    onShowWhitespaceChange: (Boolean) -> Unit,
    onShowEditorPositionChange: (Boolean) -> Unit,
    onShowEditorStatusBarChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        color = BgPrimary
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text("编辑器设置", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text("字体大小  $fontSizeSp sp", color = TextSecondary, fontSize = 12.sp)
                Slider(
                    value = fontSizeSp.toFloat(),
                    onValueChange = { onFontSizeChange(it.roundToInt().coerceIn(EditorSettingsStore.MIN_FONT_SIZE_SP, EditorSettingsStore.MAX_FONT_SIZE_SP)) },
                    valueRange = EditorSettingsStore.MIN_FONT_SIZE_SP.toFloat()..EditorSettingsStore.MAX_FONT_SIZE_SP.toFloat(),
                    steps = EditorSettingsStore.MAX_FONT_SIZE_SP - EditorSettingsStore.MIN_FONT_SIZE_SP - 1
                )
                Spacer(Modifier.height(14.dp))

                EditorSettingSwitchRow(
                    label = "显示自动换行符号",
                    checked = showWrapArrow,
                    onCheckedChange = onShowWrapArrowChange
                )
                EditorSettingSwitchRow(
                    label = "显示空白符号",
                    checked = showWhitespace,
                    onCheckedChange = onShowWhitespaceChange
                )
                EditorSettingSwitchRow(
                    label = "显示光标位置（行：列）",
                    checked = showEditorPosition,
                    onCheckedChange = onShowEditorPositionChange
                )
                EditorSettingSwitchRow(
                    label = "显示底部状态栏（行数、字符数、大小、UTF-8）",
                    checked = showEditorStatusBar,
                    onCheckedChange = onShowEditorStatusBarChange
                )
            }
        }
    }
}

/** 设置面板的一行开关：左标题右 [Switch]，与终端设置面板同一视觉。 */
@Composable
private fun EditorSettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 文件查看器工具条图标按钮：统一尺寸，[active] 高亮、[enabled]=false 变淡。 */
@Composable
private fun EditorToolIcon(
    icon: ImageVector,
    hint: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true
) {
    val tint = when {
        !enabled -> TextSecondary.copy(alpha = 0.3f)
        active -> Accent
        else -> TextSecondary
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = hint, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/** 输入区附件缩略图：图片显示预览，文件显示图标 + 文件名，右上角 × 移除。 */
@Composable
private fun AttachmentThumb(
    attachment: com.wy.openx.ui.state.ChatAttachment,
    onRemove: () -> Unit
) {
    val ctx = LocalContext.current
    val imageUri = (attachment.localPath?.takeIf { it.isNotBlank() } ?: attachment.uri.toString())
    var showPreview by remember { mutableStateOf(false) }
    Box(modifier = Modifier.size(58.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = PanelAlt,
            modifier = Modifier.fillMaxSize()
        ) {
            if (attachment.isImage) {
                var bitmap by remember(imageUri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(imageUri) {
                    // 优先本地副本路径（file:// 单次可靠），再 content URI，走单次读取
                    bitmap = decodeImageUriBest(ctx, imageUri, maxDim = 256, tag = "input")
                }
                val bmp = bitmap
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = attachment.fileName,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showPreview = true }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(22.dp))
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (attachment.fileName.endsWith(".md")) Icons.Default.Description else Icons.Default.AttachFile,
                        contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        attachment.fileName,
                        color = TextSecondary,
                        fontSize = 8.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // 移除按钮
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0xCC000000))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "移除附件", tint = Color.White, modifier = Modifier.size(12.dp))
        }
        if (showPreview && attachment.isImage) {
            ImageViewerSheet(
                name = attachment.fileName,
                subtitle = imageUri,
                onDismiss = { showPreview = false },
                decode = {
                    // localPath 是宿主绝对路径（不是 file:// URI），contentResolver 打不开，
                    // 必须走带「按路径直读文件」兜底的 decodeImageUriBest，否则预览只会报解码失败。
                    val bmp = decodeImageUriBest(ctx, imageUri, maxDim = 2560, tag = "input-view")
                    0L to bmp
                }
            )
        }
    }
}

private enum class CreateKind { FILE, FOLDER }

/** 新建 / 重命名共用的名称输入弹窗：名称非法时禁用确认。 */
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
                enabled = isValidFileEntryName(name),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = ButtonFg)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/** 文件/目录行长按弹出的功能菜单：复制 / 剪切 / 粘贴到此处 / 重命名 / 删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEntryActionSheet(
    name: String,
    onCopy: () -> Unit = {},
    onCut: () -> Unit = {},
    onPasteHere: (() -> Unit)? = null,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = Panel
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp)
            )
            if (onPasteHere != null) {
                SheetActionRow(
                    icon = Icons.Default.ContentPaste,
                    label = "粘贴到此处",
                    tint = Accent,
                    onClick = onPasteHere
                )
            }
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
            SheetActionRow(
                icon = Icons.Default.Edit,
                label = "重命名",
                tint = TextPrimary,
                onClick = onRename
            )
            SheetActionRow(
                icon = Icons.Default.Delete,
                label = "删除",
                tint = Color(0xFFFF6B6B),
                onClick = onDelete
            )
        }
    }
}

/** 队列消息卡片（老版本样式）：输入框上方内联列表，支持一键立即发送/逐条立即发送/删除与一键清空。 */
@Composable
private fun QueuedRequestPanel(
    queued: List<String>,
    onSend: (Int) -> Unit,
    onSendAll: () -> Unit = {},
    onRemove: (Int) -> Unit,
    onClearAll: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Accent.copy(alpha = 0.08f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Column {
            // 右侧操作列固定等宽并右对齐，保证标题行与每条消息的按钮垂直对齐
            val actionsWidth = 88.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "排队队列(${queued.size}条)",
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier.width(actionsWidth),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "立即发送",
                        color = Accent.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSendAll() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "清空",
                        color = Color(0xFFFF6B6B),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onClearAll() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                itemsIndexed(queued) { index, text ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}. ",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Text(
                            text,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            modifier = Modifier.width(actionsWidth),
                            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "立即发送",
                                tint = Accent,
                                modifier = Modifier
                                    .size(15.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onSend(index) }
                            )
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除",
                                tint = TextSecondary.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(15.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onRemove(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, color = tint, fontSize = 15.sp)
        }
    }
}

/** 面包屑：每一段可点回该层，横向可滚以容纳深路径（容器绝对路径 `/etc/apk`、`/root/workspace/…`）。 */
@Composable
private fun FileBreadcrumb(
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val segments = remember(path) { buildBreadcrumbSegments(path) }
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(segments) { index, segment ->
            val (label, target) = segment
            val isCurrent = index == segments.lastIndex
            if (index > 0) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(if (isCurrent) Modifier else Modifier.clickable { onNavigate(target) })
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) TextPrimary else Accent,
                    maxLines = 1
                )
            }
        }
    }
}

/** 文件浏览的单行：目录 / 文件 / 上一级均复用。左侧图标，右侧（名称 + 修改时间·大小）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileBrowserRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    emphasized: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (emphasized) Accent else TextSecondary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 文件行副标题：目录只显修改时间，文件显「修改时间 · 大小」。 */
private fun fileRowSubtitle(entry: com.wy.openx.container.FileEntry): String? {
    val time = if (entry.lastModified > 0) formatModifiedTime(entry.lastModified) else null
    if (entry.isDirectory) return time
    val size = formatBytes(entry.size)
    return if (time != null) "$time  · $size" else size
}

/** 修改时间格式化为本地时区的 yyyy-MM-dd HH:mm。 */
private fun formatModifiedTime(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

/** 字节数格式化为可读大小（B/KB/MB/GB）。 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
}

/** 容器绝对路径拆成可点击的面包屑段 [(显示名, 目标路径)]；根段为 `/`。 */
private fun buildBreadcrumbSegments(path: String): List<Pair<String, String>> {
    val cleaned = path.trim().trimEnd('/')
    if (cleaned.isEmpty() || cleaned == "/") return listOf("/" to "/")
    if (!cleaned.startsWith("/")) return listOf(cleaned to cleaned)
    return buildList {
        add("/" to "/")
        var accumulated = ""
        for (name in cleaned.removePrefix("/").split('/')) {
            accumulated += "/$name"
            add(name to accumulated)
        }
    }
}

/** 名称合法性：非空、不含斜杠/反斜杠、不是 . 或 ..。 */
internal fun isValidFileEntryName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() &&
        !trimmed.contains('/') &&
        !trimmed.contains('\\') &&
        trimmed != "." &&
        trimmed != ".."
}

/** 底部快捷条的一项：图标 + 无障碍/长按提示文案 + 点击回调。 */
private data class DrawerShortcut(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val onClick: () -> Unit
)

/**
 * 侧边栏底部快捷条：横向等分的纯图标胶囊。
 *
 *  `HomeDrawer._buildFooterShortcutBar`（Flutter）：
 * 容器 44dp 高、22dp 圆角（= 高度/2，完全胶囊化）、图标 17dp、每项等分宽度、
 * 纯图标无文字（label 仅作无障碍描述与长按提示）、无选中态、无分割线、无阴影。
 * 视觉上靠「胶囊底色比抽屉底色浅一层」形成浮起感——底色用 [Panel]，抽屉底是 BgPrimary。
 */
@Composable
private fun DrawerShortcutBar(items: List<DrawerShortcut>) {
    if (items.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Panel),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { item.onClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = TextSecondary,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun BottomSheetContent(
    stats: List<com.wy.openx.data.model.StatModel>,
    logs: List<com.wy.openx.data.log.LogEntry>,
    memoryItems: List<com.wy.openx.data.store.MemoryItem> = emptyList(),
    onExecuteCommand: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenMemoryManage: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF3A4560))
                .align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(4.dp))

        InfoPanel("运行状态", "在线") {
            if (stats.isEmpty()) {
                Text("暂无数据", color = TextSecondary, fontSize = 13.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    stats.forEach { stat ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stat.label, color = TextSecondary, fontSize = 13.sp)
                            Text(stat.value, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        InfoPanel("执行日志", "实时") {
            if (logs.isEmpty()) {
                Text("暂无日志", color = TextSecondary, fontSize = 13.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    logs.takeLast(20).forEach { log ->
                        Text("[${log.tag}] ${log.message}", color = TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }

        val memoryExpanded = remember { mutableStateOf(false) }
        Card(
            colors = CardDefaults.cardColors(containerColor = PanelAlt),
            onClick = { memoryExpanded.value = !memoryExpanded.value }
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("记忆", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (memoryExpanded.value) "▼" else "▶", color = TextSecondary, fontSize = 10.sp)
                    }
                    Text("${memoryItems.size}条", color = TextSecondary)
                }
                AnimatedVisibility(visible = memoryExpanded.value) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        MemoryPanel(memoryItems, maxItems = 4)
                        if (memoryItems.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                onClick = onOpenMemoryManage,
                                shape = RoundedCornerShape(8.dp),
                                color = Accent.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("管理记忆", modifier = Modifier.padding(12.dp), color = Accent, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        InfoPanel("快捷命令", "") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BUILTIN_SLASH_COMMANDS.filter { it.quick }.forEach { spec ->
                    Surface(
                        onClick = { onExecuteCommand(spec.name); onDismiss() },
                        shape = RoundedCornerShape(8.dp),
                        color = PanelAlt,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(spec.name, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(spec.description, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommandPaletteOverlay(
    onDismiss: () -> Unit,
    onExecuteCommand: (String) -> Unit,
    agentMode: String
) {
    var searchQuery by remember { mutableStateOf("") }
    val allCommands = BUILTIN_SLASH_COMMANDS.map {
        it.name to if (it.name == "/mode") {
            "切换至" + when (agentMode) {
                "build" -> "规划模式"
                "plan" -> "股票模式"
                "stock" -> "聊天模式"
                "chat" -> "构建模式"
                else -> "构建模式"
            }
        } else it.description
    }
    val filtered = if (searchQuery.isBlank()) allCommands else allCommands.filter { it.first.contains(searchQuery, ignoreCase = true) || it.second.contains(searchQuery, ignoreCase = true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.55f),
            shape = RoundedCornerShape(20.dp),
            color = BgPrimary
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("命令面板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = TextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索命令...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        cursorColor = Accent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(filtered) { _, (cmd, desc) ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onExecuteCommand(cmd)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = Panel
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(cmd, fontWeight = FontWeight.SemiBold, color = Accent, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(desc, color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
/**
 * 任务面板：嵌入输入框圆角容器内部，背景/圆角由输入框容器提供。
 * - 折叠态只有一行：📋「任务」+ 完成/总数；点击展开/收起。
 * - 展开态：进度条 + 逐条任务（进行中=前置灰脉动圆点+灰字；已完成=绿点+划线；取消=灰实心圆+划线；待办=空心圆点）。
 * - 有未完成任务时自动展开，全部完成/取消后自动折叠；任务为空不渲染任何内容。
 */
private fun TaskPanel(
    tasks: List<TaskItem>,
    tasksExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onUpdateStatus: (Long, TaskStatus) -> Unit,
    onRemove: (Long) -> Unit
) {
    if (tasks.isEmpty()) return
    val total = tasks.size
    val completed = tasks.count { it.status == TaskStatus.COMPLETED }
    val allDone = tasks.all { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.CANCELLED }
    val progress = if (total == 0) 0f else completed.toFloat() / total

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpanded() }
                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Checklist,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "任务待办",
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))

            // 进度胶囊：显示「N/M 完成」，
            // 全部完成时换成「全部完成」并换用成功色，一眼能看出收工了。
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (allDone && total > 0) Success.copy(alpha = 0.18f) else PanelAlt.copy(alpha = 0.5f)
            ) {
                Text(
                    text = if (allDone && total > 0) "全部完成" else "$completed/$total 完成",
                    color = if (allDone && total > 0) Success else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // 折叠时在剩余空间显示「当前进行中的任务」，不必展开就能知道在做什么。
            val inProgress = tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
            if (!tasksExpanded && inProgress != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = inProgress.description,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            Text(
                text = if (tasksExpanded) "▲" else "▼",
                fontSize = 10.sp,
                color = TextSecondary
            )
        }
        if (tasksExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp)
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp)),
                    color = Accent,
                    trackColor = Border.copy(alpha = 0.4f)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 128.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            ) {
                // 按 order 升序显示（order 由快照式 todo 工具的数组下标给出）。
                // **不依赖列表本身的先后**：并发写入时列表顺序不可靠，
                // 显式排序才能保证面板顺序稳定（这正是「任务排序乱」的修法之一）。
                tasks.sortedBy { it.order }.forEach { task ->
                    TaskPanelRow(task = task, onUpdateStatus = onUpdateStatus, onRemove = onRemove)
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            color = Border
        )
    }
}

/**
 * 单条任务行：状态圆点 + 标题。
 * - 状态圆点：进行中=前置灰脉动圆点（仿工具调用），已完成=绿点，取消=灰实心圆，待办=空心圆点。
 * - 操作：待办可 ▶ 开始，未完成保留 ✕ 关闭/删除（已完成/取消的条目无操作按钮）。
 */
@Composable
private fun TaskPanelRow(
    task: TaskItem,
    onUpdateStatus: (Long, TaskStatus) -> Unit,
    onRemove: (Long) -> Unit
) {
    val isCompleted = task.status == TaskStatus.COMPLETED
    val isInProgress = task.status == TaskStatus.IN_PROGRESS
    val isCancelled = task.status == TaskStatus.CANCELLED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            isCompleted -> {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Success)
                )
            }
            isInProgress -> {
                val transition = rememberInfiniteTransition(label = "task-panel-dot")
                val alpha by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(650),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "task-panel-dot-alpha"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { this.alpha = alpha }
                        .clip(CircleShape)
                        .background(TextSecondary)
                )
            }
            isCancelled -> {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(TextSecondary.copy(alpha = 0.5f))
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .border(1.dp, Border, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = task.description,
            color = if (isCompleted || isInProgress || isCancelled) {
                TextSecondary.copy(alpha = 0.6f)
            } else {
                TextPrimary
            },
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (isCompleted || isCancelled) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f)
        )
        if (task.status == TaskStatus.PENDING) {
            IconButton(onClick = { onUpdateStatus(task.id, TaskStatus.IN_PROGRESS) }, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        if (!isCompleted && !isCancelled) {
            IconButton(onClick = { onRemove(task.id) }, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

private fun isDiffContent(text: String): Boolean {
    if (text.length > 50000) return false
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines.size < 2) return false
    // 仅统计明确的 +/- 行（不再把两空格缩进当 diff，避免误判普通缩进内容）
    val plus = lines.count { it.startsWith("+ ") }
    val minus = lines.count { it.startsWith("- ") }
    if (plus < 1 || minus < 1) return false
    return (plus + minus) >= lines.size * 0.4
}

@Composable
private fun DiffView(diffText: String, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp) {
    Column {
        diffText.lines().forEach { line ->
            val color = when {
                line.startsWith("+ ") -> Color(0xFF4CAF50).copy(alpha = 0.9f)
                line.startsWith("- ") -> Color(0xFFE53935).copy(alpha = 0.9f)
                line.startsWith("@@") -> Accent.copy(alpha = 0.7f)
                else -> TextPrimary
            }
            val bg = when {
                line.startsWith("+ ") -> Color(0xFF4CAF50).copy(alpha = 0.08f)
                line.startsWith("- ") -> Color(0xFFE53935).copy(alpha = 0.08f)
                else -> Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    line,
                    color = color,
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = fontSize * 1.4
                )
            }
        }
    }
}

/** 中英文感知的 token 粗估：中文≈1.5 token/字，英文≈4 字符/token，预留标点/空白开销 */
internal fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    val cn = text.count { it.code in 0x4E00..0x9FFF }
    val cjkPunct = text.count { it.code in 0x3000..0x303F || it.code in 0xFF00..0xFFEF }
    val other = text.length - cn - cjkPunct
    // 中文估算（含中文标点），英文按 4 字符/token，再加 10% 保守系数
    val raw = cn + cjkPunct + other / 4
    return (raw * 11 / 10).coerceAtLeast(1)
}

/** 读取图片 EXIF 旋转角度（internal：图片查看器 ImageViewerSheet 亦复用）。 */
internal fun exifRotationDegrees(stream: java.io.InputStream): Float = try {
    val exif = android.media.ExifInterface(stream)
    when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
} catch (_: Exception) { 0f }

/**
 * 外观设置里的自定义聊天文本色。
 *
 * 仅当模式为 custom 且色号能解析时返回颜色，否则 null（= 跟随主题，这是默认行为）。
 * 色号非法时静默回退到主题色：设置页已就地报错提示，聊天区再多报一次只会打扰。
 */
internal fun chatCustomTextColor(settings: com.wy.openx.data.model.AppSettings): Color? {
    if (settings.chatTextColorMode != "custom") return null
    val s = settings.chatTextHexColor.trim().removePrefix("#")
    return try {
        when (s.length) {
            6 -> Color(0xFF000000L or s.toLong(16))
            8 -> Color(s.toLong(16))
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/** 从字节数组降采样解码图片，并按 EXIF 方向摆正。单次读取，避免 provider 多次 open 失败。 */
internal fun decodeSampledBitmapBytes(bytes: ByteArray, maxDim: Int): android.graphics.Bitmap? {
    if (bytes.isEmpty()) return null
    return try {
        // 1. 读边界
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // 2. 计算 inSampleSize
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDim) sampleSize *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        // 3. EXIF 摆正
        val rotation = exifRotationDegrees(java.io.ByteArrayInputStream(bytes))
        if (rotation != 0f) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
            val rotated = android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }
    } catch (_: Exception) { null }
}

/** 降采样解码 content/file URI 图片：一次性读完字节再解码，单次 provider open，并按 EXIF 摆正（IO 线程执行）。 */
internal suspend fun decodeSampledBitmap(context: Context, uriString: String?, maxDim: Int): android.graphics.Bitmap? {
    if (uriString.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        try {
            val uri = android.net.Uri.parse(uriString)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            decodeSampledBitmapBytes(bytes, maxDim)
        } catch (_: Exception) { null }
    }
}

/** 稳健图片解码：优先 contentResolver（单次读取），失败再按 file:// 路径直读文件，仍失败记日志。 */
internal suspend fun decodeImageUriBest(context: Context, uriString: String?, maxDim: Int, tag: String): android.graphics.Bitmap? {
    if (uriString.isNullOrBlank()) return null
    val viaProvider = decodeSampledBitmap(context, uriString, maxDim)
    if (viaProvider != null) return viaProvider
    // 兜底：解析 file:// 路径直读本地文件（不经过 provider）
    val viaFile = withContext(Dispatchers.IO) {
        val path = runCatching { android.net.Uri.parse(uriString).path }.getOrNull()
        val file = if (path != null) java.io.File(path) else null
        if (file != null && file.exists() && file.isFile) decodeSampledBitmapBytes(file.readBytes(), maxDim) else null
    }
    if (viaFile != null) return viaFile
    FileLogger.w("ImageRender", "图片解码失败($tag): $uriString")
    return null
}

private fun formatTime(timeMs: Long): String {
    return when {
        timeMs < 1000 -> "${timeMs}ms"
        timeMs < 60000 -> String.format(java.util.Locale.US, "%.1fs", timeMs / 1000.0)
        else -> String.format(java.util.Locale.US, "%dm %ds", timeMs / 60000, (timeMs % 60000) / 1000)
    }
}

private fun extractHtmlContent(text: String): String? {
    val htmlBlocks = mutableListOf<String>()
    var inBlock = false
    var currentLang = ""
    val currentLines = mutableListOf<String>()
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            if (inBlock) {
                if (currentLang.contains("html", ignoreCase = true)) {
                    htmlBlocks.add(currentLines.joinToString("\n"))
                }
                currentLang = ""
                currentLines.clear()
                inBlock = false
            } else {
                inBlock = true
                currentLang = line.trimStart().removePrefix("```").trim().lowercase()
            }
        } else if (inBlock) {
            currentLines.add(line)
        }
    }

    if (htmlBlocks.isEmpty()) return null
    val html = htmlBlocks.joinToString("\n\n").trim()
    if (html.isBlank()) return null
    if (!html.contains("<", ignoreCase = false)) return null

    return if (html.startsWith("<!") || html.startsWith("<html", ignoreCase = true)) {
        html
    } else {
        """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body { font-family: system-ui, -apple-system, sans-serif; padding: 16px; line-height: 1.6; }
img { max-width: 100%; height: auto; }
pre { overflow-x: auto; }
</style></head>
<body>$html</body></html>"""
    }
}

sealed class MsgGroup(val key: String) {
    class Single(val msg: ChatMessageModel) : MsgGroup("single_${msg.id}") {
        // equals/hashCode：流式期间 messages 每 150ms 变一次，groups 被整体重建为新 List。
        // 不实现 equals 的话 LazyColumn 认为每一项都变过，于是**整个列表全量重组**；
        // 实现后只有真正改动的那一项（通常是最后一条）会重组，长会话才能稳住帧率。
        override fun equals(other: Any?): Boolean = other is Single && other.msg == msg
        override fun hashCode(): Int = msg.hashCode()
    }

    class ToolCalls(val items: List<ChatMessageModel>) : MsgGroup("tools_${items.firstOrNull()?.id ?: 0}") {
        override fun equals(other: Any?): Boolean = other is ToolCalls && other.items == items
        override fun hashCode(): Int = items.hashCode()
    }

    /**
     * 思考过程，作为**独立 item** 渲染在消息气泡之外。
     *
     * 原来思考内容嵌套在 [Single] 气泡内部（ClaudeMessageBubble 里的 ThinkingCollapse），
     * 与气泡同色半透明叠透、视觉糊在一起，且流式增长时反复改变气泡高度、把滚动锚点带偏。
     * 拆出来后思考有自己的标题行与折叠态，气泡高度只由正文决定。
     */
    class Thinking(
        val msgId: Long,
        val content: String,
        /** 该段是否仍在生成（决定标题显示「正在思考」还是「思考过程用时 X 秒」）。 */
        val streaming: Boolean,
        /**
         * 该段思考的**权威计时**（起止），由 VM 记录、`groupMessages` 带下来；
         * null = 无计时（历史消息，或该段还没被 VM 记录）。
         *
         * 为什么必须外部传入：渲染层原来自己记起点，但流式期间助手消息 id 会变、
         * 列表滚动还会销毁 item，起点被反复重记 → 读数乱跳。
         * 更关键的是**多段思考**（思考→工具→再思考）需要**每段各自的**起止，
         * 那是只有 VM 才知道的信息（见 `ConsoleUiState.thinkingSegments`）。
         */
        val timing: ThinkingTiming? = null,
        /**
         * 该段思考是否已收尾，决定「关闭」开关下思考块该不该收起。
         *
         * 不能只看 [timing]：纯对话（无工具调用）时 VM 要到**整轮结束**才落定 endAt，
         * 那样正文流式期间思考块会一直摊开着。真正的信号是「正文已开始输出」——
         * 正文出现即说明这段思考已经吐完（见 groupMessages 里的计算）。
         */
        val done: Boolean = false
    ) : MsgGroup("thinking_$msgId") {
        // equals **不比 streaming**：它是瞬时 UI 状态（isSending 一变就翻），
        // 计入相等性会让停止瞬间这个 item 被判定为「变了」→ LazyColumn 重组 →
        // 程序性滚动被误认为「用户上翻」，置起 scrollState.userPulledUp，之后自动跟随全部退出。
        //
        // 但 **`timing` 与 `done` 要比**，理由与 streaming 相反：它们每段只变一两次
        //（开段、结束各一次），代价可忽略；而段结束时必须靠这次「变了」触发重组，
        // 标题才会从「正在思考（N秒）」切成「思考过程用时 N秒」，
        // 思考块也才会自动收起。
        // 每秒跳动的读数由 item 内部的 state 驱动，不经过本 equals，不会造成每帧重组。
        override fun equals(other: Any?): Boolean =
            other is Thinking && other.msgId == msgId && other.content == content &&
                other.timing == timing && other.done == done
        override fun hashCode(): Int =
            msgId.hashCode() * 31 + content.hashCode() * 31 + (timing?.hashCode() ?: 0) * 31 + done.hashCode()
    }
}

private fun groupMessages(
    msgs: List<ChatMessageModel>,
    streamingMsgId: Long = -1L,
    /**
     * 每段思考的计时（`uiState.thinkingSegments`，key = 助手消息 id）。
     * 查不到 = 该段无计时（历史消息），UI 只显示「思考过程」不报秒数。
     */
    thinkingSegments: Map<Long, ThinkingTiming> = emptyMap()
): List<MsgGroup> {
    // 工具消息是**单条就地更新**：调用与结果在同一条消息上，靠 toolResult
    // 是否为空区分执行中/完成，无需 call/result 两条消息配对。
    //
    // 兼容旧数据：升级前落库的是「call(assistant) + result(system)」两条，这里把旧 result
    // 合并回对应 call（按 toolCallId），旧 result 消息本身跳过，保证旧会话显示不变。
    val legacyResults = HashMap<String, ChatMessageModel>()
    for (m in msgs) {
        if (m.role == MessageRole.SYSTEM && m.toolResult != null && m.toolCallId != null && m.toolName == null) {
            legacyResults[m.toolCallId!!] = m
        }
    }
    val result = mutableListOf<MsgGroup>()
    val buf = mutableListOf<ChatMessageModel>()
    // 按 id 去重（防御）：停止/并发追加等路径曾产生同 id 双份，会令 tools_<id> 分组 key
    // 重复并导致 LazyColumn「Key already used」崩溃。保留后者（更接近最终状态，如"已停止"）。
    val uniqueMsgs = LinkedHashMap<Long, ChatMessageModel>()
    for (m in msgs) uniqueMsgs[m.id] = m
    val ms = uniqueMsgs.values.toList()
    for (msg in ms) {
        when {
            msg.toolName != null -> {
                // 工具消息（新格式单条，或旧格式 call）。旧格式 call 的 toolResult 为空，
                // 这里从 legacyResults 补回结果，使其与新格式一致。
                val merged = if (msg.toolResult == null && msg.toolCallId != null) {
                    legacyResults[msg.toolCallId]?.let { r ->
                        msg.copy(toolResult = r.toolResult, content = r.content)
                    } ?: msg
                } else msg
                buf.add(merged)
            }
            msg.role == MessageRole.SYSTEM && msg.toolResult != null && msg.toolName == null -> {
                // 旧格式 result 消息：已在上方合并进 call，这里跳过不单独渲染
            }
            else -> {
                if (buf.isNotEmpty()) { result.add(MsgGroup.ToolCalls(buf.toList())); buf.clear() }
                // 助手消息可能同时含思考过程与正文（思考以<thinking> 内联在content 里）。
                // 拆成两个平级 item：先思考块、再正文气泡。思考内容已由独立 item 承载，
                // 气泡内部的 ThinkingCollapse 相应移除（否则会重复渲染两份）。
                // 纯思考（正文为空）时只出思考块，不产生空气泡。
                if (msg.role == MessageRole.ASSISTANT && !msg.isSteps) {
                    // 流式中那条 content 每帧都在变：不缓存（缓存它只会把历史消息的条目挤出去，
                    // 反而让每帧重算全量）。历史消息稳定不变，缓存收益最大。
                    val cacheable = msg.id != streamingMsgId
                    // ⚠ 只调一次：该函数一次产出 (正文, 思考) 两半。原先分两次取 .first/.second，
                    //   在缓存 miss 时（正是流式中那条，内容每次增量都在变）等于对同一篇超长
                    //   文本做两遍全文正则扫描 + 两份等长副本——OOM 现场的直接来源
                    //   （崩在 groupValues 的整段匹配拷贝上，GC 后只剩 2MB）。
                    val (body, thinking) = ChatUtils.extractThinkingContent(msg.content, cacheable)
                    if (thinking.isNotBlank()) {
                        val isStreamingMsg = msg.id == streamingMsgId
                        val segTiming = thinkingSegments[msg.id]
                        result.add(
                            MsgGroup.Thinking(
                                msgId = msg.id,
                                content = thinking,
                                streaming = isStreamingMsg,
                                // 每段各自的起止由 VM 记录，这里按消息 id 查。
                                // 查不到（历史消息）→ null → UI 只显示「思考过程」不报秒数。
                                timing = segTiming,
                                // 这段思考是否已收尾。三个信号任一成立即可：
                                //  · 正文已开始输出 —— 最可靠，思考吐完才会出现正文
                                //  · VM 已落定该段 endAt（工具调用前 / 整轮结束时）
                                //  · 该消息已不在流式（历史消息）
                                done = body.isNotBlank() || segTiming?.finished == true || !isStreamingMsg
                            )
                        )
                    }
                    if (body.isNotBlank() || msg.toolName != null) {
                        result.add(MsgGroup.Single(msg))
                    }
                } else {
                    result.add(MsgGroup.Single(msg))
                }
            }
        }
    }
    if (buf.isNotEmpty()) result.add(MsgGroup.ToolCalls(buf.toList()))
    return result
}

/**
 * 思考内容区的 overscroll 交接。
 *
 * **流式期间（思考进行中）**：
 * - 顶部：先通知调用方停掉内层自动跟随，并锁住外层「贴底校准」（userPulledUp），
 *   然后把越界 delta 交给父级 —— 用户能顺着继续翻上文，且不会被外层拽回底部抖动。
 * - 底部：累加成橡皮筋空白（调用方用
 *   [graphicsLayer][androidx.compose.ui.graphics.graphicsLayer] 平移绘制，不改测量），松手弹回；
 *   底部越界仍卡内吃掉，避免与外层贴底校准互抢。
 *
 * **思考结束后两端都交回父级**：内层滚到边界后剩余手势透传给外层 LazyColumn，
 * 用户能顺着继续翻聊天记录。
 *
 * 不能用单例 object：它得读到内层滚动状态和空白高度，必须按块 remember 出实例。
 */
private class ThinkingScrollHandoff(
    /** 返回 true 时才把「越过底部」的手势转成橡皮筋空白并吞掉；false 时透传给外层列表。 */
    private val atBottom: () -> Boolean,
    /** 返回 true 时表示思考进行中且内层已在顶部：把「越过顶部」的 delta 直接驱动到外层列表。 */
    private val atTop: () -> Boolean,
    /** 当前是否正在流式思考（拖动/惯性开始时的判据）。 */
    private val isStreaming: () -> Boolean,
    /** 顶部继续下拖 = 用户想回看上文：通知调用方停止内层自动跟随。 */
    private val onUserScrollUp: () -> Unit,
    /** 直接把滚动增量驱动到外层列表，返回实际消耗量（等价参考实现的父列表 jumpTo）。 */
    private val parentScroll: (Float) -> Float,
    private val blankPx: MutableState<Float>,
    private val scope: CoroutineScope,
    private val maxBlankPx: Float
) : NestedScrollConnection {

    private var springJob: Job? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 流式中只要出现「向顶部方向」的滚动（拖动或惯性），就立刻停跟随：
        // 不能等到内层真的移动才判（在顶部时它根本移不动），否则每个新 token
        // 的 scrollToItem(末项) 都会把用户从顶部拽回底部 → 快速上滑时抖动。
        if (available.y < 0f && isStreaming()) {
            onUserScrollUp()
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // 惯性向顶部方向 = 用户想回看上文：先停跟随，惯性照常交给父级
        // （外层跟随已锁住，不会再把页面拽回底部）。
        if (available.y < 0f && isStreaming()) {
            onUserScrollUp()
        }
        if (blankPx.value <= 0f) return Velocity.Zero
        springBack()
        return available // 吞掉惯性，别让它漏到外层
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        // 内层真滚了 —— 在底部它只可能是往回拉，按量把空白吃回去。
        if (consumed.y != 0f && blankPx.value > 0f) {
            blankPx.value = (blankPx.value - abs(consumed.y)).coerceAtLeast(0f)
        }
        // 顶部越界（delta 向下、内层已到顶）：停掉内外层自动跟随，然后直接把 delta
        // 驱动到外层列表，让用户顺着继续往上翻聊天记录。外层跟随已由 onUserScrollUp
        // 锁住，且这里标记为已消耗，父级 nestedScroll 不会再处理一次。
        if (available.y < 0f && atTop()) {
            onUserScrollUp()
            val consumedByParent = parentScroll(available.y)
            return Offset(0f, consumedByParent)
        }
        // 内层在内容底部且吃不下：这份 delta 只可能是「想越过底部」
        // （反方向在底部永远滚得动、剩不到 post），归我们，不交父级。
        if (available.y != 0f && atBottom()) {
            springJob?.cancel()
            springJob = null
            blankPx.value = (blankPx.value + abs(available.y)).coerceAtMost(maxBlankPx)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (blankPx.value > 0f) springBack()
        return Velocity.Zero
    }

    /** 松手回弹：把空白弹簧收回 0（内容随 graphicsLayer 落回原位）。 */
    private fun springBack() {
        if (springJob?.isActive == true) return
        val start = blankPx.value
        if (start <= 0f) return
        springJob = scope.launch {
            Animatable(start).animateTo(
                0f,
                spring(stiffness = Spring.StiffnessMedium)
            ) { blankPx.value = value }
            blankPx.value = 0f
            springJob = null
        }
    }
}

/**
 * 工具卡展开后的内容块：**指令与结果合一**，共用一个折叠条。
 *
 * 为什么合一：拆成两块时两条切换栏上下相叠（指令一条、结果一条），
 * 短指令 + 短结果也要占两行切换栏，且「点哪条」不直观。
 *
 * 阈值 [MERGED_SECTION_LINE_LIMIT]（8 行）：指令与结果**合计**的行数。
 * 留尾部—— 命令回显、编译日志的结论总在最后。
 * 未超阈值时不显示折叠栏（内容短，折叠反而多一步）。
 */
private const val MERGED_SECTION_LINE_LIMIT = 8

@Composable
private fun ToolCardMergedSection(
    text: String,
    hasError: Boolean,
    fontSize: TextUnit
) {
    var expanded by remember(text) { mutableStateOf(false) }
    val lines = remember(text) { text.split("\n") }
    val collapsible = lines.size > MERGED_SECTION_LINE_LIMIT
    val visible = remember(text, expanded, collapsible) {
        if (!collapsible || expanded) text else lines.takeLast(MERGED_SECTION_LINE_LIMIT).joinToString("\n")
    }
    val hiddenCount = if (collapsible) lines.size - MERGED_SECTION_LINE_LIMIT else 0
    Text(
        text = visible,
        // 整体一色：指令与结果已合一，不再按行区分颜色；出错时整体转红。
        color = if (hasError) Color(0xFFFF6B6B) else TextPrimary,
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        maxLines = if (expanded) Int.MAX_VALUE else MERGED_SECTION_LINE_LIMIT,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
    )
    if (collapsible) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (expanded) "收起" else "展开剩余 $hiddenCount 行",
                color = Accent,
                fontSize = fontSize * 0.93f,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 把工具参数 JSON 摊成多行 `key: value`，供「指令」节展示。
 * 解析失败原样返回（参数本来就不是合法 JSON 时不该显示空白）。
 * 只取一层键值，嵌套对象按 toString 整块列出——卡片是给「扫一眼」用的，不是 JSON 浏览器。
 */
private fun formatToolArgsMultiline(args: String?): String {
    if (args.isNullOrBlank() || args == "{}" || args == "null") return ""
    return try {
        val o = JSONObject(args)
        val sb = StringBuilder()
        for (k in o.keys()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(k).append(": ").append(o.opt(k)?.toString().orEmpty())
        }
        sb.toString()
    } catch (_: Exception) {
        args
    }
}

/**
 * 工具卡状态指示（只有一个调用点：工具条目卡头部）。
 *
 * 三态共用**同一个圆环**造型，区别只在「转不转」和「什么颜色」：
 * - **执行中**：淡灰轨道 + 100° 圆头弧，线性旋转 900ms/圈；颜色随旋转在色相环上走
 *   （`Color.hsv` 1800ms 一圈），颜色跟着弧一起转。
 * - **执行完**：同样的圆环，静止，绿色。
 * - **执行失败**：同样的圆环，静止，红色。
 *
 * 三个状态刻意保持同一形状与同一尺寸：最早用「8dp 闪烁黑点」，后来转圈与结束态
 * 用了不同形状（实心点 vs 圆环），一眼看去像两张卡；线宽、直径都统一后，
 * 状态切换只有颜色和动效在变，头部不会跳。
 *
 * 原来的实现：8dp 黑点 + 透明度 1↔0.25 来回闪。闪的是整颗点的透明度，看着像坏掉的点，
 * 黑点在浅色卡片上也扎眼；闪烁这个动效并不表达「还在跑」。
 */
@Composable
private fun TactToolStatusDot(running: Boolean, isError: Boolean) {
    // 12dp：10dp 时圆环太细，转起来不容易看清
    val size = 12.dp
    // 色相与角度挂在同一个 transition 下、各自定义时长，不会互相打断重启
    val transition = rememberInfiniteTransition(label = "tool-status-spinner")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "tool-status-spin"
    )
    val hue by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1800, easing = LinearEasing)),
        label = "tool-status-hue"
    )
    Canvas(modifier = Modifier.size(size)) {
        val stroke = Stroke(width = this.size.minDimension * 0.15f, cap = StrokeCap.Round)
        if (running) {
            // 轨道：淡灰，让转弧的行程看得出来
            drawCircle(color = Color(0xFF000000).copy(alpha = 0.10f), style = stroke)
            // 转弧：100° 圆头弧，随 spin 旋转
            drawArc(
                color = Color.hsv(hue = (hue % 360f), saturation = 0.68f, value = 0.95f),
                startAngle = spin - 90f,
                sweepAngle = 100f,
                useCenter = false,
                style = stroke
            )
        } else {
            // 结束态：同一个圆环，只是不转了，整圈用绿/红。
            // drawCircle + Stroke 画的是完整圆环（空心），不是实心点。
            drawCircle(
                color = if (isError) Color(0xFFFF6B6B) else Color(0xFF22C55E),
                style = stroke
            )
        }
    }
}

private fun toolActionIcon(name: String?): ImageVector = when {
    name?.startsWith("web_") == true || name == "explorer" || name?.contains("search") == true -> Icons.Default.Search
    name?.startsWith("file_") == true || name == "file_manager" || name == "file_analyzer" -> Icons.Default.Folder
    name == "writeFile" -> Icons.Default.Edit
    name == "editFile" -> Icons.Default.Edit
    name?.startsWith("memory") == true -> Icons.Default.Psychology
    name?.startsWith("skill") == true -> Icons.Default.Extension
    name == "ask_user" -> Icons.Default.QuestionAnswer
    name == "subagent_dispatch" || name == "call_sub_agent" || name == "talk_to_agent" -> Icons.Default.Psychology
    name == "git_operation" || name == "git" -> Icons.Default.CallSplit
    name == "scaffold" -> Icons.Default.Widgets
    name?.contains("batch") == true -> Icons.Default.Layers
    name?.contains("edit") == true -> Icons.Default.Edit
    name == "http_client" -> Icons.Default.Code
    name == "sqlite_browser" -> Icons.Default.Storage
    name == "screenshot" -> Icons.Default.CameraAlt
    name == "stock_query" || name == "stock" -> Icons.Default.ShowChart
    name?.startsWith("add_") == true || name?.startsWith("update_") == true || name?.startsWith("remove_") == true -> Icons.Default.Checklist
    name == "terminal" || name == "command" || name == "shizuku" -> Icons.Default.Terminal
    name?.startsWith("read") == true || name?.contains("read") == true -> Icons.Default.HelpOutline
    else -> Icons.Default.SmartToy
}

private fun toolActionLabel(name: String?): String = when (name) {
    "explorer" -> "已探索"
    "editFile" -> "编辑"
    "writeFile" -> "写入"
    "batch_edit" -> "批量编辑"
    "file_analyzer" -> "已分析"
    "file_manager" -> "文件"
    "web_search" -> "搜索"
    "web_fetch" -> "已读取"
    "http_client" -> "请求"
    "search_code" -> "搜索"
    "git_operation", "git" -> "Git"
    "screenshot" -> "截图"
    "sqlite_browser" -> "查询"
    // 快照式任务工具（一次提交完整清单，取代旧的 add/update/remove 三工具）
    "todo" -> "更新任务"
    else -> name ?: ""
}

private fun shortFileName(path: String): String {
    val clean = path.removePrefix("file://").removePrefix("content://")
    val name = clean.substringAfterLast('/').substringAfterLast('\\')
    return name.ifEmpty { clean.takeLast(30) }
}

/**
 * 判断工具结果是否为错误。委托 [com.wy.openx.data.util.ToolResultStatus]，
 * 与 MainViewModel 的 ✅/❌ 文案判定共用同一事实来源，避免圆点与文案矛盾。
 */
private fun isErrorResult(result: String?): Boolean =
    com.wy.openx.data.util.ToolResultStatus.isError(result)

private fun toolArgHint(args: String?): String {
    if (args == null || args == "{}" || args == "null") return ""
    return try {
        val o = JSONObject(args)
        val v = when {
            o.has("command") -> o.getString("command")
            o.has("path") -> o.getString("path")
            o.has("query") -> o.getString("query")
            o.has("url") -> o.getString("url")
            o.has("keyword") -> o.getString("keyword")
            else -> o.keys().asSequence().firstOrNull()?.let { o.getString(it) }
        }
        v?.take(40) ?: ""
    } catch (_: Exception) { "" }
}

private data class ToolCounts(val search: Int, val edit: Int, val web: Int, val command: Int, val file: Int, val other: Int)

private fun countToolCategories(items: List<ChatMessageModel>): ToolCounts {
    var search = 0; var edit = 0; var web = 0; var command = 0; var file = 0; var other = 0
    for (call in items) {
        when {
            call.toolName == "web_search" || call.toolName == "search_code" || call.toolName == "explorer" -> search++
            call.toolName == "batch_edit" || call.toolName?.contains("edit") == true -> edit++
            call.toolName == "web_fetch" || call.toolName == "http_client" -> web++
            call.toolName == "terminal" || call.toolName == "command" || call.toolName == "shizuku" -> command++
            call.toolName?.startsWith("file_") == true || call.toolName == "file_manager" || call.toolName == "file_analyzer" -> file++
            else -> other++
        }
    }
    return ToolCounts(search, edit, web, command, file, other)
}

private fun ToolCounts.summary(): String = buildString {
    val parts = mutableListOf<String>()
    if (search > 0) parts.add("$search 搜索")
    if (edit > 0) parts.add("$edit 编辑")
    if (web > 0) parts.add("$web 抓取")
    if (command > 0) parts.add("$command 命令")
    if (file > 0) parts.add("$file 文件")
    if (other > 0) parts.add("$other 其他")
    append(parts.joinToString(" · "))
}

@Composable
private fun ContentDetailDialog(
    toolName: String,
    arguments: String?,
    result: String?,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\uD83D\uDD27 $toolName", color = TextPrimary, fontSize = 14.sp) },
        text = {
            // 可滚动看全：原来只有 verticalScroll、没有高度上限，内容比屏幕高时
            // 滚动区仍按内容高度撑开，被弹窗窗口裁掉 —— 表现就是「下半截看不全」，
            // 而且滑不动（没有可滚的距离）。现在显式限高成固定视口 + 滚动，
            // 弹窗高度恒定，内容再长也只在这个视口里滚。
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!arguments.isNullOrEmpty() && arguments != "{}") {
                    Text("参数（${arguments.count { it == '\n' } + 1} 行）:", color = Accent, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Surface(color = PanelAlt.copy(alpha = 0.4f), shape = RoundedCornerShape(6.dp)) {
                        SelectionContainer {
                            Text(arguments, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp).fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val resultText = result ?: "(空)"
                val lineCount = resultText.count { it == '\n' } + 1
                Text(
                    "结果（$lineCount 行 / ${resultText.length} 字符）:",
                    color = if (isError) Color(0xFFFF6B6B) else Accent,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(4.dp))
                Surface(color = PanelAlt.copy(alpha = 0.4f), shape = RoundedCornerShape(6.dp)) {
                    SelectionContainer {
                        Text(
                            resultText,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp).fillMaxWidth()
                        )
                    }
                }
                if (lineCount > 18) {
                    // 视口 ~420dp、每行 12sp，18 行左右就填满；超了这个数提示用户往上滑
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "内容较长，上下滑动查看全部 $lineCount 行；长按可选择复制",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val copyText = result ?: arguments ?: ""
                TextButton(onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("tool_result", copyText))
                    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("\uD83D\uDCCB 复制", color = Accent) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("关闭", color = TextSecondary) }
            }
        },
        containerColor = Panel
    )
}

/** 从工具结果文本中提取图片缩略图标记（generate_image 成功结果末尾的 `@image:<路径>` 行）。 */
private fun parseToolImageMarkers(text: String?): List<String> {
    if (text.isNullOrBlank()) return emptyList()
    return buildList {
        text.lineSequence().forEach { line ->
            val v = line.trim()
            if (v.startsWith("@image:")) {
                val p = v.removePrefix("@image:").trim().takeIf { it.isNotBlank() }
                if (p != null) add(p)
            }
        }
    }
}

/** 把工具结果里的容器/宿主图片路径解析为宿主真实文件：先容器语义，再宿主绝对路径兜底。 */
private fun resolveToolImageFile(path: String): File? {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null
    val host = com.wy.openx.tools.AppContextHolder.containerEngine?.toHostFileIfContainerPath(trimmed)
    if (host != null && host.exists() && host.isFile) return host
    val direct = runCatching { com.wy.openx.tools.ToolPaths.sanitize(trimmed) }.getOrNull()
    if (direct != null && direct.exists() && direct.isFile) return direct
    return null
}

/** 工具结果图片缩略图条：常显在工具卡片下方（不随展开/折叠隐藏），点击打开大图查看。 */
@Composable
private fun ToolImageThumbnailRow(paths: List<String>, itemKey: Long) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        paths.forEach { p ->
            val host = remember(p, itemKey) { resolveToolImageFile(p) }
            if (host != null) {
                var bitmap by remember(host.absolutePath) { mutableStateOf<android.graphics.Bitmap?>(null) }
                var showViewer by remember { mutableStateOf(false) }
                LaunchedEffect(host.absolutePath) {
                    // 降采样解码 + EXIF 摆正，避免大图 OOM 与横竖颠倒
                    bitmap = decodeSampledBitmap(context, host.toURI().toString(), maxDim = 1280)
                }
                val bmp = bitmap
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = host.name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showViewer = true }
                    )
                    if (showViewer) {
                        ImageViewerSheet(
                            name = host.name,
                            subtitle = host.absolutePath,
                            onDismiss = { showViewer = false },
                            decode = {
                                val bmp2 = decodeSampledBitmap(context, host.toURI().toString(), maxDim = 2560)
                                (host.length() to bmp2)
                            }
                        )
                    }
                } else {
                    // 解码失败/尚无结果：占位提示，避免布局跳动
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = PanelAlt,
                        modifier = Modifier.size(width = 120.dp, height = 90.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Image, contentDescription = "图片加载中", tint = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** 剔除消息正文里遗留的「[已附加图片 N 张]」占位行（仅展示层面，不落库）。 */
private fun stripImageAttachmentPlaceholder(text: String): String = text
    .lineSequence()
    .filterNot { it.trim().matches(Regex("""\[已附加图片\s*\d+\s*张\]""")) }
    .joinToString("\n")
    .trim('\n')

/** 从消息附件/历史 imageUri 兜底推导要渲染的附件列表：新消息用 attachments，旧消息由 imageUri 重建单条图片。 */
private fun messageDisplayAttachments(msg: ChatMessage): List<com.wy.openx.data.model.MessageAttachment> {
    if (msg.attachments.isNotEmpty()) return msg.attachments
    val uriStr = msg.imageUri ?: return emptyList()
    val parsed = runCatching { android.net.Uri.parse(uriStr) }.getOrNull()
    val name = parsed?.lastPathSegment?.takeIf { it.isNotBlank() } ?: "图片"
    val localPath = parsed?.path
    val containerPath = if (localPath != null && (localPath.contains("/workspace/") || localPath.contains("files/workspace"))) {
        "/root/workspace/uploads/" + java.io.File(localPath).name
    } else {
        ""
    }
    val size = if (localPath != null) runCatching { java.io.File(localPath).length() }.getOrDefault(0L) else 0L
    return listOf(
        com.wy.openx.data.model.MessageAttachment(
            fileName = name, containerPath = containerPath, localPath = localPath ?: "",
            mimeType = "image/*", sizeBytes = size, isImage = true
        )
    )
}

/** 已发送消息的附件行列表：一行一个文件。 */
@Composable
private fun SentAttachmentList(
    attachments: List<com.wy.openx.data.model.MessageAttachment>,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { attachment ->
            SentAttachmentRow(attachment = attachment, context = context)
        }
    }
}

/** 单个附件行：左 44dp 缩略图（图片真缩略/文件按类型给图标）+ 文件名 + 大小 · 容器路径 + 右箭头。图片整行点击全屏查看。 */
@Composable
private fun SentAttachmentRow(
    attachment: com.wy.openx.data.model.MessageAttachment,
    context: Context
) {
    var showViewer by remember { mutableStateOf(false) }
    val meta = remember(attachment.sizeBytes, attachment.containerPath) {
        listOf(formatBytes(attachment.sizeBytes), attachment.containerPath)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { if (attachment.isImage) "图片" else "" }
    }
    val openHandler: (() -> Unit)? = if (attachment.isImage) ({ showViewer = true }) else null
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = PanelAlt,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (openHandler != null) Modifier.clickable(onClick = openHandler) else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SentAttachmentThumb(attachment = attachment, context = context)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName.ifBlank { "附件" },
                    color = TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (openHandler != null) {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
    if (showViewer) {
        val imageName = attachment.fileName.ifBlank { "图片" }
        ImageViewerSheet(
            name = imageName,
            subtitle = attachment.localPath.ifBlank { attachment.containerPath },
            onDismiss = { showViewer = false },
            decode = {
                val uri = attachmentDecodeUri(attachment)
                val bmp = if (uri != null) decodeImageUriBest(context, uri, maxDim = 2560, tag = "sent-view") else null
                0L to bmp
            }
        )
    }
}

/** 附件缩略图：44dp，图片剪裁缩略，非图片按类型给图标。 */
@Composable
private fun SentAttachmentThumb(
    attachment: com.wy.openx.data.model.MessageAttachment,
    context: Context
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Panel,
        modifier = Modifier.size(44.dp)
    ) {
        if (attachment.isImage) {
            var bitmap by remember(attachment.localPath, attachment.containerPath) {
                mutableStateOf<android.graphics.Bitmap?>(null)
            }
            LaunchedEffect(attachment.localPath, attachment.containerPath) {
                bitmap = withContext(Dispatchers.IO) { decodeSampleImageBest(context, attachment) }
            }
            val bmp = bitmap
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = attachment.fileName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (attachment.fileName.endsWith(".md")) Icons.Default.Description else Icons.Default.AttachFile,
                    contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 附件对应的解码 URI 字符串：优先本地路径 file://，次之容器路径解析为宿主文件，退回空。 */
private fun attachmentDecodeUri(attachment: com.wy.openx.data.model.MessageAttachment): String? {
    if (attachment.localPath.isNotBlank()) {
        val f = java.io.File(attachment.localPath)
        if (f.isFile) return f.toURI().toString()
    }
    if (attachment.containerPath.isNotBlank()) {
        val host = com.wy.openx.tools.AppContextHolder.containerEngine?.toHostFileIfContainerPath(attachment.containerPath)
        if (host != null && host.isFile) return host.toURI().toString()
        val direct = runCatching { com.wy.openx.tools.ToolPaths.sanitize(attachment.containerPath) }.getOrNull()
        if (direct != null && direct.isFile) return direct.toURI().toString()
    }
    return null
}

/** 附件缩略档（256）解码；大图（2560）复用它取高清。 */
private suspend fun decodeSampleImageBest(context: Context, attachment: com.wy.openx.data.model.MessageAttachment): android.graphics.Bitmap? {
    val uri = attachmentDecodeUri(attachment) ?: return null
    return decodeImageUriBest(context, uri, maxDim = 256, tag = "sent")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCallGroup(
    items: List<ChatMessageModel>,
    fontSize: TextUnit,
    showDetails: Boolean = false,
    onDeleteGroup: (() -> Unit)? = null,
    onDeleteItem: ((ChatMessageModel) -> Unit)? = null,
    isStillSending: Boolean = false,
    scrollState: ChatScrollState = ChatScrollState()
) {
    val hasPending = remember(items) { items.any { it.toolResult == null } }
    val groupKey = remember(items) { items.firstOrNull()?.id ?: -1L }
    var expanded by remember(groupKey) {
        mutableStateOf(scrollState.toolGroupState[groupKey]?.expanded ?: (showDetails || hasPending))
    }
    var userToggled by remember(groupKey) {
        mutableStateOf(scrollState.toolGroupState[groupKey]?.userToggled ?: false)
    }
    // 展开/折叠状态写入 holder，离开聊天页再回来保持原状态
    LaunchedEffect(groupKey, expanded, userToggled) {
        scrollState.toolGroupState[groupKey] = ToolGroupState(expanded, userToggled)
    }
    // 执行中有工具时自动展开整组
    LaunchedEffect(groupKey, hasPending, userToggled) {
        if (hasPending && !userToggled) expanded = true
    }
    // 执行完 10 秒后自动折叠；用户手动打开过则该组不再自动折叠
    LaunchedEffect(groupKey, hasPending, userToggled) {
        if (!hasPending && !userToggled) {
            kotlinx.coroutines.delay(10_000)
            if (!userToggled) expanded = false
        }
    }
    val counts = remember(items) { countToolCategories(items) }
    val summary = remember(counts) { counts.summary() }
    var dialogItem by remember { mutableStateOf<ChatMessageModel?>(null) }
    // 工具卡字号此前全部写死（15/14/13sp），用户在小字/大字设置下看到的仍是固定大小。
    // 现在以 fs（= 用户设置，横屏/竖屏已在上游降档）为准，标题/摘要/正文按
    val fs = fontSize
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        // 工具调用汇总行。不再缩进 24dp：工具组本来就是独立卡片（有底色、圆角、状态点），
        // 缩进只是把宽度白扔掉——列表 contentPadding 已经给了 16dp，
        // 卡片再缩 24dp + 右 8dp，正文可用宽度更窄。
        // 长命令、长路径每行都得多折一次，看起来"很长"。
        Surface(
            onClick = {
                expanded = !expanded
                userToggled = true
            },
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "${items.size} 次工具调用 · $summary",
                    color = TextSecondary,
                    // 汇总行跟随用户的字号设置（原来写死 14sp，小字/大字设置对工具区不生效）
                    fontSize = fs * 0.93f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onDeleteGroup != null) {
                    var showDeleteGroupDialog by remember { mutableStateOf(false) }
                    IconButton(onClick = { showDeleteGroupDialog = true }, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "删除", tint = TextSecondary.copy(alpha = 0.35f), modifier = Modifier.size(12.dp))
                    }
                    if (showDeleteGroupDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteGroupDialog = false },
                            title = { Text("确认删除", fontWeight = FontWeight.Bold, color = TextPrimary) },
                            text = { Text("确定要删除此操作组吗？此操作不可撤销。", color = TextSecondary) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showDeleteGroupDialog = false
                                    onDeleteGroup()
                                }) { Text("确认删除", color = Color(0xFFFF6B6B)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteGroupDialog = false }) { Text("取消", color = TextSecondary) }
                            },
                            containerColor = Panel
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                items.forEachIndexed { _, call ->
                    val icon = toolActionIcon(call.toolName)
                    val resultText = call.toolResult
                    val error = isErrorResult(resultText)
                    val argsHint = toolArgHint(call.toolArguments)
                    val toolLabel = toolActionLabel(call.toolName)
                    val itemHasPending = call.toolResult == null
                    val isFileEditor = call.toolName == "editFile" || call.toolName == "writeFile"
                    // editFile / writeFile 的彩色 diff 预览：解析失败自动回退纯文本
                    val editDiff = remember(call.toolName, resultText) {
                        if (isFileEditor && !error) parseEditDiff(resultText) else null
                    }
                    // 条目默认折叠：执行中不自动展开下方工具详情，避免刷屏；用户可手动点击展开
                    var itemExpanded by remember { mutableStateOf(false) }
                    // diff 预览的两态：false=尾部预览，true=全部展开（点击标题行切换，永不隐藏）
                    var diffFullExpanded by remember(call.toolName, resultText) { mutableStateOf(false) }
                    var showDeleteItemDialog by remember { mutableStateOf(false) }

                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        // 工具调用条目卡
                        Surface(
                            onClick = {
                                if (editDiff != null && !itemHasPending) {
                                    // 编辑类工具：标题行在「尾部预览 ↔ 全部展开」间切换，永不隐藏
                                    diffFullExpanded = !diffFullExpanded
                                } else {
                                    itemExpanded = !itemExpanded
                                }
                            },
                            // 左下角用小圆角（10,10,10,4），视觉上与右侧气泡错开
                            shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 4.dp),
                            color = Panel,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TactToolStatusDot(running = itemHasPending, isError = error)
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    icon,
                                    contentDescription = call.toolName ?: toolLabel,
                                    tint = Color(0xFF1A1A1A),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    call.toolName ?: toolLabel,
                                    color = TextPrimary,
                                    fontSize = fs,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (editDiff != null && !itemHasPending) {
                                    Spacer(Modifier.width(8.dp))
                                    DiffStat(added = editDiff.added, removed = editDiff.removed)
                                }
                                if (argsHint.isNotEmpty() && editDiff == null) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        argsHint,
                                        color = TextSecondary,
                                        // 参数摘要用等宽字体，命令/路径一眼能对齐（终端类工具全靠它认内容）
                                        fontSize = fs * 0.86f,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        // fill=true：参数摘要吃掉全部剩余宽度，chevron 才贴右。
                                        // 用 fill=false 时短文本不会把箭头推到行尾。
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                Icon(
                                    when {
                                        editDiff != null && !itemHasPending ->
                                            if (diffFullExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown
                                        else ->
                                            if (itemExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown
                                    },
                                    contentDescription = if (itemExpanded) "收起" else "展开",
                                    tint = TextSecondary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        // 子代理并行进度（仅 subagent_dispatch 执行中）：子任务在仓库层跑，
                        // 没有这行就是几分钟黑盒。只显示最近 6 行，长任务不刷屏。
                        if (itemHasPending) {
                            val progressLines = call.toolProgress?.lines()?.filter { it.isNotBlank() }.orEmpty().takeLast(6)
                            if (progressLines.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp, top = 4.dp)
                                ) {
                                    progressLines.forEach { line ->
                                        Text(
                                            "· " + line,
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        // 结果图片缩略图：常显在工具卡下方（不随结果块展开/折叠隐藏），点击查看大图
                        if (!itemHasPending) {
                            val imgPaths = remember(call.id, resultText) { parseToolImageMarkers(resultText) }
                            if (imgPaths.isNotEmpty()) {
                                ToolImageThumbnailRow(paths = imgPaths, itemKey = call.id)
                            }
                        }
                        // 编辑类工具：diff 始终可见（预览或全开），展开按钮常驻
                        if (editDiff != null && !itemHasPending) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CodeBg)
                                    .combinedClickable(
                                            onClick = { dialogItem = call },
                                        onLongClick = {
                                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clip.setPrimaryClip(ClipData.newPlainText("tool_result", resultText ?: ""))
                                            android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    ToolDiffPreview(
                                        diff = editDiff,
                                        fullExpanded = diffFullExpanded,
                                        onToggleFull = { diffFullExpanded = !diffFullExpanded }
                                    )
                                    if (onDeleteItem != null) {
                                        IconButton(onClick = { showDeleteItemDialog = true }, modifier = Modifier.size(18.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "删除", tint = TextSecondary.copy(alpha = 0.35f), modifier = Modifier.size(11.dp))
                                        }
                                    }
                                }
                            }
                            if (showDeleteItemDialog) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteItemDialog = false },
                                    title = { Text("确认删除", fontWeight = FontWeight.Bold, color = TextPrimary) },
                                    text = { Text("确定要删除此工具调用及其结果吗？此操作不可撤销。", color = TextSecondary) },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showDeleteItemDialog = false
                                                onDeleteItem?.invoke(call)
                                        }) { Text("确认删除", color = Color(0xFFFF6B6B)) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDeleteItemDialog = false }) { Text("取消", color = TextSecondary) }
                                    },
                                    containerColor = Panel
                                )
                            }
                        } else {
                            // 指令 + 结果：分两节；每节超 20 行折叠、默认留尾部 20 行
                            // （留尾部是因为刷屏型输出——命令回显、编译日志——结论总在最后）。
                            // 整块仍是「点击看详情 / 长按复制」，删除按钮原样保留。
                            AnimatedVisibility(visible = itemExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CodeBg)
                                        .combinedClickable(
                                        onClick = { dialogItem = call },
                                            onLongClick = {
                                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clip.setPrimaryClip(ClipData.newPlainText("tool_result", resultText ?: ""))
                                                android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        .padding(horizontal = 8.dp, vertical = 10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val argsText = remember(call.id, call.toolArguments) {
                                                formatToolArgsMultiline(call.toolArguments)
                                            }
                                            val resultBody = resultText?.trim()?.takeIf { it.isNotEmpty() } ?: "(无返回)"
                                            // 指令与结果合成为一整块、共用一个折叠条：
                                            // 分开折叠时两条切换栏叠在一起（各 10 行、各一条），
                                            // 点哪条都不直观，而且短指令+短结果也要占两行切换栏。
                                            val merged = remember(argsText, resultBody) {
                                                buildString {
                                                    if (argsText.isNotEmpty()) {
                                                        append("指令：")
                                                        append(argsText)
                                                    }
                                                    if (resultBody.isNotBlank()) {
                                                        if (isNotEmpty()) append("\n")
                                                        append("结果：")
                                                        append(resultBody)
                                                    }
                                                }
                                            }
                                            ToolCardMergedSection(
                                                text = merged,
                                                hasError = error,
                                                fontSize = fs * 0.93f
                                            )
                                        }
                                        if (onDeleteItem != null) {
                                            IconButton(onClick = { showDeleteItemDialog = true }, modifier = Modifier.size(18.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = "删除", tint = TextSecondary.copy(alpha = 0.35f), modifier = Modifier.size(11.dp))
                                            }
                                        }
                                    }
                                }
                                if (showDeleteItemDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showDeleteItemDialog = false },
                                        title = { Text("确认删除", fontWeight = FontWeight.Bold, color = TextPrimary) },
                                        text = { Text("确定要删除此工具调用及其结果吗？此操作不可撤销。", color = TextSecondary) },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                showDeleteItemDialog = false
                                            onDeleteItem?.invoke(call)
                                            }) { Text("确认删除", color = Color(0xFFFF6B6B)) }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showDeleteItemDialog = false }) { Text("取消", color = TextSecondary) }
                                        },
                                        containerColor = Panel
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    dialogItem?.let { call ->
        ContentDetailDialog(
            toolName = call.toolName ?: "",
            arguments = call.toolArguments,
            result = call.toolResult,
            isError = isErrorResult(call.toolResult),
            onDismiss = { dialogItem = null }
        )
    }
}

/** 聊天页滚动状态（由 MainActivity 顶层持有，跨 tab 切换存活，离开再回来保持位置） */
class ChatScrollState {
    val listState = LazyListState()
    /**
     * 用户是否上翻离开了底部（true = 正在翻历史，禁用自动贴底；用户回到底部自动复位）。
     *
     * ⚠ 必须跟着这份 state 走，不能放在 `AgentConsoleScreen` 里 `remember`：
     * 竖屏离开聊天页（去设置页等）会销毁整个 composable，回来重建后 remember 归零，
     * 两个贴底 effect 就会把停在历史里的用户拽回底部 ——
     *   · `LaunchedEffect(isSending)`：进入组合必跑一次，空闲且未上翻时直接贴底；
     *   · `LaunchedEffect(conversationId, messages.size)`：内容一变就贴底。
     * 列表位置本身由 [listState] 保住，丢的只是「用户上翻了」这个标记。
     */
    var userPulledUp by mutableStateOf(false)
    var seenConversation = -1L
    var seenCount = -1
    /** 已加载完成的会话 id：用于区分「首次进入此会话要贴底」与「同一会话新增消息只跟随」 */
    var seenSessionLoaded = -1L
    val toolGroupState = HashMap<Long, ToolGroupState>()
    val thinkingState = HashMap<Long, ToolGroupState>()


}

/** 单个工具组的展开状态（key=组首条消息 id），跨 tab 切换保持 */
class ToolGroupState(
    var expanded: Boolean = false,
    var userToggled: Boolean = false
)

/** 思考块折叠状态持久化（键=消息 id），进程重启后仍保持折叠状态 */
/**
 * 思考折叠态的 prefs 文件名。
 *
 * v2：换文件是因为 v1（`openx_ui_state`）被写成了「每条消息一个 key」且永不清理，
 * 把 SharedPreferences 撑到几百 MB —— 它在**首次访问**时整表进内存常驻，
 * 而每次 `apply()` 还要再复制一份，于是长会话滚动必然 OOM
 * （`HashMap.putMapEntries` ← `EditorImpl.apply`）。旧文件由 OpenXApp 启动时删除。
 *
 * 换文件同时天然把 v1 那张巨表踢出内存：Android 只会加载**被打开过**的 prefs 文件。
 */
private const val OPENX_UI_STATE_PREFS = "openx_ui_state_v2"
private const val THINKING_FOLD_PREFIX = "thinking_fold_"
/**
 * 折叠态最多留多少条，超出按 msgId 从小到大淘汰（msgId 随时间单调递增）。
 * 没有上限的话，v2 也会被慢慢撑成下一个 v1；每条约 150B，3000 条约 0.5MB，可忽略。
 */
private const val THINKING_FOLD_MAX_ENTRIES = 3000

private fun loadThinkingFoldState(msgId: Long): Pair<Boolean, Boolean>? {
    if (msgId < 0) return null
    val ctx = com.wy.openx.tools.AppContextHolder.context ?: return null
    val v = ctx.getSharedPreferences(OPENX_UI_STATE_PREFS, android.content.Context.MODE_PRIVATE)
        .getString("thinking_fold_$msgId", null) ?: return null
    val parts = v.split(",")
    if (parts.size != 2) return null
    return (parts[0] == "1") to (parts[1] == "1")
}

/**
 * 计算需要淘汰的折叠态 key：超出 [max] 时，按 msgId **从小到大**淘汰最旧的。
 *
 * 抽成纯函数是为了能单测——这条上限正是「不再重演 v1 撑爆 prefs」的兜底，
 * 一旦被后人删掉，长会话就会重新开始 OOM。
 *
 * @param keys prefs 里的全部 key
 * @param max 允许保留的最大条数
 * @return 需要 remove 的 key（不含刚写入的那个）
 */
internal fun thinkingFoldKeysToDrop(keys: Set<String>, max: Int): List<String> {
    if (keys.size <= max) return emptyList()
    val prefixed = keys.filter { it.startsWith(THINKING_FOLD_PREFIX) }
    // 能解析出 msgId 的按 id 升序（id 随时间递增 ⇒ 升序即由旧到新），优先淘汰最旧的。
    val byOldestFirst = prefixed.filter { it.removePrefix(THINKING_FOLD_PREFIX).toLongOrNull() != null }
        .sortedBy { it.removePrefix(THINKING_FOLD_PREFIX).toLong() }
    // 解析不出 id 的（脏 key）排在最后兜底：它们同样占着上限，
    // 只删可解析的那些会让「keys.size - drop.size <= max」这个不变量在有脏 key 时失效。
    val ordered = byOldestFirst + prefixed.filterNot { it in byOldestFirst }
    val dropCount = (keys.size - max).coerceAtMost(ordered.size)
    return ordered.take(dropCount)
}

private fun saveThinkingFoldState(msgId: Long, expanded: Boolean, userToggled: Boolean) {
    if (msgId < 0) return
    // **只落盘用户手动改过的状态**。自动折叠由行数阈值推导，落盘没有意义；
    // 而此前对每条渲染出来的消息都写一次（LaunchedEffect 首帧就写默认值），
    // 这正是把 prefs 撑爆、把长会话滚到 OOM 的直接原因。
    if (!userToggled) return
    val ctx = com.wy.openx.tools.AppContextHolder.context ?: return
    runCatching {
        val prefs = ctx.getSharedPreferences(OPENX_UI_STATE_PREFS, android.content.Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString(THINKING_FOLD_PREFIX + msgId, "${if (expanded) 1 else 0},${if (userToggled) 1 else 0}")
        // 超出上限就淘汰最旧的（msgId 最小）。必须顺手做，否则 v2 也会重演 v1 的结局。
        thinkingFoldKeysToDrop(prefs.all.keys, THINKING_FOLD_MAX_ENTRIES)
            .forEach { editor.remove(it) }
        editor.apply()
    }
}

/** 把 SAF 目录选择器返回的 document tree uri 解析为宿主绝对路径（primary → /storage/emulated/0，
 *  外部卷 → /storage/<卷>）。解析失败返回 null，交由调用方提示。 */
private fun treeUriToHostPath(uri: android.net.Uri): String? {
    return try {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val idx = docId.indexOf(':')
        if (idx <= 0) return null
        val vol = docId.substring(0, idx)
        val rel = docId.substring(idx + 1)
        val hostRoot = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
        if (rel.isEmpty()) hostRoot else "$hostRoot/$rel"
    } catch (_: Exception) {
        null
    }
}

// ============ editFile / writeFile diff 预览 ============

internal val DiffAddBg = Color(0x2622C55E)
internal val DiffAddText = Color(0xFF22C55E)
internal val DiffRemoveBg = Color(0x26EF4444)
internal val DiffRemoveText = Color(0xFFEF4444)

/** 单个 hunk 的差异片段 */
private data class ToolDiffHunk(val startLine: Int, val diff: String)

/** editFile / writeFile 结果中解析出的结构化差异 */
private data class ToolEditDiff(
    val added: Int,
    val removed: Int,
    val hunks: List<ToolDiffHunk>
)

/** 从工具结果字符串解析 editFile / writeFile 的结构化差异；解析失败返回 null（回退纯文本视图） */
private fun parseEditDiff(result: String?): ToolEditDiff? {
    if (result == null) return null
    return try {
        val obj = JSONObject(result)
        val hunksArr = obj.optJSONArray("hunks") ?: return null
        if (hunksArr.length() == 0) return null
        val hunks = (0 until hunksArr.length()).mapNotNull { i ->
            val h = hunksArr.optJSONObject(i) ?: return@mapNotNull null
            val d = h.optString("diff")
            if (d.isEmpty()) null else ToolDiffHunk(h.optInt("start_line", 1), d)
        }
        if (hunks.isEmpty()) return null
        ToolEditDiff(
            added = obj.optInt("added_lines", 0),
            removed = obj.optInt("removed_lines", 0),
            hunks = hunks
        )
    } catch (_: Exception) { null }
}

/** 增删统计胶囊：绿色「+N」与红色「−M」。 */
@Composable
private fun DiffStat(added: Int, removed: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                text = "+$added",
                color = DiffAddText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (added > 0 && removed > 0) Spacer(Modifier.width(2.dp))
        if (removed > 0) {
            Text(
                text = "−$removed",
                color = DiffRemoveText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 彩色行级差异视图：带行号 gutter、+绿/−红底色、超 [REASONING_COLLAPSE_LINE_LIMIT] 行折叠为尾部预览 */
@Composable
private fun ToolDiffPreview(
    diff: ToolEditDiff,
    fullExpanded: Boolean,
    onToggleFull: () -> Unit
) {
    // 逐 hunk 展开为「带行号 + 标记」的渲染行列表，折叠时按全局行号取尾部预览
    val renderLines = remember(diff) {
        buildList {
            for (h in diff.hunks) {
                var oldLineNo = h.startLine
                var newLineNo = h.startLine
                for (lineText in h.diff.split("\n")) {
                    val marker = lineText.firstOrNull()
                    val lineNo = when (marker) {
                        '-' -> oldLineNo++
                        '+' -> newLineNo++
                        else -> { val n = newLineNo; oldLineNo++; newLineNo++; n }
                    }
                    add(Triple(lineNo, marker, lineText))
                }
            }
        }
    }
    val totalLines = renderLines.size
    val overThreshold = totalLines > REASONING_COLLAPSE_LINE_LIMIT
    val visible = if (overThreshold && !fullExpanded) renderLines.takeLast(REASONING_COLLAPSE_LINE_LIMIT)
        else renderLines
    val gutterChars = (renderLines.maxOfOrNull { it.first } ?: 1).toString().length.coerceAtLeast(2)
    val gutterColor = TextSecondary.copy(alpha = 0.6f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(CodeBg)
                .horizontalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Column {
                    visible.forEach { (lineNo, marker, lineText) ->
                        val (bg, fg) = when (marker) {
                            '+' -> DiffAddBg to DiffAddText
                            '-' -> DiffRemoveBg to DiffRemoveText
                            else -> Color.Transparent to TextSecondary
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = lineNo.toString().padStart(gutterChars),
                                color = gutterColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = lineText,
                                color = fg,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
        if (overThreshold) {
            val hidden = totalLines - REASONING_COLLAPSE_LINE_LIMIT
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleFull)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (fullExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (fullExpanded) "收起为尾部预览" else "展开剩余 $hidden 行",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * 思考强度档位全集（UI 展示用）。
 *
 * ⚠ 不含 `"none"`：那是本项目自造值、网关不认（发了等于没发）。"关闭思考"的规范档位是
 * `minimal`；历史遗留的 `"none"` 由 MainViewModel.normalizeReasoningEffort 归一到 `minimal`。
 *
 * 取值对齐 models.dev 的 `reasoning_options`，各模型**只开放其中子集**（见
 * `ModelMetadata.reasoningEffortOptions`）；网关只认自己那份，发未知值不报错但也无效。
 */
internal val ALL_REASONING_EFFORTS = listOf(
    "minimal" to "极低",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "xhigh" to "极高",
    "max" to "最高"
)

/**
 * 思考强度弹窗的选项：模型默认 + 该模型真正支持的档位。
 *
 * @param modelSupported models.dev 提供的该模型支持档位；**null = 元数据缺失 → 显示全量**
 *   （宁可多选，也不要让用户一个都选不了）。
 */
internal fun buildEffortOptions(modelSupported: List<String>?): List<Pair<String, String>> {
    val supported = modelSupported?.map { it.lowercase() }?.toSet()
    val rows = if (supported == null) ALL_REASONING_EFFORTS
    else ALL_REASONING_EFFORTS.filter { (value, _) -> value in supported }
    // 元数据存在但一个都不匹配（模型压根不支持该参数）时 rows 可能为空，
    // 此时仍保留「模型默认」——不能让用户面对空列表。
    return listOf("default" to "模型默认") + rows
}

/**
 * 多问题提问面板（`ask_user` 的 `questions` 数组，1–4 问）。
 *
 * 与单问面板的差别：每个问题可带短标题（header）、选项带说明、可分别设置单选/多选，
 * 全部答完后一次提交，答案按「1. 标题: 选择」拼接回给模型——顺序稳定，模型好解析。
 * 未作答的问题不影响提交（模型会拿到「未回答」），避免用户被强制填完所有问题。
 */
@Composable
private fun MultiQuestionPanel(
    info: QuestionInfo,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val selections = remember(info) {
        androidx.compose.runtime.mutableStateMapOf<Int, Set<Int>>()
    }
    val customs = remember(info) {
        androidx.compose.runtime.mutableStateMapOf<Int, String>()
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        border = BorderStroke(1.dp, Border),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AI 向你提问（${info.items.size} 个）",
                    fontWeight = FontWeight.Bold, color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = TextSecondary
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    info.items.forEachIndexed { qi, item ->
                        if (qi > 0) {
                            Spacer(Modifier.height(14.dp))
                            androidx.compose.material3.HorizontalDivider(color = Border)
                            Spacer(Modifier.height(14.dp))
                        }
                        if (item.header.isNotBlank()) {
                            Text(
                                item.header,
                                color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text("${qi + 1}. ${item.question}", color = TextPrimary, fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        val picked = selections[qi].orEmpty()
                        item.options.forEachIndexed { oi, option ->
                            val selected = oi in picked
                            Row(
                                modifier = Modifier.fillMaxWidth().selectable(
                                    selected = selected,
                                    role = if (item.multiSelect) Role.Checkbox else Role.RadioButton,
                                    onClick = {
                                        selections[qi] = if (item.multiSelect) {
                                            if (selected) picked - oi else picked + oi
                                        } else {
                                            setOf(oi)
                                        }
                                    }
                                ).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                if (item.multiSelect) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { checked ->
                                            selections[qi] = if (checked) picked + oi else picked - oi
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = Accent)
                                    )
                                } else {
                                    RadioButton(
                                        selected = selected,
                                        onClick = { selections[qi] = setOf(oi) },
                                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Accent)
                                    )
                                }
                                Column(modifier = Modifier.padding(top = 10.dp)) {
                                    Text(option.label, fontSize = 14.sp, color = TextPrimary)
                                    if (option.description.isNotBlank()) {
                                        Text(
                                            option.description, fontSize = 11.sp,
                                            color = TextSecondary, lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                        if (item.allowCustom) {
                            OutlinedTextField(
                                value = customs[qi].orEmpty(),
                                onValueChange = { customs[qi] = it },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                placeholder = {
                                    Text("其他（可选）", color = TextSecondary.copy(alpha = 0.5f))
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Border,
                                    cursorColor = Accent,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                maxLines = 3
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onCancel) { Text("跳过", color = TextSecondary) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val answer = info.items.mapIndexed { qi, item ->
                                    val parts = ArrayList<String>()
                                    val picked = selections[qi].orEmpty().sorted()
                                        .mapNotNull { item.options.getOrNull(it)?.label }
                                    if (picked.isNotEmpty()) parts += picked.joinToString("、")
                                    val custom = customs[qi].orEmpty().trim()
                                    if (custom.isNotEmpty()) parts += "自定义:$custom"
                                    val label = item.header.ifBlank { item.question }
                                    "${qi + 1}. $label: " +
                                        (if (parts.isEmpty()) "（未回答）" else parts.joinToString("；"))
                                }.joinToString("\n")
                                onSubmit(answer)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ButtonBg, contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("提交") }
                    }
                }
            }
        }
    }
}

/**
 * 语音提问 / 「自动朗读」开关 → AI 回复结束后播报。
 *
 * 只在两种情况下朗读：本轮由语音发起（对着手机说话就该被回话），或用户在
 * 「设置 → 默认模型 → 自动朗读」里打开了开关。文字输入默认不出声，免得打扰周围人。
 * 被打断、拔耳机由 [com.wy.openx.voice.VoiceAudioSession.onInterrupted] 停掉。
 */
@Composable
private fun AutoSpeakOnReply(uiState: ConsoleUiState) {
    LaunchedEffect(uiState.isSending) {
        // 只在「生成刚结束」这一刻朗读。这个 LaunchedEffect 每次进组合都会先跑一遍，
        // 只看「当前不在生成 + 开关已开」的话，从设置页切回聊天页就会把上一条旧回复重念。
        val engines = com.wy.openx.voice.VoiceEngines
        if (!engines.consumeReplyFinished(uiState.isSending)) return@LaunchedEffect
        val reply = uiState.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && it.toolName == null
        } ?: return@LaunchedEffect
        val text = reply.content.trim()
        if (text.isEmpty()) return@LaunchedEffect
        val voiceTurn = engines.consumeVoiceTurn()
        if (!voiceTurn && !uiState.settings.voiceAutoSpeak) return@LaunchedEffect
        val tts = engines.tts
        val audio = engines.audioSession
        audio.onInterrupted = { tts.stop() }
        audio.requestFocus()
        engines.setSpeakingMessage(reply.id)
        try {
            tts.speakAndWait(text)
        } finally {
            engines.clearSpeakingMessage()
            audio.abandonFocus()
        }
    }
}

/**
 * 输入区右侧的录音键：放在发送键左边，始终在位。
 *
 * 只在生成中隐藏（那时这个位置留给停止/队列键）。不按「输入框是否为空」显隐：
 * 识别文字是实时写回输入框的，只要一开口输入框立刻就有内容，按空判断会让按钮
 * 在开口那一瞬自己消失。
 */
@Composable
private fun VoiceInputButton(
    isSending: Boolean,
    onPromptChange: (String) -> Unit,
) {
    if (isSending) return
    com.wy.openx.voice.VoiceMicButton(
        onPartial = onPromptChange,
        onResult = onPromptChange,
        tint = TextSecondary,
        activeTint = Accent,
    )
}

/**
 * 消息底部的「朗读」按钮：用系统 TTS 念这条回复，朗读中再点一次即停。
 *
 * 朗读状态是本函数私有的：图标只影响自己这一个按钮的重组。
 */
@Composable
private fun MessageSpeakButton(
    messageId: Long,
    content: String,
    idleTint: Color,
) {
    val scope = rememberCoroutineScope()
    // 朗读状态放在 VoiceEngines 里而不是本函数内部：自动朗读也会念这条消息，
    // 按钮得跟着一起点亮，不能只有「手动点过」才知道。
    val speakingId by com.wy.openx.voice.VoiceEngines.speakingMessageId.collectAsState()
    val speaking = speakingId == messageId
    IconButton(
        onClick = {
            val engines = com.wy.openx.voice.VoiceEngines
            val tts = engines.tts
            if (speaking) {
                tts.stop()
                engines.clearSpeakingMessage()
            } else {
                val audio = engines.audioSession
                audio.onInterrupted = { tts.stop(); engines.clearSpeakingMessage() }
                audio.requestFocus()
                engines.setSpeakingMessage(messageId)
                scope.launch {
                    try {
                        tts.speakAndWait(content)
                    } finally {
                        engines.clearSpeakingMessage()
                        audio.abandonFocus()
                    }
                }
            }
        },
        modifier = Modifier.size(18.dp)
    ) {
        Icon(
            Icons.Outlined.VolumeUp,
            contentDescription = "朗读",
            tint = if (speaking) Accent else idleTint,
            modifier = Modifier.size(14.dp)
        )
    }
}
