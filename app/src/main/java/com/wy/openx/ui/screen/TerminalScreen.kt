package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import com.wy.openx.container.ContainerInitState
import com.wy.openx.tools.AppContextHolder
import com.wy.openx.tools.RunState
import com.wy.openx.tools.TerminalEngine
import com.wy.openx.tools.TerminalSettingsStore
import com.wy.openx.tools.TerminalTab
import com.wy.openx.tools.TerminalThemePreset
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect

private val TermuxBg = Color(0xFF0D0D0D)
private val TermuxText = Color(0xFFD0D0D0)
private val TermuxGreen = Color(0xFF00FF00)
private val TermuxBar = Color(0xFF111111)
private val KeyBg = Color(0xFF2A2A2A)
private val KeyBorder = Color(0xFF3A3A3A)
private val KeyActive = Color(0xFF004D40)

data class ExtraKey(
    val label: String,
    val active: Boolean = false,
    val width: Int = 1,
    val onClick: () -> Unit
)

@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    // 兜底必须回到全局单例。原先是 `?: remember { TerminalEngine() }` —— 那样造出的引擎
    // 既不是 sharedInstance、也不是 VM 持有的那个、还不会写进 AppContextHolder，
    // 谁都不知道它存在：切容器时 VM 停掉的是另一个引擎，会话进程活着却没人渲染 → 纯黑屏。
    // 正式入口由 MainActivity 显式传 vm 的引擎，这里只是避免可空。
    engine: TerminalEngine = remember { AppContextHolder.terminalEngine ?: TerminalEngine.getOrCreate() }
) {
    val isRunning by engine.isRunning.collectAsState()
    val tabs by engine.tabs.collectAsState()
    val activeTabId by engine.activeTabId.collectAsState()
    var ctrlHeld by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settingsStore = remember { TerminalSettingsStore(context) }
    val containerEngine = AppContextHolder.containerEngine
    val initStateHolder = remember { mutableStateOf<ContainerInitState?>(null) }
    LaunchedEffect(containerEngine) {
        containerEngine?.initProgress?.collect { initStateHolder.value = it }
    }
    val initState by initStateHolder
    val isPreparing by engine.isPreparing.collectAsState()
    val initError by engine.initError.collectAsState()
    // 会话进程已退出时同样要给出提示：此时 session 仍非 null（标签还在），
    // 所以「session == null 才显示面板」那个条件不够 —— proot 一退出，终端就只剩
    // 一块没有任何 shell 输出的空屏，退出码才是唯一的线索。
    val activeExitCode = (tabs.firstOrNull { it.id == activeTabId }?.runState as? RunState.Finished)?.exitCode

    LaunchedEffect(Unit) { engine.initialize() }

    // 离开终端页时释放引擎对 View 的引用，避免静态单例持有 Activity/View 泄漏
    DisposableEffect(Unit) {
        onDispose { engine.releaseViewRefs() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TermuxBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermuxBar)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TermuxText)
                }
                Text("终端", color = TermuxText, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    statusLabel(isRunning, initState),
                    color = if (isRunning) TermuxGreen else TermuxText.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                // 这里原来用 Icons.Default.Close（一个 X）表示「清屏」，与下方标签栏
                // 关闭标签的小 × 长得几乎一样、又上下相邻——用户点上面这个以为是在
                // 关标签，结果只是清屏，于是以为「关闭按钮没用」。换成明确表示"擦除"的图标。
                IconButton(onClick = { engine.clear() }) {
                    Icon(Icons.Default.CleaningServices, "清屏", tint = TermuxText)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, "终端设置", tint = TermuxText)
                }
                IconButton(onClick = { engine.restart() }) {
                    Icon(Icons.Default.Refresh, "重启", tint = TermuxText)
                }
            }

            // 多会话标签栏：切换/关闭/新建
            if (tabs.isNotEmpty()) {
                TabBar(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    onSelect = { engine.activateTab(it) },
                    onClose = { engine.closeTab(it) },
                    onNew = { engine.requestNewTab() }
                )
            }

            // 没有会话时用状态面板盖住终端区域。
            // 原来这里是裸 AndroidView：session 为 null 时 update 直接跳过 attachSession，
            // TerminalView.onDraw 见 mEmulator==null 就 canvas.drawColor(0xFF000000) ——
            // 整个区域一片纯黑、零文字，用户只能以为「卡死了」。
            // 切容器后进终端正是这条路径（见 TerminalEngine.initGeneration 的说明）。
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TerminalSurface(engine = engine, fontSize = settingsStore.fontSize)
                val hasSession = engine.sessionFlow.collectAsState().value != null
                if (!hasSession || activeExitCode != null) {
                    TerminalBlankState(
                        preparing = isPreparing,
                        error = initError
                            ?: activeExitCode?.let { "会话已退出（退出码 $it）" },
                        onRetry = { engine.restart() }
                    )
                }
            }

            ExtraKeysRow(
                keys = listOf(
                    ExtraKey("ESC") { engine.sendEscape() },
                    ExtraKey("/") { engine.writeInput("/") },
                    ExtraKey("-") { engine.writeInput("-") },
                    ExtraKey("HOME") { engine.writeInput("\u001b[H") },
                    ExtraKey("\u2191") { engine.sendArrow(TerminalEngine.ArrowDirection.UP) },
                    ExtraKey("END") { engine.writeInput("\u001b[F") },
                    ExtraKey("PGUP") { engine.writeInput("\u001b[5~") },
                )
            )
            ExtraKeysRow(
                keys = listOf(
                    ExtraKey("TAB") { engine.sendTab() },
                    ExtraKey("CTRL", active = ctrlHeld, onClick = { ctrlHeld = !ctrlHeld; engine.setCtrlHeld(ctrlHeld) }),
                    ExtraKey("\u21E7") { },
                    ExtraKey("\u2190") { engine.sendArrow(TerminalEngine.ArrowDirection.LEFT) },
                    ExtraKey("\u2193") { engine.sendArrow(TerminalEngine.ArrowDirection.DOWN) },
                    ExtraKey("\u2192") { engine.sendArrow(TerminalEngine.ArrowDirection.RIGHT) },
                    ExtraKey("PGDN") { engine.writeInput("\u001b[6~") },
                )
            )

            Spacer(modifier = Modifier.height(2.dp))
        }

        if (showSettings) {
            TerminalSettingsSheet(
                settingsStore = settingsStore,
                engine = engine,
                onDismiss = { showSettings = false }
            )
        }
    }
}

