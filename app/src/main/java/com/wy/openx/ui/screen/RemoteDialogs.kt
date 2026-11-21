package com.wy.openx.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.remote.RemoteConnection
import com.wy.openx.remote.RemoteEngines
import com.wy.openx.remote.RemoteEntry
import com.wy.openx.remote.RemoteHostKeyPendingException
import com.wy.openx.remote.RemoteMount
import com.wy.openx.remote.RemoteSyncSettings
import com.wy.openx.ui.theme.Accent
import kotlinx.coroutines.launch
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import java.util.UUID

/**
 * 新增 / 编辑一条挂载。 *
 * 本地路径是**容器内路径**（如 `/root/workspace/projects/demo`），不是手机存储路径——
 * 同步引擎会把它翻译成宿主 File。文案里写清楚，否则用户会去填 `/sdcard/...`。
 */
@Composable
internal fun RemoteMountEditDialog(
    initial: RemoteMount?,
    connections: List<RemoteConnection>,
    onDismiss: () -> Unit,
    onSave: (RemoteMount) -> Unit,
) {
    val isNew = initial == null
    var connectionId by remember {
        mutableStateOf(initial?.connectionId ?: connections.firstOrNull()?.id.orEmpty())
    }
    var remotePath by remember { mutableStateOf(initial?.remotePath ?: "") }
    var localPath by remember { mutableStateOf(initial?.localMountPath ?: "") }
    var autoConnect by remember { mutableStateOf(initial?.autoConnect ?: true) }
    var sourceOnServer by remember { mutableStateOf(initial?.sourceOnServer ?: false) }
    var showPathPicker by remember { mutableStateOf(false) }
    // 工作区列表只在打开时读一次：对话框生命周期很短，不值得为它挂 Flow
    val workspaces = remember { RemoteEngines.manager.localWorkspaces() }

    val canSave = connectionId.isNotBlank() && remotePath.isNotBlank() && localPath.isNotBlank()

    // 目录浏览器叠在本弹窗之上；选完就关掉，不改变本弹窗的其它输入
    if (showPathPicker) {
        val picked = connections.firstOrNull { it.id == connectionId }
        if (picked != null) {
            RemotePathPickerDialog(
                connection = picked,
                initialPath = remotePath.trim().ifBlank { picked.remotePath },
                onPick = {
                    remotePath = it
                    showPathPicker = false
                },
                onDismiss = { showPathPicker = false },
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) "新增挂载" else "编辑挂载",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (connections.isEmpty()) {
                    Text("还没有可用的连接，请先在「连接配置」里添加一台服务器。", fontSize = 12.sp, color = Color(0xFFFF6B6B))
                } else {
                    Text("选择连接", fontSize = 12.sp, color = TextSecondary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        connections.forEach { config ->
                            RemoteChoiceRow(
                                label = config.name,
                                hint = config.endpoint,
                                selected = connectionId == config.id,
                                onClick = { connectionId = config.id },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = { Text("远端目录", fontSize = 12.sp) },
                    placeholder = { Text("/var/www/html 或 /home/user/project", fontSize = 11.sp, color = TextSecondary) },
                    singleLine = true,
                    trailingIcon = {
                        // 没选连接就没什么可浏览的，干脆不给按钮
                        if (connections.any { it.id == connectionId }) {
                            TextButton(onClick = { showPathPicker = true }) {
                                Text("浏览", fontSize = 11.sp, color = Accent)
                            }
                        }
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = localPath,
                    onValueChange = { localPath = it },
                    label = { Text("本地工作区目录（容器内路径）", fontSize = 12.sp) },
                    placeholder = { Text("/root/workspace/projects/demo", fontSize = 11.sp, color = TextSecondary) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 让用户手填容器路径不现实（得先知道这个路径长什么样），列出已有工作区供点选。
                // 注意不能嵌套可滚动列：外层已经是 verticalScroll，同向再套一层会直接崩。
                if (workspaces.isNotEmpty()) {
                    Text("或点选一个已有工作区", fontSize = 12.sp, color = TextSecondary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        workspaces.forEach { (label, containerPath) ->
                            RemoteChoiceRow(
                                label = label,
                                hint = containerPath,
                                selected = localPath.trim() == containerPath,
                                onClick = { localPath = containerPath },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("服务器为准", fontSize = 13.sp, color = TextPrimary)
                        Text(
                            "开：AI、文件树、编辑器直接操作服务器上的这一份代码，本工作区停止同步。\n" +
                                "关：本地为源，只往服务器传（普通同步）。",
                            fontSize = 11.sp, color = TextSecondary
                        )
                    }
                    Switch(
                        checked = sourceOnServer,
                        onCheckedChange = { sourceOnServer = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = Accent,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = Border,
                        )
                    )
                }

                // 服务器为准时同步是停的，「自动连接（开始上传）」留着只会让人以为还在传
                if (!sourceOnServer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动连接", fontSize = 13.sp, color = TextPrimary)
                            Text("进入此页时自动连上并开始增量上传", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = autoConnect,
                            onCheckedChange = { autoConnect = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = Accent,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = Border,
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        RemoteMount(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            connectionId = connectionId,
                            remotePath = remotePath.trim(),
                            localMountPath = localPath.trim(),
                            autoConnect = autoConnect,
                            sourceOnServer = sourceOnServer,
                            sortOrder = initial?.sortOrder ?: 0,
                        )
                    )
                },
                enabled = canSave,
            ) { Text("保存", color = if (canSave) Accent else TextSecondary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } },
        containerColor = Panel,
    )
}

/** 单选行（用于选择连接 / 选择密钥）。 */
@Composable
internal fun RemoteChoiceRow(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Accent.copy(alpha = 0.12f) else Panel,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, Accent) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, fontSize = 13.sp, color = if (selected) Accent else TextPrimary)
            Text(hint, fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 主机密钥管理：查看/删除已保存的 SSH 主机指纹。
 *
 * 服务器换了密钥后旧指纹会让连接被拒，这一页是唯一的「解除」入口——
 * 没有它用户只能清应用数据。
 */
/**
 * 远端目录选择器：进目录、回上级、选定当前目录。
 *
 * 只列目录——挂载点填的是同步根，把文件混进来只会让人点错。
 * 「上级」是按路径字符串算的，不依赖服务端返回 `..`（SFTP/FTP 实现都会把它滤掉）。
 *
 * 用临时连接列目录（[RemoteManager.listOnce]）：用户在挂载编辑器里浏览一下，
 * 不该把另一台正在连着的服务器断掉。
 */
@Composable
internal fun RemotePathPickerDialog(
    connection: RemoteConnection,
    initialPath: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val manager = RemoteEngines.manager
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf(initialPath.ifBlank { "/" }) }
    var dirs by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load(path: String) {
        scope.launch {
            loading = true
            runCatching { manager.listOnce(connection, path) }
                .onSuccess { entries ->
                    dirs = entries.filter { it.isDirectory }
                    currentPath = path
                    error = null
                }
                .onFailure { error = it.message ?: "读取目录失败" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load(currentPath) }

    // "/a/b".trimEnd('/') = "/a/b" → 去掉最后一段得 "/a"；根目录再去就空了，回退成 "/"
    val parent = currentPath.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择远端目录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                Text(
                    "${connection.name} · $currentPath",
                    fontSize = 11.sp, color = TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { load(parent) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("↑ 返回上级", fontSize = 12.sp, color = Accent)
                }
                if (loading) Text("读取中…", fontSize = 11.sp, color = TextSecondary)
                error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFFF6B6B)) }
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                ) {
                    dirs.forEach { entry ->
                        Text(
                            entry.name,
                            fontSize = 12.sp, color = TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { load(entry.path) }
                                .padding(vertical = 7.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(currentPath) }) {
                Text("选择此目录", fontSize = 12.sp, color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", fontSize = 12.sp, color = TextSecondary) }
        },
        containerColor = Panel,
    )
}

/**
 * 主机指纹确认：首次连接、或指纹与已记录的不一致时弹出。
 *
 * 必须由用户自己拍板：指纹不对意味着对面可能不是原来那台机器，
 * 自动接受（等于没校验）和自动拒绝（服务器换了密钥就永久连不上）都不对。
 *
 * 指纹放在可选中区域，方便用户复制去和 `ssh-keygen -lf` 的输出核对。
 */
@Composable
internal fun RemoteHostKeyDialog(
    pending: RemoteHostKeyPendingException,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (pending.changed) "主机密钥已变化" else "首次连接这台服务器",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (pending.changed) {
                        "${pending.host}:${pending.port} 出示的密钥与上次记录的不一致。\n\n" +
                            "如果你确实更换过服务器密钥，可以信任；否则可能有人在中途插入，应当取消。"
                    } else {
                        "还没有记录过 ${pending.host}:${pending.port} 的指纹。\n\n" +
                            "信任后会记下来，以后变了会再问你。"
                    },
                    fontSize = 12.sp,
                    color = if (pending.changed) Color(0xFFFFB74D) else TextSecondary
                )
                Text(
                    "指纹（SHA256）",
                    fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                SelectionContainer {
                    Text(pending.fingerprint, fontSize = 10.sp, color = TextPrimary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(
                    if (pending.changed) "信任新指纹" else "信任并连接",
                    fontSize = 12.sp, color = Accent
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消", fontSize = 12.sp, color = TextSecondary) }
        },
        containerColor = Panel,
    )
}

@Composable
internal fun RemoteHostKeysDialog(
    keys: Map<String, String>,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主机密钥", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "连接时会核对服务器指纹：没记录过、或与记录不符，都会先问你一句再决定要不要连。" +
                        "核心作用是拦住中间人——指纹对不上时，别急着点信任，先确认对面是不是你自己那台机器。" +
                        "确实换过服务器密钥的话，删掉对应记录后重连即可重新记录。",
                    fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                if (keys.isEmpty()) {
                    Text("还没有记录任何主机指纹。", fontSize = 12.sp, color = TextSecondary)
                } else {
                    keys.entries.sortedBy { it.key }.forEach { (hostKey, fingerprint) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(hostKey, fontSize = 12.sp, color = TextPrimary)
                                Text(
                                    fingerprint, fontSize = 10.sp, color = TextSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { onForget(hostKey) }) {
                                Text("删除", fontSize = 11.sp, color = Color(0xFFFF6B6B))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        containerColor = Panel,
    )
}

/**
 * 同步设置：底部抽屉，含「遵循 .gitignore 规则」与「单次同步队列上限」。
 *
 * 用底部抽屉而不是居中弹窗：这两项都是需要边看说明边调的服务参数，
 * 抽屉的纵向空间和可滚动性比弹窗从容。
 *
 * 没有手填忽略列表：项目自己的 `.gitignore` 已经写了该忽略什么，
 * 再加一个列表只会变成两份规则要同步维护。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteSyncSettingsDialog(
    current: RemoteSyncSettings,
    onSave: (RemoteSyncSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var respectGitignore by remember { mutableStateOf(current.respectGitignore) }
    // 输入框用字符串承载：用户删空时不能变成 0，也不能阻止继续输入
    var batchText by remember { mutableStateOf(current.batchSize.toString()) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "同步设置",
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SyncSettingSwitchRow(
                title = "遵循 .gitignore 规则",
                hint = "自动读取项目根目录的 .gitignore 并忽略其中内容。",
                checked = respectGitignore,
                onCheckedChange = { respectGitignore = it },
            )

            androidx.compose.material3.HorizontalDivider(
                color = Border, thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            Text(
                "单次同步队列上限",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary
            )
            Text(
                "超出此数量的操作将分批排队执行，防止突发大量修改导致断连。",
                fontSize = 11.sp, color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            OutlinedTextField(
                value = batchText,
                onValueChange = { text ->
                    // 只留数字，并限制长度：超过 3 位没意义，也防用户贴进一整串
                    batchText = text.filter { it.isDigit() }.take(3)
                },
                label = { Text("最大数量", fontSize = 12.sp) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "范围 ${RemoteSyncSettings.BATCH_MIN}～${RemoteSyncSettings.BATCH_MAX}。" +
                    "调小更稳（每批传完刷索引，中断不重传）；调大更快但占通道。",
                fontSize = 11.sp, color = TextSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(20.dp))

            androidx.compose.material3.Surface(
                onClick = {
                    val size = batchText.toIntOrNull()
                        ?.coerceIn(RemoteSyncSettings.BATCH_MIN, RemoteSyncSettings.BATCH_MAX)
                        ?: current.batchSize
                    onSave(RemoteSyncSettings(respectGitignore, size))
                },
                shape = RoundedCornerShape(12.dp),
                color = Accent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "保存设置",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White
                )
            }
        }
    }
}

/** 设置项开关行：标题 + 说明 + 开关。 */
@Composable
private fun SyncSettingSwitchRow(
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
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(hint, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
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
