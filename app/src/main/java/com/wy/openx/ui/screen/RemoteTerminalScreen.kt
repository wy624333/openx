package com.wy.openx.ui.screen

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.wy.openx.remote.RemoteConnection
import com.wy.openx.remote.RemoteKeyStore
import com.wy.openx.remote.RemoteSshClient
import com.wy.openx.remote.SshShellBackend
import com.wy.openx.tools.AppContextHolder
import com.wy.openx.tools.TerminalSettingsStore
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 远程终端页：用 SSH 交互式 shell 驱动同一套 Termux 终端仿真器。
 *
 * ## 为什么独立于本地终端页
 * [com.wy.openx.tools.TerminalEngine] 的会话创建整条链路都绑在本地容器上
 *（ensureInstalled、proot argv、容器切换守卫）。远端没有这些东西，
 * 硬塞进去会让本地那条已经稳定的路径跟着变复杂，而收益只是省一个页面。
 * 这里只复用真正通用的部分：`TerminalSession` + `TerminalView` + 仿真器。
 *
 * ## 会话生命周期
 * 离开页面即关闭 shell：远端 shell 是长驻通道，留在后台只会占着服务器的会话名额。
 * 需要常驻的远程任务应该走 `remote_ssh` 工具，那是 exec 通道，跑完即释放。
 */
@Composable
internal fun RemoteTerminalScreen(
    config: RemoteConnection,
    onBack: () -> Unit,
) {
    val context = AppContextHolder.context

    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var status by remember { mutableStateOf<String?>("正在连接 ${config.name}…") }
    var viewRef by remember { mutableStateOf<TerminalView?>(null) }
    // 每次重连自增，触发 LaunchedEffect 重新建会话
    var attempt by remember { mutableStateOf(0) }

    // 远端客户端与 shell 归本页所有，离开时统一收尾
    val holder = remember { RemoteTerminalHolder() }
    val settings = remember(context) { context?.let { TerminalSettingsStore(it) } }
    var showSettings by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { holder.close() }
    }

    LaunchedEffect(config.id, attempt) {
        val ctx = context ?: run {
            status = "应用上下文不可用"
            return@LaunchedEffect
        }
        status = "正在连接 ${config.name}…"
        runCatching {
            withContext(Dispatchers.IO) {
                holder.open(ctx, config, columns = 80, rows = 24)
            }
        }.onSuccess { backend ->
            // TerminalSession 内部会 new Handler()，必须在有 Looper 的线程构造
            val created = TerminalSession(TRANSCRIPT_ROWS, RemoteSessionClient { viewRef }, backend)
            created.updateSize(80, 24, 560, 336)
            applyRemoteColors(
                created,
                settings?.remoteForeground ?: TerminalSettingsStore.DEFAULT_REMOTE_FOREGROUND,
                settings?.remoteBackground ?: TerminalSettingsStore.DEFAULT_REMOTE_BACKGROUND,
            )
            session = created
            status = null
        }.onFailure { error ->
            status = error.message ?: "连接失败"
        }
    }

    // 返回键先退本页，不要一路退到设置首页
    BackHandler { onBack() }

    Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            RemoteTerminalTopBar(
                title = config.name,
                subtitle = "SSH · ${config.username}@${config.host}",
                onBack = onBack,
                onSettings = { showSettings = true },
                onReconnect = {
                    holder.close()
                    session = null
                    attempt++
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(settings?.remoteBackground ?: TerminalSettingsStore.DEFAULT_REMOTE_BACKGROUND))
            ) {
                val current = session
                if (current != null) {
                    AndroidView(
                        factory = { ctx2 ->
                            TerminalView(ctx2, null).apply {
                                isFocusable = true
                                isFocusableInTouchMode = true
                                setTextSize(settings?.remoteFontSize ?: TerminalSettingsStore.DEFAULT_REMOTE_FONT_SIZE)
                                setTerminalViewClient(RemoteViewClient { viewRef })
                                viewRef = this
                                requestFocus()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            viewRef = view
                            if (view.getCurrentSession() != current) {
                                view.attachSession(current)
                                // attachSession 内的 updateSize 在视图尚未 layout 时会早退，
                                // mEmulator 留 null → onDraw 画纯黑。补一次 + post 一轮等布局就位。
                                view.onScreenUpdated()
                                view.post { view.requestFocus(); view.updateSize(); view.onScreenUpdated() }
                            }
                        }
                    )
                } else {
                    RemoteTerminalStatus(
                        status = status ?: "未连接",
                        onRetry = {
                            holder.close()
                            attempt++
                        },
                    )
                }
            }
        }
    }

    if (showSettings) {
        RemoteTerminalSettingsDialog(
            settings = settings,
            onFontSizeChange = { size -> viewRef?.setTextSize(size); viewRef?.onScreenUpdated() },
            onColorsChange = { foreground, background ->
                applyRemoteColors(session, foreground, background)
                viewRef?.onScreenUpdated()
            },
            onDismiss = { showSettings = false },
        )
    }
}

/** 承载远端连接与 shell，保证只被关闭一次。 */
private class RemoteTerminalHolder {
    private var client: RemoteSshClient? = null
    private var backend: SshShellBackend? = null

    suspend fun open(ctx: android.content.Context, config: RemoteConnection, columns: Int, rows: Int): SshShellBackend {
        val c = RemoteSshClient(ctx, RemoteKeyStore(ctx))
        c.connect(config)
        val shell = c.openShell(columns, rows)
        val b = SshShellBackend(shell)
        client = c
        backend = b
        return b
    }