/** 顶栏状态文案：运行中 → 初始化进度 → 已停止（初始化失败等） */
/**
 * 终端渲染面。
 *
 * 单独抽出来是为了让 [AndroidView] 在**自己的重组作用域**里读 `sessionFlow`。
 * 之前它内联在 TerminalScreen 里、在 `update` 中调 `engine.getSession()`——那是普通字段，
 * 组合期读它建立不了订阅，于是「会话创建了」不一定能让 update 重跑，视图就一直没 attachSession。
 * 现场症状：第一次进终端纯黑（会话其实活着），返回再进就正常——因为那时是新组合，update 必跑。
 */
@Composable
private fun TerminalSurface(engine: TerminalEngine, fontSize: Int) {
    val session by engine.sessionFlow.collectAsState()
    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setTextSize(fontSize)
                engine.viewClient?.terminalView = this
                setTerminalViewClient(engine.viewClient)
                engine.sessionClient?.terminalView = this
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            engine.viewClient?.terminalView = view
            val s = session
            if (s != null && view.getCurrentSession() != s) {
                view.attachSession(s)
                // attachSession 内部 updateSize() 在视图尚未 layout 时会因
                // getWidth()/getHeight()==0 直接早退，mEmulator 留 null → onDraw 画纯黑。
                // 补一次强制刷新 + post 一轮，等布局尺寸就位后补设 mEmulator。
                view.onScreenUpdated()
                view.post { view.updateSize(); view.onScreenUpdated() }
            }
        }
    )
}

/**
 * 无会话时的占位面板。
 *
 * 目的只有一个：把「黑屏 + 无提示」变成「看得见的状态 + 一键重试」。
 *  做法：完整的 PrepareState
 * 门控与空态/重试
 * 
 */
