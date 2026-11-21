package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wy.openx.git.GitCredential
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.PageBg
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import com.wy.openx.ui.viewmodel.CredentialsViewModel

/**
 * 「凭据与署名」页：底部双 tab。
 *
 * - 署名：user.name / user.email（写容器全局 gitconfig）+ remote.origin.url（写当前仓库）；
 * - 凭据：每主机一条 HTTPS 凭据，供 credential.helper 读取，push/pull 自动认证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(
    viewModel: CredentialsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    var toastMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.toast) {
        state.toast?.let {
            toastMsg = it
            viewModel.consumeToast()
        }
    }
    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            kotlinx.coroutines.delay(2500)
            toastMsg = null
        }
    }

    var tab by remember { mutableStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GitCredential?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = PageBg,
        topBar = {
            TopAppBar(
                title = { Text("凭据与署名", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextSecondary)
                    }
                },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加凭据", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = TextPrimary)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            } else if (tab == 0) {
                IdentityTab(
                    userName = state.userName,
                    userEmail = state.userEmail,
                    repoUrl = state.repoUrl,
                    notARepo = state.notARepo,
                    busy = state.busy,
                    onSave = { n, e, u, saveUrl -> viewModel.saveIdentityAndUrl(n, e, u, saveUrl) }
                )
            } else {
                CredentialTab(
                    credentials = state.credentials,
                    onEdit = { editing = it },
                    onDelete = { viewModel.deleteCredential(it.id) }
                )
            }

            BottomTabBar(
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (toastMsg != null) {
                Surface(
                    color = Panel, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                ) {
                    Text(toastMsg!!, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    if (showAddSheet) {
        AddCredentialSheet(
            onManual = {
                showAddSheet = false
                adding = true
            },
            onDismiss = { showAddSheet = false }
        )
    }

    if (adding) {
        CredentialEditorDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { viewModel.saveCredential(it); adding = false }
        )
    }

    editing?.let { cred ->
        CredentialEditorDialog(
            initial = cred,
            onDismiss = { editing = null },
            onSave = { viewModel.saveCredential(it); editing = null }
        )
    }
}

// ===== 署名 tab =====

@Composable
private fun IdentityTab(
    userName: String,
    userEmail: String,
    repoUrl: String,
    notARepo: Boolean,
    busy: Boolean,
    onSave: (String, String, String, Boolean) -> Unit
) {
    var name by remember(userName) { mutableStateOf(userName) }
    var email by remember(userEmail) { mutableStateOf(userEmail) }
    var url by remember(repoUrl) { mutableStateOf(repoUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 96.dp)
    ) {
        SectionLabel("提交署名")
        Text(
            "写入容器全局配置，跨仓库生效。",
            color = TextSecondary, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            placeholder = { Text("user.name", color = TextSecondary) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            placeholder = { Text("user.email", color = TextSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel("仓库地址")
        Text(
            if (notARepo) "当前不是 Git 仓库，需先初始化或克隆仓库才能设置远程地址。"
            else "写入当前仓库的 remote.origin.url（仅项目级，不影响其它仓库）。",
            color = TextSecondary, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            placeholder = { Text("remote.origin.url", color = TextSecondary) },
            enabled = !notARepo,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))

        val canSave = !busy && name.isNotBlank() && email.isNotBlank()
        Surface(
            onClick = {
                val saveUrl = !notARepo && url != repoUrl
                onSave(name.trim(), email.trim(), url.trim(), saveUrl)
            },
            enabled = canSave,
            shape = RoundedCornerShape(12.dp),
            color = if (canSave) Accent else Panel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Check, contentDescription = null,
                    tint = if (canSave) Color.White else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "保存",
                    color = if (canSave) Color.White else TextSecondary,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ===== 凭据 tab =====

@Composable
private fun CredentialTab(
    credentials: List<GitCredential>,
    onEdit: (GitCredential) -> Unit,
    onDelete: (GitCredential) -> Unit
) {
    if (credentials.isEmpty()) {
        EmptyCredentials()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "header") {
            SectionLabel("已保存 ${credentials.size} 条")
        }
        items(credentials, key = { it.id }) { cred ->
            CredentialItem(
                credential = cred,
                onEdit = { onEdit(cred) },
                onDelete = { onDelete(cred) }
            )
        }
    }
}

@Composable
private fun CredentialItem(
    credential: GitCredential,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(12.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    credential.host,
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${credential.username} · ${maskToken(credential.token)}",
                    color = TextSecondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EmptyCredentials() {
    Box(Modifier.fillMaxSize().padding(bottom = 72.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Icon(
                    Icons.Default.Lock, contentDescription = null, tint = Accent,
                    modifier = Modifier.padding(20.dp).size(32.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("暂无 Git 凭据", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "点击右上角 + 添加：手动配置访问令牌。配置后推送、拉取代码时会自动完成身份认证。",
                color = TextSecondary, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ===== 底部双 tab =====

@Composable
private fun BottomTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(bottom = 20.dp)
            .background(Panel, RoundedCornerShape(28.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabPill(
            label = "署名",
            icon = Icons.Default.Person,
            active = selected == 0,
            onClick = { onSelect(0) }
        )
        Spacer(Modifier.width(6.dp))
        TabPill(
            label = "凭据",
            icon = Icons.Default.Lock,
            active = selected == 1,
            onClick = { onSelect(1) }
        )
    }
}

@Composable
private fun TabPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val tint = if (active) Accent else TextSecondary
    Row(
        modifier = Modifier
            .background(if (active) Accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
    )
}

/** Token 打码：只露末 4 位，避免截图/看屏泄露。 */
private fun maskToken(token: String): String =
    if (token.length <= 4) "****" else "****${token.takeLast(4)}"

// ===== 添加凭据：方式选择 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCredentialSheet(
    onManual: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                "添加凭据",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
            AddOptionRow(
                title = "手动配置凭据",
                desc = "适用于 GitLab、Gitee、自建服务器或个人访问令牌",
                onClick = onManual
            )
        }
    }
}

@Composable
private fun AddOptionRow(title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = TextSecondary, fontSize = 12.sp)
        }
        Text("›", color = TextSecondary, fontSize = 18.sp)
    }
}

// ===== 凭据编辑 =====

@Composable
private fun CredentialEditorDialog(
    initial: GitCredential?,
    onDismiss: () -> Unit,
    onSave: (GitCredential) -> Unit
) {
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var username by remember(initial) { mutableStateOf(initial?.username ?: "") }
    var token by remember(initial) { mutableStateOf(initial?.token ?: "") }
    var tokenVisible by remember { mutableStateOf(false) }

    val canSave = host.trim().isNotBlank() && username.trim().isNotBlank() && token.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) "添加凭据" else "编辑凭据",
                color = TextPrimary, fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "GitHub/Gitee 填 Personal Access Token，GitLab 填 PAT，自建服务可填密码。",
                    color = TextSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    placeholder = { Text("主机（如 github.com）", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    placeholder = { Text("用户名", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    placeholder = { Text("访问令牌 / 密码", color = TextSecondary) },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (tokenVisible) "隐藏" else "显示",
                                tint = TextSecondary
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        GitCredential(
                            host = host.trim(),
                            username = username.trim(),
                            token = token
                        )
                    )
                },
                enabled = canSave
            ) { Text("保存", color = if (canSave) Accent else TextSecondary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) } },
        containerColor = Panel
    )
}
