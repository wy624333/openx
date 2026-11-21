package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.remote.RemoteEngines
import com.wy.openx.remote.SavedSshKey
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * 「粘贴密钥」全屏页：名称 / 口令（可选）/ 私钥内容 / 保存。
 *
 * 做成独立页而不是弹窗：私钥是几十行的多行文本，弹窗里被键盘一挤只剩一两行，
 * 用户根本看不到自己粘进去的是不是完整的一把。
 *
 * [initial] 用于「从文件导入」与「编辑已有密钥」两条路径：前者带着读出的正文、
 * 后者带着原密钥（含 id，保存时覆盖原条目）。用户能先看一眼、改个名字，再保存——
 * 而不是选中文件就悄悄存进库。
 */
@Composable
internal fun RemotePasteKeyScreen(
    initial: SavedSshKey? = null,
    onBack: () -> Unit,
    onSaved: (SavedSshKey) -> Unit,
    onFeedback: (String) -> Unit,
) {
    val manager = RemoteEngines.manager
    val scope = rememberCoroutineScope()

    // 带了非空 id = 编辑已有密钥；否则是新增（从文件导入的草稿 id 为空）
    val editingId = initial?.id?.takeIf { it.isNotBlank() }

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var passphrase by remember { mutableStateOf(initial?.passphrase.orEmpty()) }
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var showPassphrase by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // 私钥内容与名称都填了才允许保存：空私钥存进去毫无意义，还会让连接报「内容为空」
    val canSave = name.isNotBlank() && content.isNotBlank() && !saving

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                    tint = TextSecondary, modifier = Modifier.size(20.dp)
                )
            }
            Text(
                if (editingId != null) "编辑密钥" else "粘贴密钥",
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // 名称
        FieldCard {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("名称", fontSize = 14.sp, color = TextSecondary) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = TextPrimary),
                colors = TransparentFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(10.dp))

        // 口令（可选），右侧眼睛切换明文
        FieldCard {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                placeholder = { Text("口令（可选）", fontSize = 14.sp, color = TextSecondary) },
                singleLine = true,
                visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassphrase = !showPassphrase }) {
                        Icon(
                            if (showPassphrase) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassphrase) "隐藏口令" else "显示口令",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = TextPrimary),
                colors = TransparentFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(10.dp))

        // 私钥内容：多行，固定高度 220dp
        Surface(shape = RoundedCornerShape(12.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text("私钥内容", fontSize = 14.sp, color = TextSecondary) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPrimary),
                colors = TransparentFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // 保存
        Surface(
            onClick = {
                if (!canSave) return@Surface
                saving = true
                scope.launch {
                    val key = SavedSshKey(
                        // 编辑时沿原 id，[RemoteKeyStore.save] 按 id 覆盖；新增则现生成
                        id = editingId ?: UUID.randomUUID().toString(),
                        name = name.trim(),
                        content = content.trim(),
                        passphrase = passphrase,
                        createdAt = initial?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                    runCatching { manager.saveKey(key) }
                        .onSuccess {
                            onFeedback(if (editingId != null) "已保存密钥 ${key.name}" else "已添加密钥 ${key.name}")
                            onSaved(key)
                        }
                        .onFailure { onFeedback(it.message ?: "保存失败") }
                    saving = false
                }
            },
            shape = RoundedCornerShape(12.dp),
            // 不可保存时置灰：按钮状态直接表达「还缺东西」
            color = if (canSave) Accent else Accent.copy(alpha = 0.25f),
            enabled = canSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Check, contentDescription = null,
                    tint = if (canSave) Color.White else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    if (saving) "保存中…" else "保存",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = if (canSave) Color.White else Color.White.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 白色圆角输入卡（与截图的卡片式表单一致）。 */
@Composable
private fun FieldCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        content()
    }
}

/**
 * 去掉 OutlinedTextField 自带的描边与背景。
 *
 * 截图里输入框是「一张卡片 + 无边框」的形态；Material3 的 OutlinedTextField
 * 默认有描边，嵌在卡片里会出现双层边框。这里把描边与容器都设透明，
 * 只保留输入内容本身。
 */
@Composable
private fun TransparentFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
)