@Composable
private fun TerminalBlankState(
    preparing: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TermuxBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            if (preparing) {
                CircularProgressIndicator(color = TermuxGreen, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Text("正在准备终端会话…", color = TermuxText, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "首次进入或切换容器需要初始化，稍候即可",
                    color = TermuxText.copy(alpha = 0.55f), fontSize = 11.sp
                )
            } else {
                Text(
                    error ?: "没有可用的终端会话",
                    color = if (error != null) Color(0xFFFF6B6B) else TermuxText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(KeyBg)
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Refresh, null, tint = TermuxGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重试", color = TermuxGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun statusLabel(isRunning: Boolean, initState: ContainerInitState?): String = when {
    isRunning -> "运行中"
    initState is ContainerInitState.ExtractingRootfs -> "初始化: 解压 rootfs…"
    initState is ContainerInitState.DeployingProot -> "初始化: 部署 proot…"
    initState is ContainerInitState.InstallingPackages -> "初始化: 安装基础包…"
    initState is ContainerInitState.Failed -> "初始化失败"
    else -> "已停止"
}

@Composable
private fun ExtraKeysRow(keys: List<ExtraKey>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermuxBar)
            .padding(horizontal = 3.dp, vertical = 1.5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for (key in keys) {
            Box(
                modifier = Modifier
                    .weight(key.width.toFloat())
                    .padding(horizontal = 1.5.dp)
                    .height(30.dp)
                    .border(0.5.dp, if (key.active) TermuxGreen else KeyBorder, RoundedCornerShape(4.dp))
                    .background(if (key.active) KeyActive else KeyBg, RoundedCornerShape(4.dp))
                    .clickable(onClick = key.onClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    key.label,
                    color = if (key.active) TermuxGreen else TermuxText.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 可横滑的标签栏：每个标签显示状态点 + 标题 + 关闭；末尾「+」新建。 */
@Composable
private fun TabBar(
    tabs: List<TerminalTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermuxBar)
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标签名按**列表位置**编号，而不是用会话 id：
        // id 是全局自增的，切一次容器就变 "term-2"，等于把内部计数器漏给用户。
        // 位置编号从 1 开始，语义直白且不受切容器/关标签影响。
        tabs.forEachIndexed { index, tab ->
            TabChip(
                tab = tab,
                label = tab.title.ifBlank { "终端 ${index + 1}" },
                selected = tab.id == activeTabId,
                onClick = { onSelect(tab.id) },
                onClose = { onClose(tab.id) }
            )
        }
        // 新建标签
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(26.dp)
                .border(1.dp, KeyBorder, RoundedCornerShape(6.dp))
                .clickable(onClick = onNew),
            contentAlignment = Alignment.Center
        ) {
            Text("+", color = TermuxGreen, fontSize = 16.sp)
        }
    }
}

@Composable
private fun TabChip(
    tab: TerminalTab,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val running = tab.runState is RunState.Running
    Row(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .height(26.dp)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) TermuxGreen else KeyBorder,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (selected) TermuxGreen.copy(alpha = 0.15f) else KeyBg, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 运行状态指示点
        Box(
            modifier = Modifier
                .size(if (running) 8.dp else 6.dp)
                .background(if (running) TermuxGreen else TermuxText.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
        )
        Text(
            text = label,
            color = if (selected) TermuxGreen else TermuxText.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(start = 5.dp, end = 5.dp)
        )
        // 关闭按钮
        // 点击区从 18dp 提到 22dp：原先远低于 48dp 的无障碍下限，嵌在 26dp 高的标签里
        // 很容易点空/点歪。视觉大小不变，只扩大可点范围。
        Box(
            modifier = Modifier
                .size(22.dp)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Text("\u00D7", color = TermuxText.copy(alpha = 0.85f), fontSize = 13.sp)
        }
    }
}

/** 终端设置面板：字号滑条 / 配色主题 / 光标样式。改动即时生效并持久化，下次进入终端页保留。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalSettingsSheet(
    settingsStore: TerminalSettingsStore,
    engine: TerminalEngine,
    onDismiss: () -> Unit
) {
    var fontSize by remember { mutableStateOf(settingsStore.fontSize) }
    var themeId by remember { mutableStateOf(settingsStore.themeId) }
    var cursorStyle by remember { mutableStateOf(settingsStore.cursorStyle) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TermuxBar
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("终端设置", color = TermuxText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            // 字号
            Text("字号  $fontSize px", color = TermuxText, fontSize = 12.sp)
            Slider(
                value = fontSize.toFloat(),
                onValueChange = { v ->
                    fontSize = v.roundToInt().coerceIn(TerminalSettingsStore.MIN_FONT_SIZE, TerminalSettingsStore.MAX_FONT_SIZE)
                    engine.setFontSize(fontSize)
                },
                valueRange = TerminalSettingsStore.MIN_FONT_SIZE.toFloat()..TerminalSettingsStore.MAX_FONT_SIZE.toFloat(),
                steps = TerminalSettingsStore.MAX_FONT_SIZE - TerminalSettingsStore.MIN_FONT_SIZE - 1
            )
            Spacer(Modifier.height(14.dp))

            // 配色主题
            Text("主题", color = TermuxText, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            TerminalThemePreset.ALL_PRESETS.forEach { preset ->
                val selected = preset.id == themeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) TermuxGreen.copy(alpha = 0.12f) else KeyBg, RoundedCornerShape(8.dp))
                        .clickable {
                            themeId = preset.id
                            engine.applyTheme(preset)
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color(preset.background), RoundedCornerShape(5.dp))
                            .border(1.dp, Color(preset.cursor), RoundedCornerShape(5.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        preset.name,
                        color = if (selected) TermuxGreen else TermuxText.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // 光标样式
            Text("光标样式", color = TermuxText, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(
                    Triple(TerminalSettingsStore.CURSOR_BLOCK, "块", "█"),
                    Triple(TerminalSettingsStore.CURSOR_UNDERLINE, "下划线", "▁"),
                    Triple(TerminalSettingsStore.CURSOR_BAR, "竖线", "▏")
                )
                options.forEach { (style, label, glyph) ->
                    val selected = style == cursorStyle
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) TermuxGreen.copy(alpha = 0.15f) else KeyBg, RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (selected) TermuxGreen else KeyBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                cursorStyle = style
                                engine.applyCursorStyle(style)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(glyph, color = if (selected) TermuxGreen else TermuxText, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            label,
                            color = if (selected) TermuxGreen else TermuxText.copy(alpha = 0.9f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