    fun close() {
        runCatching { backend?.close() }
        backend = null
        // disconnect 走网络，不能阻塞 UI 线程
        val c = client
        client = null
        if (c != null) {
            Thread({ runCatching { c.disconnectBlocking() } }, "RemoteTermClose").start()
        }
    }
}

@Composable
private fun RemoteTerminalTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onReconnect: () -> Unit,
) {
    Column(modifier = Modifier.background(BgPrimary)) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(subtitle, fontSize = 11.sp, color = TextSecondary)
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Settings, contentDescription = "终端设置", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onReconnect, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重连", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RemoteTerminalStatus(status: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(status, fontSize = 13.sp, color = TextSecondary)
        androidx.compose.material3.TextButton(onClick = onRetry) {
            Text("重试", fontSize = 13.sp, color = Accent)
        }
    }
}

private fun applyRemoteColors(session: TerminalSession?, foreground: Int, background: Int) {
    val colors = session?.getEmulator()?.mColors?.mCurrentColors ?: return
    colors[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
    colors[TextStyle.COLOR_INDEX_BACKGROUND] = background
    colors[TextStyle.COLOR_INDEX_CURSOR] = foreground
}

@Composable
private fun RemoteTerminalSettingsDialog(
    settings: TerminalSettingsStore?,
    onFontSizeChange: (Int) -> Unit,
    onColorsChange: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var fontSize by remember {
        mutableStateOf(settings?.remoteFontSize ?: TerminalSettingsStore.DEFAULT_REMOTE_FONT_SIZE)
    }
    var foreground by remember {
        mutableStateOf(settings?.remoteForeground ?: TerminalSettingsStore.DEFAULT_REMOTE_FOREGROUND)
    }
    var background by remember {
        mutableStateOf(settings?.remoteBackground ?: TerminalSettingsStore.DEFAULT_REMOTE_BACKGROUND)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("终端设置", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("字号：$fontSize px", color = TextPrimary, fontSize = 13.sp)
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { value ->
                        fontSize = value.roundToInt().coerceIn(
                            TerminalSettingsStore.MIN_REMOTE_FONT_SIZE,
                            TerminalSettingsStore.MAX_REMOTE_FONT_SIZE
                        )
                        settings?.remoteFontSize = fontSize
                        onFontSizeChange(fontSize)
                    },
                    valueRange = TerminalSettingsStore.MIN_REMOTE_FONT_SIZE.toFloat()..TerminalSettingsStore.MAX_REMOTE_FONT_SIZE.toFloat(),
                    steps = TerminalSettingsStore.MAX_REMOTE_FONT_SIZE - TerminalSettingsStore.MIN_REMOTE_FONT_SIZE - 1,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                Text("字体颜色", color = TextPrimary, fontSize = 13.sp)
                Spacer(Modifier.size(4.dp))
                RemoteColorOption(
                    label = "浅色字 / 深色底",
                    sampleForeground = TerminalSettingsStore.DEFAULT_REMOTE_FOREGROUND,
                    sampleBackground = TerminalSettingsStore.DEFAULT_REMOTE_BACKGROUND,
                    selected = foreground == TerminalSettingsStore.DEFAULT_REMOTE_FOREGROUND &&
                        background == TerminalSettingsStore.DEFAULT_REMOTE_BACKGROUND,
                ) {
                    foreground = TerminalSettingsStore.DEFAULT_REMOTE_FOREGROUND
                    background = TerminalSettingsStore.DEFAULT_REMOTE_BACKGROUND
                    settings?.remoteForeground = foreground
                    settings?.remoteBackground = background
                    onColorsChange(foreground, background)
                }
                RemoteColorOption(
                    label = "黑字 / 白底",
                    sampleForeground = TerminalSettingsStore.REMOTE_FOREGROUND_BLACK,
                    sampleBackground = TerminalSettingsStore.REMOTE_BACKGROUND_WHITE,
                    selected = foreground == TerminalSettingsStore.REMOTE_FOREGROUND_BLACK &&
                        background == TerminalSettingsStore.REMOTE_BACKGROUND_WHITE,
                ) {
                    foreground = TerminalSettingsStore.REMOTE_FOREGROUND_BLACK
                    background = TerminalSettingsStore.REMOTE_BACKGROUND_WHITE
                    settings?.remoteForeground = foreground
                    settings?.remoteBackground = background
                    onColorsChange(foreground, background)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成", color = Accent) } },
        containerColor = Panel
    )
}

@Composable
private fun RemoteColorOption(
    label: String,
    sampleForeground: Int,
    sampleBackground: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF214A38) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "A",
            color = Color(sampleForeground),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color(sampleBackground), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextPrimary, fontSize = 13.sp)
    }
}

private const val TRANSCRIPT_ROWS = 2000

/**
 * 会话回调：只把「屏幕变了」转给 TerminalView。
 *
 * 本地那份适配器绑在 TerminalEngine 上（要读光标样式、主题、通知页面），
 * 这里没有那些状态，保持最小即可。
 */
private class RemoteSessionClient(
    private val viewProvider: () -> TerminalView?,
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        viewProvider()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        viewProvider()?.onScreenUpdated()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val ctx = AppContextHolder.context ?: return
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val ctx = AppContextHolder.context ?: return
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    /** 光标样式：块状（0）。远程页不提供光标样式设置，与本地默认保持一致。 */
    override fun getTerminalCursorStyle(): Int = 0

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }
}

/** 视图回调：远端页没有修饰键状态，全部按「未按下」处理。 */
private class RemoteViewClient(
    private val viewProvider: () -> TerminalView?,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        val view = viewProvider() ?: return 1.0f
        val next = (24 * scale).toInt().coerceIn(8, 36)
        view.setTextSize(next)
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val view = viewProvider() ?: return
        view.requestFocus()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }
}
