package com.wy.openx.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.git.GitCredentialBridge
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 全局「需要 Git 凭据」弹窗：容器内任何 git 命令缺凭据时由 helper 经文件 IPC 触发。
 *
 * 挂在根层（[com.wy.openx.MainActivity]），三端（Git 页 / AI Bash / 交互终端）共用；填完写回响应
 * 文件，helper 取走后 git 自动续跑，用户不需要重跑命令。
 */
@Composable
fun GitCredentialPromptDialog() {
    val requestState by GitCredentialBridge.request.collectAsState()
    val request = requestState ?: return
    val scope = rememberCoroutineScope()
    var username by remember(request.requestId) { mutableStateOf("") }
    var password by remember(request.requestId) { mutableStateOf("") }
    val canSubmit = username.isNotBlank() && password.isNotBlank()

    AlertDialog(
        onDismissRequest = { scope.launch { GitCredentialBridge.cancel() } },
        title = { Text("需要 Git 凭据", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "访问 ${request.host} 需要身份认证。填完会自动继续执行当前操作并保存到凭据页。",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    placeholder = { Text("用户名", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    placeholder = { Text("访问令牌 / 密码", color = TextSecondary) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { scope.launch { GitCredentialBridge.respond(username.trim(), password) } },
                enabled = canSubmit
            ) { Text("确定", color = if (canSubmit) Accent else TextSecondary) }
        },
        dismissButton = {
            TextButton(onClick = { scope.launch { GitCredentialBridge.cancel() } }) {
                Text("取消", color = TextSecondary)
            }
        },
        containerColor = Panel
    )
}
