package com.wy.openx.plugin.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.wy.openx.container.FileLogger
import com.wy.openx.plugin.ui.UiNodeParser.bool
import com.wy.openx.plugin.ui.UiNodeParser.str
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.plugin.InstalledPlugin
import com.wy.openx.plugin.PluginStore
import com.wy.openx.tools.ToolRegistry
import com.wy.openx.ui.screen.ManageTopBar
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 等用户在选择器里操作的最长时间；超时视为取消（避免 JS 线程永久阻塞）。 */
private const val PICK_TIMEOUT_MS = 5L * 60 * 1000

/**
 * 插件界面页：打开某个插件的 Compose DSL UI。
 *
 * 宿主与插件的交界点：
 *   · [PluginUiSession] 持有 QuickJS，负责 render / dispatch / reload
 *   · 本页负责「把节点树画出来」、「把点击转成 dispatch」、以及需要 Activity 的
 *     选择器（[PluginHostActions]）
 *
 * 线程：所有 JS 操作都在 [Dispatchers.Default] 上跑（QuickJS 不能上主线程），
 * 渲染结果回到主线程更新 Compose 状态。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PluginUiScreen(
    plugin: InstalledPlugin,
    onBack: () -> Unit,
    store: PluginStore,
    toolRegistry: ToolRegistry?,
    onToast: (String) -> Unit = {},
    /** 面板里启停服务成功后回调（同步刷新插件管理页/卡片状态）。 */
    onServiceChanged: () -> Unit = {},
    /** 侧边栏内容（顶栏 ≡ 按钮打开）。宿主注入的是与聊天页同一份侧边栏。 */
    drawerContent: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 渲染结果 / 错误 / 加载中
    var result by remember { mutableStateOf<UiRenderResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // JS 侧 useState setter 触发（递增）
    var rerenderTick by remember { mutableIntStateOf(0) }
    // 热重载触发（递增）
    var reloadTick by remember { mutableIntStateOf(0) }
    // 调试面板
    var showDebug by remember { mutableStateOf(false) }
    var debugText by remember { mutableStateOf("") }

    // ---- 选择器：把「启动 launcher → 等回调」桥成挂起函数 ----
    val pickPromise = remember { UriPromise() }
    val savePromise = remember { UriPromise() }
    val imagePromise = remember { UriPromise() }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> pickPromise.resolve(uri) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> savePromise.resolve(uri) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> imagePromise.resolve(uri) }

    val hostActions = remember {
        object : PluginHostActions {
            override suspend fun pickFile(mimeTypes: List<String>): PluginHostActions.PickedFile? {
                val d = pickPromise.newPromise()
                withContext(Dispatchers.Main) {
                    pickLauncher.launch(mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray())
                }
                val uri = withTimeoutOrNull(PICK_TIMEOUT_MS) { d.await() } ?: return null
                return withContext(Dispatchers.IO) { PluginUriUtils.readText(context, uri) }
            }

            override suspend fun saveFile(suggestedName: String, content: String): String? {
                val d = savePromise.newPromise()
                withContext(Dispatchers.Main) { saveLauncher.launch(suggestedName) }
                val uri = withTimeoutOrNull(PICK_TIMEOUT_MS) { d.await() } ?: return null
                return withContext(Dispatchers.IO) {
                    PluginUriUtils.writeText(context, uri, content)
                }
            }

            override suspend fun pickImage(): String? {
                val d = imagePromise.newPromise()
                withContext(Dispatchers.Main) { imageLauncher.launch("image/*") }
                val uri = withTimeoutOrNull(PICK_TIMEOUT_MS) { d.await() } ?: return null
                return withContext(Dispatchers.IO) { PluginUriUtils.toDataUri(context, uri) }
            }
        }
    }

    val session = remember(plugin.manifest.id) {
        PluginUiSession(
            context = context,
            plugin = plugin,
            store = store,
            toolRegistry = toolRegistry,
            onToast = onToast,
            onServiceChanged = onServiceChanged,
            onRerenderRequest = {
                // 两条调用来源的线程不同：useState setter 走渲染所在的后台线程，
                // 定时器回调在 QuickJS 引擎线程。rerenderTick 是 Compose state，
                // 只能在主线程写，否则重组可能不触发。
                scope.launch { rerenderTick++ }
            },
            hostActions = hostActions,
            // 挂起请求（选图等）在这个作用域里等；Session 关闭时协程自动取消
            hostScope = scope
        )
    }

    // 退出必须释放引擎：QuickJS 的 native 资源不会自动回收
    DisposableEffect(session) {
        onDispose { runCatching { session.close() } }
    }

    // 侧边栏抽屉状态留在本页；内容由宿主注入（与聊天页那份是同一套）
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // 抽屉开着时返回键先收抽屉（ModalNavigationDrawer 内部也会拦，这里兼容旧版本行为）
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 插件用了 ctx.router 时，返回键先回退一页；栈空了才退出面板。
    // 没用路由的旧插件 popRouteIfPossible() 直接返回 false，行为与以前完全一致。
    BackHandler {
        if (!session.popRouteIfPossible()) onBack()
    }

    // 首次启动
    LaunchedEffect(Unit) {
        loading = true
        val r = withContext(Dispatchers.Default) { session.start() }
        result = r
        error = r?.let { null } ?: session.lastError
        loading = false
    }

    // 热重载（按钮触发）
    LaunchedEffect(reloadTick) {
        if (reloadTick == 0) return@LaunchedEffect
        loading = true
        val r = withContext(Dispatchers.Default) { session.reload() }
        result = r
        error = r?.let { null } ?: session.lastError
        loading = false
    }

    // JS 请求重渲染（useState setter）
    LaunchedEffect(rerenderTick) {
        if (rerenderTick == 0) return@LaunchedEffect
        val r = withContext(Dispatchers.Default) { session.render() }
        if (r != null) {
            result = r
            error = null
        } else {
            error = session.lastError
        }
    }

    var showRisky by remember { mutableStateOf(false) }
    ModalNavigationDrawer(
        drawerState = drawerState,
        // 插件页自身就有上下滚动和横向手势，关闭状态下再挂一层边缘滑动手势会互相抢。
        // 但 material3 的遮罩点击关闭也受这个开关控制，一律 false 会让「点外部收起」失效，
        // 所以只在抽屉已打开时启用（那时内容被遮罩盖住，不会和页面手势冲突）。
        gesturesEnabled = drawerState.targetValue == DrawerValue.Open,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = BgPrimary,
                modifier = Modifier.width(300.dp)
            ) { drawerContent() }
        }
    ) {
        Column(
            // 键盘弹出时整页上收，否则底部输入框会被悬浮键盘盖住（本 App 是 edge-to-edge，
            // adjustResize 不生效，必须自己消费 ime inset）
            modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding().imePadding()
        ) {
            ManageTopBar(title = plugin.manifest.name, onBack = onBack) {
                // 侧边栏：就地打开文件管理器（容器文件树，与主界面侧边栏同一份实现）
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "侧边栏文件", tint = TextSecondary)
                }
                val risky = plugin.manifest.grantedPermissions.filter { it.dangerous }
                if (risky.isNotEmpty()) {
                    IconButton(onClick = { showRisky = !showRisky }) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = "高危权限", tint = if (showRisky) Color(0xFFFFA726) else TextSecondary)
                    }
                }
                // 调试面板：看节点树 JSON，排查"组件没渲染出来"这类问题
                IconButton(onClick = {
                    debugText = session.treeJson()
                    showDebug = true
                }) {
                    Icon(Icons.Outlined.BugReport, contentDescription = "调试", tint = TextSecondary)
                }
                // 热重载：改完脚本不用退出重进
                IconButton(onClick = { reloadTick++ }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重载", tint = TextSecondary)
                }
            }

            // 高危权限提示条：默认隐藏，点击右上角按钮显示/隐藏
            val risky = plugin.manifest.grantedPermissions.filter { it.dangerous }
            if (risky.isNotEmpty() && showRisky) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFA726).copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        "⚠ 此插件已获得权限：" + risky.joinToString("、") { it.wireName },
                        color = Color(0xFFFFA726),
                        fontSize = 11.sp
                    )
                }
            }

            // Column 给未加权子项的是「无界」高度约束，此时 fillMaxSize 的高度部分失效，
            // 退化成 wrapContent，WebView 测出 ≈0 高（表现为整页黑屏）。用 weight(1f).fillMaxWidth().fillMaxHeight()
            // 让内容区显式吃掉 Column 剩余高度，等价官方 Scaffold content 区拿到的有界约束。
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && result == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }

                error != null && result == null -> ErrorPane(
                    error = error.orEmpty(),
                    onRetry = { reloadTick++ }
                )

                else -> {
                    val tree = result?.tree
                    if (tree == null) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("插件没有返回界面内容", color = TextSecondary)
                        }
                    } else {
                        // reloadTick 进 key：重载后重建 context，renderEpoch 变化会让
                        // RenderUiTree 重新派发根节点 onLoad（否则插件初始化永不启动）。
                        val renderCtx = remember(result, reloadTick) {
                            UiRenderContext(
                                renderEpoch = reloadTick,
                                pluginDir = plugin.dirPath,
                                onAction = { actionId, payload, rawValue ->
                                    scope.launch {
                                        // 捕异常并转成可见错误（之前只判 null，而 dispatchAction
                                        // 失败时返回的是上一帧的 lastRendered，非 null，
                                        // 于是 result 被赋成旧树、error 不更新 → 看着像没反应）
                                        val outcome = runCatching {
                                            withContext(Dispatchers.Default) {
                                                session.dispatchAction(actionId, payload, rawValue)
                                            }
                                        }
                                        val ex = outcome.exceptionOrNull()
                                        if (ex != null) {
                                            error = "动作执行异常：${ex.message ?: ex.javaClass.simpleName}"
                                            return@launch
                                        }
                                        val o = outcome.getOrNull()
                                        error = session.lastError
                                        if (o?.result != null) {
                                            result = o.result
                                        } else {
                                            error = error ?: "动作未返回结果"
                                        }
                                        // JS 里调了 setState → 再渲染一次。
                                        // 放在**派发完全结束之后**，避免与派发抢状态。
                                        if (o?.needsRerender == true) {
                                            rerenderTick++
                                        }
                                    }
                                },
                                stateOf = { key -> result?.state?.get(key) },
                                // 渲染器据此做能力降级（如网络图无 http 权限时不发请求）
                                permissions = plugin.manifest.grantedPermissions,
                                // WebView 的同步回调（资源拦截/导航决策）要当场拿到 JS 结果。
                                // 它会在 WebView 的 IO 线程被调到，session 内有锁，安全。
                                invokeSync = { actionId, payloadJson ->
                                    runCatching { session.invokeActionSync(actionId, payloadJson) }.getOrNull()
                                }
                            )
                        }
                        // 内容超出视口时要能滚。但 `verticalScroll` 给子项的是「无限」高度约束，
                        // WebView 无内在高度会塌成 0、LazyColumn 会失去懒加载，所以按树里
                        // 有没有这类节点分流：有 → 保持有界，滚动交给组件自己；没有 → 外层套滚动。
                        // 滚动加在内层 Box 自己的 modifier 上而不是换容器：容器结构恒定，
                        // RenderUiTree 的 composition 位置就不变，根节点 onLoad 的 remember 不会
                        // 因分支切换而重置（否则插件从「占位」变成「带 webview」时会重复派发）。
                        // 外层 Box 不滚：错误横幅靠 align(TopCenter) 悬在内容之上，需要常驻可见。
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.fillMaxSize().then(
                                    if (tree.needsBoundedHeight()) Modifier
                                    else Modifier.verticalScroll(rememberScrollState())
                                )
                            ) {
                                RenderUiTree(tree, renderCtx, Modifier.fillMaxSize())
                            }
                            // 错误横幅叠在顶部，不参与内容布局（否则会把内容压扁）
                            error?.let { msg ->
                                Box(
                                    Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                        .background(Color(0xFFFF6B6B).copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            "⚠ $msg",
                                            color = Color(0xFFFF6B6B),
                                            fontSize = 11.sp
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "点右上角 🔄 重载，或 🐞 看调试面板",
                                            color = TextSecondary,
                                            fontSize = 10.sp
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
    }

    if (showDebug) {
        DebugPanel(
            text = debugText,
            console = session.consoleText(),
            error = error ?: session.lastError,
            sandboxPath = session.sandboxPathOrNull(),
            bridgeCheck = session.bridgeCheckResult,
            onClearConsole = { session.clearConsole() },
            onDismiss = { showDebug = false }
        )
    }
}

/** 加载失败时的错误面板。 */
@Composable
private fun ErrorPane(error: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("插件界面加载失败", color = TextPrimary, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            error,
            color = Color(0xFFFF6B6B),
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) { Text("重试", color = Accent) }
    }
}

