package com.wy.openx.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.remote.RemoteEngines
import com.wy.openx.remote.SavedSshKey
import com.wy.openx.ui.component.FloatingTabBar
import com.wy.openx.ui.component.FloatingTabItem
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.BgPrimary
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主机密钥页：两个 tab —— 已保存的**登录密钥** 与 **主机指纹**。
 *
 * 两件事放一起是因为它们都属于「SSH 认证材料」：用户点顶栏那个钥匙图标，
 * 想的就是「管我的密钥」；分成两个入口反而要记哪个是哪个。
 *
 * 顶栏的「+」有两个来源：从文件导入、粘贴密钥。两者都跳到独立全屏页（见 [RemotePasteKeyScreen]）——
 * 私钥是多行长文本，塞进弹窗里会被键盘挤到只剩一两行。
 *
 * 点已有密钥那一行也是进同一个页面，带着原密钥预填，保存时按 id 覆盖。
 */
@Composable
internal fun RemoteKeysScreen(
    onBack: () -> Unit,
    onPasteKey: (draft: SavedSshKey?) -> Unit,
    onFeedback: (String) -> Unit,
) {
    val manager = RemoteEngines.manager
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(0) }
    val keys by manager.keys.collectAsState()
    var hostKeys by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showAddSheet by remember { mutableStateOf(false) }

    /**
     * 选文件导入：读完正文后不直接入库，而是带着正文跳到粘贴页。
     *
     * 让用户先看一眼、起个名字再存——选中文件就惄惄存进库，
     * 用户既不知道存了什么，也无法确认读到的是一整把完整的私钥。
     */
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                onFeedback("读取失败或文件为空")
                return@launch
            }
            // 用文件名做默认名称；取不到就用固定文案。id 留空 = 新增。
            val name = withContext(Dispatchers.IO) { displayNameOf(context, uri) }
                ?: "导入的密钥"
            onPasteKey(SavedSshKey(id = "", name = name, content = content))
        }
    }

    fun refreshHostKeys() {
        hostKeys = manager.savedHostKeys()
    }

    LaunchedEffect(Unit) {
        runCatching { manager.loadKeys() }
        refreshHostKeys()
    }

    Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶栏：返回 / 标题 / 新增
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                        tint = TextSecondary, modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    "主机密钥", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(Modifier.weight(1f))
                // 指纹页不支持新增（指纹是连接时自动记录的），只在密钥页给「+」
                if (tab == 0) {
                    IconButton(onClick = { showAddSheet = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "添加密钥", tint = Accent, modifier = Modifier.size(20.dp))
                    }
                }
            }

            when (tab) {
                0 -> KeysTab(
                    keys = keys,
                    onDelete = { key ->
                        scope.launch {
                            manager.deleteKey(key.id)
                            onFeedback("已删除密钥 ${key.name}")
                        }
                    },
                    onEdit = { key -> onPasteKey(key) },
                )
                else -> FingerprintsTab(
                    hostKeys = hostKeys,
                    onForget = { hostKey ->
                        val host = hostKey.substringBeforeLast(':')
                        val port = hostKey.substringAfterLast(':').toIntOrNull() ?: 22
                        manager.forgetHostKey(host, port)
                        refreshHostKeys()
                        onFeedback("已删除指纹 $hostKey")
                    },
                )
            }
        }

        FloatingTabBar(
            selected = tab,
            onSelect = { tab = it },
            items = listOf(
                FloatingTabItem(Icons.Outlined.Key, "密钥"),
                FloatingTabItem(Icons.Outlined.Fingerprint, "指纹"),
            ),
            maskColor = BgPrimary,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showAddSheet) {
        RemoteAddKeySheet(
            onDismiss = { showAddSheet = false },
            onPaste = {
                showAddSheet = false
                onPasteKey(null)
            },
            onImportFile = {
                showAddSheet = false
                // 私钥文件没有统一 MIME（常见是 text/plain 或 octet-stream），只能全放开
                importLauncher.launch(arrayOf("*/*"))
            },
        )
    }
}

/** 密钥 tab：已保存的登录密钥列表。点一行进编辑页。 */
@Composable
private fun KeysTab(
    keys: List<SavedSshKey>,
    onDelete: (SavedSshKey) -> Unit,
    onEdit: (SavedSshKey) -> Unit,
) {
    if (keys.isEmpty()) {
        RemoteEmptyState(
            icon = { Icon(Icons.Outlined.VpnKey, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp)) },
            title = "暂无登录密钥",
            hint = "添加一把私钥，SFTP 连接就可以用它登录，不必在每条连接里各填一次",
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        keys.forEach { key ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Panel,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(key) }
                        .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Key, contentDescription = null,
                        tint = Accent, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(key.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text(
                            buildString {
                                append(if (key.passphrase.isNotBlank()) "带口令" else "无口令")
                                if (key.createdAt > 0) {
                                    append(" · ")
                                    append(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(key.createdAt)))
                                }
                            },
                            fontSize = 11.sp, color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "编辑",
                        tint = TextSecondary, modifier = Modifier.size(18.dp)
                    )
                    IconButton(onClick = { onDelete(key) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Delete, contentDescription = "删除",
                            tint = Color(0xFFFF6B6B).copy(alpha = 0.8f), modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(BOTTOM_BAR_CLEARANCE))
    }
}

/** 指纹 tab：已保存的 SSH 主机指纹。 */
@Composable
private fun FingerprintsTab(
    hostKeys: Map<String, String>,
    onForget: (String) -> Unit,
) {
    if (hostKeys.isEmpty()) {
        RemoteEmptyState(
            icon = { Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp)) },
            title = "暂无主机指纹",
            hint = "首次连接服务器时会自动记录指纹，之后指纹变化会拒绝连接（防中间人）",
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "首次连接时记录服务器指纹，之后指纹变化会拒绝连接。如果你更换了服务器的密钥，" +
                "删除对应记录后重连即可重新记录。",
            fontSize = 11.sp, color = TextSecondary,
            modifier = Modifier.padding(vertical = 10.dp)
        )
        hostKeys.entries.sortedBy { it.key }.forEach { (hostKey, fingerprint) ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Panel,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Fingerprint, contentDescription = null,
                        tint = TextSecondary, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(hostKey, fontSize = 13.sp, color = TextPrimary)
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
        Spacer(Modifier.height(BOTTOM_BAR_CLEARANCE))
    }
}

/** 添加密钥的底部抽屉：从文件导入 / 粘贴密钥。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteAddKeySheet(
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    onImportFile: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                "添加登录密钥", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "私钥只保存在本机，且加密存储",
                fontSize = 11.sp, color = TextSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            SheetActionItem(
                icon = Icons.Outlined.FileOpen,
                title = "从文件导入",
                hint = "选择 .pem / id_rsa / id_ed25519 等私钥文件",
                onClick = onImportFile,
            )
            HorizontalDivider(color = Border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
            SheetActionItem(
                icon = Icons.Outlined.Description,
                title = "粘贴密钥",
                hint = "把私钥内容直接粘贴进来",
                onClick = onPaste,
            )
        }
    }
}

/** 从 content URI 取显示文件名（含扩展名）。 */
private fun displayNameOf(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

/** 抽屉里的一行操作。 */
@Composable
private fun SheetActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = TextPrimary)
            Text(hint, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 1.dp))
        }
    }
}
