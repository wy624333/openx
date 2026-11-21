package com.wy.openx.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wy.openx.tools.BrowserSettingsStore
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.ButtonBg
import com.wy.openx.ui.theme.ButtonFg
import com.wy.openx.ui.theme.Danger
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 内置浏览器界面（多标签地址栏 + 底部操作栏）。
 *
 * 
 * - 去 Hilt/stringResource，改 openx 语义色 token（Panel/Accent/Border…）与硬编码中文；
 * - 图标用 material-icons-extended（原 FeatherIcons）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    browserManager: BrowserManager,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false
) {
    val state by browserManager.state.collectAsState()
    val activeTab = state.activeTab
    val scope = rememberCoroutineScope()

    var showTabsSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 「更多设置」抽屉（浏览器设置与工具）：夜间模式 / 开发者工具 / 浏览器标识。
    var showMenuSheet by rememberSaveable { mutableStateOf(false) }
    var showUaSheet by rememberSaveable { mutableStateOf(false) }
    var showFavoritesSheet by rememberSaveable { mutableStateOf(false) }
    var nightMode by remember { mutableStateOf(browserManager.nightMode) }
    var devTools by remember { mutableStateOf(browserManager.devTools) }
    var uaMode by remember { mutableStateOf(browserManager.uaMode) }
    var customUaInput by rememberSaveable { mutableStateOf(browserManager.customUa) }
    // 自定义 UA 输入弹窗：与 UA 抽屉分开，点抽屉里的「自定义」时先收抽屉再开它（两个窗口叠着键盘避让会打架）
    var showCustomUaDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = BgPrimary,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            BrowserTopBar(
                title = activeTab?.title.orEmpty(),
                url = activeTab?.url.orEmpty(),
                loading = activeTab?.loading == true,
                embedded = embedded,
                isFavorited = state.isFavorite(),
                onNavigateBack = onNavigateBack,
                onReload = { scope.launch { browserManager.reload() } },
                onNavigate = { url -> scope.launch { browserManager.navigate(url) } },
                onToggleFavorite = {
                    browserManager.toggleFavorite(
                        activeTab?.url.orEmpty(),
                        activeTab?.title.orEmpty()
                    )
                }
            )
        },
        bottomBar = {
            BrowserBottomBar(
                canGoBack = activeTab?.canGoBack == true,
                canGoForward = activeTab?.canGoForward == true,
                tabsCount = state.tabs.size,
                embedded = embedded,
                onGoBack = { scope.launch { browserManager.goBack() } },
                onGoForward = { scope.launch { browserManager.goForward() } },
                onNewTab = { scope.launch { browserManager.newTab() } },
                onOpenTabs = { showTabsSheet = true },
                onOpenMenu = {
                    nightMode = browserManager.nightMode
                    devTools = browserManager.devTools
                    uaMode = browserManager.uaMode
                    customUaInput = browserManager.customUa
                    showMenuSheet = true
                }
            )
        }
    ) { padding ->
        // 键盘弹出时 WebView 容器跟着变矮（等价于 adjustResize），网页自己会把聚焦的
        // 输入框滚到可视区；底部工具栏保持原位、被键盘盖住。
        //
        // 底部留白取「底栏高」与「键盘高」的较大者，**不能相加** —— Scaffold 的 padding
        // 已经含底栏高度，再叠 .imePadding() 会让内容底边高出键盘一整条底栏，
        // 表现为网页输入框与键盘之间空出一大条（实测 56dp 底栏 + 导航条 ≈ 87dp）。
        val density = LocalDensity.current
        val bottomBarPx = with(density) { padding.calculateBottomPadding().roundToPx() }
        val imePx = WindowInsets.ime.getBottom(density)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = with(density) { (imePx - bottomBarPx).coerceAtLeast(0).toDp() })
        ) {
            AndroidView(
                factory = { context -> browserManager.attachContainerView(context) },
                modifier = Modifier.fillMaxSize(),
                // 用 onRelease 而不是 DisposableEffect：带容器的身份，
                // 释放时只会回收自己那一个（见 detachViewHierarchy）
                onRelease = { container -> browserManager.detachViewHierarchy(container) }
            )

            // 错误提示
            if (activeTab?.error != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Panel,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = activeTab.error.orEmpty(),
                            color = Danger,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        IconButton(onClick = { scope.launch { browserManager.reload() } }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Danger)
                        }
                    }
                }
            }

            // 空白标签页状态：引导输入网址
            if (activeTab != null && activeTab.url.isEmpty() && !activeTab.loading && activeTab.error == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = Accent.copy(alpha = 0.45f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "点击上方地址栏输入网址",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }

    // 多标签页管理抽屉
    if (showTabsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTabsSheet = false },
            sheetState = sheetState,
            containerColor = Panel
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "标签页（${state.tabs.size}）",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                browserManager.newTab()
                                showTabsSheet = false
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新建标签页", tint = Accent)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.tabs, key = { it.id }) { tab ->
                        BrowserTabItemCard(
                            tab = tab,
                            selected = tab.id == state.activeTabId,
                            onClick = {
                                scope.launch {
                                    browserManager.selectTab(tab.id)
                                    showTabsSheet = false
                                }
                            },
                            onClose = {
                                scope.launch {
                                    browserManager.closeTab(tab.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 「更多设置」抽屉：（夜间模式 / 开发者工具 / 浏览器标识）。
    if (showMenuSheet) {
        BrowserMenuSheet(
            nightMode = nightMode,
            devTools = devTools,
            uaMode = uaMode,
            favoritesCount = state.favorites.size,
            onToggleNightMode = { on ->
                nightMode = on
                scope.launch { browserManager.setNightMode(on) }
            },
            onToggleDevTools = { on ->
                devTools = on
                scope.launch { browserManager.setDevTools(on) }
            },
            onOpenUa = {
                showMenuSheet = false
                showUaSheet = true
            },
            onOpenFavorites = {
                // 先收菜单再开收藏夹：两个 sheet 叠在一起，关闭手势与键盘避让会打架
                showMenuSheet = false
                showFavoritesSheet = true
            },
            onDismiss = { showMenuSheet = false }
        )
    }

    // 收藏夹抽屉。
    if (showFavoritesSheet) {
        FavoritesSheet(
            favorites = state.favorites,
            onOpen = { fav ->
                showFavoritesSheet = false
                scope.launch { browserManager.navigate(fav.url) }
            },
            onRemove = { url -> browserManager.removeFavorite(url) },
            onDismiss = { showFavoritesSheet = false }
        )
    }

    // 浏览器标识（User-Agent）选择子抽屉。
    if (showUaSheet) {
        UaOptionSheet(
            current = uaMode,
            customUa = customUaInput,
            onCustomClick = {
                showUaSheet = false
                showCustomUaDialog = true
            },
            onSelect = { id, custom ->
                uaMode = id
                scope.launch { browserManager.setUaMode(id, custom) }
                showUaSheet = false
                showMenuSheet = true
            },
            onDismiss = {
                showUaSheet = false
                showMenuSheet = true
            }
        )
    }

    // 自定义 UA 输入弹窗（替代原先躺在抽屉里的常驻输入框）。
    if (showCustomUaDialog) {
        CustomUaDialog(
            initial = customUaInput,
            onConfirm = { text ->
                customUaInput = text
                uaMode = BrowserSettingsStore.UA_CUSTOM
                scope.launch { browserManager.setUaMode(BrowserSettingsStore.UA_CUSTOM, text) }
                showCustomUaDialog = false
            },
            onDismiss = { showCustomUaDialog = false }
        )
    }
}

/**
 * 顶部栏独立组件：将输入态和文字变化隔离在此处，避免打字导致整屏重组卡顿。
 */
@Composable
private fun BrowserTopBar(
    title: String,
    url: String,
    loading: Boolean,
    embedded: Boolean,
    isFavorited: Boolean,
    onNavigateBack: () -> Unit,
    onReload: () -> Unit,
    onNavigate: (String) -> Unit,
    onToggleFavorite: () -> Unit
) {
    var addressInput by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(url))
    }
    val focusRequester = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(url) {
        addressInput = TextFieldValue(url, selection = TextRange(0, url.length))
    }

    // 地址栏文字样式：占位符和正文必须共用同一份（含 lineHeight）。
    // 占位符若用 M3 Text 默认样式，会继承 bodyLarge 的 lineHeight(24sp)，
    // 而这里只给 fontSize 时行高按字体度量(≈17sp)，两侧行盒不同高 → 光标和
    // 「输入网址」不在一条线上，一打字（占位符消失）又“对齐”了。
    val addressTextStyle = TextStyle(color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)

    Surface(
        color = Panel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!embedded) Modifier.statusBarsPadding() else Modifier)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：返回
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        if (embedded) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (embedded) "关闭" else "返回",
                        tint = TextSecondary
                    )
                }

                // 中间：始终存在的地址输入框（不再用「标题态/编辑态」切换 + 手动 requestFocus——
                // 该机制在部分设备上个关键盘不弹、点了没反应）。点击输入框由 Compose 原生聚焦。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Panel.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        BasicTextField(
                            value = addressInput,
                            onValueChange = { addressInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            singleLine = true,
                            textStyle = addressTextStyle,
                            cursorBrush = SolidColor(Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val input = addressInput.text.trim()
                                if (input.isNotEmpty()) {
                                    focusManager.clearFocus()
                                    onNavigate(input)
                                }
                            }),
                            decorationBox = { innerTextField ->
                                if (addressInput.text.isEmpty()) {
                                    Text(
                                        text = "输入网址",
                                        style = addressTextStyle.copy(color = TextSecondary),
                                        maxLines = 1
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (addressInput.text.isNotEmpty()) {
                            IconButton(
                                onClick = { addressInput = TextFieldValue("") },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "清空",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // 右侧：收藏星标 + 刷新。星标在刷新左边，空心=未收藏、实心=已收藏。
                IconButton(
                    onClick = onToggleFavorite,
                    enabled = url.isNotBlank()
                ) {
                    Icon(
                        if (isFavorited) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFavorited) "取消收藏" else "收藏",
                        tint = if (isFavorited) Accent else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onReload,
                    enabled = !loading
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Accent
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = loading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = Accent
                )
            }
        }
    }
}

/**
 * 底部操作栏独立组件
 */
@Composable
private fun BrowserBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabsCount: Int,
    embedded: Boolean,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onNewTab: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Surface(
        color = Panel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!embedded) Modifier.navigationBarsPadding() else Modifier)
        ) {
            HorizontalDivider(thickness = 0.5.dp, color = Border.copy(alpha = 0.5f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. 后退
                IconButton(onClick = onGoBack, enabled = canGoBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "后退",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 2. 前进
                IconButton(onClick = onGoForward, enabled = canGoForward) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "前进",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 3. 新建标签页
                IconButton(onClick = onNewTab) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "新建标签页",
                        tint = Accent,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 4. 标签页管理按钮（数字方框徽标）
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .border(1.8.dp, TextSecondary, RoundedCornerShape(7.dp))
                        .clickable(onClick = onOpenTabs),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$tabsCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                }

                // 5. 更多设置（三条杠）：打开浏览器设置与工具抽屉
                IconButton(onClick = onOpenMenu) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "更多设置",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserTabItemCard(
    tab: BrowserTabState,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when {
        tab.title.isNotBlank() -> tab.title
        tab.url.isNotBlank() -> tab.url.removePrefix("https://").removePrefix("http://")
        else -> "新标签页"
    }

    val borderColor = if (selected) Accent else Border.copy(alpha = 0.5f)
    val bgColor = if (selected) Accent.copy(alpha = 0.08f) else Panel

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Public,
            contentDescription = null,
            tint = if (selected) Accent else TextSecondary,
            modifier = Modifier.size(20.dp)
        )

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Accent else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tab.url.isNotBlank()) {
                Text(
                    text = tab.url,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (tab.loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 4.dp),
                strokeWidth = 2.dp,
                color = Accent
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "关闭标签页",
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 「更多设置」抽屉（浏览器设置与工具）：——
 * 夜间模式、开发者工具（Eruda）、浏览器标识（User-Agent）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserMenuSheet(
    nightMode: Boolean,
    devTools: Boolean,
    uaMode: String,
    favoritesCount: Int,
    onToggleNightMode: (Boolean) -> Unit,
    onToggleDevTools: (Boolean) -> Unit,
    onOpenUa: () -> Unit,
    onOpenFavorites: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "浏览器设置与工具",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Spacer(Modifier.height(12.dp))
            Text(text = "页面与工具", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(4.dp))

            SettingSwitchRow(
                icon = { Icon(Icons.Default.DarkMode, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp)) },
                title = "夜间模式",
                desc = "降低页面亮度并启用深色偏好",
                checked = nightMode,
                onCheckedChange = onToggleNightMode
            )
            SettingSwitchRow(
                icon = { Icon(Icons.Default.Code, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp)) },
                title = "开发者工具",
                desc = "注入 Eruda 控制台并启用页面调试",
                checked = devTools,
                onCheckedChange = onToggleDevTools
            )

            // 收藏夹入口：与夜间模式 / 开发者工具同组，都是「页面与工具」
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenFavorites)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Star, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "收藏夹", fontSize = 14.sp, color = TextPrimary)
                    Text(
                        text = if (favoritesCount > 0) "$favoritesCount 个网址" else "还没有收藏，点地址栏右边的星标添加",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary)
            }

            Spacer(Modifier.height(12.dp))
            Text(text = "浏览器标识 (User-Agent)", fontSize = 12.sp, color = TextSecondary)
            Spacer(Modifier.height(4.dp))

            val current = UA_OPTIONS.firstOrNull { it.id == uaMode } ?: UA_OPTIONS.first()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenUa)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Language, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = current.label, fontSize = 14.sp, color = TextPrimary)
                    Text(
                        text = current.desc,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextSecondary
                )
            }
        }
    }
}