/**
 * 调试面板：把当前节点树以 JSON 展示。
 *
 * 为什么值得做：插件界面出问题时，作者最需要的是"宿主到底收到了什么"。
 * 有这棵树就能一眼看出是"节点没生成"还是"组件不支持"还是"属性名写错"，
 * 不用靠猜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugPanel(
    text: String,
    console: String,
    error: String?,
    sandboxPath: String?,
    bridgeCheck: String?,
    onClearConsole: () -> Unit,
    onDismiss: () -> Unit
) {
    // 0 = 节点树，1 = 插件日志
    var tab by remember { mutableIntStateOf(0) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        ),
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                "界面调试",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))

            error?.let {
                Text("错误：$it", color = Color(0xFFFF6B6B), fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
            }
            bridgeCheck?.let {
                val bad = it.contains("\"ok\":false")
                Text(
                    if (bad) "桥函数自检：$it" else "桥函数自检：正常",
                    color = if (bad) Color(0xFFFF6B6B) else TextSecondary,
                    fontSize = 10.sp
                )
            }
            Text(
                "提示：不支持组件会显示替代建议；网络图需 http 权限。",
                color = TextSecondary,
                fontSize = 10.sp
            )
            Text(
                "沙箱目录：${sandboxPath ?: "（未知）"}",
                color = TextSecondary,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { tab = 0 }) {
                    Text(
                        "节点树",
                        fontSize = 12.sp,
                        color = if (tab == 0) Accent else TextSecondary,
                        fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal
                    )
                }
                TextButton(onClick = { tab = 1 }) {
                    val count = console.lines().count { it.isNotBlank() }
                    Text(
                        if (count > 0) "日志 ($count)" else "日志",
                        fontSize = 12.sp,
                        color = if (tab == 1) Accent else TextSecondary,
                        fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Spacer(Modifier.weight(1f))
                if (tab == 1) {
                    TextButton(onClick = onClearConsole) {
                        Text("清空", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .background(Border.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (tab == 0) text.ifBlank { "（空）" }
                    else console.ifBlank { "（插件还没输出日志）\n\n在插件脚本里写 console.log('...') 就能看到。" },
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * 「启动选择器 → 等结果」的桥。
 *
 * launcher 的回调在重组时注册一次，而挂起函数每次调用都要拿一个新的等待位，
 * 所以需要一个可变持有者：调用方 [newPromise] 拿 Deferred，回调通过 [resolve] 完成它。
 */
private class UriPromise {
    @Volatile
    private var deferred: CompletableDeferred<Uri?>? = null

    fun newPromise(): CompletableDeferred<Uri?> {
        val d = CompletableDeferred<Uri?>()
        deferred = d
        return d
    }

    fun resolve(uri: Uri?) {
        deferred?.complete(uri)
        deferred = null
    }
}

/**
 * 树里是否有「必须拿到视口高度」的子树（目前只有 WebView）。
 *
 * WebView 没有内在高度，高度完全由外部约束决定；一旦被放进 `verticalScroll` 这类
 * 高度约束无限的容器，它会塌成 0 高，页面里的 `100dvh` 也跟着变成 0 ——
 * 整页只剩 `position: fixed` 的元素可见。所以判断"要不要占满"不能只看根节点的
 * `fillMaxSize`，还要看内容里有没有这类子树。
 */
private fun treeContainsViewportChild(node: UiNode): Boolean {
    if (node.type.equals("WebView", ignoreCase = true)) return true
    return node.children.any { treeContainsViewportChild(it) }
}
