package com.wy.openx.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.remote.RemoteAuthType
import com.wy.openx.remote.RemoteConnection
import com.wy.openx.remote.RemoteEngines
import com.wy.openx.remote.RemoteHostKeyPendingException
import com.wy.openx.remote.RemoteProtocol
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.PanelAlt
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * 新增 / 编辑一台远程服务器。
 *
 * 校验只挡「必然失败」的输入（地址空、端口非法），不去猜用户的网络拓扑：
 * 端口写错、私钥路径不对这些，连一次就知道，比在这里做一堆启发式判断更直接。
 *
 * 密码框用 [PasswordVisualTransformation] 遮蔽：设置页可能被人看到，
 * 明文密码直接摊在屏幕上不合适。
 */
@Composable
internal fun RemoteServerEditDialog(
    initial: RemoteConnection?,
    /**
     * 测试时碰到没确认过的主机指纹：本编辑器在弹窗上，弹不了确认框，
     * 交给调用页统一展示。
     */
    onHostKeyPending: (RemoteHostKeyPendingException, RemoteConnection) -> Unit,
    onDismiss: () -> Unit,
    onSave: (RemoteConnection) -> Unit,
) {
    val isNew = initial == null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var protocol by remember { mutableStateOf(initial?.protocol ?: RemoteProtocol.SFTP) }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: RemoteConnection.defaultPort(protocol)).toString()) }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var authType by remember { mutableStateOf(initial?.authType ?: RemoteAuthType.PASSWORD) }
    // 密码与私钥路径分开存：两者在 RemoteConnection 里共用 authData 字段，但界面是两个独立输入框。
    // 若共用一个状态，填完密码切到「私钥」tab 会把密码当路径显示出来（反之亦然）。
    var password by remember {
        mutableStateOf(if (initial?.authType == RemoteAuthType.PASSWORD) initial.authData else "")
    }
    var privateKeyPath by remember {
        mutableStateOf(if (initial?.authType == RemoteAuthType.PRIVATE_KEY) initial.authData else "")
    }
    var savedKeyId by remember { mutableStateOf(initial?.savedKeyId) }
    var allowAiExec by remember { mutableStateOf(initial?.allowAiExec ?: false) }
    // 已保存密钥列表：进弹窗时读一次，不在重组里反复读文件
    val savedKeys = remember { RemoteEngines.manager.keys.value }
    var passphrase by remember { mutableStateOf(initial?.passphrase ?: "") }
    var remotePath by remember { mutableStateOf(initial?.remotePath ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val portValue = port.toIntOrNull()
    // 按当前认证方式校验对应字段：私钥模式走密钥库时无需填路径
    val authFilled = when {
        protocol == RemoteProtocol.FTP -> password.isNotBlank()
        authType == RemoteAuthType.PASSWORD -> password.isNotBlank()
        savedKeyId != null -> true
        else -> privateKeyPath.isNotBlank()
    }
    val canSave = name.isNotBlank() && host.isNotBlank() && portValue != null &&
        portValue in 1..65535 && authFilled

    /** 当前认证方式下应写入 [RemoteConnection.authData] 的值。 */
    fun currentAuthData(): String = when {
        protocol == RemoteProtocol.FTP -> password
        authType == RemoteAuthType.PASSWORD -> password
        else -> privateKeyPath
    }

    /** 测试连接结果：null=未测试，true=成功，false=失败。 */
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) "添加连接通道" else "编辑连接通道",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 协议放最上面：它决定端口默认值与「密码/私钥」哪个认证方式可用，
                // 先选协议再填其余字段，用户不会填完才发现选错了。
                ProtocolPicker(
                    selected = protocol,
                    onSelect = { picked ->
                        protocol = picked
                        // 切协议时端口跟着换默认值，否则 SFTP 改 FTP 会留着 22 连不上
                        port = RemoteConnection.defaultPort(picked).toString()
                        // FTP 只支持密码登录，私钥模式在 FTP 上没有意义
                        if (picked == RemoteProtocol.FTP) authType = RemoteAuthType.PASSWORD
                        testResult = null
                    },
                )

                RemoteField("连接名称", name, "例如 家里的电脑") { name = it }

                RemoteField("主机地址", host, "例如 192.168.1.10") { host = it }

                RemoteField(
                    label = "端口",
                    value = port,
                    placeholder = "SFTP 22 / FTP 21",
                    keyboardType = KeyboardType.Number,
                ) { port = it.filter { c -> c.isDigit() }.take(5) }

                RemoteField("用户名", username, "例如 root") { username = it }

                if (protocol == RemoteProtocol.SFTP) {
                    AuthTypePicker(selected = authType, onSelect = { authType = it; testResult = null })
                }

                if (protocol == RemoteProtocol.SFTP && authType == RemoteAuthType.PRIVATE_KEY) {
                    // 已有密钥库时优先让用户选：一把私钥可被多条连接共用，
                    // 改密钥不用逐条改连接。没密钥库时退回文件路径模式。
                    if (savedKeys.isNotEmpty()) {
                        Text("登录密钥", fontSize = 12.sp, color = TextSecondary)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            savedKeys.forEach { key ->
                                RemoteChoiceRow(
                                    label = key.name,
                                    hint = if (key.passphrase.isNotBlank()) "带口令" else "无口令",
                                    selected = savedKeyId == key.id,
                                    onClick = { savedKeyId = key.id; testResult = null },
                                )
                            }
                            // 「用文件路径」作为备选：用户可能不想把密钥存进库
                            RemoteChoiceRow(
                                label = "使用文件路径",
                                hint = "不存入库，直接读磁盘上的私钥文件",
                                selected = savedKeyId == null,
                                onClick = { savedKeyId = null; testResult = null },
                            )
                        }
                    }
                    // 只有不走密钥库时才需要填路径
                    if (savedKeyId == null) {
                        RemoteField(
                            label = "私钥路径",
                            value = privateKeyPath,
                            placeholder = "例如 /root/.ssh/id_ed25519",
                        ) { privateKeyPath = it; testResult = null }
                        RemoteField("私钥口令（可选）", passphrase, "加密私钥才需要", masked = true) { passphrase = it }
                    }
                } else {
                    RemoteField(
                        label = "密码",
                        value = password,
                        placeholder = "登录密码",
                        masked = true,
                        trailing = { showPassword = !showPassword },
                        showPassword = showPassword,
                    ) { password = it; testResult = null }
                }

                RemoteField("起始目录（可选）", remotePath, "留空则用登录目录下的 \$HOME/.openx") { remotePath = it }

                // 测试连通性：填完就去验一下，不用先保存再发现连不上
                TextButton(
                    onClick = {
                        if (testing) return@TextButton
                        testing = true
                        testResult = null
                        testError = null
                        scope.launch {
                            val draft = RemoteConnection(
                                id = initial?.id ?: "test",
                                name = name.trim().ifBlank { "test" },
                                protocol = protocol,
                                host = host.trim(),
                                port = portValue ?: RemoteConnection.defaultPort(protocol),
                                username = username.trim(),
                                authType = authType,
                                authData = currentAuthData(),
                                passphrase = passphrase,
                                savedKeyId = savedKeyId,
                                remotePath = remotePath.trim(),
                            )
                            RemoteEngines.manager.testConnection(draft)
                                .onSuccess { testResult = true }
                                .onFailure { error ->
                                    val pending = error as? RemoteHostKeyPendingException
                                    if (pending == null) {
                                        testResult = false
                                        testError = error.message?.take(160)
                                    } else {
                                        // 回到未测状态：确认框弹出来盖在上面，
                                        // 底下挂着「连接失败」会误导
                                        testResult = null
                                        onHostKeyPending(pending, draft)
                                    }
                                }
                            testing = false
                        }
                    },
                    enabled = host.isNotBlank() && authFilled && !testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            testing -> "正在测试…"
                            testResult == true -> "连通正常 ✓"
                            testResult == false -> "连接失败，请检查参数"
                            else -> "测试连通性"
                        },
                        fontSize = 12.sp,
                        color = when (testResult) {
                            true -> Color(0xFF4CAF50)
                            false -> Color(0xFFFF6B6B)
                            else -> TextSecondary
                        }
                    )
                }

                if (testResult == false && !testError.isNullOrBlank()) {
                    Text(
                        "原因：$testError",
                        fontSize = 11.sp,
                        color = Color(0xFFFF6B6B),
                    )
                }

                if (protocol == RemoteProtocol.FTP) {
                    Text(
                        "FTP 是明文协议，密码与文件内容都不加密。只在可信内网使用。",
                        fontSize = 11.sp, color = Color(0xFFFF6B6B)
                    )
                }

                // 默认关。这是本页唯一一个「开了之后 AI 能在真机器上动手」的开关，
                // 所以文案要把后果说清楚，不能写得像个普通便利选项。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("允许 AI 执行命令", fontSize = 13.sp, color = TextPrimary)
                        Text(
                            "开启后 AI 可在这台服务器上直接跑命令（remote_ssh）。" +
                                "服务器上的操作不可撤销，只给自己的机器开。",
                            fontSize = 11.sp, color = TextSecondary
                        )
                    }
                    Switch(checked = allowAiExec, onCheckedChange = { allowAiExec = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        RemoteConnection(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            protocol = protocol,
                            host = host.trim(),
                            port = portValue ?: RemoteConnection.defaultPort(protocol),
                            username = username.trim(),
                            authType = authType,
                            authData = currentAuthData(),
                            passphrase = passphrase,
                            savedKeyId = savedKeyId,
                            remotePath = remotePath.trim(),
                            allowAiExec = allowAiExec,
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

/** 表单里的单个输入项。 */
@Composable
private fun RemoteField(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    trailing: (() -> Unit)? = null,
    showPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, fontSize = 11.sp, color = TextSecondary) },
        singleLine = true,
        visualTransformation = when {
            !masked -> androidx.compose.ui.text.input.VisualTransformation.None
            showPassword -> androidx.compose.ui.text.input.VisualTransformation.None
            else -> PasswordVisualTransformation()
        },
        trailingIcon = trailing?.let { toggle ->
            {
                androidx.compose.material3.IconButton(onClick = toggle) {
                    androidx.compose.material3.Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff
                        else Icons.Outlined.Visibility,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 协议二选一。 */
@Composable
private fun ProtocolPicker(selected: RemoteProtocol, onSelect: (RemoteProtocol) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("协议", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(end = 4.dp))
        RemoteProtocol.entries.forEach { p ->
            SegmentChip(
                label = p.name,
                selected = selected == p,
                onClick = { onSelect(p) },
            )
        }
    }
}

/** 认证方式二选一。 */
@Composable
private fun AuthTypePicker(selected: RemoteAuthType, onSelect: (RemoteAuthType) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("认证", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(end = 4.dp))
        SegmentChip("密码", selected == RemoteAuthType.PASSWORD) { onSelect(RemoteAuthType.PASSWORD) }
        SegmentChip("私钥", selected == RemoteAuthType.PRIVATE_KEY) { onSelect(RemoteAuthType.PRIVATE_KEY) }
    }
}

/** 胶囊形状的分段选择项。 */
@Composable
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = if (selected) Accent else PanelAlt,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) Color.White else TextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