/**
 * 收藏夹抽屉：列出已收藏的网址，点条目打开、右侧删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesSheet(
    favorites: List<BrowserFavorite>,
    onOpen: (BrowserFavorite) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "收藏夹（${favorites.size}）",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))

            if (favorites.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "还没有收藏，浏览网页时点顶栏的 ☆ 即可收藏。",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(favorites, key = { it.url }) { fav ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpen(fav) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = fav.title.ifBlank { fav.url },
                                    fontSize = 14.sp,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = fav.url,
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onRemove(fav.url) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除收藏",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = Border.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

/**
 * 浏览器标识（User-Agent）选择子抽屉：6 个预设 + 自定义输入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UaOptionSheet(
    current: String,
    customUa: String,
    onCustomClick: () -> Unit,
    onSelect: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "浏览器标识 (User-Agent)",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))

            UA_OPTIONS.forEach { opt ->
                val selected = opt.id == current
                val isCustom = opt.id == BrowserSettingsStore.UA_CUSTOM
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        // 自定义项不在这里就地填：点开输入弹窗，行内只显示当前值
                        .clickable { if (isCustom) onCustomClick() else onSelect(opt.id, null) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = opt.label, fontSize = 14.sp, color = if (selected) Accent else TextPrimary)
                        Text(
                            text = if (isCustom && customUa.isNotBlank()) customUa else opt.desc,
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = "已选", tint = Accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * 自定义 User-Agent 输入弹窗。
 *
 * 独立成一个弹窗而不是常驻在 UA 抽屉里：抽屉那个输入框占一屏地方，只有真要用自定义时才需要出现。
 * 默认 AlertDialog 自带键盘避让（窗口会 resize）。
 */
