package com.wy.openx.ui.screen

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wy.openx.container.ContainerProfile
import com.wy.openx.container.RootfsSource
import com.wy.openx.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 单个容器行的 shell 副标题：显示生效 shell，留空按 /bin/sh。 */
internal fun containerShellSubtitle(profile: ContainerProfile): String =
    "shell: ${profile.shellPath?.ifBlank { null } ?: "/bin/sh"}"

/**
 * 容器镜像编辑/添加弹窗（本地 PRoot 镜像）：名称、shell 路径、额外挂载/参数/环境变量、
 * 选择 tar.gz/tar.xz 镜像文件（编辑内置时保持内置来源，隐藏文件选择）、设为当前容器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContainerProfileEditorSheet(
    initial: ContainerProfile?,
    activeProfileId: String,
    onDismiss: () -> Unit,
    onConfirm: (ContainerProfile, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val initialAsset = initial?.rootfsSource as? RootfsSource.Asset

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var shellPath by remember {
        mutableStateOf(initial?.shellPath ?: "")
    }
    // 「设为当前容器」= 这个镜像现在是不是当前容器。
    // 旧写法 `id == null || id == BUILTIN_ID` 是拿「是不是内置镜像」冒充「是不是当前容器」，
    // 导致内置镜像恒显示开（而点关时 save 里 if(setActive) 直接跳过 → 关不掉），
    // 自定义镜像恒显示关（哪怕它已经是当前容器 → 看着像打不开）。
    // 规则见 ContainerActiveSwitch，口径与参照一般做法。
    val isCurrentlyActive = com.wy.openx.container.ContainerActiveSwitch
        .initialState(initial?.id, activeProfileId)
    var setAsActive by remember { mutableStateOf(isCurrentlyActive) }
    val bindingsList = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial?.extraBindings?.map { b ->
                val parts = b.split(":", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else b to ""
            } ?: emptyList())
        }
    }
    val argsList = remember {
        mutableStateListOf<String>().apply { addAll(initial?.extraArgs ?: emptyList()) }
    }
    val envList = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial?.env?.toList() ?: emptyList())
        }
    }
    val initialUri = (initial?.rootfsSource as? RootfsSource.LocalFile)?.uri
    var pickedUri by remember { mutableStateOf(initialUri) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pickedUri) {
        pickedName = pickedUri?.let { queryImageDisplayName(context, it) }
    }
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pickedUri = uri.toString() }

    // 按屏高比例限高：固定 720dp 在小屏上超过整屏 → 抽屉顶到状态栏。
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgSecondary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (initial == null) "添加容器镜像" else "编辑镜像",
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = TextSecondary)
                }
            }

            Text("名称", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("自定义容器", color = TextSecondary.copy(alpha = 0.4f)) },
                colors = tfColors(), shape = RoundedCornerShape(12.dp)
            )

            Text("Shell 路径（留空自动探测 bash/sh）", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = shellPath, onValueChange = { shellPath = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("/bin/bash", color = TextSecondary.copy(alpha = 0.4f)) },
                colors = tfColors(), shape = RoundedCornerShape(12.dp)
            )

            Text("额外挂载（本机路径:容器路径）", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            if (bindingsList.isEmpty()) {
                Text("无挂载", fontSize = 12.sp, color = TextSecondary.copy(alpha = 0.6f))
            }
            bindingsList.forEachIndexed { index, (local, container) ->
                Column(modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
                    OutlinedTextField(
                        value = local,
                        onValueChange = { bindingsList[index] = it to container },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("/sdcard", color = TextSecondary.copy(alpha = 0.4f)) },
                        colors = tfColors(), shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = container,
                        onValueChange = { bindingsList[index] = local to it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("/mnt/sdcard", color = TextSecondary.copy(alpha = 0.4f)) },
                        colors = tfColors(), shape = RoundedCornerShape(10.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { bindingsList.removeAt(index) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Surface(
                onClick = { bindingsList.add("" to "") },
                shape = RoundedCornerShape(10.dp), color = PanelAlt
            ) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    Text("添加挂载", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Text("额外 proot 参数", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            if (argsList.isEmpty()) {
                Text("无参数", fontSize = 12.sp, color = TextSecondary.copy(alpha = 0.6f))
            }
            argsList.forEachIndexed { index, value ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { argsList[index] = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("-k", color = TextSecondary.copy(alpha = 0.4f)) },
                        colors = tfColors(), shape = RoundedCornerShape(10.dp)
                    )
                    IconButton(onClick = { argsList.removeAt(index) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                    }
                }
            }
            Surface(
                onClick = { argsList.add("") },
                shape = RoundedCornerShape(10.dp), color = PanelAlt
            ) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    Text("添加参数", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Text("环境变量", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
            if (envList.isEmpty()) {
                Text("无环境变量", fontSize = 12.sp, color = TextSecondary.copy(alpha = 0.6f))
            }
            envList.forEachIndexed { index, (k, v) ->
                Column(modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
                    OutlinedTextField(
                        value = k,
                        onValueChange = { envList[index] = it to v },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("MY_VAR", color = TextSecondary.copy(alpha = 0.4f)) },
                        colors = tfColors(), shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = v,
                        onValueChange = { envList[index] = k to it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("value", color = TextSecondary.copy(alpha = 0.4f)) },
                        colors = tfColors(), shape = RoundedCornerShape(10.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { envList.removeAt(index) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Surface(
                onClick = { envList.add("" to "") },
                shape = RoundedCornerShape(10.dp), color = PanelAlt
            ) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    Text("添加环境变量", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            // 内置镜像固定为内置来源，不支持导入覆盖，隐藏文件选择
            if (initial?.rootfsSource !is RootfsSource.Asset) {
                Surface(
                    onClick = { pickLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(10.dp),
                    color = PanelAlt,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.SdStorage, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextSecondary)
                        Text(
                            text = pickedName ?: if (pickedUri != null) "已选择镜像文件" else "选择镜像文件（.tar.gz / .tar.xz）",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            val canToggleActive = com.wy.openx.container.ContainerActiveSwitch.canToggle(isCurrentlyActive)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelAlt, RoundedCornerShape(12.dp))
                    .clickable(enabled = canToggleActive) { setAsActive = !setAsActive }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("设为当前容器", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (isCurrentlyActive) "当前正在使用这个容器（需先在列表里切到别的才能改）"
                        else "下次打开终端页将用此容器",
                        fontSize = 11.sp, color = TextSecondary
                    )
                }
                // 整行才是点击热区（开关本身只有 52x32dp，在弹窗底部很容易点空）；
                // 开关只作状态展示，不自己处理点击，避免手势落到它上面却被吃掉。
                Switch(
                    checked = isCurrentlyActive || setAsActive, onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = Accent, uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Border)
                )
            }

            Button(
                onClick = {
                    importing = true
                    scope.launch {
                        val finalUri = pickedUri?.let { uri ->
                            withContext(Dispatchers.IO) { copyImageToPrivate(context, uri, initial?.id) } ?: pickedUri
                        } ?: pickedUri
                        importing = false
                        val profile = buildContainerProfile(
                            initial = initial,
                            name = name,
                            shellPath = shellPath,
                            bindings = bindingsList.filter { it.first.isNotBlank() && it.second.isNotBlank() }
                                .map { (l, c) -> "${l.trim()}:${c.trim()}" },
                            args = argsList.map { it.trim() }.filter { it.isNotEmpty() },
                            env = envList.map { it.first.trim() to it.second }.filter { it.first.isNotEmpty() }.toMap(),
                            pickedUri = finalUri
                        ) ?: return@launch
                        onConfirm(profile, com.wy.openx.container.ContainerActiveSwitch.toPersist(isCurrentlyActive, setAsActive))
                    }
                },
                enabled = !importing && (pickedUri != null || initialAsset != null),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonBg, contentColor = TextPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TextPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    when {
                        importing -> "正在复制镜像…"
                        initial == null -> "添加"
                        else -> "保存"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun buildContainerProfile(
    initial: ContainerProfile?,
    name: String,
    shellPath: String,
    bindings: List<String>,
    args: List<String>,
    env: Map<String, String>,
    pickedUri: String?
): ContainerProfile? {
    val assetSource = initial?.rootfsSource as? RootfsSource.Asset
    val rootfsSource = if (pickedUri != null) RootfsSource.LocalFile(pickedUri) else assetSource ?: return null
    return ContainerProfile(
        id = "", // 由调用方覆写
        name = name,
        rootfsSource = rootfsSource,
        shellPath = shellPath.ifBlank { null },
        extraBindings = bindings,
        // 本地导入默认带 --link2symlink（Android 宿主不支持硬链接时 PRoot 用符号链接模拟，
        // Debian/Ubuntu 系镜像的 apt/dpkg 依赖它，否则安装即失败）
        extraArgs = if (args.isEmpty() && rootfsSource is RootfsSource.LocalFile) listOf("--link2symlink") else args,
        env = env,
        isBuiltin = rootfsSource is RootfsSource.Asset
    )
}

/**
 * 把所选镜像文件复制一份到 App 私有目录（rootfs_images/）保存副本，返回副本 file uri；
 * 已是 file uri（私有副本或历史数据）时不复制直接返回原值；复制失败返回 null（调用方回退原 uri）。
 */
internal fun copyImageToPrivate(context: Context, uriString: String, profileId: String?): String? {
    if (uriString.startsWith("file://")) return uriString
    val uri = Uri.parse(uriString)
    return runCatching {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.let { ".$it" } ?: ""
        val destDir = File(context.filesDir, "rootfs_images").apply { mkdirs() }
        val dest = File(destDir, "import_${profileId ?: System.currentTimeMillis()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (!dest.isFile) return@runCatching null
        Uri.fromFile(dest).toString()
    }.getOrNull()
}

internal fun queryImageDisplayName(context: Context, uriString: String): String? {
    return runCatching {
        val uri = Uri.parse(uriString)
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else uri.lastPathSegment
        }
    }.getOrNull()
}