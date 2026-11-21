package com.wy.openx

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.wy.openx.data.model.effectiveUiScale
import com.wy.openx.tools.ScreenshotTool
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.window.DialogProperties
import com.wy.openx.ui.navigation.AppScreen
import com.wy.openx.ui.screen.toLegacy
import com.wy.openx.ui.screen.AgentConsoleScreen
import com.wy.openx.ui.screen.CommandPaletteOverlay
import com.wy.openx.ui.screen.MemoryManageScreen
import com.wy.openx.ui.screen.SettingsScreen
import com.wy.openx.ui.screen.SkillManageScreen
import com.wy.openx.ui.screen.WorkflowManageScreen
import com.wy.openx.ui.screen.McpManageScreen
import com.wy.openx.ui.screen.QuantTradeScreen
import com.wy.openx.ui.screen.TerminalScreen
import com.wy.openx.ui.theme.AgentConsoleTheme
import com.wy.openx.ui.theme.AppBackgroundLayer
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.GlassPageBrush
import com.wy.openx.ui.theme.isGlassTheme
import com.wy.openx.ui.theme.toBackgroundSpec
import com.wy.openx.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    /**
     * 与 `setContent` 里 `viewModel()` 取到的是**同一个**实例（同一 ViewModelStore + 默认 key）。
     * 这里持有它只为在 onStart/onStop 同步进程前后台状态，供悬浮窗完成判定用。
     */
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            ScreenshotTool.onPermissionResult(result.resultCode, result.data)
        }
        ScreenshotTool.setLauncher { intent -> screenCaptureLauncher.launch(intent) }

        // 离屏 WebView 隐藏宿主：浏览器面板关闭时，非激活标签与后台渲染管线仍需挂载到窗口
        // 才能触发 onAttachedToWindow 激活 Chromium 光栅化（否则 AI 后台截图全白）。
        // 关键：必须挂在 Activity 根（android.R.id.content）并移出屏幕外，且尺寸 1×1——
        // 若留在 Compose 布局内、又仅靠 Modifier.size(1.dp) 约束，子 WebView 的固定像素尺寸会
        // 溢出容器盖住顶栏，导致地址栏等区域点不动。
        //
        // 时序陷阱：setContent 内部走 ComponentActivity.setContentView → PhoneWindow.setContentView，
        // 而后者在 decor 已存在时会执行 mContentParent.removeAllViews()。因此**不能**在 setContent 之前
        // addView——那一步会提前安装 decor，随后整个宿主被 setContentView 摘除，WebView 永远收不到
        // onAttachedToWindow，Chromium 光栅化不启动，AI 截图恒为纯白图。
        // 正确做法：在组合完成后的 LaunchedEffect 里（setContentView 已执行完）再 addView。
        val hiddenBrowserHost = android.widget.FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
            alpha = 0.001f
            translationX = -10000f
        }

        setContent {
            val vm: MainViewModel = viewModel()
            val chatScrollState = remember { com.wy.openx.ui.screen.ChatScrollState() }
            var showCommandPalette by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (hiddenBrowserHost.parent == null) {
                    findViewById<android.view.ViewGroup>(android.R.id.content)?.addView(hiddenBrowserHost)
                }
                vm.browserManager.attachHiddenHost(hiddenBrowserHost)
                // [PLUGIN-MOD] 启动时扫描插件并刷新侧边栏快捷区
                vm.loadPlugins()
                // 启动 Git 缺凭据请求监听（容器内 credential helper → 文件 IPC → 全局弹窗）
                com.wy.openx.git.GitCredentialBridge.start()
            }
            AgentConsoleTheme(
                themeName = vm.uiState.settings.themeName,
                themeMode = vm.uiState.settings.themeMode,
                uiScale = vm.uiState.settings.effectiveUiScale
            ) {
                // 偏好设置「防止任务运行时屏幕休眠」：仅当开关开启**且**当前正在生成时才加
                // FLAG_KEEP_SCREEN_ON。用 DisposableEffect 在条件变化/离开时清理，避免常亮残留。
                // 用 Activity 的 window 标志而非 Compose 的 KeepScreenOn：后者作用在单个 View 上，
                // 而我们要的是整窗行为（弹窗、抽屉打开时也不熄屏）。
                DisposableEffect(
                    vm.uiState.settings.keepScreenOnWhileRunning,
                    vm.uiState.isSending
                ) {
                    val keepOn = vm.uiState.settings.keepScreenOnWhileRunning && vm.uiState.isSending
                    if (keepOn) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        // 组件销毁时无条件清掉，防止标志留在 window 上
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                // 偏好设置「后台保活」：开关变化时启停前台服务。
                // 也覆盖冷启动场景——设置里已开启时，进 App 即拉起服务。
                LaunchedEffect(vm.uiState.settings.backgroundKeepAlive) {
                    com.wy.openx.service.KeepAliveService.sync(
                        applicationContext,
                        vm.uiState.settings.backgroundKeepAlive
                    )
                }
                Surface(modifier = Modifier.fillMaxSize(), color = BgPrimary) {

                // 玻璃色主题：整屏最底层铺「两侧渐变浅色 → 中间透明玻璃」渐变。
                // 聊天页等消息区/内容区为透明背景，会自然透出这层玻璃底色；
                // 卡片/按钮用 GlassElementBrush 绘制中间透明、两侧浅色的玻璃元素底色。
                if (isGlassTheme) {
                    Box(modifier = Modifier.fillMaxSize().background(GlassPageBrush))
                }

                // 全应用背景图：铺在玻璃渐变之上、所有页面之下，只渲染这一次。
                // 各页面根容器底色走 PageBg（背景图激活时为透明），于是内容透出背景图。
                // 放在这里而不是各页面内部：滚动/切页时背景固定不动，且只解码一次。
                val bgSpec = vm.uiState.settings.toBackgroundSpec()
                // 先判定是否有图源，再渲染 —— 页面在同一次组合里就能读到正确值（无延迟帧）。
                // 与 isGlassTheme 同一写法（在组合中直接写全局 state），保持一致。
                com.wy.openx.ui.theme.isBackgroundImageActive = bgSpec.hasSource
                AppBackgroundLayer(spec = bgSpec, fallbackColor = BgPrimary)

                val activity = LocalContext.current as? Activity
                BackHandler(enabled = vm.screen == AppScreen.Console) {
                    activity?.moveTaskToBack(true)
                }
                BackHandler(enabled = vm.screen != AppScreen.Console) {
                    // 从「设置」下钻的页面，系统返回键要回设置页——而不是一路跳回聊天页。
                    // 之前统一 openConsole()，导致 Token 统计/日志/偏好设置按返回键直接跳过设置页。
                    when (vm.screen) {
                        AppScreen.TokenStats, AppScreen.Logs, AppScreen.Preferences,
                        AppScreen.Permissions, AppScreen.StorageUsage, AppScreen.Prompts,
                        AppScreen.About -> vm.backToSettings()
                        else -> vm.openConsole()
                    }
                }

                val isLandscape =
                    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                val page = vm.screen
                // 需求3：横屏时子页面不整屏接管，而是塞进聊天页左栏（右栏恒为聊天页）；
                //       竖屏维持原有的整屏切换。判定只看方向，与两栏布局的触发条件一致。
                when {
                    page == AppScreen.Console || isLandscape -> {
                        AgentConsoleScreen(
                            // 需求3：横屏时非 Console 页面塞进左栏（右栏恒为聊天页）。
                            leftPage = page != AppScreen.Console,
                            uiState = vm.uiState,
                            onPromptChange = { vm.updatePrompt(it) },
                            onSend = { vm.sendMessage() },
                            onStopStreaming = { vm.stopStreamingAndSendQueue() },
                            onOpenSettings = { vm.openSettings() },
                            onOpenTerminal = { vm.openTerminal() },
                            onOpenQuantTrade = { vm.openQuantTrade() },
                            onOpenBrowser = { vm.openBrowser() },
                            onOpenFileInBrowser = { path -> vm.openBrowser(path) },
                            onAttachToInput = { path, name -> vm.attachWorkspaceEntryToInput(path, name) },
                            onOpenWorkflow = { vm.openWorkflow() },
                            onOpenGit = { vm.openGit() },
                            // [PLUGIN-MOD]
                            onOpenPluginManage = { vm.openPluginManage() },
                            pluginQuickBarExpanded = vm.uiState.settings.pluginQuickBarExpanded,
                            onPluginQuickBarExpandedChange = { expanded ->
                                vm.updateSettings(vm.uiState.settings.copy(pluginQuickBarExpanded = expanded))
                            },
                            pluginQuickGroups = vm.uiState.pluginQuickGroups,
                            onPluginQuickAction = { action -> vm.onPluginQuickAction(action) },
                            onOpenPluginById = { id -> vm.onOpenPluginById(id) },
                            onClearAttachment = { vm.clearAttachments() },
                            onRemoveAttachment = { uri -> vm.removeAttachment(uri) },
                            onPickFileResult = { uri, name -> if (uri != null) vm.addAttachment(uri, name) },
                            onApplyQuickTask = { vm.updatePrompt(it) },
                            onNewConversation = { vm.createNewConversation() },
                            onSwitchConversation = { vm.switchConversation(it) },
                            onDismissAwaitingBanner = { vm.dismissAwaitingBanner() },
                            onToggleCommandPalette = { showCommandPalette = !showCommandPalette },
                            onExecuteCommand = { cmd -> vm.executeCommand(cmd) },
                            onDeleteConversation = { id -> vm.deleteConversation(id) },
                            onResendMessage = { msg -> vm.resendMessage(msg) },
                            currentToolName = vm.uiState.currentToolName,
                            onRegenerateMessage = { vm.regenerateMessage() },
                            onDeleteMessage = { id -> vm.deleteMessage(id) },
                            onForkMessage = { msg -> vm.forkFromMessage(msg) },
                            onEditMessage = { id, content ->
                                vm.editMessage(id, content)
                            },
                            onRewindMessage = { id, option ->
                                vm.rewindMessage(id, option)
                            },
                            pendingToolCall = vm.uiState.pendingToolCall,
                            onConfirmToolCall = { approved -> vm.onConfirmToolCall(approved) },
                            onAlwaysAllowTool = { toolName -> vm.onAlwaysAllowTool(toolName) },
                            pendingTerminalConfirm = vm.uiState.pendingTerminalConfirm,
                            onRespondTerminalConfirm = { approved -> vm.onRespondTerminalConfirm(approved) },
                            pendingQuestion = vm.uiState.pendingQuestion,
                            onSubmitAnswer = { answer -> vm.submitAnswer(answer) },
                            onCancelQuestion = { vm.cancelQuestion() },
                            tasks = vm.uiState.tasks,
                            tasksExpanded = vm.uiState.tasksExpanded,
                            onAddTask = { desc -> vm.addTask(desc) },
                            onUpdateTaskStatus = { id, status -> vm.updateTaskStatus(id, status) },
                            onRemoveTask = { id -> vm.removeTask(id) },
                            onClearCompletedTasks = { vm.clearCompletedTasks() },
onToggleTasksExpanded = { vm.toggleTasksExpanded() },
                            onRename = { id, title -> vm.renameConversation(id, title) },
                            onTogglePin = { id, pinned -> vm.togglePinConversation(id, pinned) },
                            onToggleArchive = { id, archived -> vm.setConversationArchived(id, archived) },
                            onOpenHistory = { vm.openHistory() },
                            onCancelQueuedMessage = { index -> vm.cancelQueuedMessage(index) },
                            onClearMessageQueue = { vm.clearMessageQueue() },
                            onSendQueuedMessage = { index -> vm.sendQueuedMessage(index) },
                            onSendAllQueuedMessages = { vm.sendAllQueuedMessages() },
                            onSwitchPreset = { preset -> vm.applyApiPreset(preset) },
                            agentMode = vm.uiState.agentMode,
                            onSetAgentMode = { vm.setAgentMode(it) },
                            toolPermissionMode = vm.uiState.toolPermissionMode,
                            onSetToolPermissionMode = { vm.setToolPermissionMode(it) },
                            reasoningEffort = vm.uiState.reasoningEffort,
                            onSetReasoningEffort = { vm.setReasoningEffort(it) },
                            // 思考强度选项按当前模型能力过滤（元数据缺失时 VM 返回 null → 显示全量）
                            reasoningEffortOptionsProvider = { vm.effortOptionsFor(vm.uiState.currentConversationId) },
                            projects = vm.projects,
                            currentProject = vm.uiState.settings.currentProject,
                            onCreateProject = { vm.createProject(it) },
                            onSelectProject = { vm.selectProject(it) },
                            onDeleteProject = { vm.deleteProject(it) },
                            externalWorkspaces = vm.uiState.settings.externalWorkspaces,
                            onAddExternalWorkspace = { vm.addExternalWorkspace(it) },
                            browseFileSystem = vm.browseFileSystem,
                            scrollState = chatScrollState,
                            onSearchConversationMessages = { id, query, cb -> vm.searchConversationMessages(id, query, cb) },
                            onJumpToMessage = { convId, msgId -> vm.jumpToMessage(convId, msgId) },
                            onConsumeMessageJump = { vm.consumeMessageJump() },
                            contextUsedTokens = vm.uiState.lastInputContext,
                            contextWindowTokens = vm.currentContextWindow(),
                            separateSendButton = vm.uiState.settings.separateSendButton,
                            onUpdatePresetMeta = { name, model, ctx, out -> vm.updatePresetMeta(name, model, ctx, out) },
                            providers = vm.providers,
                            providerModelMetadata = vm.providerModelMetadata,
                            onSelectProviderModel = { pid, model -> vm.selectProviderModel(pid, model) },
                            currentModelMeta = vm.currentModelMetadata(),
                            currentModelName = vm.currentModelName(),
                            onSaveCurrentModelMeta = { ctx, out -> vm.updateCurrentModelMeta(ctx, out) },
                            onAutoFetchCurrentModelMeta = { cb -> vm.autoFetchCurrentModelMeta(cb) },
                            onDismissRateLimitWarning = { vm.dismissRateLimitWarning() },
                            onKeepParallel = { vm.keepParallelToolCalls() },
                            onDisableParallelToolCalls = { vm.disableParallelToolCalls() },
                            leftPageContent = { AppPageHost(vm, page) { finishAffinity() } }
                        )
                    }
                    else -> AppPageHost(vm, page) { finishAffinity() }
                }

                if (showCommandPalette) {
                    CommandPaletteOverlay(
                        onDismiss = { showCommandPalette = false },
                        onExecuteCommand = { cmd -> vm.executeCommand(cmd); showCommandPalette = false },
                        agentMode = vm.uiState.agentMode
                    )
                }
                // 全局 Git 凭据弹窗：容器内任何 git 命令缺凭据都经 helper 文件 IPC 触发，三端共用
                com.wy.openx.ui.screen.GitCredentialPromptDialog()
                }

                // 备份恢复进行中：全屏遮罩，防止用户切走/返回键退出导致恢复中断。
                if (vm.restoreBusy) {
                    // 返回键吞掉，杜绝恢复期间误退出
                    BackHandler(enabled = vm.restoreBusy) { }
                    Dialog(
                        onDismissRequest = { /* 恢复中不可关闭，防止用户误退出 */ },
                        properties = DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                            usePlatformDefaultWidth = false
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = Color(0xFF35C58B),
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "正在恢复，请稍候…",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "恢复期间请勿退出 App 或关闭屏幕",
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放静态持有的 Activity 引用，避免泄漏
        ScreenshotTool.clearLauncher()
        com.wy.openx.browser.BrowserManager.getShared()?.detachHiddenHost()
        // 仅真正退出时移除悬浮窗；转屏（配置变更）也会走 onDestroy，此时进程仍在、窗口该留着，
        // 故用 isFinishing 区分。
        if (isFinishing) {
            com.wy.openx.service.FloatingOverlayController.getShared()?.release()
        }
    }

    override fun onStart() {
        super.onStart()
        // openx 是单 Activity 应用，Activity 可见即等价于「进程在前台」。
        // 悬浮窗只在后台显示（前台会挡住聊天界面），故在此同步状态。
        com.wy.openx.service.FloatingOverlayController.getShared()?.setAppInForeground(true)
        // VM 也需要知道前后台：任务在后台跑完时要标记「完成未读」，
        // 否则悬浮窗会在任务结束那一刻立即消失（用户来不及看结果）。
        mainViewModel.setAppInForeground(true)
    }

    override fun onResume() {
        super.onResume()
        // [A 高刷] 前台时把窗口刷新的 hint 指到「分辨率不变、刷新率最高」的档位，
        // 目标 120fps。设备面板不支持就保持现状（不会降级/改分辨率）。
        requestHighRefreshRate(activity = this)
    }

    override fun onStop() {
        super.onStop()
        com.wy.openx.service.FloatingOverlayController.getShared()?.setAppInForeground(false)
        mainViewModel.setAppInForeground(false)
    }
}

/**
 * [A 高刷] 给窗口一个「用高刷新率画」的 hint。
 *
 * 背景：手机支持 120Hz，但 App 从不请求，很多机型会把第三方 App 压在 60/90 自适应档
 * （正常使用 90 说明帧轻能贴 vsync；AI 执行时帧变重就直接掉到 30-60）。这里在 onResume
 * 时把 [android.view.WindowManager.LayoutParams.preferredDisplayModeId] 指到当前显示模式里
 * 「物理分辨率不变、刷新率最高」的那一档（目标 120fps）。国产机普遍尊重该 hint；
 * 面板不支持/系统拒绝时保持原状，不会改分辨率也不会降频。
 *
 * 只在 API 23+ 生效（`Display.supportedModes` 的引入版本），minSdk 24 全覆盖。
 */
@Suppress("DEPRECATION")
private fun requestHighRefreshRate(activity: Activity) {
    if (Build.VERSION.SDK_INT < 23) return
    val window = activity.window ?: return
    val display = if (Build.VERSION.SDK_INT >= 30) {
        activity.display
    } else {
        @Suppress("DEPRECATION")
        window.windowManager.defaultDisplay
    } ?: return
    val current = runCatching { display.mode }.getOrNull() ?: return
    if (current.refreshRate >= HIGH_REFRESH_TARGET_FPS) return
    val best = runCatching {
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxBy { it.refreshRate }
    }.getOrNull() ?: return
    if (best.refreshRate > current.refreshRate && best.refreshRate >= HIGH_REFRESH_TARGET_FPS) {
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }
}

private const val HIGH_REFRESH_TARGET_FPS = 120f

/**
 * 非 Console 子页面的统一渲染器（需求3 抽取而来）。
 *
 * 两处复用同一份代码：
 *   · 竖屏：vm.screen != Console 时整屏渲染（改造前的原始行为）
 *   · 横屏：作为 leftPageContent 塞进聊天页左栏，右栏恒为聊天页
 * 分支内部的 onBack = vm.openConsole() 在横屏下正好把左栏退回会话列表，
 * 设置页的 backToSettings() 等子跳转也会自动留在左栏内，无需额外适配。
 */
@Composable
private fun AppPageHost(vm: MainViewModel, page: AppScreen, exitApp: () -> Unit) {
    when (page) {
                    // Console 由 AgentConsoleScreen 渲染（左栏即会话历史），此处留空满足 sealed 穷尽。
                    AppScreen.Console -> {}
                    AppScreen.Settings -> {
                        SettingsScreen(
                            settings = vm.uiState.settings,
                            onBack = { vm.openConsole() },
                            onSave = { vm.updateSettings(it) },
                            onTest = { testSettings, callback -> vm.testCurrentApi(testSettings, callback) },
                            onExitApp = { exitApp() },
                            totalTokens = vm.uiState.totalTokens,
                            totalInputTokens = vm.uiState.totalInputTokens,
                            tokenDetails = vm.uiState.tokenDetails,
                            cacheHitTokens = vm.uiState.cacheHitTokens,
                            cacheHitDetails = vm.uiState.cacheHitDetails,
                            apiPresets = vm.uiState.apiPresets,
                            presetTestStates = vm.presetTestStates,
                            onTestPreset = { preset -> vm.testPreset(preset) },
                            onSavePreset = { name, provider, baseUrl, model, apiKey -> vm.savePreset(name, provider, baseUrl, model, apiKey) },
                            onDeletePreset = { name -> vm.deletePreset(name) },
                            onUpdatePresetMeta = { name, model, contextTokens, outputTokens -> vm.updatePresetMeta(name, model, contextTokens, outputTokens) },
                            onResetTokenStats = { vm.resetTokenStats() },
                            llmCallRecordDao = vm.llmCallRecordDao,
                            backupBusy = vm.backupBusy,
                            restoreBusy = vm.restoreBusy,
                            settingsRevision = vm.settingsRevision,
                            onOpenPreferences = { vm.openPreferences() },
                            onOpenTokenStats = { vm.openTokenStats() },
                            onOpenLogs = { vm.openLogs() },
                            onOpenPermissions = { vm.openPermissions() },
                            onOpenStorageUsage = { vm.openStorageUsage() },
                            onOpenPrompts = { vm.openPrompts() },
                            onOpenAbout = { vm.openAbout() },
                            onExportBackup = { out, sections, pwd, cb -> vm.exportBackup(out, sections, pwd, cb) },
                            onInspectBackup = { input, pwd, cb -> vm.inspectBackup(input, pwd, cb) },
                            onRestoreBackup = { input, mode, sections, pwd, cb -> vm.restoreBackup(input, mode, sections, pwd, cb) },
                            containerInstalled = vm.containerInstalled,
                            containerMirrorId = vm.containerApkMirrorId,
                            containerResetBusy = vm.containerResetBusy,
                            containerProfiles = vm.containerProfiles,
                            activeContainerId = vm.activeContainerId,
                            onApplyContainerMirror = { id -> vm.changeContainerMirror(id) },
                            runtimeTools = vm.runtimeTools.value,
                            onDetectRuntimeTools = { vm.detectRuntimeTools() },
                            onInstallRuntimeTool = { key -> vm.installRuntimeTool(key) },
                            onUninstallRuntimeTool = { key -> vm.uninstallRuntimeTool(key) },
                            npmRegistryId = vm.npmRegistryId.value,
                            onSetNpmRegistry = { id -> vm.setNpmRegistry(id) },
                            onResetContainer = { cb -> vm.resetContainer(cb) },
                            onApplyContainerProfile = { id, cb -> vm.applyContainerProfile(id, cb) },
                            onSaveContainerProfile = { p, setActive, isNew -> vm.saveContainerProfile(p, setActive, isNew) },
                            onDeleteContainerProfile = { p -> vm.deleteContainerProfile(p) },
                            onRestoreBuiltinAlpine = { vm.restoreBuiltinAlpine() },
                            // 读一次就够：这个值只在设置页里改，不需要跟着重组反复读文件
                            gradleHeapMb = remember { vm.readGradleHeapMb() },
                            onGradleHeapChange = { vm.writeGradleHeapMb(it) },
                            containerCatalog = vm.containerImageCatalog,
                            containerImageSources = vm.containerImageSources,
                            downloadedContainerImages = vm.downloadedImages,
                            containerDownloadState = vm.containerDownloadState,
                            onDownloadContainerImage = { entry, sourceId -> vm.downloadContainerImage(entry, sourceId) },
                            onCancelContainerDownload = { vm.cancelContainerImageDownload() },
                            onImportDownloadedImage = { entryId, fileUri -> vm.importDownloadedImage(entryId, fileUri) },
                            onDeleteDownloadedImage = { entryId -> vm.deleteDownloadedImage(entryId) },
                            logs = vm.uiState.logs,
                            onExportLogs = { vm.exportRunLogs() },
                            onClearLogs = { vm.clearRunLogs() },
                            providers = vm.providers,
                            onSaveProvider = { p -> vm.saveProvider(p) },
                            onDeleteProvider = { id -> vm.deleteProvider(id) },
                            onSetProviderEnabled = { id, enabled -> vm.setProviderEnabled(id, enabled) },
                            onSelectProvider = { id -> vm.selectProvider(id) },
                            providerFetchLoading = vm.providerFetchState is com.wy.openx.ui.viewmodel.MainViewModel.ProviderFetchState.Loading,
                            providerFetchModels = (vm.providerFetchState as? com.wy.openx.ui.viewmodel.MainViewModel.ProviderFetchState.Success)?.models ?: emptyList(),
                            providerFetchError = (vm.providerFetchState as? com.wy.openx.ui.viewmodel.MainViewModel.ProviderFetchState.Error)?.message,
                            providerModelMetadata = vm.providerModelMetadata,
                            providerTestResults = vm.providerTestResults,
                            providerTesting = vm.providerTesting,
                            onFetchProviderModels = { p -> vm.fetchProviderModels(p) },
                            onTestProviderModel = { p, m -> vm.testProviderModel(p, m) },
                            onTestAllProviderModels = { p, ms -> vm.testAllProviderModels(p, ms) },
                            onStopTestAllProviderModels = { vm.stopTestAllProviderModels() },
                            onStopTestProviderModel = { model -> vm.stopTestProviderModel(model) },
                            providerTestAllProgress = vm.providerTestAllProgress,
                            onResolveProviderMetadata = { id, type, models -> vm.resolveModelMetadata(id, type, models) },
                            onSaveCustomModelMetadata = { id, m, meta -> vm.saveCustomModelMetadata(id, m, meta) },
                            onRemoveCustomModelMetadata = { id, m -> vm.removeCustomModelMetadata(id, m) },
                            onLoadCustomModelMetadata = { vm.loadCustomModelMetadata() }
                        )
                    }
                    AppScreen.QuantTrade -> {
                        QuantTradeScreen(
                            onBack = { vm.openConsole() },
                            onSubmitPrompt = { prompt ->
                                vm.updatePrompt(prompt)
                                vm.sendMessage()
                                vm.openConsole()
                            }
                        )
                    }
                    AppScreen.Workflow -> {
                        WorkflowManageScreen(
                            workflows = vm.uiState.workflows,
                            runningName = vm.uiState.runningWorkflowName,
                            onBack = { vm.openConsole() },
                            onSave = { wf, existed -> vm.saveWorkflow(wf, existed) },
                            onDelete = { name -> vm.deleteWorkflow(name) },
                            onRun = { wf -> vm.runWorkflow(wf) }
                        )
                    }
                    AppScreen.Browser -> {
                        com.wy.openx.browser.BrowserScreen(
                            browserManager = vm.browserManager,
                            onNavigateBack = { vm.openConsole() }
                        )
                    }
                    AppScreen.Git -> {
                        val gitVm: com.wy.openx.ui.viewmodel.GitViewModel = viewModel()
                        com.wy.openx.ui.screen.GitScreen(
                            viewModel = gitVm,
                            onBack = { vm.openConsole() },
                            onOpenCredentials = { vm.openGitCredentials() },
                            currentProject = vm.uiState.settings.currentProject
                        )
                    }
                    AppScreen.GitCredentials -> {
                        val credVm: com.wy.openx.ui.viewmodel.CredentialsViewModel = viewModel()
                        com.wy.openx.ui.screen.CredentialsScreen(
                            viewModel = credVm,
                            onBack = { vm.openGit() }
                        )
                    }
                    AppScreen.History -> {
                        com.wy.openx.ui.screen.HistoryScreen(
                            // 与侧边栏同源：副标题实时派生，不读永不更新的 meta。
                            conversations = vm.uiState.archivedConversations.map { c ->
                                c.toLegacy().copy(
                                    meta = com.wy.openx.ui.screen.conversationMetaLabel(
                                        c,
                                        // sessionMode 参数 = 会话级模式（全局模式的取反）
                                        !vm.uiState.settings.modelGlobalMode,
                                        vm.uiState.settings.model
                                    )
                                )
                            },
                            onBack = { vm.openConsole() },
                            onSelect = { id ->
                                vm.switchConversation(id)
                                vm.openConsole()
                            },
                            onUnarchive = { id -> vm.setConversationArchived(id, false) },
                            onDelete = { id -> vm.deleteConversation(id) }
                        )
                    }
                    AppScreen.Preferences -> {
                        com.wy.openx.ui.screen.PreferencesScreen(
                            settings = vm.uiState.settings,
                            onBack = { vm.openSettings() },
                            onUpdate = { vm.updateSettings(it) },
                            onAutoArchiveDaysChanged = { days -> vm.triggerAutoArchive(days) }
                        )
                    }
                    AppScreen.TokenStats -> {
                        com.wy.openx.ui.screen.TokenStatsSheet(
                            dao = vm.llmCallRecordDao,
                            onDismiss = { vm.backToSettings() },
                            onReset = { vm.resetTokenStats() },
                            // 已配置的渠道补进筛选下拉：只在旧记录里出现过的渠道也要能选
                            configuredProviders = vm.providers.map { it.id to it.name }
                        )
                    }
                    AppScreen.Permissions -> {
                        com.wy.openx.ui.screen.PermissionsScreen(
                            onBack = { vm.backToSettings() }
                        )
                    }
                    AppScreen.About -> {
                        com.wy.openx.ui.screen.AboutScreen(
                            onBack = { vm.backToSettings() }
                        )
                    }
                    AppScreen.StorageUsage -> {
                        com.wy.openx.ui.screen.StorageUsageScreen(
                            onBack = { vm.backToSettings() },
                            onResetContainer = { vm.resetContainer { } },
                            onCategoryCleared = { id -> if (id == "knowledge") vm.loadKnowledge() }
                        )
                    }
                    // 设置 → 提示词：内置 AI 行为规则的查看 / 编辑 / 启停（返回键回设置页）
                    AppScreen.Prompts -> {
                        com.wy.openx.ui.screen.PromptManageScreen(
                            rules = vm.uiState.agentRuleItems,
                            enabled = vm.uiState.settings.agentRulesEnabled,
                            disabledKeys = vm.uiState.settings.agentRuleDisabled,
                            onBack = { vm.backToSettings() },
                            onToggleEnabled = { vm.setAgentRulesEnabled(it) },
                            onToggleRule = { key, on -> vm.toggleAgentRuleEnabled(key, on) },
                            onSaveRule = { key, body, toProject -> vm.saveAgentRule(key, body, toProject) },
                            onResetRule = { key, source -> vm.resetAgentRule(key, source) },
                            onCreateRule = { n, name, body, toProject ->
                                vm.createAgentRule(n, name, body, toProject)
                            },
                            hasProject = vm.uiState.settings.currentProject.isNotBlank(),
                            projectName = vm.uiState.settings.currentProject
                        )
                    }
                    AppScreen.Logs -> {
                        com.wy.openx.ui.screen.LogSheet(
                            logs = vm.uiState.logs,
                            onExport = { vm.exportRunLogs() },
                            onClear = { vm.clearRunLogs() },
                            onDismiss = { vm.backToSettings() },
                            logDirPath = com.wy.openx.data.log.AppLogStore.logDir?.absolutePath
                        )
                    }
                    AppScreen.Terminal -> {
                        TerminalScreen(
                            onBack = { vm.openConsole() },
                            // 显式传 VM 持有的那个引擎，不再依赖 AppContextHolder 的时序
                            engine = vm.terminal
                        )
                    }
                    AppScreen.MemoryManage -> {
                        MemoryManageScreen(
                            memoryItems = vm.uiState.memoryItems,
                            projectMemoryItems = vm.uiState.projectMemoryItems,
                            currentProject = vm.uiState.settings.currentProject,
                            onBack = { vm.openConsole() },
                            onDeleteMemory = { key, type, scope -> vm.deleteMemory(key, type, scope) },
                            onSaveMemory = { key, value, type, scope -> vm.saveMemory(key, value, type, scope) },
                            knowledgeItems = vm.uiState.knowledgeItems,
                            onDeleteKnowledge = { id -> vm.deleteKnowledge(id) },
                            onSaveKnowledge = { id, title, body, tags -> vm.saveKnowledge(id, title, body, tags) },
                            onImportKnowledge = { uri, replaceId -> vm.importKnowledgeFromUri(uri, replaceId) }
                        )
                    }
                    AppScreen.SkillManage -> {
                        SkillManageScreen(
                            skills = vm.uiState.skills,
                            projectSkills = vm.uiState.projectSkills,
                            currentProject = vm.uiState.settings.currentProject,
                            onBack = { vm.openConsole() },
                            onDelete = { name, scope -> vm.deleteSkill(name, scope) },
                            onToggle = { name, enabled, scope -> vm.toggleSkill(name, enabled, scope) },
                            onImportMarkdown = { uri, name, scope -> vm.importSkillFromMarkdown(uri, name, scope) },
                            onImportArchive = { uri, name, scope -> vm.importSkillFromArchive(uri, name, scope) }
                        )
                    }
                    AppScreen.McpManage -> {
                        McpManageScreen(
                            entries = vm.uiState.mcpServers,
                            onBack = { vm.openConsole() },
                            onAdd = { config, scope -> vm.addMcpServer(config, scope) },
                            onUpdate = { oldName, oldScope, config, newScope -> vm.updateMcpServer(oldName, oldScope, config, newScope) },
                            onDelete = { name, scope -> vm.deleteMcpServer(name, scope) },
                            onToggle = { name, scope, enabled -> vm.toggleMcpServer(name, scope, enabled) },
                            onTest = { name -> vm.testMcpConnection(name) },
                            testResults = vm.uiState.mcpTestResults,
                            testing = vm.uiState.mcpTesting,
                            onLeave = { vm.clearMcpTestState() }
                        )
                    }
                    // [PLUGIN-MOD] 插件管理页
                    AppScreen.PluginManage -> {
                        com.wy.openx.plugin.PluginManageScreen(
                            plugins = vm.uiState.plugins,
                            onBack = { vm.openConsole() },
                            onToggle = { id, enabled -> vm.togglePlugin(id, enabled) },
                            onUninstall = { id -> vm.uninstallPlugin(id) },
                            onImportJsText = { name, src -> vm.importPluginJs(name, src) },
                            onImportArchive = { uri, name -> vm.importPluginArchive(uri, name) },
                            installResult = vm.uiState.pluginInstallResult,
                            onDismissInstallResult = { vm.dismissPluginInstallResult() },
                            onOpenPluginUi = { plugin -> vm.openPluginUi(plugin) },
                            onQuickAction = { action -> vm.onPluginQuickAction(action) },
                            servicePorts = vm.uiState.pluginServices,
                            serviceStarting = vm.uiState.pluginServiceStarting,
                            serviceErrors = vm.uiState.pluginServiceErrors,
                            onStartService = { id -> vm.startPluginService(id) },
                            onStopService = { id -> vm.stopPluginService(id) },
                            onRefreshServiceStatus = { id -> vm.refreshPluginServiceStatus(id) },
                            pluginEnv = vm.uiState.pluginEnv,
                            onUpdatePluginSetting = { id, k, v -> vm.updatePluginSetting(id, k, v) }
                        )
                    }
                    // [PLUGIN-MOD] 插件自定义界面（Compose DSL）
                    AppScreen.PluginUi -> {
                        val active = vm.activePlugin
                        if (active == null) {
                            // 直链/恢复时 activePlugin 可能为空，退回管理页避免白屏
                            androidx.compose.runtime.LaunchedEffect(Unit) { vm.openPluginManage() }
                        } else {
                            com.wy.openx.plugin.ui.PluginUiScreen(
                                plugin = active,
                                onBack = { vm.closePluginUi() },
                                store = vm.pluginStoreRef(),
                                toolRegistry = vm.toolRegistryRef(),
                                onToast = { msg -> vm.showToast(msg) },
                                onServiceChanged = {
                                    // 桥回调在 QuickJS 线程；uiState 写入切回主线程，避免重组竞争
                                    runCatching {
                                        android.os.Handler(android.os.Looper.getMainLooper())
                                            .post { vm.refreshPluginServices() }
                                    }
                                },
                                // 插件页顶栏 ≡ 打开的侧边栏：与聊天页渲染同一份内容
                                drawerContent = {
                                    com.wy.openx.ui.screen.AppSidebarContent(vm = vm)
                                }
                            )
                        }
                    }
    }
}