@Composable
private fun CustomUaDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义 User-Agent") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("User-Agent 字符串") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = ButtonFg)
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/** 带图标的开关行。 */
@Composable
private fun SettingSwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, color = TextPrimary)
            Text(text = desc, fontSize = 12.sp, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private data class UaOption(val id: String, val label: String, val desc: String)

private val UA_OPTIONS = listOf(
    UaOption(BrowserSettingsStore.UA_DEFAULT, "系统默认", "当前设备原生 WebView 标识"),
    UaOption(BrowserSettingsStore.UA_ANDROID_PHONE, "安卓手机", "Chrome Mobile (Android)"),
    UaOption(BrowserSettingsStore.UA_ANDROID_TABLET, "安卓平板", "Chrome Tablet (Android)"),
    UaOption(BrowserSettingsStore.UA_DESKTOP_PC, "桌面电脑", "Chrome Desktop (Windows PC)"),
    UaOption(BrowserSettingsStore.UA_IPHONE, "苹果 iPhone", "Mobile Safari (iOS)"),
    UaOption(BrowserSettingsStore.UA_IPAD, "苹果 iPad", "Tablet Safari (iPadOS)"),
    UaOption(BrowserSettingsStore.UA_CUSTOM, "自定义", "手动指定 User-Agent 字符串")
)
