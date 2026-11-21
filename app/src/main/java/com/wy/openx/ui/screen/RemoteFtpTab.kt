package com.wy.openx.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.remote.FtpServerConfig
import com.wy.openx.remote.RemoteEngines
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 「内置 FTP」tab：把手机上的目录挂出去，让电脑用 FTP 客户端访问。
 *
 * 布局分两块：
 * 1. **服务开关卡**：只有「内置 FTP / 服务未开启」与一个开关——启停是唯一的高频动作。
 * 2. **服务参数配置**：端口、账号、密码、匿名、自启 + 「保存配置」。
 *
 * 参数是**显式保存**（不是改完即时生效）：这些值一改就会影响已连上的客户端，
 * 让用户确认一次再落盘，比边打字边重启服务好。
 */
@Composable
internal fun RemoteFtpTab(onFeedback: (String) -> Unit) {
    val server = RemoteEngines.ftp
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(FtpServerConfig()) }
    val running by server.running.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var localIp by remember { mutableStateOf<String?>(null) }
    // 配置是否有未保存的改动：底部按钮据此变色，避免用户以为已经生效
    var dirty by remember { mutableStateOf(false) }

    // 键盘弹出后视口会变矮，而 TextField 只在光标矩形变化时才重算滚动位置（
    // TextFieldScrollerPosition.update 的早返回），所以要主动把聚焦中的输入框请进视野。
    val imeFocus = remember { ImeFocusTracker() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeFocus.requester?.bringIntoView()
    }

    fun applyConfig(updated: FtpServerConfig) {
        config = updated
        dirty = true
    }

    LaunchedEffect(Unit) {
        server.load()
        config = server.config.value
        localIp = server.localIp()
        if (config.autoStart && !running) {
            runCatching { server.start(config) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // ===== 服务开关卡 =====
        Surface(shape = RoundedCornerShape(12.dp), color = Panel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Share, contentDescription = null,
                    tint = if (running) Accent else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("内置 FTP", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (running) "服务运行中 · 端口 ${config.port}" else "服务未开启",
                        fontSize = 11.sp,
                        color = if (running) Accent else TextSecondary
                    )
                }
                Switch(
                    checked = running,
                    enabled = !busy,
                    onCheckedChange = { turnOn ->
                        if (busy) return@Switch
                        busy = true
                        scope.launch {
                            if (turnOn) {
                                runCatching { server.start(config) }
                                    .onSuccess {
                                        localIp = server.localIp()
                                        onFeedback("已启动，端口 ${config.port}")
                                    }
                                    .onFailure { onFeedback(it.message ?: "启动失败") }
                            } else {
                                runCatching { server.stop() }
                                    .onSuccess { onFeedback("已停止") }
                                    .onFailure { onFeedback(it.message ?: "停止失败") }
                            }
                            busy = false
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = Accent,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = Border,
                    )
                )
            }
        }

        // 运行中给出可直接抄写的连接信息
        if (running) {
            Surface(shape = RoundedCornerShape(12.dp), color = Accent.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("在电脑的 FTP 客户端里连接", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        "ftp://${localIp ?: "本机IP"}:${config.port}",
                        fontSize = 13.sp, color = TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        if (config.anonymous) "匿名访问已开启，无需账号密码"
                        else "账号 ${config.username} / 密码 ${config.password}",
                        fontSize = 11.sp, color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        // ===== 服务参数配置 =====
        Text(
            "服务参数配置",
            fontSize = 12.sp, color = TextSecondary,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp)
        )
        Surface(shape = RoundedCornerShape(12.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FtpField("监听端口", config.port.toString(), imeFocus) { text ->
                    applyConfig(config.copy(port = text.filter { it.isDigit() }.take(5).toIntOrNull() ?: config.port))
                }
                FtpField("登录用户名", config.username, imeFocus) { applyConfig(config.copy(username = it)) }

                // 密码行：右侧眼睛切换明文，下方说明「自动生成并加密保存」
                OutlinedTextField(
                    value = config.password,
                    onValueChange = { applyConfig(config.copy(password = it)) },
                    label = { Text("登录密码", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
                    modifier = Modifier.fillMaxWidth().trackImeFocus(imeFocus),
                )
                Text(
                    "口令自动生成并加密保存在本机，可自行修改",
                    fontSize = 11.sp, color = TextSecondary
                )

                HorizontalDivider(color = Border, thickness = 0.5.dp)

                FtpSwitchRow(
                    title = "允许匿名访问",
                    hint = "无需输入用户名和密码即可连接",
                    checked = config.anonymous,
                    onCheckedChange = { applyConfig(config.copy(anonymous = it)) },
                )
                FtpSwitchRow(
                    title = "应用启动时自启",
                    hint = "应用启动时后台自动运行 FTP 服务",
                    checked = config.autoStart,
                    onCheckedChange = { applyConfig(config.copy(autoStart = it)) },
                )

                HorizontalDivider(color = Border, thickness = 0.5.dp)

                OutlinedTextField(
                    value = config.rootPath,
                    onValueChange = { applyConfig(config.copy(rootPath = it)) },
                    label = { Text("共享目录（容器内路径）", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
                    modifier = Modifier.fillMaxWidth().trackImeFocus(imeFocus),
                )

                // 匿名开启时风险明显升高：明文协议 + 同网段任何人可读写
                Text(
                    if (config.anonymous) {
                        "已允许匿名：同一 WiFi 下任何人都能不经认证读写共享目录，且 FTP 是明文协议。" +
                            "只在可信网络里临时开启。"
                    } else {
                        "FTP 是明文协议：同一 WiFi 下能嗅探到传输内容与密码。只在可信网络里临时开启。"
                    },
                    fontSize = 11.sp, color = Color(0xFFFF6B6B)
                )
            }
        }

        // ===== 保存配置 =====
        Spacer(Modifier.height(12.dp))
        Surface(
            onClick = {
                if (busy) return@Surface
                if (config.password.isBlank()) {
                    onFeedback("请先设置访问密码：空密码等于对同网段所有人开放")
                    return@Surface
                }
                busy = true
                scope.launch {
                    server.save(config)
                    dirty = false
                    // 运行中改参数要重启才生效，否则界面上改了、实际还是旧值
                    if (running) {
                        runCatching { server.stop() }
                        runCatching { server.start(config) }
                            .onFailure { onFeedback("已保存，但重启失败：${it.message}") }
                    }
                    busy = false
                    onFeedback(if (running) "已保存并重启服务" else "已保存配置")
                }
            },
            shape = RoundedCornerShape(12.dp),
            color = if (dirty) Accent else Accent.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (busy) "处理中…" else "保存配置",
                modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White
            )
        }

        Spacer(Modifier.height(BOTTOM_BAR_CLEARANCE))
    }
}

/** 配置项输入行。 */
@Composable
private fun FtpField(
    label: String,
    value: String,
    tracker: ImeFocusTracker,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
        modifier = Modifier.fillMaxWidth().trackImeFocus(tracker),
    )
}

/** 参数开关行：标题 + 说明 + 开关。 */
@Composable
private fun FtpSwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = TextPrimary)
            Text(hint, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 1.dp))
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Border,
            )
        )
    }
}

/** 登记当前聚焦输入框的可见性请求器，供键盘弹出时把它请进视野。 */
private class ImeFocusTracker {
    var requester: BringIntoViewRequester? = null
}

/**
 * 输入框聚焦时把自己登记到 [ImeFocusTracker]。
 *
 * 键盘弹出会让可滚区变矮，但页面的滚动位置不会自动跟着改（TextField 只在光标矩形
 * 变化时才重算自己的滚动），结果是输入框停在原处被键盘盖住 —— 由 [RemoteFtpTab]
 * 在键盘弹出的瞬间调 `bringIntoView()` 补上这一步。
 */
@Composable
private fun Modifier.trackImeFocus(tracker: ImeFocusTracker): Modifier {
    val requester = remember { BringIntoViewRequester() }
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged { if (it.isFocused) tracker.requester = requester }
}
