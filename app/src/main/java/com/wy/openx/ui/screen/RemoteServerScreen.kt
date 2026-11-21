package com.wy.openx.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.remote.RemoteConnection
import com.wy.openx.remote.RemoteEngines
import com.wy.openx.remote.RemoteHostKeyPendingException
import com.wy.openx.remote.RemoteMount
import com.wy.openx.remote.SavedSshKey
import com.wy.openx.ui.component.FloatingTabBar
import com.wy.openx.ui.component.FloatingTabItem
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 「连接与同步」独立页：连接配置 / 挂载目录 / 内置 FTP 三页。
 *
 * 做成独立页而不是设置页里的分区：三个 tab 各自是完整的列表页（含空态、拖拽排序、
 * 弹窗编辑），塞进设置页的滚动容器会和外层手势打架，也没地方放底部 tab 栏。
 *
 * 拆分原则（JVM 单方法 64KB 上限）：本函数只做骨架编排（顶栏、pager、tab 栏、
 * 各弹窗开关），三个 tab 的内容各自独立成 composable。
 */
@Composable
internal fun RemoteServerScreen(
    onBack: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    val manager = RemoteEngines.manager
    val activeState by manager.activeState.collectAsState()
    val scope = rememberCoroutineScope()

    var connections by remember { mutableStateOf<List<RemoteConnection>>(emptyList()) }
    var mounts by remember { mutableStateOf<List<RemoteMount>>(emptyList()) }

    var editingConnection by remember { mutableStateOf<RemoteConnection?>(null) }
    var showConnectionEditor by remember { mutableStateOf(false) }
    var editingMount by remember { mutableStateOf<RemoteMount?>(null) }
    var showMountEditor by remember { mutableStateOf(false) }
    var showSyncSettings by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    // 待用户拍板的主机指纹（首次连接 / 指纹已变化），以及信任后要重连的那条配置
    var pendingHostKey by remember { mutableStateOf<RemoteHostKeyPendingException?>(null) }
    var retryConnection by remember { mutableStateOf<RemoteConnection?>(null) }
    // 远程终端：非空时整页交给它
    var terminalConnection by remember { mutableStateOf<RemoteConnection?>(null) }
    // 密钥页与粘贴页是二级页：进入时盖在连接页上方，不占 tab
    var showKeys by remember { mutableStateOf(false) }
    var showPasteKey by remember { mutableStateOf(false) }
    // 粘贴页的预填：新增时为 null；从文件导入 / 点已有密钥编辑时带着草稿
    var pasteDraft by remember { mutableStateOf<SavedSshKey?>(null) }

    fun refresh() {
        connections = manager.connections.value
        mounts = manager.mounts.value
    }

    /**
     * 连接失败的统一处理：指纹待确认的转成弹窗，其余按普通错误提示。
     *
     * 抛异常时 SSH 握手已经中止了，所以用户点「信任」之后必须**重连一次**才算接上，
     * 不能只存下指纹就当作连好了。
     */
    fun handleConnectFailure(error: Throwable, config: RemoteConnection) {
        val pending = error as? RemoteHostKeyPendingException
        if (pending == null) {
            onFeedback(error.message ?: "连接失败")
            return
        }
        pendingHostKey = pending
        retryConnection = config
    }

    LaunchedEffect(Unit) {
        runCatching { manager.load() }
        manager.loadSyncSettings()
        refresh()
        // 自动连接：上次退出时开着的挂载，进页面就恢复（用户期望「自动连接」就是真的自动）
        mounts.filter { it.autoConnect }.forEach { mount ->
            runCatching { manager.startMount(mount) }
        }
    }

    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    val tab = pagerState.currentPage

    // 系统返回键逐级退：粘贴页 → 密钥页 → 连接页 → 设置首页。
    // 不加这一层的话，事件会直接落到设置页那个「退出整个分区」的 BackHandler，
    // 于是从粘贴页按返回会一路退出到设置第一页，而不是回到上一级。
    // 本 Handler 在设置页之后注册（更内层），系统优先派给它。
    BackHandler(enabled = showPasteKey) { showPasteKey = false }
    BackHandler(enabled = !showPasteKey && showKeys) { showKeys = false }

    // 二级页（密钥 / 粘贴密钥）的提前返回必须放在页面渲染之前：
    // 放到后面的话主页面已经组合完了，两页会叠在一起画，背景也会重一遍。
    // 放在 LaunchedEffect 之后是为了保住自动连接等副作用——提前返回会把它连带取消。
    //
    // **粘贴页必须先判**：它是密钥页的子页，而两者互不排斥（从密钥页进入粘贴页时
    // showKeys 仍是 true）。把 showKeys 放前面会直接 return，粘贴页永远渲染不到。
    // 远程终端页：全屏接管，优先级高于其它二级页
    terminalConnection?.let { config ->
        RemoteTerminalScreen(config = config, onBack = { terminalConnection = null })
        return
    }

    if (showPasteKey) {
        RemotePasteKeyScreen(
            initial = pasteDraft,
            onBack = { showPasteKey = false },
            onSaved = {
                // 存完退回密钥页（showKeys 保持 true），让用户直接看到刚添加/改完的密钥
                showPasteKey = false
                pasteDraft = null
            },
            onFeedback = onFeedback,
        )
        return
    }

    if (showKeys) {
        RemoteKeysScreen(
            onBack = { showKeys = false },
            onPasteKey = { draft ->
                pasteDraft = draft
                showPasteKey = true
            },
            onFeedback = onFeedback,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        // imePadding 加在内容列（而不是外层 Box）上：底部悬浮 tab 栏不是内容，
        // 跟着键盘一起上浮会挡住最后一屏内容，也会在键盘上方多出一条悬浮栏。
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            RemoteTopBar(
                tab = tab,
                onBack = onBack,
                onAdd = {
                    if (tab == 0) {
                        editingConnection = null
                        showConnectionEditor = true
                    } else {
                        editingMount = null
                        showMountEditor = true
                    }
                },
                onHostKeys = { showKeys = true },
                onSyncSettings = { showSyncSettings = true },
                onGuide = { showGuide = true },
                // 内置 FTP 页不需要「+」：那一页是单个服务器的开关面板
                showAdd = tab != 2,
            )

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> RemoteConnectionsTab(
                        connections = connections,
                        onConnect = { config ->
                            scope.launch {
                                runCatching { manager.connect(config) }
                                    .onSuccess { onFeedback("已连接 ${config.name}") }
                                    .onFailure { handleConnectFailure(it, config) }
                            }
                        },
                        onDisconnect = {
                            scope.launch {
                                manager.disconnect()
                                onFeedback("已断开")
                            }
                        },
                        onEdit = { config ->
                            editingConnection = config
                            showConnectionEditor = true
                        },
                        onDelete = { config ->
                            scope.launch {
                                manager.delete(config.id)
                                refresh()
                                onFeedback("已删除 ${config.name}")
                            }
                        },
                        activeState = activeState,
                        onOpenTerminal = { config -> terminalConnection = config },
                    )
                    1 -> RemoteMountsTab(
                        mounts = mounts,
                        connections = connections,
                        onEdit = { mount ->
                            editingMount = mount
                            showMountEditor = true
                        },
                        onDelete = { mount ->
                            scope.launch {
                                manager.deleteMount(mount.id)
                                refresh()
                                onFeedback("已删除挂载")
                            }
                        },
                        onToggle = { mount, start ->
                            scope.launch {
                                if (start) {
                                    runCatching { manager.startMount(mount) }
                                        .onSuccess { onFeedback("已启动同步") }
                                        .onFailure { onFeedback(it.message ?: "启动失败") }
                                } else {
                                    manager.stopMount(mount.id)
                                    onFeedback("已停止同步")
                                }
                            }
                        },
                        onReorder = { ids ->
                            scope.launch {
                                manager.reorderMounts(ids)
                                refresh()
                            }
                        },
                        onFeedback = onFeedback,
                    )
                    else -> RemoteFtpTab(onFeedback = onFeedback)
                }
            }
        }

        FloatingTabBar(
            selected = pagerState.currentPage,
            onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
            items = listOf(
                FloatingTabItem(Icons.Outlined.Settings, "连接配置"),
                FloatingTabItem(Icons.Outlined.VpnKey, "挂载目录"),
                FloatingTabItem(Icons.Default.Add, "内置FTP"),
            ),
            maskColor = BgPrimary,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showConnectionEditor) {
        RemoteServerEditDialog(
            initial = editingConnection,
            onHostKeyPending = { pending, config ->
                // 编辑器弹不了这层：它自己就在弹窗上，再叠一层会互相盖。
                // 交给本页统一展示。
                pendingHostKey = pending
                retryConnection = config
            },
            onDismiss = { showConnectionEditor = false },
            onSave = { saved ->
                showConnectionEditor = false
                scope.launch {
                    manager.save(saved)
                    refresh()
                    onFeedback("已保存 ${saved.name}")
                }
            },
        )
    }

    if (showMountEditor) {
        RemoteMountEditDialog(
            initial = editingMount,
            connections = connections,
            onDismiss = { showMountEditor = false },
            onSave = { saved ->
                showMountEditor = false
                scope.launch {
                    manager.saveMount(saved)
                    refresh()
                    onFeedback("已保存挂载")
                }
            },
        )
    }

    if (showGuide) {
        GuideDialog(
            title = "连接与同步说明",
            blocks = listOf(
                "三个页签" to "底部切换：连接配置（SSH/SFTP 服务器）、挂载目录（远端目录同步到本地）、内置 FTP（手机文件共享给电脑）。",
                "连接配置" to "「+」新增服务器；点 ▷ 连接，再点终端图标开远程终端、文档图标浏览文件、垃圾桶删除。",
                "挂载目录" to "把远端目录绑到本地工作区，改了本地文件自动上传，AI 也能直接读写。",
                "内置 FTP" to "在手机上开 FTP 服务，电脑用 FTP 客户端访问手机目录；端口、账号密码、自启在页内配置。",
                "顶栏图标" to "钥匙 = 主机密钥管理；齿轮 = 同步设置。首次连接会弹主机指纹确认，指纹变化也会拦下。"
            ),
            onDismiss = { showGuide = false }
        )
    }

    if (showSyncSettings) {
        RemoteSyncSettingsDialog(
            current = manager.syncSettings,
            onSave = { settings ->
                manager.saveSyncSettings(settings)
                // 设置固化在引擎构造参数里，重启挂载才能生效
                manager.resetSyncEngines()
                showSyncSettings = false
                onFeedback("已保存同步设置")
            },
            onDismiss = { showSyncSettings = false },
        )
    }

    // 主机指纹确认：首次连接或指纹变化时拦住，由用户决定信不信
    pendingHostKey?.let { pending ->
        RemoteHostKeyDialog(
            pending = pending,
            onTrust = {
                // 先把用户看到的那个指纹存下去，再重连；顺序反了会出现
                //「信任的和连上的不是一个」的窗口期。
                manager.trustHostKey(pending.host, pending.port, pending.fingerprint)
                pendingHostKey = null
                val config = retryConnection
                retryConnection = null
                if (config != null) {
                    scope.launch {
                        runCatching { manager.connect(config) }
                            .onSuccess { onFeedback("已信任指纹并连接 ${config.name}") }
                            .onFailure { onFeedback(it.message ?: "连接失败") }
                    }
                }
            },
            onCancel = {
                pendingHostKey = null
                retryConnection = null
                onFeedback("已取消，指纹未记录")
            },
        )
    }
}

/**
 * 顶栏：返回、标题、主机密钥、同步设置、新增。
 *
 * 用自绘 Row 而不是 Material3 TopAppBar：与设置页顶栏的尺寸（40dp 按钮 + 20dp 图标）
 * 保持一致，避免不同页面顶栏高度不一致。
 */
@Composable
private fun RemoteTopBar(
    tab: Int,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onHostKeys: () -> Unit,
    onSyncSettings: () -> Unit,
    onGuide: () -> Unit,
    showAdd: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            "连接与同步",
            fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onGuide, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = "使用说明", tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onHostKeys, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.VpnKey, contentDescription = "主机密钥", tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onSyncSettings, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Settings, contentDescription = "同步设置", tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
        if (showAdd) {
            IconButton(onClick = onAdd, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Add, contentDescription = "新增", tint = Accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}
